package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.201 -- the ADMIN setting "Ausschreibungen: Standard-Sichtbarkeit der Angebote" (wave 1 of the tenders has no screen of its own yet; this is its
 * only client part). The chosen value only PRE-FILLS the form when a tender is created; the visibility of a published tender never changes. Saved
 * through its own RPC (ADMIN only, audited on change); a refusal leaves the select where the server has it.
 */

/** The options of the select, in the order of the enum; the values are the enum names, the labels are translated at render time. */
internal fun tenderVisibilityOptions(): List<Pair<String, String>> =
    TenderBidVisibility.entries.map {
        it.name to tenderVisibilityLabel(it)
    }

/** The label of a visibility (V1.9.202: "Nur eigenes Angebot sichtbar"); the sentences that say what it means are [tenderVisibilityExplanation]. */
internal fun tenderVisibilityLabel(visibility: TenderBidVisibility): String =
    when (visibility) {
        TenderBidVisibility.HIDDEN_UNTIL_DEADLINE -> gettext("Verdeckt bis zur Frist")
        TenderBidVisibility.OPEN -> gettext("Offen")
        TenderBidVisibility.OWN_ONLY -> gettext("Nur eigenes Angebot sichtbar")
    }

/** Renders the card into [root]; the caller guarantees the viewer is an ADMIN (the server enforces it on the call). */
internal fun renderTenderDefaultsCard(root: SimplePanel) {
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") }
    section.h2(tr("Ausschreibungen")) { addCssClass("h5") }
    val host = section.vPanel(spacing = 6)
    host
        .dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { panel, dto -> renderTenderDefaultsForm(panel, dto.tenderDefaultVisibility) },
        ).reload()
}

private fun renderTenderDefaultsForm(
    panel: SimplePanel,
    initial: TenderBidVisibility,
) {
    var lastSaved = initial
    val form = panel.lapisForm()
    val field =
        form.selectField(
            label = tr("Standard-Sichtbarkeit der Angebote"),
            options = tenderVisibilityOptions(),
            value = initial.name,
            required = true,
            rule = { v ->
                if (TenderBidVisibility.entries.any { it.name == v }) FieldCheck.Ok else FieldCheck.Invalid(gettext("Unbekannte Auswahl"))
            },
        )
    // V1.9.202: what the chosen visibility means, changing live with the select (the same sentences as in the tender form).
    val explanation = form.panel.p(tenderVisibilityExplanation(initial), className = "text-muted small mb-0")
    field.subscribe { value ->
        TenderBidVisibility.entries.firstOrNull { it.name == value }?.let { explanation.content = tenderVisibilityExplanation(it) }
    }
    form.panel.p(
        tr("Gilt als Vorbelegung beim Anlegen einer Ausschreibung; nach dem Veröffentlichen nicht mehr änderbar."),
        className = "text-muted small",
    )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val wanted = TenderBidVisibility.entries.first { it.name == field.value }
            val saved = guarded { rpcService<IOrganizationSettingsService>().updateTenderDefaultVisibility(wanted) }
            if (saved == null) {
                field.setValue(lastSaved.name)
            } else {
                lastSaved = wanted
                notifySuccess(tr("Gespeichert."))
            }
        }
    }
}
