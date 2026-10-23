package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDunningService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.190 -- the dunning-level table: wide shell, seven columns (the last header visually hidden but named "Aktionen"), icon-only edit /
 * deactivate buttons named after the level, a right-aligned tabular fee column.
 */
class DunningSettingsActionIconsDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val settingsJson =
        """{"dunningEnabled":true,"pollerEnabled":false,"postalDispatchEnabled":false,"postalMailEnabled":false,""" +
            """"activeLevelCount":1,"lastDisclaimerVersion":null,"lastAcknowledgedAt":null}"""

    private fun level(
        id: String,
        number: Int,
        name: String,
        fee: String?,
        active: Boolean,
    ) = """{"id":"$id","levelNumber":$number,"name":"$name","graceDays":7,"responseDays":14,"feeAmount":${fee?.let { "\"$it\"" }
        ?: "null"},"active":$active}"""

    private fun screenTest(check: suspend (HTMLElement) -> Unit): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(admin)
            val settingsRoute = routeOf { rpcService<IDunningService>().getDunningSettings() }
            val levelsRoute = routeOf { rpcService<IDunningService>().listDunningLevels(includeInactive = false) }
            withFetchStub(
                { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == settingsRoute -> request.answerWith(settingsJson)
                        request.rpcRoute == levelsRoute ->
                            request.answerWith(
                                "[" + level("l1", 1, "Erste Mahnung", "5.00", true) + "," + level("l2", 2, "Zweite Mahnung", null, true) +
                                    "]",
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("dunning-icons") { root, element ->
                    renderDunningSettingsScreen(root)
                    awaitUntil("the level table") { element().querySelector("table tbody tr") != null }
                    check(element())
                }
            }
        }

    @Test
    fun table_hasSevenHeaders_theLastOneVisuallyHiddenButNamed(): Promise<Unit> =
        screenTest { el ->
            val headers = el.allOf("table thead th")
            assertEquals(7, headers.size)
            val last = headers.last()
            assertTrue(last.classList.contains("visually-hidden"))
            assertEquals("Aktionen", last.textContent?.trim())
            assertNotNull(el.querySelector(".lapis-page--wide"), "the wide shell")
        }

    @Test
    fun actionButtons_areIconOnly_andNamedAfterTheLevel(): Promise<Unit> =
        screenTest { el ->
            val first = el.allOf("table tbody tr").first()
            val edit = assertNotNull(first.querySelector("button[aria-label='Mahnstufe \"Erste Mahnung\" bearbeiten']") as? HTMLElement)
            assertEquals(edit.getAttribute("aria-label"), edit.getAttribute("title"))
            assertEquals("", edit.textContent?.trim().orEmpty(), "icon-only")
            assertEquals("true", (edit.querySelector("i") as HTMLElement).getAttribute("aria-hidden"))
            assertTrue(edit.classList.contains("btn-sm"))
            val off = assertNotNull(first.querySelector("button[aria-label='Mahnstufe \"Erste Mahnung\" deaktivieren']") as? HTMLElement)
            assertEquals(off.getAttribute("aria-label"), off.getAttribute("title"))
            assertTrue(off.classList.contains("btn-outline-danger"))
            assertEquals(2, first.allOf("button").size)
        }

    @Test
    fun feeCell_isRightAlignedWithTabularFigures(): Promise<Unit> =
        screenTest { el ->
            val fee = el.allOf("table tbody tr").first().allOf("td")[4]
            val style = window.getComputedStyle(fee)
            assertEquals("right", style.getPropertyValue("text-align"))
            assertTrue(style.getPropertyValue("font-variant-numeric").contains("tabular-nums"))
        }

    @Test
    fun createAndInactiveToggle_stillExist(): Promise<Unit> =
        screenTest { el ->
            assertTrue(el.textContent.orEmpty().contains("Neue Mahnstufe"))
            assertTrue(el.textContent.orEmpty().contains("Inaktive Stufen anzeigen"))
            assertFalse(el.textContent.orEmpty().contains("PdV"))
        }
}
