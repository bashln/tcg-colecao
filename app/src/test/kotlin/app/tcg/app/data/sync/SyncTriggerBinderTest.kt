package app.tcg.app.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5: the binder connects real app events to the lifecycle's trigger methods. It owns
 * no sync policy — it only translates a collection write, a foreground transition, and a
 * network recovery into `onLocalWrite` / `onForeground` / `onNetworkAvailable`, and it
 * must ignore the initial emission of a flow (that is not a fresh write).
 */
class SyncTriggerBinderTest {

    @Test
    fun `a collection write emission fires onLocalWrite`() = runTest {
        val writes = MutableStateFlow(0)
        val fired = mutableListOf<String>()
        val binder = SyncTriggerBinder(
            collectionWrites = writes,
            isForeground = MutableStateFlow(true),
            networkAvailable = MutableStateFlow(true),
            onLocalWrite = { fired += "write" },
            onForeground = { fired += "foreground" },
            onNetworkAvailable = { fired += "network" },
        )
        val job = startBinder(binder)
        runCurrent()

        writes.value = 1
        runCurrent()

        job.cancel()
        assertEquals(listOf("write"), fired)
    }

    @Test
    fun `the initial collection emission is not treated as a write`() = runTest {
        val writes = MutableStateFlow(7)
        val fired = mutableListOf<String>()
        val binder = SyncTriggerBinder(
            collectionWrites = writes,
            isForeground = MutableStateFlow(true),
            networkAvailable = MutableStateFlow(true),
            onLocalWrite = { fired += "write" },
            onForeground = { fired += "foreground" },
            onNetworkAvailable = { fired += "network" },
        )
        val job = startBinder(binder)
        runCurrent()

        job.cancel()
        assertTrue("the seed emission must not trigger a sync", fired.isEmpty())
    }

    @Test
    fun `returning to the foreground fires onForeground`() = runTest {
        val foreground = MutableStateFlow(false)
        val fired = mutableListOf<String>()
        val binder = SyncTriggerBinder(
            collectionWrites = MutableStateFlow(0),
            isForeground = foreground,
            networkAvailable = MutableStateFlow(true),
            onLocalWrite = { fired += "write" },
            onForeground = { fired += "foreground" },
            onNetworkAvailable = { fired += "network" },
        )
        val job = startBinder(binder)
        runCurrent()

        foreground.value = true
        runCurrent()

        job.cancel()
        assertEquals(listOf("foreground"), fired)
    }

    @Test
    fun `network recovery fires onNetworkAvailable but staying online does not`() = runTest {
        val network = MutableStateFlow(false)
        val fired = mutableListOf<String>()
        val binder = SyncTriggerBinder(
            collectionWrites = MutableStateFlow(0),
            isForeground = MutableStateFlow(true),
            networkAvailable = network,
            onLocalWrite = { fired += "write" },
            onForeground = { fired += "foreground" },
            onNetworkAvailable = { fired += "network" },
        )
        val job = startBinder(binder)
        runCurrent()

        network.value = true
        runCurrent()
        network.value = true
        runCurrent()

        job.cancel()
        assertEquals(listOf("network"), fired)
    }

    private fun CoroutineScope.startBinder(binder: SyncTriggerBinder) = launch { binder.bind() }
}
