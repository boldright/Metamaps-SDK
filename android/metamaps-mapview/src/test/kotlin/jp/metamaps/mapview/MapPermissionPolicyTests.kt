package jp.metamaps.mapview

import android.Manifest
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decides which map permission requests reach the host, and which guidance a denial asks the host to show. */
class MapPermissionPolicyTests {
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    private val microphone = Manifest.permission.RECORD_AUDIO

    private fun status(
        permission: MetamapsMapPermission,
        declared: Set<String> = setOf(fine, coarse, microphone),
        granted: Set<String> = emptySet(),
    ) = mapPermissionStatus(permission, declared::contains, granted::contains)

    @Test
    fun asksTheHostOnlyForADeclaredPermissionThatIsNotGranted() {
        assertEquals(MapPermissionStatus.DENIED, status(MetamapsMapPermission.LOCATION))
        assertEquals(MapPermissionStatus.DENIED, status(MetamapsMapPermission.MICROPHONE))
        assertEquals(MapPermissionStatus.GRANTED, status(MetamapsMapPermission.LOCATION, granted = setOf(fine, coarse)))
        // GPS in the map cannot use approximate location, so the host is asked to upgrade it.
        assertEquals(
            MapPermissionStatus.APPROXIMATE_ONLY,
            status(MetamapsMapPermission.LOCATION, granted = setOf(coarse)),
        )
        // Android 12 and later ignore a fine location request without coarse location.
        assertEquals(
            MapPermissionStatus.NOT_DECLARED,
            status(MetamapsMapPermission.LOCATION, declared = setOf(fine)),
        )
        assertEquals(
            MapPermissionStatus.NOT_DECLARED,
            status(MetamapsMapPermission.MICROPHONE, declared = setOf(fine, coarse)),
        )
    }

    @Test
    fun aDenialTellsTheHostWhetherToAskAgainOrOpenTheAppSettings() {
        fun denied(permission: MetamapsMapPermission, status: MapPermissionStatus, canAskAgain: Boolean) =
            mapPermissionDeniedError(permission, status, canAskAgain).let { Triple(it.code, it.userAction, it.recoverable) }

        assertEquals(
            Triple(MetamapsError.Code.LOCATION_PERMISSION_DENIED, MetamapsUserAction.REQUEST_PERMISSION, true),
            denied(MetamapsMapPermission.LOCATION, MapPermissionStatus.DENIED, canAskAgain = true),
        )
        assertEquals(
            Triple(MetamapsError.Code.MICROPHONE_PERMISSION_DENIED, MetamapsUserAction.OPEN_APP_SETTINGS, false),
            denied(MetamapsMapPermission.MICROPHONE, MapPermissionStatus.DENIED, canAskAgain = false),
        )
        assertEquals(
            Triple(MetamapsError.Code.PRECISE_LOCATION_REQUIRED, MetamapsUserAction.ENABLE_PRECISE_LOCATION, true),
            denied(MetamapsMapPermission.LOCATION, MapPermissionStatus.APPROXIMATE_ONLY, canAskAgain = true),
        )
        // An app that does not declare the permission chose not to offer the feature; there is nothing to guide.
        val undeclared = mapPermissionDeniedError(
            MetamapsMapPermission.MICROPHONE,
            MapPermissionStatus.NOT_DECLARED,
            canAskAgain = true,
        )
        assertEquals(MetamapsUserAction.NONE, undeclared.userAction)
        assertFalse(undeclared.recoverable)
    }

    /** Android also reports no rationale for a permission that was never requested, which is not a final denial. */
    @Test
    fun aPermissionNeverRequestedCanStillBeAskedForWhenTheHostDeclines() {
        fun canAsk(requestedNow: Boolean, rationale: Boolean, requestedBefore: Boolean) = canAskForMapPermissionAgain(
            listOf(microphone),
            requestedNow,
            showsRationale = { rationale },
            wasRequestedBefore = { requestedBefore },
        )

        assertTrue(canAsk(requestedNow = false, rationale = false, requestedBefore = false))
        assertFalse(canAsk(requestedNow = false, rationale = false, requestedBefore = true))
        assertTrue(canAsk(requestedNow = true, rationale = true, requestedBefore = true))
        assertFalse(canAsk(requestedNow = true, rationale = false, requestedBefore = true))
    }
}
