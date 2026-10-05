@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.positioning.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsSdk
import jp.metamaps.MetamapsUserAction
import jp.metamaps.positioning.ManifestChecksum
import jp.metamaps.positioning.PositioningManifest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class LoadedManifest(
    val manifest: PositioningManifest,
    val json: String,
    val source: Source,
) {
    enum class Source {
        NETWORK,
        NOT_MODIFIED_CACHE,
        OFFLINE_CACHE,
    }
}

internal data class ManifestNotFoundClassification(
    val code: MetamapsError.Code,
    val debugDetail: String,
)

// org.json is an android.jar stub in local unit tests, so extraction (runtime only) and classification (pure
// function, tested) are kept separate.
internal fun extractManifestNotFoundCode(bodyData: ByteArray?): String? = runCatching {
    val data = bodyData ?: return@runCatching null
    JSONObject(String(data, Charsets.UTF_8)).opt("code") as? String
}.getOrNull()?.takeIf { it.isNotEmpty() }

internal fun classifyManifestNotFound(bodyCode: String?): ManifestNotFoundClassification = when {
    bodyCode == null -> ManifestNotFoundClassification(
        MetamapsError.Code.MANIFEST_UNAVAILABLE,
        "HTTP 404 unparsable body",
    )
    bodyCode == "map_not_found" -> ManifestNotFoundClassification(
        MetamapsError.Code.MAP_NOT_FOUND,
        "HTTP 404 body code=map_not_found",
    )
    else -> ManifestNotFoundClassification(
        MetamapsError.Code.MANIFEST_UNAVAILABLE,
        "HTTP 404 body code=$bodyCode",
    )
}

internal open class ManifestRepository(
    private val cacheDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val connectionFactory: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    },
) {
    suspend fun load(configuration: PositioningConfiguration): LoadedManifest {
        try {
            configuration.validate()
        } catch (error: IllegalArgumentException) {
            throw MetamapsError.invalidConfiguration(error.message ?: "Invalid configuration")
        }
        val paths = cachePaths(configuration)
        val cached = readCache(paths)
        val connection = connectionFactory(manifestUri(configuration))
        connection.requestMethod = "GET"
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "MetamapsPositioning/${MetamapsSdk.VERSION}")
        cached?.metadata?.etag?.let { connection.setRequestProperty("If-None-Match", it) }

        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val manifest = validate(json, configuration)
                    writeCache(
                        paths,
                        json,
                        CacheMetadata(connection.getHeaderField("ETag"), nowMillis()),
                    )
                    LoadedManifest(manifest, json, LoadedManifest.Source.NETWORK)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    val value = cached ?: throw unavailable("Server returned 304 without a verified cache")
                    val loaded = loadedCache(value, configuration, LoadedManifest.Source.NOT_MODIFIED_CACHE)
                    writeCache(
                        paths,
                        value.json,
                        CacheMetadata(connection.getHeaderField("ETag") ?: value.metadata.etag, nowMillis()),
                    )
                    loaded
                }
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw MetamapsError(
                        MetamapsError.Code.MAP_ACCESS_DENIED,
                        "The positioning manifest is not accessible.",
                        false,
                        MetamapsUserAction.CHECK_CONFIGURATION,
                    )
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    val classification = classifyManifestNotFound(extractManifestNotFoundCode(readErrorBody(connection)))
                    if (classification.code == MetamapsError.Code.MAP_NOT_FOUND) {
                        throw MetamapsError(
                            MetamapsError.Code.MAP_NOT_FOUND,
                            "The map was not found.",
                            false,
                            MetamapsUserAction.CHECK_CONFIGURATION,
                            classification.debugDetail,
                        )
                    }
                    throw unavailable(classification.debugDetail)
                }
                else -> throw unavailable("Manifest request failed with HTTP $code")
            }
        } catch (error: MetamapsError) {
            if (!error.recoverable) throw error
            if (cached != null && nowMillis() - cached.metadata.verifiedAtMillis <= configuration.maxOfflineAgeMs) {
                return loadedCache(cached, configuration, LoadedManifest.Source.OFFLINE_CACHE)
            }
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (cached != null && nowMillis() - cached.metadata.verifiedAtMillis <= configuration.maxOfflineAgeMs) {
                return loadedCache(cached, configuration, LoadedManifest.Source.OFFLINE_CACHE)
            }
            throw unavailable(error.toString())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Validates a manifest. A cached manifest may be past `expiresAt`: it stays usable offline until
     * `maxOfflineAgeMs` after its last verification, which the caller checks.
     */
    internal fun validate(
        json: String,
        configuration: PositioningConfiguration,
        rejectExpired: Boolean = true,
    ): PositioningManifest {
        val manifest = try {
            PositioningManifest.parse(json)
        } catch (error: Throwable) {
            throw MetamapsError(
                MetamapsError.Code.MANIFEST_UNSUPPORTED,
                "The positioning manifest cannot be decoded.",
                false,
                MetamapsUserAction.RETRY,
                error.toString(),
            )
        }
        val checksum = try {
            ManifestChecksum.compute(json)
        } catch (error: Throwable) {
            throw MetamapsError(
                MetamapsError.Code.MANIFEST_UNSUPPORTED,
                "The positioning manifest checksum could not be computed.",
                false,
                MetamapsUserAction.RETRY,
                error.toString(),
            )
        }
        if (manifest.schema != "metamap.positioning-manifest.v1" || manifest.checksum != checksum) {
            throw MetamapsError(
                MetamapsError.Code.MANIFEST_UNSUPPORTED,
                "The positioning manifest failed schema or checksum validation.",
                false,
                MetamapsUserAction.RETRY,
            )
        }
        val generated = parseUtcInstant(manifest.generatedAt)
        val expires = parseUtcInstant(manifest.expiresAt)
        if (generated >= expires) {
            throw MetamapsError(
                MetamapsError.Code.MANIFEST_UNSUPPORTED,
                "The positioning manifest has an invalid validity window.",
                false,
                MetamapsUserAction.RETRY,
            )
        }
        if (rejectExpired && nowMillis() >= expires) {
            throw MetamapsError(
                MetamapsError.Code.MANIFEST_EXPIRED,
                "The positioning manifest expired.",
                true,
                MetamapsUserAction.RETRY,
                manifest.expiresAt,
            )
        }
        configuration.groupId?.let {
            if (!manifest.groupId.equals(it.toString(), ignoreCase = true)) {
                throw MetamapsError(
                    MetamapsError.Code.MANIFEST_UNSUPPORTED,
                    "The positioning manifest belongs to another map group.",
                    false,
                    MetamapsUserAction.CHECK_CONFIGURATION,
                )
            }
        }
        runCatching { UUID.fromString(manifest.mapId) }.getOrElse {
            throw unsupportedIdentity("mapId", manifest.mapId)
        }
        runCatching { UUID.fromString(manifest.groupId) }.getOrElse {
            throw unsupportedIdentity("groupId", manifest.groupId)
        }
        if (manifest.beacons.none { it.isEnabled }) {
            throw MetamapsError(
                MetamapsError.Code.NO_REGISTERED_BEACONS,
                "The manifest has no enabled beacons.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
            )
        }
        return manifest
    }

    private fun manifestUri(configuration: PositioningConfiguration): URI {
        val root = configuration.baseUrl.toString().trimEnd('/')
        val query = buildList {
            configuration.groupId?.let { add("group=${it.toString().lowercase()}") }
            if (configuration.internalOptions.bleTest) add("bletest=1")
        }.joinToString("&")
        return URI.create(
            "$root/api/public/maps/${configuration.mapSlug}/positioning-manifest" +
                if (query.isEmpty()) "" else "?$query",
        )
    }

    private fun readErrorBody(connection: HttpURLConnection): ByteArray? = runCatching {
        val stream = connection.errorStream ?: return@runCatching null
        stream.use {
            val output = ByteArrayOutputStream(MAX_ERROR_BODY_BYTES)
            val buffer = ByteArray(1_024)
            while (output.size() < MAX_ERROR_BODY_BYTES) {
                val count = it.read(buffer, 0, minOf(buffer.size, MAX_ERROR_BODY_BYTES - output.size()))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun cachePaths(configuration: PositioningConfiguration): CachePaths {
        val group = configuration.groupId?.toString()?.lowercase() ?: "active"
        val mode = if (configuration.internalOptions.bleTest) "bletest-draft" else "active"
        val stem = "${configuration.mapSlug}-$group-$mode"
        return CachePaths(
            File(cacheDirectory, "$stem.json"),
            File(cacheDirectory, "$stem.properties"),
        )
    }

    private fun readCache(paths: CachePaths): CachedManifest? = runCatching {
        if (!paths.json.isFile || !paths.metadata.isFile) return null
        val properties = Properties().apply {
            FileInputStream(paths.metadata).use(::load)
        }
        val verifiedAt = properties.getProperty("verifiedAtMillis")?.toLongOrNull() ?: return null
        CachedManifest(
            paths.json.readText(Charsets.UTF_8),
            CacheMetadata(properties.getProperty("etag")?.ifEmpty { null }, verifiedAt),
        )
    }.getOrNull()

    private fun writeCache(paths: CachePaths, json: String, metadata: CacheMetadata) {
        cacheDirectory.mkdirs()
        val jsonTemporary = File(paths.json.parentFile, paths.json.name + ".tmp")
        val metadataTemporary = File(paths.metadata.parentFile, paths.metadata.name + ".tmp")
        FileOutputStream(jsonTemporary).use {
            it.write(json.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        FileOutputStream(metadataTemporary).use { stream ->
            Properties().apply {
                setProperty("etag", metadata.etag.orEmpty())
                setProperty("verifiedAtMillis", metadata.verifiedAtMillis.toString())
            }.store(stream, null)
            stream.fd.sync()
        }
        if (!jsonTemporary.renameTo(paths.json)) {
            jsonTemporary.delete()
            metadataTemporary.delete()
            throw unavailable("Unable to atomically replace the manifest cache")
        }
        if (!metadataTemporary.renameTo(paths.metadata)) {
            metadataTemporary.delete()
            throw unavailable("Unable to atomically replace the manifest metadata")
        }
    }

    private fun loadedCache(
        cached: CachedManifest,
        configuration: PositioningConfiguration,
        source: LoadedManifest.Source,
    ) = LoadedManifest(validate(cached.json, configuration, rejectExpired = false), cached.json, source)

    private fun parseUtcInstant(value: String): Long {
        val match = ISO_UTC.matchEntire(value) ?: throw invalidWindow(value)
        val fields = match.groupValues
        return try {
            val localTime = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(Calendar.YEAR, fields[1].toInt())
                set(Calendar.MONTH, fields[2].toInt() - 1)
                set(Calendar.DAY_OF_MONTH, fields[3].toInt())
                set(Calendar.HOUR_OF_DAY, fields[4].toInt())
                set(Calendar.MINUTE, fields[5].toInt())
                set(Calendar.SECOND, fields[6].toInt())
                val fraction = fields[7].padEnd(3, '0').take(3)
                set(Calendar.MILLISECOND, fraction.ifEmpty { "0" }.toInt())
            }.timeInMillis
            val offsetHours = fields[10].ifEmpty { "0" }.toInt()
            val offsetMinutes = fields[11].ifEmpty { "0" }.toInt()
            require(offsetHours in 0..23 && offsetMinutes in 0..59)
            val offsetSign = when (fields[9]) {
                "+" -> 1
                "-" -> -1
                else -> 0
            }
            localTime - offsetSign * (offsetHours * 60L + offsetMinutes) * 60_000L
        } catch (_: IllegalArgumentException) {
            throw invalidWindow(value)
        }
    }

    private fun invalidWindow(value: String) = MetamapsError(
        MetamapsError.Code.MANIFEST_UNSUPPORTED,
        "The positioning manifest has an invalid validity window.",
        false,
        MetamapsUserAction.RETRY,
        value,
    )

    private fun unsupportedIdentity(field: String, value: String) = MetamapsError(
        MetamapsError.Code.MANIFEST_UNSUPPORTED,
        "The positioning manifest contains an invalid identity.",
        false,
        MetamapsUserAction.RETRY,
        "$field=$value",
    )

    private fun unavailable(detail: String) = MetamapsError(
        MetamapsError.Code.MANIFEST_UNAVAILABLE,
        "A verified positioning manifest is unavailable.",
        true,
        MetamapsUserAction.RETRY,
        detail,
    )

    private data class CachePaths(val json: File, val metadata: File)
    private data class CacheMetadata(val etag: String?, val verifiedAtMillis: Long)
    private data class CachedManifest(val json: String, val metadata: CacheMetadata)

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 4 * 1_024
        val ISO_UTC = Regex(
            """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))""",
        )
    }
}
