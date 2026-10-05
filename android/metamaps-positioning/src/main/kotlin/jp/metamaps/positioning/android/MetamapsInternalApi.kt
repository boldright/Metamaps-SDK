package jp.metamaps.positioning.android

import jp.metamaps.positioning.ReplayEvent

/**
 * Marks APIs that only Metamaps tooling uses. They are not part of the public SDK contract and
 * may change or disappear in any release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Metamaps tooling API. It is not part of the public SDK contract.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class MetamapsInternalApi

/**
 * Options that only Metamaps tooling sets.
 *
 * @property bleTest Requests the map's BLE test draft manifest and opens the map with `?bletest=1`.
 *   BLE test mode always enables beacon diagnostics.
 * @property diagnosticEventHandler Receives every normalized BLE, motion, and lifecycle event before
 *   it reaches the estimator. The handler must return promptly.
 */
@MetamapsInternalApi
data class MetamapsInternalOptions(
    val bleTest: Boolean = false,
    val diagnosticEventHandler: ((ReplayEvent) -> Unit)? = null,
)
