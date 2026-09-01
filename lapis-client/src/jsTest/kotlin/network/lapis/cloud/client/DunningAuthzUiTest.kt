package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Client-UI wave for GitHub Issue #5 -- covers [DunningAuthzUi], the pure client-side mirror of
 * `DunningService`/`DunningRoutes`'s FOUR role tiers (plan §0 verified backend-contract matrix).
 * Same DOM-free unit-test posture as [SepaAuthzUiTest]/[GovernanceAuthzUiTest].
 */
class DunningAuthzUiTest {
    private val today = LocalDate(2026, 9, 1)
    private val yesterday = LocalDate(2026, 8, 31)
    private val tomorrow = LocalDate(2026, 9, 2)

    private fun dunnableCase(
        contributionStatus: ContributionStatus = ContributionStatus.OVERDUE,
        highestLevelNumber: Int? = 1,
        nextLevelNumber: Int? = 2,
        nextLevelDueOn: LocalDate? = null,
    ): DunningCaseDto =
        DunningCaseDto(
            contributionId = "contribution-1",
            memberId = "member-1",
            memberDisplayName = "Max Mustermann",
            periodStart = LocalDate(2026, 1, 1),
            periodEnd = LocalDate(2026, 3, 31),
            amountDue = 42.0.toDecimal(),
            dueDate = LocalDate(2026, 1, 15),
            contributionStatus = contributionStatus,
            paymentMethod = ContributionPaymentMethod.MANUAL,
            currentCycleNumber = 1,
            highestLevelNumber = highestLevelNumber,
            lastNoticeIssuedAt = null,
            nextLevelNumber = nextLevelNumber,
            nextLevelDueOn = nextLevelDueOn,
            totalFeesCharged = 0.0.toDecimal(),
        )

    // ── canAccessDunningFiles vs. canReadDunning -- the reason FILE_ACCESS_ROLES is its own
    // constant rather than a reuse of TREASURY_ROLES/READ_ROLES. ──────────────────────────────────

    @Test
    fun canAccessDunningFiles_boardIsAlwaysDenied() {
        assertFalse(DunningAuthzUi.canAccessDunningFiles(AccountRole.BOARD))
        assertFalse(DunningAuthzUi.canAccessDunningFiles(AccountRole.MEMBER))
        assertFalse(DunningAuthzUi.canAccessDunningFiles(null))
        assertTrue(DunningAuthzUi.canAccessDunningFiles(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.canAccessDunningFiles(AccountRole.ADMIN))
    }

    /** Gegenprobe: beweist, dass [DunningAuthzUi.READ_ROLES] und
     * [DunningAuthzUi.FILE_ACCESS_ROLES] nicht versehentlich verschmolzen wurden -- BOARD darf
     * lesen, aber keine Mahnungs-PDFs herunterladen. */
    @Test
    fun canReadDunning_boardIsAllowed() {
        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.BOARD))
        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.ADMIN))
        assertFalse(DunningAuthzUi.canReadDunning(AccountRole.MEMBER))
        assertFalse(DunningAuthzUi.canReadDunning(null))
    }

    @Test
    fun canTreasuryAct_boardIsDenied_treasurerAndAdminAreAllowed() {
        assertFalse(DunningAuthzUi.canTreasuryAct(AccountRole.BOARD))
        assertTrue(DunningAuthzUi.canTreasuryAct(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.canTreasuryAct(AccountRole.ADMIN))
    }

    @Test
    fun canAdminister_onlyAdminIsAllowed() {
        assertFalse(DunningAuthzUi.canAdminister(AccountRole.BOARD))
        assertFalse(DunningAuthzUi.canAdminister(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.canAdminister(AccountRole.ADMIN))
    }

    // ── nextCaseAction ───────────────────────────────────────────────────────────────────────────

    @Test
    fun nextCaseAction_nullForEveryNonDunnableStatus() {
        ContributionStatus.entries
            .filter {
                it != ContributionStatus.OVERDUE && it != ContributionStatus.RETURNED && it != ContributionStatus.IN_DUNNING
            }.forEach { status ->
                assertNull(
                    DunningAuthzUi.nextCaseAction(AccountRole.TREASURER, dunnableCase(contributionStatus = status), today),
                    "expected null for non-dunnable status $status",
                )
            }
    }

    @Test
    fun nextCaseAction_nullWhenNoNextLevel() {
        assertNull(DunningAuthzUi.nextCaseAction(AccountRole.TREASURER, dunnableCase(nextLevelNumber = null), today))
    }

    @Test
    fun nextCaseAction_nullForNonTreasuryRoles() {
        val case = dunnableCase()
        assertNull(DunningAuthzUi.nextCaseAction(AccountRole.BOARD, case, today))
        assertNull(DunningAuthzUi.nextCaseAction(AccountRole.MEMBER, case, today))
        assertNull(DunningAuthzUi.nextCaseAction(null, case, today))
    }

    @Test
    fun nextCaseAction_issueWhenNextLevelDueOnIsNull() {
        assertEquals(
            DunningCaseAction.ISSUE,
            DunningAuthzUi.nextCaseAction(AccountRole.TREASURER, dunnableCase(nextLevelDueOn = null), today),
        )
    }

    @Test
    fun nextCaseAction_issueWhenNextLevelDueOnIsTodayOrEarlier() {
        assertEquals(
            DunningCaseAction.ISSUE,
            DunningAuthzUi.nextCaseAction(AccountRole.TREASURER, dunnableCase(nextLevelDueOn = today), today),
        )
        assertEquals(
            DunningCaseAction.ISSUE,
            DunningAuthzUi.nextCaseAction(AccountRole.TREASURER, dunnableCase(nextLevelDueOn = yesterday), today),
        )
    }

    @Test
    fun nextCaseAction_issueEarlyWhenNextLevelDueOnIsInTheFuture() {
        assertEquals(
            DunningCaseAction.ISSUE_EARLY,
            DunningAuthzUi.nextCaseAction(AccountRole.ADMIN, dunnableCase(nextLevelDueOn = tomorrow), today),
        )
    }

    // ── canSkipLevel vs. canResetDunning -- deliberately different preconditions ────────────────

    @Test
    fun canSkipLevel_requiresANextLevel() {
        assertTrue(DunningAuthzUi.canSkipLevel(AccountRole.TREASURER, dunnableCase(nextLevelNumber = 2)))
        assertFalse(DunningAuthzUi.canSkipLevel(AccountRole.TREASURER, dunnableCase(nextLevelNumber = null)))
        assertFalse(DunningAuthzUi.canSkipLevel(AccountRole.BOARD, dunnableCase(nextLevelNumber = 2)))
    }

    @Test
    fun canResetDunning_requiresAPriorHighestLevel_notANextLevel() {
        // Never dunned -- nothing to reset, even though nextLevelNumber is present.
        assertFalse(DunningAuthzUi.canResetDunning(AccountRole.TREASURER, dunnableCase(highestLevelNumber = null)))
        // Already dunned, no further level available -- still resettable.
        assertTrue(
            DunningAuthzUi.canResetDunning(AccountRole.TREASURER, dunnableCase(highestLevelNumber = 3, nextLevelNumber = null)),
        )
        assertFalse(DunningAuthzUi.canResetDunning(AccountRole.BOARD, dunnableCase(highestLevelNumber = 3)))
    }

    @Test
    fun canResetDunning_deniedWhenNotDunnable() {
        assertFalse(
            DunningAuthzUi.canResetDunning(
                AccountRole.TREASURER,
                dunnableCase(contributionStatus = ContributionStatus.PAID, highestLevelNumber = 3),
            ),
        )
    }

    // ── canCancelNotice ──────────────────────────────────────────────────────────────────────────

    @Test
    fun canCancelNotice_deniedOnlyForAlreadyCancelled() {
        assertTrue(DunningAuthzUi.canCancelNotice(AccountRole.TREASURER, DunningNoticeStatus.ISSUED))
        assertTrue(DunningAuthzUi.canCancelNotice(AccountRole.TREASURER, DunningNoticeStatus.SKIPPED))
        assertFalse(DunningAuthzUi.canCancelNotice(AccountRole.TREASURER, DunningNoticeStatus.CANCELLED))
    }

    @Test
    fun canCancelNotice_boardIsDenied() {
        assertFalse(DunningAuthzUi.canCancelNotice(AccountRole.BOARD, DunningNoticeStatus.ISSUED))
    }

    // ── canPreviewNextNotice / canDownloadNoticePdf -- second drift guard for FILE_ACCESS_ROLES ─

    @Test
    fun canPreviewNextNotice_boardIsDeniedDespiteDunnableCaseWithNextLevel() {
        assertFalse(DunningAuthzUi.canPreviewNextNotice(AccountRole.BOARD, dunnableCase()))
        assertTrue(DunningAuthzUi.canPreviewNextNotice(AccountRole.TREASURER, dunnableCase()))
    }

    @Test
    fun canPreviewNextNotice_deniedWhenNoNextLevelOrNotDunnable() {
        assertFalse(DunningAuthzUi.canPreviewNextNotice(AccountRole.TREASURER, dunnableCase(nextLevelNumber = null)))
        assertFalse(
            DunningAuthzUi.canPreviewNextNotice(AccountRole.TREASURER, dunnableCase(contributionStatus = ContributionStatus.PAID)),
        )
    }

    @Test
    fun canDownloadNoticePdf_boardIsDenied_missingDocumentIdIsDenied() {
        assertFalse(DunningAuthzUi.canDownloadNoticePdf(AccountRole.BOARD, documentId = "doc-1"))
        assertFalse(DunningAuthzUi.canDownloadNoticePdf(AccountRole.TREASURER, documentId = null))
        assertTrue(DunningAuthzUi.canDownloadNoticePdf(AccountRole.TREASURER, documentId = "doc-1"))
        assertTrue(DunningAuthzUi.canDownloadNoticePdf(AccountRole.ADMIN, documentId = "doc-1"))
    }
}
