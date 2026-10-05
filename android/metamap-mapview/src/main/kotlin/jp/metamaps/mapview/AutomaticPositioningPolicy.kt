package jp.metamaps.mapview

import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.LocationAuthorizationState
import jp.metamaps.positioning.android.MotionAuthorizationState
import jp.metamaps.positioning.android.MotionPolicy

/** How long to wait for signals from the beacon area. */
internal const val AUTOMATIC_POSITIONING_SIGNAL_WAIT_MS = 10_000L

/** After stopping, how long to wait in the foreground before waiting for signals again. */
internal const val AUTOMATIC_POSITIONING_RETRY_DELAY_MS = 30_000L

/** States of the bounded automatic positioning loop. */
internal enum class AutomaticPositioningPhase {
    /** No start request has been received. */
    IDLE,

    /** Positioning was requested and the loop is waiting for scanning to start, including while a permission dialog is shown. */
    STARTING,

    /** Scanning has started and the loop is waiting for a registered beacon. */
    AWAITING_SIGNALS,

    /** The device is in the beacon area and positioning continues. */
    IN_COVERAGE,

    /** Stopped because the device is out of range or the start failed; waiting in the foreground for the next retry. */
    AWAITING_RETRY,

    /** Moved to the background. No retry timer is kept; it is recreated on returning to the foreground. */
    SUSPENDED_BACKGROUND,

    /** Stopped by `positioning.stop`. Only `positioning.start` or the host API restarts it. */
    STOPPED,
}

/** Actions the caller performs to advance the loop. */
internal sealed interface AutomaticPositioningEffect {
    /** Starts positioning, requesting OS permissions if needed. The result arrives as `positioningStarted` or `startFailed`. */
    data object Start : AutomaticPositioningEffect

    /** Releases scanning and sensors. */
    data object Stop : AutomaticPositioningEffect

    /** Cancels any running timer and sends `timerFired` after the given number of milliseconds. */
    data class ScheduleTimer(val delayMs: Long) : AutomaticPositioningEffect

    /** Cancels any running timer. */
    data object CancelTimer : AutomaticPositioningEffect
}

/**
 * A pure state machine that decides when to wait for signals and when to retry automatic positioning.
 *
 * It has no clock and no timers. The caller runs the returned [AutomaticPositioningEffect]s and sends timer
 * expiry as [timerFired], so the 10-second and 30-second decisions can be tested without real time.
 *
 * The 10-second wait starts **when scanning actually starts**. Counting from the start request would spend
 * the wait on permission dialogs and the manifest download, and report the device as out of range before
 * scanning even ran.
 */
internal class AutomaticPositioningLoop {
    var phase: AutomaticPositioningPhase = AutomaticPositioningPhase.IDLE
        private set

    private val signalWait = listOf(
        AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_SIGNAL_WAIT_MS),
    )

    /**
     * `positioning.start` from the runtime, or an explicit start from the host. Start requests are idempotent: a
     * repeated request while starting, waiting, or positioning does not reset the timer.
     */
    fun start(): List<AutomaticPositioningEffect> = when (phase) {
        AutomaticPositioningPhase.STARTING,
        AutomaticPositioningPhase.AWAITING_SIGNALS,
        AutomaticPositioningPhase.IN_COVERAGE,
        -> emptyList()
        else -> {
            phase = AutomaticPositioningPhase.STARTING
            listOf(AutomaticPositioningEffect.CancelTimer, AutomaticPositioningEffect.Start)
        }
    }

    /**
     * Scanning has started. The 10-second wait for signals starts here.
     *
     * Positioning that the host started directly with `startPositioning()` arrives here too, not only the loop's
     * own `Start`. In an automatic positioning configuration, the SDK owns the area check and the retry cycle
     * regardless of who started positioning.
     */
    fun positioningStarted(): List<AutomaticPositioningEffect> = when (phase) {
        AutomaticPositioningPhase.AWAITING_SIGNALS, AutomaticPositioningPhase.IN_COVERAGE -> emptyList()
        else -> {
            phase = AutomaticPositioningPhase.AWAITING_SIGNALS
            signalWait
        }
    }

    /** The start failed (permission denied, Bluetooth off, or manifest download failed). Retry while in the foreground. */
    fun startFailed(): List<AutomaticPositioningEffect> =
        if (phase == AutomaticPositioningPhase.STARTING) {
            phase = AutomaticPositioningPhase.AWAITING_RETRY
            listOf(AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_RETRY_DELAY_MS))
        } else {
            emptyList()
        }

    /**
     * The pending timer expired. [observedRegisteredBeacon] tells whether at least one beacon registered in the
     * manifest was received since the wait started.
     */
    fun timerFired(observedRegisteredBeacon: Boolean): List<AutomaticPositioningEffect> = when (phase) {
        AutomaticPositioningPhase.AWAITING_SIGNALS -> if (observedRegisteredBeacon) {
            // In the beacon area: stop waiting and keep positioning.
            phase = AutomaticPositioningPhase.IN_COVERAGE
            emptyList()
        } else {
            // No signal in 10 seconds means out of range. Release scanning and sensors without reporting a location,
            // guidance, or an error.
            phase = AutomaticPositioningPhase.AWAITING_RETRY
            listOf(
                AutomaticPositioningEffect.Stop,
                AutomaticPositioningEffect.ScheduleTimer(AUTOMATIC_POSITIONING_RETRY_DELAY_MS),
            )
        }
        AutomaticPositioningPhase.AWAITING_RETRY -> {
            phase = AutomaticPositioningPhase.STARTING
            listOf(AutomaticPositioningEffect.Start)
        }
        else -> emptyList()
    }

    /**
     * Moved to the background. Retries run only in the foreground, so stop the timer and wait to return.
     * The positioning client's background handling stops scanning.
     */
    fun enterBackground(): List<AutomaticPositioningEffect> = when (phase) {
        AutomaticPositioningPhase.STARTING,
        AutomaticPositioningPhase.AWAITING_SIGNALS,
        AutomaticPositioningPhase.IN_COVERAGE,
        AutomaticPositioningPhase.AWAITING_RETRY,
        -> {
            phase = AutomaticPositioningPhase.SUSPENDED_BACKGROUND
            listOf(AutomaticPositioningEffect.CancelTimer)
        }
        else -> emptyList()
    }

    /**
     * Returned from the background to the foreground. Retry immediately instead of waiting 30 seconds. This also
     * covers a stop caused by denied permission, so positioning starts if the user granted it in the Settings app.
     */
    fun returnToForeground(): List<AutomaticPositioningEffect> = when (phase) {
        AutomaticPositioningPhase.AWAITING_RETRY, AutomaticPositioningPhase.SUSPENDED_BACKGROUND -> {
            phase = AutomaticPositioningPhase.STARTING
            listOf(AutomaticPositioningEffect.CancelTimer, AutomaticPositioningEffect.Start)
        }
        else -> emptyList()
    }

    /**
     * `positioning.stop` from the runtime, or an explicit stop from the host. Stops both running positioning and
     * the retry cycle. Stop requests are not distinguished by kind.
     */
    fun stop(): List<AutomaticPositioningEffect> {
        if (phase == AutomaticPositioningPhase.STOPPED) return emptyList()
        val wasRunning = phase == AutomaticPositioningPhase.STARTING ||
            phase == AutomaticPositioningPhase.AWAITING_SIGNALS ||
            phase == AutomaticPositioningPhase.IN_COVERAGE
        phase = AutomaticPositioningPhase.STOPPED
        return if (wasRunning) {
            listOf(AutomaticPositioningEffect.CancelTimer, AutomaticPositioningEffect.Stop)
        } else {
            listOf(AutomaticPositioningEffect.CancelTimer)
        }
    }
}

/** How to handle `positioning.start` from the bridge. */
internal enum class AutomaticPositioningStartAction {
    /** `DISABLED`: reject the request. */
    REJECT,

    /** `HOST_CONTROLLED`: notify the host with `PositioningStartRequested`; the SDK does not start. */
    NOTIFY_HOST,

    /** `USER_INITIATED` with automatic positioning: run it through the bounded loop. */
    RUN_AUTOMATIC_LOOP,

    /** `USER_INITIATED` without automatic positioning: start directly as before. */
    START_DIRECTLY,
}

internal fun resolveAutomaticPositioningStartAction(
    policy: MapViewPositioningPolicy,
    isAutomaticConfiguration: Boolean,
): AutomaticPositioningStartAction = when (policy) {
    MapViewPositioningPolicy.DISABLED -> AutomaticPositioningStartAction.REJECT
    MapViewPositioningPolicy.HOST_CONTROLLED -> AutomaticPositioningStartAction.NOTIFY_HOST
    MapViewPositioningPolicy.USER_INITIATED -> if (isAutomaticConfiguration) {
        AutomaticPositioningStartAction.RUN_AUTOMATIC_LOOP
    } else {
        AutomaticPositioningStartAction.START_DIRECTLY
    }
}

/**
 * Whether positioning can start without an additional OS permission dialog.
 *
 * `AUTOMATIC_WHEN_AUTHORIZED` means "start automatically as long as no new OS dialog appears", so every
 * permission that the start could request must already be decided, not only location.
 */
internal fun canStartPositioningWithoutNewPrompt(
    capabilities: CapabilityReport,
    motionPolicy: MotionPolicy,
): Boolean {
    val authorization = capabilities.authorization
    if (authorization.location != LocationAuthorizationState.WHEN_IN_USE &&
        authorization.location != LocationAuthorizationState.ALWAYS
    ) {
        return false
    }
    // On Android 12 and later, BLUETOOTH_SCAN is a separate permission. If undecided or denied, the start shows a dialog.
    if (authorization.bluetoothScan != "notRequired" && authorization.bluetoothScan != "granted") return false
    // ACTIVITY_RECOGNITION shows a dialog only while undecided, and only on devices with a step detector.
    if (motionPolicy == MotionPolicy.PREFERRED &&
        capabilities.sensors.stepDetector &&
        authorization.motion == MotionAuthorizationState.NOT_DETERMINED
    ) {
        return false
    }
    return true
}

/** Whether the map view runs automatic positioning (`bridge.hello.payload.automaticPositioning`). */
internal fun isAutomaticPositioningConfiguration(
    trigger: MapViewPositioningStartTrigger,
    policy: MapViewPositioningPolicy,
    bleTest: Boolean,
    canStartWithoutNewPrompt: Boolean,
    hasVerifiedManifest: Boolean,
): Boolean {
    if (policy == MapViewPositioningPolicy.DISABLED) return false
    if (bleTest) return false
    if (!hasVerifiedManifest) return false
    return when (trigger) {
        MapViewPositioningStartTrigger.USER_ACTION -> false
        MapViewPositioningStartTrigger.AUTOMATIC -> true
        MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED -> canStartWithoutNewPrompt
    }
}
