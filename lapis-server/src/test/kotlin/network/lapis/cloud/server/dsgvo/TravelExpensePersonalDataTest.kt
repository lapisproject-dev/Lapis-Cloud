package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * Welle V1.4.11 -- covers [TravelExpensePersonalData]'s DRAFT-vs-REQUESTED+ erasure split and the
 * three-role (subject/requestedBy/decidedBy) no-double-counting discipline. Mirrors
 * [ContributionReliefPersonalDataTest]'s house style.
 */
class TravelExpensePersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdReportIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                TravelExpenseDocumentTable.deleteAll()
                TravelExpenseAttachmentTable.deleteWhere { TravelExpenseAttachmentTable.reportId inList createdReportIds }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList createdReportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList createdReportIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DSGVO Testmitglied"
                    it[email] = "travel-dsgvo-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun newReport(
            subjectId: Uuid,
            requestedBy: Uuid = subjectId,
            decidedBy: Uuid? = null,
            status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
            submittedAt: LocalDateTime? = null,
            // Required by chk_ter_decided_needs_note whenever status is APPROVED/EXECUTED/REJECTED.
            decisionNote: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                TravelExpenseReportTable.insert {
                    it[TravelExpenseReportTable.id] = id
                    it[TravelExpenseReportTable.subjectMemberId] = subjectId
                    it[TravelExpenseReportTable.status] = status
                    it[purpose] = "Konferenzbesuch Muenchen"
                    it[travelFrom] = LocalDate(2026, 1, 5)
                    it[travelTo] = LocalDate(2026, 1, 5)
                    it[totalAmount] = BigDecimal("36.00")
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[TravelExpenseReportTable.requestedBy] = requestedBy
                    it[TravelExpenseReportTable.decidedBy] = decidedBy
                    it[TravelExpenseReportTable.submittedAt] = submittedAt
                    it[TravelExpenseReportTable.decisionNote] = decisionNote
                }
                TravelExpenseLineTable.insert {
                    it[TravelExpenseLineTable.id] = Uuid.random()
                    it[reportId] = id
                    it[kind] = TravelExpenseLineKind.MILEAGE
                    it[description] = "Anfahrt zur Konferenz"
                    it[kilometers] = BigDecimal("120.00")
                    it[days] = null
                    it[rateSnapshot] = BigDecimal("0.3000")
                    it[amount] = BigDecimal("36.00")
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdReportIds += id
            return id
        }

        test(
            "PersonalDataCoverageTest regression anchor: coveredTables matches the travel-expense tables (V1.9.123: plus the two SEPA batch tables)",
        ) {
            TravelExpensePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf(
                    "travel_expense_report",
                    "travel_expense_line",
                    "travel_expense_receipt",
                    "travel_expense_attachment",
                    "travel_expense_document",
                    "travel_expense_sepa_batch",
                    "travel_expense_sepa_batch_item",
                )
        }

        // V1.9.198: a report-level attachment with a real file under the contributor's storage root.
        fun addAttachment(
            reportId: Uuid,
            uploadedBy: Uuid,
        ): Pair<Uuid, File> {
            val attachmentId = Uuid.random()
            val key = "travel-expenses/$reportId/attachments/$attachmentId.bin"
            val file = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage").resolve(key)
            file.parentFile.mkdirs()
            file.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D))
            transaction {
                TravelExpenseAttachmentTable.insert {
                    it[id] = attachmentId
                    it[TravelExpenseAttachmentTable.reportId] = reportId
                    it[storageKey] = key
                    it[originalFilename] = "Einladung.pdf"
                    it[mimeType] = "application/pdf"
                    it[sizeBytes] = 5
                    it[sha256] = "0".repeat(64)
                    it[TravelExpenseAttachmentTable.uploadedBy] = uploadedBy
                    it[uploadedAt] = DbClock.nowLocalDateTime()
                }
            }
            return attachmentId to file
        }

        test("V1.9.198: erasing a DRAFT removes its attachment rows and files before the report") {
            val subject = newMember()
            val reportId = newReport(subject, status = TravelExpenseReportStatus.DRAFT)
            val (_, file) = addAttachment(reportId, subject)
            file.exists() shouldBe true

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            file.exists() shouldBe false
            transaction {
                TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.reportId eq reportId }.count()
            } shouldBe
                0L
            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.count() } shouldBe 0L
            createdReportIds -= reportId
            outcomes.single { it.table == "travel_expense_attachment" }.rowsDeleted shouldBe 1
        }

        test("V1.9.198: a submitted (retained) report keeps its attachment row and file on erasure") {
            val subject = newMember()
            val reportId =
                newReport(subject, status = TravelExpenseReportStatus.REQUESTED, submittedAt = DbClock.nowLocalDateTime())
            val (attachmentId, file) = addAttachment(reportId, subject)

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            file.exists() shouldBe true
            transaction {
                TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq attachmentId }.count()
            } shouldBe
                1L
            outcomes.single { it.table == "travel_expense_attachment" }.rowsDeleted shouldBe 0
            file.delete()
        }

        test("V1.9.198: the export lists attachment metadata, never the bytes or the storage key") {
            val subject = newMember()
            val reportId = newReport(subject, status = TravelExpenseReportStatus.DRAFT)
            val (_, file) = addAttachment(reportId, subject)

            val json = transaction { TravelExpensePersonalData.exportMember(subject).toString() }
            json.contains("\"attachments\"") shouldBe true
            json.contains("Einladung.pdf") shouldBe true
            json.contains("application/pdf") shouldBe true
            json.contains("storageKey") shouldBe false
            json.contains("travel-expenses/") shouldBe false
            file.delete()
        }

        test(
            "V1.9.199: an uploaded organisation document stays on erasure, only the uploader link is removed; the export lists metadata only",
        ) {
            val uploader = newMember()
            val documentId = Uuid.random()
            transaction {
                TravelExpenseDocumentTable.insert {
                    it[id] = documentId
                    it[title] = "Reisekostenordnung"
                    it[storageKey] = "travel-expenses/documents/$documentId.bin"
                    it[originalFilename] = "ordnung.pdf"
                    it[mimeType] = "application/pdf"
                    it[sizeBytes] = 10L
                    it[sha256] = "0".repeat(64)
                    it[uploadedBy] = uploader
                    it[uploadedAt] = DbClock.nowLocalDateTime()
                }
            }
            val json = transaction { TravelExpensePersonalData.exportMember(uploader).toString() }
            json.contains("uploadedTravelExpenseDocuments") shouldBe true
            json.contains("Reisekostenordnung") shouldBe true
            json.contains("storage") shouldBe false
            json.contains("ordnung.pdf") shouldBe false

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = uploader, mode = ErasureMode.ANONYMIZE) }

            val row = transaction { TravelExpenseDocumentTable.selectAll().where { TravelExpenseDocumentTable.id eq documentId }.single() }
            row[TravelExpenseDocumentTable.uploadedBy] shouldBe null
            row[TravelExpenseDocumentTable.title] shouldBe "Reisekostenordnung"
            outcomes.single { it.table == "travel_expense_document" }.rowsRetained shouldBe 1
            transaction { TravelExpenseDocumentTable.deleteWhere { TravelExpenseDocumentTable.id eq documentId } }
        }

        test("DRAFT report is fully deleted, including its lines") {
            val subject = newMember()
            val reportId = newReport(subject, status = TravelExpenseReportStatus.DRAFT)

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.count() } shouldBe 0L
            transaction { TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.count() } shouldBe 0L
            createdReportIds -= reportId // already gone, afterSpec must not try to delete it again
            outcomes.single { it.table == "travel_expense_report" }.rowsDeleted shouldBe 1
        }

        test("REQUESTED+ report is redacted, not deleted: purpose/description nulled, amount/status/dates retained") {
            val subject = newMember()
            val reportId =
                newReport(subject, status = TravelExpenseReportStatus.REQUESTED, submittedAt = DbClock.nowLocalDateTime())

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            val row = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single() }
            row[TravelExpenseReportTable.purpose] shouldBe "[gelöscht]"
            row[TravelExpenseReportTable.status] shouldBe TravelExpenseReportStatus.REQUESTED
            row[TravelExpenseReportTable.totalAmount] shouldBe BigDecimal("36.00")
            row[TravelExpenseReportTable.travelFrom] shouldBe LocalDate(2026, 1, 5)

            val lineRow = transaction { TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.single() }
            lineRow[TravelExpenseLineTable.description] shouldBe "[gelöscht]"
            lineRow[TravelExpenseLineTable.amount] shouldBe BigDecimal("36.00")

            val reportOutcome = outcomes.single { it.table == "travel_expense_report" }
            reportOutcome.rowsAnonymized shouldBe 1
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.retentionReason.shouldNotBeBlank()
        }

        test(
            "V1.9.122: export carries the new fields; erasing a REQUESTED report nulls destination and membership snapshot but keeps times, meals and amounts",
        ) {
            val subject = newMember()
            val reportId =
                newReport(subject, status = TravelExpenseReportStatus.REQUESTED, submittedAt = DbClock.nowLocalDateTime())
            transaction {
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
                    it[advanceAmount] = BigDecimal("10.00")
                    it[payoutChoice] = TravelExpensePayoutChoice.DONATION_WAIVER
                    it[membershipNumberSnapshot] = "M-4711"
                }
                TravelExpenseLineTable.insert {
                    it[TravelExpenseLineTable.id] = Uuid.random()
                    it[TravelExpenseLineTable.reportId] = reportId
                    it[kind] = TravelExpenseLineKind.DAY_ALLOWANCE
                    it[description] = "Berlin"
                    it[destination] = "Berlin"
                    it[dayDate] = LocalDate(2026, 1, 5)
                    it[timeFrom] = LocalTime.of(8, 0)
                    it[timeTo] = LocalTime.of(17, 0)
                    it[breakfastProvided] = true
                    it[lunchProvided] = false
                    it[dinnerProvided] = false
                    it[computedMinutes] = 540
                    it[baseAmount] = BigDecimal("6.00")
                    it[deductionAmount] = BigDecimal("5.60")
                    it[amount] = BigDecimal("0.40")
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }

            val exported = transaction { TravelExpensePersonalData.exportMember(subject).toString() }
            exported.contains("\"membershipNumberSnapshot\":\"M-4711\"") shouldBe true
            exported.contains("\"payoutChoice\":\"DONATION_WAIVER\"") shouldBe true
            exported.contains("\"destination\":\"Berlin\"") shouldBe true
            exported.contains("\"breakfastProvided\":true") shouldBe true

            transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            val report = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single() }
            report[TravelExpenseReportTable.membershipNumberSnapshot] shouldBe null
            report[TravelExpenseReportTable.advanceAmount] shouldBe BigDecimal("10.00")
            report[TravelExpenseReportTable.payoutChoice] shouldBe TravelExpensePayoutChoice.DONATION_WAIVER
            val day =
                transaction {
                    TravelExpenseLineTable
                        .selectAll()
                        .where {
                            (TravelExpenseLineTable.reportId eq reportId) and
                                (TravelExpenseLineTable.kind eq TravelExpenseLineKind.DAY_ALLOWANCE)
                        }.single()
                }
            day[TravelExpenseLineTable.destination] shouldBe null
            day[TravelExpenseLineTable.description] shouldBe "[gelöscht]"
            day[TravelExpenseLineTable.timeFrom] shouldBe LocalTime.of(8, 0)
            day[TravelExpenseLineTable.breakfastProvided] shouldBe true
            day[TravelExpenseLineTable.amount] shouldBe BigDecimal("0.40")
        }

        test(
            "Security-Audit fix (2026-09-12): a REJECTED report that was submitted (never booked) is fully " +
                "deleted, not merely redacted -- the discriminator is 'ever booked', not 'ever submitted'",
        ) {
            val subject = newMember()
            val reportId =
                newReport(
                    subject,
                    status = TravelExpenseReportStatus.REJECTED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    // chk_ter_decided_needs_note.
                    decisionNote = "Antrag abgelehnt",
                )

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.count() } shouldBe 0L
            transaction { TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.count() } shouldBe 0L
            createdReportIds -= reportId // already gone, afterSpec must not try to delete it again
            outcomes.single { it.table == "travel_expense_report" }.rowsDeleted shouldBe 1
        }

        test(
            "Security-Audit fix (2026-09-12): a WITHDRAWN report that was submitted first (REQUESTED -> WITHDRAWN, " +
                "never booked) is fully deleted, not merely redacted",
        ) {
            val subject = newMember()
            val reportId =
                newReport(
                    subject,
                    status = TravelExpenseReportStatus.WITHDRAWN,
                    submittedAt = DbClock.nowLocalDateTime(),
                )

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.count() } shouldBe 0L
            createdReportIds -= reportId
            outcomes.single { it.table == "travel_expense_report" }.rowsDeleted shouldBe 1
        }

        test(
            "Security-Audit fix (2026-09-12) boundary check: APPROVED (still pending a final decision, never " +
                "booked YET) stays in the redact-only bucket, unlike REJECTED/WITHDRAWN",
        ) {
            val subject = newMember()
            val reportId =
                newReport(
                    subject,
                    status = TravelExpenseReportStatus.APPROVED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    decisionNote = "Warten auf Buchung",
                )

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            val row = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single() }
            row[TravelExpenseReportTable.purpose] shouldBe "[gelöscht]"
            row[TravelExpenseReportTable.status] shouldBe TravelExpenseReportStatus.APPROVED
            val reportOutcome = outcomes.single { it.table == "travel_expense_report" }
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.rowsAnonymized shouldBe 1
        }

        test("three roles, one row: subject == requestedBy counts as ONE row in total, never double-counted") {
            val subject = newMember()
            newReport(subject, requestedBy = subject, status = TravelExpenseReportStatus.DRAFT)

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }
            val reportOutcome = outcomes.single { it.table == "travel_expense_report" }
            (reportOutcome.rowsDeleted + reportOutcome.rowsAnonymized + reportOutcome.rowsRetained) shouldBe 1
        }

        test(
            "review MAJOR fix, Fehlerszenario A: erasing the REQUESTER (not the subject) of a still-open " +
                "DRAFT must never delete the subject's own report",
        ) {
            val subject = newMember()
            val requester = newMember()
            val reportId = newReport(subject, requestedBy = requester, status = TravelExpenseReportStatus.DRAFT)

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = requester, mode = ErasureMode.ANONYMIZE) }

            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.count() } shouldBe 1L
            val row = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single() }
            row[TravelExpenseReportTable.purpose] shouldBe "Konferenzbesuch Muenchen"
            transaction { TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.count() } shouldBe 1L
            val reportOutcome = outcomes.single { it.table == "travel_expense_report" }
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.rowsAnonymized shouldBe 0
        }

        test(
            "review MAJOR fix, Fehlerszenario B: erasing the DECIDER (not the subject) of a REJECTED " +
                "report must never redact the subject's own purpose/description",
        ) {
            val subject = newMember()
            val decider = newMember()
            val reportId =
                newReport(
                    subject,
                    decidedBy = decider,
                    status = TravelExpenseReportStatus.REJECTED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    // chk_ter_decided_needs_note -- REJECTED, like APPROVED/EXECUTED, requires a note.
                    decisionNote = "Antrag abgelehnt",
                )

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = decider, mode = ErasureMode.ANONYMIZE) }

            val row = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single() }
            row[TravelExpenseReportTable.purpose] shouldBe "Konferenzbesuch Muenchen"
            val lineRow = transaction { TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.single() }
            lineRow[TravelExpenseLineTable.description] shouldBe "Anfahrt zur Konferenz"
            val reportOutcome = outcomes.single { it.table == "travel_expense_report" }
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.rowsAnonymized shouldBe 0
            reportOutcome.rowsRetained shouldBe 1
        }

        test("export contains the report and its lines, never a receipt's file bytes") {
            val subject = newMember()
            newReport(subject, status = TravelExpenseReportStatus.DRAFT)

            val export = transaction { TravelExpensePersonalData.exportMember(subject) }
            val json = export.toString()
            json.contains("travelExpenseReports") shouldBe true
            json.contains("Konferenzbesuch") shouldBe true
            json.contains("lineCount") shouldBe true
        }
    })
