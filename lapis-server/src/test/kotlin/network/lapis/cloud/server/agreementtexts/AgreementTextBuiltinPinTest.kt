package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.FriendTermsDisclaimer
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.shared.domain.AgreementTextKind

/**
 * V1.9.134 -- the two built-in placeholders are the proof behind every acknowledgment stored before an instance published its own text: their
 * label and hash are pinned here as LITERALS. Changing one character of either text changes the hash and fails this test -- the correct
 * response is never to update the literal but to add a new version through the administration instead (pattern of
 * `MemberPublicBioConsentTextPinTest`).
 */
class AgreementTextBuiltinPinTest :
    FunSpec({
        test("the membership agreement placeholder keeps its label and hash") {
            MembershipAgreementDisclaimer.VERSION shouldBe "2026-07-23.v1"
            MembershipAgreementDisclaimer.SHA256 shouldBe "26982d09636e7b9275b36e9cf6200debcf26d05eb6a3a7846640df6e2326d5ac"
        }

        test("the FRIEND terms placeholder keeps its label (2026-08-15, not 2026-07-23) and hash") {
            FriendTermsDisclaimer.VERSION shouldBe "2026-08-15.v1"
            FriendTermsDisclaimer.SHA256 shouldBe "b49bb09040705caba834aad3b789e15807395f31666216169765b57ae8341cb8"
        }

        test("the hash formula of a database version is the placeholder formula: sha256 of the label, a line feed and the text") {
            AgreementTextHash.of(versionLabel = MembershipAgreementDisclaimer.VERSION, body = MembershipAgreementDisclaimer.TEXT) shouldBe
                MembershipAgreementDisclaimer.SHA256
            AgreementTextHash.of(versionLabel = FriendTermsDisclaimer.VERSION, body = FriendTermsDisclaimer.TEXT) shouldBe
                FriendTermsDisclaimer.SHA256
        }

        test("one changed character or a different label changes the hash") {
            val base = AgreementTextHash.of(versionLabel = "x.v1", body = "Text")
            (AgreementTextHash.of(versionLabel = "x.v1", body = "Texu") == base) shouldBe false
            (AgreementTextHash.of(versionLabel = "x.v2", body = "Text") == base) shouldBe false
            (AgreementTextHash.of(versionLabel = "x.v1", body = "Text\n") == base) shouldBe false
            base.length shouldBe 64
            base shouldBe base.lowercase()
        }

        test("BuiltinAgreementTexts reads the two placeholder objects unchanged") {
            val m = BuiltinAgreementTexts.of(kind = AgreementTextKind.MEMBERSHIP)
            m.versionLabel shouldBe MembershipAgreementDisclaimer.VERSION
            m.body shouldBe MembershipAgreementDisclaimer.TEXT
            m.sha256 shouldBe MembershipAgreementDisclaimer.SHA256
            val f = BuiltinAgreementTexts.of(kind = AgreementTextKind.FRIEND)
            f.versionLabel shouldBe FriendTermsDisclaimer.VERSION
            f.body shouldBe FriendTermsDisclaimer.TEXT
            f.sha256 shouldBe FriendTermsDisclaimer.SHA256
        }

        test("hash comparison: case-insensitive hex, malformed input is a non-match and never throws") {
            val sha = AgreementTextHash.of(versionLabel = "x.v1", body = "Text")
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = sha) shouldBe true
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = sha.uppercase()) shouldBe true
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = "") shouldBe false
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = "zz") shouldBe false
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = "abc") shouldBe false
            AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = "0".repeat(64)) shouldBe false
        }
    })
