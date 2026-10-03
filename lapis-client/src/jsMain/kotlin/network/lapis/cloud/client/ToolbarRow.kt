package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span

/**
 * Filter / tool row: labelled fields and buttons on ONE row, aligned at the BOTTOM edge of their controls (V1.9.43, guideline R56).
 *
 * ## Why not `hPanel { align-items-center }`
 *
 * KVision wraps every labelled field in `div.form-group.kv-mb-3`: label (24 px) above the control, 16 px margin below. With
 * `align-items: center` the flex item for the field is 78 px high and the 38 px button is centred in it, so the button sits 8 px
 * higher than the control it belongs to (measured in `ToolbarGeometryDomTest`: select bottom 70, button bottom 62). The CSS class
 * `.lapis-toolbar` (theme.css) aligns the items at the bottom and removes that margin, so controls and buttons share one baseline.
 *
 * A plain [Div] (no `hPanel`): `hPanel` writes its own inline flex styles that would compete with the class.
 */
fun Container.lapisToolbar(init: Div.() -> Unit = {}): Div = div(className = "lapis-toolbar") { init() }

/** Plain text next to a button inside a [lapisToolbar]: vertically centred in a box of control height. */
fun Container.toolbarText(text: String): Span = span(text, className = "lapis-toolbar-text")
