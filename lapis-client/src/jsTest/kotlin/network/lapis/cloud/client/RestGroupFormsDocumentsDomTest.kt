package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDocumentService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the documents: two title rows with a create button each ("Neuer Ordner", "Neues Dokument"). The document button
 * lives in a slot of its own that appears only once a folder is open AND its documents are loaded; a folder switch over a typed document form
 * asks first; a late answer of an earlier folder never shows the button for the wrong folder (generation counter).
 */
class RestGroupFormsDocumentsDomTest {
    private val folderFormId = "documents-folder-create"
    private val documentFormId = "documents-document-create"
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun folder(
        id: String,
        name: String,
    ) = DocumentFolderDto(
        id = id,
        name = name,
        parentFolderId = null,
        documentCount = 0,
        accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
    )

    private fun document(
        id: String,
        folderId: String,
    ) = DocumentDto(
        id = id,
        folderId = folderId,
        title = "Satzung $id",
        currentVersionId = null,
        createdBy = "caller-1",
        createdByDisplayName = "Vera Vorstand",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
        isDeleted = false,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.linkNamed(text: String): HTMLElement =
        assertNotNull(allOf("a").firstOrNull { it.textContent?.trim() == text }, "no link '$text'")

    @Test
    fun aMember_seesNoCreateButtonAtAll(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == listFolders) {
                        request.answerWith(jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder("fa", "Satzungen"))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-docs-member") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder row") { element().shows("Satzungen") }
                    assertEquals(emptyList(), element().createButtonLabels())
                }
            }
        }

    @Test
    fun theFolderButtonIsThereAtOnce_theDocumentButtonOnlyAfterAFolderIsOpenAndLoaded(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocuments = routeOf { rpcService<IDocumentService>().listDocuments("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listFolders ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(DocumentFolderDto.serializer()),
                                    listOf(folder("fa", "Satzungen"), folder("fz", "${KV_MARKER}Geheim")),
                                ),
                            )
                        request.rpcRoute == listDocuments ->
                            request.answerWith(jsonOf(ListSerializer(DocumentDto.serializer()), listOf(document("d1", "fa"))))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-docs-buttons") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder rows") { element().shows("Satzungen") }
                    val screen = element()
                    assertFalse(screen.shows(KV_MARKER), "a forged i18n marker in a folder name never reaches the DOM")
                    assertEquals(
                        listOf("Neuer Ordner"),
                        screen.createButtonLabels(),
                        "the document button is not even built while its slot is hidden",
                    )
                    assertTrue(screen.createButtonShown(folderFormId), "the folder button is shown at once")
                    assertFalse(screen.createButtonShown(documentFormId), "no document button without an open folder")

                    screen.linkNamed("Satzungen").click()
                    awaitUntil("the documents were loaded") { calls.toRoute(listDocuments).size == 1 }
                    awaitUntil("the document button appears") { screen.createButtonShown(documentFormId) }
                    assertFalse(screen.hostOpen(documentFormId), "collapsed after the load")
                    assertEquals(
                        listOf("Neuer Ordner", "Neues Dokument"),
                        screen.createButtonLabels(),
                        "one button per title row, never a second",
                    )

                    val host = openCreateForm(screen, documentFormId)
                    pressEscape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen(documentFormId) }
                }
            }
        }

    @Test
    fun changingTheFolderOverATypedDocumentForm_asksFirst(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocuments = routeOf { rpcService<IDocumentService>().listDocuments("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listFolders ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(DocumentFolderDto.serializer()),
                                    listOf(folder("fa", "Satzungen"), folder("fb", "Protokolle")),
                                ),
                            )
                        request.rpcRoute == listDocuments ->
                            request.answerWith(
                                jsonOf(ListSerializer(DocumentDto.serializer()), emptyList()),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-docs-switch") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder rows") { element().shows("Protokolle") }
                    val screen = element()
                    screen.linkNamed("Satzungen").click()
                    awaitUntil("folder A loaded") { calls.toRoute(listDocuments).size == 1 }
                    awaitUntil("the document button appears") { screen.createButtonShown(documentFormId) }
                    val host = openCreateForm(screen, documentFormId)
                    host.typeInto("Neuer Dokumenttitel", "Entwurf")

                    screen.linkNamed("Protokolle").click()
                    answerDiscardDialog("Weiter bearbeiten")
                    assertEquals(1, calls.toRoute(listDocuments).size, "'Weiter bearbeiten' does not switch the folder")
                    assertTrue(screen.hostOpen(documentFormId), "the typed form stays")

                    screen.linkNamed("Protokolle").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("folder B loaded") { calls.toRoute(listDocuments).size == 2 }
                    assertEquals("fb", calls.toRoute(listDocuments).last().rpcParam(0) as String)
                    assertFalse(screen.hostOpen(documentFormId), "the form is closed after the switch")
                    awaitUntil("the button is back for folder B") { screen.createButtonShown(documentFormId) }
                }
            }
        }

    @Test
    fun aLateAnswerOfAnEarlierFolder_neverShowsTheButtonForAFolderThatFailedToLoad(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocuments = routeOf { rpcService<IDocumentService>().listDocuments("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listFolders ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(DocumentFolderDto.serializer()),
                                    listOf(folder("fa", "Satzungen"), folder("fb", "Protokolle")),
                                ),
                            )
                        request.rpcRoute == listDocuments ->
                            if (request.rpcParam(0) as String == "fa") {
                                // A answers late and successfully ...
                                StubResponse(
                                    text = request.answerWith(jsonOf(ListSerializer(DocumentDto.serializer()), emptyList())).text,
                                    delayMs = 600,
                                )
                            } else {
                                // ... B fails at once.
                                serviceExceptionResult(request.json.id as Int, conflict)
                            }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-docs-race") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder rows") { element().shows("Protokolle") }
                    val screen = element()
                    screen.linkNamed("Satzungen").click()
                    screen.linkNamed("Protokolle").click()
                    awaitUntil("both folders were requested") { calls.toRoute(listDocuments).size == 2 }
                    delay(900)
                    assertFalse(
                        screen.createButtonShown(documentFormId),
                        "the late answer of folder A must not show the button for folder B",
                    )
                }
            }
        }

    @Test
    fun aSavedFolderAndASavedDocument_foldBack_andReload(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocuments = routeOf { rpcService<IDocumentService>().listDocuments("x") }
            val createFolder = routeOf { rpcService<IDocumentService>().createFolder("x", null) }
            val createDocument =
                routeOf { rpcService<IDocumentService>().createDocument("x", "t", DocumentAccessLevel.PUBLIC_MEMBERS) }
            val folders = mutableListOf(folder("fa", "Satzungen"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listFolders ->
                            request.answerWith(
                                jsonOf(ListSerializer(DocumentFolderDto.serializer()), folders.toList()),
                            )
                        request.rpcRoute == listDocuments ->
                            request.answerWith(
                                jsonOf(ListSerializer(DocumentDto.serializer()), emptyList()),
                            )
                        request.rpcRoute == createFolder -> {
                            folders += folder("fn", "Neuer Ordner Eins")
                            request.answerWith(jsonOf(DocumentFolderDto.serializer(), folders.last()))
                        }
                        request.rpcRoute == createDocument ->
                            request.answerWith(jsonOf(DocumentDto.serializer(), document("dn", "fa")))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-docs-save") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder row") { element().shows("Satzungen") }
                    val screen = element()

                    val folderHost = openCreateForm(screen, folderFormId)
                    folderHost.typeInto("Neuer Ordnername", "Neuer Ordner Eins")
                    val foldersBefore = calls.toRoute(listFolders).size
                    folderHost.buttonNamed("Ordner anlegen").click()
                    awaitUntil("createFolder") { calls.toRoute(createFolder).size == 1 }
                    awaitUntil("the folder form folded back") { !screen.hostOpen(folderFormId) }
                    awaitUntil("the folder list was reloaded") { calls.toRoute(listFolders).size > foldersBefore }
                    awaitUntil("the new folder is listed") { screen.shows("Neuer Ordner Eins") }

                    screen.linkNamed("Satzungen").click()
                    awaitUntil("documents loaded") { calls.toRoute(listDocuments).size == 1 }
                    awaitUntil("the document button appears") { screen.createButtonShown(documentFormId) }
                    val documentHost = openCreateForm(screen, documentFormId)
                    documentHost.typeInto("Neuer Dokumenttitel", "Satzung 2027")
                    documentHost.buttonNamed("Dokument anlegen (danach Datei hochladen)").click()
                    awaitUntil("createDocument") { calls.toRoute(createDocument).size == 1 }
                    assertEquals("fa", calls.singleCall(createDocument).rpcParam(0) as String)
                    awaitUntil("the document form folded back") { !screen.hostOpen(documentFormId) }
                    awaitUntil("the documents were reloaded") { calls.toRoute(listDocuments).size == 2 }
                    awaitUntil("the button is back") { screen.createButtonShown(documentFormId) }
                }
            }
        }
}
