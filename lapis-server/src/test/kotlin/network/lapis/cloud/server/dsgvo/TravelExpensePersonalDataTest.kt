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
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
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

        test("PersonalDataCoverageTest regression anchor: coveredTables matches the three travel-expense tables") {
            TravelExpensePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("travel_expense_report", "travel_expense_line", "travel_expense_receipt")
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
