package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * W6a S5: an amount that is widget content follows a language switch WITHOUT a screen rebuild (the renderer re-resolves the
 * [moneyToken] on every render), and neither the marker nor the sentinel ever shows in the DOM. Only this class and
 * `LanguageChangeDomTest` may touch `I18n.language` (its setter restarts the root), always restored in `finally`.
 */
class MoneyTokenDomTest {
    private fun leaks(text: String): Boolean =
        text.contains(KV_I18N_MARKER) || text.contains(MONEY_SENTINEL) || text.contains(I18N_ARG_SEPARATOR)

    @Test
    fun moneySpan_rendersLocalized_andFollowsALanguageSwitch() {
        withMountedRoot("money-token-switch") { root, element ->
            val span = root.moneySpan(1234.5.toDecimal())
            assertEquals("1.234,50$NBSP€", span.getElement()?.textContent)
            try {
                I18n.language = "en"
                span.refresh()
                assertEquals("€1,234.50", element().querySelector("span")?.textContent)
            } finally {
                I18n.language = "de"
            }
            span.refresh()
            assertEquals("1.234,50$NBSP€", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun ltrSpan_keepsItsGlyph_andNeverLeaksTheMarker() {
        withMountedRoot("money-token-ltr") { root, element ->
            root.ltrSpan(12.5.toDecimal())
            assertEquals("◆ 12,50${NBSP}LTR", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun trFormat_withAMoneyToken_resolvesInsideTheTemplate() {
        withMountedRoot("money-token-trformat") { root, element ->
            root.div(trFormat(tr("Offen: %1"), moneyToken(1234.5.toDecimal())))
            assertEquals("Offen: 1.234,50$NBSP€", element().querySelector("div div, div")?.textContent?.trim())
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun serverTextThatLooksLikeAToken_isNotSpecial_unlessItCarriesTheControlCharacter() {
        withMountedRoot("money-token-forgery") { root, element ->
            root.span("Betrag E1234.5")
            assertEquals("Betrag E1234.5", element().querySelector("span")?.textContent)
            // even a forged payload cannot throw or render anything but text
            root.span(KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_EUR + "<img src=x onerror=alert(1)>")
            assertEquals(0, element().querySelectorAll("img").length)
        }
    }

    @Test
    fun plainAmountAndCountSpans_followALanguageSwitch() {
        withMountedRoot("money-token-plain") { root, element ->
            val plain = root.plainAmountSpan(3.0.toDecimal())
            val count = root.countSpan(1234.0.toDecimal())
            assertEquals("3,00", plain.getElement()?.textContent)
            assertEquals("1.234", count.getElement()?.textContent)
            try {
                I18n.language = "en"
                plain.refresh()
                count.refresh()
                assertEquals("3.00", plain.getElement()?.textContent)
                assertEquals("1,234", count.getElement()?.textContent)
            } finally {
                I18n.language = "de"
            }
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }
}
