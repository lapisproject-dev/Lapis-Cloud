package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.141 -- the country column and filter of the member roster: shown to BOARD/ADMIN only (flag, name, a dash with an accessible text for
 * "no value"), not sortable, and the filter sets `MemberAdminQuery.countryCode` and goes back to the first page.
 */
class MemberAdminCountryDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun session(role: AccountRole): SessionInfoDto =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    private fun row(
        id: String,
        name: String,
        countryCode: String?,
    ) = MemberAdminRowDto(
        id = id,
        displayName = name,
        email = "$id@example.org",
        status = MemberStatus.ACTIVE,
        role = AccountRole.MEMBER,
        joinedAt = LocalDate(2020, 1, 1),
        countryCode = countryCode,
    )

    private fun pageJson(vararg rows: MemberAdminRowDto): String =
        jsonOf(
            MemberAdminPageDto.serializer(),
            MemberAdminPageDto(
                rows = rows.toList(),
                totalCount = rows.size,
                statusCounts = mapOf(MemberStatus.ACTIVE to rows.size),
                limit = 25,
                offset = 0,
            ),
        )

    private val twoRows =
        arrayOf(
            row("m-pl", "Paula Polen", "PL"),
            row("m-none", "Nina Ohneland", null),
        )

    @Test
    fun boardSeesTheCountryColumn_withFlagAndName_andADashWithAnAccessibleTextForNoValue(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.BOARD))
            // The test server has no flag assets (404). The production error handler would hide the <img> before the asynchronous
            // assertion runs, so the image error is swallowed in the capture phase, before it reaches the element's own handler.
            val swallowImageError: (Event) -> Unit = { it.stopImmediatePropagation() }
            document.addEventListener("error", swallowImageError, true)
            try {
                val listRoute =
                    routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
                val page = pageJson(*twoRows)
                withFetchStub(respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                        else -> rpcResult(request.json.id as Int, "[]")
                    }
                }) {
                    mountedForm("roster-country-board") { root, element ->
                        renderMemberAdministrationScreen(root)
                        awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Paula Polen") }
                        assertTrue(element().allOf("img.lapis-flag[src\$='/pl.svg']").isNotEmpty(), "the flag of Poland")
                        val code = element().allOf("[role=img][aria-label='Polen']").first()
                        assertEquals("PL", code.textContent.orEmpty().trim())
                        assertEquals("Polen", code.getAttribute("title"))
                        assertEquals("Polen", element().allOf("img.lapis-flag[src\$='/pl.svg']").first().getAttribute("title"))
                        assertTrue(
                            element().allOf("img.lapis-flag").all { it.getAttribute("aria-hidden") == "true" },
                            "flags are decorative",
                        )
                        val dash = element().allOf("[aria-label='Keine Angabe']")
                        assertTrue(dash.isNotEmpty(), "the dash for a missing value has an accessible text")
                        assertEquals(
                            "—",
                            dash
                                .first()
                                .textContent
                                .orEmpty()
                                .trim(),
                        )
                        // Not sortable: if a table header "Land" is rendered (wide window), it carries no sort button.
                        element()
                            .allOf("th")
                            .filter {
                                it.textContent
                                    .orEmpty()
                                    .trim()
                                    .startsWith("Land")
                            }.forEach {
                                assertTrue(it.querySelector("button") == null, "no sort button on the country column")
                            }
                        // The filter exists next to the other filters.
                        assertTrue(element().allOf("label").any { it.textContent.orEmpty().trim() == "Land" }, "the filter label")
                    }
                }
            } finally {
                document.removeEventListener("error", swallowImageError, true)
            }
        }

    @Test
    fun flaglessCodesKeepTheName_andAHostileValueStaysText(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.BOARD))
            val swallowImageError: (Event) -> Unit = { it.stopImmediatePropagation() }
            document.addEventListener("error", swallowImageError, true)
            try {
                val listRoute =
                    routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
                val page =
                    pageJson(
                        row("m-xs", "Stella Staatenlos", "XS"),
                        row("m-ge", "Gela Georgien", "GE"),
                        row("m-bad", "Bert Boese", "<script>x</script>"),
                    )
                withFetchStub(respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                        else -> rpcResult(request.json.id as Int, "[]")
                    }
                }) {
                    mountedForm("roster-country-flagless") { root, element ->
                        renderMemberAdministrationScreen(root)
                        awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Stella Staatenlos") }
                        assertEquals(
                            "GE",
                            element()
                                .allOf("[role=img][aria-label='Georgien']")
                                .first()
                                .textContent
                                .orEmpty()
                                .trim(),
                        )
                        assertTrue(element().allOf("img.lapis-flag[src\$='/xs.svg']").isEmpty(), "no flag for XS")
                        assertTrue(element().allOf(".lapis-flag-spacer").isNotEmpty(), "a spacer keeps the names aligned")
                        assertTrue(element().allOf("script").isEmpty(), "a hostile value never becomes an element")
                        assertTrue(element().allOf("img.lapis-flag").all { !(it.getAttribute("src").orEmpty().contains("<")) })
                    }
                }
            } finally {
                document.removeEventListener("error", swallowImageError, true)
            }
        }

    @Test
    fun treasurerSeesNeitherTheCountryColumnNorTheFilter(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.TREASURER))
            val listRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
            val page = pageJson(*twoRows)
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }) { recorded ->
                mountedForm("roster-country-treasurer") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Paula Polen") }
                    assertTrue(element().allOf("img.lapis-flag").isEmpty(), "no flags")
                    assertTrue(element().allOf("[aria-label='Keine Angabe']").isEmpty())
                    assertTrue(element().allOf("input[role=combobox]").isEmpty(), "no country filter combobox")
                    assertNull(
                        recorded
                            .toRoute(listRoute)
                            .first()
                            .rpcParam(0)
                            .countryCode as? String,
                    )
                }
            }
        }

    @Test
    fun theFilterSendsTheCountryCode_andReloadsFromTheFirstPage(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.ADMIN))
            val listRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
            val page = pageJson(*twoRows)
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }) { recorded ->
                mountedForm("roster-country-filter") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Paula Polen") }
                    val before = recorded.toRoute(listRoute).size
                    val input = element().allOf("input[role=combobox]").first() as HTMLInputElement
                    input.value = "poland"
                    input.dispatchEvent(Event("input"))
                    awaitUntil("the option") { element().querySelector("[role=option]") != null }
                    input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true, cancelable = true)))
                    awaitUntil("a reload with the filter") { recorded.toRoute(listRoute).size > before }
                    val query = recorded.toRoute(listRoute).last().rpcParam(0)
                    assertEquals("PL", query.countryCode as String)
                    assertEquals(0, (query.offset as? Int) ?: 0)
                }
            }
        }
}
