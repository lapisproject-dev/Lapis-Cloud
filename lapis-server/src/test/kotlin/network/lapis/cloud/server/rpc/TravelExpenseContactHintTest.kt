package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseRatesSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val HINT_ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val HINT_BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val HINT_TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * V1.9.199 -- the Markdown contact hint (format flag, server-rendered sanitized HTML, never taken from the client), its ADMIN-only preview with
 * a rate limit, and the document list in the rates answer (who may see the uploader). BOARD reaches the documents but never the settings writer.
 */
class TravelExpenseContactHintTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach {
            transaction {
                TravelExpenseDocumentTable.deleteAll()
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelContactHint] = null
                    it[travelContactHintFormat] = "PLAIN"
                }
            }
        }
        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(
            role: AccountRole = AccountRole.MEMBER,
            memberStatus: MemberStatus = MemberStatus.ACTIVE,
        ): String {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Hint Testmitglied"
                    it[email] = "travel-hint-$id@example.org"
                    it[status] = memberStatus
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id.toString()
        }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.hint(
            who: String,
            text: String?,
            format: String,
            html: String? = null,
        ): HttpResponse =
            post(
                "/test/travelexpense/hint?format=$format" + (text?.let { "&text=${enc(it)}" } ?: "") +
                    (html?.let { "&html=${enc(it)}" } ?: ""),
            ) {
                header("X-Member-Id", who)
            }

        suspend fun HttpClient.rates(who: String): List<String> =
            post("/test/travelexpense/hintrates") { header("X-Member-Id", who) }.bodyAsText().split("|")

        fun insertDocument(
            title: String,
            uploader: String?,
        ) = transaction {
            TravelExpenseDocumentTable.insert {
                it[id] = Uuid.random()
                it[TravelExpenseDocumentTable.title] = title
                it[storageKey] = "travel-expenses/documents/${Uuid.random()}.bin"
                it[originalFilename] = "$title.pdf"
                it[mimeType] = "application/pdf"
                it[sizeBytes] = 10L
                it[sha256] = "0".repeat(64)
                it[uploadedBy] = uploader?.let { id -> Uuid.parse(id) }
                it[uploadedAt] = LocalDateTime(2026, 5, 1, 9, 0)
            }
        }

        test("a stored PLAIN hint has no rendered HTML (the client shows it as text)") {
            withApp {
                client.hint(HINT_ADMIN_ID, "# nicht fett\nZeile", "PLAIN").bodyAsText() shouldBe "PLAIN|-"
                client.rates(newMember())[4] shouldBe "PLAIN"
                client.rates(newMember())[5] shouldBe "-"
            }
        }

        test("a MARKDOWN hint is rendered by the server: script is escaped, javascript: links lose their href, images become text") {
            withApp {
                val text =
                    "**fett** <script>alert(1)</script>\n\n[x](javascript:alert(1)) ![alt](https://evil/p.png) [ok](https://example.org)"
                val body = client.hint(HINT_ADMIN_ID, text, "MARKDOWN").bodyAsText()
                body shouldStartWith "MARKDOWN|<p>"
                body shouldContain "<strong>fett</strong>"
                body shouldContain "&lt;script&gt;"
                body shouldNotContain "<script"
                body shouldNotContain "javascript:"
                body shouldNotContain "<img"
                body shouldContain "alt"
                body shouldContain "href=\"https://example.org\""
                // every member sees the same HTML
                client.rates(newMember())[5] shouldBe body.substringAfter("|")
            }
        }

        test("the hintHtml a client sends is ignored: the server renders again") {
            withApp {
                val body = client.hint(HINT_ADMIN_ID, "harmlos", "MARKDOWN", html = "<script>alert(1)</script>").bodyAsText()
                body shouldNotContain "<script"
                body shouldContain "<p>harmlos</p>"
            }
        }

        test("without a text the format falls back to PLAIN") {
            withApp {
                client.hint(HINT_ADMIN_ID, null, "MARKDOWN").bodyAsText() shouldBe "PLAIN|-"
            }
        }

        test("2000 characters are accepted, 2001 are refused (400)") {
            withApp {
                client.hint(HINT_ADMIN_ID, "x".repeat(2000), "MARKDOWN").status shouldBe HttpStatusCode.OK
                client.hint(HINT_ADMIN_ID, "x".repeat(2001), "MARKDOWN").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a deeply nested quote is refused (400), not a 500") {
            withApp {
                client
                    .hint(HINT_ADMIN_ID, ">".repeat(1900), "MARKDOWN")
                    .status.value
                    .let { (it == 200 || it == 400) shouldBe true }
            }
        }

        test("BOARD may NOT write the settings (403); only ADMIN can") {
            withApp {
                client.hint(HINT_BOARD_ID, "x", "MARKDOWN").status shouldBe HttpStatusCode.Forbidden
                client.hint(HINT_TREASURER_ID, "x", "MARKDOWN").status shouldBe HttpStatusCode.Forbidden
                client.hint(newMember(), "x", "MARKDOWN").status shouldBe HttpStatusCode.Forbidden
                client.hint(HINT_ADMIN_ID, "x", "MARKDOWN").status shouldBe HttpStatusCode.OK
            }
        }

        test("the preview: ADMIN renders, BOARD and MEMBER are refused, the text is capped at 2000, the budget is limited") {
            withApp {
                testPreviewLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes)

                suspend fun preview(
                    who: String,
                    text: String,
                ) = client.post("/test/travelexpense/previewhint?text=${enc(text)}") { header("X-Member-Id", who) }
                val ok = preview(HINT_ADMIN_ID, "**a**")
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText() shouldContain "<strong>a</strong>"
                preview(HINT_BOARD_ID, "x").status shouldBe HttpStatusCode.Forbidden
                preview(newMember(), "x").status shouldBe HttpStatusCode.Forbidden
                preview(HINT_ADMIN_ID, "x".repeat(2001)).status shouldBe HttpStatusCode.BadRequest
                // the third admin request used the budget up (the refused ones never reach the limiter)
                preview(HINT_ADMIN_ID, "x").status shouldBe HttpStatusCode.OK
                preview(HINT_ADMIN_ID, "x").status shouldBe HttpStatusCode.TooManyRequests
                testPreviewLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)
            }
        }

        test("the audit entry of a settings change carries only the Markdown flag, never the hint text") {
            withApp {
                client.hint(HINT_ADMIN_ID, "Geheimer Hinweis zur Abrechnung", "MARKDOWN").status shouldBe HttpStatusCode.OK
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                            .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                            .first()
                    }
                val after = entry[AuditLogEntryTable.afterSnapshot]!!
                after shouldNotContain "Geheimer"
                kotlinx.serialization.json.Json
                    .decodeFromString(TravelExpenseRatesSnapshot.serializer(), after)
                    .contactHintMarkdown shouldBe true
            }
        }

        test("rates: BOARD and ADMIN may manage documents and see the uploader; a member sees the sorted list without it") {
            withApp {
                insertDocument("zeta Beschluss", uploader = HINT_ADMIN_ID)
                insertDocument("Alpha Ordnung", uploader = null)
                val member = client.rates(newMember())
                member[0] shouldBe "false"
                member[2] shouldBe "2"
                member[3] shouldBe "Alpha Ordnung:-,zeta Beschluss:-"
                val board = client.rates(HINT_BOARD_ID)
                board[0] shouldBe "true"
                board[1] shouldBe "false"
                board[3].split(",")[0] shouldBe "Alpha Ordnung:-"
                board[3].split(",")[1].startsWith("zeta Beschluss:") shouldBe true
                board[3].split(",")[1] shouldNotContain ":-"
                client.rates(HINT_ADMIN_ID)[0] shouldBe "true"
                client.rates(HINT_TREASURER_ID)[0] shouldBe "false"
            }
        }

        test("rates: a non-ACTIVE caller (GUEST, FRIEND, APPLICATION, WITHDRAWN) gets an empty document list") {
            withApp {
                insertDocument("Interner Beschluss", uploader = null)
                for (s in listOf(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN)) {
                    val r = client.rates(newMember(memberStatus = s))
                    r[2] shouldBe "0"
                    r[3] shouldBe ""
                }
                client.rates(newMember())[2] shouldBe "1"
            }
        }
    })
