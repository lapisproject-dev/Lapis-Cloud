package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.Span
import io.kvision.html.button
import io.kvision.html.div
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
        val exit: ConferenceExitGroup,
        val endClicks: IntArray,
        val sheet: Widget,
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
        // V1.9.184: the REAL three-zone bar (RoomControlBar) with the real compatibility class, so the grid, the zones and the separators
        // of theme.css are measured, not a flat row.
        val dock = root.div(className = "lapis-room-bar-dock lapis-room-bar-dock--conference")
        val roomBar = RoomControlBar(dock, "lapis-room-bar--conference lapis-conference-controls-row")
        val bar = roomBar.root
        bar.width = width.px
        val centre = roomBar.centre
        // the real class of the sheet, so the twin's rule above it (theme.css) applies
        val sheet = dock.vPanel(spacing = 4) { addCssClass("lapis-conference-more-sheet") }
        val endClicks = IntArray(1)
        val buttons = linkedMapOf<ConferenceControlSlot, Button>()
        val twins = linkedMapOf<ConferenceControlSlot, Button>()
        buttons[ConferenceControlSlot.SETTINGS] = roomBar.start.conferenceSettingsButton()
        buttons[ConferenceControlSlot.MIC] = centre.conferenceControlButton(ActionIcon.MICROPHONE, "Mikrofon")
        buttons[ConferenceControlSlot.CAMERA] = centre.conferenceControlButton(ActionIcon.CAMERA, "Kamera")
        buttons[ConferenceControlSlot.SCREEN] = centre.conferenceControlButton(ActionIcon.SCENE, "Bildschirm teilen")
        val divider3 = roomBar.divider(centre)
        buttons[ConferenceControlSlot.ROSTER] = centre.conferenceControlButton(ActionIcon.PEOPLE, "Teilnehmende")
        buttons[ConferenceControlSlot.CHAT] = centre.conferenceControlButton(ActionIcon.CHAT, "Chat")
        buttons[ConferenceControlSlot.VOTE] = centre.conferenceControlButton(ActionIcon.APPROVE, "Abstimmen")
        val divider5 = roomBar.divider(centre)
        val group = centre.conferenceModerationGroup(moderator, {}, {})
        val divider6 = roomBar.divider(centre)
        buttons[ConferenceControlSlot.MORE] = centre.conferenceControlButton(ActionIcon.SETTINGS, "Mehr")
        // As in ConferenceScreen (V1.9.72): one exit group in the end zone -- [BACK (breakout)] [END_FOR_ALL (moderator, main room)] [LEAVE].
        val exit =
            roomBar.end.conferenceExitGroup(
                canEndForAll = moderator && !screenLike,
                isBreakout = screenLike,
                onEndForAll = { endClicks[0]++ },
            )
        exit.backButton?.let { buttons[ConferenceControlSlot.BACK] = it }
        exit.endButton?.let { buttons[ConferenceControlSlot.END_FOR_ALL] = it }
        buttons[ConferenceControlSlot.LEAVE] = exit.leaveButton
        if (group != null) {
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            buttons[ConferenceControlSlot.RECORD] = group.recordButton
            buttons[ConferenceControlSlot.STREAM] = group.streamButton
        } else {
            divider5.hide()
        }
        val twinLabels =
            mapOf(
                ConferenceControlSlot.SETTINGS to "Einstellungen",
                ConferenceControlSlot.MIC to "Mikrofon",
                ConferenceControlSlot.CAMERA to "Kamera",
                ConferenceControlSlot.SCREEN to "Bildschirm teilen",
                ConferenceControlSlot.RECORD to "Aufzeichnung starten",
                ConferenceControlSlot.STREAM to "Live-Stream starten …",
                ConferenceControlSlot.ROSTER to "Teilnehmende",
                ConferenceControlSlot.VOTE to "Abstimmen",
                // last, as in ConferenceScreen: the twin of "Für alle beenden" ends the sheet
                ConferenceControlSlot.END_FOR_ALL to "Für alle beenden",
            )
        for ((slot, label) in twinLabels) {
            val primary = buttons[slot] ?: continue
            val twin = if (slot == ConferenceControlSlot.END_FOR_ALL) sheet.conferenceEndForAllTwin() else sheet.button(label)
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
                dividers = listOf(divider3 to 3, divider5 to 5, divider6 to 6),
                onChanged = { moved += it },
                containerGroups = setOfNotNull(if (group != null) CONFERENCE_MODERATION_GROUP else null, CONFERENCE_EXIT_GROUP),
                reservedWidth = RoomControlBar.ZONE_SEPARATORS_RESERVED_PX,
            )
        return Rig(bar, group, overflow, buttons, twins, listOf(divider3, divider5, divider6), moved, exit, endClicks, sheet)
    }

    /**
     * The row is not clipped: every shown button lies inside the bar, and every button of the centre inside the centre zone. (`scrollWidth` of
     * the bar says nothing any more -- the centre zone clips with `overflow: clip`, which is no scroll overflow of the bar.)
     */
    private fun assertNothingClipped(
        bar: HTMLElement,
        context: String,
    ) {
        val barRect = bar.getBoundingClientRect()
        val centre = bar.q(".lapis-room-bar__centre")
        val centreRect = centre.getBoundingClientRect()
        for (button in bar.allOf("button").filter { shown(it) }) {
            val r = button.getBoundingClientRect()
            assertTrue(
                r.left >= barRect.left - 0.5 && r.right <= barRect.right + 0.5,
                "$context: inside the bar: ${r.left}-${r.right} of ${barRect.left}-${barRect.right}",
            )
            if (centre.contains(button)) {
                assertTrue(
                    r.left >= centreRect.left - 0.5 && r.right <= centreRect.right + 0.5,
                    "$context: inside the centre zone: ${r.left}-${r.right} of ${centreRect.left}-${centreRect.right}",
                )
            }
        }
        assertTrue(
            centre.scrollWidth <= centre.clientWidth + 1,
            "$context: centre scrollWidth ${centre.scrollWidth} > clientWidth ${centre.clientWidth}",
        )
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
            assertNothingClipped(bar, "moved=${rig.overflow.moved()}")
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
                assertNothingClipped(bar, "width $width")
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

    private fun barLabels(rig: Rig): List<String> =
        rig.bar
            .getElement()!!
            .allOf("button")
            .filter { shown(it) }
            .map { it.getAttribute("aria-label").orEmpty() }

    @Test
    fun endForAll_isImmediatelyBeforeLeave_inTheDom_atEveryWidth() {
        assertTrue(stylesLoaded)
        for (width in listOf(300, 320, 360, 480, 640, 800, 1024, 1280)) {
            for (breakout in listOf(false, true)) {
                withMountedRoot("overflow-order-$width-$breakout") { root, _ ->
                    val rig = build(root, width, screenLike = breakout)
                    rig.overflow.recompute()
                    val order = ConferenceControlSlot.entries.filter { it in rig.buttons && it !in rig.overflow.moved() }
                    assertTrue(endForAllImmediatelyBeforeLeave(order), "width $width breakout $breakout: $order")
                    // ... and the DOM agrees with the model: the visible buttons end with [BACK] [END] LEAVE
                    val labels = barLabels(rig)
                    val leaveLabel = if (breakout) "Besprechung ganz verlassen" else "Verlassen"
                    assertEquals(leaveLabel, labels.last(), "last visible control: $labels")
                    if (ConferenceControlSlot.END_FOR_ALL in order) {
                        assertEquals("Für alle beenden", labels[labels.size - 2], "Beenden directly before Verlassen: $labels")
                    }
                }
            }
        }
    }

    @Test
    fun theExitGroup_isOneWrapper_inDocumentOrder_backEndLeave() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-exit-wrapper") { root, element ->
            val rig = build(root, 1280)
            val wrapper = element().q(".lapis-conference-controls-group--exit")
            assertEquals("group", wrapper.getAttribute("role"))
            val labels = wrapper.allOf("button").map { it.getAttribute("aria-label") }
            assertEquals(listOf("Für alle beenden", "Verlassen"), labels)
            // no `ms-2` any more: the gap comes from the wrapper only
            assertEquals(0, element().querySelectorAll(".ms-2").length)
            val breakout = build(root, 1280, screenLike = true)
            val wrappers = element().allOf(".lapis-conference-controls-group--exit")
            val labelsInBreakout = wrappers.last().allOf("button").map { it.getAttribute("aria-label").orEmpty() }
            assertEquals(listOf("Zurück zum Hauptraum", "Besprechung ganz verlassen"), labelsInBreakout)
            assertTrue(rig.exit.endButton != null && breakout.exit.endButton == null && breakout.exit.backButton != null)
        }
    }

    @Test
    fun at500px_endForAllAndLeaveAreBothInTheBar_inOneRow_notClipped() {
        // V1.9.184: the bar carries the gear and two zone separators now, so a moderator's bar keeps "Für alle beenden" in the bar from about
        // 450 px; below that it moves into the sheet (see at280px_... and the order test above, which holds at every width).
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-500-exit") { root, element ->
            val rig = build(root, 500)
            rig.overflow.recompute()
            val bar = element().q(".lapis-conference-controls-row")
            assertTrue(ConferenceControlSlot.END_FOR_ALL !in rig.overflow.moved(), "moved=${rig.overflow.moved()}")
            val end = rig.buttons.getValue(ConferenceControlSlot.END_FOR_ALL).getElement()!!
            val leave = rig.buttons.getValue(ConferenceControlSlot.LEAVE).getElement()!!
            assertTrue(shown(end) && shown(leave))
            assertTrue(abs(end.getBoundingClientRect().top - leave.getBoundingClientRect().top) <= 1.0, "one row")
            assertNothingClipped(bar, "500")
            val barRect = bar.getBoundingClientRect()
            assertTrue(leave.getBoundingClientRect().right <= barRect.right + 0.5, "Verlassen inside the bar")
        }
    }

    @Test
    fun withTheExitGroup_nothingIsClipped_overAWidthSweep_forEveryRole() {
        assertTrue(stylesLoaded)
        for (moderator in listOf(true, false)) {
            for (width in 300..700 step 4) {
                withMountedRoot("overflow-exit-sweep-$moderator-$width") { root, element ->
                    val rig = build(root, width, moderator = moderator)
                    rig.overflow.recompute()
                    val bar = element().q(".lapis-conference-controls-row")
                    assertNothingClipped(bar, "moderator $moderator width $width")
                    val leave = rig.buttons.getValue(ConferenceControlSlot.LEAVE).getElement()!!
                    assertTrue(leave.getBoundingClientRect().right <= bar.getBoundingClientRect().right + 0.5, "Verlassen inside at $width")
                }
            }
        }
    }

    @Test
    fun endForAllAndLeave_areAtLeast12pxApart_andTheirHitAreasDoNotOverlap() {
        assertTrue(stylesLoaded)
        for (width in listOf(360, 480, 1280)) {
            withMountedRoot("overflow-gap-$width") { root, _ ->
                val rig = build(root, width)
                rig.overflow.recompute()
                if (ConferenceControlSlot.END_FOR_ALL in rig.overflow.moved()) return@withMountedRoot
                val end =
                    rig.buttons
                        .getValue(ConferenceControlSlot.END_FOR_ALL)
                        .getElement()!!
                        .getBoundingClientRect()
                val leave =
                    rig.buttons
                        .getValue(ConferenceControlSlot.LEAVE)
                        .getElement()!!
                        .getBoundingClientRect()
                assertTrue(end.right <= leave.left, "no overlap at $width")
                assertTrue(leave.left - end.right >= CONFERENCE_EXIT_GAP_PX - 0.5, "gap ${leave.left - end.right} at $width")
                assertTrue(end.width >= 44.0 && end.height >= 44.0 && leave.width >= 44.0 && leave.height >= 44.0)
            }
        }
    }

    @Test
    fun at280px_endForAllMovesIntoTheSheet_asTheLastRedEntry_andLeaveStays() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-280-exit") { root, _ ->
            val rig = build(root, 280)
            rig.overflow.recompute()
            val moved = rig.overflow.moved()
            assertTrue(ConferenceControlSlot.END_FOR_ALL in moved, "280 px is too narrow for Beenden: $moved")
            assertTrue(ConferenceControlSlot.LEAVE !in moved && shown(rig.buttons.getValue(ConferenceControlSlot.LEAVE).getElement()!!))
            val endTwin = rig.twins.getValue(ConferenceControlSlot.END_FOR_ALL)
            assertTrue(isShown(endTwin), "the twin shows")
            val shownTwins =
                rig.sheet
                    .getElement()!!
                    .allOf("button")
                    .filter { shown(it) }
            assertEquals(endTwin.getElement(), shownTwins.last(), "the twin of Beenden is the LAST visible entry of the sheet")
            assertTrue(endTwin.getElement()!!.classList.contains("text-danger"), "danger colour")
            val rule = window.getComputedStyle(endTwin.getElement()!!, "::before")
            assertEquals("solid", rule.borderTopStyle, "set apart by a rule")
            // a click on the twin goes through the primary control -> the same single path (the dialog) as in the bar
            endTwin.getElement()!!.click()
            assertEquals(1, rig.endClicks[0])
        }
    }

    @Test
    fun clickOnEndForAll_callsOnlyOnEndForAll() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-end-click") { root, _ ->
            val rig = build(root, 1280)
            var leaves = 0
            rig.exit.leaveButton.onClick { leaves++ }
            rig.buttons
                .getValue(ConferenceControlSlot.END_FOR_ALL)
                .getElement()!!
                .click()
            assertEquals(1, rig.endClicks[0])
            assertEquals(0, leaves)
        }
    }

    @Test
    fun wide_movesNothing_andSuppressesNoDivider() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-wide") { root, _ ->
            val rig = build(root, 1280)
            rig.overflow.recompute()
            assertEquals(emptySet(), rig.overflow.moved())
            assertTrue(rig.dividers.all { isShown(it) }, "all groups of the centre shown, all dividers shown")
        }
    }

    @Test
    fun aParticipant_hasNoModerationDivider() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-participant") { root, _ ->
            val rig = build(root, 1280, moderator = false)
            rig.overflow.recompute()
            val visibleDividers = rig.dividers.map { isShown(it) }
            assertEquals(listOf(true, false, true), visibleDividers, "no divider for the missing moderation group")
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

    // ── V1.9.184: the gear, its twin in the "Mehr" sheet, its panel, and the auto-hide of the dock ─────────────────────────────

    @Test
    fun theGear_movesIntoTheSheetAtAVeryNarrowBar_asALabelledTwin_andTheTwinClicksTheGear() {
        assertTrue(stylesLoaded)
        var found = false
        for (width in 360 downTo 200 step 10) {
            withMountedRoot("overflow-gear-$width") { root, _ ->
                val rig = build(root, width)
                var clicked = 0
                rig.buttons.getValue(ConferenceControlSlot.SETTINGS).onClick { clicked++ }
                rig.overflow.recompute()
                if (ConferenceControlSlot.SETTINGS in rig.overflow.moved() && !found) {
                    found = true
                    val gear = rig.buttons.getValue(ConferenceControlSlot.SETTINGS).getElement()!!
                    assertTrue(!shown(gear), "width $width: the gear is gone from the bar")
                    val twin = rig.twins.getValue(ConferenceControlSlot.SETTINGS)
                    assertTrue(isShown(twin), "width $width: the twin is shown")
                    assertEquals(
                        "Einstellungen",
                        twin
                            .getElement()!!
                            .textContent
                            .orEmpty()
                            .trim(),
                        "the twin carries the word",
                    )
                    twin.getElement()!!.click()
                    assertEquals(1, clicked, "the twin clicks the gear")
                }
            }
        }
        assertTrue(found, "some width moves the gear into the sheet")
    }

    @Test
    fun theGear_staysInTheStartZone_whileTheBarIsWide() {
        assertTrue(stylesLoaded)
        withMountedRoot("overflow-gear-wide") { root, element ->
            val rig = build(root, 1200)
            rig.overflow.recompute()
            assertTrue(ConferenceControlSlot.SETTINGS !in rig.overflow.moved())
            val gear = rig.buttons.getValue(ConferenceControlSlot.SETTINGS).getElement()!!
            assertTrue(shown(gear))
            assertEquals("Einstellungen", gear.getAttribute("aria-label"))
            assertTrue(element().q(".lapis-room-bar__start").contains(gear), "the gear is the start zone's control")
        }
    }

    @Test
    fun wireSettingsSheet_tiesTheGearToItsNonModalDialog() {
        assertTrue(stylesLoaded)
        withMountedRoot("settings-wire") { root, _ ->
            val rig = build(root, 600)
            val gear = rig.buttons.getValue(ConferenceControlSlot.SETTINGS)
            val sheet = root.vPanel { addCssClass("lapis-conference-settings-sheet") }
            wireSettingsSheet(gear, sheet)
            val gearEl = gear.getElement()!!
            val sheetEl = sheet.getElement()!!
            assertEquals("dialog", sheetEl.getAttribute("role"))
            assertEquals("Einstellungen", sheetEl.getAttribute("aria-label"))
            assertEquals(CONFERENCE_SETTINGS_SHEET_ID, sheetEl.id)
            assertEquals(CONFERENCE_SETTINGS_SHEET_ID, gearEl.getAttribute("aria-controls"))
            assertEquals("false", gearEl.getAttribute("aria-expanded"))
            assertEquals(sheetEl, kotlinx.browser.document.getElementById(gearEl.getAttribute("aria-controls")!!))
        }
    }

    @Test
    fun theHiddenClass_fadesTheWholeDock_surfaceIncluded_notOnlyTheBar() {
        assertTrue(stylesLoaded)
        withMountedRoot("dock-hidden") { root, element ->
            build(root, 600)
            val dock = element().q(".lapis-room-bar-dock")
            val bar = element().q(".lapis-room-bar")
            assertEquals("1", window.getComputedStyle(dock).opacity)
            dock.classList.add("lapis-conference-controls-hidden")
            bar.classList.add("lapis-conference-controls-hidden")
            assertEquals("0", window.getComputedStyle(dock).opacity, "no opaque strip stays behind the faded buttons")
            assertEquals("none", window.getComputedStyle(dock).getPropertyValue("pointer-events"))
            assertEquals("none", window.getComputedStyle(bar).getPropertyValue("pointer-events"))
            dock.classList.remove("lapis-conference-controls-hidden")
            bar.classList.remove("lapis-conference-controls-hidden")
            assertEquals("1", window.getComputedStyle(dock).opacity)
        }
    }
}
