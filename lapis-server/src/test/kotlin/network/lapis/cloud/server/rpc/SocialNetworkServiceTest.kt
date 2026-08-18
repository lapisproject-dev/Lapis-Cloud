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
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialCommentInput
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
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Exercises [SocialNetworkService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly, no wire format to reverse-engineer). Every
 * member that stakes LTR or otherwise accrues rows is a fresh test member, same discipline
 * [CrowdfundingServiceTest] documents for its own fixtures. [afterSpec] hard-deletes every row this
 * file created -- since Welle V1.1.2, `social_post` children (comments) genuinely exist here, so a
 * single bulk delete over the whole id set can violate the self-referencing `parent_id`/`root_id`
 * FK; boosts are deleted first, then posts in repeated deepest-`depth`-first batches (Stolperfalle
 * 12, see the `afterSpec` block itself for the loop).
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
                    // Welle V1.1.2 (Stolperfalle 12): comments now exist, so a single bulk delete
                    // over the whole id set can violate the self-referencing parent_id/root_id FK.
                    // Delete deepest-depth batches first, repeatedly, until nothing remains.
                    SocialPostBoostTable.deleteWhere { SocialPostBoostTable.postId inList createdPostIds }
                    val remaining = createdPostIds.toMutableList()
                    while (remaining.isNotEmpty()) {
                        val depthById =
                            SocialPostTable
                                .select(SocialPostTable.id, SocialPostTable.depth)
                                .where { SocialPostTable.id inList remaining }
                                .associate { it[SocialPostTable.id] to it[SocialPostTable.depth] }
                        if (depthById.isEmpty()) break
                        val maxDepth = depthById.values.max()
                        val toDelete = depthById.filterValues { it == maxDepth }.keys.toList()
                        SocialPostTable.deleteWhere { SocialPostTable.id inList toDelete }
                        remaining.removeAll(toDelete)
                    }
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
                // Response fields: id:state:authorFreeBalanceLtr:totalCurrentWeightLtr:
                // directCommentCount:totalDescendantCount:boostCount (Welle V1.1.2 extension).
                val asActive = client.get("/test/get-post/$postId") { header("X-Member-Id", activeViewer.toString()) }.bodyAsText()
                asActive.split(":")[2] shouldBe "6.00"

                // NON_MEMBER (FRIEND, self-registered, no board approval) must never learn another
                // member's exact free LTR balance via the timeline -- see LtrLedgerService
                // .getMemberBalance's own LTR_TREASURY_ROLES gate for the boundary this used to
                // bypass entirely.
                val asFriend = client.get("/test/get-post/$postId") { header("X-Member-Id", friendViewer.toString()) }.bodyAsText()
                asFriend.split(":")[2] shouldBe "null"
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

        // ── Welle V1.1.2: Kommentarbaum, Boosts, rekursive Gesamtgewichtung ────────────────────

        test("createComment: happy path binds its own SOCIAL_POST_STAKE debit, correct parentId/rootId/depth") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-comment-happy@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)

                val commentResponse =
                    client
                        .post("/test/create-comment?parentId=$rootId&content=Kommentar&weight=2.00") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                val parts = commentResponse.split(":")
                val commentId = parts[0]
                parts[1] shouldBe rootId // parentId
                parts[2] shouldBe rootId // rootId
                parts[3] shouldBe "1" // depth
                parts[5] shouldBe "2.00" // ownCurrentWeightLtr
                createdPostIds += Uuid.parse(commentId)

                val balance = client.get("/test/free-balance") { header("X-Member-Id", author.toString()) }.bodyAsText()
                balance shouldBe "7.00" // 10.00 - 1.00 (root) - 2.00 (comment)

                val stakeRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq author) and
                                    (LtrLedgerEntryTable.referenceId eq Uuid.parse(commentId))
                            }.single()
                    }
                stakeRow[LtrLedgerEntryTable.entryType] shouldBe LtrLedgerEntryType.SOCIAL_POST_STAKE
                stakeRow[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("-2.00")) shouldBe 0
            }
        }

        test("createComment: weight propagates to totalCurrentWeightLtr of BOTH ancestors across 3 levels") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-comment-propagate@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val level2Id =
                    client
                        .post("/test/create-comment?parentId=$rootId&content=L2&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(level2Id)
                val level3Id =
                    client
                        .post("/test/create-comment?parentId=$level2Id&content=L3&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(level3Id)

                val rootGet = client.get("/test/get-post/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText()
                val level2Get = client.get("/test/get-post/$level2Id") { header("X-Member-Id", author.toString()) }.bodyAsText()
                // fields: id:state:authorFreeBalanceLtr:totalCurrentWeightLtr:directCommentCount:totalDescendantCount:boostCount
                rootGet.split(":")[3] shouldBe "3.00" // 1.00 (own) + 1.00 (L2) + 1.00 (L3)
                rootGet.split(":")[5] shouldBe "2" // totalDescendantCount
                level2Get.split(":")[3] shouldBe "2.00" // 1.00 (own) + 1.00 (L3)
                level2Get.split(":")[5] shouldBe "1"
            }
        }

        test("createComment: S5 visibility inheritance is read from the ROOT post, not the direct parent") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-s5-author@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=MEMBERS_ONLY") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val level2Id =
                    client
                        .post("/test/create-comment?parentId=$rootId&content=L2&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(level2Id)

                // Directly corrupt level2's visibility to PUBLIC via SQL -- a level3 comment must
                // STILL inherit MEMBERS_ONLY from the ROOT, not from this (deliberately wrong)
                // direct parent value.
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(level2Id) }) {
                        it[visibility] = SocialPostVisibility.PUBLIC
                    }
                }

                val level3Response =
                    client
                        .post("/test/create-comment?parentId=$level2Id&content=L3&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                createdPostIds += Uuid.parse(level3Response.split(":")[0])
                level3Response.split(":")[4] shouldBe "MEMBERS_ONLY"
            }
        }

        test("createComment: HIDDEN_BY_AUTHOR parent -> Conflict, REMOVED_LEGAL parent -> NotFound (no existence oracle for either)") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-comment-parentstate@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val hiddenPostId =
                    client
                        .post("/test/create-post?content=WillHide&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(hiddenPostId)
                client.post("/test/hide-post/$hiddenPostId") { header("X-Member-Id", author.toString()) }

                val hiddenAttempt =
                    client.post("/test/create-comment?parentId=$hiddenPostId&content=x&weight=1.00") {
                        header("X-Member-Id", author.toString())
                    }
                hiddenAttempt.status shouldBe HttpStatusCode.Conflict

                val removedPostId =
                    client
                        .post("/test/create-post?content=WillRemove&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(removedPostId)
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(removedPostId) }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                    }
                }
                val removedAttempt =
                    client.post("/test/create-comment?parentId=$removedPostId&content=x&weight=1.00") {
                        header("X-Member-Id", author.toString())
                    }
                removedAttempt.status shouldBe HttpStatusCode.NotFound
                // No third "invisible-to-a-non-member-caller" sub-case here: [createComment] gates on
                // requireActiveMembership FIRST (same as [createPost]), which already rejects any
                // non-ORGANIZATION_MEMBER caller with Forbidden before the parent's visibility is ever
                // checked -- and every ORGANIZATION_MEMBER can read all three visibility tiers (see
                // [SocialVisibility.readableByCondition]), so there is no reachable combination where
                // an ACTIVE caller is blocked by visibility alone. The no-existence-oracle guarantee
                // for an unreadable-but-existing post is exercised by [getPost]'s own dedicated test.
            }
        }

        test("createComment: depth guard -- 64 levels succeed, the 65th is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-depth-guard@example.org")
                mintLtr(author, BigDecimal("100.00"))

                var currentId =
                    client
                        .post("/test/create-post?content=Root&weight=0.10&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(currentId)
                // Root is depth 0 -- 64 more comments reach depth 64 (the maximum), the 65th comment
                // (which would be depth 65) must be rejected.
                repeat(64) { index ->
                    val response =
                        client.post("/test/create-comment?parentId=$currentId&content=d$index&weight=0.10") {
                            header("X-Member-Id", author.toString())
                        }
                    response.status shouldBe HttpStatusCode.OK
                    currentId = response.bodyAsText().split(":")[0]
                    createdPostIds += Uuid.parse(currentId)
                }
                val overLimit =
                    client.post("/test/create-comment?parentId=$currentId&content=overflow&weight=0.10") {
                        header("X-Member-Id", author.toString())
                    }
                overLimit.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "K2 Cascade-Hide on the ROOT: whole thread disappears from getThread/listTimeline, children stay reachable via getPost, no child row is written",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-cascade-root@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val childId =
                    client
                        .post(
                            "/test/create-comment?parentId=$rootId&content=Child&weight=1.00",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(childId)
                // Fund #8 (Review Runde 1, 2026-08-18): capture ALL THREE state-related columns, not
                // just stateChangedAt -- a hypothetical cascade write that only touched
                // stateChangedBy or state (but left stateChangedAt untouched by coincidence, or a
                // buggy write that copies the wrong timestamp) would have slipped past a
                // stateChangedAt-only assertion.
                val childRowBefore =
                    transaction {
                        SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(childId) }.single()
                    }
                val childStateChangedAtBefore = childRowBefore[SocialPostTable.stateChangedAt]
                val childStateChangedByBefore = childRowBefore[SocialPostTable.stateChangedBy]
                val childStateBefore = childRowBefore[SocialPostTable.state]

                client.post("/test/hide-post/$rootId") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK

                val threadAttempt = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }
                threadAttempt.status shouldBe HttpStatusCode.NotFound

                val timeline = client.get("/test/list-timeline") { header("X-Member-Id", author.toString()) }.bodyAsText()
                timeline.contains(rootId) shouldBe false

                val childDirect = client.get("/test/get-post/$childId") { header("X-Member-Id", author.toString()) }
                childDirect.status shouldBe HttpStatusCode.OK

                // No cascade WRITE: the child row's own state/stateChangedAt/stateChangedBy are all
                // untouched by hiding the root.
                val childRowAfter =
                    transaction {
                        SocialPostTable.selectAll().where { SocialPostTable.id eq Uuid.parse(childId) }.single()
                    }
                childRowAfter[SocialPostTable.stateChangedAt] shouldBe childStateChangedAtBefore
                childRowAfter[SocialPostTable.stateChangedBy] shouldBe childStateChangedByBefore
                childRowAfter[SocialPostTable.state] shouldBe childStateBefore
            }
        }

        test(
            "K2 Cascade-Hide on an INTERMEDIATE node: it and its subtree disappear from getThread, root's totalCurrentWeightLtr is unchanged (E3)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-cascade-intermediate@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val level2Id =
                    client
                        .post("/test/create-comment?parentId=$rootId&content=L2&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(level2Id)
                val level3Id =
                    client
                        .post("/test/create-comment?parentId=$level2Id&content=L3&weight=1.00") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(level3Id)

                val rootWeightBefore =
                    client.get("/test/get-post/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText().split(":")[3]

                client.post("/test/hide-post/$level2Id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK

                val threadText = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText()
                threadText.contains(level2Id) shouldBe false
                threadText.contains(level3Id) shouldBe false

                val rootWeightAfter =
                    client.get("/test/get-post/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText().split(":")[3]
                rootWeightAfter shouldBe rootWeightBefore
            }
        }

        test("getThread: preorder listing, K3 (non-VISIBLE root -> NotFound), K4 (non-root id -> NotFound)") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-thread-preorder@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val childId =
                    client
                        .post(
                            "/test/create-comment?parentId=$rootId&content=Child&weight=1.00",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(childId)

                val threadText = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText()
                val nodesPart = threadText.substringAfter(":").substringAfter(":")
                nodesPart.startsWith(rootId) shouldBe true // preorder: root first

                // K4: a non-root (child) id must NOT resolve as a thread root.
                val nonRootAttempt = client.get("/test/get-thread/$childId") { header("X-Member-Id", author.toString()) }
                nonRootAttempt.status shouldBe HttpStatusCode.NotFound

                // K3: hiding the root -> NotFound, not an empty-but-counted thread.
                client.post("/test/hide-post/$rootId") { header("X-Member-Id", author.toString()) }
                val hiddenRootAttempt = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }
                hiddenRootAttempt.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("boostPost: happy path binds a SOCIAL_POST_BOOST debit with referenceId = postId (K5), increases boostCount and weight") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-boost-happy-author@example.org")
                val booster = createTestMember("social-boost-happy-booster@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(booster, BigDecimal("5.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Boostable&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val boostResponse =
                    client.post("/test/boost/$postId?amount=2.00") { header("X-Member-Id", booster.toString()) }.bodyAsText()
                val parts = boostResponse.split(":")
                parts[1] shouldBe "1" // boostCount
                parts[2] shouldBe "3.00" // ownCurrentWeightLtr = 1.00 stake + 2.00 boost
                parts[3] shouldBe "3.00" // totalCurrentWeightLtr, no descendants

                val boosterBalance = client.get("/test/free-balance") { header("X-Member-Id", booster.toString()) }.bodyAsText()
                boosterBalance shouldBe "3.00" // 5.00 - 2.00

                val ledgerRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq booster) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.SOCIAL_POST_BOOST)
                            }.single()
                    }
                ledgerRow[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("-2.00")) shouldBe 0
                // K5: referenceId is the POST id, not a boost row id.
                ledgerRow[LtrLedgerEntryTable.referenceId] shouldBe Uuid.parse(postId)
            }
        }

        test("boostPost: S3 multiple boosts by the same member with different amounts are both accepted and summed") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-boost-multi-author@example.org")
                val booster = createTestMember("social-boost-multi-booster@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(booster, BigDecimal("10.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Multi&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", booster.toString()) }.status shouldBe
                    HttpStatusCode.OK
                val second =
                    client.post("/test/boost/$postId?amount=2.50") { header("X-Member-Id", booster.toString()) }
                second.status shouldBe HttpStatusCode.OK
                val parts = second.bodyAsText().split(":")
                parts[1] shouldBe "2" // boostCount
                parts[2] shouldBe "4.50" // 1.00 stake + 1.00 + 2.50 boosts
            }
        }

        test(
            "boostPost: E6 duplicate-window -- an identical (member, post, amount) boost within 5s is rejected, an older one (outside the window) is allowed",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-boost-dup-author@example.org")
                val booster = createTestMember("social-boost-dup-booster@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(booster, BigDecimal("10.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Dup&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", booster.toString()) }.status shouldBe
                    HttpStatusCode.OK
                val duplicateAttempt = client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", booster.toString()) }
                duplicateAttempt.status shouldBe HttpStatusCode.Conflict

                // Simulate an older boost (outside the 5s window) by moving its boosted_at back --
                // a fresh identical-amount boost must then be accepted.
                transaction {
                    SocialPostBoostTable.update({
                        (SocialPostBoostTable.postId eq Uuid.parse(postId)) and (SocialPostBoostTable.memberId eq booster)
                    }) {
                        it[boostedAt] = LocalDateTime(2020, 1, 1, 0, 0, 0)
                    }
                }
                val afterWindow = client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", booster.toString()) }
                afterWindow.status shouldBe HttpStatusCode.OK
            }
        }

        test("boostPost: HIDDEN_BY_AUTHOR post -> Conflict, REMOVED_LEGAL post -> NotFound (no existence oracle for either)") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-boost-state-author@example.org")
                val booster = createTestMember("social-boost-state-booster@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(booster, BigDecimal("15.00"))

                val hiddenId =
                    client
                        .post("/test/create-post?content=WillHide&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(hiddenId)
                client.post("/test/hide-post/$hiddenId") { header("X-Member-Id", author.toString()) }
                client.post("/test/boost/$hiddenId?amount=1.00") { header("X-Member-Id", booster.toString()) }.status shouldBe
                    HttpStatusCode.Conflict

                val removedId =
                    client
                        .post("/test/create-post?content=WillRemove&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(removedId)
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(removedId) }) { it[state] = SocialPostState.REMOVED_LEGAL }
                }
                client.post("/test/boost/$removedId?amount=1.00") { header("X-Member-Id", booster.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                // No third "invisible-to-a-non-member-caller" sub-case: same reasoning as
                // createComment's own test above -- [boostPost] gates on requireActiveMembership
                // FIRST, so a non-ORGANIZATION_MEMBER caller never reaches the visibility check at
                // all, and no ORGANIZATION_MEMBER caller is ever blocked by visibility alone.
            }
        }

        test("Ranking nach Gesamtgewicht: a heavily-discussed post with a small own stake outranks a large-stake post with no comments") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-ranking-author@example.org")
                // 50.00 (big-stake post) + 1.00 (discussed post) + 10 x 10.00 (comments) = 151.00.
                mintLtr(author, BigDecimal("200.00"))

                val bigStakePostId =
                    client
                        .post("/test/create-post?content=BigStake&weight=50.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(bigStakePostId)

                val discussedPostId =
                    client
                        .post("/test/create-post?content=Discussed&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(discussedPostId)
                repeat(10) { index ->
                    val commentId =
                        client
                            .post("/test/create-comment?parentId=$discussedPostId&content=c$index&weight=10.00") {
                                header("X-Member-Id", author.toString())
                            }.bodyAsText()
                            .split(":")[0]
                    createdPostIds += Uuid.parse(commentId)
                }

                val ranked =
                    client
                        .get("/test/list-timeline?author=$author") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(",")
                        .filter { it.isNotBlank() }
                // discussedPostId (own 1.00 + 10x10.00 = 101.00) must outrank bigStakePostId (50.00).
                (ranked.indexOf(discussedPostId) < ranked.indexOf(bigStakePostId)) shouldBe true
            }
        }

        test(
            "Ranking-Horizont für Nachfahren: a comment older than RANKING_HORIZON_DAYS is excluded from listTimeline's weight aggregation but still shows up in getThread",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-ranking-horizon@example.org")
                mintLtr(author, BigDecimal("20.00"))

                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(rootId)
                val commentId =
                    client
                        .post("/test/create-comment?parentId=$rootId&content=OldComment&weight=10.00") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(commentId)

                // Push the comment's publishedAt well beyond RANKING_HORIZON_DAYS (400 days).
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(commentId) }) {
                        it[publishedAt] = LocalDateTime(2020, 1, 1, 0, 0, 0)
                    }
                }

                // Fund #3 (Review Runde 1, 2026-08-18): the /test/list-timeline-weighted route
                // exposes totalCurrentWeightLtr/totalDescendantCount, so this now asserts EXACT
                // values instead of the previous isNotBlank()-only check that didn't actually verify
                // the horizon exclusion. NOTE on why the WEIGHT assertion alone can't distinguish
                // "excluded by horizon" from "included but decayed" -- RANKING_HORIZON_DAYS is, by
                // its own KDoc, deliberately calibrated to the day count beyond which
                // WeightDecayClock's 10%/day decay has ALREADY pushed a contribution below
                // MIN_WEIGHT_LTR: by day ~66, even a 10.00 LTR stake decays under 0.01 and rounds to
                // "0.00" regardless of whether the row is included or excluded. So the weight
                // assertion below is necessary but not sufficient; totalDescendantCount is the
                // assertion that actually proves the OLD COMMENT'S ROW never reached the
                // aggregation at all in the horizon-filtered path -- it is a COUNT, not decayed, and
                // stays exactly 0 vs. 1 regardless of how old "well beyond the horizon" means.
                val timelineWeighted =
                    client
                        .get("/test/list-timeline-weighted?author=$author") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                val rootPartsInTimeline = timelineWeighted.split(",").single { it.startsWith("$rootId:") }.split(":")
                rootPartsInTimeline[1] shouldBe "1.00" // totalCurrentWeightLtr: root's own stake only
                rootPartsInTimeline[2] shouldBe "0" // totalDescendantCount: the old comment's row never reaches this aggregation

                // getThread (horizon-free) must still show the old comment's row.
                val threadText = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText()
                threadText.contains(commentId) shouldBe true
                val rootInTimeline =
                    client.get("/test/get-post/$rootId") { header("X-Member-Id", author.toString()) }.bodyAsText()
                // getPost always uses horizon=null internally for aggregation, so the old comment's
                // row DOES reach the count -- 1, in contrast to listTimeline's 0 above -- even though
                // its decayed weight contribution is itself negligible (still folded into the 1.00
                // total below, same reasoning as the NOTE above).
                rootInTimeline.split(":")[3] shouldBe "1.00"
                rootInTimeline.split(":")[5] shouldBe "1" // totalDescendantCount
            }
        }

        test("boostPost: rate-limited by its OWN limiter (K6) -- independent from createRateLimiter's budget") {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing {
                        registerSocialNetworkTestRoutes(
                            createRateLimiter = generousLimiter(),
                            boostRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val author = createTestMember("social-boost-ratelimit-author@example.org")
                val booster = createTestMember("social-boost-ratelimit-booster@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(booster, BigDecimal("10.00"))
                val postId =
                    client
                        .post("/test/create-post?content=RateLimited&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", booster.toString()) }.status shouldBe
                    HttpStatusCode.OK
                val second = client.post("/test/boost/$postId?amount=2.00") { header("X-Member-Id", booster.toString()) }
                second.status shouldBe HttpStatusCode.Conflict
                second.bodyAsText().contains("Zu viele") shouldBe true
            }
        }

        test(
            "concurrency: two parallel boostPost calls by the same member for 60% of the free balance each -- exactly one succeeds (lockForDebit)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-boost-concurrency-author@example.org")
                val member = createTestMember("social-boost-concurrency-member@example.org")
                mintLtr(author, BigDecimal("5.00"))
                mintLtr(member, BigDecimal("10.00"))
                val postId =
                    client
                        .post("/test/create-post?content=Concurrency&weight=1.00&visibility=PUBLIC") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postId)

                val results =
                    runBlocking {
                        listOf(
                            async {
                                runCatching {
                                    client.post(
                                        "/test/boost/$postId?amount=6.00",
                                    ) { header("X-Member-Id", member.toString()) }
                                }
                            },
                            async {
                                runCatching {
                                    client.post(
                                        "/test/boost/$postId?amount=6.00",
                                    ) { header("X-Member-Id", member.toString()) }
                                }
                            },
                        ).awaitAll()
                    }
                val statuses = results.mapNotNull { it.getOrNull()?.status }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1
            }
        }

        test(
            "concurrency smoke test: cross-boosting two different posts by two different members completes without hanging (NOT a deadlock-ordering proof -- see comment)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter(), boostRateLimiter = generousLimiter()) }
                }
                val authorA = createTestMember("social-deadlock-authorA@example.org")
                val authorB = createTestMember("social-deadlock-authorB@example.org")
                val memberA = createTestMember("social-deadlock-memberA@example.org")
                val memberB = createTestMember("social-deadlock-memberB@example.org")
                mintLtr(authorA, BigDecimal("5.00"))
                mintLtr(authorB, BigDecimal("5.00"))
                mintLtr(memberA, BigDecimal("5.00"))
                mintLtr(memberB, BigDecimal("5.00"))
                val postA =
                    client
                        .post("/test/create-post?content=PostA&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", authorA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postA)
                val postB =
                    client
                        .post("/test/create-post?content=PostB&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", authorB.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postB)

                // Corrected (Fund #7, Review Runde 1, 2026-08-18): this test's original comment
                // claimed it validated the "Post -> Member" lock-ordering discipline against a real
                // deadlock. That is NOT what it exercises: memberA boosts postB, memberB boosts
                // postA -- txn1 locks postB's row + memberA's balance row, txn2 locks postA's row +
                // memberB's balance row. Those two lock sets are FULLY DISJOINT, so a real deadlock
                // is structurally impossible here regardless of lock ORDER -- a deadlock requires
                // two transactions racing to lock the SAME two resources in opposite sequence, which
                // this fixture never sets up (that would need both members boosting the SAME two
                // posts). This is therefore a plain concurrency smoke test -- two unrelated boosts
                // complete promptly without hanging -- not a deadlock-ordering proof. The "Post ->
                // Member" lock-ordering discipline itself (SocialNetworkService KDoc "Lock-
                // Reihenfolge") remains a documented CODE CONVENTION enforced by review, not by an
                // automated test that could actually provoke a reversed-order deadlock.
                val results =
                    runBlocking {
                        withTimeout(20_000) {
                            listOf(
                                async { client.post("/test/boost/$postB?amount=1.00") { header("X-Member-Id", memberA.toString()) } },
                                async { client.post("/test/boost/$postA?amount=1.00") { header("X-Member-Id", memberB.toString()) } },
                            ).awaitAll()
                        }
                    }
                results.all { it.status == HttpStatusCode.OK } shouldBe true
            }
        }

        // ── Review Runde 1 (2026-08-18) fixes: additional coverage ─────────────────────────────

        test(
            "Ranking: Tiebreaker -- two posts with identical totalCurrentWeightLtr rank in stable publishedAt-then-id order across repeated calls (Fund #6)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-tiebreak-author@example.org")
                mintLtr(author, BigDecimal("10.00"))

                val postAId =
                    client
                        .post("/test/create-post?content=TieA&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postAId)
                val postBId =
                    client
                        .post("/test/create-post?content=TieB&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(postBId)

                // Force IDENTICAL publishedAt so the id-tiebreaker (thenBy { it[id].toString() }) is
                // the only thing that can still distinguish these two otherwise-identical rows --
                // without this, publishedAt DESC alone would already fully order them. Copies A's
                // OWN publishedAt onto B rather than a hardcoded date (Review Runde 2, 2026-08-18):
                // a fixed past date this close to "now" would silently drift out of
                // RANKING_HORIZON_DAYS (400 days) and start failing this test from ~2027-02 on,
                // with no code change -- see the "Ranking-Horizont" test above for the same lesson
                // applied correctly to a row that's SUPPOSED to be excluded (there, "old enough to
                // be excluded forever" is the deliberate point; here it would be an accident).
                val sharedPublishedAt =
                    transaction {
                        SocialPostTable
                            .selectAll()
                            .where { SocialPostTable.id eq Uuid.parse(postAId) }
                            .single()[SocialPostTable.publishedAt]
                    }
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq Uuid.parse(postBId) }) {
                        it[publishedAt] = sharedPublishedAt
                    }
                }

                // The comparator's tiebreaker is `.thenBy { it[SocialPostTable.id].toString() }`
                // (ascending) -- natural String ordering of the two UUIDs.
                val expectedOrder = listOf(postAId, postBId).sorted()

                val firstCall =
                    client
                        .get("/test/list-timeline?author=$author") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(",")
                        .filter { it.isNotBlank() }
                val secondCall =
                    client
                        .get("/test/list-timeline?author=$author") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(",")
                        .filter { it.isNotBlank() }

                firstCall shouldBe expectedOrder
                secondCall shouldBe expectedOrder
            }
        }

        test(
            "listTimeline: parentId pointing at a HIDDEN_BY_AUTHOR post returns NotFound, not its (still VISIBLE) children (Fund #4)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-parentid-hidden@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val parentId =
                    client
                        .post("/test/create-post?content=Parent&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(parentId)
                val childId =
                    client
                        .post("/test/create-comment?parentId=$parentId&content=Child&weight=1.00") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                        .split(":")[0]
                createdPostIds += Uuid.parse(childId)

                client.post("/test/hide-post/$parentId") { header("X-Member-Id", author.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // Before the fix, this returned an EMPTY page (200 OK, zero posts) -- getThread
                // already suppresses the same child (K2), so a caller could use this parentId path
                // to keep reading a hidden thread's children as long as it asked for them directly
                // instead of via getThread. Now it must be NotFoundException, same "existiert nicht"
                // gate every other parent-post access in this service already uses.
                val response =
                    client.get("/test/list-timeline?parentId=$parentId") { header("X-Member-Id", author.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "concurrency: parallel createComment + hideOwnPost on the same parent never leaves a comment attached to an already-hidden parent (Fund #6)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-concurrency-comment-hide@example.org")
                mintLtr(author, BigDecimal("10.00"))
                val parentId =
                    client
                        .post("/test/create-post?content=Parent&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdPostIds += Uuid.parse(parentId)

                // The Post-row FOR UPDATE lock both createComment and hideOwnPost take on the SAME
                // parent row (Lock-Reihenfolge Post -> Member) fully serializes them: whichever
                // transaction wins the lock commits before the other can even read the row. So
                // createComment either sees the parent still VISIBLE (succeeds -- the comment was
                // created strictly BEFORE the hide) or already HIDDEN_BY_AUTHOR (fails with
                // Conflict). There is no third outcome where a comment is inserted while its parent
                // already carries a non-VISIBLE state.
                val results =
                    runBlocking {
                        withTimeout(20_000) {
                            listOf(
                                async {
                                    runCatching {
                                        client.post("/test/create-comment?parentId=$parentId&content=Race&weight=1.00") {
                                            header("X-Member-Id", author.toString())
                                        }
                                    }
                                },
                                async {
                                    runCatching {
                                        client.post("/test/hide-post/$parentId") { header("X-Member-Id", author.toString()) }
                                    }
                                },
                            ).awaitAll()
                        }
                    }
                val commentResult = results[0].getOrThrow()
                val hideResult = results[1].getOrThrow()

                // hideOwnPost is called by the post's own author and is not rate-limited in this
                // fixture, so it always succeeds regardless of ordering relative to the comment.
                hideResult.status shouldBe HttpStatusCode.OK

                if (commentResult.status == HttpStatusCode.OK) {
                    createdPostIds += Uuid.parse(commentResult.bodyAsText().split(":")[0])
                } else {
                    commentResult.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test(
            "getThread: a subtree larger than THREAD_MAX_NODES stays truncated but keeps its root reachable (Fund #2 -- a naive publishedAt-DESC cut previously dropped the oldest row, which is always the root, producing an EMPTY thread instead of a truncated one)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSocialExceptionHandlers() }
                    routing { registerSocialNetworkTestRoutes(createRateLimiter = generousLimiter()) }
                }
                val author = createTestMember("social-thread-overflow@example.org")
                mintLtr(author, BigDecimal("5.00"))
                val rootId =
                    client
                        .post("/test/create-post?content=Root&weight=1.00&visibility=PUBLIC") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val rootUuid = Uuid.parse(rootId)
                createdPostIds += rootUuid

                // The root is made the OLDEST row in its own subtree -- with the pre-fix
                // `publishedAt DESC` cut, exactly THIS row is the first one a "keep the newest N"
                // truncation would drop.
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq rootUuid }) { it[publishedAt] = LocalDateTime(2020, 1, 1, 0, 0, 0) }
                }

                // Seed exactly THREAD_MAX_NODES direct children (total subtree = THREAD_MAX_NODES +
                // 1 = one more row than loadSubtreeRows' own query limit for getThread) via a direct
                // batch insert -- creating this many rows through the rate-limited/LTR-staking HTTP
                // path would be far too slow for a test. Each child's publishedAt is strictly AFTER
                // the root's, so the pre-fix `.take(THREAD_MAX_NODES)` over a `publishedAt DESC` list
                // would keep exactly these newest rows and drop the root.
                val childBase = LocalDateTime(2020, 1, 2, 0, 0, 0)
                val childIds = (0 until SocialPostWeight.THREAD_MAX_NODES).map { Uuid.random() }
                transaction {
                    SocialPostTable.batchInsert(childIds.withIndex().toList(), shouldReturnGeneratedValues = false) { (index, childId) ->
                        this[SocialPostTable.id] = childId
                        this[SocialPostTable.parentId] = rootUuid
                        this[SocialPostTable.rootId] = rootUuid
                        this[SocialPostTable.depth] = 1
                        this[SocialPostTable.authorMemberId] = author
                        this[SocialPostTable.content] = "seeded"
                        this[SocialPostTable.visibility] = SocialPostVisibility.PUBLIC
                        this[SocialPostTable.initialWeightLtr] = BigDecimal("0.01")
                        this[SocialPostTable.publishedAt] =
                            (childBase.toInstant(TimeZone.UTC) + index.seconds).toLocalDateTime(TimeZone.UTC)
                        this[SocialPostTable.state] = SocialPostState.VISIBLE
                        this[SocialPostTable.stateChangedAt] = null
                        this[SocialPostTable.stateChangedBy] = null
                        this[SocialPostTable.stateReason] = null
                    }
                }
                createdPostIds += childIds

                val threadResponse = client.get("/test/get-thread/$rootId") { header("X-Member-Id", author.toString()) }
                threadResponse.status shouldBe HttpStatusCode.OK
                val bodyParts = threadResponse.bodyAsText().split(":", limit = 3)
                val truncated = bodyParts[0]
                val totalNodeCount = bodyParts[1]
                val nodesEncoded = bodyParts[2]

                truncated shouldBe "true"
                totalNodeCount shouldBe "${SocialPostWeight.THREAD_MAX_NODES + 1}" // capped by loadSubtreeRows' own query limit
                val nodeCount = nodesEncoded.split(",").filter { it.isNotBlank() }.size
                nodeCount shouldBe SocialPostWeight.THREAD_MAX_NODES

                // The critical assertion: before the fix, buildPreorder found no `parentId == null`
                // row at all (the root had been silently dropped by the truncation) and returned an
                // EMPTY node list here -- an oversized-but-real thread masquerading as an empty one.
                nodesEncoded.startsWith(rootId) shouldBe true
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
 * generous throwaway limiters here) -- production wiring (`Application.kt`) always passes THREE
 * DIFFERENT module-scoped instances, see [SocialNetworkService] KDoc. [boostRateLimiter] defaults
 * to [createRateLimiter] too for the same reason -- only the dedicated `G-3`-style rate-limit tests
 * pass a deliberately sharp one.
 */
private fun Route.registerSocialNetworkTestRoutes(
    createRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter = createRateLimiter,
    boostRateLimiter: FederationInboxRateLimiter = createRateLimiter,
) {
    fun service(call: io.ktor.server.application.ApplicationCall) =
        SocialNetworkService(
            call = call,
            createRateLimiter = createRateLimiter,
            readRateLimiter = readRateLimiter,
            boostRateLimiter = boostRateLimiter,
        )
    post("/test/create-post") {
        val service = service(call)
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
    post("/test/create-comment") {
        val service = service(call)
        val q = call.request.queryParameters
        val p =
            service.createComment(
                SocialCommentInput(
                    parentId = q["parentId"]!!,
                    content = q["content"] ?: "Testkommentar",
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        // id:parentId:rootId:depth:visibility:ownWeight:totalWeight
        call.respondText(
            "${p.id}:${p.parentId}:${p.rootId}:${p.depth}:${p.visibility}:${p.ownCurrentWeightLtr}:${p.totalCurrentWeightLtr}",
        )
    }
    post("/test/boost/{id}") {
        val service = service(call)
        val amount = BigDecimal(call.request.queryParameters["amount"] ?: "1.00")
        val p = service.boostPost(postId = call.parameters["id"]!!, amountLtr = amount)
        call.respondText("${p.id}:${p.boostCount}:${p.ownCurrentWeightLtr}:${p.totalCurrentWeightLtr}")
    }
    get("/test/get-thread/{rootId}") {
        val service = service(call)
        val thread = service.getThread(call.parameters["rootId"]!!)
        // truncated:totalNodeCount:id1(depth1;total1),id2(depth2;total2),...  -- preorder, one entry
        // per NODE (not per LEVEL): [SocialThreadDto.nodes] is already the flat preorder list.
        val nodesEncoded =
            thread.nodes.joinToString(",") { node -> "${node.id}(${node.depth};${node.totalCurrentWeightLtr})" }
        call.respondText("${thread.truncated}:${thread.totalNodeCount}:$nodesEncoded")
    }
    get("/test/get-post/{id}") {
        val service = service(call)
        val p = service.getPost(call.parameters["id"]!!)
        // authorFreeBalanceLtr appended for the S-1 (Security-Audit Runde 1, 2026-08-18) test --
        // "null" (string) when the viewer is not ORGANIZATION_MEMBER, see SocialNetworkService
        // .toDtos KDoc. totalCurrentWeightLtr/directCommentCount/boostCount appended for Welle
        // V1.1.2's own tests.
        call.respondText(
            "${p.id}:${p.state}:${p.authorFreeBalanceLtr ?: "null"}:${p.totalCurrentWeightLtr}:" +
                "${p.directCommentCount}:${p.totalDescendantCount}:${p.boostCount}",
        )
    }
    get("/test/list-timeline") {
        val service = service(call)
        val authorFilter = call.request.queryParameters["author"]
        val includeHidden = call.request.queryParameters["includeHidden"]?.toBoolean() ?: false
        val parentId = call.request.queryParameters["parentId"]
        val page =
            service.listTimeline(
                SocialTimelineQuery(limit = 100, authorMemberId = authorFilter, includeHidden = includeHidden, parentId = parentId),
            )
        // Plain comma-separated ids, IN RANKING ORDER (existing tests parse this as an unordered
        // Set; ranking-order tests below parse the same string as an ordered List instead).
        call.respondText(page.posts.joinToString(",") { it.id })
    }
    // Fund #3 (Review Runde 1, 2026-08-18): the plain /test/list-timeline route above never exposed
    // totalCurrentWeightLtr/totalDescendantCount, so the "Ranking-Horizont für Nachfahren" test
    // could only assert isNotBlank() -- a comment there falsely claimed a targeted assertion. This
    // dedicated route exposes id:weight:totalDescendantCount triples so that test (and any future
    // one) can assert an EXACT value.
    get("/test/list-timeline-weighted") {
        val service = service(call)
        val authorFilter = call.request.queryParameters["author"]
        val includeHidden = call.request.queryParameters["includeHidden"]?.toBoolean() ?: false
        val page =
            service.listTimeline(SocialTimelineQuery(limit = 100, authorMemberId = authorFilter, includeHidden = includeHidden))
        call.respondText(page.posts.joinToString(",") { "${it.id}:${it.totalCurrentWeightLtr}:${it.totalDescendantCount}" })
    }
    post("/test/hide-post/{id}") {
        val service = service(call)
        val p = service.hideOwnPost(call.parameters["id"]!!)
        call.respondText(p.state.name)
    }
    get("/test/free-balance") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
}
