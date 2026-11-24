package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
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
import network.lapis.cloud.shared.domain.AuctionFederationSettingsDto
import network.lapis.cloud.shared.domain.CarpoolFederationSettingsDto
import network.lapis.cloud.shared.domain.DiscoveredPartnerServerDto
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
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
        root.dataSection<PartnerPageData>(
            isEmpty = { false },
            load = {
                val service = rpcService<IPartnerServerService>()
                val overview = guarded { service.list() }
                val recursion = overview?.let { guarded { service.getPartnerRecursionSettings() } }
                val discovered = recursion?.let { guarded { service.listDiscoveredPartnerServers() } }
                val carpool = discovered?.let { guarded { service.getCarpoolFederationSettings() } }
                val auction = carpool?.let { guarded { service.getAuctionFederationSettings() } }
                if (overview != null && recursion != null && discovered != null && carpool != null && auction != null) {
                    PartnerPageData(overview, recursion, discovered, carpool, auction)
                } else {
                    null
                }
            },
            render = { panel, page ->
                renderPartnerServers(panel, page, savedMessage, onSaved = { savedMessage = it }) { data.reload() }
            },
        )
    data.reload()
}

/** Everything the page shows, loaded together. */
private data class PartnerPageData(
    val overview: PartnerServerOverviewDto,
    val recursion: PartnerRecursionSettingsDto,
    val discovered: List<DiscoveredPartnerServerDto>,
    val carpool: CarpoolFederationSettingsDto,
    val auction: AuctionFederationSettingsDto,
)

private fun renderPartnerServers(
    panel: SimplePanel,
    page: PartnerPageData,
    savedMessage: String?,
    onSaved: (String?) -> Unit,
    reload: () -> Unit,
) {
    val overview = page.overview
    renderCarpoolExchange(panel, page.carpool, reload)
    renderAuctionExchange(panel, page.auction, reload)
    renderAddForm(panel, savedMessage, onSaved, reload)
    panel.h2(tr("Eingetragene Partner")) { addCssClasses("h5 mt-3") }
    if (overview.partners.isEmpty()) {
        panel.p(tr("Noch kein Partner eingetragen.")) { addCssClasses("text-muted") }
    }
    val list = panel.div { setAttribute("role", "list") }
    overview.partners.forEach { renderPartnerRow(list, it, page.carpool.enabled, page.auction.enabled, page.auction.sharedActive, reload) }
    renderHiddenSwitch(panel, overview.partnerListHidden, reload)
    renderRecursion(panel, page.recursion, page.discovered, reload)
    renderBlocklist(panel, overview.blocklist, reload)
}

/** The honesty sentence since wave 5: this server pulls and hands on (within the reach), and active members see the received short hints. */
private const val CARPOOL_EXCHANGE_PULL_NOTE =
    "Freigegebene Partner können die Kurzhinweise abrufen. " +
        "Dieser Server ruft die Kurzhinweise freigegebener Partner selbst ab " +
        "und gibt sie im Rahmen der Reichweite an freigegebene Partner weiter. " +
        "Aktive Mitglieder sehen die empfangenen Kurzhinweise (Tag, PLZ-Gebiete, Herkunft) auf der Mitfahrseite."

private const val CARPOOL_EXCHANGE_WITHDRAWAL_NOTE =
    "Zurückgezogene Kurzhinweise werden Partnern bis zu 14 Tage lang als zurückgezogen gemeldet, nur mit pseudonymer Kennung und Tag."

private const val CARPOOL_EXCHANGE_INFO =
    "Mitglieder entscheiden für jede Fahrt selbst, ob ein Kurzhinweis an freigegebene Partner geht: " +
        "das Datum und die ersten zwei Ziffern der Postleitzahlen von Start und Ziel. " +
        "Namen, Orte, Uhrzeiten und Notizen werden nie weitergegeben."

/** "Mitfahrzentrale-Austausch": the instance switch and the reach. The partners themselves are switched in their rows below. */
private fun renderCarpoolExchange(
    panel: SimplePanel,
    settings: CarpoolFederationSettingsDto,
    reload: () -> Unit,
) {
    panel.h2(tr("Mitfahrzentrale-Austausch")) { addCssClasses("h5 mt-1") }
    val box = panel.vPanel(spacing = 2)
    val toggle = box.checkBox(value = settings.enabled, label = tr("Fahrtangebote mit freigegebenen Partnern austauschen"))
    box.div(
        tr(CARPOOL_EXCHANGE_INFO),
        className = "text-muted small",
    )
    // The reach is offered only while the exchange is on (after the switch the page reloads and shows it).
    val reach =
        if (PartnerServersLogic.hopSelectVisible(settings.enabled)) {
            box.select(
                options =
                    listOf(
                        "1" to tr("Nur direkte Partner"),
                        "2" to tr("Auch Partner unserer Partner"),
                        "3" to tr("Eine Stufe weiter"),
                    ),
                value = PartnerServersLogic.hopSelection(settings.hopLimit).toString(),
                label = tr("Reichweite"),
            )
        } else {
            null
        }

    fun save() {
        toggle.disabled = true
        reach?.disabled = true
        val wantedReach = reach?.value?.toIntOrNull() ?: settings.hopLimit
        runGuardedAction(button = null) {
            guarded {
                rpcService<IPartnerServerService>().setCarpoolFederationSettings(
                    enabled = toggle.value == true,
                    hopLimit = wantedReach,
                )
            }
            reload()
        }
    }
    toggle.onClick { save() }
    // `subscribe` would call back at once with the current value (a reload loop); the DOM change event fires only on a choice by the person.
    reach?.onEvent { change = { save() } }
    box.div(tr(CARPOOL_EXCHANGE_PULL_NOTE), className = "text-muted small") { setAttribute("role", "status") }
    box.div(tr(CARPOOL_EXCHANGE_WITHDRAWAL_NOTE), className = "text-muted small")
    PartnerServersLogic.carpoolFeedSettingsLines(settings).forEach { box.div(it, className = "text-muted small") }
    // V1.9.318: the rejection causes, folded away (counts only, since the server started).
    if (settings.enabled) {
        val details = Tag(TAG.DETAILS, className = "small text-muted carpool-reject-reasons")
        details.add(Tag(TAG.SUMMARY, className = "text-muted").apply { add(Span(tr("Ablehnungsgründe"))) })
        val list = SimplePanel().apply { setAttribute("role", "list") }
        PartnerServersLogic.rejectReasonLines(settings).forEach { list.div(it) { setAttribute("role", "listitem") } }
        details.add(list)
        box.add(details)
    }
}

private const val AUCTION_EXCHANGE_INFO =
    "Mitglieder entscheiden für jede Euro-Auktion selbst, ob ein Kurzhinweis an freigegebene Partner geht: " +
        "Titel, Preis, Ende und Sofortkaufpreis. Verkäufer, Bieter, Gebote und Zahlungsangaben werden nie weitergegeben. " +
        "Bieten und Kaufen ist nur beim anbietenden Server möglich."

/** "Auktionen auf Partnerservern": the instance switch and the reach. The partners themselves are switched in their rows below. Only the sending side exists so far. */
private fun renderAuctionExchange(
    panel: SimplePanel,
    settings: AuctionFederationSettingsDto,
    reload: () -> Unit,
) {
    panel.h2(tr("Auktionen auf Partnerservern")) { addCssClasses("h5 mt-1") }
    val box = panel.vPanel(spacing = 2)
    val toggle = box.checkBox(value = settings.enabled, label = tr("Euro-Auktionen mit freigegebenen Partnern teilen"))
    box.div(tr(AUCTION_EXCHANGE_INFO), className = "text-muted small")
    val reach =
        if (PartnerServersLogic.hopSelectVisible(settings.enabled)) {
            box.select(
                options = listOf("1" to tr("Nur direkte Partner"), "2" to tr("Auch Partner unserer Partner")),
                value = PartnerServersLogic.auctionHopSelection(settings.hopLimit).toString(),
                label = tr("Reichweite"),
            )
        } else {
            null
        }

    fun save(
        enabled: Boolean,
        hopLimit: Int,
    ) {
        toggle.disabled = true
        reach?.disabled = true
        runGuardedAction(button = null) {
            guarded { rpcService<IPartnerServerService>().setAuctionFederationSettings(enabled = enabled, hopLimit = hopLimit) }
            reload()
        }
    }
    toggle.onClick {
        val wanted = toggle.value == true
        if (!wanted && PartnerServersLogic.auctionDialogNeeded(settings.sharedActive)) {
            // The box jumps back until the person confirms (the reload after a refusal resets it as well).
            toggle.value = true
            confirmDialog(
                title = tr("Auktionsaustausch ausschalten?"),
                message = PartnerServersLogic.auctionSwitchOffText(settings.sharedActive),
                confirmLabel = tr("Ausschalten"),
            ) { save(enabled = false, hopLimit = settings.hopLimit) }
        } else {
            save(enabled = wanted, hopLimit = settings.hopLimit)
        }
    }
    reach?.onEvent { change = { save(enabled = settings.enabled, hopLimit = reach.value?.toIntOrNull() ?: settings.hopLimit) } }
    PartnerServersLogic.auctionSettingsLines(settings).forEach {
        box.div(it, className = "text-muted small") { setAttribute("role", "status") }
    }
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
    carpoolExchangeOn: Boolean,
    auctionExchangeOn: Boolean,
    auctionSharedActive: Int,
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
    PartnerServersLogic.pullStateOf(partner = partner, exchangeOn = carpoolExchangeOn)?.let { state ->
        // The word is the information; the colour is only an addition.
        info.div(className = "small partner-pull-state") {
            span(PartnerServersLogic.pullStateLabel(state), className = "badge ${PartnerServersLogic.pullStateBadgeClass(state)}")
        }
    }
    if (partner.blocked) {
        info.div(PartnerServersLogic.blockedNote(), className = "text-muted small")
    } else {
        partner.lastFetchAt?.let { info.div(gettext("Letzter Abruf: %1", formatSystemDateTime(it)), className = "text-muted small") }
        info.div(gettext("Veranstaltungen: %1", partner.eventCount), className = "text-muted small")
        PartnerServersLogic.viaThisPartner(partner)?.let { info.div(it, className = "text-muted small partner-via") }
        partner.lastError?.let { info.div(PartnerServersTexts.fetchError(it), className = "text-danger small") }
        PartnerServersLogic.carpoolFeedLines(partner = partner, exchangeOn = carpoolExchangeOn).forEach {
            info.div(it, className = "text-muted small partner-carpool-feed")
        }
        PartnerServersLogic.auctionFeedLines(partner = partner, exchangeOn = auctionExchangeOn).forEach {
            info.div(it, className = "text-muted small partner-auction-feed")
        }
    }
    val actions = head.div { addCssClasses("d-flex align-items-center gap-2") }
    // V1.9.319: the partner announced another signing key (or none). The exchange is paused until an administrator accepts the new key.
    if (partner.signingKeyChangedAt != null) renderSigningKeyChange(info = info, actions = actions, partner = partner, reload = reload)
    // V1.9.311: per-partner consent to exchange carpool hints; only while the exchange is switched on.
    if (PartnerServersLogic.partnerSharingVisible(carpoolExchangeOn)) {
        val sharing = actions.checkBox(value = partner.carpoolSharing, label = tr("Fahrten teilen"))
        sharing.onClick {
            val wanted = sharing.value == true
            sharing.disabled = true
            runGuardedAction(button = null) {
                guarded { rpcService<IPartnerServerService>().setPartnerCarpoolSharing(partner.id, wanted) }
                reload()
            }
        }
    }
    // V1.9.322: per-partner consent to exchange auction hints; only while the auction exchange is switched on.
    if (PartnerServersLogic.auctionPartnerSharingVisible(auctionExchangeOn)) {
        val sharing = actions.checkBox(value = partner.auctionSharing, label = tr("Auktionen teilen"))
        sharing.onClick {
            val wanted = sharing.value == true

            fun save() {
                sharing.disabled = true
                runGuardedAction(button = null) {
                    guarded { rpcService<IPartnerServerService>().setPartnerAuctionSharing(partner.id, wanted) }
                    reload()
                }
            }
            if (!wanted && PartnerServersLogic.auctionDialogNeeded(auctionSharedActive)) {
                sharing.value = true
                confirmDialog(
                    title = tr("Auktionen für diesen Partner zurückziehen?"),
                    message = PartnerServersLogic.partnerAuctionOffText(partner.host),
                    confirmLabel = tr("Zurückziehen"),
                ) { save() }
            } else {
                save()
            }
        }
    }
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

/**
 * The warning of a changed signing key. The fingerprints are SHA-256 hex computed by this server; they are still shown through the `untrusted*`
 * helpers and never as an argument of a translated text.
 */
private fun renderSigningKeyChange(
    info: Container,
    actions: Container,
    partner: PartnerServerDto,
    reload: () -> Unit,
) {
    info.div(tr("Signaturschlüssel geändert: Austausch angehalten"), className = "text-danger small fw-bold partner-key-change") {
        setAttribute("role", "alert")
    }
    partner.signingKeyFingerprint?.let { fingerprint ->
        info.div(tr("Bisher vertrauter Schlüssel (Fingerabdruck)"), className = "small text-muted")
        info.untrustedDiv(fingerprint, className = "font-monospace small text-break")
    }
    val pending = partner.signingKeyPendingFingerprint
    if (pending == null) {
        info.div(tr("Der Partner kündigt keinen Schlüssel mehr an."), className = "small text-muted")
        return
    }
    info.div(tr("Neu angekündigter Schlüssel (Fingerabdruck)"), className = "small text-muted")
    info.untrustedDiv(pending, className = "font-monospace small text-break")
    actions.actionButton(ActionIcon.APPROVE, tr("Neuen Schlüssel übernehmen"), style = ButtonStyle.OUTLINEDANGER, small = true) {
        onClick {
            confirmDialog(
                title = tr("Schlüssel übernehmen?"),
                message = tr("Prüfen Sie den Fingerabdruck mit der Partnerorganisation, bevor Sie ihn übernehmen."),
                confirmLabel = tr("Jetzt übernehmen"),
                confirmIcon = ActionIcon.APPROVE,
            ) {
                runGuardedAction(button = null) {
                    guarded { rpcService<IPartnerServerService>().acceptPartnerSigningKey(partner.id, pending) }
                    reload()
                }
            }
        }
    }
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

/** "Partner von Partnern": the switch, the depth, the state of the last run and the servers that were reached. */
private fun renderRecursion(
    panel: SimplePanel,
    settings: PartnerRecursionSettingsDto,
    discovered: List<DiscoveredPartnerServerDto>,
    reload: () -> Unit,
) {
    panel.h2(tr("Partner von Partnern")) { addCssClasses("h5 mt-4") }
    val box = panel.vPanel(spacing = 2)
    val recursionToggle = box.checkBox(value = settings.enabled, label = tr("Auch Partner der Partner einbeziehen"))
    box.div(
        tr("Dann holt dieser Server stündlich auch die Veranstaltungen der Partner Ihrer Partner ab, bis zur gewählten Stufe."),
        className = "text-muted small",
    )
    val depthOptions = (1..3).map { it.toString() to it.toString() }
    val depth = box.select(options = depthOptions, value = settings.depth.toString(), label = tr("Zusätzliche Stufen"))
    depth.disabled = !PartnerServersLogic.depthSelectEnabled(settings.enabled)

    fun save() {
        recursionToggle.disabled = true
        depth.disabled = true
        val wantedDepth = depth.value?.toIntOrNull() ?: settings.depth
        runGuardedAction(button = null) {
            guarded { rpcService<IPartnerServerService>().setPartnerRecursionSettings(recursionToggle.value == true, wantedDepth) }
            reload()
        }
    }
    recursionToggle.onClick { save() }
    // `subscribe` would call back at once with the current value (a reload loop); the DOM change event fires only on a choice by the person.
    depth.onEvent { change = { save() } }

    val status =
        panel.div(PartnerServersLogic.runStatus(settings), className = "small mt-2") {
            setAttribute("role", "status")
        }
    settings.summary?.let { summary ->
        val lines = PartnerServersLogic.summaryLines(summary)
        if (lines.isNotEmpty()) {
            val list = panel.div(className = "small text-muted") { setAttribute("role", "list") }
            lines.forEach { line -> list.div(line) { setAttribute("role", "listitem") } }
        }
    }
    status.setAttribute("aria-live", "polite")

    panel.h2(tr("Erreichte Server")) { addCssClasses("h6 mt-3") }
    if (discovered.isEmpty()) {
        panel.p(tr("Noch keine weiteren Server erreicht.")) { addCssClasses("text-muted small") }
        return
    }
    val list = panel.div { setAttribute("role", "list") }
    discovered.forEach { renderDiscoveredRow(list, it, reload) }
}

private fun renderDiscoveredRow(
    list: Container,
    server: DiscoveredPartnerServerDto,
    reload: () -> Unit,
) {
    val row =
        list.div {
            addCssClasses("partner-row partner-discovered border-bottom py-2")
            setAttribute("role", "listitem")
        }
    val head = row.div { addCssClasses("d-flex flex-wrap align-items-start gap-2") }
    val info = head.div { addCssClass("flex-grow-1") }
    info.untrustedDiv(server.name ?: server.host, className = "fw-bold")
    if (server.name != null) info.untrustedSpan(server.host, className = "font-monospace small text-muted")
    info.div(className = "small text-muted") {
        span(PartnerServersLogic.levelText(server.hops))
        span(" · ")
        span(gettext("Veranstaltungen: %1", server.eventCount))
    }
    info.untrustedDiv(PartnerServersLogic.chainText(server.viaChain), className = "small text-muted partner-chain")
    info.div(
        PartnerServersLogic.discoveredStatus(server),
        className = "small ${if (server.lastError != null) "text-danger" else "text-muted"}",
    )
    if (server.signingKeyHeld) {
        info.div(
            tr("Schlüsselwechsel gesperrt"),
            className = "small text-danger fw-bold partner-key-hold",
        ) { setAttribute("role", "status") }
        head.actionButton(ActionIcon.UNDO, tr("Sperre aufheben"), small = true) {
            onClick {
                confirmDialog(
                    title = tr("Sperre aufheben?"),
                    message =
                        tr(
                            "Der nächste Abruf übernimmt den Schlüssel dieses Servers neu. Prüfen Sie vorher, ob der Wechsel erwartet war.",
                        ),
                    confirmLabel = tr("Sperre jetzt aufheben"),
                    confirmIcon = ActionIcon.UNDO,
                ) {
                    runGuardedAction(button = null) {
                        guarded { rpcService<IPartnerServerService>().releaseDiscoveredSigningKeyHold(server.host) }
                        reload()
                    }
                }
            }
        }
    }
    head.actionButton(ActionIcon.REVOKE, tr("Sperren"), small = true) {
        onClick {
            runGuardedAction(button = this) {
                guarded { rpcService<IPartnerServerService>().addBlock(server.host) }
                reload()
            }
        }
    }
    row.setAttribute("aria-label", server.host)
}

private fun renderBlocklist(
    panel: SimplePanel,
    blocks: List<PartnerBlockDto>,
    reload: () -> Unit,
) {
    val details = Tag(TAG.DETAILS, className = "lapis-settings-section border rounded mt-3")
    // V1.9.257: the block list is ALWAYS open, empty or not -- a closed list hid the very field that adds the first entry. It cannot be collapsed
    // either (the click on the summary is cancelled), so `aria-expanded="true"` stays true for the life of the element.
    details.setAttribute("open", "")
    val head = Tag(TAG.SUMMARY, className = "lapis-settings-summary")
    head.setAttribute("aria-expanded", "true")
    head.onEvent { click = { event -> event.preventDefault() } }
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
    // V1.9.257: the entries come BEFORE the form. A report of the required field ("Host oder Muster") grows the form; below the entries it can
    // never push a "Entsperren" button away between the press and the release of the mouse.
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
}
