package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.isEncounterConsent
import network.lapis.cloud.shared.domain.isEventCapture
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService

/**
 * V1.9.196 -- the administration of the agreement texts (ADMIN only) as a master-detail pair (the V1.9.192 pattern): [renderAgreementTextsScreen] is
 * the table of all texts ([Routes.AGREEMENT_TEXTS]), [renderAgreementTextDetailRoute] one text as its own page ([Routes.AGREEMENT_TEXTS_DETAIL],
 * deep link). The detail page shows, from top to bottom: the warning while the built-in placeholder is the live text, the current version
 * (collapsed), the editor of the next version (Markdown on the left, the server-rendered preview on the right) and the history of everything
 * that was ever published. A published version can never be changed; "Veröffentlichen" asks first and says so.
 *
 * **The client never renders Markdown.** The preview and every Markdown text come as HTML from the server and go through the one rich site
 * of the agreement renderer ([agreementHtml]); plain (legacy) texts stay element text ([renderAgreementText]).
 */
fun renderAgreementTextsScreen(container: SimplePanel) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 10)
    root.pageHeader(tr("Vertragstexte"), intro = PageIntros.forRoute(Routes.AGREEMENT_TEXTS))
    root
        .dataSection<AgreementTextOverviewDto>(
            isEmpty = { it.kinds.isEmpty() },
            load = { guarded { rpcService<IAgreementTextAdminService>().getOverview() } },
            render = { panel, overview -> renderAgreementTextTable(panel, overview.kinds) },
        ).reload()
}

private fun renderAgreementTextTable(
    host: SimplePanel,
    kinds: List<AgreementTextKindOverviewDto>,
) {
    val headers =
        listOf(
            TableHeader(tr("Text")),
            TableHeader(tr("Aktuelle Fassung")),
            TableHeader(tr("Gültig ab")),
            TableHeader(tr("Veröffentlicht am")),
            TableHeader(tr("Status")),
            TableHeader(tr("Aktion")),
        )
    val table = host.masterTable(tr("Vertragstexte"), headers)
    kinds.forEach { overview ->
        val kind = overview.kind
        val name = agreementKindLabel(kind)
        val route = kindRouteId(kind)
        table.row {
            masterRow()
            val nameCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(nameCell)
            nameCell.recordLink(name, Routes.AGREEMENT_TEXTS, route)
            val current = overview.current
            textCell(current?.versionLabel ?: overview.builtinVersionLabel)
            textCell(current?.let { formatDate(it.validFrom) } ?: "–")
            textCell(current?.let { formatSystemDateTime(it.publishedAt) } ?: "–")
            cell {
                if (overview.placeholderActive) {
                    statusBadge(
                        tr("Platzhalter aktiv"),
                        "warning",
                    )
                } else {
                    statusBadge(tr("Eigene Fassung"), "success")
                }
                if (overview.hasDraft) {
                    span(" ")
                    statusBadge(tr("Entwurf offen"), "info")
                }
            }
            cell {
                tableActionGroup()
                    .tableActionButton(ActionIcon.EDIT, gettext("Bearbeiten: %1", name))
                    .onClick {
                        MasterDetailFocus.remember(Routes.AGREEMENT_TEXTS, route)
                        navigateTo(detailRoute(Routes.AGREEMENT_TEXTS, route))
                    }
            }
        }
    }
}

/** The id of a kind in the route (`/agreement-texts/membership`). */
internal fun kindRouteId(kind: AgreementTextKind): String = kind.name.lowercase()

/** The kind of a route id, or `null` for anything that is not one (an unknown deep link is a hint on the page, never an exception). */
internal fun agreementKindFromRoute(id: String?): AgreementTextKind? =
    AgreementTextKind.entries.firstOrNull {
        it.name.lowercase() ==
            id?.lowercase()
    }

/** The detail route: resolves the kind of the path and shows either its page or a hint with the way back. */
fun renderAgreementTextDetailRoute(
    container: SimplePanel,
    kindId: String,
) {
    val kind = agreementKindFromRoute(kindId)
    if (kind == null) {
        agreementDetailShell(container, tr("Vertragstext")).p(tr("Diesen Vertragstext gibt es nicht."))
    } else {
        renderAgreementTextDetail(container, kind)
    }
}

/** The frame of a detail page: the back button ABOVE the one page header (the only `pageHeader` call of the detail routes). */
private fun agreementDetailShell(
    container: SimplePanel,
    title: String,
): io.kvision.panel.VPanel {
    val root = container.pageShell(PageWidth.WIDE, spacing = 10)
    root.overviewBackButton(Routes.AGREEMENT_TEXTS)
    root.pageHeader(title)
    return root
}

fun renderAgreementTextDetail(
    container: SimplePanel,
    kind: AgreementTextKind,
) {
    val root = agreementDetailShell(container, gettext("Vertragstext: %1", agreementKindLabel(kind)))
    lateinit var section: DataSection
    section =
        root.dataSection<AgreementKindData>(
            isEmpty = { false },
            load = { guarded { loadAgreementKindData(kind) } },
            render = { panel, data ->
                if (isOptionalFeatureKind(kind)) {
                    panel.div(
                        when {
                            kind == AgreementTextKind.FINDER ->
                                gettext(
                                    "Gilt nur, wenn der Mitglieder-Finder für die Instanz eingeschaltet ist.",
                                )
                            kind.isEncounterConsent() -> gettext("Gilt nur für Begegnungsräume, die Gäste oder Freunde zulassen.")
                            kind.isEventCapture() ->
                                gettext("Gilt nur, wenn die Aufzeichnung in Veranstaltungsräumen für die Instanz eingeschaltet ist.")
                            else -> gettext("Gilt nur, wenn die Aufzeichnung der Kanzel für die Instanz eingeschaltet ist.")
                        },
                        className = "text-muted small",
                    )
                }
                if (data.overview.placeholderActive) {
                    renderPlaceholderWarning(panel, data.overview.builtinVersionLabel, isOptionalFeatureKind(kind))
                }
                renderCurrentVersionCard(panel, kind, data)
                renderDraftEditor(panel, kind, data, reload = { section.reload() })
                renderVersionHistory(panel, kind, data.versions)
            },
        )
    section.reload()
}

/** Everything one detail page shows, loaded together so the page renders once. [currentBody] is the source of the live version (pre-fills a new draft). */
internal class AgreementKindData(
    val overview: AgreementTextKindOverviewDto,
    val draft: AgreementTextDraftDto?,
    val versions: List<AgreementTextVersionSummaryDto>,
    val currentBody: String? = null,
)

private suspend fun loadAgreementKindData(kind: AgreementTextKind): AgreementKindData {
    val service = rpcService<IAgreementTextAdminService>()
    val overview = service.getOverview().kinds.first { it.kind == kind }
    val draft = service.getDraft(kind).draft
    val currentBody = overview.current?.let { service.getVersion(kind, it.versionLabel).body }
    return AgreementKindData(overview = overview, draft = draft, versions = service.listVersions(kind), currentBody = currentBody)
}

private fun renderPlaceholderWarning(
    panel: SimplePanel,
    builtinLabel: String,
    recording: Boolean = false,
) {
    panel.div {
        addCssClasses("alert alert-warning d-flex align-items-center gap-2")
        icon("fas fa-triangle-exclamation")
        div(
            if (recording) {
                gettext(
                    "Platzhaltertext aktiv: Diese Instanz zeigt den eingebauten Mustertext (Version %1). " +
                        "Bitte veröffentlichen Sie eine rechtlich geprüfte Fassung für Ihr Land.",
                    builtinLabel,
                )
            } else {
                gettext(
                    "Platzhaltertext aktiv: Diese Instanz zeigt bei der Registrierung den eingebauten Mustertext (Version %1). " +
                        "Bitte veröffentlichen Sie Ihre rechtlich geprüfte Fassung.",
                    builtinLabel,
                )
            },
        )
    }
}

private fun shortHash(
    sha256: String,
    length: Int = SHORT_HASH_LENGTH,
): String = sha256.take(length)

private const val SHORT_HASH_LENGTH = 8
private const val HISTORY_HASH_LENGTH = 12

/**
 * A "Text anzeigen" button that shows or hides the text of version [versionLabel] of [kind] in [textHost]; the text is fetched on the first
 * click only. The state is `aria-expanded`; the label stays the same (a disclosure, not two actions). A MARKDOWN version is shown through its
 * server-rendered HTML ([renderAgreementBody]), a plain one as text.
 */
private fun attachVersionTextToggle(
    button: Button,
    textHost: SimplePanel,
    kind: AgreementTextKind,
    versionLabel: String,
) {
    button.setAttribute("aria-expanded", "false")
    var loaded = false

    fun setShown(shown: Boolean) {
        if (shown) textHost.show() else textHost.hide()
        button.setAttribute("aria-expanded", shown.toString())
    }
    button.onClick {
        if (textHost.visible) {
            setShown(false)
        } else if (loaded) {
            setShown(true)
        } else {
            AppScope.launch {
                val version = guarded { rpcService<IAgreementTextAdminService>().getVersion(kind, versionLabel) }
                if (version != null) {
                    textHost.removeAll()
                    textHost.agreementFrame(
                        text = version.body,
                        ariaLabel = gettext("Text der Version %1", versionLabel),
                        format = version.summary.format,
                        renderedHtml = version.renderedHtml,
                    )
                    loaded = true
                    setShown(true)
                }
            }
        }
    }
}

private fun renderCurrentVersionCard(
    panel: SimplePanel,
    kind: AgreementTextKind,
    data: AgreementKindData,
) {
    val current = data.overview.current
    val label = current?.versionLabel ?: data.overview.builtinVersionLabel
    // Collapsed: the current version is reference material, the editor below is the task.
    val details = Tag(TAG.DETAILS, className = "border rounded p-3")
    details.add(Tag(TAG.SUMMARY, content = sanitizeUntrustedI18nText(gettext("Aktuelle Fassung: %1", label)), className = "fw-bold"))
    val card = details.vPanel(spacing = 4) { addCssClass("mt-2") }
    if (current == null) {
        card.div(gettext("Eingebauter Mustertext, Version %1.", label))
    } else {
        card.div(gettext("Version: %1", label))
        card.div(gettext("Gültig ab: %1", formatDate(current.validFrom)))
        card.untrustedDiv(
            gettext(
                "Veröffentlicht am %1 von %2",
                formatSystemDateTime(current.publishedAt),
                sanitizeUntrustedI18nText(current.authorDisplayName),
            ),
        )
        // The full hash is the tooltip, the short form (8 characters) the visible text.
        card.div(gettext("Prüfsumme: %1", shortHash(current.sha256))) {
            addCssClass("font-monospace")
            title = current.sha256
        }
        card.div(gettext("Zeichen: %1", current.charCount))
        data.overview.olderAcknowledgmentCount?.takeIf { it > 0 }?.let { count ->
            card.div(gettext("Personen ohne Bestätigung dieser Fassung: %1", count))
        }
    }
    val textHost = card.simplePanel { hide() }
    val viewButton = card.actionButton(ActionIcon.VIEW, tr("Text anzeigen"))
    attachVersionTextToggle(button = viewButton, textHost = textHost, kind = kind, versionLabel = label)
    panel.add(details)
}

internal fun agreementKindLabel(kind: AgreementTextKind): String =
    when (kind) {
        AgreementTextKind.MEMBERSHIP -> tr("Beitrittsvertrag")
        AgreementTextKind.FRIEND -> tr("Freund-Nutzungsbedingungen")
        // V1.9.143: the two texts of the optional pulpit recording (see PulpitRecordingSettings.kt).
        AgreementTextKind.REC_ROOM -> tr("Hinweis bei Aufzeichnung (Raum)")
        AgreementTextKind.REC_PULPIT -> tr("Zustimmung der Kanzel zur Aufzeichnung")
        // V1.9.144/V1.9.145: the consent text of the optional member finder (shown to the member on /member-finder, see MemberFinderScreen.kt).
        AgreementTextKind.FINDER -> tr("Einwilligung Mitglieder-Finder")
        // V1.9.148: the entry notice a non-member acknowledges before entering an encounter room.
        AgreementTextKind.ENC_CHURCH -> tr("Eintrittshinweis Gottesdienst")
        AgreementTextKind.ENC_ASSEM -> tr("Eintrittshinweis Versammlung")
        // V1.9.156: the consent every participant gives before entering an event room that may be recorded or streamed (see EventCaptureSettings.kt).
        AgreementTextKind.EVENT_REC -> tr("Aufzeichnung (Veranstaltung)")
    }

/**
 * V1.9.143/V1.9.144/V1.9.148: the pulpit-recording kinds, the member-finder consent and the two encounter entry notices are no registration text: nobody acknowledges them as part of the
 * registration, and they apply only while the optional feature is on.
 */
private fun isOptionalFeatureKind(kind: AgreementTextKind): Boolean =
    kind == AgreementTextKind.REC_ROOM ||
        kind == AgreementTextKind.REC_PULPIT ||
        kind == AgreementTextKind.FINDER ||
        kind.isEncounterConsent() ||
        kind.isEventCapture()

private fun labelCheck(
    kind: AgreementTextKind,
    value: String,
): FieldCheck =
    when {
        !AgreementTextRules.isValidLabel(value.trim()) ->
            FieldCheck.Invalid(
                gettext(
                    "Bitte eine Version angeben: Buchstaben, Ziffern, Punkt, Unterstrich und Bindestrich, höchstens %1 Zeichen.",
                    AgreementTextRules.VERSION_LABEL_MAX,
                ),
            )
        // V1.9.148: the system adds "+rec" for rooms with a recording; the label must leave room for it.
        kind.isEncounterConsent() && value.trim().length > EncounterConsentLayout.ENC_LABEL_MAX ->
            FieldCheck.Invalid(gettext("Die Version darf höchstens %1 Zeichen lang sein.", EncounterConsentLayout.ENC_LABEL_MAX))
        else -> FieldCheck.Ok
    }

private fun bodyCheck(
    kind: AgreementTextKind,
    value: String,
): FieldCheck {
    val body = AgreementTextRules.normalizeBody(value)
    return when {
        body.isEmpty() -> FieldCheck.Invalid(gettext("Bitte geben Sie den Vertragstext ein."))
        body.length > AgreementTextRules.MAX_CHARS ->
            FieldCheck.Invalid(gettext("Der Vertragstext darf höchstens %1 Zeichen lang sein.", AgreementTextRules.MAX_CHARS))
        AgreementTextRules.invalidCharacter(body) ->
            FieldCheck.Invalid(gettext("Der Vertragstext enthält unzulässige Zeichen (Steuerzeichen oder Richtungsumschalter)."))
        kind.isEncounterConsent() || kind.isEventCapture() -> layoutCheck(body)
        (kind == AgreementTextKind.REC_ROOM || kind == AgreementTextKind.REC_PULPIT) && EncounterConsentLayout.hasBlockMarkup(body) ->
            FieldCheck.Invalid(gettext("In diesem kurzen Text sind Überschriften, Listen, Zitate und Codeblöcke nicht erlaubt."))
        else -> FieldCheck.Ok
    }
}

/** V1.9.148: the paragraph rule of an entry notice, with the concrete message (the server only answers with its own fixed one). */
private fun layoutCheck(body: String): FieldCheck {
    val paragraphs = EncounterConsentLayout.paragraphs(body = body)
    return when {
        paragraphs.size < EncounterConsentLayout.MIN_PARAGRAPHS ->
            FieldCheck.Invalid(gettext("Der Hinweis braucht mindestens vier Absätze: Überschrift und drei Kernpunkte."))
        paragraphs[0].length > EncounterConsentLayout.HEADLINE_MAX ->
            FieldCheck.Invalid(gettext("Die Überschrift darf höchstens %1 Zeichen lang sein.", EncounterConsentLayout.HEADLINE_MAX))
        EncounterConsentLayout.violation(label = "", body = body, format = AgreementTextFormat.MARKDOWN) ==
            EncounterConsentLayout.Violation.BLOCK_MARKUP_IN_SUMMARY ->
            // V1.9.196: the first four paragraphs are shown as plain text outside the full text.
            FieldCheck.Invalid(
                gettext("In Überschrift und Kernpunkten (Absatz 1 bis 4) sind Überschriften, Listen, Zitate und Codeblöcke nicht erlaubt."),
            )
        else -> {
            val tooLong =
                (1 until EncounterConsentLayout.MIN_PARAGRAPHS).firstOrNull {
                    paragraphs[it].length >
                        EncounterConsentLayout.KEY_POINT_MAX
                }
            if (tooLong == null) {
                FieldCheck.Ok
            } else {
                FieldCheck.Invalid(
                    gettext("Kernpunkt %1 darf höchstens %2 Zeichen lang sein.", tooLong, EncounterConsentLayout.KEY_POINT_MAX),
                )
            }
        }
    }
}

/** V1.9.148: `true` if the text says nothing is recorded -- which contradicts the sentence code appends in a room with a recording. */
private fun contradictsRecording(body: String): Boolean = body.lowercase().replace(Regex("\\s+"), " ").contains("nichts aufgezeichnet")

private fun counterText(value: String): String =
    gettext("%1 von %2 Zeichen", AgreementTextRules.normalizeBody(value).length, AgreementTextRules.MAX_CHARS)

/** The preview waits for this long after the last keystroke before it asks the server (one request per pause, the latest answer wins). */
internal const val AGREEMENT_PREVIEW_DEBOUNCE_MS = 500

private fun renderDraftEditor(
    panel: SimplePanel,
    kind: AgreementTextKind,
    data: AgreementKindData,
    reload: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    card.h2(tr("Nächste Fassung")) { addCssClass("h5") }
    val draft = data.draft
    val today = organizationToday()
    val sameDay = data.versions.count { it.versionLabel.startsWith("$today.") }
    // A new draft starts from the source of the live version, so the next version is an edit of it, not a blank page.
    val seededFromPlain = draft == null && data.currentBody != null && data.overview.current?.format == AgreementTextFormat.PLAIN
    val startBody = draft?.body ?: data.currentBody
    val form = card.lapisForm()
    val labelField =
        form.textField(
            label = tr("Version"),
            value = draft?.versionLabel ?: AgreementTextRules.suggestLabel(today, sameDay + 1),
            required = true,
            hint = tr("Zum Beispiel 2026-10-15.v1. Jede Fassung braucht eine neue Bezeichnung."),
            rule = { labelCheck(kind, it) },
        )
    val validFromField =
        form.localDateField(
            label = tr("Gültig ab"),
            value = draft?.validFrom ?: today,
            required = true,
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            // gettext, not tr(): the hint is joined to "Format: ..." in LocalDateField and a tr() marker only resolves at the start of a text
            extraHint = gettext("Nicht in der Zukunft und nicht vor der aktuellen Fassung."),
        )
    if (seededFromPlain || draft?.format == AgreementTextFormat.PLAIN) {
        form.panel.div(
            tr(
                "Die bisherige Fassung ist einfacher Text. Die neue Fassung wird als Markdown gespeichert: Aufzählungen beginnen mit " +
                    "\"- \" (ein Punktzeichen wird nicht umgewandelt), Absätze trennt eine Leerzeile.",
            ),
            className = "alert alert-info small mb-0",
        ) { setAttribute("role", "note") }
    }
    val grid = form.panel.div { addCssClass("lapis-agreement-editor-grid") }
    val editorColumn = grid.div()
    val previewColumn = grid.div()
    val bodyField =
        form.textAreaField(
            label = tr("Text (Markdown)"),
            rows = 20,
            value = startBody,
            required = true,
            requiredMessage = tr("Bitte geben Sie den Vertragstext ein."),
            hint = counterText(startBody.orEmpty()),
            hintLive = true,
            host = editorColumn,
            rule = { bodyCheck(kind, it) },
        )
    editorColumn.div {
        // Plain element text: the help only NAMES the syntax (lower-case https:// is the one link scheme that works).
        add(
            Tag(TAG.DETAILS, className = "small mt-1").apply {
                add(Tag(TAG.SUMMARY, content = tr("Kurzhilfe: Formatierung")))
                add(
                    Tag(
                        TAG.PRE,
                        content =
                            "## Überschrift\n\nEin Absatz mit **fett** und *kursiv*.\n\n- Aufzählung\n- zweiter Punkt\n\n" +
                                "1. nummeriert\n\n[Linktext](https://beispiel.de)",
                        className = "small mb-0",
                    ),
                )
                add(
                    Tag(
                        TAG.P,
                        content =
                            tr(
                                "Zeilenumbrüche bleiben erhalten. Verlinkt werden nur Adressen, die mit https:// oder mailto: beginnen " +
                                    "(klein geschrieben). Bilder und eingebettetes HTML werden nicht dargestellt.",
                            ),
                        className = "mt-1 mb-0",
                    ),
                )
            },
        )
    }
    form.panel.p(
        tr("Maßgeblich ist die deutsche Fassung. Übersetzungen sind nicht rechtsverbindlich."),
    ) { addCssClasses("text-muted small") }

    // V1.9.148: the entry notices are laid out as headline + three key points + detail, and the system owns the recording sentence.
    val encounter = kind.isEncounterConsent()
    var recordingRoom = false
    var refreshPreviewHook: (() -> Unit)? = null
    var contradictionWarning: SimplePanel? = null
    if (encounter) {
        form.panel.p(
            tr("Die Aussage zur Kanzel-Aufzeichnung fügt das System selbst an, wenn ein Raum sie erlaubt."),
        ) { addCssClasses("text-muted small") }
        contradictionWarning =
            form.panel.simplePanel {
                addCssClasses("alert alert-warning small")
                setAttribute("role", "status")
                span(tr("Dieser Text widerspricht der Kanzel-Aufzeichnung in Räumen, die sie erlauben."))
                hide()
            }
    }

    previewColumn.h2(tr("Vorschau – so sehen Mitglieder den Text")) { addCssClass("h6") }
    if (encounter) {
        form
            .checkField(
                label = if (kind == AgreementTextKind.ENC_ASSEM) tr("Raum mit Podium-Aufzeichnung") else tr("Raum mit Kanzel-Aufzeichnung"),
                value = false,
                host = previewColumn,
            ).subscribe {
                recordingRoom = it == "true"
                refreshPreviewHook?.invoke()
            }
    }
    val previewHost = previewColumn.div { addCssClasses("lapis-agreement-preview border rounded p-2") }
    var previewSerial = 0

    fun refreshPreview() {
        val text = AgreementTextRules.normalizeBody(bodyField.value)
        if (contradictionWarning != null) {
            if (contradictsRecording(text)) contradictionWarning.show() else contradictionWarning.hide()
        }
        val mine = ++previewSerial
        val invalid = text.length > AgreementTextRules.MAX_CHARS || AgreementTextRules.invalidCharacter(text)
        when {
            text.isEmpty() -> {
                previewHost.removeAll()
                previewHost.p(tr("Die Vorschau erscheint, sobald Sie Text eingeben.")) { addCssClasses("text-muted") }
            }
            invalid -> {
                previewHost.removeAll()
                previewHost.p(tr("Für diesen Text ist keine Vorschau möglich. Bitte beheben Sie den Fehler im Feld links.")) {
                    addCssClasses("text-muted")
                }
            }
            else -> {
                // The server owns the rendering. Only the answer of the LATEST request is shown.
                val source =
                    if (encounter && recordingRoom) text + "\n\n" + EncounterConsentLayout.recordingParagraph(kind) else text
                AppScope.launch {
                    val html =
                        try {
                            rpcService<IAgreementTextAdminService>().previewAgreementText(kind, source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            null
                        }
                    if (mine == previewSerial) {
                        previewHost.removeAll()
                        if (html == null) {
                            previewHost.p(tr("Die Vorschau ist gerade nicht verfügbar.")) { addCssClasses("text-danger") }
                        } else {
                            previewHost.renderAgreementBody(text = text, format = AgreementTextFormat.MARKDOWN, renderedHtml = html)
                        }
                    }
                }
            }
        }
    }
    refreshPreviewHook = ::refreshPreview
    refreshPreview()

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Als Entwurf speichern"), style = ButtonStyle.OUTLINESECONDARY)
    val publishButton = Button(text = tr("Veröffentlichen"), style = ButtonStyle.PRIMARY)

    /** `true` while the editor holds exactly what the stored draft holds (only then the draft is what gets published). */
    fun matchesDraft(): Boolean =
        draft != null &&
            // a draft stored before V1.9.196 is PLAIN: it must be saved again (as Markdown) before it can be published
            draft.format == AgreementTextFormat.MARKDOWN &&
            labelField.value.trim() == draft.versionLabel &&
            validFromField.localDateOrNull() == draft.validFrom &&
            AgreementTextRules.normalizeBody(bodyField.value) == draft.body

    fun syncPublishButton() {
        publishButton.disabled = !matchesDraft()
    }
    syncPublishButton()
    var previewTimer: Int? = null
    bodyField.subscribe { value ->
        bodyField.setHintText(counterText(value))
        previewTimer?.let { window.clearTimeout(it) }
        previewTimer = window.setTimeout({ refreshPreview() }, AGREEMENT_PREVIEW_DEBOUNCE_MS)
        syncPublishButton()
    }
    labelField.subscribe { syncPublishButton() }
    validFromField.subscribe { syncPublishButton() }
    form.buttons(primary = publishButton, cancel = saveButton)
    if (draft != null && draft.format != AgreementTextFormat.MARKDOWN) {
        // explains the grey publish button: a pre-V1.9.196 draft is PLAIN and has to be saved again first
        card.add(
            Tag(
                TAG.DIV,
                content = tr("Bitte den Entwurf erneut speichern, bevor er veröffentlicht werden kann."),
                className = "small text-muted mt-1",
            ),
        )
    }

    saveButton.onClick {
        form.submit(saveButton) {
            val saved =
                try {
                    rpcService<IAgreementTextAdminService>().saveDraft(
                        AgreementTextDraftInput(
                            kind = kind,
                            versionLabel = labelField.value.trim(),
                            validFrom = checkNotNull(validFromField.localDateOrNull()),
                            body = bodyField.value,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AgreementTextVersionLabelTakenException) {
                    labelField.showError(tr("Diese Versionsbezeichnung ist bereits vergeben."))
                    null
                } catch (e: AgreementTextLayoutInvalidException) {
                    bodyField.showError(tr("Der Hinweis entspricht nicht der vorgegebenen Gliederung."))
                    null
                } catch (e: AgreementTextInvalidException) {
                    bodyField.showError(
                        tr(
                            "Dieser Text oder dieses Datum ist nicht zulässig: Das Datum darf weder in der Zukunft noch " +
                                "vor der aktuellen Fassung liegen, und der Text darf nicht dem Mustertext entsprechen.",
                        ),
                    )
                    null
                } catch (e: Throwable) {
                    guarded { throw e }
                }
            if (saved != null) {
                notifySuccess(tr("Entwurf gespeichert."))
                reload()
            }
        }
    }

    publishButton.onClick {
        val stored = draft ?: return@onClick
        if (!matchesDraft()) return@onClick
        confirmDialog(
            title = tr("Fassung veröffentlichen?"),
            message =
                gettext("Version %1, gültig ab %2, %3 Zeichen.", stored.versionLabel, formatDate(stored.validFrom), stored.charCount) +
                    // V1.9.148: a warning, never a block -- the admin may have a reason (and the system appends its own sentence anyway).
                    if (encounter && contradictsRecording(stored.body)) {
                        " " + gettext("Dieser Text widerspricht der Kanzel-Aufzeichnung in Räumen, die sie erlauben.")
                    } else {
                        ""
                    },
            confirmLabel = tr("Veröffentlichen"),
            confirmStyle = ButtonStyle.PRIMARY,
            dangerNote = tr("Diese Fassung kann nicht mehr geändert oder gelöscht werden."),
            focusCancel = true,
        ) {
            AppScope.launch {
                val published =
                    try {
                        rpcService<IAgreementTextAdminService>().publishDraft(kind, stored.sha256)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AgreementTextVersionLabelTakenException) {
                        labelField.showError(tr("Diese Versionsbezeichnung ist bereits vergeben."))
                        null
                    } catch (e: Throwable) {
                        // DraftChanged and every other failure: the shared toast; the view is reloaded so the admin sees the stored draft.
                        guarded { throw e }
                        reload()
                        null
                    }
                if (published != null) {
                    notifySuccess(tr("Die Fassung wurde veröffentlicht."))
                    reload()
                }
            }
        }
    }
}

/** The history of everything that was ever published: a table (newest first); "Text anzeigen" opens one version below it. */
private fun renderVersionHistory(
    panel: SimplePanel,
    kind: AgreementTextKind,
    versions: List<AgreementTextVersionSummaryDto>,
) {
    panel.h2(tr("Verlauf der Fassungen")) { addCssClass("h5") }
    if (versions.isEmpty()) {
        panel.p(tr("Noch keine eigene Fassung veröffentlicht.")) { addCssClass("text-muted") }
        return
    }
    val headers =
        listOf(
            TableHeader(tr("Version")),
            TableHeader(tr("Gültig ab")),
            TableHeader(tr("Veröffentlicht am")),
            TableHeader(tr("Von")),
            TableHeader(tr("Format")),
            TableHeader(tr("Prüfsumme")),
            TableHeader(tr("Text")),
        )
    val table = panel.standardTable(headers)
    table.caption = tr("Verlauf der Fassungen")
    table.addCssClass(REPORT_CAPTION_HIDDEN_CLASS)
    val textHost = SimplePanel()
    val buttons = mutableListOf<Button>()
    var shownLabel: String? = null
    versions.forEach { version ->
        table.row {
            textCell(version.versionLabel)
            textCell(formatDate(version.validFrom))
            textCell(formatSystemDateTime(version.publishedAt))
            textCell(version.authorDisplayName)
            cell {
                if (version.format ==
                    AgreementTextFormat.MARKDOWN
                ) {
                    typeBadge(tr("Markdown"), "primary")
                } else {
                    typeBadge(tr("Einfacher Text"), "secondary")
                }
            }
            cell {
                untrustedSpan(shortHash(version.sha256, HISTORY_HASH_LENGTH)) {
                    addCssClass("font-monospace")
                    title = version.sha256
                }
            }
            cell {
                val view = tableActionGroup().tableActionButton(ActionIcon.VIEW, gettext("Text anzeigen: %1", version.versionLabel))
                view.setAttribute("aria-expanded", "false")
                buttons += view
                view.onClick {
                    buttons.forEach { it.setAttribute("aria-expanded", "false") }
                    if (shownLabel == version.versionLabel) {
                        shownLabel = null
                        textHost.removeAll()
                    } else {
                        AppScope.launch {
                            val loaded = guarded { rpcService<IAgreementTextAdminService>().getVersion(kind, version.versionLabel) }
                            if (loaded != null) {
                                shownLabel = version.versionLabel
                                view.setAttribute("aria-expanded", "true")
                                textHost.removeAll()
                                textHost.agreementFrame(
                                    text = loaded.body,
                                    ariaLabel = gettext("Text der Version %1", version.versionLabel),
                                    format = loaded.summary.format,
                                    renderedHtml = loaded.renderedHtml,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    panel.add(textHost)
}

/**
 * V1.9.134 -- the hint on the member administration (ADMIN only; the caller gates that): which agreement texts still are the built-in
 * placeholder, with a link to the administration. Invisible without a placeholder and when the overview cannot be read -- a failed hint
 * must never disturb the screen it sits on ([quietly]).
 */
internal fun renderAgreementPlaceholderHint(root: SimplePanel) {
    val band = root.div { hide() }
    AppScope.launch {
        val overview = quietly { rpcService<IAgreementTextAdminService>().getOverview() } ?: return@launch
        val names = overview.kinds.filter { it.placeholderActive && !isOptionalFeatureKind(it.kind) }.map { agreementKindLabel(it.kind) }
        if (names.isEmpty()) return@launch
        band.addCssClasses("alert alert-warning d-flex align-items-center flex-wrap gap-2")
        band.icon("fas fa-triangle-exclamation")
        band.span(gettext("Platzhaltertext aktiv: %1", names.joinToString(", ")))
        band.link(tr("Vertragstexte bearbeiten"), url = "#${Routes.AGREEMENT_TEXTS}")
        band.show()
    }
}
