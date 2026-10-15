package app.tcg.app.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tcg.app.R
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState

@Composable
fun ColumnScope.GlobalMissingSection(
    state: CollectionUiState,
    onScanClick: () -> Unit,
    onRetry: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onCardSelected: (String) -> Unit,
    selectingCards: Boolean = false,
    selectedCardIds: Set<String> = emptySet(),
    onToggleCard: (String) -> Unit = {},
) {
    when (val overview = state.globalMissing) {
        GlobalMissingState.NoStartedSets -> EmptyState(
            icon = Icons.Outlined.CollectionsBookmark,
            title = stringResource(R.string.collection_missing_no_sets_title),
            body = stringResource(R.string.collection_missing_no_sets_body),
            action = {
                Button(onClick = onScanClick) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Text(stringResource(R.string.collection_empty_action), modifier = Modifier.padding(start = 8.dp))
                }
            },
            modifier = Modifier.weight(1f),
        )
        is GlobalMissingState.NotChecked -> LoadingState(
            message = stringResource(R.string.collection_missing_checking),
            modifier = Modifier.weight(1f),
        )
        is GlobalMissingState.Loading -> MissingResults(
            verified = emptyList(),
            pending = overview.pending,
            failed = overview.failed,
            distinctOwnedCards = state.distinctOwnedCards,
            savedCopies = state.savedCopies,
            query = state.query,
            onRetry = onRetry,
            onClearSearch = onClearSearch,
            onCardSelected = onCardSelected,
            selectingCards = selectingCards,
            selectedCardIds = selectedCardIds,
            onToggleCard = onToggleCard,
        )
        is GlobalMissingState.Unavailable -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.collection_missing_unavailable_title),
            body = stringResource(
                R.string.collection_missing_unavailable_body,
                state.distinctOwnedCards,
                state.savedCopies,
            ),
            action = {
                Button(onClick = { onRetry(null) }) {
                    Text(stringResource(R.string.collection_set_retry))
                }
            },
            modifier = Modifier.weight(1f),
        )
        is GlobalMissingState.Partial -> MissingResults(
            verified = overview.verified,
            pending = overview.pending,
            failed = overview.failed,
            distinctOwnedCards = state.distinctOwnedCards,
            savedCopies = state.savedCopies,
            query = state.query,
            onRetry = onRetry,
            onClearSearch = onClearSearch,
            onCardSelected = onCardSelected,
            selectingCards = selectingCards,
            selectedCardIds = selectedCardIds,
            onToggleCard = onToggleCard,
        )
        is GlobalMissingState.Complete -> MissingResults(
            verified = overview.verified,
            pending = emptyList(),
            failed = emptyList(),
            distinctOwnedCards = state.distinctOwnedCards,
            savedCopies = state.savedCopies,
            query = state.query,
            onRetry = onRetry,
            onClearSearch = onClearSearch,
            onCardSelected = onCardSelected,
            selectingCards = selectingCards,
            selectedCardIds = selectedCardIds,
            onToggleCard = onToggleCard,
        )
    }
}

@Composable
private fun ColumnScope.MissingResults(
    verified: List<MissingGroup>,
    pending: List<StartedSet>,
    failed: List<StartedSet>,
    distinctOwnedCards: Int,
    savedCopies: Int,
    query: String,
    onRetry: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onCardSelected: (String) -> Unit,
    selectingCards: Boolean,
    selectedCardIds: Set<String>,
    onToggleCard: (String) -> Unit,
) {
    val visibleGroups = verified.mapNotNull { group ->
        val matchesSet = group.set.name.contains(query, ignoreCase = true)
        val cards = group.cards.filter { card ->
            matchesSet || card.name.contains(query, ignoreCase = true) ||
                card.collectorNumber.contains(query, ignoreCase = true)
        }
        group.takeIf { cards.isNotEmpty() || (query.isBlank() && it.savedIdsOutsideCatalog.isNotEmpty()) }
            ?.copy(cards = cards)
    }
    val confirmedMissing = verified.sumOf { it.cards.size }
    val totalStarted = verified.size + pending.size + failed.size

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        item(key = "coverage") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    verified.isEmpty() -> Text(
                        stringResource(R.string.collection_missing_waiting),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    pending.isNotEmpty() || failed.isNotEmpty() -> Text(
                        pluralStringResource(
                            R.plurals.collection_missing_confirmed_partial,
                            confirmedMissing,
                            confirmedMissing,
                            verified.size,
                            totalStarted,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    confirmedMissing == 0 -> Text(
                        stringResource(R.string.collection_missing_all_complete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    else -> Text(
                        pluralStringResource(
                            R.plurals.collection_missing_confirmed_complete,
                            confirmedMissing,
                            confirmedMissing,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(R.string.collection_missing_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.collection_missing_owned_summary, distinctOwnedCards, savedCopies),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pending.isNotEmpty()) {
            item(key = "pending") {
                Text(
                    stringResource(R.string.collection_missing_pending, pending.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(pending, key = { "pending-${it.id}" }) { set ->
                Text(
                    stringResource(R.string.collection_missing_pending_set, set.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (failed.isNotEmpty()) {
            item(key = "failed-title") {
                Text(
                    stringResource(R.string.collection_missing_failed_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(failed, key = { "failed-${it.id}" }) { set ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(set.name, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    TextButton(onClick = { onRetry(set.id) }) {
                        Text(stringResource(R.string.collection_set_retry))
                    }
                }
            }
            item(key = "retry-all") {
                TextButton(onClick = { onRetry(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.collection_missing_retry_all))
                }
            }
        }
        if (visibleGroups.isEmpty()) {
            item(key = "empty-results") {
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.collection_missing_no_results_title),
                    body = if (query.isBlank()) {
                        stringResource(R.string.collection_missing_none_body)
                    } else {
                        stringResource(R.string.collection_missing_no_results, query)
                    },
                    action = if (query.isNotBlank()) {
                        { TextButton(onClick = onClearSearch) { Text(stringResource(R.string.collection_clear_search)) } }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        } else {
            visibleGroups.forEach { group ->
                item(key = "set-${group.set.id}") {
                    Text(
                        group.set.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                if (group.savedIdsOutsideCatalog.isNotEmpty() && query.isBlank()) {
                    item(key = "mismatch-${group.set.id}") {
                        Text(
                            stringResource(R.string.collection_missing_catalog_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(group.cards, key = { "${group.set.id}-${it.id}" }) { card ->
                    Card(
                        onClick = { if (selectingCards) onToggleCard(card.id) else onCardSelected(card.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.collection_missing_card_subtitle, card.collectorNumber, group.set.name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selectingCards) Checkbox(
                                checked = card.id in selectedCardIds,
                                onCheckedChange = { onToggleCard(card.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
