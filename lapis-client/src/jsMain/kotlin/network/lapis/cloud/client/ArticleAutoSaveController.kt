package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto

/** A cancellable pending timer -- the real implementation wraps `kotlinx.browser.window.setTimeout`/`clearTimeout`, a test double can fire synchronously. */
fun interface ArticleAutoSaveSchedule {
    fun schedule(
        delayMs: Int,
        action: () -> Unit,
    ): Handle

    fun interface Handle {
        fun cancel()
    }
}

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- pure, UI-framework-free auto-save
 * logic for [ArticleEditor]'s "Schreiben"-tab. Every side effect ([save], [schedule], [now]) is
 * injected, so `ArticleAutoSaveControllerTest` (`jsTest`) exercises this class WITHOUT a DOM, a
 * real `XMLHttpRequest`, or a real timer -- same "inject every effect" discipline this codebase's
 * server-side pure-logic classes (`ArticlePolicy`, `EmbedArticlesFeedLimits`, ...) already
 * establish, applied here to the client.
 *
 * **Serialization discipline (Stolperfalle §6 "Doppelte Artikel beim Auto-Save")**: this class is
 * the ONLY caller of [save] the editor ever wires up -- never a second, independent `saveDraft`
 * call site -- and it enforces "at most one [save] in flight at a time" itself: a change that
 * arrives while a save is already running is captured in [pendingInput]/[dirty] rather than
 * starting a second, concurrent `saveDraft(id = null)` (which would insert a SECOND draft row for
 * what the author experiences as one continuous edit).
 *
 * **2000ms debounce, immediate follow-up.** [onChange] restarts a 2-second timer on every
 * keystroke; when it fires with no save currently running, [save] is called with the freshest
 * [pendingInput]. If further changes arrive WHILE that call is in flight, they are captured but do
 * NOT start a second call -- once the in-flight call returns, exactly one follow-up [save] runs
 * IMMEDIATELY (not debounced a second time) with whatever the latest [pendingInput] is by then.
 *
 * **First save gated on non-empty content.** While [articleId] is still `null` (no draft created
 * yet), [onChange] does not even arm the debounce timer for a title+body that are both blank --
 * an author who opens the editor and closes it again without typing anything must never create an
 * empty draft row. Once a draft exists ([articleId] non-null), this gate no longer applies: an
 * existing draft may be edited down to blank fields (`ArticlePolicy.validateDraftLengths` only
 * caps upper length, never a lower bound) and still auto-saves.
 */
class ArticleAutoSaveController(
    private val scope: CoroutineScope,
    private val save: suspend (id: String?, input: ArticleDraftInput) -> ArticleDto,
    private val schedule: ArticleAutoSaveSchedule,
    private val now: () -> Double = { 0.0 },
    private val onStateChange: (SaveState) -> Unit = {},
) {
    sealed interface SaveState {
        data object Idle : SaveState

        data object Saving : SaveState

        data class Saved(
            val at: Double,
        ) : SaveState

        data class Failed(
            val message: String?,
        ) : SaveState
    }

    var articleId: String? = null
        private set

    var saveState: SaveState = SaveState.Idle
        private set

    /**
     * V1.9.50: the input of the last save that SUCCEEDED (`null` before the first one). The editor compares the current form with it to
     * decide whether leaving would lose anything -- additive, the save logic itself does not read it.
     */
    var lastSavedInput: ArticleDraftInput? = null
        private set

    private var pendingInput: ArticleDraftInput? = null
    private var dirty: Boolean = false
    private var inFlight: Boolean = false
    private var debounceHandle: ArticleAutoSaveSchedule.Handle? = null

    /**
     * Non-null exactly while a save chain (the initial [attemptSave] plus any immediate follow-up
     * triggered by [dirty] input arriving while it was in flight) is still running. [flushNowAndAwait]
     * suspends on this so a caller can be sure the LATEST edit has actually reached the server
     * before navigating away -- see that function's KDoc.
     */
    private var pendingCompletion: CompletableDeferred<Unit>? = null

    /** Set once, right after loading an EXISTING draft into the editor (a brand-new draft leaves this `null`). */
    fun setInitialArticleId(id: String?) {
        articleId = id
    }

    /** Called on every editor keystroke/field change. */
    fun onChange(input: ArticleDraftInput) {
        pendingInput = input
        dirty = true
        if (articleId == null && input.title.isBlank() && input.body.isBlank()) {
            // Nothing worth creating a draft for yet -- do not even arm the timer.
            debounceHandle?.cancel()
            debounceHandle = null
            return
        }
        debounceHandle?.cancel()
        debounceHandle =
            schedule.schedule(DEBOUNCE_MS) {
                debounceHandle = null
                attemptSave()
            }
    }

    /** Saves immediately, bypassing the debounce timer -- fire-and-forget, see [flushNowAndAwait]. */
    fun flushNow() {
        debounceHandle?.cancel()
        debounceHandle = null
        attemptSave()
    }

    /**
     * [flushNow], but suspends until the triggered save (and any immediate follow-up it chains,
     * per [attemptSave]'s "exactly one follow-up" rule) has actually settled -- successfully or not.
     * If a save was ALREADY in flight when called (e.g. from an earlier keystroke's debounce
     * firing), this awaits that one too, it does not start a redundant second call.
     *
     * Callers navigating away from the editor (Stolperfalle "orphaned debounce timer") MUST call
     * this instead of [flushNow] and wait for it before reloading/leaving, otherwise a save that
     * fires after navigation can race a subsequent state transition (e.g. `submitArticle`) on the
     * server and silently lose the last edits -- see [ArticleEditor]'s "Zurück"-button wiring.
     */
    suspend fun flushNowAndAwait() {
        debounceHandle?.cancel()
        debounceHandle = null
        attemptSave()
        pendingCompletion?.await()
    }

    /**
     * V1.9.50: drops the pending edit -- cancels the debounce timer and clears [dirty], so nothing fires after the person chose to
     * discard it. A save that is ALREADY in flight is not touched (it cannot be recalled). Never starts a save.
     */
    fun cancelPending() {
        debounceHandle?.cancel()
        debounceHandle = null
        dirty = false
    }

    /** Re-attempts a save after [saveState] is [SaveState.Failed], using the last input that failed. */
    fun retry() {
        if (saveState !is SaveState.Failed) return
        dirty = true
        attemptSave()
    }

    private fun attemptSave() {
        if (inFlight) return // already running -- the in-flight completion handler re-checks `dirty`.
        if (!dirty) return
        val input = pendingInput ?: return
        if (articleId == null && input.title.isBlank() && input.body.isBlank()) {
            // Same "nothing worth creating a draft for yet" guard as onChange -- repeated here
            // because [onChange]'s guard only ever skips ARMING the debounce timer; it does not
            // (and cannot) stop [flushNow]/[flushNowAndAwait] from calling this function directly
            // with `dirty` already `true` (KVision's `subscribe` fires the observer immediately on
            // wiring, before the author has typed anything, which already sets `dirty = true` via
            // [onChange]). Without this guard, closing a brand-new, still-empty editor right after
            // opening it -- or typing a character and deleting it again -- created an empty draft
            // row. See [ArticleEditor]'s "Zurück"-button KDoc.
            dirty = false
            pendingCompletion?.complete(Unit)
            pendingCompletion = null
            return
        }
        dirty = false
        inFlight = true
        setState(SaveState.Saving)
        // Only START a new completion signal if none is already running (a chained follow-up
        // keeps the SAME instance so an `await()`er waits for the whole chain, not just one hop).
        if (pendingCompletion == null) pendingCompletion = CompletableDeferred()
        scope.launch {
            try {
                val result = save(articleId, input)
                articleId = result.id
                lastSavedInput = input
                inFlight = false
                setState(SaveState.Saved(now()))
                if (dirty) {
                    attemptSave() // exactly one immediate follow-up with the freshest input.
                } else {
                    pendingCompletion?.complete(Unit)
                    pendingCompletion = null
                }
            } catch (e: Throwable) {
                inFlight = false
                dirty = true // the failed input stays pending so retry()/a later change can resend it.
                setState(SaveState.Failed(e.message))
                pendingCompletion?.complete(Unit)
                pendingCompletion = null
            }
        }
    }

    private fun setState(state: SaveState) {
        saveState = state
        onStateChange(state)
    }

    companion object {
        const val DEBOUNCE_MS = 2000
    }
}
