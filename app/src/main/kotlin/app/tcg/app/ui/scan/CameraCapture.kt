package app.tcg.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import app.tcg.app.R
import app.tcg.core.domain.model.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

@Composable
internal fun rememberTextRecognizer(): TextRecognizer {
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.close() }
    }
    return recognizer
}

/**
 * CameraX preview with the guide frame and the ML Kit capture pipeline. The recognizer lives
 * here, not in the ViewModel, so [ScanViewModel] stays testable on the JVM.
 */
@Composable
fun CameraCapture(
    captureRequest: Int,
    onTextRecognized: (List<OcrLine>) -> Unit,
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

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recognizer = rememberTextRecognizer()
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return@LaunchedEffect
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                } catch (_: Exception) {
                    // No camera available; the screen stays on the preview placeholder.
                }
            },
            executor,
        )
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    LaunchedEffect(captureRequest) {
        if (captureRequest > 0 && hasPermission) {
            capture(imageCapture, executor, recognizer, onTextRecognized, onError)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Portrait card frame: 5:7 width:height, 72% of preview width, clamped to fit the height.
        val frameWidth = minOf(maxWidth * 0.72f, maxHeight * 0.8f * CARD_ASPECT)
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

/**
 * Centered rectangle with a 5:7 (width:height) card aspect that fits inside [width] x [height].
 * The camera preview reads only this region so cards beside the framed one are ignored.
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
                    onError("Falha ao processar a imagem. Tente novamente.")
                    return
                } finally {
                    image.close()
                }
                recognizeText(recognizer, bitmap.rotated(rotation).cropToCard(), 0, onTextRecognized, onError)
            }

            override fun onError(exception: ImageCaptureException) {
                onError("Falha ao capturar. Tente novamente.")
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
) {
    try {
        recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees))
            .addOnSuccessListener { text -> onTextRecognized(text.toOcrLines()) }
            .addOnFailureListener { onError("Falha ao reconhecer o texto. Tente a busca manual.") }
    } catch (_: Exception) {
        onError("Falha ao reconhecer o texto. Tente a busca manual.")
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
