package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.rpc.IMailingService

/** The tabs of the mailing-list screen. [slug] is the `?tab=` value. */
internal enum class MailingListsTab(
    val slug: String,
) {
    SUBSCRIPTIONS("subscriptions"),
    MANAGE("manage"),
}

/**
 * The tab to open for the `?tab=` value [param]. "manage" is only honoured for the board tier ([canManage]); anyone else falls back to the
 * subscriptions without a message. Cosmetic only: the server gates every board call itself. Pure.
 */
internal fun resolveMailingListsTab(
    param: String?,
    canManage: Boolean,
): MailingListsTab = if (canManage && param == MailingListsTab.MANAGE.slug) MailingListsTab.MANAGE else MailingListsTab.SUBSCRIPTIONS

internal fun mailingListsHash(tab: MailingListsTab): String = "#${Routes.MAILING_LISTS}?tab=${tab.slug}"

/**
 * V1.9.231 -- "Mailinglisten": the member's subscriptions, and for BOARD/ADMIN a second tab "Verwalten" (the former authoring section of the
 * "Kommunikation" screen, unchanged). A plain member gets no tab bar at all -- the "Verwalten" tab is not built for them; the server stays the
 * authority (`BOARD_ROLES`).
 */
fun renderMailingListsScreen(
    container: SimplePanel,
    deepLinkHash: String = window.location.hash,
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.pageHeader(tr("Mailinglisten"), intro = PageIntros.forRoute(Routes.MAILING_LISTS))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    if (!canManage) {
        renderMailingSubscriptions(root)
        return
    }
    val initial = resolveMailingListsTab(parseHashQueryParam(deepLinkHash, "tab"), canManage)
    // The tab panels are built lazily; the board tab refreshes the subscriptions only when that tab exists, and the subscriptions reload
    // every time their tab is shown again (a list created or filled in the other tab is then visible).
    var refreshSubscriptions: (() -> Unit)? = null
    val rendered = mutableSetOf<MailingListsTab>()
    var initialSelectionDone = false
    root.pageTabs(
        tabs =
            listOf(
                PageTab(MailingListsTab.SUBSCRIPTIONS, tr("Meine Abonnements"), "mailing-tab-subscriptions", "mailing-panel-subscriptions"),
                PageTab(MailingListsTab.MANAGE, tr("Verwalten"), "mailing-tab-manage", "mailing-panel-manage"),
            ),
        selected = initial,
        ariaLabel = tr("Bereiche der Mailinglisten"),
    ) { tab, panel ->
        // The address follows the tab without a re-render and without a history entry.
        if (initialSelectionDone) runCatching { window.history.replaceState(null, "", mailingListsHash(tab)) }
        val first = rendered.add(tab)
        when (tab) {
            MailingListsTab.SUBSCRIPTIONS ->
                if (first) {
                    refreshSubscriptions = renderMailingSubscriptions(panel)
                } else {
                    refreshSubscriptions?.invoke()
                }
            MailingListsTab.MANAGE -> if (first) renderMailingListAdminSection(panel) { refreshSubscriptions?.invoke() }
        }
    }
    initialSelectionDone = true
}

/**
 * The subscription table: one row per list with the name (and the subscriber count), the status and icon-only actions (R58). Returns its own
 * refresh closure. A subscribed list can open its tracking switches under the name; which rows are open survives a refresh.
 */
private fun renderMailingSubscriptions(root: SimplePanel): () -> Unit {
    val openSettings = mutableSetOf<String>()
    lateinit var section: DataSection
    section =
        root.dataSection<List<MailingListDto>>(
            emptyText = tr("Noch keine Mailinglisten."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IMailingService>().listMailingLists() } },
            render = { panel, lists ->
                val table =
                    panel.standardTable(listOf(TableHeader(tr("Liste")), TableHeader(tr("Status")), TableHeader(tr("Aktionen"))))
                lists.forEach { list ->
                    // V1.9.111: an automatic list has no subscriber count and no tracking switches -- its own cells.
                    if (list.kind == MailingListKind.DYNAMIC) {
                        table.row { renderDynamicSelfServiceCells(this, list) { section.reload() } }
                    } else {
                        table.row { renderManualSubscriptionCells(this, list, openSettings) { section.reload() } }
                    }
                }
            },
        )
    section.reload()
    return { section.reload() }
}

private fun renderManualSubscriptionCells(
    row: io.kvision.table.Row,
    list: MailingListDto,
    openSettings: MutableSet<String>,
    refresh: () -> Unit,
) {
    val subscribed = list.isSubscribedByCurrentMember
    val nameCell = row.cell()
    // list.name is member-/admin-editable free text composed into a gettext(...) string before it reaches widget content -- sanitize the
    // whole composed result before KVision can resolve a forged marker on render.
    nameCell.div(sanitizeUntrustedI18nText(gettext("%1 (%2 Abonnenten)", list.name, list.subscriberCount)))
    val settingsHost = nameCell.vPanel(spacing = 2)
    settingsHost.visible = subscribed && list.id in openSettings
    if (subscribed) {
        lateinit var switches: TrackingConsentSwitches
        switches =
            renderTrackingConsentSwitches(settingsHost, list) { open, click ->
                switches.setBusy(true)
                val gaveConsent =
                    (open && list.currentMemberOpenTrackingConsentedAt == null) ||
                        (click && list.currentMemberClickTrackingConsentedAt == null)
                runGuardedAction(null) {
                    val saved = guarded { rpcService<IMailingService>().setTrackingConsent(list.id, open, click) }
                    if (saved != null) {
                        notifySuccess(if (gaveConsent) tr("Einwilligung gespeichert.") else tr("Einwilligung widerrufen."))
                    }
                    // Re-render from the server state either way: on success it carries the new timestamps, on failure it puts the
                    // switches back to what is actually stored.
                    refresh()
                }
            }
    }
    row.cell { if (subscribed) statusBadge(tr("Abonniert"), "success") }
    val actionsCell = row.cell { addCssClasses("text-end text-nowrap") }
    val actions = actionsCell.tableActionGroup().apply { addCssClass("justify-content-end") }
    val toggle =
        if (subscribed) {
            actions.tableActionButton(ActionIcon.REMOVE, tr("Abbestellen"))
        } else {
            actions.tableActionButton(ActionIcon.ADD, tr("Abonnieren"), ButtonStyle.OUTLINEPRIMARY)
        }
    toggle.onClick {
        runGuardedAction(toggle) {
            guarded {
                if (subscribed) rpcService<IMailingService>().unsubscribe(list.id) else rpcService<IMailingService>().subscribe(list.id)
            }
            // Re-render from the server state either way: after a failure (already toasted) it shows what is really stored.
            openSettings.remove(list.id)
            refresh()
        }
    }
    if (subscribed) {
        val settingsButton = actions.tableActionButton(settingsIcon(settingsHost.visible), tr("Einstellungen"))
        settingsButton.setAttribute("aria-expanded", settingsHost.visible.toString())
        settingsButton.onClick {
            val show = !settingsHost.visible
            settingsHost.visible = show
            if (show) openSettings.add(list.id) else openSettings.remove(list.id)
            settingsButton.setAttribute("aria-expanded", show.toString())
            settingsButton.icon = actionIconClasses(settingsIcon(show))
        }
    }
}

private fun settingsIcon(open: Boolean): ActionIcon = if (open) ActionIcon.CHEVRON_UP else ActionIcon.CHEVRON_DOWN
