package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.image
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoUploadError
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.OwnMemberPhotoDto
import network.lapis.cloud.shared.rpc.IMemberPhotoService
import network.lapis.cloud.shared.rpc.MemberPhotoConsentOutdatedException
import org.w3c.files.File

/**
 * The EXACT consent wording shown before a photo is published, version
 * [MemberPhotoRules.CONSENT_TEXT_VERSION]. **A change of this text REQUIRES a new version** -- the
 * server stores the version with every consent and only accepts the current one.
 * `MemberPhotoConsentTextPinTest` pins the SHA-256 of this literal to that version and fails the
 * build if the wording changes without a new version.
 */
internal const val MEMBER_PHOTO_CONSENT_TEXT =
    "Ihr Foto wird auf öffentlichen Webseiten dieser Organisation angezeigt, zum Beispiel in Vorstands- oder Mitgliederübersichten. Sie können die Freigabe jederzeit widerrufen; das Foto ist dann sofort nicht mehr abrufbar. Kopien, die Dritte bereits angefertigt haben, können wir nicht zurückholen."

/** The RPC surface [MemberPhotoCard] needs -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberPhotoRpc {
    suspend fun getOwnPhoto(): OwnMemberPhotoDto

    suspend fun setOwnPhotoVisibility(
        visibility: MemberPhotoVisibility,
        consentTextVersion: String?,
    ): OwnMemberPhotoDto

    suspend fun deleteOwnPhoto(): OwnMemberPhotoDto
}

internal fun liveMemberPhotoRpc(): MemberPhotoRpc =
    object : MemberPhotoRpc {
        override suspend fun getOwnPhoto() = rpcService<IMemberPhotoService>().getOwnPhoto()

        override suspend fun setOwnPhotoVisibility(
            visibility: MemberPhotoVisibility,
            consentTextVersion: String?,
        ) = rpcService<IMemberPhotoService>().setOwnPhotoVisibility(visibility, consentTextVersion)

        override suspend fun deleteOwnPhoto() = rpcService<IMemberPhotoService>().deleteOwnPhoto()
    }

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- the self-service card on "Meine Daten" (`DsgvoRightsScreen`):
 * preview, upload/replace, remove, and the consent-gated "show publicly" switch.
 *
 * **The server state is the only truth.** Every state change renders from the DTO the server
 * answered with; the switch is reset to that state BEFORE a consent dialog opens and is never
 * flipped optimistically. Enabling publication opens a confirmation dialog carrying the exact
 * consent wording ([MEMBER_PHOTO_CONSENT_TEXT]); only its confirmation calls the server, with the
 * server-announced consent version. Disabling needs no dialog (withdrawal must be one click).
 *
 * Upload errors are shown as fixed, translated sentences chosen by an enum code
 * ([memberPhotoUploadErrorMessage]) -- server free text never reaches the screen. While a request
 * runs, every control is disabled ([busy]).
 *
 * File selection uses the registered `upload` field of the form grammar, like `DocumentsScreen`:
 * no hand-built `<input type=file>`.
 */
internal class MemberPhotoCard(
    parent: Container,
    private val eligible: Boolean,
    private val rpc: MemberPhotoRpc,
    private val uploader: suspend (File, (Double) -> Unit) -> MemberPhotoHttp.Result = { file, progress ->
        MemberPhotoHttp.upload(file, progress)
    },
    private val confirm: (title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) -> Unit =
        { title, message, confirmLabel, onConfirm ->
            confirmDialog(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                confirmStyle = ButtonStyle.PRIMARY,
                onConfirm = onConfirm,
            )
        },
) {
    internal val root: SimplePanel = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }

    // Declaration order IS the visual order: preview, hints, upload form, remove, switch, link, status.
    private val previewBox: Div = root.div { addCssClasses("member-photo-preview rounded-3 border") }
    private val emptyHint: Div = root.div(tr("Noch kein Foto hinterlegt.")) { addCssClasses("text-muted small") }
    private val notEligibleHint: Div = root.div(tr("Nur Mitglieder können ein Foto hinterlegen.")) { addCssClasses("text-muted small") }
    private val limitsHint: Div =
        root.div(tr("JPEG oder PNG, mindestens 400 × 400 Pixel, höchstens 10 MB.")) {
            addCssClasses("text-muted small")
        }

    internal val uploadButton: Button = Button(tr("Foto hochladen"), icon = "fas fa-upload", style = ButtonStyle.OUTLINESECONDARY)
    internal val removeButton: Button = Button(tr("Foto entfernen"), icon = "fas fa-trash", style = ButtonStyle.OUTLINEDANGER)

    private val uploadForm = root.lapisForm()
    private val fileUpload = uploadForm.panel.upload(label = tr("Foto auswählen"))
    private val fileField =
        uploadForm.register(
            fileUpload,
            label = tr("Foto auswählen"),
            required = true,
            requiredMessage = tr("Bitte eine Datei auswählen."),
        )

    init {
        uploadForm.buttons(primary = uploadButton)
    }

    init {
        root.add(removeButton)
    }

    internal val publicSwitch: CheckBox =
        CheckBox(value = false, label = tr("Öffentlich auf Webseiten dieser Organisation anzeigen")).also { root.add(it) }
    private val publicLinkHost: Div = root.div()
    private val privateHint: Div =
        root.div(tr("Ihr Foto ist privat. Andere Mitglieder und der Vorstand sehen es nicht.")) {
            addCssClasses("text-muted small")
        }
    private val freshPhotoHint: Div =
        root.div(tr("Ein neues Foto ist zunächst privat. Geben Sie es bei Bedarf erneut frei.")) { addCssClasses("text-muted small") }
    private val statusLine: Div = root.div("") { addCssClasses("text-muted small") }
    private val errorBox: Div = root.div("") { addCssClasses("alert alert-danger small mb-0") }

    private var state: OwnMemberPhotoDto? = null
    private var busy = false
    private var syncingSwitch = false

    init {
        statusLine.setAttribute("role", "status")
        errorBox.setAttribute("role", "alert")

        uploadButton.onClick {
            uploadForm.submit(uploadButton) {
                val nativeFile = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) } ?: return@submit
                handleUpload(nativeFile)
                fileField.reset()
            }
        }
        removeButton.onClick { requestRemove() }
        publicSwitch.subscribe { checked ->
            // KVision notifies subscribers ALSO for programmatic `value =` changes, and does so after our own
            // `syncingSwitch` window has closed -- so a notification that merely echoes the server state we just
            // rendered must never be mistaken for a click. A real click always DIFFERS from the server state.
            val serverPublic = state?.visibility == MemberPhotoVisibility.PUBLIC
            if (syncingSwitch || checked == serverPublic) return@subscribe
            onSwitchToggled(checked)
        }
        errorBox.hide()
        statusLine.hide()
        publicLinkHost.hide()
        render(null)
    }

    /** Fetches the current state from the server and renders it. A failed read leaves the previous rendering in place. */
    suspend fun load() {
        val dto = guarded { rpc.getOwnPhoto() } ?: return
        render(dto)
    }

    /**
     * Validates the picked [file] client-side (courtesy only -- the server re-checks everything),
     * uploads it and re-reads the state. Errors become fixed sentences, never server text.
     */
    internal suspend fun handleUpload(file: File) {
        if (busy) return
        clearError()
        val preError = clientPreCheck(file)
        if (preError != null) {
            showError(preError)
            return
        }
        setBusy(true)
        statusLine.content = tr("Foto wird hochgeladen …")
        statusLine.show()
        try {
            when (val result = uploader(file) { }) {
                MemberPhotoHttp.Result.Ok -> Unit
                is MemberPhotoHttp.Result.Error -> showError(result.code)
            }
        } finally {
            statusLine.hide()
            setBusy(false)
        }
        load()
    }

    private fun clientPreCheck(file: File): MemberPhotoUploadError? {
        // An empty type (e.g. HEIC on many systems) counts as the wrong format.
        if (file.type !in MemberPhotoRules.ACCEPTED_MIME_TYPES) return MemberPhotoUploadError.UNSUPPORTED_FORMAT
        if (file.size.toDouble() > MemberPhotoRules.MAX_UPLOAD_BYTES) return MemberPhotoUploadError.FILE_TOO_LARGE
        return null
    }

    private fun onSwitchToggled(checked: Boolean) {
        if (busy) {
            resetSwitchToServerState()
            return
        }
        if (checked) {
            // Never flip optimistically: show the server state again first, publish only after the dialog.
            resetSwitchToServerState()
            confirm(
                tr("Foto veröffentlichen"),
                tr(MEMBER_PHOTO_CONSENT_TEXT),
                tr("Veröffentlichen"),
            ) { publish() }
        } else {
            unpublish()
        }
    }

    private fun publish() {
        val required = state?.requiredConsentTextVersion ?: MemberPhotoRules.CONSENT_TEXT_VERSION
        runGuardedAction(button = null) {
            applyVisibility(MemberPhotoVisibility.PUBLIC, required)
        }
    }

    private fun unpublish() {
        runGuardedAction(button = null) {
            applyVisibility(MemberPhotoVisibility.PRIVATE, null)
        }
    }

    /** Calls the server and renders ONLY its answer. A stale consent text reloads and asks for a new confirmation. */
    internal suspend fun applyVisibility(
        visibility: MemberPhotoVisibility,
        consentTextVersion: String?,
    ) {
        if (busy) return
        setBusy(true)
        try {
            val dto =
                try {
                    rpc.setOwnPhotoVisibility(visibility, consentTextVersion)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MemberPhotoConsentOutdatedException) {
                    notifyError(tr("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen."))
                    load()
                    return
                } catch (e: Throwable) {
                    guarded<Unit> { throw e }
                    resetSwitchToServerState()
                    return
                }
            render(dto)
        } finally {
            setBusy(false)
        }
    }

    private fun requestRemove() {
        if (busy) return
        confirm(
            tr("Foto entfernen"),
            tr("Ihr Foto wird endgültig gelöscht."),
            tr("Entfernen"),
        ) { runGuardedAction(button = null) { performRemove() } }
    }

    internal suspend fun performRemove() {
        if (busy) return
        setBusy(true)
        try {
            val dto =
                try {
                    rpc.deleteOwnPhoto()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    guarded<Unit> { throw e }
                    return
                }
            clearError()
            render(dto)
        } finally {
            setBusy(false)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        applyEnabled()
    }

    private fun applyEnabled() {
        val hasPhoto = state?.hasPhoto == true
        uploadButton.disabled = busy || !eligible
        removeButton.disabled = busy
        publicSwitch.disabled = busy || !hasPhoto || !eligible
    }

    private fun resetSwitchToServerState() {
        syncingSwitch = true
        try {
            publicSwitch.value = state?.visibility == MemberPhotoVisibility.PUBLIC
        } finally {
            syncingSwitch = false
        }
    }

    private fun showError(code: MemberPhotoUploadError) {
        errorBox.content = memberPhotoUploadErrorMessage(code)
        errorBox.show()
    }

    private fun clearError() {
        errorBox.content = ""
        errorBox.hide()
    }

    /** DIE einzige Stelle, die den Kartenzustand aus einem Server-DTO ableitet. */
    internal fun render(dto: OwnMemberPhotoDto?) {
        state = dto
        val hasPhoto = dto?.hasPhoto == true
        val isPublic = dto?.visibility == MemberPhotoVisibility.PUBLIC

        previewBox.removeAll()
        if (hasPhoto) {
            previewBox.image("/api/member-photo/own?v=${dto?.previewVersion.orEmpty()}", gettext("Ihr Mitgliedsfoto"))
        } else {
            previewBox.icon("fas fa-user").setAttribute("aria-hidden", "true")
        }
        emptyHint.visible = !hasPhoto && eligible
        notEligibleHint.visible = !eligible
        limitsHint.visible = eligible
        privateHint.visible = hasPhoto && !isPublic
        freshPhotoHint.visible = hasPhoto
        removeButton.visible = hasPhoto
        uploadButton.text = if (hasPhoto) tr("Foto ersetzen") else tr("Foto hochladen")
        uploadForm.panel.visible = eligible
        publicSwitch.visible = eligible

        publicLinkHost.removeAll()
        val publicUrl = dto?.publicUrl
        if (isPublic && publicUrl != null) {
            publicLinkHost
                .link(tr("Öffentliche Ansicht öffnen"), url = publicUrl, target = "_blank")
                .setAttribute("rel", "noopener noreferrer")
            publicLinkHost.show()
        } else {
            publicLinkHost.hide()
        }
        resetSwitchToServerState()
        applyEnabled()
    }
}

/**
 * Mounts the card into [container]. The initial read is a [dataSection]: loading text, and on failure the shared error state
 * with "Erneut versuchen" instead of a card that silently shows "no photo".
 */
internal fun renderMemberPhotoSection(container: SimplePanel) {
    val eligible = AppState.session?.status?.let { it in network.lapis.cloud.shared.domain.MemberStatusSets.MEMBER_PHOTO_ELIGIBLE } ?: false
    val rpc = liveMemberPhotoRpc()
    container
        .dataSection<OwnMemberPhotoDto>(
            isEmpty = { false },
            load = { guarded { rpc.getOwnPhoto() } },
            render = { panel, dto -> MemberPhotoCard(parent = panel, eligible = eligible, rpc = rpc).render(dto) },
        ).reload()
}
