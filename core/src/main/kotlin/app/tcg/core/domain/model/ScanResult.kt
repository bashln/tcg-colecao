package app.tcg.core.domain.model

data class OcrLine(
    val text: String,
    val top: Float,
    val height: Float,
    val left: Float = 0f,
    val width: Float = 0f,
)

data class ParsedCardText(
    val name: String?, val collectorNumber: String?, val promoCode: String?,
    val hp: Int?, val rawText: String,
)

data class Scored<T>(val value: T, val score: Int)

data class ScanResult(
    val rawText: String,
    val collectorNumber: String?,
    val name: String?,
    val card: Card?,
    val confidence: Double,
)
