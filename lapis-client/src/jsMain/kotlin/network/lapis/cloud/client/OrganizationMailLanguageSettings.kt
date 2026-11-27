package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.332 -- the ADMIN setting "Sprache der E-Mails" (section "Allgemein" of the page Organisation). It is the language of the mails the
 * instance sends in ONE language (today: the receipt to a tender winner and the notice to the board about a failed payout). Saved through
 * its own RPC (`updateMailLanguage`: ADMIN, validated, audited on change); nothing is saved when the select merely changes.
 */

/** The eight supported mail languages in a fixed order, as (code, endonym). Endonyms are not translated. Mirrors `MembershipDecisionTexts.SUPPORTED` on the server (a server test guards it). */
internal val MAIL_LANGUAGES: List<Pair<String, String>> =
    listOf(
        "de" to "Deutsch",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "ru" to "Русский",
    )

/** The endonym of [code], or `null` for a code this client does not know. */
internal fun mailLanguageEndonym(code: String): String? = MAIL_LANGUAGES.firstOrNull { it.first == code }?.second

/** Renders the setting into [panel]; [onChanged] lets the page refresh its summary line. */
internal fun renderOrganizationMailLanguageSettings(
    panel: SimplePanel,
    onChanged: () -> Unit = {},
) {
    panel.p(
        tr(
            "Die Sprache, in der die Instanz ihre E-Mails versendet, jeweils in einer einzigen Sprache. Ausgenommen sind die Mitteilungen zu Mitgliedsanträgen (in der Sprache des Antrags), Rundschreiben mit eigener Sprache und die Veranstaltungs-E-Mails, die derzeit nur auf Deutsch versendet werden.",
        ),
        className = "text-muted small",
    )
    panel
        .vPanel(spacing = 6)
        .dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { body, settings -> renderMailLanguageForm(body, settings.mailLanguage, onChanged) },
        ).reload()
}

private fun renderMailLanguageForm(
    panel: SimplePanel,
    initial: String,
    onChanged: () -> Unit,
) {
    val form = panel.lapisForm()
    var saved = if (mailLanguageEndonym(initial) != null) initial else "de"
    val field =
        form.selectField(
            label = tr("Sprache der E-Mails"),
            options = MAIL_LANGUAGES,
            value = saved,
            required = true,
            rule = { code ->
                if (mailLanguageEndonym(code) != null) FieldCheck.Ok else FieldCheck.Invalid(gettext("Unbekannte Sprache"))
            },
        )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val result = guarded { rpcService<IOrganizationSettingsService>().updateMailLanguage(field.value) }
            if (result == null) {
                // Rejected (or failed): the select goes back to the stored value.
                field.setValue(saved)
            } else {
                saved = result.mailLanguage
                field.setValue(saved)
                notifySuccess(tr("Sprache der E-Mails gespeichert."))
                onChanged()
            }
        }
    }
}
