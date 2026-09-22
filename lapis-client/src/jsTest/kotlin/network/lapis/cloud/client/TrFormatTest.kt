package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Audit V1.4.27 (B): [trFormat] -- a `tr(...)` string with arguments that stays live-translatable. KVision's own
 * `tr(key)` takes no arguments and resolves a marker only as a PREFIX, so `gettext("Summe %1", tr("Einnahmen"))`
 * showed `Summe ###KvI18nS###Einnahmen`. `I18nCatalogManager` decodes the composed key.
 *
 * Security audit W6b follow-up (major finding 1): a `tr(...)`/[moneyToken]/[ltrToken]/... argument must be wrapped
 * with [trusted] to be passed through unresolved -- trust in [trFormat] is a TYPE ([TrArg]), never a string prefix, so
 * every plain `String` argument here (even one produced by `tr(...)` but passed unwrapped) is unconditionally
 * sanitized. [VoteSectionRenderingDomTest] proves the forgery this closes end to end.
 */
class TrFormatTest {
    @Test
    fun aComposedStringKeepsTheTemplateMarkerAsItsPrefix_soAWidgetReResolvesIt() {
        val composed = trFormat(tr("Summe %1"), trusted(tr("Einnahmen")))
        assertTrue(composed.startsWith(KV_I18N_MARKER), "the widget only re-resolves a string that starts with the marker")
        assertTrue(composed.contains(I18N_ARG_SEPARATOR))
    }

    @Test
    fun resolvesTemplateAndTranslatedArgument_inTheSourceLanguage() {
        assertEquals("Summe Einnahmen", I18n.trans(trFormat(tr("Summe %1"), trusted(tr("Einnahmen")))))
    }

    @Test
    fun resolvesBothInTheCurrentLanguage_whenBothAreTranslated() {
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            assertEquals("Total Income", I18n.trans(trFormat(tr("Summe %1"), trusted(tr("Einnahmen")))))
        }
    }

    @Test
    fun aPlainArgumentIsTakenAsItIs_evenIfACatalogEntryWithThatTextExists() {
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            assertEquals("Total Einnahmen", I18n.trans(trFormat(tr("Summe %1"), "Einnahmen")))
        }
    }

    @Test
    fun anUnwrappedTrResultIsSanitizedLikeAnyOtherPlainArgument() {
        // A `tr(...)` result NOT wrapped with `trusted` is untrusted by TYPE, regardless of its marker-prefixed content --
        // it is sanitized (the marker stripped) like any other plain String, so it does NOT resolve through the catalog.
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            val resolved = I18n.trans(trFormat(tr("Summe %1"), tr("Einnahmen")))
            assertEquals("Total Einnahmen", resolved)
            assertTrue(!resolved.contains("###"), "an unwrapped tr() result must not leak the marker either: $resolved")
        }
    }

    @Test
    fun severalArgumentsFillTheirPlaceholdersInOrder() {
        withTranslations(mapOf("%1 von %2" to "%2 in total, %1 shown", "Spendern" to "donors")) {
            assertEquals("3 in total, 2 shown", I18n.trans(trFormat(tr("%1 von %2"), "2", "3")))
        }
        assertEquals("2 von Spendern", I18n.trans(trFormat(tr("%1 von %2"), "2", trusted(tr("Spendern")))))
    }

    @Test
    fun anUntranslatedTemplateFallsBackToItsKey_neverToAMarker() {
        val resolved = I18n.trans(trFormat(tr("Ganz neuer Satz %1"), trusted(tr("Einnahmen"))))
        assertEquals("Ganz neuer Satz Einnahmen", resolved)
        assertTrue(!resolved.contains("###"))
    }

    @Test
    fun ordinaryGettextAndTrAreUntouched() {
        assertEquals("Hallo Welt", gettext("Hallo %1", "Welt"))
        assertEquals("Einnahmen", I18n.trans(tr("Einnahmen")))
        assertEquals("plain", I18n.trans("plain"))
    }

    // Security audit W6b, round 5 (minor finding): `trFormat`'s `vararg args` was widened from `String` to `Any` so
    // a `TrArg` (from `trusted(...)`) could be passed alongside plain `String` arguments -- a type that used to be
    // caught by the COMPILER for any other argument type is now only caught at RUNTIME, by `trFormatArgPayload`'s
    // `error(...)` branch. This test closes the gap the finding called out: no test previously asserted that this
    // runtime guard actually fires, so a future accidental widening of the accepted types (or its silent removal)
    // would not be caught here either.
    @Test
    fun aNonStringNonTrArgArgument_failsFastAtCallTimeRatherThanSilentlyStringifying() {
        val exception =
            assertFailsWith<IllegalStateException> {
                trFormat(tr("Stufe %1"), 5)
            }
        assertTrue(exception.message.orEmpty().contains("trFormat argument must be a String or a TrArg"))
    }

    // Security audit W6b follow-up (major finding 1, round 2): `I18nCatalogManager.gettext(key, vararg args)` --
    // reached directly by dozens of `gettext("... %1 ...", <server field>)` call sites across the client, not only
    // through `trFormat` -- used to resolve a `String` argument that STARTED WITH `KV_I18N_MARKER` through a nested
    // `gettext` call, exactly the content-based trust decision `trFormat`'s own KDoc says must never happen. These
    // tests prove that path is now sanitized like any other untrusted plain argument, regardless of argument position.

    @Test
    fun aGettextArgumentCarryingAForgedMoneyPayload_isNeverResolvedIntoAnAmount() {
        // Sanitization strips [KV_I18N_MARKER] and [MONEY_SENTINEL], never the kind char/digits after them -- so what
        // survives is inert plain text ("L9999"), never a formatted amount, and never the control characters.
        val forgedArgument = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
        val resolved = gettext("Teilnehmende: %1", forgedArgument)
        assertEquals("Teilnehmende: L9999", resolved)
        assertTrue(!resolved.contains("99,99"), "a forged amount must never render: $resolved")
        assertTrue(!resolved.contains(KV_I18N_MARKER), "the marker must never leak: $resolved")
        assertTrue(!resolved.contains(MONEY_SENTINEL), "the sentinel must never leak: $resolved")
    }

    @Test
    fun aGettextArgumentCarryingAForgedMoneyPayload_isSanitizedRegardlessOfItsPositionInTheTemplate() {
        val forgedArgument = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_EUR + "123456"
        val resolved = gettext("%1 · %2 · %3", "vorher", forgedArgument, "nachher")
        assertEquals("vorher · E123456 · nachher", resolved)
        assertTrue(!resolved.contains("123.456"), "a forged amount must never render: $resolved")
        assertTrue(!resolved.contains(KV_I18N_MARKER))
        assertTrue(!resolved.contains(MONEY_SENTINEL))
    }

    // Security audit W6b, round 5 (minor finding): `substitute` used to replace `%1`, `%2`, ... one at a time over
    // the RUNNING result string, forward -- so a value substituted for an earlier placeholder could itself contain
    // text like "%4" that a LATER iteration's `.replace("%4", ...)` would then match too, spoofing which argument's
    // value ends up under which placeholder. This is display-spoofing of a genuine, trusted value (never a forged
    // amount -- the value itself is never attacker-chosen), but it is real: an attacker-controlled display name could
    // shift a later, trusted LTR amount into the wrong slot.
    @Test
    fun anArgumentThatLooksLikeAPlaceholder_neverSwallowsALaterArguments() {
        val forgedName = "%4"
        val resolved = gettext("%1: %2, %3 · belastet: %4", forgedName, "Ja", "10 LTR", "5 LTR")
        assertEquals("%4: Ja, 10 LTR · belastet: 5 LTR", resolved)
    }

    // Security audit W6b, round 7 (major finding 3, part 2): `substitute`'s placeholder regex (`%(\d+)`) captures an
    // UNBOUNDED digit run, which can come from an untranslated key/template (`lookup` returns an unknown catalog
    // key unchanged). Reachable from attacker-controlled text via KVision's own plural `trans` dispatch: a forged
    // `"###KvI18nP###%2147483648###KvI18nP###x###KvI18nP###1"` payload resolves into exactly this
    // `gettext(key = "%2147483648", args = [1])` shape. A bare `.toInt()` on that digit run threw
    // `NumberFormatException` (`Int` overflow) and crashed the render; `toIntOrNull()` must instead leave an
    // out-of-range run untouched, exactly like a genuinely out-of-bounds index already does.
    @Test
    fun aPlaceholderIndexThatOverflowsIntRange_isLeftVerbatimRatherThanCrashing() {
        val resolved = gettext("%2147483648 · %1", "wert")
        assertEquals("%2147483648 · wert", resolved)
    }

    @Test
    fun aPlaceholderIndexThatIsOutOfBoundsOrOverflowing_bothLeaveTheirPlaceholderVerbatim() {
        // Out-of-bounds (existing behaviour) and int-overflow (round 7) must behave identically: verbatim
        // passthrough, never a thrown exception either way -- both exercised with a non-empty args array so
        // `substitute` actually reaches the placeholder regex instead of short-circuiting on `args.isEmpty()`.
        assertEquals("%5 bleibt stehen (wert)", gettext("%5 bleibt stehen (%1)", "wert"))
        assertEquals("%99999999999 bleibt auch stehen (wert)", gettext("%99999999999 bleibt auch stehen (%1)", "wert"))
    }

    @Test
    fun aGettextArgumentThatIsAPlainTrResultAlreadyResolved_isNotResolvedAgainAndNotSanitizedAway() {
        // Every helper that returns translated text (e.g. `erasureModeLabel`) does so via `gettext(...)` itself, which
        // resolves IMMEDIATELY and returns plain, non-marker-prefixed text -- never a `tr(...)` marker string. Such an
        // already-resolved argument must pass through untouched.
        withTranslations(mapOf("Anonymisierung" to "Anonymized")) {
            val alreadyResolved = gettext("Anonymisierung")
            assertEquals("Anonymized", alreadyResolved)
            assertEquals("Modus: Anonymized", gettext("Modus: %1", alreadyResolved))
        }
    }
}
