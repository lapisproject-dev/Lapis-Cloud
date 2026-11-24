package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CarpoolFederationSettingsDto
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.CarpoolPullState
import network.lapis.cloud.shared.domain.CarpoolPullStatus
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.domain.PartnerProbeResultDto
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
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
    fun theProbeNeedsAnAddress() {
        assertFalse(PartnerServersLogic.canProbe("  "))
        assertTrue(PartnerServersLogic.canProbe("partner.beispiel.de"))
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

    @Test
    fun theDepthSelectFollowsTheSwitch() {
        assertTrue(PartnerServersLogic.depthSelectEnabled(true))
        assertFalse(PartnerServersLogic.depthSelectEnabled(false))
    }

    @Test
    fun theChainReadsNearestFirst_andALevelHasItsNumber() {
        assertEquals("über a.beispiel.de → b.beispiel.de", PartnerServersLogic.chainText(listOf("a.beispiel.de", "b.beispiel.de")))
        assertEquals("Stufe 3", PartnerServersLogic.levelText(3))
    }

    @Test
    fun theSummaryListsEveryWalkedLevelAndOnlyTheNonZeroCounters() {
        val quiet = PartnerRecursionSummaryDto(reachedByHop = listOf(2, 0), eventsByHop = listOf(5, 0))
        assertEquals(
            listOf("Stufe 2: 2 Server, 5 Veranstaltungen", "Stufe 3: 0 Server, 0 Veranstaltungen"),
            PartnerServersLogic.summaryLines(quiet),
        )
        val busy =
            PartnerRecursionSummaryDto(
                reachedByHop = listOf(1),
                eventsByHop = listOf(1),
                skippedBlocked = 1,
                skippedHiddenList = 2,
                skippedVisited = 3,
                skippedCap = 4,
                serverIdConflicts = 5,
                errors = 6,
                aborted = true,
            )
        val lines = PartnerServersLogic.summaryLines(busy)
        assertEquals(8, lines.size)
        assertEquals(lines.size, lines.toSet().size)
        assertTrue(lines.last().startsWith("Zeitbudget erreicht"))
    }

    @Test
    fun theRunStatusSaysWhenThereWasNoRunAndTotalsOtherwise() {
        assertEquals("Noch kein Lauf", PartnerServersLogic.runStatus(PartnerRecursionSettingsDto(true, 2, null, null)))
        val summary = PartnerRecursionSummaryDto(reachedByHop = listOf(2, 1), eventsByHop = listOf(4, 3))
        val status = PartnerServersLogic.runStatus(PartnerRecursionSettingsDto(true, 2, LocalDateTime(2026, 11, 5, 10, 0), summary))
        assertTrue(status.contains("3 Server · 7 Veranstaltungen"))
    }

    @Test
    fun theConflictCodeHasItsSentence() {
        assertEquals("Kennung bereits vergeben", PartnerServersTexts.fetchError(PartnerFetchErrorCode.SERVER_ID_CONFLICT))
    }

    @Test
    fun theViaThisPartnerLineAppearsOnlyWhenSomethingWasReached() {
        assertEquals(null, PartnerServersLogic.viaThisPartner(partner()))
        val with = partner().copy(discoveredServerCount = 2, discoveredEventCount = 9)
        assertEquals("Über diesen Partner: 2 Server, 9 Veranstaltungen", PartnerServersLogic.viaThisPartner(with))
    }

    @Test
    fun theCarpoolExchangeControlsFollowTheSwitch() {
        assertTrue(PartnerServersLogic.hopSelectVisible(true))
        assertFalse(PartnerServersLogic.hopSelectVisible(false))
        assertTrue(PartnerServersLogic.partnerSharingVisible(true))
        assertFalse(PartnerServersLogic.partnerSharingVisible(false))
    }

    @Test
    fun theReachPreselectsTheServerDefaultWhenNothingValidIsStored() {
        assertEquals(2, PartnerServersLogic.hopSelection(0))
        assertEquals(2, PartnerServersLogic.hopSelection(4))
        assertEquals(1, PartnerServersLogic.hopSelection(1))
        assertEquals(3, PartnerServersLogic.hopSelection(3))
        assertEquals(2, PartnerServersLogic.hopSelection(2))
    }

    @Test
    fun theCarpoolFeedLinesAppearOnlyWithTheExchangeOnAndSharingOn() {
        val shared = partner().copy(carpoolSharing = true)
        assertTrue(PartnerServersLogic.carpoolFeedLines(partner = shared, exchangeOn = false).isEmpty())
        assertTrue(PartnerServersLogic.carpoolFeedLines(partner = partner(), exchangeOn = true).isEmpty())
        val noKey = PartnerServersLogic.carpoolFeedLines(partner = shared, exchangeOn = true)
        assertEquals(2, noKey.size)
        val pinned = PartnerServersLogic.carpoolFeedLines(partner = shared.copy(carpoolKeyPinned = true), exchangeOn = true)
        assertEquals(1, pinned.size)
    }

    @Test
    fun theSettingsLinesNameTheMissingKeyAndTheRefusals() {
        val off = CarpoolFederationSettingsDto(enabled = false, hopLimit = 2)
        assertTrue(PartnerServersLogic.carpoolFeedSettingsLines(off).isEmpty())
        val on = CarpoolFederationSettingsDto(enabled = true, hopLimit = 2, feedKeyAvailable = true, feedRefusedSinceStart = 0)
        assertTrue(PartnerServersLogic.carpoolFeedSettingsLines(on).isEmpty())
        assertEquals(2, PartnerServersLogic.carpoolFeedSettingsLines(on.copy(feedKeyAvailable = false, feedRefusedSinceStart = 3)).size)
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(on.copy(feedRateLimitedSinceStart = 4)).size)
    }

    @Test
    fun theWithdrawalLinesAreShownEvenWithTheSwitchesOff() {
        // the settings line sits BEFORE the early return of a switched-off exchange: after switching off, the withdrawals are what is going on
        val off = CarpoolFederationSettingsDto(enabled = false, hopLimit = 2, carpoolWithdrawalsActive = 3)
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(off).size)
        assertTrue(PartnerServersLogic.carpoolFeedSettingsLines(off.copy(carpoolWithdrawalsActive = 0)).isEmpty())
        val on = CarpoolFederationSettingsDto(enabled = true, hopLimit = 2, feedKeyAvailable = true, carpoolWithdrawalsActive = 1)
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(on).size)
        // the partner line is shown with the partner's own switch off and with the exchange off; with nothing scoped there is no line
        val scoped = partner().copy(carpoolSharing = false, carpoolWithdrawalsScoped = 2)
        assertEquals(1, PartnerServersLogic.carpoolFeedLines(partner = scoped, exchangeOn = true).size)
        assertEquals(1, PartnerServersLogic.carpoolFeedLines(partner = scoped, exchangeOn = false).size)
        assertTrue(PartnerServersLogic.carpoolFeedLines(partner = partner().copy(carpoolSharing = false), exchangeOn = true).isEmpty())
        // with the switch on the scoped line comes first, then the usual lines
        val shared = partner().copy(carpoolSharing = true, carpoolKeyPinned = true, carpoolWithdrawalsScoped = 1)
        assertEquals(2, PartnerServersLogic.carpoolFeedLines(partner = shared, exchangeOn = true).size)
    }

    @Test
    fun thePullLinesNameTheLastPullItsCountsAndTheReceivedHints_andOnlyWithSharingOn() {
        val at = LocalDateTime(2026, 11, 19, 10, 0)
        val shared = partner().copy(carpoolSharing = true, carpoolKeyPinned = true)
        // never pulled and nothing received: no pull line
        assertEquals(1, PartnerServersLogic.carpoolFeedLines(partner = shared, exchangeOn = true).size)
        val pulled =
            shared.copy(
                carpoolPullAt = at,
                carpoolPullStatus = CarpoolPullStatus.OK,
                carpoolPullLastAccepted = 4,
                carpoolPullLastRejected = 1,
            )
        val lines = PartnerServersLogic.carpoolFeedLines(partner = pulled, exchangeOn = true)
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("4") && lines.last().contains("1"))
        val failed =
            shared.copy(
                carpoolPullAt = at,
                carpoolPullStatus = CarpoolPullStatus.FAILED,
                carpoolPullError = CarpoolPullErrorCode.RATE_LIMITED,
            )
        assertEquals(2, PartnerServersLogic.carpoolFeedLines(partner = failed, exchangeOn = true).size)
        val received = pulled.copy(carpoolReceivedHints = 7)
        assertEquals(3, PartnerServersLogic.carpoolFeedLines(partner = received, exchangeOn = true).size)
        // sharing off or the exchange off: nothing of it is shown
        assertTrue(PartnerServersLogic.carpoolFeedLines(partner = received.copy(carpoolSharing = false), exchangeOn = true).isEmpty())
        assertTrue(PartnerServersLogic.carpoolFeedLines(partner = received, exchangeOn = false).isEmpty())
    }

    @Test
    fun theSettingsLinesCountTheReceivedRejectedAndKeylessHints() {
        val on = CarpoolFederationSettingsDto(enabled = true, hopLimit = 2, feedKeyAvailable = true)
        assertTrue(PartnerServersLogic.carpoolFeedSettingsLines(on).isEmpty())
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(on.copy(carpoolReceivedHintsTotal = 5)).size)
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(on.copy(carpoolReceivedRejectedSinceStart = 2)).size)
        assertEquals(1, PartnerServersLogic.carpoolFeedSettingsLines(on.copy(carpoolReceivedUnknownOriginKeySinceStart = 2)).size)
        assertEquals(
            3,
            PartnerServersLogic
                .carpoolFeedSettingsLines(
                    on.copy(
                        carpoolReceivedHintsTotal = 5,
                        carpoolReceivedRejectedSinceStart = 2,
                        carpoolReceivedUnknownOriginKeySinceStart = 1,
                    ),
                ).size,
        )
    }

    @Test
    fun everyPullErrorCodeHasAWordingThatNamesNoAddress() {
        CarpoolPullErrorCode.entries.forEach { assertTrue(PartnerServersTexts.carpoolPullError(it).isNotBlank()) }
    }

    @Test
    fun thePullStateHasAWordForEveryStateAndTheBadgeShowsOnlyWithSharingOn() {
        val words = CarpoolPullState.entries.map { PartnerServersLogic.pullStateLabel(it) }
        assertEquals(3, words.toSet().size)
        assertTrue(words.all { it.isNotBlank() })
        val shared = partner().copy(carpoolSharing = true, carpoolPullState = CarpoolPullState.PAUSED)
        assertEquals(CarpoolPullState.PAUSED, PartnerServersLogic.pullStateOf(partner = shared, exchangeOn = true))
        assertEquals(null, PartnerServersLogic.pullStateOf(partner = shared, exchangeOn = false))
        assertEquals(null, PartnerServersLogic.pullStateOf(partner = shared.copy(carpoolSharing = false), exchangeOn = true))
        assertEquals(null, PartnerServersLogic.pullStateOf(partner = shared.copy(carpoolPullState = null), exchangeOn = true))
    }

    @Test
    fun theOperationLinesNameLastSuccessNextTryAndTheBlocks() {
        val shared = partner().copy(carpoolSharing = true, carpoolKeyPinned = true)
        val base = PartnerServersLogic.carpoolFeedLines(partner = shared, exchangeOn = true).size
        val lines =
            PartnerServersLogic.carpoolFeedLines(
                partner =
                    shared.copy(
                        carpoolPullOkAt = LocalDateTime(2026, 11, 19, 10, 0),
                        carpoolPullNextAt = LocalDateTime(2026, 11, 19, 12, 0),
                        carpoolReceivedBlocks = 3,
                    ),
                exchangeOn = true,
            )
        assertEquals(base + 3, lines.size)
        assertTrue(lines.any { it.contains("Letzter Erfolg") })
        assertTrue(lines.any { it.contains("Nächster Versuch") })
        assertTrue(lines.any { it.contains("Sperren aktiv: 3") })
    }

    @Test
    fun theRejectReasonLinesFollowTheFixedOrder_andAnUnknownKeyIsIgnored() {
        val on =
            CarpoolFederationSettingsDto(
                enabled = true,
                hopLimit = 2,
                carpoolReceivedRejectedByReason =
                    mapOf("CAPACITY" to 7L, "SIGNATURE" to 2L, "SOMETHING_NEW" to 99L),
            )
        val lines = PartnerServersLogic.rejectReasonLines(on)
        assertEquals(CarpoolReceivedRejectReason.entries.size, lines.size)
        assertTrue(lines.first().endsWith("2"), "the signature line comes first")
        assertTrue(lines.last().endsWith("7"), "the capacity line comes last")
        assertFalse(lines.any { it.contains("99") })
    }

    @Test
    fun theOperationSettingsLinesNameTheStartTheStoredRowsAndTheLagOfTheSweep() {
        val on = CarpoolFederationSettingsDto(enabled = true, hopLimit = 2, feedKeyAvailable = true)
        val lines =
            PartnerServersLogic.carpoolFeedSettingsLines(
                on.copy(
                    serverStartedAt = LocalDateTime(2026, 11, 19, 8, 0),
                    feedServedSinceStart = 5,
                    carpoolReceivedHintsTotal = 10,
                    carpoolReceivedStoredRows = 14,
                    carpoolPullBudgetDeferredSinceStart = 2,
                ),
            )
        assertTrue(lines.any { it.contains("Seit dem Serverstart") && it.contains("5 ausgeliefert") })
        assertTrue(lines.any { it.contains("Gespeichert: 14") && it.contains("noch nicht gelöscht: 4") })
        assertTrue(lines.any { it.contains("Aufgeschoben") && it.contains("2") })
    }

    // ── V1.9.322 c3: the receiving side of the auction exchange ──────────────────────────────────

    private fun auctionPartner(
        pullAt: LocalDateTime? = null,
        status: CarpoolPullStatus? = null,
        error: CarpoolPullErrorCode? = null,
        failures: Int = 0,
        received: Int = 0,
    ) = partner().copy(
        auctionSharing = true,
        auctionPullAt = pullAt,
        auctionPullStatus = status,
        auctionPullError = error,
        auctionPullFailures = failures,
        auctionReceivedFromPartner = received,
        auctionPullLastAccepted = 3,
        auctionPullLastRejected = 1,
    )

    @Test
    fun theAuctionPullLines_lastResult_failureCode_received_andThePauseNote() {
        val at = LocalDateTime(2026, 11, 24, 10, 0)
        assertEquals(emptyList(), PartnerServersLogic.auctionPullLines(auctionPartner()))
        val ok = PartnerServersLogic.auctionPullLines(auctionPartner(pullAt = at, status = CarpoolPullStatus.OK, received = 4))
        assertTrue(ok.any { it.startsWith("Auktionshinweise zuletzt abgerufen:") && it.contains("3 übernommen, 1 abgelehnt") })
        assertTrue(ok.contains("Empfangene Auktionshinweise von diesem Partner: 4"))
        val failed =
            PartnerServersLogic.auctionPullLines(
                auctionPartner(pullAt = at, status = CarpoolPullStatus.FAILED, error = CarpoolPullErrorCode.RATE_LIMITED, failures = 8),
            )
        assertTrue(failed.any { it.startsWith("Abruf der Auktionshinweise fehlgeschlagen:") })
        assertTrue(failed.contains("Abruf der Auktionshinweise pausiert nach 8 Fehlschlägen"))
        assertFalse(
            PartnerServersLogic.auctionPullLines(auctionPartner(pullAt = at, status = CarpoolPullStatus.OK, failures = 7)).any {
                "pausiert" in
                    it
            },
        )
    }

    @Test
    fun theAuctionSettingsLines_receivedHidden_andOnlyTheCausesThatHappened() {
        val settings =
            network.lapis.cloud.shared.domain.AuctionFederationSettingsDto(
                enabled = true,
                hopLimit = 1,
                feedKeyAvailable = true,
                receivedHintsTotal = 12,
                hiddenActive = 2,
                receivedRejectedByReason = mapOf("SIGNATURE" to 3L, "CAPACITY" to 0L, "TAMPER" to 1L, "NOT_A_REASON" to 9L),
            )
        val lines = PartnerServersLogic.auctionSettingsLines(settings)
        assertTrue(lines.contains("Empfangene Auktionshinweise: 12"))
        assertTrue(lines.contains("Ausgeblendete Auktionshinweise: 2"))
        assertTrue(lines.contains("Signatur ungültig oder fehlt: 3"))
        assertTrue(lines.contains("Inhalt bei gleicher Fassung verändert: 1"))
        assertFalse(lines.any { it.startsWith("Kapazität erreicht") })
        assertFalse(lines.any { "NOT_A_REASON" in it || "9" == it.takeLast(1) && "Nicht" in it })
        // switched off: only what is still going on
        assertFalse(
            PartnerServersLogic.auctionSettingsLines(settings.copy(enabled = false)).any { it.startsWith("Empfangene Auktionshinweise") },
        )
    }
}
