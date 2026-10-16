package jp.metamaps.mapview

import android.Manifest
import android.app.Activity
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction

/** A map feature that needs an Android runtime permission from your app. */
enum class MetamapsMapPermission(internal val androidPermissions: List<String>) {
    /** GPS location in the map. Android 12 and later need fine and coarse location requested together. */
    LOCATION(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),

    /** Voice input in the map's search field and AI chat. */
    MICROPHONE(listOf(Manifest.permission.RECORD_AUDIO)),
}

/**
 * A request from the map for a permission that your app declares but has not been granted yet. The map waits until
 * you answer it once, on the main thread: call [request] to show the OS permission dialog, request
 * [androidPermissions] yourself, for example with an `ActivityResultLauncher`, and then call [finish], or call
 * [decline] to leave the permission as it is, for example when the user turned down your own explanation. Later calls
 * do nothing. In every case the map view reads the permission state from the OS; you do not pass the result.
 *
 * If the user denies the permission, the map view emits [MetamapsMapViewEvent.Error] with a `userAction` that tells
 * your app which guidance to show. The map itself shows no settings guidance.
 */
class MetamapsMapPermissionRequest internal constructor(
    /** The map feature that asks for the permission. */
    val permission: MetamapsMapPermission,
    private val onRequest: (Activity) -> Unit,
    private val onFinish: () -> Unit,
    private val onDecline: () -> Unit,
) {
    private var answered = false

    /** The Android permissions to request together. */
    val androidPermissions: List<String> get() = permission.androidPermissions

    /** Shows the OS permission dialog from [activity] and answers this request with the result. */
    fun request(activity: Activity) {
        if (answered) return
        answered = true
        onRequest(activity)
    }

    /** Answers this request after your app requested the permissions itself, whatever the user chose. */
    fun finish() {
        if (answered) return
        answered = true
        onFinish()
    }

    /** Answers this request without asking the OS. The map's request is denied unless the permission is granted. */
    fun decline() {
        if (answered) return
        answered = true
        onDecline()
    }
}

/** The app's state for a map permission, read from its manifest and the OS. */
internal enum class MapPermissionStatus {
    GRANTED,

    /** Only approximate location is granted. GPS in the map needs precise location. */
    APPROXIMATE_ONLY,
    DENIED,

    /** The app's manifest does not declare the permission, so the OS would deny any request without a dialog. */
    NOT_DECLARED,
}

internal fun mapPermissionStatus(
    permission: MetamapsMapPermission,
    isDeclared: (String) -> Boolean,
    isGranted: (String) -> Boolean,
): MapPermissionStatus = when (permission) {
    MetamapsMapPermission.LOCATION -> when {
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> MapPermissionStatus.GRANTED
        !permission.androidPermissions.all(isDeclared) -> MapPermissionStatus.NOT_DECLARED
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> MapPermissionStatus.APPROXIMATE_ONLY
        else -> MapPermissionStatus.DENIED
    }
    MetamapsMapPermission.MICROPHONE -> when {
        isGranted(Manifest.permission.RECORD_AUDIO) -> MapPermissionStatus.GRANTED
        !isDeclared(Manifest.permission.RECORD_AUDIO) -> MapPermissionStatus.NOT_DECLARED
        else -> MapPermissionStatus.DENIED
    }
}

/**
 * Whether a new request for the [missing] permissions can still show the OS dialog. Right after a request
 * ([requestedNow]), Android's rationale flag answers it. Without a request now, the flag is also `false` for a
 * permission that was never requested, so only an earlier request that left no rationale means the dialog is gone.
 */
internal fun canAskForMapPermissionAgain(
    missing: List<String>,
    requestedNow: Boolean,
    showsRationale: (String) -> Boolean,
    wasRequestedBefore: (String) -> Boolean,
): Boolean = if (requestedNow) {
    missing.any(showsRationale)
} else {
    missing.none { wasRequestedBefore(it) && !showsRationale(it) }
}

/**
 * The error the map view emits when it denies a map permission request. [canAskAgain] is whether a new request can
 * still show the OS dialog; when it cannot, the user has to change the permission in the app's settings.
 */
internal fun mapPermissionDeniedError(
    permission: MetamapsMapPermission,
    status: MapPermissionStatus,
    canAskAgain: Boolean,
): MetamapsError {
    val code = when {
        status == MapPermissionStatus.APPROXIMATE_ONLY -> MetamapsError.Code.PRECISE_LOCATION_REQUIRED
        permission == MetamapsMapPermission.LOCATION -> MetamapsError.Code.LOCATION_PERMISSION_DENIED
        else -> MetamapsError.Code.MICROPHONE_PERMISSION_DENIED
    }
    val message = when (code) {
        MetamapsError.Code.PRECISE_LOCATION_REQUIRED -> "GPS location in the map needs precise location permission."
        MetamapsError.Code.LOCATION_PERMISSION_DENIED -> "GPS location in the map needs location permission."
        else -> "Voice input in the map needs microphone permission."
    }
    if (status == MapPermissionStatus.NOT_DECLARED) {
        return MetamapsError(
            code,
            message,
            false,
            MetamapsUserAction.NONE,
            "${permission.androidPermissions.joinToString()} not declared in the app manifest",
        )
    }
    val action = when {
        !canAskAgain -> MetamapsUserAction.OPEN_APP_SETTINGS
        status == MapPermissionStatus.APPROXIMATE_ONLY -> MetamapsUserAction.ENABLE_PRECISE_LOCATION
        else -> MetamapsUserAction.REQUEST_PERMISSION
    }
    return MetamapsError(code, message, canAskAgain, action, permission.androidPermissions.joinToString())
}
