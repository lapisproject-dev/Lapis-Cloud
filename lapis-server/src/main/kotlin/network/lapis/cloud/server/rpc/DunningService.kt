package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.dunning.DunningConfig
import network.lapis.cloud.server.payment.dunning.DunningIssueOutcome
import network.lapis.cloud.server.payment.dunning.DunningIssueRequest
import network.lapis.cloud.server.payment.dunning.archiveDunningNoticePdf
import network.lapis.cloud.server.payment.dunning.currentCycleNumber
import network.lapis.cloud.server.payment.dunning.dunningReferenceDate
import network.lapis.cloud.server.payment.dunning.requireDunningUsable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningCaseDetailDto
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.DunningComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.DunningLevelDto
import network.lapis.cloud.shared.domain.DunningLevelInput
import network.lapis.cloud.shared.domain.DunningLevelSnapshot
import network.lapis.cloud.shared.domain.DunningNoticeDto
import network.lapis.cloud.shared.domain.DunningNoticeSnapshot
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.DunningSettingsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IDunningService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import network.lapis.cloud.server.payment.dunning.issueDunningNotice as issueDunningNoticeInternal

private val DUNNING_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val DUNNING_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Mahngebuehr-Obergrenze -- see `V9__dunning.sql`'s `chk_dunning_level_fee_amount` and `DunningComplianceDisclaimer` KDoc. */
private val MAX_FEE_AMOUNT = BigDecimal("25.00")
private const val MAX_LEVEL_NAME_LENGTH = 100

/** Generous ceiling, not a realistic escalation-ladder length -- just enough to reject `levelNumber <= 0`/absurd input. See [DunningService.validateLevelInput]. */
private const val MAX_LEVEL_NUMBER = 1000

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Implements [IDunningService] -- see that interface's
 * KDoc for the full gate/safeguard mechanism.
 *
 * ## Framework rules for every method here (mirrors `SepaService`'s own "Framework rules")
 *
 * 1. Role gate first, before any lookup.
 * 2. Feature gate via [requireDunningUsable] on every WRITE method.
 * 3. [AuditLogRecorder.record] is the LAST lock-taking operation of every writing transaction.
 * 4. [NotFoundException] for a foreign resource the caller has no legitimate reason to probe.
 *
 * ### Constructor default exists for tests only -- production MUST pass a shared instance
 *
 * Same "kilua-rpc constructs a fresh instance per dispatch" reasoning [SepaService]'s own KDoc
 * documents -- [issueRateLimiter]/[dunningConfig]/[documentStorageRoot]/[postalMailProvider] MUST
 * be threaded through `Application.module` as explicit, shared, module-scoped `val`s.
 */
class DunningService(
    private val call: ApplicationCall,
    private val dunningConfig: DunningConfig = DunningConfig.load(),
    private val documentStorageRoot: File = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage"),
    private val issueRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
) : IDunningService {
    // ════════════════════════════════════════════════════════════════════
    // Gate + Rechtshinweis
    // ════════════════════════════════════════════════════════════════════

    override suspend fun getDunningComplianceDisclaimer(): DunningComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return DunningComplianceDisclaimerDto(
            version = DunningComplianceDisclaimer.VERSION,
            text = DunningComplianceDisclaimer.TEXT,
            sha256 = DunningComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enableDunning(input: DunningComplianceAcknowledgmentInput): DunningSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (!DunningComplianceDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 stimmen nicht mit dem aktuellen DunningComplianceDisclaimer ueberein -- " +
                    "getDunningComplianceDisclaimer erneut aufrufen und dessen AKTUELLE version/sha256 unveraendert senden.",
            )
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            DunningComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = input.disclaimerVersion
                it[disclaimerSha256] = input.disclaimerSha256
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[dunningEnabled] = true
            }
            loadDunningSettingsDto()
        }
    }

    override suspend fun disableDunning(): DunningSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[dunningEnabled] = false
            }
            loadDunningSettingsDto()
        }
    }

    override suspend fun getDunningSettings(): DunningSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadDunningSettingsDto() }
    }

    // ════════════════════════════════════════════════════════════════════
    // Mahnstufen-Konfiguration
    // ════════════════════════════════════════════════════════════════════

    override suspend fun listDunningLevels(includeInactive: Boolean): List<DunningLevelDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val rows =
                if (includeInactive) {
                    DunningLevelTable.selectAll()
                } else {
                    DunningLevelTable.selectAll().where { DunningLevelTable.active eq true }
                }
            rows.orderBy(DunningLevelTable.levelNumber, SortOrder.ASC).map { it.toDunningLevelDto() }
        }
    }

    override suspend fun createDunningLevel(input: DunningLevelInput): DunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val name = validateLevelInput(input)
        return transaction {
            val duplicate =
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.levelNumber eq input.levelNumber }
                    .limit(1)
                    .any()
            if (duplicate) throw ConflictException("Mahnstufe ${input.levelNumber} existiert bereits.")

            val id = Uuid.random()
            DunningLevelTable.insert {
                it[DunningLevelTable.id] = id
                it[levelNumber] = input.levelNumber
                it[DunningLevelTable.name] = name
                it[graceDays] = input.graceDays
                it[responseDays] = input.responseDays
                it[feeAmount] = input.feeAmount
                it[active] = input.active
                it[createdAt] = DbClock.nowLocalDateTime()
            }
            // Security review LOW finding -- level CRUD had NO audit trail at all, even though this
            // configuration determines what fee a member gets charged. Same idiom
            // `SepaService.updateSepaCreditorSettings` already established for its own analogous
            // org-wide payment configuration: [AuditEntityType.ORGANIZATION_SETTINGS] +
            // `entityId = ORGANIZATION_SETTINGS_ID` (a `dunning_level` row is configuration, not a
            // per-member fact, so it does not warrant its own [AuditEntityType] literal -- see
            // [DunningLevelSnapshot] KDoc). See the security review finding this fixes.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        DunningLevelSnapshot.serializer(),
                        DunningLevelSnapshot(
                            levelNumber = input.levelNumber,
                            name = name,
                            graceDays = input.graceDays,
                            responseDays = input.responseDays,
                            feeAmount = input.feeAmount,
                            active = input.active,
                        ),
                    ),
            )
            loadDunningLevelDto(id)
        }
    }

    override suspend fun updateDunningLevel(
        levelId: String,
        input: DunningLevelInput,
    ): DunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = levelId.toDunningUuid("DunningLevel")
        val name = validateLevelInput(input)
        return transaction {
            val existing =
                DunningLevelTable.selectAll().where { DunningLevelTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Mahnstufe nicht gefunden.")
            val duplicate =
                DunningLevelTable
                    .selectAll()
                    .where { (DunningLevelTable.levelNumber eq input.levelNumber) and (DunningLevelTable.id neq id) }
                    .limit(1)
                    .any()
            if (duplicate) throw ConflictException("Mahnstufe ${input.levelNumber} existiert bereits.")

            val beforeSnapshot = existing.toDunningLevelSnapshot()
            val afterSnapshot =
                DunningLevelSnapshot(
                    levelNumber = input.levelNumber,
                    name = name,
                    graceDays = input.graceDays,
                    responseDays = input.responseDays,
                    feeAmount = input.feeAmount,
                    active = input.active,
                )
            DunningLevelTable.update({ DunningLevelTable.id eq id }) {
                it[levelNumber] = input.levelNumber
                it[DunningLevelTable.name] = name
                it[graceDays] = input.graceDays
                it[responseDays] = input.responseDays
                it[feeAmount] = input.feeAmount
                it[active] = input.active
            }
            // Same audit idiom as createDunningLevel above -- only written on an ACTUAL change, same
            // narrowing `SepaService.updateSepaCreditorSettings` already applies (a no-op update
            // request, e.g. a UI re-submitting the unchanged form, should not flood the audit chain).
            if (beforeSnapshot != afterSnapshot) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(DunningLevelSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(DunningLevelSnapshot.serializer(), afterSnapshot),
                )
            }
            loadDunningLevelDto(id)
        }
    }

    override suspend fun deactivateDunningLevel(levelId: String): DunningLevelDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = levelId.toDunningUuid("DunningLevel")
        return transaction {
            val existing =
                DunningLevelTable.selectAll().where { DunningLevelTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Mahnstufe nicht gefunden.")
            DunningLevelTable.update({ DunningLevelTable.id eq id }) { it[active] = false }
            // Same audit idiom as createDunningLevel/updateDunningLevel above -- only written when
            // this actually flips active true -> false (idempotent re-deactivation of an already-
            // inactive level is not a real change).
            if (existing[DunningLevelTable.active]) {
                val beforeSnapshot = existing.toDunningLevelSnapshot()
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(DunningLevelSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(DunningLevelSnapshot.serializer(), beforeSnapshot.copy(active = false)),
                )
            }
            loadDunningLevelDto(id)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Uebersicht
    // ════════════════════════════════════════════════════════════════════

    override suspend fun listDunningCases(
        onlyOpen: Boolean,
        limit: Int,
        beforeDueDate: LocalDate?,
    ): List<DunningCaseDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_READ_ROLES)
        val effectiveLimit = limit.coerceIn(1, 200)
        return transaction {
            // Loaded once, reused for every row's `toDunningCaseDto` call below instead of that
            // function re-querying `dunning_level` per row -- see the security review finding this
            // fixes ("N+1: one DunningLevelTable.selectAll() per case").
            val activeLevels =
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.active eq true }
                    .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                    .toList()

            // `limit`/`orderBy`/`beforeDueDate` pushed into SQL (not applied in Kotlin after
            // loading everything) -- an organization with thousands of DUNNABLE contributions used
            // to build an `inList` with as many bind parameters AND load every one of them into the
            // heap before sorting/capping. Condition built up-front as one `Op<Boolean>`, not a
            // `.where {}.andWhere {}` chain -- same convention `PoliticianService
            // .loadWeightHistory`/`FederationService`'s own comments already establish for this
            // codebase.
            val dunnableCondition =
                if (beforeDueDate != null) {
                    (ContributionTable.status inList ContributionStatusSets.DUNNABLE.toList()) and
                        (ContributionTable.dueDate less beforeDueDate)
                } else {
                    ContributionTable.status inList ContributionStatusSets.DUNNABLE.toList()
                }
            val dunnableRows =
                contributionJoin()
                    .selectAll()
                    .where { dunnableCondition }
                    .orderBy(ContributionTable.dueDate, SortOrder.ASC)
                    .limit(effectiveLimit)
                    .toList()

            val rows =
                if (onlyOpen) {
                    dunnableRows
                } else {
                    // Historical view: contributions that received at least one dunning notice but
                    // are no longer in a DUNNABLE status (e.g. PAID/WAIVED after being dunned).
                    // Bounded by however many distinct contributions were EVER dunned -- not by the
                    // organization's total contribution volume -- so this second condition stays
                    // cheap even when [dunnableRows] itself is capped at [effectiveLimit]. Merging
                    // each source's OWN sorted+capped top-`effectiveLimit` and re-capping below is
                    // sufficient to reconstruct the true combined top-`effectiveLimit`: any row
                    // missing from a source's own top-N cannot be in the merged top-N either.
                    //
                    // Formulated as a correlated `inSubQuery` against `dunning_notice.contribution_id`
                    // rather than materializing that set into Kotlin first: the former
                    // `DunningNoticeTable.selectAll().map { contributionId }.toSet()` loaded every
                    // COLUMN of every ROW in the entire dunning_notice table just to build an id set,
                    // and the resulting `ContributionTable.id inList historicIds` then bound one
                    // parameter per distinct EVER-dunned contribution -- a set that only grows over
                    // the instance's lifetime and eventually exceeds PostgreSQL's ~32,767
                    // bind-parameter limit, turning the historical view into a hard 500 while the
                    // `onlyOpen = true` view kept working. `alreadyIncluded` stays a plain `notInList`
                    // (bounded by [effectiveLimit], never large). See the security review finding
                    // this fixes.
                    val alreadyIncluded = dunnableRows.mapTo(mutableSetOf()) { it[ContributionTable.id] }
                    val everDunnedSubQuery = DunningNoticeTable.select(DunningNoticeTable.contributionId)
                    var historicCondition: Op<Boolean> = ContributionTable.id inSubQuery everDunnedSubQuery
                    if (alreadyIncluded.isNotEmpty()) {
                        historicCondition = historicCondition and (ContributionTable.id notInList alreadyIncluded)
                    }
                    if (beforeDueDate != null) {
                        historicCondition = historicCondition and (ContributionTable.dueDate less beforeDueDate)
                    }
                    val historicRows =
                        contributionJoin()
                            .selectAll()
                            .where { historicCondition }
                            .orderBy(ContributionTable.dueDate, SortOrder.ASC)
                            .limit(effectiveLimit)
                            .toList()
                    (dunnableRows + historicRows)
                        .sortedBy { it[ContributionTable.dueDate] }
                        .take(effectiveLimit)
                }
            if (rows.isEmpty()) return@transaction emptyList()

            val allNoticesByContribution =
                DunningNoticeTable
                    .selectAll()
                    .where { DunningNoticeTable.contributionId inList rows.map { it[ContributionTable.id] } }
                    .groupBy { it[DunningNoticeTable.contributionId] }
            rows.map { row ->
                row.toDunningCaseDto(
                    notices = allNoticesByContribution[row[ContributionTable.id]].orEmpty(),
                    activeLevels = activeLevels,
                )
            }
        }
    }

    override suspend fun getDunningCase(contributionId: String): List<DunningCaseDetailDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_READ_ROLES)
        val id = runCatching { Uuid.parse(contributionId) }.getOrNull() ?: return emptyList()
        return transaction {
            val row = contributionJoin().selectAll().where { ContributionTable.id eq id }.singleOrNull() ?: return@transaction emptyList()
            val activeLevels =
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.active eq true }
                    .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                    .toList()
            val notices =
                DunningNoticeTable
                    .selectAll()
                    .where { DunningNoticeTable.contributionId eq id }
                    .orderBy(DunningNoticeTable.issuedAt, SortOrder.ASC)
                    .toList()
            listOf(
                DunningCaseDetailDto(
                    case = row.toDunningCaseDto(notices = notices, activeLevels = activeLevels),
                    notices = notices.map { it.toDunningNoticeDto() },
                ),
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Manuelle Steuerung
    // ════════════════════════════════════════════════════════════════════

    override suspend fun issueDunningNotice(contributionId: String): DunningCaseDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_TREASURY_ROLES)
        requireDunningUsable()
        requireWithinRate(current.memberId.toString())
        val id = contributionId.toDunningUuid("Contribution")

        val outcome =
            issueDunningNoticeInternal(
                request =
                    DunningIssueRequest(
                        contributionId = id,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        now = DbClock.nowLocalDateTime(),
                        respectSchedule = false,
                    ),
                storageRoot = documentStorageRoot,
            )
        when (outcome) {
            is DunningIssueOutcome.Issued -> {
                archiveDunningNoticePdf(
                    storageRoot = documentStorageRoot,
                    noticeId = outcome.noticeId,
                    pdfBytes = outcome.pdfBytes,
                    levelName = outcome.levelName,
                    uploadedBy = outcome.documentUploaderId,
                )
            }
            is DunningIssueOutcome.NotDunnable -> throw ConflictException(outcome.reason)
            DunningIssueOutcome.NoLevelsConfigured ->
                throw ConflictException("Keine aktive Mahnstufe konfiguriert -- zuerst eine Mahnstufe anlegen.")
            DunningIssueOutcome.NoFurtherLevel ->
                throw ConflictException("Alle konfigurierten Mahnstufen wurden im laufenden Zyklus bereits genutzt.")
            DunningIssueOutcome.NotDue ->
                throw ConflictException("Die naechste Mahnstufe ist noch nicht faellig.")
            DunningIssueOutcome.AlreadyIssued ->
                throw ConflictException("Diese Mahnstufe wurde soeben bereits ausgestellt.")
            DunningIssueOutcome.Superseded ->
                throw ConflictException(
                    "Der Mahnzyklus dieses Beitrags hat sich zwischenzeitlich geaendert (z. B. Stornierung/Reset) -- " +
                        "bitte erneut versuchen.",
                )
        }
        return getDunningCase(contributionId).firstOrNull() ?: throw NotFoundException("Beitrag nicht gefunden.")
    }

    override suspend fun skipDunningLevel(
        contributionId: String,
        reason: String,
    ): DunningCaseDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_TREASURY_ROLES)
        requireDunningUsable()
        val id = contributionId.toDunningUuid("Contribution")
        if (reason.isBlank()) throw ConflictException("Ein Grund fuer das Ueberspringen ist erforderlich.")

        transaction {
            val contributionRow =
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Beitrag nicht gefunden.")
            if (contributionRow[ContributionTable.status] !in ContributionStatusSets.DUNNABLE) {
                throw ConflictException("Beitrag hat den Status ${contributionRow[ContributionTable.status]} -- nicht mahnfaehig.")
            }
            val allNotices = DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq id }.toList()
            val currentCycle = allNotices.currentCycleNumber()
            val lastLevelNumber =
                allNotices
                    .filter {
                        it[DunningNoticeTable.cycleNumber] == currentCycle &&
                            it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                    }.maxOfOrNull { it[DunningNoticeTable.levelNumber] } ?: 0
            val nextLevel =
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.active eq true }
                    .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                    .firstOrNull { it[DunningLevelTable.levelNumber] > lastLevelNumber }
                    ?: throw ConflictException("Alle konfigurierten Mahnstufen wurden im laufenden Zyklus bereits genutzt.")

            // Slot-occupancy pre-check mirroring `issueDunningNotice`'s own `alreadyOccupied` guard
            // -- without it, a concurrent call (poller tick or another treasurer) racing this one
            // for the SAME (contribution, cycle, level) slot hit the raw `uq_dunning_notice_slot`
            // unique-constraint violation below with no pre-check at all, propagating an
            // `ExposedSQLException` (500) instead of a `ConflictException`. See the security review
            // finding this fixes.
            val slotOccupied =
                DunningNoticeTable
                    .selectAll()
                    .where {
                        (DunningNoticeTable.contributionId eq id) and
                            (DunningNoticeTable.cycleNumber eq currentCycle) and
                            (DunningNoticeTable.levelNumber eq nextLevel[DunningLevelTable.levelNumber])
                    }.limit(1)
                    .any()
            if (slotOccupied) throw ConflictException("Diese Mahnstufe wurde soeben bereits ausgestellt.")

            val now = DbClock.nowLocalDateTime()
            val respondBy = now.date
            val noticeId = Uuid.random()
            try {
                DunningNoticeTable.insert {
                    it[DunningNoticeTable.id] = noticeId
                    it[DunningNoticeTable.contributionId] = id
                    it[dunningLevelId] = nextLevel[DunningLevelTable.id]
                    it[cycleNumber] = currentCycle
                    it[levelNumber] = nextLevel[DunningLevelTable.levelNumber]
                    it[levelName] = nextLevel[DunningLevelTable.name]
                    it[feeAmount] = null
                    it[amountDue] = contributionRow[ContributionTable.amountDue]
                    it[status] = DunningNoticeStatus.SKIPPED
                    it[issuedAt] = now
                    it[DunningNoticeTable.respondBy] = respondBy
                    it[documentId] = null
                    it[postalDeliveryLogId] = null
                    it[createdBy] = current.memberId
                    it[cancelledAt] = null
                    it[cancellationReason] = reason.take(500)
                }
            } catch (e: ExposedSQLException) {
                // Concurrent-duplicate-slot race -- the `forUpdate()` lock above already makes this
                // practically unreachable for a race on the SAME contribution (same "pre-check is
                // racy, the DB-level UNIQUE is the real backstop" idiom `RegistrationService
                // .registerFriend`/`AccountingService.createLedgerAccount`/`PoliticianService
                // .grantPoliticianStatus`/`ElectionService.castElectionBallot` each already
                // establish), but a genuine safety net beats a raw 500 leaking to the caller.
                throw ConflictException("Diese Mahnstufe wurde soeben bereits ausgestellt.")
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.DUNNING_NOTICE,
                entityId = noticeId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        DunningNoticeSnapshot.serializer(),
                        DunningNoticeSnapshot(
                            contributionId = id.toString(),
                            cycleNumber = currentCycle,
                            levelNumber = nextLevel[DunningLevelTable.levelNumber],
                            levelName = nextLevel[DunningLevelTable.name],
                            status = DunningNoticeStatus.SKIPPED,
                            amountDue = contributionRow[ContributionTable.amountDue],
                            feeAmount = null,
                            respondBy = respondBy,
                            documentId = null,
                            issuedBySystem = false,
                        ),
                    ),
            )
        }
        return getDunningCase(contributionId).firstOrNull() ?: throw NotFoundException("Beitrag nicht gefunden.")
    }

    override suspend fun resetDunning(
        contributionId: String,
        reason: String,
    ): DunningCaseDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_TREASURY_ROLES)
        requireDunningUsable()
        // Security review MAJOR-1 -- unlike issueDunningNotice (which never sends post, see that
        // method's own rate limit above), resetDunning RE-ARMS the whole cycle: the very next
        // DunningPoller tick (>= MIN_POLL_INTERVAL_SECONDS later, see DunningConfig) treats this
        // contribution as freshly overdue and can issue -- and, if postal dispatch is enabled,
        // actually MAIL -- a fresh level-1 notice. Without a rate limit here, a compromised/malicious
        // TREASURER could force real, cost-incurring letters at will by repeatedly resetting. Reuses
        // the SAME per-member budget as issueDunningNotice -- a tighter shared budget across every
        // action that can re-arm postal dispatch, not a separate one. See the security review
        // finding this fixes.
        requireWithinRate(current.memberId.toString())
        val id = contributionId.toDunningUuid("Contribution")
        if (reason.isBlank()) throw ConflictException("Ein Grund fuer das Zuruecksetzen ist erforderlich.")

        transaction {
            val contributionRow =
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Beitrag nicht gefunden.")
            // Same DUNNABLE guard [skipDunningLevel] applies -- without it, resetting a PAID/WAIVED/
            // DEBIT_SCHEDULED/DEBIT_SUBMITTED contribution's dunning history unconditionally rewrote
            // `contribution.status` to OPEN/OVERDUE below, silently undoing a payment/waiver/SEPA
            // run and making the contribution DUNNABLE again -- see the security review finding this
            // fixes ("resetDunning liest den Status, prueft ihn aber nicht").
            if (contributionRow[ContributionTable.status] !in ContributionStatusSets.DUNNABLE) {
                throw ConflictException(
                    "Beitrag hat den Status ${contributionRow[ContributionTable.status]} -- nicht mahnfaehig, " +
                        "Mahnlauf kann nicht zurueckgesetzt werden.",
                )
            }
            val allNotices = DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq id }.toList()
            val currentCycle = allNotices.currentCycleNumber()
            val now = DbClock.nowLocalDateTime()

            val liveInCurrentCycle =
                allNotices.filter {
                    it[DunningNoticeTable.cycleNumber] == currentCycle &&
                        it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                }
            // Security review LOW finding -- AuditLogRecorder.record's own "deadlock-avoidance
            // contract" (see that object's KDoc) requires it to be the LAST lock-taking DB operation
            // of the transaction. Interleaving update-then-record PER notice (the old shape) took a
            // NEW dunning_notice row lock on iteration 2+ AFTER record() had already been called on
            // iteration 1 -- exactly the ordering the contract forbids. Every DunningNoticeTable.update
            // now runs FIRST, in its own loop; every AuditLogRecorder.record call runs SECOND, in a
            // separate loop -- so once the first record() call fires, no further row lock (other than
            // the already-held ContributionTable one, re-touched below) is taken. See the security
            // review finding this fixes.
            for (notice in liveInCurrentCycle) {
                val noticeId = notice[DunningNoticeTable.id]
                DunningNoticeTable.update({ DunningNoticeTable.id eq noticeId }) {
                    it[status] = DunningNoticeStatus.CANCELLED
                    it[cancelledAt] = now
                    it[cancellationReason] = reason.take(500)
                }
            }
            for (notice in liveInCurrentCycle) {
                val noticeId = notice[DunningNoticeTable.id]
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.DUNNING_NOTICE,
                    entityId = noticeId,
                    action = AuditAction.UPDATE,
                    before =
                        Json.encodeToString(
                            DunningNoticeSnapshot.serializer(),
                            notice.toSnapshot(),
                        ),
                    after =
                        Json.encodeToString(
                            DunningNoticeSnapshot.serializer(),
                            notice.toSnapshot().copy(status = DunningNoticeStatus.CANCELLED),
                        ),
                )
            }

            val today = DbClock.nowLocalDateTime().date
            val newStatus = if (contributionRow[ContributionTable.dueDate] >= today) ContributionStatus.OPEN else ContributionStatus.OVERDUE
            ContributionTable.update({ ContributionTable.id eq id }) { it[status] = newStatus }
        }
        return getDunningCase(contributionId).firstOrNull() ?: throw NotFoundException("Beitrag nicht gefunden.")
    }

    /**
     * Cancelling ANY ONE notice cancels its ENTIRE dunning cycle -- not just the targeted row.
     * Deliberately mirrors [resetDunning]'s own "whole cycle, never a partial one" discipline:
     * leaving an earlier-level notice `ISSUED` while a later-level one in the SAME cycle got
     * cancelled would permanently occupy that later level's `uq_dunning_notice_slot` idempotency
     * anchor with a `CANCELLED` row that `currentCycleNumber()` disregards but the raw unique index
     * still counts, and neither [issueDunningNotice] nor [skipDunningLevel] can ever re-issue that
     * exact (contribution, cycle, level) slot again -- the escalation ladder freezes permanently at
     * whatever level preceded the cancelled one, the ONLY way out being a full [resetDunning]. See
     * the security review finding this fixes for the concrete scenario.
     *
     * **Side effect the caller should expect**: cancelling the WHOLE cycle resets
     * `currentCycleNumber()`/`lastLevelNumber` back to "nothing live", so [DunningPoller]'s very
     * next tick (within [DunningConfig.pollIntervalSeconds], typically well under an hour) treats
     * this contribution as never dunned and issues a FRESH level-1 notice in a new cycle -- with a
     * real, potentially cost-incurring postal letter if postal dispatch is enabled. Before this fix
     * that only happened when cancelling the SOLE notice in a cycle, or via [resetDunning] (where
     * "start over" is the whole point); a treasurer cancelling ONE wrong notice out of several now
     * gets the same automatic-restart behaviour, which is easy to misread as "just remove that one
     * mistake" rather than "the whole dunning cycle for this contribution restarts". TODO(UI):
     * surface this explicitly in the confirmation dialog once one exists.
     *
     * **Locking**: locks the `contribution` row FIRST ([ContributionTable] `forUpdate()`), THEN the
     * targeted `dunning_notice` row -- same lock ORDER [resetDunning]/[skipDunningLevel] already
     * use, and for the same reason: without the contribution-row lock, this function's whole-cycle
     * cancellation ran concurrently and unserialized against [issueDunningNotice]'s own
     * contribution-row lock (`DunningIssuance.kt` Phase 2), so a poller tick issuing a new level
     * mid-flight (PDF generation happens OUTSIDE any transaction, a real window) could commit its
     * insert into the very cycle this call is in the middle of cancelling, leaving a cycle with
     * SOME notices `CANCELLED` and one freshly `ISSUED` -- exactly the partially-cancelled cycle
     * this function's own KDoc promises never happens.
     *
     * The lock alone only SERIALIZES the two transactions -- it does not, by itself, stop
     * [issueDunningNotice] Phase 2 from acting on the STALE cycle/level it computed back in its own
     * unlocked Phase 1. What actually closes the race is [issueDunningNotice] Phase 2 RE-DERIVING
     * the current cycle/level under the very lock this function also takes, and backing off with
     * [network.lapis.cloud.server.payment.dunning.DunningIssueOutcome.Superseded] when that
     * recomputed state no longer matches its Phase 1 snapshot -- e.g. because THIS function just
     * whole-cycle-cancelled it while Phase 2 was waiting on the lock. See the security review
     * finding this fixes (round 3 -- the earlier version of this KDoc claimed the lock alone was
     * sufficient, which understated what was actually needed).
     */
    override suspend fun cancelDunningNotice(
        noticeId: String,
        reason: String,
    ): DunningCaseDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_TREASURY_ROLES)
        requireDunningUsable()
        // Security review MAJOR-1 -- see [resetDunning]'s own rate-limit comment: cancelling a
        // notice whole-cycle-cancels it (this method's own KDoc), which re-arms the SAME
        // poller-driven re-issuance path resetDunning does. Same shared per-member budget.
        requireWithinRate(current.memberId.toString())
        val id = noticeId.toDunningUuid("DunningNotice")
        if (reason.isBlank()) throw ConflictException("Ein Grund fuer die Stornierung ist erforderlich.")

        val contributionId =
            transaction {
                // Look up which contribution this notice belongs to WITHOUT locking it (the FK is
                // immutable for a given notice id, so there's nothing to race here), then lock THAT
                // contribution row before touching any dunning_notice row -- see this function's own
                // KDoc "Locking" section for why this ordering matters.
                val contributionIdForNotice =
                    DunningNoticeTable
                        .selectAll()
                        .where { DunningNoticeTable.id eq id }
                        .singleOrNull()
                        ?.get(DunningNoticeTable.contributionId)
                        ?: throw NotFoundException("Mahnung nicht gefunden.")
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id eq contributionIdForNotice }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Beitrag nicht gefunden.")

                val notice =
                    DunningNoticeTable
                        .selectAll()
                        .where { DunningNoticeTable.id eq id }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("Mahnung nicht gefunden.")
                if (notice[DunningNoticeTable.status] == DunningNoticeStatus.CANCELLED) {
                    throw ConflictException("Mahnung ist bereits storniert.")
                }
                val cId = notice[DunningNoticeTable.contributionId]
                val cycle = notice[DunningNoticeTable.cycleNumber]
                val now = DbClock.nowLocalDateTime()

                val liveInCycle =
                    DunningNoticeTable
                        .selectAll()
                        .where {
                            (DunningNoticeTable.contributionId eq cId) and
                                (DunningNoticeTable.cycleNumber eq cycle) and
                                (DunningNoticeTable.status neq DunningNoticeStatus.CANCELLED)
                        }.toList()
                // See resetDunning's own comment on this exact same restructuring -- all row updates
                // FIRST, all AuditLogRecorder.record calls SECOND, to satisfy record()'s own
                // "must be the LAST lock-taking operation" deadlock-avoidance contract. See the
                // security review finding this fixes.
                for (liveNotice in liveInCycle) {
                    val liveNoticeId = liveNotice[DunningNoticeTable.id]
                    DunningNoticeTable.update({ DunningNoticeTable.id eq liveNoticeId }) {
                        it[status] = DunningNoticeStatus.CANCELLED
                        it[cancelledAt] = now
                        it[cancellationReason] = reason.take(500)
                    }
                }
                for (liveNotice in liveInCycle) {
                    val liveNoticeId = liveNotice[DunningNoticeTable.id]
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.DUNNING_NOTICE,
                        entityId = liveNoticeId,
                        action = AuditAction.UPDATE,
                        before = Json.encodeToString(DunningNoticeSnapshot.serializer(), liveNotice.toSnapshot()),
                        after =
                            Json.encodeToString(
                                DunningNoticeSnapshot.serializer(),
                                liveNotice.toSnapshot().copy(status = DunningNoticeStatus.CANCELLED),
                            ),
                    )
                }

                // The whole cycle is now CANCELLED, so the contribution always falls back to
                // OVERDUE (unlike the old partial-cancel path, there is no "a higher notice still
                // stands" case to preserve IN_DUNNING for anymore).
                ContributionTable.update({
                    (ContributionTable.id eq cId) and (ContributionTable.status eq ContributionStatus.IN_DUNNING)
                }) {
                    it[status] = ContributionStatus.OVERDUE
                }
                cId
            }
        return getDunningCase(contributionId.toString()).firstOrNull() ?: throw NotFoundException("Beitrag nicht gefunden.")
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    // requireDunningUsable() is now `network.lapis.cloud.server.payment.dunning.requireDunningUsable`
    // -- a shared, transaction-independent top-level function used by every WRITE method here AND
    // by `registerDunningRoutes`'s `preview.pdf` route (which used to bypass this gate entirely).
    // See that function's own KDoc for why this was lifted out of a `private` method here. Imported
    // above.

    private fun requireWithinRate(key: String) {
        if (!issueRateLimiter.checkAndRecord("member:$key")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    private fun validateLevelInput(input: DunningLevelInput): String {
        // Security review LOW finding -- levelNumber itself was never validated (only graceDays/
        // responseDays/feeAmount/name were). An active level with levelNumber <= 0 counts towards
        // activeLevelCount/hasActiveLevel (requireDunningUsable passes -- "Mahnwesen aktiv, 1 aktive
        // Stufe") but can NEVER be selected as `nextLevel`, since level selection always requires
        // `levelNumber > lastLevelNumber` with a `lastLevelNumber` default of 0 (see
        // DunningIssuance.kt/DunningPoller.kt "nextLevel" derivation) -- a silent misconfiguration
        // dead end instead of a rejection at input time. See the security review finding this fixes.
        if (input.levelNumber !in 1..MAX_LEVEL_NUMBER) {
            throw ConflictException("level_number muss zwischen 1 und $MAX_LEVEL_NUMBER liegen.")
        }
        if (input.graceDays !in 1..365) throw ConflictException("grace_days muss zwischen 1 und 365 liegen.")
        if (input.responseDays !in 1..365) throw ConflictException("response_days muss zwischen 1 und 365 liegen.")
        input.feeAmount?.let { fee ->
            if (fee < BigDecimal.ZERO || fee > MAX_FEE_AMOUNT) {
                throw ConflictException("fee_amount muss zwischen 0 und $MAX_FEE_AMOUNT liegen.")
            }
        }
        if (input.feeAmount != null && input.levelNumber == 1) {
            throw ConflictException(
                "Eine Mahngebuehr auf der ersten Mahnstufe ist unzulaessig -- eine erste Zahlungserinnerung begruendet " +
                    "den Verzug (§ 286 BGB) in aller Regel erst.",
            )
        }
        val name = input.name.trim().take(MAX_LEVEL_NAME_LENGTH)
        if (name.isBlank()) throw ConflictException("name darf nicht leer sein.")
        return name
    }

    private fun loadDunningLevelDto(id: Uuid): DunningLevelDto =
        DunningLevelTable
            .selectAll()
            .where { DunningLevelTable.id eq id }
            .single()
            .toDunningLevelDto()

    private fun loadDunningSettingsDto(): DunningSettingsDto {
        val settingsRow = OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
        val activeLevelCount =
            DunningLevelTable
                .selectAll()
                .where { DunningLevelTable.active eq true }
                .count()
                .toInt()
        val lastAck =
            DunningComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(DunningComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return DunningSettingsDto(
            dunningEnabled = settingsRow[OrganizationSettingsTable.dunningEnabled],
            pollerEnabled = dunningConfig.pollerEnabled,
            postalDispatchEnabled = dunningConfig.postalDispatchEnabled,
            postalMailEnabled = settingsRow[OrganizationSettingsTable.postalMailEnabled],
            activeLevelCount = activeLevelCount,
            lastDisclaimerVersion = lastAck?.get(DunningComplianceAcknowledgmentTable.disclaimerVersion),
            lastAcknowledgedAt = lastAck?.get(DunningComplianceAcknowledgmentTable.acknowledgedAt),
        )
    }

    private fun String.toDunningUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}

/**
 * Explicit join, not `ContributionTable innerJoin MemberTable innerJoin MembershipTierTable`: both
 * `ContributionTable.membershipTierId` and `MemberTable.membershipTierId` reference
 * `MembershipTierTable.id`, so Exposed's implicit FK-based join resolution can't tell which path to
 * use. Mirrors `ContributionService.contributionJoin` exactly.
 */
private fun contributionJoin() =
    ContributionTable
        .innerJoin(MemberTable)
        .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)

private fun ResultRow.toDunningLevelDto(): DunningLevelDto =
    DunningLevelDto(
        id = this[DunningLevelTable.id].toString(),
        levelNumber = this[DunningLevelTable.levelNumber],
        name = this[DunningLevelTable.name],
        graceDays = this[DunningLevelTable.graceDays],
        responseDays = this[DunningLevelTable.responseDays],
        feeAmount = this[DunningLevelTable.feeAmount],
        active = this[DunningLevelTable.active],
    )

/** See [DunningLevelSnapshot] KDoc -- the audit-trail counterpart of [toDunningLevelDto], without the id (already carried by the audit entry's own `entityId`... except here `entityId = ORGANIZATION_SETTINGS_ID`, so `levelNumber` is what actually identifies WHICH level a reader is looking at). */
private fun ResultRow.toDunningLevelSnapshot(): DunningLevelSnapshot =
    DunningLevelSnapshot(
        levelNumber = this[DunningLevelTable.levelNumber],
        name = this[DunningLevelTable.name],
        graceDays = this[DunningLevelTable.graceDays],
        responseDays = this[DunningLevelTable.responseDays],
        feeAmount = this[DunningLevelTable.feeAmount],
        active = this[DunningLevelTable.active],
    )

private fun ResultRow.toDunningNoticeDto(): DunningNoticeDto {
    val logId = this[DunningNoticeTable.postalDeliveryLogId]
    val postalStatus =
        logId?.let {
            PostalDeliveryLogTable
                .selectAll()
                .where { PostalDeliveryLogTable.id eq logId }
                .singleOrNull()
                ?.get(PostalDeliveryLogTable.status)
        }
    return DunningNoticeDto(
        id = this[DunningNoticeTable.id].toString(),
        contributionId = this[DunningNoticeTable.contributionId].toString(),
        cycleNumber = this[DunningNoticeTable.cycleNumber],
        levelNumber = this[DunningNoticeTable.levelNumber],
        levelName = this[DunningNoticeTable.levelName],
        feeAmount = this[DunningNoticeTable.feeAmount],
        amountDue = this[DunningNoticeTable.amountDue],
        status = this[DunningNoticeTable.status],
        issuedAt = this[DunningNoticeTable.issuedAt],
        respondBy = this[DunningNoticeTable.respondBy],
        documentId = this[DunningNoticeTable.documentId]?.toString(),
        postalDeliveryStatus = postalStatus,
        createdByMemberId = this[DunningNoticeTable.createdBy]?.toString(),
        cancellationReason = this[DunningNoticeTable.cancellationReason],
    )
}

private fun ResultRow.toSnapshot(): DunningNoticeSnapshot =
    DunningNoticeSnapshot(
        contributionId = this[DunningNoticeTable.contributionId].toString(),
        cycleNumber = this[DunningNoticeTable.cycleNumber],
        levelNumber = this[DunningNoticeTable.levelNumber],
        levelName = this[DunningNoticeTable.levelName],
        status = this[DunningNoticeTable.status],
        amountDue = this[DunningNoticeTable.amountDue],
        feeAmount = this[DunningNoticeTable.feeAmount],
        respondBy = this[DunningNoticeTable.respondBy],
        documentId = this[DunningNoticeTable.documentId]?.toString(),
        issuedBySystem = this[DunningNoticeTable.createdBy] == null,
    )

private fun ResultRow.toDunningCaseDto(
    notices: List<ResultRow>,
    activeLevels: List<ResultRow>,
): DunningCaseDto {
    val currentCycle = notices.currentCycleNumber()
    val liveInCycle =
        notices.filter {
            it[DunningNoticeTable.cycleNumber] == currentCycle && it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
        }
    val highest = liveInCycle.maxByOrNull { it[DunningNoticeTable.levelNumber] }
    val totalFees =
        notices
            .filter { it[DunningNoticeTable.status] == DunningNoticeStatus.ISSUED }
            .mapNotNull { it[DunningNoticeTable.feeAmount] }
            .fold(BigDecimal.ZERO) { acc, fee -> acc.add(fee) }

    val lastLevelNumber = highest?.get(DunningNoticeTable.levelNumber) ?: 0
    val nextLevel = activeLevels.firstOrNull { it[DunningLevelTable.levelNumber] > lastLevelNumber }
    // Security review LOW finding (Round 5): must mirror the SAME reference-date rule the engine
    // (DunningIssuance.issueDunningNotice) and the poller's candidate pre-filter (DunningPoller)
    // already use -- `dunningReferenceDate`, not a naive `?: dueDate` fallback. After a whole-cycle
    // cancel/reset, the escalation window is anchored on `cancelled_at` (see that function's own
    // KDoc), not on the original, already-in-the-past `dueDate`. Using the naive fallback here made
    // this DTO's `nextLevelDueOn` -- what a TREASURER actually sees in the UI -- diverge from the
    // engine's real behaviour for the full `graceDays` cooldown window after every reset/cancel,
    // showing "faellig" while the engine correctly still withholds the notice. See the security
    // review finding this fixes.
    val referenceDate =
        dunningReferenceDate(
            allNoticesForContribution = notices,
            lastLiveNotice = highest,
            dueDate = this[ContributionTable.dueDate],
        )
    val nextLevelDueOn = nextLevel?.let { referenceDate.plus(it[DunningLevelTable.graceDays], DateTimeUnit.DAY) }

    return DunningCaseDto(
        contributionId = this[ContributionTable.id].toString(),
        memberId = this[ContributionTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        periodStart = this[ContributionTable.periodStart],
        periodEnd = this[ContributionTable.periodEnd],
        amountDue = this[ContributionTable.amountDue],
        dueDate = this[ContributionTable.dueDate],
        contributionStatus = this[ContributionTable.status],
        paymentMethod = this[ContributionTable.paymentMethod],
        currentCycleNumber = currentCycle,
        highestLevelNumber = highest?.get(DunningNoticeTable.levelNumber),
        lastNoticeIssuedAt = highest?.get(DunningNoticeTable.issuedAt),
        nextLevelNumber = nextLevel?.get(DunningLevelTable.levelNumber),
        nextLevelDueOn = nextLevelDueOn,
        totalFeesCharged = totalFees,
    )
}
