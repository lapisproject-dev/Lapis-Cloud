package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/*
 * V1.9.180: an on/off setting shows exactly ONE action -- the one that applies to the current state. Before, the screens for auction,
 * dunning, receivable dunning, online payment and SEPA always rendered both "... aktivieren" and "... deaktivieren" side by side, so half
 * of the time one of the two buttons was a dead end the server then refused.
 *
 * Pure UI: no RPC is known here, no role check is made or bypassed (the screens stay ADMIN-only through their route guard).
 */

/** The four states of an on/off setting row. */
internal enum class ToggleLoadState { LOADING, ENABLED, DISABLED, FAILED }

/** CSS class of the status text of the row (also the focus target after a switch). */
internal const val TOGGLE_STATUS_CLASS = "toggle-status"

/**
 * Renders ONE row into [container] (after emptying it): the status text on the left, at most one button on the right.
 *
 * - [ToggleLoadState.ENABLED]: "Status: Aktiviert" and the button built by [disable].
 * - [ToggleLoadState.DISABLED]: "Status: Deaktiviert" and the button built by [enable].
 * - [ToggleLoadState.LOADING] / [ToggleLoadState.FAILED]: only the text, no button.
 *
 * The button that does not apply is never created -- no `hide()`, no `disabled`. The status text is a `role="status"` element with
 * `tabindex="-1"` so a script can move the focus to it after a switch (the activated button is gone by then). Returns the status text.
 * [rowClass] (V1.9.190, optional): a layout class for the row; the default keeps the compact horizontal panel of the other screens.
 */
internal fun renderToggleActionRow(
    container: SimplePanel,
    state: ToggleLoadState,
    enable: (Container) -> Button,
    disable: (Container) -> Button,
    rowClass: String? = null,
): Span {
    container.removeAll()
    // V1.9.190: a screen with a wider row (provider select next to the button) passes its own CSS class and gets a plain wrapping flex
    // `div`; every other screen keeps the narrow `hPanel`.
    val row: SimplePanel =
        if (rowClass == null) {
            container.hPanel(spacing = 8) { addCssClasses("mt-2 align-items-center") }
        } else {
            container.div(className = rowClass)
        }
    val text =
        when (state) {
            ToggleLoadState.LOADING -> tr("Wird geladen …")
            ToggleLoadState.ENABLED -> tr("Status: Aktiviert")
            ToggleLoadState.DISABLED -> tr("Status: Deaktiviert")
            ToggleLoadState.FAILED -> tr("Einstellungen konnten nicht geladen werden.")
        }
    val status =
        row.span(text) {
            addCssClass(TOGGLE_STATUS_CLASS)
            when (state) {
                ToggleLoadState.LOADING -> addCssClasses("text-muted small")
                ToggleLoadState.FAILED -> addCssClasses("text-danger small")
                else -> Unit
            }
            setAttribute("tabindex", "-1")
            setAttribute("role", "status")
        }
    when (state) {
        ToggleLoadState.ENABLED -> disable(row)
        ToggleLoadState.DISABLED -> enable(row)
        ToggleLoadState.LOADING, ToggleLoadState.FAILED -> Unit
    }
    return status
}

/** Moves the focus to the status text of the row inside [container]; waits a few frames for the first render of a fresh row. */
internal fun focusToggleStatus(container: SimplePanel) {
    AppScope.launch {
        repeat(FOCUS_ATTEMPTS) {
            val target = container.getElement()?.querySelector(".$TOGGLE_STATUS_CLASS") as? HTMLElement
            if (target != null) {
                target.focus()
                return@launch
            }
            delay(FOCUS_RETRY_MS)
        }
    }
}

private const val FOCUS_ATTEMPTS = 40
private const val FOCUS_RETRY_MS = 25L

/**
 * The shared loader of an on/off screen: the summary in [settingsPanel], the single applicable action in [actionsHost].
 *
 * [fetch] returns `null` when the call failed (the `guarded` toast was shown already) -- the row then says so instead of showing
 * "Wird geladen …" forever. Two overlapping [load] runs (a click during a reload) are resolved by a run number: the result of a stale
 * run is dropped, so an old answer never overwrites a newer one.
 */
internal class ToggleSettingsPanel<T : Any>(
    private val settingsPanel: SimplePanel,
    private val actionsHost: SimplePanel,
    private val fetch: suspend () -> T?,
    private val isEnabled: (T) -> Boolean,
    private val renderSummary: (SimplePanel, T) -> Unit,
    private val enable: (Container) -> Button,
    private val disable: (Container) -> Button,
    private val rowClass: String? = null,
) {
    private var generation = 0

    /** [focusStatus]: after a switch the focus moves to the status text; the first load of a page must not steal it. */
    fun load(focusStatus: Boolean = false) {
        val mine = ++generation
        settingsPanel.removeAll()
        renderToggleActionRow(actionsHost, ToggleLoadState.LOADING, enable, disable, rowClass)
        AppScope.launch {
            val settings = fetch()
            if (mine != generation) return@launch
            settingsPanel.removeAll()
            if (settings == null) {
                renderToggleActionRow(actionsHost, ToggleLoadState.FAILED, enable, disable, rowClass)
                return@launch
            }
            renderSummary(settingsPanel, settings)
            val state = if (isEnabled(settings)) ToggleLoadState.ENABLED else ToggleLoadState.DISABLED
            renderToggleActionRow(actionsHost, state, enable, disable, rowClass)
            if (focusStatus) focusToggleStatus(actionsHost)
        }
    }
}

/** An empty block host for the row: keeps the position of the actions between the summary and the following section. */
internal fun SimplePanel.toggleActionsHost(): SimplePanel = div()
