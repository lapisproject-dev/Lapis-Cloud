package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventTicketCode]'s canonicalization grammar. */
class EventTicketCodeTest {
    private val validCode = "ABCD2345GHJKMN23" // 16 chars, all from ALPHABET.

    @Test
    fun canonicalize_acceptsAlreadyCanonicalCode() {
        assertEquals(validCode, EventTicketCode.canonicalize(validCode))
    }

    @Test
    fun canonicalize_stripsHyphensAndWhitespace() {
        assertEquals(validCode, EventTicketCode.canonicalize("  ABCD-2345-GHJK-MN23  "))
    }

    @Test
    fun canonicalize_lowercaseIsUppercased() {
        assertEquals(validCode, EventTicketCode.canonicalize(validCode.lowercase()))
    }

    @Test
    fun canonicalize_repairsOToZero() {
        // "O" is not in ALPHABET -- a typo for "0".
        assertEquals("A0CD2345GHJKMN23", EventTicketCode.canonicalize("AOCD2345GHJKMN23"))
    }

    @Test
    fun canonicalize_repairsIAndLToOne() {
        assertEquals("A1CD2345GHJKMN23", EventTicketCode.canonicalize("AICD2345GHJKMN23"))
        assertEquals("A1CD2345GHJKMN23", EventTicketCode.canonicalize("ALCD2345GHJKMN23"))
    }

    @Test
    fun canonicalize_rejectsU_notInAlphabetAndNotRepaired() {
        assertNull(EventTicketCode.canonicalize("AUCD2345GHJKMN23"))
    }

    @Test
    fun canonicalize_rejectsWrongLength() {
        assertNull(EventTicketCode.canonicalize("ABCD2345"))
        assertNull(EventTicketCode.canonicalize(validCode + "AB"))
    }

    @Test
    fun canonicalize_rejectsNull() {
        assertNull(EventTicketCode.canonicalize(null))
    }

    @Test
    fun canonicalize_rejectsBlank() {
        assertNull(EventTicketCode.canonicalize(""))
        assertNull(EventTicketCode.canonicalize("   "))
    }

    @Test
    fun formatForDisplay_groupsInFours() {
        assertEquals("ABCD-2345-GHJK-MN23", EventTicketCode.formatForDisplay(validCode))
    }

    @Test
    fun formatForDisplay_canonicalize_roundTrips() {
        val displayed = EventTicketCode.formatForDisplay(validCode)
        assertEquals(validCode, EventTicketCode.canonicalize(displayed))
    }

    @Test
    fun extractAndCanonicalize_bareCode() {
        assertEquals(validCode, EventTicketCode.extractAndCanonicalize(validCode))
    }

    @Test
    fun extractAndCanonicalize_ticketUrl() {
        assertEquals(
            validCode,
            EventTicketCode.extractAndCanonicalize("https://example.org/veranstaltung/sommerfest/ticket?code=$validCode"),
        )
    }

    @Test
    fun extractAndCanonicalize_ticketPdfUrl() {
        assertEquals(
            validCode,
            EventTicketCode.extractAndCanonicalize("https://example.org/veranstaltung/sommerfest/ticket.pdf?code=$validCode"),
        )
    }

    @Test
    fun extractAndCanonicalize_urlWithAdditionalQueryParameters() {
        assertEquals(
            validCode,
            EventTicketCode.extractAndCanonicalize("https://example.org/veranstaltung/s/ticket?utm_source=x&code=$validCode&foo=bar"),
        )
    }

    @Test
    fun extractAndCanonicalize_urlWithoutCodeParam_returnsNull() {
        assertNull(EventTicketCode.extractAndCanonicalize("https://example.org/veranstaltung/sommerfest/ticket?foo=bar"))
    }

    @Test
    fun extractAndCanonicalize_garbage_returnsNull() {
        assertNull(EventTicketCode.extractAndCanonicalize("not a code at all"))
    }

    @Test
    fun extractAndCanonicalize_null_returnsNull() {
        assertNull(EventTicketCode.extractAndCanonicalize(null))
    }
}
