package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- the ONE new authorization boundary
 * this wave adds: what a plain [network.lapis.cloud.shared.domain.AccountRole.MEMBER] caller who
 * ALSO holds an ACTIVE regional-chapter-officer grant ("Landesvorstand") is allowed to see via
 * [network.lapis.cloud.shared.rpc.IMemberService.listMembersForAdministration]. Every OTHER RPC
 * service/route is completely unaffected by this wave -- the officer's [network.lapis.cloud.shared
 * .domain.AccountRole] stays `MEMBER`, so every existing `requireRole`/`isPrivileged`/
 * `ESCALATED_ROLES` gate in this codebase treats them exactly like any other plain member. See
 * `docs/architecture/regional-chapters.adoc` (Welle V1.9.14) for the full design doc -- a
 * behavioral `RegionalChapterNoWideningTest` (query-manipulation coverage against a chapter-scoped
 * caller) remains deferred to a follow-up wave per the CHANGELOG's own "Ausdrücklich nicht Teil
 * dieser Welle" disclosure. [MemberVisibility]/[memberVisibility] below carry the actual security
 * argument, and `RegionalChapterVisibilityAllowlistScanTest` (see [memberVisibility] KDoc
 * "Allowlisted call sites only") is the real, currently-existing regression guard.
 *
 * **Role always wins first.** [MemberVisibility.All] for BOARD/TREASURER/ADMIN is checked BEFORE
 * the officer-grant lookup even runs -- a BOARD/ADMIN/TREASURER member who additionally happens to
 * hold an (irrelevant, never exercised) officer grant is never narrowed by it.
 */
sealed interface MemberVisibility {
    /**
     * BOARD/TREASURER/ADMIN ONLY -- [memberVisibility] checks `role in ESCALATED_ROLES` first and
     * returns this immediately if so. A plain MEMBER not currently in organization-member status
     * gets [None], NOT this (doc bug fixed, review finding: an earlier revision of this KDoc
     * incorrectly claimed that case landed here too).
     */
    data object All : MemberVisibility

    /** A plain MEMBER holding an ACTIVE regional-chapter-officer grant for [chapterId], assigned to that SAME chapter themselves. */
    data class Chapter(
        val chapterId: Uuid,
    ) : MemberVisibility

    /** Everyone else -- an organization member with no ACTIVE officer grant. */
    data object None : MemberVisibility
}

/**
 * **MUST be called inside the caller's own already-open `transaction {}` block** -- see the
 * `check(...)` below, same discipline [network.lapis.cloud.server.audit.AuditLogRecorder.record]
 * establishes. **Allowlisted call sites only** (`RegionalChapterVisibilityAllowlistScanTest` scans
 * the whole `lapis-server` main source set for every `.memberVisibility()` call and fails if one
 * exists outside the allowlisted files): `MemberService.kt` (`listMembersForAdministration`) and
 * `AuthService.kt` (`getSessionInfo`) -- doc fix (review finding): an earlier revision of this
 * KDoc also listed `RegionalChapterService`, which calls `requireRole` directly and has never
 * actually called this function. Never call this from an API-key route
 * (`ApiKeyAuth`/`PublicApiRoutes`) or from the `mcp` package -- those surfaces are out of scope for
 * this wave entirely (a dedicated behavioral `RegionalChapterNoWideningTest` covering that
 * explicitly remains deferred, per the CHANGELOG's own V1.9.14 "Ausdrücklich nicht Teil dieser
 * Welle" disclosure -- the allowlist scan above is this wave's actual guard against a NEW call
 * site appearing there unnoticed).
 *
 * Re-reads [CurrentMember.status]/the officer-grant table FRESH on every call, inside the SAME
 * transaction as whatever query the caller is about to run -- no caching, no time-of-check/
 * time-of-use gap: a status change or a `revokeOfficer` committed one transaction earlier is
 * always already reflected here.
 */
fun CurrentMember.memberVisibility(): MemberVisibility {
    check(TransactionManager.currentOrNull() != null) {
        "memberVisibility must be called from inside an already-open transaction {} block"
    }
    if (role in ESCALATED_ROLES) return MemberVisibility.All
    if (status !in MemberStatusSets.ORGANIZATION_MEMBER) return MemberVisibility.None

    // Two separate, single-table lookups (not a join with a cross-column `eq`) -- simpler to keep
    // type-safe, and there is at most one row on each side anyway (uq_regional_chapter_officer_active
    // guarantees at most one ACTIVE grant per member).
    val activeGrantChapterId =
        RegionalChapterOfficerTable
            .select(RegionalChapterOfficerTable.regionalChapterId)
            .where { RegionalChapterOfficerTable.activeForMemberId eq memberId }
            .singleOrNull()
            ?.get(RegionalChapterOfficerTable.regionalChapterId)
            ?: return MemberVisibility.None

    // The second condition (the caller's OWN current chapter still matches the grant's chapter)
    // matters because assignMemberToChapter revokes a now-mismatched grant in the SAME
    // transaction it reassigns the member in (see RegionalChapterService KDoc) -- re-checking it
    // here too is a defense-in-depth belt-and-braces read, not the only place this invariant is
    // enforced.
    val ownChapterId =
        MemberTable
            .select(MemberTable.regionalChapterId)
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.regionalChapterId)

    return if (ownChapterId == activeGrantChapterId) MemberVisibility.Chapter(activeGrantChapterId) else MemberVisibility.None
}

/**
 * Resolves [MemberVisibility.Chapter] into the `(id, name)` pair [network.lapis.cloud.shared
 * .domain.SessionInfoDto.chapterScope] carries -- used only by `AuthService.getSessionInfo`
 * (`All`/`None` both map to `null`, i.e. no chapter-scope badge shown).
 */
fun resolveChapterScopeRef(visibility: MemberVisibility): RegionalChapterRefDto? {
    if (visibility !is MemberVisibility.Chapter) return null
    return RegionalChapterTable
        .selectAll()
        .where { RegionalChapterTable.id eq visibility.chapterId }
        .singleOrNull()
        ?.let { RegionalChapterRefDto(id = it[RegionalChapterTable.id].toString(), name = it[RegionalChapterTable.name]) }
}
