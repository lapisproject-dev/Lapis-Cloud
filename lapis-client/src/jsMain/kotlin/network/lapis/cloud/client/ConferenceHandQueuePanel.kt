package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.localStorage
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.get
import org.w3c.dom.set

/*
 * V1.9.127 -- the "Wortmeldungen" section of the roster: the raised hands in the order the server keeps, visible to everybody as a numbered
 * list (`<ol>`). Names are always set as text (never markup) and come from the roster the screen already has; an identity without a tile is
 * "Unbekannt". Only for the moderation: a "Hand senken" button per row (accessible name "Hand von {0} senken"), "Alle Hände senken" with a
 * confirmation, and the switch "Wortmeldungen ansagen" (the moderators' polite live region; a personal choice kept in this browser).
 *
 * The widgets are KVision widgets, every attribute goes through the patch cycle -- no `addAfterInsertHook`, no raw DOM.
 */

/** `localStorage` key of the moderator's personal "announce raised hands" switch. Only `"off"` is ever written; "on" removes the entry. */
internal const val HAND_ANNOUNCE_STORAGE_KEY = "lapis.conference.announceHands"

/** The personal switch "Wortmeldungen ansagen": on unless the person switched it off. A storage that throws means "on" and the choice lives in memory. */
internal class HandAnnouncePreference {
    private var memory: Boolean? = null

    fun isOn(): Boolean =
        memory ?: try {
            localStorage[HAND_ANNOUNCE_STORAGE_KEY] != "off"
        } catch (e: Throwable) {
            true
        }

    fun setOn(on: Boolean) {
        memory = on
        try {
            if (on) localStorage.removeItem(HAND_ANNOUNCE_STORAGE_KEY) else localStorage[HAND_ANNOUNCE_STORAGE_KEY] = "off"
        } catch (e: Throwable) {
            // blocked storage: the choice stays in memory for this call
        }
    }
}

/** Whether the native checkbox behind this widget is checked right now (the DOM is the truth for a switch the person just flipped). */
private fun Tag.checkedNow(): Boolean = (getElement() as? HTMLInputElement)?.checked == true

internal class ConferenceHandQueuePanel(
    parent: Container,
    private val canModerate: Boolean,
    private val preference: HandAnnouncePreference,
    private val onLowerOf: (identity: String) -> Unit,
    private val onLowerAll: () -> Unit,
) {
    val root: Div = parent.div(className = "lapis-conference-hands")
    private val list: Tag
    private val emptyLine: Div
    val lowerAllButton: Button?
    val announceSwitch: Tag?

    init {
        root.setAttribute("role", "region")
        root.setAttribute("aria-label", gettext("Wortmeldungen"))
        root.h2(tr("Wortmeldungen")) { addCssClasses("h6 mt-2") }
        emptyLine = root.div(tr("Keine Wortmeldungen"), className = "text-muted small")
        list = root.tag(TAG.OL, className = "lapis-conference-hands-list ps-4 mb-2")
        if (canModerate) {
            lowerAllButton =
                root.actionButton(ActionIcon.HAND, tr("Alle Hände senken"), style = ButtonStyle.OUTLINESECONDARY, small = true) {
                    addCssClass("lapis-conference-hands-lower-all")
                    onClick { onLowerAll() }
                }
            lowerAllButton.hide()
            val row = root.div(className = "form-check form-switch lapis-conference-moderation-switch")
            val input = row.tag(TAG.INPUT, className = "form-check-input")
            input.setAttribute("type", "checkbox")
            input.setAttribute("role", "switch")
            input.setAttribute("id", ANNOUNCE_ID)
            if (preference.isOn()) input.setAttribute("checked", "")
            input.setAttribute("aria-checked", preference.isOn().toString())
            row.tag(TAG.LABEL, content = tr("Wortmeldungen ansagen"), className = "form-check-label").setAttribute("for", ANNOUNCE_ID)
            input.onEvent {
                change = {
                    val on = input.checkedNow()
                    preference.setOn(on)
                    input.setAttribute("aria-checked", on.toString())
                }
            }
            announceSwitch = input
        } else {
            lowerAllButton = null
            announceSwitch = null
        }
    }

    /** Redraws the list: [order] first hand first, [nameOf] the display name of an identity or `null` if the roster does not know it. */
    fun render(
        order: List<String>,
        nameOf: (String) -> String?,
    ) {
        list.removeAll()
        if (order.isEmpty()) emptyLine.show() else emptyLine.hide()
        if (order.isEmpty()) list.hide() else list.show()
        for (identity in order) {
            val name = nameOf(identity) ?: gettext("Unbekannt")
            val item = list.tag(TAG.LI, className = "lapis-conference-hands-item")
            val line = item.div(className = "d-flex align-items-center flex-wrap gap-2")
            line.div(name, className = "small flex-grow-1")
            if (canModerate) {
                val lower = line.actionButton(ActionIcon.HAND, tr("Hand senken"), style = ButtonStyle.OUTLINESECONDARY, small = true)
                // the name is data: an attribute value, never markup; the visible text stays the static verb
                lower.setAttribute("aria-label", gettext("Hand von %1 senken", name))
                lower.onClick { onLowerOf(identity) }
            }
        }
        if (canModerate) {
            if (order.isEmpty()) lowerAllButton?.hide() else lowerAllButton?.show()
        }
    }

    private companion object {
        const val ANNOUNCE_ID = "lapis-conference-announce-hands"
    }
}

/** The confirmation before every hand of the room is lowered; [count] is the number of hands that will be removed. */
internal fun conferenceLowerAllHandsDialog(
    count: Int,
    onConfirm: () -> Unit,
) = confirmDialog(
    title = tr("Alle Hände senken?"),
    message = gettext("%1 Wortmeldung(en) werden entfernt.", count),
    confirmLabel = tr("Alle Hände senken"),
    confirmIcon = ActionIcon.HAND,
    confirmStyle = ButtonStyle.PRIMARY,
    focusCancel = true,
    onConfirm = onConfirm,
)
