package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolFederationReachDto
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.317 -- the DOM-free decisions of the hint list and of the reach text. */
class PartnerCarpoolHintsUiLogicTest {
    private val logic = PartnerCarpoolHintsUiLogic

    private fun hint(
        from: String = "38",
        to: String = "30",
    ) = PartnerCarpoolHintDto(LocalDate(2026, 12, 1), from, to, "o.beispiel.de")

    @Test
    fun theFilterKeepsTwoDigitsOnly() {
        assertEquals("38", logic.sanitizeFilter("38"))
        assertEquals("38", logic.sanitizeFilter("3a8x9"))
        assertEquals("", logic.sanitizeFilter("abc"))
        assertEquals("12", logic.sanitizeFilter("123"))
    }

    @Test
    fun aHintMatchesOnItsStartOrItsDestinationByPrefix() {
        assertTrue(logic.matches(hint(), ""))
        assertTrue(logic.matches(hint(from = "38", to = "30"), "38"))
        assertTrue(logic.matches(hint(from = "10", to = "38"), "38"))
        assertTrue(logic.matches(hint(from = "38", to = "30"), "3"))
        assertFalse(logic.matches(hint(from = "10", to = "20"), "3"))
        assertFalse(logic.matches(hint(from = "10", to = "20"), "38"))
    }

    @Test
    fun theThreeEmptyTexts() {
        assertEquals("Zurzeit liegen keine Hinweise von Partnerorganisationen vor.", logic.emptyText(0, 0, "", false))
        assertEquals("Zurzeit liegen keine Hinweise von Partnerorganisationen vor.", logic.emptyText(0, 0, "38", true))
        assertEquals("Keine Treffer für PLZ-Gebiet 38 in den geladenen Hinweisen.", logic.emptyText(5, 0, "38", true))
        assertEquals("Keine Angebote für PLZ-Gebiet 38.", logic.emptyText(5, 0, "38", false))
        assertNull(logic.emptyText(5, 2, "38", true))
    }

    @Test
    fun theRowTexts() {
        assertEquals("PLZ-Gebiet 38 → PLZ-Gebiet 30", logic.routeText(hint()))
        assertEquals("von o.beispiel.de – Details und Kontakt nur bei dieser Organisation", logic.originText("o.beispiel.de"))
    }

    @Test
    fun theClientNeverAsksForPageTen() {
        assertTrue(logic.canLoadMore(hasMore = true, page = 8))
        assertFalse(logic.canLoadMore(hasMore = true, page = 9))
        assertFalse(logic.canLoadMore(hasMore = false, page = 0))
    }

    @Test
    fun theRecipientsSentence() {
        assertEquals("Derzeit ruft keine Partnerorganisation Hinweise ab.", logic.recipientsSentence(0))
        assertEquals("Abrufen kann ihn 1 Partnerorganisation.", logic.recipientsSentence(1))
        assertEquals("Abrufen können ihn 7 Partnerorganisationen.", logic.recipientsSentence(7))
        assertEquals("Abrufen können ihn mehr als 50 Partnerorganisationen.", logic.recipientsSentence(50, truncated = true))
    }

    @Test
    fun theReachSentenceFollowsTheHopLimit_andAnUnknownValueIsTheMostConservative() {
        val one = "Er geht nur an direkte Partner und wird von diesen nicht weitergegeben."
        assertEquals(one, logic.reachSentence(1))
        assertEquals("Er geht an direkte Partner und kann von diesen an deren Partner weitergegeben werden.", logic.reachSentence(2))
        assertEquals(
            "Er geht an direkte Partner und kann über deren Partner noch eine Stufe weiter weitergegeben werden.",
            logic.reachSentence(3),
        )
        assertEquals(one, logic.reachSentence(0))
        assertEquals(one, logic.reachSentence(9))
    }

    @Test
    fun fiveNamesAreFolded_andTheReachSentenceNeedsSomebodyToFetch() {
        assertFalse(logic.collapsed(4))
        assertTrue(logic.collapsed(5))
        assertFalse(logic.showReachSentence(CarpoolFederationReachDto(enabled = true, partners = emptyList(), maxHops = 2)))
        assertFalse(logic.showReachSentence(CarpoolFederationReachDto(enabled = false, partners = listOf("a"), maxHops = 2)))
        assertTrue(logic.showReachSentence(CarpoolFederationReachDto(enabled = true, partners = listOf("a"), maxHops = 2)))
    }
}
