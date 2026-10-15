package app.tcg.app.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.CardListKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CardListsOverview(repository: CardListRepository, onOpen: (String) -> Unit) {
    val flow = remember(repository) { repository.observeLists() }
    val lists by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val operationError = stringResource(R.string.lists_operation_error)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.lists_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = { creating = true }) { Text(stringResource(R.string.lists_create)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (lists.isEmpty()) Text(stringResource(R.string.lists_empty_overview))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lists, key = { it.list.id }) { summary ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(summary.list.id) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(summary.list.name, style = MaterialTheme.typography.titleMedium)
                            Text("${kindLabel(summary.list.kind)} · ${stringResource(R.string.collection_count, summary.cardCount)}")
                        }
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.lists_open))
                    }
                }
            }
        }
    }
    if (creating) ListNameDialog(
        title = stringResource(R.string.lists_create), withKind = true,
        onDismiss = { creating = false },
        onSave = { name, kind ->
            scope.launch {
                try { repository.create(name, kind); creating = false; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: operationError else operationError }
            }
        },
    )
}

@Composable
fun CardListDetailScreen(
    listId: String, repository: CardListRepository,
    onBack: () -> Unit, onCardOpen: (String) -> Unit,
) {
    val flow = remember(repository, listId) { repository.observeList(listId) }
    val detail by flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val operationError = stringResource(R.string.lists_operation_error)
    val current = detail
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.cd_back)) }
        if (current == null) {
            Text(stringResource(R.string.lists_loading))
        } else {
            Text(current.list.name, style = MaterialTheme.typography.headlineSmall)
            Text(kindLabel(current.list.kind))
            Row {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.lists_rename)) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.lists_delete)) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (current.cards.isEmpty()) Text(stringResource(R.string.lists_empty_detail))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(current.cards, key = { it.id }) { card ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onCardOpen(card.id) }) {
                                Text(card.name, style = MaterialTheme.typography.titleMedium)
                                Text("${card.setName} · ${card.collectorNumber}")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try { repository.removeCard(listId, card.id); error = null }
                                    catch (e: CancellationException) { throw e }
                                     catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: operationError else operationError }
                                }
                            }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.lists_remove_card)) }
                        }
                    }
                }
            }
        }
    }
    if (renaming && current != null) ListNameDialog(
        title = stringResource(R.string.lists_rename), initialName = current.list.name,
        onDismiss = { renaming = false },
        onSave = { name, _ ->
            scope.launch {
                try { repository.rename(listId, name); renaming = false; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: operationError else operationError }
            }
        },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.lists_delete)) },
        text = { Text(stringResource(R.string.lists_delete_confirm)) },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                try { repository.delete(listId); deleting = false; onBack() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: operationError else operationError; deleting = false }
            }
        }) { Text(stringResource(R.string.lists_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.lists_cancel)) } },
    )
}

@Composable
private fun ListNameDialog(
    title: String, initialName: String = "", withKind: Boolean = false,
    onDismiss: () -> Unit, onSave: (String, CardListKind) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf(CardListKind.WANTED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(stringResource(R.string.lists_name)) }, singleLine = true)
                if (withKind) CardListKind.entries.forEach { option ->
                    TextButton(onClick = { kind = option }) { Text((if (kind == option) "✓ " else "") + kindLabel(option)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, kind) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.lists_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_cancel)) } },
    )
}
