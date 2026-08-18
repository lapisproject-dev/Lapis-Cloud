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
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.rpc.SocialNetworkService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Proves [SocialNetworkPersonalData] (Blocker-Punkt #1 aus dem Implementierungsplan) is actually
 * wired end to end via [DsgvoService]: `PersonalDataCoverageTest` alone only proves `social_post`
 * is COVERED, not that export/erasure behave correctly -- this test exercises the real self-service
 * flow, mirroring [FriendPersonalDataTest]'s house style.
 */
class SocialNetworkPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPostIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdPostIds.isNotEmpty()) {
                    SocialPostTable.deleteWhere { SocialPostTable.id inList createdPostIds }
                }
                DsgvoAuditLogTable.deleteWhere { DsgvoAuditLogTable.subjectMemberId inList createdMemberIds }
                ErasureRequestTable.deleteWhere { ErasureRequestTable.subjectMemberId inList createdMemberIds }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DSGVO Social Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
                LtrLedgerEntryTable.insert {
                    it[LtrLedgerEntryTable.id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = id
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal("5.00")
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
            createdMemberIds += id
            return id
        }

        test("A member's own Social Post appears in their DSGVO export under the social_network section") {
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
                        post("/test/create-post") {
                            val service =
                                SocialNetworkService(
                                    call = call,
                                    createRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    readRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    boostRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                )
                            val p =
                                service.createPost(
                                    SocialPostInput(
                                        content = "DSGVO-Export-Test",
                                        visibility = SocialPostVisibility.PUBLIC,
                                        initialWeightLtr = BigDecimal("1.00"),
                                    ),
                                )
                            call.respondText(p.id)
                        }
                        get("/test/export-manifest/{memberId}") {
                            val service = DsgvoService(call)
                            val manifest = service.exportManifest(call.parameters["memberId"]!!)
                            call.respondText(manifest.sectionCounts.entries.joinToString(",") { "${it.key}=${it.value}" })
                        }
                    }
                }
                val member = createTestMember("dsgvo-social-export@example.org")
                val postId = client.post("/test/create-post") { header("X-Member-Id", member.toString()) }.bodyAsText()
                createdPostIds += Uuid.parse(postId)

                val export = client.get("/test/export-manifest/$member") { header("X-Member-Id", member.toString()) }
                export.status shouldBe HttpStatusCode.OK
                val counts = export.bodyAsText().split(",").associate { it.substringBefore("=") to it.substringAfter("=").toInt() }
                (counts["social_network"] ?: 0) shouldBe 1
            }
        }

        test(
            "ANONYMIZE erasure of a member who authored a Social Post anonymizes the member row but RETAINS the post's content/weight/visibility unchanged",
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
                        post("/test/create-post") {
                            val service =
                                SocialNetworkService(
                                    call = call,
                                    createRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    readRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    boostRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                )
                            val p =
                                service.createPost(
                                    SocialPostInput(
                                        content = "DSGVO-Erasure-Test",
                                        visibility = SocialPostVisibility.PUBLIC,
                                        initialWeightLtr = BigDecimal("1.00"),
                                    ),
                                )
                            call.respondText(p.id)
                        }
                        post("/test/request-erasure/{subjectId}") {
                            val service = DsgvoService(call)
                            val request =
                                service.requestErasure(
                                    subjectMemberId = call.parameters["subjectId"]!!,
                                    reason = "Social Network self-service erasure test",
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
                val member = createTestMember("dsgvo-social-erasure@example.org")
                val postId = client.post("/test/create-post") { header("X-Member-Id", member.toString()) }.bodyAsText()
                createdPostIds += Uuid.parse(postId)

                val requestId =
                    client.post("/test/request-erasure/$member") { header("X-Member-Id", member.toString()) }.bodyAsText()
                val decided = client.post("/test/decide/$requestId/true") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                decided shouldBe ErasureStatus.APPROVED.name
                val executed = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                executed shouldBe ErasureStatus.COMPLETED.name

                transaction {
                    val memberRow = MemberTable.selectAll().where { MemberTable.id eq member }.single()
                    (memberRow[MemberTable.anonymizedAt] != null) shouldBe true

                    val postRow = SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single()
                    postRow[SocialPostTable.content] shouldBe "DSGVO-Erasure-Test"
                    postRow[SocialPostTable.initialWeightLtr].compareTo(BigDecimal("1.00")) shouldBe 0
                    postRow[SocialPostTable.visibility] shouldBe SocialPostVisibility.PUBLIC
                    postRow[SocialPostTable.authorMemberId] shouldBe member
                }
            }
        }
    })
