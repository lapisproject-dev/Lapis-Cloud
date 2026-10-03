package network.lapis.cloud.server

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Asserts that [number] does not appear as a standalone numeric token in the text.
 *
 * A plain `shouldNotContain "4242"` is flaky on output that holds random UUIDs or timestamps: four to five digits
 * occasionally occur inside a generated id. A token that is glued to hex digits, letters, `-` or `_` is part of such an
 * id and does not count; `4242`, `4242.42` or `"4242"` do.
 */
infix fun String.shouldNotContainNumber(number: String) {
    val token = Regex("(?<![0-9A-Za-z_-])" + Regex.escape(number) + "(?![0-9A-Za-z_-])")
    withClue("the number $number must not appear as a standalone token") { token.containsMatchIn(this) shouldBe false }
}
