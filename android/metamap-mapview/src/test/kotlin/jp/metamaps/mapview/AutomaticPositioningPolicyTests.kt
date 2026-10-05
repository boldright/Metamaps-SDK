package jp.metamaps.mapview

import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.LocationAuthorizationState
import jp.metamaps.positioning.android.MotionAuthorizationState
import jp.metamaps.positioning.android.MotionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bounded automatic positioning loop. Decisions are pinned by feeding start completion and timer expiry, without waiting in real time. */
class AutomaticPositioningLoopTests {
    private val startEffects = listOf(
        AutomaticPositioningEffect.CancelTimer,
        AutomaticPositioningEffect.Start,
    )
    private val signalWait = listOf(
        AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_SIGNAL_WAIT_MS),
    )
    private val retryWait = listOf(
        AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_RETRY_DELAY_MS),
    )
    private val outOfCoverage = listOf(
        AutomaticPositioningEffect.Stop,
        AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_RETRY_DELAY_MS),
    )

    /** Builds the state after a start request has advanced to scanning. */
    private fun started(): AutomaticPositioningLoop {
        val loop = AutomaticPositioningLoop()
        assertEquals(startEffects, loop.start())
        assertEquals(AutomaticPositioningPhase.STARTING, loop.phase)
        assertEquals(signalWait, loop.positioningStarted())
        return loop
    }

    @Test
    fun countsTheSignalWaitFromWhenScanActuallyStarts() {
        val loop = AutomaticPositioningLoop()
        loop.start()
        // Time spent on permission dialogs or the manifest download does not count as waiting for signals.
        assertTrue(loop.timerFired(observedRegisteredBeacon = false).isEmpty())
        assertEquals(AutomaticPositioningPhase.STARTING, loop.phase)
        assertEquals(signalWait, loop.positioningStarted())
        assertEquals(AutomaticPositioningPhase.AWAITING_SIGNALS, loop.phase)
    }

    @Test
    fun waitsTenSecondsForRegisteredBeaconsThenRetriesEveryThirtySeconds() {
        val loop = started()
        // No signal in 10 seconds means out of range. Release scanning and schedule a retry in 30 seconds.
        assertEquals(outOfCoverage, loop.timerFired(observedRegisteredBeacon = false))
        assertEquals(AutomaticPositioningPhase.AWAITING_RETRY, loop.phase)

        // While in the foreground, repeat with the same 10-second wait.
        assertEquals(listOf(AutomaticPositioningEffect.Start), loop.timerFired(observedRegisteredBeacon = false))
        assertEquals(signalWait, loop.positioningStarted())
        assertEquals(outOfCoverage, loop.timerFired(observedRegisteredBeacon = false))
        assertEquals(10_000L, AUTOMATIC_POSITIONING_SIGNAL_WAIT_MS)
        assertEquals(30_000L, AUTOMATIC_POSITIONING_RETRY_DELAY_MS)
    }

    @Test
    fun staysInCoverageOnceARegisteredBeaconArrives() {
        val loop = started()
        // A received signal means in range. Stop waiting, and neither stop nor retry afterward.
        assertTrue(loop.timerFired(observedRegisteredBeacon = true).isEmpty())
        assertEquals(AutomaticPositioningPhase.IN_COVERAGE, loop.phase)
        assertTrue(loop.timerFired(observedRegisteredBeacon = false).isEmpty())
        assertEquals(AutomaticPositioningPhase.IN_COVERAGE, loop.phase)
    }

    @Test
    fun startIsIdempotentWhileStartingWaitingOrPositioning() {
        val loop = AutomaticPositioningLoop()
        loop.start()
        // A repeated request while starting does not start twice.
        assertTrue(loop.start().isEmpty())
        loop.positioningStarted()
        // Do not reset the wait (rescheduling the timer on every request would keep extending the 10 seconds).
        assertTrue(loop.start().isEmpty())
        loop.timerFired(observedRegisteredBeacon = true)
        assertTrue(loop.start().isEmpty())
    }

    @Test
    fun startFailureGoesToTheRetryCycleInsteadOfWaitingForSignals() {
        val loop = AutomaticPositioningLoop()
        loop.start()
        // Denied permission, Bluetooth off, and manifest failures take this path. Do not enter the signal wait.
        assertEquals(retryWait, loop.startFailed())
        assertEquals(AutomaticPositioningPhase.AWAITING_RETRY, loop.phase)
        // If the user grants permission in the Settings app right after denying it and comes back, retry without waiting 30 seconds.
        assertEquals(startEffects, loop.returnToForeground())
    }

    @Test
    fun hostStartedPositioningIsAdoptedIntoTheAreaGate() {
        val loop = AutomaticPositioningLoop()
        // Positioning the host started directly with `startPositioning()` joins the same wait and retry cycle.
        assertEquals(AutomaticPositioningPhase.IDLE, loop.phase)
        assertEquals(signalWait, loop.positioningStarted())
        assertEquals(outOfCoverage, loop.timerFired(observedRegisteredBeacon = false))
    }

    @Test
    fun retryCycleIsSuspendedWhileInTheBackground() {
        val loop = started()
        loop.timerFired(observedRegisteredBeacon = false)
        // In the background, the 30-second timer does not advance (retries happen only in the foreground).
        assertEquals(listOf(AutomaticPositioningEffect.CancelTimer), loop.enterBackground())
        assertEquals(AutomaticPositioningPhase.SUSPENDED_BACKGROUND, loop.phase)
        assertTrue(loop.timerFired(observedRegisteredBeacon = false).isEmpty())
        assertEquals(startEffects, loop.returnToForeground())
    }

    @Test
    fun backgroundDuringTheSignalWaitRestartsTheWindowOnReturn() {
        val loop = started()
        // Even after a signal was received, the client recreates scanning on returning to the foreground, so the wait is rescheduled too.
        assertEquals(listOf(AutomaticPositioningEffect.CancelTimer), loop.enterBackground())
        assertEquals(startEffects, loop.returnToForeground())
        assertEquals(signalWait, loop.positioningStarted())
    }

    @Test
    fun foregroundReturnDoesNothingWhileScanIsAlreadyRunning() {
        val loop = started()
        assertTrue(loop.returnToForeground().isEmpty())
        loop.timerFired(observedRegisteredBeacon = true)
        assertTrue(loop.returnToForeground().isEmpty())
    }

    @Test
    fun stopEndsBothPositioningAndTheRetryCycle() {
        val loop = started()
        assertEquals(
            listOf(AutomaticPositioningEffect.CancelTimer, AutomaticPositioningEffect.Stop),
            loop.stop(),
        )
        assertEquals(AutomaticPositioningPhase.STOPPED, loop.phase)
        // After a stop, neither elapsed time nor returning to the foreground restarts positioning.
        assertTrue(loop.timerFired(observedRegisteredBeacon = false).isEmpty())
        assertTrue(loop.returnToForeground().isEmpty())
        assertTrue(loop.enterBackground().isEmpty())
        assertTrue(loop.stop().isEmpty())
        // Only `positioning.start` or the host API restarts it.
        assertEquals(startEffects, loop.start())
    }

    @Test
    fun stopDuringRetryWaitOnlyCancelsTheTimer() {
        val loop = started()
        loop.timerFired(observedRegisteredBeacon = false)
        // Scanning is already released. Do not stop twice.
        assertEquals(listOf(AutomaticPositioningEffect.CancelTimer), loop.stop())
    }
}

/** How `bridge.hello.payload.automaticPositioning` is decided. */
class AutomaticPositioningConfigurationTests {
    private fun enabled(
        trigger: MapViewPositioningStartTrigger,
        policy: MapViewPositioningPolicy = MapViewPositioningPolicy.USER_INITIATED,
        bleTest: Boolean = false,
        canStartWithoutNewPrompt: Boolean = true,
        hasVerifiedManifest: Boolean = true,
    ) = isAutomaticPositioningConfiguration(
        trigger = trigger,
        policy = policy,
        bleTest = bleTest,
        canStartWithoutNewPrompt = canStartWithoutNewPrompt,
        hasVerifiedManifest = hasVerifiedManifest,
    )

    @Test
    fun triggerDecidesWhetherTheMapViewPositionsAutomatically() {
        assertFalse(enabled(MapViewPositioningStartTrigger.USER_ACTION))
        assertTrue(enabled(MapViewPositioningStartTrigger.AUTOMATIC))
        // `AUTOMATIC` may request permissions, so it counts as automatic positioning even while undecided.
        assertTrue(enabled(MapViewPositioningStartTrigger.AUTOMATIC, canStartWithoutNewPrompt = false))
        assertTrue(enabled(MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED))
        // On a device that needs a new permission dialog, the value is false.
        assertFalse(
            enabled(MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED, canStartWithoutNewPrompt = false),
        )
    }

    @Test
    fun bleTestAndManifestGateTheConfiguration() {
        assertFalse(enabled(MapViewPositioningStartTrigger.AUTOMATIC, bleTest = true))
        assertFalse(enabled(MapViewPositioningStartTrigger.AUTOMATIC, hasVerifiedManifest = false))
    }

    /** Pin all nine combinations of the start trigger (3 values) and the policy (3 values). */
    @Test
    fun coversEveryTriggerAndPolicyCombination() {
        val expected = mapOf(
            MapViewPositioningStartTrigger.USER_ACTION to mapOf(
                MapViewPositioningPolicy.USER_INITIATED to AutomaticPositioningStartAction.START_DIRECTLY,
                MapViewPositioningPolicy.HOST_CONTROLLED to AutomaticPositioningStartAction.NOTIFY_HOST,
                MapViewPositioningPolicy.DISABLED to AutomaticPositioningStartAction.REJECT,
            ),
            MapViewPositioningStartTrigger.AUTOMATIC to mapOf(
                MapViewPositioningPolicy.USER_INITIATED to AutomaticPositioningStartAction.RUN_AUTOMATIC_LOOP,
                MapViewPositioningPolicy.HOST_CONTROLLED to AutomaticPositioningStartAction.NOTIFY_HOST,
                MapViewPositioningPolicy.DISABLED to AutomaticPositioningStartAction.REJECT,
            ),
            MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED to mapOf(
                MapViewPositioningPolicy.USER_INITIATED to AutomaticPositioningStartAction.RUN_AUTOMATIC_LOOP,
                MapViewPositioningPolicy.HOST_CONTROLLED to AutomaticPositioningStartAction.NOTIFY_HOST,
                MapViewPositioningPolicy.DISABLED to AutomaticPositioningStartAction.REJECT,
            ),
        )
        for ((trigger, byPolicy) in expected) {
            for ((policy, action) in byPolicy) {
                val isAutomatic = enabled(trigger, policy = policy)
                assertEquals(
                    action,
                    resolveAutomaticPositioningStartAction(policy, isAutomatic),
                    "trigger=$trigger policy=$policy",
                )
            }
        }
    }

    @Test
    fun defaultConfigurationPositionsAutomaticallyWhenAlreadyAuthorized() {
        val configuration = MetamapMapViewConfiguration(mapSlug = "example")
        assertEquals(
            MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED,
            configuration.positioningStartTrigger,
        )
        assertEquals(MapViewPositioningPolicy.USER_INITIATED, configuration.positioningPolicy)
    }
}

/** `AUTOMATIC_WHEN_AUTHORIZED` starts automatically only when no new permission dialog appears. */
class AutomaticPositioningPromptTests {
    private fun report(
        location: LocationAuthorizationState = LocationAuthorizationState.WHEN_IN_USE,
        bluetoothScan: String = "granted",
        motion: MotionAuthorizationState = MotionAuthorizationState.GRANTED,
        stepDetector: Boolean = true,
    ) = CapabilityReport(
        platform = "android",
        osVersion = "15",
        sdkVersion = "0.1.0",
        ble = CapabilityReport.Ble(
            supported = true,
            enabled = true,
            rangingAvailable = true,
            foregroundScan = true,
            backgroundScan = "paused_in_v1",
            directionFinding = "unsupported",
            channelSounding = "unsupported",
        ),
        authorization = CapabilityReport.Authorization(
            location = location,
            preciseLocation = true,
            bluetoothScan = bluetoothScan,
            motion = motion,
        ),
        sensors = CapabilityReport.Sensors(
            stepDetector = stepDetector,
            rotationVector = true,
            gyroscope = true,
            magnetometer = true,
            barometer = true,
        ),
        selectedProfile = "ble_pdr_pf",
    )

    @Test
    fun requiresEveryPermissionTheStartWouldRequest() {
        assertTrue(canStartPositioningWithoutNewPrompt(report(), MotionPolicy.PREFERRED))
        // Undecided or denied location shows a dialog.
        for (state in listOf(LocationAuthorizationState.NOT_DETERMINED, LocationAuthorizationState.DENIED)) {
            assertFalse(canStartPositioningWithoutNewPrompt(report(location = state), MotionPolicy.PREFERRED))
        }
        // Undecided or denied BLUETOOTH_SCAN on Android 12 and later shows a dialog.
        for (scan in listOf("notDetermined", "denied")) {
            assertFalse(canStartPositioningWithoutNewPrompt(report(bluetoothScan = scan), MotionPolicy.PREFERRED))
        }
        assertTrue(canStartPositioningWithoutNewPrompt(report(bluetoothScan = "notRequired"), MotionPolicy.PREFERRED))
        // Undecided ACTIVITY_RECOGNITION shows the step counting dialog.
        assertFalse(
            canStartPositioningWithoutNewPrompt(
                report(motion = MotionAuthorizationState.NOT_DETERMINED),
                MotionPolicy.PREFERRED,
            ),
        )
        // Devices without a step detector do not request the permission.
        assertTrue(
            canStartPositioningWithoutNewPrompt(
                report(motion = MotionAuthorizationState.NOT_DETERMINED, stepDetector = false),
                MotionPolicy.PREFERRED,
            ),
        )
        // If denied, no dialog appears again (steps are given up and positioning runs on heading alone).
        assertTrue(
            canStartPositioningWithoutNewPrompt(
                report(motion = MotionAuthorizationState.DENIED),
                MotionPolicy.PREFERRED,
            ),
        )
        // A configuration without PDR does not request Motion.
        assertTrue(
            canStartPositioningWithoutNewPrompt(
                report(motion = MotionAuthorizationState.NOT_DETERMINED),
                MotionPolicy.DISABLED,
            ),
        )
    }
}
