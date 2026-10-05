import Foundation
import MetamapsPositioningCore
import Testing
@_spi(MetamapsInternal) @testable import MetamapsPositioning
#if os(iOS)
import CoreLocation
import CoreMotion
#endif

@Suite(.serialized)
struct ManifestAndClientTests {
    @Test func compassHeadingUsesXEastZSouthFrame() {
        #expect(HeadingTransform.compassToLocal(0) == 180)
        #expect(HeadingTransform.compassToLocal(90) == 90)
        #expect(HeadingTransform.compassToLocal(180) == 0)
        #expect(HeadingTransform.compassToLocal(270) == 270)
    }

    /// Guards against a regression where step authorization (Motion & Fitness) also cost the attitude subscription.
    @Test func motionSubscriptionsKeepAttitudeWithoutStepAuthorization() {
        let denied = decideMotionSubscriptions(motionSnapshot(authorization: .denied))
        #expect(denied.subscribeToAttitude)
        #expect(!denied.subscribeToStep)
        #expect(!denied.subscribeToActivity)

        let noStepSensor = decideMotionSubscriptions(
            motionSnapshot(authorization: .granted, stepAvailable: false))
        #expect(noStepSensor.subscribeToAttitude)
        #expect(!noStepSensor.subscribeToStep)
        #expect(noStepSensor.subscribeToActivity)

        let noActivitySensor = decideMotionSubscriptions(motionSnapshot(activityAvailable: false))
        #expect(!noActivitySensor.subscribeToActivity)
        #expect(noActivitySensor.subscribeToStep)

        let noAttitude = decideMotionSubscriptions(motionSnapshot(attitudeAvailable: false))
        #expect(!noAttitude.subscribeToAttitude)

        let nothing = decideMotionSubscriptions(motionSnapshot(
            stepAvailable: false, activityAvailable: false, attitudeAvailable: false,
            magnetometerAvailable: false))
        #expect(!nothing.hasSubscriptions)
    }

    /// Guards against a regression where the start decision makes step authorization a precondition for heading again.
    @Test func motionSubscriptionsKeepAttitudeWhenStepIsUnusable() {
        let denied = motionSubscriptions(
            motionPolicy: .preferred,
            snapshot: motionSnapshot(authorization: .notDetermined, stepAvailable: false),
            motionUsageDescriptionPresent: false)
        #expect(denied.hasSubscriptions)
        #expect(denied.subscribeToAttitude)
        #expect(!denied.subscribeToStep)
        #expect(!denied.subscribeToActivity)

        #expect(!motionSubscriptions(
            motionPolicy: .preferred,
            snapshot: motionSnapshot(
                stepAvailable: false, activityAvailable: false, attitudeAvailable: false,
                magnetometerAvailable: false),
            motionUsageDescriptionPresent: true).hasSubscriptions)

        #expect(!motionSubscriptions(
            motionPolicy: .disabled,
            snapshot: motionSnapshot(),
            motionUsageDescriptionPresent: true).hasSubscriptions)

        // Without `NSMotionUsageDescription`, neither steps nor activity are subscribed. When the adapter decided this
        // itself, this case was missed and CMPedometer could start without the usage description.
        let noUsageDescription = motionSubscriptions(
            motionPolicy: .preferred,
            snapshot: motionSnapshot(),
            motionUsageDescriptionPresent: false)
        #expect(!noUsageDescription.subscribeToStep)
        #expect(!noUsageDescription.subscribeToActivity)
        #expect(noUsageDescription.subscribeToAttitude)
        let usable = motionSubscriptions(
            motionPolicy: .preferred,
            snapshot: motionSnapshot(),
            motionUsageDescriptionPresent: true)
        #expect(usable.subscribeToStep)
        #expect(usable.subscribeToActivity)
    }

    /// Multiple Core Motion flags or low confidence must not be turned into a definite stationary diagnosis.
    @Test func activityClassificationMapsToSpecValues() {
        #expect(ActivityClassification.label(
            flags: activityFlags(stationary: true), confidence: "high") == "stationary")
        #expect(ActivityClassification.label(
            flags: activityFlags(stationary: true), confidence: "low") == "unknown")
        #expect(ActivityClassification.label(
            flags: activityFlags(walking: true), confidence: "low") == "walking")
        #expect(ActivityClassification.label(
            flags: activityFlags(running: true), confidence: "medium") == "running")
        // Core Motion flags are not exclusive. On conflict, only the raw flags go to diagnostics.
        #expect(ActivityClassification.label(
            flags: activityFlags(stationary: true, walking: true), confidence: "high") == "unknown")
        #expect(ActivityClassification.label(
            flags: activityFlags(automotive: true), confidence: "high") == "unknown")
        #expect(ActivityClassification.label(
            flags: activityFlags(), confidence: "high") == "unknown")
    }

    @Test func activityStartDateMapsToCallbackMonotonicAxis() {
        let callbackDate = Date(timeIntervalSince1970: 10_000)
        #expect(ActivityTiming.estimatedStartMonotonicTimestampMs(
            callbackMonotonicTimestampMs: 7_000,
            callbackDate: callbackDate,
            activityStartDate: callbackDate.addingTimeInterval(-2.25)) == 4_750)
        #expect(ActivityTiming.estimatedStartMonotonicTimestampMs(
            callbackMonotonicTimestampMs: 7_000,
            callbackDate: callbackDate,
            activityStartDate: callbackDate.addingTimeInterval(1)) == 7_000)
        #expect(ActivityTiming.estimatedStartMonotonicTimestampMs(
            callbackMonotonicTimestampMs: 500,
            callbackDate: callbackDate,
            activityStartDate: callbackDate.addingTimeInterval(-2)) == 0)
    }

    private func activityFlags(
        stationary: Bool = false,
        walking: Bool = false,
        running: Bool = false,
        automotive: Bool = false,
        cycling: Bool = false,
        unknown: Bool = false
    ) -> ActivityDiagnosticFlags {
        .init(
            stationary: stationary,
            walking: walking,
            running: running,
            automotive: automotive,
            cycling: cycling,
            unknown: unknown
        )
    }

    private func motionSnapshot(
        authorization: MotionAuthorizationState = .granted,
        stepAvailable: Bool = true,
        activityAvailable: Bool = true,
        attitudeAvailable: Bool = true,
        magnetometerAvailable: Bool = true
    ) -> MotionSnapshot {
        .init(
            authorization: authorization,
            stepAvailable: stepAvailable,
            activityAvailable: activityAvailable,
            attitudeAvailable: attitudeAvailable,
            gyroscopeAvailable: true,
            magnetometerAvailable: magnetometerAvailable,
            barometerAvailable: true
        )
    }

    #if os(iOS)
    @Test @MainActor func coreLocationDelegateEntrypointsRemainNonisolated() {
        let adapter = CoreLocationBeaconAdapter()
        let authorizationCallback: (CLLocationManager) -> Void =
            adapter.locationManagerDidChangeAuthorization
        let rangingCallback: (CLLocationManager, [CLBeacon], CLBeaconIdentityConstraint) -> Void =
            adapter.locationManager(_:didRange:satisfying:)
        let failureCallback: (CLLocationManager, Error) -> Void =
            adapter.locationManager(_:didFailWithError:)

        _ = (authorizationCallback, rangingCallback, failureCallback)
    }

    @Test func coreLocationServiceAvailabilityDoesNotRequireSynchronousGlobalQuery() {
        #expect(coreLocationServicesAreUsable(for: .notDetermined))
        #expect(coreLocationServicesAreUsable(for: .authorizedWhenInUse))
        #expect(coreLocationServicesAreUsable(for: .authorizedAlways))
        #expect(!coreLocationServicesAreUsable(for: .restricted))
        #expect(!coreLocationServicesAreUsable(for: .denied))
    }

    @Test @MainActor func coreMotionCallbacksAcceptBackgroundQueueEntry() async {
        let adapter = CoreMotionObservationAdapter()
        let callbacks = CoreMotionCallbackBox(
            deviceMotion: CoreMotionCallbackBridge.deviceMotion(
                onEvent: { _ in },
                onError: { _ in }
            ),
            pedometer: CoreMotionCallbackBridge.pedometer(
                owner: adapter,
                generation: 0,
                onEvent: { _ in },
                onError: { _ in }
            )
        )

        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                callbacks.deviceMotion(nil, nil)
                callbacks.pedometer(nil, nil)
                continuation.resume()
            }
        }
        await Task.yield()
    }
    #endif

    @Test func localOriginReturnsManifestWgs84Origin() throws {
        let fixture = try fixtureManifest()
        let position = try PositioningCoordinateTransform.localToWgs84(
            .init(x: 0, y: 3, z: 0), frame: fixture.manifest.coordinateFrame)
        #expect(position.longitude == fixture.manifest.coordinateFrame.origin[0])
        #expect(position.latitude == fixture.manifest.coordinateFrame.origin[1])
        #expect(position.elevationM == 3)
    }

    @Test func coordinateTransformRoundTripsLocalPlacement() throws {
        let fixture = try fixtureManifest()
        let local = LocalPosition(x: 3.25, y: 2.4, z: -7.5)
        let wgs84 = try PositioningCoordinateTransform.localToWgs84(
            local, frame: fixture.manifest.coordinateFrame)
        let roundTrip = try PositioningCoordinateTransform.wgs84ToLocal(
            wgs84, frame: fixture.manifest.coordinateFrame)

        #expect(abs(roundTrip.x - local.x) < 0.000_001)
        #expect(abs(roundTrip.y - local.y) < 0.000_001)
        #expect(abs(roundTrip.z - local.z) < 0.000_001)
    }

    @Test func blePilotReadingUsesManifestCalibrationAndRegisteredPlacement() throws {
        let fixture = try fixtureManifest()
        let beacon = try #require(fixture.manifest.beacons.first)
        var diagnostics = BeaconSignalDiagnostics()
        let readings = try diagnostics.readings(
            observations: [
                .init(
                    beaconKey: beacon.key,
                    uuid: beacon.proximityUuid,
                    major: beacon.major,
                    minor: beacon.minor,
                    rssiDbm: -59,
                    windowStartMonotonicTimestampMs: 1_000,
                    monotonicTimestampMs: 1_000),
            ],
            manifest: fixture.manifest,
            latestUpdate: nil)
        let reading = try #require(readings.first)

        #expect(readings.count == 1)
        #expect(reading.beaconId == beacon.id)
        #expect(abs(reading.radioDistanceM - 1) < 0.000_001)
        #expect(reading.rssiDbm == -59)
        #expect(reading.configuredDistanceM == nil)
        #expect(reading.configuredPosition.y == beacon.position[2])
        #expect(reading.configuredWgs84.longitude == beacon.position[0])
        #expect(reading.configuredWgs84.latitude == beacon.position[1])
    }

    @Test func blePilotRadioDistanceRejectsInvalidCalibration() {
        #expect(BeaconSignalDiagnostics.radioDistanceM(
            rssiDbm: -70,
            calibratedRssiAt1mDbm: -59,
            pathLossExponent: 0) == 100)
    }

    @Test func beaconPickerSnapshotRetainsAdjacentRangingCallbacks() async throws {
        let fixture = try fixtureManifest()
        let beacons = Array(fixture.manifest.beacons.prefix(2))
        #expect(beacons.count == 2)
        let worker = BeaconSignalDiagnosticsWorker()

        func observation(_ beacon: PositioningBeacon, timestamp: Int64) -> BeaconObservation {
            .init(
                beaconKey: beacon.key,
                uuid: beacon.proximityUuid,
                major: beacon.major,
                minor: beacon.minor,
                rssiDbm: timestamp == 1_000 ? -70 : -60,
                windowStartMonotonicTimestampMs: timestamp,
                monotonicTimestampMs: timestamp)
        }

        _ = try await worker.readings(
            observations: [observation(beacons[0], timestamp: 1_000)],
            manifest: fixture.manifest,
            latestUpdate: nil)
        let combined = try await worker.readings(
            observations: [observation(beacons[1], timestamp: 11_000)],
            manifest: fixture.manifest,
            latestUpdate: nil)

        #expect(combined.count == 2)
        #expect(combined.map(\.beaconId) == [beacons[1].id, beacons[0].id])
    }

    @Test func manifestUsesNetworkThen304AndOfflineCache() async throws {
        let fixture = try fixtureManifest()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let session = stubSession()
        let clock = TestClock(Date(timeIntervalSince1970: 0))
        let configuration = PositioningConfiguration(
            baseURL: URL(string: "https://example.test")!, mapSlug: "office", groupId: fixture.manifest.groupId)
        let repository = ManifestRepository(session: session, directory: directory, now: { clock.now })

        StubURLProtocol.handler = { request in
            #expect(request.url?.path == "/api/public/maps/office/positioning-manifest")
            #expect(request.url?.query?.contains(fixture.manifest.groupId.uuidString.lowercased()) == true)
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                    headerFields: ["ETag": "\"revision-1\""])!, fixture.data)
        }
        let network = try await repository.load(configuration: configuration)
        #expect(network.source == .network)
        #expect(network.manifest.mapId == fixture.manifest.mapId)
        let cacheStem = "office-\(fixture.manifest.groupId.uuidString.lowercased())-active"
        #expect(FileManager.default.fileExists(atPath: directory.appendingPathComponent(cacheStem + ".json").path))
        let metadataURL = directory.appendingPathComponent(cacheStem + ".metadata.json")
        #expect(FileManager.default.fileExists(atPath: metadataURL.path))
        let metadata = try JSONDecoder().decode(TestCacheMetadata.self, from: Data(contentsOf: metadataURL))
        #expect(metadata.etag == "\"revision-1\"")

        clock.advance(6 * 24 * 60 * 60)
        StubURLProtocol.handler = { request in
            #expect(request.value(forHTTPHeaderField: "If-None-Match") == "\"revision-1\"")
            return (HTTPURLResponse(url: request.url!, statusCode: 304, httpVersion: nil, headerFields: nil)!, Data())
        }
        #expect(try await repository.load(configuration: configuration).source == .notModifiedCache)

        clock.advance(6 * 24 * 60 * 60)
        StubURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
        #expect(try await repository.load(configuration: configuration).source == .offlineCache)
    }

    /// Activated revisions are stored with fractional seconds, so they must parse alongside second-precision Z timestamps.
    @Test func manifestInstantParsingAcceptsStoredFractionalSeconds() {
        let seconds = parseManifestInstant("2026-08-06T09:45:29Z")
        #expect(seconds == Date(timeIntervalSince1970: 1_786_009_529))
        #expect(parseManifestInstant("2026-08-06T09:45:29.7026787+00:00") == seconds)
        #expect(parseManifestInstant("2026-08-06T18:45:29.702+09:00") == seconds)
        #expect(parseManifestInstant("2026-08-06") == nil)
    }

    @Test func manifestNotFoundBodyPreservesMapNotFound() {
        let body = Data(#"{"code":"map_not_found","message":"not found"}"#.utf8)

        #expect(
            classifyManifestNotFound(body) == .mapNotFound(
                debugDetail: "HTTP 404 body code=map_not_found"))
    }

    @Test func positioningManifestNotFoundBodyIsManifestUnavailable() {
        let body = Data(#"{"code":"positioning_manifest_not_found","message":"not enabled"}"#.utf8)

        #expect(
            classifyManifestNotFound(body) == .manifestUnavailable(
                debugDetail: "HTTP 404 body code=positioning_manifest_not_found"))
    }

    @Test func unparsableManifestNotFoundBodyIsManifestUnavailable() {
        #expect(
            classifyManifestNotFound(Data("not-json".utf8)) == .manifestUnavailable(
                debugDetail: "HTTP 404 unparsable body"))
        #expect(
            classifyManifestNotFound(Data()) == .manifestUnavailable(
                debugDetail: "HTTP 404 unparsable body"))
    }

    @Test func bleTestManifestRequestUsesDraftFlagAndSeparateCache() async throws {
        let fixture = try fixtureManifest()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let configuration = PositioningConfiguration(
            baseURL: URL(string: "https://example.test")!,
            mapSlug: "office",
            groupId: fixture.manifest.groupId,
            bleTest: true)
        let repository = ManifestRepository(session: stubSession(), directory: directory)

        StubURLProtocol.handler = { request in
            let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            #expect(items.contains(URLQueryItem(name: "group", value: fixture.manifest.groupId.uuidString.lowercased())))
            #expect(items.contains(URLQueryItem(name: "bletest", value: "1")))
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                    headerFields: ["ETag": "\"draft\""])!, fixture.data)
        }

        _ = try await repository.load(configuration: configuration)
        let cacheStem = "office-\(fixture.manifest.groupId.uuidString.lowercased())-bletest-draft"
        #expect(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent(cacheStem + ".json").path))
    }

    @Test @MainActor func clientRequiresExplicitAuthorizationAndStopsIdempotently() async throws {
        let fixture = try fixtureManifest()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let session = stubSession()
        StubURLProtocol.handler = { request in
            (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, fixture.data)
        }
        let location = FakeLocationAdapter()
        let motion = FakeMotionAdapter()
        // Reproduce a device without step authorization. Heading must still be subscribed in this state.
        motion.snapshot = .init(
            authorization: .denied, stepAvailable: true, activityAvailable: true,
            attitudeAvailable: true, gyroscopeAvailable: true, magnetometerAvailable: true,
            barometerAvailable: false)
        let diagnostics = DiagnosticEventRecorder()
        let client = MetamapsPositioningClient(
            configuration: .init(
                baseURL: URL(string: "https://example.test")!,
                mapSlug: "office",
                groupId: fixture.manifest.groupId,
                bleTest: false,
                diagnosticEventHandler: diagnostics.record),
            repository: ManifestRepository(session: session, directory: directory),
            locationAdapter: location, motionAdapter: motion, requiresUsageDescriptions: false)

        try await client.configure()
        #expect(client.status == .configured)
        #expect(client.manifestIdentity == .init(mapId: fixture.manifest.mapId, groupId: fixture.manifest.groupId, revision: 1))
        await #expect(throws: MetamapsError.self) { try await client.start() }
        try await client.requestAuthorization()
        try await client.start()
        #expect(client.status == .acquiring)
        // If startHardware() went back to the old condition, the motion adapter would not start without step
        // authorization. Testing the pure function alone cannot catch wiring regressions, so pin it through the client.
        #expect(motion.startCount == 1)
        #expect(motion.lastSubscriptions?.subscribeToAttitude == true)
        #expect(location.startCount == 1)
        location.emit([
            BeaconObservation(
                beaconKey: "fda50693-a4e2-4fb1-afcf-c6eb07647825/100/1",
                uuid: "fda50693-a4e2-4fb1-afcf-c6eb07647825",
                major: 100,
                minor: 1,
                rssiDbm: -65,
                windowStartMonotonicTimestampMs: 1_000,
                monotonicTimestampMs: 1_000),
        ])
        for _ in 0..<20 where !diagnostics.types.contains("ble") {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(diagnostics.types.contains("ble"))
        try client.resetPedestrianRoute()
        #expect(motion.resetCount == 1)
        for _ in 0..<20 where !diagnostics.states.contains("route_reset") {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(diagnostics.states.contains("route_reset"))

        // Even if the map view's willEnterForeground start races with the client's didBecomeActive recovery, both wait
        // for the same new session. Without serialization, the adapters would start twice during recovery.
        client.pauseForBackground()
        #expect(client.status == .pausedBackground)
        async let hostStart: Void = client.start()
        async let lifecycleResume: Void = client.resumeFromBackground()
        try await hostStart
        await lifecycleResume
        #expect(location.startCount == 2)
        #expect(client.status == .recovering)
        // The new estimator's first tick is uninitialized and returns `acquiring`, but the same scan start is not
        // reported again as a status; `recovering` is kept while returning to the foreground.
        for _ in 0..<100 where client.latestUpdate == nil {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(client.latestUpdate?.estimate.status == "acquiring")
        #expect(client.status == .recovering)

        client.stop()
        client.stop()
        #expect(client.status == .stopped)
        #expect(location.stopCount >= 1)
        #expect(motion.stopCount >= 1)
        client.dispose()
    }

    @Test @MainActor func disposeBeforeHardwareStartsDoesNotStopPlatformAdapters() async throws {
        let fixture = try fixtureManifest()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let session = stubSession()
        StubURLProtocol.handler = { request in
            (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, fixture.data)
        }
        let location = FakeLocationAdapter()
        let motion = FakeMotionAdapter()
        let client = MetamapsPositioningClient(
            configuration: .init(
                baseURL: URL(string: "https://example.test")!,
                mapSlug: "office",
                groupId: fixture.manifest.groupId),
            repository: ManifestRepository(session: session, directory: directory),
            locationAdapter: location,
            motionAdapter: motion,
            requiresUsageDescriptions: false)

        try await client.configure()
        client.dispose()

        #expect(location.stopCount == 0)
        #expect(motion.stopCount == 0)
        #expect(client.status == .disposed)
    }

    @Test @MainActor func disabledMotionPolicyNeverStopsUnstartedMotionAdapter() async throws {
        let fixture = try fixtureManifest()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let session = stubSession()
        StubURLProtocol.handler = { request in
            (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, fixture.data)
        }
        let location = FakeLocationAdapter()
        let motion = FakeMotionAdapter()
        let client = MetamapsPositioningClient(
            configuration: .init(
                baseURL: URL(string: "https://example.test")!,
                mapSlug: "office",
                groupId: fixture.manifest.groupId,
                motionPolicy: .disabled),
            repository: ManifestRepository(session: session, directory: directory),
            locationAdapter: location,
            motionAdapter: motion,
            requiresUsageDescriptions: false)

        try await client.configure()
        try await client.requestAuthorization()
        try await client.start()
        #expect(location.startCount == 1)
        #expect(motion.startCount == 0)

        client.stop()
        #expect(location.stopCount == 1)
        #expect(motion.stopCount == 0)

        try await client.start()
        client.dispose()
        #expect(location.stopCount == 2)
        #expect(motion.stopCount == 0)
    }

    private func fixtureManifest() throws -> (manifest: PositioningManifest, data: Data) {
        let url = try #require(Bundle.module.url(forResource: "positioning-manifest", withExtension: "json"))
        let data = try Data(contentsOf: url)
        return (try JSONDecoder().decode(PositioningManifest.self, from: data), data)
    }

    private func stubSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Date

    init(_ value: Date) { self.value = value }

    var now: Date {
        lock.withLock { value }
    }

    func advance(_ interval: TimeInterval) {
        lock.withLock { value = value.addingTimeInterval(interval) }
    }
}

private struct TestCacheMetadata: Decodable {
    let etag: String?
    let verifiedAt: Date
}

private final class DiagnosticEventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []
    private var stateValues: [String] = []

    var types: [String] { lock.withLock { values } }
    var states: [String] { lock.withLock { stateValues } }

    func record(_ event: ReplayEvent) {
        lock.withLock {
            values.append(event.type)
            if let state = event.state { stateValues.append(state) }
        }
    }
}

/// Guards against regressions that write CMPedometer's cumulative distance directly into step events or drop collected values.
@Suite
struct PedometerDistanceCollectionTests {
    @Test func cumulativeDistanceIsRecordedAsFirstIntervalOnStepEvent() throws {
        var accumulator = PedometerEventAccumulator()
        let start = Date(timeIntervalSince1970: 1_000)

        let observed = accumulator.consume(
            count: 2,
            distanceM: 1.46,
            startDate: start,
            endDate: start.addingTimeInterval(1),
            monotonicTimestampMs: 5_000
        )
        let event = try #require(observed)

        #expect(event.pedometerDistanceM == 1.46)
    }

    @Test func missingAndRewoundDistanceRemainNilOnStepEvent() throws {
        var accumulator = PedometerEventAccumulator()
        let start = Date(timeIntervalSince1970: 1_000)
        _ = accumulator.consume(
            count: 1, distanceM: 0.7, startDate: start,
            endDate: start.addingTimeInterval(1), monotonicTimestampMs: 5_000)

        let missingObserved = accumulator.consume(
            count: 2, distanceM: nil, startDate: start,
            endDate: start.addingTimeInterval(2), monotonicTimestampMs: 6_000)
        let afterMissingObserved = accumulator.consume(
            count: 3, distanceM: 2.1, startDate: start,
            endDate: start.addingTimeInterval(3), monotonicTimestampMs: 7_000)
        let rewoundObserved = accumulator.consume(
            count: 4, distanceM: 0.5, startDate: start,
            endDate: start.addingTimeInterval(4), monotonicTimestampMs: 8_000)
        let missing = try #require(missingObserved)
        let afterMissing = try #require(afterMissingObserved)
        let rewound = try #require(rewoundObserved)

        #expect(missing.pedometerDistanceM == nil)
        #expect(afterMissing.pedometerDistanceM == nil)
        #expect(rewound.pedometerDistanceM == nil)
    }

    @Test func laterPedometerBatchRecordsDistanceDeltaRatherThanCumulative() throws {
        var accumulator = PedometerEventAccumulator()
        let start = Date(timeIntervalSince1970: 1_000)
        _ = accumulator.consume(
            count: 1, distanceM: 0.72, startDate: start,
            endDate: start.addingTimeInterval(1), monotonicTimestampMs: 5_000)

        let observed = accumulator.consume(
            count: PositioningEstimator.maxStepsPerEvent + 2,
            distanceM: 3.25,
            startDate: start,
            endDate: start.addingTimeInterval(2),
            monotonicTimestampMs: 6_000
        )
        let event = try #require(observed)

        #expect(abs(try #require(event.pedometerDistanceM) - 2.53) < 0.000_001)
        #expect(event.stepCount == PositioningEstimator.maxStepsPerEvent)
    }
}

#if os(iOS)
private final class CoreMotionCallbackBox: @unchecked Sendable {
    let deviceMotion: CMDeviceMotionHandler
    let pedometer: CMPedometerHandler

    init(
        deviceMotion: @escaping CMDeviceMotionHandler,
        pedometer: @escaping CMPedometerHandler
    ) {
        self.deviceMotion = deviceMotion
        self.pedometer = pedometer
    }
}
#endif

private final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: (@Sendable (URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard let handler = Self.handler else { throw URLError(.unknown) }
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

@MainActor
private final class FakeLocationAdapter: BeaconRangingAdapter {
    var authorized = false
    var startCount = 0
    var stopCount = 0
    var observationHandler: (@MainActor @Sendable ([BeaconObservation]) -> Void)?
    var snapshot: LocationSnapshot {
        .init(authorization: authorized ? .whenInUse : .notDetermined,
              precise: authorized, servicesEnabled: true, rangingAvailable: true)
    }
    func requestAuthorization() async throws { authorized = true }
    func start(constraints: [BeaconScanConstraint],
               onObservations: @escaping @MainActor @Sendable ([BeaconObservation]) -> Void,
               onError: @escaping @MainActor @Sendable (MetamapsError) -> Void) throws {
        #expect(!constraints.isEmpty)
        observationHandler = onObservations
        startCount += 1
    }
    func stop() { stopCount += 1 }
    func emit(_ observations: [BeaconObservation]) { observationHandler?(observations) }
}

@MainActor
private final class FakeMotionAdapter: MotionObservationAdapter {
    private(set) var stopCount = 0
    private(set) var resetCount = 0
    private(set) var startCount = 0
    private(set) var lastSubscriptions: MotionSubscriptionDecision?
    var snapshot: MotionSnapshot = .init(
        authorization: .granted, stepAvailable: true, activityAvailable: true,
        attitudeAvailable: true, gyroscopeAvailable: true, magnetometerAvailable: true,
        barometerAvailable: false)
    func requestAuthorization() async throws {}
    func start(subscriptions: MotionSubscriptionDecision,
               onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
               onError: @escaping @MainActor @Sendable (MetamapsError) -> Void) {
        startCount += 1
        lastSubscriptions = subscriptions
    }
    func resetStepBaseline() { resetCount += 1 }
    func stop() { stopCount += 1 }
}
