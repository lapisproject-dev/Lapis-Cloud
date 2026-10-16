package network.lapis.cloud.client

import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberFinderService

/**
 * V1.9.145 -- whether the sidebar offers the member finder. The server alone knows whether the instance switch is on, so one `getState()` per session
 * answers it (`offered`). The result lives only in memory (never in the DOM, a log or the storage of the browser) and is dropped when the identity or
 * the status of the session changes ([clear], called by `AppState.setSession`).
 *
 * `getState` is called for ACTIVE members only: for every other status the server answers a plain refusal, which would show a toast and cost a request
 * of the hourly budget. The probe never shows a toast: a failed probe simply leaves the entry out.
 */
internal object MemberFinderNav {
    /** `null` = unknown or not loaded yet; otherwise `getState().offered` of the current session. */
    var offered: Boolean? = null
        private set

    private var loadedOnce = false
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** The sidebar registers the visibility of its link here. */
    fun onChange(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    /** `buildSidebar` throws the old links away, so their listeners go too. */
    fun resetListeners() {
        listeners.clear()
    }

    /** Loads the answer once per session and only for an ACTIVE member. */
    fun ensureLoaded(session: SessionInfoDto) {
        if (session.status !in MemberStatusSets.ORGANIZATION_MEMBER) return
        if (loadedOnce) return
        loadedOnce = true
        refresh()
    }

    /** Reads the state again (after a switch of the instance or a change of the own participation). */
    fun refresh() {
        val status = AppState.session?.status ?: return
        if (status !in MemberStatusSets.ORGANIZATION_MEMBER) return
        loadedOnce = true
        AppScope.launch {
            val state = runCatching { rpcService<IMemberFinderService>().getState() }.getOrNull() ?: return@launch
            update(state)
        }
    }

    /** Takes `offered` from an answer that was loaded anyway, which saves a request. */
    fun update(state: MemberFinderStateDto) {
        set(state.offered)
    }

    /** Called when the identity or the status of the session changes. */
    fun clear() {
        loadedOnce = false
        offered = null
        listeners.forEach { it(false) }
    }

    private fun set(value: Boolean) {
        offered = value
        listeners.forEach { it(value) }
    }
}
