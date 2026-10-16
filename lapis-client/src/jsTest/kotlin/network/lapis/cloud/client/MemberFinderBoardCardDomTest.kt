package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderBoardOverviewDto
import network.lapis.cloud.shared.domain.MemberFinderRegionBandDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.145 -- the board overview of the finder: regions as a band (never a count), sorted by region, silent while the finder is off. */
class MemberFinderBoardCardDomTest {
    private fun HTMLElement.text(): String = textContent.orEmpty()

    private suspend fun withCard(
        failWith: String? = null,
        overview: MemberFinderBoardOverviewDto = MemberFinderBoardOverviewDto(computedOn = null, regions = emptyList()),
        block: suspend (HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        val route = routeOf { rpcService<network.lapis.cloud.shared.rpc.IMemberFinderService>().getBoardOverview() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == route ->
                        if (failWith != null) {
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.$failWith")
                        } else {
                            request.answerWith(jsonOf(MemberFinderBoardOverviewDto.serializer(), overview))
                        }
                    else -> StubResponse()
                }
            },
        ) { requests ->
            mountedForm("member-finder-board") { root, element ->
                renderMemberFinderBoardCard(root as SimplePanel)
                block(element(), requests)
            }
        }
    }

    @Test
    fun regionsAreSortedAndShownAsBands(): Promise<Unit> =
        formTest {
            val overview =
                MemberFinderBoardOverviewDto(
                    computedOn = finderDay,
                    regions =
                        listOf(
                            "50",
                            "10",
                            "38",
                        ).zip(listOf(MemberFinderBand.B5_9, MemberFinderBand.B10_19, MemberFinderBand.B20_PLUS)).map { (label, band) ->
                            MemberFinderRegionBandDto(regionLabel = label, band = band)
                        },
                )
            withCard(overview = overview) { element, _ ->
                awaitUntil("the card") { element.text().contains("Mitglieder-Finder: Regionen") }
                awaitAppScopeIdle("loaded")
                val text = element.text()
                val order = listOf("DE-10", "DE-38", "DE-50").map { text.indexOf(it) }
                assertTrue(order.all { it >= 0 }, "all regions shown: $text")
                assertEquals(order.sorted(), order, "sorted by region")
                assertTrue(text.contains("10 bis 19 Teilnehmende"))
                assertTrue(text.contains("20 oder mehr Teilnehmende"))
                assertTrue(text.contains("5 bis 9 Teilnehmende"))
                assertNotNull(element.querySelector("table, [role=list], ul"), "a table (or the card list of a narrow window)")
                assertTrue(text.contains("Stand: ${formatDate(finderDay)}"))
            }
        }

    @Test
    fun noRegionYet_saysSo(): Promise<Unit> =
        formTest {
            withCard { element, _ ->
                awaitUntil("the text") { element.text().contains("Noch keine Region mit mindestens fünf Teilnehmenden.") }
            }
        }

    @Test
    fun whileTheFinderIsOff_orForANonMember_nothingIsDrawn(): Promise<Unit> =
        formTest {
            listOf("MemberFinderUnavailableException", "ForbiddenException").forEach { type ->
                withCard(failWith = type) { element, requests ->
                    awaitUntil("the call") { requests.rpcCount >= 1 }
                    awaitAppScopeIdle("settled")
                    assertFalse(element.text().contains("Mitglieder-Finder"), "no heading for $type")
                    assertFalse(element.text().contains("Keine Berechtigung"))
                    assertEquals("", element.allOf(".alert-danger").joinToString { it.text() }, "no error box either")
                }
            }
        }
}
