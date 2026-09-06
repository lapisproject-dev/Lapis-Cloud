package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** `BankStatementImportTable` carries no by-uploader query helper of its own outside `BankStatementStore` -- pulling that internal object in here for a one-column lookup would be more than this test file needs. */
private fun JdbcTransaction.importIdsUploadedBy(uploadedBy: Uuid): List<Uuid> =
    BankStatementImportTable
        .selectAll()
        .where { BankStatementImportTable.uploadedBy eq uploadedBy }
        .map { it[BankStatementImportTable.id] }

private const val SPARKASSE_HEADER =
    "Auftragskonto;Buchungstag;Valutadatum;Buchungstext;Verwendungszweck;Beguenstigter/Zahlungspflichtiger;" +
        "Kontonummer/IBAN;BIC (SWIFT-Code);Betrag;Waehrung;Kundenreferenz (End-to-End)"

/**
 * Review fix (MEDIUM, "Fehlende Testabdeckung", Runde-2/3-Fund #9): before this file,
 * [registerBankStatementRoutes] -- the ONLY HTTP boundary in this codebase that can post real money
 * into the general ledger via a raw file upload -- had ZERO test coverage, unlike every comparable
 * booking-adjacent route surface (`PspWebhookRoutesTest`, `SepaRoutesTest`, `DunningRoutesTest`).
 * Covers exactly the guards that route's own class KDoc "Security checklist" lists: the TREASURER/
 * ADMIN role gate (checked BEFORE `receiveMultipart()`), the streaming upload-size cap, the rate
 * limiter, and the 409/422 status mapping. Own throwaway `routing {}` + a small-limit
 * [FederationInboxRateLimiter] (same "own small-limit instance, not the production one" idiom
 * [PspWebhookRoutesTest]'s own rate-limit test establishes) rather than the full `Application
 * .module()` -- this route needs no other app-wide wiring, and a dedicated limiter keeps the 429
 * test fast (no need to fire the real 10-requests/minute production limit 11 times).
 */
class BankStatementRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdImportIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                if (createdImportIds.isNotEmpty()) {
                    BankStatementLineTable.deleteWhere { BankStatementLineTable.importId inList createdImportIds }
                    BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList createdImportIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // A successful import writes an AuditLogRecorder entry with actor_member_id =
                    // the uploader (BankStatementImportService.import's own BANK_STATEMENT_IMPORT
                    // record) -- null it out FIRST, same precedent every other bank-statement test
                    // file in this package (BankStatementImportServiceTest/BankStatementStoreTest)
                    // already establishes, or the member delete below hits
                    // fk_audit_log_entry_actor_member_id.
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                createdMemberIds.forEach {
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
            createdImportIds.clear()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Bank-Route-Testmitglied"
                    it[email] = "bank-route-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun installExceptionHandlers(config: StatusPagesConfig) {
            config.exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
        }

        fun multipartBody(
            bytes: ByteArray,
            fileName: String = "kontoauszug.csv",
        ): MultiPartFormDataContent =
            MultiPartFormDataContent(
                formData {
                    // Ktor's own documented file-upload idiom: `name="file"` is derived from the
                    // `append` key automatically -- the extra header supplies ONLY `filename=`, never
                    // a duplicate/competing `name=` (that would malform the Content-Disposition
                    // header the server's `receiveMultipart()` parses `part.originalFileName` from).
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            )

        fun validCsvBytes(amount: String = "48,00"): ByteArray =
            (
                listOf(SPARKASSE_HEADER) +
                    listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Unbekannte Zahlung;Unbekannter Absender;;;$amount;EUR;")
            ).joinToString("\r\n").toByteArray()

        fun rememberImportIdsFor(memberId: Uuid) {
            transaction { importIdsUploadedBy(memberId) }.forEach { createdImportIds += it }
        }

        test("role gate: MEMBER and BOARD are rejected with 403 BEFORE the body is read -- TREASURER succeeds") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installExceptionHandlers(this) }
                    routing {
                        registerBankStatementRoutes(
                            secretBox = null,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                        )
                    }
                }
                val member = createMember(AccountRole.MEMBER)
                val board = createMember(AccountRole.BOARD)
                val treasurer = createMember(AccountRole.TREASURER)

                // Deliberately garbage bytes -- if the role gate ran AFTER body parsing, this would
                // still fail, but for a format-detection reason (422), not a role reason (403). Using
                // unparseable bytes here proves the 403 happens strictly before any parsing attempt.
                val garbage = "not a bank statement at all".toByteArray()

                client
                    .post("/api/bank-statements/import") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(garbage))
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/api/bank-statements/import") {
                        header("X-Member-Id", board.toString())
                        setBody(multipartBody(garbage))
                    }.status shouldBe HttpStatusCode.Forbidden

                val success =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(validCsvBytes()))
                    }
                success.status shouldBe HttpStatusCode.OK
                rememberImportIdsFor(treasurer)
            }
        }

        test("upload size cap: a file over MAX_UPLOAD_BYTES is rejected with 413 while streaming") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installExceptionHandlers(this) }
                    routing {
                        registerBankStatementRoutes(
                            secretBox = null,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                        )
                    }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val oversized = ByteArray((5L * 1024 * 1024 + 1).toInt())

                val response =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(oversized))
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge

                // Nothing must have been persisted for a rejected-too-large upload.
                transaction { importIdsUploadedBy(treasurer) } shouldBe emptyList()
            }
        }

        test("rate limiter: exceeding the configured window returns 429") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installExceptionHandlers(this) }
                    routing {
                        registerBankStatementRoutes(
                            secretBox = null,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val treasurer = createMember(AccountRole.TREASURER)

                // A distinct amount (hence distinct file digest) from every other test in this file
                // -- the 409 "already imported" check is a SEPARATE guard from the one under test
                // here, and a coincidental digest collision must never be what actually triggers the
                // second request's rejection.
                val first =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(validCsvBytes(amount = "21,00")))
                    }
                first.status shouldBe HttpStatusCode.OK
                rememberImportIdsFor(treasurer)

                val second =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(validCsvBytes(amount = "22,00")))
                    }
                second.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("409: uploading the identical bytes twice is rejected as already imported, referencing the file digest") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installExceptionHandlers(this) }
                    routing {
                        registerBankStatementRoutes(
                            secretBox = null,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                        )
                    }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                // A distinct amount from every other test in this file -- see the rate-limiter
                // test's own comment on why a coincidental cross-test digest collision must never be
                // what actually triggers a 409.
                val bytes = validCsvBytes(amount = "77,00")

                val first =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(bytes))
                    }
                first.status shouldBe HttpStatusCode.OK
                rememberImportIdsFor(treasurer)

                val second =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(bytes))
                    }
                second.status shouldBe HttpStatusCode.Conflict
                second.bodyAsText() shouldContain "bereits importiert"
            }
        }

        test("422: an unrecognized format is rejected, echoing the observed header fields for diagnosis") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installExceptionHandlers(this) }
                    routing {
                        registerBankStatementRoutes(
                            secretBox = null,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                        )
                    }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val garbage = "Spalte A,Spalte B,Spalte C\nx,y,z".toByteArray()

                val response =
                    client.post("/api/bank-statements/import") {
                        header("X-Member-Id", treasurer.toString())
                        setBody(multipartBody(garbage))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                val body = response.bodyAsText()
                body shouldContain "Format nicht erkannt"
                body shouldContain "Beobachtete Spalten"
            }
        }
    })
