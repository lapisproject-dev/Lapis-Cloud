package network.lapis.cloud.client

import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.PublicTextRules
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOfficerDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.RegionalChapterPublicRules
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegionalChapterService
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- ADMIN-only chapter
 * management screen (`Routes.REGIONAL_CHAPTERS`), the client half of the `IRegionalChapterService`
 * surface Welle V1.9.13 built server-side. See `docs/architecture/regional-chapters.adoc` for the
 * full design (data model, role architecture decision, visibility boundary).
 *
 * Also hosts two small helpers used across this wave's other screens, deliberately placed HERE
 * rather than on `AppState`/`MemberAdministrationScreen`: this file already has `dataSection`
 * (zero client-data-state reads counted, see `ClientDataStateTripwireTest`'s ratchet, plan §2.4/§1
 * P4), so an extra `rpcService<...>()` call site here costs nothing against the 150/150-exhausted
 * budget that a call site in a `dataSection`-free file (`RegistrationScreen.kt`) would.
 */
fun renderRegionalChaptersScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.pageHeader(tr("Gliederungsverwaltung"))

    lateinit var section: DataSection
    section =
        root.dataSection<RegionalChapterOverviewDto>(
            // Review fix (MINOR edge case): ALWAYS `false` -- with the previous
            // `it.chapters.isEmpty() && it.unassignedCount == 0` predicate, an instance with no
            // chapters where no member happens to be ACTIVE/APPLICATION (only DONOR/WITHDRAWN
            // members, or simply none yet) hit the `DataViewState.Empty` branch, which shows ONLY
            // `emptyText` and never calls `render` at all -- `renderChapterCreationForm` (the only
            // way to create the FIRST chapter) was unreachable. This screen has no "empty" state:
            // the creation form is part of its NORMAL content, not something an empty state should
            // ever hide. The equivalent notice now lives inside [renderRegionalChaptersBody] itself,
            // shown alongside the form instead of in place of it.
            isEmpty = { false },
            load = { regionalChapterGuarded { rpcService<IRegionalChapterService>().listChapters() } },
            render = { panel, overview ->
                renderRegionalChaptersBody(panel, overview) { section.reload() }
            },
        )
    section.reload()
}

private fun renderRegionalChaptersBody(
    root: SimplePanel,
    overview: RegionalChapterOverviewDto,
    onChanged: () -> Unit,
) {
    if (overview.chapters.isEmpty() && overview.unassignedCount == 0) {
        root.p(
            tr(
                "Noch keine Landesverbände angelegt. Solange keiner existiert, ändert sich für " +
                    "Registrierung und Mitgliederverwaltung nichts.",
            ),
        ) { addCssClasses("text-muted") }
    }
    if (overview.unassignedCount > 0) {
        val n = overview.unassignedCount
        root.p(ngettext("%1 Mitglied ohne Landesverband", "%1 Mitglieder ohne Landesverband", n, n)) {
            addCssClass("fw-bold")
        }
        root.p(tr("In der Mitgliederverwaltung nach „Nicht zugeordnet“ filtern.")) { addCssClass("text-muted") }
    }

    // BOARD reaches this screen for the crest and the public description only (server: create/rename/delete/officers ADMIN-only).
    val structure = NavVisibility.showsRegionalChapterStructure(AppState.session?.role)
    if (structure) {
        root.p(tr("Landesverband anlegen")) { addCssClasses("fw-bold mt-2") }
        renderChapterCreationForm(root, overview, onChanged)
    }

    val cardsPanel = root.vPanel(spacing = 8) { addCssClass("mt-3") }
    overview.chapters.forEach { chapter -> renderChapterCard(cardsPanel, chapter, structure, onChanged) }
}

private fun renderChapterCreationForm(
    root: SimplePanel,
    overview: RegionalChapterOverviewDto,
    onChanged: () -> Unit,
) {
    val atLimit = overview.chapters.size >= RegionalChapterRules.MAX_CHAPTERS
    val form = root.lapisForm()
    val nameField =
        form.textField(
            label = tr("Name des Landesverbands"),
            required = true,
            rule = { chapterNameCheck(it) },
        )
    if (atLimit) {
        form.panel.p(gettext("Höchstens %1 Landesverbände möglich.", RegionalChapterRules.MAX_CHAPTERS)) {
            addCssClasses("text-muted small")
        }
    }
    val createButton = Button(tr("Landesverband anlegen"), style = ButtonStyle.PRIMARY)
    createButton.disabled = atLimit
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val name = RegionalChapterRules.normalizeName(nameField.value)
            val result =
                regionalChapterGuarded(onNameTaken = { nameField.showError(tr("Ein Landesverband mit diesem Namen existiert bereits.")) }) {
                    rpcService<IRegionalChapterService>().createChapter(name)
                }
            if (result != null) {
                notifySuccess(gettext("Landesverband \"%1\" wurde angelegt.", result.name))
                nameField.reset()
                onChanged()
                refreshSessionFromServer()
            }
        }
    }
}

private fun renderChapterCard(
    panel: SimplePanel,
    chapter: RegionalChapterDto,
    structure: Boolean,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    card.untrustedCardTitle(chapter.name)
    card.p(chapterCountsLine(chapter)) { addCssClasses("text-muted small mb-1") }

    if (structure) renderChapterStructureControls(card, chapter, onChanged)
    // ── Öffentliche Darstellung (Wappen + Beschreibung), Welle V1.9.20 ──
    renderChapterPublicSection(card, chapter, onChanged)
}

/**
 * ADMIN-only structural controls of a chapter card (rename, officers, delete) -- split out so a BOARD session, which reaches
 * this screen for the crest and the public description only, never builds them.
 */
private fun renderChapterStructureControls(
    card: SimplePanel,
    chapter: RegionalChapterDto,
    onChanged: () -> Unit,
) {
    val actionsRow = card.hPanel(spacing = 8) { addCssClass("flex-wrap") }

    // ── Umbenennen ──
    val renameButton = actionsRow.actionButton(ActionIcon.EDIT, tr("Umbenennen"), style = ButtonStyle.OUTLINEPRIMARY)
    val renamePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    renamePanel.hide()
    var renameOpen = false
    renameButton.onClick {
        renameOpen = !renameOpen
        if (renameOpen) {
            renamePanel.removeAll()
            renderChapterRenameForm(renamePanel, chapter) {
                renamePanel.hide()
                onChanged()
            }
            renamePanel.show()
        } else {
            renamePanel.hide()
        }
    }

    // ── Landesvorstand verwalten ──
    val officerButton = actionsRow.button(tr("Landesvorstand verwalten"), style = ButtonStyle.OUTLINESECONDARY)
    val officerPanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    officerPanel.hide()
    var officerOpen = false
    var officerLoaded = false
    officerButton.onClick {
        officerOpen = !officerOpen
        if (officerOpen) {
            if (!officerLoaded) {
                officerLoaded = true
                renderOfficerPanel(officerPanel, chapter, onChanged)
            }
            officerPanel.show()
        } else {
            officerPanel.hide()
        }
    }

    // ── Löschen ──
    val blockReason = chapterDeleteBlockReason(chapter)
    val deleteButton = actionsRow.actionButton(ActionIcon.DELETE, tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
    deleteButton.disabled = blockReason != null
    if (blockReason != null) {
        card.p(blockReason) { addCssClasses("text-muted small mb-0") }
    }

    deleteButton.onClick {
        confirmDialog(
            title = gettext("Landesverband %1 löschen", chapter.name),
            message = gettext("Landesverband \"%1\" wirklich löschen? Das kann nicht rückgängig gemacht werden.", chapter.name),
            confirmLabel = tr("Löschen"),
            confirmIcon = ActionIcon.DELETE,
        ) {
            AppScope.launch {
                // deleteChapter returns Unit -- Unit is never itself null, so a `null` result from
                // regionalChapterGuarded<Unit> means exactly "failed" (guarded already showed a toast),
                // same convention every other guarded call in this codebase relies on.
                val result = regionalChapterGuarded { rpcService<IRegionalChapterService>().deleteChapter(chapter.id) }
                if (result != null) {
                    notifyInfo(gettext("Landesverband \"%1\" wurde gelöscht.", chapter.name))
                    onChanged()
                    refreshSessionFromServer()
                }
            }
        }
    }
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the public face of a chapter: the crest tile (fixed 96 px, so a
 * missing crest never shifts the layout), upload/remove, and the public description with a live
 * code-point counter. The file goes to `POST /api/regional-chapters/{id}/crest` ([ChapterCrestHttp]);
 * description and removal are RPCs. Every failure is a fixed, translated sentence chosen by an enum code.
 */
private fun renderChapterPublicSection(
    card: SimplePanel,
    chapter: RegionalChapterDto,
    onChanged: () -> Unit,
) {
    val section = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    section.p(tr("Öffentliche Darstellung")) { addCssClasses("fw-bold small mb-0") }

    val tile = section.div { addCssClass("lapis-crest-tile") }
    val crestUrl = chapter.crestUrl
    if (chapter.hasCrest && crestUrl != null) {
        tile.image(crestUrl, sanitizeUntrustedI18nText(gettext("Wappen %1", chapter.name)))
    } else {
        tile.icon("fas fa-shield-halved").setAttribute("aria-hidden", "true")
    }
    section.p(tr("JPEG oder PNG (mindestens 64 × 64 Pixel, höchstens 2 MB) oder SVG (höchstens 256 KB).")) {
        addCssClasses("text-muted small mb-0")
    }
    val errorBox = section.div("") { addCssClasses("alert alert-danger small mb-0") }
    errorBox.setAttribute("role", "alert")
    errorBox.hide()

    val uploadForm = section.lapisForm()
    val fileUpload = uploadForm.panel.upload(label = tr("Wappen auswählen"), accept = CREST_ACCEPT)
    val fileField =
        uploadForm.register(
            fileUpload,
            label = tr("Wappen auswählen"),
            required = true,
            requiredMessage = tr("Bitte eine Datei auswählen."),
        )
    val uploadButton = Button(if (chapter.hasCrest) tr("Wappen ersetzen") else tr("Wappen hochladen"), style = ButtonStyle.OUTLINEPRIMARY)
    uploadForm.buttons(primary = uploadButton)
    uploadButton.onClick {
        uploadForm.submit(uploadButton) {
            val nativeFile = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) } ?: return@submit
            errorBox.hide()
            val kind = crestFileKindOf(type = nativeFile.type, name = nativeFile.name)
            val failure =
                crestPrecheck(type = nativeFile.type, name = nativeFile.name, size = nativeFile.size.toDouble())
                    ?: when (val result = ChapterCrestHttp.upload(chapterId = chapter.id, file = nativeFile)) {
                        ChapterCrestHttp.Result.Ok -> null
                        is ChapterCrestHttp.Result.Error -> result.code
                    }
            fileField.reset()
            if (failure != null) {
                errorBox.content = chapterCrestUploadErrorMessage(code = failure, kind = kind)
                errorBox.show()
            } else {
                notifySuccess(tr("Wappen gespeichert."))
                onChanged()
            }
        }
    }

    if (chapter.hasCrest) {
        val removeButton = section.actionButton(ActionIcon.REMOVE, tr("Wappen entfernen"), style = ButtonStyle.OUTLINEDANGER)
        removeButton.onClick {
            confirmDialog(
                title = tr("Wappen entfernen"),
                message = gettext("Das Wappen von \"%1\" wird endgültig gelöscht.", chapter.name),
                confirmLabel = tr("Entfernen"),
                confirmIcon = ActionIcon.REMOVE,
            ) {
                runGuardedAction(button = null) {
                    regionalChapterGuarded { rpcService<IRegionalChapterService>().removeChapterCrest(chapter.id) }
                        ?: return@runGuardedAction
                    notifySuccess(tr("Wappen entfernt."))
                    onChanged()
                }
            }
        }
    }

    val descriptionForm = section.lapisForm()
    val descriptionField =
        descriptionForm.textAreaField(
            label = tr("Öffentliche Beschreibung"),
            rows = 3,
            value = chapter.description,
            rule = { raw -> chapterDescriptionCheck(raw) },
        )
    val counter = descriptionForm.panel.div("") { addCssClasses("small text-muted") }

    fun updateCounter() {
        val count = PublicTextRules.codePointCount(descriptionField.value.trim())
        counter.content = gettext("%1 / %2", count, RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS)
        if (count > RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS) {
            counter.addCssClass("text-danger")
        } else {
            counter.removeCssClass("text-danger")
        }
    }
    updateCounter()
    descriptionField.subscribe { updateCounter() }
    val saveDescriptionButton = Button(tr("Beschreibung speichern"), style = ButtonStyle.PRIMARY)
    descriptionForm.buttons(primary = saveDescriptionButton)
    saveDescriptionButton.onClick {
        descriptionForm.submit(saveDescriptionButton) {
            val result =
                regionalChapterGuarded {
                    rpcService<IRegionalChapterService>().updateChapterDescription(chapter.id, descriptionField.value)
                }
            if (result != null) {
                notifySuccess(tr("Beschreibung gespeichert."))
                onChanged()
            }
        }
    }
}

private fun renderChapterRenameForm(
    panel: SimplePanel,
    chapter: RegionalChapterDto,
    onSaved: () -> Unit,
) {
    val form = panel.lapisForm()
    val nameField =
        form.textField(
            label = tr("Name des Landesverbands"),
            value = chapter.name,
            required = true,
            rule = { chapterNameCheck(it) },
        )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val name = RegionalChapterRules.normalizeName(nameField.value)
            val result =
                regionalChapterGuarded(onNameTaken = { nameField.showError(tr("Ein Landesverband mit diesem Namen existiert bereits.")) }) {
                    rpcService<IRegionalChapterService>().renameChapter(chapter.id, name)
                }
            if (result != null) {
                notifySuccess(gettext("Landesverband wurde in \"%1\" umbenannt.", result.name))
                onSaved()
            }
        }
    }
}

private fun renderOfficerPanel(
    host: SimplePanel,
    chapter: RegionalChapterDto,
    onCardChanged: () -> Unit,
) {
    host.removeAll()
    lateinit var listSection: DataSection

    fun reloadAll() {
        listSection.reload()
        onCardChanged()
    }

    listSection =
        host.dataSection<List<RegionalChapterOfficerDto>>(
            emptyText = tr("Noch kein Landesvorstand eingetragen."),
            isEmpty = { it.isEmpty() },
            load = { regionalChapterGuarded { rpcService<IRegionalChapterService>().listOfficers(chapter.id) } },
            render = { panel, officers ->
                officers.forEach { officer -> renderOfficerRow(panel, officer, ::reloadAll) }
            },
        )
    listSection.reload()

    host.div { addCssClass("mt-2") }
    host.p(tr("Mitglied dieses Landesverbands suchen")) { addCssClasses("fw-bold small mb-1") }
    renderOfficerGrantSearch(host, chapter, ::reloadAll)
}

private fun renderOfficerRow(
    panel: SimplePanel,
    officer: RegionalChapterOfficerDto,
    onChanged: () -> Unit,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center border-bottom py-1 flex-wrap") }
    row.untrustedSpan(officer.displayName)
    row.span(gettext("seit %1", formatDate(systemDate(officer.grantedAt)))) { addCssClasses("text-muted small") }
    officer.grantedByDisplayName?.let { grantedBy ->
        row.untrustedSpan(gettext("erteilt von %1", grantedBy), className = "text-muted small")
    }

    val revokeButton = row.button(tr("Zugang entziehen"), style = ButtonStyle.OUTLINEDANGER)
    val confirmBox = panel.div { addCssClasses("alert alert-warning d-flex align-items-center gap-2") }
    confirmBox.hide()
    val gate = InlineConfirmGate()
    revokeButton.onClick {
        if (!gate.openConfirmation()) return@onClick
        confirmBox.removeAll()
        confirmBox.span(tr("Zugang wirklich entziehen?"))
        val confirmButton = confirmBox.button(tr("Jetzt entziehen"), style = ButtonStyle.DANGER)
        val backButton = confirmBox.actionButton(ActionIcon.BACK, tr("Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        backButton.onClick {
            if (gate.cancelConfirmation()) confirmBox.hide()
        }
        confirmButton.onClick {
            if (!gate.beginRequest()) return@onClick
            AppScope.launch {
                val result = regionalChapterGuarded { rpcService<IRegionalChapterService>().revokeOfficer(officer.grantId) }
                gate.endRequest(result != null)
                if (result != null) {
                    confirmBox.hide()
                    onChanged()
                }
            }
        }
        confirmBox.show()
    }
}

private fun renderOfficerGrantSearch(
    host: SimplePanel,
    chapter: RegionalChapterDto,
    onGranted: () -> Unit,
) {
    val searchField = host.lapisForm().textField(label = tr("Suche"))
    val resultsPanel = host.vPanel(spacing = 4) { addCssClass("mt-1") }
    var officerMemberIds: Set<String> = emptySet()

    // Review fix (MINOR race): a `generation` counter, same shape as `DataLoadController.reload`'s
    // own -- two searches started close together (a debounced keystroke followed by a paused-then-
    // resumed one) each ran two SEQUENTIAL RPCs with no staleness check at all, so an older search's
    // late-arriving rows could land in the panel AFTER a newer search had already cleared and
    // repopulated it: a mixed, duplicated list that no longer matches what is typed. `resultsPanel
    // .removeAll()` now happens ONLY once the search that is still current gets its answer, gated by
    // comparing `mine` against the field's current value at that point.
    var generation = 0

    fun runSearch(term: String) {
        generation++
        val mine = generation
        AppScope.launch {
            val officers = regionalChapterGuarded { rpcService<IRegionalChapterService>().listOfficers(chapter.id) }
            if (mine != generation) return@launch // a newer search has taken over
            officerMemberIds = officers?.map { it.memberId }?.toSet() ?: emptySet()
            val page =
                guarded { rpcService<IMemberService>().listMembersForAdministration(officerCandidateQuery(term, chapter.id)) }
                    ?: return@launch
            if (mine != generation) return@launch // a newer search has taken over
            resultsPanel.removeAll()
            page.rows.forEach { row -> renderOfficerCandidateRow(resultsPanel, row, officerMemberIds, chapter, onGranted) }
        }
    }

    var isInitialEvent = true
    var debounceHandle: Int? = null
    searchField.subscribe { value ->
        if (isInitialEvent) {
            isInitialEvent = false
            runSearch("")
            return@subscribe
        }
        debounceHandle?.let { kotlinx.browser.window.clearTimeout(it) }
        debounceHandle = kotlinx.browser.window.setTimeout({ runSearch(value) }, 300)
    }
}

private fun renderOfficerCandidateRow(
    panel: SimplePanel,
    row: MemberAdminRowDto,
    officerMemberIds: Set<String>,
    chapter: RegionalChapterDto,
    onGranted: () -> Unit,
) {
    val rowPanel = panel.hPanel(spacing = 8) { addCssClasses("align-items-center border-bottom py-1") }
    rowPanel.untrustedSpan(row.displayName)
    val state = officerCandidateState(row, officerMemberIds)
    val button =
        when (state) {
            OfficerCandidateState.ELIGIBLE -> rowPanel.button(tr("Als Landesvorstand eintragen"), style = ButtonStyle.OUTLINEPRIMARY)
            OfficerCandidateState.NO_ACCOUNT -> rowPanel.button(tr("kein Login-Konto"), style = ButtonStyle.OUTLINESECONDARY)
            OfficerCandidateState.ALREADY_OFFICER -> rowPanel.button(tr("bereits Landesvorstand"), style = ButtonStyle.OUTLINESECONDARY)
        }
    if (state != OfficerCandidateState.ELIGIBLE) {
        button.disabled = true
    } else {
        button.onClick {
            confirmDialog(
                title = tr("Landesvorstand eintragen"),
                message = officerGrantConsequence(row.displayName, chapter.name),
                confirmLabel = tr("Zugang erteilen"),
            ) {
                AppScope.launch {
                    val result = regionalChapterGuarded { rpcService<IRegionalChapterService>().grantOfficer(row.id, chapter.id) }
                    if (result != null) {
                        notifySuccess(gettext("%1 wurde als Landesvorstand eingetragen.", row.displayName))
                        onGranted()
                    }
                }
            }
        }
    }
}

// ── Reine Funktionen (DOM-frei, testbar) ──────────────────────────────────────────────────────

internal fun chapterDeleteBlockReason(dto: RegionalChapterDto): String? {
    if (dto.assignedMemberCount == 0 && dto.activeOfficerCount == 0) return null
    return gettext(
        "Löschen erst möglich, wenn keine Mitglieder (%1) und keine Landesvorstände (%2) mehr zugeordnet sind.",
        dto.assignedMemberCount,
        dto.activeOfficerCount,
    )
}

internal fun officerGrantConsequence(
    memberName: String,
    chapterName: String,
): String =
    gettext(
        "%1 erhält Zugriff auf die aktiven Mitglieder von %2 (Name und E-Mail, keine Beitrags- oder Familiendaten). " +
            "Der Vorgang wird protokolliert.",
        memberName,
        chapterName,
    )

internal fun chapterCountsLine(dto: RegionalChapterDto): String =
    gettext(
        "Aktive Mitglieder: %1 · Zugeordnet: %2 · Landesvorstände: %3",
        dto.activeMemberCount,
        dto.assignedMemberCount,
        dto.activeOfficerCount,
    )

internal fun chapterDescriptionCheck(raw: String): FieldCheck =
    when (
        PublicTextRules.normalize(
            raw = raw,
            maxCodePoints = RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS,
            maxLineBreaks = RegionalChapterPublicRules.DESCRIPTION_MAX_LINE_BREAKS,
        )
    ) {
        is PublicTextNormalization.Ok, PublicTextNormalization.Empty -> FieldCheck.Ok
        PublicTextNormalization.TooLong ->
            FieldCheck.Invalid(
                gettext("Die Beschreibung darf höchstens %1 Zeichen lang sein.", RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS),
            )
        PublicTextNormalization.TooManyLineBreaks ->
            FieldCheck.Invalid(
                gettext("Bitte höchstens %1 Zeilenumbrüche verwenden.", RegionalChapterPublicRules.DESCRIPTION_MAX_LINE_BREAKS),
            )
        PublicTextNormalization.ControlChars -> FieldCheck.Invalid(gettext("Der Text enthält unzulässige Zeichen."))
    }

internal fun chapterNameCheck(raw: String): FieldCheck {
    val normalized = RegionalChapterRules.normalizeName(raw)
    return if (RegionalChapterRules.isValidName(normalized)) {
        FieldCheck.Ok
    } else {
        FieldCheck.Invalid(
            gettext(
                "Bitte einen Namen mit %1 bis %2 Zeichen angeben.",
                RegionalChapterRules.NAME_MIN,
                RegionalChapterRules.NAME_MAX,
            ),
        )
    }
}

internal fun officerCandidateQuery(
    search: String,
    chapterId: String,
): MemberAdminQuery =
    MemberAdminQuery(
        search = search.ifBlank { null },
        statuses = setOf(MemberStatus.ACTIVE),
        regionalChapterId = chapterId,
        limit = 10,
    )

internal enum class OfficerCandidateState { ELIGIBLE, NO_ACCOUNT, ALREADY_OFFICER }

internal fun officerCandidateState(
    row: MemberAdminRowDto,
    officerMemberIds: Set<String>,
): OfficerCandidateState =
    when {
        row.id in officerMemberIds -> OfficerCandidateState.ALREADY_OFFICER
        row.role == null -> OfficerCandidateState.NO_ACCOUNT
        else -> OfficerCandidateState.ELIGIBLE
    }

// ── Geteilte Helfer (auch von MemberAdministrationScreen.kt/RegistrationScreen.kt genutzt) ────

/**
 * Re-reads the session and pushes it through [AppState.setSession] -- fires `onSessionChange`
 * (`App.kt`'s `refreshShell`, rebuilds navbar+sidebar only, NEVER the current page/screen) whenever
 * the chapter-derived parts of the session (`chapterScope`) actually changed. Silent for an
 * ORDINARY failure (network error, 5xx): no toast, just `null` -- a failed refresh here is not
 * itself the action the caller cares about (create/delete/grant/revoke already showed its own
 * success toast). See [NavVisibility.showsChapterRoster]/plan §1 P5 for why this exists at all:
 * `SessionInfoDto.chapterScope` is computed once at login/boot-probe time and does NOT update
 * itself when an ADMIN grants/revokes an officer elsewhere.
 *
 * Review fix (MINOR logic): a genuinely EXPIRED session ([UnauthenticatedException]) is no longer
 * swallowed the same way as an ordinary network failure -- the previous blanket `runCatching`
 * made the two indistinguishable to every caller, and `ChapterRosterScreen.kt`'s own load treated
 * BOTH as "the officer grant is gone" (see that file's fix). Routing it through [guarded] instead
 * gets the SAME session-expiry handling every other RPC call already has (clears
 * [AppState.session], shows the "Sitzung abgelaufen" toast, navigates to [Routes.LOGIN]) --
 * `guarded<SessionInfoDto>` matches [handleMemberAdminFailure]'s own `is UnauthenticatedException ->
 * guarded<T> { throw e }` idiom.
 *
 * Deliberately placed in THIS file, not `AppState.kt` -- see this file's own class KDoc "P4".
 */
internal suspend fun refreshSessionFromServer(): SessionInfoDto? =
    try {
        rpcService<IAuthService>().getSessionInfo().also { AppState.setSession(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnauthenticatedException) {
        guarded<SessionInfoDto> { throw e }
    } catch (e: Throwable) {
        null
    }

/**
 * Loads the flat chapter-options list for a picker (`RegistrationScreen`/
 * `renderDirectMemberCreation`/`MemberAdministrationScreen`'s roster filter). NEVER `guarded` --
 * see plan §1 P4/S3: a toast (or `ClientVersionWatcher` reacting to a failed RPC) on a purely
 * optional, silently-degrading picker load would be wrong for every one of those call sites, and
 * `guarded`'s stub-JSON-mismatch behavior in several existing DOM tests (`answerLoadWith` returning
 * a bare object for an unstubbed RPC) would start showing spurious toasts in tests that never
 * intended to exercise this picker at all.
 */
internal suspend fun loadRegionalChapterOptionsOrEmpty(): List<RegionalChapterRefDto> =
    runCatching { rpcService<IRegistrationService>().listRegionalChapterOptions() }.getOrElse { emptyList() }
