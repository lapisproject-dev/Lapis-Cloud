package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.text.Text
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.VideoOverlayAdminItemDto
import network.lapis.cloud.shared.domain.VideoOverlayAdminLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayRules
import network.lapis.cloud.shared.domain.VideoOverlayUploadError
import network.lapis.cloud.shared.domain.VideoOverlayUploadResultDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IVideoOverlayService
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.SAME_ORIGIN
import org.w3c.files.File
import org.w3c.xhr.FormData

/*
 * V1.9.129 "Video-Overlays" -- the ADMIN part of the card "Videokonferenz": the switch "Overlays anbieten", the library (thumbnails of the
 * stored elements, rename, order, delete) and the two uploads (logo, frame). Everything is ADMIN-only on the server as well; the screen
 * only does not offer what the server would refuse.
 *
 * A refusal of an upload is one of the fixed sentences below (an enum code of the server), never a raw server text. The thumbnails are plain
 * same-origin `<img>` for the ADMIN (a stored element, delivered authenticated); the members' drawing step never uses an `<img>`.
 */

private var overlaySwitchSerial = 0

private external fun encodeURIComponent(value: String): String

/** The fixed sentence of a refused upload -- only enum codes, never a server text. */
internal fun videoOverlayUploadErrorText(error: VideoOverlayUploadError?): String =
    when (error) {
        VideoOverlayUploadError.FILE_TOO_LARGE -> gettext("Die Datei ist zu groß.")
        VideoOverlayUploadError.UNSUPPORTED_FORMAT -> gettext("Dieses Dateiformat wird nicht unterstützt (nur PNG und JPEG).")
        VideoOverlayUploadError.FRAME_NOT_PNG -> gettext("Ein Rahmen muss eine PNG-Datei mit Transparenz sein.")
        VideoOverlayUploadError.TOO_SMALL -> gettext("Das Bild ist zu klein.")
        VideoOverlayUploadError.DIMENSIONS_TOO_LARGE -> gettext("Das Bild ist zu groß (Kantenlänge).")
        VideoOverlayUploadError.UNDECODABLE -> gettext("Die Bilddatei konnte nicht gelesen werden.")
        VideoOverlayUploadError.FRAME_NEEDS_ALPHA ->
            gettext(
                "Der Rahmen braucht transparente Bereiche, durch die das Kamerabild sichtbar bleibt.",
            )
        VideoOverlayUploadError.FRAME_WRONG_ASPECT -> gettext("Ein Rahmen muss das Seitenverhältnis 16:9 haben.")
        VideoOverlayUploadError.LIBRARY_FULL -> gettext("Die Bibliothek ist voll – bitte zuerst ein Element löschen.")
        VideoOverlayUploadError.INVALID_NAME -> gettext("Der Name muss 1 bis 60 Zeichen lang sein und darf keine Steuerzeichen enthalten.")
        VideoOverlayUploadError.RATE_LIMITED -> gettext("Zu viele Uploads – bitte später erneut versuchen.")
        VideoOverlayUploadError.FORBIDDEN, VideoOverlayUploadError.INVALID_REQUEST, null -> gettext("Hochladen fehlgeschlagen.")
    }

internal sealed interface VideoOverlayUploadOutcome {
    data object Ok : VideoOverlayUploadOutcome

    data class Refused(
        val error: VideoOverlayUploadError?,
    ) : VideoOverlayUploadOutcome
}

/** Same-origin POST of one image; the answer is decoded into the fixed [VideoOverlayUploadError] vocabulary (never shown raw). */
internal suspend fun uploadVideoOverlay(
    kind: VideoOverlayKind,
    name: String,
    file: File,
): VideoOverlayUploadOutcome {
    val form = FormData()
    form.append("file", file, file.name)
    val response =
        window
            .fetch(
                "/api/video-overlays/${kind.name}?name=${encodeURIComponent(name)}",
                RequestInit(method = "POST", body = form, credentials = RequestCredentials.SAME_ORIGIN),
            ).await()
    if (response.ok) return VideoOverlayUploadOutcome.Ok
    val error =
        runCatching {
            Json.decodeFromString(VideoOverlayUploadResultDto.serializer(), response.text().await()).error
        }.getOrNull()
    return VideoOverlayUploadOutcome.Refused(error)
}

/** The member's view of the library; `null` when it cannot be read. Lives here because this file has the state blocks (R34 ledger). */
internal suspend fun loadVideoOverlayLibrary(): VideoOverlayLibraryDto? =
    runCatching {
        rpcService<IVideoOverlayService>().getLibrary()
    }.getOrNull()

/** The switch "Overlays anbieten", same shape as the noise-suppression switch above it. */
internal fun renderVideoOverlaysOfferedSwitch(
    panel: SimplePanel,
    initial: Boolean,
    onChanged: () -> Unit = {},
) {
    val serial = ++overlaySwitchSerial
    val labelId = "lapis-overlay-admin-label-$serial"
    val helpId = "lapis-overlay-admin-help-$serial"
    val line = panel.div(className = "lapis-noise-suppression-line")
    val label = line.span(tr("Overlays anbieten"))
    label.setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", initial.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.setAttribute("aria-describedby", helpId)
    val help =
        panel.p(
            tr(
                "Mitglieder können Logos, ihren Namen und einen Rahmen in ihr eigenes Kamerabild legen. Das veränderte Bild sehen alle Teilnehmer, auch in Aufnahme und Livestream.",
            ),
            className = "text-muted small",
        )
    help.setAttribute("id", helpId)
    switch.onClick {
        val wanted = switch.getAttribute("aria-checked") != "true"
        runGuardedAction(switch) {
            val saved = guarded { rpcService<IOrganizationSettingsService>().updateVideoOverlaysOffered(wanted) }
            if (saved == null) {
                switch.setAttribute("aria-checked", (!wanted).toString())
            } else {
                switch.setAttribute("aria-checked", saved.videoOverlaysOffered.toString())
                notifySuccess(
                    if (saved.videoOverlaysOffered) {
                        gettext("Overlays werden angeboten.")
                    } else {
                        gettext("Overlays werden nicht mehr angeboten.")
                    },
                )
                onChanged()
            }
        }
    }
}

/** The library list and the two uploads, below the switch. [reload] re-reads the library after an upload. */
internal fun renderVideoOverlayLibrary(panel: SimplePanel) {
    val host = panel.vPanel(spacing = 6)
    lateinit var section: DataSection
    section =
        host.dataSection<VideoOverlayAdminLibraryDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IVideoOverlayService>().getAdminLibrary() } },
            render = { body, dto -> renderLibraryBody(body, dto, reload = { section.reload() }) },
        )
    section.reload()
}

private fun renderLibraryBody(
    body: SimplePanel,
    dto: VideoOverlayAdminLibraryDto,
    reload: () -> Unit,
) {
    body.div(tr("Bibliothek der Overlays")) { addCssClasses("fw-bold") }
    val list = body.vPanel(spacing = 4)
    if (dto.items.isEmpty() && !dto.instanceLogoOffered) {
        list.div(tr("Noch keine Logos oder Rahmen hinterlegt.")) { addCssClasses("text-muted small") }
    }
    if (dto.instanceLogoOffered) {
        list.div(tr("Das Logo der Instanz steht allen Mitgliedern als erstes Logo zur Verfügung.")) { addCssClasses("text-muted small") }
    } else if (dto.instanceLogoReason == "svg") {
        list.div(
            tr(
                "Das Logo der Instanz ist eine SVG-Datei und steht deshalb nicht als Overlay zur Verfügung. Laden Sie es bei Bedarf als PNG hoch.",
            ),
        ) { addCssClasses("text-muted small") }
    }
    dto.items.forEachIndexed { index, item ->
        renderLibraryRow(list, item, isFirst = index == 0, isLast = index == dto.items.lastIndex, reload = reload)
    }
    body.div(tr("Logo oder Rahmen hinzufügen")) { addCssClasses("fw-bold mt-2") }
    renderUploadForm(body, kind = VideoOverlayKind.LOGO, reload = reload)
    renderUploadForm(body, kind = VideoOverlayKind.FRAME, reload = reload)
}

private fun renderLibraryRow(
    list: SimplePanel,
    item: VideoOverlayAdminItemDto,
    isFirst: Boolean,
    isLast: Boolean,
    reload: () -> Unit,
) {
    val row = list.hPanel(spacing = 8) { addCssClasses("align-items-center border rounded p-2") }
    row.image(item.item.imagePath, "") { addCssClasses("lapis-overlay-admin-thumb") }
    val text = row.vPanel(spacing = 0) { addCssClasses("flex-grow-1") }
    val nameLine = text.div(sanitizeUntrustedI18nText(item.item.name)) { addCssClasses("fw-bold") }
    text.div(
        "${videoOverlayKindWord(item.item.kind)} · ${item.item.width} × ${item.item.height} px · ${item.sizeBytes / 1024} KB",
    ) { addCssClasses("text-muted small") }
    val actions = row.hPanel(spacing = 4)
    actions.actionButton(ActionIcon.MOVE_UP, gettext("Nach oben"), iconOnly = true).apply {
        disabled = isFirst
        onClick {
            runGuardedAction(this) {
                guarded { rpcService<IVideoOverlayService>().moveItem(item.item.id, up = true) }?.let { reload() }
            }
        }
    }
    actions.actionButton(ActionIcon.MOVE_DOWN, gettext("Nach unten"), iconOnly = true).apply {
        disabled = isLast
        onClick {
            runGuardedAction(this) {
                guarded { rpcService<IVideoOverlayService>().moveItem(item.item.id, up = false) }?.let { reload() }
            }
        }
    }
    actions.actionButton(ActionIcon.EDIT, gettext("Umbenennen"), iconOnly = true).onClick {
        renameInline(nameLine, item, reload)
    }
    actions.actionButton(ActionIcon.DELETE, gettext("Löschen"), style = ButtonStyle.OUTLINEDANGER, iconOnly = true).onClick {
        confirmDialog(
            title = tr("Element löschen?"),
            message = tr("Mitglieder, die dieses Element gewählt haben, sehen danach eine leere Ecke."),
            confirmLabel = tr("Löschen"),
            confirmIcon = ActionIcon.DELETE,
            onConfirm = {
                AppScope.launch {
                    guarded { rpcService<IVideoOverlayService>().deleteItem(item.item.id) }?.let { reload() }
                }
            },
        )
    }
}

private fun videoOverlayKindWord(kind: VideoOverlayKind): String =
    when (kind) {
        VideoOverlayKind.LOGO -> gettext("Logo")
        VideoOverlayKind.FRAME -> gettext("Rahmen")
    }

/** Rename in place: the name becomes a text field with "Speichern" / "Abbrechen"; a refusal keeps the field open. */
private fun renameInline(
    nameLine: io.kvision.html.Div,
    item: VideoOverlayAdminItemDto,
    reload: () -> Unit,
) {
    val parent = nameLine.parent as? SimplePanel ?: return
    val field = Text(value = item.item.name, label = gettext("Name"))
    field.maxlength = VideoOverlayRules.NAME_MAX
    val bar = parent.hPanel(spacing = 4)
    parent.add(field)
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY, small = true)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY, small = true)
    bar.add(save)
    bar.add(cancel)
    cancel.onClick {
        parent.remove(field)
        parent.remove(bar)
    }
    save.onClick {
        val wanted = VideoOverlayRules.normalizeName(field.value.orEmpty())
        if (wanted == null) {
            notifyError(videoOverlayUploadErrorText(VideoOverlayUploadError.INVALID_NAME))
        } else {
            runGuardedAction(save) {
                guarded { rpcService<IVideoOverlayService>().renameItem(item.item.id, wanted) }?.let { reload() }
            }
        }
    }
}

private fun renderUploadForm(
    body: SimplePanel,
    kind: VideoOverlayKind,
    reload: () -> Unit,
) {
    val box = body.vPanel(spacing = 4) { addCssClasses("border-top pt-2") }
    val form = box.lapisForm()
    val nameField =
        form.textField(
            label = if (kind == VideoOverlayKind.LOGO) tr("Name des Logos") else tr("Name des Rahmens"),
            required = true,
            init = { it.maxlength = VideoOverlayRules.NAME_MAX },
        )
    val chooser = if (kind == VideoOverlayKind.LOGO) tr("Logo-Datei auswählen") else tr("Rahmen-Datei auswählen")
    val upload = form.panel.upload(label = chooser)
    upload.input.setAttribute("accept", if (kind == VideoOverlayKind.LOGO) "image/png,image/jpeg" else "image/png")
    val fileField = form.register(upload, label = chooser, required = true, requiredMessage = tr("Bitte eine Datei auswählen."))
    box.div(
        if (kind == VideoOverlayKind.LOGO) {
            tr("PNG mit transparentem Hintergrund oder JPEG, mindestens 256 px an der langen Kante, empfohlen 512 px, höchstens 2 MB.")
        } else {
            tr("PNG mit Transparenz im Format 16:9, 1280×720 bis 1920×1080 Pixel, höchstens 4 MB.")
        },
    ) { addCssClasses("text-muted small") }
    val addButton =
        newActionButton(
            ActionIcon.UPLOAD,
            if (kind ==
                VideoOverlayKind.LOGO
            ) {
                tr("Logo hochladen")
            } else {
                tr("Rahmen hochladen")
            },
            ButtonStyle.OUTLINESECONDARY,
        )
    form.buttons(primary = addButton)
    addButton.onClick {
        form.submit(addButton) {
            val file = upload.value?.firstOrNull()?.let { upload.getNativeFile(it) } ?: return@submit
            val name = VideoOverlayRules.normalizeName(nameField.value)
            if (name == null) {
                notifyError(videoOverlayUploadErrorText(VideoOverlayUploadError.INVALID_NAME))
                return@submit
            }
            val cap =
                if (kind ==
                    VideoOverlayKind.LOGO
                ) {
                    VideoOverlayRules.LOGO_MAX_UPLOAD_BYTES
                } else {
                    VideoOverlayRules.FRAME_MAX_UPLOAD_BYTES
                }
            if (file.size.toLong() > cap) {
                notifyError(videoOverlayUploadErrorText(VideoOverlayUploadError.FILE_TOO_LARGE))
                return@submit
            }
            when (val outcome = runCatching { uploadVideoOverlay(kind = kind, name = name, file = file) }.getOrNull()) {
                VideoOverlayUploadOutcome.Ok -> {
                    notifySuccess(gettext("Element wurde hinzugefügt."))
                    reload()
                }
                is VideoOverlayUploadOutcome.Refused -> notifyError(videoOverlayUploadErrorText(outcome.error))
                null -> notifyError(videoOverlayUploadErrorText(null))
            }
            fileField.reset()
        }
    }
}
