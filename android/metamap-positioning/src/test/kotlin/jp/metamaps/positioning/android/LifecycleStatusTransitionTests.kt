package jp.metamaps.positioning.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LifecycleStatusTransitionTests {
    @Test
    fun foregroundRecoveryDoesNotPublishAcquiringTwice() {
        assertNull(
            lifecycleTransitionFromEstimate(
                PositioningLifecycleStatus.RECOVERING,
                PositioningLifecycleStatus.ACQUIRING.wireValue,
            ),
        )
        assertEquals(
            PositioningLifecycleStatus.TRACKING,
            lifecycleTransitionFromEstimate(
                PositioningLifecycleStatus.RECOVERING,
                PositioningLifecycleStatus.TRACKING.wireValue,
            ),
        )
    }
}
