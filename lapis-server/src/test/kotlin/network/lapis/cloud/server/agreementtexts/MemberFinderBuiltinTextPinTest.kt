package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AgreementTextKind
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.144 -- the placeholder of the FINDER kind: label and hash are pinned as LITERALS (pattern of [PulpitRecordingBuiltinTextPinTest]). A changed
 * character changes the hash and fails this test; the answer is never to update the literal but to publish a new version through the administration.
 */
class MemberFinderBuiltinTextPinTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }

        test("the placeholder keeps its label and hash") {
            MemberFinderConsentDefault.VERSION shouldBe "2026-10-16.finder.v1"
            MemberFinderConsentDefault.SHA256 shouldBe "bc79c540bfb5094a4b6a4e71a1107fc9aaeb1dd084992793f3c2986423c13218"
        }

        test("the hash is the formula of every other placeholder: sha256 of the label, a line feed and the text") {
            AgreementTextHash.of(versionLabel = MemberFinderConsentDefault.VERSION, body = MemberFinderConsentDefault.TEXT) shouldBe
                MemberFinderConsentDefault.SHA256
        }

        test("the text asks for explicit consent, names the special category, the age confirmation, the revocation and neutral wording") {
            val text = MemberFinderConsentDefault.TEXT
            text shouldContain "Ich willige ausdrücklich ein"
            text shouldContain "besondere Kategorie personenbezogener Daten"
            text shouldContain "Art. 9 DSGVO"
            text shouldContain "volljährig"
            text shouldContain "jederzeit widerrufen"
            text shouldContain "fünf Teilnehmenden"
            // no gender star, colon, underscore or slash forms
            for (bad in listOf("*innen", ":innen", "_innen", "/innen")) text shouldNotContain bad
        }

        test("BuiltinAgreementTexts hands out the placeholder for the FINDER kind, for both profiles alike, and lists exactly one") {
            val builtin = BuiltinAgreementTexts.of(kind = AgreementTextKind.FINDER)
            builtin.versionLabel shouldBe MemberFinderConsentDefault.VERSION
            BuiltinAgreementTexts.of(
                kind = AgreementTextKind.FINDER,
                profile = network.lapis.cloud.shared.domain.EncounterProfile.ASSEMBLY,
            ) shouldBe
                builtin
            BuiltinAgreementTexts.allOf(AgreementTextKind.FINDER).size shouldBe 1
        }

        test(
            "without a published version the live text is the placeholder; a published version replaces it; nothing is ever counted as an older acknowledgment",
        ) {
            transaction {
                val placeholder = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
                placeholder.placeholder shouldBe true
                placeholder.versionLabel shouldBe MemberFinderConsentDefault.VERSION
                AgreementTextStore.countOlderAcknowledgments(kind = AgreementTextKind.FINDER, currentLabel = "any") shouldBe 0
            }
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.FINDER,
                    label = "2026-10-17.v1",
                    body = "Ein eigener, geprüfter Text.",
                )
            transaction {
                val current = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
                current.placeholder shouldBe false
                current.sha256 shouldBe sha
                AgreementTextStore.countOlderAcknowledgments(kind = AgreementTextKind.FINDER, currentLabel = "2026-10-17.v1") shouldBe 0
            }
        }
    })
