package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.OwnPublicProfileDto
import network.lapis.cloud.shared.domain.PublicListingPlace
import network.lapis.cloud.shared.domain.PublicRankingConsentStateDto
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.PublicTextRules
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.IMemberPublicProfileService
import network.lapis.cloud.shared.rpc.MemberPublicBioConsentOutdatedException

/**
 * The EXACT consent wording shown before the short introduction is published, version
 * [MemberPublicBioRules.CONSENT_TEXT_VERSION]. **A change of this text REQUIRES a new version** --
 * the server stores the version with every consent and treats a consent under an older version as
 * not effective. `MemberPublicBioConsentTextPinTest` pins the SHA-256 of this literal to that
 * version and fails the build if the wording changes without a new version.
 */
internal const val MEMBER_PUBLIC_BIO_CONSENT_TEXT =
    "Ihre Kurzvorstellung wird auf den öffentlichen Webseiten dieser Organisation angezeigt, zum Beispiel auf den Seiten Vorstand und Politiker sowie im Einbettungs-Feed der Website. Sie können die Freigabe jederzeit widerrufen; der Text ist dann mit der nächsten Abfrage nicht mehr abrufbar. Kopien, die Dritte bereits angefertigt haben, können wir nicht zurückholen."

/** The RPC surface [MemberPublicProfileCard] needs -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberPublicProfileRpc {
    suspend fun getOwnPublicProfile(): OwnPublicProfileDto

    suspend fun saveOwnBio(text: String): OwnPublicProfileDto

    suspend fun setOwnBioPublic(
        visible: Boolean,
        consentTextVersion: String?,
    ): OwnPublicProfileDto
}

internal fun liveMemberPublicProfileRpc(): MemberPublicProfileRpc =
    object : MemberPublicProfileRpc {
        override suspend fun getOwnPublicProfile() = rpcService<IMemberPublicProfileService>().getOwnPublicProfile()

        override suspend fun saveOwnBio(text: String) = rpcService<IMemberPublicProfileService>().saveOwnBio(text)

        override suspend fun setOwnBioPublic(
            visible: Boolean,
            consentTextVersion: String?,
        ) = rpcService<IMemberPublicProfileService>().setOwnBioPublic(visible, consentTextVersion)
    }

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the self-service card "Mein öffentliches Profil" on "Meine
 * Daten": the short introduction (edit, live counter and preview, consent-gated publication) and,
 * for an appointed politician, the listing consent.
 *
 * - **The server state is the only truth.** Every state change renders from the DTO the server
 *   answered with; the switch is reset to that state BEFORE the consent dialog opens and is never
 *   flipped optimistically. Enabling publication opens a confirmation dialog carrying the exact
 *   wording ([MEMBER_PUBLIC_BIO_CONSENT_TEXT]); only its confirmation calls the server, with the
 *   server-announced version. Disabling needs no dialog (withdrawal must be one click).
 * - **Nothing is rendered for a member who is neither eligible nor holds a stored text** -- no
 *   empty card, no hint about a feature they cannot use.
 * - **The counter counts Unicode code points** ([PublicTextRules.codePointCount]), exactly like the
 *   server -- never `String.length`.
 * - **All user text goes through `untrusted*` helpers**; the preview is built from the SAME CSS
 *   classes as the public page.
 */
internal class MemberPublicProfileCard(
    parent: Container,
    private val rpc: MemberPublicProfileRpc,
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
    /** Loads the politician listing consent state -- injectable so DOM tests never touch `IDsgvoService`. */
    private val loadListingState: suspend () -> PublicRankingConsentStateDto? = {
        guarded { rpcService<IDsgvoService>().getPublicRankingConsents() }?.firstOrNull { it.kind == PublicRankingKind.POLITICIAN_LISTING }
    },
) {
    internal val root: SimplePanel = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }

    private val intro: Div = root.div("") { addCssClasses("text-muted small") }
    private val statusLine: Div = root.div("") { addCssClasses("small fw-bold") }

    private val form = root.lapisForm()
    internal val bioField =
        form.textAreaField(
            label = tr("Kurzvorstellung"),
            rows = 5,
            rule = { raw -> bioCheck(raw) },
        )
    private val counter: Div = form.panel.div("") { addCssClasses("small text-muted") }
    internal val saveButton: Button = Button(tr("Kurzvorstellung speichern"), style = ButtonStyle.PRIMARY)

    init {
        form.buttons(primary = saveButton)
    }

    internal val deleteButton: Button = Button(tr("Kurzvorstellung löschen"), style = ButtonStyle.OUTLINEDANGER)

    init {
        root.add(deleteButton)
    }

    internal val publicSwitch: CheckBox =
        CheckBox(value = false, label = tr("Kurzvorstellung öffentlich anzeigen")).also { root.add(it) }
    private val switchHint: Div = root.div("") { addCssClasses("text-muted small") }
    private val outdatedHint: Div =
        root.div(tr("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen.")) {
            addCssClasses("text-warning small")
        }
    private val previewHost: Div = root.div { addCssClass("lapis-public-card-host") }
    private val photoHint: Div = root.div(tr("Das Foto ändern Sie in der Foto-Karte oben.")) { addCssClasses("text-muted small") }
    private val listingHost: SimplePanel = root.vPanel(spacing = 4)

    private var state: OwnPublicProfileDto? = null
    private var busy = false
    private var syncingSwitch = false
    private var listingRendered = false

    init {
        statusLine.setAttribute("role", "status")
        bioField.subscribe { onTextChanged() }
        saveButton.onClick { save() }
        deleteButton.onClick { requestDelete() }
        publicSwitch.subscribe { checked ->
            // KVision notifies subscribers ALSO for programmatic `value =` changes; a notification that merely echoes
            // the server state we just rendered must never be mistaken for a click. A real click always DIFFERS from it.
            val serverPublic = state?.bioPublic == true
            if (syncingSwitch || checked == serverPublic) return@subscribe
            onSwitchToggled(checked)
        }
        render(null)
    }

    /** Fetches the current state from the server and renders it. A failed read leaves the previous rendering in place. */
    suspend fun load() {
        val dto = guarded { rpc.getOwnPublicProfile() } ?: return
        // A silent refresh must never throw away what the member is typing.
        render(dto, keepDraft = isDirty())
    }

    private fun savedText(): String = state?.bioText.orEmpty()

    private fun isDirty(): Boolean = bioField.value.trim() != savedText().trim()

    private fun onTextChanged() {
        updateCounter()
        renderPreview()
        applyEnabled()
    }

    private fun updateCounter() {
        val count = PublicTextRules.codePointCount(bioField.value.trim())
        counter.content = gettext("%1 / %2", count, MemberPublicBioRules.MAX_CODEPOINTS)
        if (count > MemberPublicBioRules.MAX_CODEPOINTS) counter.addCssClass("text-danger") else counter.removeCssClass("text-danger")
    }

    private fun save() {
        if (busy) return
        form.submit(saveButton) {
            applySave(bioField.value)
        }
    }

    /** Saves [text] (blank deletes) and renders ONLY the server's answer. Internal for tests. */
    internal suspend fun applySave(text: String) {
        if (busy) return
        setBusy(true)
        try {
            val dto = guarded { rpc.saveOwnBio(text) } ?: return
            render(dto)
            notifySuccess(if (dto.bioText == null) tr("Kurzvorstellung gelöscht.") else tr("Kurzvorstellung gespeichert."))
        } finally {
            setBusy(false)
        }
    }

    private fun requestDelete() {
        if (busy) return
        confirm(
            tr("Kurzvorstellung löschen"),
            tr("Ihre Kurzvorstellung wird endgültig gelöscht."),
            tr("Löschen"),
        ) { runGuardedAction(button = null) { applySave("") } }
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
                tr("Kurzvorstellung veröffentlichen"),
                // Deliberately NOT translated: the pinned wording (version member-bio-public-v1) is the German text only.
                MEMBER_PUBLIC_BIO_CONSENT_TEXT,
                tr("Veröffentlichen"),
            ) {
                runGuardedAction(button = null) {
                    applyVisibility(
                        true,
                        state?.requiredConsentTextVersion ?: MemberPublicBioRules.CONSENT_TEXT_VERSION,
                    )
                }
            }
        } else {
            runGuardedAction(button = null) { applyVisibility(false, null) }
        }
    }

    /** Calls the server and renders ONLY its answer. A stale consent text reloads and asks for a new confirmation. */
    internal suspend fun applyVisibility(
        visible: Boolean,
        consentTextVersion: String?,
    ) {
        if (busy) return
        setBusy(true)
        try {
            val dto =
                try {
                    rpc.setOwnBioPublic(visible, consentTextVersion)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MemberPublicBioConsentOutdatedException) {
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

    private fun setBusy(value: Boolean) {
        busy = value
        applyEnabled()
    }

    private fun applyEnabled() {
        val hasText = state?.bioText != null
        val dirty = isDirty()
        saveButton.disabled = busy || !dirty
        deleteButton.disabled = busy
        // Publishing refers to the SAVED text -- an unsaved edit must be saved first.
        publicSwitch.disabled = busy || !hasText || dirty
        switchHint.content = if (hasText && dirty) tr("Bitte zuerst speichern.") else ""
    }

    private fun resetSwitchToServerState() {
        syncingSwitch = true
        try {
            publicSwitch.value = state?.bioPublic == true
        } finally {
            syncingSwitch = false
        }
    }

    /** DIE einzige Stelle, die den Kartenzustand aus einem Server-DTO ableitet. */
    internal fun render(
        dto: OwnPublicProfileDto?,
        keepDraft: Boolean = false,
    ) {
        state = dto
        val show = dto != null && (dto.eligible || dto.bioText != null)
        root.visible = show
        if (!show) return
        val profile = dto ?: return

        intro.content =
            if (profile.eligible) {
                tr(
                    "Ihr Name und Ihre Rolle stehen bereits auf der öffentlichen Seite. Die Kurzvorstellung ist freiwillig und zunächst privat.",
                )
            } else {
                tr(
                    "Sie sind derzeit weder im Vorstand noch als Politiker gelistet. Ein gespeicherter Text bleibt privat und kann jederzeit gelöscht werden.",
                )
            }

        if (profile.publicOn.isEmpty()) {
            statusLine.content = tr("Derzeit nicht öffentlich sichtbar.")
        } else {
            statusLine.content = gettext("Derzeit öffentlich auf: %1", profile.publicOn.joinToString(", ") { placeLabel(it) })
        }

        // Never overwrite a dirty edit on a silent refresh ([keepDraft]); own saves/deletes render the server text.
        if (!keepDraft && bioField.value != profile.bioText.orEmpty()) bioField.setValue(profile.bioText.orEmpty())
        updateCounter()

        deleteButton.visible = profile.bioText != null
        outdatedHint.visible = profile.bioConsentOutdated
        resetSwitchToServerState()
        renderPreview()
        renderListingSection(profile)
        applyEnabled()
    }

    private fun placeLabel(place: PublicListingPlace): String =
        when (place) {
            PublicListingPlace.BOARD -> gettext("Vorstand")
            PublicListingPlace.POLITICIANS -> gettext("Politiker")
        }

    /** The card the public page will show -- same CSS classes, text nodes only. Reflects the editor, not the saved text. */
    private fun renderPreview() {
        val profile = state ?: return
        previewHost.removeAll()
        val card = previewHost.div { addCssClass("lapis-public-card") }
        if (profile.photoPublic) {
            card.image("/api/member-photo/own", "").apply { addCssClass("lapis-public-card-avatar") }
        } else {
            card.untrustedDiv(initialsOf(profile.displayName), className = "lapis-public-card-avatar lapis-public-card-initials")
        }
        card.untrustedDiv(profile.displayName, className = "lapis-public-card-name")
        val role = profile.roleLabel ?: profile.office
        if (role != null) card.untrustedDiv(role, className = "lapis-public-card-role")
        val draft = bioField.value.trim()
        if (draft.isNotEmpty()) card.untrustedDiv(draft, className = "lapis-public-card-bio")
    }

    /** The politician listing consent -- built ONCE, only for an appointed politician; the disclosure text comes from the server. */
    private fun renderListingSection(profile: OwnPublicProfileDto) {
        if (!profile.isPolitician) {
            listingHost.visible = false
            return
        }
        listingHost.visible = true
        if (listingRendered) return
        listingRendered = true
        AppScope.launch {
            val listingState = loadListingState()
            renderPublicRankingConsentToggle(listingHost, PublicRankingKind.POLITICIAN_LISTING, listingState) {
                AppScope.launch { load() }
            }
        }
    }
}

private fun bioCheck(raw: String): FieldCheck =
    when (MemberPublicBioRules.normalize(raw)) {
        is PublicTextNormalization.Ok, PublicTextNormalization.Empty -> FieldCheck.Ok
        PublicTextNormalization.TooLong ->
            FieldCheck.Invalid(gettext("Die Kurzvorstellung darf höchstens %1 Zeichen lang sein.", MemberPublicBioRules.MAX_CODEPOINTS))
        PublicTextNormalization.TooManyLineBreaks ->
            FieldCheck.Invalid(gettext("Bitte höchstens %1 Zeilenumbrüche verwenden.", MemberPublicBioRules.MAX_LINE_BREAKS))
        PublicTextNormalization.ControlChars -> FieldCheck.Invalid(gettext("Der Text enthält unzulässige Zeichen."))
    }

/** Initials for the preview avatar: first letter of the first and of the last word. Approximation of the server's grapheme-aware rule -- a preview only. */
internal fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val picked = if (words.size <= 1) words else listOf(words.first(), words.last())
    val letters = picked.mapNotNull { word -> word.firstOrNull()?.takeIf { it.isLetter() } }
    return letters.joinToString("").uppercase()
}

/**
 * Mounts the card into [container]. The initial read is a [dataSection]: loading text, and on failure the shared error state with
 * "Erneut versuchen" instead of a card that silently shows nothing.
 */
internal fun renderMemberPublicProfileSection(container: SimplePanel) {
    val rpc = liveMemberPublicProfileRpc()
    container
        .dataSection<OwnPublicProfileDto>(
            isEmpty = { false },
            load = { guarded { rpc.getOwnPublicProfile() } },
            render = { panel, dto ->
                // Title only for a member who actually gets a card (eligible or holding a text) -- no dangling heading.
                if (dto.eligible || dto.bioText != null) {
                    panel.h2(tr("Mein öffentliches Profil")) { addCssClass("h5") }
                }
                MemberPublicProfileCard(parent = panel, rpc = rpc).render(dto)
            },
        ).reload()
}
