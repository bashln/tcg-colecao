package app.tcg.app.ui.profile

import android.net.Uri
import app.tcg.app.data.backup.BackupStatus
import app.tcg.app.data.backup.CollectionBackup
import app.tcg.app.data.backup.ImportLocalState
import app.tcg.app.data.backup.ImportPreview
import app.tcg.app.data.backup.ImportResult
import app.tcg.app.data.backup.ImportSummary
import app.tcg.app.data.backup.ImportTicket
import app.tcg.app.data.backup.ValidatedWebBackup
import app.tcg.app.testutil.FakeCollectionRepository
import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.app.testutil.testCard
import app.tcg.app.testutil.testEntry
import app.tcg.app.testutil.testPrice
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeCollectionBackup : CollectionBackup {
    val _status = MutableStateFlow<BackupStatus>(BackupStatus.Unset)
    override val status: Flow<BackupStatus> = _status

    var chosenFolder: String? = null
    var previewResult: ImportPreview = ImportPreview.Invalid("Sem arquivo")
    var confirmResult: ImportResult = ImportResult.Success(ImportSummary(1, 0, 0))

    override suspend fun chooseBackupFolder(treeUri: String) {
        chosenFolder = treeUri
        _status.value = BackupStatus.Current(treeUri, 12345L)
    }

    override suspend fun previewImport(bytes: ByteArray): ImportPreview = previewResult

    override suspend fun confirmImport(ticket: ImportTicket): ImportResult = confirmResult
}

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initial_state_is_loading_then_empty_collection_settles() = runTest {
        val viewModel = ProfileViewModel(FakeCollectionRepository())
        assertEquals(true, viewModel.state.value.loading)

        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.loading)
        assertEquals(0, viewModel.state.value.stats.totalCards)
        assertNull(viewModel.state.value.stats.invested)
    }

    @Test
    fun stats_carry_breakdowns_and_invested_profit() = runTest {
        val viewModel = ProfileViewModel(
            FakeCollectionRepository(
                listOf(
                    testEntry(
                        testCard("a", setId = "s1", prices = listOf(testPrice(3.0))),
                        qty = 2,
                        pricePaid = Money(1.0, Currency.USD),
                    ),
                    testEntry(
                        testCard("b", setId = "s2", prices = listOf(testPrice(5.0))),
                        condition = Condition.LIGHTLY_PLAYED,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val stats = viewModel.state.value.stats
        assertEquals(Money(11.0, Currency.USD), stats.totalValue)
        assertEquals(3, stats.totalCards)
        assertEquals(2, stats.distinctSets)
        assertEquals(Money(1.0, Currency.USD), stats.invested)
        assertEquals(Money(10.0, Currency.USD), stats.profit)
        assertEquals(Condition.NEAR_MINT, stats.byCondition.first().key)
    }

    @Test
    fun choose_backup_folder_updates_service_and_reflects_status() = runTest {
        val fakeBackup = FakeCollectionBackup()
        val viewModel = ProfileViewModel(FakeCollectionRepository(), fakeBackup)
        advanceUntilIdle()

        assertEquals(BackupStatus.Unset, viewModel.state.value.backupStatus)

        viewModel.chooseBackupFolder(Uri.parse("content://tree/my-backup"))
        advanceUntilIdle()

        assertEquals("content://tree/my-backup", fakeBackup.chosenFolder)
        assertTrue(viewModel.state.value.backupStatus is BackupStatus.Current)
    }

    @Test
    fun preview_import_and_confirmation_lifecycle() = runTest {
        val fakeBackup = FakeCollectionBackup()
        val ticket = ImportTicket(
            backup = ValidatedWebBackup("2026-10-18T10:00:00Z", emptyList()),
            expectedLocalState = ImportLocalState(emptyList(), emptyMap(), null, emptySet()),
            actions = emptyList(),
            summary = ImportSummary(added = 2, merged = 1, unchanged = 0),
        )
        fakeBackup.previewResult = ImportPreview.Ready(ticket)
        fakeBackup.confirmResult = ImportResult.Success(ImportSummary(2, 1, 0))
        val viewModel = ProfileViewModel(FakeCollectionRepository(), fakeBackup)
        advanceUntilIdle()

        viewModel.previewImport(byteArrayOf(1, 2, 3))
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.activePreview)
        assertEquals(2, viewModel.state.value.activePreview!!.summary.added)

        viewModel.confirmImport()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activePreview)
        assertNotNull(viewModel.state.value.importMessage)
        assertTrue(viewModel.state.value.importMessage!!.contains("2 adicionadas, 1 combinadas"))
    }

    @Test
    fun preview_import_invalid_sets_error_message() = runTest {
        val fakeBackup = FakeCollectionBackup()
        fakeBackup.previewResult = ImportPreview.Invalid("Arquivo corrompido")
        val viewModel = ProfileViewModel(FakeCollectionRepository(), fakeBackup)
        advanceUntilIdle()

        viewModel.previewImport(byteArrayOf())
        advanceUntilIdle()

        assertNull(viewModel.state.value.activePreview)
        assertEquals("Arquivo corrompido", viewModel.state.value.previewError)
    }
}
