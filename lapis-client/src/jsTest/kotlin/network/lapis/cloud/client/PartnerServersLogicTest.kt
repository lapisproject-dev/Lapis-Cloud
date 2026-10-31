package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.domain.PartnerProbeResultDto
import network.lapis.cloud.shared.domain.PartnerServerDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.9.244 -- the DOM-free decisions and fixed sentences of the partner page. */
class PartnerServersLogicTest {
    private fun partner(
        active: Boolean = true,
        blocked: Boolean = false,
        status: PartnerFetchStatus = PartnerFetchStatus.OK,
        next: LocalDateTime? = LocalDateTime(2026, 11, 5, 11, 7),
        displayName: String? = null,
        remoteName: String? = null,
    ) = PartnerServerDto(
        id = "p1",
        host = "partner.beispiel.de",
        displayName = displayName,
        remoteName = remoteName,
        active = active,
        blocked = blocked,
        status = status,
        lastFetchAt = null,
        nextFetchAt = next,
        consecutiveFailures = 0,
        eventCount = 3,
        lastError = null,
    )

    @Test
    fun blockedWinsOverPausedWhichWinsOverTheFetchResult() {
        assertEquals(PartnerStatusKind.BLOCKED, PartnerServersLogic.statusKind(partner(blocked = true, active = false)))
        assertEquals(PartnerStatusKind.PAUSED, PartnerServersLogic.statusKind(partner(active = false)))
        assertEquals(PartnerStatusKind.FETCHED, PartnerServersLogic.statusKind(partner()))
        assertEquals(PartnerStatusKind.WAITING, PartnerServersLogic.statusKind(partner(status = PartnerFetchStatus.ERROR)))
        assertEquals(PartnerStatusKind.WAITING, PartnerServersLogic.statusKind(partner(status = PartnerFetchStatus.NEVER)))
    }

    @Test
    fun everyKindHasItsOwnSymbolAndItsOwnWords_neverColourAlone() {
        val kinds = PartnerStatusKind.entries
        assertEquals(kinds.size, kinds.map { it.symbol }.toSet().size)
        val texts =
            listOf(
                partner(blocked = true),
                partner(active = false),
                partner(),
                partner(status = PartnerFetchStatus.ERROR),
            ).map { PartnerServersLogic.statusText(it) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }

    @Test
    fun aWaitingPartnerWithoutASlotSaysItWaitsForTheFirstFetch() {
        assertEquals("Wartet auf den ersten Abruf", PartnerServersLogic.statusText(partner(status = PartnerFetchStatus.NEVER, next = null)))
    }

    @Test
    fun theTitleIsTheOwnLabel_thenTheAnnouncedName_thenTheHost() {
        assertEquals("Mein Name", PartnerServersLogic.title(partner(displayName = "Mein Name", remoteName = "Fremd")))
        assertEquals("Fremd", PartnerServersLogic.title(partner(remoteName = "Fremd")))
        assertEquals("partner.beispiel.de", PartnerServersLogic.title(partner()))
        assertFalse(PartnerServersLogic.showsHostLine(partner()))
        assertTrue(PartnerServersLogic.showsHostLine(partner(remoteName = "Fremd")))
    }

    @Test
    fun theProbeNeedsAnAddress_andTheBlocklistIsClosedWhileEmpty() {
        assertFalse(PartnerServersLogic.canProbe("  "))
        assertTrue(PartnerServersLogic.canProbe("partner.beispiel.de"))
        assertFalse(PartnerServersLogic.blocklistOpen(0))
        assertTrue(PartnerServersLogic.blocklistOpen(2))
    }

    @Test
    fun everyErrorCodeAndEveryRejectionHasASentence() {
        PartnerFetchErrorCode.entries.forEach { assertTrue(PartnerServersTexts.fetchError(it).isNotBlank(), "no text for $it") }
        PartnerAddressRejectionCode.entries.forEach { assertTrue(PartnerServersTexts.rejection(it).isNotBlank(), "no text for $it") }
    }

    @Test
    fun theAddressReasonWinsOverTheFetchError_andTheSentencesDiffer() {
        val both = PartnerProbeResultDto(false, null, 0, PartnerFetchErrorCode.TIMEOUT, PartnerAddressRejectionCode.NOT_HTTPS)
        assertEquals(PartnerServersTexts.rejection(PartnerAddressRejectionCode.NOT_HTTPS), PartnerServersTexts.probeFailure(both))
        val timeout = PartnerProbeResultDto(false, "x.de", 0, PartnerFetchErrorCode.TIMEOUT, null)
        assertNotEquals(PartnerServersTexts.probeFailure(timeout), PartnerServersTexts.probeFailure(both))
        assertTrue(PartnerServersTexts.fetchError(PartnerFetchErrorCode.TIMEOUT).contains("10 Sekunden"))
        assertTrue(PartnerServersTexts.fetchError(PartnerFetchErrorCode.TOO_LARGE).contains("512 KiB"))
    }

    @Test
    fun theSavedSentenceNamesTheHostAndTheCount() {
        val text = PartnerServersTexts.saved("partner.beispiel.de", 12)
        assertTrue(text.contains("partner.beispiel.de") && text.contains("12"))
    }
}
