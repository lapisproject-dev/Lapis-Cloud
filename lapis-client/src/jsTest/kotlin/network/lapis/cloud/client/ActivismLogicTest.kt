package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.246 -- the DOM-free decisions of the action pages. */
class ActivismLogicTest {
    private fun detail(
        status: ActivismStatus = ActivismStatus.RUNNING,
        role: ActivismViewerRole = ActivismViewerRole.MEMBER,
        progress: Boolean = true,
        signal: Boolean = true,
        noticeText: String? = null,
    ) = ActivismActionDetailDto(
        id = "a1",
        title = "Plakatieren",
        description = null,
        goalText = null,
        location = null,
        startsAt = null,
        endsAt = null,
        kind = ActivismKind.POSTERING,
        formAppointment = false,
        formProgress = progress,
        formSignal = signal,
        goalQuantity = 5000,
        unit = "Plakaten",
        confirmContributions = true,
        status = status,
        viewerRole = role,
        progressReported = 1240,
        progressConfirmed = 800,
        signal = ActivismSignalStateDto(),
        noticeText = noticeText,
        ownNotice = null,
        tasks = emptyList(),
        textBlocks = emptyList(),
        material = emptyList(),
        eventShifts = null,
        result = null,
        responsibles = null,
        withdrawnReason = null,
        reviewRejectReason = null,
        costCenterId = null,
        costCenterName = null,
        eventId = null,
        revision = 0,
    )

    @Test
    fun theListStartsAtPlannedAndRunning() {
        assertEquals(listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING), ActivismLogic.DEFAULT_STATUSES)
    }

    @Test
    fun progressIsAGroupedNumberWithGoalUnitAndConfirmation() {
        val text = ActivismLogic.progressText(reported = 1240, confirmed = null, goal = 5000, unit = "Plakaten")
        assertTrue(
            text.contains("1") && text.contains("240") && text.contains("5") && text.contains("000") && text.contains("Plakaten"),
            text,
        )
        assertTrue(ActivismLogic.progressText(10, 4, null, null).contains("bestätigt"))
        assertEquals("7", ActivismLogic.progressText(7, null, null, null))
    }

    @Test
    fun theBarIsClampedAndMissingWithoutAGoal() {
        assertEquals(25, ActivismLogic.progressPercent(1250, 5000))
        assertEquals(100, ActivismLogic.progressPercent(9000, 5000))
        assertNull(ActivismLogic.progressPercent(10, null))
        assertNull(ActivismLogic.progressPercent(10, 0))
    }

    @Test
    fun theSignalNeverShowsANumberBelowFive() {
        assertTrue(ActivismLogic.signalText(ActivismSignalStateDto(countAtLeast5 = null)).contains("Weniger als 5"))
        assertTrue(ActivismLogic.signalText(ActivismSignalStateDto(countAtLeast5 = 12)).contains("12"))
    }

    @Test
    fun buttonsFollowTheRoleAndTheReviewSwitch() {
        // a plain member gets none
        assertTrue(ActivismLogic.statusTargets(detail(role = ActivismViewerRole.MEMBER), false).isEmpty())
        // a responsible person moves between planned / running / completed but not out of COMPLETED
        val running = ActivismLogic.statusTargets(detail(role = ActivismViewerRole.RESPONSIBLE), false)
        assertEquals(listOf(ActivismStatus.PLANNED, ActivismStatus.COMPLETED), running)
        assertTrue(ActivismLogic.statusTargets(detail(ActivismStatus.COMPLETED, ActivismViewerRole.RESPONSIBLE), false).isEmpty())
        assertEquals(2, ActivismLogic.statusTargets(detail(ActivismStatus.COMPLETED, ActivismViewerRole.MANAGER), false).size)
        // an idea: the manager plans it directly only without the review switch
        assertEquals(
            listOf(ActivismStatus.PLANNED),
            ActivismLogic.statusTargets(detail(ActivismStatus.IDEA, ActivismViewerRole.MANAGER), false).filter {
                it ==
                    ActivismStatus.PLANNED
            },
        )
        assertTrue(ActivismLogic.statusTargets(detail(ActivismStatus.IDEA, ActivismViewerRole.MANAGER), true).isEmpty())
        assertTrue(ActivismLogic.canSubmitForReview(detail(ActivismStatus.IDEA, ActivismViewerRole.RESPONSIBLE), true))
        assertFalse(ActivismLogic.canSubmitForReview(detail(ActivismStatus.IDEA, ActivismViewerRole.RESPONSIBLE), false))
        assertTrue(ActivismLogic.statusTargets(detail(ActivismStatus.AWAITING_REVIEW, ActivismViewerRole.MANAGER), true).isEmpty())
    }

    @Test
    fun participationOnlyWhileRunning() {
        assertTrue(ActivismLogic.canReportProgress(detail(ActivismStatus.RUNNING)))
        assertFalse(ActivismLogic.canReportProgress(detail(ActivismStatus.PLANNED)))
        assertFalse(ActivismLogic.canReportProgress(detail(progress = false)))
        assertTrue(ActivismLogic.canSignal(detail(ActivismStatus.RUNNING)))
        assertFalse(ActivismLogic.canSignal(detail(ActivismStatus.COMPLETED)))
    }

    @Test
    fun theNoticeIsTheOperatorsTextOrTheStandard() {
        assertEquals("Eigener Text", ActivismLogic.noticeFor(detail(noticeText = "Eigener Text")))
        assertEquals(ActivismTexts.defaultNotice(ActivismKind.POSTERING), ActivismLogic.noticeFor(detail()))
    }

    @Test
    fun wholeNumbersAreParsedInRange() {
        assertEquals(5, ActivismLogic.parseWhole(" 5 ", 1, 10))
        assertNull(ActivismLogic.parseWhole("0", 1, 10))
        assertNull(ActivismLogic.parseWhole("11", 1, 10))
        assertNull(ActivismLogic.parseWhole("1,5", 1, 10))
        assertNull(ActivismLogic.parseWhole("abc", 1, 10))
    }

    @Test
    fun everyKindAndStatusHasAText() {
        ActivismKind.entries.forEach {
            assertTrue(ActivismTexts.kind(it).isNotBlank())
            assertTrue(ActivismTexts.defaultNotice(it).isNotBlank())
        }
        ActivismStatus.entries.forEach { assertTrue(ActivismTexts.status(it).isNotBlank()) }
        assertEquals("Keine Rechtsberatung.", ActivismTexts.legalDisclaimer())
    }
}
