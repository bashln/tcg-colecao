package app.tcg.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.CollectionKeyNumbers
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.components.SectionHeader
import app.tcg.app.ui.components.TotalValueHero
import app.tcg.app.ui.components.formatMoney
import app.tcg.app.ui.components.joinNonBlank
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.HistoryContinuity
import app.tcg.core.domain.stats.CollectionStats
import app.tcg.core.domain.valuation.Valuation
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit = {},
    onCollectionItemSelected: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(stringResource(R.string.home_loading))
            }
            state.stats.totalCards == 0 -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    action = {
                        Button(onClick = onScanClick) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_empty_action))
                        }
                    },
                )
            }
            else -> HomeContent(
                stats = state.stats,
                priceHistory = viewModel.priceHistory.collectAsStateWithLifecycle().value,
                priceSource = viewModel.priceSource.collectAsStateWithLifecycle().value,
                onPriceSourceSelected = viewModel::selectPriceSource,
                onPriceHistoryRetry = viewModel::retryPriceHistory,
                onCollectionItemSelected = onCollectionItemSelected,
            )
        }
    }
}

@Composable
private fun HomeContent(
    stats: CollectionStats,
    priceHistory: PriceHistoryUiState,
    priceSource: PriceSource,
    onPriceSourceSelected: (PriceSource) -> Unit,
    onPriceHistoryRetry: () -> Unit,
    onCollectionItemSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TotalValueHero(
                label = stringResource(R.string.home_total_label),
                total = stats.totalValue,
                unpricedCards = stats.unpricedCards,
                totalCards = stats.totalCards,
            )
        }
        item { CollectionKeyNumbers(stats) }
        item { SectionHeader(stringResource(R.string.home_most_valuable)) }
        items(stats.mostValuable, key = { it.item.id }) { entry ->
            MostValuableRow(entry = entry, onClick = { onCollectionItemSelected(entry.item.id) })
        }
        item {
            PriceHistoryCard(priceHistory, priceSource, onPriceSourceSelected, onPriceHistoryRetry)
        }
    }
}

@Composable
private fun MostValuableRow(entry: CollectionEntry, onClick: () -> Unit) {
    val card = entry.card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.cd_open_card), role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = stringResource(R.string.cd_card_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = card.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = joinNonBlank(card.setName, card.collectorNumber),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatMoney(Valuation.itemValue(card, entry.item.variant, PriceSource.TCGPLAYER))
                    ?: stringResource(R.string.common_no_price),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PriceHistoryCard(
    state: PriceHistoryUiState,
    source: PriceSource,
    onSourceSelected: (PriceSource) -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.home_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == PriceSource.TCGPLAYER,
                    onClick = { onSourceSelected(PriceSource.TCGPLAYER) },
                    label = { Text(stringResource(R.string.common_price_tcgplayer)) },
                )
                FilterChip(
                    selected = source == PriceSource.CARDMARKET,
                    onClick = { onSourceSelected(PriceSource.CARDMARKET) },
                    label = { Text(stringResource(R.string.common_price_cardmarket)) },
                )
            }
            Text(
                text = stringResource(R.string.home_chart_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                PriceHistoryUiState.Loading -> Text(stringResource(R.string.home_chart_loading))
                PriceHistoryUiState.Unavailable -> {
                    Text(stringResource(R.string.home_chart_error))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.collection_set_retry)) }
                }
                is PriceHistoryUiState.Ready -> PriceHistoryContent(state.history)
            }
        }
    }
}

@Composable
private fun PriceHistoryContent(history: CurrentHoldingsHistory) {
    val points = history.recorded
    if (points.isEmpty()) {
        Text(stringResource(R.string.home_chart_empty))
        return
    }
    val last = history.latest ?: return
    Text(
        text = formatMoney(last.value).orEmpty(),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(
            R.string.home_chart_coverage,
            last.coverage.pricedUnits,
            last.coverage.totalUnits,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (last.coverage.olderThanSevenDaysUnits > 0) {
        Text(
            text = stringResource(R.string.home_chart_old_capture, last.coverage.olderThanSevenDaysUnits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (points.size == 1) {
        Text(stringResource(R.string.home_chart_one_day, points.single().utcDay.toString()))
    } else {
        PriceHistoryPlot(history)
    }
    points.takeLast(3).asReversed().forEach { point ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(point.utcDay.toString(), style = MaterialTheme.typography.bodySmall)
            Text(
                formatMoney(point.estimate.value).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PriceHistoryPlot(history: CurrentHoldingsHistory) {
    val points = history.recorded
    val primary = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    val values = points.map { it.estimate.value.amount }
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 0.0
    val firstDay = points.first().utcDay.toEpochDay()
    val spanDays = (points.last().utcDay.toEpochDay() - firstDay).coerceAtLeast(1)
    val spokenPoints = points.joinToString(". ") { point ->
        "${point.utcDay}: ${formatMoney(point.estimate.value)}, " +
            "${point.estimate.coverage.pricedUnits} de ${point.estimate.coverage.totalUnits} unidades com preço"
    }
    Canvas(
        modifier = Modifier.fillMaxWidth().height(148.dp).semantics { contentDescription = spokenPoints },
    ) {
        val left = 8.dp.toPx()
        val top = 12.dp.toPx()
        val right = size.width - left
        val bottom = size.height - top
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = top + (bottom - top) * fraction
            drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        fun position(index: Int): Offset {
            val point = points[index]
            val x = left + (point.utcDay.toEpochDay() - firstDay).toFloat() / spanDays * (right - left)
            val range = maxValue - minValue
            val normalized = if (range == 0.0) 0.5f else ((point.estimate.value.amount - minValue) / range).toFloat()
            return Offset(x, bottom - normalized * (bottom - top))
        }
        points.forEachIndexed { index, point ->
            val current = position(index)
            if (index > 0 && point.continuity == HistoryContinuity.CONTINUES_SEGMENT) {
                drawLine(primary, position(index - 1), current, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(primary, radius = 4.dp.toPx(), center = current)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().utcDay.toString(), style = MaterialTheme.typography.labelSmall)
        Text(points.last().utcDay.toString(), style = MaterialTheme.typography.labelSmall)
    }
}
