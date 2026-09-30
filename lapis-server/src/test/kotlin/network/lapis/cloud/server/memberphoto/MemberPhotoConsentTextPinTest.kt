package network.lapis.cloud.server.memberphoto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MemberPhotoRules
import java.io.File
import java.security.MessageDigest

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- tripwire for the consent wording. The wording shown in the
 * publish dialog (`MEMBER_PHOTO_CONSENT_TEXT` in `MemberPhotoCard.kt`) is what a member consents to;
 * the server stores only the VERSION tag ([MemberPhotoRules.CONSENT_TEXT_VERSION]). If somebody edits
 * the wording but keeps the version, previously stored consents would silently claim to cover a text
 * the member never saw. This test pins the SHA-256 of the literal to the version: a changed wording
 * without a new version (and a new pin here) fails the build.
 *
 * To change the wording legitimately: bump [MemberPhotoRules.CONSENT_TEXT_VERSION], add the NEW
 * version with the new hash to [PINNED_CONSENT_HASHES] (keep the old entries -- they document what
 * earlier consents covered), and update all seven catalogs.
 */
private val PINNED_CONSENT_HASHES: Map<String, String> =
    mapOf(
        "member-photo-public-v1" to "4977c12f7dfce2e92afbfab26619403a021b5edfa6b0a13e14c8fb7a045c3154",
    )

private val CARD_SOURCE: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MemberPhotoCard.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MemberPhotoCard.kt") }

private val CONSENT_LITERAL = Regex("""internal const val MEMBER_PHOTO_CONSENT_TEXT =\s*"((?:[^"\\]|\\.)*)"""")

/** The SHA-256 is taken from a fresh [MessageDigest] instance on every call (instances are not thread-safe). */
private fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class MemberPhotoConsentTextPinTest :
    FunSpec({
        test("the current consent version has a pin, and the wording in MemberPhotoCard.kt hashes to it") {
            val match = CONSENT_LITERAL.find(CARD_SOURCE.readText())
            (match != null) shouldBe true
            val wording = match!!.groupValues[1]
            val pinned = PINNED_CONSENT_HASHES[MemberPhotoRules.CONSENT_TEXT_VERSION]
            (pinned != null) shouldBe true
            sha256Hex(wording) shouldBe pinned
        }

        test("the pin is not vacuous: a one-character change of the wording changes the hash") {
            val match = CONSENT_LITERAL.find(CARD_SOURCE.readText())!!
            val wording = match.groupValues[1]
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
