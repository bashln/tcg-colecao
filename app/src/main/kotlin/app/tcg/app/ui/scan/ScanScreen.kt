package app.tcg.app.ui.scan

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import coil.compose.AsyncImage
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    onCardSelected: (Card) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recognizer = rememberTextRecognizer()
    var captureRequest by remember { mutableStateOf(0) }
    var manualQuery by remember { mutableStateOf("") }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        val bitmap = uri?.let { loadBitmap(context, it) }
        if (bitmap != null) {
            recognizeText(recognizer, bitmap, 0) { lines ->
                scope.launch { viewModel.onTextRecognized(lines) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CameraCapture(
                captureRequest = captureRequest,
                onTextRecognized = { lines -> scope.launch { viewModel.onTextRecognized(lines) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { captureRequest++ }, modifier = Modifier.weight(1f)) {
                Text("Capturar")
            }
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Galeria")
            }
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (state.manualSearch) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualQuery,
                    onValueChange = { manualQuery = it },
                    label = { Text("Buscar por nome") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val query = manualQuery.trim()
                        if (query.isNotEmpty()) scope.launch { viewModel.onManualSearch(query) }
                    },
                ) {
                    Text("Buscar")
                }
            }
        }
        Text(
            text = "${state.candidates.size} candidato(s)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(state.candidates, key = { it.value.id }) { scored ->
                CandidateRow(card = scored.value, onClick = { onCardSelected(scored.value) })
            }
        }
    }
}

@Composable
private fun CandidateRow(card: Card, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${card.setName} · ${card.collectorNumber}" +
                    (card.hp?.let { " · HP $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val price = card.variants.firstOrNull()?.let {
            Valuation.itemValue(card, it, PriceSource.TCGPLAYER)
        }
        Text(
            text = price?.let { "%.2f ${it.currency.name}".format(it.amount) } ?: "sem preço",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun loadBitmap(context: Context, uri: Uri) =
    try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) {
        null
    }
