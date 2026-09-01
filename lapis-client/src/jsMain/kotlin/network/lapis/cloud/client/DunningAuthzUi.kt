package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningNoticeStatus

/**
 * Client-UI wave for GitHub Issue #5 ("Client-UI für das Mahnwesen") -- pure, DOM-free client-side
 * mirror of the FOUR role tiers `network.lapis.cloud.server.rpc.DunningService`/`DunningRoutes`
 * actually enforce (verified against source, see this wave's approved plan §0):
 *
 * - [READ_ROLES] mirrors `DunningService.DUNNING_READ_ROLES` (`DunningService.kt:71`) --
 *   TREASURER/BOARD/ADMIN, gates `listDunningCases`/`getDunningCase`.
 * - [TREASURY_ROLES] mirrors `DunningService.DUNNING_TREASURY_ROLES` (`DunningService.kt:70`) --
 *   TREASURER/ADMIN, gates `issueDunningNotice`/`skipDunningLevel`/`resetDunning`/
 *   `cancelDunningNotice`.
 * - [ADMIN_ROLES] mirrors every `requireRole(AccountRole.ADMIN)` in `DunningService` -- the gate
 *   (`getDunningComplianceDisclaimer`/`enableDunning`/`disableDunning`/`getDunningSettings`) AND
 *   the level-CRUD block (`listDunningLevels`/`createDunningLevel`/`updateDunningLevel`/
 *   `deactivateDunningLevel`). Deliberately ADMIN-only -- unlike SEPA's analogous settings screen,
 *   there is no TREASURER-readable settings tier here at all (plan finding B2).
 * - [FILE_ACCESS_ROLES] mirrors `DunningRoutes.DUNNING_FILE_DOWNLOAD_ROLES` -- TREASURER/ADMIN,
 *   **never** BOARD. Deliberately its own constant, NEVER reused from [TREASURY_ROLES]: a dunning
 *   notice PDF carries a member's full postal address and the specific amount they owe, exactly
 *   the same "own file-download constant" precedent `SepaAuthzUi.FILE_DOWNLOAD_ROLES`/
 *   `DunningRoutes.kt`'s own file KDoc already establish. Reusing [TREASURY_ROLES] here would be a
 *   silent BOARD-can-download-addresses regression the moment someone "simplified" the two
 *   identical-looking sets together -- see [DunningAuthzUiTest] for the drift-guard test.
 */
object DunningAuthzUi {
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val TREASURY_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)
    val ADMIN_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

    /**
     * Bewusst eigene Konstante, NICHT [TREASURY_ROLES] wiederverwendet -- spiegelt
     * `DunningRoutes.DUNNING_FILE_DOWNLOAD_ROLES`. Eine Mahnung trägt die volle Postanschrift
     * eines Mitglieds sowie den konkreten Schuldbetrag -- BOARD niemals.
     */
    val FILE_ACCESS_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)

    fun canReadDunning(role: AccountRole?): Boolean = role in READ_ROLES

    fun canTreasuryAct(role: AccountRole?): Boolean = role in TREASURY_ROLES

    fun canAdminister(role: AccountRole?): Boolean = role in ADMIN_ROLES

    fun canAccessDunningFiles(role: AccountRole?): Boolean = role in FILE_ACCESS_ROLES

    /**
     * The "next step" button for one dunning case, from a treasurer's point of view. `null` means
     * structurally impossible right now (no treasury role, contribution not DUNNABLE, or no next
     * level exists) -- the button disappears entirely and the caller renders a reason line instead
     * (plan §2.5 "5. Aktionsleiste"). Never disabled-with-no-explanation.
     *
     * [DunningCaseAction.ISSUE_EARLY] (plan finding B3): manual issuance always calls
     * `issueDunningNotice` with `respectSchedule = false` (`DunningService.kt:468`) --
     * `DunningIssueOutcome.NotDue` is only reachable via the poller, never via this manual path. So
     * "next level not yet due" is NEVER a reason to disable the button -- only to relabel it as an
     * early-issuance warning that the caller must confirm before firing.
     */
    fun nextCaseAction(
        role: AccountRole?,
        case: DunningCaseDto,
        today: LocalDate,
    ): DunningCaseAction? {
        if (!canTreasuryAct(role)) return null
        if (case.contributionStatus !in ContributionStatusSets.DUNNABLE) return null
        if (case.nextLevelNumber == null) return null
        val dueOn = case.nextLevelDueOn
        return if (dueOn == null || dueOn <= today) DunningCaseAction.ISSUE else DunningCaseAction.ISSUE_EARLY
    }

    /**
     * `skipDunningLevel` needs the same "there IS a next level" precondition `issueDunningNotice`
     * itself derives (`DunningService.kt:528-534`) -- unlike [canResetDunning], which has no such
     * requirement.
     */
    fun canSkipLevel(
        role: AccountRole?,
        case: DunningCaseDto,
    ): Boolean = canTreasuryAct(role) && case.contributionStatus in ContributionStatusSets.DUNNABLE && case.nextLevelNumber != null

    /**
     * `resetDunning` requires the contribution to be DUNNABLE (`DunningService.kt:645`) but places
     * NO requirement on a next level existing -- deliberately different from [canSkipLevel]. A
     * contribution that was never actually dunned (`highestLevelNumber == null`) has nothing to
     * reset, so this additionally requires that.
     */
    fun canResetDunning(
        role: AccountRole?,
        case: DunningCaseDto,
    ): Boolean = canTreasuryAct(role) && case.contributionStatus in ContributionStatusSets.DUNNABLE && case.highestLevelNumber != null

    /**
     * `cancelDunningNotice` rejects only an already-CANCELLED notice (`DunningService.kt:788-790`)
     * -- both ISSUED and SKIPPED notices are cancellable, not just ISSUED ones.
     */
    fun canCancelNotice(
        role: AccountRole?,
        status: DunningNoticeStatus,
    ): Boolean = canTreasuryAct(role) && status != DunningNoticeStatus.CANCELLED

    /**
     * The dry-run letter-preview button (`POST /api/dunning/contributions/{id}/preview.pdf`):
     * requires [FILE_ACCESS_ROLES] (the route itself is `DUNNING_FILE_DOWNLOAD_ROLES`-gated, same
     * "a dunning letter carries a postal address" reasoning as the archived-PDF download) AND a
     * next level the server could actually render a preview for -- mirrors the route's own
     * `Kein Beitrag im mahnfaehigen Zustand oder keine weitere Mahnstufe konfiguriert.` rejection
     * (`DunningRoutes.kt:226`), so the button is hidden rather than producing a raw-text 409 in a
     * freshly opened tab (plan Stolperfalle 10).
     */
    fun canPreviewNextNotice(
        role: AccountRole?,
        case: DunningCaseDto,
    ): Boolean = canAccessDunningFiles(role) && case.contributionStatus in ContributionStatusSets.DUNNABLE && case.nextLevelNumber != null

    /** Archived PDF of an already-issued notice -- `documentId == null` means the archive step has
     * not (yet) run (see `DunningPoller` Phase C self-healing KDoc), so no file exists to fetch. */
    fun canDownloadNoticePdf(
        role: AccountRole?,
        documentId: String?,
    ): Boolean = canAccessDunningFiles(role) && documentId != null
}

/** The two possible "next step" states for one dunning case from a treasurer's point of view --
 * see [DunningAuthzUi.nextCaseAction] KDoc. */
enum class DunningCaseAction { ISSUE, ISSUE_EARLY }
