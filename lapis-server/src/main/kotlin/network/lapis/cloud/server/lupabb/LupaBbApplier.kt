package network.lapis.cloud.server.lupabb

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.member.MemberCountryStore
import network.lapis.cloud.server.member.MemberRoleStatusMutations
import network.lapis.cloud.server.member.MemberStatusHistory
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.PeerGuard
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbApplyFailureDto
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.LupaBbSyncRunSnapshot
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** One record that cannot be written; carries only a fixed code. */
private class RowFailure(
    val code: LupaBbRowReasonCode,
) : RuntimeException(code.name)

/**
 * V1.9.226 -- writes a comparison, record by record. Every record is its own transaction: the member row is locked `FOR UPDATE`, the Lapis side is
 * re-read and compared with the fingerprint of the preview (`CHANGED_SINCE_PREVIEW` when somebody changed the member in between), then the fields,
 * the profile, the country, the chapter and (only through [MemberRoleStatusMutations.applyStatusChangeLocked]) the status are written and audited.
 * A failing record is counted with its fixed code and never stops the run.
 *
 * NEVER done here: deleting or deactivating a member, creating an account, sending a mail or a notice, writing the e-mail address of an existing
 * member, writing the legacy `member.country` text. Sessions of a member whose new status blocks the login are revoked AFTER the commit.
 */
internal class LupaBbApplier(
    private val chapterEnforced: Boolean,
) {
    private enum class Outcome { CREATED, UPDATED, UNCHANGED, MISSING_MARKED }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun apply(
        entry: LupaBbPreviewStore.Entry,
        actor: CurrentMember,
    ): LupaBbApplyResultDto {
        var created = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        var ambiguous = 0
        var errors = 0
        var missing = 0
        val failures = ArrayList<LupaBbApplyFailureDto>()
        val today = OrganizationTimeZone.today()
        for (row in entry.rows) {
            when (row.category) {
                LupaBbCategory.UNCHANGED -> unchanged++
                LupaBbCategory.SKIPPED -> skipped++
                LupaBbCategory.AMBIGUOUS -> ambiguous++
                LupaBbCategory.ERROR -> errors++
                LupaBbCategory.NEW, LupaBbCategory.CHANGED, LupaBbCategory.MISSING -> {
                    try {
                        val now = ServerClock.now()
                        val revoke = BooleanArray(1)
                        val outcome = transaction { applyRow(row = row, actor = actor, now = now, today = today, revokeSessions = revoke) }
                        if (revoke[0] && row.memberId != null) SessionStore.revokeAllForMember(memberId = row.memberId)
                        when (outcome) {
                            Outcome.CREATED -> created++
                            Outcome.UPDATED -> updated++
                            Outcome.UNCHANGED -> unchanged++
                            Outcome.MISSING_MARKED -> missing++
                        }
                    } catch (e: RowFailure) {
                        errors++
                        if (failures.size < MAX_FAILURES) failures += LupaBbApplyFailureDto(personNumber = row.personNumber, code = e.code)
                    } catch (e: RegionalChapterRequiredException) {
                        errors++
                        if (failures.size <
                            MAX_FAILURES
                        ) {
                            failures += LupaBbApplyFailureDto(personNumber = row.personNumber, code = LupaBbRowReasonCode.CHAPTER_REQUIRED)
                        }
                    } catch (e: Exception) {
                        // class name only: the message of a database or mapping exception can quote personal data
                        logger.warn { "LupaBB apply: a record could not be written (${e::class.simpleName})" }
                        errors++
                        if (failures.size <
                            MAX_FAILURES
                        ) {
                            failures += LupaBbApplyFailureDto(personNumber = row.personNumber, code = LupaBbRowReasonCode.WRITE_FAILED)
                        }
                    }
                }
            }
        }
        val runId = Uuid.random()
        val finishedAt = ServerClock.now()
        transaction {
            LupaBbSyncRunTable.insert {
                it[id] = runId
                it[startedBy] = actor.memberId
                it[startedAt] = entry.startedAt
                it[LupaBbSyncRunTable.finishedAt] = finishedAt
                it[createdCount] = created
                it[updatedCount] = updated
                it[unchangedCount] = unchanged
                it[skippedCount] = skipped
                it[ambiguousCount] = ambiguous
                it[errorCount] = errors
                it[missingCount] = missing
                it[dataSha256] = entry.plan.dataSha256
            }
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.LUPABB_SYNC_RUN,
                entityId = runId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        LupaBbSyncRunSnapshot.serializer(),
                        LupaBbSyncRunSnapshot(
                            created = created,
                            updated = updated,
                            unchanged = unchanged,
                            skipped = skipped,
                            ambiguous = ambiguous,
                            errors = errors,
                            missing = missing,
                            dataSha256 = entry.plan.dataSha256,
                        ),
                    ),
                occurredAt = finishedAt,
            )
        }
        return LupaBbApplyResultDto(
            runId = runId.toString(),
            created = created,
            updated = updated,
            unchanged = unchanged,
            skipped = skipped,
            ambiguous = ambiguous,
            errors = errors,
            missing = missing,
            failures = failures,
        )
    }

    private fun applyRow(
        row: PlanRow,
        actor: CurrentMember,
        now: LocalDateTime,
        today: LocalDate,
        revokeSessions: BooleanArray,
    ): Outcome =
        when (row.category) {
            LupaBbCategory.NEW -> insertMember(row = row, actor = actor, now = now)
            LupaBbCategory.CHANGED, LupaBbCategory.MISSING ->
                updateMember(
                    row = row,
                    actor = actor,
                    now = now,
                    today = today,
                    revokeSessions = revokeSessions,
                )
            else -> error("not a writable category")
        }

    private fun insertMember(
        row: PlanRow,
        actor: CurrentMember,
        now: LocalDateTime,
    ): Outcome {
        val r = row.record ?: throw RowFailure(LupaBbRowReasonCode.WRITE_FAILED)
        val email = r.email ?: throw RowFailure(LupaBbRowReasonCode.NO_EMAIL)
        val id = Uuid.random()
        val joinDay = r.joinedAt ?: OrganizationTimeZone.today()
        try {
            withSavepoint(name = "lupabb_member_insert") {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = r.displayName
                    it[MemberTable.email] = email
                    it[status] = r.status
                    it[joinedAt] = joinDay
                    // as every other way of creating a supporter (registration, seed): the day the person became a friend
                    if (r.status == MemberStatus.FRIEND) it[friendSince] = joinDay
                    it[street] = r.street
                    it[postalCode] = r.postalCode
                    it[city] = r.city
                    it[dateOfBirth] = r.dateOfBirth
                    it[lupabbPersonNumber] = r.personNumber
                    it[regionalChapterId] = row.chapterId
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
            val emailTaken = MemberTable.selectAll().where { MemberTable.email eq email }.any()
            throw RowFailure(if (emailTaken) LupaBbRowReasonCode.EMAIL_TAKEN else LupaBbRowReasonCode.PERSON_NUMBER_TAKEN)
        }
        (r.country as? LupaBbCountry.Code)?.let { MemberCountryStore.setCountry(memberId = id, code = it.code) }
        upsertProfile(memberId = id, r = r, now = now, touched = row.diffs.map { it.field })
        MemberStatusHistory.recordLocked(
            memberId = id,
            newStatus = r.status,
            now = now,
            source = MemberStatusHistorySource.LUPABB_SYNC,
            effectiveFrom = joinDay.atStartOfDayIn(OrganizationTimeZone.current()).toLocalDateTime(TimeZone.UTC),
        )
        audit(
            actor = actor,
            memberId = id,
            action = AuditAction.CREATE,
            status = r.status,
            fields = row.diffs.map { it.field },
            now = now,
            displayNameChanged = true,
        )
        return Outcome.CREATED
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun updateMember(
        row: PlanRow,
        actor: CurrentMember,
        now: LocalDateTime,
        today: LocalDate,
        revokeSessions: BooleanArray,
    ): Outcome {
        val id = row.memberId ?: throw RowFailure(LupaBbRowReasonCode.WRITE_FAILED)
        val locked =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .forMemberUpdate()
                .singleOrNull()
                ?: throw RowFailure(LupaBbRowReasonCode.WRITE_FAILED)
        if (locked[MemberTable.anonymizedAt] != null) throw RowFailure(LupaBbRowReasonCode.ANONYMIZED)
        val current = LupaBbMemberLoader.loadOne(id) ?: throw RowFailure(LupaBbRowReasonCode.WRITE_FAILED)
        if (current.fingerprint() != row.fingerprint) throw RowFailure(LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW)

        val fields = row.diffs.map { it.field }.toSet()
        if (row.category == LupaBbCategory.MISSING) {
            MemberTable.update({ MemberTable.id eq id }) { it[lupabbMissingSince] = today }
            audit(
                actor = actor,
                memberId = id,
                action = AuditAction.UPDATE,
                status = current.status,
                fields = listOf(LupaBbField.MISSING_FLAG),
                now = now,
                displayNameChanged = false,
            )
            return Outcome.MISSING_MARKED
        }
        val r = row.record ?: throw RowFailure(LupaBbRowReasonCode.WRITE_FAILED)
        if (fields.isEmpty()) return Outcome.UNCHANGED
        val memberColumns =
            setOf(
                LupaBbField.PERSON_NUMBER,
                LupaBbField.DISPLAY_NAME,
                LupaBbField.STREET,
                LupaBbField.POSTAL_CODE,
                LupaBbField.CITY,
                LupaBbField.DATE_OF_BIRTH,
                LupaBbField.MISSING_FLAG,
                LupaBbField.REGIONAL_CHAPTER,
            )
        if (fields.any { it in memberColumns }) {
            try {
                withSavepoint(name = "lupabb_member_update") {
                    MemberTable.update({ MemberTable.id eq id }) {
                        if (LupaBbField.PERSON_NUMBER in fields) it[lupabbPersonNumber] = r.personNumber
                        if (LupaBbField.DISPLAY_NAME in fields) it[displayName] = r.displayName
                        if (LupaBbField.STREET in fields) it[street] = r.street
                        if (LupaBbField.POSTAL_CODE in fields) it[postalCode] = r.postalCode
                        if (LupaBbField.CITY in fields) it[city] = r.city
                        if (LupaBbField.DATE_OF_BIRTH in fields) it[dateOfBirth] = r.dateOfBirth
                        if (LupaBbField.MISSING_FLAG in fields) it[lupabbMissingSince] = null
                        if (LupaBbField.REGIONAL_CHAPTER in fields) it[regionalChapterId] = row.chapterId
                    }
                }
            } catch (e: Exception) {
                if (e.isUniqueViolation()) throw RowFailure(LupaBbRowReasonCode.PERSON_NUMBER_TAKEN)
                throw e
            }
        }
        if (LupaBbField.COUNTRY in fields) {
            MemberCountryStore.setCountry(memberId = id, code = (r.country as? LupaBbCountry.Code)?.code)
        }
        val profileFields =
            setOf(
                LupaBbField.GIVEN_NAME,
                LupaBbField.FAMILY_NAME,
                LupaBbField.TITLE,
                LupaBbField.NAME_AFFIX,
                LupaBbField.COMPANY,
                LupaBbField.PERSON_TYPE,
                LupaBbField.ADDRESS_SUPPLEMENT,
                LupaBbField.PO_BOX,
                LupaBbField.PHONE,
                LupaBbField.MOBILE,
            )
        if (fields.any { it in profileFields }) upsertProfile(memberId = id, r = r, now = now, touched = fields.toList())

        var resultingStatus = current.status
        val fieldAuditNeeded = fields.any { it != LupaBbField.STATUS }
        if (LupaBbField.STATUS in fields) {
            val facts = PeerGuard.lockFactsAfterMemberLock(targetId = id, memberRow = locked, requesterId = actor.memberId)
            if (facts.targetRole != null && facts.targetRole in ESCALATED_ROLES || id == actor.memberId) {
                throw RowFailure(LupaBbRowReasonCode.ADMIN_ROLE_MANUAL)
            }
            val outcome =
                MemberRoleStatusMutations.applyStatusChangeLocked(
                    actor = actor,
                    targetId = id,
                    newStatus = r.status,
                    trimmedReason = STATUS_REASON,
                    dateOfDeath = null,
                    row = locked,
                    existingRole = facts.targetRole,
                    lockedAccountRows = facts.lockedAccountRows,
                    now = now,
                    regionalChapterEnforced = chapterEnforced,
                    peerFacts = null,
                    historySource = MemberStatusHistorySource.LUPABB_SYNC,
                )
            revokeSessions[0] = outcome.revokeSessions
            resultingStatus = r.status
        }
        if (fieldAuditNeeded) {
            audit(
                actor = actor,
                memberId = id,
                action = AuditAction.UPDATE,
                status = resultingStatus,
                fields = fields.filter { it != LupaBbField.STATUS },
                now = now,
                displayNameChanged = LupaBbField.DISPLAY_NAME in fields,
            )
        }
        return Outcome.UPDATED
    }

    private fun upsertProfile(
        memberId: Uuid,
        r: LupaBbRecord,
        now: LocalDateTime,
        touched: Collection<LupaBbField>,
    ) {
        val keys = touched.joinToString(",") { it.name }.take(MAX_TOUCHED_LENGTH)
        val exists = MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq memberId }.any()
        if (exists) {
            MemberLupaBbProfileTable.update({ MemberLupaBbProfileTable.memberId eq memberId }) {
                it[givenName] = r.givenName
                it[familyName] = r.familyName
                it[title] = r.title
                it[nameAffix] = r.nameAffix
                it[company] = r.company
                it[personType] = r.personType
                it[phone] = r.phone
                it[mobile] = r.mobile
                it[addressSupplement] = r.addressSupplement
                it[poBox] = r.poBox
                it[lastSyncedAt] = now
                it[lastTouchedFields] = keys
            }
        } else {
            MemberLupaBbProfileTable.insert {
                it[MemberLupaBbProfileTable.memberId] = memberId
                it[givenName] = r.givenName
                it[familyName] = r.familyName
                it[title] = r.title
                it[nameAffix] = r.nameAffix
                it[company] = r.company
                it[personType] = r.personType
                it[phone] = r.phone
                it[mobile] = r.mobile
                it[addressSupplement] = r.addressSupplement
                it[poBox] = r.poBox
                it[lastSyncedAt] = now
                it[lastTouchedFields] = keys
            }
        }
    }

    @Suppress("LongParameterList")
    private fun audit(
        actor: CurrentMember,
        memberId: Uuid,
        action: AuditAction,
        status: MemberStatus,
        fields: Collection<LupaBbField>,
        now: LocalDateTime,
        displayNameChanged: Boolean,
    ) {
        val before = MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = status, role = null)
        val after =
            before.copy(
                displayNameChanged = displayNameChanged,
                lupabbFields = fields.map { it.name }.distinct().sorted(),
                lupabbLinked = LupaBbField.PERSON_NUMBER in fields,
            )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEMBER,
            entityId = memberId,
            action = action,
            before = if (action == AuditAction.CREATE) null else Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
            occurredAt = now,
        )
    }

    private companion object {
        const val MAX_FAILURES = 200
        const val MAX_TOUCHED_LENGTH = 400
        const val STATUS_REASON = "LupaBB-Abgleich"
    }
}
