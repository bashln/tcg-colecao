package app.tcg.core.data.jornada

import kotlinx.serialization.Serializable

@Serializable
data class JornadaRequest(
    val cards: List<JornadaCardRequest>,
    val maxAgeHours: Int = DEFAULT_MAX_AGE_HOURS,
)

@Serializable
data class JornadaCardRequest(
    val cardId: String,
    val setId: String,
    val collectorNumber: String,
    val name: String,
    val variants: List<String>,
)

@Serializable
data class JornadaResponse(
    val quotes: Map<String, JornadaQuote> = emptyMap(),
    val misses: List<String> = emptyList(),
    val quota: JornadaQuota? = null,
)

@Serializable
data class JornadaQuote(
    val status: String,
    val publicId: Long? = null,
    val code: String? = null,
    val marketCents: Long? = null,
    val marketKind: String? = null,
    val fetchedAt: String? = null,
    val skus: List<JornadaSku> = emptyList(),
    val byVariant: Map<String, JornadaSku> = emptyMap(),
)

@Serializable
data class JornadaSku(
    val condition: String? = null,
    val language: String? = null,
    val finish: String? = null,
    val lowestCents: Long? = null,
    val highestCents: Long? = null,
    val listings: Int = 0,
)

@Serializable
data class JornadaQuota(
    val marketRemaining: Int? = null,
    val catalogRemaining: Int? = null,
    val retryAfterSeconds: Int? = null,
)

const val DEFAULT_MAX_AGE_HOURS = 24
