package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Welle V1.9.123 -- who may set which refund account when, and what the server stores and shows. */
class TravelExpenseRefundAccountTest :
    FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)

        beforeSpec {
            DatabaseConfig.connect()
            sepa.configureOrganization()
        }
        afterSpec {
            sepa.cleanUp()
            data.cleanUp()
        }

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseSepaTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.refund(
            member: Uuid,
            report: Uuid,
            iban: String?,
            holder: String? = null,
            extra: String = "",
        ) = post(
            "/test/sepa/refund?id=$report${iban?.let {
                "&iban=" +
                    it.replace(
                        " ",
                        "%20",
                    )
            } ?: ""}${holder?.let { "&holder=" + it.replace(" ", "%20") } ?: ""}$extra",
        ) {
            header("X-Member-Id", member.toString())
        }

        fun ciphertextOf(report: Uuid) = sepa.report(report)[TravelExpenseReportTable.refundIbanCiphertext]

        test("the subject sets the account on a draft: only last4 and holder are visible, the IBAN is sealed and bound to the report") {
            withApp {
                val subject = data.member(label = "refund1", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                val response = client.refund(subject, report, iban = "de02 1203 0000 0000 2020 51", holder = "Erika Mustermann")
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split("|")
                parts[2] shouldBe "2051"
                parts[3] shouldBe "Erika Mustermann"
                parts[4] shouldBe "false"
                parts[5] shouldBe "RK-" +
                    report
                        .toString()
                        .replace("-", "")
                        .take(8)
                        .uppercase()
                val cipher = ciphertextOf(report)!!
                cipher shouldNotContain "DE02"
                SecretBox(SEPA_TEST_KEY).open(sealed = cipher, aad = TravelExpenseRefundAccount.aad(report)) shouldBe
                    "DE02120300000000202051"
            }
        }

        test("without a holder the display name of the subject is stored") {
            withApp {
                val subject = data.member(label = "refund2", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                client.refund(subject, report, iban = "AT611904300234573201").bodyAsText().split("|")[3] shouldBe "Poll-Test refund2"
            }
        }

        test("only the subject names the account -- never the requester (draft or submitted), never a stranger") {
            withApp {
                val subject = data.member(label = "refund3", role = AccountRole.MEMBER)
                val requester = data.member(label = "refund3r", role = AccountRole.BOARD)
                val stranger = data.member(label = "refund3x", role = AccountRole.MEMBER)
                val draft =
                    sepa.executedReport(
                        subject = subject,
                        requester = requester,
                        status = TravelExpenseReportStatus.DRAFT,
                        iban = null,
                    )
                client.refund(requester, draft, iban = "AT611904300234573201", holder = "Erika Mustermann").status shouldBe
                    HttpStatusCode.Forbidden
                client.refund(stranger, draft, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Forbidden
                ciphertextOf(draft) shouldBe null
                client.refund(subject, draft, iban = "AT611904300234573201", holder = "Erika Mustermann").status shouldBe HttpStatusCode.OK

                val submitted =
                    sepa.executedReport(
                        subject = subject,
                        requester = requester,
                        status = TravelExpenseReportStatus.REQUESTED,
                        iban = null,
                    )
                client.refund(requester, submitted, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Forbidden
                client.refund(stranger, submitted, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Forbidden
                ciphertextOf(submitted) shouldBe null
                // The subject names the account after the board filed the report on their behalf; that is flagged for the approver.
                val response = client.refund(subject, submitted, iban = "AT611904300234573201")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[4] shouldBe "true"
            }
        }

        test("a change after submission is flagged and audited without the IBAN; the second change adds no second audit entry") {
            withApp {
                val subject = data.member(label = "refund4", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, iban = "DE02120300000000202051", holder = "Erika Mustermann")
                val auditBefore = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq report }.count() }
                val first = client.refund(subject, report, iban = "AT611904300234573201", holder = "Erika Mustermann")
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText().split("|")[4] shouldBe "true"
                client.refund(subject, report, iban = "DE02120300000000202051", holder = "Erika Mustermann").status shouldBe
                    HttpStatusCode.OK
                val entries = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq report }.toList() }
                (entries.size - auditBefore).toInt() shouldBe 1
                val text = entries.joinToString { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
                listOf("DE02", "AT61", "Mustermann").forEach { text shouldNotContain it }
                text shouldContain "\"refundAccountChanged\":true"
            }
        }

        test("after the SEPA export the subject can still correct the account: flagged, audited, export mark stays") {
            withApp {
                val subject = data.member(label = "refund5", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject)
                val admin = data.member(label = "refund5a", role = AccountRole.ADMIN)
                val bank = sepa.bankAccount(createdBy = admin)
                val export = TravelExpenseSepaExport(SEPA_TEST_KEY)
                val adminActor =
                    network.lapis.cloud.server.security.CurrentMember(
                        memberId = admin,
                        role = AccountRole.ADMIN,
                        status = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE,
                    )
                network.lapis.cloud.server.time.TimeTestSupport.withServerClock(instant = "2026-10-13T10:00:00Z") {
                    export.export(
                        actor = adminActor,
                        reportIds = listOf(report),
                        debtorBankAccountId = bank,
                        executionDate = kotlinx.datetime.LocalDate(2026, 10, 14),
                    )
                }
                val auditBefore = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq report }.count() }
                val exportedAt = sepa.report(report)[TravelExpenseReportTable.sepaExportedAt]
                // The bank returned the credit (AC04): the subject names a new account.
                val response = client.refund(subject, report, iban = "AT611904300234573201", holder = "Erika Mustermann")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[4] shouldBe "true"
                sepa.report(report)[TravelExpenseReportTable.sepaExportedAt] shouldBe exportedAt
                ciphertextOf(report)!!.let {
                    SecretBox(SEPA_TEST_KEY).open(sealed = it, aad = TravelExpenseRefundAccount.aad(report)) shouldBe "AT611904300234573201"
                }
                // A second correction after the export is audited again (unlike the second one before it).
                client.refund(subject, report, iban = "DE02120300000000202051", holder = "Erika Mustermann").status shouldBe
                    HttpStatusCode.OK
                val entries = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq report }.toList() }
                (entries.size - auditBefore).toInt() shouldBe 2
                // The reasoned re-export now uses the new account.
                val reexport =
                    network.lapis.cloud.server.time.TimeTestSupport.withServerClock(instant = "2026-10-13T10:00:00Z") {
                        export.reexport(
                            actor = adminActor,
                            reportId = report,
                            reason = "Rueckgabe AC04 Konto erloschen",
                            debtorBankAccountId = bank,
                            executionDate = kotlinx.datetime.LocalDate(2026, 10, 14),
                        )
                    }
                String(reexport.xml, Charsets.UTF_8) shouldContain "DE02120300000000202051"
                // The requester or a stranger still cannot touch it.
                client.refund(admin, report, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a holder-only update keeps the IBAN, is flagged after submission, and needs an existing account") {
            withApp {
                val subject = data.member(label = "refund11", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, iban = "DE02120300000000202051", holder = "Erika Mustermann")
                val cipherBefore = ciphertextOf(report)
                val response = client.refund(subject, report, iban = null, holder = "Erika Musterfrau")
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split("|")
                parts[2] shouldBe "2051"
                parts[3] shouldBe "Erika Musterfrau"
                parts[4] shouldBe "true"
                ciphertextOf(report) shouldBe cipherBefore

                val draft = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                val refused = client.refund(subject, draft, iban = null, holder = "Erika Musterfrau")
                refused.status shouldBe HttpStatusCode.BadRequest
                refused.bodyAsText() shouldBe "refund_account_missing"
            }
        }

        test("invalid IBANs are refused with a fixed message that never repeats the value") {
            withApp {
                val subject = data.member(label = "refund6", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                val badIbans =
                    listOf("DE02120300000000202052", "US64SVBKUS6S3300958879", "BR1800360305000010009795493C1", "DE12", "nonsense")
                badIbans.forEach { bad ->
                    val response = client.refund(subject, report, iban = bad, holder = "Erika Mustermann")
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldBe "refund_iban_invalid"
                }
                ciphertextOf(report) shouldBe null
            }
        }

        test("clearing works on a draft only") {
            withApp {
                val subject = data.member(label = "refund7", role = AccountRole.MEMBER)
                val draft = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT)
                client.refund(subject, draft, iban = null).status shouldBe HttpStatusCode.OK
                ciphertextOf(draft) shouldBe null
                sepa.report(draft)[TravelExpenseReportTable.refundIbanLast4] shouldBe null
                val submitted = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.REQUESTED)
                client.refund(subject, submitted, iban = null).status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rejected and withdrawn reports no longer accept an account") {
            withApp {
                val subject = data.member(label = "refund8", role = AccountRole.MEMBER)
                listOf(TravelExpenseReportStatus.REJECTED, TravelExpenseReportStatus.WITHDRAWN).forEach { status ->
                    val report = sepa.executedReport(subject = subject, status = status, iban = null)
                    client.refund(subject, report, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("without an encryption key nothing is stored and there is no plaintext fallback") {
            withApp {
                val subject = data.member(label = "refund9", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                val response = client.refund(subject, report, iban = "AT611904300234573201", extra = "&nokey=1")
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "encryption_key_missing"
                ciphertextOf(report) shouldBe null
            }
        }

        test("a holder with control characters or more than 70 characters is refused") {
            withApp {
                val subject = data.member(label = "refund10", role = AccountRole.MEMBER)
                val report = sepa.executedReport(subject = subject, status = TravelExpenseReportStatus.DRAFT, iban = null)
                client.refund(subject, report, iban = "AT611904300234573201", holder = "A".repeat(71)).status shouldBe
                    HttpStatusCode.BadRequest
                client.refund(subject, report, iban = "AT611904300234573201", holder = "Bad%0AName").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("the prefill returns the earlier account to the subject only -- never to a requester, a board member or a stranger") {
            withApp {
                val subject = data.member(label = "prefill1", role = AccountRole.MEMBER)
                val requester = data.member(label = "prefill1r", role = AccountRole.BOARD)
                val stranger = data.member(label = "prefill1x", role = AccountRole.MEMBER)
                sepa.executedReport(subject = subject, iban = "AT611904300234573201", holder = "Erika Mustermann")
                val newDraft =
                    sepa.executedReport(
                        subject = subject,
                        requester = requester,
                        status = TravelExpenseReportStatus.DRAFT,
                        iban = null,
                    )

                client.get("/test/sepa/prefill?id=$newDraft") { header("X-Member-Id", subject.toString()) }.bodyAsText() shouldBe
                    "AT611904300234573201|Erika Mustermann"
                client.get("/test/sepa/prefill?id=$newDraft") { header("X-Member-Id", requester.toString()) }.bodyAsText() shouldBe "-|-"
                client.get("/test/sepa/prefill?id=$newDraft") { header("X-Member-Id", stranger.toString()) }.bodyAsText() shouldBe "-|-"
                client.get("/test/sepa/prefill?id=$newDraft&nokey=1") { header("X-Member-Id", subject.toString()) }.bodyAsText() shouldBe
                    "-|-"
            }
        }

        test("a legacy report (refunded by hand before V98) cannot get an IBAN and is never a SEPA candidate") {
            withApp {
                val subject = data.member(label = "legacy1", role = AccountRole.MEMBER)
                val board = data.member(label = "legacy2", role = AccountRole.BOARD)
                val legacy = sepa.executedReport(subject = subject, iban = null, paidOutsideLapis = true)
                val legacyWithIban = sepa.executedReport(subject = subject, iban = MEMBER_IBAN_1, paidOutsideLapis = true)
                val current = sepa.executedReport(subject = subject, iban = null)

                client.refund(subject, legacy, iban = "AT611904300234573201").status shouldBe HttpStatusCode.Conflict
                ciphertextOf(legacy) shouldBe null

                val body = client.get("/test/sepa/candidates") { header("X-Member-Id", board.toString()) }.bodyAsText()
                val ids = body.split(";").map { it.split("|")[0] }
                ids.contains(legacy.toString()) shouldBe false
                ids.contains(legacyWithIban.toString()) shouldBe false
                // A report that is not legacy is still offered (and blocked only by the missing IBAN).
                ids.contains(current.toString()) shouldBe true
            }
        }

        test("the SEPA service methods are for BOARD/ADMIN only") {
            withApp {
                val plain = data.member(label = "authz1", role = AccountRole.MEMBER)
                val board = data.member(label = "authz2", role = AccountRole.BOARD)
                listOf("candidates", "batches", "context").forEach { path ->
                    client.get("/test/sepa/$path") { header("X-Member-Id", plain.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client.get("/test/sepa/$path") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("candidates carry their blocker, batches list metadata only, the context names the missing setup") {
            withApp {
                val subject = data.member(label = "cand1", role = AccountRole.MEMBER)
                val board = data.member(label = "cand2", role = AccountRole.BOARD)
                val free = sepa.executedReport(subject = subject)
                val noIban = sepa.executedReport(subject = subject, iban = null)
                val own = sepa.executedReport(subject = board)
                val body = client.get("/test/sepa/candidates") { header("X-Member-Id", board.toString()) }.bodyAsText()
                val byId = body.split(";").map { it.split("|") }.associateBy { it[0] }
                byId.getValue(free.toString())[6] shouldBe "-"
                byId.getValue(noIban.toString())[6] shouldBe "NO_REFUND_IBAN"
                byId.getValue(own.toString())[6] shouldBe "OWN_REPORT"
                val context = client.get("/test/sepa/context?nokey=1") { header("X-Member-Id", board.toString()) }.bodyAsText().split("|")
                context[2] shouldContain "ENCRYPTION_KEY"
                context[3] shouldBe "500"
            }
        }
    })
