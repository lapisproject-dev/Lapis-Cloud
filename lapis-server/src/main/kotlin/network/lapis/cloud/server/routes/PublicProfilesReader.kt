package network.lapis.cloud.server.routes

import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.memberbio.MemberPublicBioStore
import network.lapis.cloud.server.memberphoto.MemberPhotoPolicy
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.rank
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.text.Collator
import java.util.Locale

/** Translated display label of a board role -- the ONE mapping shared by `/vorstand`, the board embed feed and the own-profile status line. */
internal fun CommitteeRole.publicLabel(strings: PublicUiStrings): String =
    when (this) {
        CommitteeRole.CHAIR -> strings.committeeRoleChair
        CommitteeRole.DEPUTY_CHAIR -> strings.committeeRoleDeputyChair
        CommitteeRole.SECRETARY -> strings.committeeRoleSecretary
        CommitteeRole.ASSESSOR -> strings.committeeRoleAssessor
        CommitteeRole.MEMBER -> strings.committeeRoleMember
        CommitteeRole.GENERAL_SECRETARY -> strings.committeeRoleGeneralSecretary
        CommitteeRole.PRESS_SPOKESPERSON -> strings.committeeRolePressSpokesperson
        CommitteeRole.MANAGING_DIRECTOR -> strings.committeeRoleManagingDirector
    }

/** One person on a public card -- name, role/office text, optional photo token and optional short bio. Never an id, an e-mail, a date or a number about the person. */
internal data class PublicPersonCard(
    val name: String,
    val roleOrOffice: String?,
    val photoToken: String?,
    val bio: String?,
)

/** A board card keeps the [role] so renderers can translate it; the card's own `roleOrOffice` stays `null`. */
internal data class PublicBoardCard(
    val card: PublicPersonCard,
    val role: CommitteeRole,
)

internal data class PublicChapterCard(
    val name: String,
    val crestToken: String?,
    val description: String?,
)

/** Hard caps, shared by the pages, the nav availability check and the embed feeds -- no pagination, and the lists are not countable beyond these. */
internal object PublicProfilesLimits {
    const val BOARD_MAX = 30
    const val POLITICIANS_MAX = 100
    const val CHAPTERS_MAX = 50

    /** DB-side pre-limit of the politician query -- sorted with a locale-aware [Collator] afterwards, then capped at [POLITICIANS_MAX]. */
    const val POLITICIANS_DB_PRELIMIT = 500

    /** Public office text of a politician (`mandate_text` can hold 2000 characters maintained by third parties). */
    const val OFFICE_MAX_CODEPOINTS = 200
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the ONE read layer behind `/vorstand`, `/politiker`,
 * `/landesverbaende`, the three embed feeds and the nav availability check. A page, its feed and
 * the nav tab therefore cannot disagree about who is visible ("no dead end, no lie", Raskin).
 *
 * Every function here runs inside the CALLER's `transaction {}` (this object never opens one) and
 * returns data-minimized cards -- no member id, no e-mail, no join/since date, no trust or like
 * figures, no counts about other members.
 *
 * **Visibility rules** (all evaluated live, no cache here):
 * - Board: current `EXECUTIVE_BOARD` mandate ([boardSelectionCondition], the SAME condition
 *   `/transparenz` uses); a photo only when [MemberPhotoStore.publicServableCondition] holds; a bio
 *   only while its consent is effective ([MemberPublicBioStore.effectivePublicCondition]).
 * - Politicians: ACTIVE profile, not revoked, member ACTIVE and not anonymized, AND an effective
 *   `POLITICIAN_LISTING` consent. No ranking, no trust weight.
 * - Chapters: every chapter (there is no active flag); name, crest token and description only.
 */
internal object PublicProfilesReader {
    /**
     * EXACT selection of the current executive board -- `committee`(EXECUTIVE_BOARD, active) ⋈
     * `committee_membership`(`until IS NULL`) ⋈ `member`(ACTIVE, not anonymized). Needs a query that
     * joins [CommitteeMembershipTable], [CommitteeTable] and [MemberTable]. Extracted from
     * `PublicTransparencyReader.loadBoard` so /transparenz and /vorstand can never drift apart.
     */
    fun boardSelectionCondition(): Op<Boolean> =
        (CommitteeTable.type eq CommitteeType.EXECUTIVE_BOARD) and
            (CommitteeTable.active eq true) and
            CommitteeMembershipTable.until.isNull() and
            MemberTable.anonymizedAt.isNull() and
            (MemberTable.status eq MemberStatus.ACTIVE)

    fun loadBoardCards(limit: Int = PublicProfilesLimits.BOARD_MAX): List<PublicBoardCard> {
        val source =
            (CommitteeMembershipTable innerJoin CommitteeTable innerJoin MemberTable)
                .join(MemberPhotoTable, JoinType.LEFT, MemberTable.id, MemberPhotoTable.memberId) {
                    MemberPhotoStore.publicServableCondition()
                }.join(MemberPublicBioTable, JoinType.LEFT, MemberTable.id, MemberPublicBioTable.memberId) {
                    MemberPublicBioStore.effectivePublicCondition()
                }
        return source
            .selectAll()
            .where { boardSelectionCondition() }
            .toList()
            .sortedWith(
                compareBy<ResultRow> { it[CommitteeMembershipTable.role].rank }
                    .thenBy { it[CommitteeMembershipTable.since] }
                    .thenBy { it[MemberTable.id].toString() },
            ).take(limit)
            .map { row ->
                PublicBoardCard(
                    card =
                        PublicPersonCard(
                            name = row[MemberTable.displayName],
                            roleOrOffice = null,
                            photoToken = row.photoToken(),
                            bio = row.bioText(),
                        ),
                    role = row[CommitteeMembershipTable.role],
                )
            }
    }

    fun loadPoliticianCards(limit: Int = PublicProfilesLimits.POLITICIANS_MAX): List<PublicPersonCard> {
        // politician_profile has THREE FKs to member (member_id/granted_by/revoked_by) -- an explicit
        // join on member_id is required, `innerJoin` would be ambiguous.
        val source =
            PoliticianProfileTable
                .join(MemberTable, JoinType.INNER, PoliticianProfileTable.memberId, MemberTable.id)
                .join(PublicRankingConsentEventTable, JoinType.INNER, MemberTable.id, PublicRankingConsentEventTable.memberId)
                .join(MemberPhotoTable, JoinType.LEFT, MemberTable.id, MemberPhotoTable.memberId) {
                    MemberPhotoStore.publicServableCondition()
                }.join(MemberPublicBioTable, JoinType.LEFT, MemberTable.id, MemberPublicBioTable.memberId) {
                    MemberPublicBioStore.effectivePublicCondition()
                }
        val collator = Collator.getInstance(Locale.GERMAN)
        return source
            .selectAll()
            .where {
                (PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE) and
                    PoliticianProfileTable.revokedAt.isNull() and
                    (MemberTable.status eq MemberStatus.ACTIVE) and
                    MemberTable.anonymizedAt.isNull() and
                    PublicRankingConsentStore.effectiveGrantCondition(PublicRankingKind.POLITICIAN_LISTING)
            }.orderBy(MemberTable.id)
            .limit(PublicProfilesLimits.POLITICIANS_DB_PRELIMIT)
            .toList()
            .sortedWith(
                Comparator<ResultRow> { a, b -> collator.compare(a[MemberTable.displayName], b[MemberTable.displayName]) }
                    .thenBy { it[MemberTable.id].toString() },
            ).take(limit)
            .map { row ->
                PublicPersonCard(
                    name = row[MemberTable.displayName],
                    roleOrOffice = publicOfficeText(row[PoliticianProfileTable.mandateText]),
                    photoToken = row.photoToken(),
                    bio = row.bioText(),
                )
            }
    }

    fun loadChapterCards(limit: Int = PublicProfilesLimits.CHAPTERS_MAX): List<PublicChapterCard> {
        val collator = Collator.getInstance(Locale.GERMAN)
        return RegionalChapterTable
            .selectAll()
            .toList()
            .sortedWith(
                Comparator<ResultRow> { a, b -> collator.compare(a[RegionalChapterTable.name], b[RegionalChapterTable.name]) }
                    .thenBy { it[RegionalChapterTable.id].toString() },
            ).take(limit)
            .map { row ->
                PublicChapterCard(
                    name = row[RegionalChapterTable.name],
                    crestToken = if (row[RegionalChapterTable.crestImageId] != null) row[RegionalChapterTable.crestPublicToken] else null,
                    description = row[RegionalChapterTable.description]?.takeIf { it.isNotBlank() },
                )
            }
    }

    /**
     * The public office line of a politician: `mandate_text` squeezed to ONE line and cut to
     * [PublicProfilesLimits.OFFICE_MAX_CODEPOINTS] code points (with an ellipsis). The field can hold
     * 2000 characters and is maintained by a third party (BOARD) -- the listing consent covers the
     * name and the office, not an essay.
     */
    fun publicOfficeText(mandateText: String?): String? {
        val oneLine = mandateText?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (oneLine.isEmpty()) return null
        val max = PublicProfilesLimits.OFFICE_MAX_CODEPOINTS
        if (oneLine.codePointCount(0, oneLine.length) <= max) return oneLine
        val end = oneLine.offsetByCodePoints(0, max - 1)
        return oneLine.substring(0, end).trimEnd() + "\u2026"
    }

    private fun ResultRow.photoToken(): String? = this[MemberPhotoTable.publicToken]

    private fun ResultRow.bioText(): String? = this.getOrNull(MemberPublicBioTable.bioText)
}

/**
 * Builds the absolute image URLs of the cards -- ONLY from tokens that match the exact token
 * pattern, so a corrupt database value can never become a link or break out of an attribute.
 */
internal object PublicProfileUrls {
    fun photoUrl(
        baseUrl: String,
        token: String?,
    ): String? = token?.takeIf { MemberPhotoPolicy.PUBLIC_TOKEN_PATTERN.matches(it) }?.let { "$baseUrl/public/member-photos/$it" }

    fun crestUrl(
        baseUrl: String,
        token: String?,
    ): String? = token?.takeIf { MemberPhotoPolicy.PUBLIC_TOKEN_PATTERN.matches(it) }?.let { "$baseUrl/public/chapter-crests/$it" }
}
