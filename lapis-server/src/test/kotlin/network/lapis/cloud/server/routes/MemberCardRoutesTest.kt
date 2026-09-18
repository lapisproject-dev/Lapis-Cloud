package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Exercises `POST /api/members/{memberId}/card.pdf` through the real `application { module() }`,
 * same idiom as [MailmergeRoutesTest] -- these are plain Ktor routes, so the real module's
 * StatusPages/routing wiring is exactly what a request goes through.
 *
 * The access-control cases are the point of this file: the IDOR gate (a member must not be able to
 * fetch another member's identity document), the eligibility gate, and the fact that the route is
 * NOT reachable as a GET -- the last one guards the wave's most consequential design decision (see
 * [registerMemberCardRoutes] KDoc) against a future "make it a link again" refactor.
 */
class MemberCardRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    // Every successful download writes an audit entry (see `registerMemberCardRoutes`)
                    // -- same cleanup `AuditLogPersonalDataTest`/`VolunteerAllowanceAuthzTest` do.
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    MemberCardCodeTable.deleteWhere { MemberCardCodeTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
            displayName: String = "Ausweis Testmitglied",
            anonymizedAt: LocalDateTime? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 2, 1)
                    it[membershipTierId] = null
                    it[MemberTable.anonymizedAt] = anonymizedAt
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

        fun activeCodeCount(memberId: Uuid): Long =
            transaction {
                MemberCardCodeTable
                    .selectAll()
                    .where { (MemberCardCodeTable.memberId eq memberId) and (MemberCardCodeTable.revokedAt.isNull()) }
                    .count()
            }

        test("401 unauthenticated, 403 for a foreign member, 200 for the subject") {
            testApplication {
                application { module() }
                val subject = createMember("card-subject-1@example.org")
                val stranger = createMember("card-stranger-1@example.org")

                client.post("/api/members/$subject/card.pdf").status shouldBe HttpStatusCode.Unauthorized

                client
                    .post("/api/members/$subject/card.pdf") { header("X-Member-Id", stranger.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                val own = client.post("/api/members/$subject/card.pdf") { header("X-Member-Id", subject.toString()) }
                own.status shouldBe HttpStatusCode.OK
                own
                    .bodyAsBytes()
                    .take(4)
                    .toByteArray()
                    .decodeToString() shouldBe "%PDF"
            }
        }

        test("BOARD may download any member's card; the route is never reachable as a GET") {
            testApplication {
                application { module() }
                val subject = createMember("card-subject-2@example.org")
                val board = createMember("card-board-2@example.org", role = AccountRole.BOARD)

                client
                    .post("/api/members/$subject/card.pdf") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // A GET must NOT produce a card -- it would be a credential-rotating GET. It falls
                // through this route entirely (staticFiles' catch-all answers instead), so the only
                // assertion that matters is "not a PDF, not a 200 card".
                val viaGet = client.get("/api/members/$subject/card.pdf") { header("X-Member-Id", board.toString()) }
                viaGet.status shouldNotBe HttpStatusCode.OK
            }
        }

        test("a first download issues a card code, a second rotates rather than adding one") {
            testApplication {
                application { module() }
                val subject = createMember("card-subject-3@example.org")
                activeCodeCount(subject) shouldBe 0L

                client.post("/api/members/$subject/card.pdf") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK
                activeCodeCount(subject) shouldBe 1L

                client.post("/api/members/$subject/card.pdf") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK
                // Still exactly ONE active code -- the previous one was revoked, not left valid
                // alongside the new one.
                activeCodeCount(subject) shouldBe 1L
                transaction {
                    MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq subject }.count()
                } shouldBe 2L
            }
        }

        test("a first download allocates the member number and names the PDF after it") {
            testApplication {
                application { module() }
                val subject = createMember("card-subject-4@example.org")

                val response = client.post("/api/members/$subject/card.pdf") { header("X-Member-Id", subject.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentDisposition]!! shouldContain "Mitgliedsausweis-M-2026-"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"

                val allocated =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq subject }.single()[MemberTable.memberNumber] }
                allocated!! shouldContain "M-2026-"
            }
        }

        test("409 for a member who is not a member -- a guest has no membership card") {
            testApplication {
                application { module() }
                val guest = createMember("card-guest-5@example.org", status = MemberStatus.GUEST)

                val response = client.post("/api/members/$guest/card.pdf") { header("X-Member-Id", guest.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "Mitgliedsausweis"
                activeCodeCount(guest) shouldBe 0L
            }
        }

        test("409 for an anonymized member even with a still-ACTIVE status -- DSGVO erasure does not touch status") {
            testApplication {
                application { module() }
                // Erasure (FoundationPersonalData.eraseMember) does NOT reset `status`, so a
                // member anonymized without a status change stays ACTIVE -- exactly the row this
                // guard must still refuse.
                val anonymized =
                    createMember(
                        "card-anonymized-7@example.org",
                        status = MemberStatus.ACTIVE,
                        anonymizedAt = LocalDateTime(2026, 3, 1, 0, 0),
                    )
                val board = createMember("card-board-7@example.org", role = AccountRole.BOARD)

                val response =
                    client.post("/api/members/$anonymized/card.pdf") { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "anonymized"
                activeCodeCount(anonymized) shouldBe 0L
            }
        }

        test("400 for a malformed memberId, 404 for an unknown one") {
            testApplication {
                application { module() }
                val board = createMember("card-board-6@example.org", role = AccountRole.BOARD)

                client
                    .post("/api/members/not-a-uuid/card.pdf") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/api/members/${Uuid.random()}/card.pdf") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }
    })
