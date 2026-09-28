package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- a flat (single-level) set of named
 * regional chapters an ADMIN maintains. See `network.lapis.cloud.server.security
 * .RegionalChapterVisibility` for how an assigned "Landesvorstand" (regional-chapter officer)
 * grant narrows `network.lapis.cloud.shared.rpc.IMemberService.listMembersForAdministration`'s
 * result set.
 *
 * Deliberately NOT hierarchical (no Kreis-/Ortsverbände) for THIS wave -- a standalone
 * `docs/architecture/regional-chapters.adoc` design doc listing deferred follow-up waves (a
 * "Zurückgestellt" section) is, per the CHANGELOG's own "Umfang dieser Welle" disclosure, not yet
 * built.
 */

/** Minimal id+name reference -- the shape [network.lapis.cloud.shared.rpc.IRegistrationService.listRegionalChapterOptions] returns unauthenticated, and used for the client's chapter picker/session-scope fields. */
@Serializable
data class RegionalChapterRefDto(
    val id: String,
    val name: String,
)

/**
 * ADMIN-facing row for [network.lapis.cloud.shared.rpc.IRegionalChapterService.listChapters].
 * [activeMemberCount] drives the roster (status == ACTIVE, non-anonymized only);
 * [assignedMemberCount] (any status, non-anonymized) drives the delete-blocked explanation --
 * Kilua RPC never transmits an [dev.kilua.rpc.AbstractServiceException] subclass's own `message`
 * across the wire (see [network.lapis.cloud.shared.rpc.RegionalChapterInUseException] KDoc), so
 * the client reads its numbers from THIS row, not from the exception.
 */
@Serializable
data class RegionalChapterDto(
    val id: String,
    val name: String,
    val activeMemberCount: Int,
    val assignedMemberCount: Int,
    val activeOfficerCount: Int,
)

/**
 * [unassignedCount] counts `regionalChapterId == null` members with `status in {ACTIVE,
 * APPLICATION}` -- a NARROWER set than [RegionalChapterRules.ASSIGNABLE_STATUSES] (which also
 * permits WITHDRAWN/DECEASED, see that val's own KDoc "an already-WITHDRAWN/DECEASED member keeps
 * their historical chapter assignment") -- doc fixed (review finding: an earlier revision of this
 * KDoc incorrectly claimed the full [RegionalChapterRules.ASSIGNABLE_STATUSES] set). Same scope
 * `RegionalChapterService.listChapters`'s own "Nicht zugeordnet" worklist query uses.
 */
@Serializable
data class RegionalChapterOverviewDto(
    val chapters: List<RegionalChapterDto>,
    val unassignedCount: Int,
)

/** One "Landesvorstand" (regional-chapter officer) grant, ADMIN-only read via [network.lapis.cloud.shared.rpc.IRegionalChapterService.listOfficers]. Never carries the member's email (only display name, already ADMIN-visible via the roster). */
@Serializable
data class RegionalChapterOfficerDto(
    val grantId: String,
    val memberId: String,
    val displayName: String,
    val grantedAt: LocalDateTime,
    val grantedByDisplayName: String?,
)

/**
 * Shared, DOM-free validation/normalization rules -- server ([network.lapis.cloud.server.rpc
 * .RegionalChapterService]) and client (chapter-editor dialogs) both call this, same idiom
 * [DeathDateRules]/[MembershipAgreementDisclaimer] already establish for a shared rule object.
 */
object RegionalChapterRules {
    const val NAME_MIN = 2
    const val NAME_MAX = 80
    const val MAX_CHAPTERS = 50
    const val MAX_ACTIVE_OFFICERS_PER_CHAPTER = 25

    /**
     * §0.7 -- which [MemberStatus] values may be assigned to a chapter at all. Deliberately
     * excludes DONOR/FRIEND/GUEST (not members) and does not distinguish further beyond that --
     * an already-WITHDRAWN/DECEASED member keeps their historical chapter assignment (see
     * `RegionalChapterService.deleteChapter` KDoc "F2").
     */
    val ASSIGNABLE_STATUSES: Set<MemberStatus> =
        setOf(MemberStatus.APPLICATION, MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DECEASED)

    /** Trims and collapses inner whitespace runs to a single space; rejects (via [isValidName]) anything left blank or carrying a control character. */
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * The [nameKey] length check (security fix, LOW robustness) guards against a `lowercase()`
     * length change -- e.g. U+0130 'İ' (Turkish dotted capital I) lowercases to TWO characters
     * ('i' + U+0307 COMBINING DOT ABOVE) on the JVM's Unicode-aware `lowercase()`. An 80-character
     * name built entirely from 'İ' passes THIS length check (80) but produces a 160-character
     * `name_key`, which overflows `VARCHAR(80)` (`RegionalChapterTable.nameKey`) and surfaces as
     * an [ExposedSQLException] that `createChapter`/`renameChapter` misinterpret as
     * [network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException] -- a misleading error for
     * the ADMIN caller, not a security issue (only ADMIN can reach this).
     */
    fun isValidName(normalized: String): Boolean =
        normalized.length in NAME_MIN..NAME_MAX &&
            normalized.none { it.isISOControl() } &&
            nameKey(normalized).length <= NAME_MAX

    /** H2/Postgres cannot index an expression (`lower(name)`) -- this column-level key is what `uq_regional_chapter_name_key` actually enforces uniqueness on. `Locale.ROOT`-equivalent: JS/JVM-portable, ASCII/Unicode `lowercase()` with no platform-default-locale dependency. */
    fun nameKey(normalized: String): String = normalized.lowercase()
}
