package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.button
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.66: the bar never wraps. A real bar (real cascade, 360 px wide like a phone) with every control the screen builds, the real
 * moderation group, the real dividers and the labelled twins in a sheet: after [ConferenceControlsOverflow.recompute] the visible controls
 * share one row, nothing is clipped, the fixed controls stay, moved controls have a labelled twin and no duplicate tab stop.
 */
class ConferenceControlsOverflowDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class Rig(
        val bar: Widget,
        val group: ConferenceModerationGroup?,
        val overflow: ConferenceControlsOverflow,
        val buttons: Map<ConferenceControlSlot, Button>,
        val twins: Map<ConferenceControlSlot, Button>,
        val dividers: List<Span>,
        val moved: MutableList<Set<ConferenceControlSlot>>,
    )

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun shown(el: HTMLElement) = window.getComputedStyle(el).display != "none"

    /** A hidden KVision widget has no element of its own (it is not rendered), so its state is `visible`. */
    private fun isShown(widget: Widget) = widget.visible && widget.getElement()?.let { shown(it) } == true

    private fun build(
        root: Container,
        width: Int,
        moderator: Boolean = true,
        screenLike: Boolean = false,
    ): Rig {
        val bar =
            root.hPanel(spacing = 6) {
                addCssClasses("align-items-center lapis-conference-controls-row")
                this.width = width.px
            }
        val sheet = root.vPanel(spacing = 4)
        val buttons = linkedMapOf<ConferenceControlSlot, Button>()
        val twins = linkedMapOf<ConferenceControlSlot, Button>()
        buttons[ConferenceControlSlot.MIC] = bar.conferenceControlButton(ActionIcon.MICROPHONE, "Mikrofon")
        buttons[ConferenceControlSlot.CAMERA] = bar.conferenceControlButton(ActionIcon.CAMERA, "Kamera")
        buttons[ConferenceControlSlot.SCREEN] = bar.conferenceControlButton(ActionIcon.SCENE, "Bildschirm teilen")
        val divider1 = bar.conferenceControlsDivider()
        val group = bar.conferenceModerationGroup(moderator, {}, {}, {})
        val divider2 = bar.conferenceControlsDivider()
        buttons[ConferenceControlSlot.ROSTER] = bar.conferenceControlButton(ActionIcon.PEOPLE, "Teilnehmende")
        buttons[ConferenceControlSlot.CHAT] = bar.conferenceControlButton(ActionIcon.CHAT, "Chat")
        buttons[ConferenceControlSlot.VOTE] = bar.conferenceControlButton(ActionIcon.APPROVE, "Abstimmen")
        buttons[ConferenceControlSlot.MORE] = bar.conferenceControlButton(ActionIcon.SETTINGS, "Mehr")
        val divider3 = bar.conferenceControlsDivider()
        // screenLike: as in ConferenceScreen -- a breakout BACK button and `ms-2` margins on BACK / LEAVE.
        if (screenLike) {
            buttons[ConferenceControlSlot.BACK] =
                bar.conferenceControlButton(ActionIcon.LEAVE, "Zurück zum Hauptraum").apply { addCssClass("ms-2") }
        }
        buttons[ConferenceControlSlot.LEAVE] =
            bar.conferenceControlButton(ActionIcon.LEAVE, "Verlassen", ButtonStyle.DANGER).apply { if (screenLike) addCssClass("ms-2") }
        if (group != null) {
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            buttons[ConferenceControlSlot.RECORD] = group.recordButton
            buttons[ConferenceControlSlot.STREAM] = group.streamButton
            buttons[ConferenceControlSlot.END_FOR_ALL] = group.endButton
        } else {
            divider1.hide()
        }
        val twinLabels =
            mapOf(
                ConferenceControlSlot.SCREEN to "Bildschirm teilen",
                ConferenceControlSlot.RECORD to "Aufzeichnung starten",
                ConferenceControlSlot.STREAM to "Live-Stream starten …",
                ConferenceControlSlot.END_FOR_ALL to "Für alle beenden",
                ConferenceControlSlot.ROSTER to "Teilnehmende",
                ConferenceControlSlot.VOTE to "Abstimmen",
            )
        for ((slot, label) in twinLabels) {
            val primary = buttons[slot] ?: continue
            val twin = sheet.button(label)
            twin.hide()
            twin.onClick { primary.getElement()?.click() }
            twins[slot] = twin
        }
        val moved = mutableListOf<Set<ConferenceControlSlot>>()
        val overflow =
            ConferenceControlsOverflow(
                bar = bar,
                slots =
                    buttons.map { (slot, primary) ->
                        OverflowSlot(slot, primary, twins[slot], mirrorPressed = slot == ConferenceControlSlot.ROSTER)
                    },
                dividers = listOf(divider1 to 1, divider2 to 2, divider3 to 3),
                onChanged = { moved += it },
                containerGroups = if (group != null) setOf(1) else emptySet(),
            )
        return Rig(bar, group, overflow, buttons, twins, listOf(divider1, divider2, divider3), moved)
    }

    @Test
    fun at360px_theBarIsOneRow_notClipped_andKeepsTheFixedControls() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-360") { root, element ->
            val rig = build(root, 360)
            rig.overflow.recompute()
            val bar = element().q(".lapis-conference-controls-row")
            val visible =
                rig.buttons
                    .filter { (slot, _) -> slot !in rig.overflow.moved() }
                    .values
                    .map { it.getElement()!! }
            assertTrue(visible.all { shown(it) })
            val tops = visible.map { it.getBoundingClientRect().top }
            assertTrue(tops.all { abs(it - tops.first()) <= 1.0 }, "one row: $tops")
            val debug =
                "moved=${rig.overflow.moved()} gap=${window.getComputedStyle(bar).columnGap}/ " +
                    "pad=${window.getComputedStyle(bar).paddingLeft} " +
                    visible.joinToString {
                        "${it.getAttribute(
                            "aria-label",
                        )}:${it.getBoundingClientRect().left}-${it.getBoundingClientRect().right}"
                    } +
                    " dividers=" + rig.dividers.joinToString { "${it.getElement()?.getBoundingClientRect()?.left}" }
            assertTrue(
                bar.scrollWidth <= bar.clientWidth,
                "nothing is clipped: scrollWidth ${bar.scrollWidth} > clientWidth ${bar.clientWidth} $debug",
            )
            for (fixed in listOf(
                ConferenceControlSlot.MIC,
                ConferenceControlSlot.CAMERA,
                ConferenceControlSlot.CHAT,
                ConferenceControlSlot.MORE,
                ConferenceControlSlot.LEAVE,
            )) {
                assertTrue(shown(rig.buttons.getValue(fixed).getElement()!!), "$fixed stays in the bar")
            }
            assertTrue(rig.overflow.moved().isNotEmpty(), "360 px needs the sheet")
        }
    }

    @Test
    fun withMarginsAndBackButton_nothingIsClipped_overAFineWidthSweep() {
        assertTrue(stylesLoaded)
        for (width in 400..700) {
            withMountedRoot("overflow-sweep-$width") { root, element ->
                val rig = build(root, width, screenLike = true)
                rig.overflow.recompute()
                val bar = element().q(".lapis-conference-controls-row")
                assertTrue(
                    bar.scrollWidth <= bar.clientWidth,
                    "width $width: scrollWidth ${bar.scrollWidth} > clientWidth ${bar.clientWidth}",
                )
            }
        }
    }

    @Test
    fun movedControls_areHiddenInTheBar_andHaveALabelledVisibleTwin() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-twins") { root, _ ->
            val rig = build(root, 360)
            rig.overflow.recompute()
            val moved = rig.overflow.moved()
            assertTrue(moved.isNotEmpty())
            for (slot in moved) {
                assertEquals("none", window.getComputedStyle(rig.buttons.getValue(slot).getElement()!!).display, "$slot hidden in the bar")
                val twin = assertNotNull(rig.twins[slot], "$slot has a twin")
                assertTrue(isShown(twin), "$slot twin shown")
                assertTrue(
                    twin
                        .getElement()!!
                        .textContent
                        .orEmpty()
                        .trim()
                        .isNotEmpty(),
                    "the twin of $slot is labelled with words (R58)",
                )
            }
            for (slot in rig.twins.keys - moved) assertTrue(!isShown(rig.twins.getValue(slot)), "$slot twin hidden while the primary shows")
        }
    }

    @Test
    fun noNameIsFocusableTwice() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-tabstops") { root, element ->
            val rig = build(root, 360)
            rig.overflow.recompute()
            val names =
                element()
                    .querySelectorAll("button")
                    .let { list -> (0 until list.length).map { list.item(it) as HTMLElement } }
                    .filter { shown(it) }
                    .map { it.getAttribute("aria-label") ?: it.textContent.orEmpty().trim() }
            assertEquals(names.distinct().sorted(), names.sorted(), "every visible name only once: $names")
        }
    }

    @Test
    fun endForAll_isNeverNextToLeave_inTheDom() {
        assertTrue(stylesLoaded)
        for (width in listOf(300, 360, 480, 640, 800, 1024, 1280)) {
            withMountedRoot("overflow-adjacent-$width") { root, _ ->
                val rig = build(root, width)
                rig.overflow.recompute()
                val order = ConferenceControlSlot.entries.filter { it in rig.buttons && it !in rig.overflow.moved() }
                assertTrue(!endForAllAdjacentToLeave(order), "width $width: $order")
            }
        }
    }

    @Test
    fun wide_movesNothing_andSuppressesNoDivider() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-wide") { root, _ ->
            val rig = build(root, 1280)
            rig.overflow.recompute()
            assertEquals(emptySet(), rig.overflow.moved())
            assertTrue(rig.dividers.all { isShown(it) }, "three groups, three dividers")
        }
    }

    @Test
    fun aParticipant_hasNoModerationDivider() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-participant") { root, _ ->
            val rig = build(root, 1280, moderator = false)
            rig.overflow.recompute()
            val visibleDividers = rig.dividers.map { isShown(it) }
            assertEquals(listOf(false, true, true), visibleDividers, "no divider for the missing moderation group")
        }
    }

    @Test
    fun movingTwinClick_clicksThePrimary_andDisabledIsMirrored() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-mirror") { root, _ ->
            val rig = build(root, 360)
            var clicked = 0
            rig.buttons.getValue(ConferenceControlSlot.SCREEN).onClick { clicked++ }
            rig.overflow.recompute()
            assertTrue(ConferenceControlSlot.SCREEN in rig.overflow.moved())
            rig.twins
                .getValue(ConferenceControlSlot.SCREEN)
                .getElement()!!
                .click()
            assertEquals(1, clicked)
            rig.buttons.getValue(ConferenceControlSlot.SCREEN).disabled = true
            rig.overflow.recompute()
            assertTrue(rig.twins.getValue(ConferenceControlSlot.SCREEN).disabled)
        }
    }

    @Test
    fun dispose_stopsObserving() {
        withMountedRoot("overflow-dispose") { root, _ ->
            val rig = build(root, 360)
            rig.overflow.ensureObserving()
            rig.overflow.dispose()
            val before = rig.overflow.moved()
            rig.overflow.recompute()
            assertEquals(before, rig.overflow.moved(), "a disposed handler does nothing")
        }
    }
}
