package app.tcg.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.data.backup.BackupStatus
import app.tcg.app.ui.components.BreakdownRow
import app.tcg.app.ui.components.CollectionKeyNumbers
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.components.SectionHeader
import app.tcg.app.ui.components.TotalValueHero
import app.tcg.app.ui.components.conditionLabel
import app.tcg.app.ui.components.formatMoney
import app.tcg.app.ui.components.variantLabel
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.stats.CollectionStats
import app.tcg.core.domain.stats.StatBucket

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onUpdatesClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.chooseBackupFolder(uri)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                viewModel.previewImport(bytes)
            }
        }
    }

    if (state.activePreview != null) {
        val summary = state.activePreview!!.summary
        AlertDialog(
            onDismissRequest = { viewModel.cancelPreview() },
            title = { Text(stringResource(R.string.profile_import_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.profile_import_preview_added, summary.added))
                    Text(stringResource(R.string.profile_import_preview_merged, summary.merged))
                    Text(stringResource(R.string.profile_import_preview_unchanged, summary.unchanged))
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmImport() }) {
                    Text(stringResource(R.string.profile_import_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelPreview() }) {
                    Text(stringResource(R.string.profile_import_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onAccountClick,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.account_title))
            }
            OutlinedButton(
                onClick = onUpdatesClick,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_title))
            }
        }

        Spacer(Modifier.height(12.dp))

        BackupCard(
            status = state.backupStatus,
            importMessage = state.importMessage,
            previewError = state.previewError,
            onChooseFolder = { folderLauncher.launch(null) },
            onImportClick = { fileLauncher.launch(arrayOf("application/json", "*/*")) },
            onDismissMessages = { viewModel.dismissMessages() },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(stringResource(R.string.profile_loading))
            }
            state.stats.totalCards == 0 -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = stringResource(R.string.profile_empty_title),
                    body = stringResource(R.string.profile_empty_body),
                    action = {
                        Button(onClick = onScanClick) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_empty_action))
                        }
                    },
                )
            }
            else -> Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ProfileContent(state.stats)
            }
        }
    }
}

@Composable
private fun BackupCard(
    status: BackupStatus,
    importMessage: String?,
    previewError: String?,
    onChooseFolder: () -> Unit,
    onImportClick: () -> Unit,
    onDismissMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.profile_backup_section_title),
                style = MaterialTheme.typography.titleMedium,
            )

            val statusText = when (status) {
                is BackupStatus.Unset -> stringResource(R.string.profile_backup_status_unset)
                is BackupStatus.Pending -> stringResource(R.string.profile_backup_status_pending)
                is BackupStatus.Current -> stringResource(R.string.profile_backup_status_current) + (status.warning?.let { " ($it)" } ?: "")
                is BackupStatus.Failed -> stringResource(R.string.profile_backup_status_failed, status.reason)
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is BackupStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onChooseFolder,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (status is BackupStatus.Unset) R.string.profile_backup_folder_choose else R.string.profile_backup_folder_change
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onImportClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_import_button))
                }
            }

            if (importMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = importMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessages) {
                        Text("OK")
                    }
                }
            }

            if (previewError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.profile_import_error, previewError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessages) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(stats: CollectionStats) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TotalValueHero(label = stringResource(R.string.profile_total_value), total = stats.totalValue)
        }
        item { CollectionKeyNumbers(stats) }
        stats.invested?.let { invested ->
            item { InvestmentCard(invested = invested, profit = stats.profit) }
        }
        if (stats.byCondition.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_condition),
                    buckets = stats.byCondition,
                    totalCards = stats.totalCards,
                ) { conditionLabel(it) }
            }
        }
        if (stats.byVariant.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_variant),
                    buckets = stats.byVariant,
                    totalCards = stats.totalCards,
                ) { variantLabel(it) }
            }
        }
        if (stats.byRarity.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_rarity),
                    buckets = stats.byRarity,
                    totalCards = stats.totalCards,
                ) { rarity -> rarity ?: stringResource(R.string.profile_rarity_unknown) }
            }
        }
    }
}

@Composable
private fun InvestmentCard(invested: Money, profit: Money?) {
    val isProfit = (profit?.amount ?: 0.0) >= 0.0
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MoneyRow(
                label = stringResource(R.string.profile_invested),
                value = invested,
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            MoneyRow(
                label = stringResource(if (isProfit) R.string.profile_profit else R.string.profile_loss),
                value = profit,
                valueColor = if (isProfit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MoneyRow(label: String, value: Money?, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatMoney(value) ?: stringResource(R.string.common_no_price),
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun <K> BreakdownSection(
    title: String,
    buckets: List<StatBucket<K>>,
    totalCards: Int,
    label: @Composable (K) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                buckets.forEach { bucket ->
                    BreakdownRow(
                        label = label(bucket.key),
                        count = bucket.count,
                        share = if (totalCards > 0) bucket.count.toFloat() / totalCards else 0f,
                    )
                }
            }
        }
    }
}
