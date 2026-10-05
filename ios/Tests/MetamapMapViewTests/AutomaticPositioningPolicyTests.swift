import Foundation
import MetamapPositioning
import Testing
@testable import Metamap

// The bounded automatic positioning loop. Decisions are pinned by feeding start completion and timer expiry, without waiting in real time.
struct AutomaticPositioningLoopTests {
    /// Builds the state after a start request has advanced to scanning.
    private func started() -> AutomaticPositioningLoop {
        var loop = AutomaticPositioningLoop()
        #expect(loop.start() == [.cancelTimer, .start])
        #expect(loop.phase == .starting)
        #expect(loop.positioningStarted() == [.scheduleTimer(delayMs: 10_000)])
        return loop
    }

    @Test func countsTheSignalWaitFromWhenScanActuallyStarts() {
        var loop = AutomaticPositioningLoop()
        _ = loop.start()
        // Time spent on permission dialogs or the manifest download does not count as waiting for signals.
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [])
        #expect(loop.phase == .starting)
        #expect(loop.positioningStarted() == [.scheduleTimer(delayMs: 10_000)])
        #expect(loop.phase == .awaitingSignals)
    }

    @Test func waitsTenSecondsForRegisteredBeaconsThenRetriesEveryThirtySeconds() {
        var loop = started()
        // No signal in 10 seconds means out of range. Release scanning and schedule a retry in 30 seconds.
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [.stop, .scheduleTimer(delayMs: 30_000)])
        #expect(loop.phase == .awaitingRetry)

        // While in the foreground, repeat with the same 10-second wait.
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [.start])
        #expect(loop.phase == .starting)
        #expect(loop.positioningStarted() == [.scheduleTimer(delayMs: 10_000)])
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [.stop, .scheduleTimer(delayMs: 30_000)])
    }

    @Test func staysInCoverageOnceARegisteredBeaconArrives() {
        var loop = started()
        // A received signal means in range. Stop waiting, and neither stop nor retry afterward.
        #expect(loop.timerFired(observedRegisteredBeacon: true) == [])
        #expect(loop.phase == .inCoverage)
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [])
        #expect(loop.phase == .inCoverage)
    }

    @Test func startIsIdempotentWhileStartingWaitingOrPositioning() {
        var loop = AutomaticPositioningLoop()
        _ = loop.start()
        // A repeated request while starting does not start twice.
        #expect(loop.start() == [])
        _ = loop.positioningStarted()
        // Do not reset the wait (rescheduling the timer on every request would keep extending the 10 seconds).
        #expect(loop.start() == [])
        _ = loop.timerFired(observedRegisteredBeacon: true)
        #expect(loop.start() == [])
        #expect(loop.phase == .inCoverage)
    }

    @Test func startFailureGoesToTheRetryCycleInsteadOfWaitingForSignals() {
        var loop = AutomaticPositioningLoop()
        _ = loop.start()
        // Denied permission, Bluetooth off, and manifest failures take this path. Do not enter the signal wait.
        #expect(loop.startFailed() == [.scheduleTimer(delayMs: 30_000)])
        #expect(loop.phase == .awaitingRetry)
        // If the user grants permission in the Settings app right after denying it and comes back, retry without waiting 30 seconds.
        #expect(loop.returnToForeground() == [.cancelTimer, .start])
        #expect(loop.phase == .starting)
    }

    @Test func hostStartedPositioningIsAdoptedIntoTheAreaGate() {
        var loop = AutomaticPositioningLoop()
        // Positioning the host started directly with `startPositioning()` joins the same wait and retry cycle.
        #expect(loop.phase == .idle)
        #expect(loop.positioningStarted() == [.scheduleTimer(delayMs: 10_000)])
        #expect(loop.phase == .awaitingSignals)
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [.stop, .scheduleTimer(delayMs: 30_000)])
    }

    @Test func retryCycleIsSuspendedWhileInTheBackground() {
        var loop = started()
        _ = loop.timerFired(observedRegisteredBeacon: false)
        #expect(loop.phase == .awaitingRetry)
        // In the background, the 30-second timer does not advance (retries happen only in the foreground).
        #expect(loop.enterBackground() == [.cancelTimer])
        #expect(loop.phase == .suspendedBackground)
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [])
        #expect(loop.returnToForeground() == [.cancelTimer, .start])
    }

    @Test func backgroundDuringTheSignalWaitRestartsTheWindowOnReturn() {
        var loop = started()
        // Even after a signal was received, the client recreates scanning on returning to the foreground, so the wait is rescheduled too.
        #expect(loop.enterBackground() == [.cancelTimer])
        #expect(loop.returnToForeground() == [.cancelTimer, .start])
        #expect(loop.positioningStarted() == [.scheduleTimer(delayMs: 10_000)])
    }

    @Test func foregroundReturnDoesNothingWhileScanIsAlreadyRunning() {
        var loop = started()
        #expect(loop.returnToForeground() == [])
        _ = loop.timerFired(observedRegisteredBeacon: true)
        #expect(loop.returnToForeground() == [])
    }

    @Test func stopEndsBothPositioningAndTheRetryCycle() {
        var loop = started()
        #expect(loop.stop() == [.cancelTimer, .stop])
        #expect(loop.phase == .stopped)
        // After a stop, neither elapsed time nor returning to the foreground restarts positioning.
        #expect(loop.timerFired(observedRegisteredBeacon: false) == [])
        #expect(loop.returnToForeground() == [])
        #expect(loop.enterBackground() == [])
        #expect(loop.stop() == [])
        // Only `positioning.start` or the host API restarts it.
        #expect(loop.start() == [.cancelTimer, .start])
    }

    @Test func stopDuringRetryWaitOnlyCancelsTheTimer() {
        var loop = started()
        _ = loop.timerFired(observedRegisteredBeacon: false)
        // Scanning is already released. Do not stop twice.
        #expect(loop.stop() == [.cancelTimer])
        #expect(loop.phase == .stopped)
    }
}

// How `bridge.hello.payload.automaticPositioning` is decided.
struct AutomaticPositioningConfigurationTests {
    private func enabled(
        trigger: MapViewPositioningStartTrigger,
        policy: MapViewPositioningPolicy = .userInitiated,
        bleTest: Bool = false,
        canStartWithoutNewPrompt: Bool = true,
        hasVerifiedManifest: Bool = true
    ) -> Bool {
        isAutomaticPositioningConfiguration(
            trigger: trigger,
            policy: policy,
            bleTest: bleTest,
            canStartWithoutNewPrompt: canStartWithoutNewPrompt,
            hasVerifiedManifest: hasVerifiedManifest)
    }

    @Test func triggerDecidesWhetherTheMapViewPositionsAutomatically() {
        #expect(enabled(trigger: .userAction) == false)
        #expect(enabled(trigger: .automatic) == true)
        // `automatic` may request permissions, so it counts as automatic positioning even while undecided.
        #expect(enabled(trigger: .automatic, canStartWithoutNewPrompt: false) == true)
        #expect(enabled(trigger: .automaticWhenAuthorized) == true)
        // On a device that needs a new OS dialog, the value is false.
        #expect(enabled(trigger: .automaticWhenAuthorized, canStartWithoutNewPrompt: false) == false)
    }

    @Test func bleTestAndManifestGateTheConfiguration() {
        #expect(enabled(trigger: .automatic, bleTest: true) == false)
        #expect(enabled(trigger: .automatic, hasVerifiedManifest: false) == false)
    }

    // Pin all nine combinations of the start trigger (3 values) and the policy (3 values).
    @Test func coversEveryTriggerAndPolicyCombination() {
        let expected: [MapViewPositioningStartTrigger: [MapViewPositioningPolicy: AutomaticPositioningStartAction]] = [
            .userAction: [
                .userInitiated: .startDirectly,
                .hostControlled: .notifyHost,
                .disabled: .reject,
            ],
            .automatic: [
                .userInitiated: .runAutomaticLoop,
                .hostControlled: .notifyHost,
                .disabled: .reject,
            ],
            .automaticWhenAuthorized: [
                .userInitiated: .runAutomaticLoop,
                .hostControlled: .notifyHost,
                .disabled: .reject,
            ],
        ]
        for (trigger, byPolicy) in expected {
            for (policy, action) in byPolicy {
                let isAutomatic = enabled(trigger: trigger, policy: policy)
                #expect(
                    resolveAutomaticPositioningStartAction(policy: policy, isAutomaticConfiguration: isAutomatic)
                        == action,
                    "trigger=\(trigger) policy=\(policy)")
            }
        }
    }

    @Test func defaultConfigurationPositionsAutomaticallyWhenAlreadyAuthorized() {
        let configuration = MetamapMapViewConfiguration(mapSlug: "example")
        #expect(configuration.positioningStartTrigger == .automaticWhenAuthorized)
        #expect(configuration.positioningPolicy == .userInitiated)
    }
}

// `automaticWhenAuthorized` starts automatically only when no new OS dialog appears.
struct AutomaticPositioningPromptTests {
    /// `CapabilityReport` does not expose a memberwise init, so build it from the same JSON the bridge carries.
    private func report(
        location: LocationAuthorizationState = .whenInUse,
        precise: Bool = true,
        motion: MotionAuthorizationState = .granted
    ) -> CapabilityReport {
        let json = """
        {
          "platform": "ios",
          "osVersion": "18.0",
          "sdkVersion": "0.1.0",
          "ble": {
            "supported": true, "enabled": true, "rangingAvailable": true, "foregroundScan": true,
            "backgroundScan": "paused_in_v1", "directionFinding": "unsupported",
            "channelSounding": "unsupported"
          },
          "authorization": {
            "location": "\(location.rawValue)", "preciseLocation": \(precise),
            "bluetoothScan": "notRequired", "motion": "\(motion.rawValue)"
          },
          "sensors": {
            "stepDetector": true, "rotationVector": true, "gyroscope": true,
            "magnetometer": true, "barometer": true
          },
          "selectedProfile": "ble_pdr_pf"
        }
        """
        // swiftlint:disable:next force_try
        return try! JSONDecoder().decode(CapabilityReport.self, from: Data(json.utf8))
    }

    @Test func requiresEveryPermissionTheStartWouldRequest() {
        #expect(canStartPositioningWithoutNewPrompt(capabilities: report(), motionPolicy: .preferred))
        // Undecided or denied location shows a dialog.
        for state in [LocationAuthorizationState.notDetermined, .denied, .restricted] {
            #expect(canStartPositioningWithoutNewPrompt(
                capabilities: report(location: state), motionPolicy: .preferred) == false)
        }
        // A device with precise location turned off cannot range beacons and fails at start even if declared.
        #expect(canStartPositioningWithoutNewPrompt(
            capabilities: report(precise: false), motionPolicy: .preferred) == false)
        // Undecided Motion & Fitness shows the step counting dialog.
        #expect(canStartPositioningWithoutNewPrompt(
            capabilities: report(motion: .notDetermined), motionPolicy: .preferred) == false)
        // If denied, no dialog appears again (steps are given up and positioning runs on heading alone).
        #expect(canStartPositioningWithoutNewPrompt(
            capabilities: report(motion: .denied), motionPolicy: .preferred))
        // A configuration without PDR does not request Motion.
        #expect(canStartPositioningWithoutNewPrompt(
            capabilities: report(motion: .notDetermined), motionPolicy: .disabled))
    }
}
