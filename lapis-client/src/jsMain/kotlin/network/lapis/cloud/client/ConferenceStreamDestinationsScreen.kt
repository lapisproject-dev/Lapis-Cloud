package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.rpc.IConferenceStreamingService

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- the ADMIN-only Stream-Ziele
 * (destination) CRUD screen. Ran the same mandatory UI/UX-Design-Team review as `ConferenceScreen.kt`'s
 * own Wave 3 section (root `CLAUDE.md`) -- see that file's KDoc for the full cross-file D-item list;
 * this file's own load-bearing decisions:
 *
 * - **D1 (Kare/Atkinson/Jobs "never relaxed") -- the stream key is write-only, exactly like a
 *   password field, and STAYS that way.** [renderDestinationEditModal]'s key field is `password`,
 *   ALWAYS starts empty with the literal placeholder text "unverändert lassen" (never prefilled --
 *   [ConferenceStreamDestinationDto.streamKeyMask] is always the constant `"********"`, never real
 *   key material, see that field's own KDoc), and carries a small lock icon (Font Awesome `fa-lock`, `aria-hidden`; the guideline forbids emoji) next to it -- the
 *   one non-technical visual cue that this field behaves differently from every other text field on
 *   the page. On save, [renderDestinationEditModal] shows a real, VISIBLE re-masking confirmation
 *   ("Gespeichert -- Schlüssel wird nicht erneut angezeigt.") rather than silently closing the modal,
 *   so the admin SEES the secret leave their control (Atkinson's "gulf of evaluation" fix) -- the
 *   modal only closes a beat after that message is shown, via `onSaved` running after a brief pause
 *   is deliberately NOT implemented as an artificial timeout here; instead the confirmation renders
 *   inline in the modal body and the admin closes it themselves via "Fertig", so there is no
 *   race between "did it save?" and the modal disappearing.
 * - **D9 (Zhuo/Rams) -- the list view is plain CRUD, no unearned decoration**: `label`, a small
 *   PLATFORM-TYPE glyph (never a trademarked logo, see [conferenceStreamPlatformGlyph] -- brand risk),
 *   `enabled`, and `streamKeySetAt` ("Schlüssel gesetzt am …") so an admin managing several
 *   destinations over months can tell a stale/never-rotated destination from a fresh one WITHOUT
 *   opening edit mode. [ConferenceStreamDestinationDto.rtmpUrl]/`createdByDisplayName` are shown too
 *   (ADMIN-only view, non-secret) but the masked key itself never appears outside edit mode either.
 *
 * **Never returns/reads the plaintext stream key anywhere** -- there is no RPC method on
 * [IConferenceStreamingService] whose return type CAN carry it (see that interface's own KDoc
 * "credential storage model"); this screen simply has nothing to display even if it wanted to.
 *
 * **Role gating**: every method this screen calls (`listDestinations`/`createDestination`/
 * `updateDestination`/`setDestinationEnabled`/`deleteDestination`) is ADMIN-only server-side (see
 * [IConferenceStreamingService] KDoc "Why ADMIN-only for destination CRUD") -- `Routing.kt` gates the
 * whole route at ADMIN, mirroring [Routes.BACKUP]'s own precedent (the first ADMIN-only route in this
 * client), so there is no narrower in-screen role split to make, unlike `CostCentersScreen.kt`'s own
 * `canManage`.
 */
fun renderConferenceStreamDestinationsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Stream-Ziele"))
    root.div(
        tr(
            "Verwalten Sie die externen RTMP-Ziele (YouTube, Twitch, PeerTube, Owncast, generisches RTMP), " +
                "zu denen Moderatorinnen und Moderatoren eine Besprechung live übertragen können. Der " +
                "Stream-Schlüssel wird verschlüsselt gespeichert und nach dem Speichern nie wieder angezeigt.",
        ),
    ) { addCssClasses("text-muted small") }

    val listPanel = root.vPanel(spacing = 8)

    fun refreshList() {
        listPanel.removeAll()
        listPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val destinations = guarded { rpcService<IConferenceStreamingService>().listDestinations() } ?: return@launch
            listPanel.removeAll()
            if (destinations.isEmpty()) {
                listPanel.div(tr("Noch keine Stream-Ziele angelegt.")) { addCssClasses("text-muted small") }
            } else {
                destinations
                    .sortedBy { it.label }
                    .forEach { destination -> renderDestinationRow(listPanel, destination, ::refreshList) }
            }
        }
    }

    root.h2(tr("Neues Stream-Ziel anlegen")) { addCssClass("h5") }
    renderDestinationCreateForm(root, ::refreshList)

    refreshList()
}

// ================================================================================================
// List row + create/edit forms
// ================================================================================================

private fun renderDestinationRow(
    panel: SimplePanel,
    destination: ConferenceStreamDestinationDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    // W5: a generic Font Awesome icon per platform TYPE (decorative, `aria-hidden`; the platform name is the badge below).
    headerRow.icon(conferenceStreamPlatformIconClass(destination.platform)) {
        addCssClass("text-muted")
        setAttribute("aria-hidden", "true")
    }
    headerRow.untrustedCardTitle(destination.label)
    headerRow.typeBadge(conferenceStreamPlatformLabel(destination.platform), "secondary")
    headerRow.activeStatusBadge(destination.enabled)

    row.untrustedDiv(destination.rtmpUrl, className = "text-muted small")
    row.div(
        gettext(
            "Schlüssel gesetzt am %1 von %2",
            conferenceStreamDestinationDateLabel(destination.streamKeySetAt),
            destination.createdByDisplayName,
        ),
    ) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val editButton = actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
    editButton.onClick { renderDestinationEditModal(destination, onChanged) }

    val toggleButton =
        actionRow.button(
            if (destination.enabled) tr("Deaktivieren") else tr("Aktivieren"),
            style = if (destination.enabled) ButtonStyle.OUTLINESECONDARY else ButtonStyle.OUTLINESUCCESS,
        )
    toggleButton.onClick {
        toggleButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IConferenceStreamingService>().setDestinationEnabled(destination.id, !destination.enabled)
                }
            toggleButton.disabled = false
            if (result != null) {
                notifySuccess(if (result.enabled) tr("Stream-Ziel aktiviert.") else tr("Stream-Ziel deaktiviert."))
                onChanged()
            }
        }
    }

    val deleteButton = actionRow.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        confirmDialog(
            title = tr("Stream-Ziel löschen"),
            message =
                gettext(
                    "\"%1\" wirklich löschen? Dies ist nur möglich, solange kein aktiver oder unterbrochener " +
                        "Live-Stream dieses Ziel verwendet.",
                    destination.label,
                ),
            confirmLabel = tr("Löschen"),
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().deleteDestination(destination.id) }
                if (result == true) {
                    notifyInfo(gettext("Stream-Ziel \"%1\" wurde gelöscht.", destination.label))
                    onChanged()
                }
            }
        }
    }
}

/**
 * Client-side non-blank/URL-scheme pre-check only (mirrors `Validation`'s own "loose mirror, not the
 * security boundary" posture) -- the server remains the authority on a duplicate label
 * ([ConflictException][network.lapis.cloud.shared.rpc.ConflictException]) or malformed URL.
 */
private fun renderDestinationCreateForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val card = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    // Formular-Grammatik (V1.4.28): vier Felder, alle Pflicht => Legende "Alle Felder sind Pflichtfelder." (Fall b).
    val form = card.lapisForm()
    val labelField = form.textField(label = tr("Bezeichnung (z. B. \"PdV YouTube-Kanal\")"), required = true)
    val platformOptions = ConferenceStreamPlatform.entries.map { it.name to conferenceStreamPlatformLabel(it) }
    val platformField =
        form.selectField(
            label = tr("Plattform"),
            options = platformOptions,
            value = ConferenceStreamPlatform.GENERIC_RTMP.name,
            required = true,
        )
    val platformSelect = platformField.control as Select
    val urlField =
        form.textField(
            label = tr("RTMP-Basis-URL"),
            required = true,
            rule = { rtmpUrlCheck(it) },
        )
    val hintLine = form.panel.div { addCssClasses("text-muted small") }

    // D1: the lock glyph is the one non-technical cue this field behaves differently from every other text field on the
    // page (Kare). Der Schlüssel ist ein Geheimnis: keine Passwortmanager-Angebote, kein Aufdecken (er soll den Bildschirm
    // nicht mehr als nötig sehen).
    val keyField =
        form.passwordField(
            label = tr("Stream-Schlüssel"),
            required = true,
            suppressManagers = true,
            actions = { it.lockIcon() },
        )
    val createButton = Button(tr("Stream-Ziel anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)

    fun applyPlatformDefaults() {
        val platform = ConferenceStreamPlatform.valueOf(platformSelect.value ?: ConferenceStreamPlatform.GENERIC_RTMP.name)
        conferenceStreamPresetUrl(platform)?.let { preset ->
            if (urlField.value.isBlank()) {
                // Über das LapisField, nicht am Control vorbei: sonst bliebe ein schon gezeigter Feldfehler (und
                // aria-invalid) an der nun gültigen URL stehen.
                urlField.setValue(preset)
                urlField.validate(force = false)
            }
        }
        hintLine.content = conferenceStreamPlatformHint(platform).orEmpty()
    }
    platformSelect.subscribe { applyPlatformDefaults() }
    applyPlatformDefaults()

    createButton.onClick {
        form.submit(createButton) {
            val label = labelField.value.trim()
            val platform = ConferenceStreamPlatform.valueOf(platformSelect.value ?: ConferenceStreamPlatform.GENERIC_RTMP.name)
            val result =
                guarded {
                    rpcService<IConferenceStreamingService>().createDestination(label, platform, urlField.value.trim(), keyField.value)
                }
            if (result != null) {
                notifySuccess(gettext("Stream-Ziel \"%1\" wurde angelegt.", label))
                labelField.reset()
                urlField.reset()
                keyField.reset()
                onCreated()
            }
        }
    }
}

/** Feldregel der RTMP-URL (lose gespiegelt, der Server bleibt Autorität). */
private fun rtmpUrlCheck(url: String): FieldCheck =
    if (conferenceStreamUrlLooksValid(url)) {
        FieldCheck.Ok
    } else {
        FieldCheck.Invalid(gettext("Bitte eine gültige RTMP-URL (rtmp:// oder rtmps://) angeben."))
    }

/**
 * D1 -- the key field starts EMPTY with placeholder "unverändert lassen" (never prefilled with
 * anything, not even the mask), and on a successful save shows a real, visible re-masking
 * confirmation line INSIDE the modal before the admin dismisses it themselves ("Fertig") -- so the
 * transition from "I just typed a secret" to "it is gone from my screen" is legible, not a silent
 * reset that reads as "did it save?" (Atkinson's gulf-of-evaluation fix).
 */
private fun renderDestinationEditModal(
    destination: ConferenceStreamDestinationDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = tr("Stream-Ziel bearbeiten"))
    // Formular-Grammatik (V1.4.28): Bezeichnung und URL Pflicht, der neue Schlüssel optional => Fall (a).
    val form = modal.lapisForm()
    val labelField = form.textField(label = tr("Bezeichnung"), value = destination.label, required = true)
    val urlField =
        form.textField(
            label = tr("RTMP-Basis-URL"),
            value = destination.rtmpUrl,
            required = true,
            rule = { rtmpUrlCheck(it) },
        )
    val keyField =
        form.passwordField(
            label = tr("Neuer Stream-Schlüssel (leer lassen = unverändert lassen)"),
            suppressManagers = true,
            actions = { it.lockIcon() },
        )
    form.panel.div(gettext("Aktueller Schlüssel: %1 -- wird hier nie im Klartext angezeigt.", destination.streamKeyMask)) {
        addCssClasses("text-muted small")
    }
    val savedBox =
        form.panel.div().apply {
            addCssClasses("text-success fw-bold")
            hide()
        }
    form.finish()

    // Fußleiste: die Verwerfer stehen IMMER links der Primäraktion (R27). Vor dem ersten erfolgreichen Speichern ist das
    // "Abbrechen"; danach wird es durch "Fertig" ersetzt -- die Re-Maskierungs-Quittung (D1) bleibt erhalten.
    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val doneButton = Button(tr("Fertig"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    doneButton.hide()
    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        form.submit(saveButton) {
            val newKey = keyField.value.takeIf { it.isNotEmpty() }
            val result =
                guarded {
                    rpcService<IConferenceStreamingService>()
                        .updateDestination(destination.id, labelField.value.trim(), urlField.value.trim(), newKey)
                }
            if (result != null) {
                // D1: re-masks visibly INSTEAD OF silently -- the key field itself clears and this confirmation line appears,
                // proving to the admin the secret left their control, before the modal is dismissed (by the admin's own
                // "Fertig" click).
                keyField.reset()
                savedBox.content = tr("Gespeichert -- Schlüssel wird nicht erneut angezeigt.")
                savedBox.show()
                cancelButton.hide()
                doneButton.show()
                onChanged()
            }
        }
    }

    modal.addButton(cancelButton)
    modal.addButton(doneButton)
    modal.addButton(saveButton)
    modal.show()
}

// ================================================================================================
// Pure, DOM-independent logic -- see [ConferenceStreamingUiTest] for coverage (no rendering harness
// exists in this module, same posture every other screen's own pure-helper section documents).
// ================================================================================================

/** German platform labels -- used both here (create/edit forms, list badges) and by
 * `ConferenceScreen.kt`'s own `startStreamDialog` (moderator-facing destination checklist),
 * `internal` so it is shared across this package without duplication. */
internal fun conferenceStreamPlatformLabel(platform: ConferenceStreamPlatform): String =
    when (platform) {
        ConferenceStreamPlatform.YOUTUBE -> gettext("YouTube Live")
        ConferenceStreamPlatform.TWITCH -> gettext("Twitch")
        ConferenceStreamPlatform.PEERTUBE -> gettext("PeerTube")
        ConferenceStreamPlatform.OWNCAST -> gettext("Owncast")
        ConferenceStreamPlatform.GENERIC_RTMP -> gettext("Generisches RTMP")
    }

/** D9 (Zhuo/Rams) -- generic PLATFORM-TYPE iconography, deliberately NOT a trademarked platform
 * logo (brand risk, per the design review's own note). A single, distinguishable Font Awesome icon per type
 * (W5: was a text glyph/emoji per type). */
internal fun conferenceStreamPlatformIconClass(platform: ConferenceStreamPlatform): String =
    when (platform) {
        ConferenceStreamPlatform.YOUTUBE -> "fas fa-play"
        ConferenceStreamPlatform.TWITCH -> "fas fa-gamepad"
        ConferenceStreamPlatform.PEERTUBE -> "fas fa-share-nodes"
        ConferenceStreamPlatform.OWNCAST -> "fas fa-tower-broadcast"
        ConferenceStreamPlatform.GENERIC_RTMP -> "fas fa-gem"
    }

/** Pure UX/validation metadata only -- the SERVER has zero platform-specific code paths (every
 * platform is `<rtmpUrl>/<streamKey>` built from the same two stored columns, see
 * [ConferenceStreamDestinationDto] KDoc). `YOUTUBE`/`TWITCH` have a single, stable, global ingest
 * endpoint worth prefilling; `PEERTUBE`/`OWNCAST`/`GENERIC_RTMP` do not (self-hosted or genuinely
 * arbitrary), see [conferenceStreamPlatformHint] for their hint text instead. */
internal fun conferenceStreamPresetUrl(platform: ConferenceStreamPlatform): String? =
    when (platform) {
        ConferenceStreamPlatform.YOUTUBE -> "rtmp://a.rtmp.youtube.com/live2"
        ConferenceStreamPlatform.TWITCH -> "rtmp://live.twitch.tv/app"
        ConferenceStreamPlatform.PEERTUBE, ConferenceStreamPlatform.OWNCAST, ConferenceStreamPlatform.GENERIC_RTMP -> null
    }

internal fun conferenceStreamPlatformHint(platform: ConferenceStreamPlatform): String? =
    when (platform) {
        ConferenceStreamPlatform.PEERTUBE -> tr("RTMP-URL Ihrer PeerTube-Instanz, z. B. rtmp://peertube.example.org:1935/live")
        ConferenceStreamPlatform.OWNCAST -> tr("RTMP-URL Ihrer Owncast-Instanz, z. B. rtmp://owncast.example.org:1935/live")
        ConferenceStreamPlatform.GENERIC_RTMP -> tr("RTMP-Ziel-URL, z. B. für Facebook Live, LinkedIn Live oder Vimeo")
        ConferenceStreamPlatform.YOUTUBE, ConferenceStreamPlatform.TWITCH -> null
    }

/** Client-side UX pre-check only, mirroring the server's own scheme validation
 * (`ConferenceStreamingService`: `rtmp://`/`rtmps://` with a parseable host, deliberately NOT run
 * through the SSRF private-range blocklist -- see [IConferenceStreamingService.createDestination]
 * KDoc) -- never the security boundary itself, same "Validation.kt" posture this whole client
 * follows. */
internal fun conferenceStreamUrlLooksValid(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("rtmp://") || trimmed.startsWith("rtmps://")
}

/** D9's "Schlüssel gesetzt am …" list-row copy -- zero-padded date, no time component (the exact
 * minute is not operationally interesting for this field, unlike the in-call "gestartet um HH:MM"
 * labels, which are). */
@Suppress("DEPRECATION")
internal fun conferenceStreamDestinationDateLabel(dateTime: LocalDateTime): String {
    val year = dateTime.year.toString().padStart(4, '0')
    val month = dateTime.monthNumber.toString().padStart(2, '0')
    val day = dateTime.dayOfMonth.toString().padStart(2, '0')
    return "$year-$month-$day"
}
