package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OwnMemberPhotoDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val CURRENT = MemberPhotoRules.CONSENT_TEXT_VERSION

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- [MemberPhotoService]: consent/visibility lifecycle, token
 * rotation, audit entries (and what they must NOT contain), moderation authorization, rate limits,
 * self-healing and the DB-level state constraint. Driven through a throwaway route (the house style
 * of the other `*ServiceTest` files).
 */
class MemberPhotoServiceTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun routes(
            storage: MemberPhotoStorage,
            visibilityLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 60.minutes),
            moderationLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 60.minutes),
        ): Application.() -> Unit =
            {
                install(ContentNegotiation) { json() }
                routing {
                    post("/t/{op}") {
                        val service =
                            MemberPhotoService(
                                call = call,
                                storage = storage,
                                visibilityRateLimiter = visibilityLimiter,
                                moderationRateLimiter = moderationLimiter,
                                baseUrl = "https://lapis.example.org",
                            )
                        val q = call.request.queryParameters
                        try {
                            when (call.parameters["op"]) {
                                "get" -> call.respond(service.getOwnPhoto())
                                "public" ->
                                    call.respond(
                                        service.setOwnPhotoVisibility(
                                            visibility = MemberPhotoVisibility.PUBLIC,
                                            consentTextVersion = q["v"],
                                        ),
                                    )
                                "private" ->
                                    call.respond(
                                        service.setOwnPhotoVisibility(
                                            visibility = MemberPhotoVisibility.PRIVATE,
                                            consentTextVersion = null,
                                        ),
                                    )
                                "delete" -> call.respond(service.deleteOwnPhoto())
                                "moderate" -> {
                                    service.moderationRemovePhoto(q["id"]!!)
                                    call.respondText("ok")
                                }
                                else -> call.respondText("?", status = HttpStatusCode.NotFound)
                            }
                        } catch (e: AbstractServiceException) {
                            call.respondText(e::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                }
            }

        suspend fun HttpClient.op(
            member: Uuid?,
            op: String,
            query: String = "",
        ): HttpResponse = post("/t/$op$query") { if (member != null) header("X-Member-Id", member.toString()) }

        suspend fun HttpResponse.dto(): OwnMemberPhotoDto = Json.decodeFromString(OwnMemberPhotoDto.serializer(), bodyAsText())

        test("getOwnPhoto: empty state, then a stored photo (PRIVATE, no public URL)") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-get"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                val empty = client.op(member, "get").dto()
                empty.hasPhoto shouldBe false
                empty.publicUrl shouldBe null
                empty.requiredConsentTextVersion shouldBe CURRENT

                fixtures.seedPhoto(storage = storage, memberId = member)
                val stored = client.op(member, "get").dto()
                stored.hasPhoto shouldBe true
                stored.visibility shouldBe MemberPhotoVisibility.PRIVATE
                stored.publicUrl shouldBe null
                stored.widthPx shouldBe 800
                stored.previewVersion?.length shouldBe 8
            }
        }

        test("self-healing: a row whose file is gone is deleted and reported as no photo") {
            val root = MemberPhotoFixtures.freshRoot("svc-heal")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member)
                MemberPhotoFixtures.filesIn(root).forEach { it.delete() }
                client.op(member, "get").dto().hasPhoto shouldBe false
                fixtures.rowCount(member) shouldBe 0
            }
        }

        test(
            "PUBLIC without a photo is MemberPhotoMissingException; a wrong or missing consent version is MemberPhotoConsentOutdatedException",
        ) {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-guards"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                client.op(member, "public", "?v=$CURRENT").bodyAsText() shouldBe "MemberPhotoMissingException"

                fixtures.seedPhoto(storage = storage, memberId = member)
                client.op(member, "public", "?v=member-photo-public-v0").bodyAsText() shouldBe "MemberPhotoConsentOutdatedException"
                client.op(member, "public").bodyAsText() shouldBe "MemberPhotoConsentOutdatedException"
                fixtures.tokenOf(member) shouldBe null
            }
        }

        test(
            "lifecycle: publish mints a token, PUBLIC again is a no-op, PRIVATE clears token and consent, re-publishing mints a NEW token",
        ) {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-life"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member)

                val published = client.op(member, "public", "?v=$CURRENT").dto()
                published.visibility shouldBe MemberPhotoVisibility.PUBLIC
                val firstToken = fixtures.tokenOf(member)!!
                published.publicUrl shouldBe "https://lapis.example.org/public/member-photos/$firstToken"
                val rowAfterPublish = transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single() }
                rowAfterPublish[MemberPhotoTable.consentTextVersion] shouldBe CURRENT
                (rowAfterPublish[MemberPhotoTable.consentGrantedAt] != null) shouldBe true

                client.op(member, "public", "?v=$CURRENT").dto().visibility shouldBe MemberPhotoVisibility.PUBLIC
                fixtures.tokenOf(member) shouldBe firstToken

                val withdrawn = client.op(member, "private").dto()
                withdrawn.visibility shouldBe MemberPhotoVisibility.PRIVATE
                withdrawn.publicUrl shouldBe null
                val rowAfterWithdraw = transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single() }
                rowAfterWithdraw[MemberPhotoTable.publicToken] shouldBe null
                rowAfterWithdraw[MemberPhotoTable.consentGrantedAt] shouldBe null
                rowAfterWithdraw[MemberPhotoTable.consentTextVersion] shouldBe null

                client.op(member, "public", "?v=$CURRENT").dto().visibility shouldBe MemberPhotoVisibility.PUBLIC
                val secondToken = fixtures.tokenOf(member)!!
                (secondToken == firstToken) shouldBe false
            }
        }

        test("audit: publish/unpublish write MEMBER entries with the photo snapshot and NEVER the token or the storage key") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-audit"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member)
                client.op(member, "public", "?v=$CURRENT")
                val token = fixtures.tokenOf(member)!!
                val storageKey =
                    transaction {
                        MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single()[MemberPhotoTable.storageKey]
                    }
                client.op(member, "private")

                val snapshots = fixtures.memberAuditAfterSnapshots(member)
                snapshots.size shouldBe 2
                snapshots[0].contains("\"memberPhoto\"") shouldBe true
                snapshots[0].contains("PUBLISHED") shouldBe true
                snapshots[0].contains(CURRENT) shouldBe true
                snapshots[1].contains("UNPUBLISHED") shouldBe true
                snapshots.forEach { snap ->
                    snap.contains(token) shouldBe false
                    snap.contains(storageKey) shouldBe false
                    snap.contains(storageKey.removeSuffix(".jpg")) shouldBe false
                }
            }
        }

        test("deleteOwnPhoto removes file and row; the audit entry exists only when the photo was PUBLIC") {
            val root = MemberPhotoFixtures.freshRoot("svc-delete")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application(routes(storage))
                val privateOwner = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = privateOwner)
                client.op(privateOwner, "delete").dto().hasPhoto shouldBe false
                fixtures.rowCount(privateOwner) shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
                fixtures.memberAuditAfterSnapshots(privateOwner).size shouldBe 0

                val publicOwner = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = publicOwner, publish = true)
                client.op(publicOwner, "delete").dto().hasPhoto shouldBe false
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
                fixtures.memberAuditAfterSnapshots(publicOwner).single().contains("DELETED_BY_OWNER") shouldBe true
            }
        }

        test(
            "moderation: MEMBER and TREASURER are forbidden, BOARD and ADMIN remove, and the call is idempotent and always answers the same",
        ) {
            val root = MemberPhotoFixtures.freshRoot("svc-moderation")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application(routes(storage))
                val owner = fixtures.newMember()
                val plain = fixtures.newMember(role = AccountRole.MEMBER)
                val treasurer = fixtures.newMember(role = AccountRole.TREASURER)
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                fixtures.seedPhoto(storage = storage, memberId = owner, publish = true)

                client.op(plain, "moderate", "?id=$owner").bodyAsText() shouldBe "ForbiddenException"
                client.op(treasurer, "moderate", "?id=$owner").bodyAsText() shouldBe "ForbiddenException"
                fixtures.rowCount(owner) shouldBe 1

                val removed = client.op(board, "moderate", "?id=$owner")
                removed.status shouldBe HttpStatusCode.OK
                removed.bodyAsText() shouldBe "ok"
                fixtures.rowCount(owner) shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
                fixtures.memberAuditAfterSnapshots(owner).single().contains("REMOVED_BY_MODERATION") shouldBe true

                // no photo any more, a member that never had one, and a random id: the very same answer
                client.op(admin, "moderate", "?id=$owner").bodyAsText() shouldBe "ok"
                client.op(admin, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "ok"
            }
        }

        test("unauthenticated callers are rejected by every method") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-unauth"))
            testApplication {
                application(routes(storage))
                listOf("get", "public", "private", "delete", "moderate").forEach { op ->
                    client.op(member = null, op = op, query = "?id=${Uuid.random()}&v=$CURRENT").bodyAsText() shouldBe
                        "UnauthenticatedException"
                }
            }
        }

        test("eligibility: a non-eligible member cannot publish, but can still withdraw and delete their own photo") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-eligible"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member, publish = true)
                fixtures.setStatus(memberId = member, status = MemberStatus.WITHDRAWN)
                client.op(member, "public", "?v=$CURRENT").bodyAsText() shouldBe "MemberPhotoNotEligibleException"
                client.op(member, "private").dto().visibility shouldBe MemberPhotoVisibility.PRIVATE
                client.op(member, "delete").dto().hasPhoto shouldBe false
            }
        }

        test("rate limits: visibility and delete share a budget; moderation has its own per-actor budget") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("svc-rate"))
            testApplication {
                application(
                    routes(
                        storage,
                        visibilityLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes),
                        moderationLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 60.minutes),
                    ),
                )
                val member = fixtures.newMember()
                client.op(member, "private").status shouldBe HttpStatusCode.OK
                client.op(member, "delete").status shouldBe HttpStatusCode.OK
                client.op(member, "private").bodyAsText() shouldBe "MemberPhotoRateLimitedException"
                client.op(member, "delete").bodyAsText() shouldBe "MemberPhotoRateLimitedException"

                val board = fixtures.newMember(role = AccountRole.BOARD)
                client.op(board, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "ok"
                client.op(board, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "MemberPhotoRateLimitedException"
            }
        }

        test("the DB itself refuses an inconsistent publication state (PUBLIC without token/consent, PRIVATE with a token)") {
            val member = fixtures.newMember()

            fun tryInsert(
                visibility: MemberPhotoVisibility,
                token: String?,
                consentAt: Boolean,
                version: String?,
            ) = transaction {
                MemberPhotoTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it[storageKey] = "${Uuid.random()}.jpg"
                    it[contentType] = "image/jpeg"
                    it[widthPx] = 800
                    it[heightPx] = 800
                    it[sizeBytes] = 1L
                    it[uploadedAt] = DbClock.nowLocalDateTime()
                    it[MemberPhotoTable.visibility] = visibility
                    it[publicToken] = token
                    it[consentGrantedAt] = if (consentAt) DbClock.nowLocalDateTime() else null
                    it[consentTextVersion] = version
                }
            }
            shouldThrow<Exception> { tryInsert(MemberPhotoVisibility.PUBLIC, token = null, consentAt = true, version = CURRENT) }
            shouldThrow<Exception> { tryInsert(MemberPhotoVisibility.PUBLIC, token = "t".repeat(43), consentAt = false, version = CURRENT) }
            shouldThrow<Exception> { tryInsert(MemberPhotoVisibility.PRIVATE, token = "t".repeat(43), consentAt = false, version = null) }
            shouldThrow<Exception> { tryInsert(MemberPhotoVisibility.PRIVATE, token = null, consentAt = true, version = null) }
            // the consistent shapes are accepted; a second photo row for the same member violates the unique index
            tryInsert(MemberPhotoVisibility.PRIVATE, token = null, consentAt = false, version = null)
            shouldThrow<Exception> { tryInsert(MemberPhotoVisibility.PRIVATE, token = null, consentAt = false, version = null) }
        }
    })
