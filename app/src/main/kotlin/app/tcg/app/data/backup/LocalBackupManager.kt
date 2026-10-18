package app.tcg.app.data.backup

import app.tcg.app.data.local.BackupGenerationEntity
import app.tcg.app.data.local.CollectionDao

sealed interface BackupExecutionResult {
    data class Success(val savedRevision: Long, val totalGenerations: Int) : BackupExecutionResult
    data class Failure(val reason: String) : BackupExecutionResult
    data object NoOp : BackupExecutionResult
}

class LocalBackupManager(
    private val dao: CollectionDao,
    private val store: BackupDocumentStore,
) {
    suspend fun runBackup(): BackupExecutionResult {
        val state = dao.getBackupState() ?: return BackupExecutionResult.NoOp
        val treeUri = state.treeUri ?: return BackupExecutionResult.NoOp

        if (state.pendingRevision <= state.savedRevision) {
            return BackupExecutionResult.NoOp
        }

        val revisionToSave = state.pendingRevision
        val items = dao.listItems()
        val cards = dao.listAllCards().associateBy { it.id }
        val prices = dao.listAllPrices().groupBy { it.cardId }

        val entries = items.map { item ->
            val card = cards[item.cardId] ?: return fail("Carta não encontrada no banco: ${item.cardId}")
            val cardPrices = prices[item.cardId] ?: emptyList()
            WebBackupAdapter.fromRoom(item, card, cardPrices)
        }

        val bytes = try {
            WebBackupAdapter.encode(entries)
        } catch (ex: Exception) {
            return fail("Falha ao codificar backup: ${ex.message}")
        }
        val expectedSha = WebBackupAdapter.digest(bytes)

        val existing = dao.listGenerations(treeUri)
        val copiesToCreate = if (existing.isEmpty()) 3 else 1

        val timestamp = System.currentTimeMillis()
        for (i in 1..copiesToCreate) {
            val name = "tcg-colecao-$timestamp-$i.json"
            val docUri = store.writeDocument(treeUri, name, bytes)
                ?: return fail("Não foi possível criar o arquivo de backup")

            val readBack = store.readDocument(docUri)
                ?: return fail("Falha ao reabrir o arquivo gravado")

            val actualSha = WebBackupAdapter.digest(readBack)
            if (actualSha != expectedSha) {
                return fail("Divergência de integridade (SHA-256)")
            }

            try {
                WebBackupAdapter.parse(readBack)
            } catch (ex: Exception) {
                return fail("Arquivo gravado inválido: ${ex.message}")
            }

            dao.insertGeneration(
                BackupGenerationEntity(
                    documentUri = docUri,
                    treeUri = treeUri,
                    sha256 = actualSha,
                    createdAt = timestamp + i,
                )
            )
        }

        val updatedGenerations = dao.listGenerations(treeUri)
        var retentionWarning: String? = null
        if (updatedGenerations.size > 3) {
            val toRemove = updatedGenerations.drop(3)
            for (gen in toRemove) {
                val currentBytes = store.readDocument(gen.documentUri)
                if (currentBytes != null && WebBackupAdapter.digest(currentBytes) == gen.sha256) {
                    val deleted = store.deleteDocument(gen.documentUri)
                    if (deleted) {
                        dao.deleteGeneration(gen.documentUri)
                    } else {
                        retentionWarning = "Falha ao remover cópia antiga ${gen.documentUri}"
                    }
                } else {
                    dao.deleteGeneration(gen.documentUri)
                }
            }
        }

        val rowsUpdated = dao.markBackupSaved(revisionToSave, retentionWarning)
        val finalGenerations = dao.listGenerations(treeUri).size
        return if (rowsUpdated > 0) {
            BackupExecutionResult.Success(revisionToSave, finalGenerations)
        } else {
            BackupExecutionResult.NoOp
        }
    }

    private suspend fun fail(reason: String): BackupExecutionResult.Failure {
        dao.setBackupFailure(reason)
        return BackupExecutionResult.Failure(reason)
    }
}
