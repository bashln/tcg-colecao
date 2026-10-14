package app.tcg.app.ui.card

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionItemEditScreen(
    viewModel: CollectionItemEditViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.collection_edit_saved)

    LaunchedEffect(viewModel) { viewModel.load() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collection_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            CollectionItemEditState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingState(stringResource(R.string.detail_loading))
            }
            CollectionItemEditState.Missing -> EditMessage(
                modifier = Modifier.fillMaxSize().padding(padding),
                body = stringResource(R.string.collection_edit_missing),
                actionLabel = stringResource(R.string.collection_edit_back),
                onAction = onBack,
            )
            CollectionItemEditState.FailedToLoad -> EditMessage(
                modifier = Modifier.fillMaxSize().padding(padding),
                body = stringResource(R.string.collection_edit_load_error),
                actionLabel = stringResource(R.string.collection_edit_retry),
                onAction = { scope.launch { viewModel.load() } },
            )
            is CollectionItemEditState.Ready -> {
                val ready = state as CollectionItemEditState.Ready
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).testTag(CollectionItemEditTags.LIST),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ready.card?.let { card ->
                                HeroCard(card)
                                TitleBlock(card)
                            } ?: Text(
                                text = "${ready.original.cardId}\n${stringResource(R.string.collection_edit_card_unavailable)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        VariantSelector(
                            variants = ready.card?.variants?.takeIf { it.isNotEmpty() }?.toList() ?: listOf(ready.variant),
                            selected = ready.variant,
                            onSelect = viewModel::selectVariant,
                        )
                    }
                    item {
                        ConditionSelector(ready.condition, viewModel::selectCondition)
                    }
                    item {
                        QuantityStepper(
                            quantity = ready.quantity,
                            onDecrease = { viewModel.setQuantity(ready.quantity - 1) },
                            onIncrease = { viewModel.setQuantity(ready.quantity + 1) },
                        )
                    }
                    item {
                        PricePaidEditor(
                            amount = ready.pricePaidAmount,
                            currency = ready.pricePaidCurrency,
                            onAmountChange = viewModel::setPricePaidAmount,
                            onCurrencyChange = viewModel::selectPricePaidCurrency,
                            isValid = ready.pricePaidIsValid,
                        )
                    }
                    ready.error?.let { error ->
                        item {
                            EditErrorMessage(
                                error = error,
                                onReload = { scope.launch { viewModel.load() } },
                            )
                        }
                    }
                    item {
                        Button(
                            enabled = !ready.saving && ready.pricePaidIsValid,
                            onClick = {
                                scope.launch {
                                    if (viewModel.save()) {
                                        scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                                        delay(450)
                                        onSaved()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (ready.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.collection_edit_saving))
                            } else {
                                Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.collection_edit_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

object CollectionItemEditTags {
    const val LIST = "collection_item_edit_list"
}

@Composable
private fun PricePaidEditor(
    amount: String,
    currency: Currency,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    isValid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.collection_edit_price_paid)) },
            placeholder = { Text(stringResource(R.string.collection_edit_price_hint)) },
            supportingText = {
                Text(
                    if (isValid) stringResource(R.string.collection_edit_price_help)
                    else stringResource(R.string.collection_edit_price_invalid),
                )
            },
            isError = !isValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = if (amount.isNotBlank()) {
                {
                    IconButton(onClick = { onAmountChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.collection_edit_clear_price))
                    }
                }
            } else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Currency.entries.forEach { option ->
                FilterChip(
                    selected = option == currency,
                    onClick = { onCurrencyChange(option) },
                    label = { Text(option.name) },
                )
            }
        }
    }
}

@Composable
private fun EditErrorMessage(error: CollectionItemEditError, onReload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(
                when (error) {
                    CollectionItemEditError.DUPLICATE_CONFIGURATION -> R.string.collection_edit_duplicate
                    CollectionItemEditError.CHANGED_SINCE_LOADED -> R.string.collection_edit_stale
                    CollectionItemEditError.SAVE_FAILED -> R.string.collection_edit_save_error
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error == CollectionItemEditError.CHANGED_SINCE_LOADED) {
            TextButton(onClick = onReload) { Text(stringResource(R.string.collection_edit_reload)) }
        }
    }
}

@Composable
private fun EditMessage(modifier: Modifier, body: String, actionLabel: String, onAction: () -> Unit) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmptyState(icon = Icons.Outlined.SearchOff, title = stringResource(R.string.collection_edit_title), body = body)
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
    }
}
