package app.tcg.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger

/**
 * Pure parser over a GitHub `/releases` page. Keeps only the stable channel (non-draft,
 * non-prerelease), derives `versionName`/`versionCode` from a strict `vMAJOR.MINOR.PATCH`
 * tag exactly as the release workflow does, and resolves a single APK asset. Ambiguity or
 * malformed input is a typed error, never a crash.
 */
object ReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val STABLE_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private val MAX_VERSION_CODE = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val MAX_MINOR_OR_PATCH = BigInteger.valueOf(99)

    fun parse(raw: String): ReleaseSelection {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return ReleaseSelection.Rejected(OtaError.InvalidJson)
        val array = root as? JsonArray
            ?: return ReleaseSelection.Rejected(OtaError.InvalidJson)

        val stable = array.filterIsInstance<JsonObject>()
            .filter { !it.boolean("draft") && !it.boolean("prerelease") }
        if (stable.isEmpty()) return ReleaseSelection.NoStableRelease

        var firstError: OtaError? = null
        val candidates = mutableListOf<GitHubRelease>()
        for (obj in stable) {
            when (val built = buildRelease(obj)) {
                is Built.Ok -> candidates += built.release
                is Built.Error -> if (firstError == null) firstError = built.error
            }
        }

        if (candidates.isEmpty()) {
            return ReleaseSelection.Rejected(firstError ?: OtaError.MissingApkAsset)
        }
        return ReleaseSelection.Selected(candidates.maxBy { it.versionCode })
    }

    private sealed interface Built {
        data class Ok(val release: GitHubRelease) : Built
        data class Error(val error: OtaError) : Built
    }

    private fun buildRelease(obj: JsonObject): Built {
        val tag = obj.string("tag_name") ?: return Built.Error(OtaError.MalformedTag)
        val match = STABLE_TAG.matchEntire(tag) ?: return Built.Error(OtaError.MalformedTag)
        val (majorText, minorText, patchText) = match.destructured

        val major = BigInteger(majorText)
        val minor = BigInteger(minorText)
        val patch = BigInteger(patchText)
        if (minor > MAX_MINOR_OR_PATCH || patch > MAX_MINOR_OR_PATCH) {
            return Built.Error(OtaError.MalformedTag)
        }
        val code = major.multiply(BigInteger.valueOf(10_000))
            .add(minor.multiply(BigInteger.valueOf(100)))
            .add(patch)
        if (code > MAX_VERSION_CODE) return Built.Error(OtaError.VersionOverflow)

        val apk = resolveApkAsset(obj) ?: return Built.Error(assetError(obj))

        val url = apk.string("browser_download_url") ?: return Built.Error(OtaError.MissingApkAsset)
        return Built.Ok(
            GitHubRelease(
                tagName = tag,
                versionName = "$majorText.$minorText.$patchText",
                versionCode = code.toLong(),
                releaseNotes = obj.string("body").orEmpty(),
                apkUrl = url,
                apkSizeBytes = apk.longOrNull("size") ?: 0L,
                apkSha256 = apk.string("digest")?.removePrefix("sha256:"),
                publishedAt = obj.string("published_at"),
            ),
        )
    }

    private fun resolveApkAsset(obj: JsonObject): JsonObject? {
        val assets = obj["assets"] as? JsonArray ?: return null
        val apks = assets.filterIsInstance<JsonObject>().filter { it.isApk() }
        return apks.singleOrNull()
    }

    private fun assetError(obj: JsonObject): OtaError {
        val assets = obj["assets"] as? JsonArray ?: return OtaError.MissingApkAsset
        val apks = assets.filterIsInstance<JsonObject>().filter { it.isApk() }
        return if (apks.size > 1) OtaError.MultipleApkAssets else OtaError.MissingApkAsset
    }

    private fun JsonObject.isApk(): Boolean {
        val contentType = string("content_type")
        val name = string("name")
        return contentType == APK_CONTENT_TYPE || name?.endsWith(".apk", ignoreCase = true) == true
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.longOrNull
}
