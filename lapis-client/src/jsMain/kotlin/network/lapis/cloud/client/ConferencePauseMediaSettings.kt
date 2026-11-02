package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PauseMediaRules
import network.lapis.cloud.shared.domain.PauseMediaUploadError

/*
 * V1.9.255 -- the ADMIN part "Pause im Live-Stream" of the card "Aufzeichnung & Übertragung": the picture and the music the SERVER sends to the
 * stream targets while the room pause holds the live stream. Two cards, each with a preview (or player) and its upload; nothing here is shown to
 * anybody but an ADMIN, and the server checks it again on every call.
 *
 * A refusal is one of the fixed sentences of [pauseMediaErrorText] (an enum code of the server), never a raw server text. The music label is a
 * file name chosen by a person: it is shown as TEXT only, never as markup.
 */

/** The fixed sentence of a refused upload. */
internal fun pauseMediaErrorText(error: PauseMediaUploadError?): String =
    when (error) {
        PauseMediaUploadError.FILE_TOO_LARGE -> gettext("Die Datei ist zu groß.")
        PauseMediaUploadError.UNSUPPORTED_FORMAT -> gettext("Dieses Dateiformat wird nicht unterstützt.")
        PauseMediaUploadError.TOO_SMALL -> gettext("Das Bild ist zu klein (mindestens 640 × 360 Pixel).")
        PauseMediaUploadError.DIMENSIONS_TOO_LARGE -> gettext("Das Bild ist zu groß (Kantenlänge).")
        PauseMediaUploadError.UNDECODABLE -> gettext("Die Datei konnte nicht gelesen werden.")
        PauseMediaUploadError.TOO_SHORT -> gettext("Die Musik ist zu kurz (mindestens 5 Sekunden).")
        PauseMediaUploadError.TOO_LONG -> gettext("Die Musik ist zu lang (höchstens 10 Minuten).")
        PauseMediaUploadError.HAS_VIDEO -> gettext("Die Datei enthält ein Video. Bitte laden Sie nur eine Audiodatei hoch.")
        PauseMediaUploadError.PROCESSING_UNAVAILABLE ->
            gettext(
                "Auf diesem Server ist ffmpeg nicht verfügbar, die Musik kann nicht verarbeitet werden.",
            )
        PauseMediaUploadError.BUSY -> gettext("Der Server ist gerade ausgelastet. Bitte versuchen Sie es gleich noch einmal.")
        PauseMediaUploadError.RATE_LIMITED -> gettext("Zu viele Uploads – bitte später erneut versuchen.")
        PauseMediaUploadError.FORBIDDEN, PauseMediaUploadError.INVALID_REQUEST, null -> gettext("Hochladen fehlgeschlagen.")
    }

/** `m:ss` of a duration in seconds. */
internal fun formatPauseMusicDuration(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Only hex digits and dashes survive: the version goes into a URL (it is a server-made cache-buster, but nothing is trusted on the way). */
internal fun pauseMediaVersionParam(version: String?): String =
    version?.filter { it.isLetterOrDigit() }.orEmpty().let { if (it.isEmpty()) "0" else it }

/** The two cards below [panel]; [onChanged] re-reads the settings after a change. */
internal fun renderPauseMediaSection(
    panel: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val host = panel.vPanel(spacing = 6) { addCssClasses("border-top pt-2") }
    host.div(tr("Pause im Live-Stream")) { addCssClasses("fw-bold") }
    host.p(
        tr("Nur im Live-Stream zu sehen und zu hören. Nicht in der Aufzeichnung, nicht im Raum."),
        className = "text-muted small",
    )
    renderPauseImageCard(host, dto, onChanged)
    renderPauseMusicCard(host, dto, onChanged)
}

private fun renderPauseImageCard(
    host: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val card = host.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    card.div(tr("Pausenbild")) { addCssClasses("fw-bold") }
    val preview = card.div(className = "lapis-pause-preview")
    preview.image(
        "/api/conference/pause-media/image?v=${pauseMediaVersionParam(dto.pauseImageVersion)}",
        gettext("Vorschau des Pausenbilds"),
    )
    card.div(
        if (dto.pauseImageCustom) tr("Ein eigenes Bild ist hinterlegt.") else tr("Es wird das Standardbild verwendet."),
    ) { addCssClasses("text-muted small") }
    card.div(
        tr(
            "JPEG, PNG oder WebP, mindestens 640 × 360 Pixel, höchstens 10 MB. Das Bild wird nie beschnitten; was nicht ins 16:9-Format passt, bekommt einen ruhigen dunklen Rand.",
        ),
    ) { addCssClasses("text-muted small") }
    renderUploadRow(
        box = card,
        chooser = tr("Bild auswählen"),
        accept = "image/jpeg,image/png,image/webp",
        uploadLabel = tr("Eigenes Bild hochladen"),
        capBytes = PauseMediaRules.IMAGE_MAX_UPLOAD_BYTES,
        removeLabel = if (dto.pauseImageCustom) tr("Entfernen") else null,
        upload = { uploadPauseImage(it) },
        remove = { deletePauseMedia(music = false) },
        uploaded = gettext("Das Pausenbild wurde gespeichert."),
        removed = gettext("Das Standardbild wird wieder verwendet."),
        onChanged = onChanged,
    )
}

private fun renderPauseMusicCard(
    host: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val card = host.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    card.div(tr("Pausenmusik")) { addCssClasses("fw-bold") }
    val label = dto.pauseMusicLabel
    if (label == null) {
        card.div(tr("Keine Musik hinterlegt. Im Stream ist während der Pause nichts zu hören.")) { addCssClasses("text-muted small") }
    } else {
        // the label is a person's file name: shown as text (a plain content, never rich)
        val duration = dto.pauseMusicDurationSeconds?.let { " · ${formatPauseMusicDuration(it)}" }.orEmpty()
        card.div(sanitizeUntrustedI18nText(label) + duration) { addCssClasses("lapis-pause-music-label") }
        val player =
            card.div(
                "<audio controls preload=\"none\" src=\"/api/conference/pause-media/music?v=${pauseMediaVersionParam(
                    dto.pauseMusicVersion,
                )}\"></audio>",
                rich = true,
            )
        player.addCssClass("lapis-pause-music-player")
    }
    card.div(
        tr(
            "MP3, M4A, OGG, FLAC oder WAV, 5 Sekunden bis 10 Minuten, höchstens 15 MB. Die Musik läuft in Endlosschleife und wird auf eine einheitliche Lautstärke gebracht.",
        ),
    ) { addCssClasses("text-muted small") }
    card.div(
        tr(
            "Laden Sie nur Musik hoch, für deren öffentliche Wiedergabe Sie die Rechte haben. Plattformen können Streams mit geschützter Musik stummschalten oder sperren.",
        ),
    ) { addCssClasses("text-muted small") }
    renderUploadRow(
        box = card,
        chooser = tr("Musikdatei auswählen"),
        accept = "audio/mpeg,audio/mp4,audio/x-m4a,audio/ogg,audio/flac,audio/wav,audio/x-wav,.mp3,.m4a,.ogg,.flac,.wav",
        uploadLabel = tr("Musik hochladen"),
        capBytes = PauseMediaRules.MUSIC_MAX_UPLOAD_BYTES,
        removeLabel = if (label != null) tr("Entfernen") else null,
        upload = { uploadPauseMusic(it) },
        remove = { deletePauseMedia(music = true) },
        uploaded = gettext("Die Pausenmusik wurde gespeichert."),
        removed = gettext("Die Pausenmusik wurde entfernt."),
        onChanged = onChanged,
    )
}

private fun renderUploadRow(
    box: SimplePanel,
    chooser: String,
    accept: String,
    uploadLabel: String,
    capBytes: Long,
    removeLabel: String?,
    upload: suspend (org.w3c.files.File) -> PauseMediaOutcome,
    remove: suspend () -> PauseMediaOutcome,
    uploaded: String,
    removed: String,
    onChanged: () -> Unit,
) {
    val form = box.lapisForm()
    val chooserField = form.panel.upload(label = chooser)
    chooserField.input.setAttribute("accept", accept)
    val fileField = form.register(chooserField, label = chooser, required = true, requiredMessage = tr("Bitte eine Datei auswählen."))
    val addButton = newActionButton(ActionIcon.UPLOAD, uploadLabel, ButtonStyle.OUTLINESECONDARY)
    form.buttons(primary = addButton)
    addButton.onClick {
        form.submit(addButton) {
            val file = chooserField.value?.firstOrNull()?.let { chooserField.getNativeFile(it) } ?: return@submit
            if (file.size.toLong() > capBytes) {
                notifyError(pauseMediaErrorText(PauseMediaUploadError.FILE_TOO_LARGE))
                return@submit
            }
            when (val outcome = runCatching { upload(file) }.getOrNull()) {
                is PauseMediaOutcome.Ok -> {
                    notifySuccess(uploaded)
                    onChanged()
                }
                is PauseMediaOutcome.Refused -> notifyError(pauseMediaErrorText(outcome.error))
                null -> notifyError(pauseMediaErrorText(null))
            }
            fileField.reset()
        }
    }
    if (removeLabel != null) {
        val removeButton = newActionButton(ActionIcon.DELETE, removeLabel, ButtonStyle.OUTLINEDANGER)
        box.add(removeButton)
        removeButton.onClick {
            runGuardedAction(removeButton) {
                when (val outcome = runCatching { remove() }.getOrNull()) {
                    is PauseMediaOutcome.Ok -> {
                        notifySuccess(removed)
                        onChanged()
                    }
                    is PauseMediaOutcome.Refused -> notifyError(pauseMediaErrorText(outcome.error))
                    null -> notifyError(pauseMediaErrorText(null))
                }
            }
        }
    }
}
