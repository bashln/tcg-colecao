package app.tcg.app.ui.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tcg.app.R
import app.tcg.app.ui.components.ErrorBanner
import app.tcg.app.ui.components.SectionHeader
import app.tcg.app.ui.components.formatMoney
import app.tcg.app.ui.components.joinNonBlank
import app.tcg.core.data.ligapokemon.LigaPokemonUrl
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
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = rememberTextRecognizer()
    var captureRequest by remember { mutableStateOf(0) }
    var manualQuery by remember { mutableStateOf("") }
    var manualSearchRevealed by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var captureReserved by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var torchError by remember { mutableStateOf(false) }

    // Auto-unfreeze camera on resume: whenever returning from CardDetailScreen, reset stale scan state
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!state.isBatchMode && state.candidates.isNotEmpty()) {
                    viewModel.resetForNextScan()
                    capturedBitmap = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        val bitmap = uri?.let { loadBitmap(context, it) }
        if (bitmap != null) {
            viewModel.prepareForCapture()
            val cardBitmap = bitmap.cropToCard()
            if (cardBitmap !== bitmap) bitmap.recycle()
            capturedBitmap = null
            capturedBitmap = cardBitmap.previewCopy()
            recognizeText(
                recognizer = recognizer,
                bitmap = cardBitmap,
                rotationDegrees = 0,
                onTextRecognized = { lines -> scope.launch { viewModel.onTextRecognized(lines) } },
                onError = viewModel::onCaptureError,
                onComplete = { cardBitmap.recycle() },
            )
        }
    }

    ScanContent(
        state = state,
        capturedBitmap = capturedBitmap,
        manualQuery = manualQuery,
        onManualQueryChange = { manualQuery = it },
        manualSearchRevealed = manualSearchRevealed,
        onManualSearchRevealedChange = { manualSearchRevealed = it },
        onManualSearch = {
            val query = manualQuery.trim()
            if (query.isNotEmpty()) {
                capturedBitmap = null
                scope.launch { viewModel.onManualSearch(query) }
            }
        },
        onCapture = {
            capturedBitmap = null
            viewModel.prepareForCapture()
            captureReserved = true
            captureRequest++
        },
        onGallery = {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onShareDiagnostics = state.diagnostics?.let { diagnostics ->
            {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico de scan")
                    putExtra(Intent.EXTRA_TEXT, diagnostics.toJson())
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        },
        onCardSelected = { card ->
            capturedBitmap = null
            viewModel.resetForNextScan()
            onCardSelected(card)
        },
        onToggleBatchMode = viewModel::toggleBatchMode,
        onSetGridMode = viewModel::setGridMode,
        onQuickAddCard = { card ->
            scope.launch { viewModel.quickAddCard(card) }
            capturedBitmap = null
        },
        onQuickAddAllMultiCards = {
            scope.launch { viewModel.quickAddAllMultiCards() }
            capturedBitmap = null
        },
        onResetForNext = {
            capturedBitmap = null
            viewModel.resetForNextScan()
        },
        onExit = onExit,
        torchAvailable = torchAvailable,
        torchEnabled = torchEnabled,
        torchError = torchError,
        onToggleTorch = { torchError = false; torchEnabled = !torchEnabled },
        modifier = modifier,
        cameraPreview = {
            CameraCapture(
                captureRequest = captureRequest,
                recognizer = recognizer,
                gridMode = state.gridMode,
                onTextRecognized = { lines -> scope.launch { viewModel.onTextRecognized(lines) } },
                onMultiCardTextRecognized = { quadrants -> scope.launch { viewModel.onMultiCardTextRecognized(quadrants) } },
                onCapturedBitmap = {
                    capturedBitmap = null
                    capturedBitmap = it
                    captureReserved = false
                },
                onAutoCapture = {
                    viewModel.prepareForCapture()
                    captureReserved = true
                    captureRequest++
                },
                autoCaptureEnabled = !captureReserved && capturedBitmap == null &&
                    !state.loading && state.error == null && !state.manualSearch &&
                    !manualSearchRevealed && state.candidates.isEmpty() &&
                    state.multiCardResults.isEmpty() && state.gridMode == ScanGridMode.SINGLE,
                torchEnabled = torchEnabled,
                onTorchAvailabilityChanged = { available -> torchAvailable = available },
                onTorchError = { torchError = true; torchEnabled = false },
                onError = { message ->
                    captureReserved = false
                    viewModel.onCaptureError(message)
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/**
 * Stateless scan UI so the manual-search entry point and result states stay testable without a
 * camera. [ScanScreen] supplies the live [CameraCapture] preview through [cameraPreview].
 */
@Composable
fun ScanContent(
    state: ScanUiState,
    capturedBitmap: Bitmap? = null,
    manualQuery: String,
    onManualQueryChange: (String) -> Unit,
    manualSearchRevealed: Boolean = false,
    onManualSearchRevealedChange: (Boolean) -> Unit = {},
    onManualSearch: () -> Unit,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onCardSelected: (Card) -> Unit,
    onExit: () -> Unit = {},
    torchAvailable: Boolean = false,
    torchEnabled: Boolean = false,
    torchError: Boolean = false,
    onToggleTorch: () -> Unit = {},
    onToggleBatchMode: () -> Unit = {},
    onSetGridMode: (ScanGridMode) -> Unit = {},
    onQuickAddCard: (Card) -> Unit = {},
    onQuickAddAllMultiCards: () -> Unit = {},
    onResetForNext: () -> Unit = {},
    onShareDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable () -> Unit = {},
) {
    // A failed scan must always surface the manual field; a manual reveal can be dismissed.
    val showManual = manualSearchRevealed || state.manualSearch
    val dismiss: (() -> Unit)? = if (state.manualSearch) null else { { onManualSearchRevealedChange(false) } }

    // Once a search succeeds, collapse the panel so the candidates own the screen.
    LaunchedEffect(state.candidates.isNotEmpty()) {
        if (state.candidates.isNotEmpty()) onManualSearchRevealedChange(false)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            cameraPreview()

            // Header bar: Title, Exit, and Mode toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onExit) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scan_exit))
                        }
                        Text(
                            text = stringResource(R.string.scan_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.isBatchMode,
                        onClick = onToggleBatchMode,
                        label = { Text(if (state.isBatchMode) "⚡ " + stringResource(R.string.scan_mode_batch) else stringResource(R.string.scan_mode_single)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    FilterChip(
                        selected = state.gridMode == ScanGridMode.GRID_2X2,
                        onClick = {
                            onSetGridMode(
                                if (state.gridMode == ScanGridMode.SINGLE) ScanGridMode.GRID_2X2 else ScanGridMode.SINGLE,
                            )
                        },
                        label = { Text(if (state.gridMode == ScanGridMode.GRID_2X2) stringResource(R.string.scan_mode_grid) else "1x1") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    )
                }
            }

            // Batch counter badge
            if (state.batchCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = stringResource(R.string.scan_batch_counter, state.batchCount),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Flash toast notification for last added card
            state.lastAddedCard?.let { lastCard ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (state.batchCount > 0) 100.dp else 64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.scan_added_success, lastCard.name),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (state.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.scan_searching),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        CaptureControls(
            onCapture = onCapture,
            onGallery = onGallery,
            onManualSearch = { onManualSearchRevealedChange(true) },
            torchAvailable = torchAvailable,
            torchEnabled = torchEnabled,
            torchError = torchError,
            onToggleTorch = onToggleTorch,
        )

        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        state.error?.let { message ->
            ErrorBanner(
                message = message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            // Mode 1: Fast Continuous Batch Mode - Instant Quick Add Card overlay
            state.isBatchMode && state.candidates.isNotEmpty() -> {
                BatchQuickAddCard(
                    card = state.candidates.first().card,
                    onQuickAdd = { onQuickAddCard(state.candidates.first().card) },
                    onDetails = { onCardSelected(state.candidates.first().card) },
                    onSkip = onResetForNext,
                )
            }

            // Mode 2: Multi-card Grid Review (4 cards in 2x2 grid)
            state.gridMode == ScanGridMode.GRID_2X2 && state.multiCardResults.isNotEmpty() -> {
                MultiCardReviewCard(
                    results = state.multiCardResults,
                    onAddAll = onQuickAddAllMultiCards,
                    onDismiss = onResetForNext,
                    onCardClick = onCardSelected,
                )
            }

            // Mode 3: Normal Individual Scanning - Candidates list
            state.candidates.isNotEmpty() -> {
                if (showManual) {
                    ManualSearchPanel(
                        query = manualQuery,
                        onQueryChange = onManualQueryChange,
                        onSearch = onManualSearch,
                        notFound = false,
                        onDismiss = dismiss,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionHeader(
                        title = stringResource(R.string.scan_candidates_title),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.scan_candidates, state.candidates.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    if (capturedBitmap != null) {
                        item(key = "scan-comparison") {
                            ScanComparison(capturedBitmap, state.candidates.first().card)
                        }
                    }
                    items(state.candidates, key = { it.card.id }) { scored ->
                        CandidateCard(card = scored.card, onClick = { onCardSelected(scored.card) })
                    }
                    if (onShareDiagnostics != null && state.diagnostics != null) {
                        item(key = "scan-diagnostics") {
                            TextButton(
                                onClick = onShareDiagnostics,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.scan_share_diagnostics))
                            }
                        }
                    }
                }
            }

            showManual -> ManualSearchPanel(
                query = manualQuery,
                onQueryChange = onManualQueryChange,
                onSearch = onManualSearch,
                notFound = state.manualSearch,
                onDismiss = dismiss,
                onShareDiagnostics = onShareDiagnostics,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun BatchQuickAddCard(
    card: Card,
    onQuickAdd: () -> Unit,
    onDetails: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val ligaUrl = remember(card) { LigaPokemonUrl.buildUrl(card) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = joinNonBlank(card.setName, card.collectorNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "🇧🇷 LigaPokemon",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { uriHandler.openUri(ligaUrl) },
                    )
                }
                IconButton(onClick = onSkip) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scan_next_card))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onQuickAdd,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_quick_add))
                }
                OutlinedButton(
                    onClick = onDetails,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.detail_prices))
                }
            }
        }
    }
}

@Composable
private fun MultiCardReviewCard(
    results: List<MultiCardResult>,
    onAddAll: () -> Unit,
    onDismiss: () -> Unit,
    onCardClick: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.scan_multi_found_count, results.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                }
            }
            results.forEachIndexed { index, item ->
                val card = item.selectedCard
                if (card != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onCardClick(card) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(card.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                joinNonBlank(card.setName, card.collectorNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onAddAll,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.scan_multi_add_all, results.size))
            }
        }
    }
}

@Composable
private fun ScanComparison(capturedBitmap: Bitmap, matchedCard: Card) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.scan_compare_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.scan_captured_label), style = MaterialTheme.typography.labelMedium)
                    Image(
                        bitmap = capturedBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.scan_captured_image_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.scan_match_label), style = MaterialTheme.typography.labelMedium)
                    AsyncImage(
                        model = matchedCard.imageUrl,
                        contentDescription = stringResource(R.string.scan_match_image_cd, matchedCard.name),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }
            Text(
                text = joinNonBlank(matchedCard.name, matchedCard.setName, matchedCard.collectorNumber),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CaptureControls(
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onManualSearch: () -> Unit,
    torchAvailable: Boolean,
    torchEnabled: Boolean,
    torchError: Boolean,
    onToggleTorch: () -> Unit,
) {
    val torchDescription = stringResource(
        if (torchEnabled) R.string.scan_flash_turn_off else R.string.scan_flash_turn_on,
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(
                    onClick = onToggleTorch,
                    enabled = torchAvailable,
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = torchDescription
                    },
                ) {
                    Text("⚡", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = when {
                        torchError -> stringResource(R.string.scan_flash_error_short)
                        !torchAvailable -> stringResource(R.string.scan_flash_unavailable)
                        torchEnabled -> stringResource(R.string.scan_flash_on)
                        else -> stringResource(R.string.scan_flash_off)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = onGallery, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(R.string.cd_gallery_button))
                }
                Text(stringResource(R.string.scan_gallery), style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = onCapture, shape = CircleShape, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = stringResource(R.string.cd_capture_button),
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(stringResource(R.string.scan_capture), style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = onManualSearch, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.scan_manual_entry))
                }
                Text(stringResource(R.string.scan_manual_entry), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ManualSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    notFound: Boolean,
    onDismiss: (() -> Unit)?,
    onShareDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val container = if (notFound) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (notFound) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (notFound) R.string.scan_feedback_title else R.string.scan_manual_entry,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                onDismiss?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_close_search),
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    if (notFound) R.string.scan_feedback_body else R.string.scan_search_help,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.scan_search_label)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onSearch, enabled = query.isNotBlank()) {
                    Text(stringResource(R.string.scan_search_action))
                }
            }
            if (notFound && onShareDiagnostics != null) {
                TextButton(onClick = onShareDiagnostics) {
                    Text(stringResource(R.string.scan_share_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(card: Card, onClick: () -> Unit) {
    val price = card.variants.firstOrNull()?.let {
        Valuation.itemValue(card, it, PriceSource.TCGPLAYER)
    }
    val uriHandler = LocalUriHandler.current
    val ligaUrl = remember(card) { LigaPokemonUrl.buildUrl(card) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClickLabel = stringResource(R.string.cd_open_card), onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 52.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = stringResource(R.string.cd_card_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = card.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = joinNonBlank(card.setName, card.collectorNumber, card.hp?.let { "HP $it" }),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    formatMoney(price)?.let { formatted ->
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "🇧🇷 LigaPokemon",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable { uriHandler.openUri(ligaUrl) },
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun loadBitmap(context: Context, uri: Uri) =
    try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) {
        null
    }
