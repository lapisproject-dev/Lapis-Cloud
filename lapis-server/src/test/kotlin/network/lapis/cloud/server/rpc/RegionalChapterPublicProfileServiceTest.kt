package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterPublicRules
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val BASE = "https://lapis.example.org"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the public side of [RegionalChapterService]: description
 * (BOARD/ADMIN, normalized, audited without the text), crest removal (row first, file after the
 * commit), chapter deletion taking the crest file with it, and the DTO fields the management screen
 * renders.
 */
class RegionalChapterPublicProfileServiceTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()
        val root = MemberPhotoFixtures.freshRoot("chapter-service-crests")
        val storage = EventCoverStorage(root)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup(crestStorage = storage) }

        fun routes(): Application.() -> Unit =
            {
                install(ContentNegotiation) { json() }
                routing {
                    post("/t/{op}") {
                        val service = RegionalChapterService(call = call, crestStorage = storage, baseUrl = BASE)
                        val q = call.request.queryParameters
                        try {
                            when (call.parameters["op"]) {
                                "describe" ->
                                    call.respond(
                                        service.updateChapterDescription(chapterId = q["id"]!!, description = call.receiveText()),
                                    )
                                "describe-null" -> call.respond(service.updateChapterDescription(chapterId = q["id"]!!, description = null))
                                "remove-crest" -> call.respond(service.removeChapterCrest(q["id"]!!))
                                "delete" -> {
                                    service.deleteChapter(q["id"]!!)
                                    call.respondText("ok")
                                }
                                "list" -> call.respond(service.listChapters())
                                else -> call.respondText("?", status = HttpStatusCode.NotFound)
                            }
                        } catch (e: AbstractServiceException) {
                            call.respondText(e::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                }
            }

        suspend fun HttpClient.op(
            actor: Uuid?,
            op: String,
            chapter: Uuid,
            body: String? = null,
        ): HttpResponse =
            post("/t/$op?id=$chapter") {
                if (actor != null) header("X-Member-Id", actor.toString())
                if (body != null) setBody(body)
            }

        suspend fun HttpResponse.chapter(): RegionalChapterDto = Json.decodeFromString(RegionalChapterDto.serializer(), bodyAsText())

        test("description: BOARD and ADMIN may set it, it is trimmed and CRLF-normalized, the DTO and the row carry it") {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val chapter = fixtures.newChapter()
                val dto = client.op(board, "describe", chapter, "  Zeile eins\r\nZeile zwei  ").chapter()
                dto.description shouldBe "Zeile eins\nZeile zwei"
                transaction {
                    RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq chapter }.single()[RegionalChapterTable.description]
                } shouldBe "Zeile eins\nZeile zwei"
                client.op(admin, "describe", chapter, "Von der Admin").chapter().description shouldBe "Von der Admin"
            }
        }

        test("description: MEMBER and TREASURER are forbidden, an unauthenticated call is refused, the row stays untouched") {
            testApplication {
                application(routes())
                val chapter = fixtures.newChapter(description = "Bestand")
                val member = fixtures.newMember(role = AccountRole.MEMBER)
                val treasurer = fixtures.newMember(role = AccountRole.TREASURER)
                client.op(member, "describe", chapter, "Hack").bodyAsText() shouldBe "ForbiddenException"
                client.op(treasurer, "describe", chapter, "Hack").bodyAsText() shouldBe "ForbiddenException"
                client.op(null, "describe", chapter, "Hack").bodyAsText() shouldBe "UnauthenticatedException"
                transaction {
                    RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq chapter }.single()[RegionalChapterTable.description]
                } shouldBe "Bestand"
            }
        }

        test("description: blank or null clears it; too long, too many line breaks and control characters are refused") {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter(description = "Alt")
                client.op(board, "describe", chapter, "   ").chapter().description shouldBe null
                client.op(board, "describe", chapter, "Wieder da").chapter().description shouldBe "Wieder da"
                client.op(board, "describe-null", chapter).chapter().description shouldBe null

                val limit = RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS
                client.op(board, "describe", chapter, "a".repeat(limit)).status shouldBe HttpStatusCode.OK
                for (bad in listOf("a".repeat(limit + 1), (1..8).joinToString("\n") { "z$it" }, "a\u0000b", "a‮b")) {
                    client.op(board, "describe", chapter, bad).bodyAsText() shouldBe "BadRequestException"
                }
                // Code points, not UTF-16 units: 300 emojis are allowed, 301 are not.
                client.op(board, "describe", chapter, "😀".repeat(limit)).status shouldBe HttpStatusCode.OK
                client.op(board, "describe", chapter, "😀".repeat(limit + 1)).bodyAsText() shouldBe "BadRequestException"
            }
        }

        test("description: the audit entry records only booleans, never the text") {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val secret = "BESCHREIBUNG-GEHEIM-FUER-DEN-AUDIT-LOG"
                client.op(board, "describe", chapter, secret)
                client.op(board, "describe", chapter, "")
                val snapshots = fixtures.chapterAuditAfterSnapshots(chapter)
                snapshots.size shouldBe 2
                snapshots.forEach { it shouldNotContain secret }
                snapshots.first() shouldContain "\"descriptionPresent\":true"
                snapshots.last() shouldNotContain "descriptionPresent"
            }
        }

        test("an unknown chapter id is NotFound for description and crest removal") {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                client.op(board, "describe", Uuid.random(), "x").bodyAsText() shouldBe "NotFoundException"
                client.op(board, "remove-crest", Uuid.random()).bodyAsText() shouldBe "NotFoundException"
            }
        }

        test(
            "removeChapterCrest: the row is cleared, the FILE is deleted after the commit, the audit says crestPresent false; a second call is a no-op",
        ) {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter(description = "Mit Wappen")
                fixtures.seedCrest(storage = storage, chapterId = chapter)
                val imageId =
                    transaction {
                        RegionalChapterTable
                            .selectAll()
                            .where {
                                RegionalChapterTable.id eq chapter
                            }.single()[RegionalChapterTable.crestImageId]
                    }!!
                (MemberPhotoFixtures.filesIn(root).any { it.name.startsWith(imageId.toString()) }) shouldBe true

                val dto = client.op(board, "remove-crest", chapter).chapter()
                dto.hasCrest shouldBe false
                dto.crestUrl shouldBe null
                dto.description shouldBe "Mit Wappen"
                (MemberPhotoFixtures.filesIn(root).any { it.name.startsWith(imageId.toString()) }) shouldBe false
                transaction {
                    RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq chapter }.single()
                }.let { row ->
                    row[RegionalChapterTable.crestImageId] shouldBe null
                    row[RegionalChapterTable.crestPublicToken] shouldBe null
                    row[RegionalChapterTable.crestContentType] shouldBe null
                }
                fixtures.chapterAuditAfterSnapshots(chapter).last() shouldNotContain "crestPresent"

                val auditCount = fixtures.chapterAuditAfterSnapshots(chapter).size
                client.op(board, "remove-crest", chapter).status shouldBe HttpStatusCode.OK
                fixtures.chapterAuditAfterSnapshots(chapter).size shouldBe auditCount
            }
        }

        test("removeChapterCrest: MEMBER and TREASURER are forbidden and the crest stays") {
            testApplication {
                application(routes())
                val chapter = fixtures.newChapter()
                fixtures.seedCrest(storage = storage, chapterId = chapter)

                fun crestImageId() =
                    transaction {
                        RegionalChapterTable
                            .selectAll()
                            .where {
                                RegionalChapterTable.id eq chapter
                            }.single()[RegionalChapterTable.crestImageId]
                    }
                val before = crestImageId()
                (before != null) shouldBe true
                val member = fixtures.newMember(role = AccountRole.MEMBER)
                val treasurer = fixtures.newMember(role = AccountRole.TREASURER)
                client.op(member, "remove-crest", chapter).bodyAsText() shouldBe "ForbiddenException"
                client.op(treasurer, "remove-crest", chapter).bodyAsText() shouldBe "ForbiddenException"
                crestImageId() shouldBe before
            }
        }

        test("listChapters: crestUrl is the absolute token URL, hasCrest and description are filled, a chapter without them has neither") {
            testApplication {
                application(routes())
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val withCrest = fixtures.newChapter(name = "LV Mit ${Uuid.random()}", description = "Hat alles")
                val token = fixtures.seedCrest(storage = storage, chapterId = withCrest)
                val plain = fixtures.newChapter(name = "LV Ohne ${Uuid.random()}")
                val overview =
                    Json.decodeFromString(
                        network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
                            .serializer(),
                        client.op(board, "list", withCrest).bodyAsText(),
                    )
                val full = overview.chapters.single { it.id == withCrest.toString() }
                full.crestUrl shouldBe "$BASE/public/chapter-crests/$token"
                full.hasCrest shouldBe true
                full.description shouldBe "Hat alles"
                val empty = overview.chapters.single { it.id == plain.toString() }
                empty.crestUrl shouldBe null
                empty.hasCrest shouldBe false
                empty.description shouldBe null
            }
        }

        test("deleteChapter: the crest FILE is removed after the commit; a blocked delete (member still assigned) keeps chapter and file") {
            testApplication {
                application(routes())
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val keep = fixtures.newChapter()
                fixtures.seedCrest(storage = storage, chapterId = keep)
                val assigned = fixtures.newMember()
                transaction { MemberTable.update({ MemberTable.id eq assigned }) { it[regionalChapterId] = keep } }
                val keepImage =
                    transaction {
                        RegionalChapterTable
                            .selectAll()
                            .where {
                                RegionalChapterTable.id eq keep
                            }.single()[RegionalChapterTable.crestImageId]
                    }!!
                client.op(admin, "delete", keep).bodyAsText() shouldBe "RegionalChapterInUseException"
                (MemberPhotoFixtures.filesIn(root).any { it.name.startsWith(keepImage.toString()) }) shouldBe true
                transaction { MemberTable.update({ MemberTable.id eq assigned }) { it[regionalChapterId] = null } }

                client.op(admin, "delete", keep).bodyAsText() shouldBe "ok"
                (MemberPhotoFixtures.filesIn(root).any { it.name.startsWith(keepImage.toString()) }) shouldBe false
            }
        }
    })
