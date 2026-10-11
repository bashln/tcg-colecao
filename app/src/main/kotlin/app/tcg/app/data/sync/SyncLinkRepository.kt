package app.tcg.app.data.sync

import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.SyncBindingEntity

/**
 * Persists which account (identity) this device is linked to, plus a stable device id used as
 * the sync `deviceId`. Confirming a link stores it; unlinking clears it. The local collection
 * is never touched here — linking is metadata only, so sign-out/unlink always preserves data.
 */
class SyncLinkRepository(
    private val dao: CollectionDao,
    private val deviceId: String,
) {
    suspend fun link(identity: String) {
        dao.setSyncBinding(SyncBindingEntity(slot = 0, identity = identity, deviceId = deviceId))
    }

    suspend fun unlink() {
        dao.clearSyncBinding()
    }

    suspend fun currentIdentity(): String? = dao.getSyncBinding()?.identity

    fun deviceId(): String = deviceId
}
