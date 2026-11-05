package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.GuestScreenShareOutcome
import network.lapis.cloud.shared.domain.GuestScreenShareStateDto

// ================================================================================================
// V1.9.282 -- the moderator's side of the guest screen share: one labelled control per account-less guest of an event room. The server decides
// everything (rights, one grant per room, the guest's session); nothing here is authority. See docs/features/conference-moderation-audio.adoc.
// ================================================================================================

/** The LiveKit identity prefix of a person who joined an event without an account. */
internal const val GUEST_SHARE_IDENTITY_PREFIX = "evg-"

/** One guest the section lists. [displayName] is untrusted text (set through `textContent`/escaped content only). */
internal data class GuestShareGuest(
    val identity: String,
    val displayName: String,
)

/** What the row of one guest says in words. */
internal enum class GuestShareRowState { NOT_ALLOWED, ALLOWED, SHARING }

/** The state of one guest: sharing wins over allowed; the server's grant is the only source of "allowed". */
internal fun guestShareRowState(
    grantedIdentity: String?,
    sharingIdentities: Set<String>,
    identity: String,
): GuestShareRowState =
    when {
        identity in sharingIdentities -> GuestShareRowState.SHARING
        identity == grantedIdentity -> GuestShareRowState.ALLOWED
        else -> GuestShareRowState.NOT_ALLOWED
    }

internal fun guestShareRowStateText(state: GuestShareRowState): String =
    when (state) {
        GuestShareRowState.NOT_ALLOWED -> gettext("Darf den Bildschirm nicht teilen")
        GuestShareRowState.ALLOWED -> gettext("Darf den Bildschirm teilen")
        GuestShareRowState.SHARING -> gettext("Teilt gerade den Bildschirm")
    }

/** The standing hint above the list: what a shared screen becomes while a recording or a stream runs. `null` when neither runs. */
internal fun guestShareCaptureHint(
    recordingActive: Boolean,
    streamActive: Boolean,
): String? =
    when {
        recordingActive && streamActive ->
            gettext("Aufzeichnung und Livestream laufen: Ein geteilter Bildschirm ist in beiden zu sehen.")
        recordingActive -> gettext("Die Aufzeichnung läuft: Ein geteilter Bildschirm wird mit aufgezeichnet.")
        streamActive -> gettext("Der Livestream läuft: Ein geteilter Bildschirm ist für alle Zuschauer zu sehen.")
        else -> null
    }

/** What the moderator is told after a call: the sentence and whether it is a problem (an error) or a confirmation. */
internal data class GuestShareNoticeLine(
    val text: String,
    val error: Boolean,
)

internal fun guestShareOutcomeNotice(outcome: GuestScreenShareOutcome): GuestShareNoticeLine? =
    when (outcome) {
        GuestScreenShareOutcome.GRANTED, GuestScreenShareOutcome.ALREADY_GRANTED ->
            GuestShareNoticeLine(gettext("Der Gast darf jetzt den Bildschirm teilen."), error = false)
        GuestScreenShareOutcome.REVOKED, GuestScreenShareOutcome.NOT_GRANTED ->
            GuestShareNoticeLine(gettext("Die Freigabe wurde entzogen."), error = false)
        GuestScreenShareOutcome.TAKEN ->
            GuestShareNoticeLine(
                gettext("Es darf immer nur ein Gast den Bildschirm teilen. Bitte entziehen Sie zuerst die andere Freigabe."),
                error = true,
            )
        GuestScreenShareOutcome.NOT_IN_ROOM -> GuestShareNoticeLine(gettext("Dieser Gast ist nicht mehr im Raum."), error = true)
        GuestScreenShareOutcome.REVOKED_REMOVED ->
            GuestShareNoticeLine(
                gettext("Das Teilen ließ sich nicht beenden. Der Gast wurde aus dem Raum entfernt."),
                error = true,
            )
    }

/**
 * The "Gäste" part of the moderation panel: a heading, the standing capture hint and one row per guest (name as text, the state in words, one
 * button whose accessible name carries the guest's name). Built once; [render] only rebuilds the rows. Hidden while no guest is in the room.
 */
internal class ConferenceGuestShareSection(
    parent: Container,
    private val onToggle: (identity: String, allow: Boolean) -> Unit,
) {
    val root: Div = parent.div(className = "lapis-conference-guest-share")
    private val heading: Div = root.div(tr("Gäste"), className = "fw-semibold small")
    private val hint: Div = root.div("", className = "text-muted small")
    private val list: Div = root.div(className = "lapis-conference-guest-share-list")
    private var rowCount = 0

    init {
        heading.setAttribute("role", "heading")
        heading.setAttribute("aria-level", "3")
        root.hide()
        hint.hide()
    }

    /** The number of guest rows currently shown (read by tests). */
    val rows: Int get() = rowCount

    fun render(
        guests: List<GuestShareGuest>,
        grantedIdentity: String?,
        sharingIdentities: Set<String>,
        recordingActive: Boolean,
        streamActive: Boolean,
        busyIdentity: String? = null,
    ) {
        list.removeAll()
        rowCount = guests.size
        if (guests.isEmpty()) {
            root.hide()
            return
        }
        root.show()
        val captureHint = guestShareCaptureHint(recordingActive, streamActive)
        if (captureHint != null) {
            hint.content = captureHint
            hint.show()
        } else {
            hint.hide()
        }
        guests.forEach { guest ->
            val state = guestShareRowState(grantedIdentity, sharingIdentities, guest.identity)
            val row = list.div(className = "d-flex align-items-center flex-wrap gap-2 lapis-conference-guest-share-row")
            row.div(guest.displayName, className = "flex-grow-1 small")
            row.div(guestShareRowStateText(state), className = "text-muted small lapis-conference-guest-share-state")
            val allow = state == GuestShareRowState.NOT_ALLOWED
            val button =
                if (allow) {
                    row.actionButton(
                        ActionIcon.SCREEN_SHARE,
                        tr("Bildschirm teilen erlauben"),
                        style = ButtonStyle.OUTLINESECONDARY,
                        small = true,
                    )
                } else {
                    row.actionButton(ActionIcon.REVOKE, tr("Freigabe entziehen"), style = ButtonStyle.OUTLINEDANGER, small = true)
                }
            button.setStaticA11yLabel(
                if (allow) {
                    gettext("Bildschirm teilen erlauben für %1", guest.displayName)
                } else {
                    gettext("Freigabe entziehen für %1", guest.displayName)
                },
            )
            button.disabled = busyIdentity != null
            button.onClick { onToggle(guest.identity, allow) }
        }
    }
}

/**
 * Keeps the section in step with the room: the server's state is read when the panel opens, after every own action and (debounced, [debounceMs])
 * when a guest comes or goes or a share starts or stops. The REAL grant always comes from the server; the roster and the screen share tile only
 * supply who is there and who is sharing.
 */
internal class GuestShareController(
    private val scope: CoroutineScope,
    private val section: ConferenceGuestShareSection,
    private val loadState: suspend () -> GuestScreenShareStateDto?,
    private val setShare: suspend (identity: String, allow: Boolean) -> GuestScreenShareOutcome?,
    private val guests: () -> List<GuestShareGuest>,
    private val sharingIdentities: () -> Set<String>,
    private val recordingActive: () -> Boolean,
    private val streamActive: () -> Boolean,
    private val notify: (GuestShareNoticeLine) -> Unit,
    private val failed: () -> Unit,
    private val debounceMs: Long = 1_000,
) {
    private var granted: String? = null
    private var serverSharing = false
    private var busy: String? = null
    private var pending: Job? = null
    private var disposed = false

    /** The panel was opened or an action finished: read the server's state now. */
    fun refreshNow() {
        if (disposed) return
        scope.launch {
            loadState()?.let {
                granted = it.grantedIdentity
                serverSharing = it.sharing
            }
            render()
        }
    }

    /** A guest came or went, or a share started or stopped: redraw at once from what is known, read the server's state once the burst is over. */
    fun onRoomChanged() {
        if (disposed) return
        render()
        if (pending?.isActive == true) return
        pending =
            scope.launch {
                delay(debounceMs)
                if (disposed) return@launch
                loadState()?.let {
                    granted = it.grantedIdentity
                    serverSharing = it.sharing
                }
                render()
            }
    }

    fun act(
        identity: String,
        allow: Boolean,
    ) {
        if (disposed || busy != null) return
        busy = identity
        render()
        scope.launch {
            val outcome = setShare(identity, allow)
            busy = null
            if (outcome == null) failed() else guestShareOutcomeNotice(outcome)?.let(notify)
            refreshNow()
        }
    }

    fun render() {
        if (disposed) return
        val sharing = sharingIdentities().toMutableSet()
        // the server says the granted guest shares even if this client has not (yet) seen the track
        if (serverSharing) granted?.let { sharing += it }
        section.render(
            guests = guests(),
            grantedIdentity = granted,
            sharingIdentities = sharing,
            recordingActive = recordingActive(),
            streamActive = streamActive(),
            busyIdentity = busy,
        )
    }

    fun dispose() {
        disposed = true
        pending?.cancel()
        pending = null
    }
}
