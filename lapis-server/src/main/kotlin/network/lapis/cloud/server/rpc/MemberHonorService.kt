package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberHonorDto
import network.lapis.cloud.shared.domain.MemberHonorInput
import network.lapis.cloud.shared.domain.MemberHonorLimits
import network.lapis.cloud.shared.domain.MemberHonorPageDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IMemberHonorService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val HONOR_READ_WRITE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- see [IMemberHonorService] KDoc for
 * the overall shape (BOARD/ADMIN read/write, ADMIN-only delete). Every method resolves
 * [network.lapis.cloud.server.security.CurrentMember] exactly once via [resolveCurrentMember], same
 * house rule the rest of the `rpc` package follows.
 *
 * **Deliberately no separate Store/Policy pair** (unlike `CrmContactStore`/`CrmContactPolicy`) --
 * see the Welle-Plan §1 "Architektur-Entscheidung" this class implements: `member_honor` is a
 * single, simple table with five validation rules and no concurrency problem, the same shape
 * [BoardMembershipService] already handles with direct Exposed access and no Store class.
 * [validate] lives as a private function on this class, tested through [IMemberHonorService]'s own
 * HTTP-level test suite (`MemberHonorServiceTest`), not a separate Policy test.
 *
 * **Deliberately does NOT write to `audit_log_entry`** (the GoBD hash-chained ledger) -- same
 * doctrine `38-crm.kuml.kts`'s "Why NOT audit_log_entry" file header states for `crm_interaction`,
 * which `41-member-honor.kuml.kts`'s own file header restates for this table's `title`/`note` free
 * text specifically.
 */
class MemberHonorService(
    private val call: ApplicationCall,
    private val clock: () -> LocalDate = { DbClock.nowLocalDateTime().date },
) : IMemberHonorService {
    override suspend fun listHonors(
        memberId: String?,
        category: MemberHonorCategory?,
        limit: Int,
        offset: Int,
    ): MemberHonorPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*HONOR_READ_WRITE_ROLES)
        val memberUuid = memberId?.let { it.toUuidOrBadRequest("Member") }
        val effectiveLimit = limit.coerceIn(1, MemberHonorLimits.MAX_LIMIT)
        val effectiveOffset = offset.coerceAtLeast(0)

        return transaction {
            // Condition built up-front as a nullable Op<Boolean> -- same idiom
            // CrmContactStore.list/EventStore establish, this codebase does not chain
            // `.where {}.andWhere {}` anywhere.
            var condition: Op<Boolean>? = null
            if (memberUuid != null) condition = (MemberHonorTable.memberId eq memberUuid).andWith(condition)
            if (category != null) condition = (MemberHonorTable.category eq category).andWith(condition)
            val fixedCondition = condition

            fun baseQuery() =
                if (fixedCondition != null) {
                    honorMemberJoin().selectAll().where { fixedCondition }
                } else {
                    honorMemberJoin().selectAll()
                }

            val total = baseQuery().count().toInt()
            val entries =
                baseQuery()
                    .orderBy(
                        MemberHonorTable.awardedAt to SortOrder.DESC,
                        MemberHonorTable.title to SortOrder.ASC,
                        MemberHonorTable.id to SortOrder.ASC,
                    ).limit(effectiveLimit)
                    .offset(effectiveOffset.toLong())
                    .map { it.toDto() }

            MemberHonorPageDto(entries = entries, totalCount = total, limit = effectiveLimit, offset = effectiveOffset)
        }
    }

    override suspend fun createHonor(input: MemberHonorInput): MemberHonorDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*HONOR_READ_WRITE_ROLES)
        val memberUuid = input.memberId.toUuidOrBadRequest("Member")
        return transaction {
            requireExistingEligibleMember(memberUuid)
            validate(input = input, today = clock())
            val id = Uuid.random()
            MemberHonorTable.insert {
                it[MemberHonorTable.id] = id
                it[MemberHonorTable.memberId] = memberUuid
                it[category] = input.category
                it[title] = input.title.trim()
                it[awardedAt] = input.awardedAt
                it[awardedBy] = input.awardedBy?.trim()?.takeIf { b -> b.isNotBlank() }
                it[note] = input.note?.trim()?.takeIf { n -> n.isNotBlank() }
                it[recordedBy] = current.memberId
                it[recordedAt] = DbClock.nowLocalDateTime()
            }
            logger.info { "member honor created: actor=${current.memberId} actorRole=${current.role} honorId=$id memberId=$memberUuid" }
            loadHonorOrThrow(id)
        }
    }

    override suspend fun updateHonor(
        id: String,
        input: MemberHonorInput,
    ): MemberHonorDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*HONOR_READ_WRITE_ROLES)
        val honorId = id.toUuidOrNotFound("MemberHonor")
        val memberUuid = input.memberId.toUuidOrBadRequest("Member")
        return transaction {
            MemberHonorTable.selectAll().where { MemberHonorTable.id eq honorId }.singleOrNull()
                ?: throw NotFoundException("MemberHonor $id not found")
            requireExistingEligibleMember(memberUuid)
            validate(input = input, today = clock())
            MemberHonorTable.update({ MemberHonorTable.id eq honorId }) {
                it[MemberHonorTable.memberId] = memberUuid
                it[category] = input.category
                it[title] = input.title.trim()
                it[awardedAt] = input.awardedAt
                it[awardedBy] = input.awardedBy?.trim()?.takeIf { b -> b.isNotBlank() }
                it[note] = input.note?.trim()?.takeIf { n -> n.isNotBlank() }
            }
            logger.info {
                "member honor updated: actor=${current.memberId} actorRole=${current.role} honorId=$honorId memberId=$memberUuid"
            }
            loadHonorOrThrow(honorId)
        }
    }

    /**
     * Role: **ADMIN** -- a real, irreversible data-correction DELETE (mis-entered row), NOT an Art.
     * 17 erasure. See [IMemberHonorService.deleteHonor] KDoc.
     */
    override suspend fun deleteHonor(id: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val honorId = id.toUuidOrNotFound("MemberHonor")
        transaction {
            val row =
                MemberHonorTable.selectAll().where { MemberHonorTable.id eq honorId }.singleOrNull()
                    ?: throw NotFoundException("MemberHonor $id not found")
            val memberId = row[MemberHonorTable.memberId]
            MemberHonorTable.deleteWhere { MemberHonorTable.id eq honorId }
            logger.info { "member honor deleted: actor=${current.memberId} actorRole=${current.role} honorId=$honorId memberId=$memberId" }
        }
    }

    /** Server-side invariants, independent of any client-side pre-check -- see [IMemberHonorService] KDoc. */
    private fun validate(
        input: MemberHonorInput,
        today: LocalDate,
    ) {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > MemberHonorLimits.TITLE_MAX_LENGTH) {
            throw BadRequestException("title must be non-blank and at most ${MemberHonorLimits.TITLE_MAX_LENGTH} characters")
        }
        val awardedBy = input.awardedBy?.trim()
        if (awardedBy != null && awardedBy.length > MemberHonorLimits.AWARDED_BY_MAX_LENGTH) {
            throw BadRequestException("awardedBy must be at most ${MemberHonorLimits.AWARDED_BY_MAX_LENGTH} characters")
        }
        val note = input.note?.trim()
        if (note != null && note.length > MemberHonorLimits.NOTE_MAX_LENGTH) {
            throw BadRequestException("note must be at most ${MemberHonorLimits.NOTE_MAX_LENGTH} characters")
        }
        if (input.awardedAt > today) {
            throw BadRequestException("awardedAt must not be in the future")
        }
    }

    /**
     * Must exist AND not be anonymized -- same posture [MemberAnniversaryService]'s own
     * `MemberTable.anonymizedAt.isNull()` exclusion establishes, restated here as a
     * [BadRequestException] (not [NotFoundException]) because a DSGVO-anonymized member row still
     * exists, it is simply no longer a valid target for a NEW honor.
     */
    private fun requireExistingEligibleMember(memberId: Uuid) {
        val row =
            MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
                ?: throw BadRequestException("Member $memberId not found")
        if (row[MemberTable.anonymizedAt] != null) {
            throw BadRequestException("Member $memberId is anonymized and can no longer be the target of a new honor")
        }
    }

    private fun loadHonorOrThrow(id: Uuid): MemberHonorDto =
        honorMemberJoin()
            .selectAll()
            .where { MemberHonorTable.id eq id }
            .singleOrNull()
            ?.toDto()
            ?: throw NotFoundException("MemberHonor $id not found")

    /**
     * Explicit join, never `innerJoin` -- [MemberHonorTable] has TWO FKs to [MemberTable]
     * (`member_id`, `recorded_by`), so Exposed's implicit-join inference throws
     * `IllegalStateException: multiple primary key <-> foreign key references` at runtime (caught
     * during this wave's own implementation, see `MemberHonorServiceTest`). Always joins on
     * `member_id` (the honoree, for `memberDisplayName`) -- same explicit-join idiom
     * `CrmContactStore.interactionJoin`/`CrowdfundingService`'s own multi-member-FK joins already
     * establish.
     */
    private fun honorMemberJoin() = MemberHonorTable.join(MemberTable, JoinType.INNER, MemberHonorTable.memberId, MemberTable.id)

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }

    private fun String.toUuidOrBadRequest(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw BadRequestException("Invalid $kind id: $this") }
}

private fun Op<Boolean>.andWith(existing: Op<Boolean>?): Op<Boolean> = existing?.and(this) ?: this

private fun ResultRow.toDto(): MemberHonorDto =
    MemberHonorDto(
        id = this[MemberHonorTable.id].toString(),
        memberId = this[MemberHonorTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        category = this[MemberHonorTable.category],
        title = this[MemberHonorTable.title],
        awardedAt = this[MemberHonorTable.awardedAt],
        awardedBy = this[MemberHonorTable.awardedBy],
        note = this[MemberHonorTable.note],
        recordedById = this[MemberHonorTable.recordedBy].toString(),
        recordedAt = this[MemberHonorTable.recordedAt],
    )
