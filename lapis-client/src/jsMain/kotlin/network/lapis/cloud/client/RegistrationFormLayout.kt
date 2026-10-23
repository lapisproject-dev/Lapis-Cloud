package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AgreementTextFormat

/**
 * V1.9.134 -- the building blocks of the wide registration form: a two-column field grid (one field per cell, so the DOM order IS the row
 * order and no field needs a `tabindex`) and the reading frame of the agreement text. The layout itself lives in `theme.css`
 * (`lapis-form-grid`, `lapis-form-cell`, `lapis-agreement-frame`); this file only names the classes.
 */
internal const val FORM_GRID_CLASS = "lapis-form-grid"
internal const val FORM_CELL_CLASS = "lapis-form-cell"
internal const val FORM_CELL_FULL_CLASS = "lapis-form-cell--full"
internal const val AGREEMENT_FRAME_CLASS = "lapis-agreement-frame"

/** The grid the fields of a registration form are built into: one column below 768 px, two above. */
internal fun Container.registrationFieldGrid(): Div = div { addCssClass(FORM_GRID_CLASS) }

/** One grid cell for one field (pass it as `host` to `textField`, `passwordField`, `selectField`); [full] spans both columns. */
internal fun Div.gridCell(full: Boolean = false): Div =
    div {
        addCssClass(FORM_CELL_CLASS)
        if (full) addCssClass(FORM_CELL_FULL_CLASS)
    }

/**
 * The reading area of an agreement text: a focusable region (`tabindex=0`, `role=region`, [ariaLabel]) that holds the whole text. Above 768 px
 * it is a bounded, scrollable box (R59, class E3d); below, it grows with the text. Attributes go through `setAttribute`, never an insert hook.
 */
internal fun Container.agreementFrame(
    text: String,
    ariaLabel: String,
    format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    renderedHtml: String? = null,
): Div =
    div {
        addCssClass(AGREEMENT_FRAME_CLASS)
        setAttribute("tabindex", "0")
        setAttribute("role", "region")
        setAttribute("aria-label", ariaLabel)
        renderAgreementBody(text = text, format = format, renderedHtml = renderedHtml)
    }

/**
 * The heading, the reading frame and the "please read it all" hint of one agreement text, replaceable in place: [show] again after the
 * server answered "the text changed" swaps heading, frame and hint without touching the form's fields. [heading] builds the visible
 * heading (and the frame's accessible name) from the version label.
 */
internal class AgreementTextSection(
    parent: Container,
    private val heading: (String) -> String,
) {
    private val host: Div = parent.div()

    fun show(
        version: String,
        text: String,
        format: AgreementTextFormat = AgreementTextFormat.PLAIN,
        renderedHtml: String? = null,
    ) {
        host.removeAll()
        val title = heading(version)
        host.h2(title) { addCssClass("h5") }
        host.agreementFrame(text = text, ariaLabel = title, format = format, renderedHtml = renderedHtml)
        host.p(tr("Bitte lesen Sie den vollständigen Text.")) { addCssClasses("text-muted small mt-1") }
    }
}
