import Foundation
import MetamapsPositioning

/// How long to wait for signals from the beacon area.
let automaticPositioningSignalWaitMs: Int = 10_000
/// After stopping, how long to wait in the foreground before waiting for signals again.
let automaticPositioningRetryDelayMs: Int = 30_000

/// States of the bounded automatic positioning loop.
enum AutomaticPositioningPhase: Equatable, Sendable {
    /// No start request has been received.
    case idle
    /// Positioning was requested and the loop is waiting for scanning to start, including while a permission dialog is open.
    case starting
    /// Scanning has started and the loop is waiting for a registered beacon.
    case awaitingSignals
    /// The device is in the beacon area and positioning continues.
    case inCoverage
    /// Stopped because the device is out of range or the start failed; waiting in the foreground for the next retry.
    case awaitingRetry
    /// Moved to the background. No retry timer is kept; it is recreated on returning to the foreground.
    case suspendedBackground
    /// Stopped by `positioning.stop`. Only `positioning.start` or the host API restarts it.
    case stopped
}

/// Actions the caller performs to advance the loop.
enum AutomaticPositioningEffect: Equatable, Sendable {
    /// Starts positioning, requesting OS permissions if needed. The result arrives as `positioningStarted` or `startFailed`.
    case start
    /// Releases scanning and sensors.
    case stop
    /// Cancels any running timer and sends `timerFired` after the given number of milliseconds.
    case scheduleTimer(delayMs: Int)
    /// Cancels any running timer.
    case cancelTimer
}

/// A pure state machine that decides when to wait for signals and when to retry automatic positioning.
///
/// It has no clock and no timers. The caller runs the returned `AutomaticPositioningEffect`s and sends timer
/// expiry as `timerFired`, so the 10-second and 30-second decisions can be tested without real time.
///
/// The 10-second wait starts **when scanning actually starts**. Counting from the start request would spend
/// the wait on OS permission dialogs and the manifest download, and report the device as out of range before
/// scanning even ran.
struct AutomaticPositioningLoop: Equatable, Sendable {
    private(set) var phase: AutomaticPositioningPhase = .idle

    init() {}

    /// `positioning.start` from the runtime, or an explicit start from the host. Start requests are idempotent: a
    /// repeated request while starting, waiting, or positioning does not reset the timer.
    mutating func start() -> [AutomaticPositioningEffect] {
        switch phase {
        case .starting, .awaitingSignals, .inCoverage:
            return []
        case .idle, .awaitingRetry, .suspendedBackground, .stopped:
            phase = .starting
            return [.cancelTimer, .start]
        }
    }

    /// Scanning has started. The 10-second wait for signals starts here.
    ///
    /// Positioning that the host started directly with `startPositioning()` arrives here too, not only the loop's own
    /// `start`. In an automatic positioning configuration, the SDK owns the area check and the retry cycle regardless of who started positioning.
    mutating func positioningStarted() -> [AutomaticPositioningEffect] {
        switch phase {
        case .awaitingSignals, .inCoverage:
            return []
        case .idle, .starting, .awaitingRetry, .suspendedBackground, .stopped:
            phase = .awaitingSignals
            return [.scheduleTimer(delayMs: automaticPositioningSignalWaitMs)]
        }
    }

    /// The start failed (permission denied, Bluetooth off, or manifest download failed). Retry while in the foreground.
    mutating func startFailed() -> [AutomaticPositioningEffect] {
        guard phase == .starting else { return [] }
        phase = .awaitingRetry
        return [.scheduleTimer(delayMs: automaticPositioningRetryDelayMs)]
    }

    /// The pending timer expired. `observedRegisteredBeacon` tells whether at least one beacon registered in the
    /// manifest was received since the wait started.
    mutating func timerFired(observedRegisteredBeacon: Bool) -> [AutomaticPositioningEffect] {
        switch phase {
        case .awaitingSignals:
            if observedRegisteredBeacon {
                // In the beacon area: stop waiting and keep positioning.
                phase = .inCoverage
                return []
            }
            // No signal in 10 seconds means out of range. Release scanning and sensors without reporting a location, guidance, or an error.
            phase = .awaitingRetry
            return [.stop, .scheduleTimer(delayMs: automaticPositioningRetryDelayMs)]
        case .awaitingRetry:
            phase = .starting
            return [.start]
        case .idle, .starting, .inCoverage, .suspendedBackground, .stopped:
            return []
        }
    }

    /// Moved to the background. Retries run only in the foreground, so stop the timer and wait to return.
    /// The positioning client's background handling stops scanning.
    mutating func enterBackground() -> [AutomaticPositioningEffect] {
        switch phase {
        case .starting, .awaitingSignals, .inCoverage, .awaitingRetry:
            phase = .suspendedBackground
            return [.cancelTimer]
        case .idle, .suspendedBackground, .stopped:
            return []
        }
    }

    /// Returned from the background to the foreground. Retry immediately instead of waiting 30 seconds. This also
    /// covers a stop caused by denied permission, so positioning starts if the user granted it in the Settings app.
    mutating func returnToForeground() -> [AutomaticPositioningEffect] {
        switch phase {
        case .awaitingRetry, .suspendedBackground:
            phase = .starting
            return [.cancelTimer, .start]
        case .idle, .starting, .awaitingSignals, .inCoverage, .stopped:
            return []
        }
    }

    /// `positioning.stop` from the runtime, or an explicit stop from the host. Stops both running positioning and
    /// the retry cycle. Stop requests are not distinguished by kind.
    mutating func stop() -> [AutomaticPositioningEffect] {
        guard phase != .stopped else { return [] }
        let wasRunning = phase == .starting || phase == .awaitingSignals || phase == .inCoverage
        phase = .stopped
        return wasRunning ? [.cancelTimer, .stop] : [.cancelTimer]
    }
}

/// How to handle `positioning.start` from the bridge.
enum AutomaticPositioningStartAction: Equatable, Sendable {
    /// `disabled`: reject the request.
    case reject
    /// `hostControlled`: notify the host with `PositioningStartRequested`; the SDK does not start.
    case notifyHost
    /// `userInitiated` with automatic positioning: run it through the bounded loop.
    case runAutomaticLoop
    /// `userInitiated` without automatic positioning: start directly as before.
    case startDirectly
}

func resolveAutomaticPositioningStartAction(
    policy: MapViewPositioningPolicy,
    isAutomaticConfiguration: Bool
) -> AutomaticPositioningStartAction {
    switch policy {
    case .disabled: return .reject
    case .hostControlled: return .notifyHost
    case .userInitiated: return isAutomaticConfiguration ? .runAutomaticLoop : .startDirectly
    }
}

/// Whether positioning can start without an additional OS permission dialog.
///
/// `automaticWhenAuthorized` means "start automatically as long as no new OS dialog appears", so every
/// permission that the start could request must already be decided, not only location.
func canStartPositioningWithoutNewPrompt(
    capabilities: CapabilityReport,
    motionPolicy: MotionPolicy
) -> Bool {
    let authorization = capabilities.authorization
    guard authorization.location == .whenInUse || authorization.location == .always else { return false }
    // A device with precise location turned off cannot range beacons and fails with `preciseLocationRequired` even if declared.
    guard authorization.preciseLocation else { return false }
    // Motion & Fitness shows a dialog only while undecided. If denied, positioning gives up steps and runs on heading alone.
    if motionPolicy == .preferred, authorization.motion == .notDetermined { return false }
    return true
}

/// Whether the map view runs automatic positioning (`bridge.hello.payload.automaticPositioning`).
func isAutomaticPositioningConfiguration(
    trigger: MapViewPositioningStartTrigger,
    policy: MapViewPositioningPolicy,
    bleTest: Bool,
    canStartWithoutNewPrompt: Bool,
    hasVerifiedManifest: Bool
) -> Bool {
    guard policy != .disabled, !bleTest, hasVerifiedManifest else { return false }
    switch trigger {
    case .userAction: return false
    case .automatic: return true
    case .automaticWhenAuthorized: return canStartWithoutNewPrompt
    }
}

/// Whether the map view prepares positioning (downloads the positioning manifest) for the loaded map.
///
/// `map.ready` carries `manifestRevision` only for maps with active indoor positioning, and the web map never asks
/// other maps to start positioning. Skipping them avoids a manifest request and an error event on every load of a
/// map without beacons.
///
/// A client that already holds the same verified manifest (`verifiedManifestRevision`), for example after
/// `startPositioning()` before `.ready` or after a reload, is not configured again: configuring resets the
/// estimator and interrupts positioning in progress.
func shouldPreparePositioning(
    policy: MapViewPositioningPolicy,
    manifestRevision: Int?,
    bleTest: Bool,
    verifiedManifestRevision: Int?
) -> Bool {
    guard policy != .disabled else { return false }
    if let manifestRevision { return verifiedManifestRevision != manifestRevision }
    return bleTest && verifiedManifestRevision == nil
}
