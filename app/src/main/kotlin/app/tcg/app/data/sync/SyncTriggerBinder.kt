package app.tcg.app.data.sync

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Translates real app events into sync triggers. It owns no sync policy — it only maps a
 * collection write, a return to the foreground, and a network recovery onto the lifecycle's
 * trigger methods. The initial emission of each flow is skipped: a cold start is not a
 * fresh write, a foreground transition, or a recovery, so it must not force a sync.
 */
class SyncTriggerBinder(
    private val collectionWrites: Flow<*>,
    private val isForeground: Flow<Boolean>,
    private val networkAvailable: Flow<Boolean>,
    private val onLocalWrite: () -> Unit,
    private val onForeground: () -> Unit,
    private val onNetworkAvailable: () -> Unit,
) {
    /** Collects every trigger until the calling scope is cancelled. */
    suspend fun bind() = coroutineScope {
        launch {
            collectionWrites.drop(1).collect { onLocalWrite() }
        }
        launch {
            isForeground.drop(1).distinctUntilChanged()
                .onEach { if (it) onForeground() }
                .launchIn(this)
        }
        launch {
            networkAvailable.drop(1).distinctUntilChanged()
                .onEach { if (it) onNetworkAvailable() }
                .launchIn(this)
        }
    }
}
