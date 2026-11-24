package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AuctionFederationSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.322 (step c2) -- the decisions of the auction card on the partner page. */
class PartnerAuctionLogicTest {
    @Test
    fun aConfirmationIsNeededOnlyWhileAuctionsAreShared() {
        assertFalse(PartnerServersLogic.auctionDialogNeeded(0))
        assertTrue(PartnerServersLogic.auctionDialogNeeded(1))
        assertTrue(PartnerServersLogic.auctionDialogNeeded(20))
    }

    @Test
    fun theReachOffersTwoLevelsAndReadsAnythingElseAsOne() {
        assertEquals(1, PartnerServersLogic.auctionHopSelection(1))
        assertEquals(2, PartnerServersLogic.auctionHopSelection(2))
        assertEquals(1, PartnerServersLogic.auctionHopSelection(3))
        assertEquals(1, PartnerServersLogic.auctionHopSelection(0))
    }

    @Test
    fun theTextsCarryTheNumberAndTheHostAsPlainArguments() {
        assertTrue(PartnerServersLogic.auctionSwitchOffText(1).contains("1"))
        assertTrue(PartnerServersLogic.auctionSwitchOffText(3).contains("3"))
        val text = PartnerServersLogic.partnerAuctionOffText("<b>x</b>.beispiel.de")
        assertTrue(text.contains("<b>x</b>.beispiel.de"), "set as text by the dialog, never as markup")
        assertFalse(text.contains("%1"))
        assertTrue(PartnerServersLogic.auctionSharedLine(2).contains("2"))
    }

    @Test
    fun theSettingsLinesNameTheSharedCountAndTheMissingKey() {
        val off = AuctionFederationSettingsDto(enabled = false, hopLimit = 1, sharedActive = 2, withdrawalsActive = 1)
        val offLines = PartnerServersLogic.auctionSettingsLines(off)
        assertEquals(2, offLines.size)
        val on = AuctionFederationSettingsDto(enabled = true, hopLimit = 2, feedKeyAvailable = false, feedRefusedSinceStart = 3)
        val onLines = PartnerServersLogic.auctionSettingsLines(on)
        assertTrue(onLines.any { it.contains("LAPIS_SECRET_ENCRYPTION_KEY") })
        assertTrue(onLines.any { it.contains("3") })
    }

    @Test
    fun theSwitchOnTheRowIsOfferedOnlyWhileTheExchangeIsOn() {
        assertTrue(PartnerServersLogic.auctionPartnerSharingVisible(true))
        assertFalse(PartnerServersLogic.auctionPartnerSharingVisible(false))
    }
}
