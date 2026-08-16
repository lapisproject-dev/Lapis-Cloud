package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * V0.11.0 -- proves B7's "yes, a FRIEND gets the full self-service DSGVO rights, and it already
 * does with no code change" claim end to end: [DsgvoService.exportManifest]/
 * [DsgvoService.requestErasure] gate on subject-or-ADMIN only, with NO membership-status check, so
 * a [MemberStatus.FRIEND] can export and request erasure of its own data exactly like any other
 * member. Also proves [RegistrationPersonalData]'s FRIEND-specific tables
 * ([FriendTermsAcknowledgmentTable]/[FriendEmailVerificationTokenTable]) are actually covered --
 * both are hard-deleted, not anonymized, on erasure. Mirrors [DsgvoServiceTest]'s house style.
 */
class FriendPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // dsgvo_audit_log/erasure_request rows are NOT hash-chained (unlike
                // audit_log_entry, see E2eSupport KDoc) -- deleted outright, same recipe
                // DsgvoServiceTest's own cleanUpDsgvoTestData establishes.
                network.lapis.cloud.server.db.generated.DsgvoAuditLogTable.deleteWhere {
                    network.lapis.cloud.server.db.generated.DsgvoAuditLogTable.subjectMemberId inList createdMemberIds
                }
                network.lapis.cloud.server.db.generated.ErasureRequestTable.deleteWhere {
                    network.lapis.cloud.server.db.generated.ErasureRequestTable.subjectMemberId inList createdMemberIds
                }
                FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList createdMemberIds }
                FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createFriendMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DSGVO Testfreund"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.FRIEND
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
                FriendTermsAcknowledgmentTable.insert {
                    it[FriendTermsAcknowledgmentTable.id] = Uuid.random()
                    it[FriendTermsAcknowledgmentTable.memberId] = id
                    it[acknowledgedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[termsVersion] = "test-version"
                    it[termsSha256] = "0".repeat(64)
                }
            }
            createdMemberIds += id
            FriendEmailVerificationTokenStore.createToken(id)
            return id
        }

        test("A FRIEND can export its own data -- registration section includes the friendTermsAcknowledgments it wrote") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        get("/test/export-manifest/{memberId}") {
                            val service = DsgvoService(call)
                            val manifest = service.exportManifest(call.parameters["memberId"]!!)
                            call.respondText(manifest.sectionCounts.entries.joinToString(",") { "${it.key}=${it.value}" })
                        }
                    }
                }
                val friend = createFriendMember("dsgvo-friend-export@example.org")

                // A DIFFERENT, unrelated member must NOT be able to export this FRIEND's data.
                val strangerAttempt =
                    client.get(
                        "/test/export-manifest/$friend",
                    ) { header("X-Member-Id", createFriendMember("dsgvo-friend-stranger@example.org").toString()) }
                strangerAttempt.status shouldBe HttpStatusCode.Forbidden

                val selfExport = client.get("/test/export-manifest/$friend") { header("X-Member-Id", friend.toString()) }
                selfExport.status shouldBe HttpStatusCode.OK
                val counts = selfExport.bodyAsText().split(",").associate { it.substringBefore("=") to it.substringAfter("=").toInt() }
                (counts["foundation"] ?: 0) shouldBe 1
                (counts["registration"] ?: 0) shouldBe 1 // friendTermsAcknowledgments section is non-empty

                val adminExport = client.get("/test/export-manifest/$friend") { header("X-Member-Id", ADMIN_ID) }
                adminExport.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "A FRIEND can request erasure of its own data; ADMIN approves+executes; friend_terms_acknowledgment and friend_email_verification_token rows are HARD-DELETED, member row anonymized",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/request-erasure/{subjectId}") {
                            val service = DsgvoService(call)
                            val request =
                                service.requestErasure(
                                    subjectMemberId = call.parameters["subjectId"]!!,
                                    reason = "FRIEND self-service erasure test",
                                    mode = ErasureMode.ANONYMIZE,
                                )
                            call.respondText(request.id)
                        }
                        post("/test/decide/{requestId}/{approve}") {
                            val service = DsgvoService(call)
                            val request =
                                service.decideErasure(
                                    requestId = call.parameters["requestId"]!!,
                                    approve = call.parameters["approve"]!!.toBoolean(),
                                )
                            call.respondText(request.status.name)
                        }
                        post("/test/execute/{requestId}") {
                            val service = DsgvoService(call)
                            val request = service.executeErasure(call.parameters["requestId"]!!)
                            call.respondText(request.status.name)
                        }
                    }
                }
                val friend = createFriendMember("dsgvo-friend-erasure@example.org")

                val requestId =
                    client.post("/test/request-erasure/$friend") { header("X-Member-Id", friend.toString()) }.bodyAsText()

                val decided = client.post("/test/decide/$requestId/true") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                decided shouldBe ErasureStatus.APPROVED.name

                val executed = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                executed shouldBe ErasureStatus.COMPLETED.name

                transaction {
                    val memberRow = MemberTable.selectAll().where { MemberTable.id eq friend }.single()
                    (memberRow[MemberTable.anonymizedAt] != null) shouldBe true
                    memberRow[MemberTable.friendSince] shouldBe null
                    memberRow[MemberTable.emailVerifiedAt] shouldBe null

                    FriendTermsAcknowledgmentTable.selectAll().where { FriendTermsAcknowledgmentTable.memberId eq friend }.count() shouldBe
                        0L
                    FriendEmailVerificationTokenTable
                        .selectAll()
                        .where { FriendEmailVerificationTokenTable.memberId eq friend }
                        .count() shouldBe 0L
                }
            }
        }
    })
