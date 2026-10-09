package app.tcg.app.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import coil.compose.AsyncImage

@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Coleção", style = MaterialTheme.typography.headlineSmall)
                Text(formatMoney(state.total), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = { Text("Buscar por nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.entries.isEmpty()) {
            Text(
                text = "Nenhuma carta na coleção.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.entries, key = { it.item.id }) { entry ->
                    CollectionCard(entry)
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(entry: CollectionEntry) {
    val card = entry.card
    val item = entry.item

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Text(
            text = card.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = card.setName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "${variantLabel(item.variant)} · ${conditionLabel(item.condition)} · x${item.quantity}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = formatMoney(Valuation.itemValue(card, item.variant, PriceSource.TCGPLAYER)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatMoney(money: Money?): String =
    money?.let { "%.2f ${it.currency.name}".format(it.amount) } ?: "sem preço"

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
