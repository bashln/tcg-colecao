package app.tcg.app.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.PricePaidEditor
import app.tcg.app.ui.components.PriceRow
import app.tcg.app.ui.components.SectionHeader
import app.tcg.app.ui.components.conditionLabel
import app.tcg.app.ui.components.joinNonBlank
import app.tcg.app.ui.components.variantLabel
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    viewModel: CardDetailViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.detail_saved)
    val keptPurchaseAmountMessage = stringResource(R.string.detail_saved_purchase_amount_kept)
    val saveErrorMessage = stringResource(R.string.detail_save_error)
    val card = state.card

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(CardDetailTags.LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { HeroCard(card) }
                item { TitleBlock(card) }
                item { AttackSection(card) }
                item { PriceSection(state.tcgplayer, state.cardmarket) }
                item {
                    VariantSelector(
                        variants = card.variants.ifEmpty { setOf(state.variant) }.toList(),
                        selected = state.variant,
                        onSelect = viewModel::selectVariant,
                    )
                }
                item {
                    ConditionSelector(
                        selected = state.condition,
                        onSelect = viewModel::selectCondition,
                    )
                }
                item {
                    QuantityStepper(
                        quantity = state.quantity,
                        onDecrease = { viewModel.setQuantity(state.quantity - 1) },
                        onIncrease = { viewModel.setQuantity(state.quantity + 1) },
                    )
                }
                item {
                    PricePaidEditor(
                        draft = state.pricePaid,
                        onAmountChange = viewModel::setPricePaidAmount,
                        onCurrencyChange = viewModel::selectPricePaidCurrency,
                    )
                }
                item {
                    Button(
                        onClick = {
                            if (saving) return@Button
                            scope.launch {
                                saving = true
                                try {
                                    viewModel.save(state.variant, state.condition, state.quantity)
                                    snackbarHostState.showSnackbar(
                                        if (viewModel.state.value.purchaseAmountNotApplied) keptPurchaseAmountMessage else savedMessage,
                                    )
                                    delay(600)
                                    onSaved()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    snackbarHostState.showSnackbar(saveErrorMessage)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving && state.pricePaid.isValid,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.detail_saving))
                        } else {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.detail_save))
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@Composable
internal fun HeroCard(card: Card) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = stringResource(R.string.cd_card_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(20.dp),
        )
    }
}

@Composable
internal fun TitleBlock(card: Card) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.name, style = MaterialTheme.typography.headlineSmall)
        val subtitle = joinNonBlank(card.setName, card.collectorNumber)
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            card.rarity?.let { InfoChip(it) }
            card.hp?.let { InfoChip(stringResource(R.string.detail_hp, it)) }
            card.types.forEach { type -> InfoChip(type) }
        }
    }
}

@Composable
private fun AttackSection(card: Card) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.detail_attacks))
        if (card.attacks.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_no_attacks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    card.attacks.forEach { AttackRow(it) }
                }
            }
        }
    }
}

@Composable
private fun PriceSection(tcgplayer: app.tcg.core.domain.model.Money?, cardmarket: app.tcg.core.domain.model.Money?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.detail_prices))
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PriceRow(stringResource(R.string.common_price_tcgplayer), tcgplayer)
                PriceRow(stringResource(R.string.common_price_cardmarket), cardmarket)
            }
        }
    }
}

@Composable
internal fun VariantSelector(
    variants: List<CardVariant>,
    selected: CardVariant,
    onSelect: (CardVariant) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.detail_variant))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            variants.forEach { variant ->
                FilterChip(
                    selected = variant == selected,
                    onClick = { onSelect(variant) },
                    label = { Text(variantLabel(variant)) },
                )
            }
        }
    }
}

@Composable
internal fun ConditionSelector(
    selected: Condition,
    onSelect: (Condition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.detail_condition))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Condition.entries.forEach { condition ->
                FilterChip(
                    selected = condition == selected,
                    onClick = { onSelect(condition) },
                    label = { Text(conditionLabel(condition)) },
                )
            }
        }
    }
}

@Composable
internal fun QuantityStepper(
    quantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.detail_quantity))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(onClick = onDecrease, enabled = quantity > 1) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cd_decrease_quantity))
            }
            Text(quantity.toString(), style = MaterialTheme.typography.headlineSmall)
            FilledTonalIconButton(onClick = onIncrease) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_increase_quantity))
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AttackRow(attack: Attack) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attack.cost.isNotEmpty()) {
                Text(attack.cost.joinToString(), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = attack.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            attack.damage?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        attack.text?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Stable handles so instrumentation tests can scroll the detail list without UI coupling. */
object CardDetailTags {
    const val LIST = "card_detail_list"
}
