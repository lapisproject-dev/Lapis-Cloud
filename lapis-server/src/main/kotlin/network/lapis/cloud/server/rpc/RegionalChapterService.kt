package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberRegionalChapterSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOfficerDto
import network.lapis.cloud.shared.domain.RegionalChapterOfficerSnapshot
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.domain.RegionalChapterSnapshot
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IRegionalChapterService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RegionalChapterInUseException
import network.lapis.cloud.shared.rpc.RegionalChapterLimitReachedException
import network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException
import network.lapis.cloud.shared.rpc.RegionalChapterOfficerIneligibleException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- a standalone
 * `docs/architecture/regional-chapters.adoc` design doc is, per the CHANGELOG's own "Umfang dieser
 * Welle" disclosure, NOT YET BUILT (deferred to a follow-up wave); this class's own KDoc and
 * [network.lapis.cloud.shared.rpc.IRegionalChapterService]'s carry the model instead. ADMIN
 * maintains the flat chapter list and grants/revokes "Landesvorstand" (regional-chapter officer)
 * access; BOARD may
 * additionally (re-)assign a member to a chapter (D9/F9: BOARD can therefore indirectly REVOKE an
 * officer's access -- by moving them out of their chapter -- but can never GRANT one).
 *
 * **F5**: chapter NAMES are not secret -- [IRegistrationService.listRegionalChapterOptions] (this
 * service's sibling on [RegistrationService]) exposes them unauthenticated, so the counts this
 * service's own [listChapters] additionally reveals to BOARD/ADMIN/TREASURER... no, [listChapters]
 * itself is BOARD/ADMIN only (TREASURER is deliberately NOT admitted -- unlike
 * `listMembersForAdministration`, this is chapter-*administration*, not roster reading).
 */
class RegionalChapterService(
    private val call: ApplicationCall,
) : IRegionalChapterService {
    override suspend fun listChapters(): RegionalChapterOverviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        return transaction {
            val chapters =
                RegionalChapterTable
                    .selectAll()
                    .orderBy(RegionalChapterTable.name)
                    .map { it[RegionalChapterTable.id] }
            val rows = chapters.map { loadChapterDto(it) }
            val unassignedCount =
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.regionalChapterId.isNull()) and
                            (MemberTable.status inList setOf(MemberStatus.ACTIVE, MemberStatus.APPLICATION)) and
                            (MemberTable.anonymizedAt.isNull())
                    }.count()
                    .toInt()
            RegionalChapterOverviewDto(chapters = rows, unassignedCount = unassignedCount)
        }
    }

    override suspend fun createChapter(name: String): RegionalChapterDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val normalized = RegionalChapterRules.normalizeName(name)
        if (!RegionalChapterRules.isValidName(normalized)) {
            throw BadRequestException(
                "name must be ${RegionalChapterRules.NAME_MIN}-${RegionalChapterRules.NAME_MAX} characters, no control characters",
            )
        }
        val nameKey = RegionalChapterRules.nameKey(normalized)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Serializes concurrent createChapter/renameChapter callers against the
            // organization-wide MAX_CHAPTERS limit -- same "lock the singleton settings row"
            // idiom OrganizationSettingsService.updateOrganizationSettings already establishes
            // for a different limit.
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .forUpdate()
                .single()

            val alreadyExists = RegionalChapterTable.selectAll().where { RegionalChapterTable.nameKey eq nameKey }.count() > 0
            if (alreadyExists) throw RegionalChapterNameTakenException()
            val existingCount = RegionalChapterTable.selectAll().count()
            if (existingCount >= RegionalChapterRules.MAX_CHAPTERS) throw RegionalChapterLimitReachedException()

            val id = Uuid.random()
            try {
                RegionalChapterTable.insert {
                    it[RegionalChapterTable.id] = id
                    it[RegionalChapterTable.name] = normalized
                    it[RegionalChapterTable.nameKey] = nameKey
                    it[createdAt] = now
                }
            } catch (e: ExposedSQLException) {
                // Race backstop, same two-layer uniqueness idiom MemberService.updateMemberCoreData
                // already establishes for MemberTable's UNIQUE(email).
                logger.warn { "RegionalChapterTable.insert failed in createChapter: ${e::class.simpleName}" }
                throw RegionalChapterNameTakenException()
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.REGIONAL_CHAPTER,
                entityId = id,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(RegionalChapterSnapshot.serializer(), RegionalChapterSnapshot(name = normalized)),
                occurredAt = now,
            )
            loadChapterDto(id)
        }
    }

    override suspend fun renameChapter(
        chapterId: String,
        name: String,
    ): RegionalChapterDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = chapterId.toChapterUuidOrThrow()
        val normalized = RegionalChapterRules.normalizeName(name)
        if (!RegionalChapterRules.isValidName(normalized)) {
            throw BadRequestException(
                "name must be ${RegionalChapterRules.NAME_MIN}-${RegionalChapterRules.NAME_MAX} characters, no control characters",
            )
        }
        val nameKey = RegionalChapterRules.nameKey(normalized)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .forUpdate()
                .single()

            val row =
                RegionalChapterTable
                    .selectAll()
                    .where { RegionalChapterTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Regional chapter $chapterId not found")
            val beforeName = row[RegionalChapterTable.name]

            val takenByAnother =
                RegionalChapterTable
                    .selectAll()
                    .where { (RegionalChapterTable.nameKey eq nameKey) and (RegionalChapterTable.id neq id) }
                    .count() > 0
            if (takenByAnother) throw RegionalChapterNameTakenException()

            try {
                RegionalChapterTable.update({ RegionalChapterTable.id eq id }) {
                    it[RegionalChapterTable.name] = normalized
                    it[RegionalChapterTable.nameKey] = nameKey
                }
            } catch (e: ExposedSQLException) {
                logger.warn { "RegionalChapterTable.update failed in renameChapter: ${e::class.simpleName}" }
                throw RegionalChapterNameTakenException()
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.REGIONAL_CHAPTER,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(RegionalChapterSnapshot.serializer(), RegionalChapterSnapshot(name = beforeName)),
                after = Json.encodeToString(RegionalChapterSnapshot.serializer(), RegionalChapterSnapshot(name = normalized)),
                occurredAt = now,
            )
            loadChapterDto(id)
        }
    }

    override suspend fun deleteChapter(chapterId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = chapterId.toChapterUuidOrThrow()
        val now = DbClock.nowLocalDateTime()
        transaction {
            val row =
                RegionalChapterTable
                    .selectAll()
                    .where { RegionalChapterTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Regional chapter $chapterId not found")

            // F2: blocked even for a chapter whose only assignments are WITHDRAWN/DECEASED
            // members -- deliberately not narrowed to ACTIVE, see interface KDoc "F2".
            val assignedCount = MemberTable.selectAll().where { MemberTable.regionalChapterId eq id }.count()
            if (assignedCount > 0) throw RegionalChapterInUseException()
            val activeOfficerCount =
                RegionalChapterOfficerTable
                    .selectAll()
                    .where { (RegionalChapterOfficerTable.regionalChapterId eq id) and (RegionalChapterOfficerTable.revokedAt.isNull()) }
                    .count()
            if (activeOfficerCount > 0) throw RegionalChapterInUseException()

            // Only revoked officer grants can remain at this point (their history stays in the
            // audit log) -- delete them so a later chapter of the SAME name is not haunted by
            // stale rows; harmless no-op if there are none.
            RegionalChapterOfficerTable.deleteWhere { RegionalChapterOfficerTable.regionalChapterId eq id }

            RegionalChapterTable.deleteWhere { RegionalChapterTable.id eq id }
            // VOID, not a DELETE literal -- see AuditAction KDoc "VOID" and
            // AuditEntityType.REGIONAL_CHAPTER KDoc: this is the sole surviving record that the
            // chapter ever existed.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.REGIONAL_CHAPTER,
                entityId = id,
                action = AuditAction.VOID,
                before =
                    Json.encodeToString(
                        RegionalChapterSnapshot.serializer(),
                        RegionalChapterSnapshot(name = row[RegionalChapterTable.name]),
                    ),
                after = null,
                occurredAt = now,
            )
        }
    }

    override suspend fun assignMemberToChapter(
        memberId: String,
        chapterId: String?,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        val newChapterId = chapterId?.toChapterUuidOrThrow()
        val now = DbClock.nowLocalDateTime()
        transaction {
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
            if (memberRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer be edited")
            }
            if (newChapterId != null && memberRow[MemberTable.status] !in RegionalChapterRules.ASSIGNABLE_STATUSES) {
                throw BadRequestException("Member status ${memberRow[MemberTable.status]} may not be assigned to a regional chapter")
            }
            if (newChapterId != null) {
                // Serializes against deleteChapter's own row lock.
                RegionalChapterTable
                    .selectAll()
                    .where { RegionalChapterTable.id eq newChapterId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Regional chapter $chapterId not found")
            }

            val oldChapterId = memberRow[MemberTable.regionalChapterId]
            if (oldChapterId == newChapterId) return@transaction // no-op, see interface KDoc

            // Security fix (LOW, Peer-Schutz) -- same ESCALATED_ROLES boundary
            // MemberService.updateMemberStatus/updateMemberMembershipTier and
            // MemberFamilyService.addFamilyMember/removeFamilyMember already draw: a BOARD caller
            // may not reassign a fellow BOARD/TREASURER/ADMIN peer's (or their OWN, since BOARD
            // is itself in ESCALATED_ROLES) chapter -- only ADMIN may. `currentAccountRole` below
            // takes its own `.forUpdate()` lock on AccountTable (same idiom
            // MemberService/MemberFamilyService's own file-private helper of the same name
            // establishes), so this always observes the row's truly-current role, never a stale
            // snapshot racing a concurrent updateMemberRole commit. Without this check, a BOARD
            // account could move an ADMIN/TREASURER/fellow-BOARD member (or itself) into a
            // different chapter -- and, per this method's own auto-revoke logic below, silently
            // strip that peer's active regional-chapter-officer grant in the same call.
            currentAccountRole(targetId)?.let { existingRole ->
                if (existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)
            }

            MemberTable.update({ MemberTable.id eq targetId }) {
                it[regionalChapterId] = newChapterId
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                action = AuditAction.UPDATE,
                before =
                    Json.encodeToString(
                        MemberRegionalChapterSnapshot.serializer(),
                        MemberRegionalChapterSnapshot(regionalChapterId = oldChapterId?.toString()),
                    ),
                after =
                    Json.encodeToString(
                        MemberRegionalChapterSnapshot.serializer(),
                        MemberRegionalChapterSnapshot(regionalChapterId = newChapterId?.toString()),
                    ),
                occurredAt = now,
            )

            // The member's own ACTIVE officer grant, if any, is now for the WRONG chapter (or for
            // no chapter at all) -- revoke it in the SAME transaction, same reasoning
            // requireRegionalChapterBeforeActivation's sibling
            // revokeActiveRegionalChapterOfficerGrant establishes for a status change (F9: this is
            // how BOARD can indirectly revoke -- but never grant -- officer access).
            val activeGrant =
                RegionalChapterOfficerTable
                    .selectAll()
                    .where { RegionalChapterOfficerTable.activeForMemberId eq targetId }
                    .singleOrNull()
            if (activeGrant != null && activeGrant[RegionalChapterOfficerTable.regionalChapterId] != newChapterId) {
                revokeOfficerGrantRow(
                    grantId = activeGrant[RegionalChapterOfficerTable.id],
                    memberId = targetId,
                    chapterId = activeGrant[RegionalChapterOfficerTable.regionalChapterId],
                    now = now,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            }
        }
    }

    override suspend fun listOfficers(chapterId: String): List<RegionalChapterOfficerDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = chapterId.toChapterUuidOrThrow()
        return transaction {
            // Two simple, single-table-joined queries rather than a self-joined alias over
            // MemberTable (member_id AND granted_by_member_id both point at MemberTable) -- at
            // MAX_ACTIVE_OFFICERS_PER_CHAPTER scale (<= 25 rows) a second small lookup map is
            // simpler to reason about than a Table alias, same "plain Kotlin over an unproven
            // SQL idiom" preference MemberService.listMembersForAdministration's own
            // statusCounts KDoc documents for a single call site.
            val grants =
                // Explicit join, not `innerJoin` -- MemberTable has TWO FK references from
                // RegionalChapterOfficerTable (member_id AND granted_by_member_id), so Exposed's
                // implicit-join inference cannot disambiguate (same "multiple primary key <->
                // foreign key references" pitfall MemberFamilyLinkTable's own KDoc documents for
                // itself in MemberService.adminRosterSource).
                RegionalChapterOfficerTable
                    .join(MemberTable, JoinType.INNER, RegionalChapterOfficerTable.memberId, MemberTable.id)
                    .selectAll()
                    .where { (RegionalChapterOfficerTable.regionalChapterId eq id) and (RegionalChapterOfficerTable.revokedAt.isNull()) }
                    .orderBy(MemberTable.displayName)
                    .toList()
            val granterIds = grants.mapNotNull { it[RegionalChapterOfficerTable.grantedByMemberId] }.toSet()
            val granterNames =
                if (granterIds.isEmpty()) {
                    emptyMap()
                } else {
                    MemberTable
                        .select(MemberTable.id, MemberTable.displayName)
                        .where { MemberTable.id inList granterIds }
                        .associate { it[MemberTable.id] to it[MemberTable.displayName] }
                }
            grants.map {
                RegionalChapterOfficerDto(
                    grantId = it[RegionalChapterOfficerTable.id].toString(),
                    memberId = it[RegionalChapterOfficerTable.memberId].toString(),
                    displayName = it[MemberTable.displayName],
                    grantedAt = it[RegionalChapterOfficerTable.grantedAt],
                    grantedByDisplayName = it[RegionalChapterOfficerTable.grantedByMemberId]?.let { granterId -> granterNames[granterId] },
                )
            }
        }
    }

    override suspend fun grantOfficer(
        memberId: String,
        chapterId: String,
    ): RegionalChapterOfficerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        val cId = chapterId.toChapterUuidOrThrow()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Lock ordering: MemberTable BEFORE RegionalChapterTable, always -- same order
            // `assignMemberToChapter` below locks in. Deadlock fix (review finding): this method
            // used to lock the chapter row first and the member row second, the EXACT REVERSE of
            // `assignMemberToChapter`'s own order; two concurrent callers (one calling each method
            // for the same member/chapter pair) could each hold the lock the other was waiting on,
            // and Postgres would abort one of them with an unhandled deadlock exception. Both
            // methods now acquire member-then-chapter, so no cycle can form.
            //
            // Postgres rejects `FOR UPDATE` on the nullable side of an OUTER join ("FOR UPDATE
            // cannot be applied to the nullable side of an outer join") -- H2 (the test suite's
            // MODE=PostgreSQL dialect) silently accepts it, which let a `(MemberTable leftJoin
            // AccountTable).forUpdate()` query here pass every test while throwing an unhandled
            // ExposedSQLException on real Postgres (PdV/ELB/Staging), see this method's own
            // review-fix history. Lock ONLY MemberTable (the row this method actually needs to
            // serialize concurrent grantOfficer/assignMemberToChapter callers on), then check
            // account existence as a separate, unlocked query -- same "lock only what you need to
            // serialize on" posture the chapter/member row locks elsewhere in this file already
            // take.
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")

            // Serializes against deleteChapter and against a concurrent grantOfficer for the
            // SAME chapter (the MAX_ACTIVE_OFFICERS_PER_CHAPTER count below must see a
            // consistent, locked snapshot).
            RegionalChapterTable
                .selectAll()
                .where { RegionalChapterTable.id eq cId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Regional chapter $chapterId not found")

            val hasAccount = AccountTable.selectAll().where { AccountTable.memberId eq targetId }.count() > 0
            val eligible =
                memberRow[MemberTable.status] == MemberStatus.ACTIVE &&
                    memberRow[MemberTable.regionalChapterId] == cId &&
                    hasAccount
            if (!eligible) throw RegionalChapterOfficerIneligibleException()

            val activeCount =
                RegionalChapterOfficerTable
                    .selectAll()
                    .where { (RegionalChapterOfficerTable.regionalChapterId eq cId) and (RegionalChapterOfficerTable.revokedAt.isNull()) }
                    .count()
            if (activeCount >= RegionalChapterRules.MAX_ACTIVE_OFFICERS_PER_CHAPTER) throw RegionalChapterLimitReachedException()

            val existingActive =
                RegionalChapterOfficerTable
                    .selectAll()
                    .where { RegionalChapterOfficerTable.activeForMemberId eq targetId }
                    .singleOrNull()
            if (existingActive != null) {
                if (existingActive[RegionalChapterOfficerTable.regionalChapterId] == cId) {
                    // Already an officer of THIS chapter -- idempotent no-op, return the existing grant.
                    return@transaction existingActive.toOfficerDto()
                }
                revokeOfficerGrantRow(
                    grantId = existingActive[RegionalChapterOfficerTable.id],
                    memberId = targetId,
                    chapterId = existingActive[RegionalChapterOfficerTable.regionalChapterId],
                    now = now,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            }

            val grantId = Uuid.random()
            try {
                RegionalChapterOfficerTable.insert {
                    it[id] = grantId
                    it[RegionalChapterOfficerTable.memberId] = targetId
                    it[regionalChapterId] = cId
                    it[grantedAt] = now
                    it[grantedByMemberId] = current.memberId
                    it[revokedAt] = null
                    it[activeForMemberId] = targetId
                }
            } catch (e: ExposedSQLException) {
                // Race backstop against uq_regional_chapter_officer_active -- same two-layer
                // uniqueness idiom used throughout this codebase.
                logger.warn { "RegionalChapterOfficerTable.insert failed in grantOfficer: ${e::class.simpleName}" }
                throw ConflictException("Member already holds an active regional-chapter-officer grant")
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.REGIONAL_CHAPTER_OFFICER,
                entityId = grantId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        RegionalChapterOfficerSnapshot.serializer(),
                        RegionalChapterOfficerSnapshot(memberId = targetId.toString(), regionalChapterId = cId.toString()),
                    ),
                occurredAt = now,
            )
            RegionalChapterOfficerDto(
                grantId = grantId.toString(),
                memberId = targetId.toString(),
                displayName = memberRow[MemberTable.displayName],
                grantedAt = now,
                grantedByDisplayName = null,
            )
        }
    }

    override suspend fun revokeOfficer(grantId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = grantId.toGrantUuidOrThrow()
        val now = DbClock.nowLocalDateTime()
        transaction {
            val row =
                RegionalChapterOfficerTable
                    .selectAll()
                    .where { RegionalChapterOfficerTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Officer grant $grantId not found")
            if (row[RegionalChapterOfficerTable.revokedAt] != null) return@transaction // idempotent no-op
            revokeOfficerGrantRow(
                grantId = id,
                memberId = row[RegionalChapterOfficerTable.memberId],
                chapterId = row[RegionalChapterOfficerTable.regionalChapterId],
                now = now,
                actorMemberId = current.memberId,
                actorRole = current.role,
            )
        }
    }

    private fun revokeOfficerGrantRow(
        grantId: Uuid,
        memberId: Uuid,
        chapterId: Uuid,
        now: LocalDateTime,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ) {
        RegionalChapterOfficerTable.update({ RegionalChapterOfficerTable.id eq grantId }) {
            it[revokedAt] = now
            it[activeForMemberId] = null
        }
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.REGIONAL_CHAPTER_OFFICER,
            entityId = grantId,
            action = AuditAction.UPDATE,
            before =
                Json.encodeToString(
                    RegionalChapterOfficerSnapshot.serializer(),
                    RegionalChapterOfficerSnapshot(memberId = memberId.toString(), regionalChapterId = chapterId.toString()),
                ),
            after = null,
            occurredAt = now,
        )
    }

    private fun ResultRow.toOfficerDto(): RegionalChapterOfficerDto {
        val displayName =
            MemberTable
                .select(MemberTable.displayName)
                .where { MemberTable.id eq this[RegionalChapterOfficerTable.memberId] }
                .single()[MemberTable.displayName]
        return RegionalChapterOfficerDto(
            grantId = this[RegionalChapterOfficerTable.id].toString(),
            memberId = this[RegionalChapterOfficerTable.memberId].toString(),
            displayName = displayName,
            grantedAt = this[RegionalChapterOfficerTable.grantedAt],
            grantedByDisplayName = null,
        )
    }

    private fun loadChapterDto(id: Uuid): RegionalChapterDto {
        val chapterRow = RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq id }.single()
        val activeMemberCount =
            MemberTable
                .selectAll()
                .where {
                    (MemberTable.regionalChapterId eq id) and
                        (MemberTable.status eq MemberStatus.ACTIVE) and
                        (MemberTable.anonymizedAt.isNull())
                }.count()
                .toInt()
        val assignedMemberCount =
            MemberTable
                .selectAll()
                .where { MemberTable.regionalChapterId eq id }
                .count()
                .toInt()
        val activeOfficerCount =
            RegionalChapterOfficerTable
                .selectAll()
                .where { (RegionalChapterOfficerTable.regionalChapterId eq id) and (RegionalChapterOfficerTable.revokedAt.isNull()) }
                .count()
                .toInt()
        return RegionalChapterDto(
            id = id.toString(),
            name = chapterRow[RegionalChapterTable.name],
            activeMemberCount = activeMemberCount,
            assignedMemberCount = assignedMemberCount,
            activeOfficerCount = activeOfficerCount,
        )
    }
}

private val logger = KotlinLogging.logger {}

// Local copy, not a reuse of MemberService.kt's/MemberFamilyService.kt's own file-private
// `currentAccountRole` (Kotlin top-level `private` is file-scoped, so those are invisible here)
// -- identical shape, including the `.forUpdate()` lock (see MemberService.kt's own KDoc on its
// copy for the TOCTOU reasoning this mirrors).
private fun currentAccountRole(memberId: Uuid): AccountRole? =
    AccountTable
        .selectAll()
        .where { AccountTable.memberId eq memberId }
        .forUpdate()
        .singleOrNull()
        ?.get(AccountTable.role)

private fun String.toChapterUuidOrThrow(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Regional chapter $this not found") }

private fun String.toGrantUuidOrThrow(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Officer grant $this not found") }

// Local copy, not a reuse of MemberService.kt's own file-private `toMemberUuidOrThrow` (Kotlin
// top-level `private` is file-scoped, so that one is invisible here) -- identical shape.
private fun String.toMemberUuidOrThrow(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Member $this not found") }
