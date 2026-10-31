package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerServerDto
import network.lapis.cloud.shared.domain.PartnerServerOverviewDto
import network.lapis.cloud.shared.rpc.IPartnerServerService

/**
 * V1.9.244 "Partnerserver P1" -- `/organization/partner-servers`, ADMIN only (the server gates every call). Reached from the section
 * "Partnerserver" of the Organisation page; there is no sidebar entry of its own.
 *
 * The form is "Adresse, Name, Prüfen und speichern": ONE request to the partner is made, and the partner is stored only if it answered with a
 * valid feed. Everything that comes from a foreign server (names, hosts) is set ONLY through the `untrusted*` helpers -- it is data, never markup.
 */
fun renderPartnerServersScreen(container: SimplePanel) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.ORGANIZATION, tr("Zur Organisation"))
    root.pageHeader(
        tr("Partnerserver"),
        subtitle = tr("Wir holen stündlich die öffentlichen Veranstaltungen dieser Server ab. Die Partner müssen nichts einrichten."),
    )
    // The sentence after a successful save survives the reload of the list that the save causes.
    var savedMessage: String? = null
    lateinit var data: DataSection
    data =
        root.dataSection<PartnerServerOverviewDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IPartnerServerService>().list() } },
            render = { panel, overview ->
                renderPartnerServers(panel, overview, savedMessage, onSaved = { savedMessage = it }) { data.reload() }
            },
        )
    data.reload()
}

private fun renderPartnerServers(
    panel: SimplePanel,
    overview: PartnerServerOverviewDto,
    savedMessage: String?,
    onSaved: (String?) -> Unit,
    reload: () -> Unit,
) {
    renderAddForm(panel, savedMessage, onSaved, reload)
    panel.h2(tr("Eingetragene Partner")) { addCssClasses("h5 mt-3") }
    if (overview.partners.isEmpty()) {
        panel.p(tr("Noch kein Partner eingetragen.")) { addCssClasses("text-muted") }
    }
    val list = panel.div { setAttribute("role", "list") }
    overview.partners.forEach { renderPartnerRow(list, it, reload) }
    renderHiddenSwitch(panel, overview.partnerListHidden, reload)
    renderBlocklist(panel, overview.blocklist, reload)
}

private fun renderAddForm(
    panel: SimplePanel,
    savedMessage: String?,
    onSaved: (String?) -> Unit,
    reload: () -> Unit,
) {
    val form = panel.lapisForm()
    val address =
        form.textField(label = tr("Adresse des Servers"), required = true, hint = tr("Zum Beispiel partner.beispiel.de"), init = {
            it.placeholder = "partner.beispiel.de"
            it.setAttribute("spellcheck", "false")
            it.setAttribute("autocapitalize", "none")
        })
    val name = form.textField(label = tr("Anzeigename (optional)"))
    val save = newActionButton(ActionIcon.SAVE, tr("Prüfen und speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = save, enterSubmits = true)
    // Mounted with the form so a screen reader announces its changes: the sentence after a save, as a status.
    val status =
        panel.div {
            addCssClasses("text-success small")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    if (savedMessage != null) {
        status.content = savedMessage
        onSaved(null)
    }
    save.onClick {
        if (!PartnerServersLogic.canProbe(address.value)) return@onClick
        form.submit(save) {
            val result =
                guarded { rpcService<IPartnerServerService>().probeAndSave(address.value.trim(), name.value.trim().ifEmpty { null }) }
                    ?: return@submit
            if (result.saved) {
                onSaved(PartnerServersTexts.saved(result.host.orEmpty(), result.eventCount))
                reload()
            } else {
                form.showFormError(PartnerServersTexts.probeFailure(result))
            }
        }
    }
}

private fun renderPartnerRow(
    list: Container,
    partner: PartnerServerDto,
    reload: () -> Unit,
) {
    val row =
        list.div {
            addCssClasses("partner-row border-bottom py-2")
            if (partner.blocked) addCssClass("partner-row--blocked")
            setAttribute("role", "listitem")
        }
    val head = row.div { addCssClasses("d-flex flex-wrap align-items-start gap-2") }
    val info = head.div { addCssClass("flex-grow-1") }
    info.untrustedDiv(PartnerServersLogic.title(partner), className = "fw-bold")
    if (PartnerServersLogic.showsHostLine(partner)) info.untrustedSpan(partner.host, className = "font-monospace small text-muted")
    val kind = PartnerServersLogic.statusKind(partner)
    val symbol = kind.symbol
    info.div(className = "partner-status small") {
        span(symbol, className = "me-1") { setAttribute("aria-hidden", "true") }
        span(PartnerServersLogic.statusText(partner))
    }
    if (partner.blocked) {
        info.div(PartnerServersLogic.blockedNote(), className = "text-muted small")
    } else {
        partner.lastFetchAt?.let { info.div(gettext("Letzter Abruf: %1", formatSystemDateTime(it)), className = "text-muted small") }
        info.div(gettext("Veranstaltungen: %1", partner.eventCount), className = "text-muted small")
        partner.lastError?.let { info.div(PartnerServersTexts.fetchError(it), className = "text-danger small") }
    }
    val actions = head.div { addCssClasses("d-flex align-items-center gap-2") }
    val active = actions.checkBox(value = partner.active, label = tr("Aktiv"))
    active.onClick {
        val wanted = active.value == true
        active.disabled = true
        runGuardedAction(button = null) {
            guarded { rpcService<IPartnerServerService>().setActive(partner.id, wanted) }
            reload()
        }
    }
    actions.actionButton(ActionIcon.DELETE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER, small = true) {
        onClick {
            confirmDialog(
                title = tr("Partner entfernen?"),
                message = tr("Partner und zwischengespeicherte Veranstaltungen entfernen?"),
                confirmLabel = tr("Entfernen"),
                confirmIcon = ActionIcon.DELETE,
            ) {
                runGuardedAction(button = null) {
                    guarded { rpcService<IPartnerServerService>().remove(partner.id) }
                    reload()
                }
            }
        }
    }
    // The group name is the host, so the "Aktiv" box and the buttons of every row have a distinguishable context for assistive technology.
    row.setAttribute("aria-label", partner.host)
}

private fun renderHiddenSwitch(
    panel: SimplePanel,
    hidden: Boolean,
    reload: () -> Unit,
) {
    val box = panel.vPanel(spacing = 2) { addCssClass("mt-3") }
    val toggle = box.checkBox(value = hidden, label = tr("Eigene Partnerliste im Feed ausblenden"))
    box.div(tr("Andere Server sehen dann nicht, mit wem Sie verbunden sind."), className = "text-muted small")
    toggle.onClick {
        val wanted = toggle.value == true
        toggle.disabled = true
        runGuardedAction(button = null) {
            guarded { rpcService<IPartnerServerService>().setPartnerListHidden(wanted) }
            reload()
        }
    }
}

private fun renderBlocklist(
    panel: SimplePanel,
    blocks: List<PartnerBlockDto>,
    reload: () -> Unit,
) {
    val details = Tag(TAG.DETAILS, className = "lapis-settings-section border rounded mt-3")
    if (PartnerServersLogic.blocklistOpen(blocks.size)) details.setAttribute("open", "")
    val head = Tag(TAG.SUMMARY, className = "lapis-settings-summary")
    head.add(Span("⊘", className = "me-2").apply { setAttribute("aria-hidden", "true") })
    head.add(Span(tr("Sperrliste"), className = "lapis-settings-title"))
    details.add(head)
    val body = SimplePanel(className = "lapis-settings-body p-3")
    details.add(body)
    panel.add(details)

    body.p(
        tr("Gesperrte Hosts werden nie abgerufen und nie angezeigt. Erlaubt sind ein Servername oder *.servername für alle Unterdomänen."),
    ) {
        addCssClasses("text-muted small")
    }
    val form = body.lapisForm()
    val pattern =
        form.textField(
            label = tr("Host oder Muster"),
            required = true,
            hint = tr("Zum Beispiel spam.beispiel.de oder *.beispiel.de"),
        )
    val add = newActionButton(ActionIcon.REVOKE, tr("Sperren"), ButtonStyle.PRIMARY)
    form.buttons(primary = add, enterSubmits = true)
    add.onClick {
        form.submit(add) {
            val result = guarded { rpcService<IPartnerServerService>().addBlock(pattern.value.trim()) } ?: return@submit
            if (result.invalid) {
                form.showFormError(tr("Bitte einen Servernamen oder *.servername angeben."))
            } else {
                reload()
            }
        }
    }
    blocks.forEach { block ->
        val row = body.div { addCssClasses("d-flex align-items-center gap-2 border-bottom py-1") }
        row.untrustedSpan(block.pattern, className = "font-monospace flex-grow-1")
        row.actionButton(ActionIcon.UNDO, tr("Entsperren"), small = true) {
            onClick {
                runGuardedAction(button = this) {
                    guarded { rpcService<IPartnerServerService>().removeBlock(block.id) }
                    reload()
                }
            }
        }
    }
}
