package app.tcg.app.ui.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.CollectionItemCard
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.components.TotalValueHero
import app.tcg.app.ui.components.conditionLabel
import app.tcg.app.ui.components.variantLabel
import app.tcg.app.ui.lists.CardListDetailScreen
import app.tcg.app.ui.lists.CardListPicker
import app.tcg.app.ui.lists.CardListsOverview
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.Card
import coil.compose.AsyncImage

@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    cardListRepository: CardListRepository,
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit = {},
    onCardSelected: (String) -> Unit = {},
    onCollectionItemSelected: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedSet = state.selectedSet
    var selectedListId by remember { mutableStateOf<String?>(null) }
    var selectingCards by remember { mutableStateOf(false) }
    var selectedCardIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showListPicker by remember { mutableStateOf(false) }
    val selectedSetId = when (selectedSet) {
        SelectedSetState.Closed -> null
        is SelectedSetState.Loading -> selectedSet.setId
        is SelectedSetState.Ready -> selectedSet.catalog.setId
        is SelectedSetState.Unavailable -> selectedSet.setId
    }
    val availableCards = buildList {
        addAll(state.entries.map { it.card })
        addAll(state.setCards.map { it.card })
        when (val missing = state.globalMissing) {
            is GlobalMissingState.Complete -> addAll(missing.verified.flatMap { it.cards })
            is GlobalMissingState.Partial -> addAll(missing.verified.flatMap { it.cards })
            else -> Unit
        }
    }.distinctBy(Card::id)
    val selectedCards = availableCards.filter { it.id in selectedCardIds }
    val visibleSelectionIds = when {
        state.section == CollectionSection.CARDS && state.cardsLens == CardsLens.OWNED ->
            state.entries.mapTo(linkedSetOf()) { it.card.id }
        state.section == CollectionSection.CARDS -> {
            val groups = when (val missing = state.globalMissing) {
                is GlobalMissingState.Complete -> missing.verified
                is GlobalMissingState.Partial -> missing.verified
                else -> emptyList()
            }
            groups.flatMapTo(linkedSetOf()) { group ->
                val matchesSet = group.set.name.contains(state.query, ignoreCase = true)
                group.cards.filter { card ->
                    matchesSet || card.name.contains(state.query, ignoreCase = true) ||
                        card.collectorNumber.contains(state.query, ignoreCase = true)
                }.map { it.id }
            }
        }
        selectedSet is SelectedSetState.Ready -> state.setCards.mapTo(linkedSetOf()) { it.card.id }
        else -> emptySet()
    }

    androidx.compose.runtime.LaunchedEffect(state.section, state.cardsLens, state.query, selectedSetId, selectedListId) {
        selectingCards = false
        selectedCardIds = emptySet()
    }

    if (selectedListId != null) {
        BackHandler { selectedListId = null }
        CardListDetailScreen(
            listId = selectedListId!!,
            repository = cardListRepository,
            onBack = { selectedListId = null },
            onCardOpen = onCardSelected,
        )
        return
    }

    BackHandler(enabled = selectedSet != SelectedSetState.Closed) {
        viewModel.onCloseSet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedSet == SelectedSetState.Closed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.collection_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.section != CollectionSection.LISTS &&
                        (state.section == CollectionSection.CARDS || selectedSet is SelectedSetState.Ready)
                    ) {
                        TextButton(
                            onClick = {
                                selectingCards = !selectingCards
                                selectedCardIds = emptySet()
                            },
                        ) {
                            Text(stringResource(if (selectingCards) R.string.collection_selection_cancel else R.string.collection_selection_start))
                        }
                    }
                }
                if (state.section == CollectionSection.CARDS && state.cardsLens == CardsLens.OWNED) {
                    TotalValueHero(label = stringResource(R.string.collection_total), total = state.total)
                }
                TabRow(
                    selectedTabIndex = when (state.section) {
                        CollectionSection.CARDS -> 0
                        CollectionSection.SETS -> 1
                        CollectionSection.LISTS -> 2
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Tab(
                        selected = state.section == CollectionSection.CARDS,
                        onClick = { viewModel.onSection(CollectionSection.CARDS) },
                        text = { Text(stringResource(R.string.collection_cards)) },
                    )
                    Tab(
                        selected = state.section == CollectionSection.SETS,
                        onClick = { viewModel.onSection(CollectionSection.SETS) },
                        text = { Text(stringResource(R.string.collection_sets)) },
                    )
                    Tab(
                        selected = state.section == CollectionSection.LISTS,
                        onClick = { viewModel.onSection(CollectionSection.LISTS) },
                        text = { Text(stringResource(R.string.collection_lists)) },
                    )
                }
                if (state.section == CollectionSection.CARDS) {
                    TabRow(
                        selectedTabIndex = if (state.cardsLens == CardsLens.OWNED) 0 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Tab(
                            selected = state.cardsLens == CardsLens.OWNED,
                            onClick = { viewModel.onCardsLens(CardsLens.OWNED) },
                            text = { Text(stringResource(R.string.collection_lens_owned)) },
                        )
                        Tab(
                            selected = state.cardsLens == CardsLens.MISSING,
                            onClick = { viewModel.onCardsLens(CardsLens.MISSING) },
                            text = { Text(stringResource(R.string.collection_lens_missing)) },
                        )
                    }
                }
                if (state.section != CollectionSection.LISTS) OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    label = {
                        Text(
                            stringResource(
                                if (state.section == CollectionSection.CARDS && state.cardsLens == CardsLens.MISSING) {
                                    R.string.collection_missing_search
                                } else if (state.section == CollectionSection.CARDS) {
                                    R.string.collection_search_label
                                } else {
                                    R.string.collection_sets_search
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQuery("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selectingCards && state.section != CollectionSection.LISTS) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.collection_selection_count, selectedCardIds.size))
                            TextButton(onClick = {
                                selectedCardIds = if (selectedCardIds.containsAll(visibleSelectionIds)) {
                                    selectedCardIds - visibleSelectionIds
                                } else {
                                    selectedCardIds + visibleSelectionIds
                                }
                            }, enabled = visibleSelectionIds.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        if (visibleSelectionIds.isNotEmpty() && selectedCardIds.containsAll(visibleSelectionIds)) {
                                            R.string.collection_deselect_all_visible
                                        } else {
                                            R.string.collection_select_all_visible
                                        },
                                    ),
                                )
                            }
                        }
                        Button(
                            onClick = { showListPicker = true },
                            enabled = selectedCards.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.scan_add_to_lists))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::onCloseSet) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_close_set),
                        )
                    }
                    Text(
                        text = state.selectedSetName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        when {
            state.loading && state.entries.isEmpty() -> Centered { LoadingState(stringResource(R.string.collection_loading)) }
            state.section == CollectionSection.CARDS && state.cardsLens == CardsLens.OWNED -> CardsSection(
                state = state,
                onScanClick = onScanClick,
                onClearSearch = { viewModel.onQuery("") },
                onCollectionItemSelected = onCollectionItemSelected,
                selectingCards = selectingCards,
                selectedCardIds = selectedCardIds,
                onToggleCard = { id -> selectedCardIds = if (id in selectedCardIds) selectedCardIds - id else selectedCardIds + id },
            )
            state.section == CollectionSection.CARDS -> GlobalMissingSection(
                state = state,
                onScanClick = onScanClick,
                onRetry = viewModel::onRetryMissingCatalogs,
                onClearSearch = { viewModel.onQuery("") },
                onCardSelected = onCardSelected,
                selectingCards = selectingCards,
                selectedCardIds = selectedCardIds,
                onToggleCard = { id -> selectedCardIds = if (id in selectedCardIds) selectedCardIds - id else selectedCardIds + id },
            )
            state.section == CollectionSection.LISTS -> CardListsOverview(
                repository = cardListRepository,
                onOpen = { selectedListId = it },
            )
            selectedSet == SelectedSetState.Closed -> SetsSection(
                state = state,
                onSetSelected = viewModel::onSetSelected,
                onClearSearch = { viewModel.onQuery("") },
            )
            else -> SelectedSetSection(
                state = state,
                onFilter = viewModel::onOwnershipFilter,
                onRetry = viewModel::onRetryCatalog,
                onCardSelected = onCardSelected,
                onCollectionItemSelected = onCollectionItemSelected,
                selectingCards = selectingCards,
                selectedCardIds = selectedCardIds,
                onToggleCard = { id -> selectedCardIds = if (id in selectedCardIds) selectedCardIds - id else selectedCardIds + id },
            )
        }
    }
    if (showListPicker && selectedCards.isNotEmpty()) {
        CardListPicker(
            cards = selectedCards,
            repository = cardListRepository,
            onDismiss = {
                showListPicker = false
                selectingCards = false
                selectedCardIds = emptySet()
            },
        )
    }
}

@Composable
private fun ColumnScope.CardsSection(
    state: CollectionUiState,
    onScanClick: () -> Unit,
    onClearSearch: () -> Unit,
    onCollectionItemSelected: (String) -> Unit,
    selectingCards: Boolean,
    selectedCardIds: Set<String>,
    onToggleCard: (String) -> Unit,
) {
    when {
        state.entries.isEmpty() && state.query.isBlank() -> Centered {
            EmptyState(
                icon = Icons.Outlined.CollectionsBookmark,
                title = stringResource(R.string.collection_empty_title),
                body = stringResource(R.string.collection_empty_body),
                action = {
                    Button(onClick = onScanClick) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.collection_empty_action))
                    }
                },
            )
        }
        state.entries.isEmpty() -> Centered {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.collection_no_results_title),
                body = stringResource(R.string.collection_no_results, state.query),
                action = { TextButton(onClick = onClearSearch) { Text(stringResource(R.string.collection_clear_search)) } },
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(state.entries, key = { it.item.id }) { entry ->
                Box {
                    CollectionItemCard(
                        entry = entry,
                        modifier = if (selectingCards) Modifier else Modifier.clickable(
                            onClickLabel = stringResource(R.string.cd_open_card),
                            role = Role.Button,
                            onClick = { onCollectionItemSelected(entry.item.id) },
                        ),
                    )
                    if (selectingCards) {
                        val selectionDescription = stringResource(R.string.collection_select_card_action, entry.card.name)
                        Checkbox(
                            checked = entry.card.id in selectedCardIds,
                            onCheckedChange = { onToggleCard(entry.card.id) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).semantics {
                                contentDescription = selectionDescription
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SetsSection(
    state: CollectionUiState,
    onSetSelected: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    when {
        state.ownedSets.isEmpty() && state.query.isBlank() -> Centered {
            EmptyState(
                icon = Icons.Outlined.CollectionsBookmark,
                title = stringResource(R.string.collection_sets_empty_title),
                body = stringResource(R.string.collection_sets_empty_body),
            )
        }
        state.ownedSets.isEmpty() -> Centered {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.collection_no_results_title),
                body = stringResource(R.string.collection_no_results, state.query),
                action = { TextButton(onClick = onClearSearch) { Text(stringResource(R.string.collection_clear_search)) } },
            )
        }
        else -> androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            columnItems(state.ownedSets, key = { it.id }) { set ->
                Card(
                    onClick = { onSetSelected(set.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(set.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            pluralStringResource(R.plurals.collection_set_owned_count, set.ownedCards, set.ownedCards),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SelectedSetSection(
    state: CollectionUiState,
    onFilter: (OwnershipFilter) -> Unit,
    onRetry: () -> Unit,
    onCardSelected: (String) -> Unit,
    onCollectionItemSelected: (String) -> Unit,
    selectingCards: Boolean,
    selectedCardIds: Set<String>,
    onToggleCard: (String) -> Unit,
) {
    when (val catalogState = state.selectedSet) {
        SelectedSetState.Closed -> Unit
        is SelectedSetState.Loading -> {
            LoadingState(stringResource(R.string.collection_set_loading))
            OwnedSetCards(state.ownedSetEntries, onCollectionItemSelected)
        }
        is SelectedSetState.Unavailable -> {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.collection_set_unavailable_title),
                body = stringResource(R.string.collection_set_unavailable_body),
                action = { Button(onClick = onRetry) { Text(stringResource(R.string.collection_set_retry)) } },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OwnedSetCards(state.ownedSetEntries, onCollectionItemSelected)
        }
        is SelectedSetState.Ready -> {
            val catalogSize = catalogState.catalog.cards.size
            val ownedCount = catalogState.catalog.cards.count { row ->
                state.ownedSetEntries.any { it.card.id == row.id }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.collection_set_progress, ownedCount, catalogSize),
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(
                    progress = { ownedCount.toFloat() / catalogSize },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OwnershipFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.ownershipFilter == filter,
                            onClick = { onFilter(filter) },
                            label = {
                                Text(
                                    stringResource(
                                        when (filter) {
                                            OwnershipFilter.ALL -> R.string.collection_set_filter_all
                                            OwnershipFilter.OWNED -> R.string.collection_set_filter_owned
                                            OwnershipFilter.MISSING -> R.string.collection_set_filter_missing
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (state.setCards.isEmpty() && state.ownershipFilter == OwnershipFilter.MISSING) {
                Centered {
                    EmptyState(
                        icon = Icons.Outlined.CollectionsBookmark,
                        title = stringResource(R.string.collection_set_complete_title),
                        body = stringResource(R.string.collection_set_complete_body),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(state.setCards, key = { it.card.id }) { row ->
                        CatalogCardTile(
                            row = row,
                            ownedEntries = state.ownedSetEntries.filter { it.card.id == row.card.id },
                            onAddCard = { onCardSelected(row.card.id) },
                            onCollectionItemSelected = onCollectionItemSelected,
                            selectingCards = selectingCards,
                            selected = row.card.id in selectedCardIds,
                            onToggleCard = { onToggleCard(row.card.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.OwnedSetCards(
    entries: List<app.tcg.core.domain.model.CollectionEntry>,
    onCollectionItemSelected: (String) -> Unit,
) {
    if (entries.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        items(entries, key = { it.item.id }) { entry ->
            CollectionItemCard(
                entry = entry,
                modifier = Modifier.clickable(
                    onClickLabel = stringResource(R.string.cd_open_card),
                    role = Role.Button,
                    onClick = { onCollectionItemSelected(entry.item.id) },
                ),
            )
        }
    }
}

@Composable
private fun CatalogCardTile(
    row: SetCardRow,
    ownedEntries: List<app.tcg.core.domain.model.CollectionEntry>,
    onAddCard: () -> Unit,
    onCollectionItemSelected: (String) -> Unit,
    selectingCards: Boolean,
    selected: Boolean,
    onToggleCard: () -> Unit,
) {
    var showOwnedChoices by remember(row.card.id) { mutableStateOf(false) }
    val onClick = {
        if (!row.isOwned) {
            onAddCard()
        } else {
            when (ownedEntries.size) {
                1 -> onCollectionItemSelected(ownedEntries.single().item.id)
                in 2..Int.MAX_VALUE -> showOwnedChoices = true
                else -> Unit
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(
            onClickLabel = stringResource(
                if (selectingCards) R.string.collection_select_card_action else R.string.collection_set_card_action,
                row.card.name,
            ),
            role = Role.Button,
            onClick = { if (selectingCards) onToggleCard() else onClick() },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AsyncImage(
                model = row.card.imageUrl,
                contentDescription = stringResource(R.string.cd_card_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(8.dp).aspectRatio(5f / 7f),
            )
            if (selectingCards) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleCard() },
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
                )
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(row.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(row.card.collectorNumber.takeIf(String::isNotBlank), row.card.rarity).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (row.isOwned) {
                        stringResource(R.string.collection_set_owned, row.ownedQuantity)
                    } else {
                        stringResource(R.string.collection_set_missing)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.isOwned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
    if (showOwnedChoices) {
        AlertDialog(
            onDismissRequest = { showOwnedChoices = false },
            title = { Text(stringResource(R.string.collection_edit_choose_copy)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ownedEntries.forEach { entry ->
                        TextButton(
                            onClick = {
                                showOwnedChoices = false
                                onCollectionItemSelected(entry.item.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${variantLabel(entry.item.variant)} · ${conditionLabel(entry.item.condition)} · ×${entry.item.quantity}",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOwnedChoices = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
}
