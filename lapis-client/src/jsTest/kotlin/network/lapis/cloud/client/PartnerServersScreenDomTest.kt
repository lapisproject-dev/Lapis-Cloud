package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DiscoveredPartnerServerDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
import network.lapis.cloud.shared.domain.PartnerServerDto
import network.lapis.cloud.shared.domain.PartnerServerOverviewDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IPartnerServerService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.244 -- the partner page: foreign names are text and never markup, a status is a symbol AND words, the block list is closed while it is
 * empty, the form is a real labelled form with an alert/status region.
 */
class PartnerServersScreenDomTest {
    private val evil = "<img src=x onerror=alert(1)> Fremd"

    private fun partner(
        id: String = "p1",
        host: String = "partner.beispiel.de",
        displayName: String? = null,
        remoteName: String? = null,
        active: Boolean = true,
        blocked: Boolean = false,
        status: PartnerFetchStatus = PartnerFetchStatus.OK,
        error: PartnerFetchErrorCode? = null,
        discoveredServers: Int = 0,
    ) = PartnerServerDto(
        id = id,
        host = host,
        displayName = displayName,
        remoteName = remoteName,
        active = active,
        blocked = blocked,
        status = status,
        lastFetchAt = LocalDateTime(2026, 11, 5, 9, 0),
        nextFetchAt = LocalDateTime(2026, 11, 5, 11, 7),
        consecutiveFailures = if (error != null) 2 else 0,
        eventCount = 7,
        lastError = error,
        discoveredServerCount = discoveredServers,
        discoveredEventCount = discoveredServers * 3,
    )

    private fun server(
        host: String = "fern.beispiel.de",
        name: String? = null,
        hops: Int = 2,
        via: List<String> = listOf("partner.beispiel.de"),
    ) = DiscoveredPartnerServerDto(
        host = host,
        name = name,
        hops = hops,
        viaChain = via,
        rootPartnerId = "p1",
        rootName = "Partner",
        eventCount = 4,
        lastStatus = PartnerFetchStatus.OK,
        lastError = null,
        lastFetchAt = LocalDateTime(2026, 11, 5, 9, 0),
    )

    private suspend fun scenario(
        overview: PartnerServerOverviewDto,
        recursion: PartnerRecursionSettingsDto = PartnerRecursionSettingsDto(true, 2, null, null),
        discovered: List<DiscoveredPartnerServerDto> = emptyList(),
        block: suspend (HTMLElement) -> Unit,
    ) {
        AppState.setSession(
            SessionInfoDto(
                memberId = "admin-1",
                displayName = "Ada Admin",
                role = AccountRole.ADMIN,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )
        val list = routeOf { rpcService<IPartnerServerService>().list() }
        val settingsRoute = routeOf { rpcService<IPartnerServerService>().getPartnerRecursionSettings() }
        val discoveredRoute = routeOf { rpcService<IPartnerServerService>().listDiscoveredPartnerServers() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list -> request.answerWith(jsonOf(PartnerServerOverviewDto.serializer(), overview))
                    request.rpcRoute == settingsRoute -> request.answerWith(jsonOf(PartnerRecursionSettingsDto.serializer(), recursion))
                    request.rpcRoute == discoveredRoute ->
                        request.answerWith(jsonOf(ListSerializer(DiscoveredPartnerServerDto.serializer()), discovered))
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("partner-servers-screen") { root, element ->
                renderPartnerServersScreen(root)
                awaitAppScopeIdle("the page finished loading")
                block(element())
            }
        }
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    @Test
    fun aForeignNameIsText_neverMarkup(): Promise<Unit> =
        test {
            scenario(PartnerServerOverviewDto(listOf(partner(remoteName = evil)), emptyList(), false)) { page ->
                assertNull(page.querySelector("img"), "no element may be created from a foreign name")
                assertTrue(page.textContent.orEmpty().contains("<img src=x onerror=alert(1)> Fremd"))
            }
        }

    @Test
    fun aStatusIsASymbolAndWords_andABlockedRowSaysSo(): Promise<Unit> =
        test {
            val rows =
                listOf(
                    partner(id = "a", host = "a.beispiel.de"),
                    partner(id = "b", host = "b.beispiel.de", active = false),
                    partner(id = "c", host = "c.beispiel.de", blocked = true),
                    partner(id = "d", host = "d.beispiel.de", status = PartnerFetchStatus.ERROR, error = PartnerFetchErrorCode.TIMEOUT),
                )
            scenario(PartnerServerOverviewDto(rows, listOf(PartnerBlockDto("x", "c.beispiel.de")), false)) { page ->
                val text = page.textContent.orEmpty()
                assertTrue(text.contains("Abgerufen"))
                assertTrue(text.contains("Pausiert"))
                assertTrue(text.contains("Gesperrt – wird nicht abgerufen und nicht angezeigt"))
                assertTrue(text.contains("Der Server hat nicht innerhalb von 10 Sekunden geantwortet."))
                assertTrue(page.allOf(".partner-row--blocked").size == 1)
                // the symbol is decoration: hidden from assistive technology
                assertTrue(page.allOf(".partner-status span[aria-hidden=true]").size == 4)
            }
        }

    @Test
    fun theBlocklistIsClosedWhileEmptyAndOpenOtherwise(): Promise<Unit> =
        test {
            scenario(PartnerServerOverviewDto(emptyList(), emptyList(), false)) { page ->
                val details = assertNotNull(page.querySelector("details.lapis-settings-section") as? HTMLElement)
                assertFalse(details.hasAttribute("open"))
            }
            scenario(PartnerServerOverviewDto(emptyList(), listOf(PartnerBlockDto("x", "*.spam.example")), false)) { page ->
                val details = assertNotNull(page.querySelector("details.lapis-settings-section") as? HTMLElement)
                assertTrue(details.hasAttribute("open"))
                assertTrue(details.textContent.orEmpty().contains("*.spam.example"))
            }
        }

    @Test
    fun theFormHasLabelledFieldsAndAnAlertRegion(): Promise<Unit> =
        test {
            scenario(PartnerServerOverviewDto(emptyList(), emptyList(), false)) { page ->
                val text = page.textContent.orEmpty()
                assertTrue(text.contains("Adresse des Servers"))
                assertTrue(text.contains("Anzeigename (optional)"))
                assertTrue(text.contains("Prüfen und speichern"))
                assertTrue(text.contains("Noch kein Partner eingetragen."))
            }
        }

    @Test
    fun theSwitchIsOffAndTheDepthSelectDisabledTogether(): Promise<Unit> =
        test {
            val off = PartnerRecursionSettingsDto(enabled = false, depth = 2, lastRunAt = null, summary = null)
            scenario(PartnerServerOverviewDto(emptyList(), emptyList(), false), recursion = off) { page ->
                assertTrue(page.textContent.orEmpty().contains("Auch Partner der Partner einbeziehen"))
                assertTrue(page.textContent.orEmpty().contains("Zusätzliche Stufen"))
                assertTrue(page.textContent.orEmpty().contains("Noch kein Lauf"))
                val select = assertNotNull(page.querySelector("select") as? org.w3c.dom.HTMLSelectElement)
                assertTrue(select.disabled)
            }
            val on = PartnerRecursionSettingsDto(enabled = true, depth = 3, lastRunAt = null, summary = null)
            scenario(PartnerServerOverviewDto(emptyList(), emptyList(), false), recursion = on) { page ->
                val select = assertNotNull(page.querySelector("select") as? org.w3c.dom.HTMLSelectElement)
                assertFalse(select.disabled)
                assertTrue(select.value == "3")
            }
        }

    @Test
    fun theLastRunIsSummarisedInWords(): Promise<Unit> =
        test {
            val summary =
                PartnerRecursionSummaryDto(reachedByHop = listOf(3, 1), eventsByHop = listOf(9, 2), skippedBlocked = 1, aborted = true)
            val settings = PartnerRecursionSettingsDto(true, 2, LocalDateTime(2026, 11, 5, 10, 0), summary)
            scenario(PartnerServerOverviewDto(emptyList(), emptyList(), false), recursion = settings) { page ->
                val text = page.textContent.orEmpty()
                assertTrue(text.contains("4 Server · 11 Veranstaltungen"))
                assertTrue(text.contains("Stufe 2: 3 Server, 9 Veranstaltungen"))
                assertTrue(text.contains("Stufe 3: 1 Server, 2 Veranstaltungen"))
                assertTrue(text.contains("Sperrliste: 1"))
                assertTrue(text.contains("Zeitbudget erreicht, Rest im nächsten Lauf"))
            }
        }

    @Test
    fun reachedServersShowTheirChain_aForeignNameIsText_andBlockCallsAddBlockWithTheHost(): Promise<Unit> =
        test {
            val rows = listOf(server(name = evil, via = listOf("partner.beispiel.de", "<b>mitte</b>.beispiel.de"), hops = 3))
            val overview = PartnerServerOverviewDto(listOf(partner(discoveredServers = 2)), emptyList(), false)
            scenario(overview, discovered = rows) { page ->
                assertNull(page.querySelector("img"), "no element may be created from a foreign name")
                assertNull(page.querySelector("b"), "no element may be created from a foreign chain")
                val text = page.textContent.orEmpty()
                assertTrue(text.contains("<img src=x onerror=alert(1)> Fremd"))
                assertTrue(text.contains("über partner.beispiel.de → <b>mitte</b>.beispiel.de"))
                assertTrue(text.contains("Stufe 3"))
                assertTrue(text.contains("Über diesen Partner: 2 Server, 6 Veranstaltungen"))
                val row = assertNotNull(page.querySelector(".partner-discovered") as? HTMLElement)
                assertTrue(row.getAttribute("aria-label") == "fern.beispiel.de")
                val block = row.allOf("button").single { it.textContent.orEmpty().trim() == "Sperren" }
                assertNotNull(block)
            }
        }

    @Test
    fun withoutReachedServersTheListSaysSo(): Promise<Unit> =
        test {
            scenario(PartnerServerOverviewDto(listOf(partner()), emptyList(), false)) { page ->
                assertTrue(page.textContent.orEmpty().contains("Noch keine weiteren Server erreicht."))
                assertFalse(page.textContent.orEmpty().contains("Über diesen Partner"))
            }
        }
}
