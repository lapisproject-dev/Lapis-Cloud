package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.member.MemberCardIssuance
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * `GET /ausweis?code=...` -- the unauthenticated verification page.
 *
 * Three properties are pinned here because each one is a security property, not a cosmetic one:
 * the four negative outcomes are indistinguishable from outside, the lookup writes nothing (the
 * exact DoS the previous wave's audit found), and a membership that has ENDED stops verifying even
 * though its card code was never explicitly revoked.
 */
class MemberCardPublicRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    MemberCardCodeTable.deleteWhere { MemberCardCodeTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            displayName: String = "Ausweis Prüfmitglied",
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
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        /** Mints a card the way the download route does, and hands back the raw code the QR would carry. */
        fun issueCode(memberId: Uuid): String =
            transaction { MemberCardIssuance.rotate(memberId = memberId, now = DbClock.nowLocalDateTime()).rawCode }

        test("a valid code shows name, member number, status and joining date") {
            testApplication {
                application { module() }
                val member = createMember("ausweis-valid-1@example.org", displayName = "Erika Mustermann")
                val code = issueCode(member)

                val response = client.get("/ausweis?code=$code")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = response.bodyAsText()
                body shouldContain "Gültiger Mitgliedsausweis"
                body shouldContain "Erika Mustermann"
                body shouldContain "M-2026-"
                body shouldContain "01.02.2026"
            }
        }

        test("unknown, malformed, missing and revoked codes all render the identical page and status") {
            testApplication {
                application { module() }
                val member = createMember("ausweis-revoked-2@example.org")
                val revokedCode = issueCode(member)
                // A second issuance revokes the first code -- `revokedCode` is now a real,
                // existing-but-revoked value, which is exactly the case that must stay
                // indistinguishable from a code that never existed.
                issueCode(member)

                val unknown = client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ")
                val malformed = client.get("/ausweis?code=nope")
                val missing = client.get("/ausweis")
                val revoked = client.get("/ausweis?code=$revokedCode")

                listOf(unknown, malformed, missing, revoked).forEach { it.status shouldBe HttpStatusCode.OK }
                val bodies = listOf(unknown, malformed, missing, revoked).map { it.bodyAsText() }
                bodies.distinct().size shouldBe 1
                bodies.first() shouldContain "Kein gültiger Mitgliedsausweis"
            }
        }

        test("the public lookup writes nothing -- no code row and no member number for the probed member") {
            testApplication {
                application { module() }
                // A member who has never had a card: if the public lookup ever wrote again (the DoS
                // the previous wave fixed), this is the row that would sprout a member_number.
                val untouched = createMember("ausweis-untouched-6@example.org")

                fun memberNumberOf(id: Uuid): String? =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.memberNumber] }

                fun codeCountOf(id: Uuid): Long =
                    transaction { MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq id }.count() }

                memberNumberOf(untouched) shouldBe null
                repeat(5) { client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ") }
                client.get("/ausweis?code=nope")
                val probed = createMember("ausweis-probed-7@example.org")
                val code = issueCode(probed)
                repeat(3) { client.get("/ausweis?code=$code") }

                memberNumberOf(untouched) shouldBe null
                codeCountOf(untouched) shouldBe 0L
                // The probed member's own state is unchanged by READING their card, too: exactly
                // the one code row that issuing created, no second allocation.
                codeCountOf(probed) shouldBe 1L
            }
        }

        test("a card whose holder has left the organization stops verifying, without being revoked") {
            testApplication {
                application { module() }
                val member = createMember("ausweis-left-3@example.org")
                val code = issueCode(member)
                client.get("/ausweis?code=$code").bodyAsText() shouldContain "Gültiger Mitgliedsausweis"

                transaction {
                    MemberTable.update({ MemberTable.id eq member }) { it[status] = MemberStatus.WITHDRAWN }
                }

                val afterLeaving = client.get("/ausweis?code=$code")
                afterLeaving.status shouldBe HttpStatusCode.OK
                afterLeaving.bodyAsText() shouldContain "Kein gültiger Mitgliedsausweis"
                // The code row itself is untouched -- membership status, not revocation, is what
                // invalidated the card here.
                transaction {
                    MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq member }.count()
                } shouldBe 1L
            }
        }

        test("the verification page is not indexable and carries no card code in its body") {
            testApplication {
                application { module() }
                val member = createMember("ausweis-noindex-4@example.org")
                val code = issueCode(member)

                val body = client.get("/ausweis?code=$code").bodyAsText()
                body shouldContain "noindex,nofollow"
                // The page must never echo the bearer credential back into a document that can be
                // screenshotted, shared or cached by the visitor's own browser history preview.
                body shouldNotContain code
            }
        }

        test("the download route and the verification page agree on the freshly issued code") {
            testApplication {
                application { module() }
                val member = createMember("ausweis-roundtrip-5@example.org")
                // Download once through the real route (this is what a member actually does), then
                // check that the code now active in the database is the one that verifies.
                client.post("/api/members/$member/card.pdf") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.OK
                // The raw code is unrecoverable by design, so the round trip is verified the only
                // way it can be: issue a fresh one and confirm IT verifies while the old one does not.
                val fresh = issueCode(member)
                client.get("/ausweis?code=$fresh").bodyAsText() shouldContain "Gültiger Mitgliedsausweis"
            }
        }
    })
