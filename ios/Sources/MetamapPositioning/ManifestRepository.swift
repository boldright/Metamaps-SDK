import Foundation
import MetamapPositioningCore

struct LoadedManifest: Sendable {
    let manifest: PositioningManifest
    let data: Data
    let source: Source

    enum Source: Equatable, Sendable { case network, notModifiedCache, offlineCache }
}

enum ManifestNotFoundClassification: Equatable {
    case mapNotFound(debugDetail: String)
    case manifestUnavailable(debugDetail: String)
}

/// Parses the manifest's `generatedAt` and `expiresAt`.
///
/// The canonical form is second-precision ISO 8601 UTC, but revisions stored with fractional seconds must also
/// parse, or activated settings become unusable. The default `ISO8601DateFormatter` rejects fractional seconds,
/// and `withFractionalSeconds` depends on the number of digits, so the fraction is dropped before parsing.
func parseManifestInstant(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    if let date = formatter.date(from: value) { return date }
    guard let separator = value.firstIndex(of: ".") else { return nil }
    let fraction = value[value.index(after: separator)...]
    guard let suffixStart = fraction.firstIndex(where: { !($0.isASCII && $0.isNumber) }) else { return nil }
    return formatter.date(from: String(value[value.startIndex..<separator]) + String(fraction[suffixStart...]))
}

func classifyManifestNotFound(_ bodyData: Data) -> ManifestNotFoundClassification {
    guard let object = try? JSONSerialization.jsonObject(with: bodyData),
          let body = object as? [String: Any],
          let code = body["code"] as? String,
          !code.isEmpty else {
        return .manifestUnavailable(debugDetail: "HTTP 404 unparsable body")
    }
    if code == "map_not_found" {
        return .mapNotFound(debugDetail: "HTTP 404 body code=map_not_found")
    }
    return .manifestUnavailable(debugDetail: "HTTP 404 body code=\(code)")
}

actor ManifestRepository {
    private struct Metadata: Codable {
        let etag: String?
        let verifiedAt: Date
    }

    private let session: URLSession
    private let directory: URL
    private let now: @Sendable () -> Date
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared, directory: URL? = nil, now: @escaping @Sendable () -> Date = Date.init) {
        self.session = session
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("jp.metamaps.MetamapPositioning", isDirectory: true)
        self.now = now
    }

    func load(configuration: PositioningConfiguration) async throws -> LoadedManifest {
        try configuration.validate()
        let paths = cachePaths(configuration)
        let cached = readCache(paths: paths)
        var request = URLRequest(url: try manifestURL(configuration))
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("MetamapPositioning/\(MetamapPositioningSDK.version)", forHTTPHeaderField: "User-Agent")
        if let etag = cached?.metadata.etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw MetamapPositioningError(code: .manifestUnavailable, message: "The manifest response was not HTTP.",
                                              recoverable: true, userAction: .retry)
            }
            switch http.statusCode {
            case 200:
                let manifest = try validate(data: data, configuration: configuration)
                try writeCache(data: data, metadata: .init(etag: http.value(forHTTPHeaderField: "ETag"), verifiedAt: now()), paths: paths)
                return .init(manifest: manifest, data: data, source: .network)
            case 304:
                guard let cached else { throw unavailable("Server returned 304 without a verified cache.") }
                let loaded = try loadedCache(cached, configuration: configuration, source: .notModifiedCache)
                try writeCache(
                    data: cached.data,
                    metadata: .init(
                        etag: http.value(forHTTPHeaderField: "ETag") ?? cached.metadata.etag,
                        verifiedAt: now()),
                    paths: paths)
                return loaded
            case 401, 403:
                throw MetamapPositioningError(code: .mapAccessDenied, message: "The positioning manifest is not accessible.",
                                              recoverable: false, userAction: .checkConfiguration)
            case 404:
                switch classifyManifestNotFound(data) {
                case .mapNotFound(let debugDetail):
                    throw MetamapPositioningError(code: .mapNotFound, message: "The map was not found.",
                                                  recoverable: false, userAction: .checkConfiguration,
                                                  debugDetail: debugDetail)
                case .manifestUnavailable(let debugDetail):
                    throw unavailable(debugDetail)
                }
            default:
                throw unavailable("Manifest request failed with HTTP \(http.statusCode).")
            }
        } catch let error as MetamapPositioningError where !error.recoverable {
            throw error
        } catch {
            if let cached, now().timeIntervalSince(cached.metadata.verifiedAt) <= configuration.maxOfflineAge {
                return try loadedCache(cached, configuration: configuration, source: .offlineCache)
            }
            if let typed = error as? MetamapPositioningError { throw typed }
            throw unavailable(String(describing: error))
        }
    }

    private func validate(data: Data, configuration: PositioningConfiguration) throws -> PositioningManifest {
        let computed = try ManifestChecksum.compute(data)
        let manifest: PositioningManifest
        do { manifest = try decoder.decode(PositioningManifest.self, from: data) }
        catch {
            throw MetamapPositioningError(code: .manifestUnsupported, message: "The positioning manifest cannot be decoded.",
                                          recoverable: false, userAction: .retry, debugDetail: String(describing: error))
        }
        guard manifest.schema == "metamap.positioning-manifest.v1", manifest.checksum == computed else {
            throw MetamapPositioningError(code: .manifestUnsupported, message: "The positioning manifest failed schema or checksum validation.",
                                          recoverable: false, userAction: .retry)
        }
        guard let generatedAt = parseManifestInstant(manifest.generatedAt),
              let expiresAt = parseManifestInstant(manifest.expiresAt),
              generatedAt < expiresAt else {
            throw MetamapPositioningError(code: .manifestUnsupported, message: "The positioning manifest has an invalid validity window.",
                                          recoverable: false, userAction: .retry)
        }
        if let requested = configuration.groupId, manifest.groupId != requested {
            throw MetamapPositioningError(code: .manifestUnsupported, message: "The positioning manifest belongs to another map group.",
                                          recoverable: false, userAction: .checkConfiguration)
        }
        guard !manifest.beacons.filter(\.isEnabled).isEmpty else {
            throw MetamapPositioningError(code: .noRegisteredBeacons, message: "The manifest has no enabled beacons.",
                                          recoverable: false, userAction: .selectLocationManually)
        }
        return manifest
    }

    private func manifestURL(_ configuration: PositioningConfiguration) throws -> URL {
        var url = configuration.baseURL
        url.append(path: "api/public/maps")
        url.append(path: configuration.mapSlug)
        url.append(path: "positioning-manifest")
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw MetamapPositioningError.configurationInvalid("Unable to build manifest URL.")
        }
        var queryItems: [URLQueryItem] = []
        if let groupId = configuration.groupId {
            queryItems.append(URLQueryItem(name: "group", value: groupId.uuidString.lowercased()))
        }
        if configuration.bleTest {
            queryItems.append(URLQueryItem(name: "bletest", value: "1"))
        }
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let built = components.url else {
            throw MetamapPositioningError.configurationInvalid("Unable to build manifest URL.")
        }
        return built
    }

    private func cachePaths(_ configuration: PositioningConfiguration) -> (data: URL, metadata: URL) {
        let group = configuration.groupId?.uuidString.lowercased() ?? "active"
        let mode = configuration.bleTest ? "bletest-draft" : "active"
        let stem = "\(configuration.mapSlug)-\(group)-\(mode)"
        return (directory.appendingPathComponent(stem + ".json"), directory.appendingPathComponent(stem + ".metadata.json"))
    }

    private func readCache(paths: (data: URL, metadata: URL)) -> (data: Data, metadata: Metadata)? {
        guard let data = try? Data(contentsOf: paths.data),
              let metadataData = try? Data(contentsOf: paths.metadata),
              let metadata = try? decoder.decode(Metadata.self, from: metadataData) else { return nil }
        return (data, metadata)
    }

    private func writeCache(data: Data, metadata: Metadata, paths: (data: URL, metadata: URL)) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: paths.data, options: Self.cacheWriteOptions)
        try JSONEncoder().encode(metadata).write(to: paths.metadata, options: Self.cacheWriteOptions)
    }

    private func loadedCache(
        _ cached: (data: Data, metadata: Metadata),
        configuration: PositioningConfiguration,
        source: LoadedManifest.Source
    ) throws -> LoadedManifest {
        .init(manifest: try validate(data: cached.data, configuration: configuration), data: cached.data, source: source)
    }

    private func unavailable(_ detail: String) -> MetamapPositioningError {
        .init(code: .manifestUnavailable, message: "A verified positioning manifest is unavailable.",
              recoverable: true, userAction: .retry, debugDetail: detail)
    }

    private static var cacheWriteOptions: Data.WritingOptions {
        #if os(iOS)
        [.atomic, .completeFileProtectionUnlessOpen]
        #else
        [.atomic]
        #endif
    }
}
