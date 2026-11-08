package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOfficerDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.RegionalChapterTreasurerDto

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- ADMIN maintains the flat chapter list
 * and grants/revokes "Landesvorstand" (regional-chapter officer) access; BOARD/ADMIN may (re-)
 * assign a member to a chapter. See `docs/architecture/regional-chapters.adoc` (Welle V1.9.14) for
 * the full design doc; this interface's own per-method KDoc and
 * `network.lapis.cloud.server.rpc.RegionalChapterService`'s carry the model too.
 */
@RpcService
interface IRegionalChapterService {
    /** BOARD, ADMIN. */
    suspend fun listChapters(): RegionalChapterOverviewDto

    /** ADMIN only. Throws [RegionalChapterNameTakenException]/[RegionalChapterLimitReachedException]/[BadRequestException]. */
    suspend fun createChapter(name: String): RegionalChapterDto

    /** ADMIN only. Throws [RegionalChapterNameTakenException]/[NotFoundException]/[BadRequestException]. */
    suspend fun renameChapter(
        chapterId: String,
        name: String,
    ): RegionalChapterDto

    /** ADMIN only. Throws [RegionalChapterInUseException] if any member (any status) is still assigned, or any officer grant is still active. */
    suspend fun deleteChapter(chapterId: String)

    /**
     * BOARD, ADMIN -- but if the target already holds an escalated role (BOARD/TREASURER/ADMIN),
     * ADMIN is required (same peer-protection boundary as
     * `network.lapis.cloud.server.security.ESCALATED_ROLES`-gated writes elsewhere in this
     * codebase, e.g. `IMemberService.updateMemberStatus`/`updateMemberMembershipTier`) -- a plain
     * BOARD caller may therefore never reassign a fellow BOARD/TREASURER/ADMIN peer, nor
     * themselves (BOARD is itself an escalated role). `chapterId = null` clears the assignment.
     * If the member already holds an ACTIVE officer grant for a *different* chapter, that grant
     * is revoked automatically in the same call. Throws [ForbiddenException] (peer-protection
     * boundary above), [ConflictException] (anonymized member), [BadRequestException] (status
     * not in [network.lapis.cloud.shared.domain.RegionalChapterRules.ASSIGNABLE_STATUSES]),
     * [NotFoundException].
     */
    suspend fun assignMemberToChapter(
        memberId: String,
        chapterId: String?,
    )

    /** ADMIN only. */
    suspend fun listOfficers(chapterId: String): List<RegionalChapterOfficerDto>

    /** ADMIN only. Throws [RegionalChapterOfficerIneligibleException]/[RegionalChapterLimitReachedException]/[NotFoundException]. */
    suspend fun grantOfficer(
        memberId: String,
        chapterId: String,
    ): RegionalChapterOfficerDto

    /** ADMIN only. Idempotent for an already-revoked grant (silent no-op). Throws [NotFoundException] for an unknown grantId. */
    suspend fun revokeOfficer(grantId: String)

    /**
     * Welle V1.9.20 -- BOARD/ADMIN. Sets (or, for a blank [description], clears) the public
     * description of [chapterId] (at most `RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS` code points).
     */
    suspend fun updateChapterDescription(
        chapterId: String,
        description: String?,
    ): RegionalChapterDto

    /** Welle V1.9.20 -- BOARD/ADMIN. Removes the crest image of [chapterId] (idempotent). The upload itself is `POST /api/regional-chapters/{id}/crest`. */
    suspend fun removeChapterCrest(chapterId: String): RegionalChapterDto

    /** V1.9.289 (R4a). ADMIN only. The ACTIVE chapter-treasurer grants of [chapterId]. */
    suspend fun listChapterTreasurers(chapterId: String): List<RegionalChapterTreasurerDto>

    /**
     * V1.9.289 (R4a). ADMIN only. Grants [memberId] (an organization member) the right to book into and read the journal of [chapterId]. At most 10
     * active grants per member and 5 per chapter ([RegionalChapterLimitReachedException]); idempotent for an existing active grant.
     */
    suspend fun grantChapterTreasurer(
        chapterId: String,
        memberId: String,
    ): RegionalChapterTreasurerDto

    /** V1.9.289 (R4a). ADMIN only. Idempotent for an already-revoked grant. The effect is immediate. */
    suspend fun revokeChapterTreasurer(grantId: String)
}
