package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AiIndexStatus
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.rpc.IAiAssistantService
import network.lapis.cloud.shared.rpc.IDocumentService

/**
 * Screen 6 of the V0.7.3 plan -- a real folder/document/version browser with upload, against the
 * dedicated HTTP routes file bytes travel over (`network.lapis.cloud.server.routes.DocumentRoutes`,
 * see [DocumentHttp] and `IDocumentService` KDoc for why: not through Kilua RPC, which is
 * inefficient for large byte arrays). Server-side access-level filtering (`listDocuments`) and
 * role checks (`createFolder`/`createDocument`/`deleteDocument`/the upload route: BOARD/TREASURER/
 * ADMIN, i.e. `ESCALATED_ROLES` -- Welle "Treasurer Document Upload") are the actual authority --
 * this screen's `canManage` gating is a UX nicety on top of that, matching the same posture every
 * other privileged action in this wave takes. Delegated to [DocumentsAuthzUi.canManage] (not
 * computed inline any more -- review finding fix), which is the pure, unit-tested predicate that
 * must mirror the server's role set exactly: see that object's own KDoc.
 */
fun renderDocumentsScreen(container: SimplePanel) {
    val root = container.dataScreenRoot(spacing = 14)
    root.pageHeader(tr("Dokumentenablage"))
    val canManage = DocumentsAuthzUi.canManage(AppState.session?.role)

    root.h2(tr("Ordner")) { addCssClass("h5") }
    val folderPanel = root.vPanel(spacing = 4)
    val folderCreationPanel = if (canManage) root.vPanel(spacing = 6) else null

    root.h2(tr("Dokumente")) { addCssClass("h5") }
    val documentPanel = root.vPanel(spacing = 6)

    root.h2(tr("Versionen")) { addCssClass("h5") }
    val versionPanel = root.vPanel(spacing = 6)

    fun loadVersions(document: DocumentDto) {
        versionPanel.removeAll()
        AppScope.launch {
            val versions = guarded { rpcService<IDocumentService>().listVersions(document.id) }
            if (versions == null) {
                // Welle V1.4.27 (W3): a failed load is an error state with a retry, not an empty panel.
                versionPanel.dataErrorState(onRetry = { loadVersions(document) })
                return@launch
            }
            versionPanel.p(gettext("Versionen von \"%1\":", document.title))
            if (versions.isEmpty()) {
                versionPanel.p(tr("Noch keine Version hochgeladen."))
            } else {
                // A `dataTable` (guideline 2.4): the version list is fully loaded, so it sorts on the client. Only
                // this host is re-rendered by a sort click -- the upload form below must survive it.
                val tableHost = versionPanel.vPanel(spacing = 0)
                var versionSort: SortState? = null
                var pendingSortFocus: String? = null

                fun renderVersionTable() {
                    tableHost.removeAll()
                    val focusKey = pendingSortFocus
                    pendingSortFocus = null
                    tableHost.dataTable(
                        columns = versionColumns(),
                        rows = sortDocumentVersions(versions, versionSort),
                        sort = versionSort,
                        onSort = { next ->
                            versionSort = next
                            pendingSortFocus = next?.key
                            renderVersionTable()
                        },
                        sortOptions = VERSION_SORT_OPTIONS,
                        actions = { actions, version ->
                            actions.link(
                                tr("Herunterladen"),
                                url = DocumentHttp.downloadUrl(document.id, version.id),
                                icon = "fas fa-download",
                                target = "_blank",
                            )
                        },
                        focusSortKey = focusKey,
                    )
                }
                renderVersionTable()
            }
            if (canManage) renderVersionUpload(versionPanel, document.id) { loadVersions(document) }
        }
    }

    fun loadDocuments(folderId: String) {
        documentPanel.removeAll()
        versionPanel.removeAll()
        // Drei Geschwister-Panels statt einem gemeinsamen `documentPanel` fuer Suchzeile, Liste und
        // Anlage-Formular (Stolperfalle 1 der Implementierungswelle): `renderDocumentCreation` lief
        // frueher im selben Panel wie die Dokumentzeilen -- ein Filter-Re-Render haette das
        // Anlage-Formular bei jedem Tastendruck abgerissen und den Fokus aus dem Suchfeld geworfen.
        // Alle drei werden bei jedem `loadDocuments`-Aufruf frisch unter `documentPanel` erzeugt, das
        // vorherige `documentPanel.removeAll()` entsorgt die alten Referenzen mit -- kein Extra-Reset
        // beim Ordnerwechsel noetig.
        val searchRow = documentPanel.hPanel(spacing = 8)
        val listPanel = documentPanel.vPanel(spacing = 6)
        val creationPanel = documentPanel.vPanel(spacing = 6)

        AppScope.launch {
            val documents = guarded { rpcService<IDocumentService>().listDocuments(folderId) }
            if (documents == null) {
                // Welle V1.4.27 (W3): a failed load is an error state with a retry, not an empty panel.
                listPanel.dataErrorState(onRetry = { loadDocuments(folderId) })
                return@launch
            }

            // V1.6.1 "Wissensbasis" column -- only where the AI layer is operational (else the service is
            // not even registered) and only for BOARD/ADMIN. A failed load just hides the column.
            // The state lives HERE, outside the cell lambdas: `dataTable` cells run again on every re-render (sort,
            // mode switch), so a `var entry` inside the lambda would lose the state of a released document.
            val knowledgeEntries: MutableMap<String, AiKnowledgeEntryDto> =
                if (DocumentsAuthzUi.showsKnowledgeBaseColumn(AppState.session?.role, AppState.session?.aiAssistantEnabled == true)) {
                    guarded { rpcService<IAiAssistantService>().listKnowledgeEntries() }
                        ?.associateBy { it.documentId }
                        .orEmpty()
                        .toMutableMap()
                } else {
                    mutableMapOf()
                }
            val showsKnowledgeColumn = knowledgeEntries.isNotEmpty()
            var documentSort: SortState? = null
            var pendingDocumentSortFocus: String? = null

            // Schwellenwert bewusst auf der tatsaechlich geladenen Liste, nicht auf
            // `folder.documentCount` (der zaehlt server-seitig VOR der Access-Level-Filterung).
            var searchInput: Text? = null
            if (shouldShowDocumentSearch(documents.size)) {
                searchInput = searchRow.text(label = tr("Dokumente in diesem Ordner durchsuchen"))
            }

            // Aktuell im Versionen-Panel geoeffnetes Dokument -- getrackt, damit `renderList` das
            // Panel nur leert, wenn dieses Dokument durch den Filter tatsaechlich herausfaellt
            // (nicht bei jeder Filteraenderung pauschal, siehe Kommentar in `renderList`).
            var openDocumentId: String? = null

            // Zuletzt tatsaechlich gerenderte Query -- dedupliziert einen doppelten `renderList`-Aufruf,
            // falls das programmatische `searchInput.value = null` im Reset-Link-Handler zusaetzlich
            // den `subscribe`-Callback ausloest (KVisions genaues Verhalten dabei ist nicht verifiziert,
            // dieser Guard macht das Verhalten unabhaengig davon korrekt).
            var lastRenderedQuery: String? = null

            fun renderList(query: String) {
                lastRenderedQuery = query
                listPanel.removeAll()
                val filtered = filterDocuments(documents, query)
                // Versionspanel nur leeren, wenn das aktuell geoeffnete Dokument durch den Filter
                // herausfaellt -- sonst bleibt es bei jeder Filteraenderung ersatzlos verschwinden,
                // obwohl das angezeigte Dokument weiterhin in der gefilterten Liste sichtbar ist.
                val currentlyOpenId = openDocumentId
                if (currentlyOpenId != null && filtered.none { it.id == currentlyOpenId }) {
                    versionPanel.removeAll()
                    openDocumentId = null
                }
                if (documents.isEmpty()) {
                    listPanel.p(tr("Keine Dokumente in diesem Ordner."))
                } else if (filtered.isEmpty()) {
                    listPanel.p(gettext("Kein Dokument mit \"%1\" in diesem Ordner.", query.trim()))
                    val resetLink = listPanel.link(tr("Filter zurücksetzen"), url = "javascript:void(0)", dataNavigo = false)
                    resetLink.onClick {
                        // Reihenfolge bewusst so: `renderList("")` zuerst setzt `lastRenderedQuery = ""`,
                        // sodass der `subscribe`-Callback (falls er durch das nachfolgende `value = null`
                        // synchron feuert) den Dedupe-Guard tatsaechlich greifen sieht und nicht erneut
                        // rendert. In der umgekehrten Reihenfolge sah der Guard noch die alte Query und
                        // verhinderte das Doppel-Rendering nicht (siehe Review-Befund).
                        renderList("")
                        searchInput?.value = null
                    }
                } else {
                    if (query.isNotBlank()) {
                        listPanel.div(gettext("%1 von %2 Dokumenten", filtered.size, documents.size)) {
                            addCssClasses("text-muted small")
                        }
                    }
                    val focusKey = pendingDocumentSortFocus
                    pendingDocumentSortFocus = null
                    listPanel.dataTable(
                        columns =
                            documentColumns(showsKnowledgeColumn, knowledgeEntries) { document ->
                                openDocumentId = document.id
                                loadVersions(document)
                            },
                        rows = sortDocuments(filtered, documentSort),
                        sort = documentSort,
                        onSort = { next ->
                            documentSort = next
                            pendingDocumentSortFocus = next?.key
                            renderList(lastRenderedQuery.orEmpty())
                        },
                        sortOptions = DOCUMENT_SORT_OPTIONS,
                        actions =
                            if (canManage) {
                                { actions, document -> actions.renderDocumentDeleteAction(document) { loadDocuments(folderId) } }
                            } else {
                                null
                            },
                        focusSortKey = focusKey,
                    )
                }
            }

            renderList("")

            // Kein Debounce (Design-Team-Entscheidung: rein clientseitige Filterung, kein
            // RPC-Roundtrip pro Tastendruck) -- nur der `isInitialSearchEvent`-Guard, weil KVisions
            // `subscribe` bei der Registrierung sofort einmal synthetisch mit dem aktuellen Wert
            // aufruft (gleiches Muster wie `MemberAdministrationScreen.kt`).
            searchInput?.let { input ->
                var isInitialSearchEvent = true
                input.subscribe { value ->
                    if (isInitialSearchEvent) {
                        isInitialSearchEvent = false
                        return@subscribe
                    }
                    val normalized = value.orEmpty()
                    // Bereits gerendert (z. B. weil der Reset-Link-Handler `renderList("")` schon
                    // explizit aufgerufen hat, bevor/nachdem dieser Callback feuert) -- nicht doppelt
                    // rendern.
                    if (normalized == lastRenderedQuery) return@subscribe
                    renderList(normalized)
                }
            }

            if (canManage) {
                renderDocumentCreation(creationPanel, folderId, AppState.session?.role) { loadDocuments(folderId) }
            }
        }
    }

    fun refreshFolders() {
        folderPanel.removeAll()
        AppScope.launch {
            val folders = guarded { rpcService<IDocumentService>().listFolders() }
            if (folders == null) {
                // A failed load is NOT "Noch keine Ordner vorhanden." -- that would be a false statement about the data.
                folderPanel.dataErrorState(onRetry = { refreshFolders() })
                return@launch
            }
            if (folders.isEmpty()) {
                folderPanel.p(tr("Noch keine Ordner vorhanden."))
            } else {
                folders.forEach { folder ->
                    val folderButton =
                        folderPanel.button(
                            "${folder.name} (${folder.documentCount})",
                            icon = "fas fa-folder",
                            style = ButtonStyle.OUTLINESECONDARY,
                        )
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

/**
 * Reines, DOM-unabhaengiges Filter-Praedikat fuer die Dokumentensuche -- testbar ohne Rendering-
 * Harness (analog `FileDisplay.kt`). `DocumentDto` traegt kein `fileName`/`mimeType` (das existiert
 * nur auf `DocumentVersionDto`, separat per `listVersions` geladen), Suche ist deshalb zwangslaeufig
 * auf `title` beschraenkt.
 */
internal fun filterDocuments(
    documents: List<DocumentDto>,
    query: String,
): List<DocumentDto> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return documents
    return documents.filter { it.title.contains(trimmed, ignoreCase = true) }
}

/**
 * Sichtbarkeitsschwelle fuer das Suchfeld -- ab 5 Dokumenten (Design-Team-Fazit: "bei acht ist der
 * Nutzer schon am Scrollen"). Isoliert als pure Funktion, damit sie unabhaengig vom DOM-Code
 * testbar ist.
 */
internal fun shouldShowDocumentSearch(documentCount: Int): Boolean = documentCount >= 5

/** Sort keys (Welle V1.4.27 / W3): the documents by title, the versions by number or upload time. */
internal const val DOCUMENT_SORT_TITLE = "title"
internal const val VERSION_SORT_NUMBER = "version"
internal const val VERSION_SORT_UPLOADED = "uploadedAt"

private val DOCUMENT_SORT_OPTIONS = SortOptions(allowUnsorted = true)

/** The newest version first on the first click of the version number or the upload time; a third click unsorts. */
private val VERSION_SORT_OPTIONS =
    SortOptions(allowUnsorted = true, firstDirection = { SortDirection.DESC })

/** Clientseitige Sortierung der geladenen Dokumentenliste nach Titel (pur); `null` = Reihenfolge des Servers. */
internal fun sortDocuments(
    documents: List<DocumentDto>,
    sort: SortState?,
): List<DocumentDto> {
    if (sort == null) return documents
    val comparator = compareBy<DocumentDto, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    return documents.sortedWith(if (sort.direction == SortDirection.ASC) comparator else comparator.reversed())
}

/** Clientseitige Sortierung der geladenen Versionsliste (pur); `null` = Reihenfolge des Servers. */
internal fun sortDocumentVersions(
    versions: List<DocumentVersionDto>,
    sort: SortState?,
): List<DocumentVersionDto> {
    if (sort == null) return versions
    val comparator: Comparator<DocumentVersionDto> =
        when (sort.key) {
            VERSION_SORT_UPLOADED -> compareBy { it.uploadedAt }
            else -> compareBy { it.versionNumber }
        }
    return versions.sortedWith(if (sort.direction == SortDirection.ASC) comparator else comparator.reversed())
}

/**
 * Columns of the document list / card list. The title is a link that opens the versions below (a purely local
 * click handler, `dataNavigo = false`, see `LoginScreen.kt`) and the card title. The "Wissensbasis" column
 * exists only where the AI layer is on and the caller may decide (BOARD/ADMIN, see [DocumentsAuthzUi]).
 */
private fun documentColumns(
    showsKnowledgeColumn: Boolean,
    knowledgeEntries: MutableMap<String, AiKnowledgeEntryDto>,
    onOpen: (DocumentDto) -> Unit,
): List<DataColumn<DocumentDto>> =
    listOfNotNull(
        DataColumn(
            title = tr("Titel"),
            primary = true,
            sortKey = DOCUMENT_SORT_TITLE,
            cell = { container, document ->
                container.icon("fas fa-file")
                container.untrustedLink(document.title, url = "javascript:void(0)", dataNavigo = false).onClick { onOpen(document) }
            },
        ),
        if (showsKnowledgeColumn) {
            DataColumn(
                title = tr("Wissensbasis"),
                cell = { container, document ->
                    if (knowledgeEntries.containsKey(document.id)) renderKnowledgeControls(container, document.id, knowledgeEntries)
                },
            )
        } else {
            null
        },
    )

private fun versionColumns(): List<DataColumn<DocumentVersionDto>> =
    listOf(
        textColumn(title = tr("Version"), primary = true, sortKey = VERSION_SORT_NUMBER) { version: DocumentVersionDto ->
            gettext("v%1", version.versionNumber)
        },
        DataColumn(
            title = tr("Datei"),
            cell = { container, version ->
                container.icon(fileTypeIcon(version.mimeType))
                container.untrustedSpan(version.fileName)
            },
        ),
        textColumn(title = tr("Größe"), numeric = true) { version: DocumentVersionDto -> formatFileSize(version.fileSizeBytes) },
        textColumn(title = tr("Hochgeladen von")) { version: DocumentVersionDto -> version.uploadedByDisplayName },
        textColumn(title = tr("Hochgeladen am"), sortKey = VERSION_SORT_UPLOADED) { version: DocumentVersionDto ->
            version.uploadedAt.toString()
        },
        textColumn(title = tr("Änderungshinweis")) { version: DocumentVersionDto -> version.changeNote.orEmpty() },
        // Own text element, `text-muted small` as its own CSS class (design rule, see `formatDownloadCount` in FileDisplay.kt).
        DataColumn(
            title = tr("Downloads"),
            numeric = true,
            cell = {
                container,
                version,
                ->
                container.span(formatDownloadCount(version.downloadCount)) { addCssClasses("text-muted small") }
            },
        ),
    )

/** Delete action of a document row -- role gate (`canManage`) and confirmation dialog unchanged. */
internal fun Container.renderDocumentDeleteAction(
    document: DocumentDto,
    onDeleted: () -> Unit,
) {
    val deleteButton = tableActionButton("fas fa-trash", tr("Löschen"), ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        // Audit fix M9: the trigger is disabled while the delete runs, so a second click cannot open a second dialog for a second request.
        if (deleteButton.disabled) return@onClick
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
            runGuardedAction(deleteButton) {
                val result = guarded { rpcService<IDocumentService>().deleteDocument(document.id) }
                if (result != null) {
                    notifySuccess(tr("Gelöscht."))
                    onDeleted()
                }
            }
        }
    }
}

private fun renderFolderCreation(
    panel: SimplePanel,
    onCreated: () -> Unit,
) {
    val nameInput = panel.text(label = tr("Neuer Ordnername"))
    val createButton = panel.button(tr("Ordner anlegen"), icon = "fas fa-folder-plus", style = ButtonStyle.OUTLINEPRIMARY)
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
    role: AccountRole?,
    onCreated: () -> Unit,
) {
    // Review finding fix (Welle "Treasurer Document Upload", Runde 4): only offer access levels
    // the current role is actually allowed to create at -- see [DocumentsAuthzUi.allowedCreateLevels]
    // KDoc for the orphaned-document failure mode this prevents.
    val accessLevelOptions = DocumentsAuthzUi.allowedCreateLevels(role).map { it.name to it.name }
    val titleInput = panel.text(label = tr("Neuer Dokumenttitel"))
    val accessSelect =
        panel.select(options = accessLevelOptions, value = DocumentAccessLevel.PUBLIC_MEMBERS.name, label = tr("Sichtbarkeit"))
    val createButton =
        panel.button(
            tr("Dokument anlegen (danach Datei hochladen)"),
            icon = "fas fa-file-circle-plus",
            style = ButtonStyle.OUTLINEPRIMARY,
        )
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

    // Nutzer-Beschwerde 2026-09-15 ("kein Signal während des Uploads, man klickt wild") -- Bootstraps
    // eigene `.progress`/`.progress-bar`-Klassen, keine dedizierte KVision-Komponente nötig (diese
    // Version von KVision hat keine). Standardmäßig versteckt, nur während eines laufenden Uploads
    // sichtbar (nicht dauerhaft eingeblendet mit 0% -- Norman: ein Fortschrittsbalken, der nichts
    // tut, ist Lärm).
    val progressWrapper = uploadRow.div(className = "progress mt-1") { hide() }
    val progressBar = progressWrapper.div(className = "progress-bar progress-bar-striped progress-bar-animated")
    progressBar.setAttribute("role", "progressbar")
    progressBar.setStyle("width", "0%")

    val uploadButton = uploadRow.button(tr("Hochladen"), icon = "fas fa-upload", style = ButtonStyle.PRIMARY)
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
        progressBar.setStyle("width", "0%")
        progressWrapper.show()
        AppScope.launch {
            val error =
                DocumentHttp.uploadVersion(documentId, nativeFile, changeNoteInput.value) { fraction ->
                    progressBar.setStyle("width", "${(fraction * 100).toInt()}%")
                }
            uploadButton.disabled = false
            progressWrapper.hide()
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

/**
 * V1.6.1 "Wissensbasis" column of one document row -- a release switch plus a status mark, for
 * BOARD/ADMIN on an installation with the AI layer on (the caller only passes [initial] then). No
 * separate screen, no navigation entry: the decision belongs next to the document it concerns.
 *
 * The switch is enabled only for [AiKnowledgeEntryDto.releasable] documents (`PUBLIC_MEMBERS`); the
 * server refuses every other level anyway (`AiDocumentNotReleasableException`) -- this is the UX
 * echo of that rule. Marks: indexiert / Indexierung läuft / Format nicht lesbar / Indexierung
 * fehlgeschlagen / nicht freigegeben (see [StatuteQaUi.statusMark]). A stale or failed index offers
 * "Neu indexieren".
 */
private fun renderKnowledgeControls(
    row: Container,
    documentId: String,
    state: MutableMap<String, AiKnowledgeEntryDto>,
) {
    // The current entry lives in [state], outside the (re-run) cell lambda: it survives a sort or a mode switch.
    fun current(): AiKnowledgeEntryDto = state.getValue(documentId)
    val box = row.checkBox(value = current().released, label = tr("Wissensbasis"))
    box.disabled = !current().releasable
    val mark = row.div(tr(StatuteQaUi.statusMark(current().status))) { addCssClasses("text-muted small") }
    val reindex = row.button(tr("Neu indexieren"), icon = "fas fa-rotate", style = ButtonStyle.OUTLINESECONDARY)

    fun refresh() {
        box.value = current().released
        mark.content = tr(StatuteQaUi.statusMark(current().status))
        reindex.visible = current().released && (current().status == AiIndexStatus.PENDING || current().status == AiIndexStatus.FAILED)
    }
    refresh()

    box.subscribe { checked ->
        if (checked == current().released) return@subscribe
        AppScope.launch {
            val updated = guarded { rpcService<IAiAssistantService>().setKnowledgeBaseRelease(documentId, checked) }
            if (updated != null) {
                state[documentId] = updated
                notifySuccess(tr("Wissensbasis aktualisiert."))
            }
            refresh()
        }
    }
    reindex.onClick {
        reindex.disabled = true
        AppScope.launch {
            val updated = guarded { rpcService<IAiAssistantService>().reindexKnowledgeDocument(documentId) }
            reindex.disabled = false
            if (updated != null) {
                state[documentId] = updated
                notifySuccess(tr("Wissensbasis aktualisiert."))
            }
            refresh()
        }
    }
}
