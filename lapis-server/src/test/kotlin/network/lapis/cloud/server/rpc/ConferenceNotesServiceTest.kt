package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoteBlockCreateWireDto
import network.lapis.cloud.shared.domain.NoteBlockEditWireDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Mirrors [ConferenceWhiteboardServiceTest]'s own `WHITEBOARD_ENABLED_CONFIG`. */
private val NOTES_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

private val NOTES_DISABLED_CONFIG = ConferenceConfig.load { null }

/**
 * Exercises [ConferenceNotesService] end to end -- mirrors [ConferenceWhiteboardServiceTest]'s own
 * house style (throwaway routes calling the service class directly, pipe-separated response bodies).
 * [afterSpec] hard-deletes every row this file created.
 */
class ConferenceNotesServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val documentStorageRoot = Files.createTempDirectory("notes-test-docs").toFile()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            cleanUpNotesTestData(memberIds = createdMemberIds, roomIds = createdRoomIds)
            documentStorageRoot.deleteRecursively()
        }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = email.substringBefore("@")
                    it[MemberTable.email] = email
                    it[MemberTable.status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestRoom(
            creatorId: Uuid,
            openMemberIds: List<Uuid> = emptyList(),
            leftMemberIds: List<Uuid> = emptyList(),
        ): Uuid {
            val roomId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Notizen-Testsitzung"
                    it[description] = ""
                    it[livekitRoomName] = "lc-notes-test-$roomId"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
                (openMemberIds + creatorId).distinct().forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = if (memberId == creatorId) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = null
                    }
                }
                leftMemberIds.forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = now
                    }
                }
            }
            createdRoomIds += roomId
            return roomId
        }

        // ── getNotesState / createBlock / commitBlockEdit happy paths ────────

        test("getNotesState: empty room returns an empty list") {
            val creator = createTestMember("notes-empty@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                val response =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        test("createBlock: round-trips through getNotesState with server-filled lastEditedBy* fields") {
            val creator = createTestMember("notes-roundtrip@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                val createResponse =
                    client.post("/test/create-block?roomId=$roomId&blockId=b1&content=hello&position=1") {
                        header("X-Member-Id", creator.toString())
                    }
                createResponse.status shouldBe HttpStatusCode.OK
                val created = createResponse.bodyAsText().split("|")
                created[0] shouldBe "b1"
                created[1] shouldBe creator.toString()
                created[2] shouldBe "1" // version

                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val blocks = stateResponse.bodyAsText().split(";")
                blocks.size shouldBe 1
                blocks[0].split("|")[0] shouldBe "b1"
            }
        }

        test("createBlock: multiple blocks accumulate, snapshot returned in POSITION order") {
            val creator = createTestMember("notes-accumulate@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b2&content=second&position=2") {
                    header("X-Member-Id", creator.toString())
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=first&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val ids = stateResponse.bodyAsText().split(";").map { it.split("|")[0] }
                ids shouldBe listOf("b1", "b2")
            }
        }

        test("createBlock: the (N+1)th create is rejected with Conflict once the room is at its block-count cap") {
            val creator = createTestMember("notes-cap@example.org")
            val roomId = createTestRoom(creator)
            val tinyState = ConferenceNotesState(maxBlocksPerRoom = 2)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = tinyState) }
                }
                repeat(2) { i ->
                    client
                        .post("/test/create-block?roomId=$roomId&blockId=b$i&content=x&position=$i") {
                            header("X-Member-Id", creator.toString())
                        }.status shouldBe HttpStatusCode.OK
                }
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b-over&content=x&position=99") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("createBlock: blank content is rejected with BadRequest") {
            val creator = createTestMember("notes-bad-blank@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b1&content=&position=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("createBlock: oversized content is rejected with BadRequest") {
            val creator = createTestMember("notes-bad-oversized@example.org")
            val roomId = createTestRoom(creator)
            val tooLong = "x".repeat(8_001)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b1&content=$tooLong&position=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("createBlock: blank blockId is rejected with BadRequest") {
            val creator = createTestMember("notes-bad-blockid@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client
                    .post("/test/create-block?roomId=$roomId&blockId=&content=x&position=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("commitBlockEdit: happy path -- accepted, content/version updated, round-trips") {
            val creator = createTestMember("notes-edit-happy@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=v1&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                val editResponse =
                    client.post("/test/commit-edit?roomId=$roomId&blockId=b1&content=v2&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }
                editResponse.status shouldBe HttpStatusCode.OK
                val parts = editResponse.bodyAsText().split("|")
                parts[0] shouldBe "true" // accepted
                parts[1] shouldBe "v2" // content
                parts[2] shouldBe "2" // version

                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val onlyBlock = stateResponse.bodyAsText().split(";").single()
                val fields = onlyBlock.split("|")
                fields[2] shouldBe "v2"
            }
        }

        test(
            "tamper: commitBlockEdit with a STALE baseVersion is genuinely rejected -- accepted=false, the returned " +
                "block is the CURRENT unmodified version, and a follow-up getNotesState proves the server-side " +
                "state was NOT overwritten",
        ) {
            val creator = createTestMember("notes-edit-stale@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=original&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                // baseVersion=99 -- deliberately stale, real current version is 1.
                val editResponse =
                    client.post("/test/commit-edit?roomId=$roomId&blockId=b1&content=attacker&baseVersion=99") {
                        header("X-Member-Id", creator.toString())
                    }
                editResponse.status shouldBe HttpStatusCode.OK
                val parts = editResponse.bodyAsText().split("|")
                parts[0] shouldBe "false" // accepted
                parts[1] shouldBe "original" // current, untouched content
                parts[2] shouldBe "1" // current, untouched version

                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val stillCurrentBlock = stateResponse.bodyAsText().split(";").single()
                val stillCurrent = stillCurrentBlock.split("|")
                stillCurrent[2] shouldBe "original" // content
                stillCurrent[3] shouldBe "1" // version
            }
        }

        test("commitBlockEdit: unknown blockId returns accepted=false, block=null") {
            val creator = createTestMember("notes-edit-unknown@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                val editResponse =
                    client.post("/test/commit-edit?roomId=$roomId&blockId=does-not-exist&content=x&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }
                editResponse.status shouldBe HttpStatusCode.OK
                editResponse.bodyAsText() shouldBe "false||"
            }
        }

        test("commitBlockEdit: oversized content is rejected with BadRequest, validated before touching state") {
            val creator = createTestMember("notes-edit-oversized@example.org")
            val roomId = createTestRoom(creator)
            val tooLong = "x".repeat(8_001)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=original&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                client
                    .post("/test/commit-edit?roomId=$roomId&blockId=b1&content=$tooLong&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val onlyBlock = stateResponse.bodyAsText().split(";").single()
                val fields = onlyBlock.split("|")
                fields[2] shouldBe "original" // content
            }
        }

        // ── deleteBlock ────────────────────────────────────────────────────

        test("deleteBlock: the block's own last-editor can delete it") {
            val creator = createTestMember("notes-delete-own@example.org")
            val other = createTestMember("notes-delete-own-other@example.org")
            val roomId = createTestRoom(creator, openMemberIds = listOf(other))
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                    header("X-Member-Id", other.toString())
                }
                client
                    .post("/test/delete-block?roomId=$roomId&blockId=b1") { header("X-Member-Id", other.toString()) }
                    .status shouldBe HttpStatusCode.OK
                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                stateResponse.bodyAsText() shouldBe ""
            }
        }

        test("deleteBlock: the room's creator (moderator) can delete someone else's block") {
            val creator = createTestMember("notes-delete-mod@example.org")
            val other = createTestMember("notes-delete-mod-other@example.org")
            val roomId = createTestRoom(creator, openMemberIds = listOf(other))
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                    header("X-Member-Id", other.toString())
                }
                client
                    .post("/test/delete-block?roomId=$roomId&blockId=b1") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "tamper: an ordinary participant who is neither the block's last editor nor a moderator cannot delete " +
                "it -- Forbidden, block still present in a follow-up getNotesState",
        ) {
            val creator = createTestMember("notes-delete-tamper-creator@example.org")
            val author = createTestMember("notes-delete-tamper-author@example.org")
            val bystander = createTestMember("notes-delete-tamper-bystander@example.org")
            val roomId = createTestRoom(creator, openMemberIds = listOf(author, bystander))
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                    header("X-Member-Id", author.toString())
                }
                client
                    .post("/test/delete-block?roomId=$roomId&blockId=b1") { header("X-Member-Id", bystander.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                val stateResponse =
                    client.get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                stateResponse.bodyAsText().split(";").size shouldBe 1
            }
        }

        test("deleteBlock: deleting an already-gone blockId is an idempotent no-op success") {
            val creator = createTestMember("notes-delete-idempotent@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client
                    .post("/test/delete-block?roomId=$roomId&blockId=never-existed") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        // ── saveAsDocument ─────────────────────────────────────────────────

        test("saveAsDocument: archives a Markdown document into the Notizen folder with the requested accessLevel") {
            val creator = createTestMember("notes-save@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(
                            notesState = ConferenceNotesState(),
                            documentStorageRoot = documentStorageRoot,
                        )
                    }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=Wichtiger+Punkt&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                val saveResponse =
                    client.post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }
                saveResponse.status shouldBe HttpStatusCode.OK
                val documentId = Uuid.parse(saveResponse.bodyAsText())

                val (versionId, folderId, accessLevel) =
                    transaction {
                        val docRow = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()
                        Triple(
                            docRow[DocumentTable.currentVersionId],
                            docRow[DocumentTable.folderId],
                            docRow[DocumentTable.accessLevel],
                        )
                    }
                accessLevel shouldBe DocumentAccessLevel.BOARD_ONLY
                versionId shouldNotBe null

                val (mimeType, storageKey, folderName) =
                    transaction {
                        val versionRow = DocumentVersionTable.selectAll().where { DocumentVersionTable.id eq versionId!! }.single()
                        val folderRow = DocumentFolderTable.selectAll().where { DocumentFolderTable.id eq folderId }.single()
                        Triple(
                            versionRow[DocumentVersionTable.mimeType],
                            versionRow[DocumentVersionTable.storageKey],
                            folderRow[DocumentFolderTable.name],
                        )
                    }
                mimeType shouldBe "text/markdown"
                folderName shouldBe "Notizen"

                val markdown = documentStorageRoot.resolve(storageKey).readText()
                markdown shouldNotBe ""
                (markdown.contains("Wichtiger Punkt")) shouldBe true
                (markdown.contains("notes-save")) shouldBe true
            }
        }

        test("saveAsDocument: empty notes are rejected with Conflict, nothing to save") {
            val creator = createTestMember("notes-save-empty@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(
                            notesState = ConferenceNotesState(),
                            documentStorageRoot = documentStorageRoot,
                        )
                    }
                }
                client
                    .post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── NotFound / disabled / tamper (non-participant) / TOCTOU / rate limit / unauth ────

        test("getNotesState/createBlock/commitBlockEdit/deleteBlock/saveAsDocument against a nonexistent room are rejected with NotFound") {
            val creator = createTestMember("notes-notfound@example.org")
            val bogusRoomId = Uuid.random()
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(
                            notesState = ConferenceNotesState(),
                            documentStorageRoot = documentStorageRoot,
                        )
                    }
                }
                client
                    .get("/test/notes-state?roomId=$bogusRoomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/create-block?roomId=$bogusRoomId&blockId=b1&content=x&position=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/commit-edit?roomId=$bogusRoomId&blockId=b1&content=x&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/delete-block?roomId=$bogusRoomId&blockId=b1") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/save-as-document?roomId=$bogusRoomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("requireConferenceEnabled: every method is rejected with Conflict when Videokonferenzen is not configured") {
            val creator = createTestMember("notes-disabled@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState(), config = NOTES_DISABLED_CONFIG) }
                }
                client
                    .get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "tamper: a member who NEVER joined the room cannot read/create/edit/delete/save (Forbidden) -- direct RPC probing is rejected",
        ) {
            val creator = createTestMember("notes-tamper-neverjoined-creator@example.org")
            val outsider = createTestMember("notes-tamper-neverjoined-outsider@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(
                            notesState = ConferenceNotesState(),
                            documentStorageRoot = documentStorageRoot,
                        )
                    }
                }
                client
                    .get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", outsider.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/commit-edit?roomId=$roomId&blockId=b1&content=x&baseVersion=1") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/delete-block?roomId=$roomId&blockId=b1") { header("X-Member-Id", outsider.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "tamper: a participant who already LEFT the room cannot read/create/edit/delete/save (Forbidden) -- the STRICTER " +
                "open-participation gate",
        ) {
            val creator = createTestMember("notes-tamper-left-creator@example.org")
            val leftMember = createTestMember("notes-tamper-left-member@example.org")
            val roomId = createTestRoom(creator, leftMemberIds = listOf(leftMember))
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(
                            notesState = ConferenceNotesState(),
                            documentStorageRoot = documentStorageRoot,
                        )
                    }
                }
                client
                    .get("/test/notes-state?roomId=$roomId") { header("X-Member-Id", leftMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                        header("X-Member-Id", leftMember.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "tamper (TOCTOU): createBlock/commitBlockEdit against a room whose endedAt was set AFTER the initial " +
                "requireOpenParticipation check passed are rejected with Conflict by the final requireRoomStillOpen " +
                "re-check, and no block is ever created/mutated into the shared map",
        ) {
            val creator = createTestMember("notes-tamper-toctou@example.org")
            val roomId = createTestRoom(creator)
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) {
                    it[endedAt] = DbClock.nowLocalDateTime()
                }
            }
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client
                    .post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/commit-edit?roomId=$roomId&blockId=b1&content=x&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rate limiting: exceeding editRateLimiter's budget within the window throws Conflict") {
            val creator = createTestMember("notes-ratelimit@example.org")
            val roomId = createTestRoom(creator)
            val tinyEditLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing {
                        registerConferenceNotesTestRoutes(notesState = ConferenceNotesState(), editRateLimiter = tinyEditLimiter)
                    }
                }
                client.post("/test/create-block?roomId=$roomId&blockId=b1&content=x&position=1") {
                    header("X-Member-Id", creator.toString())
                }
                repeat(2) {
                    client
                        .post("/test/commit-edit?roomId=$roomId&blockId=b1&content=y&baseVersion=1") {
                            header("X-Member-Id", creator.toString())
                        }.status shouldBe HttpStatusCode.OK
                }
                client
                    .post("/test/commit-edit?roomId=$roomId&blockId=b1&content=z&baseVersion=1") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("unauthenticated caller is rejected with Unauthorized") {
            val creator = createTestMember("notes-unauth@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceNotesExceptionHandlers() }
                    routing { registerConferenceNotesTestRoutes(notesState = ConferenceNotesState()) }
                }
                client.get("/test/notes-state?roomId=$roomId").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })

private fun cleanUpNotesTestData(
    memberIds: List<Uuid>,
    roomIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && roomIds.isEmpty()) return
    transaction {
        val documentIds =
            DocumentTable.selectAll().where { DocumentTable.createdBy inList memberIds }.map { it[DocumentTable.id] }
        DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList documentIds }
        DocumentTable.deleteWhere { DocumentTable.id inList documentIds }
        ConferenceParticipationTable.deleteWhere {
            (ConferenceParticipationTable.memberId inList memberIds) or (ConferenceParticipationTable.roomId inList roomIds)
        }
        ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installConferenceNotesExceptionHandlers() {
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
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

private fun Route.registerConferenceNotesTestRoutes(
    notesState: ConferenceNotesState,
    documentStorageRoot: File = Files.createTempDirectory("notes-test-docs-unused").toFile(),
    config: ConferenceConfig = NOTES_ENABLED_CONFIG,
    readRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    createRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    editRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
    deleteRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes),
    saveRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
) {
    fun service(call: ApplicationCall) =
        ConferenceNotesService(
            call = call,
            documentStorageRoot = documentStorageRoot,
            notesState = notesState,
            config = config,
            readRateLimiter = readRateLimiter,
            createRateLimiter = createRateLimiter,
            editRateLimiter = editRateLimiter,
            deleteRateLimiter = deleteRateLimiter,
            saveRateLimiter = saveRateLimiter,
        )
    get("/test/notes-state") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.getNotesState(q["roomId"]!!)
        call.respondText(dto.blocks.joinToString(";") { "${it.id}|${it.lastEditedByMemberId}|${it.content}|${it.version}" })
    }
    post("/test/create-block") {
        val service = service(call)
        val q = call.request.queryParameters
        val block =
            NoteBlockCreateWireDto(
                blockId = q["blockId"] ?: "",
                content = q["content"] ?: "",
                position = q["position"]?.toIntOrNull() ?: 0,
            )
        val dto = service.createBlock(roomId = q["roomId"]!!, block = block)
        call.respondText("${dto.id}|${dto.lastEditedByMemberId}|${dto.version}")
    }
    post("/test/commit-edit") {
        val service = service(call)
        val q = call.request.queryParameters
        val edit =
            NoteBlockEditWireDto(
                blockId = q["blockId"] ?: "",
                content = q["content"] ?: "",
                baseVersion = q["baseVersion"]?.toIntOrNull() ?: 0,
            )
        val dto = service.commitBlockEdit(roomId = q["roomId"]!!, edit = edit)
        call.respondText("${dto.accepted}|${dto.block?.content.orEmpty()}|${dto.block?.version?.toString().orEmpty()}")
    }
    post("/test/delete-block") {
        val service = service(call)
        val q = call.request.queryParameters
        service.deleteBlock(roomId = q["roomId"]!!, blockId = q["blockId"]!!)
        call.respondText("ok")
    }
    post("/test/save-as-document") {
        val service = service(call)
        val q = call.request.queryParameters
        val level = DocumentAccessLevel.valueOf(q["accessLevel"] ?: "BOARD_ONLY")
        val dto = service.saveAsDocument(roomId = q["roomId"]!!, accessLevel = level)
        call.respondText(dto.documentId)
    }
}
