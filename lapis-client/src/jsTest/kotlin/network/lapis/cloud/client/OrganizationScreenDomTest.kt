package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IOrganizationTimeZoneService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.221 (six since V1.9.244) -- the page Organisation: six closed sections, one summary line each, content built on the first opening only, the old
 * RPCs behind the switches, deep links and their aliases, and the sidebar entry that only an ADMIN gets.
 */
class OrganizationScreenDomTest {
    private fun settings(
        noise: Boolean = false,
        visibility: TenderBidVisibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
        showCount: Boolean = true,
        finder: Boolean = false,
    ) = jsonOf(
        OrganizationSettingsDto.serializer(),
        OrganizationSettingsDto(
            "o1",
            "Verein",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            showPublicMemberCount = showCount,
            noiseSuppressionDefault = noise,
            memberFinderEnabled = finder,
            tenderDefaultVisibility = visibility,
        ),
    )

    private val zones = """["Europe/Berlin","Asia/Tbilisi","UTC"]"""

    private fun HTMLElement.sections() = allOf("details.lapis-settings-section")

    private fun HTMLElement.section(key: String) = assertNotNull(querySelector("#org-$key") as? HTMLElement, "no section $key")

    private fun HTMLElement.summaryOf(key: String) = section(key).querySelector(".lapis-settings-value")?.textContent.orEmpty()

    private fun HTMLElement.open(key: String) {
        (section(key).querySelector("summary") as HTMLElement).click()
    }

    private fun HTMLElement.isOpen(key: String) = section(key).hasAttribute("open")

    private class Calls {
        var settingsReads = 0
        var noiseWrites = mutableListOf<Boolean>()
        var tenderWrites = mutableListOf<TenderBidVisibility>()
    }

    private suspend fun scenario(
        section: String? = null,
        noise: Boolean = false,
        block: suspend (HTMLElement, Calls) -> Unit,
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
        val calls = Calls()
        var noiseNow = noise
        var visibilityNow = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE
        val getSettings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
        val setNoise = routeOf { rpcService<IOrganizationSettingsService>().updateNoiseSuppressionDefault(true) }
        val setTender = routeOf { rpcService<IOrganizationSettingsService>().updateTenderDefaultVisibility(TenderBidVisibility.OPEN) }
        val getZone = routeOf { rpcService<IOrganizationTimeZoneService>().getOrganizationTimeZone() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getSettings -> {
                        calls.settingsReads++
                        request.answerWith(settings(noise = noiseNow, visibility = visibilityNow))
                    }
                    request.rpcRoute == setNoise -> {
                        noiseNow = request.rpcParam(0) as Boolean
                        calls.noiseWrites += noiseNow
                        request.answerWith(settings(noise = noiseNow))
                    }
                    request.rpcRoute == setTender -> {
                        visibilityNow = TenderBidVisibility.valueOf(request.rpcParam(0) as String)
                        calls.tenderWrites += visibilityNow
                        request.answerWith(settings(visibility = visibilityNow))
                    }
                    request.rpcRoute == getZone -> request.answerWith("""{"zoneId":"Europe/Berlin","availableZoneIds":$zones}""")
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("organization-screen-${section ?: "none"}") { root, element ->
                renderOrganizationScreen(root, section)
                awaitAppScopeIdle("the page finished loading")
                block(element(), calls)
            }
        }
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    @Test
    fun sixSectionsAreThere_allClosed_andTheSummariesShowTheStoredValues(): Promise<Unit> =
        test {
            scenario(noise = true) { page, calls ->
                assertEquals(
                    listOf(
                        "org-allgemein",
                        "org-videokonferenz",
                        "org-aufzeichnung",
                        "org-mitglieder-finder",
                        "org-ausschreibungen",
                        "org-partnerserver",
                        "org-aktionen",
                    ),
                    page.sections().map { it.getAttribute("id") },
                )
                assertTrue(page.sections().none { it.hasAttribute("open") }, "all sections start closed")
                assertEquals("Zeitzone: Europe/Berlin · Mitgliederzahl öffentlich: an", page.summaryOf("allgemein"))
                assertEquals("Rauschunterdrückung: an · Overlays: aus", page.summaryOf("videokonferenz"))
                assertEquals("Pulpit-Aufzeichnung: aus · Veranstaltungen: aus", page.summaryOf("aufzeichnung"))
                assertEquals("Mitglieder-Finder: aus", page.summaryOf("mitglieder-finder"))
                assertTrue(page.summaryOf("ausschreibungen").contains("Verdeckt bis zur Frist"))
                // V1.9.244: a static summary, no RPC of its own
                assertEquals("Befreundete Lapis-Server, Sperrliste", page.summaryOf("partnerserver"))
                // V1.9.246: a static summary, no RPC of its own
                assertEquals("Hinweistexte, Freigabe von Ideen, Meldungen", page.summaryOf("aktionen"))
                // nothing is built before a section is opened: no switch, no select, only the one read for the summaries
                assertNull(page.querySelector("button[role=switch]"))
                assertNull(page.querySelector("select"))
                assertEquals(1, calls.settingsReads, "the summaries read the settings once")
                page.sections().forEach {
                    assertEquals("true", it.querySelector(".lapis-settings-icon")?.getAttribute("aria-hidden"))
                }
            }
        }

    @Test
    fun openingASection_buildsItsControl_once_andASwitchCallsTheOldRpc_thenTheSummaryFollows(): Promise<Unit> =
        test {
            scenario { page, calls ->
                page.open("videokonferenz")
                awaitUntil("the switch appears") { page.section("videokonferenz").querySelector("button[role=switch]") != null }
                awaitAppScopeIdle("the section finished loading")
                val readsAfterOpening = calls.settingsReads
                // closing and opening again does not build a second time
                page.open("videokonferenz")
                awaitUntil("closed") { !page.isOpen("videokonferenz") }
                page.open("videokonferenz")
                awaitUntil("open again") { page.isOpen("videokonferenz") }
                awaitAppScopeIdle("idle")
                assertEquals(readsAfterOpening, calls.settingsReads, "no second build, no second read")
                assertEquals(
                    1,
                    page.section("videokonferenz").allOf("button[role=switch]").count {
                        it.getAttribute("aria-labelledby").orEmpty().startsWith("lapis-noise-admin-label")
                    },
                )
                val noiseSwitch =
                    page.section("videokonferenz").allOf("button[role=switch]").first {
                        it.getAttribute("aria-labelledby").orEmpty().startsWith("lapis-noise-admin-label")
                    }
                noiseSwitch.click()
                awaitUntil("the old RPC was called and the summary follows") {
                    calls.noiseWrites == listOf(true) && page.summaryOf("videokonferenz").startsWith("Rauschunterdrückung: an")
                }
                assertTrue(page.isOpen("videokonferenz"), "the section stays open after saving")
            }
        }

    @Test
    fun theTenderSelect_savesThroughItsOldRpc_andRefreshesTheSummary(): Promise<Unit> =
        test {
            scenario { page, calls ->
                page.open("ausschreibungen")
                awaitUntil("the select appears") { page.section("ausschreibungen").querySelector("select") != null }
                val select = page.section("ausschreibungen").querySelector("select").asDynamic()
                select.value = "OPEN"
                select.dispatchEvent(
                    org.w3c.dom.events
                        .Event("change"),
                )
                page.section("ausschreibungen").buttonNamed("Speichern").click()
                awaitUntil("saved") {
                    calls.tenderWrites == listOf(TenderBidVisibility.OPEN) &&
                        page.summaryOf("ausschreibungen").contains("Offen")
                }
            }
        }

    @Test
    fun aDeepLink_opensThatSection_buildsIt_andIgnoresTheOthers(): Promise<Unit> =
        test {
            scenario(section = "aufzeichnung") { page, _ ->
                assertTrue(page.isOpen("aufzeichnung"))
                assertEquals(listOf("org-aufzeichnung"), page.sections().filter { it.hasAttribute("open") }.map { it.getAttribute("id") })
                awaitUntil("the recording switches are built") {
                    page.section("aufzeichnung").allOf("button[role=switch]").size >= 2
                }
                val link = page.section("aufzeichnung").allOf("a").firstOrNull { it.textContent?.trim() == "Stream-Ziele verwalten" }
                assertEquals("#${Routes.CONFERENCE_STREAM_DESTINATIONS}", link?.getAttribute("href"))
            }
        }

    @Test
    fun aliasKeysOpenTheSectionThatHoldsTheSetting_andAnUnknownKeyOpensNothing(): Promise<Unit> =
        test {
            scenario(section = "zeitzone") { page, _ ->
                assertEquals(listOf("org-allgemein"), page.sections().filter { it.hasAttribute("open") }.map { it.getAttribute("id") })
            }
            scenario(section = "<img src=x onerror=alert(1)>") { page, _ ->
                assertTrue(page.sections().none { it.hasAttribute("open") })
                assertNull(page.querySelector("img"))
            }
        }

    @Test
    fun theKeyResolution_isAFixedLookup() {
        assertEquals("allgemein", resolveOrganizationSection("zeitzone"))
        assertEquals("allgemein", resolveOrganizationSection(" Mitgliederzahl "))
        assertEquals("videokonferenz", resolveOrganizationSection("overlays"))
        assertEquals("aufzeichnung", resolveOrganizationSection("stream"))
        assertEquals("ausschreibungen", resolveOrganizationSection("ausschreibungen"))
        assertNull(resolveOrganizationSection("unbekannt"))
        assertNull(resolveOrganizationSection(""))
        assertNull(resolveOrganizationSection(null))
        ORGANIZATION_SECTION_ALIASES.values.forEach { assertTrue(it in ORGANIZATION_SECTION_KEYS, "alias target $it") }
    }

    @Test
    fun onlyAnAdmin_isOfferedThePage_inTheSidebar() {
        AccountRole.entries.forEach { role ->
            MemberStatus.entries.forEach { status ->
                val ctx =
                    NavContext(
                        session =
                            SessionInfoDto(
                                memberId = "m-1",
                                displayName = "Person",
                                role = role,
                                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                                status = status,
                            ),
                        memberFinderOffered = null,
                    )
                val offered =
                    NavRegistry.visibleGroups(ctx).flatMap { NavRegistry.visibleInGroup(it, ctx) }.any { it.route == Routes.ORGANIZATION }
                if (role != AccountRole.ADMIN) assertFalse(offered, "$role/$status must not be offered the page")
                if (role == AccountRole.ADMIN && status == MemberStatus.ACTIVE) assertTrue(offered, "an active ADMIN is offered the page")
            }
        }
    }
}
