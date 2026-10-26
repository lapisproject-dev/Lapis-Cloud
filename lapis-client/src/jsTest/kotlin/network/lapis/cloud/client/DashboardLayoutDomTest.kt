package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.224 -- the "Für Sie" grid in a real browser with the real theme: one column on a phone, two and three on wider content areas (the
 * grid follows the width of its section, a container query, not of the window), never a horizontal scroll, and a long reason is cut with an
 * ellipsis instead of widening the page.
 */
class DashboardLayoutDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun session() =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Person",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    private val signals =
        listOf(
            DashboardSignalDto(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_OUTBID, 12, priority = DashboardPriority.URGENT),
            DashboardSignalDto(DashboardModuleKeys.SURVEY, DashboardReasonKeys.SURVEY_NOT_ANSWERED, 1, priority = DashboardPriority.ACTION),
            DashboardSignalDto(
                DashboardModuleKeys.CONTRIBUTIONS,
                DashboardReasonKeys.CONTRIBUTIONS_OVERDUE,
                2,
                priority = DashboardPriority.URGENT,
            ),
            DashboardSignalDto(DashboardModuleKeys.MEETING, DashboardReasonKeys.MEETING_UPCOMING, 5, priority = DashboardPriority.INFO),
        )

    private fun columnsAt(
        id: String,
        widthPx: Int,
    ): Promise<Int> {
        var result = 0
        return formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(session())
            withFetchStub {
                mountedForm(id) { root, element ->
                    element().style.width = "${widthPx}px"
                    element().style.boxSizing = "border-box"
                    renderDashboardScreen(
                        root,
                        OnlineCountsRpc { error("unused") },
                        WindowOnlineCountsScheduler,
                        DashboardSignalsRpc { signals },
                    )
                    awaitUntil("the cards appear") { element().querySelectorAll(".lapis-signal-card").length == signals.size }
                    val grid = assertNotNull(element().querySelector(".lapis-dashboard-signals") as HTMLElement?)
                    val template = window.getComputedStyle(grid).getPropertyValue("grid-template-columns")
                    result = template.trim().split(Regex("\\s+")).size
                    assertTrue(
                        element().scrollWidth <= element().clientWidth,
                        "horizontal overflow at $widthPx px: ${element().scrollWidth} > ${element().clientWidth}",
                    )
                    // the one-line reason shrinks (ellipsis), it never pushes a card wider than its column
                    element().querySelectorAll(".lapis-signal-card").let { cards ->
                        for (i in 0 until cards.length) {
                            val card = cards.item(i) as HTMLElement
                            assertTrue(card.scrollWidth <= card.clientWidth + 1, "card $i overflows at $widthPx px")
                        }
                    }
                }
            }
        }.then { result }
    }

    @Test
    fun onAPhone_theGridHasOneColumn_andNothingScrollsSideways(): Promise<Unit> = columnsAt("dash-375", 375).then { assertEquals(1, it) }

    @Test
    fun onATablet_theGridHasTwoColumns(): Promise<Unit> = columnsAt("dash-768", 768).then { assertEquals(2, it) }

    @Test
    fun onAWideArea_theGridHasThreeColumns(): Promise<Unit> = columnsAt("dash-1200", 1200).then { assertEquals(3, it) }
}
