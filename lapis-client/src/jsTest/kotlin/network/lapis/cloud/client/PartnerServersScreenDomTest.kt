package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
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
    )

    private suspend fun scenario(
        overview: PartnerServerOverviewDto,
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
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list -> request.answerWith(jsonOf(PartnerServerOverviewDto.serializer(), overview))
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
}
