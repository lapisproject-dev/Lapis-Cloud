package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import network.lapis.cloud.client.livekit.ConferenceModerationSignal
import org.w3c.dom.HTMLInputElement
import kotlin.time.Clock

// ================================================================================================
// V1.9.108 -- moderator audio controls of the video conference: pure rules + the small DOM building blocks the call screen assembles.
// See docs/architecture/conference-moderation-audio.adoc. The server decides everything (rights, peer rule); nothing here is authority.
// ================================================================================================

/** What the roster shows for one person's microphone. */
internal enum class ConferenceMicState { OPEN, MUTED, MUTED_BY_MODERATION }

/**
 * The microphone state of one person. [hasMicTrack] = a published microphone track exists, [micMuted] = LiveKit reports it muted (the
 * real `TrackMuted` signal, no longer the old "does a subscribed track exist" approximation), [mutedByModeration] = this client was told
 * (by a server packet, only the person themselves and the moderators are told) that a moderator muted it. The moderation mark only
 * matters while the microphone stays closed: it is lifted by the next unmute, see `ConferenceScreen`.
 */
internal fun conferenceMicState(
    hasMicTrack: Boolean,
    micMuted: Boolean,
    mutedByModeration: Boolean,
): ConferenceMicState =
    when {
        hasMicTrack && !micMuted -> ConferenceMicState.OPEN
        mutedByModeration -> ConferenceMicState.MUTED_BY_MODERATION
        else -> ConferenceMicState.MUTED
    }

/** The room's audio policy as the moderation panel keeps it. */
internal data class ConferenceAudioPolicyState(
    val muteOnJoin: Boolean,
    val participantsMayUnmute: Boolean,
)

/**
 * The policy the panel must show after a policy call FAILED. The server commits the row before it talks to LiveKit (and then answers
 * with a conflict), so the stored state is the truth whenever it can be read; only when it cannot, the local state stays.
 */
internal fun conferenceAudioPolicyResync(
    local: ConferenceAudioPolicyState,
    stored: ConferenceAudioPolicyState?,
): ConferenceAudioPolicyState = stored ?: local

/** The one action a roster row offers a moderator. */
internal enum class ConferenceRosterAction { MUTE, ASK, NONE }

/**
 * Which action a roster row offers: nothing unless the caller moderates; never against oneself or the room creator; and the PEER RULE --
 * somebody without BOARD/ADMIN cannot moderate somebody who has it. [targetPrivileged] is `null` when the client cannot know (it must NOT
 * be sent by the server: that would be a role oracle for everybody); then the button is shown and the server refuses with its one uniform
 * answer. An open microphone offers [ConferenceRosterAction.MUTE], a closed one [ConferenceRosterAction.ASK] ("Bitten").
 */
internal fun conferenceModerationRowAction(
    canModerate: Boolean,
    targetId: String,
    localId: String?,
    creatorId: String,
    targetPrivileged: Boolean?,
    callerPrivileged: Boolean,
    micState: ConferenceMicState,
): ConferenceRosterAction {
    if (!canModerate) return ConferenceRosterAction.NONE
    if (targetId == localId || targetId == creatorId) return ConferenceRosterAction.NONE
    if (targetPrivileged == true && !callerPrivileged) return ConferenceRosterAction.NONE
    return if (micState == ConferenceMicState.OPEN) ConferenceRosterAction.MUTE else ConferenceRosterAction.ASK
}

/**
 * How many people the "Alle stummschalten" button could affect, from the roster -- ONLY a hint that enables/disables the button. The
 * confirmation always uses the server's own `estimateMuteAll` (it knows who is BOARD/ADMIN). Never counts the creator or oneself.
 */
internal fun conferenceMuteAllCandidates(
    micStates: Map<String, ConferenceMicState>,
    creatorId: String,
    localId: String?,
): Int = micStates.count { (identity, state) -> state == ConferenceMicState.OPEN && identity != creatorId && identity != localId }

internal fun muteAllConfirmText(count: Int): String =
    ngettext(
        "%1 Person stummschalten? Moderatoren sind ausgenommen.",
        "%1 Personen stummschalten? Moderatoren sind ausgenommen.",
        count,
        count,
    )

internal fun lockOpenMicrophonesText(count: Int): String =
    ngettext(
        "Das offene Mikrofon von %1 Person wird stummgeschaltet.",
        "Die offenen Mikrofone von %1 Personen werden stummgeschaltet.",
        count,
        count,
    )

internal fun conferenceMicStateText(
    state: ConferenceMicState,
    lockedForMe: Boolean = false,
): String =
    when (state) {
        ConferenceMicState.OPEN -> tr("Mikrofon an")
        ConferenceMicState.MUTED -> if (lockedForMe) tr("Stumm, Einschalten gesperrt") else tr("Stumm")
        ConferenceMicState.MUTED_BY_MODERATION -> tr("Von der Moderation stummgeschaltet")
    }

internal fun conferenceMicStateIcon(
    state: ConferenceMicState,
    lockedForMe: Boolean = false,
): ActionIcon =
    when (state) {
        ConferenceMicState.OPEN -> ActionIcon.MICROPHONE
        ConferenceMicState.MUTED -> if (lockedForMe) ActionIcon.MIC_LOCKED else ActionIcon.MIC_OFF
        ConferenceMicState.MUTED_BY_MODERATION -> ActionIcon.MIC_MODERATION
    }

/** What the call's microphone button shows (V1.9.108). A pure function so the locked state is testable without a call. */
internal data class ConferenceMicButtonView(
    val iconCss: String,
    val label: String,
    val disabled: Boolean,
    val danger: Boolean,
)

/**
 * The microphone button: on / off as before, and -- when the moderators locked the room's microphones for this person -- disabled with the
 * lock symbol and the reason as its label and tooltip (the permission, not a data packet, decides [permitted]).
 */
internal fun conferenceMicButtonView(
    micEnabled: Boolean,
    permitted: Boolean,
): ConferenceMicButtonView =
    when {
        !permitted ->
            ConferenceMicButtonView(
                "fas fa-lock",
                tr("Die Moderation hat das Einschalten gesperrt"),
                disabled = true,
                danger = false,
            )
        micEnabled -> ConferenceMicButtonView("fas fa-microphone", tr("Mikrofon ausschalten"), disabled = false, danger = false)
        else -> ConferenceMicButtonView("fas fa-microphone-slash", tr("Mikrofon einschalten"), disabled = false, danger = true)
    }

/** Entering full screen while the rename field is up ends the rename first: the heading row is not painted in full screen. */
internal fun conferenceTitleReturnsToView(
    fullscreen: Boolean,
    editing: Boolean,
): Boolean = fullscreen && editing

// -- Roster building blocks ------------------------------------------------------------------------------------------------------------

/** Kare's symbol WITH its text: the symbol is decoration (`aria-hidden`), the words carry the meaning. */
internal fun Container.conferenceMicStateIndicator(
    state: ConferenceMicState,
    lockedForMe: Boolean = false,
): Div {
    val modifier =
        when {
            state == ConferenceMicState.OPEN -> "open"
            state == ConferenceMicState.MUTED_BY_MODERATION -> "moderation"
            lockedForMe -> "locked"
            else -> "muted"
        }
    val wrapper = div(className = "lapis-conference-mic-state lapis-conference-mic-state--$modifier text-muted small")
    wrapper.tag(TAG.I, className = "${conferenceMicStateIcon(state, lockedForMe).css} lapis-conference-mic-state-icon") {
        setAttribute("aria-hidden", "true")
    }
    wrapper.span(conferenceMicStateText(state, lockedForMe), className = "lapis-conference-mic-state-text")
    return wrapper
}

/**
 * The FIXED place of the roster action: always the same width, so a row never jumps when the action changes from "Stummschalten" to
 * "Bitten" or disappears. Only the moderators get a slot at all; [ConferenceRosterAction.NONE] leaves an empty placeholder.
 */
internal fun Container.conferenceRosterActionSlot(
    action: ConferenceRosterAction,
    displayName: String,
    onAct: () -> Unit,
): Div {
    val slot = div(className = "lapis-roster-action-slot")
    when (action) {
        ConferenceRosterAction.MUTE -> {
            val button = slot.actionButton(ActionIcon.MIC_OFF, tr("Stummschalten"), style = ButtonStyle.OUTLINESECONDARY, small = true)
            button.setStaticA11yLabel(gettext("Mikrofon von %1 stummschalten", displayName))
            button.onClick { onAct() }
        }
        ConferenceRosterAction.ASK -> {
            val button = slot.actionButton(ActionIcon.MICROPHONE, tr("Bitten"), style = ButtonStyle.OUTLINESECONDARY, small = true)
            button.setStaticA11yLabel(gettext("%1 bitten, das Mikrofon einzuschalten", displayName))
            button.onClick { onAct() }
        }
        ConferenceRosterAction.NONE -> slot.setAttribute("aria-hidden", "true")
    }
    return slot
}

/** "Alle stummschalten" at the head of the roster: always there for the moderation, disabled with a reason while nobody is to be muted. */
internal class ConferenceMuteAllControl(
    parent: Container,
    onClick: () -> Unit,
) {
    val row: Div = parent.div(className = "lapis-conference-mute-all d-flex align-items-center flex-wrap gap-2")
    val button: Button = row.actionButton(ActionIcon.MIC_OFF, tr("Alle stummschalten"), style = ButtonStyle.OUTLINESECONDARY, small = true)
    val hint: Div = row.div(tr("Niemand hat ein offenes Mikrofon."), className = "text-muted small")

    init {
        hint.setAttribute("id", HINT_ID)
        button.setAttribute("aria-describedby", HINT_ID)
        button.onClick { onClick() }
        update(0)
    }

    /** [candidates] from [conferenceMuteAllCandidates]. */
    fun update(candidates: Int) {
        button.disabled = candidates <= 0
        if (candidates <= 0) hint.show() else hint.hide()
    }

    private companion object {
        const val HINT_ID = "lapis-conference-mute-all-hint"
    }
}

internal fun conferenceMuteAllDialog(
    count: Int,
    onConfirm: () -> Unit,
) = confirmDialog(
    title = tr("Alle stummschalten"),
    message = muteAllConfirmText(count),
    confirmLabel = tr("Stummschalten"),
    confirmIcon = ActionIcon.MIC_OFF,
    confirmStyle = ButtonStyle.PRIMARY,
    focusCancel = true,
    onConfirm = onConfirm,
)

/** The confirmation before the room's microphones are locked; [openMicrophones] is the server's count of the ones that will be muted. */
internal fun conferenceLockDialog(
    openMicrophones: Int,
    onConfirm: () -> Unit,
) = confirmDialog(
    title = tr("Mikrofone sperren"),
    message = tr("Teilnehmende können ihr Mikrofon dann nicht mehr selbst einschalten. Wen Sie bitten, kann es wieder einschalten."),
    extraLines = if (openMicrophones > 0) listOf(lockOpenMicrophonesText(openMicrophones)) else emptyList(),
    confirmLabel = tr("Mikrofone sperren"),
    confirmIcon = ActionIcon.MIC_LOCKED,
    confirmStyle = ButtonStyle.PRIMARY,
    focusCancel = true,
    onConfirm = onConfirm,
)

// -- The person's own notices ----------------------------------------------------------------------------------------------------------

/**
 * What the affected person sees and hears: a calm band (info colour, never error red; a close button of at least 44 px) and two
 * live regions. The announcement is set ONCE per event and cleared again; the same text within [DEDUPE_MS] is not repeated (the lock
 * arrives as a data packet AND as a permission change). A request replaces the band, bands never stack, and nothing here ever moves
 * the keyboard focus.
 */
internal class ConferenceModerationNotices(
    parent: Container,
) {
    private val band: Div = parent.div(className = "lapis-conference-moderation-band")
    private val assertiveRegion: Div = parent.div(className = "visually-hidden")
    private val politeRegion: Div = parent.div(className = "visually-hidden")
    private var lastAnnounced: String? = null
    private var lastAnnouncedAt: Long = 0
    private val clearHandles = mutableMapOf<Div, Int>()

    init {
        band.hide()
        assertiveRegion.setAttribute("aria-live", "assertive")
        assertiveRegion.setAttribute("aria-atomic", "true")
        politeRegion.setAttribute("aria-live", "polite")
        politeRegion.setAttribute("aria-atomic", "true")
    }

    /** The text of the band at the moment, `null` if none is shown (read by tests). */
    val bandText: String?
        get() = if (band.visible) band.getElement()?.querySelector(".lapis-conference-moderation-band-text")?.textContent else null

    /** A moderator muted the own microphone. */
    fun showMuted() {
        val text = tr("Ihr Mikrofon wurde von der Moderation stummgeschaltet.")
        showBand(text, actions = emptyList())
        announce(text, assertive = true)
    }

    /** A moderator asks to switch the microphone on; [onTurnOn] is the person's OWN mic toggle, nothing is forced. */
    fun showUnmuteRequest(onTurnOn: () -> Unit) {
        val text = tr("Die Moderation bittet Sie, Ihr Mikrofon einzuschalten.")
        showBand(text, actions = listOf(tr("Mikrofon einschalten") to onTurnOn))
        announce(text, assertive = false)
    }

    fun announce(
        text: String,
        assertive: Boolean,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (text == lastAnnounced && now - lastAnnouncedAt < DEDUPE_MS) return
        lastAnnounced = text
        lastAnnouncedAt = now
        val region = if (assertive) assertiveRegion else politeRegion
        clearHandles.remove(region)?.let { window.clearTimeout(it) }
        region.content = text
        clearHandles[region] = window.setTimeout({ region.content = "" }, CLEAR_AFTER_MS)
    }

    fun dismiss() {
        band.hide()
        band.removeAll()
    }

    /** The text currently in the live region (read by tests; empty after it was cleared). */
    fun liveText(assertive: Boolean): String = (if (assertive) assertiveRegion else politeRegion).getElement()?.textContent.orEmpty()

    private fun showBand(
        text: String,
        actions: List<Pair<String, () -> Unit>>,
    ) {
        band.removeAll()
        band.span(text, className = "lapis-conference-moderation-band-text")
        actions.forEachIndexed { index, (label, action) ->
            val button =
                band.actionButton(
                    ActionIcon.MICROPHONE,
                    label,
                    style = if (index == 0) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY,
                    small = true,
                )
            button.addCssClass("lapis-conference-moderation-band-action")
            button.onClick {
                dismiss()
                action()
            }
        }
        if (actions.isNotEmpty()) {
            val notNow = band.actionButton(ActionIcon.CLOSE, tr("Nicht jetzt"), style = ButtonStyle.OUTLINESECONDARY, small = true)
            notNow.addCssClass("lapis-conference-moderation-band-action")
            notNow.onClick { dismiss() }
        } else {
            val close =
                band.actionButton(
                    ActionIcon.CLOSE,
                    tr("Hinweis schließen"),
                    style = ButtonStyle.OUTLINESECONDARY,
                    iconOnly = true,
                    small = true,
                )
            close.addCssClass("lapis-conference-moderation-band-close")
            close.onClick { dismiss() }
        }
        band.show()
    }

    private companion object {
        const val DEDUPE_MS = 3_000L
        const val CLEAR_AFTER_MS = 4_000
    }
}

/** The texts of the moderation signals the person only hears (no band): the lock and its lifting. */
internal fun conferenceModerationAnnouncement(signal: ConferenceModerationSignal): String? =
    when (signal) {
        ConferenceModerationSignal.Lock -> tr("Die Moderation hat das Einschalten der Mikrofone gesperrt.")
        ConferenceModerationSignal.Unlock -> tr("Sie können Ihr Mikrofon wieder einschalten.")
        else -> null
    }

// -- The moderation disclosure (rendered for the moderation only) ----------------------------------------------------------------------

/**
 * The "Moderation" disclosure of a running room, built like the guest-access row (the room form of the lobby does not exist, design
 * decision D4 "one button, no form"): a real disclosure button with `aria-expanded` and two native `input[type=checkbox][role=switch]`
 * with their own labels and hints. It starts open while the room is locked. The panel only reports the person's wish through
 * [onMuteOnJoin]/[onLock] (each gets a `revert` to undo the visual change); the screen decides and calls [setState] with the server's
 * answer.
 */
internal class ConferenceModerationPanel(
    parent: Container,
    muteOnJoin: Boolean,
    participantsMayUnmute: Boolean,
    private val onMuteOnJoin: (value: Boolean, revert: () -> Unit) -> Unit,
    private val onLock: (locked: Boolean, revert: () -> Unit) -> Unit,
) {
    val root: Div = parent.div(className = "lapis-conference-moderation-panel lapis-conference-config-row")
    val toggle: Tag =
        root.tag(
            TAG.BUTTON,
            content = tr("Moderation"),
            className = "btn btn-outline-secondary btn-sm lapis-conference-moderation-toggle",
        )
    private val body: Div = root.div(className = "lapis-conference-moderation-body")

    // The switch state lives here, not in the DOM: a closed body is not rendered at all (a hidden KVision widget leaves the DOM), so the
    // inputs are NEW elements every time the panel opens and read their state from these fields when they are inserted.
    private var muteOnJoinState = muteOnJoin
    private var lockState = !participantsMayUnmute
    val muteOnJoinInput =
        body.switchRow(
            MUTE_ON_JOIN_ID,
            tr("Teilnehmende stumm beitreten lassen"),
            tr("Wer beitritt, startet mit ausgeschaltetem Mikrofon und kann es selbst einschalten."),
        ) { muteOnJoinState }
    val lockInput =
        body.switchRow(
            LOCK_ID,
            tr("Teilnehmende dürfen sich nicht selbst einschalten"),
            tr("Nur Sie und die Menschen, die Sie bitten, können das Mikrofon dann einschalten."),
        ) { lockState }
    private var expanded = false

    init {
        toggle.setAttribute("type", "button")
        toggle.setAttribute("aria-controls", BODY_ID)
        body.setAttribute("id", BODY_ID)
        toggle.onEvent { click = { setOpen(!expanded) } }
        muteOnJoinInput.onEvent {
            change = {
                muteOnJoinState = muteOnJoinInput.isChecked()
                onMuteOnJoin(muteOnJoinState) { putMuteOnJoin(!muteOnJoinState) }
            }
        }
        lockInput.onEvent {
            change = {
                // the switch reads "participants must NOT switch themselves on": checked = locked
                lockState = lockInput.isChecked()
                onLock(lockState) { putLock(!lockState) }
            }
        }
        setOpen(false)
        setState(muteOnJoin, participantsMayUnmute)
    }

    private fun putMuteOnJoin(value: Boolean) {
        muteOnJoinState = value
        muteOnJoinInput.setChecked(value)
    }

    private fun putLock(value: Boolean) {
        lockState = value
        lockInput.setChecked(value)
    }

    /** Applies the server's state; the panel opens by itself while the room is locked. */
    fun setState(
        muteOnJoin: Boolean,
        participantsMayUnmute: Boolean,
    ) {
        putMuteOnJoin(muteOnJoin)
        putLock(!participantsMayUnmute)
        if (!participantsMayUnmute) setOpen(true) else setOpen(expanded)
    }

    fun setOpen(value: Boolean) {
        expanded = value
        toggle.setAttribute("aria-expanded", value.toString())
        if (value) body.show() else body.hide()
    }

    private fun Div.switchRow(
        id: String,
        label: String,
        hint: String,
        state: () -> Boolean,
    ): Widget {
        val row = div(className = "form-check form-switch lapis-conference-moderation-switch")
        val input = row.tag(TAG.INPUT, className = "form-check-input")
        input.setAttribute("type", "checkbox")
        input.setAttribute("role", "switch")
        input.setAttribute("id", id)
        input.setAttribute("aria-describedby", "$id-hint")
        // the widget's own attributes carry the state: a re-opened body renders new elements from them
        if (state()) input.setAttribute("checked", "")
        input.setAttribute("aria-checked", state().toString())
        row.tag(TAG.LABEL, content = label, className = "form-check-label").setAttribute("for", id)
        row.div(hint, className = "text-muted small").setAttribute("id", "$id-hint")
        return input
    }

    private fun Widget.isChecked(): Boolean = (getElement() as? HTMLInputElement)?.checked == true

    private fun Widget.setChecked(value: Boolean) {
        (getElement() as? HTMLInputElement)?.checked = value
        if (value) setAttrIfChanged("checked", "") else removeAttribute("checked")
        setAttrIfChanged("aria-checked", value.toString())
    }

    fun setBusy(busy: Boolean) {
        muteOnJoinInput.getElement()?.let { (it as? HTMLInputElement)?.disabled = busy }
        lockInput.getElement()?.let { (it as? HTMLInputElement)?.disabled = busy }
    }

    private companion object {
        const val MUTE_ON_JOIN_ID = "lapis-conference-mute-on-join"
        const val LOCK_ID = "lapis-conference-lock-microphones"
        const val BODY_ID = "lapis-conference-moderation-body"
    }
}
