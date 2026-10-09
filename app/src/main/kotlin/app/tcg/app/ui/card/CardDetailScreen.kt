package app.tcg.app.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun CardDetailScreen(
    viewModel: CardDetailViewModel,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val card = state.card

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )
        }
        item {
            Column {
                Text(card.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${card.setName} · ${card.collectorNumber}" +
                        (card.rarity?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = listOfNotNull(
                        card.hp?.let { "HP $it" },
                        card.types.takeIf { it.isNotEmpty() }?.joinToString(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (card.attacks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ataques", style = MaterialTheme.typography.titleMedium)
                    card.attacks.forEach { AttackRow(it) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Preços", style = MaterialTheme.typography.titleMedium)
                PriceRow("TCGplayer", state.tcgplayer)
                PriceRow("Cardmarket", state.cardmarket)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Variante", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    card.variants.forEach { variant ->
                        FilterChip(
                            selected = variant == state.variant,
                            onClick = { viewModel.selectVariant(variant) },
                            label = { Text(variantLabel(variant)) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Condição", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Condition.entries.forEach { condition ->
                        FilterChip(
                            selected = condition == state.condition,
                            onClick = { viewModel.selectCondition(condition) },
                            label = { Text(conditionLabel(condition)) },
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantidade", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { viewModel.setQuantity(state.quantity - 1) },
                    enabled = state.quantity > 1,
                ) {
                    Text("−")
                }
                Text(
                    text = state.quantity.toString(),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                OutlinedButton(onClick = { viewModel.setQuantity(state.quantity + 1) }) {
                    Text("+")
                }
            }
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        viewModel.save(state.variant, state.condition, state.quantity)
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salvar na coleção")
            }
        }
    }
}

@Composable
private fun AttackRow(attack: Attack) {
    val damage = attack.damage
    val text = attack.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(attack.cost.joinToString(), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = attack.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (damage != null) {
            Text(damage, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PriceRow(label: String, price: Money?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            text = price?.let { "%.2f ${it.currency.name}".format(it.amount) } ?: "sem preço",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun variantLabel(variant: CardVariant) = when (variant) {
    CardVariant.NORMAL -> "Normal"
    CardVariant.HOLOFOIL -> "Holo"
    CardVariant.REVERSE_HOLOFOIL -> "Reverse"
    CardVariant.FIRST_EDITION -> "1ª edição"
    CardVariant.PROMO -> "Promo"
}

private fun conditionLabel(condition: Condition) = when (condition) {
    Condition.NEAR_MINT -> "NM"
    Condition.LIGHTLY_PLAYED -> "LP"
    Condition.MODERATELY_PLAYED -> "MP"
    Condition.HEAVILY_PLAYED -> "HP"
    Condition.DAMAGED -> "DMG"
}
