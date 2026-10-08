package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.Span
import kotlinx.browser.window
import network.lapis.cloud.client.maplibre.ResizeObserver

/*
 * V1.9.74 -- the overflow mechanism of a one-line control bar, generalised over the slot type [S]. It was written for the conference bar
 * (V1.9.66, `ConferenceControlsOverflow`) and is now shared with the encounter room's bar; the conference classes are thin wrappers
 * around this file and keep their names and behaviour.
 *
 * It contains NO RPC call and NO late hook (ledgers of ClientDataStateTripwireTest / ClientLateHookRatchetTest must not grow): every
 * attribute is written through the KVision patch cycle.
 */

/** V1.9.72: gap between the controls of the exit group; theme.css `--exit` of both bars uses the same 12 px. */
internal const val CONTROL_BAR_EXIT_GAP_PX = 12.0

/** Space a divider takes in the bar (1 px line + 4 px margin on each side, see theme.css). */
internal const val CONTROL_BAR_DIVIDER_FOOTPRINT_PX = 9.0

/** Fallback width of a control that was never measured (44 px minimum target + border). */
internal const val CONTROL_BAR_FALLBACK_WIDTH_PX = 46.0

/** One shown control of the bar with its measured [width] and the index of the [group] it sits in. */
internal data class ControlMeasure<S>(
    val slot: S,
    val width: Double,
    val group: Int,
)

/**
 * Pure: which slots move into the sheet so that the shown controls (plus the gaps and the dividers between non-empty groups) fit in
 * [available]. Slots move strictly in [order]; a slot that is not among [visible] is skipped (nothing to move).
 *
 * [moreWidth] (default 0 = the "Mehr" control is a slot of its own and always there): the width of a "Mehr" control that only appears
 * once the first slot has moved. It sits in [moreGroup], which then counts as a shown group.
 */
internal fun <S> controlBarOverflow(
    available: Double,
    visible: List<ControlMeasure<S>>,
    order: List<S>,
    gap: Double,
    dividerWidth: Double,
    exitGroup: Int,
    containerGroups: Set<Int> = emptySet(),
    exitGap: Double = CONTROL_BAR_EXIT_GAP_PX,
    moreWidth: Double = 0.0,
    moreGroup: Int = -1,
): Set<S> {
    val moved = linkedSetOf<S>()

    fun total(): Double {
        val shown = visible.filter { it.slot !in moved }
        if (shown.isEmpty()) return 0.0
        val moreShown = moreWidth > 0.0 && moved.isNotEmpty()
        val shownGroups = (shown.map { it.group } + (if (moreShown) listOf(moreGroup) else emptyList())).distinct()
        val groups = shownGroups.size
        // A group wrapper that stays rendered although all its controls moved is an empty flex item: it still takes a column gap.
        val emptyContainers = containerGroups.count { it !in shownGroups }
        val items = shown.size + (if (moreShown) 1 else 0) + (groups - 1) + emptyContainers
        // V1.9.72: inside the exit group the controls sit exitGap apart instead of the bar's gap.
        val exitItems = shown.count { it.group == exitGroup }
        val exitExtra = (exitGap - gap) * maxOf(0, exitItems - 1)
        return shown.sumOf { it.width } + (if (moreShown) moreWidth else 0.0) + (groups - 1) * dividerWidth + gap * (items - 1) + exitExtra
    }
    for (slot in order) {
        if (total() <= available) break
        if (visible.any { it.slot == slot }) moved += slot
    }
    return moved
}

/** `Widget.setAttribute` re-renders on every call; only a real change is written. */
internal fun Widget.setAttrIfChanged(
    name: String,
    value: String,
) {
    if (getAttribute(name) != value) setAttribute(name, value)
}

/** One control of a bar that [ControlBarOverflow] may move into the sheet. [twin] is its labelled counterpart in the sheet. */
internal data class ControlBarSlot<S>(
    val slot: S,
    val primary: Button,
    val twin: Button?,
    /** Mirror `aria-pressed` of the primary onto the twin (panel toggles); verbs that carry their state in the label need no mirror. */
    val mirrorPressed: Boolean = false,
)

/**
 * Measures the bar (ResizeObserver, started lazily WITHOUT a hook through [ensureObserving]), moves slots that do not fit into the
 * sheet and mirrors the state of a moved control to its twin. A moved primary is hidden by a CSS class (never `hide()`: its owner keeps
 * control over its own visibility); a twin is shown only while its primary is moved AND wanted.
 *
 * Only a change of the moved set is written; widths of the controls are cached (a hidden control cannot be measured), the fallback
 * is [CONTROL_BAR_FALLBACK_WIDTH_PX]. That keeps the bar from flickering.
 */
internal class ControlBarOverflow<S>(
    private val bar: Widget,
    slots: List<ControlBarSlot<S>>,
    /** A divider and the group it sits in front of. */
    private val dividers: List<Pair<Span, Int>>,
    private val groupOf: (S) -> Int,
    private val order: List<S>,
    private val exitGroup: Int,
    private val overflowedClass: String,
    private val onChanged: (moved: Set<S>) -> Unit,
    /** Groups whose controls sit in a wrapper element that stays rendered (and takes a column gap) even when empty. */
    private val containerGroups: Set<Int> = emptySet(),
    /** Width of a "Mehr" control that appears only while something is moved; 0 = there is none (the conference bar). */
    private val moreWidth: Double = 0.0,
    private val moreGroup: Int = -1,
    /** Shown while [moved] is not empty, hidden otherwise. */
    private val moreButton: Button? = null,
) {
    private val slots: MutableList<ControlBarSlot<S>> = slots.toMutableList()
    private var observer: ResizeObserver? = null
    private val widths = mutableMapOf<S, Double>()
    private var moved: Set<S> = emptySet()
    private val twinState = mutableMapOf<S, Triple<Boolean, Boolean, Boolean>>()
    private val dividerState = mutableMapOf<Span, Boolean>()
    private var disposed = false

    /** Adds a control that came into being after the overflow (the encounter room builds its device controls in `bind`). */
    fun register(slot: ControlBarSlot<S>) {
        slots += slot
    }

    fun moved(): Set<S> = moved

    /** Idempotent. Needs the element of the bar, so it simply tries again on the next call while it does not exist. */
    fun ensureObserving() {
        if (disposed || observer != null) return
        val element = bar.getElement() ?: return
        observer = ResizeObserver { _, _ -> recompute() }.also { it.observe(element) }
        recompute()
    }

    fun dispose() {
        disposed = true
        observer?.disconnect()
        observer = null
    }

    fun recompute() {
        if (disposed) return
        val element = bar.getElement() ?: return
        if (element.clientWidth <= 0) return
        for (s in slots) {
            val el = s.primary.getElement()
            if (el != null && el.offsetWidth > 0) {
                // The bounding box excludes CSS margins, the row still has to hold them.
                val cs = window.getComputedStyle(el)
                widths[s.slot] = el.getBoundingClientRect().width + parsePx(cs.marginLeft) + parsePx(cs.marginRight)
            }
        }
        val style = window.getComputedStyle(element)
        val padding = parsePx(style.paddingLeft) + parsePx(style.paddingRight)
        val gap = style.columnGap.removeSuffix("px").toDoubleOrNull() ?: 6.0
        val measures =
            slots.filter { it.primary.visible }.map {
                ControlMeasure(it.slot, widths[it.slot] ?: CONTROL_BAR_FALLBACK_WIDTH_PX, groupOf(it.slot))
            }
        val newMoved =
            controlBarOverflow(
                available = element.clientWidth - padding,
                visible = measures,
                order = order,
                gap = gap,
                dividerWidth = CONTROL_BAR_DIVIDER_FOOTPRINT_PX,
                exitGroup = exitGroup,
                containerGroups = containerGroups,
                moreWidth = moreWidth,
                moreGroup = moreGroup,
            )
        apply(newMoved)
    }

    private fun apply(newMoved: Set<S>) {
        val shownGroups = mutableSetOf<Int>()
        for (s in slots) {
            val isMoved = s.slot in newMoved
            val wanted = s.primary.visible
            if (wanted && !isMoved) shownGroups += groupOf(s.slot)
            if (isMoved != (s.slot in moved)) {
                if (isMoved) s.primary.addCssClass(overflowedClass) else s.primary.removeCssClass(overflowedClass)
            }
            val twin = s.twin ?: continue
            val twinShown = isMoved && wanted
            val state = Triple(twinShown, s.primary.disabled, s.primary.getAttribute("aria-pressed") == "true")
            if (twinState[s.slot] == state) continue
            twinState[s.slot] = state
            if (twin.visible != twinShown) {
                if (twinShown) {
                    twin.show()
                } else {
                    twin.hide()
                }
            }
            twin.disabled = state.second
            if (s.mirrorPressed) twin.setAttrIfChanged("aria-pressed", state.third.toString())
        }
        for ((divider, beforeGroup) in dividers) {
            val show = beforeGroup in shownGroups && shownGroups.any { it < beforeGroup }
            if (dividerState[divider] == show) continue
            dividerState[divider] = show
            if (divider.visible != show) {
                if (show) {
                    divider.show()
                } else {
                    divider.hide()
                }
            }
        }
        val more = moreButton
        if (more != null) {
            val wantMore = newMoved.isNotEmpty()
            if (more.visible != wantMore) {
                if (wantMore) {
                    more.show()
                } else {
                    more.hide()
                }
            }
        }
        if (newMoved != moved) {
            moved = newMoved
            onChanged(newMoved)
        }
    }

    private fun parsePx(value: String): Double = value.removeSuffix("px").toDoubleOrNull() ?: 0.0
}
