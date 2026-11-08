package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- which journal entries a caller may book into and read. The ONE new authorization boundary of the "chapter treasurer".
 *
 * - [Federal]: the caller holds one of the federal roles of the method (TREASURER/ADMIN to write, plus BOARD to read). Sees and books everything.
 * - [Chapters]: a plain MEMBER (organization member) who holds at least one ACTIVE chapter-treasurer grant. Sees and books ONLY the entries whose
 *   `regional_chapter_id` is one of [Chapters.ids]. Never the federal level (NULL), never another chapter.
 *
 * **Default is refusal.** [bookingScope] is called only by an allowlist of methods of `AccountingService` (pinned by
 * `BookingScopeAllowlistScanTest`); every other method keeps its `requireRole` and therefore still refuses a MEMBER. A chapter treasurer thus
 * cannot reach the income statement, balance sheet, DATEV, exports, the donor register or any set-up.
 */
sealed interface BookingScope {
    data object Federal : BookingScope

    data class Chapters(
        val ids: Set<Uuid>,
    ) : BookingScope
}

/**
 * The chapters the caller holds an ACTIVE grant for. **Must be called inside an open `transaction {}`**: it re-reads the status of the caller
 * and the grant table fresh on every call (no cache), so a revoke or a status change committed earlier is always effective on the next call.
 * Empty for every role other than MEMBER and for a member that is not an organization member.
 */
fun CurrentMember.activeChapterTreasurerIds(): Set<Uuid> {
    check(TransactionManager.currentOrNull() != null) { "activeChapterTreasurerIds must be called inside an open transaction {} block" }
    if (role != AccountRole.MEMBER) return emptySet()
    if (status !in MemberStatusSets.ORGANIZATION_MEMBER) return emptySet()
    return RegionalChapterTreasurerTable
        .selectAll()
        .where { (RegionalChapterTreasurerTable.memberId eq memberId) and RegionalChapterTreasurerTable.revokedAt.isNull() }
        .map { it[RegionalChapterTreasurerTable.regionalChapterId] }
        .toSet()
}

/**
 * The scope of the caller. [federalRoles] are the roles that see/book everything for the method at hand (the role wins FIRST: a BOARD/TREASURER/
 * ADMIN who additionally holds a grant is [BookingScope.Federal] when the role is in [federalRoles]). Anything else without a grant is refused
 * with [ForbiddenException] -- a BOARD member does not gain a write right, a guest or applicant gets nothing.
 */
fun CurrentMember.bookingScope(federalRoles: Array<AccountRole>): BookingScope {
    check(TransactionManager.currentOrNull() != null) { "bookingScope must be called inside an open transaction {} block" }
    if (role in federalRoles) return BookingScope.Federal
    val ids = activeChapterTreasurerIds()
    if (ids.isEmpty()) throw ForbiddenException()
    return BookingScope.Chapters(ids)
}

/**
 * The SQL condition on `journal_entry` for the scope and the optional filter [unitFilter] (`null` = everything in the scope, `"FEDERAL"` = only
 * federal entries, otherwise a chapter id). `null` result = no restriction. A chapter treasurer asking for a unit outside the scope (including
 * `"FEDERAL"`) is refused with [ForbiddenException]; a federal caller naming a chapter that does not exist gets [NotFoundException].
 */
fun BookingScope.journalCondition(unitFilter: String?): Op<Boolean>? =
    when (this) {
        is BookingScope.Federal ->
            when (unitFilter) {
                null -> null
                FEDERAL_UNIT_FILTER -> JournalEntryTable.regionalChapterId.isNull()
                else -> JournalEntryTable.regionalChapterId eq parseUnitFilter(unitFilter)
            }
        is BookingScope.Chapters ->
            when (unitFilter) {
                null -> JournalEntryTable.regionalChapterId inList ids
                FEDERAL_UNIT_FILTER -> throw ForbiddenException()
                else -> {
                    val id = runCatching { Uuid.parse(unitFilter) }.getOrNull() ?: throw ForbiddenException()
                    if (id !in ids) throw ForbiddenException()
                    JournalEntryTable.regionalChapterId eq id
                }
            }
    }

/** `true` iff the entry of [chapterId] (`null` = federal) lies inside the scope. */
fun BookingScope.covers(chapterId: Uuid?): Boolean =
    when (this) {
        is BookingScope.Federal -> true
        is BookingScope.Chapters -> chapterId != null && chapterId in ids
    }

private fun parseUnitFilter(raw: String): Uuid {
    val id = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw NotFoundException("Invalid unit filter")
    RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq id }.singleOrNull()
        ?: throw NotFoundException("Regional chapter not found")
    return id
}

const val FEDERAL_UNIT_FILTER: String = "FEDERAL"

/** Resolves the granted chapters into the `(id, name)` pairs [network.lapis.cloud.shared.domain.SessionInfoDto.chapterTreasurerScopes] carries. */
fun resolveChapterTreasurerRefs(ids: Set<Uuid>): List<RegionalChapterRefDto> {
    if (ids.isEmpty()) return emptyList()
    return RegionalChapterTable
        .selectAll()
        .where { RegionalChapterTable.id inList ids }
        .map { RegionalChapterRefDto(id = it[RegionalChapterTable.id].toString(), name = it[RegionalChapterTable.name]) }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
}
