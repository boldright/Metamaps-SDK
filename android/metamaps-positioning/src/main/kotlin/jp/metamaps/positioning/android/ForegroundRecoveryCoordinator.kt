package jp.metamaps.positioning.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

/** Serializes competing foreground recoveries from the map view and the application lifecycle into one session. */
internal class ForegroundRecoveryCoordinator(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var activeRecovery: Deferred<Unit>? = null
    private var generation = 0L

    suspend fun recoverIfNeeded(
        isRequired: () -> Boolean,
        prepare: suspend () -> Unit,
        complete: () -> Unit,
        markRecovered: () -> Unit,
    ): Boolean {
        val (recovery, initiated) = synchronized(lock) {
            activeRecovery?.let { return@synchronized it to false }
            if (!isRequired()) return false
            val recoveryGeneration = generation
            val created = scope.async(start = CoroutineStart.LAZY) {
                prepare()
                coroutineContext.ensureActive()
                synchronized(lock) {
                    if (generation != recoveryGeneration) {
                        throw CancellationException("foreground recovery was cancelled")
                    }
                    complete()
                    markRecovered()
                }
            }
            created.invokeOnCompletion {
                synchronized(lock) {
                    if (activeRecovery === created) activeRecovery = null
                }
            }
            activeRecovery = created
            created to true
        }
        recovery.await()
        return initiated
    }

    /** Discards the recovery after an explicit stop, so a pending manifest download does not go on to start scanning. */
    fun cancel() {
        synchronized(lock) {
            generation++
            activeRecovery?.cancel()
            activeRecovery = null
        }
    }
}
