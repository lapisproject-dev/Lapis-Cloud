package network.lapis.cloud.server.conference.roompoll

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollTextProblem

private fun input(
    question: String = "Sollen wir verlängern?",
    options: List<String> = listOf("Ja", "Nein"),
) = RoomPollStartInput(roomId = "r", question = question, options = options, countingMode = RoomPollCountingMode.BOTH)

/** V1.9.304 -- [RoomPollRules]: length, number and distinctness of the texts, forbidden characters. */
class RoomPollRulesTest :
    FunSpec({
        test("a valid input is normalised (trim, collapsed whitespace)") {
            val v = RoomPollRules.validate(input(question = "  Sollen   wir  verlängern? ", options = listOf(" Ja ", "Nein  Danke")))
            v.question shouldBe "Sollen wir verlängern?"
            v.options shouldBe listOf("Ja", "Nein Danke")
        }

        test("length and number limits") {
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(question = "ab")) }
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(question = "x".repeat(301))) }
            RoomPollRules.validate(input(question = "x".repeat(300)))
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = listOf("Ja"))) }
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = (1..11).map { "o$it" })) }
            RoomPollRules.validate(input(options = (1..10).map { "o$it" }))
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = listOf("Ja", "y".repeat(81)))) }
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = listOf("Ja", "  "))) }
        }

        test("the minimum question length counts code points like the database CHECK (two emoji are 2, not 4)") {
            val twoEmoji = "\uD83D\uDE00\uD83D\uDE00"
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(question = twoEmoji)) }
            RoomPollRules.validate(input(question = twoEmoji + "?"))
        }

        test("duplicate options are refused after trimming and ignoring case") {
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = listOf("Ja", " ja "))) }
        }

        test("forbidden characters are refused on the raw input") {
            RoomPollRules.rejectReason("<img onerror=x>") shouldBe RoomPollTextProblem.ANGLE_BRACKET
            RoomPollRules.rejectReason("a>b") shouldBe RoomPollTextProblem.ANGLE_BRACKET
            RoomPollRules.rejectReason("evil‮txt") shouldBe RoomPollTextProblem.BIDI_CONTROL
            RoomPollRules.rejectReason("iso⁦late") shouldBe RoomPollTextProblem.BIDI_CONTROL
            RoomPollRules.rejectReason("bell\u0007") shouldBe RoomPollTextProblem.CONTROL_CHARACTER
            RoomPollRules.rejectReason("next\u0085line") shouldBe RoomPollTextProblem.CONTROL_CHARACTER
            RoomPollRules.rejectReason("new\nline") shouldBe RoomPollTextProblem.CONTROL_CHARACTER
            RoomPollRules.rejectReason("Ganz normal: äöü ß, 100 %?") shouldBe null
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(question = "Frage<b>")) }
            shouldThrow<IllegalArgumentException> { RoomPollRules.validate(input(options = listOf("Ja", "Ne‮in"))) }
        }
    })
