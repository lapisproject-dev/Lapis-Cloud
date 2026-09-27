package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.ai.kb.KnowledgeReleaseStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.routes.archiveGeneratedBytes
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar" -- RPC-surface
 * coverage of [DocumentService]'s new folder-level access control (own `document_folder.access_level`,
 * effective-level ancestor climb, `createFolder`/`createDocument`'s folder-vs-document invariant,
 * `setDocumentAccessLevel`/`setFolderAccessLevel`, the tighten-cascade, and the K4 knowledge-base
 * revocation). Same throwaway-route harness `ServiceIntegrationTest` establishes for
 * [DocumentService]'s pre-existing tests.
 */
class DocumentAccessLevelServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun StatusPagesConfig.documentExceptionHandlers() {
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
        }

        test("listFolders: a BOARD_ONLY folder is invisible to a plain MEMBER") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S1 Ordner", accessLevel = DocumentAccessLevel.BOARD_ONLY)
                            call.respondText(f.id)
                        }
                        get("/list") {
                            val folders = DocumentService(call).listFolders()
                            call.respondText(folders.joinToString(",") { it.id })
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val seenByBoard = client.get("/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                (folderId in seenByBoard.split(",")) shouldBe true
                val seenByMember = client.get("/list") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                (folderId in seenByMember.split(",")) shouldBe false
            }
        }

        test("listDocuments: a folder the caller cannot read is reported NotFound, not Forbidden (existence must not leak)") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S2 Ordner", accessLevel = DocumentAccessLevel.BOARD_ONLY)
                            call.respondText(f.id)
                        }
                        get("/docs/{id}") {
                            val docs = DocumentService(call).listDocuments(call.parameters["id"]!!)
                            call.respondText(docs.size.toString())
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val response = client.get("/docs/$folderId") { header("X-Member-Id", MEMBER_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("effective level: a PUBLIC_MEMBERS sub-folder under a parent tightened to ADMIN_ONLY is invisible to BOARD/TREASURER") {
            // Both folders are created at PUBLIC_MEMBERS -- `createFolder`'s own invariant check
            // would otherwise reject a PUBLIC_MEMBERS child under an ADMIN_ONLY parent outright
            // (that IS the invariant this whole wave establishes; it is not the shape under test
            // here). The realistic way a sub-folder ends up LESS restrictive than its parent's
            // effective level is exactly this sequence: the parent is tightened AFTER the child
            // already exists -- `setFolderAccessLevel` only cascades into DOCUMENTS, never into a
            // sub-folder's own `access_level` (see that method's KDoc), so the child's OWN level
            // stays PUBLIC_MEMBERS while its EFFECTIVE level (ancestor-chain maximum) becomes
            // ADMIN_ONLY -- exactly the case this test pins.
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/parent") {
                            val f = DocumentService(call).createFolder(name = "T-S3 Eltern")
                            call.respondText(f.id)
                        }
                        post("/child/{parentId}") {
                            val f = DocumentService(call).createFolder(name = "T-S3 Kind", parentFolderId = call.parameters["parentId"])
                            call.respondText(f.id)
                        }
                        post("/tighten-parent/{parentId}") {
                            DocumentService(call).setFolderAccessLevel(
                                folderId = call.parameters["parentId"]!!,
                                accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                            )
                            call.respondText("ok")
                        }
                        get("/list") {
                            val folders = DocumentService(call).listFolders()
                            call.respondText(folders.joinToString(",") { it.id })
                        }
                    }
                }
                val parentId = client.post("/parent") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val childId = client.post("/child/$parentId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/tighten-parent/$parentId") { header("X-Member-Id", ADMIN_ID) }

                val seenByAdmin = client.get("/list") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split(",")
                (childId in seenByAdmin) shouldBe true

                val seenByBoard = client.get("/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split(",")
                (childId in seenByBoard) shouldBe false
                val seenByTreasurer = client.get("/list") { header("X-Member-Id", TREASURER_ID) }.bodyAsText().split(",")
                (childId in seenByTreasurer) shouldBe false
            }
        }

        test("createDocument: a level less restrictive than the folder's effective level is rejected (Conflict)") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S4 Ordner", accessLevel = DocumentAccessLevel.BOARD_ONLY)
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "T-S4 Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val response = client.post("/d/$folderId") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("setDocumentAccessLevel: loosening below the folder's effective level is rejected (Conflict)") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S5 Ordner", accessLevel = DocumentAccessLevel.BOARD_ONLY)
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "T-S5 Dokument",
                                    accessLevel = DocumentAccessLevel.BOARD_ONLY,
                                )
                            call.respondText(d.id)
                        }
                        post("/set/{docId}/{level}") {
                            val d =
                                DocumentService(call).setDocumentAccessLevel(
                                    documentId = call.parameters["docId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(d.accessLevel.name)
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val response = client.post("/set/$docId/PUBLIC_MEMBERS") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("setDocumentAccessLevel/setFolderAccessLevel: TREASURER cannot set ADMIN_ONLY, MEMBER cannot set anything") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S6 Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "T-S6 Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/setDoc/{docId}/{level}") {
                            val d =
                                DocumentService(call).setDocumentAccessLevel(
                                    documentId = call.parameters["docId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(d.accessLevel.name)
                        }
                        post("/setFolder/{folderId}/{level}") {
                            val result =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(result.folder.accessLevel.name)
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                client.post("/setDoc/$docId/ADMIN_ONLY") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                val treasurerSetFolder = client.post("/setFolder/$folderId/ADMIN_ONLY") { header("X-Member-Id", TREASURER_ID) }
                treasurerSetFolder.status shouldBe HttpStatusCode.Forbidden
                client.post("/setDoc/$docId/BOARD_ONLY") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                val memberSetFolder = client.post("/setFolder/$folderId/BOARD_ONLY") { header("X-Member-Id", MEMBER_ID) }
                memberSetFolder.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("setFolderAccessLevel: tightening cascades into every document of the subtree, clamped up, never loosened by a later widen") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/parent") {
                            val f = DocumentService(call).createFolder(name = "T-S7 Eltern")
                            call.respondText(f.id)
                        }
                        post("/child/{parentId}") {
                            val f = DocumentService(call).createFolder(name = "T-S7 Kind", parentFolderId = call.parameters["parentId"])
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}/{title}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = call.parameters["title"]!!,
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/tighten/{folderId}/{level}") {
                            val result =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(result.tightenedDocuments.toString())
                        }
                        get("/level/{docId}") {
                            val docs = DocumentService(call).listDocuments()
                            call.respondText(docs.single { it.id == call.parameters["docId"] }.accessLevel.name)
                        }
                    }
                }
                val parentId = client.post("/parent") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val childId = client.post("/child/$parentId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val doc1 = client.post("/d/$parentId/T-S7-Doc1") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val doc2 = client.post("/d/$parentId/T-S7-Doc2") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val doc3 = client.post("/d/$childId/T-S7-Doc3") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val doc4 = client.post("/d/$childId/T-S7-Doc4") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                val tightenedCount = client.post("/tighten/$parentId/BOARD_ONLY") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                tightenedCount shouldBe "4"

                listOf(doc1, doc2, doc3, doc4).forEach { docId ->
                    client.get("/level/$docId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "BOARD_ONLY"
                }

                // Audit: 1x DOCUMENT_FOLDER/UPDATE for the parent + 4x DOCUMENT/UPDATE, one per cascaded document
                // (each document also carries its own DOCUMENT/CREATE row from creation -- filtered out below by
                // `action eq UPDATE`, since only the cascade's UPDATE rows are under test here).
                val cascadedIds = listOf(doc1, doc2, doc3, doc4).map { Uuid.parse(it) }
                val cascadedDocumentAuditRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.DOCUMENT) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE) and
                                    (AuditLogEntryTable.entityId inList cascadedIds)
                            }.count()
                    }
                cascadedDocumentAuditRows shouldBe 4L

                // T-S8: widening the parent back does NOT cascade -- the documents stay BOARD_ONLY.
                val widenedCount = client.post("/tighten/$parentId/PUBLIC_MEMBERS") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                widenedCount shouldBe "0"
                listOf(doc1, doc2, doc3, doc4).forEach { docId ->
                    client.get("/level/$docId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "BOARD_ONLY"
                }
            }
        }

        test("setDocumentAccessLevel: a no-op (same level) writes no audit entry") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-S11 Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "T-S11 Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/set/{docId}") {
                            val d =
                                DocumentService(call).setDocumentAccessLevel(
                                    documentId = call.parameters["docId"]!!,
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.accessLevel.name)
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docUuid = Uuid.parse(docId)
                val before = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq docUuid }.count() }
                client.post("/set/$docId") { header("X-Member-Id", ADMIN_ID) }
                val after = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq docUuid }.count() }
                after shouldBe before
            }
        }

        test("a cycle in document_folder.parent_folder_id fails closed to ADMIN_ONLY, not an infinite loop") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        get("/list") {
                            val folders = DocumentService(call).listFolders()
                            call.respondText(folders.joinToString(",") { it.id })
                        }
                    }
                }
                // Two folders pointing at each other -- unreachable through the service's own
                // createFolder (which always validates the parent first), so built directly.
                val a = Uuid.random()
                val b = Uuid.random()
                transaction {
                    DocumentFolderTable.insert {
                        it[id] = a
                        it[name] = "T-S12 Zyklus A"
                        it[parentFolderId] = b
                        it[accessLevel] = DocumentAccessLevel.PUBLIC_MEMBERS
                    }
                    DocumentFolderTable.insert {
                        it[id] = b
                        it[name] = "T-S12 Zyklus B"
                        it[parentFolderId] = a
                        it[accessLevel] = DocumentAccessLevel.PUBLIC_MEMBERS
                    }
                }
                try {
                    // A MEMBER must not see either cycle folder (fail-closed to ADMIN_ONLY); an
                    // ADMIN must (ADMIN_ONLY is still readable by ADMIN) -- and, above all, this
                    // call must terminate at all.
                    val seenByMember = client.get("/list") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split(",")
                    (a.toString() in seenByMember) shouldBe false
                    (b.toString() in seenByMember) shouldBe false
                    val seenByAdmin = client.get("/list") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split(",")
                    (a.toString() in seenByAdmin) shouldBe true
                    (b.toString() in seenByAdmin) shouldBe true
                } finally {
                    transaction {
                        DocumentFolderTable.update({ DocumentFolderTable.id eq a }) { it[parentFolderId] = null }
                        DocumentFolderTable.update({ DocumentFolderTable.id eq b }) { it[parentFolderId] = null }
                    }
                }
            }
        }

        test("K4: tightening a document's own level revokes an existing knowledge-base release; loosening back does not re-release") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "K4 Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "K4 Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/set/{docId}/{level}") {
                            val d =
                                DocumentService(call).setDocumentAccessLevel(
                                    documentId = call.parameters["docId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(d.accessLevel.name)
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docUuid = Uuid.parse(docId)
                KnowledgeReleaseStore.release(documentId = docUuid, releasedBy = Uuid.parse(ADMIN_ID))
                KnowledgeReleaseStore.isReleased(docUuid) shouldBe true

                client.post("/set/$docId/BOARD_ONLY") { header("X-Member-Id", ADMIN_ID) }
                KnowledgeReleaseStore.isReleased(docUuid) shouldBe false

                // Loosening back to PUBLIC_MEMBERS must NOT silently re-release it.
                client.post("/set/$docId/PUBLIC_MEMBERS") { header("X-Member-Id", ADMIN_ID) }
                KnowledgeReleaseStore.isReleased(docUuid) shouldBe false
            }
        }

        test("K4: a folder tighten's cascade also revokes an existing knowledge-base release of a cascaded document") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "K4-Kaskade Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "K4-Kaskade Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/tighten/{folderId}") {
                            val result =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(result.tightenedDocuments.toString())
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docUuid = Uuid.parse(docId)
                KnowledgeReleaseStore.release(documentId = docUuid, releasedBy = Uuid.parse(ADMIN_ID))
                KnowledgeReleaseStore.isReleased(docUuid) shouldBe true

                client.post("/tighten/$folderId") { header("X-Member-Id", ADMIN_ID) }
                KnowledgeReleaseStore.isReleased(docUuid) shouldBe false
            }
        }
        // ── Welle V1.9.1, fix round ─────────────────────────────────────────────────────────────

        test("B5: tightening a parent clamps every DESCENDANT FOLDER's own level, so own level == effective level") {
            // Before the fix the cascade touched documents only: the child folder kept its own
            // PUBLIC_MEMBERS while being effectively ADMIN_ONLY, and the client's "Sichtbarkeit" badge
            // -- which renders the OWN level, the only one on the wire -- said "Alle Mitglieder" about
            // a folder nobody but an ADMIN could open.
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/parent") {
                            val f = DocumentService(call).createFolder(name = "T-B5 Eltern")
                            call.respondText(f.id)
                        }
                        post("/child/{parentId}") {
                            val f = DocumentService(call).createFolder(name = "T-B5 Kind", parentFolderId = call.parameters["parentId"])
                            call.respondText(f.id)
                        }
                        post("/grandchild/{parentId}") {
                            val f = DocumentService(call).createFolder(name = "T-B5 Enkel", parentFolderId = call.parameters["parentId"])
                            call.respondText(f.id)
                        }
                        post("/tighten/{folderId}/{level}") {
                            val result =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText("${result.tightenedFolders}/${result.tightenedDocuments}")
                        }
                        get("/own-level/{folderId}") {
                            val folders = DocumentService(call).listFolders()
                            call.respondText(folders.single { it.id == call.parameters["folderId"] }.accessLevel.name)
                        }
                    }
                }
                val parentId = client.post("/parent") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val childId = client.post("/child/$parentId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val grandchildId = client.post("/grandchild/$childId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                val counts = client.post("/tighten/$parentId/ADMIN_ONLY") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                counts shouldBe "2/0" // two descendant folders clamped, no documents in this fixture

                listOf(parentId, childId, grandchildId).forEach { folderId ->
                    client.get("/own-level/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "ADMIN_ONLY"
                }

                // One DOCUMENT_FOLDER/UPDATE audit row per clamped descendant, each carrying
                // cascadedFromFolderId -- the cascade is independently reviewable per folder.
                val descendantIds = listOf(childId, grandchildId).map { Uuid.parse(it) }
                val descendantAuditPayloads =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.DOCUMENT_FOLDER) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE) and
                                    (AuditLogEntryTable.entityId inList descendantIds)
                            }.map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                    }
                descendantAuditPayloads.size shouldBe 2
                descendantAuditPayloads.all { it.contains("\"cascadedFromFolderId\":\"$parentId\"") } shouldBe true

                // T-B5b: a loosening does NOT cascade back -- the asymmetry, for folders as for documents.
                client.post("/tighten/$parentId/PUBLIC_MEMBERS") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "0/0"
                client.get("/own-level/$childId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "ADMIN_ONLY"
            }
        }

        test("B4: no audit payload of this wave carries a document title or a folder name") {
            // AuditLogService hands the payload, filterable by entity type, to TREASURER/BOARD/ADMIN with
            // no field filtering at all -- a TREASURER who may not read an ADMIN_ONLY document must not
            // learn its title ("Kündigung Mitarbeiter Müller") or its folder's name ("Personalakten")
            // through the log. Fail-closed by construction: the fields are never written.
            val secretFolderName = "T-B4-Personalakten-Geheim"
            val secretDocumentTitle = "T-B4-Kuendigung-Geheim"
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = secretFolderName)
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = secretDocumentTitle,
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/tighten/{folderId}") {
                            DocumentService(call).setFolderAccessLevel(
                                folderId = call.parameters["folderId"]!!,
                                accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                            )
                            call.respondText("ok")
                        }
                        post("/delete/{docId}") {
                            DocumentService(call).deleteDocument(call.parameters["docId"]!!)
                            call.respondText("ok")
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/tighten/$folderId") { header("X-Member-Id", ADMIN_ID) }
                client.post("/delete/$docId") { header("X-Member-Id", ADMIN_ID) }

                val payloads =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId inList listOf(Uuid.parse(folderId), Uuid.parse(docId)))
                            }.flatMap {
                                listOf(it[AuditLogEntryTable.beforeSnapshot].orEmpty(), it[AuditLogEntryTable.afterSnapshot].orEmpty())
                            }
                    }
                payloads.isNotEmpty() shouldBe true
                payloads.none { it.contains(secretFolderName) } shouldBe true
                payloads.none { it.contains(secretDocumentTitle) } shouldBe true
                // The shape is still attributable: entityId plus folderId, never the human label.
                payloads.any { it.contains("\"folderId\":\"$folderId\"") } shouldBe true
            }
        }

        test("B3: the cascade's audit entries form one contiguous block that ENDS with the folder's own entry") {
            // AuditLogRecorder.record takes the application's one global chain-state row lock and must be
            // the LAST lock-taking operation of its transaction. The cascade used to call it from inside
            // its per-document loop and then take more row locks. Every UPDATE now runs first and the
            // audit entries are appended afterwards in one closed block -- observable as a contiguous
            // sequence_number run whose highest entry is the DOCUMENT_FOLDER row for the tightened folder.
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-B3 Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}/{title}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = call.parameters["title"]!!,
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/tighten/{folderId}") {
                            val r =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(r.tightenedDocuments.toString())
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docIds = (1..3).map { client.post("/d/$folderId/T-B3-Doc$it") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() }

                val highestBefore =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .limit(1)
                            .single()[AuditLogEntryTable.sequenceNumber]
                    }
                client.post("/tighten/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "3"

                val block =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.sequenceNumber greater highestBefore }
                            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                            .map { it[AuditLogEntryTable.entityType] to it[AuditLogEntryTable.entityId] }
                    }
                // Exactly 3 cascaded DOCUMENT rows, then the folder's own DOCUMENT_FOLDER row, nothing else.
                block.size shouldBe 4
                block.dropLast(1).map { it.first }.toSet() shouldBe setOf(AuditEntityType.DOCUMENT)
                block.dropLast(1).map { it.second }.toSet() shouldBe docIds.map { Uuid.parse(it) }.toSet()
                block.last() shouldBe (AuditEntityType.DOCUMENT_FOLDER to Uuid.parse(folderId))
            }
        }

        test("B3: a tightening is never refused for size -- a folder with many documents cascades in full") {
            // A security measure must not fail because a folder happens to be large. There is deliberately
            // no cap and no partial application: one transaction, all or nothing.
            val documentCount = 25
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-B3-Gross Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}/{title}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = call.parameters["title"]!!,
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/tighten/{folderId}") {
                            val r =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(r.tightenedDocuments.toString())
                        }
                        get("/levels/{folderId}") {
                            val docs = DocumentService(call).listDocuments(call.parameters["folderId"]!!)
                            val levels =
                                docs
                                    .map { it.accessLevel.name }
                                    .distinct()
                                    .sorted()
                            call.respondText(levels.joinToString(","))
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                repeat(documentCount) { index ->
                    client.post("/d/$folderId/T-B3-Gross-Doc$index") { header("X-Member-Id", ADMIN_ID) }
                }
                client.post("/tighten/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe documentCount.toString()
                client.get("/levels/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "ADMIN_ONLY"
            }
        }

        test("W3: the cascade clamps SOFT-DELETED documents too -- a later restore must not become a leak") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f = DocumentService(call).createFolder(name = "T-W3 Ordner")
                            call.respondText(f.id)
                        }
                        post("/d/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "T-W3 Dokument",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        post("/delete/{docId}") {
                            DocumentService(call).deleteDocument(call.parameters["docId"]!!)
                            call.respondText("ok")
                        }
                        post("/tighten/{folderId}") {
                            val r =
                                DocumentService(call).setFolderAccessLevel(
                                    folderId = call.parameters["folderId"]!!,
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(r.tightenedDocuments.toString())
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val docId = client.post("/d/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/delete/$docId") { header("X-Member-Id", ADMIN_ID) }

                client.post("/tighten/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "1"
                transaction {
                    DocumentTable
                        .selectAll()
                        .where { DocumentTable.id eq Uuid.parse(docId) }
                        .single()[DocumentTable.accessLevel]
                } shouldBe DocumentAccessLevel.ADMIN_ONLY
            }
        }

        test("createFolder: a child less restrictive than its parent's effective level is rejected (Conflict)") {
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/parent") {
                            val f =
                                DocumentService(call).createFolder(
                                    name = "T-CF Eltern",
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(f.id)
                        }
                        post("/child/{parentId}/{level}") {
                            val f =
                                DocumentService(call).createFolder(
                                    name = "T-CF Kind",
                                    parentFolderId = call.parameters["parentId"],
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(f.id)
                        }
                    }
                }
                val parentId = client.post("/parent") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/child/$parentId/PUBLIC_MEMBERS") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
                client
                    .post("/child/$parentId/BOARD_ONLY") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
                // Equally restrictive is fine.
                client
                    .post("/child/$parentId/ADMIN_ONLY") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("setFolderAccessLevel on a folder the caller cannot read is NotFound, never Forbidden") {
            // A 403 would confirm the folder exists -- the same "existence must not leak" posture
            // listFolders/listDocuments take. The caller here HAS the role to write (BOARD is in
            // ESCALATED_ROLES) and MAY set the target level, so the only thing rejecting them is the
            // folder's own unreadable level, which is exactly the branch under test.
            testApplication {
                application {
                    install(StatusPages) { documentExceptionHandlers() }
                    routing {
                        post("/f") {
                            val f =
                                DocumentService(call).createFolder(
                                    name = "T-NF Ordner",
                                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                                )
                            call.respondText(f.id)
                        }
                        post("/set/{folderId}/{level}") {
                            DocumentService(call).setFolderAccessLevel(
                                folderId = call.parameters["folderId"]!!,
                                accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                            )
                            call.respondText("ok")
                        }
                    }
                }
                val folderId = client.post("/f") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/set/$folderId/BOARD_ONLY") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.NotFound
                // A non-existent folder is reported identically -- indistinguishable, by design.
                client
                    .post("/set/${Uuid.random()}/BOARD_ONLY") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("DocumentArchiving clamps its caller-supplied level to the archive folder's effective level") {
            // The archiving pipeline bypasses createDocument entirely, so its own clamp is the only thing
            // keeping a PUBLIC_MEMBERS recording/PDF out of a folder an admin has since tightened. This
            // path had no test at all before the fix round.
            val storageRoot = Files.createTempDirectory("doc-archiving-clamp").toFile()
            try {
                val folderName = "T-ARCH Archivordner"
                transaction {
                    DocumentFolderTable.insert {
                        it[id] = Uuid.random()
                        it[name] = folderName
                        it[parentFolderId] = null
                        it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                    }
                }
                val documentId =
                    archiveGeneratedBytes(
                        storageRoot = storageRoot,
                        folderName = folderName,
                        fileName = "t-arch.pdf",
                        title = "T-ARCH Dokument",
                        bytes = "archived bytes".toByteArray(Charsets.UTF_8),
                        mimeType = "application/pdf",
                        uploadedBy = Uuid.parse(ADMIN_ID),
                        // Deliberately the most permissive level a caller could pass.
                        accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                        changeNote = "T-ARCH",
                    )
                transaction {
                    DocumentTable
                        .selectAll()
                        .where { DocumentTable.id eq documentId }
                        .single()[DocumentTable.accessLevel]
                } shouldBe DocumentAccessLevel.ADMIN_ONLY

                // An already-stricter caller-supplied level is never loosened either.
                val strictDocumentId =
                    archiveGeneratedBytes(
                        storageRoot = storageRoot,
                        folderName = "T-ARCH Offener Ordner",
                        fileName = "t-arch-strict.pdf",
                        title = "T-ARCH Strenges Dokument",
                        bytes = "archived bytes".toByteArray(Charsets.UTF_8),
                        mimeType = "application/pdf",
                        uploadedBy = Uuid.parse(ADMIN_ID),
                        accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                        changeNote = "T-ARCH",
                    )
                transaction {
                    DocumentTable
                        .selectAll()
                        .where { DocumentTable.id eq strictDocumentId }
                        .single()[DocumentTable.accessLevel]
                } shouldBe DocumentAccessLevel.ADMIN_ONLY
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })
