package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Security audit W6b, round 7 (major finding 2, i18n regression): [textColumn]'s `text` lambda used to be typed
 * `(R) -> String` and its cell renderer unconditionally ran the result through [sanitizeUntrustedI18nText] --
 * correct for untrusted DTO fields, but WRONG for a caller that deliberately returns a `tr(...)` result (e.g.
 * `if (x) tr("Ja") else tr("Nein")`): stripping the marker breaks translation resolution for every non-German
 * locale (the column would show the raw German msgid instead of the translated text). [text] now returns [Any]:
 * a plain `String` stays unconditionally sanitized (untrusted, never decided by content), a [TrArg] (produced only
 * by [trusted] on the direct result of `tr(...)`/money-token helpers) passes through untouched and keeps its
 * marker, so a mounted table cell re-resolves it on every render.
 */
class TextColumnTrustDomTest {
    private data class Row(
        val untrustedField: String,
    )

    @Test
    fun aTrustedTrResult_keepsItsMarker_soItStaysLiveTranslatable() {
        withTranslations(mapOf("Ja" to "Yes")) {
            withMountedRoot<Unit>("text-column-trusted-test") { root, element ->
                val column = textColumn<Row>(title = tr("Spalte")) { trusted(tr("Ja")) }
                column.cell(root, Row(untrustedField = "irrelevant"))
                assertEquals("Yes", element().textContent)
            }
        }
    }

    @Test
    fun aPlainUntrustedStringReturn_isSanitizedAsBefore() {
        withMountedRoot("text-column-untrusted-test") { root, element ->
            val forgedPayload = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
            val column = textColumn<Row>(title = tr("Spalte")) { it.untrustedField }
            column.cell(root, Row(untrustedField = forgedPayload))
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
    }

    @Test
    fun anUnwrappedTrResultReturnedAsAPlainString_isSanitizedLikeAnyOtherUntrustedString() {
        // A caller returning `tr("Ja")` directly (no `trusted(...)` wrap) gets the SAME treatment as any other
        // plain String -- trust is a type, never decided by content, exactly like `trFormat`'s own argument rule.
        withTranslations(mapOf("Ja" to "Yes")) {
            withMountedRoot<Unit>("text-column-unwrapped-tr-test") { root, element ->
                val column = textColumn<Row>(title = tr("Spalte")) { tr("Ja") }
                column.cell(root, Row(untrustedField = "irrelevant"))
                val text = element().textContent.orEmpty()
                assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive an unwrapped tr() result: $text")
            }
        }
    }
}
