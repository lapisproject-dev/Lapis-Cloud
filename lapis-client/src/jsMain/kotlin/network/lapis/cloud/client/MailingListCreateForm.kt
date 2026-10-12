package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService

/**
 * V1.9.49 (R36B): inline mini-form -- name (required) + description (optional), built into the host of a collapsible create form
 * ([collapsibleCreateForm]) behind the title-row button "Neue Mailingliste" of the admin section. On success, both the self-service
 * panel above (subscriber counts changed) and the "Liste verwalten" selector below need a refresh; [onCreated] lets the caller
 * re-render the selector so the new list is immediately pickable without a full page reload. The old bold title line is gone: the
 * button carries the meaning.
 *
 * **V1.9.111 -- two kinds of list.** A segmented choice at the top ("Manuell" is the default, exactly the form of old) switches to
 * "Automatisch (nach Regel)": the rule builder appears ([renderRuleBuilder]) together with the "Pflichtliste" switch. The kind and the
 * mandatory flag cannot be changed after creation -- the form says so right under the choice and under the switch. Switching back to
 * "Manuell" tears the builder down (its fields leave the form, so a half-filled rule can never block a manual list).
 */
internal fun SimplePanel.renderCreateMailingListForm(
    refreshSelfService: () -> Unit,
    collapse: ((Boolean) -> Unit)? = null,
    onCreated: (newListId: String) -> Unit,
): FormSnapshot {
    val panel = vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    var kind = MailingListKind.MANUAL
    var builder: RuleBuilderHandle? = null
    var mandatoryField: LapisField? = null
    lateinit var autoHost: SimplePanel

    // Formular-Grammatik (V1.4.29, W4b): Name ist Pflicht, Beschreibung optional => Fall (a).
    val form = panel.lapisForm()
    form.panel.segmentedControl(
        options =
            listOf(
                MailingListKind.MANUAL to gettext("Manuell"),
                MailingListKind.DYNAMIC to gettext("Automatisch (nach Regel)"),
            ),
        selected = MailingListKind.MANUAL,
        ariaLabel = tr("Art der Liste"),
    ) { chosen ->
        kind = chosen
        if (chosen == MailingListKind.DYNAMIC) {
            AppScope.launch {
                buildAutomaticPart(form, autoHost, onBuilt = { b, m ->
                    builder = b
                    mandatoryField = m
                })
            }
        } else {
            builder?.dispose()
            mandatoryField?.detach()
            builder = null
            mandatoryField = null
            autoHost.removeAll()
        }
    }
    form.panel.div(tr("Die Art lässt sich später nicht ändern.")) { addCssClasses("text-muted small") }
    val nameField = form.textField(label = tr("Name"), required = true)
    val descriptionField = form.textField(label = tr("Beschreibung"))
    autoHost = form.panel.vPanel(spacing = 6)
    form.crossFieldRule {
        val problem = if (kind == MailingListKind.DYNAMIC) builder?.validationError() else null
        if (problem != null) FieldCheck.Invalid(problem) else FieldCheck.Ok
    }
    val createButton = newActionButton(ActionIcon.ADD, tr("Anlegen"), ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })

    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val description = descriptionField.value.trim().takeIf { it.isNotBlank() }
            val current = builder
            val result =
                if (kind == MailingListKind.DYNAMIC && current != null) {
                    val ruleJson = MemberRuleCodec.encode(current.rule())
                    val mandatory = mandatoryField?.value == "true"
                    guarded { rpcService<IMailingService>().createDynamicMailingList(name, description, ruleJson, mandatory) }
                } else {
                    guarded { rpcService<IMailingService>().createMailingList(name, description) }
                }
            if (result != null) {
                notifySuccess(
                    if (result.kind == MailingListKind.DYNAMIC) {
                        gettext("Automatische Mailingliste \"%1\" wurde angelegt.", name)
                    } else {
                        gettext("Mailingliste \"%1\" wurde angelegt.", name)
                    },
                )
                collapse?.invoke(true)
                refreshSelfService()
                onCreated(result.id)
            }
        }
    }
    return form.snapshot()
}

/** Loads the committees and tiers and builds the rule builder plus the "Pflichtliste" switch into [host]. */
private suspend fun buildAutomaticPart(
    form: LapisForm,
    host: SimplePanel,
    onBuilt: (RuleBuilderHandle, LapisField) -> Unit,
) {
    // V1.9.112: only committees a rule may reference are offered (manual ones); the server refuses the others
    val committees: List<CommitteeDto> = committeesForRules(guarded { rpcService<IGovernanceService>().listCommittees(true) }.orEmpty())
    val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty()
    host.removeAll()
    // Read at preview time (the first preview is scheduled while the builder is still being built, before the switch exists).
    var mandatoryOn = false
    val builder =
        renderRuleBuilder(
            form = form,
            host = host,
            initial = MemberRule(op = RuleOp.ALL, conditions = emptyList()),
            committees = committees,
            tiers = tiers,
            wording = mailingRuleWording(),
            previewer = mailingPreviewer(listId = null, mandatory = { mandatoryOn }),
        )
    val mandatory =
        form.checkField(
            label = tr("Pflichtliste (keine Abmeldung möglich)"),
            hint =
                tr(
                    "Nur für Vereinsmitteilungen wie die Einladung zur Mitgliederversammlung. " +
                        "Das Merkmal lässt sich später nicht ändern.",
                ),
            host = host,
        )
    mandatory.subscribe { state ->
        mandatoryOn = state == "true"
        builder.refresh()
    }
    onBuilt(builder, mandatory)
}
