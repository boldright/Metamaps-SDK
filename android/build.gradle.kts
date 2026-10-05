import groovy.json.JsonOutput
import java.security.MessageDigest
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    // Pins the Kotlin Gradle plugin used by the Android Gradle plugin's built-in Kotlin support.
    kotlin("jvm") version "2.4.20" apply false
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

group = "jp.metamaps.positioning"
version = "0.5.0"


// Release report tasks read the projects only while they are configured. Their actions use the captured values, so
// they do not touch a Project at execution time (Gradle 10 and the configuration cache reject that).
val releaseModules = listOf("metamap-positioning", "metamap-mapview").map { project(":$it") }

tasks.register("writeReleaseSbom") {
    group = "distribution"
    description = "Writes CycloneDX JSON SBOMs for the two Android release AARs."
    dependsOn(":metamap-positioning:assembleRelease", ":metamap-mapview:assembleRelease")

    val sdkGroup = project.group.toString()
    val sdkVersion = project.version.toString()
    val dependencyGraphs = releaseModules.associate { module ->
        module.name to module.configurations.named("releaseRuntimeClasspath")
            .flatMap { it.incoming.resolutionResult.rootComponent }
    }
    val outputDirectory = layout.buildDirectory.dir("reports/sbom")

    doLast {
        // The module itself and every component it resolves to, the same set as ResolutionResult.allComponents.
        fun ResolvedComponentResult.withAllDependencies(): Collection<ResolvedComponentResult> {
            val seen = linkedMapOf(id to this)
            val queue = ArrayDeque(listOf(this))
            while (queue.isNotEmpty()) {
                queue.removeFirst().dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .map { it.selected }
                    .forEach { if (seen.putIfAbsent(it.id, it) == null) queue.addLast(it) }
            }
            return seen.values
        }

        dependencyGraphs.forEach { (moduleName, root) ->
            val components = root.get()
                .withAllDependencies()
                .mapNotNull { component ->
                    component.moduleVersion?.let { id ->
                    mapOf(
                        "type" to "library",
                        "group" to id.group,
                        "name" to id.name,
                        "version" to id.version,
                        "purl" to "pkg:maven/${id.group}/${id.name}@${id.version}",
                    )
                    }
                }
                .distinctBy { "${it["group"]}:${it["name"]}:${it["version"]}" }
                .sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}" }
            val bom = linkedMapOf(
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.6",
                "version" to 1,
                "metadata" to mapOf(
                    "component" to mapOf(
                        "type" to "library",
                        "group" to sdkGroup,
                        "name" to moduleName,
                        "version" to sdkVersion,
                    ),
                ),
                "components" to components,
            )
            val output = outputDirectory.get().file("$moduleName.cdx.json").asFile
            output.parentFile.mkdirs()
            output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n")
        }
    }
}

tasks.register("writeReleaseChecksums") {
    group = "distribution"
    description = "Writes SHA-256 checksums for the Android release AARs."
    dependsOn(":metamap-positioning:assembleRelease", ":metamap-mapview:assembleRelease")

    val artifacts = releaseModules.map { module ->
        module.layout.buildDirectory.file("outputs/aar/${module.name}-release.aar")
    }
    val output = layout.buildDirectory.file("reports/checksums/SHA256SUMS")

    doLast {
        fun File.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            artifacts.map { it.get().asFile }.joinToString(separator = "\n", postfix = "\n") {
                "${it.sha256()}  ${it.name}"
            },
        )
    }
}

// Copies the positioning engine next to the SDK AARs so that build/maven-repository resolves on its own.
val copyEngineToBuildRepository = tasks.register<Copy>("copyEngineToBuildRepository") {
    var engineRepository = providers.gradleProperty("metamap.engineRepository")
        .map { file(it) }
        .getOrElse(file("maven-repository"))
    from(engineRepository)
    include("jp/metamaps/positioning/metamap-positioning-core/**")
    into(layout.buildDirectory.dir("maven-repository"))
}

tasks.register("publishSdkToBuildRepository") {
    group = "distribution"
    description = "Publishes both Android AARs and the positioning engine to build/maven-repository."
    dependsOn(
        ":metamap-positioning:publishReleasePublicationToBuildRepository",
        ":metamap-mapview:publishReleasePublicationToBuildRepository",
        "writeReleaseSbom",
        "writeReleaseChecksums",
    )
    dependsOn(copyEngineToBuildRepository)
}
