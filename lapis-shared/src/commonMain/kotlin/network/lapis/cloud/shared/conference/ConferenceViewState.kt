package network.lapis.cloud.shared.conference

/**
 * V1.9.115 -- the state machine behind the view switcher of the live conference: what the user chose, whom they pinned, and what to go
 * back to when a screen share ends. Pure; the controller in `lapis-client` feeds it events and reads [ConferenceViewState].
 *
 * Persisted per viewer (browser storage) is ONLY the choice between GALLERY and SPEAKER ([conferenceViewStorageValue]) -- never a person.
 */
data class ConferenceViewState(
    val chosen: ConferenceView = ConferenceView.GALLERY,
    val pinnedId: String? = null,
    /** The view (and pinned person) from before the first running screen share; `null` while no share runs. */
    val beforeShare: Pair<ConferenceView, String?>? = null,
    /** The user picked a view by hand while a share was running (still restored when the last share ends). */
    val manualDuringShare: Boolean = false,
)

sealed interface ConferenceViewEvent {
    data class UserChose(
        val view: ConferenceView,
    ) : ConferenceViewEvent

    data class Pin(
        val identity: String,
    ) : ConferenceViewEvent

    data object Unpin : ConferenceViewEvent

    data object ShareStarted : ConferenceViewEvent

    data object AllSharesEnded : ConferenceViewEvent

    /** The pinned person left the room. */
    data object PinnedLeft : ConferenceViewEvent

    /** A tile of the filmstrip was tapped / activated: that person becomes the pinned one. */
    data class StripTileActivated(
        val identity: String,
    ) : ConferenceViewEvent
}

/**
 * The reducer. [lastExternalSpeaker] (the last other person who spoke) and [firstOther] (the first other person in the room) are what
 * choosing PINNED without a pinned person falls back to, in that order.
 */
fun reduceConferenceView(
    state: ConferenceViewState,
    event: ConferenceViewEvent,
    selfId: String,
    lastExternalSpeaker: String? = null,
    firstOther: String? = null,
): ConferenceViewState {
    val sharing = state.beforeShare != null

    fun pinned(identity: String): ConferenceViewState =
        if (identity == selfId) {
            state
        } else {
            state.copy(chosen = ConferenceView.PINNED, pinnedId = identity, manualDuringShare = state.manualDuringShare || sharing)
        }
    return when (event) {
        is ConferenceViewEvent.UserChose ->
            if (event.view == ConferenceView.PINNED) {
                val target =
                    state.pinnedId?.takeIf { it != selfId } ?: lastExternalSpeaker?.takeIf { it != selfId }
                        ?: firstOther?.takeIf { it != selfId }
                state.copy(chosen = ConferenceView.PINNED, pinnedId = target, manualDuringShare = state.manualDuringShare || sharing)
            } else {
                state.copy(chosen = event.view, manualDuringShare = state.manualDuringShare || sharing)
            }
        is ConferenceViewEvent.Pin -> pinned(event.identity)
        is ConferenceViewEvent.StripTileActivated -> pinned(event.identity)
        ConferenceViewEvent.Unpin ->
            state.copy(
                pinnedId = null,
                chosen = if (state.chosen == ConferenceView.PINNED) ConferenceView.SPEAKER else state.chosen,
            )
        ConferenceViewEvent.ShareStarted ->
            state.copy(
                chosen = ConferenceView.SCREEN_SHARE,
                beforeShare = state.beforeShare ?: (state.chosen to state.pinnedId),
                manualDuringShare = false,
            )
        ConferenceViewEvent.AllSharesEnded -> {
            val before = state.beforeShare
            when {
                before != null -> state.copy(chosen = before.first, pinnedId = before.second, beforeShare = null, manualDuringShare = false)
                state.chosen == ConferenceView.SCREEN_SHARE -> state.copy(chosen = ConferenceView.GALLERY, manualDuringShare = false)
                else -> state
            }
        }
        ConferenceViewEvent.PinnedLeft ->
            state.copy(
                pinnedId = null,
                chosen = if (state.chosen == ConferenceView.PINNED) ConferenceView.SPEAKER else state.chosen,
            )
    }
}

/** What goes into browser storage: GALLERY / SPEAKER; a pinned view is stored as SPEAKER (a person is never stored); a share view not at all. */
fun conferenceViewStorageValue(view: ConferenceView): String? =
    when (view) {
        ConferenceView.GALLERY -> "GALLERY"
        ConferenceView.SPEAKER, ConferenceView.PINNED -> "SPEAKER"
        ConferenceView.SCREEN_SHARE -> null
    }

/** The view a stored value stands for; anything unknown (garbage, `null`) is the gallery. */
fun conferenceViewFromStorage(raw: String?): ConferenceView = if (raw == "SPEAKER") ConferenceView.SPEAKER else ConferenceView.GALLERY

/** The state when joining: the stored view, or SCREEN_SHARE (remembering the stored view) when a share is already running. */
fun initialConferenceView(
    stored: ConferenceView,
    shareActive: Boolean,
): ConferenceViewState {
    val base = if (stored == ConferenceView.GALLERY || stored == ConferenceView.SPEAKER) stored else ConferenceView.GALLERY
    return if (shareActive) {
        ConferenceViewState(chosen = ConferenceView.SCREEN_SHARE, beforeShare = base to null)
    } else {
        ConferenceViewState(chosen = base)
    }
}
