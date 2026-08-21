package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.rpc.IDocumentService

/**
 * Screen 6 of the V0.7.3 plan -- a real folder/document/version browser with upload, against the
 * dedicated HTTP routes file bytes travel over (`network.lapis.cloud.server.routes.DocumentRoutes`,
 * see [DocumentHttp] and `IDocumentService` KDoc for why: not through Kilua RPC, which is
 * inefficient for large byte arrays). Server-side access-level filtering (`listDocuments`) and
 * role checks (`createFolder`/`createDocument`/`deleteDocument`/the upload route: BOARD/ADMIN
 * only) are the actual authority -- this screen's `canManage` gating is a UX nicety on top of that,
 * matching the same posture every other privileged action in this wave takes.
 */
fun renderDocumentsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("Dokumentenablage"))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

    root.h2(tr("Ordner"))
    val folderPanel = root.vPanel(spacing = 4)
    val folderCreationPanel = if (canManage) root.vPanel(spacing = 6) else null

    root.h2(tr("Dokumente"))
    val documentPanel = root.vPanel(spacing = 6)

    root.h2(tr("Versionen"))
    val versionPanel = root.vPanel(spacing = 6)

    fun loadVersions(document: DocumentDto) {
        versionPanel.removeAll()
        AppScope.launch {
            val versions = guarded { rpcService<IDocumentService>().listVersions(document.id) } ?: return@launch
            versionPanel.p(gettext("Versionen von \"%1\":", document.title))
            if (versions.isEmpty()) {
                versionPanel.p(tr("Noch keine Version hochgeladen."))
            } else {
                versions.forEach { version ->
                    val row = versionPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
                    val changeNoteSuffix = version.changeNote?.let { gettext(" -- %1", it) } ?: ""
                    row.div(
                        gettext(
                            "v%1: %2 (%3 Bytes, hochgeladen von %4 am %5)",
                            version.versionNumber,
                            version.fileName,
                            version.fileSizeBytes,
                            version.uploadedByDisplayName,
                            version.uploadedAt,
                        ) + changeNoteSuffix,
                    ) { addCssClass("flex-grow-1") }
                    row.link(tr("Herunterladen"), url = DocumentHttp.downloadUrl(document.id, version.id), target = "_blank")
                }
            }
            if (canManage) renderVersionUpload(versionPanel, document.id) { loadVersions(document) }
        }
    }

    fun loadDocuments(folderId: String) {
        documentPanel.removeAll()
        versionPanel.removeAll()
        AppScope.launch {
            val documents = guarded { rpcService<IDocumentService>().listDocuments(folderId) } ?: return@launch
            if (documents.isEmpty()) {
                documentPanel.p(tr("Keine Dokumente in diesem Ordner."))
            } else {
                documents.forEach { document ->
                    val row = documentPanel.hPanel(spacing = 8) { addCssClasses("border rounded p-2 align-items-center") }
                    // dataNavigo = false: rein lokaler Klick-Handler (laedt Versionen unten,
                    // keine Route) -- siehe LoginScreen.kt-Kommentar zum globalen
                    // Link.useDataNavigoForLinks-Default (V1.2.4-Audit, dataNavigo-Sweep).
                    val titleLink =
                        row.link(document.title, url = "javascript:void(0)", dataNavigo = false) {
                            addCssClass("flex-grow-1")
                        }
                    titleLink.onClick { loadVersions(document) }
                    if (canManage) {
                        val deleteButton = row.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
                        deleteButton.onClick {
                            confirmDialog(
                                title = tr("Dokument löschen"),
                                message =
                                    gettext(
                                        "\"%1\" wirklich löschen? (Soft-Delete -- bisherige Versionen " +
                                            "bleiben zu Prüfzwecken erhalten, das Dokument verschwindet aus der Ansicht.)",
                                        document.title,
                                    ),
                                confirmLabel = tr("Löschen"),
                            ) {
                                AppScope.launch {
                                    val result = guarded { rpcService<IDocumentService>().deleteDocument(document.id) }
                                    if (result != null) {
                                        notifySuccess(tr("Gelöscht."))
                                        loadDocuments(folderId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (canManage) renderDocumentCreation(documentPanel, folderId) { loadDocuments(folderId) }
        }
    }

    fun refreshFolders() {
        folderPanel.removeAll()
        AppScope.launch {
            val folders = guarded { rpcService<IDocumentService>().listFolders() } ?: emptyList()
            if (folders.isEmpty()) {
                folderPanel.p(tr("Noch keine Ordner vorhanden."))
            } else {
                folders.forEach { folder ->
                    val folderButton = folderPanel.button(folder.name, style = ButtonStyle.OUTLINESECONDARY)
                    folderButton.onClick { loadDocuments(folder.id) }
                }
            }
        }
    }

    refreshFolders()
    if (canManage && folderCreationPanel != null) {
        renderFolderCreation(folderCreationPanel) { refreshFolders() }
    }
}

private fun renderFolderCreation(
    panel: SimplePanel,
    onCreated: () -> Unit,
) {
    val nameInput = panel.text(label = tr("Neuer Ordnername"))
    val createButton = panel.button(tr("Ordner anlegen"), style = ButtonStyle.OUTLINEPRIMARY)
    createButton.onClick {
        val name = nameInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(name)) return@onClick
        createButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IDocumentService>().createFolder(name, null) }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Ordner \"%1\" angelegt.", name))
                nameInput.value = null
                onCreated()
            }
        }
    }
}

private fun renderDocumentCreation(
    panel: SimplePanel,
    folderId: String,
    onCreated: () -> Unit,
) {
    val accessLevelOptions = DocumentAccessLevel.entries.map { it.name to it.name }
    val titleInput = panel.text(label = tr("Neuer Dokumenttitel"))
    val accessSelect =
        panel.select(options = accessLevelOptions, value = DocumentAccessLevel.PUBLIC_MEMBERS.name, label = tr("Sichtbarkeit"))
    val createButton = panel.button(tr("Dokument anlegen (danach Datei hochladen)"), style = ButtonStyle.OUTLINEPRIMARY)
    createButton.onClick {
        val title = titleInput.value.orEmpty().trim()
        val accessLevelValue = accessSelect.value
        if (!Validation.isNonBlank(title) || accessLevelValue == null) return@onClick
        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IDocumentService>().createDocument(folderId, title, DocumentAccessLevel.valueOf(accessLevelValue))
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Dokument \"%1\" angelegt -- jetzt eine Datei hochladen.", title))
                onCreated()
            }
        }
    }
}

private fun renderVersionUpload(
    panel: SimplePanel,
    documentId: String,
    onUploaded: () -> Unit,
) {
    val uploadRow = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val fileUpload = uploadRow.upload(label = tr("Neue Version hochladen"))
    val changeNoteInput = uploadRow.text(label = tr("Änderungshinweis (optional)"))
    val errorBox =
        uploadRow.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val uploadButton = uploadRow.button(tr("Hochladen"), style = ButtonStyle.PRIMARY)
    uploadButton.onClick {
        errorBox.hide()
        val selected = fileUpload.value?.firstOrNull()
        val nativeFile = selected?.let { fileUpload.getNativeFile(it) }
        if (nativeFile == null) {
            errorBox.content = tr("Bitte eine Datei auswählen.")
            errorBox.show()
            return@onClick
        }
        uploadButton.disabled = true
        AppScope.launch {
            val error = DocumentHttp.uploadVersion(documentId, nativeFile, changeNoteInput.value)
            uploadButton.disabled = false
            if (error != null) {
                errorBox.content = error
                errorBox.show()
            } else {
                notifySuccess(tr("Version hochgeladen."))
                fileUpload.clearInput()
                changeNoteInput.value = null
                onUploaded()
            }
        }
    }
}
