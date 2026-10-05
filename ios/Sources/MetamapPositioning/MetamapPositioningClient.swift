import Foundation
import MetamapPositioningCore
#if os(iOS)
import UIKit
#endif

/// Keeps the particle filter and the RSSI filter off the UI actor. The single AsyncStream consumer in
/// `MetamapPositioningClient` keeps the events of one instance in order.
private actor PositioningEstimatorWorker {
    private let estimator: PositioningEstimator

    init(manifest: PositioningManifest, seed: UInt64) throws {
        estimator = try PositioningEstimator(manifest: manifest, seed: seed)
    }

    func process(_ event: ReplayEvent) throws -> PositionEstimate? {
        try estimator.process(event)
    }
}

/// A headless client that controls foreground positioning with Core Location and Core Motion.
///
/// Use it only from the main actor. `configure()` never shows a permission dialog.
@MainActor
public final class MetamapPositioningClient {
    /// An asynchronous stream of position, status, capability, diagnostic, and error events.
    public nonisolated let events: AsyncStream<MetamapPositioningEvent>
    /// The most recently reported lifecycle state.
    public private(set) var status: PositioningLifecycleStatus = .unconfigured
    /// The identity of the loaded manifest, or `nil` before configuration.
    public private(set) var manifestIdentity: ManifestIdentity?
    /// The most recently reported position, or `nil` if none has been received.
    public private(set) var latestUpdate: PositioningUpdate?
    /// A latch that reports the `lost` error only once. The estimator emits a result on every tick while `lost`,
    /// so without it the same error would flow to the host and the bridge at 4 Hz (matching the Android SDK).
    private var lostErrorEmitted = false
    /// Whether at least one beacon registered in the manifest was received since the last `start()`.
    ///
    /// The map view's automatic positioning uses it to decide whether the device is in the beacon area. Scan
    /// constraints come from the manifest's active beacons, so any received observation is a registered beacon.
    /// Raw observations and RSSI values never leave through this property.
    public private(set) var hasObservedRegisteredBeaconSinceStart = false

    /// The verified manifest currently driving the estimator. `nil` until `configure()` succeeds.
    @_spi(MetamapInternal)
    public var verifiedManifest: PositioningManifest? { manifest }

    private let configuration: PositioningConfiguration
    private let repository: ManifestRepository
    private let locationAdapter: BeaconRangingAdapter
    private let motionAdapter: MotionObservationAdapter
    private let requiresUsageDescriptions: Bool
    private let continuation: AsyncStream<MetamapPositioningEvent>.Continuation
    private var manifest: PositioningManifest?
    private var estimator: PositioningEstimatorWorker?
    private var estimatorContinuation: AsyncStream<ReplayEvent>.Continuation?
    private var estimatorProcessingTask: Task<Void, Never>?
    private var estimatorGeneration = 0
    private var signalDiagnostics = BeaconSignalDiagnosticsWorker()
    private var signalDiagnosticsTask: Task<Void, Never>?
    private var timer: Timer?
    private var running = false
    private var locationAdapterRunning = false
    private var motionAdapterRunning = false
    private var resumeAfterForeground = false
    /// Merges the manifest refresh and the scan start into one new session even when a map view start request
    /// races with the client's own `didBecomeActive` recovery. The task is shared, and the later caller waits for the same result.
    private var foregroundRecoveryTask: Task<Void, Error>?
    private var notificationTokens: [NSObjectProtocol] = []

    /// Creates a headless client from a validated configuration.
    public convenience init(configuration: PositioningConfiguration) {
        #if os(iOS)
        self.init(configuration: configuration, repository: ManifestRepository(),
                  locationAdapter: CoreLocationBeaconAdapter(), motionAdapter: CoreMotionObservationAdapter(),
                  requiresUsageDescriptions: true)
        #else
        self.init(configuration: configuration, repository: ManifestRepository(),
                  locationAdapter: UnsupportedBeaconRangingAdapter(), motionAdapter: UnsupportedMotionAdapter(),
                  requiresUsageDescriptions: false)
        #endif
    }

    init(
        configuration: PositioningConfiguration,
        repository: ManifestRepository,
        locationAdapter: BeaconRangingAdapter,
        motionAdapter: MotionObservationAdapter,
        requiresUsageDescriptions: Bool
    ) {
        self.configuration = configuration
        self.repository = repository
        self.locationAdapter = locationAdapter
        self.motionAdapter = motionAdapter
        self.requiresUsageDescriptions = requiresUsageDescriptions
        let stream = AsyncStream.makeStream(of: MetamapPositioningEvent.self, bufferingPolicy: .bufferingNewest(64))
        events = stream.stream
        continuation = stream.continuation
        installLifecycleObservers()
    }

    /// Downloads and validates the manifest and configures the client without showing a permission dialog.
    public func configure() async throws {
        guard status != .disposed else { throw invariant("configure called after dispose") }
        try configuration.validate()
        let loaded = try await repository.load(configuration: configuration)
        manifest = loaded.manifest
        manifestIdentity = .init(mapId: loaded.manifest.mapId, groupId: loaded.manifest.groupId,
                                 revision: loaded.manifest.revision)
        try installEstimator(manifest: loaded.manifest)
        resetSignalDiagnostics()
        latestUpdate = nil
        lostErrorEmitted = false
        transition(.configured)
        continuation.yield(.capabilities(capabilities()))
    }

    /// Returns the current OS permissions, sensor capabilities, and the selected profile.
    public func capabilities() -> CapabilityReport {
        let location = locationAdapter.snapshot
        let motion = motionAdapter.snapshot
        let selectedProfile: String
        if !location.rangingAvailable || !location.servicesEnabled { selectedProfile = "unavailable" }
        else if motion.authorization == .granted && motion.stepAvailable && motion.attitudeAvailable
            && motion.magnetometerAvailable {
            selectedProfile = "ble_pdr_pf"
        }
        else { selectedProfile = "ble_pf" }
        return .init(
            platform: "ios",
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            sdkVersion: MetamapPositioningSDK.version,
            ble: .init(supported: location.rangingAvailable, enabled: location.servicesEnabled,
                       rangingAvailable: location.rangingAvailable, foregroundScan: location.rangingAvailable,
                       backgroundScan: "paused_in_v1", directionFinding: "unsupported", channelSounding: "unsupported"),
            authorization: .init(location: location.authorization, preciseLocation: location.precise,
                                 bluetoothScan: "notRequired", motion: motion.authorization),
            sensors: .init(stepDetector: motion.stepAvailable, rotationVector: motion.attitudeAvailable,
                           gyroscope: motion.gyroscopeAvailable, magnetometer: motion.magnetometerAvailable,
                           barometer: motion.barometerAvailable),
            selectedProfile: selectedProfile)
    }

    /// Shows the OS permission dialogs. Always call this from an explicit user action.
    public func requestAuthorization(_ mode: PositioningAuthorizationMode = .foregroundNavigation) async throws {
        guard mode == .foregroundNavigation else { throw invariant("unsupported authorization mode") }
        guard manifest != nil else { throw invariant("configure must complete before requesting authorization") }
        if requiresUsageDescriptions {
            guard Self.hasUsageDescription("NSLocationWhenInUseUsageDescription") else {
                throw MetamapPositioningError.configurationInvalid("NSLocationWhenInUseUsageDescription is missing from the host app Info.plist.")
            }
        }
        try await locationAdapter.requestAuthorization()
        if configuration.motionPolicy == .preferred {
            if !requiresUsageDescriptions || Self.hasUsageDescription("NSMotionUsageDescription") {
                do { try await motionAdapter.requestAuthorization() }
                catch let error as MetamapPositioningError { continuation.yield(.error(error)) }
            } else {
                continuation.yield(.error(.init(
                    code: .motionPermissionDenied, message: "NSMotionUsageDescription is missing; step counting (PDR) is disabled while heading stays available.",
                    recoverable: true, userAction: .checkConfiguration)))
            }
        }
        transition(.authorized)
        continuation.yield(.capabilities(capabilities()))
    }

    /// Starts foreground positioning, calling `configure()` first if needed.
    public func start() async throws {
        guard status != .disposed else { throw invariant("start called after dispose") }
        if try await recoverFromBackgroundIfNeeded() { return }
        if manifest == nil { try await configure() }
        guard !running else { return }
        let location = locationAdapter.snapshot
        guard location.authorization == .whenInUse || location.authorization == .always else {
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Call requestAuthorization from a user action before start.",
                                          recoverable: true, userAction: .requestPermission)
        }
        guard location.precise else {
            throw MetamapPositioningError(code: .preciseLocationRequired, message: "Precise Location is required for iBeacon ranging.",
                                          recoverable: false, userAction: .enablePreciseLocation)
        }
        try startHardware()
    }

    /// Stops hardware subscriptions and keeps the client reusable.
    public func stop() {
        guard status != .disposed else { return }
        resumeAfterForeground = false
        foregroundRecoveryTask?.cancel()
        stopHardware()
        transition(.stopped)
    }

    /// Resets the estimator and the latest result while keeping the manifest.
    public func reset() throws {
        guard let manifest else { throw invariant("configure must complete before reset") }
        try installEstimator(manifest: manifest)
        resetSignalDiagnostics()
        latestUpdate = nil
        lostErrorEmitted = false
        if running { transition(.acquiring) }
    }

    /// Resets PDR step timing and the estimator state at a known route start point.
    public func resetPedestrianRoute() throws {
        guard running else { throw invariant("positioning must be running before route reset") }
        motionAdapter.resetStepBaseline()
        latestUpdate = nil
        lostErrorEmitted = false
        ingest(ReplayEvent(
            type: "lifecycle",
            monotonicTimestampMs: MonotonicClock.nowMilliseconds,
            state: "route_reset"))
        transition(.acquiring)
    }

    /// Converts WGS 84 coordinates to manifest-local coordinates using the floor elevations of the same validated
    /// manifest. Returns `nil` before configuration or for a floor outside the manifest.
    public func manifestLocalPosition(
        longitude: Double,
        latitude: Double,
        floorId: UUID
    ) throws -> LocalPosition? {
        guard let manifest,
              let floor = manifest.floors.first(where: { $0.id == floorId }) else { return nil }
        return try PositioningCoordinateTransform.wgs84ToLocal(
            Wgs84Position(longitude: longitude, latitude: latitude, elevationM: floor.elevationM),
            frame: manifest.coordinateFrame)
    }

    /// Releases hardware, the estimator worker, and the event stream. Safe to call more than once.
    public func dispose() {
        guard status != .disposed else { return }
        resumeAfterForeground = false
        foregroundRecoveryTask?.cancel()
        stopHardware()
        stopEstimator()
        signalDiagnosticsTask?.cancel()
        signalDiagnosticsTask = nil
        notificationTokens.forEach(NotificationCenter.default.removeObserver)
        notificationTokens.removeAll()
        transition(.disposed)
        continuation.finish()
    }

    private func startHardware() throws {
        guard let manifest else { throw invariant("manifest missing") }
        // On returning to the foreground, the client's own background recovery and a start request from the host or
        // map view can run from the same notification. Do nothing if already running, so adapters are not subscribed twice.
        guard !running else { return }
        // Restart the wait for signals on every start, so an earlier reception is not taken as being in the area.
        hasObservedRegisteredBeaconSinceStart = false
        let enabledBeacons = manifest.beacons.filter(\.isEnabled)
        let rawConstraints: [BeaconScanConstraint] = enabledBeacons.compactMap { beacon in
            guard let uuid = UUID(uuidString: beacon.proximityUuid),
                  beacon.major >= 0, beacon.major <= Int(UInt16.max) else { return nil }
            return BeaconScanConstraint(uuid: uuid, major: UInt16(beacon.major))
        }
        let constraints = Set(rawConstraints).sorted { lhs, rhs in
            lhs.uuid.uuidString == rhs.uuid.uuidString
                ? (lhs.major ?? 0) < (rhs.major ?? 0) : lhs.uuid.uuidString < rhs.uuid.uuidString
        }
        try locationAdapter.start(constraints: constraints, onObservations: { [weak self] observations in
            self?.ingestBle(observations)
        }, onError: { [weak self] error in
            self?.continuation.yield(.error(error))
        })
        locationAdapterRunning = true
        // Attitude does not depend on Motion & Fitness authorization. Making step authorization and step availability
        // a precondition for attitude would lose the heading display just because the user declined step counting.
        // `NSMotionUsageDescription` is required by `CMPedometer`, so treat it as a condition for steps only.
        let motionDecision = motionSubscriptions(
            motionPolicy: configuration.motionPolicy,
            snapshot: motionAdapter.snapshot,
            motionUsageDescriptionPresent: !requiresUsageDescriptions
                || Self.hasUsageDescription("NSMotionUsageDescription"))
        if motionDecision.hasSubscriptions {
            motionAdapter.start(subscriptions: motionDecision,
                                onEvent: { [weak self] event in self?.ingest(event) },
                                onError: { [weak self] error in self?.continuation.yield(.error(error)) })
            motionAdapterRunning = true
        }
        running = true
        transition(.acquiring)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func stopHardware() {
        // On physical iOS devices, the stop sequence of a Core Motion adapter that never started shows the Motion & Fitness
        // dialog. Use each adapter's own started state, not the client's, and never touch unstarted motion adapters even
        // with motionPolicy.disabled.
        guard running || locationAdapterRunning || motionAdapterRunning else { return }
        timer?.invalidate()
        timer = nil
        if locationAdapterRunning {
            locationAdapter.stop()
            locationAdapterRunning = false
        }
        if motionAdapterRunning {
            motionAdapter.stop()
            motionAdapterRunning = false
        }
        running = false
    }

    private func ingestBle(_ observations: [BeaconObservation]) {
        guard let timestamp = observations.map(\.monotonicTimestampMs).max() else { return }
        hasObservedRegisteredBeaconSinceStart = true
        ingest(ReplayEvent(type: "ble", monotonicTimestampMs: timestamp, observations: observations))
        guard configuration.beaconDiagnosticsEnabled, let manifest else { return }
        let worker = signalDiagnostics
        let update = latestUpdate
        signalDiagnosticsTask?.cancel()
        signalDiagnosticsTask = Task { [weak self] in
            do {
                let readings = try await worker.readings(
                    observations: observations,
                    manifest: manifest,
                    latestUpdate: update)
                guard !Task.isCancelled else { return }
                self?.continuation.yield(.beaconSignals(readings))
            } catch {
                guard !Task.isCancelled else { return }
                self?.continuation.yield(.error(
                    self?.invariant("BLE diagnostic conversion failed", error)
                    ?? MetamapPositioningError.configurationInvalid("Positioning client released")))
            }
        }
    }

    private func ingest(_ event: ReplayEvent) {
        if event.type == "attitude" {
            continuation.yield(.motionHeading(.init(
                localHeadingDeg: event.headingDeg,
                magneticFieldAccuracy: event.magneticFieldAccuracy)))
        }
        estimatorContinuation?.yield(event)
    }

    private func tick() {
        guard running else { return }
        ingest(ReplayEvent(type: "tick", monotonicTimestampMs: MonotonicClock.nowMilliseconds))
    }

    private func installEstimator(manifest: PositioningManifest) throws {
        stopEstimator()
        estimatorGeneration += 1
        let generation = estimatorGeneration
        // The estimator core throws `PositioningCoreError`. The public error contract is the `code` and `userAction`
        // of `MetamapPositioningError`, so convert to the typed error here.
        let worker: PositioningEstimatorWorker
        do {
            worker = try PositioningEstimatorWorker(
                manifest: manifest,
                seed: UInt64.random(in: 1...UInt64.max))
        } catch let error as MetamapPositioningError {
            throw error
        } catch {
            throw MetamapPositioningError(
                code: .manifestUnsupported,
                message: "The positioning manifest requests an unsupported estimator configuration.",
                recoverable: false, userAction: .retry, debugDetail: String(describing: error))
        }
        estimator = worker
        let stream = AsyncStream.makeStream(
            of: ReplayEvent.self,
            bufferingPolicy: .bufferingNewest(64))
        estimatorContinuation = stream.continuation
        let diagnosticEventHandler = configuration.diagnosticEventHandler
        estimatorProcessingTask = Task.detached(priority: .userInitiated) { [weak self] in
            for await event in stream.stream {
                guard !Task.isCancelled else { break }
                diagnosticEventHandler?(event)
                do {
                    guard let estimate = try await worker.process(event) else { continue }
                    let wgs84 = try estimate.local.map {
                        try PositioningCoordinateTransform.localToWgs84(
                            $0,
                            frame: manifest.coordinateFrame)
                    }
                    let update = PositioningUpdate(
                        mapId: manifest.mapId,
                        groupId: manifest.groupId,
                        estimate: estimate,
                        wgs84: wgs84)
                    await self?.publish(update: update, generation: generation)
                } catch {
                    await self?.publishEstimatorError(
                        String(describing: error),
                        generation: generation)
                }
            }
        }
    }

    private func stopEstimator() {
        estimatorContinuation?.finish()
        estimatorContinuation = nil
        estimatorProcessingTask?.cancel()
        estimatorProcessingTask = nil
        estimator = nil
    }

    private func resetSignalDiagnostics() {
        signalDiagnosticsTask?.cancel()
        signalDiagnosticsTask = nil
        signalDiagnostics = BeaconSignalDiagnosticsWorker()
    }

    private func publish(update: PositioningUpdate, generation: Int) {
        guard generation == estimatorGeneration, running, status != .disposed else { return }
        latestUpdate = update
        if let mapped = PositioningLifecycleStatus(rawValue: update.estimate.status) {
            // On returning to the foreground, send `recovering` after the `acquiring` of the scan start. Reporting the
            // `acquiring` that the new estimator returns on ticks before it initializes would make one scan start look like two.
            if status != .recovering || mapped != .acquiring {
                transition(mapped)
            }
        }
        continuation.yield(.position(update))
        if update.estimate.status == "lost" {
            if !lostErrorEmitted {
                lostErrorEmitted = true
                continuation.yield(.error(.init(
                    code: .positionLost,
                    message: "Registered beacon signals were lost.",
                    recoverable: true,
                    userAction: .selectLocationManually)))
            }
        } else {
            lostErrorEmitted = false
        }
    }

    private func publishEstimatorError(_ detail: String, generation: Int) {
        guard generation == estimatorGeneration, status != .disposed else { return }
        continuation.yield(.error(invariant("estimator input failed: \(detail)")))
    }

    private func transition(_ value: PositioningLifecycleStatus) {
        guard status != value else { return }
        status = value
        continuation.yield(.status(value))
    }

    private func installLifecycleObservers() {
        #if os(iOS)
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.pauseForBackground() }
        })
        notificationTokens.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.resumeFromBackground() }
        })
        #endif
    }

    func pauseForBackground() {
        guard running else { return }
        configuration.diagnosticEventHandler?(
            ReplayEvent(
                type: "lifecycle",
                monotonicTimestampMs: MonotonicClock.nowMilliseconds,
                state: "background"))
        resumeAfterForeground = true
        stopHardware()
        transition(.pausedBackground)
        continuation.yield(.error(.init(code: .pausedBackground, message: "Foreground positioning paused in the background.",
                                        recoverable: true, userAction: .none)))
    }

    func resumeFromBackground() async {
        do {
            _ = try await recoverFromBackgroundIfNeeded()
        } catch let error as MetamapPositioningError {
            continuation.yield(.error(error))
        } catch {
            continuation.yield(.error(invariant("foreground recovery failed", error)))
        }
    }

    /// Runs a foreground return as one new positioning session. Whichever comes first, the map view's
    /// `startPositioning()` or the lifecycle observer, both wait for the same task, so scanning never starts twice.
    private func recoverFromBackgroundIfNeeded() async throws -> Bool {
        if let foregroundRecoveryTask {
            try await foregroundRecoveryTask.value
            return true
        }
        guard resumeAfterForeground, status != .disposed else { return false }

        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            configuration.diagnosticEventHandler?(
                ReplayEvent(
                    type: "lifecycle",
                    monotonicTimestampMs: MonotonicClock.nowMilliseconds,
                    state: "foreground"))
            try await configure()
            try Task.checkCancellation()
            try startHardware()
            transition(.recovering)
            resumeAfterForeground = false
        }
        foregroundRecoveryTask = task
        do {
            try await task.value
            foregroundRecoveryTask = nil
            return true
        } catch {
            foregroundRecoveryTask = nil
            throw error
        }
    }

    private static func hasUsageDescription(_ key: String) -> Bool {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func invariant(_ detail: String, _ underlying: Error? = nil) -> MetamapPositioningError {
        .init(code: .internalInvariantViolation, message: "The positioning SDK entered an invalid state.",
              recoverable: false, userAction: .retry,
              debugDetail: [detail, underlying.map(String.init(describing:))].compactMap { $0 }.joined(separator: ": "))
    }
}
