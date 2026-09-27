package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.form.upload.upload
import io.kvision.html.Button
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
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AiIndexStatus
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.domain.restrictiveness
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

    fun loadDocuments(folder: DocumentFolderDto) {
        val folderId = folder.id
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
                listPanel.dataErrorState(onRetry = { loadDocuments(folder) })
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

            if (canManage) {
                renderDocumentCreation(creationPanel, folder, AppState.session?.role) { loadDocuments(folder) }
            }
        }
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
                    columns = folderColumns { folder -> loadDocuments(folder) },
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
    if (canManage && folderCreationPanel != null) {
        renderFolderCreation(folderCreationPanel, AppState.session?.role) { refreshFolders() }
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

/**
 * Welle V1.9.1: "Sichtbarkeit ändern" action of a document row. The modal IS the confirmation --
 * no second `confirmDialog` on top (unlike [renderDocumentDeleteAction]'s soft-delete, changing a
 * level is reversible and the modal already requires an explicit "Speichern" click). Options are
 * [DocumentsAuthzUi.allowedLevels] filtered to the folder's own level and up, same UX-nicety
 * reasoning as [renderDocumentCreation] -- the server (`ConflictException`) remains the authority.
 */
internal fun Container.renderDocumentAccessLevelAction(
    document: DocumentDto,
    folderAccessLevel: DocumentAccessLevel,
    onChanged: () -> Unit,
) {
    val changeButton = tableActionButton("fas fa-user-lock", tr("Sichtbarkeit ändern"))
    changeButton.onClick {
        val modal = Modal(caption = tr("Sichtbarkeit des Dokuments ändern"))
        val form = modal.lapisForm()
        val options =
            DocumentsAuthzUi
                .allowedLevels(AppState.session?.role)
                .filter { it.restrictiveness >= folderAccessLevel.restrictiveness }
                .map { it.name to documentAccessLevelLabel(it) }
        val levelField =
            form.selectField(label = tr("Sichtbarkeit"), options = options, value = document.accessLevel.name, required = true)
        form.finish()
        modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
        saveButton.onClick {
            form.submit(saveButton) {
                val newLevel = DocumentAccessLevel.valueOf(levelField.value)
                // The returned DocumentDto is deliberately unused -- `onChanged()` reloads the whole
                // list, so binding it would only invite someone to render a second, stale source of
                // truth next to the reloaded one (fix round, W8: it used to be an unused `val`).
                guarded { rpcService<IDocumentService>().setDocumentAccessLevel(document.id, newLevel) } ?: return@submit
                modal.hide()
                notifySuccess(tr("Sichtbarkeit geändert."))
                onChanged()
            }
        }
        modal.addButton(saveButton)
        modal.show()
    }
}

/**
 * Welle V1.9.1: "Sichtbarkeit ändern" action of a folder row. Unlike the document-level dialog
 * above, this one ALWAYS shows both cascade sentences (never conditionally) -- an admin choosing a
 * folder's level cannot yet know whether the choice tightens or loosens, and "an einschränkende
 * Wahl schränkt mit ein, eine erweiternde erweitert nichts" is the one fact that must land BEFORE
 * the click, not just in the success toast afterward. Since the fix round (B5) the tighten reaches
 * descendant FOLDERS as well as documents, which is why both sentences name both.
 *
 * The options are NOT filtered against a parent folder's level here (unlike the document dialog's
 * `>=` predicate): this screen only creates top-level folders, so the only way a sub-folder exists
 * at all is a nesting some other tool created, and in that case the server's `ConflictException`
 * ("A subfolder cannot be more visible than its parent folder") is the authority. `guarded` surfaces
 * it to the user as an error toast.
 */
internal fun Container.renderFolderAccessLevelAction(
    folder: DocumentFolderDto,
    onChanged: () -> Unit,
) {
    val changeButton = tableActionButton("fas fa-user-lock", tr("Sichtbarkeit ändern"))
    changeButton.onClick {
        val modal = Modal(caption = tr("Sichtbarkeit des Ordners ändern"))
        modal.div(tr("Eine Einschränkung des Ordners schränkt alle enthaltenen Unterordner und Dokumente mit ein."))
        modal.div(tr("Eine Erweiterung des Ordners erweitert die enthaltenen Unterordner und Dokumente nicht."))
        val form = modal.lapisForm()
        val options = DocumentsAuthzUi.allowedLevels(AppState.session?.role).map { it.name to documentAccessLevelLabel(it) }
        val levelField =
            form.selectField(label = tr("Sichtbarkeit"), options = options, value = folder.accessLevel.name, required = true)
        form.finish()
        modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
        saveButton.onClick {
            form.submit(saveButton) {
                val newLevel = DocumentAccessLevel.valueOf(levelField.value)
                val result = guarded { rpcService<IDocumentService>().setFolderAccessLevel(folder.id, newLevel) } ?: return@submit
                modal.hide()
                if (result.tightenedFolders > 0) {
                    notifySuccess(
                        gettext(
                            "Sichtbarkeit geändert -- %1 Unterordner und %2 Dokumente wurden mit eingeschränkt.",
                            result.tightenedFolders,
                            result.tightenedDocuments,
                        ),
                    )
                } else if (result.tightenedDocuments > 0) {
                    notifySuccess(gettext("Sichtbarkeit geändert -- %1 Dokumente wurden mit eingeschränkt.", result.tightenedDocuments))
                } else {
                    notifySuccess(tr("Sichtbarkeit geändert."))
                }
                onChanged()
            }
        }
        modal.addButton(saveButton)
        modal.show()
    }
}

/**
 * R24 (W4d): migrated to the form grammar. Welle V1.9.1, fix round (W1): a "Sichtbarkeit" select was
 * added, mirroring [renderDocumentCreation]'s own. Without it every new folder was PUBLIC_MEMBERS by
 * force -- and a folder NAME is frequently the sensitive part ("Kündigungen Q3"), visible to every
 * member from the moment of creation until someone remembered to change the level in a second step.
 * Options come from [DocumentsAuthzUi.allowedLevels], the same single source of truth the document
 * dialog and both "Sichtbarkeit ändern" modals use; the server's `canAccessDocumentAtLevel` check in
 * `createFolder` remains the real authority.
 *
 * No parent-folder-derived filtering here (unlike [renderDocumentCreation]'s `>=` predicate): this
 * screen only ever creates TOP-LEVEL folders (`parentFolderId = null`), so there is no parent level
 * to be at least as restrictive as.
 */
private fun renderFolderCreation(
    panel: SimplePanel,
    role: AccountRole?,
    onCreated: () -> Unit,
) {
    val form = panel.lapisForm()
    val nameField = form.textField(label = tr("Neuer Ordnername"), required = true)
    val accessLevelOptions = DocumentsAuthzUi.allowedLevels(role).map { it.name to documentAccessLevelLabel(it) }
    val accessField =
        form.selectField(
            label = tr("Sichtbarkeit"),
            options = accessLevelOptions,
            value = DocumentAccessLevel.PUBLIC_MEMBERS.name,
            required = true,
        )
    val createButton = Button(tr("Ordner anlegen"), icon = "fas fa-folder-plus", style = ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val accessLevel = DocumentAccessLevel.valueOf(accessField.value)
            val result = guarded { rpcService<IDocumentService>().createFolder(name, null, accessLevel) }
            if (result != null) {
                notifySuccess(gettext("Ordner \"%1\" angelegt.", name))
                nameField.reset()
                onCreated()
            }
        }
    }
}

// R24/R24B (W4d): migrated to the form grammar -- Titel (required text) + Sichtbarkeit (required select).
private fun renderDocumentCreation(
    panel: SimplePanel,
    folder: DocumentFolderDto,
    role: AccountRole?,
    onCreated: () -> Unit,
) {
    // Review finding fix (Welle "Treasurer Document Upload", Runde 4): only offer access levels
    // the current role is actually allowed to create at -- see [DocumentsAuthzUi.allowedLevels]
    // KDoc for the orphaned-document failure mode this prevents. Welle V1.9.1: additionally
    // restricted to levels at least as restrictive as the OPEN folder's own level -- a UX nicety on
    // top of `createDocument`'s real `ConflictException` authority.
    //
    // Fix round (B5): this filter is only CORRECT because a folder tighten now materializes into
    // every descendant folder's own level, so "own level == effective level" actually holds. The
    // earlier comment here argued the opposite way round and was logically inverted: the effective
    // level can only be EQUAL OR STRICTER than the own level, so filtering by the OWN level offers a
    // superset of what the server accepts, not a subset. Before the materialization that superset was
    // reachable in practice (tighten a parent, open a sub-folder that kept PUBLIC_MEMBERS) and every
    // such creation answered `ConflictException`. It is now only reachable for a sub-folder some
    // other tool created and never materialized -- and the server still rejects it, which is the
    // posture that is actually load-bearing.
    val accessLevelOptions =
        DocumentsAuthzUi
            .allowedLevels(role)
            .filter { it.restrictiveness >= folder.accessLevel.restrictiveness }
            // Welle V1.9.1: fixes a labeling bug -- this dropdown used to show the raw enum
            // constant ("PUBLIC_MEMBERS") instead of a translated label (see
            // ClientUntrustedWidgetTextTripwireTest's KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS ledger).
            .map { it.name to documentAccessLevelLabel(it) }
    val form = panel.lapisForm()
    val titleField = form.textField(label = tr("Neuer Dokumenttitel"), required = true)
    val accessField =
        form.selectField(
            // Defaults to the folder's own level -- itself always the most permissive option offered
            // above (PUBLIC_MEMBERS, restrictiveness 0, is never filtered out by the `>=` predicate).
            label = tr("Sichtbarkeit"),
            options = accessLevelOptions,
            value = folder.accessLevel.name,
            required = true,
        )
    val createButton =
        Button(
            tr("Dokument anlegen (danach Datei hochladen)"),
            icon = "fas fa-file-circle-plus",
            style = ButtonStyle.OUTLINEPRIMARY,
        )
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val title = titleField.value.trim()
            val accessLevel = DocumentAccessLevel.valueOf(accessField.value)
            val result = guarded { rpcService<IDocumentService>().createDocument(folder.id, title, accessLevel) }
            if (result != null) {
                notifySuccess(gettext("Dokument \"%1\" angelegt -- jetzt eine Datei hochladen.", title))
                onCreated()
            }
        }
    }
}

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

    val uploadButton = Button(tr("Hochladen"), icon = "fas fa-upload", style = ButtonStyle.PRIMARY)
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
