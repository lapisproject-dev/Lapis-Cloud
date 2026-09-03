package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.DataBreachIncidentTable
import network.lapis.cloud.server.db.generated.DataProtectionImpactAssessmentTable
import network.lapis.cloud.server.db.generated.ProcessingAgreementTable
import network.lapis.cloud.server.db.generated.TechnicalOrganizationalMeasureTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ProcessingAgreementTable]/[TechnicalOrganizationalMeasureTable]/
 * [DataProtectionImpactAssessmentTable]/[DataBreachIncidentTable] -- the four member-FK-bearing
 * tables the V0.5.5 DSGVO-Vollausbau domain adds (`created_by`/`updated_by`, plus `reported_by` on
 * the breach table). See `network.lapis.cloud.server.rpc.DsgvoComplianceService` KDoc for the write
 * path and `16-dsgvo-compliance.kuml.kts`'s file header for the domain rationale.
 *
 * **These records are ORGANISATIONAL compliance documentation, not personal data ABOUT the member --
 * the member is only ever the AKTOR (who documented/reported/edited), never the data subject of the
 * row's content.** A member's own DSGVO-relevant personal data (their contributions, memberships,
 * elections, etc.) is exported/erased by that data's own domain-specific contributor; this
 * contributor only ever surfaces "which of these compliance rows did this member happen to create,
 * update, or report".
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- same "no field is ever cleared" outcome
 * as [AuditLogPersonalData]/[BackupOperationPersonalData], but on a DELIBERATELY SOFTER legal basis
 * than those two.** [AuditLogPersonalData] rests on GoBD Nachvollziehbarkeit (a fiscal-law
 * retention obligation) and [BackupOperationPersonalData] on "who triggered the single most
 * privileged ADMIN operation, permanently". This domain's actor-accountability rationale is Art.
 * 5(2) DSGVO Rechenschaftspflicht applied to the organization's OWN compliance register (who
 * attested a TOM was in place, who signed off a DSFA outcome, who reported an incident) -- a
 * reasonable but genuinely weaker retention basis, since no GoBD-style statutory retention period
 * attaches to these specific tables the way it does to `journal_entry`/`resolution`.
 *
 * **Review-Pflicht, explicitly flagged (per this wave's task instructions):** whether anonymizing
 * the actor on these four tables would in fact be DSGVO-compliant (e.g. once a DSFA/breach report
 * is old enough that its accountability value has faded) is NOT settled by this wave's current
 * understanding alone -- unlike GoBD, there is no external legal deadline forcing "never delete"
 * here. A real deployment must confirm with a Datenschutzbeauftragter/lawyer whether a
 * time-bounded or role-scoped anonymization path should exist instead of this wave's blanket
 * unconditional retention. Until that review happens, retaining is the conservative default (an
 * accidentally-anonymized compliance record that turns out to have been needed is worse than an
 * accidentally-retained one), but this is explicitly NOT the same confidence level as
 * [AuditLogPersonalData]'s GoBD-grounded retention.
 *
 * [export] returns only metadata (id/category-or-processor-name/status/timestamps/version) for rows
 * where [memberId] was the actor -- never the free-text content fields (`processing_purpose`,
 * `description`, `risk_assessment`, etc.), which can legitimately describe OTHER people's data
 * categories and would otherwise leak into an unrelated data subject's export (same rationale as
 * [AuditLogPersonalData]'s own before/after-snapshot exclusion).
 */
object DsgvoCompliancePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "dsgvoCompliance"
    override val displayName = "DSGVO-Compliance (AVV/TOMs/DSFA/Datenpannen)"
    override val coveredTables =
        setOf(
            ProcessingAgreementTable,
            TechnicalOrganizationalMeasureTable,
            DataProtectionImpactAssessmentTable,
            DataBreachIncidentTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "processingAgreements",
                buildJsonArray {
                    ProcessingAgreementTable
                        .selectAll()
                        .where { (ProcessingAgreementTable.createdBy eq memberId) or (ProcessingAgreementTable.updatedBy eq memberId) }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[ProcessingAgreementTable.id].toString())
                                    put("processorName", row[ProcessingAgreementTable.processorName])
                                    put("avvStatus", row[ProcessingAgreementTable.avvStatus].name)
                                    put("createdAt", row[ProcessingAgreementTable.createdAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "technicalOrganizationalMeasures",
                buildJsonArray {
                    TechnicalOrganizationalMeasureTable
                        .selectAll()
                        .where {
                            (TechnicalOrganizationalMeasureTable.createdBy eq memberId) or
                                (TechnicalOrganizationalMeasureTable.updatedBy eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[TechnicalOrganizationalMeasureTable.id].toString())
                                    put("category", row[TechnicalOrganizationalMeasureTable.category].name)
                                    put("version", row[TechnicalOrganizationalMeasureTable.version])
                                    put("createdAt", row[TechnicalOrganizationalMeasureTable.createdAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "dpiaAssessments",
                buildJsonArray {
                    DataProtectionImpactAssessmentTable
                        .selectAll()
                        .where {
                            (DataProtectionImpactAssessmentTable.createdBy eq memberId) or
                                (DataProtectionImpactAssessmentTable.updatedBy eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[DataProtectionImpactAssessmentTable.id].toString())
                                    put("status", row[DataProtectionImpactAssessmentTable.status].name)
                                    put("version", row[DataProtectionImpactAssessmentTable.version])
                                    put("createdAt", row[DataProtectionImpactAssessmentTable.createdAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "dataBreachIncidents",
                buildJsonArray {
                    DataBreachIncidentTable
                        .selectAll()
                        .where {
                            (DataBreachIncidentTable.reportedBy eq memberId) or (DataBreachIncidentTable.updatedBy eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[DataBreachIncidentTable.id].toString())
                                    put("status", row[DataBreachIncidentTable.status].name)
                                    put("reportedAt", row[DataBreachIncidentTable.reportedAt].toString())
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val retentionReason =
            "Organizational Art. 5(2) DSGVO accountability record of who documented/edited this " +
                "organization-facing compliance row -- retained unconditionally, on a DELIBERATELY " +
                "SOFTER basis than AuditLogPersonalData's GoBD grounding; see " +
                "DsgvoCompliancePersonalData KDoc's explicit Review-Pflicht note before assuming " +
                "this is the final word for a real deployment."
        val agreementCount =
            ProcessingAgreementTable
                .selectAll()
                .where { (ProcessingAgreementTable.createdBy eq memberId) or (ProcessingAgreementTable.updatedBy eq memberId) }
                .count()
        val tomCount =
            TechnicalOrganizationalMeasureTable
                .selectAll()
                .where {
                    (TechnicalOrganizationalMeasureTable.createdBy eq memberId) or
                        (TechnicalOrganizationalMeasureTable.updatedBy eq memberId)
                }.count()
        val dpiaCount =
            DataProtectionImpactAssessmentTable
                .selectAll()
                .where {
                    (DataProtectionImpactAssessmentTable.createdBy eq memberId) or
                        (DataProtectionImpactAssessmentTable.updatedBy eq memberId)
                }.count()
        val breachCount =
            DataBreachIncidentTable
                .selectAll()
                .where { (DataBreachIncidentTable.reportedBy eq memberId) or (DataBreachIncidentTable.updatedBy eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(table = "processing_agreement", rowsRetained = agreementCount.toInt(), retentionReason = retentionReason),
            TableErasureOutcome(
                table = "technical_organizational_measure",
                rowsRetained = tomCount.toInt(),
                retentionReason = retentionReason,
            ),
            TableErasureOutcome(
                table = "data_protection_impact_assessment",
                rowsRetained = dpiaCount.toInt(),
                retentionReason = retentionReason,
            ),
            TableErasureOutcome(table = "data_breach_incident", rowsRetained = breachCount.toInt(), retentionReason = retentionReason),
        )
    }
}
