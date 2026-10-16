package network.lapis.cloud.server.memberfinder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MEMBER_FINDER_MIN_REGION_SIZE
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderRules
import network.lapis.cloud.shared.rpc.MemberFinderNoteInvalidException

/** V1.9.144 -- the shared rules: the visibility threshold and its bands, and the normalization of the free-text note. */
class MemberFinderRulesTest :
    FunSpec({
        test("bandOf: below the threshold nothing is visible, then three coarse bands") {
            MemberFinderRules.bandOf(0) shouldBe null
            MemberFinderRules.bandOf(MEMBER_FINDER_MIN_REGION_SIZE - 1) shouldBe null
            MemberFinderRules.bandOf(4) shouldBe null
            MemberFinderRules.bandOf(5) shouldBe MemberFinderBand.B5_9
            MemberFinderRules.bandOf(9) shouldBe MemberFinderBand.B5_9
            MemberFinderRules.bandOf(10) shouldBe MemberFinderBand.B10_19
            MemberFinderRules.bandOf(19) shouldBe MemberFinderBand.B10_19
            MemberFinderRules.bandOf(20) shouldBe MemberFinderBand.B20_PLUS
            MemberFinderRules.bandOf(5000) shouldBe MemberFinderBand.B20_PLUS
        }

        test("a note is trimmed, loses control and bidirectional characters and line breaks, and blank becomes null") {
            MemberFinderRules.normalizeNote(null) shouldBe null
            MemberFinderRules.normalizeNote("   ") shouldBe null
            MemberFinderRules.normalizeNote("\u0000‮​") shouldBe null
            MemberFinderRules.normalizeNote("  Hallo  Welt  ") shouldBe "Hallo Welt"
            MemberFinderRules.normalizeNote("Zeile eins\r\nZeile zwei\nund drei") shouldBe "Zeile eins Zeile zwei und drei"
            MemberFinderRules.normalizeNote("Ab‮cd⁦ef\u0085gh\u007F") shouldBe "Abcdefgh"
        }

        test("200 UTF-16 code units are the limit, 201 are refused") {
            MemberFinderRules.normalizeNote("a".repeat(200)) shouldBe "a".repeat(200)
            shouldThrow<MemberFinderNoteInvalidException> { MemberFinderRules.normalizeNote("a".repeat(201)) }
            // an emoji is TWO code units: 100 of them fit, 101 do not
            MemberFinderRules.normalizeNote("😀".repeat(100)) shouldBe "😀".repeat(100)
            shouldThrow<MemberFinderNoteInvalidException> { MemberFinderRules.normalizeNote("😀".repeat(101)) }
        }
    })
