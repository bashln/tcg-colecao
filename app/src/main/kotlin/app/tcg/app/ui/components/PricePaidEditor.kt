package app.tcg.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tcg.app.R
import app.tcg.core.domain.model.Currency

@Composable
fun PricePaidEditor(
    draft: PricePaidDraft,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.amount,
            onValueChange = onAmountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.collection_edit_price_paid)) },
            placeholder = { Text(stringResource(R.string.collection_edit_price_hint)) },
            supportingText = {
                Text(
                    if (draft.isValid) stringResource(R.string.collection_edit_price_help)
                    else stringResource(R.string.collection_edit_price_invalid),
                )
            },
            isError = !draft.isValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = if (draft.amount.isNotBlank()) {
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
                    selected = option == draft.currency,
                    onClick = { onCurrencyChange(option) },
                    label = { Text(option.name) },
                )
            }
        }
    }
}
