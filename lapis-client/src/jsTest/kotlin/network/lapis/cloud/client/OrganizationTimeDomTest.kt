package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IOrganizationTimeZoneService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.38 -- what the person sees. Class-A values are shown in the organization zone (the session expiry with its zone
 * abbreviation, audit timestamps with the UTC instant as hover title), class-B values are shown as stored, and the ADMIN
 * setting offers the server's zones and saves through its own RPC.
 */
class OrganizationTimeDomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
        OrganizationTime.zoneId = DEFAULT_ORGANIZATION_ZONE_ID
        PageFocus.consume()
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun session(
        zone: String = "Europe/Berlin",
        role: AccountRole = AccountRole.ADMIN,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Testperson",
        role = role,
        expiresAt = LocalDateTime(2026, 10, 2, 1, 48),
        status = MemberStatus.ACTIVE,
        organizationTimeZone = zone,
    )

    private fun HTMLElement.buttonLabelled(label: String): HTMLElement? {
        val buttons = querySelectorAll("button")
        return (0 until buttons.length).map { buttons.item(it) as HTMLElement }.firstOrNull { it.textContent?.trim() == label }
    }

    @Test
    fun theSessionZoneReachesTheClock_atLoginAndReset() {
        AppState.setSession(session(zone = "Asia/Tbilisi"))
        assertEquals("Asia/Tbilisi", OrganizationTime.zoneId)
        AppState.setSession(null)
        assertEquals(DEFAULT_ORGANIZATION_ZONE_ID, OrganizationTime.zoneId)
    }

    @Test
    fun dashboard_showsTheSessionExpiryInTheOrganizationZone_withTheAbbreviation(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withFetchStub {
                withMountedRoot("org-time-dashboard") { root, element ->
                    renderDashboardScreen(root)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Sitzung gültig bis 02.10.2026,${NBSP}03:48${NBSP}MESZ"), "dashboard text: $text")
                    assertFalse(text.contains("01:48"), "the UTC wall-clock must not be shown")
                }
            }
        }

    @Test
    fun dashboard_abbreviationFollowsTheCatalog_cestInEnglish(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withTranslationsAsync(mapOf("MESZ" to "CEST")) {
                withFetchStub {
                    withMountedRoot("org-time-dashboard-en") { root, element ->
                        renderDashboardScreen(root)
                        assertTrue(element().textContent.orEmpty().contains("03:48${NBSP}CEST"), "text: ${element().textContent}")
                    }
                }
            }
        }

    @Test
    fun auditColumn_showsTheOrganizationZone_andTheUtcInstantAsTitle(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withMountedRoot("org-time-audit") { root, element ->
                val column =
                    systemTimestampColumn<LocalDateTime>(title = "Zeitpunkt", numeric = false, primary = true, utcTitle = true) { it }
                root.plainDataTable(
                    columns = listOf(column),
                    rows = listOf(LocalDateTime(2026, 10, 2, 1, 48, 5)),
                    viewport = FakeNarrowViewport(narrow = false),
                )
                val cell = assertNotNull(element().querySelector("td span"), "no timestamp cell")
                assertEquals("02.10.2026,${NBSP}03:48:05", cell.textContent)
                assertEquals("2026-10-02T01:48:05Z", cell.getAttribute("title"))
            }
        }

    @Test
    fun classB_eventAndDeadline_areShownAsTypedIn_whateverTheZone(): Promise<Unit> =
        test {
            AppState.setSession(session(zone = "Asia/Tbilisi"))
            withMountedRoot("org-time-classb") { root, element ->
                root.dateTimeSpan(LocalDateTime(2026, 7, 1, 20, 0)) // event start / poll deadline: typed in as 20:00
                assertEquals("01.07.2026,${NBSP}20:00", element().querySelector("span")?.textContent)
            }
        }

    @Test
    fun systemSpan_followsALanguageSwitch_withoutLeakingTheMarker(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withMountedRoot("org-time-switch") { root, element ->
                val span = root.systemDateTimeSpan(LocalDateTime(2026, 10, 2, 1, 48))
                assertEquals("02.10.2026,${NBSP}03:48", span.getElement()?.textContent)
                try {
                    I18n.language = "en"
                    span.refresh()
                    assertEquals("2026-10-02,${NBSP}03:48", element().querySelector("span")?.textContent)
                } finally {
                    I18n.language = "de"
                }
                assertFalse(element().textContent.orEmpty().contains(KV_I18N_MARKER))
            }
        }

    @Test
    fun adminCard_offersTheServersZones_commonFirst_previewsAndSaves(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val getRoute = routeOf { rpcService<IOrganizationTimeZoneService>().getOrganizationTimeZone() }
            val setRoute = routeOf { rpcService<IOrganizationTimeZoneService>().updateOrganizationTimeZone("UTC") }
            val zones = """["Africa/Cairo","Asia/Tbilisi","Europe/Berlin","Europe/Vienna","UTC"]"""
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute ->
                        rpcResult(
                            request.json.id as Int,
                            """{"zoneId":"Europe/Berlin","availableZoneIds":$zones}""",
                        )
                    request.rpcRoute == setRoute ->
                        rpcResult(
                            request.json.id as Int,
                            """{"zoneId":"Asia/Tbilisi","availableZoneIds":$zones}""",
                        )
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) { requests ->
                withMountedRoot("org-time-admin") { root, element ->
                    renderOrganizationTimeZoneCard(root)
                    awaitUntil("the zone form is rendered") { element().querySelector("select") != null }
                    val select = element().querySelector("select") as HTMLSelectElement
                    val values =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)!!
                                .asDynamic()
                                .value as String
                        }
                    // common zones first (in the fixed order), the separator, then the rest sorted
                    assertEquals(listOf("Europe/Berlin", "Europe/Vienna", "Asia/Tbilisi", "UTC", "", "Africa/Cairo"), values)
                    assertEquals("Europe/Berlin", select.value)
                    assertTrue(element().textContent.orEmpty().contains("Jetzt: "), "live preview shown")
                    val note = "Wiederkehrende Veranstaltungen verwenden weiterhin Europe/Berlin."
                    assertFalse(
                        element()
                            .querySelector("div.text-muted.small:not(.d-none)")
                            ?.textContent
                            .orEmpty()
                            .contains(note),
                    )

                    select.value = "Asia/Tbilisi"
                    select.dispatchEvent(Event("change"))
                    awaitUntil("the preview names the new zone's offset") { element().textContent.orEmpty().contains("UTC+4") }
                    assertTrue(element().textContent.orEmpty().contains(note), "series note appears for a zone other than Europe/Berlin")

                    assertNotNull(element().buttonLabelled("Speichern")).click()
                    awaitUntil("the new zone is sent") { requests.any { it.isRpc && it.rpcRoute == setRoute } }
                    assertEquals("Asia/Tbilisi", requests.first { it.rpcRoute == setRoute }.rpcParam(0).toString())
                }
            }
        }

    @Test
    fun zoneSelectOptions_listsOnlyOfferedCommonZones_andSkipsASeparatorWhenThereIsNothingToSeparate() {
        assertEquals(listOf("UTC" to "UTC"), zoneSelectOptions(listOf("UTC")))
        assertEquals(
            listOf("Europe/Berlin" to "Europe/Berlin", "UTC" to "UTC", "" to "──────────", "Africa/Cairo" to "Africa/Cairo"),
            zoneSelectOptions(listOf("UTC", "Africa/Cairo", "Europe/Berlin")),
        )
    }

    @Test
    fun organizationZonePreview_nameTheLocalTimeAndTheAbbreviation() {
        val at = kotlin.time.Instant.parse("2026-10-02T01:48:00Z")
        assertEquals("02.10.2026,${NBSP}03:48${NBSP}MESZ", organizationZonePreview("Europe/Berlin", at))
        assertEquals("02.10.2026,${NBSP}05:48${NBSP}UTC+4", organizationZonePreview("Asia/Tbilisi", at))
        assertEquals(LocalDate(2026, 10, 2), LocalDate(2026, 10, 2))
    }
}
