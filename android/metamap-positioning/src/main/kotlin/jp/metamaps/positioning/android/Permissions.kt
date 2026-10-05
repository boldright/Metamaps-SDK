@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package jp.metamaps.positioning.android

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Android runtime permissions that positioning and beacon ranging need. */
@MetamapInternalApi
object PermissionRequirements {
    fun bleRuntime(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun motionRuntime(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        else emptyList()

    fun ensureDeclared(context: Context, motionPolicy: MotionPolicy) {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions.orEmpty().toSet()
        val required = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (motionPolicy == MotionPolicy.PREFERRED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        val missing = required.filterNot(declared::contains)
        if (missing.isNotEmpty()) {
            throw MetamapPositioningError.invalidConfiguration(
                "Host manifest is missing permissions: ${missing.joinToString()}",
            )
        }
    }

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun locationState(context: Context): LocationAuthorizationState {
        if (isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return LocationAuthorizationState.WHEN_IN_USE
        }
        return if (wasRequested(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            LocationAuthorizationState.DENIED
        } else {
            LocationAuthorizationState.NOT_DETERMINED
        }
    }

    fun bluetoothScanState(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "notRequired"
        return when {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) -> "granted"
            wasRequested(context, Manifest.permission.BLUETOOTH_SCAN) -> "denied"
            else -> "notDetermined"
        }
    }

    fun motionState(context: Context): MotionAuthorizationState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MotionAuthorizationState.GRANTED
        return when {
            isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION) -> MotionAuthorizationState.GRANTED
            wasRequested(context, Manifest.permission.ACTIVITY_RECOGNITION) -> MotionAuthorizationState.DENIED
            else -> MotionAuthorizationState.NOT_DETERMINED
        }
    }

    fun markRequested(context: Context, permissions: Collection<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().apply {
            permissions.forEach { putBoolean(it, true) }
        }.apply()
    }

    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean =
        wasRequested(activity, permission) &&
            !isGranted(activity, permission) &&
            !activity.shouldShowRequestPermissionRationale(permission)

    private fun wasRequested(context: Context, permission: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(permission, false)

    private const val PREFERENCES = "jp.metamaps.positioning.permissions"
}

/** Requests Android runtime permissions from an [Activity] one batch at a time. */
@MetamapInternalApi
object ActivityPermissionRequester {
    private val requestMutex = Mutex()

    suspend fun request(activity: Activity, permissions: List<String>): Map<String, Boolean> =
        requestMutex.withLock {
            requestInternal(activity, permissions)
        }

    private suspend fun requestInternal(
        activity: Activity,
        permissions: List<String>,
    ): Map<String, Boolean> {
        val pending = permissions.distinct().filterNot { PermissionRequirements.isGranted(activity, it) }
        if (pending.isEmpty()) return permissions.associateWith { true }
        PermissionRequirements.markRequested(activity, pending)
        return suspendCancellableCoroutine { continuation ->
            val fragment = PermissionRequestFragment().apply {
                requestedPermissions = pending.toTypedArray()
                onResult = { result ->
                    if (continuation.isActive) {
                        continuation.resume(
                            completePermissionResults(permissions, result) { permission ->
                                PermissionRequirements.isGranted(activity, permission)
                            },
                        )
                    }
                }
            }
            val finishDenied: () -> Unit = {
                if (continuation.isActive) {
                    continuation.resume(permissions.associateWith { permission ->
                        PermissionRequirements.isGranted(activity, permission)
                    })
                }
            }
            val attach: () -> Unit = {
                if (!continuation.isActive) {
                    Unit
                } else if (activity.isFinishing || activity.isDestroyed) {
                    finishDenied()
                } else {
                    runCatching {
                        activity.fragmentManager.findFragmentByTag(FRAGMENT_TAG)?.let { orphan ->
                            activity.fragmentManager.beginTransaction()
                                .remove(orphan)
                                .commitNowAllowingStateLoss()
                        }
                        activity.fragmentManager.beginTransaction()
                            .add(fragment, FRAGMENT_TAG)
                            .commitNowAllowingStateLoss()
                    }.onFailure {
                        finishDenied()
                    }
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) attach() else activity.runOnUiThread(attach)
            continuation.invokeOnCancellation {
                fragment.onResult = null
                if (fragment.isAdded) {
                    activity.runOnUiThread {
                        if (fragment.isAdded) {
                            activity.fragmentManager.beginTransaction()
                                .remove(fragment)
                                .commitAllowingStateLoss()
                        }
                    }
                }
            }
        }
    }

    private const val FRAGMENT_TAG = "jp.metamaps.positioning.permission"
}

/**
 * Android's permission callback returns only the permissions requested from the OS this time. Permissions that
 * were already granted and not requested are not treated as denied; their current grant state fills in the result.
 */
internal fun completePermissionResults(
    requestedPermissions: List<String>,
    callbackResults: Map<String, Boolean>,
    isGranted: (String) -> Boolean,
): Map<String, Boolean> = requestedPermissions.associateWith { permission ->
    callbackResults[permission] ?: isGranted(permission)
}

class PermissionRequestFragment : Fragment() {
    var requestedPermissions: Array<String> = emptyArray()
    var onResult: ((Map<String, Boolean>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onResume() {
        super.onResume()
        if (requestedPermissions.isNotEmpty() && !requested) {
            requested = true
            requestPermissions(requestedPermissions, REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val result = permissions.indices.associate { index ->
            permissions[index] to (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED)
        }
        onResult?.invoke(result)
        onResult = null
        if (isAdded) fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
    }

    private var requested = false

    private companion object {
        const val REQUEST_CODE = 0x4D50
    }
}
