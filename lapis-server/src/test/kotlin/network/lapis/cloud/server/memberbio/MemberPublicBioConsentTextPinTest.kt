package network.lapis.cloud.server.memberbio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import java.io.File
import java.security.MessageDigest

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- tripwire for the consent wording of the public short
 * introduction. The wording shown in the publish dialog (`MEMBER_PUBLIC_BIO_CONSENT_TEXT` in
 * `MemberPublicProfileCard.kt`) is what a member consents to; the server stores only the VERSION tag
 * ([MemberPublicBioRules.CONSENT_TEXT_VERSION]). If somebody edits the wording but keeps the version,
 * stored consents would silently claim to cover a text the member never saw. This test pins the
 * SHA-256 of the literal to the version: a changed wording without a new version (and a new pin here)
 * fails the build. Same mechanism as `MemberPhotoConsentTextPinTest`.
 *
 * To change the wording legitimately: bump [MemberPublicBioRules.CONSENT_TEXT_VERSION], add the NEW
 * version with the new hash to [PINNED_CONSENT_HASHES] (keep the old entries -- they document what
 * earlier consents covered), and update all seven catalogs.
 */
private val PINNED_CONSENT_HASHES: Map<String, String> =
    mapOf(
        "member-bio-public-v1" to "7414b10325baceb959972684b44ec3a5b4917908ddab6b1126a3c41f83c14968",
    )

private val CARD_SOURCE: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MemberPublicProfileCard.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MemberPublicProfileCard.kt") }

private val CONSENT_LITERAL = Regex("""internal const val MEMBER_PUBLIC_BIO_CONSENT_TEXT =\s*"((?:[^"\\]|\\.)*)"""")

/** The SHA-256 is taken from a fresh [MessageDigest] instance on every call (instances are not thread-safe). */
private fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class MemberPublicBioConsentTextPinTest :
    FunSpec({
        test("the current consent version has a pin, and the wording in MemberPublicProfileCard.kt hashes to it") {
            val match = CONSENT_LITERAL.find(CARD_SOURCE.readText())
            (match != null) shouldBe true
            val wording = match!!.groupValues[1]
            val pinned = PINNED_CONSENT_HASHES[MemberPublicBioRules.CONSENT_TEXT_VERSION]
            (pinned != null) shouldBe true
            sha256Hex(wording) shouldBe pinned
        }

        test("the pin is not vacuous: a one-character change of the wording changes the hash") {
            val wording = CONSENT_LITERAL.find(CARD_SOURCE.readText())!!.groupValues[1]
            (sha256Hex(wording) == sha256Hex("$wording.")) shouldBe false
        }

        test(
            "the dialog wording names the three facts a consent needs: where it is shown, that it can be withdrawn, and the limit of a withdrawal",
        ) {
            val wording = CONSENT_LITERAL.find(CARD_SOURCE.readText())!!.groupValues[1]
            wording.contains("öffentlichen Webseiten") shouldBe true
            wording.contains("jederzeit widerrufen") shouldBe true
            wording.contains("Kopien, die Dritte bereits angefertigt haben, können wir nicht zurückholen") shouldBe true
        }
    })
