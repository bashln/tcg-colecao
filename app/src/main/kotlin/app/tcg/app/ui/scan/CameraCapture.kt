package app.tcg.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.55f)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
        )
        if (!hasPermission) {
            Text(
                text = "Permita o acesso à câmera para escanear.",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White,
            )
        }
    }
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
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = try {
                    image.toBitmap()
                } catch (_: Exception) {
                    onError("Falha ao processar a imagem. Tente novamente.")
                    return
                } finally {
                    image.close()
                }
                recognizeText(recognizer, bitmap, rotation, onTextRecognized, onError)
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
            )
        }
    }
