package jp.metamaps.positioning.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class ForegroundRecoveryCoordinatorTests {
    @Test
    fun hostAndLifecycleShareOneSuccessfulForegroundRecovery() = runBlocking {
        val coordinator = ForegroundRecoveryCoordinator(this)
        val recoveryEntered = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        var recoveryRequired = true
        var recoveryCount = 0

        suspend fun recover(): Boolean = coordinator.recoverIfNeeded(
            isRequired = { recoveryRequired },
            prepare = {
                recoveryCount++
                recoveryEntered.complete(Unit)
                finishRecovery.await()
            },
            complete = {},
            markRecovered = { recoveryRequired = false },
        )

        val host = async(Dispatchers.Default) { recover() }
        recoveryEntered.await()
        val lifecycle = async(Dispatchers.Default) { recover() }
        finishRecovery.complete(Unit)

        assertTrue(host.await())
        assertFalse(lifecycle.await())
        assertEquals(1, recoveryCount)
    }

    @Test
    fun explicitStopCancelsRecoveryBeforeHardwareStarts() = runBlocking {
        val coordinator = ForegroundRecoveryCoordinator(this)
        val recoveryEntered = CompletableDeferred<Unit>()
        val finishConfigure = CompletableDeferred<Unit>()
        var recoveryRequired = true
        var hardwareStarted = false
        var markedRecovered = false

        val recovery = async(Dispatchers.Default) {
            coordinator.recoverIfNeeded(
                isRequired = { recoveryRequired },
                prepare = {
                    recoveryEntered.complete(Unit)
                    finishConfigure.await()
                },
                complete = { hardwareStarted = true },
                markRecovered = { markedRecovered = true },
            )
        }

        recoveryEntered.await()
        recoveryRequired = false
        coordinator.cancel()
        finishConfigure.complete(Unit)

        assertTrue(runCatching { recovery.await() }.exceptionOrNull() is CancellationException)
        assertFalse(hardwareStarted)
        assertFalse(markedRecovered)
    }

    @Test
    fun cancellingOneWaiterKeepsTheSharedRecoveryActive() = runBlocking {
        val recoveryJob = Job()
        val recoveryScope = CoroutineScope(recoveryJob + Dispatchers.Default)
        val coordinator = ForegroundRecoveryCoordinator(recoveryScope)
        val recoveryEntered = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        var recoveryRequired = true
        var recoveryCount = 0

        suspend fun recover(): Boolean = coordinator.recoverIfNeeded(
            isRequired = { recoveryRequired },
            prepare = {
                recoveryCount++
                recoveryEntered.complete(Unit)
                finishRecovery.await()
            },
            complete = {},
            markRecovered = { recoveryRequired = false },
        )

        val owner = async(Dispatchers.Default) { recover() }
        recoveryEntered.await()
        val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { recover() }
        cancelledWaiter.cancelAndJoin()
        val remainingWaiter = async(start = CoroutineStart.UNDISPATCHED) { recover() }
        finishRecovery.complete(Unit)

        assertTrue(owner.await())
        assertFalse(remainingWaiter.await())
        assertEquals(1, recoveryCount)
        recoveryJob.cancel()
    }
}
