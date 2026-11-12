package app.tcg.app.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.components.joinNonBlank
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardListDetail
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    viewModel: ListDetailViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onCardSelected: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var addingByNumber by remember { mutableStateOf(false) }
    val ready = state as? ListDetailUiState.Ready
    val message = ready?.message
    val messageText = when (message) {
        is ListDetailMessage.CardsAdded -> if (message.added == 0) {
            stringResource(R.string.lists_already_in_list)
        } else {
            pluralStringResource(R.plurals.lists_added_count, message.added, message.added)
        }
        ListDetailMessage.CardRemoved -> stringResource(R.string.lists_removed)
        ListDetailMessage.Failed -> stringResource(R.string.lists_error_generic)
        null -> null
    }

    LaunchedEffect(message) {
        if (message == null || messageText == null) return@LaunchedEffect
        if (message is ListDetailMessage.CardsAdded) {
            addingByNumber = false
            viewModel.updateBatchInput("")
        }
        snackbarHostState.showSnackbar(messageText)
        viewModel.dismissMessage()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ready?.let { it.detail.list.name } ?: stringResource(R.string.lists_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { addingByNumber = true }, enabled = ready != null) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_lists_add))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            ListDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingState(stringResource(R.string.lists_loading))
            }
            ListDetailUiState.NotFound -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.lists_detail_not_found_title),
                    body = stringResource(R.string.lists_detail_not_found),
                )
            }
            is ListDetailUiState.Ready -> ListCards(
                detail = current.detail,
                modifier = Modifier.fillMaxSize().padding(padding),
                onRemove = viewModel::removeCard,
                onAddByNumber = { addingByNumber = true },
                onCardSelected = onCardSelected,
            )
        }
    }

    if (addingByNumber && ready != null) {
        AddByNumberDialog(
            input = ready.batchInput,
            batch = ready.batch,
            onInputChange = viewModel::updateBatchInput,
            onSearch = viewModel::runBatchSearch,
            onAdd = viewModel::addBatchMatches,
            onDismiss = {
                addingByNumber = false
                viewModel.updateBatchInput("")
            },
        )
    }
}

@Composable
private fun ListCards(
    detail: CardListDetail,
    modifier: Modifier,
    onRemove: (String) -> Unit,
    onAddByNumber: () -> Unit,
    onCardSelected: (String) -> Unit,
) {
    if (detail.cards.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.lists_detail_empty_title),
                body = stringResource(R.string.lists_detail_empty_body),
                action = {
                    Button(onClick = onAddByNumber) {
                        Text(stringResource(R.string.lists_add_by_number))
                    }
                },
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(detail.cards, key = { it.id }) { card ->
                CardListRow(
                    card = card,
                    onOpen = { onCardSelected(card.id) },
                    onRemove = { onRemove(card.id) },
                )
            }
        }
    }
}

@Composable
private fun CardListRow(card: Card, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.cd_open_card), onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = stringResource(R.string.cd_card_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = joinNonBlank(card.setName, card.collectorNumber.takeIf { it.isNotBlank() }?.let { "#$it" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.lists_remove_card))
        }
    }
}

@Composable
private fun AddByNumberDialog(
    input: String,
    batch: BatchState,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.lists_add_by_number),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.detail_liga_web_close))
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    placeholder = { Text("021/084, 9/108") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.lists_add_by_number_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onSearch,
                    enabled = input.isNotBlank() && batch != BatchState.Searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.lists_search))
                }
                when (batch) {
                    BatchState.Idle -> Spacer(Modifier.weight(1f))
                    BatchState.Searching -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.lists_searching),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    is BatchState.Failed -> {
                        Text(
                            text = stringResource(
                                when (batch.reason) {
                                    BatchError.INVALID_FORMAT -> R.string.lists_batch_invalid
                                    BatchError.OFFLINE -> R.string.lists_batch_offline
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        RejectedTokens(batch.rejected)
                        Spacer(Modifier.weight(1f))
                    }
                    is BatchState.Ready -> {
                        if (batch.matched.isEmpty()) {
                            Text(
                                text = stringResource(R.string.lists_batch_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.lists_batch_matched,
                                    batch.matched.size,
                                    batch.matched.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(batch.matched, key = { it.id }) { card ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AsyncImage(
                                            model = card.imageUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(40.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = card.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = joinNonBlank(
                                                    card.setName,
                                                    card.collectorNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (batch.unmatched.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.lists_batch_unmatched,
                                    batch.unmatched.joinToString(", "),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RejectedTokens(batch.rejected)
                        if (batch.matched.isEmpty()) Spacer(Modifier.weight(1f))
                    }
                }
                if (batch is BatchState.Ready) {
                    Button(
                        onClick = onAdd,
                        enabled = batch.matched.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.lists_batch_add,
                                batch.matched.size,
                                batch.matched.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RejectedTokens(rejected: List<String>) {
    if (rejected.isEmpty()) return
    Text(
        text = stringResource(R.string.lists_batch_rejected, rejected.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
