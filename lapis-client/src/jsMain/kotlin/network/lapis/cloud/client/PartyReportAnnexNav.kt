package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import org.w3c.dom.HTMLElement

/**
 * V1.9.283 (R3b) -- the blocks of the tab "Ausweise". The member count (E) lives inside block A (one form, one save button); its entry in the
 * jump bar opens block A and scrolls to the member count.
 */
internal enum class AnnexBlock(
    val anchor: String,
) {
    A("partg-block-a"),
    MEMBERS("partg-block-members"),
    B("partg-block-b"),
    C("partg-block-c"),
    D("partg-block-d"),
    F("partg-block-f"),
    ;

    /** The block that holds the collapsible region: the member count is part of A. */
    val host: AnnexBlock get() = if (this == MEMBERS) A else this
}

/** Which block a hint belongs to; `null` for a hint that belongs to no block of this tab (P05, the notes). */
internal fun partgFindingBlock(finding: PartgFindingDto): AnnexBlock? =
    when (finding.code) {
        PartgFindingCode.P03 -> AnnexBlock.A
        PartgFindingCode.P06 ->
            when (finding.gap) {
                "DEDUCTIONS" -> AnnexBlock.A
                "MEMBER_COUNT" -> AnnexBlock.MEMBERS
                else -> null
            }
        PartgFindingCode.P01, PartgFindingCode.P02, PartgFindingCode.P04 -> AnnexBlock.B
        PartgFindingCode.P07, PartgFindingCode.P08, PartgFindingCode.P09 -> AnnexBlock.C
        PartgFindingCode.P10 -> AnnexBlock.F
        PartgFindingCode.P11, PartgFindingCode.P12, PartgFindingCode.P13 -> AnnexBlock.D
        PartgFindingCode.P05 -> null
    }

/** The number of hints per block for one unit; read from the hints that are already loaded, no extra call. */
internal fun partgBlockCounts(
    findings: List<PartgFindingDto>,
    unitKey: String,
): Map<AnnexBlock, Int> =
    findings
        .filter { it.unitKey == unitKey }
        .mapNotNull { partgFindingBlock(it) }
        .groupingBy { it }
        .eachCount()

/** The block that is open on load: [focus] when a hint link asked for it, else the one with the most hints, else A. */
internal fun partgDefaultOpenBlock(
    counts: Map<AnnexBlock, Int>,
    focus: AnnexBlock?,
): AnnexBlock {
    if (focus != null) return focus.host
    val merged = counts.entries.groupBy({ it.key.host }, { it.value }).mapValues { it.value.sum() }
    return merged.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: AnnexBlock.A
}

/**
 * One open form for the WHOLE tab (donor, sponsor, youth organisation): opening another one asks first. [generation] counts the forms, so that
 * a late focus call or a debounce timer of a form that is closed (or replaced) by now does nothing; [dispose] clears the timer of the open form.
 */
internal class AnnexEditorGuard {
    var open = false
    var generation = 0
    var dispose: (() -> Unit)? = null
    private var remover: (() -> Unit)? = null

    /** Closes a form that is open, then marks a new one as open; [remove] takes this form's widgets out of the page when it is closed later. */
    fun begin(remove: () -> Unit) {
        close()
        remover = remove
        open = true
    }

    fun close() {
        generation++
        dispose?.invoke()
        dispose = null
        remover?.invoke()
        remover = null
        open = false
    }
}

/** A disclosure block: a button with `aria-expanded` / `aria-controls` above a region that is hidden while closed. */
internal class CollapsibleBlock(
    private val toggle: Button,
    private val region: SimplePanel,
) {
    var isOpen = false
        private set

    fun setOpen(open: Boolean) {
        isOpen = open
        toggle.setAttribute("aria-expanded", open.toString())
        // a CSS class, not `visible`: the content of a closed block must stay in the DOM (tables, forms) and keep its state
        if (open) region.removeCssClass("d-none") else region.addCssClass("d-none")
    }

    fun focusToggle() {
        (document.getElementById(toggle.id ?: return) as? HTMLElement)?.focus()
    }
}

internal fun SimplePanel.collapsibleBlock(
    block: AnnexBlock,
    title: String,
    open: Boolean,
    content: SimplePanel.() -> Unit,
): CollapsibleBlock {
    val wrap = simplePanel { id = block.anchor }
    wrap.addCssClass("mt-3")
    wrap.addCssClass("partg-annex-block")
    val regionId = block.anchor + "-region"
    val toggle = wrap.actionButton(ActionIcon.CHEVRON, title, style = ButtonStyle.LINK)
    toggle.id = block.anchor + "-toggle"
    toggle.setAttribute("aria-controls", regionId)
    toggle.addCssClass("fw-semibold")
    val region = wrap.simplePanel { id = regionId }
    region.content()
    val handle = CollapsibleBlock(toggle, region)
    handle.setOpen(open)
    toggle.onClick { handle.setOpen(!handle.isOpen) }
    return handle
}

private fun blockLabel(block: AnnexBlock): String =
    when (block) {
        AnnexBlock.MEMBERS -> tr("Mitglieder")
        else -> block.name
    }

/** The jump bar "A · Mitglieder · B · C · D · F": real buttons of at least 44 px, the count is plain text, not a colour. */
internal fun SimplePanel.annexNav(
    counts: Map<AnnexBlock, Int>,
    onJump: (AnnexBlock) -> Unit,
) {
    val nav = Tag(TAG.NAV)
    nav.setAttribute("aria-label", gettext("Ausweise"))
    nav.addCssClass("partg-annex-nav")
    add(nav)
    AnnexBlock.entries.forEach { block ->
        val count = counts[block] ?: 0
        val label = blockLabel(block)
        val button = Button(if (count > 0) "$label ($count)" else label, style = ButtonStyle.OUTLINESECONDARY)
        button.id = block.anchor + "-nav"
        button.addCssClass("lapis-action-btn")
        button.addCssClass("partg-annex-nav-button")
        if (count > 0) {
            button.setAttribute("aria-label", gettext("%1, %2 Hinweise", label, count))
        }
        button.onClick { onJump(block) }
        nav.add(button)
    }
}
