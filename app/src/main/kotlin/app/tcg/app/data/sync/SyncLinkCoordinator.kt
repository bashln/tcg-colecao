package app.tcg.app.data.sync

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** What the account screen renders for a first-link preview. */
data class LinkPreviewSummary(
    val plan: LinkPreview,
    val localOnlyCount: Int,
    val cloudOnlyCount: Int,
    val identicalCount: Int,
    val conflictingCount: Int,
    val duplicateCount: Int,
)

sealed interface LinkOutcome {
    data class Linked(val highWater: Long) : LinkOutcome
    /** The preview still blocks confirmation: the person must resolve the rows first. */
    data object Blocked : LinkOutcome
    /** The local collection changed after the preview; nothing was sent. */
    data object LocalChanged : LinkOutcome
    /** Commit may have reached the server, but this device could not finish local binding. */
    data class Partial(val outcomes: List<PushOutcome> = emptyList()) : LinkOutcome
    data class Refused(val reason: LinkCommitResult) : LinkOutcome
    data class Failed(val message: String) : LinkOutcome
}

/** First-link flow the account screen drives: a read-only preview, then an explicit confirm. */
interface SyncLinkCoordinator {
    suspend fun preparePreview(): LinkPreviewSummary
    suspend fun confirm(identity: String, plan: LinkPreview): LinkOutcome
}

/**
 * First-link flow for Android, mirroring the web contract exactly: preview is read-only,
 * confirmation re-checks the local digest before any upload, remote-only cards are
 * resolved before the commit so a catalog miss cannot half-link, and the local write is
 * the same atomic completion the web uses. Every refusal leaves local data untouched.
 */
class RoomSyncLinkCoordinator(
    private val transport: SyncLinkTransport,
    private val store: RoomSyncLocalStore,
    private val dao: CollectionDao,
    private val cardDao: CardDao,
    private val cards: CardRepository,
    private val deviceId: String,
) : SyncLinkCoordinator {
    override suspend fun preparePreview(): LinkPreviewSummary {
        val items = localSyncItems()
        val plan = transport.prepareLink(items)
        return LinkPreviewSummary(
            plan = plan,
            localOnlyCount = plan.localOnly.size,
            cloudOnlyCount = plan.cloudOnly.size,
            identicalCount = plan.identical.size,
            conflictingCount = plan.conflicting.size,
            duplicateCount = plan.duplicateConfigurations.size,
        )
    }

    override suspend fun confirm(identity: String, plan: LinkPreview): LinkOutcome {
        if (plan.conflicting.isNotEmpty() || plan.duplicateConfigurations.isNotEmpty()) {
            return LinkOutcome.Blocked
        }

        val current = localSyncItems()
        if (syncSnapshotDigest(current) != plan.digest) return LinkOutcome.LocalChanged

        // Resolve every remote-only card before the commit so a catalog miss can never
        // leave the server seeded with rows this device refused to import.
        val currentIds = current.map { it.id }.toSet()
        val remoteOnly = plan.remoteItems.filter { it.item.id !in currentIds }
        for (remote in remoteOnly) {
            val card = cards.getCard(remote.item.cardId)
            if (card === null || card.id != remote.item.cardId) {
                return LinkOutcome.Failed("Não foi possível carregar os dados da carta ${remote.item.cardId} para importar da nuvem. Verifique a conexão e tente novamente.")
            }
            cardDao.cacheCard(card)
        }

        val byId = current.associateBy { it.id }
        val operations = plan.localOnly.mapNotNull { itemId ->
            val item = byId[itemId] ?: return@mapNotNull null
            SyncOperation.Create(opId = UUID.randomUUID().toString(), deviceId = deviceId, item = item)
        }

        val commit = try {
            transport.commitLink(plan, deviceId, operations)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SupabaseSyncException) {
            return LinkOutcome.Failed(error.message ?: "Não foi possível confirmar o vínculo")
        } catch (error: Exception) {
            return LinkOutcome.Failed("A resposta da nuvem não chegou e ela pode já ter recebido itens da prévia. Gere uma nova prévia e confira qualquer conflito.")
        }
        if (commit is LinkCommitResult.Partial) return LinkOutcome.Partial(commit.outcomes)
        if (commit !is LinkCommitResult.Linked) return LinkOutcome.Refused(commit)

        val seeds = LinkedHashMap<String, Long>()
        for ((index, operation) in operations.withIndex()) {
            when (val outcome = commit.outcomes.getOrNull(index)) {
                is PushOutcome.Applied -> seeds[operation.item.id] = outcome.sequence
                is PushOutcome.AlreadyApplied -> seeds[operation.item.id] = outcome.sequence
                else -> return LinkOutcome.Partial(commit.outcomes)
            }
        }

        val linked = store.completeLink(
            LinkCompletion(
                identity = identity,
                deviceId = deviceId,
                expectedItems = current,
                remoteItems = plan.remoteItems,
                seedRevisions = seeds,
                highWater = commit.highWater,
            ),
        )
        return if (linked) LinkOutcome.Linked(commit.highWater) else LinkOutcome.Partial(commit.outcomes)
    }

    private suspend fun localSyncItems(): List<SyncItem> = dao.listItems().map { it.toSyncItem() }
}

private fun CollectionItemEntity.toSyncItem() = SyncItem(
    id = id,
    cardId = cardId,
    quantity = quantity,
    variant = variant,
    condition = condition,
    pricePaid = pricePaidAmount?.let { amount ->
        Money(amount = amount, currency = enumValueOrNull<Currency>(pricePaidCurrency ?: return@let null) ?: return@let null)
    },
    addedAt = addedAt,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
    name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
