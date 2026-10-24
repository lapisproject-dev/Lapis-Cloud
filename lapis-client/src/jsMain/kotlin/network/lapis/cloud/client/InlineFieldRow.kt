package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div

/**
 * V1.9.200: the three small layout helpers behind "label next to the field" rows and filter rows.
 *
 * - [inlineFieldRow]: one wrapping row, each labelled field has its label BESIDE the control (CSS only, the KVision `form-group` stays).
 * - `filterRow(hint)` (DataScreenLayout.kt): labels ABOVE the controls, one shared bottom edge; an optional muted hint line stands above the row.
 * - [inlineFieldGroup]: label, field and button that must wrap as a unit ("Boost-Betrag (LTR)", the field and "Boosten").
 *
 * The heights come from the form-control box itself (see `.lapis-inline-row` in theme.css), so a field, a select and a button in the same
 * row share one height and one centre line.
 */
internal fun Container.inlineFieldRow(
    className: String = "lapis-inline-row",
    block: Div.() -> Unit,
): Div = div(className = className) { block() }

/** A unit that wraps only as a whole: label, field and button never get torn apart by a line break. */
internal fun Container.inlineFieldGroup(block: Div.() -> Unit): Div = div(className = "lapis-inline-group") { block() }
