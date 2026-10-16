package network.lapis.cloud.client

import kotlin.test.assertFalse

/**
 * Port of the server's `shouldNotContainNumber`: asserts that [number] does not appear as a standalone numeric token in [text].
 *
 * A plain `assertFalse(text.contains("12"))` is flaky on text that holds random UUIDs: two digits occur inside a generated id all the time. A
 * token that is glued to hex digits, letters, `-` or `_` is part of such an id and does not count; `12`, `12.5` or `"12"` do.
 */
internal fun assertNoNumberToken(
    text: String,
    number: String,
) {
    val token = Regex("(?<![0-9A-Za-z_-])" + Regex.escape(number) + "(?![0-9A-Za-z_-])")
    assertFalse(token.containsMatchIn(text), "the number $number must not appear as a standalone token")
}
