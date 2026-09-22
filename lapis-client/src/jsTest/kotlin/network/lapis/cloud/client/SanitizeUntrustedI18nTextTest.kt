package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Security audit W6b follow-up (major finding 2): [sanitizeUntrustedI18nText] is the sole sanitization boundary for
 * every untrusted plain-`String` argument in this file ([I18nCatalogManager.gettext], [trFormatArgPayload]) and for
 * server-/member-controlled text used directly as widget content ([MotionsScreen]'s `vote.title`/`option.label`,
 * `renderVoteSection`). No test referenced the function directly before this file -- the single-pass "delete and
 * rescan" nesting bug below would have been caught immediately by a 3-line test.
 */
class SanitizeUntrustedI18nTextTest {
    @Test
    fun stripsThePlainMarkerSentinelAndArgSeparator() {
        assertEquals("Gewinner", sanitizeUntrustedI18nText(KV_I18N_MARKER + "Gewinner"))
        assertEquals("abc", sanitizeUntrustedI18nText("a" + I18N_ARG_SEPARATOR + "b" + MONEY_SENTINEL + "c"))
    }

    @Test
    fun leavesOrdinaryTextUntouched() {
        assertEquals("Klausurort Braunschweig", sanitizeUntrustedI18nText("Klausurort Braunschweig"))
    }

    // Security audit W6b round 4: the three control sequences stripped individually and combined, as the "Untrusted
    // widget text" section of the ui-ux-guideline.adoc requires.
    @Test
    fun stripsTheArgSeparatorAlone() {
        assertEquals("ab", sanitizeUntrustedI18nText("a" + I18N_ARG_SEPARATOR + "b"))
    }

    @Test
    fun stripsTheMoneySentinelAlone() {
        assertEquals("ab", sanitizeUntrustedI18nText("a" + MONEY_SENTINEL + "b"))
    }

    @Test
    fun stripsTheMarkerAlone() {
        assertEquals("ab", sanitizeUntrustedI18nText("a" + KV_I18N_MARKER + "b"))
    }

    @Test
    fun stripsAMarkerReconstructedByDeletingANestedOccurrence() {
        // Security audit W6b follow-up (major finding 2, empirically verified nesting bug): a single `replace` pass
        // deletes the INNER marker occurrence and splices its neighbours together into a brand-new, intact marker --
        // "###KvI" + KV_I18N_MARKER + "18nS###" becomes exactly "###KvI18nS###" (the marker itself) after one pass.
        // Sanitizing must reach a fixed point, not stop after a single pass.
        val nestedPayload = "###KvI" + KV_I18N_MARKER + "18nS###Gewinner"
        val sanitized = sanitizeUntrustedI18nText(nestedPayload)
        assertFalse(sanitized.contains(KV_I18N_MARKER), "a reconstructed marker must not survive sanitization: $sanitized")
    }

    @Test
    fun stripsADeeplyNestedForgedMoneyPayload_evenAfterSeveralReconstructionRounds() {
        // Several nested markers chained together, so a fixed-point implementation must actually iterate rather than
        // stop after the second pass by coincidence.
        val innerMost = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
        val nested = "###KvI" + ("###KvI" + innerMost + "18nS###") + "18nS###"
        val sanitized = sanitizeUntrustedI18nText(nested)
        assertFalse(sanitized.contains(KV_I18N_MARKER), "no reconstructed marker may survive: $sanitized")
        assertFalse(sanitized.contains(MONEY_SENTINEL), "no sentinel may survive: $sanitized")
    }

    @Test
    fun terminatesAndReturnsEmptyString_forAStringThatIsNothingButTheMarker() {
        assertEquals("", sanitizeUntrustedI18nText(KV_I18N_MARKER))
        assertEquals("", sanitizeUntrustedI18nText(KV_I18N_MARKER + KV_I18N_MARKER))
    }

    // Security audit W6b, round 7 (major finding 3): KVision's own `Widget.trans` render path resolves the PLURAL
    // marker (`###KvI18nP###`, `ntr()`) exactly as unconditionally as the singular one -- a forged plural payload in
    // untrusted DTO text (a motion rationale, a receipt filename, ...) reached translated catalog text even after
    // this function had already run, because only [KV_I18N_MARKER] was stripped.
    @Test
    fun stripsThePluralMarkerAlone() {
        assertEquals("ab", sanitizeUntrustedI18nText("a" + KV_I18N_MARKER_PLURAL + "b"))
    }

    @Test
    fun stripsAForgedPluralSpoofingPayload() {
        val forged = KV_I18N_MARKER_PLURAL + "Gewinner" + KV_I18N_MARKER_PLURAL + "x" + KV_I18N_MARKER_PLURAL + "1"
        val sanitized = sanitizeUntrustedI18nText(forged)
        assertFalse(sanitized.contains(KV_I18N_MARKER_PLURAL), "no plural marker may survive: $sanitized")
        assertEquals("Gewinnerx1", sanitized)
    }

    @Test
    fun stripsAPluralMarkerReconstructedByDeletingANestedOccurrence() {
        // Same nesting-splice bug as the singular marker (see stripsAMarkerReconstructedByDeletingANestedOccurrence),
        // for the multi-character plural marker.
        val nestedPayload = "###KvI" + KV_I18N_MARKER_PLURAL + "18nP###Gewinner"
        val sanitized = sanitizeUntrustedI18nText(nestedPayload)
        assertFalse(sanitized.contains(KV_I18N_MARKER_PLURAL), "a reconstructed plural marker must not survive sanitization: $sanitized")
    }

    @Test
    fun stripsBothMarkersMixedTogether() {
        val mixed = KV_I18N_MARKER + "a" + KV_I18N_MARKER_PLURAL + "b" + KV_I18N_MARKER + "c" + KV_I18N_MARKER_PLURAL
        val sanitized = sanitizeUntrustedI18nText(mixed)
        assertFalse(sanitized.contains(KV_I18N_MARKER), "no singular marker may survive: $sanitized")
        assertFalse(sanitized.contains(KV_I18N_MARKER_PLURAL), "no plural marker may survive: $sanitized")
        assertEquals("abc", sanitized)
    }
}
