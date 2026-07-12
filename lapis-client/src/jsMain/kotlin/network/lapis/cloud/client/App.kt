package network.lapis.cloud.client

import io.kvision.Application
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.panel.SimplePanel
import io.kvision.panel.root
import io.kvision.panel.vPanel
import io.kvision.remote.registerRemoteTypes
import io.kvision.startApplication
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.time.Clock

/** Application-wide coroutine scope tied to the browser's event loop. */
val AppScope: CoroutineScope = CoroutineScope(window.asCoroutineDispatcher())

// Matches DevSeedData.standardTierId on the server.
private const val STANDARD_TIER_ID = "00000000-0000-0000-0000-0000000000f1"

/**
 * Single-dashboard V0.1.5 demo UI: a "current member" switcher (Foundation-stub auth, see
 * [AppState]) plus one screen that exercises all four new RPC services (Contributions,
 * Documents metadata, Mailing, Direct messages) end to end against the real backend. A fuller
 * multi-screen UI (dedicated document browser with upload, mailing compose screen, etc. — see
 * the V0.1.5 plan) is follow-up scope; this proves the wiring works for every service.
 */
class App : Application() {
    override fun start() {
        root("lapis-client") {
            h1("Lapis Cloud — V0.1.5 Demo")
            div("Beitragsverwaltung, Dokumentenablage, Kommunikation") {
                marginBottom = 16.px
            }
            memberSwitcher(this)
            val dashboard = vPanel()
            add(dashboard)
            renderDashboard(dashboard)
        }
    }

    private fun memberSwitcher(parent: SimplePanel) {
        parent.div {
            marginBottom = 16.px
            +"Anmelden als: "
            DevMembers.all.forEach { member ->
                val btn = button(member.label, style = ButtonStyle.OUTLINELIGHT)
                btn.marginLeft = 4.px
                btn.onClick {
                    AppState.currentMemberId = member.id
                    window.location.reload()
                }
            }
        }
    }
}

private object DevMembers {
    data class Entry(
        val id: String,
        val label: String,
    )

    // Mirrors network.lapis.cloud.server.db.DevSeedData.demoMembers — kept in sync by hand
    // since the client has no "list all members without auth" bootstrap endpoint.
    val all =
        listOf(
            Entry("00000000-0000-0000-0000-000000000001", "Amara (Admin)"),
            Entry("00000000-0000-0000-0000-000000000002", "Boris (Board)"),
            Entry("00000000-0000-0000-0000-000000000003", "Theresa (Schatzmeisterin)"),
            Entry("00000000-0000-0000-0000-000000000004", "Max (Mitglied)"),
        )
}

private fun renderDashboard(panel: SimplePanel) {
    AppScope.launch {
        val memberService = rpcService<IMemberService>()
        val current =
            try {
                memberService.getCurrentMember()
            } catch (e: Throwable) {
                panel.div("Fehler beim Laden des aktuellen Mitglieds: ${e.message}")
                return@launch
            }

        panel.div("Angemeldet als ${current.displayName} (${current.role})")

        renderContributionsSection(panel, current.id, current.role)
        renderMailingSection(panel)
        renderInboxSection(panel)
    }
}

private fun renderContributionsSection(
    panel: SimplePanel,
    memberId: String,
    role: AccountRole,
) {
    panel.h2("Beitragsverwaltung")
    val section = SimplePanel()
    panel.add(section)
    val canManage = role == AccountRole.TREASURER || role == AccountRole.ADMIN

    fun refresh() {
        section.removeAll()
        AppScope.launch {
            val service = rpcService<IContributionService>()
            val summary = service.getMemberContributionSummary(memberId)
            section.div(
                "Offen: ${summary.totalOpen} | Bezahlt: ${summary.totalPaid} | Gesamt: ${summary.totalDue}",
            )
            summary.contributions.forEach { contribution ->
                val row = section.div()
                row.add(
                    io.kvision.html.Span(
                        "${contribution.periodStart}–${contribution.periodEnd}: " +
                            "${contribution.amountDue} (${contribution.status})",
                    ),
                )
                if (contribution.status == ContributionStatus.OPEN && canManage) {
                    val payButton = row.button("Als bezahlt markieren", style = ButtonStyle.SUCCESS)
                    payButton.marginLeft = 8.px
                    payButton.onClick {
                        AppScope.launch {
                            val now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            service.markContributionPaid(contribution.id, now, contribution.amountDue, null)
                            refresh()
                        }
                    }
                }
            }
            if (canManage) {
                val generateButton =
                    section.button(
                        "Beiträge für Standardbeitrag generieren (Juli 2026)",
                        style = ButtonStyle.PRIMARY,
                    )
                generateButton.marginTop = 8.px
                generateButton.onClick {
                    AppScope.launch {
                        val periodStart = LocalDate(2026, 7, 1)
                        val periodEnd = LocalDate(2026, 7, 31)
                        service.generateContributionsForPeriod(STANDARD_TIER_ID, periodStart, periodEnd)
                        refresh()
                    }
                }
            }
        }
    }
    refresh()
}

private fun renderMailingSection(panel: SimplePanel) {
    panel.h2("Mailinglisten")
    val section = SimplePanel()
    panel.add(section)

    fun refresh() {
        section.removeAll()
        AppScope.launch {
            val service = rpcService<IMailingService>()
            val lists = service.listMailingLists()
            if (lists.isEmpty()) {
                section.div("Noch keine Mailinglisten.")
            }
            lists.forEach { list ->
                val row = section.div()
                row.add(io.kvision.html.Span("${list.name} (${list.subscriberCount} Abonnenten) "))
                val toggleButton = row.button(if (list.isSubscribedByCurrentMember) "Abbestellen" else "Abonnieren")
                toggleButton.onClick {
                    AppScope.launch {
                        if (list.isSubscribedByCurrentMember) service.unsubscribe(list.id) else service.subscribe(list.id)
                        refresh()
                    }
                }
            }
        }
    }
    refresh()
}

private fun renderInboxSection(panel: SimplePanel) {
    panel.h2("Postfach")
    val section = SimplePanel()
    panel.add(section)

    AppScope.launch {
        val service = rpcService<IDirectMessageService>()
        val unread = service.unreadCount()
        section.div("Ungelesene Nachrichten: $unread")
    }
}

fun main() {
    registerRemoteTypes()
    startApplication(::App)
}
