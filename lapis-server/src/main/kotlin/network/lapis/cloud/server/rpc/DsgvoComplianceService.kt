package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.DataBreachIncidentTable
import network.lapis.cloud.server.db.generated.DataProtectionImpactAssessmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ProcessingAgreementTable
import network.lapis.cloud.server.db.generated.TechnicalOrganizationalMeasureTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentDto
import network.lapis.cloud.shared.domain.DataBreachIncidentInput
import network.lapis.cloud.shared.domain.DpiaAssessmentDto
import network.lapis.cloud.shared.domain.DpiaAssessmentInput
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.ProcessingAgreementDto
import network.lapis.cloud.shared.domain.ProcessingAgreementInput
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureDto
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureInput
import network.lapis.cloud.shared.domain.TomCategory
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** See [IDsgvoComplianceService] KDoc for the tier rationale (deliberately no MEMBER/TREASURER). */
private val COMPLIANCE_READ_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val AVV_TOM_WRITE_ROLES = arrayOf(AccountRole.ADMIN)
private val DSFA_BREACH_WRITE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Server-side cap on every `list*` method here -- DoS guard, same idiom as
 * [AuditLogService]'s `MAX_PAGE_SIZE`. These four tables are small, hand-maintained registers (not
 * an ever-growing transactional log), so a single fixed cap (no pagination parameter at all) is
 * proportionate -- if a real deployment ever approaches this cap, that is itself a signal the
 * register needs pagination, not just a larger constant.
 */
private const val MAX_LIST_SIZE = 500

/**
 * DSGVO-Vollausbau (V0.5.5) -- AVV-Register/TOMs/DSFA-Vorlage/Datenpannenmeldung. Implements
 * [IDsgvoComplianceService] -- see that interface's KDoc and
 * `lapis-server/src/main/kuml/16-dsgvo-compliance.kuml.kts`'s file header for the full bounded-scope
 * rationale and legal-verification disclaimer.
 *
 * **Documentation-/workflow-tool support for a human-made legal decision, never automated legal
 * advice.** [ProcessingAgreementInput.avvStatus], [DpiaAssessmentInput.dpiaRequired],
 * [DataBreachIncidentInput.authorityNotificationRequired], and every [network.lapis.cloud.shared
 * .domain.RiskLevel] input are stored EXACTLY as submitted -- this class never derives, overrides,
 * or defaults any of them from another field. The only computed outputs are read-time DISPLAY
 * helpers ([BreachDeadlineCalculator], [DpiaRiskMatrix]) folded into the outgoing Dto, never written
 * back to a column.
 *
 * **"Versioned" TOM/DSFA rows**: update-in-place with a monotonically increasing `version` Int
 * (incremented on every `update*` call) -- NOT a full superseded-row point-in-time history. See
 * `16-dsgvo-compliance.kuml.kts` file header for why this bound was chosen; a real superseded-row
 * history is a plausible future extension, deliberately deferred.
 *
 * **AVV-register <-> [PostalMailService] coupling**: see that class's `requirePostalMailEnabled`
 * KDoc -- this service exposes [hasActiveProcessingAgreement] as a small, read-only, best-effort
 * helper for that non-blocking advisory check; it is not part of [IDsgvoComplianceService] itself
 * (no client needs to call it directly today).
 */
class DsgvoComplianceService(
    private val call: ApplicationCall,
) : IDsgvoComplianceService {
    // ── Baustein 1: AVV-Register ─────────────────────────────────────────────────

    override suspend fun listProcessingAgreements(): List<ProcessingAgreementDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*COMPLIANCE_READ_ROLES)
        return transaction {
            ProcessingAgreementTable
                .selectAll()
                .orderBy(ProcessingAgreementTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_SIZE)
                .map { it.toProcessingAgreementDto() }
        }
    }

    override suspend fun createProcessingAgreement(input: ProcessingAgreementInput): ProcessingAgreementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AVV_TOM_WRITE_ROLES)
        val id = Uuid.random()
        val now = nowLocalDateTime()
        return transaction {
            ProcessingAgreementTable.insert {
                it[ProcessingAgreementTable.id] = id
                it[processorName] = input.processorName
                it[processingPurpose] = input.processingPurpose
                it[dataCategories] = input.dataCategories
                it[avvStatus] = input.avvStatus
                it[signedDate] = input.signedDate
                it[reviewDueDate] = input.reviewDueDate
                it[documentId] = input.documentId?.toComplianceUuid("Document")
                it[notes] = input.notes
                it[createdAt] = now
                it[createdBy] = current.memberId
                it[updatedAt] = null
                it[updatedBy] = null
            }
            requireProcessingAgreement(id).toProcessingAgreementDto()
        }
    }

    override suspend fun updateProcessingAgreement(
        id: String,
        input: ProcessingAgreementInput,
    ): ProcessingAgreementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AVV_TOM_WRITE_ROLES)
        val agreementId = id.toComplianceUuid("ProcessingAgreement")
        val now = nowLocalDateTime()
        return transaction {
            requireProcessingAgreement(agreementId)
            ProcessingAgreementTable.update({ ProcessingAgreementTable.id eq agreementId }) {
                it[processorName] = input.processorName
                it[processingPurpose] = input.processingPurpose
                it[dataCategories] = input.dataCategories
                it[avvStatus] = input.avvStatus
                it[signedDate] = input.signedDate
                it[reviewDueDate] = input.reviewDueDate
                it[documentId] = input.documentId?.toComplianceUuid("Document")
                it[notes] = input.notes
                it[updatedAt] = now
                it[updatedBy] = current.memberId
            }
            requireProcessingAgreement(agreementId).toProcessingAgreementDto()
        }
    }

    /**
     * Best-effort, read-only advisory helper for [PostalMailService]'s non-blocking AVV-presence
     * warning -- see that class's `requirePostalMailEnabled` KDoc. `true` iff at least one
     * [AvvStatus.SIGNED] row for [processorName] exists whose `reviewDueDate` is either unset or
     * not yet in the past. Deliberately not part of [IDsgvoComplianceService] (no authorization
     * check here -- callers are internal server code, not RPC clients).
     */
    fun hasActiveProcessingAgreement(processorName: String): Boolean =
        transaction {
            val today = nowLocalDateTime().date
            ProcessingAgreementTable
                .selectAll()
                .where {
                    (ProcessingAgreementTable.processorName eq processorName) and
                        (ProcessingAgreementTable.avvStatus eq AvvStatus.SIGNED)
                }.any { row ->
                    val reviewDueDate = row[ProcessingAgreementTable.reviewDueDate]
                    reviewDueDate == null || reviewDueDate >= today
                }
        }

    // ── Baustein 2: TOMs ──────────────────────────────────────────────────────────

    override suspend fun listTechnicalOrganizationalMeasures(category: TomCategory?): List<TechnicalOrganizationalMeasureDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*COMPLIANCE_READ_ROLES)
        return transaction {
            val base = TechnicalOrganizationalMeasureTable.selectAll()
            val filtered = if (category != null) base.where { TechnicalOrganizationalMeasureTable.category eq category } else base
            filtered
                .orderBy(TechnicalOrganizationalMeasureTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_SIZE)
                .map { it.toTechnicalOrganizationalMeasureDto() }
        }
    }

    override suspend fun createTechnicalOrganizationalMeasure(
        input: TechnicalOrganizationalMeasureInput,
    ): TechnicalOrganizationalMeasureDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AVV_TOM_WRITE_ROLES)
        val id = Uuid.random()
        val now = nowLocalDateTime()
        return transaction {
            TechnicalOrganizationalMeasureTable.insert {
                it[TechnicalOrganizationalMeasureTable.id] = id
                it[category] = input.category
                it[title] = input.title
                it[description] = input.description
                it[version] = 1
                it[createdAt] = now
                it[createdBy] = current.memberId
                it[updatedAt] = null
                it[updatedBy] = null
            }
            requireTom(id).toTechnicalOrganizationalMeasureDto()
        }
    }

    override suspend fun updateTechnicalOrganizationalMeasure(
        id: String,
        input: TechnicalOrganizationalMeasureInput,
    ): TechnicalOrganizationalMeasureDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AVV_TOM_WRITE_ROLES)
        val tomId = id.toComplianceUuid("TechnicalOrganizationalMeasure")
        val now = nowLocalDateTime()
        return transaction {
            val existing = requireTom(tomId)
            TechnicalOrganizationalMeasureTable.update({ TechnicalOrganizationalMeasureTable.id eq tomId }) {
                it[category] = input.category
                it[title] = input.title
                it[description] = input.description
                it[version] = existing[TechnicalOrganizationalMeasureTable.version] + 1
                it[updatedAt] = now
                it[updatedBy] = current.memberId
            }
            requireTom(tomId).toTechnicalOrganizationalMeasureDto()
        }
    }

    // ── Baustein 3: DSFA ──────────────────────────────────────────────────────────

    override suspend fun listDpiaAssessments(status: DsfaStatus?): List<DpiaAssessmentDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*COMPLIANCE_READ_ROLES)
        return transaction {
            val base = DataProtectionImpactAssessmentTable.selectAll()
            val filtered = if (status != null) base.where { DataProtectionImpactAssessmentTable.status eq status } else base
            filtered
                .orderBy(DataProtectionImpactAssessmentTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_SIZE)
                .map { it.toDpiaAssessmentDto() }
        }
    }

    override suspend fun createDpiaAssessment(input: DpiaAssessmentInput): DpiaAssessmentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DSFA_BREACH_WRITE_ROLES)
        val id = Uuid.random()
        val now = nowLocalDateTime()
        return transaction {
            DataProtectionImpactAssessmentTable.insert {
                it[DataProtectionImpactAssessmentTable.id] = id
                it[title] = input.title
                it[processingDescription] = input.processingDescription
                it[necessityProportionality] = input.necessityProportionality
                it[riskLikelihood] = input.riskLikelihood
                it[riskSeverity] = input.riskSeverity
                it[riskAssessment] = input.riskAssessment
                it[mitigationMeasures] = input.mitigationMeasures
                it[dpiaRequired] = input.dpiaRequired
                it[outcomeRationale] = input.outcomeRationale
                it[status] = input.status
                it[version] = 1
                it[createdAt] = now
                it[createdBy] = current.memberId
                it[updatedAt] = null
                it[updatedBy] = null
            }
            requireDpia(id).toDpiaAssessmentDto()
        }
    }

    override suspend fun updateDpiaAssessment(
        id: String,
        input: DpiaAssessmentInput,
    ): DpiaAssessmentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DSFA_BREACH_WRITE_ROLES)
        val dpiaId = id.toComplianceUuid("DataProtectionImpactAssessment")
        val now = nowLocalDateTime()
        return transaction {
            val existing = requireDpia(dpiaId)
            DataProtectionImpactAssessmentTable.update({ DataProtectionImpactAssessmentTable.id eq dpiaId }) {
                it[title] = input.title
                it[processingDescription] = input.processingDescription
                it[necessityProportionality] = input.necessityProportionality
                it[riskLikelihood] = input.riskLikelihood
                it[riskSeverity] = input.riskSeverity
                it[riskAssessment] = input.riskAssessment
                it[mitigationMeasures] = input.mitigationMeasures
                it[dpiaRequired] = input.dpiaRequired
                it[outcomeRationale] = input.outcomeRationale
                it[status] = input.status
                it[version] = existing[DataProtectionImpactAssessmentTable.version] + 1
                it[updatedAt] = now
                it[updatedBy] = current.memberId
            }
            requireDpia(dpiaId).toDpiaAssessmentDto()
        }
    }

    // ── Baustein 4: Datenpannenmeldung ────────────────────────────────────────────

    override suspend fun listDataBreachIncidents(status: BreachStatus?): List<DataBreachIncidentDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*COMPLIANCE_READ_ROLES)
        val now = nowLocalDateTime()
        return transaction {
            val base = DataBreachIncidentTable.selectAll()
            val filtered = if (status != null) base.where { DataBreachIncidentTable.status eq status } else base
            filtered
                .orderBy(DataBreachIncidentTable.reportedAt, SortOrder.DESC)
                .limit(MAX_LIST_SIZE)
                .map { it.toDataBreachIncidentDto(now) }
        }
    }

    override suspend fun createDataBreachIncident(input: DataBreachIncidentInput): DataBreachIncidentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DSFA_BREACH_WRITE_ROLES)
        val id = Uuid.random()
        val now = nowLocalDateTime()
        return transaction {
            DataBreachIncidentTable.insert {
                it[DataBreachIncidentTable.id] = id
                it[discoveredAt] = input.discoveredAt
                it[description] = input.description
                it[affectedDataCategories] = input.affectedDataCategories
                it[estimatedAffectedPersons] = input.estimatedAffectedPersons
                it[riskAssessment] = input.riskAssessment
                it[riskLevel] = input.riskLevel
                it[authorityNotificationRequired] = input.authorityNotificationRequired
                it[authorityNotifiedAt] = input.authorityNotifiedAt
                it[dataSubjectsNotifiedAt] = input.dataSubjectsNotifiedAt
                it[status] = input.status
                it[reportedAt] = now
                it[reportedBy] = current.memberId
                it[updatedAt] = null
                it[updatedBy] = null
            }
            requireBreach(id).toDataBreachIncidentDto(now)
        }
    }

    override suspend fun updateDataBreachIncident(
        id: String,
        input: DataBreachIncidentInput,
    ): DataBreachIncidentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*DSFA_BREACH_WRITE_ROLES)
        val breachId = id.toComplianceUuid("DataBreachIncident")
        val now = nowLocalDateTime()
        return transaction {
            requireBreach(breachId)
            DataBreachIncidentTable.update({ DataBreachIncidentTable.id eq breachId }) {
                it[discoveredAt] = input.discoveredAt
                it[description] = input.description
                it[affectedDataCategories] = input.affectedDataCategories
                it[estimatedAffectedPersons] = input.estimatedAffectedPersons
                it[riskAssessment] = input.riskAssessment
                it[riskLevel] = input.riskLevel
                it[authorityNotificationRequired] = input.authorityNotificationRequired
                it[authorityNotifiedAt] = input.authorityNotifiedAt
                it[dataSubjectsNotifiedAt] = input.dataSubjectsNotifiedAt
                it[status] = input.status
                it[updatedAt] = now
                it[updatedBy] = current.memberId
            }
            requireBreach(breachId).toDataBreachIncidentDto(now)
        }
    }

    // ── Shared lookup helpers ─────────────────────────────────────────────────────

    private fun requireProcessingAgreement(id: Uuid): ResultRow =
        ProcessingAgreementTable
            .selectAll()
            .where { ProcessingAgreementTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("ProcessingAgreement $id not found")

    private fun requireTom(id: Uuid): ResultRow =
        TechnicalOrganizationalMeasureTable
            .selectAll()
            .where { TechnicalOrganizationalMeasureTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("TechnicalOrganizationalMeasure $id not found")

    private fun requireDpia(id: Uuid): ResultRow =
        DataProtectionImpactAssessmentTable
            .selectAll()
            .where { DataProtectionImpactAssessmentTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("DataProtectionImpactAssessment $id not found")

    private fun requireBreach(id: Uuid): ResultRow =
        DataBreachIncidentTable
            .selectAll()
            .where { DataBreachIncidentTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("DataBreachIncident $id not found")

    private fun ResultRow.toProcessingAgreementDto(): ProcessingAgreementDto {
        val avvStatus = this[ProcessingAgreementTable.avvStatus]
        val reviewDueDate = this[ProcessingAgreementTable.reviewDueDate]
        val today = nowLocalDateTime().date
        val createdBy = this[ProcessingAgreementTable.createdBy]
        val updatedBy = this[ProcessingAgreementTable.updatedBy]
        return ProcessingAgreementDto(
            id = this[ProcessingAgreementTable.id].toString(),
            processorName = this[ProcessingAgreementTable.processorName],
            processingPurpose = this[ProcessingAgreementTable.processingPurpose],
            dataCategories = this[ProcessingAgreementTable.dataCategories],
            avvStatus = avvStatus,
            signedDate = this[ProcessingAgreementTable.signedDate],
            reviewDueDate = reviewDueDate,
            documentId = this[ProcessingAgreementTable.documentId]?.toString(),
            notes = this[ProcessingAgreementTable.notes],
            createdAt = this[ProcessingAgreementTable.createdAt],
            createdBy = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            updatedAt = this[ProcessingAgreementTable.updatedAt],
            updatedBy = updatedBy?.toString(),
            active = avvStatus == AvvStatus.SIGNED && (reviewDueDate == null || reviewDueDate >= today),
        )
    }

    private fun ResultRow.toTechnicalOrganizationalMeasureDto(): TechnicalOrganizationalMeasureDto {
        val createdBy = this[TechnicalOrganizationalMeasureTable.createdBy]
        val updatedBy = this[TechnicalOrganizationalMeasureTable.updatedBy]
        return TechnicalOrganizationalMeasureDto(
            id = this[TechnicalOrganizationalMeasureTable.id].toString(),
            category = this[TechnicalOrganizationalMeasureTable.category],
            title = this[TechnicalOrganizationalMeasureTable.title],
            description = this[TechnicalOrganizationalMeasureTable.description],
            version = this[TechnicalOrganizationalMeasureTable.version],
            createdAt = this[TechnicalOrganizationalMeasureTable.createdAt],
            createdBy = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            updatedAt = this[TechnicalOrganizationalMeasureTable.updatedAt],
            updatedBy = updatedBy?.toString(),
        )
    }

    private fun ResultRow.toDpiaAssessmentDto(): DpiaAssessmentDto {
        val createdBy = this[DataProtectionImpactAssessmentTable.createdBy]
        val updatedBy = this[DataProtectionImpactAssessmentTable.updatedBy]
        val likelihood = this[DataProtectionImpactAssessmentTable.riskLikelihood]
        val severity = this[DataProtectionImpactAssessmentTable.riskSeverity]
        return DpiaAssessmentDto(
            id = this[DataProtectionImpactAssessmentTable.id].toString(),
            title = this[DataProtectionImpactAssessmentTable.title],
            processingDescription = this[DataProtectionImpactAssessmentTable.processingDescription],
            necessityProportionality = this[DataProtectionImpactAssessmentTable.necessityProportionality],
            riskLikelihood = likelihood,
            riskSeverity = severity,
            riskBand = DpiaRiskMatrix.band(likelihood, severity),
            riskAssessment = this[DataProtectionImpactAssessmentTable.riskAssessment],
            mitigationMeasures = this[DataProtectionImpactAssessmentTable.mitigationMeasures],
            dpiaRequired = this[DataProtectionImpactAssessmentTable.dpiaRequired],
            outcomeRationale = this[DataProtectionImpactAssessmentTable.outcomeRationale],
            status = this[DataProtectionImpactAssessmentTable.status],
            version = this[DataProtectionImpactAssessmentTable.version],
            createdAt = this[DataProtectionImpactAssessmentTable.createdAt],
            createdBy = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            updatedAt = this[DataProtectionImpactAssessmentTable.updatedAt],
            updatedBy = updatedBy?.toString(),
        )
    }

    private fun ResultRow.toDataBreachIncidentDto(now: LocalDateTime): DataBreachIncidentDto {
        val discoveredAt = this[DataBreachIncidentTable.discoveredAt]
        val authorityNotifiedAt = this[DataBreachIncidentTable.authorityNotifiedAt]
        val reportedBy = this[DataBreachIncidentTable.reportedBy]
        val updatedBy = this[DataBreachIncidentTable.updatedBy]
        return DataBreachIncidentDto(
            id = this[DataBreachIncidentTable.id].toString(),
            discoveredAt = discoveredAt,
            description = this[DataBreachIncidentTable.description],
            affectedDataCategories = this[DataBreachIncidentTable.affectedDataCategories],
            estimatedAffectedPersons = this[DataBreachIncidentTable.estimatedAffectedPersons],
            riskAssessment = this[DataBreachIncidentTable.riskAssessment],
            riskLevel = this[DataBreachIncidentTable.riskLevel],
            authorityNotificationRequired = this[DataBreachIncidentTable.authorityNotificationRequired],
            authorityNotifiedAt = authorityNotifiedAt,
            dataSubjectsNotifiedAt = this[DataBreachIncidentTable.dataSubjectsNotifiedAt],
            status = this[DataBreachIncidentTable.status],
            reportedAt = this[DataBreachIncidentTable.reportedAt],
            reportedBy = reportedBy.toString(),
            reportedByDisplayName = memberDisplayName(reportedBy),
            updatedAt = this[DataBreachIncidentTable.updatedAt],
            updatedBy = updatedBy?.toString(),
            authorityNotificationDeadline = BreachDeadlineCalculator.deadline(discoveredAt),
            deadlineStatus = BreachDeadlineCalculator.status(discoveredAt, authorityNotifiedAt, now),
        )
    }

    private fun memberDisplayName(memberId: Uuid): String? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName)

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toComplianceUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
