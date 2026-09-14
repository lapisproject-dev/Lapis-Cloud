package network.lapis.cloud.server.openitem.dunning

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.ReceivableDunningLevelTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.loadOpenItemDetail
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import network.lapis.cloud.shared.domain.ReceivableDunningLevelInput
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IReceivableDunningService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
private val NOTICE_ACTION_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private const val MIN_GRACE_OR_RESPONSE_DAYS = 1
private const val MAX_GRACE_OR_RESPONSE_DAYS = 365
private const val MAX_FEE_AMOUNT = 25.00
private const val MAX_LEVEL_NAME_LENGTH = 100

/** Matches every `*_reason` VARCHAR(500) column in V35__open_items.sql. */
private const val MAX_REASON_LENGTH = 500

/**
 * Welle V1.4.15 -- the dunning-domain sibling of `OpenItemService`. **Structurally, deliberately
 * independent** from `network.lapis.cloud.server.payment.dunning.DunningService`/`DunningPoller`
 * (the pre-existing member-contribution dunning domain): no shared import, no shared table, no
 * shared config. This wave's receivable dunning is scoped to a single escalation mechanism with an
 * optional fee -- no PDF, no postal dispatch, no compliance-disclaimer-acknowledgment flow.
 *
 * **Three safeguards replace the disclaimer-acknowledgment flow the member-contribution domain
 * has** (deliberate, see `docs/architecture/open-items.adoc` "Deferred/out of scope" for why a
 * disclaimer is not required here): [ReceivableDunningConfig.pollerEnabled] (env, one instance
 * only), `organization_settings.receivable_dunning_enabled` (DB flag, ADMIN-writable, defaults
 * `false`), and zero active [ReceivableDunningLevelTable] rows (a fresh organization has none
 * seeded).
 */
class ReceivableDunningService(
    private val call: ApplicationCall,
) : IReceivableDunningService {
    override suspend fun getReceivableDunningSettings(): ReceivableDunningSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*READ_ROLES)
        return transaction {
            val settingsRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()
            val activeLevelCount = ReceivableDunningLevelTable.selectAll().where { ReceivableDunningLevelTable.active eq true }.count()
            ReceivableDunningSettingsDto(
                receivableDunningEnabled = settingsRow[OrganizationSettingsTable.receivableDunningEnabled],
                pollerEnabled = ReceivableDunningConfig.load().pollerEnabled,
                activeLevelCount = activeLevelCount.toInt(),
            )
        }
    }

    override suspend fun enableReceivableDunning(): ReceivableDunningSettingsDto = setEnabled(true)

    override suspend fun disableReceivableDunning(): ReceivableDunningSettingsDto = setEnabled(false)

    private suspend fun setEnabled(enabled: Boolean): ReceivableDunningSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[receivableDunningEnabled] = enabled
            }
        }
        return getReceivableDunningSettings()
    }

    override suspend fun listReceivableDunningLevels(includeInactive: Boolean): List<ReceivableDunningLevelDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*READ_ROLES)
        return transaction {
            val rows = ReceivableDunningLevelTable.selectAll().orderBy(ReceivableDunningLevelTable.levelNumber to SortOrder.ASC).toList()
            (if (includeInactive) rows else rows.filter { it[ReceivableDunningLevelTable.active] }).map { it.toDto() }
        }
    }

    override suspend fun createReceivableDunningLevel(input: ReceivableDunningLevelInput): ReceivableDunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        validateLevelInput(input)
        return transaction {
            if (ReceivableDunningLevelTable.selectAll().where { ReceivableDunningLevelTable.levelNumber eq input.levelNumber }.any()) {
                throw ConflictException("levelNumber ${input.levelNumber} already exists")
            }
            val id = Uuid.random()
            ReceivableDunningLevelTable.insert {
                it[ReceivableDunningLevelTable.id] = id
                it[levelNumber] = input.levelNumber
                it[name] = input.name
                it[graceDays] = input.graceDays
                it[responseDays] = input.responseDays
                it[feeAmount] = input.feeAmount
                it[active] = input.active
                it[createdAt] = DbClock.nowLocalDateTime()
            }
            ReceivableDunningLevelTable
                .selectAll()
                .where { ReceivableDunningLevelTable.id eq id }
                .single()
                .toDto()
        }
    }

    override suspend fun updateReceivableDunningLevel(
        levelId: String,
        input: ReceivableDunningLevelInput,
    ): ReceivableDunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        validateLevelInput(input)
        val id = levelId.toReceivableDunningUuid("levelId")
        return transaction {
            ReceivableDunningLevelTable.selectAll().where { ReceivableDunningLevelTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("ReceivableDunningLevel $id not found")
            if (ReceivableDunningLevelTable
                    .selectAll()
                    .where { ReceivableDunningLevelTable.levelNumber eq input.levelNumber }
                    .any { it[ReceivableDunningLevelTable.id] != id }
            ) {
                throw ConflictException("levelNumber ${input.levelNumber} already exists")
            }
            ReceivableDunningLevelTable.update({ ReceivableDunningLevelTable.id eq id }) {
                it[levelNumber] = input.levelNumber
                it[name] = input.name
                it[graceDays] = input.graceDays
                it[responseDays] = input.responseDays
                it[feeAmount] = input.feeAmount
                it[active] = input.active
            }
            ReceivableDunningLevelTable
                .selectAll()
                .where { ReceivableDunningLevelTable.id eq id }
                .single()
                .toDto()
        }
    }

    override suspend fun deactivateReceivableDunningLevel(levelId: String): ReceivableDunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = levelId.toReceivableDunningUuid("levelId")
        return transaction {
            ReceivableDunningLevelTable.selectAll().where { ReceivableDunningLevelTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("ReceivableDunningLevel $id not found")
            ReceivableDunningLevelTable.update({ ReceivableDunningLevelTable.id eq id }) { it[active] = false }
            ReceivableDunningLevelTable
                .selectAll()
                .where { ReceivableDunningLevelTable.id eq id }
                .single()
                .toDto()
        }
    }

    override suspend fun issueReceivableDunningNotice(openItemId: String): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*NOTICE_ACTION_ROLES)
        val id = openItemId.toReceivableDunningUuid("openItemId")
        return transaction {
            requireReceivableItem(id)
            val outcome =
                ReceivableDunningEngine.issueNextLevel(
                    itemId = id,
                    asOf = DbClock.nowLocalDateTime().date,
                    respectGraceDays = false,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            if (outcome is ReceivableDunningEngine.IssueOutcome.NothingDue) {
                throw ConflictException("OpenItem $id has no further active dunning level to issue")
            }
            loadOpenItemDetail(id)
        }
    }

    override suspend fun skipReceivableDunningLevel(
        openItemId: String,
        reason: String,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*NOTICE_ACTION_ROLES)
        if (reason.isBlank()) throw BadRequestException("reason must not be blank")
        if (reason.length > MAX_REASON_LENGTH) throw BadRequestException("reason must be at most $MAX_REASON_LENGTH characters")
        val id = openItemId.toReceivableDunningUuid("openItemId")
        return transaction {
            requireReceivableItem(id)
            ReceivableDunningEngine.skipNextLevel(
                itemId = id,
                asOf = DbClock.nowLocalDateTime().date,
                reason = reason,
                actorMemberId = current.memberId,
                actorRole = current.role,
            )
            loadOpenItemDetail(id)
        }
    }

    override suspend fun cancelReceivableDunningNotice(
        noticeId: String,
        reason: String,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*NOTICE_ACTION_ROLES)
        if (reason.isBlank()) throw BadRequestException("reason must not be blank")
        if (reason.length > MAX_REASON_LENGTH) throw BadRequestException("reason must be at most $MAX_REASON_LENGTH characters")
        val id = noticeId.toReceivableDunningUuid("noticeId")
        return transaction {
            val noticeRow =
                ReceivableDunningNoticeTable
                    .selectAll()
                    .where { ReceivableDunningNoticeTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("ReceivableDunningNotice $id not found")
            if (noticeRow[ReceivableDunningNoticeTable.status] != ReceivableDunningNoticeStatus.ISSUED) {
                throw ConflictException("ReceivableDunningNotice $id is not ISSUED, cannot cancel")
            }
            val openItemId = noticeRow[ReceivableDunningNoticeTable.openItemId]
            ReceivableDunningNoticeTable.update({ ReceivableDunningNoticeTable.id eq id }) {
                it[status] = ReceivableDunningNoticeStatus.CANCELLED
                it[cancelledAt] = DbClock.nowLocalDateTime()
                it[cancellationReason] = reason
            }
            loadOpenItemDetail(openItemId)
        }
    }

    private fun requireReceivableItem(id: Uuid) {
        val row =
            OpenItemTable
                .selectAll()
                .where { OpenItemTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("OpenItem $id not found")
        if (row[OpenItemTable.direction] != OpenItemDirection.RECEIVABLE) {
            throw ConflictException("OpenItem $id is not RECEIVABLE -- only receivables are dunned")
        }
    }

    private fun validateLevelInput(input: ReceivableDunningLevelInput) {
        if (input.name.isBlank() ||
            input.name.length > MAX_LEVEL_NAME_LENGTH
        ) {
            throw BadRequestException("name must be 1..$MAX_LEVEL_NAME_LENGTH characters")
        }
        if (input.graceDays !in
            MIN_GRACE_OR_RESPONSE_DAYS..MAX_GRACE_OR_RESPONSE_DAYS
        ) {
            throw BadRequestException("graceDays must be $MIN_GRACE_OR_RESPONSE_DAYS..$MAX_GRACE_OR_RESPONSE_DAYS")
        }
        if (input.responseDays !in
            MIN_GRACE_OR_RESPONSE_DAYS..MAX_GRACE_OR_RESPONSE_DAYS
        ) {
            throw BadRequestException("responseDays must be $MIN_GRACE_OR_RESPONSE_DAYS..$MAX_GRACE_OR_RESPONSE_DAYS")
        }
        input.feeAmount?.let {
            if (it < BigDecimal.ZERO ||
                it > BigDecimal.valueOf(MAX_FEE_AMOUNT)
            ) {
                throw BadRequestException("feeAmount must be 0..$MAX_FEE_AMOUNT")
            }
        }
    }
}

private fun ResultRow.toDto(): ReceivableDunningLevelDto =
    ReceivableDunningLevelDto(
        id = this[ReceivableDunningLevelTable.id].toString(),
        levelNumber = this[ReceivableDunningLevelTable.levelNumber],
        name = this[ReceivableDunningLevelTable.name],
        graceDays = this[ReceivableDunningLevelTable.graceDays],
        responseDays = this[ReceivableDunningLevelTable.responseDays],
        feeAmount = this[ReceivableDunningLevelTable.feeAmount],
        active = this[ReceivableDunningLevelTable.active],
    )

private fun String.toReceivableDunningUuid(role: String): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid $role: $this") }
