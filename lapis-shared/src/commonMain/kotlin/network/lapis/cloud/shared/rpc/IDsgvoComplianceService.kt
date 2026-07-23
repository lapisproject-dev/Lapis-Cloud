package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
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

/**
 * DSGVO-Vollausbau (V0.5.5) -- AVV-Register/TOMs/DSFA-Vorlage/Datenpannenmeldung. See
 * `network.lapis.cloud.server.rpc.DsgvoComplianceService` KDoc for the write-path design and
 * `lapis-server/src/main/kuml/16-dsgvo-compliance.kuml.kts` for the bounded-scope rationale and
 * legal-verification disclaimer.
 *
 * **Documentation-/workflow-tool support for a human-made legal decision, never automated legal
 * advice** -- every method here reads or records data a person entered; nothing computes a legal
 * conclusion (see file header, and each Dto's own KDoc for the specific human-input fields this
 * applies to).
 *
 * Authorization tiers (deliberately WITHOUT MEMBER/TREASURER on any method -- these are
 * organizational DSGVO-compliance records, not treasury records, unlike `IAuditLogService`'s own
 * TREASURER-inclusive read tier):
 *  - AVV-register / TOM read: BOARD/ADMIN. Write: ADMIN only.
 *  - DSFA / data-breach-incident read+write: BOARD/ADMIN (data-breach write is intentionally the
 *    same tier as DSFA, not narrowed to ADMIN-only, so a BOARD member can also record/update a
 *    freshly discovered incident without waiting for an ADMIN -- see
 *    `network.lapis.cloud.server.rpc.DsgvoComplianceService` KDoc for why this was still judged
 *    "hochsensibel enough to exclude MEMBER/TREASURER" without going all the way to ADMIN-only).
 */
@RpcService
interface IDsgvoComplianceService {
    /** Role: BOARD/ADMIN. Newest-first by `createdAt`. */
    suspend fun listProcessingAgreements(): List<ProcessingAgreementDto>

    /** Role: ADMIN. */
    suspend fun createProcessingAgreement(input: ProcessingAgreementInput): ProcessingAgreementDto

    /** Role: ADMIN. Throws [NotFoundException] if [id] does not resolve to a row. */
    suspend fun updateProcessingAgreement(
        id: String,
        input: ProcessingAgreementInput,
    ): ProcessingAgreementDto

    /** Role: BOARD/ADMIN. Newest-first by `createdAt`, optionally filtered by [category]. */
    suspend fun listTechnicalOrganizationalMeasures(category: TomCategory? = null): List<TechnicalOrganizationalMeasureDto>

    /** Role: ADMIN. `version` starts at 1. */
    suspend fun createTechnicalOrganizationalMeasure(input: TechnicalOrganizationalMeasureInput): TechnicalOrganizationalMeasureDto

    /** Role: ADMIN. Throws [NotFoundException] if [id] does not resolve to a row. `version` increments by 1. */
    suspend fun updateTechnicalOrganizationalMeasure(
        id: String,
        input: TechnicalOrganizationalMeasureInput,
    ): TechnicalOrganizationalMeasureDto

    /** Role: BOARD/ADMIN. Newest-first by `createdAt`, optionally filtered by [status]. */
    suspend fun listDpiaAssessments(status: DsfaStatus? = null): List<DpiaAssessmentDto>

    /** Role: BOARD/ADMIN. `version` starts at 1, `status` defaults to `DRAFT`. */
    suspend fun createDpiaAssessment(input: DpiaAssessmentInput): DpiaAssessmentDto

    /** Role: BOARD/ADMIN. Throws [NotFoundException] if [id] does not resolve to a row. `version` increments by 1. */
    suspend fun updateDpiaAssessment(
        id: String,
        input: DpiaAssessmentInput,
    ): DpiaAssessmentDto

    /** Role: BOARD/ADMIN. Newest-first by `reportedAt`, optionally filtered by [status]. */
    suspend fun listDataBreachIncidents(status: BreachStatus? = null): List<DataBreachIncidentDto>

    /** Role: BOARD/ADMIN. `status` defaults to `REPORTED`. */
    suspend fun createDataBreachIncident(input: DataBreachIncidentInput): DataBreachIncidentDto

    /** Role: BOARD/ADMIN. Throws [NotFoundException] if [id] does not resolve to a row. */
    suspend fun updateDataBreachIncident(
        id: String,
        input: DataBreachIncidentInput,
    ): DataBreachIncidentDto
}
