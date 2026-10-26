package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.GalleryLockedException
import network.lapis.cloud.shared.rpc.GalleryOrderMismatchException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val AUCTION = AttachedImageOwnerType.AUCTION_LISTING
private val PROJECT = AttachedImageOwnerType.CROWDFUNDING_PROJECT

/**
 * V1.9.222 -- the gallery on H2 AND PostgreSQL: order and cover, the limits, the two freezing rules, moderation, IDOR, the races (the
 * ninth
 * picture,
 * a bid against an upload), DSGVO and the orphan hook/sweep. The Postgres lane is where the unique index (owner, position) is NOT
 * deferrable: a swap
 * of two pictures must work there too (two-phase re-order).
 */
abstract class AttachedImageScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures(limits = GalleryLimits(maxBytesPerOwner = 5_000))

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setAuctionEnabled(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun ids(rows: List<AttachedImageRow>) = rows.map { it.id.toString() }

        test("pictures are listed in gallery order, the first one is the cover, and the creator may edit") {
            val seller = fx.member(label = "seller")
            val auction = fx.auction(seller = seller)
            val rows = (1..3).map { fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller)) }
            rows.map { it.position } shouldContainExactly listOf(0, 1, 2)
            galleryTestApplication(fixtures = fx) {
                val gallery = call(member = seller) { service(it).list(ownerType = AUCTION, ownerId = auction.toString()) }
                ids(rows) shouldContainExactly gallery.images.map { it.id }
                gallery.canEdit shouldBe true
                gallery.lockReason shouldBe null
                gallery.images.first().position shouldBe 0
                gallery.images.all { it.uploadedByMe } shouldBe true
            }
        }

        test("a re-order is a full permutation, a swap of the first two works, a partial or foreign list is refused") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            val rows = (1..4).map { fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller)) }
            galleryTestApplication(fixtures = fx) {
                val swapped = listOf(rows[1], rows[0], rows[2], rows[3]).map { it.id.toString() }
                call(member = seller) {
                    service(it).reorder(ownerType = PROJECT, ownerId = project.toString(), orderedIds = swapped)
                }.images.map { it.id } shouldContainExactly
                    swapped
                fx.rows(type = PROJECT, ownerId = project).map { it.position } shouldContainExactly listOf(0, 1, 2, 3)
                val reversed = swapped.reversed()
                call(member = seller) {
                    service(it).reorder(ownerType = PROJECT, ownerId = project.toString(), orderedIds = reversed)
                }.images.map { it.id } shouldContainExactly
                    reversed
                attempt(
                    member = seller,
                ) { service(it).reorder(ownerType = PROJECT, ownerId = project.toString(), orderedIds = reversed.drop(1)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<GalleryOrderMismatchException>()
                attempt(member = seller) {
                    service(it).reorder(
                        ownerType = PROJECT,
                        ownerId = project.toString(),
                        orderedIds =
                            reversed.dropLast(1) + Uuid.random().toString(),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<GalleryOrderMismatchException>()
                attempt(member = seller) {
                    service(it).reorder(
                        ownerType = PROJECT,
                        ownerId = project.toString(),
                        orderedIds =
                            reversed + reversed.first(),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<GalleryOrderMismatchException>()
                fx.rows(type = PROJECT, ownerId = project).map { it.id.toString() } shouldContainExactly reversed
            }
        }

        test("setCover moves the fifth picture to the front and the others follow without a gap") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            val rows = (1..5).map { fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller), byteSize = 500) }
            galleryTestApplication(fixtures = fx) {
                val gallery = call(member = seller) { service(it).setCover(rows[4].id.toString()) }
                gallery.images.map { it.id } shouldContainExactly
                    listOf(rows[4], rows[0], rows[1], rows[2], rows[3]).map { it.id.toString() }
                gallery.images.map { it.position } shouldContainExactly listOf(0, 1, 2, 3, 4)
            }
        }

        test("removing the cover closes the gap, the next picture becomes the cover, and the file is gone") {
            val seller = fx.member(label = "seller")
            val auction = fx.auction(seller = seller)
            val rows = (1..3).map { fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller)) }
            val before = fx.filesOnDisk()
            galleryTestApplication(fixtures = fx) {
                val gallery = call(member = seller) { service(it).delete(rows[0].id.toString()) }
                gallery.images.map { it.id } shouldContainExactly listOf(rows[1].id.toString(), rows[2].id.toString())
                gallery.images.map { it.position } shouldContainExactly listOf(0, 1)
            }
            fx.filesOnDisk() shouldBe before - 1
            fx.storage.resolve(storageKey = rows[0].storageKey, mimeType = "image/jpeg") shouldBe null
        }

        test("the per-owner limits: the eleventh picture is refused, so are more bytes than allowed") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            (1..10).forEach { fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller), byteSize = 10) }
            fx
                .tryAddImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller), byteSize = 10)
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.Full
            fx.rows(type = PROJECT, ownerId = project) shouldHaveSize 10
            val auction = fx.auction(seller = seller)
            fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller), byteSize = 3_000)
            fx
                .tryAddImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller), byteSize = 2_500)
                .shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe GalleryGateResult.BytesExceeded
            fx.rows(type = AUCTION, ownerId = auction) shouldHaveSize 1
        }

        test("auction: the first bid freezes the gallery for the seller -- add, re-order, cover, caption and removal") {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            val auction = fx.auction(seller = seller)
            val rows = (1..2).map { fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller)) }
            fx.bid(auctionId = auction, bidder = bidder)
            fx
                .tryAddImage(
                    type = AUCTION,
                    ownerId = auction,
                    caller = fx.current(memberId = seller),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe
                GalleryGateResult.Locked
            galleryTestApplication(fixtures = fx) {
                val gallery = call(member = seller) { service(it).list(ownerType = AUCTION, ownerId = auction.toString()) }
                gallery.lockReason shouldBe GalleryLockReason.AUCTION_HAS_BIDS
                gallery.canEdit shouldBe false
                val order = rows.map { it.id.toString() }.reversed()
                attempt(
                    member = seller,
                ) {
                    service(it).reorder(ownerType = AUCTION, ownerId = auction.toString(), orderedIds = order)
                }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
                attempt(
                    member = seller,
                ) { service(it).setCover(rows[1].id.toString()) }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
                attempt(member = seller) { service(it).updateCaption(imageId = rows[0].id.toString(), caption = "x", altText = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<GalleryLockedException>()
                attempt(
                    member = seller,
                ) { service(it).delete(rows[0].id.toString()) }.exceptionOrNull().shouldBeInstanceOf<GalleryLockedException>()
            }
            fx.rows(type = AUCTION, ownerId = auction) shouldHaveSize 2
        }

        test("auction: an expired or settled auction is frozen as well, and the lock is read-only (no lazy close, no settlement)") {
            val seller = fx.member(label = "seller")
            val past =
                DbClock
                    .nowLocalDateTime()
                    .toInstant(TimeZone.UTC)
                    .minus(2.days)
                    .toLocalDateTime(TimeZone.UTC)
            val expired = fx.auction(seller = seller, endsAt = past)
            fx
                .tryAddImage(
                    type = AUCTION,
                    ownerId = expired,
                    caller = fx.current(memberId = seller),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe
                GalleryGateResult.Locked
            val settled = fx.auction(seller = seller, status = AuctionStatus.SETTLED)
            fx
                .tryAddImage(
                    type = AUCTION,
                    ownerId = settled,
                    caller = fx.current(memberId = seller),
                ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                .gate shouldBe
                GalleryGateResult.Locked
            galleryTestApplication(fixtures = fx) {
                call(member = seller) { service(it).list(ownerType = AUCTION, ownerId = expired.toString()) }.lockReason shouldBe
                    GalleryLockReason.AUCTION_CLOSED
            }
            // the overdue row is still OPEN in the database: the gallery did not close it
            transaction {
                network.lapis.cloud.server.db.generated.AuctionTable
                    .selectAll()
                    .where { network.lapis.cloud.server.db.generated.AuctionTable.id eq expired }
                    .single()[network.lapis.cloud.server.db.generated.AuctionTable.status] shouldBe AuctionStatus.OPEN
            }
        }

        test("crowdfunding: a decision (approved, rejected) and silence-is-approval freeze the gallery, a pending project stays open") {
            val submitter = fx.member(label = "submitter")
            val pending = fx.project(submitter = submitter)
            fx.addImage(type = PROJECT, ownerId = pending, caller = fx.current(memberId = submitter))
            val old =
                DbClock
                    .nowLocalDateTime()
                    .toInstant(TimeZone.UTC)
                    .minus(30.days)
                    .toLocalDateTime(TimeZone.UTC)
            val approved = fx.project(submitter = submitter, status = CrowdfundingProjectStatus.APPROVED)
            val rejected = fx.project(submitter = submitter, status = CrowdfundingProjectStatus.REJECTED)
            val silent = fx.project(submitter = submitter, submittedAt = old) // stored PENDING, but the review window is over
            listOf(approved, rejected, silent).forEach {
                fx
                    .tryAddImage(
                        type = PROJECT,
                        ownerId = it,
                        caller = fx.current(memberId = submitter),
                    ).shouldBeInstanceOf<GalleryCommitResult.Refused>()
                    .gate shouldBe
                    GalleryGateResult.Locked
            }
            galleryTestApplication(fixtures = fx) {
                call(member = submitter) { service(it).list(ownerType = PROJECT, ownerId = silent.toString()) }.lockReason shouldBe
                    GalleryLockReason.CROWDFUNDING_DECIDED
                call(member = submitter) { service(it).list(ownerType = PROJECT, ownerId = pending.toString()) }.lockReason shouldBe null
            }
        }

        test("moderation: BOARD and ADMIN remove a picture even while frozen (audited with the moderator), but cannot add or re-order") {
            val seller = fx.member(label = "seller")
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val auction = fx.auction(seller = seller)
            val rows = (1..3).map { fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller)) }
            fx.bid(auctionId = auction, bidder = fx.member(label = "bidder"))
            galleryTestApplication(fixtures = fx) {
                attempt(member = board) {
                    service(it).reorder(
                        ownerType = AUCTION,
                        ownerId = auction.toString(),
                        orderedIds =
                            rows
                                .map { r ->
                                    r.id.toString()
                                }.reversed(),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                val view = call(member = board) { service(it).list(ownerType = AUCTION, ownerId = auction.toString()) }
                view.canModerate shouldBe true
                view.canEdit shouldBe false
                call(member = board) { service(it).delete(rows[0].id.toString()) }.images shouldHaveSize 2
                call(member = admin) { service(it).delete(rows[1].id.toString()) }.images shouldHaveSize 1
            }
            val removed =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.ATTACHED_IMAGE) and
                                (AuditLogEntryTable.entityId eq rows[0].id) and
                                (AuditLogEntryTable.action eq AuditAction.VOID)
                        }.single()
                }
            removed[AuditLogEntryTable.action] shouldBe AuditAction.VOID
            removed[AuditLogEntryTable.beforeSnapshot]!!.contains(board.toString()) shouldBe true
        }

        test("IDOR: a foreign member is refused, an invisible owner and an unknown id are the same NotFound") {
            val seller = fx.member(label = "seller")
            val other = fx.member(label = "other")
            val auction = fx.auction(seller = seller)
            val row = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller))
            fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller))
            galleryTestApplication(fixtures = fx) {
                attempt(member = other) { service(it).delete(row.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(member = other) { service(it).updateCaption(imageId = row.id.toString(), caption = "mine now", altText = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                attempt(
                    member = other,
                ) { service(it).setCover(row.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(
                    member = other,
                ) { service(it).delete(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = other) { service(it).delete("not-a-uuid") }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                // the auction is switched off: list AND picture ids are NotFound for everybody, the seller included
                fx.setAuctionEnabled(false)
                attempt(member = seller) {
                    service(it).list(ownerType = AUCTION, ownerId = auction.toString())
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = seller) { service(it).delete(row.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = fx.member(label = "board2", role = AccountRole.BOARD)) { service(it).delete(row.id.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("captions are trimmed and cleaned, blank becomes null, text beyond 200 characters is refused") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            val row = fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller))
            galleryTestApplication(fixtures = fx) {
                val ok =
                    call(
                        member = seller,
                    ) { service(it).updateCaption(imageId = row.id.toString(), caption = "  Vorderansicht\u0007 ", altText = "   ") }
                ok.images.single().caption shouldBe "Vorderansicht"
                ok.images.single().altText shouldBe null
                attempt(
                    member = seller,
                ) { service(it).updateCaption(imageId = row.id.toString(), caption = "x".repeat(201), altText = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
            }
        }

        test("race: two uploads for the last free slot of ten -- exactly one wins, positions stay unique") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            (1..9).forEach { fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller), byteSize = 10) }
            val results =
                runBlocking {
                    (1..2)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { fx.tryAddImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller), byteSize = 10) }
                        }.awaitAll()
                }
            results.count { it is GalleryCommitResult.Added } shouldBe 1
            results.filterIsInstance<GalleryCommitResult.Refused>().single().gate shouldBe GalleryGateResult.Full
            fx.rows(type = PROJECT, ownerId = project).map { it.position } shouldContainExactly (0..9).toList()
        }

        test("race: a bid that holds the auction row wins over a waiting upload -- the upload is refused as locked") {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            val auction = fx.auction(seller = seller)
            val result =
                runBlocking {
                    val bidTx =
                        async(Dispatchers.IO) {
                            transaction {
                                network.lapis.cloud.server.db.generated.AuctionTable
                                    .selectAll()
                                    .where { network.lapis.cloud.server.db.generated.AuctionTable.id eq auction }
                                    .forUpdate()
                                    .single()
                                // joins this transaction: visible to others only after the commit below
                                fx.bid(auctionId = auction, bidder = bidder)
                                Thread.sleep(400)
                            }
                        }
                    withContext(Dispatchers.IO) { Thread.sleep(150) }
                    val upload =
                        async(Dispatchers.IO) { fx.tryAddImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller)) }
                    bidTx.await()
                    upload.await()
                }
            result.shouldBeInstanceOf<GalleryCommitResult.Refused>().gate shouldBe GalleryGateResult.Locked
            fx.rows(type = AUCTION, ownerId = auction) shouldHaveSize 0
        }

        test("audit: one CREATE per added and one VOID per removed picture, without caption, file name or storage key") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            val row = fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller))
            galleryTestApplication(fixtures = fx) {
                call(
                    member = seller,
                ) { service(it).updateCaption(imageId = row.id.toString(), caption = "GEHEIME BESCHRIFTUNG", altText = "alt") }
                call(member = seller) { service(it).delete(row.id.toString()) }
            }
            val entries =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.ATTACHED_IMAGE) and
                                (AuditLogEntryTable.entityId eq row.id)
                        }.orderBy(AuditLogEntryTable.sequenceNumber)
                        .toList()
                }
            entries.map { it[AuditLogEntryTable.action] } shouldContainExactly listOf(AuditAction.CREATE, AuditAction.VOID)
            val text = entries.joinToString { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
            text shouldNotContain "GEHEIME"
            text shouldNotContain row.storageKey.toString()
        }

        test("the orphan hook removes every picture of a deleted owner and returns exactly its files; another owner's pictures stay") {
            val seller = fx.member(label = "seller")
            val a = fx.project(submitter = seller)
            val b = fx.project(submitter = seller)
            val rowsA = (1..2).map { fx.addImage(type = PROJECT, ownerId = a, caller = fx.current(memberId = seller)) }
            fx.addImage(type = PROJECT, ownerId = b, caller = fx.current(memberId = seller))
            val removed = transaction { AttachedImageStore.deleteAllForOwnerInTx(type = PROJECT, ownerId = a) }
            removed.map { it.first } shouldContainExactly rowsA.map { it.storageKey }
            fx.storage.deleteAfterCommit(removed.map { it.first })
            fx.rows(type = PROJECT, ownerId = a) shouldHaveSize 0
            fx.rows(type = PROJECT, ownerId = b) shouldHaveSize 1
            rowsA.forEach { fx.storage.resolve(storageKey = it.storageKey, mimeType = "image/jpeg") shouldBe null }
        }

        test("the start sweep deletes an old file nobody points to, keeps a young one, a referenced one and a stranger's file") {
            val seller = fx.member(label = "seller")
            val project = fx.project(submitter = seller)
            val referenced = fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = seller))
            val oldOrphan = Uuid.random()
            val youngOrphan = Uuid.random()
            fx.storage.write(storageKey = oldOrphan, format = network.lapis.cloud.server.events.CoverImageFormat.PNG, bytes = ByteArray(10))
            fx.storage.write(
                storageKey = youngOrphan,
                format = network.lapis.cloud.server.events.CoverImageFormat.JPEG,
                bytes = ByteArray(10),
            )
            val stranger = java.io.File(fx.root, "notes.txt").apply { writeText("keep") }
            val tmp = java.io.File(fx.root, "$oldOrphan.png.tmp").apply { writeText("keep") }
            java.io.File(fx.root, "$oldOrphan.png").setLastModified(System.currentTimeMillis() - 3 * 24 * 3600 * 1000L)
            java.io.File(fx.root, "${referenced.storageKey}.jpg").setLastModified(System.currentTimeMillis() - 3 * 24 * 3600 * 1000L)
            AttachedImageOrphanSweep.sweep(storage = fx.storage) shouldBe 1
            fx.storage.resolve(storageKey = oldOrphan, mimeType = "image/png") shouldBe null
            fx.storage.resolve(storageKey = youngOrphan, mimeType = "image/jpeg") shouldBe java.io.File(fx.root, "$youngOrphan.jpg")
            fx.storage.resolve(storageKey = referenced.storageKey, mimeType = "image/jpeg") shouldBe
                java.io.File(fx.root, "${referenced.storageKey}.jpg")
            stranger.exists() shouldBe true
            tmp.exists() shouldBe true
            fx.storage.delete(youngOrphan)
            stranger.delete()
            tmp.delete()
        }
    })
