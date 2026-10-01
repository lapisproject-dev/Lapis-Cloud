package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.22: the list view of the elections screen -- rows, status filter, empty state, and the way to the motions. */
class ElectionsScreenDomTest {
    private fun session() =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Mitglied",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @Test
    fun theList_showsEveryElectionNewestFirst_withTypeAndStatus(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = electionRoutes()
            val older = election(status = ElectionStatus.TALLIED, title = "Ältere Wahl")
            val newer =
                election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.OPEN, title = "Neuere Wahl").copy(
                    id = "e2",
                    openedAt = LocalDateTime(2026, 6, 1, 12, 0),
                )
            val world = ElectionWorld(older, elections = listOf(older, newer))
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("elections-list") { root, element ->
                    renderElectionsScreen(root)
                    awaitUntil("list rendered", 3000) { element().flatText().contains("Neuere Wahl") }
                    val text = element().flatText()
                    assertTrue(text.indexOf("Neuere Wahl") < text.indexOf("Ältere Wahl"), "newest first: $text")
                    assertTrue(text.contains("Einzelwahl") && text.contains("Ja/Nein-Wahl"))
                    assertTrue(text.contains("Abstimmung läuft") && text.contains("Ausgezählt"))
                    assertTrue(element().hasButton("Zu den Anträgen"))
                }
            }
        }

    @Test
    fun theStatusFilter_isSentToTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = electionRoutes()
            val world = ElectionWorld(election(), elections = listOf(election(title = "Eine Wahl")))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("elections-filter") { root, element ->
                    renderElectionsScreen(root)
                    awaitUntil("first load", 3000) { calls.toRoute(routes.listElections).size == 1 }
                    val select = element().controlOf("Status") as HTMLSelectElement
                    select.value = ElectionStatus.OPEN.name
                    select.dispatchEvent(Event("change"))
                    element().buttonNamed("Aktualisieren").click()
                    awaitUntil("second load", 3000) { calls.toRoute(routes.listElections).size == 2 }
                    val second = calls.toRoute(routes.listElections).last()
                    assertEquals("OPEN", second.rpcParam(1) as String, "the chosen status is the second parameter")
                    assertTrue(second.rpcParam(0) == null, "no motion filter on the overview")
                }
            }
        }

    @Test
    fun withoutElections_theEmptyStateExplainsWhereAnElectionComesFrom(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = electionRoutes()
            val world = ElectionWorld(election(), elections = emptyList())
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("elections-empty") { root, element ->
                    renderElectionsScreen(root)
                    awaitUntil("empty state", 3000) {
                        element().flatText().contains(
                            "Noch keine Wahlen vorhanden. Eine Wahl wird aus einem terminierten Antrag heraus eröffnet.",
                        )
                    }
                    assertTrue(element().hasButton("Zu den Anträgen"))
                }
            }
        }

    @Test
    fun theDetailRoute_opensTheDetailOfThatElection_withABackButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = electionRoutes()
            val world = ElectionWorld(election(status = ElectionStatus.OPEN, title = "Detailwahl"), participation(eligible = false))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("elections-detail-route") { root, element ->
                    renderElectionsScreen(root, initialElectionId = "e1")
                    awaitUntil("detail rendered", 3000) { element().flatText().contains("Detailwahl") }
                    assertTrue(element().hasButton("Zur Wahlübersicht"))
                    assertEquals(0, calls.toRoute(routes.listElections).size, "the detail route loads no list")
                    assertEquals("e1", calls.toRoute(routes.getElection).first().rpcParam(0) as String)
                }
            }
        }
}
