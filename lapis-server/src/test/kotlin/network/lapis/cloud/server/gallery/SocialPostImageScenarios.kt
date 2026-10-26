package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.SOCIAL_MODERATION_ROLES
import network.lapis.cloud.server.rpc.SocialNetworkService
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.GalleryLockedException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val SOCIAL = AttachedImageOwnerType.SOCIAL_POST

private fun minutesAgo(minutes: Int): LocalDateTime =
    DbClock
        .nowLocalDateTime()
        .toInstant(TimeZone.UTC)
        .minus(minutes.minutes)
        .toLocalDateTime(TimeZone.UTC)

/**
 * V1.9.223 -- pictures of a ROOT social post on H2 and PostgreSQL: the author's 10-minute window, the limit of four, visibility per
 * viewer, the states that hide a post's pictures, IDOR, the preview RPC, the gate's membership rule and the races.
 */
abstract class SocialPostImageScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun author(status: MemberStatus = MemberStatus.ACTIVE) = fx.member(label = "author", status = status)

        test(
            "the author adds pictures in the window, lists them in order and removes one; positions stay gap-free and the audit is written",
        ) {
            val a = author()
            val post = fx.socialPost(author = a)
            val rows = (1..3).map { fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a)) }
            rows.map { it.position } shouldContainExactly listOf(0, 1, 2)
            galleryTestApplication(fixtures = fx) {
                val gallery = call(member = a) { service(it).list(ownerType = SOCIAL, ownerId = post.toString()) }
                gallery.images.map { it.id } shouldContainExactly rows.map { it.id.toString() }
                gallery.canEdit shouldBe true
                gallery.lockReason shouldBe null
                gallery.maxImages shouldBe GalleryRules.SOCIAL_POST_MAX_IMAGES
                val after = call(member = a) { service(it).delete(rows[0].id.toString()) }
                after.images.map { it.position } shouldContainExactly listOf(0, 1)
            }
            val actions =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.ATTACHED_IMAGE) and
                                (AuditLogEntryTable.entityId eq rows[0].id)
                        }.orderBy(AuditLogEntryTable.sequenceNumber)
                        .map { it[AuditLogEntryTable.action] }
                }
            actions shouldContainExactly listOf(AuditAction.CREATE, AuditAction.VOID)
        }

        test("at most four pictures; a fifth is refused as full, and two parallel uploads for the last slot give exactly four") {
            val a = author()
            val post = fx.socialPost(author = a)
            (1..4).forEach { fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a)) }
            fx
                .tryAddImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a))
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Full
            val racing = fx.socialPost(author = a)
            (1..3).forEach { fx.addImage(type = SOCIAL, ownerId = racing, caller = fx.current(memberId = a)) }
            val results =
                runBlocking {
                    (1..2)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { fx.tryAddImage(type = SOCIAL, ownerId = racing, caller = fx.current(memberId = a)) }
                        }.awaitAll()
                }
            results.count { it is GalleryCommitResult.Added } shouldBe 1
            fx.rows(type = SOCIAL, ownerId = racing).map { it.position } shouldContainExactly listOf(0, 1, 2, 3)
        }

        test(
            "window: allowed at nine minutes, frozen at ten for upload and for the author's own removal; BOARD may still remove, audited",
        ) {
            val a = author()
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val young = fx.socialPost(author = a, publishedAt = minutesAgo(9))
            fx.addImage(type = SOCIAL, ownerId = young, caller = fx.current(memberId = a))
            val old = fx.socialPost(author = a, publishedAt = minutesAgo(10))
            fx
                .tryAddImage(type = SOCIAL, ownerId = old, caller = fx.current(memberId = a))
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Locked
            // a picture that exists when the window closes
            val lateRow = fx.addImage(type = SOCIAL, ownerId = young, caller = fx.current(memberId = a))
            transaction {
                SocialPostTable.update({ SocialPostTable.id eq young }) { it[publishedAt] = minutesAgo(11) }
            }
            galleryTestApplication(fixtures = fx) {
                val view = call(member = a) { service(it).list(ownerType = SOCIAL, ownerId = young.toString()) }
                view.lockReason shouldBe GalleryLockReason.SOCIAL_POST_PUBLISHED
                view.canEdit shouldBe false
                attempt(
                    member = a,
                ) { service(it).delete(lateRow.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
                call(member = board) { service(it).delete(lateRow.id.toString()) }.images shouldHaveSize 1
            }
            val removed =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq lateRow.id) and (AuditLogEntryTable.action eq AuditAction.VOID)
                        }.single()
                }
            removed[AuditLogEntryTable.beforeSnapshot]!! shouldContain board.toString()
        }

        test("re-order and cover never exist for a social post, not even inside the window") {
            val a = author()
            val post = fx.socialPost(author = a)
            val rows = (1..2).map { fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a)) }
            galleryTestApplication(fixtures = fx) {
                attempt(member = a) {
                    service(it).reorder(
                        ownerType = SOCIAL,
                        ownerId = post.toString(),
                        orderedIds =
                            rows
                                .map { r ->
                                    r.id.toString()
                                }.reversed(),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
                attempt(
                    member = a,
                ) { service(it).setCover(rows[1].id.toString()) }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
            }
            fx.rows(type = SOCIAL, ownerId = post).map { it.id } shouldContainExactly rows.map { it.id }
        }

        test("captions are refused, the alt text can be set exactly once and only inside the window") {
            val a = author()
            val post = fx.socialPost(author = a)
            val row = fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a))
            galleryTestApplication(fixtures = fx) {
                attempt(member = a) { service(it).updateCaption(imageId = row.id.toString(), caption = "Beschriftung", altText = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                call(member = a) { service(it).updateCaption(imageId = row.id.toString(), caption = null, altText = "Ein Hund") }
                    .images
                    .single()
                    .altText shouldBe "Ein Hund"
                attempt(member = a) { service(it).updateCaption(imageId = row.id.toString(), caption = null, altText = "Eine Katze") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<GalleryLockedException>()
                val lateRow = fx.addImage(type = SOCIAL, ownerId = fx.socialPost(author = a), caller = fx.current(memberId = a))
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq lateRow.ownerId }) { it[publishedAt] = minutesAgo(30) }
                }
                attempt(member = a) { service(it).updateCaption(imageId = lateRow.id.toString(), caption = null, altText = "zu spät") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<GalleryLockedException>()
            }
        }

        test("a comment is no owner: gate, list and preview treat it as unknown") {
            val a = author()
            val root = fx.socialPost(author = a)
            val comment = fx.socialPost(author = a, parentId = root)
            fx
                .tryAddImage(type = SOCIAL, ownerId = comment, caller = fx.current(memberId = a))
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.NotFound
            galleryTestApplication(fixtures = fx) {
                attempt(member = a) { service(it).list(ownerType = SOCIAL, ownerId = comment.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                fx.addImage(type = SOCIAL, ownerId = root, caller = fx.current(memberId = a))
                call(member = a) { service(it).listSocialPostPreviews(listOf(comment.toString())) } shouldHaveSize 0
            }
        }

        test("visibility matrix per viewer status for list and for the preview RPC") {
            val a = author()
            val pub = fx.socialPost(author = a, visibility = SocialPostVisibility.PUBLIC)
            val ext = fx.socialPost(author = a, visibility = SocialPostVisibility.MEMBERS_AND_EXTERNAL)
            val mem = fx.socialPost(author = a, visibility = SocialPostVisibility.MEMBERS_ONLY)
            listOf(pub, ext, mem).forEach { fx.addImage(type = SOCIAL, ownerId = it, caller = fx.current(memberId = a)) }
            val active = fx.member(label = "active")
            val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
            val applicant = fx.member(label = "applicant", status = MemberStatus.APPLICATION)
            val expected =
                mapOf(
                    active to setOf(pub, ext, mem),
                    friend to setOf(pub, ext),
                    applicant to setOf(pub),
                )
            galleryTestApplication(fixtures = fx) {
                expected.forEach { (viewer, visible) ->
                    listOf(pub, ext, mem).forEach { post ->
                        val result = attempt(member = viewer) { service(it).list(ownerType = SOCIAL, ownerId = post.toString()) }
                        if (post in visible) {
                            result.getOrThrow().images shouldHaveSize 1
                        } else {
                            result.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                        }
                    }
                    val previews =
                        call(member = viewer) { service(it).listSocialPostPreviews(listOf(pub, ext, mem).map { p -> p.toString() }) }
                    previews.map { it.postId }.toSet() shouldBe visible.map { it.toString() }.toSet()
                }
            }
        }

        test("hidden, legally removed and content-erased posts reveal no picture; the rows and files stay for the legal removal") {
            val a = author()
            val viewer = fx.member(label = "viewer")
            val hidden = fx.socialPost(author = a)
            val removed = fx.socialPost(author = a)
            val erased = fx.socialPost(author = a)
            val rowsRemoved =
                listOf(
                    hidden,
                    removed,
                    erased,
                ).map { fx.addImage(type = SOCIAL, ownerId = it, caller = fx.current(memberId = a)) }
            fx.setPostState(postId = hidden, state = SocialPostState.HIDDEN_BY_AUTHOR)
            fx.setPostState(postId = removed, state = SocialPostState.REMOVED_LEGAL)
            transaction {
                SocialPostTable.update({ SocialPostTable.id eq erased }) { it[contentErasedAt] = DbClock.nowLocalDateTime() }
            }
            galleryTestApplication(fixtures = fx) {
                listOf(hidden, removed, erased).forEach { post ->
                    listOf(viewer, a).forEach { who ->
                        attempt(member = who) { service(it).list(ownerType = SOCIAL, ownerId = post.toString()) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<NotFoundException>()
                    }
                }
                call(member = viewer) {
                    service(it).listSocialPostPreviews(
                        listOf(hidden, removed, erased).map { p ->
                            p.toString()
                        },
                    )
                } shouldHaveSize
                    0
            }
            fx.rows(type = SOCIAL, ownerId = removed) shouldHaveSize 1
            fx.storage.resolve(storageKey = rowsRemoved[1].storageKey, mimeType = "image/jpeg") shouldBe
                fx.root.resolve("${rowsRemoved[1].storageKey}.jpg")
        }

        test("IDOR: a foreign member cannot add or remove; one who may not see the post gets NotFound, byte-identical to an unknown id") {
            val a = author()
            val other = fx.member(label = "other")
            val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
            val open = fx.socialPost(author = a)
            val closed = fx.socialPost(author = a, visibility = SocialPostVisibility.MEMBERS_ONLY)
            val openRow = fx.addImage(type = SOCIAL, ownerId = open, caller = fx.current(memberId = a))
            val closedRow = fx.addImage(type = SOCIAL, ownerId = closed, caller = fx.current(memberId = a))
            fx
                .tryAddImage(type = SOCIAL, ownerId = open, caller = fx.current(memberId = other))
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Forbidden
            fx
                .tryAddImage(type = SOCIAL, ownerId = closed, caller = fx.current(memberId = friend, status = MemberStatus.FRIEND))
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.NotFound
            galleryTestApplication(fixtures = fx) {
                attempt(
                    member = other,
                ) { service(it).delete(openRow.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                val hiddenFromFriend = attempt(member = friend) { service(it).delete(closedRow.id.toString()) }.exceptionOrNull()
                val unknown = attempt(member = friend) { service(it).delete(Uuid.random().toString()) }.exceptionOrNull()
                hiddenFromFriend.shouldBeInstanceOf<NotFoundException>()
                hiddenFromFriend.message shouldBe unknown.shouldBeInstanceOf<NotFoundException>().message
            }
            fx.rows(type = SOCIAL, ownerId = closed) shouldHaveSize 1
        }

        test("the gate: a FRIEND author may attach pictures, an applicant may not; auction and crowdfunding still need an ACTIVE member") {
            val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
            val applicant = fx.member(label = "applicant", status = MemberStatus.APPLICATION)
            val friendPost = fx.socialPost(author = friend)
            fx.addImage(type = SOCIAL, ownerId = friendPost, caller = fx.current(memberId = friend, status = MemberStatus.FRIEND))
            val applicantPost = fx.socialPost(author = applicant)
            fx
                .tryAddImage(
                    type = SOCIAL,
                    ownerId = applicantPost,
                    caller = fx.current(memberId = applicant, status = MemberStatus.APPLICATION),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Forbidden
            fx.setAuctionEnabled(true)
            fx
                .tryAddImage(
                    type = AttachedImageOwnerType.AUCTION_LISTING,
                    ownerId = fx.auction(seller = friend),
                    caller = fx.current(memberId = friend, status = MemberStatus.FRIEND),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Forbidden
            fx
                .tryAddImage(
                    type = AttachedImageOwnerType.CROWDFUNDING_PROJECT,
                    ownerId = fx.project(submitter = friend),
                    caller = fx.current(memberId = friend, status = MemberStatus.FRIEND),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Forbidden
        }

        test("preview RPC: more than 50 ids is refused, bad and foreign ids are ignored, the result is sorted and counts all pictures") {
            val a = author()
            val first = fx.socialPost(author = a)
            val second = fx.socialPost(author = a)
            val empty = fx.socialPost(author = a)
            val firstRows = (1..3).map { fx.addImage(type = SOCIAL, ownerId = first, caller = fx.current(memberId = a)) }
            fx.addImage(type = SOCIAL, ownerId = second, caller = fx.current(memberId = a))
            galleryTestApplication(fixtures = fx) {
                attempt(
                    member = a,
                ) { service(it).listSocialPostPreviews(List(GalleryRules.SOCIAL_PREVIEW_MAX_IDS + 1) { Uuid.random().toString() }) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                val ids = listOf(first, second, empty).map { it.toString() } + listOf("nonsense", Uuid.random().toString())
                val result = call(member = a) { service(it).listSocialPostPreviews(ids) }
                result.map { it.postId } shouldContainExactly listOf(first, second).map { it.toString() }.sorted()
                val byPost = result.associateBy { it.postId }
                byPost.getValue(first.toString()).totalCount shouldBe 3
                byPost.getValue(first.toString()).first.id shouldBe firstRows[0].id.toString()
                byPost.getValue(second.toString()).totalCount shouldBe 1
            }
        }

        test("moderation roles of the gallery are exactly the social moderation roles") {
            AccountRole.entries.forEach { role ->
                GalleryOwnerPolicy.canModerate(fx.current(memberId = Uuid.random(), role = role)) shouldBe (role in SOCIAL_MODERATION_ROLES)
            }
        }

        test("an erasure request for a post removes its pictures at once -- rows, files and audit entries") {
            val a = author()
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val post = fx.socialPost(author = a)
            val rows = (1..2).map { fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = a)) }
            val erasure = fx.approvedErasure(postId = post, requester = a)
            galleryTestApplication(fixtures = fx) {
                call(member = admin) {
                    val limiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 60.minutes)
                    SocialNetworkService(
                        call = it,
                        createRateLimiter = limiter,
                        readRateLimiter = limiter,
                        boostRateLimiter = limiter,
                        moderationRateLimiter = limiter,
                        reportRateLimiter = limiter,
                        attachedImageStorage = fx.storage,
                    ).executeContentErasure(erasure.toString())
                }
            }
            fx.rows(type = SOCIAL, ownerId = post) shouldHaveSize 0
            rows.forEach { fx.storage.resolve(storageKey = it.storageKey, mimeType = "image/jpeg") shouldBe null }
            val voids =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.ATTACHED_IMAGE) and
                                (AuditLogEntryTable.action eq AuditAction.VOID)
                        }.map { it[AuditLogEntryTable.entityId] }
                }
            voids.containsAll(rows.map { it.id }) shouldBe true
            transaction {
                SocialPostTable.selectAll().where { SocialPostTable.id eq post }.single()[SocialPostTable.contentErasedAt]
            }.toString() shouldNotContain "null"
        }
    })
