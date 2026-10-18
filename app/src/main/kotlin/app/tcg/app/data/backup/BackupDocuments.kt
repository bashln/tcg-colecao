package app.tcg.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

interface BackupDocumentStore {
    suspend fun writeDocument(treeUri: String, name: String, bytes: ByteArray): String?
    suspend fun readDocument(documentUri: String): ByteArray?
    suspend fun deleteDocument(documentUri: String): Boolean
}

class SafBackupDocumentStore(context: Context) : BackupDocumentStore {
    private val resolver = context.contentResolver

    override suspend fun writeDocument(treeUri: String, name: String, bytes: ByteArray): String? = runCatching {
        val tree = Uri.parse(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val doc = DocumentsContract.createDocument(resolver, parentUri, "application/json", name) ?: return null
        resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) } ?: return null
        doc.toString()
    }.getOrNull()

    override suspend fun readDocument(documentUri: String): ByteArray? = runCatching {
        resolver.openInputStream(Uri.parse(documentUri))?.use { it.readBytes() }
    }.getOrNull()

    override suspend fun deleteDocument(documentUri: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, Uri.parse(documentUri))
    }.getOrDefault(false)
}

interface RecoveryStore {
    suspend fun writeAndVerifyRecovery(entries: List<WebEntry>): Boolean
}

class FileRecoveryStore(private val directory: File) : RecoveryStore {
    override suspend fun writeAndVerifyRecovery(entries: List<WebEntry>): Boolean = runCatching {
        if (!directory.exists()) directory.mkdirs()
        val bytes = WebBackupAdapter.encode(entries)
        val file = File(directory, "recovery-${System.currentTimeMillis()}.json")
        file.writeBytes(bytes)
        val readBack = file.readBytes()
        val parsed = WebBackupAdapter.parse(readBack)
        parsed.entries.size == entries.size
    }.getOrDefault(false)
}
