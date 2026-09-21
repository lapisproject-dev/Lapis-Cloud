package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit V1.4.27 (B): [trFormat] -- a `tr(...)` string with arguments that stays live-translatable. KVision's own
 * `tr(key)` takes no arguments and resolves a marker only as a PREFIX, so `gettext("Summe %1", tr("Einnahmen"))`
 * showed `Summe ###KvI18nS###Einnahmen`. `I18nCatalogManager` decodes the composed key.
 */
class TrFormatTest {
    @Test
    fun aComposedStringKeepsTheTemplateMarkerAsItsPrefix_soAWidgetReResolvesIt() {
        val composed = trFormat(tr("Summe %1"), tr("Einnahmen"))
        assertTrue(composed.startsWith(KV_I18N_MARKER), "the widget only re-resolves a string that starts with the marker")
        assertTrue(composed.contains(I18N_ARG_SEPARATOR))
    }

    @Test
    fun resolvesTemplateAndTranslatedArgument_inTheSourceLanguage() {
        assertEquals("Summe Einnahmen", I18n.trans(trFormat(tr("Summe %1"), tr("Einnahmen"))))
    }

    @Test
    fun resolvesBothInTheCurrentLanguage_whenBothAreTranslated() {
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            assertEquals("Total Income", I18n.trans(trFormat(tr("Summe %1"), tr("Einnahmen"))))
        }
    }

    @Test
    fun aPlainArgumentIsTakenAsItIs_evenIfACatalogEntryWithThatTextExists() {
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            assertEquals("Total Einnahmen", I18n.trans(trFormat(tr("Summe %1"), "Einnahmen")))
        }
    }

    @Test
    fun severalArgumentsFillTheirPlaceholdersInOrder() {
        withTranslations(mapOf("%1 von %2" to "%2 in total, %1 shown", "Spendern" to "donors")) {
            assertEquals("3 in total, 2 shown", I18n.trans(trFormat(tr("%1 von %2"), "2", "3")))
        }
        assertEquals("2 von Spendern", I18n.trans(trFormat(tr("%1 von %2"), "2", tr("Spendern"))))
    }

    @Test
    fun anUntranslatedTemplateFallsBackToItsKey_neverToAMarker() {
        val resolved = I18n.trans(trFormat(tr("Ganz neuer Satz %1"), tr("Einnahmen")))
        assertEquals("Ganz neuer Satz Einnahmen", resolved)
        assertTrue(!resolved.contains("###"))
    }

    @Test
    fun ordinaryGettextAndTrAreUntouched() {
        assertEquals("Hallo Welt", gettext("Hallo %1", "Welt"))
        assertEquals("Einnahmen", I18n.trans(tr("Einnahmen")))
        assertEquals("plain", I18n.trans("plain"))
    }
}
