package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.encounter.ConsentHashing
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION

/**
 * V1.9.144 -- the operator notice an ADMIN acknowledges before switching the member finder on. Version and hash of the German reference text are pinned as
 * LITERALS (pattern of `PulpitRecordingNoticePinTest`): every textual change forces a new version -- the answer to a failing pin is to bump
 * [MEMBER_FINDER_NOTICE_VERSION] together with [MemberFinderNotice.VERSION], never to edit the literal. (The admin dialog that shows this text comes with
 * the client wave; its paragraph-for-paragraph check is added there.)
 */
class MemberFinderNoticePinTest :
    FunSpec({
        test("version and hash of the notice are pinned") {
            MemberFinderNotice.VERSION shouldBe "1"
            MemberFinderNotice.SHA256 shouldBe "5aab1116b5bb9dcdf994e4c79c98eb4fc6c1426b5824e57e8dcf8948a71fd83f"
        }

        test("the shared constant is the version of the server's reference text") {
            MEMBER_FINDER_NOTICE_VERSION shouldBe MemberFinderNotice.VERSION
        }

        test("the hash is sha256 over the version, a line feed and the paragraphs joined by blank lines") {
            MemberFinderNotice.SHA256 shouldBe
                ConsentHashing.sha256Hex("${MemberFinderNotice.VERSION}\n${MemberFinderNotice.PARAGRAPHS_DE.joinToString("\n\n")}")
        }

        test(
            "the notice names the special category, the country-specific check, the operator's responsibility and the closing confirmation",
        ) {
            val text = MemberFinderNotice.TEXT_DE
            text shouldContain "besonders geschütztes Datum"
            text shouldContain "Art. 9 DSGVO"
            text shouldContain "Datenschutz-Folgenabschätzung"
            text shouldContain "Lapis Cloud ist keine Rechtsberatung"
            text shouldContain "Als Betreiber sind Sie dafür verantwortlich"
            text shouldContain "Ich habe die rechtliche Zulässigkeit für mein Land geprüft und übernehme die Verantwortung."
            MemberFinderNotice.PARAGRAPHS_DE.size shouldBe 4
            for (bad in listOf("*innen", ":innen", "_innen", "/innen")) text shouldNotContain bad
        }
    })
