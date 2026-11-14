package app.tcg.app.data.backup

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRecoveryStoreTest {

    private fun entry(id: String) = WebEntry(
        item = WebItem(
            id = id,
            cardId = "base1-4",
            quantity = 1,
            variant = CardVariant.NORMAL,
            condition = Condition.NEAR_MINT,
            pricePaid = null,
            addedAt = 1000L,
            revision = 1L,
        ),
        card = WebCard(
            id = "base1-4",
            name = "Charizard",
            setId = "base1",
            setName = "Base Set",
            collectorNumber = "4",
            imageUrl = null,
            variants = listOf(CardVariant.NORMAL),
            prices = emptyList(),
            setTotal = 102,
        ),
    )

    private fun tempDir(): File = Files.createTempDirectory("recovery").toFile()

    private fun copies(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.name.startsWith("recovery-") }

    @Test
    fun `only the newest recovery copy survives a second write`() = runTest {
        val directory = tempDir()
        val store = FileRecoveryStore(directory)

        assertTrue(store.writeAndVerifyRecovery(listOf(entry("item-0001"))))
        assertTrue(store.writeAndVerifyRecovery(listOf(entry("item-0002"))))

        val copies = copies(directory)
        assertEquals(1, copies.size)
        assertEquals(
            listOf("item-0002"),
            WebBackupAdapter.parse(copies.single().readBytes()).entries.map { it.item.id },
        )
        directory.deleteRecursively()
    }

    @Test
    fun `discarding removes every recovery copy`() = runTest {
        val directory = tempDir()
        val store = FileRecoveryStore(directory)
        assertTrue(store.writeAndVerifyRecovery(listOf(entry("item-0001"))))

        store.discardRecovery()

        assertEquals(emptyList<File>(), copies(directory))
        directory.deleteRecursively()
    }
}
