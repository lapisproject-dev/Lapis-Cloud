package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.DataBreachIncidentTable
import network.lapis.cloud.server.db.generated.ProcessingAgreementTable
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")

/**
 * Exercises [DsgvoCompliancePersonalData] directly (no HTTP layer needed) -- the actual
 * cross-cutting enforcement ([PersonalDataRegistry] registration + coverage) is exercised by
 * [PersonalDataCoverageTest], which this test's own rows also feed into (a fresh
 * `processing_agreement`/`data_breach_incident` row with a member FK is exactly the kind of row
 * [PersonalDataCoverageTest] would go red on if [DsgvoCompliancePersonalData] were not registered).
 */
class DsgvoCompliancePersonalDataTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("DsgvoCompliancePersonalData.coveredTables covers exactly the four V0.5.5 tables") {
            DsgvoCompliancePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf(
                    "processing_agreement",
                    "technical_organizational_measure",
                    "data_protection_impact_assessment",
                    "data_breach_incident",
                )
        }

        test("export() returns only rows where the member was an actor, with metadata only -- no third-party PII leaked") {
            val agreementId = Uuid.random()
            transaction {
                ProcessingAgreementTable.insert {
                    it[id] = agreementId
                    it[processorName] = "Export-Test-Processor"
                    it[processingPurpose] = "Sensible Verarbeitungsdetails, die NICHT im Export erscheinen duerfen"
                    it[dataCategories] = "Name, Adresse"
                    it[avvStatus] = AvvStatus.DRAFT
                    it[signedDate] = null
                    it[reviewDueDate] = null
                    it[documentId] = null
                    it[notes] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                    it[createdBy] = ADMIN_ID
                    it[updatedAt] = null
                    it[updatedBy] = null
                }
            }

            val exported =
                transaction {
                    DsgvoCompliancePersonalData.export(ADMIN_ID)
                }
            val agreements = exported["processingAgreements"]!!.jsonArray
            (agreements.any { it.jsonObject["id"]!!.jsonPrimitive.content == agreementId.toString() }) shouldBe true
            // The free-text processing_purpose content is never included in the export payload.
            exported.toString().contains("Sensible Verarbeitungsdetails") shouldBe false

            val exportedForUnrelatedMember =
                transaction {
                    DsgvoCompliancePersonalData.export(BOARD_ID)
                }
            val unrelatedAgreements = exportedForUnrelatedMember["processingAgreements"]!!.jsonArray
            (unrelatedAgreements.any { it.jsonObject["id"]!!.jsonPrimitive.content == agreementId.toString() }) shouldBe false
        }

        test("erase() retains every row unconditionally regardless of ErasureMode, with a non-blank retentionReason") {
            val breachId = Uuid.random()
            transaction {
                DataBreachIncidentTable.insert {
                    it[id] = breachId
                    it[discoveredAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                    it[description] = "Erasure-Test-Vorfall"
                    it[affectedDataCategories] = "E-Mail-Adressen"
                    it[estimatedAffectedPersons] = null
                    it[riskAssessment] = null
                    it[riskLevel] = null
                    it[authorityNotificationRequired] = null
                    it[authorityNotifiedAt] = null
                    it[dataSubjectsNotifiedAt] = null
                    it[status] = BreachStatus.REPORTED
                    it[reportedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                    it[reportedBy] = BOARD_ID
                    it[updatedAt] = null
                    it[updatedBy] = null
                }
            }

            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcomes =
                    transaction {
                        DsgvoCompliancePersonalData.erase(memberId = BOARD_ID, mode = mode)
                    }
                outcomes.size shouldBe 4
                outcomes.forEach { outcome ->
                    outcome.rowsAnonymized shouldBe 0
                    outcome.rowsDeleted shouldBe 0
                    (outcome.retentionReason?.isNotBlank() ?: false) shouldBe true
                }
                val breachOutcome = outcomes.single { it.table == "data_breach_incident" }
                (breachOutcome.rowsRetained >= 1) shouldBe true

                // The row itself is untouched -- still present, reportedBy still points at BOARD_ID.
                val stillThere =
                    transaction {
                        DataBreachIncidentTable
                            .selectAll()
                            .where { DataBreachIncidentTable.id eq breachId }
                            .single()[DataBreachIncidentTable.reportedBy]
                    }
                stillThere shouldBe BOARD_ID
            }
        }
    })
