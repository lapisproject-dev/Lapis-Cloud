package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.DunningSettingsDto
import network.lapis.cloud.shared.domain.MemberStatus
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
            memberStatus = MemberStatus.ACTIVE,
        )

    private fun settings(
        dunningEnabled: Boolean = true,
        activeLevelCount: Int = 0,
        lastDisclaimerVersion: String? = "2026-01",
    ): DunningSettingsDto =
        DunningSettingsDto(
            dunningEnabled = dunningEnabled,
            pollerEnabled = false,
            postalDispatchEnabled = false,
            postalMailEnabled = false,
            activeLevelCount = activeLevelCount,
            lastDisclaimerVersion = lastDisclaimerVersion,
            lastAcknowledgedAt = null,
        )

    private fun disclaimer(version: String = "2026-02"): DunningComplianceDisclaimerDto =
        DunningComplianceDisclaimerDto(version = version, text = "…", sha256 = "…")

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

    /** Since `f30022c`, `getDunningSettings`/`listDunningLevels` are READ_ROLES server-side --
     * TREASURER/BOARD can read, but only ADMIN can administer. MEMBER/null can neither. */
    @Test
    fun dunningRoleMatrix_readVsAdminister() {
        assertFalse(DunningAuthzUi.canAdminister(AccountRole.TREASURER))
        assertFalse(DunningAuthzUi.canAdminister(AccountRole.BOARD))
        assertTrue(DunningAuthzUi.canAdminister(AccountRole.ADMIN))

        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.BOARD))
        assertTrue(DunningAuthzUi.canReadDunning(AccountRole.ADMIN))
        assertFalse(DunningAuthzUi.canReadDunning(AccountRole.MEMBER))
        assertFalse(DunningAuthzUi.canReadDunning(null))
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

    // ── showNoActiveLevelWarning -- role-INDEPENDENT by design, unlike showStaleDisclaimerWarning ─

    @Test
    fun showNoActiveLevelWarning_enabledWithZeroLevels() {
        assertTrue(DunningAuthzUi.showNoActiveLevelWarning(settings(dunningEnabled = true, activeLevelCount = 0)))
    }

    @Test
    fun showNoActiveLevelWarning_enabledWithOneLevel() {
        assertFalse(DunningAuthzUi.showNoActiveLevelWarning(settings(dunningEnabled = true, activeLevelCount = 1)))
    }

    @Test
    fun showNoActiveLevelWarning_disabled() {
        assertFalse(DunningAuthzUi.showNoActiveLevelWarning(settings(dunningEnabled = false, activeLevelCount = 0)))
    }

    @Test
    fun showNoActiveLevelWarning_nullSettings() {
        assertFalse(DunningAuthzUi.showNoActiveLevelWarning(null))
    }

    // ── showDunningWarningBands -- screen-level gate for renderDunningWarningBands itself ────────

    /** Regression guard for the actual `DunningCasesScreen` fix in this wave: TREASURER/BOARD/ADMIN
     * all see the warning bands, matching [READ_ROLES] -- an earlier revision instead wrapped the
     * `renderDunningWarningBands` call site in `if (AppState.hasRole(AccountRole.ADMIN))`, which
     * hid band 1 ("Mahnwesen aktiviert, aber keine Mahnstufe konfiguriert") from TREASURER/BOARD
     * even though `getDunningSettings` is READ_ROLES since `f30022c`. */
    @Test
    fun showDunningWarningBands_treasurerAndBoardAndAdminSeeIt() {
        assertTrue(DunningAuthzUi.showDunningWarningBands(AccountRole.TREASURER))
        assertTrue(DunningAuthzUi.showDunningWarningBands(AccountRole.BOARD))
        assertTrue(DunningAuthzUi.showDunningWarningBands(AccountRole.ADMIN))
    }

    @Test
    fun showDunningWarningBands_memberAndNullAreDenied() {
        assertFalse(DunningAuthzUi.showDunningWarningBands(AccountRole.MEMBER))
        assertFalse(DunningAuthzUi.showDunningWarningBands(null))
    }

    // ── showStaleDisclaimerWarning -- ADMIN-only, because getDunningComplianceDisclaimer stays so ─

    @Test
    fun showStaleDisclaimerWarning_adminWithVersionMismatch() {
        assertTrue(
            DunningAuthzUi.showStaleDisclaimerWarning(
                AccountRole.ADMIN,
                settings(lastDisclaimerVersion = "2026-01"),
                disclaimer(version = "2026-02"),
            ),
        )
    }

    /** Regression guard: the disclaimer-staleness gate must NOT have been loosened alongside
     * [showNoActiveLevelWarning] -- `getDunningComplianceDisclaimer` remains ADMIN-only. */
    @Test
    fun showStaleDisclaimerWarning_treasurerIsDenied() {
        assertFalse(
            DunningAuthzUi.showStaleDisclaimerWarning(
                AccountRole.TREASURER,
                settings(lastDisclaimerVersion = "2026-01"),
                disclaimer(version = "2026-02"),
            ),
        )
    }

    @Test
    fun showStaleDisclaimerWarning_boardIsDenied() {
        assertFalse(
            DunningAuthzUi.showStaleDisclaimerWarning(
                AccountRole.BOARD,
                settings(lastDisclaimerVersion = "2026-01"),
                disclaimer(version = "2026-02"),
            ),
        )
    }

    @Test
    fun showStaleDisclaimerWarning_sameVersion() {
        assertFalse(
            DunningAuthzUi.showStaleDisclaimerWarning(
                AccountRole.ADMIN,
                settings(lastDisclaimerVersion = "2026-02"),
                disclaimer(version = "2026-02"),
            ),
        )
    }

    @Test
    fun showStaleDisclaimerWarning_nullDisclaimer() {
        assertFalse(
            DunningAuthzUi.showStaleDisclaimerWarning(AccountRole.ADMIN, settings(lastDisclaimerVersion = "2026-01"), null),
        )
    }

    @Test
    fun showStaleDisclaimerWarning_disabledDunning() {
        assertFalse(
            DunningAuthzUi.showStaleDisclaimerWarning(
                AccountRole.ADMIN,
                settings(dunningEnabled = false, lastDisclaimerVersion = "2026-01"),
                disclaimer(version = "2026-02"),
            ),
        )
    }
}
