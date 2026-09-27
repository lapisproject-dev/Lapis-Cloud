package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.domain.FolderAccessLevelChangeDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDocumentService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
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

    private val folder =
        DocumentFolderDto(
            id = "folder-1",
            name = "Satzungen",
            parentFolderId = null,
            documentCount = 0,
            accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
        )

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
                    awaitUntil("folder link rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Satzungen" }
                    }
                    element().linkNamed("Satzungen").click()
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
                    awaitUntil("folder link rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Satzungen" }
                    }
                    element().linkNamed("Satzungen").click()
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
                    awaitUntil("folder link rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Satzungen" }
                    }
                    element().linkNamed("Satzungen").click()
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
    // ── Welle V1.9.1, fix round: the "Sichtbarkeit" column and the two modals ────────────────────

    /**
     * The icon-only row-action button whose accessible name is [name] (see `tableActionTooltip`);
     * [nth] picks among several. Both the folder table and the document table carry a
     * "Sichtbarkeit ändern" button, and the folder table is rendered FIRST -- so the document row's
     * button is `nth = 1` on a screen with an opened folder. Taking the first match blindly opened the
     * folder modal and sent `setFolderAccessLevel`, which is exactly how this helper first failed.
     */
    private fun HTMLElement.actionButtonNamed(
        name: String,
        nth: Int = 0,
    ): HTMLElement {
        val matching = allOf("button").filter { it.getAttribute("aria-label") == name }
        return assertNotNull(matching.getOrNull(nth), "no row-action button '$name' #$nth (found ${matching.size})")
    }

    private val adminSession =
        SessionInfoDto(
            memberId = "admin-member-1",
            displayName = "Admin-Testperson",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    /** Folgepunkt zu V1.9.1: needed to pin the folder-creation default for TREASURER (also BOARD_ONLY, same as BOARD). */
    private val treasurerSession =
        SessionInfoDto(
            memberId = "treasurer-member-1",
            displayName = "Treasurer-Testperson",
            role = AccountRole.TREASURER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val boardOnlyFolder =
        DocumentFolderDto(
            id = "folder-2",
            name = "Vorstandsprotokolle",
            parentFolderId = null,
            documentCount = 3,
            accessLevel = DocumentAccessLevel.BOARD_ONLY,
        )

    @Test
    fun folderList_showsTheVisibilityBadgeOfEachFolder(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to
                            jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder, boardOnlyFolder)),
                    ),
            ) {
                mountedForm("documents-folder-visibility-column") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder rows rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Vorstandsprotokolle" }
                    }
                    val texts = element().allOf("span").mapNotNull { it.textContent?.trim() }
                    assertTrue("Alle Mitglieder" in texts, "the PUBLIC_MEMBERS folder shows its translated badge; saw $texts")
                    assertTrue("Nur Vorstand" in texts, "the BOARD_ONLY folder shows its translated badge; saw $texts")
                    // Not the raw enum constant -- the labelling bug this wave also fixed.
                    assertTrue(texts.none { it == "PUBLIC_MEMBERS" || it == "BOARD_ONLY" }, "no raw enum constant is shown")
                }
            }
        }

    @Test
    fun folderCreation_sendsTheChosenAccessLevel(): Promise<Unit> =
        formTest {
            // Fix round (W1): without its own select, every new folder was PUBLIC_MEMBERS by force and
            // its NAME -- often the sensitive part -- was visible to every member until a second step.
            // Folgepunkt zu V1.9.1: BOARD's own default is now BOARD_ONLY (not PUBLIC_MEMBERS), so this
            // test deliberately chooses PUBLIC_MEMBERS -- otherwise the chosen value and the default
            // would coincide and the test would no longer distinguish "the choice is sent" from "the
            // default is sent" (see [folderCreation_sendsTheDefaultLevel_whenNothingIsChosen] for that).
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
                mountedForm("documents-folder-create-level") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    element().typeInto("Neuer Ordnername", "Kündigungen Q3")
                    element().chooseIn("Sichtbarkeit", DocumentAccessLevel.PUBLIC_MEMBERS.name)
                    element().buttonNamed("Ordner anlegen").click()
                    awaitUntil("createFolder", timeoutMs = 800) { calls.toRoute(createFolderRoute).size == 1 }
                    val call = calls.singleCall(createFolderRoute)
                    assertEquals("Kündigungen Q3", call.rpcParam(0) as String)
                    assertEquals(
                        "PUBLIC_MEMBERS",
                        call.rpcParam(2) as String,
                        "the chosen level is sent, not the role default (BOARD_ONLY)",
                    )
                }
            }
        }

    /** Folgepunkt zu V1.9.1: the select is preselected to the role default, not always PUBLIC_MEMBERS. */
    @Test
    fun folderCreation_admin_defaultsToAdminOnly(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond = answering(listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList())),
            ) {
                mountedForm("documents-folder-create-default-admin") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    val select = element().controlOf("Sichtbarkeit") as HTMLSelectElement
                    assertEquals("ADMIN_ONLY", select.value)
                }
            }
        }

    @Test
    fun folderCreation_board_defaultsToBoardOnly(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond = answering(listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList())),
            ) {
                mountedForm("documents-folder-create-default-board") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    val select = element().controlOf("Sichtbarkeit") as HTMLSelectElement
                    assertEquals("BOARD_ONLY", select.value)
                }
            }
        }

    @Test
    fun folderCreation_treasurer_defaultsToBoardOnly(): Promise<Unit> =
        formTest {
            AppState.setSession(treasurerSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond = answering(listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList())),
            ) {
                mountedForm("documents-folder-create-default-treasurer") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    val select = element().controlOf("Sichtbarkeit") as HTMLSelectElement
                    assertEquals("BOARD_ONLY", select.value)
                }
            }
        }

    @Test
    fun folderCreation_sendsTheDefaultLevel_whenNothingIsChosen(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val createFolderRoute = routeOf { rpcService<IDocumentService>().createFolder("x", null) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList()),
                        createFolderRoute to jsonOf(DocumentFolderDto.serializer(), folder),
                    ),
            ) { calls ->
                mountedForm("documents-folder-create-default-sent") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    element().typeInto("Neuer Ordnername", "Kündigungen Q3")
                    // Deliberately no chooseIn(...) call -- the default itself must be sent.
                    element().buttonNamed("Ordner anlegen").click()
                    awaitUntil("createFolder", timeoutMs = 800) { calls.toRoute(createFolderRoute).size == 1 }
                    val call = calls.singleCall(createFolderRoute)
                    assertEquals("ADMIN_ONLY", call.rpcParam(2) as String, "the untouched default (ADMIN's own) is sent")
                }
            }
        }

    @Test
    fun folderCreation_resetsToTheDefaultLevel_afterCreating(): Promise<Unit> =
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
                mountedForm("documents-folder-create-reset-after") { root, element ->
                    renderDocumentsScreen(root)
                    delay(80)
                    element().chooseIn("Sichtbarkeit", DocumentAccessLevel.PUBLIC_MEMBERS.name)
                    element().typeInto("Neuer Ordnername", "Kündigungen Q3")
                    element().buttonNamed("Ordner anlegen").click()
                    awaitUntil("createFolder", timeoutMs = 800) { calls.toRoute(createFolderRoute).size == 1 }
                    delay(80)
                    val select = element().controlOf("Sichtbarkeit") as HTMLSelectElement
                    assertEquals(
                        "BOARD_ONLY",
                        select.value,
                        "the select springs back to the role default (BOARD_ONLY), not the last-chosen value",
                    )
                }
            }
        }

    @Test
    fun folderVisibilityModal_warnsAboutTheCascadeAndSendsTheChosenLevel(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val setFolderLevelRoute =
                routeOf { rpcService<IDocumentService>().setFolderAccessLevel("folder-1", DocumentAccessLevel.ADMIN_ONLY) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                        setFolderLevelRoute to
                            jsonOf(
                                FolderAccessLevelChangeDto.serializer(),
                                FolderAccessLevelChangeDto(folder = folder, tightenedDocuments = 2, tightenedFolders = 1),
                            ),
                    ),
            ) { calls ->
                mountedForm("documents-folder-visibility-modal") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder row rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Satzungen" }
                    }
                    element().actionButtonNamed("Sichtbarkeit ändern").click()
                    awaitUntil("modal opened", timeoutMs = 800) {
                        lastOpenModal().allOf("button").any { it.textContent?.trim() == "Speichern" }
                    }
                    val modal = lastOpenModal()
                    val modalText = modal.textContent.orEmpty()
                    // Both cascade sentences land BEFORE the click, and both name sub-folders as well as
                    // documents since the tighten materializes into descendant folders too (B5).
                    assertTrue(
                        modalText.contains("schränkt alle enthaltenen Unterordner und Dokumente mit ein"),
                        "the restricting sentence is shown; saw: $modalText",
                    )
                    assertTrue(
                        modalText.contains("erweitert die enthaltenen Unterordner und Dokumente nicht"),
                        "the expanding sentence is shown; saw: $modalText",
                    )
                    modal.chooseIn("Sichtbarkeit", DocumentAccessLevel.ADMIN_ONLY.name)
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("setFolderAccessLevel", timeoutMs = 800) { calls.toRoute(setFolderLevelRoute).size == 1 }
                    val call = calls.singleCall(setFolderLevelRoute)
                    assertEquals("folder-1", call.rpcParam(0) as String)
                    assertEquals("ADMIN_ONLY", call.rpcParam(1) as String)
                }
            }
        }

    @Test
    fun documentVisibilityModal_sendsTheChosenLevel(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocumentsRoute = routeOf { rpcService<IDocumentService>().listDocuments("folder-1") }
            val setDocumentLevelRoute =
                routeOf { rpcService<IDocumentService>().setDocumentAccessLevel("doc-1", DocumentAccessLevel.ADMIN_ONLY) }
            withFetchStub(
                respond =
                    answering(
                        listFoldersRoute to jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                        listDocumentsRoute to jsonOf(ListSerializer(DocumentDto.serializer()), listOf(document("doc-1"))),
                        setDocumentLevelRoute to jsonOf(DocumentDto.serializer(), document("doc-1")),
                    ),
            ) { calls ->
                mountedForm("documents-document-visibility-modal") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("folder link rendered", timeoutMs = 800) {
                        element().allOf("a").any { it.textContent?.trim() == "Satzungen" }
                    }
                    element().linkNamed("Satzungen").click()
                    awaitUntil("documents loaded", timeoutMs = 800) { calls.toRoute(listDocumentsRoute).size == 1 }
                    delay(80)
                    // nth = 1: the folder table's own "Sichtbarkeit ändern" button comes first.
                    element().actionButtonNamed("Sichtbarkeit ändern", nth = 1).click()
                    awaitUntil("modal opened", timeoutMs = 800) {
                        lastOpenModal().allOf("button").any { it.textContent?.trim() == "Speichern" }
                    }
                    val modal = lastOpenModal()
                    modal.chooseIn("Sichtbarkeit", DocumentAccessLevel.ADMIN_ONLY.name)
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("setDocumentAccessLevel", timeoutMs = 800) { calls.toRoute(setDocumentLevelRoute).size == 1 }
                    val call = calls.singleCall(setDocumentLevelRoute)
                    assertEquals("doc-1", call.rpcParam(0) as String)
                    assertEquals("ADMIN_ONLY", call.rpcParam(1) as String)
                }
            }
        }
}
