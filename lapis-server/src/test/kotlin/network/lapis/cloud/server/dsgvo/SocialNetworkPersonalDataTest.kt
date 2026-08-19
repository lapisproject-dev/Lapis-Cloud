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
import network.lapis.cloud.shared.domain.SocialPostErasureInput
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
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
                    // Cross-Path-Test (Review-Fund 6) leaves a social_post_erasure row FK'd to its
                    // post -- must be deleted first, same discipline as SocialNetworkServiceTest's
                    // own afterSpec (Stolperfalle 11).
                    network.lapis.cloud.server.db.generated.SocialPostErasureTable
                        .deleteWhere { network.lapis.cloud.server.db.generated.SocialPostErasureTable.postId inList createdPostIds }
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
                                    moderationRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    reportRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
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
                                    moderationRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    reportRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
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

        // Welle V1.1.5 -- HARD_DELETE_WHERE_UNCONSTRAINED wird von "retain-with-reason" (V1.1.1/
        // V1.1.2) auf echtes Content-Tombstoning aufgewertet.
        test(
            "HARD_DELETE_WHERE_UNCONSTRAINED erasure of a member's Social Post tombstones content (ON_AUTHOR_REQUEST marker), leaves id/parentId/rootId/depth/initialWeightLtr/publishedAt/state UNTOUCHED, and reports rowsAnonymized (not rowsDeleted)",
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
                                    moderationRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                    reportRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                )
                            val p =
                                service.createPost(
                                    SocialPostInput(
                                        content = "DSGVO-HardDelete-Test",
                                        visibility = SocialPostVisibility.PUBLIC,
                                        initialWeightLtr = BigDecimal("2.50"),
                                    ),
                                )
                            call.respondText(p.id)
                        }
                        post("/test/request-erasure/{subjectId}") {
                            val service = DsgvoService(call)
                            val request =
                                service.requestErasure(
                                    subjectMemberId = call.parameters["subjectId"]!!,
                                    reason = "Social Network hard-delete test",
                                    mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
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
                            // Review-Fund 7 (Runde 1, 2026-08-19): the test name below claims to
                            // assert `rowsAnonymized` in the RETURNED `TableErasureOutcome`, so the
                            // route must actually expose it -- not just the request status.
                            val socialOutcome = request.outcome.single { it.table == "social_post" }
                            call.respondText("${request.status}:${socialOutcome.rowsAnonymized}:${socialOutcome.rowsRetained}")
                        }
                    }
                }
                val member = createTestMember("dsgvo-social-harddelete@example.org")
                val postId = client.post("/test/create-post") { header("X-Member-Id", member.toString()) }.bodyAsText()
                createdPostIds += Uuid.parse(postId)

                val row0 = transaction { SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single() }
                val depthBefore = row0[SocialPostTable.depth]
                val rootIdBefore = row0[SocialPostTable.rootId]
                val publishedAtBefore = row0[SocialPostTable.publishedAt]

                val requestId =
                    client.post("/test/request-erasure/$member") { header("X-Member-Id", member.toString()) }.bodyAsText()
                client.post("/test/decide/$requestId/true") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    ErasureStatus.APPROVED.name
                val executeResponse = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                // "COMPLETED:rowsAnonymized:rowsRetained" -- the actual returned TableErasureOutcome,
                // not just the DB row (Review-Fund 7).
                executeResponse shouldBe "${ErasureStatus.COMPLETED.name}:1:0"

                transaction {
                    val postRow = SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single()
                    postRow[SocialPostTable.content] shouldBe network.lapis.cloud.server.rpc.SocialContentTombstone.ON_AUTHOR_REQUEST
                    (postRow[SocialPostTable.contentErasedAt] != null) shouldBe true
                    postRow[SocialPostTable.depth] shouldBe depthBefore
                    postRow[SocialPostTable.rootId] shouldBe rootIdBefore
                    postRow[SocialPostTable.publishedAt] shouldBe publishedAtBefore
                    postRow[SocialPostTable.initialWeightLtr].compareTo(BigDecimal("2.50")) shouldBe 0
                    postRow[SocialPostTable.visibility] shouldBe SocialPostVisibility.PUBLIC
                    postRow[SocialPostTable.state] shouldBe network.lapis.cloud.shared.domain.SocialPostState.VISIBLE
                }
            }
        }

        // Addendum-Test 52, zweiter Teil (Review-Fund 6, Runde 1 2026-08-19): "erster Schreiber
        // gewinnt" cross-path -- ein per (A) `executeContentErasure` bereits getombstoneter Post
        // darf vom (B) mitglieds-weiten `SocialNetworkPersonalData.erase(HARD_DELETE_WHERE_
        // UNCONSTRAINED)` NICHT erneut ueberschrieben werden. Ruft [SocialNetworkPersonalData.erase]
        // direkt auf (statt ueber den vollen `DsgvoService`-Antragsfluss) -- dasselbe Package, siehe
        // Klassen-KDoc "läuft in der Transaktion des Aufrufers".
        test(
            "Cross-path 'first writer wins': a post already tombstoned via (A) executeContentErasure " +
                "(ON_POST_REQUEST) is left UNTOUCHED by a subsequent (B) SocialNetworkPersonalData.erase " +
                "(HARD_DELETE_WHERE_UNCONSTRAINED) for its author -- counted rowsRetained, not rowsAnonymized",
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
                        fun socialService(call: io.ktor.server.application.ApplicationCall) =
                            SocialNetworkService(
                                call = call,
                                createRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                readRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                boostRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                moderationRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                                reportRateLimiter = FederationInboxRateLimiter(window = 1.minutes),
                            )
                        post("/test/create-post") {
                            val p =
                                socialService(call).createPost(
                                    SocialPostInput(
                                        content = "Cross-Path-Test",
                                        visibility = SocialPostVisibility.PUBLIC,
                                        initialWeightLtr = BigDecimal("1.00"),
                                    ),
                                )
                            call.respondText(p.id)
                        }
                        post("/test/request-content-erasure/{postId}") {
                            val e =
                                socialService(call).requestContentErasure(
                                    SocialPostErasureInput(postId = call.parameters["postId"]!!, reason = "Cross-Path-Antrag"),
                                )
                            call.respondText(e.id)
                        }
                        post("/test/decide-content-erasure/{erasureId}") {
                            val e =
                                socialService(call).decideContentErasure(
                                    erasureId = call.parameters["erasureId"]!!,
                                    approve = true,
                                    note = null,
                                )
                            call.respondText(e.status.name)
                        }
                        post("/test/execute-content-erasure/{erasureId}") {
                            val e = socialService(call).executeContentErasure(call.parameters["erasureId"]!!)
                            call.respondText(e.status.name)
                        }
                    }
                }
                val author = createTestMember("dsgvo-social-crosspath@example.org")
                val admin = ADMIN_ID
                val postId = client.post("/test/create-post") { header("X-Member-Id", author.toString()) }.bodyAsText()
                createdPostIds += Uuid.parse(postId)

                // (A) post-bezogener Antrag: request -> approve -> execute.
                val erasureId =
                    client
                        .post("/test/request-content-erasure/$postId") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                client
                    .post("/test/decide-content-erasure/$erasureId") { header("X-Member-Id", admin) }
                    .bodyAsText() shouldBe SocialPostErasureStatus.APPROVED.name
                client
                    .post("/test/execute-content-erasure/$erasureId") { header("X-Member-Id", admin) }
                    .bodyAsText() shouldBe SocialPostErasureStatus.EXECUTED.name

                transaction {
                    val row = SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single()
                    row[SocialPostTable.content] shouldBe network.lapis.cloud.server.rpc.SocialContentTombstone.ON_POST_REQUEST
                }

                // (B) mitglieds-weiter Antrag fuer denselben Autor, direkt am Contributor.
                val outcome =
                    transaction {
                        SocialNetworkPersonalData.erase(memberId = author, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED)
                    }
                val socialOutcome = outcome.single { it.table == "social_post" }
                socialOutcome.rowsAnonymized shouldBe 0
                socialOutcome.rowsRetained shouldBe 1

                transaction {
                    val row = SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single()
                    // (A)'s Text ueberlebt UNVERAENDERT -- (B) hat NICHT ueberschrieben.
                    row[SocialPostTable.content] shouldBe network.lapis.cloud.server.rpc.SocialContentTombstone.ON_POST_REQUEST
                }
            }
        }
    })
