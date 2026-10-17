package network.lapis.cloud.client

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
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.client.encounter.renderEncounterConsentPreview
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
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
 * V1.9.134 -- the administration of the registration texts (ADMIN only, route [Routes.AGREEMENT_TEXTS]): one tab per document (membership
 * agreement, FRIEND terms). Each tab shows, from top to bottom: the warning while the built-in placeholder is the live text, the current
 * version, the editor of the draft (version label, "valid from", the text with a live counter) with a preview, and the list of everything that
 * was ever published. A published version can never be changed; "Veröffentlichen" asks first and says so.
 *
 * Every text goes through [renderAgreementText] (the same renderer the registration uses): only ever element text, never markup.
 */
fun renderAgreementTextsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 10) {
            addCssClass("mx-auto")
            maxWidth = WIDE_FORM_MAX_WIDTH_PX.px
            width = 100.perc
        }
    root.pageHeader(tr("Vertragstexte"))
    root.p(
        tr(
            "Hier pflegen Sie die Texte, die bei der Registrierung angezeigt und bestätigt werden. " +
                "Eine veröffentlichte Fassung kann nicht mehr geändert werden; jede Änderung ist eine neue Fassung.",
        ),
    ) { addCssClass("text-muted") }
    val tabRow =
        root.hPanel(spacing = 8) {
            addCssClasses("flex-wrap")
            setAttribute("role", "tablist")
        }
    val content = root.vPanel(spacing = 10)
    val tabButtons =
        AgreementTextKind.entries.associateWith { kind ->
            tabRow.button(agreementKindLabel(kind), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") }
        }

    fun showTab(kind: AgreementTextKind) {
        tabButtons.forEach { (tabKind, tabButton) -> tabButton.setAttribute("aria-selected", (tabKind == kind).toString()) }
        content.removeAll()
        renderAgreementKind(content, kind)
    }
    tabButtons.forEach { (kind, tabButton) -> tabButton.onClick { showTab(kind) } }
    showTab(AgreementTextKind.MEMBERSHIP)
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

/** Everything one tab shows, loaded together so the tab renders once. */
internal class AgreementKindData(
    val overview: AgreementTextKindOverviewDto,
    val draft: AgreementTextDraftDto?,
    val versions: List<AgreementTextVersionSummaryDto>,
)

private suspend fun loadAgreementKindData(kind: AgreementTextKind): AgreementKindData {
    val service = rpcService<IAgreementTextAdminService>()
    val overview = service.getOverview().kinds.first { it.kind == kind }
    return AgreementKindData(overview = overview, draft = service.getDraft(kind).draft, versions = service.listVersions(kind))
}

private fun renderAgreementKind(
    host: SimplePanel,
    kind: AgreementTextKind,
) {
    lateinit var section: DataSection
    section =
        host.dataSection<AgreementKindData>(
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
                    renderPlaceholderWarning(
                        panel,
                        data.overview.builtinVersionLabel,
                        isOptionalFeatureKind(kind),
                    )
                }
                renderCurrentVersionCard(panel, kind, data)
                renderDraftEditor(panel, kind, data, reload = { section.reload() })
                renderVersionList(panel, kind, data.versions)
            },
        )
    section.reload()
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

private fun shortHash(sha256: String): String = sha256.take(SHORT_HASH_LENGTH)

private const val SHORT_HASH_LENGTH = 8

/** The hash in its short form (8 characters, monospace); the full hash is the tooltip. */
private fun SimplePanel.hashSpan(sha256: String) {
    span(shortHash(sha256)) {
        addCssClass("font-monospace")
        title = sha256
    }
}

/**
 * A "Text anzeigen" button that shows or hides the text of version [versionLabel] of [kind] in [textHost]; the text is fetched on the first
 * click only. The state is `aria-expanded`; the label stays the same (a disclosure, not two actions).
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
                    textHost.agreementFrame(text = version.body, ariaLabel = gettext("Text der Version %1", versionLabel))
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
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    card.h2(tr("Aktuelle Fassung")) { addCssClass("h5") }
    val current = data.overview.current
    val label = current?.versionLabel ?: data.overview.builtinVersionLabel
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
}

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

private const val PREVIEW_DEBOUNCE_MS = 300

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
            extraHint = tr("Nicht in der Zukunft und nicht vor der aktuellen Fassung."),
        )
    val bodyField =
        form.textAreaField(
            label = tr("Vertragstext"),
            rows = 14,
            value = draft?.body,
            required = true,
            requiredMessage = tr("Bitte geben Sie den Vertragstext ein."),
            hint = counterText(draft?.body.orEmpty()),
            hintLive = true,
            rule = { bodyCheck(kind, it) },
        )
    form.panel.p(
        tr("Maßgeblich ist die deutsche Fassung. Übersetzungen sind nicht rechtsverbindlich."),
    ) { addCssClasses("text-muted small") }

    // V1.9.148: the entry notices are laid out as headline + three key points + detail, and the system owns the recording sentence.
    val encounter = kind.isEncounterConsent()
    // V1.9.156: the event-room consent has the same layout (headline + three key points + detail) but NO code-owned recording sentence.
    val eventCapture = kind.isEventCapture()
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

    val previewTitle =
        when {
            encounter -> tr("So sieht der Gast den Hinweis")
            eventCapture -> tr("So sehen Teilnehmende den Hinweis")
            else -> tr("Vorschau")
        }
    form.panel.h2(previewTitle) { addCssClass("h6") }
    if (encounter) {
        form
            .checkField(
                label = if (kind == AgreementTextKind.ENC_ASSEM) tr("Raum mit Podium-Aufzeichnung") else tr("Raum mit Kanzel-Aufzeichnung"),
                value = false,
            ).subscribe {
                recordingRoom = it == "true"
                refreshPreviewHook?.invoke()
            }
    }
    val previewHost = form.panel.simplePanel()

    fun refreshPreview() {
        previewHost.removeAll()
        val text = AgreementTextRules.normalizeBody(bodyField.value)
        if (contradictionWarning != null) {
            if (contradictsRecording(text)) contradictionWarning.show() else contradictionWarning.hide()
        }
        if (text.isEmpty()) {
            previewHost.p(tr("Die Vorschau erscheint, sobald Sie Text eingeben.")) { addCssClasses("text-muted") }
        } else if (encounter || eventCapture) {
            val paragraph = if (recordingRoom && encounter) EncounterConsentLayout.recordingParagraph(kind) else null
            val split = EncounterConsentLayout.splitOrNull(body = text, extraKeyPoint = paragraph)
            if (split == null) {
                previewHost.p(tr("Die Vorschau erscheint, sobald der Hinweis die vorgegebene Gliederung erfüllt.")) {
                    addCssClasses("text-muted")
                }
            } else {
                previewHost.renderEncounterConsentPreview(
                    split.copy(text = if (paragraph != null) text + "\n\n" + paragraph else text),
                )
            }
        } else {
            previewHost.agreementFrame(text = text, ariaLabel = tr("Vorschau des Vertragstextes"))
        }
    }
    refreshPreviewHook = ::refreshPreview
    refreshPreview()
    form.panel.actionButton(ActionIcon.REFRESH, tr("Vorschau aktualisieren")) { onClick { refreshPreview() } }

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Als Entwurf speichern"), style = ButtonStyle.OUTLINESECONDARY)
    val publishButton = Button(text = tr("Veröffentlichen"), style = ButtonStyle.PRIMARY)

    /** `true` while the editor holds exactly what the stored draft holds (only then the draft is what gets published). */
    fun matchesDraft(): Boolean =
        draft != null &&
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
        previewTimer = window.setTimeout({ refreshPreview() }, PREVIEW_DEBOUNCE_MS)
        syncPublishButton()
    }
    labelField.subscribe { syncPublishButton() }
    validFromField.subscribe { syncPublishButton() }
    form.buttons(primary = publishButton, cancel = saveButton)

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

private fun renderVersionList(
    panel: SimplePanel,
    kind: AgreementTextKind,
    versions: List<AgreementTextVersionSummaryDto>,
) {
    panel.h2(tr("Veröffentlichte Fassungen")) { addCssClass("h5") }
    if (versions.isEmpty()) {
        panel.p(tr("Noch keine eigene Fassung veröffentlicht.")) { addCssClass("text-muted") }
        return
    }
    versions.forEach { version ->
        val row = panel.vPanel(spacing = 4) { addCssClasses("border-bottom pb-2") }
        val textHost = SimplePanel().apply { hide() }
        row.hPanel(spacing = 12) {
            addCssClasses("flex-wrap align-items-center")
            span(formatSystemDateTime(version.publishedAt))
            untrustedSpan(version.versionLabel) { addCssClass("fw-bold") }
            untrustedSpan(version.authorDisplayName)
            hashSpan(version.sha256)
            val viewButton = actionButton(ActionIcon.VIEW, tr("Text anzeigen"), small = true)
            attachVersionTextToggle(button = viewButton, textHost = textHost, kind = kind, versionLabel = version.versionLabel)
        }
        row.add(textHost)
    }
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
