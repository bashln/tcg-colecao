package app.tcg.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.tcg.app.R
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.scan.OcrParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
internal fun rememberTextRecognizer(): TextRecognizer {
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.close() }
    }
    return recognizer
}

/**
 * CameraX preview with the guide frame and the ML Kit capture pipeline. The caller owns the
 * [recognizer] (shared with the gallery path) so one instance serves the whole screen, and
 * [ScanViewModel] stays testable on the JVM.
 */
@Composable
fun CameraCapture(
    captureRequest: Int,
    recognizer: TextRecognizer,
    onTextRecognized: (List<OcrLine>) -> Unit,
    onCapturedBitmap: (Bitmap) -> Unit,
    onAutoCapture: () -> Unit,
    autoCaptureEnabled: Boolean,
    torchEnabled: Boolean,
    onTorchAvailabilityChanged: (Boolean) -> Unit,
    onTorchError: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView mode keeps the preview inside its Compose bounds; the default
            // SurfaceView layer draws over sibling UI such as the top app bar.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val imageAnalysis = remember { ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build() }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val currentAutoCapture by rememberUpdatedState(onAutoCapture)
    val currentAutoCaptureEnabled by rememberUpdatedState(autoCaptureEnabled)
    val currentTorchEnabled by rememberUpdatedState(torchEnabled)
    val stability = remember { AutoCaptureStability() }
    val active = remember { AtomicBoolean(true) }
    val analyzing = remember { AtomicBoolean(false) }
    val lastAnalysisAt = remember { AtomicLong(0L) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var resumeGeneration by remember { mutableStateOf(0) }
    val torchAppliedOn = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // CameraX reopens the camera on resume with the torch off; re-apply the chosen state.
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return@LaunchedEffect
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (!active.get()) return@addListener
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    imageAnalysis.clearAnalyzer()
                    imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                        val now = SystemClock.elapsedRealtime()
                        if (!currentAutoCaptureEnabled) {
                            stability.reset()
                            image.close()
                            return@setAnalyzer
                        }
                        if (now - lastAnalysisAt.get() < ANALYSIS_INTERVAL_MILLIS || !analyzing.compareAndSet(false, true)) {
                            image.close()
                            return@setAnalyzer
                        }
                        lastAnalysisAt.set(now)
                        val mediaImage = image.image
                        if (mediaImage == null) {
                            analyzing.set(false)
                            image.close()
                            return@setAnalyzer
                        }
                        try {
                            recognizer.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                                .addOnSuccessListener(analysisExecutor) { text ->
                                    val uprightWidth = if (image.imageInfo.rotationDegrees % 180 == 0) image.width else image.height
                                    val uprightHeight = if (image.imageInfo.rotationDegrees % 180 == 0) image.height else image.width
                                    val cardLines = linesInsideCardFrame(
                                        text.toOcrLines(),
                                        uprightWidth,
                                        uprightHeight,
                                    )
                                    val parsed = OcrParser.parse(cardLines)
                                    val name = parsed.name
                                    val identity = name?.takeIf { it.filter(Char::isLetter).length >= 3 }
                                    if (stability.observe(identity, now) != null) {
                                        executor.execute { currentAutoCapture() }
                                    }
                                }
                                .addOnFailureListener(analysisExecutor) { stability.reset() }
                                .addOnCompleteListener(analysisExecutor) {
                                    image.close()
                                    analyzing.set(false)
                                }
                        } catch (_: Exception) {
                            image.close()
                            analyzing.set(false)
                            stability.reset()
                        }
                    }
                    provider.unbindAll()
                    boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        imageAnalysis,
                    )
                } catch (_: Exception) {
                    onError(context.getString(R.string.scan_camera_unavailable))
                }
            },
            executor,
        )
    }

    LaunchedEffect(boundCamera, torchEnabled, resumeGeneration) {
        val camera = boundCamera ?: return@LaunchedEffect
        if (!camera.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        // Some HALs fail a disable when the torch is already off, so only issue a command
        // when the requested state actually differs from what we applied.
        if (!torchEnabled && !torchAppliedOn.get()) return@LaunchedEffect
        try {
            val torchRequest = camera.cameraControl.enableTorch(torchEnabled)
            torchRequest.addListener(
                {
                    try {
                        torchRequest.get()
                        torchAppliedOn.set(torchEnabled)
                    } catch (_: Exception) {
                        if (torchEnabled && currentTorchEnabled == torchEnabled) onTorchError()
                    }
                },
                executor,
            )
        } catch (_: Exception) {
            if (torchEnabled) onTorchError()
        }
    }

    LaunchedEffect(boundCamera) {
        val camera = boundCamera ?: return@LaunchedEffect
        val available = camera.cameraInfo.hasFlashUnit()
        onTorchAvailabilityChanged(available)
    }

    DisposableEffect(Unit) {
        active.set(true)
        onDispose {
            active.set(false)
            cameraProvider?.unbindAll()
            imageAnalysis.clearAnalyzer()
            analysisExecutor.shutdown()
            stability.reset()
        }
    }

    LaunchedEffect(captureRequest) {
        if (captureRequest > 0 && hasPermission) {
            capture(imageCapture, executor, recognizer, onTextRecognized, onCapturedBitmap, onError)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Portrait card frame: 5:7 width:height, 72% of preview width, clamped to fit the height.
        val frameWidth = minOf(
            maxWidth * CARD_FRAME_WIDTH_FRACTION,
            maxHeight * CARD_FRAME_HEIGHT_FRACTION * CARD_ASPECT,
        )
        val frameHeight = frameWidth / CARD_ASPECT
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(frameWidth, frameHeight)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = stringResource(R.string.scan_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        if (!hasPermission) {
            Text(
                text = stringResource(R.string.scan_camera_permission),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White,
            )
        }
    }
}

private const val CARD_ASPECT = 5f / 7f
private const val CARD_FRAME_WIDTH_FRACTION = 0.72f
private const val CARD_FRAME_HEIGHT_FRACTION = 0.8f
private const val ANALYSIS_INTERVAL_MILLIS = 450L

/**
 * Centered rectangle with a 5:7 (width:height) card aspect that fits inside [width] x [height].
 */
internal fun cardCropRect(width: Int, height: Int): Rect {
    if (width <= 0 || height <= 0) return Rect(0, 0, 0, 0)
    if (width * 7 > height * 5) {
        val cropWidth = height * 5 / 7
        val left = (width - cropWidth) / 2
        return Rect(left, 0, left + cropWidth, height)
    }
    val cropHeight = width * 7 / 5
    val top = (height - cropHeight) / 2
    return Rect(0, top, width, top + cropHeight)
}

internal fun Bitmap.cropToCard(): Bitmap {
    val rect = cardCropRect(width, height)
    return Bitmap.createBitmap(this, rect.left, rect.top, rect.width(), rect.height())
}

internal fun Bitmap.previewCopy(maxHeight: Int = 960): Bitmap {
    if (height <= maxHeight) return copy(Bitmap.Config.ARGB_8888, false)
    val scaledWidth = (width * maxHeight.toFloat() / height).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, maxHeight, true)
}

internal fun linesInsideCardFrame(
    lines: List<OcrLine>,
    imageWidth: Int,
    imageHeight: Int,
): List<OcrLine> {
    val frameWidth = minOf(
        imageWidth * CARD_FRAME_WIDTH_FRACTION,
        imageHeight * CARD_FRAME_HEIGHT_FRACTION * CARD_ASPECT,
    )
    val frameHeight = frameWidth / CARD_ASPECT
    val left = (imageWidth - frameWidth) / 2f
    val top = (imageHeight - frameHeight) / 2f
    val right = left + frameWidth
    val bottom = top + frameHeight
    return lines.filter { line ->
        val centerX = line.left + line.width / 2f
        val centerY = line.top + line.height / 2f
        centerX in left..right && centerY in top..bottom
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun capture(
    imageCapture: ImageCapture,
    executor: Executor,
    recognizer: TextRecognizer,
    onTextRecognized: (List<OcrLine>) -> Unit,
    onCapturedBitmap: (Bitmap) -> Unit,
    onError: (String) -> Unit,
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var rotation = 0
                val bitmap = try {
                    rotation = image.imageInfo.rotationDegrees
                    image.toBitmap()
                } catch (_: Exception) {
                    onError("Não foi possível ler a foto. Tente novamente.")
                    return
                } finally {
                    image.close()
                }
                val rotatedBitmap = bitmap.rotated(rotation)
                if (rotatedBitmap !== bitmap) bitmap.recycle()
                val cardBitmap = rotatedBitmap.cropToCard()
                if (cardBitmap !== rotatedBitmap) rotatedBitmap.recycle()
                onCapturedBitmap(cardBitmap.previewCopy())
                recognizeText(
                    recognizer,
                    cardBitmap,
                    0,
                    onTextRecognized,
                    onError,
                    onComplete = { cardBitmap.recycle() },
                )
            }

            override fun onError(exception: ImageCaptureException) {
                onError("Não foi possível capturar a carta. Tente novamente.")
            }
        },
    )
}

internal fun recognizeText(
    recognizer: TextRecognizer,
    bitmap: Bitmap,
    rotationDegrees: Int,
    onTextRecognized: (List<OcrLine>) -> Unit,
    onError: (String) -> Unit,
    onComplete: () -> Unit = {},
) {
    try {
        recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees))
            .addOnSuccessListener { text -> onTextRecognized(text.toOcrLines()) }
            .addOnFailureListener { onError("Não foi possível reconhecer o texto. Busque pelo nome.") }
            .addOnCompleteListener { onComplete() }
    } catch (_: Exception) {
        onError("Não foi possível reconhecer o texto. Busque pelo nome.")
        onComplete()
    }
}

internal fun com.google.mlkit.vision.text.Text.toOcrLines(): List<OcrLine> =
    textBlocks.flatMap { block ->
        block.lines.map { line ->
            val box = line.boundingBox
            OcrLine(
                text = line.text,
                top = box?.top?.toFloat() ?: 0f,
                height = box?.height()?.toFloat() ?: 0f,
                left = box?.left?.toFloat() ?: 0f,
                width = box?.width()?.toFloat() ?: 0f,
            )
        }
    }
