package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
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
import network.lapis.cloud.shared.domain.AiIndexStatus
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
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

    // V1.9.49 (R36B): each list has a title row with its own create button; the forms sit directly under the title row.
    val folderSlot = root.sectionTitleRow(tr("Ordner"))
    val folderHost = root.vPanel(spacing = 6)
    val folderPanel = root.vPanel(spacing = 4)

    val documentSlot = root.sectionTitleRow(tr("Dokumente"))
    // The button of the document form gets a slot of its own: it appears only once a folder is open and its documents are loaded.
    val documentButtonSlot = documentSlot.div().apply { hide() }
    val documentHost = root.vPanel(spacing = 6)
    val documentPanel = root.vPanel(spacing = 6)
    var documentForm: CollapsibleCreateFormController<Unit>? = null
    var openFolder: DocumentFolderDto? = null
    var loadGeneration = 0

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

    fun loadDocuments(folder: DocumentFolderDto) {
        val folderId = folder.id
        // The create button of the document form hides while the list loads and returns once THIS load succeeded; a late answer of an
        // earlier folder (quick A -> B) finds a newer generation and never shows the button for the wrong folder.
        val generation = ++loadGeneration
        openFolder = folder
        documentButtonSlot.hide()
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

        AppScope.launch {
            val documents = guarded { rpcService<IDocumentService>().listDocuments(folderId) }
            if (documents == null) {
                // Welle V1.4.27 (W3): a failed load is an error state with a retry, not an empty panel.
                listPanel.dataErrorState(onRetry = { loadDocuments(folder) })
                return@launch
            }
            if (canManage && generation == loadGeneration) documentButtonSlot.show()

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
                                { actions, document ->
                                    actions.renderDocumentAccessLevelAction(document, folder.accessLevel) { loadDocuments(folder) }
                                    actions.renderDocumentDeleteAction(document) { loadDocuments(folder) }
                                }
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
        }
    }

    // A folder switch with a changed, still open document form asks first ("Weiter bearbeiten" stays in the folder).
    fun switchFolder(folder: DocumentFolderDto) {
        val form = documentForm
        if (form != null) form.requestClose { loadDocuments(folder) } else loadDocuments(folder)
    }

    /**
     * Welle V1.9.1: the folder list migrated from a plain button row to a `dataTable` (Name +
     * "Sichtbarkeit"-Abzeichen + Dokumentzahl) -- a row with its own property (the access level)
     * AND its own action ("Sichtbarkeit ändern") is a table row, not a navigation button (see
     * `docs/architecture/ui-ux-guideline.adoc`'s `dataTable` rule). This also gives folders the
     * SAME mobile card-list fallback below 768px that `dataTable` already provides everywhere else
     * -- the button row had no separate mobile layout of its own. Same column order (Name,
     * Sichtbarkeit, rest) as [documentColumns] -- a user should not have to learn twice where
     * visibility is shown.
     */
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
                folderPanel.dataTable(
                    columns = folderColumns { folder -> switchFolder(folder) },
                    rows = folders,
                    actions =
                        if (canManage) {
                            { actions, folder -> actions.renderFolderAccessLevelAction(folder) { refreshFolders() } }
                        } else {
                            null
                        },
                )
            }
        }
    }

    refreshFolders()
    if (canManage) {
        collapsibleCreateForm<Unit>(
            actionSlot = folderSlot,
            formHost = folderHost,
            buttonLabel = tr("Neuer Ordner"),
            formId = "documents-folder-create",
        ) { _, close -> renderFolderCreation(AppState.session?.role, close) { refreshFolders() } }
        // One controller for the whole screen life: the form is built per opening (for the folder open at that moment).
        documentForm =
            collapsibleCreateForm<Unit>(
                actionSlot = documentButtonSlot,
                formHost = documentHost,
                buttonLabel = tr("Neues Dokument"),
                formId = "documents-document-create",
            ) { _, close ->
                val folder = checkNotNull(openFolder) { "the document form opens only with a folder" }
                renderDocumentCreation(folder, AppState.session?.role, close) { loadDocuments(folder) }
            }
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
 * click handler, `dataNavigo = false`, see `LoginScreen.kt`) and the card title. The "Sichtbarkeit" column
 * (Welle V1.9.1) sits between title and "Wissensbasis" -- SAME position [folderColumns] gives it, one place a
 * user learns to look for visibility. The "Wissensbasis" column exists only where the AI layer is on and the
 * caller may decide (BOARD/ADMIN, see [DocumentsAuthzUi]).
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
        DataColumn(
            title = tr("Sichtbarkeit"),
            cell = { container, document -> container.documentAccessLevelBadge(document.accessLevel) },
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

/**
 * Welle V1.9.1: columns of the folder list, migrated from a plain button row to a `dataTable` (see
 * [refreshFolders]'s own KDoc for why). The folder name is a link that opens it (same "purely local click
 * handler" idiom [documentColumns]' title link uses) -- `untrustedLink`, not a plain `tr(...)`/`textColumn`,
 * because a folder NAME is foreign data (created by any BOARD/TREASURER/ADMIN member), same discipline every
 * other user-supplied label in this screen already follows.
 */
private fun folderColumns(onOpen: (DocumentFolderDto) -> Unit): List<DataColumn<DocumentFolderDto>> =
    listOf(
        DataColumn(
            title = tr("Ordner"),
            primary = true,
            cell = { container, folder ->
                container.icon("fas fa-folder")
                container.untrustedLink(folder.name, url = "javascript:void(0)", dataNavigo = false).onClick { onOpen(folder) }
            },
        ),
        DataColumn(
            // Fix round (B5): this badge renders the folder's OWN level, the only one on the wire.
            // That is truthful because a tighten now materializes into every descendant folder's own
            // level -- before that, a sub-folder under a tightened parent kept `PUBLIC_MEMBERS` and
            // this badge said "Alle Mitglieder" about a folder that was effectively ADMIN_ONLY, in
            // the very feature whose purpose is making the level visible. See
            // `DocumentFolderDto.accessLevel` KDoc.
            title = tr("Sichtbarkeit"),
            cell = { container, folder -> container.documentAccessLevelBadge(folder.accessLevel) },
        ),
        textColumn(title = tr("Dokumente"), numeric = true) { folder: DocumentFolderDto -> folder.documentCount.toString() },
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

// R24 (W4d): migrated to the form grammar -- the raw Upload control registered via `register` (pattern
// `BankStatementImportScreen.kt`'s "Datei auswählen"), Änderungshinweis as an optional textField. The
// progress bar (Nutzer-Beschwerde 2026-09-15, "kein Signal während des Uploads, man klickt wild") is
// unchanged: Bootstrap's own `.progress`/`.progress-bar` classes, only visible during a running upload.
private fun renderVersionUpload(
    panel: SimplePanel,
    documentId: String,
    onUploaded: () -> Unit,
) {
    val uploadRow = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val form = uploadRow.lapisForm()
    val fileUpload = form.panel.upload(label = tr("Neue Version hochladen"))

    fun selectedNativeFile() = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }

    val fileField =
        form.register(
            fileUpload,
            label = tr("Neue Version hochladen"),
            required = true,
            requiredMessage = tr("Bitte eine Datei auswählen."),
        )
    val changeNoteField = form.textField(label = tr("Änderungshinweis (optional)"))

    val progressWrapper = form.panel.div(className = "progress mt-1") { hide() }
    val progressBar = progressWrapper.div(className = "progress-bar progress-bar-striped progress-bar-animated")
    progressBar.setAttribute("role", "progressbar")
    progressBar.setStyle("width", "0%")

    val uploadButton = newActionButton(ActionIcon.UPLOAD, tr("Hochladen"), ButtonStyle.PRIMARY)
    form.buttons(primary = uploadButton)
    uploadButton.onClick {
        form.submit(uploadButton) {
            val nativeFile = selectedNativeFile() ?: return@submit
            progressBar.setStyle("width", "0%")
            progressWrapper.show()
            val error =
                DocumentHttp.uploadVersion(documentId, nativeFile, changeNoteField.value) { fraction ->
                    progressBar.setStyle("width", "${(fraction * 100).toInt()}%")
                }
            progressWrapper.hide()
            if (error != null) {
                form.showFormError(error)
            } else {
                notifySuccess(tr("Version hochgeladen."))
                fileField.reset()
                changeNoteField.reset()
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
        // R29 (W4d): an immediate switch (R24B_JUSTIFIED below), no surrounding form and no button of its
        // own to hand `runGuardedAction` -- same `runGuardedAction(null)` idiom `PoliticianScreen.kt`'s
        // `castRating`/`retractRating` already use, `box` disabled manually around the call. `refresh()`
        // re-enables it (or leaves it disabled, per `current().releasable`).
        box.disabled = true
        runGuardedAction(null) {
            val updated = guarded { rpcService<IAiAssistantService>().setKnowledgeBaseRelease(documentId, checked) }
            if (updated != null) {
                state[documentId] = updated
                notifySuccess(tr("Wissensbasis aktualisiert."))
            }
            refresh()
        }
    }
    reindex.onClick {
        runGuardedAction(reindex) {
            val updated = guarded { rpcService<IAiAssistantService>().reindexKnowledgeDocument(documentId) }
            if (updated != null) {
                state[documentId] = updated
                notifySuccess(tr("Wissensbasis aktualisiert."))
            }
            refresh()
        }
    }
}
