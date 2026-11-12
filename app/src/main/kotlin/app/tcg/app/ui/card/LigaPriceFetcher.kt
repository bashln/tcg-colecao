package app.tcg.app.ui.card

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tcg.app.R
import app.tcg.core.data.ligapokemon.LigaPokemonUrl
import app.tcg.core.data.ligapokemon.LigaPricePhase
import app.tcg.core.data.ligapokemon.LigaQuote
import app.tcg.core.data.ligapokemon.LigaQuoteParser
import app.tcg.core.data.ligapokemon.awaitLigaPrice
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val QUOTE_EXPRESSION =
    "(typeof cards_editions !== 'undefined' && cards_editions) ? JSON.stringify(cards_editions) : ''"

private sealed interface LigaState {
    data object Loading : LigaState
    data object Verifying : LigaState
    data class Ready(val quote: LigaQuote) : LigaState
    data object Failed : LigaState
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LigaPriceDialog(
    card: Card,
    variant: CardVariant,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val url = remember(card) { LigaPokemonUrl.buildUrl(card) }
    val alive = remember { AtomicBoolean(true) }
    var pageLoads by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LigaState>(LigaState.Loading) }
    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            setWebViewClient(
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        pageLoads += 1
                    }
                },
            )
            loadUrl(url)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            alive.set(false)
            webView.stopLoading()
            // AndroidView detaches the WebView after this effect runs, and destroying a still
            // attached WebView leaks it.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // The Cloudflare challenge page finishes loading too, so each document gets its own wait
    // window; the reload after the challenge is what puts cards_editions on the page.
    LaunchedEffect(pageLoads) {
        if (pageLoads == 0 || state is LigaState.Ready) return@LaunchedEffect
        state = LigaState.Loading
        val quote = awaitLigaPrice(onPhase = { phase ->
            if (phase == LigaPricePhase.VERIFYING) state = LigaState.Verifying
        }) {
            readQuote(webView, card.collectorNumber, alive)
        }
        state = if (quote != null) LigaState.Ready(quote) else LigaState.Failed
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.detail_liga_web_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.detail_liga_web_close))
                    }
                }
                val progressText = when (state) {
                    LigaState.Verifying -> R.string.detail_liga_web_verifying
                    LigaState.Loading -> R.string.detail_liga_web_loading
                    else -> null
                }
                if (progressText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(progressText),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
                when (val current = state) {
                    LigaState.Loading, LigaState.Verifying -> Unit
                    is LigaState.Ready -> {
                        current.quote.normalMedium?.let { QuoteLine(R.string.detail_liga_web_normal, it) }
                        current.quote.foilMedium?.let { QuoteLine(R.string.detail_liga_web_foil, it) }
                        val amount = current.quote.mediumFor(variant)
                        if (amount == null) {
                            Text(
                                text = stringResource(R.string.detail_liga_web_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FailureButtons(onManual = onDismiss, onBrowser = { uriHandler.openUri(url) })
                        } else {
                            Button(
                                onClick = {
                                    onApply(amount)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.detail_liga_web_apply, money(amount)))
                            }
                        }
                    }
                    LigaState.Failed -> {
                        Text(
                            text = stringResource(R.string.detail_liga_web_failed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FailureButtons(onManual = onDismiss, onBrowser = { uriHandler.openUri(url) })
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteLine(labelRes: Int, amount: Double) {
    Text(
        text = stringResource(labelRes, money(amount)),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun FailureButtons(onManual: () -> Unit, onBrowser: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.detail_liga_web_manual))
        }
        OutlinedButton(onClick = onBrowser, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.detail_liga_web_browser))
        }
    }
}

private suspend fun readQuote(webView: WebView, collectorNumber: String?, alive: AtomicBoolean): LigaQuote? =
    suspendCancellableCoroutine { continuation ->
        if (!alive.get()) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            webView.evaluateJavascript(QUOTE_EXPRESSION) { raw ->
                if (alive.get() && continuation.isActive) {
                    continuation.resume(jsString(raw)?.let { LigaQuoteParser.parse(it, collectorNumber) })
                }
            }
        } catch (_: IllegalStateException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

/** evaluateJavascript hands back the string result JSON-encoded, so the payload arrives quoted. */
private fun jsString(raw: String?): String? {
    val value = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonPrimitive
        ?: return null
    return value.content.takeIf { value.isString && it.isNotBlank() }
}

private fun money(amount: Double): String = "%.2f".format(amount)
