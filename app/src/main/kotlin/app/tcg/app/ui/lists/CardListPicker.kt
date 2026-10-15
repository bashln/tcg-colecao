package app.tcg.app.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardListKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardListPicker(
    cards: List<Card>,
    repository: CardListRepository,
    onDismiss: () -> Unit,
) {
    val lists by remember(repository) { repository.observeLists() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val operationError = stringResource(R.string.lists_operation_error)
    val addSuccess = stringResource(R.string.lists_add_success)
    val alreadyAdded = stringResource(R.string.lists_add_existing)
    val doneLabel = stringResource(R.string.lists_done)
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CardListKind.WANTED) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lists_assign_title, cards.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_cancel)) }
            }
            Text(cards.take(3).joinToString { it.name } + if (cards.size > 3) "…" else "")
            if (lists.isEmpty()) Text(stringResource(R.string.lists_empty_picker))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(lists, key = { it.list.id }) { summary ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = summary.list.id in checked,
                            enabled = !busy,
                            onCheckedChange = { selected ->
                                checked = if (selected) checked + summary.list.id else checked - summary.list.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(summary.list.name)
                            Text(
                                "${kindLabel(summary.list.kind)} · ${stringResource(R.string.collection_count, summary.cardCount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.lists_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CardListKind.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = kind == option,
                        onClick = { kind = option },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, CardListKind.entries.size),
                        enabled = !busy,
                    ) {
                        Text(kindLabel(option))
                    }
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val created = repository.create(name, kind)
                            checked = checked + created.id
                            name = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = if (failure is IllegalArgumentException) failure.message ?: operationError else operationError
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && name.isNotBlank(),
            ) { Text(stringResource(R.string.lists_create)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(
                onClick = {
                    if (result != null) {
                        onDismiss()
                    } else {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                val outcome = repository.addCards(cards, checked)
                                result = if (outcome.addedMemberships == 0) alreadyAdded else addSuccess
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = if (failure is IllegalArgumentException) failure.message ?: operationError else operationError
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy && (result != null || checked.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text(if (result != null) doneLabel else stringResource(R.string.lists_add_to_count, checked.size))
                }
            }
        }
    }
}

@Composable
fun kindLabel(kind: CardListKind): String = stringResource(
    if (kind == CardListKind.WANTED) R.string.lists_wanted else R.string.lists_missing,
)
