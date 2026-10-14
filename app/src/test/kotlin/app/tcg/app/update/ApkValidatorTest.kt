package app.tcg.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkValidatorTest {

    private val installed = InstalledAppInfo(
        applicationId = "app.tcg.app",
        versionCode = 10_203L,
        signatureDigest = "aa:bb:cc",
    )

    private fun archive(
        applicationId: String = "app.tcg.app",
        versionCode: Long = 10_300L,
        signatureDigest: String? = "aa:bb:cc",
    ) = ApkMetadata(applicationId, versionCode, signatureDigest)

    @Test
    fun `wrong application id is rejected`() {
        val result = ApkValidator.validate(installed, archive(applicationId = "com.other.app"))
        assertEquals(ApkValidation.Rejected(ApkRejectReason.WrongApplicationId), result)
    }

    @Test
    fun `lower or equal version code is rejected (no downgrade)`() {
        assertEquals(
            ApkValidation.Rejected(ApkRejectReason.NotNewerVersion),
            ApkValidator.validate(installed, archive(versionCode = 10_203L)),
        )
        assertEquals(
            ApkValidation.Rejected(ApkRejectReason.NotNewerVersion),
            ApkValidator.validate(installed, archive(versionCode = 10_100L)),
        )
    }

    @Test
    fun `signature mismatch is rejected`() {
        val result = ApkValidator.validate(installed, archive(signatureDigest = "dd:ee:ff"))
        assertEquals(ApkValidation.Rejected(ApkRejectReason.SignatureMismatch), result)
    }

    @Test
    fun `missing archive signature digest is rejected`() {
        val result = ApkValidator.validate(installed, archive(signatureDigest = null))
        assertEquals(ApkValidation.Rejected(ApkRejectReason.MissingSignature), result)
    }

    @Test
    fun `matching package version and signature is accepted`() {
        val result = ApkValidator.validate(installed, archive())
        assertTrue(result is ApkValidation.Accepted)
    }

    @Test
    fun `signature comparison ignores case`() {
        val result = ApkValidator.validate(installed, archive(signatureDigest = "AA:BB:CC"))
        assertTrue(result is ApkValidation.Accepted)
    }
}
