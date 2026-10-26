package network.lapis.cloud.server.gallery

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SocialPostErasureTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Harness of the V1.9.222 gallery tests. House style of `TenderApp`: ONE generic throwaway route runs a lambda against the call of that
 * request
 * (`X-Member-Id` resolves the caller) and stores the [Result], so tests assert on real exception TYPES. Works on H2 and on PostgreSQL
 * (it
 * never
 * calls `module()`).
 */
internal class GalleryApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
    val fixtures: GalleryFixtures,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/gallery-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): T = attempt(member = member, op = op).getOrThrow()

    /** The gallery service as `member` sees it. */
    fun service(callArg: ApplicationCall) = fixtures.service(callArg)
}

internal fun galleryTestApplication(
    fixtures: GalleryFixtures,
    block: suspend GalleryApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/gallery-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(op(call))
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        GalleryApp(client = client, ops = ops, results = results, fixtures = fixtures).block()
    }
}

/** Fixtures + cleanup of everything a gallery test creates (own rows and own temp directory only). */
internal class GalleryFixtures(
    val limits: GalleryLimits = GalleryLimits(),
    sharedStorage: AttachedImageStorage? = null,
) {
    private val ownsRoot = sharedStorage == null
    val storage: AttachedImageStorage = sharedStorage ?: AttachedImageStorage(Files.createTempDirectory("gallery-test-").toFile())
    val root: File = storage.canonicalRoot
    private val writtenKeys = mutableListOf<Uuid>()
    private val generous = FederationInboxRateLimiter(maxRequests = 100_000, window = 60.minutes)
    val memberIds = mutableListOf<Uuid>()
    val auctionIds = mutableListOf<Uuid>()
    val projectIds = mutableListOf<Uuid>()
    val postIds = mutableListOf<Uuid>()

    fun service(call: ApplicationCall) =
        AttachedImageService(call = call, storage = storage, writeRateLimiter = generous, readRateLimiter = generous)

    fun member(
        label: String = "m",
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Gallery-Test $label"
                it[email] = "gallery-test-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return id
    }

    fun current(
        memberId: Uuid,
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = CurrentMember(memberId = memberId, role = role, status = status)

    /** A social post (root when [parentId] is `null`, else a comment of [parentId]'s thread) with a settable publication time. */
    fun socialPost(
        author: Uuid,
        visibility: SocialPostVisibility = SocialPostVisibility.PUBLIC,
        state: SocialPostState = SocialPostState.VISIBLE,
        parentId: Uuid? = null,
        publishedAt: LocalDateTime = DbClock.nowLocalDateTime(),
        contentErasedAt: LocalDateTime? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            val rootOfParent =
                parentId?.let { parent ->
                    SocialPostTable.selectAll().where { SocialPostTable.id eq parent }.single()[SocialPostTable.rootId]
                }
            SocialPostTable.insert {
                it[SocialPostTable.id] = id
                it[SocialPostTable.parentId] = parentId
                it[rootId] = rootOfParent ?: id
                it[depth] = if (parentId == null) 0 else 1
                it[authorMemberId] = author
                it[content] = "Gallery test post"
                it[SocialPostTable.visibility] = visibility
                it[initialWeightLtr] = BigDecimal("0.01")
                it[SocialPostTable.publishedAt] = publishedAt
                it[SocialPostTable.state] = state
                it[SocialPostTable.contentErasedAt] = contentErasedAt
            }
        }
        postIds += id
        return id
    }

    /** An APPROVED post-related erasure request, ready for `executeContentErasure`. */
    fun approvedErasure(
        postId: Uuid,
        requester: Uuid,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            SocialPostErasureTable.insert {
                it[SocialPostErasureTable.id] = id
                it[SocialPostErasureTable.postId] = postId
                it[requestedAt] = DbClock.nowLocalDateTime()
                it[requestedBy] = requester
                it[subjectMemberId] = requester
                it[requesterContact] = null
                it[reason] = "Gallery test"
                it[status] = SocialPostErasureStatus.APPROVED
                it[decidedBy] = requester
                it[decidedAt] = DbClock.nowLocalDateTime()
                it[decisionNote] = null
                it[executedAt] = null
                it[sourceReportId] = null
            }
        }
        return id
    }

    fun setPostState(
        postId: Uuid,
        state: SocialPostState,
    ) {
        transaction { SocialPostTable.update({ SocialPostTable.id eq postId }) { it[SocialPostTable.state] = state } }
    }

    fun setAuctionEnabled(enabled: Boolean) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[auctionEnabled] = enabled }
        }
    }

    fun auction(
        seller: Uuid,
        endsAt: LocalDateTime = DbClock.nowLocalDateTime().let { LocalDateTime(it.year + 1, 1, 1, 0, 0) },
        status: AuctionStatus = AuctionStatus.OPEN,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            AuctionTable.insert {
                it[AuctionTable.id] = id
                it[title] = "Gallery Test Auction"
                it[description] = "desc"
                it[startingBidLtr] = BigDecimal("1.00")
                it[buyNowPriceLtr] = null
                it[AuctionTable.status] = status
                it[sellerMemberId] = seller
                it[winnerMemberId] = null
                it[finalPriceLtr] = null
                it[listingFeeLtr] = BigDecimal("0.01")
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[AuctionTable.endsAt] = endsAt
                it[settledAt] = null
            }
        }
        auctionIds += id
        return id
    }

    fun bid(
        auctionId: Uuid,
        bidder: Uuid,
    ) {
        transaction {
            AuctionBidTable.insert {
                it[id] = Uuid.random()
                it[AuctionBidTable.auctionId] = auctionId
                it[bidderMemberId] = bidder
                it[maxBidLtr] = BigDecimal("2.00")
                it[createdAt] = LocalDateTime(2026, 1, 2, 0, 0)
            }
        }
    }

    fun project(
        submitter: Uuid,
        status: CrowdfundingProjectStatus = CrowdfundingProjectStatus.PENDING,
        submittedAt: LocalDateTime = DbClock.nowLocalDateTime(),
    ): Uuid {
        val id = Uuid.random()
        transaction {
            CrowdfundingProjectTable.insert {
                it[CrowdfundingProjectTable.id] = id
                it[title] = "Gallery Test Project"
                it[description] = "desc"
                it[submitterMemberId] = submitter
                it[initialWeightLtr] = BigDecimal("1.00")
                it[CrowdfundingProjectTable.status] = status
                it[rejectionReason] = null
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[CrowdfundingProjectTable.submittedAt] = submittedAt
            }
        }
        projectIds += id
        return id
    }

    /** Adds a picture THROUGH the real commit path (gate + insert + audit) with a stand-in file; returns the row, or fails on a
     * refusal. */
    fun addImage(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        caller: CurrentMember,
        byteSize: Long = 1_000,
    ): AttachedImageRow {
        val result = tryAddImage(type = type, ownerId = ownerId, caller = caller, byteSize = byteSize)
        return (result as? GalleryCommitResult.Added)?.row ?: error("Image was refused: ${(result as GalleryCommitResult.Refused).gate}")
    }

    fun tryAddImage(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        caller: CurrentMember,
        byteSize: Long = 1_000,
    ): GalleryCommitResult {
        val storageKey = Uuid.random()
        writtenKeys += storageKey
        storage.write(storageKey = storageKey, format = CoverImageFormat.JPEG, bytes = ByteArray(byteSize.toInt()) { 7 })
        val result =
            transaction {
                GalleryUploadGate.commitInTx(
                    type = type,
                    ownerId = ownerId,
                    caller = caller,
                    now = DbClock.nowLocalDateTime(),
                    limits = limits,
                    imageId = Uuid.random(),
                    storageKey = storageKey,
                    mimeType = "image/jpeg",
                    byteSize = byteSize,
                    width = 100,
                    height = 80,
                )
            }
        if (result is GalleryCommitResult.Refused) storage.delete(storageKey)
        return result
    }

    fun rows(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
    ): List<AttachedImageRow> = transaction { AttachedImageStore.listInTx(type = type, ownerId = ownerId) }

    fun filesOnDisk(): Int = root.listFiles()?.count { it.isFile } ?: 0

    fun cleanUp() {
        transaction {
            AttachedImageTable.deleteWhere { AttachedImageTable.uploadedBy inList memberIds }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AuctionBidTable.deleteWhere { AuctionBidTable.auctionId inList auctionIds }
            AuctionTable.deleteWhere { AuctionTable.id inList auctionIds }
            CrowdfundingProjectTable.deleteWhere { CrowdfundingProjectTable.id inList projectIds }
            SocialPostErasureTable.deleteWhere { SocialPostErasureTable.postId inList postIds }
            SocialPostTable.deleteWhere { (SocialPostTable.id inList postIds) and SocialPostTable.parentId.isNotNull() }
            SocialPostTable.deleteWhere { SocialPostTable.id inList postIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
        setAuctionEnabled(false)
        writtenKeys.forEach { storage.delete(it) }
        if (ownsRoot) root.deleteRecursively()
    }
}
