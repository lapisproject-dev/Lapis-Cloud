package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchItemTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
import network.lapis.cloud.server.payment.bankstatement.BankAccountStore
import network.lapis.cloud.server.payment.sepa.SepaPain001Writer
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaReference
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.w3c.dom.Document
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.uuid.Uuid

/** Tuesday 2026-10-13; the next TARGET2 day is Wednesday 2026-10-14. All dates in the scenarios are relative to this pinned clock. */
private const val PINNED_NOW = "2026-10-13T10:00:00Z"
private val TODAY = LocalDate(2026, 10, 13)
private val TOMORROW = LocalDate(2026, 10, 14)

/**
 * Welle V1.9.123 -- the domain behaviour of [TravelExpenseSepaExport] against a real schema, on H2 and (the actual proof of the
 * locking) on PostgreSQL. Data is created directly in the database ([TravelExpenseSepaTestData]); the clock is pinned and every date
 * is derived from it.
 */
abstract class TravelExpenseSepaExportScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)
        lateinit var board: network.lapis.cloud.server.security.CurrentMember
        lateinit var admin: network.lapis.cloud.server.security.CurrentMember
        lateinit var plainMember: network.lapis.cloud.server.security.CurrentMember
        lateinit var debtorAccount: Uuid

        val export = TravelExpenseSepaExport(SEPA_TEST_KEY)

        val ns =
            object : NamespaceContext {
                override fun getNamespaceURI(prefix: String?): String = if (prefix == "p") SepaPain001Writer.NAMESPACE else ""

                override fun getPrefix(namespaceURI: String?): String? = null

                override fun getPrefixes(namespaceURI: String?): Iterator<String>? = null
            }

        fun parse(bytes: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            return factory.newDocumentBuilder().parse(bytes.inputStream())
        }

        fun xpath(
            document: Document,
            expression: String,
        ): String {
            val xp = XPathFactory.newInstance().newXPath()
            xp.namespaceContext = ns
            return xp.evaluate(expression, document, XPathConstants.STRING) as String
        }

        fun subject(label: String = "Reisende") = data.member(label = label, role = AccountRole.MEMBER)

        beforeSpec {
            db.activate()
            sepa.configureOrganization()
            board = sepa.actor(label = "board", role = AccountRole.BOARD)
            admin = sepa.actor(label = "admin", role = AccountRole.ADMIN)
            plainMember = sepa.actor(label = "plain", role = AccountRole.MEMBER)
            debtorAccount = sepa.bankAccount(createdBy = admin.memberId)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            if (!db.isPostgres) {
                sepa.cleanUp()
                data.cleanUp()
            }
            db.deactivate()
        }

        fun exportOf(
            reports: List<Uuid>,
            actor: network.lapis.cloud.server.security.CurrentMember = board,
            date: LocalDate = TOMORROW,
            debtor: Uuid? = debtorAccount,
        ) = TimeTestSupport.withServerClock(instant = PINNED_NOW) {
            export.export(actor = actor, reportIds = reports, debtorBankAccountId = debtor, executionDate = date)
        }

        fun failureOf(block: () -> Unit): TravelExpenseSepaException = shouldThrow<TravelExpenseSepaException>(block)

        test("happy path: two reports in one file, marks, batch, items and the file content agree") {
            val s1 = subject("hp1")
            val s2 = subject("hp2")
            val r1 =
                sepa.executedReport(
                    subject = s1,
                    totalCents = 10_000,
                    advanceCents = 2_000,
                    iban = MEMBER_IBAN_1,
                    holder = "Müller, Jörg",
                )
            val r2 = sepa.executedReport(subject = s2, totalCents = 4_505, advanceCents = 0, iban = MEMBER_IBAN_2, holder = "Zoë O'Neil")

            val result = exportOf(listOf(r1, r2))

            result.count shouldBe 2
            result.sumCents shouldBe 8_000L + 4_505L
            result.filename shouldMatch Regex("sepa-reisekosten-20261014-[0-9a-f]{8}\\.xml")
            val doc = parse(result.xml)
            xpath(doc, "//p:GrpHdr/p:NbOfTxs") shouldBe "2"
            xpath(doc, "//p:GrpHdr/p:CtrlSum") shouldBe "125.05"
            xpath(doc, "//p:PmtInf/p:CtrlSum") shouldBe "125.05"
            xpath(doc, "//p:PmtInf/p:ReqdExctnDt/p:Dt") shouldBe "2026-10-14"
            xpath(doc, "//p:PmtInf/p:DbtrAcct/p:Id/p:IBAN") shouldBe DEBTOR_IBAN
            xpath(doc, "//p:PmtInf/p:Dbtr/p:Nm") shouldBe ORGANIZATION_TEST_NAME
            xpath(doc, "//p:PmtInf/p:DbtrAgt/p:FinInstnId/p:BICFI") shouldBe "COBADEFFXXX"
            val ibans = (1..2).map { xpath(doc, "(//p:CdtTrfTxInf/p:CdtrAcct/p:Id/p:IBAN)[$it]") }
            ibans.toSet() shouldBe setOf(MEMBER_IBAN_1, MEMBER_IBAN_2)
            val names = (1..2).map { xpath(doc, "(//p:CdtTrfTxInf/p:Cdtr/p:Nm)[$it]") }.toSet()
            names shouldBe setOf("Mueller, Joerg", "Zoe O'Neil")
            xpath(doc, "(//p:CdtTrfTxInf/p:RmtInf/p:Ustrd)[1]") shouldContain "Reisekosten RK-"

            transaction {
                listOf(r1, r2).forEach { id ->
                    val row = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq id }.single()
                    row[TravelExpenseReportTable.sepaExportBatchId] shouldBe result.batchId
                    row[TravelExpenseReportTable.sepaExportedBy] shouldBe board.memberId
                    (row[TravelExpenseReportTable.sepaExportedAt] != null) shouldBe true
                    row[TravelExpenseReportTable.sepaReexportCount] shouldBe 0
                }
                val batch = TravelExpenseSepaBatchTable.selectAll().where { TravelExpenseSepaBatchTable.id eq result.batchId }.single()
                batch[TravelExpenseSepaBatchTable.transactionCount] shouldBe 2
                batch[TravelExpenseSepaBatchTable.controlSumCents] shouldBe 12_505L
                batch[TravelExpenseSepaBatchTable.debtorIbanLast4] shouldBe "3000"
                batch[TravelExpenseSepaBatchTable.painVersion] shouldBe "pain.001.001.09"
                batch[TravelExpenseSepaBatchTable.isReexport] shouldBe false
                TravelExpenseSepaBatchItemTable
                    .selectAll()
                    .where {
                        TravelExpenseSepaBatchItemTable.batchId eq result.batchId
                    }.count() shouldBe
                    2
            }
            // The audit trail has the batch entry and one per report -- and not a single IBAN, holder or name.
            val auditText =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId inList listOf(result.batchId, r1, r2) }
                        .joinToString("\n") { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
                }
            listOf(MEMBER_IBAN_1, MEMBER_IBAN_2, DEBTOR_IBAN, "Mueller", "Müller", "Jörg", "O'Neil", "Mustermann", "Reisende").forEach {
                auditText shouldNotContain it
            }
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq result.batchId }
                    .single()[AuditLogEntryTable.entityType] shouldBe AuditEntityType.TRAVEL_EXPENSE_SEPA_BATCH
            }
        }

        test("roles: a plain member is forbidden, BOARD and ADMIN may export; nothing is marked on refusal") {
            val s = subject("roles")
            val r = sepa.executedReport(subject = s)
            failureOf { exportOf(listOf(r), actor = plainMember) }.kind shouldBe SepaFailureKind.FORBIDDEN
            sepa.report(r)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
            exportOf(listOf(r), actor = admin).count shouldBe 1
        }

        test("unknown, garbage and foreign ids are one indistinguishable NOT_FOUND") {
            val s = subject("nf")
            val r = sepa.executedReport(subject = s)
            val e1 = failureOf { exportOf(listOf(Uuid.random())) }
            val e2 = failureOf { exportOf(listOf(r, Uuid.random())) }
            e1.kind shouldBe SepaFailureKind.NOT_FOUND
            e2.kind shouldBe SepaFailureKind.NOT_FOUND
            e1.code shouldBe e2.code
            sepa.report(r)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
        }

        test("every blocker is reported per report and the whole export is refused") {
            val s = subject("blk")
            val notBooked = sepa.executedReport(subject = s, status = TravelExpenseReportStatus.REQUESTED)
            val waiver = sepa.executedReport(subject = s, payout = TravelExpensePayoutChoice.DONATION_WAIVER)
            val nothing = sepa.executedReport(subject = s, totalCents = 5_000, advanceCents = 5_000)
            val noIban = sepa.executedReport(subject = s, iban = null)
            val mismatch = sepa.executedReport(subject = s, bookedBankCreditCents = 1)
            val ok = sepa.executedReport(subject = s)
            val all = listOf(notBooked, waiver, nothing, noIban, mismatch, ok)

            val e = failureOf { exportOf(all) }

            e.kind shouldBe SepaFailureKind.CONFLICT
            e.code shouldBe "reports_not_exportable"
            e.blockers shouldBe
                mapOf(
                    notBooked.toString() to TravelExpenseSepaBlocker.NOT_BOOKED,
                    waiver.toString() to TravelExpenseSepaBlocker.NOT_PAYOUT,
                    nothing.toString() to TravelExpenseSepaBlocker.NOTHING_PAYABLE,
                    noIban.toString() to TravelExpenseSepaBlocker.NO_REFUND_IBAN,
                    mismatch.toString() to TravelExpenseSepaBlocker.AMOUNT_MISMATCH_WITH_BOOKING,
                )
            all.forEach { sepa.report(it)[TravelExpenseReportTable.sepaExportedAt] shouldBe null }
        }

        test("a legacy report with an IBAN added later is refused by the export (paid outside Lapis)") {
            val legacy = sepa.executedReport(subject = subject("legacy"), paidOutsideLapis = true)
            val e = failureOf { exportOf(listOf(legacy)) }
            e.code shouldBe "reports_not_exportable"
            e.blockers shouldBe mapOf(legacy.toString() to TravelExpenseSepaBlocker.PAID_OUTSIDE_LAPIS)
            sepa.report(legacy)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
        }

        test("four eyes: subject and requester are blocked, the person who only approved may export") {
            val own = sepa.executedReport(subject = board.memberId)
            val requestedByActor = sepa.executedReport(subject = subject("fe"), requester = admin.memberId)
            val foreign = sepa.executedReport(subject = subject("fe2"))
            failureOf { exportOf(listOf(own)) }.blockers shouldBe mapOf(own.toString() to TravelExpenseSepaBlocker.OWN_REPORT)
            failureOf { exportOf(listOf(requestedByActor), actor = admin) }.blockers shouldBe
                mapOf(requestedByActor.toString() to TravelExpenseSepaBlocker.OWN_REPORT)
            // An own report inside a larger selection refuses the whole selection; nothing is filtered silently.
            failureOf { exportOf(listOf(own, foreign)) }.blockers shouldBe mapOf(own.toString() to TravelExpenseSepaBlocker.OWN_REPORT)
            sepa.report(foreign)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
            exportOf(listOf(foreign)).count shouldBe 1
        }

        test("a second export of the same report is ALREADY_EXPORTED and creates no batch") {
            val r = sepa.executedReport(subject = subject("dbl"))
            exportOf(listOf(r))
            val before = sepa.batchCount()
            failureOf { exportOf(listOf(r)) }.blockers shouldBe mapOf(r.toString() to TravelExpenseSepaBlocker.ALREADY_EXPORTED)
            sepa.batchCount() shouldBe before
        }

        test("atomicity: one bad report in the selection leaves every other report unmarked and creates no batch") {
            val good = sepa.executedReport(subject = subject("at1"))
            val bad = sepa.executedReport(subject = subject("at2"), iban = null)
            val before = sepa.batchCount()
            failureOf { exportOf(listOf(good, bad)) }.code shouldBe "reports_not_exportable"
            sepa.report(good)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
            sepa.batchCount() shouldBe before
        }

        test("re-export: needs an earlier export, a 10+ character reason without an account number, and counts up with an -R suffix") {
            val r = sepa.executedReport(subject = subject("re"))

            fun reexport(
                reason: String,
                actor: network.lapis.cloud.server.security.CurrentMember = board,
            ) = TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                export.reexport(actor = actor, reportId = r, reason = reason, debtorBankAccountId = debtorAccount, executionDate = TOMORROW)
            }
            failureOf { reexport("Datei wurde von der Bank abgelehnt") }.code shouldBe "not_exported_yet"
            val first = exportOf(listOf(r))
            failureOf { reexport("zu kurz") }.code shouldBe "reason_length_invalid"
            failureOf { reexport("Neue Datei fuer DE02 1203 0000 0000 2020 51 bitte") }.code shouldBe "reason_contains_account_number"
            failureOf { reexport("Datei wurde von der Bank abgelehnt", actor = plainMember) }.kind shouldBe SepaFailureKind.FORBIDDEN

            val second = reexport("  Datei wurde von der Bank abgelehnt  ")

            second.batchId shouldNotBe first.batchId
            val row = sepa.report(r)
            row[TravelExpenseReportTable.sepaReexportCount] shouldBe 1
            row[TravelExpenseReportTable.sepaExportBatchId] shouldBe second.batchId
            transaction {
                val batch = TravelExpenseSepaBatchTable.selectAll().where { TravelExpenseSepaBatchTable.id eq second.batchId }.single()
                batch[TravelExpenseSepaBatchTable.isReexport] shouldBe true
                batch[TravelExpenseSepaBatchTable.reason] shouldBe "Datei wurde von der Bank abgelehnt"
                // The old item stays: two items for the report, two distinct EndToEndIds.
                val ids =
                    TravelExpenseSepaBatchItemTable.selectAll().where { TravelExpenseSepaBatchItemTable.reportId eq r }.map {
                        it[TravelExpenseSepaBatchItemTable.endToEndId]
                    }
                ids.size shouldBe 2
                ids.toSet().size shouldBe 2
                ids.count { it.endsWith("-R1") } shouldBe 1
            }
            val doc = parse(second.xml)
            xpath(doc, "//p:CdtTrfTxInf/p:PmtId/p:EndToEndId") shouldEndWith "-R1"
        }

        test("setup problems are named, not generic: missing debtor account, unusable debtor IBAN, blank organization name, missing key") {
            val r = sepa.executedReport(subject = subject("setup"))
            failureOf { exportOf(listOf(r), debtor = Uuid.random()) }.code shouldBe "debtor_account_missing"

            val badIban = sepa.bankAccount(createdBy = admin.memberId, iban = "DE00000000000000000000", label = "Kaputt")
            failureOf { exportOf(listOf(r), debtor = badIban) }.code shouldBe "debtor_account_invalid"

            sepa.setOrganizationName(creditorName = null, name = "")
            failureOf { exportOf(listOf(r)) }.code shouldBe "organization_name_missing"
            sepa.setOrganizationName(creditorName = ORGANIZATION_TEST_NAME)

            val keyless = TravelExpenseSepaExport(null)
            val e =
                shouldThrow<TravelExpenseSepaException> {
                    TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                        keyless.export(actor = board, reportIds = listOf(r), debtorBankAccountId = debtorAccount, executionDate = TOMORROW)
                    }
                }
            e.code shouldBe "encryption_key_missing"
            sepa.report(r)[TravelExpenseReportTable.sepaExportedAt] shouldBe null
        }

        test("without an explicit account the default bank account is used") {
            val r = sepa.executedReport(subject = subject("def"))
            val previous = sepa.clearDefaultBankAccounts()
            try {
                failureOf { exportOf(listOf(r), debtor = null) }.code shouldBe "debtor_account_missing"
                val other = sepa.bankAccount(createdBy = admin.memberId, iban = MEMBER_IBAN_2, label = "Zweitkonto", bic = null)
                sepa.makeDefault(other)
                val result = exportOf(listOf(r), debtor = null)
                val doc = parse(result.xml)
                xpath(doc, "//p:PmtInf/p:DbtrAcct/p:Id/p:IBAN") shouldBe MEMBER_IBAN_2
                // No BIC on the account: the mandatory DbtrAgt carries the NOTPROVIDED placeholder.
                xpath(doc, "//p:PmtInf/p:DbtrAgt/p:FinInstnId/p:Othr/p:Id") shouldBe "NOTPROVIDED"
            } finally {
                sepa.restoreDefaultBankAccounts(previous)
            }
        }

        test("execution date: past, weekend, TARGET2 holiday and more than 60 days ahead are refused, nothing is moved silently") {
            val r = sepa.executedReport(subject = subject("date"))
            failureOf { exportOf(listOf(r), date = LocalDate(2026, 10, 12)) }.code shouldBe "execution_date_past"
            failureOf { exportOf(listOf(r), date = LocalDate(2026, 10, 17)) }.code shouldBe "execution_date_not_target2_day"
            failureOf { exportOf(listOf(r), date = LocalDate(2026, 12, 14)) }.code shouldBe "execution_date_too_far"
            // Today itself is allowed (a weekday), 60 days ahead as well.
            exportOf(listOf(r), date = TODAY).count shouldBe 1
        }

        test("Good Friday is refused with a clock where it is within range") {
            val r = sepa.executedReport(subject = subject("gf"))
            val e =
                shouldThrow<TravelExpenseSepaException> {
                    TimeTestSupport.withServerClock(instant = "2027-03-20T10:00:00Z") {
                        export.export(
                            actor = board,
                            reportIds = listOf(r),
                            debtorBankAccountId = debtorAccount,
                            executionDate = LocalDate(2027, 3, 26),
                        )
                    }
                }
            e.code shouldBe "execution_date_not_target2_day"
        }

        test("limits: empty, duplicate and more than 500 ids are refused up front") {
            val r = sepa.executedReport(subject = subject("lim"))
            failureOf { exportOf(emptyList()) }.code shouldBe "no_reports_selected"
            failureOf { exportOf(listOf(r, r)) }.code shouldBe "duplicate_reports"
            failureOf { exportOf((1..501).map { Uuid.random() }) }.code shouldBe "too_many_transactions"
        }

        test("the default execution date is the next TARGET2 day after today") {
            TimeTestSupport.withServerClock(instant = PINNED_NOW) { TravelExpenseSepaExport.defaultExecutionDate() } shouldBe TOMORROW
            // Friday evening -> Monday.
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") { TravelExpenseSepaExport.defaultExecutionDate() } shouldBe
                LocalDate(2026, 10, 19)
        }

        test("a bank account that was used for a SEPA file cannot be deleted") {
            val r = sepa.executedReport(subject = subject("del"))
            val account = sepa.bankAccount(createdBy = admin.memberId, iban = MEMBER_IBAN_1, label = "Wegwerf", bic = null)
            exportOf(listOf(r), debtor = account)
            val e =
                shouldThrow<ConflictException> {
                    BankAccountStore.delete(bankAccountId = account, actorMemberId = admin.memberId, actorRole = AccountRole.ADMIN)
                }
            e.message shouldContain "SEPA"
            transaction { BankAccountTable.selectAll().where { BankAccountTable.id eq account }.count() } shouldBe 1
        }

        test("the candidate blockers: exported and exportable reports are told apart, an own report is OWN_REPORT") {
            val own = sepa.executedReport(subject = board.memberId)
            val free = sepa.executedReport(subject = subject("cand"))
            val rows =
                transaction {
                    val list = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id inList listOf(own, free) }.toList()
                    export.blockersFor(rows = list, actor = board)
                }
            rows[own] shouldBe TravelExpenseSepaBlocker.OWN_REPORT
            rows[free] shouldBe null
            TravelExpenseSepaReference.of(free.toString()) shouldStartWith "RK-"
        }

        test("two parallel exports of the same report: exactly one wins, the other sees ALREADY_EXPORTED") {
            val r = sepa.executedReport(subject = subject("race"))
            val batchesBefore = sepa.batchCount()
            // ONE clock pin around all threads (the pin is global state; per-thread pins would restore each other's value).
            val outcomes =
                TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                    coroutineScope {
                        (1..4)
                            .map {
                                async(Dispatchers.IO) {
                                    runCatching {
                                        export.export(
                                            actor = board,
                                            reportIds = listOf(r),
                                            debtorBankAccountId = debtorAccount,
                                            executionDate = TOMORROW,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                }
            outcomes.count { it.isSuccess } shouldBe 1
            outcomes
                .filter { it.isFailure }
                .map { (it.exceptionOrNull() as TravelExpenseSepaException).blockers[r.toString()] }
                .toSet() shouldBe setOf(TravelExpenseSepaBlocker.ALREADY_EXPORTED)
            sepa.batchCount() shouldBe batchesBefore + 1
        }

        test("a later change of the payment bank account setting does not make an existing booking look wrong") {
            val r = sepa.executedReport(subject = subject("bankswitch"))
            sepa.switchPaymentBankAccount(sepa.ledgerAccount(LedgerAccountType.ASSET))
            try {
                exportOf(listOf(r)).count shouldBe 1
            } finally {
                sepa.switchPaymentBankAccount(sepa.bankLedgerAccountId)
            }
        }

        test("an advance credit on a second account is not counted as money that left the bank") {
            val advanceAccount = sepa.ledgerAccount(LedgerAccountType.ASSET)
            val ok = sepa.executedReport(subject = subject("advok"), advanceCreditAccount = advanceAccount)
            val wrong = sepa.executedReport(subject = subject("advbad"), bookedBankCreditCents = 1, advanceCreditAccount = advanceAccount)
            failureOf { exportOf(listOf(ok, wrong)) }.blockers shouldBe
                mapOf(wrong.toString() to TravelExpenseSepaBlocker.AMOUNT_MISMATCH_WITH_BOOKING)
            exportOf(listOf(ok)).count shouldBe 1
        }

        test("the re-export reason check finds a real IBAN only: return codes followed by words are fine") {
            listOf(
                "Rueckgabe AC04 Konto erloschen",
                "Rückgabe AC04 Konto erloschen",
                "AC01 Kontonummer falsch, neue Datei",
                "Rueckgabe MD07 Kontoinhaber verstorben",
                "Bank meldet AM04 Deckung fehlt Ueberweisung",
            ).forEach { TravelExpenseSepaExport.containsIban(it) shouldBe false }
            listOf(
                "Neue Datei fuer DE02 1203 0000 0000 2020 51 bitte",
                "Neue Datei fuer DE02120300000000202051 bitte",
                "AC04 DE02 1203 0000 0000 2020 51",
                "iban at61 1904 3002 3457 3201 neu",
            ).forEach { TravelExpenseSepaExport.containsIban(it) shouldBe true }
        }

        test("a booking without any CREDIT on the payment bank account does not satisfy the booking check") {
            val r = sepa.executedReport(subject = subject("nocredit"), bookedBankCreditCents = 0)
            failureOf { exportOf(listOf(r)) }.blockers shouldBe mapOf(r.toString() to TravelExpenseSepaBlocker.AMOUNT_MISMATCH_WITH_BOOKING)
        }

        test("Christmas Day is refused, the next open day is accepted") {
            val r = sepa.executedReport(subject = subject("xmas"))
            val clock = "2026-12-20T10:00:00Z"
            val e =
                shouldThrow<TravelExpenseSepaException> {
                    TimeTestSupport.withServerClock(instant = clock) {
                        export.export(
                            actor = board,
                            reportIds = listOf(r),
                            debtorBankAccountId = debtorAccount,
                            executionDate = LocalDate(2026, 12, 25),
                        )
                    }
                }
            e.code shouldBe "execution_date_not_target2_day"
            TimeTestSupport.withServerClock(instant = clock) {
                export
                    .export(
                        actor = board,
                        reportIds = listOf(r),
                        debtorBankAccountId = debtorAccount,
                        executionDate = LocalDate(2026, 12, 28),
                    ).count
            } shouldBe 1
        }

        test("a ciphertext copied onto another report cannot be opened (AAD bound to the report)") {
            val a = sepa.executedReport(subject = subject("aad1"), iban = MEMBER_IBAN_1)
            val b = sepa.executedReport(subject = subject("aad2"), iban = MEMBER_IBAN_2)
            val cipherA = sepa.report(a)[TravelExpenseReportTable.refundIbanCiphertext]
            transaction {
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq b }) { it[refundIbanCiphertext] = cipherA }
            }
            failureOf { exportOf(listOf(b)) }.code shouldBe "refund_iban_unreadable"
        }
    })

class TravelExpenseSepaExportTest : TravelExpenseSepaExportScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TravelExpenseSepaExportPostgresTest : TravelExpenseSepaExportScenarios(TestDatabase.Postgres())
