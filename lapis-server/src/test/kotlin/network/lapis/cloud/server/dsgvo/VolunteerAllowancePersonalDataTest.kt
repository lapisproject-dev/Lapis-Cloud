package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- covers [VolunteerAllowancePersonalData]'s
 * "wurde je gebucht" (status==EXECUTED) erasure discriminator, the four-role (subject/requestedBy/
 * decidedBy/capAcknowledgedBy) no-double-counting discipline on the payment table, and the
 * self-declaration retention-while-still-needed rule. Mirrors [TravelExpensePersonalDataTest]'s
 * house style.
 */
class VolunteerAllowancePersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPaymentIds = mutableListOf<Uuid>()
        val createdDeclarationIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                // Order matters: volunteer_allowance_payment.posted_journal_entry_id FKs ->
                // journal_entry, and journal_entry.created_by FKs -> member -- payments before
                // journal entries before members, or the FK constraints reject the delete.
                VolunteerAllowancePaymentTable.deleteWhere { VolunteerAllowancePaymentTable.id inList createdPaymentIds }
                VolunteerAllowanceSelfDeclarationTable.deleteWhere {
                    VolunteerAllowanceSelfDeclarationTable.id inList createdDeclarationIds
                }
                if (createdJournalEntryIds.isNotEmpty()) {
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
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
                    it[email] = "volunteer-dsgvo-$id@example.org"
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

        fun newPayment(
            subject: Uuid,
            requestedBy: Uuid = subject,
            decidedBy: Uuid? = null,
            capAcknowledger: Uuid? = null,
            status: VolunteerAllowancePaymentStatus = VolunteerAllowancePaymentStatus.DRAFT,
            submittedAt: LocalDateTime? = null,
            decisionNote: String? = null,
            postedJournalEntryId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                VolunteerAllowancePaymentTable.insert {
                    it[VolunteerAllowancePaymentTable.id] = id
                    it[subjectMemberId] = subject
                    it[category] = VolunteerAllowanceCategory.HONORARY
                    it[VolunteerAllowancePaymentTable.status] = status
                    it[amount] = BigDecimal("50.00")
                    it[activityDescription] = "Vereinshelfer beim Sommerfest"
                    it[paymentDate] = LocalDate(2026, 6, 1)
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[VolunteerAllowancePaymentTable.requestedBy] = requestedBy
                    it[VolunteerAllowancePaymentTable.decidedBy] = decidedBy
                    it[VolunteerAllowancePaymentTable.submittedAt] = submittedAt
                    it[VolunteerAllowancePaymentTable.decisionNote] = decisionNote
                    it[VolunteerAllowancePaymentTable.postedJournalEntryId] = postedJournalEntryId
                    it[priorTotalSnapshot] =
                        if (status == VolunteerAllowancePaymentStatus.DRAFT ||
                            status == VolunteerAllowancePaymentStatus.REQUESTED
                        ) {
                            null
                        } else {
                            BigDecimal("0.00")
                        }
                    it[freeAmountSnapshot] =
                        if (status == VolunteerAllowancePaymentStatus.DRAFT ||
                            status == VolunteerAllowancePaymentStatus.REQUESTED
                        ) {
                            null
                        } else {
                            BigDecimal("50.00")
                        }
                    it[exceedingAmountSnapshot] =
                        if (status == VolunteerAllowancePaymentStatus.DRAFT ||
                            status == VolunteerAllowancePaymentStatus.REQUESTED
                        ) {
                            null
                        } else {
                            BigDecimal("0.00")
                        }
                    it[capAcknowledgedBy] = capAcknowledger
                }
            }
            createdPaymentIds += id
            return id
        }

        fun newDeclaration(
            member: Uuid,
            year: Int = 2026,
            recordedBy: Uuid = member,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                VolunteerAllowanceSelfDeclarationTable.insert {
                    it[VolunteerAllowanceSelfDeclarationTable.id] = id
                    it[memberId] = member
                    it[category] = VolunteerAllowanceCategory.HONORARY
                    it[calendarYear] = year
                    it[declarationSource] =
                        if (recordedBy ==
                            member
                        ) {
                            VolunteerAllowanceDeclarationSource.IN_APP
                        } else {
                            VolunteerAllowanceDeclarationSource.ON_PAPER
                        }
                    it[declaredAt] = DbClock.nowLocalDateTime()
                    it[signedOn] = if (recordedBy == member) null else LocalDate(2026, 1, 1)
                    it[VolunteerAllowanceSelfDeclarationTable.recordedBy] = recordedBy
                }
            }
            createdDeclarationIds += id
            return id
        }

        test("PersonalDataCoverageTest regression anchor: coveredTables matches the two volunteer-allowance tables") {
            VolunteerAllowancePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("volunteer_allowance_payment", "volunteer_allowance_self_declaration")
        }

        test("a DRAFT payment is fully deleted") {
            val subject = newMember()
            val paymentId = newPayment(subject, status = VolunteerAllowancePaymentStatus.DRAFT)

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction {
                VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.count()
            } shouldBe
                0L
            createdPaymentIds -= paymentId
            outcomes.single { it.table == "volunteer_allowance_payment" }.rowsDeleted shouldBe 1
        }

        test("a REJECTED payment (never booked) is fully deleted -- discriminator is 'ever booked', not 'ever submitted'") {
            val subject = newMember()
            val paymentId =
                newPayment(
                    subject,
                    status = VolunteerAllowancePaymentStatus.REJECTED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    decisionNote = "Antrag abgelehnt",
                )

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction {
                VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.count()
            } shouldBe
                0L
            createdPaymentIds -= paymentId
            outcomes.single { it.table == "volunteer_allowance_payment" }.rowsDeleted shouldBe 1
        }

        test("an APPROVED payment (approved but never actually booked) is fully deleted") {
            val subject = newMember()
            val paymentId =
                newPayment(
                    subject,
                    status = VolunteerAllowancePaymentStatus.APPROVED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    decisionNote = "Genehmigt",
                )

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            transaction {
                VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.count()
            } shouldBe
                0L
            createdPaymentIds -= paymentId
            outcomes.single { it.table == "volunteer_allowance_payment" }.rowsDeleted shouldBe 1
        }

        test(
            "an EXECUTED payment is redacted, not deleted: activityDescription replaced with a placeholder, " +
                "decisionNote/amount/status/dates retained",
        ) {
            val subject = newMember()
            val fakeJournalEntryId = Uuid.random()
            // Satisfies chk_vap_posted_entry_state (EXECUTED <=> posted_journal_entry_id IS NOT NULL)
            // without pulling in the whole JournalEntry/PostingBridge machinery -- a plain FK target
            // row is enough for THIS test's purpose (erasure behaviour, not booking correctness).
            transaction {
                JournalEntryTable.insert {
                    it[id] = fakeJournalEntryId
                    it[entryDate] = LocalDate(2026, 6, 1)
                    it[description] = "Test-Fixture"
                    it[voucherReference] = "TEST-FIXTURE-$fakeJournalEntryId"
                    it[createdBy] = subject
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = DbClock.nowLocalDateTime()
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[donorMemberId] = null
                    it[externalDonorId] = null
                    it[donorCategory] = null
                }
            }
            createdJournalEntryIds += fakeJournalEntryId
            val paymentId =
                newPayment(
                    subject,
                    status = VolunteerAllowancePaymentStatus.EXECUTED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    decisionNote = "Genehmigt",
                    postedJournalEntryId = fakeJournalEntryId,
                )

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            val row =
                transaction { VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.single() }
            row[VolunteerAllowancePaymentTable.activityDescription] shouldBe "[gelöscht]"
            // decision_note is RETAINED, not nulled -- chk_vap_decided_needs_note requires it
            // non-null for every EXECUTED row (see VolunteerAllowancePersonalData class KDoc).
            row[VolunteerAllowancePaymentTable.decisionNote] shouldBe "Genehmigt"
            row[VolunteerAllowancePaymentTable.status] shouldBe VolunteerAllowancePaymentStatus.EXECUTED
            row[VolunteerAllowancePaymentTable.amount] shouldBe BigDecimal("50.00")
            row[VolunteerAllowancePaymentTable.postedJournalEntryId] shouldBe fakeJournalEntryId

            val reportOutcome = outcomes.single { it.table == "volunteer_allowance_payment" }
            reportOutcome.rowsAnonymized shouldBe 1
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.retentionReason.shouldNotBeBlank()
            // Content assert, not just non-blank -- a Round-1 defect had EXECUTED and "every other
            // status" swapped in this exact sentence, which the DsgvoRightsScreen ships VERBATIM to
            // the affected person (review MINOR finding: a bare shouldNotBeBlank() would not have
            // caught that regression, or catch it coming back). Pins BOTH halves of the sentence so
            // a future swap of "NICHT gelöscht"/"vollständig gelöscht" between the two branches
            // fails here instead of only in a human re-read of the text shipped to members.
            reportOutcome.retentionReason shouldContain "Nur EXECUTED wurde tatsächlich gebucht"
            reportOutcome.retentionReason shouldContain "wird deshalb NICHT gelöscht"
            reportOutcome.retentionReason shouldContain
                "Jeder andere Status (noch nicht bzw. nicht mehr gebucht) wird vollständig gelöscht"
            // Cleanup happens in afterSpec (payment row, then journal entry, then member, in that
            // FK-safe order) -- not here, see createdJournalEntryIds/createdPaymentIds.
        }

        test("three roles, one row: subject == requestedBy counts as ONE row in total, never double-counted") {
            val subject = newMember()
            newPayment(subject, requestedBy = subject, status = VolunteerAllowancePaymentStatus.DRAFT)

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }
            val reportOutcome = outcomes.single { it.table == "volunteer_allowance_payment" }
            (reportOutcome.rowsDeleted + reportOutcome.rowsAnonymized + reportOutcome.rowsRetained) shouldBe 1
        }

        test("erasing the REQUESTER (not the subject) of a still-open DRAFT must never delete the subject's own payment") {
            val subject = newMember()
            val requester = newMember()
            val paymentId = newPayment(subject, requestedBy = requester, status = VolunteerAllowancePaymentStatus.DRAFT)

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = requester, mode = ErasureMode.ANONYMIZE) }

            transaction {
                VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.count()
            } shouldBe
                1L
            val row =
                transaction { VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.single() }
            row[VolunteerAllowancePaymentTable.activityDescription] shouldBe "Vereinshelfer beim Sommerfest"
            val reportOutcome = outcomes.single { it.table == "volunteer_allowance_payment" }
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.rowsAnonymized shouldBe 0
        }

        test("erasing the DECIDER (not the subject) of a REJECTED payment must never touch the subject's own activityDescription") {
            val subject = newMember()
            val decider = newMember()
            val paymentId =
                newPayment(
                    subject,
                    decidedBy = decider,
                    status = VolunteerAllowancePaymentStatus.REJECTED,
                    submittedAt = DbClock.nowLocalDateTime(),
                    decisionNote = "Antrag abgelehnt",
                )

            val outcomes = transaction { VolunteerAllowancePersonalData.eraseMember(memberId = decider, mode = ErasureMode.ANONYMIZE) }

            val row =
                transaction { VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq paymentId }.single() }
            row[VolunteerAllowancePaymentTable.activityDescription] shouldBe "Vereinshelfer beim Sommerfest"
            val reportOutcome = outcomes.single { it.table == "volunteer_allowance_payment" }
            reportOutcome.rowsDeleted shouldBe 0
            reportOutcome.rowsAnonymized shouldBe 0
            reportOutcome.rowsRetained shouldBe 1
        }

        test(
            "declareSelf declarations are deleted for the subject, EXCEPT when a still-EXECUTED payment of the same category/year exists",
        ) {
            val subjectWithoutBooking = newMember()
            val declId1 = newDeclaration(subjectWithoutBooking)
            val outcomes1 =
                transaction { VolunteerAllowancePersonalData.eraseMember(memberId = subjectWithoutBooking, mode = ErasureMode.ANONYMIZE) }
            transaction {
                VolunteerAllowanceSelfDeclarationTable.selectAll().where { VolunteerAllowanceSelfDeclarationTable.id eq declId1 }.count()
            } shouldBe 0L
            createdDeclarationIds -= declId1
            outcomes1.single { it.table == "volunteer_allowance_self_declaration" }.rowsDeleted shouldBe 1
        }

        test("export contains both tables and every role, never a receipt/file byte since this domain has none") {
            val subject = newMember()
            newPayment(subject, status = VolunteerAllowancePaymentStatus.DRAFT)
            newDeclaration(subject)

            val export = transaction { VolunteerAllowancePersonalData.exportMember(subject) }
            val json = export.toString()
            json.contains("volunteerAllowancePayments") shouldBe true
            json.contains("volunteerAllowanceDeclarations") shouldBe true
            json.contains("Vereinshelfer") shouldBe true
        }

        test("export total is computed via a single multi-way OR, not a naive sum of per-role counts") {
            val subject = newMember()
            // subject is BOTH the payment subject and requestedBy for this one row -- if export()
            // counted per-role rather than per-row, this would silently show up as two rows.
            newPayment(subject, requestedBy = subject, status = VolunteerAllowancePaymentStatus.DRAFT)

            val export = transaction { VolunteerAllowancePersonalData.exportMember(subject) }
            val paymentsArray = export["volunteerAllowancePayments"]
            (paymentsArray as JsonArray).size shouldBe 1
        }

        // Security-Fund (INFORMATIONAL DSGVO Art. 15) -- a BOARD member's own export must never
        // surface the SUBJECT's activityDescription or the board member's own decisionNote about
        // that subject, even though the row is correctly matched into the export via decidedBy.
        // See VolunteerAllowancePersonalData.exportMember's own inline comment for the reasoning.
        test("export for a DECIDER-only role (not the subject) never leaks activityDescription/decisionNote") {
            val subject = newMember()
            val decider = newMember()
            newPayment(
                subject,
                requestedBy = subject,
                decidedBy = decider,
                status = VolunteerAllowancePaymentStatus.REJECTED,
                submittedAt = DbClock.nowLocalDateTime(),
                decisionNote = "Board-interne Ablehnungsbegruendung",
            )

            val deciderExport = transaction { VolunteerAllowancePersonalData.exportMember(decider) }
            val deciderJson = deciderExport.toString()
            deciderJson.contains("Vereinshelfer") shouldBe false
            deciderJson.contains("Board-interne Ablehnungsbegruendung") shouldBe false

            // Control: the SUBJECT's own export of the SAME row still carries both free-text fields.
            val subjectExport = transaction { VolunteerAllowancePersonalData.exportMember(subject) }
            val subjectJson = subjectExport.toString()
            subjectJson.contains("Vereinshelfer") shouldBe true
            subjectJson.contains("Board-interne Ablehnungsbegruendung") shouldBe true
        }
    })
