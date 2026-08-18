package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialTimelineQuery
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Exercises [SocialNetworkService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly, no wire format to reverse-engineer). Every
 * member that stakes LTR or otherwise accrues rows is a fresh test member, same discipline
 * [CrowdfundingServiceTest] documents for its own fixtures. [afterSpec] hard-deletes every row this
 * file created (children of `social_post` -- none exist yet this wave -- would need to be deleted
 * before parents; not relevant here since no post is ever its own parent's non-root descendant in
 * Welle V1.1.1).
 */
class SocialNetworkServiceTest :
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
                if (createdMemberIds.isNotEmpty()) {
                    LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Social Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
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

        fun mintLtr(
            memberId: Uuid,
            amount: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = amount
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)

        test(
            "createPost: happy path binds a SOCIAL_POST_STAKE debit, is immediately VISIBLE, own weight equals the stake at 0 days elapsed",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-happy@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val response =
                    client
                        .post("/test/create-post?content=Hallo%20Welt&weight=5.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                val (postId, state, ownWeight) = response.split(":")
                state shouldBe "VISIBLE"
                ownWeight shouldBe "5.00"
                createdPostIds += Uuid.parse(postId)

                val balance = client.get("/test/free-balance") { header("X-Member-Id", author.toString()) }.bodyAsText()
                balance shouldBe "5.00"

                val stakeRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq author) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.SOCIAL_POST_STAKE)
                            }.single()
                    }
                stakeRow[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("-5.00")) shouldBe 0
                stakeRow[LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.SOCIAL_POST
                stakeRow[LtrLedgerEntryTable.referenceId] shouldBe Uuid.parse(postId)

                // E5 (offene Entscheidung): die Note traegt KEINEN Inhaltsausschnitt, nur eine
                // ID-Referenz -- der Post-Inhalt "Hallo Welt" darf hier nicht auftauchen.
                (stakeRow[LtrLedgerEntryTable.note] ?: "").contains("Hallo Welt") shouldBe false
                (stakeRow[LtrLedgerEntryTable.note] ?: "").contains(postId) shouldBe true
            }
        }

        test("createPost: root post has parentId=null, rootId=own id, depth=0") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-root@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val row = transaction { SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(postId) }.single() }
                row[SocialPostTable.parentId] shouldBe null
                row[SocialPostTable.rootId] shouldBe Uuid.parse(postId)
                row[SocialPostTable.depth] shouldBe 0
            }
        }

        test(
            "createPost: validation -- below 0.01 LTR, more than 2 decimal places, over free balance, and blank content are all rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-validation@example.org")
                mintLtr(author, BigDecimal("1.00"))

                val belowMin =
                    client.post("/test/create-post?content=x&weight=0.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                belowMin.status shouldBe HttpStatusCode.Conflict

                val tooManyDecimals =
                    client.post("/test/create-post?content=x&weight=1.001&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                tooManyDecimals.status shouldBe HttpStatusCode.Conflict

                val overBalance =
                    client.post("/test/create-post?content=x&weight=999.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                overBalance.status shouldBe HttpStatusCode.Conflict

                val blankContent =
                    client.post("/test/create-post?content=%20&weight=0.10&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                blankContent.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "createPost: requires ORGANIZATION_MEMBER (ACTIVE) -- FRIEND cannot post yet (Welle V1.1.1, LTR_ELIGIBLE widening is Welle V1.1.4)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val friend = createTestMember("social-friend@example.org", status = MemberStatus.FRIEND)
                mintLtr(friend, BigDecimal("10.00"))
                val response =
                    client.post("/test/create-post?content=x&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("createPost: requires ORGANIZATION_MEMBER -- GUEST and APPLICATION are rejected too") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val guest = createTestMember("social-guest@example.org", status = MemberStatus.GUEST)
                val applicant = createTestMember("social-application@example.org", status = MemberStatus.APPLICATION)
                mintLtr(guest, BigDecimal("10.00"))
                mintLtr(applicant, BigDecimal("10.00"))

                client
                    .post("/test/create-post?content=x&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", guest.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/create-post?content=x&weight=1.00&visibility=PUBLIC") {
                        header("X-Member-Id", applicant.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("no production source file calls SocialPostTable.update( outside hideOwnPost's state* columns -- unveraenderlichkeit scan") {
            val mainSourceDir =
                File("src/main/kotlin").let { relative -> if (relative.exists()) relative else File("lapis-server/src/main/kotlin") }
            require(mainSourceDir.exists()) { "main source dir not found: ${mainSourceDir.absolutePath}" }

            val offenders =
                mainSourceDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (line.contains("SocialPostTable.update(")) "${file.path}:${index + 1}" else null
                        }
                    }.toList()
            // Exactly one legitimate mutation site is allowed: SocialNetworkService.hideOwnPost's
            // own state/stateChangedAt/stateChangedBy update -- nothing else in production code may
            // ever call SocialPostTable.update( (there is no updatePost RPC method, content is
            // immutable after publication).
            offenders.size shouldBe 1
            offenders.single().contains("SocialNetworkService.kt") shouldBe true
        }

        test(
            "no production source file ever assigns SocialPostTable.content/initialWeightLtr/visibility/publishedAt/parentId inside an update( block",
        ) {
            val mainSourceDir =
                File("src/main/kotlin").let { relative -> if (relative.exists()) relative else File("lapis-server/src/main/kotlin") }
            val serviceFile = File(mainSourceDir, "network/lapis/cloud/server/rpc/SocialNetworkService.kt")
            require(serviceFile.exists()) { "SocialNetworkService.kt not found at ${serviceFile.absolutePath}" }
            val lines = serviceFile.readLines()
            val updateBlockStart = lines.indexOfFirst { it.contains("SocialPostTable.update(") }
            (updateBlockStart >= 0) shouldBe true
            val updateBlockEnd = lines.drop(updateBlockStart).indexOfFirst { it.trim() == "}" } + updateBlockStart
            val updateBlockLines = lines.subList(updateBlockStart, updateBlockEnd + 1)
            val immutableColumnTokens = listOf("it[content]", "it[initialWeightLtr]", "it[visibility]", "it[publishedAt]", "it[parentId]")
            val offenders = updateBlockLines.filter { line -> immutableColumnTokens.any { line.contains(it) } }
            offenders.shouldBeEmpty()
        }

        test("listTimeline: visibility filtering matrix across three poster visibilities and four viewer statuses") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-matrix-author@example.org")
                mintLtr(author, BigDecimal("10.00"))

                suspend fun post(
                    content: String,
                    visibility: SocialPostVisibility,
                ): String {
                    val id =
                        client
                            .post("/test/create-post?content=$content&weight=0.10&visibility=$visibility") {
                                header("X-Member-Id", author.toString())
                            }.bodyAsText()
                            .substringBefore(":")
                    createdPostIds += Uuid.parse(id)
                    return id
                }

                val publicId = post("MatrixPublic", SocialPostVisibility.PUBLIC)
                val membersOnlyId = post("MatrixMembersOnly", SocialPostVisibility.MEMBERS_ONLY)
                val membersAndExternalId = post("MatrixMembersAndExternal", SocialPostVisibility.MEMBERS_AND_EXTERNAL)

                val active = createTestMember("social-matrix-active@example.org")
                val guest = createTestMember("social-matrix-guest@example.org", status = MemberStatus.GUEST)
                val applicant = createTestMember("social-matrix-application@example.org", status = MemberStatus.APPLICATION)
                // G7 (Review Runde 1, 2026-08-18): FRIEND is, like GUEST, MemberStatusSets.NON_MEMBER
                // (see Foundation.kt) -- SocialVisibility.readableByCondition treats both statuses
                // identically, so a FRIEND viewer must see exactly the same posts a GUEST viewer does.
                val friend = createTestMember("social-matrix-friend@example.org", status = MemberStatus.FRIEND)

                // Scoped via authorMemberId to this test's own fixture author -- other tests in
                // this Spec share the same H2 instance and are not cleaned up until afterSpec, so
                // an unfiltered listTimeline would also see their posts.
                suspend fun timelineIds(viewer: Uuid): Set<String> =
                    client
                        .get("/test/list-timeline?author=$author") { header("X-Member-Id", viewer.toString()) }
                        .bodyAsText()
                        .split(",")
                        .filter { it.isNotBlank() }
                        .toSet()

                timelineIds(active) shouldBe setOf(publicId, membersOnlyId, membersAndExternalId)
                timelineIds(guest) shouldBe setOf(publicId, membersAndExternalId)
                timelineIds(friend) shouldBe setOf(publicId, membersAndExternalId)
                timelineIds(applicant) shouldBe setOf(publicId)
            }
        }

        test(
            "hideOwnPost: author can hide, a different member cannot, hidden post disappears from listTimeline but stays reachable via getPost",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-hide-author@example.org")
                val stranger = createTestMember("social-hide-stranger@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=HideMe&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val strangerAttempt = client.post("/test/hide-post/$postId") { header("X-Member-Id", stranger.toString()) }
                strangerAttempt.status shouldBe HttpStatusCode.Forbidden

                val hideResult = client.post("/test/hide-post/$postId") { header("X-Member-Id", author.toString()) }
                hideResult.status shouldBe HttpStatusCode.OK
                hideResult.bodyAsText() shouldBe "HIDDEN_BY_AUTHOR"

                val secondHide = client.post("/test/hide-post/$postId") { header("X-Member-Id", author.toString()) }
                secondHide.status shouldBe HttpStatusCode.Conflict

                val timeline = client.get("/test/list-timeline") { header("X-Member-Id", author.toString()) }.bodyAsText()
                timeline.contains(postId) shouldBe false

                val directGet = client.get("/test/get-post/$postId") { header("X-Member-Id", author.toString()) }
                directGet.status shouldBe HttpStatusCode.OK
                directGet.bodyAsText().contains(postId) shouldBe true

                // Keine LTR-Rueckerstattung -- der urspruengliche SOCIAL_POST_STAKE-Debit bleibt die
                // einzige Ledger-Zeile fuer diesen Post.
                val ledgerEntryCount =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq author) and
                                    (LtrLedgerEntryTable.referenceId eq Uuid.parse(postId))
                            }.count()
                    }
                ledgerEntryCount shouldBe 1L
            }
        }

        test("getPost: a post not visible to the caller (or not existing at all) returns identically NotFound -- no existence oracle") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-oracle-author@example.org")
                val applicant = createTestMember("social-oracle-applicant@example.org", status = MemberStatus.APPLICATION)
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Internal&weight=1.00&visibility=MEMBERS_ONLY") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val notVisible = client.get("/test/get-post/$postId") { header("X-Member-Id", applicant.toString()) }
                val notExisting = client.get("/test/get-post/${Uuid.random()}") { header("X-Member-Id", applicant.toString()) }
                notVisible.status shouldBe HttpStatusCode.NotFound
                notExisting.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "S3 (Review Runde 1, 2026-08-18): a REMOVED_LEGAL post is treated as not-existent via getPost -- even for its own author -- unlike HIDDEN_BY_AUTHOR, which stays reachable via direct ID access",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-removed-legal-author@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=WillBeRemoved&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                // No SocialNetworkService method writes REMOVED_LEGAL yet (that's
                // removePostForLegalReason, Welle V1.1.5) -- simulate it directly against the table,
                // exactly the way this codebase's other "future state, no writer yet" schema-drift
                // fixtures do.
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(postId) }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                    }
                }

                val byAuthor = client.get("/test/get-post/$postId") { header("X-Member-Id", author.toString()) }
                byAuthor.status shouldBe HttpStatusCode.NotFound

                val strangerViewer = createTestMember("social-removed-legal-stranger@example.org")
                val byStranger = client.get("/test/get-post/$postId") { header("X-Member-Id", strangerViewer.toString()) }
                byStranger.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "NEU-4 (Review Runde 2, 2026-08-18): hideOwnPost on a REMOVED_LEGAL post returns NotFound, not Conflict -- even for its own author",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-hide-removed-legal-author@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=WillBeRemovedThenHidden&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                // Same "no writer yet this wave" simulation as the S3 getPost test above.
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(postId) }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                    }
                }

                val hideAttempt = client.post("/test/hide-post/$postId") { header("X-Member-Id", author.toString()) }
                // Before the fix, this was HttpStatusCode.Conflict -- leaking both that the post
                // exists and that it is REMOVED_LEGAL to its own author, contradicting getPost's
                // "treated as not-existent" guarantee for the same state.
                hideAttempt.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "NEU-5 (Review Runde 2, 2026-08-18): the RANKING_HORIZON_DAYS cutoff does not apply to the author's own includeHidden overview",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-horizon-selfview-author@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=VeryOldOwnPost&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                // Well beyond SocialPostWeight.RANKING_HORIZON_DAYS (400 days) before "now".
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(postId) }) {
                        it[publishedAt] = LocalDateTime(2020, 1, 1, 0, 0, 0)
                    }
                }

                // Ranked (non-self) view: the horizon filter excludes the now-ancient post.
                val ranked =
                    client
                        .get("/test/list-timeline?author=$author") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                ranked.contains(postId) shouldBe false

                // Author's own includeHidden overview: exempt from the horizon filter, still shows it.
                val selfView =
                    client
                        .get("/test/list-timeline?author=$author&includeHidden=true") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                selfView.contains(postId) shouldBe true
            }
        }

        test("createPost: reads (listTimeline/getPost) and writes all require authentication") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                client.get("/test/list-timeline").status shouldBe HttpStatusCode.Unauthorized
                client.post("/test/create-post?content=x&weight=1.00&visibility=PUBLIC").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "createPost: concurrent createPost calls for the same member are serialized by lockForDebit -- exactly one of two overlapping 60% stakes succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val member = createTestMember("social-concurrency@example.org")
                mintLtr(member, BigDecimal("10.00"))

                val results =
                    runBlocking {
                        listOf(
                            async {
                                runCatching {
                                    client.post("/test/create-post?content=A&weight=6.00&visibility=PUBLIC") {
                                        header("X-Member-Id", member.toString())
                                    }
                                }
                            },
                            async {
                                runCatching {
                                    client.post("/test/create-post?content=B&weight=6.00&visibility=PUBLIC") {
                                        header("X-Member-Id", member.toString())
                                    }
                                }
                            },
                        ).awaitAll()
                    }
                val statuses = results.mapNotNull { it.getOrNull()?.status }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1

                results.forEach { result ->
                    result.getOrNull()?.let { response ->
                        if (response.status == HttpStatusCode.OK) {
                            createdPostIds += Uuid.parse(response.bodyAsText().substringBefore(":"))
                        }
                    }
                }
            }
        }

        test(
            "S-1 (Security-Audit Runde 1, 2026-08-18): authorFreeBalanceLtr is populated for an ORGANIZATION_MEMBER viewer but null for a NON_MEMBER (FRIEND) viewer",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-s1-author@example.org")
                mintLtr(author, BigDecimal("7.00"))
                val postId =
                    client
                        .post("/test/create-post?content=S1&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val activeViewer = createTestMember("social-s1-active@example.org")
                val friendViewer = createTestMember("social-s1-friend@example.org", status = MemberStatus.FRIEND)

                // 7.00 minted minus the 1.00 stake debited by createPost itself -- see
                // "lockForDebit -> freeBalance check -> insert -> ledger debit" in createPost.
                val asActive = client.get("/test/get-post/$postId") { header("X-Member-Id", activeViewer.toString()) }.bodyAsText()
                asActive.substringAfterLast(":") shouldBe "6.00"

                // NON_MEMBER (FRIEND, self-registered, no board approval) must never learn another
                // member's exact free LTR balance via the timeline -- see LtrLedgerService
                // .getMemberBalance's own LTR_TREASURY_ROLES gate for the boundary this used to
                // bypass entirely.
                val asFriend = client.get("/test/get-post/$postId") { header("X-Member-Id", friendViewer.toString()) }.bodyAsText()
                asFriend.substringAfterLast(":") shouldBe "null"
            }
        }

        test(
            "G-3 (Security-Audit Runde 1, 2026-08-18): a sharply configured createRateLimiter actually blocks a second immediate createPost call",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing {
                        registerSocialNetworkTestRoutes(createRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                    }
                }
                val author = createTestMember("social-g3-create@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val first =
                    client.post("/test/create-post?content=First&weight=1.00&visibility=PUBLIC") {
                        header("X-Member-Id", author.toString())
                    }
                first.status shouldBe HttpStatusCode.OK
                createdPostIds += Uuid.parse(first.bodyAsText().substringBefore(":"))

                val second =
                    client.post("/test/create-post?content=Second&weight=1.00&visibility=PUBLIC") {
                        header("X-Member-Id", author.toString())
                    }
                second.status shouldBe HttpStatusCode.Conflict
                second.bodyAsText().contains("Zu viele") shouldBe true
            }
        }

        test(
            "S-2/G-3 (Security-Audit Runde 1, 2026-08-18): hideOwnPost is now rate-limited (shares createRateLimiter's budget) -- a second immediate call is blocked",
        ) {
            val author = createTestMember("social-g3-hide@example.org")
            var postA = ""
            var postB = ""
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                mintLtr(author, BigDecimal("5.00"))
                postA =
                    client
                        .post("/test/create-post?content=HideA&weight=0.10&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                postB =
                    client
                        .post("/test/create-post?content=HideB&weight=0.10&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
            }
            createdPostIds += Uuid.parse(postA)
            createdPostIds += Uuid.parse(postB)

            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing {
                        registerSocialNetworkTestRoutes(createRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                    }
                }
                val hideFirst = client.post("/test/hide-post/$postA") { header("X-Member-Id", author.toString()) }
                hideFirst.status shouldBe HttpStatusCode.OK

                val hideSecond = client.post("/test/hide-post/$postB") { header("X-Member-Id", author.toString()) }
                hideSecond.status shouldBe HttpStatusCode.Conflict
                hideSecond.bodyAsText().contains("Zu viele") shouldBe true
            }
        }
    })

private fun StatusPagesConfig.installSocialExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/**
 * Shared throwaway routes for [SocialNetworkService] -- mirrors [CrowdfundingServiceTest]'s
 * `registerCrowdfundingTestRoutes` style. [readRateLimiter] defaults to [createRateLimiter] (both
 * generous throwaway limiters here) -- production wiring (`Application.kt`) always passes two
 * DIFFERENT module-scoped instances, see [SocialNetworkService] KDoc.
 */
private fun Route.registerSocialNetworkTestRoutes(
    createRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter = createRateLimiter,
) {
    post("/test/create-post") {
        val service = SocialNetworkService(call = call, createRateLimiter = createRateLimiter, readRateLimiter = readRateLimiter)
        val q = call.request.queryParameters
        val p =
            service.createPost(
                SocialPostInput(
                    content = q["content"] ?: "Testinhalt",
                    visibility = SocialPostVisibility.valueOf(q["visibility"] ?: "PUBLIC"),
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        call.respondText("${p.id}:${p.state}:${p.ownCurrentWeightLtr}")
    }
    get("/test/get-post/{id}") {
        val service = SocialNetworkService(call = call, createRateLimiter = createRateLimiter, readRateLimiter = readRateLimiter)
        val p = service.getPost(call.parameters["id"]!!)
        // authorFreeBalanceLtr appended for the S-1 (Security-Audit Runde 1, 2026-08-18) test --
        // "null" (string) when the viewer is not ORGANIZATION_MEMBER, see SocialNetworkService
        // .toDtos KDoc.
        call.respondText("${p.id}:${p.state}:${p.authorFreeBalanceLtr ?: "null"}")
    }
    get("/test/list-timeline") {
        val service = SocialNetworkService(call = call, createRateLimiter = createRateLimiter, readRateLimiter = readRateLimiter)
        val authorFilter = call.request.queryParameters["author"]
        val includeHidden = call.request.queryParameters["includeHidden"]?.toBoolean() ?: false
        val page =
            service.listTimeline(SocialTimelineQuery(limit = 100, authorMemberId = authorFilter, includeHidden = includeHidden))
        call.respondText(page.posts.joinToString(",") { it.id })
    }
    post("/test/hide-post/{id}") {
        val service = SocialNetworkService(call = call, createRateLimiter = createRateLimiter, readRateLimiter = readRateLimiter)
        val p = service.hideOwnPost(call.parameters["id"]!!)
        call.respondText(p.state.name)
    }
    get("/test/free-balance") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
}
