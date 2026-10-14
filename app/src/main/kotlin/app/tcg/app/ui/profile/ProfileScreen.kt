package app.tcg.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.BreakdownRow
import app.tcg.app.ui.components.CollectionKeyNumbers
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.components.SectionHeader
import app.tcg.app.ui.components.TotalValueHero
import app.tcg.app.ui.components.conditionLabel
import app.tcg.app.ui.components.formatMoney
import app.tcg.app.ui.components.variantLabel
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.stats.CollectionStats
import app.tcg.core.domain.stats.StatBucket

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onUpdatesClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        OutlinedButton(
            onClick = onAccountClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.account_title))
        }
        OutlinedButton(
            onClick = onUpdatesClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.update_title))
        }
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(stringResource(R.string.profile_loading))
            }
            state.stats.totalCards == 0 -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = stringResource(R.string.profile_empty_title),
                    body = stringResource(R.string.profile_empty_body),
                    action = {
                        Button(onClick = onScanClick) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_empty_action))
                        }
                    },
                )
            }
            else -> ProfileContent(state.stats)
        }
    }
}

@Composable
private fun ProfileContent(stats: CollectionStats) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TotalValueHero(label = stringResource(R.string.profile_total_value), total = stats.totalValue)
        }
        item { CollectionKeyNumbers(stats) }
        stats.invested?.let { invested ->
            item { InvestmentCard(invested = invested, profit = stats.profit) }
        }
        if (stats.byCondition.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_condition),
                    buckets = stats.byCondition,
                    totalCards = stats.totalCards,
                ) { conditionLabel(it) }
            }
        }
        if (stats.byVariant.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_variant),
                    buckets = stats.byVariant,
                    totalCards = stats.totalCards,
                ) { variantLabel(it) }
            }
        }
        if (stats.byRarity.isNotEmpty()) {
            item {
                BreakdownSection(
                    title = stringResource(R.string.profile_breakdown_rarity),
                    buckets = stats.byRarity,
                    totalCards = stats.totalCards,
                ) { rarity -> rarity ?: stringResource(R.string.profile_rarity_unknown) }
            }
        }
    }
}

@Composable
private fun InvestmentCard(invested: Money, profit: Money?) {
    val isProfit = (profit?.amount ?: 0.0) >= 0.0
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MoneyRow(
                label = stringResource(R.string.profile_invested),
                value = invested,
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            MoneyRow(
                label = stringResource(if (isProfit) R.string.profile_profit else R.string.profile_loss),
                value = profit,
                valueColor = if (isProfit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MoneyRow(label: String, value: Money?, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatMoney(value) ?: stringResource(R.string.common_no_price),
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun <K> BreakdownSection(
    title: String,
    buckets: List<StatBucket<K>>,
    totalCards: Int,
    label: @Composable (K) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                buckets.forEach { bucket ->
                    BreakdownRow(
                        label = label(bucket.key),
                        count = bucket.count,
                        share = if (totalCards > 0) bucket.count.toFloat() / totalCards else 0f,
                    )
                }
            }
        }
    }
}
