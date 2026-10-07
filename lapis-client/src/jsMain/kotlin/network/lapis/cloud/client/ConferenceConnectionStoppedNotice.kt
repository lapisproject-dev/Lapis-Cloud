package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.tr
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** V1.9.69 -- why the connection stopped on its own: the same account joined elsewhere, or the automatic re-join guard ran out. */
internal enum class ConnectionStoppedKind { Displaced, LoopStopped }

/**
 * V1.9.69 -- the calm card that replaces the call (video grid + control bar) once the connection stopped and must NOT be
 * re-established automatically. No countdown, no auto-dismiss, no shortcut: the person decides.
 *
 * [onResume] makes exactly ONE attempt to join again (`true` = joined, the card is replaced anyway; `false` = it stays and
 * the button becomes active again). The button is disabled at once, so a double click cannot start a second attempt.
 * [onOverview] leaves for the overview.
 *
 * No own scroll surface (R59), only [actionButton]s (R57/R58).
 */
internal fun Container.conferenceConnectionStoppedNotice(
    kind: ConnectionStoppedKind,
    onResume: suspend () -> Boolean,
    onOverview: () -> Unit,
): Div {
    // Built with the constructor and added through addWithLifecycle (hook registered BEFORE the add: stable vnode key).
    val card =
        Div(className = "lapis-connection-stopped card card-body my-3") {
            setAttribute("role", "alert")
            setAttribute("tabindex", "-1")
        }
    with(card) {
        when (kind) {
            ConnectionStoppedKind.Displaced -> {
                h2(tr("Auf einem anderen Gerät verbunden"), className = "h5")
                p(
                    tr(
                        "Dieses Konto ist gerade auf einem anderen Gerät mit der Besprechung verbunden. Pro Konto ist zurzeit nur ein Gerät gleichzeitig möglich.",
                    ),
                )
                p(tr("Wenn Sie hier fortsetzen, wird das andere Gerät getrennt."), className = "text-muted")
            }
            ConnectionStoppedKind.LoopStopped -> {
                h2(tr("Verbindung mehrmals getrennt"), className = "h5")
                p(tr("Die Verbindung wurde mehrmals getrennt. Bitte treten Sie erneut bei."))
            }
        }
        val resumeLabel =
            when (kind) {
                ConnectionStoppedKind.Displaced -> tr("Hier fortsetzen")
                ConnectionStoppedKind.LoopStopped -> tr("Erneut beitreten")
            }
        div(className = "d-flex flex-wrap gap-2") {
            val resume = actionButton(ActionIcon.ENTER, resumeLabel, style = ButtonStyle.PRIMARY)
            resume.onClick {
                if (resume.disabled) return@onClick
                resume.disabled = true
                resume.setAttribute("aria-busy", "true")
                AppScope.launch {
                    val joined = runCatching { onResume() }.getOrDefault(false)
                    if (!joined) {
                        resume.disabled = false
                        resume.removeAttribute("aria-busy")
                    }
                }
            }
            actionButton(ActionIcon.BACK, tr("Zur Übersicht"), style = ButtonStyle.OUTLINESECONDARY).onClick { onOverview() }
        }
    }
    return addWithLifecycle(card, onInsert = { vnode -> (vnode.elm as? HTMLElement)?.focus() })
}
