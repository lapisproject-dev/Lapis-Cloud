package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.183: the collapsible group headers of the sidebar, measured in a real mounted DOM (Chrome via Karma): trailing chevron,
 * `aria-expanded`/`aria-controls`, the active-group marker, the storage rules (only header clicks persist, v2 key with one-time
 * migration, no exception when storage throws) and the CSS the headers depend on. Honest limits: no real phone, no screen reader,
 * no Safari; keyboard activation relies on the native `<button>` (a synthetic `click()` stands in for Enter/Space).
 */
class SidebarGroupHeaderDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @BeforeTest
    fun setUp() {
        clearStorage()
        AppState.setSession(adminSession)
    }

    @AfterTest
    fun tearDown() {
        clearStorage()
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    private fun clearStorage() {
        runCatching {
            localStorage.removeItem(SidebarGroupStorage.STORAGE_KEY)
            localStorage.removeItem(SidebarGroupStorage.LEGACY_STORAGE_KEY)
        }
    }

    private fun <T> withSidebar(
        activeRoute: String?,
        block: (HTMLElement) -> T,
    ): T {
        assertTrue(stylesLoaded)
        return withMountedRoot("sidebar-group-header-test") { root, element ->
            if (activeRoute != null) NavHighlight.setActiveRoute(activeRoute)
            val panel = SimplePanel()
            buildSidebar(panel, adminSession, activeRoute = activeRoute) {}
            root.add(panel)
            block(element())
        }
    }

    private fun HTMLElement.headers(): List<HTMLElement> =
        querySelectorAll("button.lapis-sidebar-group-header").asList().map { it as HTMLElement }

    /** The group body, or `null` while the group is closed (KVision does not render a hidden widget, so a closed body is not in the DOM). */
    private fun bodyOf(header: HTMLElement): HTMLElement? = document.getElementById(header.getAttribute("aria-controls")!!) as HTMLElement?

    private fun HTMLElement.activeHeaderIds(): List<String> =
        headers().filter { it.classList.contains("active") }.map { it.getAttribute("aria-controls")!! }

    private fun bodyId(group: SidebarGroupId) = "lapis-sidebar-group-${group.name.lowercase()}"

    /** The last (= winning, later in the cascade) style rule whose selector text equals [selector], across all loaded stylesheets. */
    private fun lastRuleFor(selector: String): dynamic {
        var found: dynamic = null
        val sheets = document.asDynamic().styleSheets
        for (i in 0 until (sheets.length as Int)) {
            val rules = runCatching { sheets[i].cssRules }.getOrNull() ?: continue
            for (k in 0 until (rules.length as Int)) {
                if (rules[k].selectorText == selector) found = rules[k]
            }
        }
        return found
    }

    @Test
    fun everyHeader_hasAHiddenTrailingChevron() {
        withSidebar(activeRoute = null) { sidebar ->
            val headers = sidebar.headers()
            assertEquals(6, headers.size)
            headers.forEach { header ->
                val chevron = header.querySelector(".lapis-sidebar-group-chevron") as HTMLElement
                assertNotNull(chevron)
                assertEquals("true", chevron.getAttribute("aria-hidden"))
                assertTrue(
                    ActionIcon.CHEVRON.css
                        .split(" ")
                        .all { chevron.classList.contains(it) },
                    "chevron carries ${ActionIcon.CHEVRON.css}",
                )
                assertTrue(header.lastElementChild === chevron, "the chevron is the last child of the header")
            }
        }
    }

    @Test
    fun click_togglesAriaExpandedAndVisibility_alsoOnTheChevron() {
        withSidebar(activeRoute = null) { sidebar ->
            val id = sidebar.headers().first().getAttribute("aria-controls")!!

            fun header() = sidebar.headers().first { it.getAttribute("aria-controls") == id }
            assertEquals("false", header().getAttribute("aria-expanded"))
            assertEquals(null, bodyOf(header()), "a closed group renders no body")

            header().click()
            assertEquals("true", header().getAttribute("aria-expanded"))
            assertNotNull(bodyOf(header()), "an open group renders its body")

            (header().querySelector(".lapis-sidebar-group-chevron") as HTMLElement).click()
            assertEquals("false", header().getAttribute("aria-expanded"))
            assertEquals(null, bodyOf(header()))
        }
    }

    @Test
    fun ariaControls_pointsToAnExistingBody() {
        withSidebar(activeRoute = null) { sidebar ->
            val ids = sidebar.headers().map { it.getAttribute("aria-controls") }
            assertEquals(SidebarGroupId.entries.map { bodyId(it) }.toSet(), ids.toSet())
            // Open every group: an open group's body exists under exactly that id (a closed one is not rendered at all).
            sidebar.headers().forEach { it.click() }
            sidebar.headers().forEach { assertNotNull(bodyOf(it), "body of ${it.getAttribute("aria-controls")}") }
        }
    }

    @Test
    fun headers_areNativeButtons() {
        // Enter/Space activation is the browser's own for a real <button type=button>; a synthetic key event would not prove more.
        withSidebar(activeRoute = null) { sidebar ->
            sidebar.headers().forEach {
                assertEquals("BUTTON", it.tagName)
                assertEquals("button", it.getAttribute("type"))
            }
        }
    }

    @Test
    fun activeRoute_opensItsGroupAtBuild_andMarksTheHeader() {
        val route = GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first()
        withSidebar(activeRoute = route) { sidebar ->
            assertEquals(listOf(bodyId(SidebarGroupId.FINANCE)), sidebar.activeHeaderIds())
            val finance = sidebar.headers().first { it.getAttribute("aria-controls") == bodyId(SidebarGroupId.FINANCE) }
            assertEquals("true", finance.getAttribute("aria-expanded"))
        }
    }

    @Test
    fun navigation_movesTheActiveMarker_andOpensTheNewGroup_closedGroupsAreMarkedToo() {
        withSidebar(activeRoute = GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first()) { sidebar ->
            val membershipRoute = GROUP_ROUTES.getValue(SidebarGroupId.MEMBERSHIP).first()
            NavHighlight.setActiveRoute(membershipRoute)
            assertEquals(listOf(bodyId(SidebarGroupId.MEMBERSHIP)), sidebar.activeHeaderIds())
            val membership = sidebar.headers().first { it.getAttribute("aria-controls") == bodyId(SidebarGroupId.MEMBERSHIP) }
            assertEquals("true", membership.getAttribute("aria-expanded"))

            membership.click() // collapse the group that holds the current page
            val closed = sidebar.headers().first { it.getAttribute("aria-controls") == bodyId(SidebarGroupId.MEMBERSHIP) }
            assertEquals("false", closed.getAttribute("aria-expanded"))
            assertTrue(closed.classList.contains("active"), "a collapsed group with the current page keeps its marker")
        }
    }

    @Test
    fun flatRoutes_markNoHeader() {
        listOf(Routes.DASHBOARD, Routes.CONFERENCE, Routes.ENCOUNTER).forEach { route ->
            withSidebar(activeRoute = route) { sidebar ->
                assertEquals(emptyList(), sidebar.activeHeaderIds(), "flat route $route marks no group header")
            }
        }
    }

    @Test
    fun highlightAndGroupLookup_agreeForEveryGroupRoute() {
        withSidebar(activeRoute = null) { sidebar ->
            val seenGroups = mutableSetOf<SidebarGroupId>()
            GROUP_ROUTES.values.flatten().forEach { route ->
                NavHighlight.setActiveRoute(route)
                val marked = sidebar.activeHeaderIds()
                if (marked.isNotEmpty()) {
                    val expected = sidebarGroupForRoute(route)
                    assertNotNull(expected, "route $route is marked but has no group")
                    assertEquals(listOf(bodyId(expected)), marked, "route $route")
                    seenGroups += expected
                }
            }
            assertEquals(SidebarGroupId.entries.toSet(), seenGroups, "every group is reachable through at least one of its routes")
        }
    }

    @Test
    fun automaticOpening_isNotStored() {
        withSidebar(activeRoute = GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first()) { _ ->
            assertEquals(null, localStorage.getItem(SidebarGroupStorage.STORAGE_KEY))
            NavHighlight.setActiveRoute(GROUP_ROUTES.getValue(SidebarGroupId.MEMBERSHIP).first())
            assertEquals(null, localStorage.getItem(SidebarGroupStorage.STORAGE_KEY), "routing opens groups without persisting")
        }
    }

    @Test
    fun click_persistsOnlyTheClickedGroup_notTheAutomaticallyOpenedOne() {
        withSidebar(activeRoute = GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first()) { sidebar ->
            sidebar.headers().first { it.getAttribute("aria-controls") == bodyId(SidebarGroupId.SYSTEM) }.click()
            assertEquals(SidebarGroupId.SYSTEM.storageKey, localStorage.getItem(SidebarGroupStorage.STORAGE_KEY))
        }
    }

    @Test
    fun legacyKey_isMigratedOnce_sanitised_andRemoved() {
        localStorage.setItem(SidebarGroupStorage.LEGACY_STORAGE_KEY, "membership,bogus,finance")
        val loaded = SidebarGroupStorage.load()
        assertEquals(setOf(SidebarGroupId.MEMBERSHIP, SidebarGroupId.FINANCE), loaded)
        assertEquals("membership,finance", localStorage.getItem(SidebarGroupStorage.STORAGE_KEY))
        assertEquals(null, localStorage.getItem(SidebarGroupStorage.LEGACY_STORAGE_KEY))
    }

    @Test
    fun unknownIds_inV2_areIgnored() {
        localStorage.setItem(SidebarGroupStorage.STORAGE_KEY, "<img src=x onerror=alert(1)>,system")
        assertEquals(setOf(SidebarGroupId.SYSTEM), SidebarGroupStorage.load())
    }

    @Test
    fun throwingStorage_neverThrows_andTheGroupsStayUsable() {
        val proto: dynamic = js("Storage.prototype")
        val originalGet = proto.getItem
        val originalSet = proto.setItem
        try {
            proto.getItem = js("(function () { throw new Error('blocked'); })")
            proto.setItem = js("(function () { throw new Error('blocked'); })")
            assertEquals(emptySet(), SidebarGroupStorage.load())
            SidebarGroupStorage.save(setOf(SidebarGroupId.SYSTEM)) // must not throw
            withSidebar(activeRoute = GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first()) { sidebar ->
                val finance = { sidebar.headers().first { it.getAttribute("aria-controls") == bodyId(SidebarGroupId.FINANCE) } }
                assertEquals("true", finance().getAttribute("aria-expanded"), "the current group is open without storage")
                finance().click()
                assertEquals("false", finance().getAttribute("aria-expanded"))
            }
        } finally {
            proto.getItem = originalGet
            proto.setItem = originalSet
        }
    }

    @Test
    fun chevron_rotatesOnlyWhileExpanded() {
        withSidebar(activeRoute = null) { sidebar ->
            val id = sidebar.headers().first().getAttribute("aria-controls")!!

            fun header() = sidebar.headers().first { it.getAttribute("aria-controls") == id }

            fun transform() = window.getComputedStyle(header().querySelector(".lapis-sidebar-group-chevron") as HTMLElement).transform
            assertEquals("none", transform())
            header().click()
            val matrix = transform()
            assertTrue(matrix.startsWith("matrix("), "expanded chevron is rotated: $matrix")
            val values =
                matrix
                    .removePrefix("matrix(")
                    .removeSuffix(")")
                    .split(",")
                    .map { it.trim().toDouble() }
            assertTrue(values[1] > 0.99 && kotlin.math.abs(values[0]) < 0.01, "a quarter turn: $matrix")
        }
    }

    @Test
    fun focusRing_isFleckInk_insideTheRow_withoutShadowOrBackground() {
        assertTrue(stylesLoaded)
        // The :focus-visible state cannot be forced from script reliably, so the rule itself is read from the loaded stylesheets.
        val rule = lastRuleFor(".lapis-sidebar-group-header:focus-visible")
        assertNotNull(rule, "focus-visible rule for the group header exists")
        val declared = rule.style.cssText as String
        assertTrue(declared.contains("var(--lapis-fleck-ink)"), "outline uses fleck-ink: $declared")
        assertEquals("-2px", rule.style.getPropertyValue("outline-offset"))
        assertEquals("none", rule.style.getPropertyValue("box-shadow"))

        withSidebar(activeRoute = null) { sidebar ->
            val header = sidebar.headers().first()
            header.focus()
            if (header.matches(":focus-visible")) {
                val computed = window.getComputedStyle(header)
                assertEquals("none", computed.boxShadow)
                assertEquals("-2px", computed.outlineOffset)
            }
            // Focus without hover: no tint behind the row.
            val background = window.getComputedStyle(header).backgroundColor
            assertTrue(background == "rgba(0, 0, 0, 0)" || background == "transparent", "no background on a focused header: $background")
        }
    }
}
