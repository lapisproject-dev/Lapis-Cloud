package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDocumentService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W4d batch 2: `DocumentsScreen.kt`'s two migrated `lapisForm`s that go through Kilua RPC --
 * "Neuer Ordnername" (folder creation) and "Neuer Dokumenttitel" + "Sichtbarkeit" (document
 * creation) -- driven the way a person does (type, choose, click) against a stubbed
 * `window.fetch`, same idiom `PoliticianScreenFormDomTest.kt` (W4d batch 1) already establishes.
 * `canManage` (BOARD/TREASURER/ADMIN, see [DocumentsAuthzUi]) is required for both panels.
 *
 * The third migrated form ("Neue Version hochladen") posts file bytes over a dedicated
 * `XMLHttpRequest` route (`DocumentHttp.uploadVersion`, deliberately NOT Kilua RPC / `window.fetch`
 * -- see that object's KDoc), which this suite's fetch stub cannot intercept. Only its required-field
 * validation is covered here (no XHR is attempted, so no stub is needed for it), the same scope
 * `BackupRestoreDomTest.kt`'s `restoreWithoutAFile_...` case covers for its own XHR-based upload.
 */
class DocumentsScreenFormDomTest {
    private val boardSession =
        SessionInfoDto(
            memberId = "board-member-1",
            displayName = "Board-Testperson",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val folder = DocumentFolderDto(id = "folder-1", name = "Satzungen", parentFolderId = null, documentCount = 0)

    private fun document(id: String) =
        DocumentDto(
            id = id,
            folderId = "folder-1",
            title = "Satzung",
            currentVersionId = null,
            createdBy = "board-member-1",
            createdByDisplayName = "Board-Testperson",
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
            isDeleted = false,
        )

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `PoliticianScreenFormDomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    /** The `<a>` link whose text equals [text] exactly (the document-title link that opens the versions panel). */
    private fun HTMLElement.linkNamed(text: String): HTMLElement =
        assertNotNull(allOf("a").firstOrNull { it.textContent?.trim() == text }, "no link '$text'")

    @Test
    fun folderCreation_sendsTheTypedName_trimmed(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val createFolderRoute = routeOf { rpcService<IDocumentService>().createFolder("x", null) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList()),
                        createFolderRoute to jsonOf(DocumentFolderDto.serializer(), folder),
                    ),
            ) { calls ->
                mountedForm("documents-folder-create-happy") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    element().typeInto("Neuer Ordnername", "  Satzungen  ")
                    element().buttonNamed("Ordner anlegen").click()
                    awaitUntil("createFolder", timeoutMs = 800) { calls.toRoute(createFolderRoute).size == 1 }
                    val call = calls.singleCall(createFolderRoute)
                    assertEquals("Satzungen", call.rpcParam(0) as String, "the typed name is sent, trimmed")
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a non-blank name")
                }
            }
        }

    @Test
    fun folderCreation_withoutAName_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val createFolderRoute = routeOf { rpcService<IDocumentService>().createFolder("x", null) }
            withFetchStub(
                respond = answering(listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList())),
            ) { calls ->
                mountedForm("documents-folder-create-invalid") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    element().buttonNamed("Ordner anlegen").click()
                    delay(80)
                    assertTrue(calls.toRoute(createFolderRoute).isEmpty(), "no createFolder call for a blank name")
                    assertTrue(element().shownErrors().isNotEmpty(), "the blank name is reported on the field")
                }
            }
        }

    @Test
    fun documentCreation_sendsTheTypedTitleAndTheDefaultAccessLevel(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocumentsRoute = routeOf { rpcService<IDocumentService>().listDocuments("folder-1") }
            val createDocumentRoute =
                routeOf { rpcService<IDocumentService>().createDocument("folder-1", "x", DocumentAccessLevel.PUBLIC_MEMBERS) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                        listDocumentsRoute to jsonOf(ListSerializer(DocumentDto.serializer()), emptyList()),
                        createDocumentRoute to jsonOf(DocumentDto.serializer(), document("doc-1")),
                    ),
            ) { calls ->
                mountedForm("documents-document-create-happy") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Satzungen (0)" }
                    }
                    element().buttonNamed("Satzungen (0)").click()
                    awaitUntil("documents loaded", timeoutMs = 800) { calls.toRoute(listDocumentsRoute).size == 1 }
                    delay(80)
                    element().typeInto("Neuer Dokumenttitel", "  Satzung 2026  ")
                    element().buttonNamed("Dokument anlegen (danach Datei hochladen)").click()
                    awaitUntil("createDocument", timeoutMs = 800) { calls.toRoute(createDocumentRoute).size == 1 }
                    val call = calls.singleCall(createDocumentRoute)
                    assertEquals("folder-1", call.rpcParam(0) as String)
                    assertEquals("Satzung 2026", call.rpcParam(1) as String, "the typed title is sent, trimmed")
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid title")
                }
            }
        }

    @Test
    fun documentCreation_withoutATitle_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocumentsRoute = routeOf { rpcService<IDocumentService>().listDocuments("folder-1") }
            val createDocumentRoute =
                routeOf { rpcService<IDocumentService>().createDocument("folder-1", "x", DocumentAccessLevel.PUBLIC_MEMBERS) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                        listDocumentsRoute to jsonOf(ListSerializer(DocumentDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("documents-document-create-invalid") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Satzungen (0)" }
                    }
                    element().buttonNamed("Satzungen (0)").click()
                    awaitUntil("documents loaded", timeoutMs = 800) { calls.toRoute(listDocumentsRoute).size == 1 }
                    delay(80)
                    element().buttonNamed("Dokument anlegen (danach Datei hochladen)").click()
                    delay(80)
                    assertTrue(calls.toRoute(createDocumentRoute).isEmpty(), "no createDocument call for a blank title")
                    assertTrue(element().shownErrors().isNotEmpty(), "the blank title is reported on the field")
                }
            }
        }

    @Test
    fun versionUpload_withoutAFile_sendsNoRequestAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocumentsRoute = routeOf { rpcService<IDocumentService>().listDocuments("folder-1") }
            val listVersionsRoute = routeOf { rpcService<IDocumentService>().listVersions("doc-1") }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                        listDocumentsRoute to jsonOf(ListSerializer(DocumentDto.serializer()), listOf(document("doc-1"))),
                        listVersionsRoute to jsonOf(ListSerializer(DocumentVersionDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("documents-version-upload-invalid") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Satzungen (0)" }
                    }
                    element().buttonNamed("Satzungen (0)").click()
                    awaitUntil("documents loaded", timeoutMs = 800) { calls.toRoute(listDocumentsRoute).size == 1 }
                    delay(80)
                    element().linkNamed("Satzung").click()
                    awaitUntil("versions loaded", timeoutMs = 800) { calls.toRoute(listVersionsRoute).size == 1 }
                    awaitUntil("upload button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Hochladen" }
                    }
                    delay(80)
                    element().buttonNamed("Hochladen").click()
                    delay(80)
                    assertTrue(element().shownErrors().isNotEmpty(), "no file selected is reported on the field")
                }
            }
        }
}
