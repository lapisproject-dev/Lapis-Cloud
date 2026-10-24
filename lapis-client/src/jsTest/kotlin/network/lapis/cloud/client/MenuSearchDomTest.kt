package network.lapis.cloud.client

import io.kvision.panel.Root
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.203: the menu search in a real mounted root -- the field in the sidebar, the Ctrl+K command palette, the keyboard, the focus, the
 * escaping of text, the roles and the listeners that must not pile up.
 */
class MenuSearchDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }

        const val FIELD_INPUT = "lapis-menu-search-input"
        const val PALETTE_INPUT = "lapis-command-palette-input"
    }

    private fun session(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Person",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
    )

    private val admin = session(AccountRole.ADMIN)
    private val navigated = mutableListOf<String>()
    private val previousNavigator = menuEntryNavigator

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        navigated.clear()
        menuEntryNavigator = { navigated += it }
        localStorage.removeItem(MenuRecents.KEY)
    }

    @AfterTest
    fun tearDown() {
        menuEntryNavigator = previousNavigator
        CommandPalette.uninstall()
        MenuSearchField.disposeCurrent()
        MenuIndex.reset()
        AppState.onSessionChange = {}
        AppState.setSession(null)
        localStorage.removeItem(MenuRecents.KEY)
    }

    /** Mounts the sidebar of [who] and the palette into a real root, then runs [block]. */
    private suspend fun <T> withMenu(
        who: SessionInfoDto = admin,
        block: suspend (root: Root, panel: SimplePanel, element: () -> HTMLElement) -> T,
    ): T =
        withMountedRoot("menu-search-dom") { root, element ->
            AppState.setSession(who)
            val panel = SimplePanel()
            root.add(panel)
            buildSidebar(panel, who, activeRoute = null) {}
            CommandPalette.install(root)
            block(root, panel, element)
        }

    private fun byId(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

    private fun fieldInput(): HTMLInputElement = byId(FIELD_INPUT) as HTMLInputElement

    private fun paletteInput(): HTMLInputElement? = byId(PALETTE_INPUT) as? HTMLInputElement

    private fun options(scope: String): List<HTMLElement> =
        document.querySelectorAll("$scope [role=option]").let { list -> (0 until list.length).map { list[it] as HTMLElement } }

    private fun dialogs() = document.querySelectorAll("[role=dialog].lapis-command-palette__dialog").length

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input", EventInit(bubbles = true)))
    }

    private fun key(
        target: HTMLElement,
        key: String,
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): KeyboardEvent {
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(
                    key = key,
                    ctrlKey = ctrl,
                    metaKey = meta,
                    altKey = alt,
                    shiftKey = shift,
                    bubbles = true,
                    cancelable = true,
                ),
            )
        target.dispatchEvent(event)
        return event
    }

    private suspend fun openPalette(): HTMLInputElement {
        key(document.body!!, "k", ctrl = true)
        awaitUntil("the palette opens with the focus in its input") {
            paletteInput() != null && document.activeElement?.id == PALETTE_INPUT
        }
        return checkNotNull(paletteInput())
    }

    @Test
    fun field_isALabelledCombobox_andTheFirstEntryOfTheNav(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                val input = fieldInput()
                assertEquals("combobox", input.getAttribute("role"))
                assertEquals("false", input.getAttribute("aria-expanded"))
                assertEquals("lapis-menu-search-list", input.getAttribute("aria-controls"))
                assertEquals("list", input.getAttribute("aria-autocomplete"))
                assertEquals("off", input.getAttribute("autocomplete"))
                assertTrue(input.getAttribute("aria-keyshortcuts") in listOf("Control+K", "Meta+K"))
                assertEquals("Suchen …", input.getAttribute("placeholder"))
                val label = element().querySelector("label[for=$FIELD_INPUT]")
                assertEquals("Menü durchsuchen", label?.textContent)
                val nav = element().querySelector(".lapis-sidebar-nav") as HTMLElement
                assertTrue((nav.firstElementChild as HTMLElement).classList.contains("lapis-menu-search"), "before Dashboard")
                val hint = element().querySelector(".lapis-menu-search__hint")?.textContent
                assertTrue(hint == "⌘K" || hint == "Strg K", hint)
            }
        }

    @Test
    fun typing_showsHitsWithTheirGroup_andMarksTheWords(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                awaitUntil("the overlay opens on focus") { input.getAttribute("aria-expanded") == "true" }
                type(input, "reise")
                awaitUntil("hits for 'reise'") { options(".lapis-menu-search").size >= 2 }
                val first = options(".lapis-menu-search").first()
                assertTrue(first.textContent.orEmpty().contains("Reisekosten"), first.textContent)
                assertEquals("Reise", first.querySelector("mark")?.textContent)
                assertEquals("Mitgliedschaft", first.querySelector(".lapis-menu-search__path")?.textContent)
                assertEquals("true", first.getAttribute("aria-selected"))
                assertEquals(first.id, input.getAttribute("aria-activedescendant"))
            }
        }

    @Test
    fun aSpellingWithAeFindsTheUmlaut_andAGermanKeywordFindsTheEntry(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "Beitraege")
                awaitUntil("'Beiträge' found by its transcription") {
                    options(".lapis-menu-search").any { it.textContent.orEmpty().contains("Beiträge") }
                }
                type(input, "spesen")
                awaitUntil("'Reisekosten' found by the keyword 'spesen'") {
                    options(".lapis-menu-search").any { it.textContent.orEmpty().contains("Reisekosten") }
                }
            }
        }

    @Test
    fun menuTextWithMarkup_isShownAsText_neverInterpreted(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                js("delete window.__lapisXss")
                MenuIndex.register(
                    route = "/x-test",
                    title = "<img src=x onerror=\"window.__lapisXss=1\">Seite",
                    icon = "fas fa-x",
                    groupLabel = "<b>Gruppe</b>",
                )
                val input = fieldInput()
                input.focus()
                type(input, "img")
                awaitUntil("the crafted entry is a hit") { options(".lapis-menu-search").isNotEmpty() }
                val option = options(".lapis-menu-search").first()
                assertNull(option.querySelector("img"), "no element was created from the title")
                assertNull(option.querySelector("b"), "no element was created from the group")
                assertTrue(option.textContent.orEmpty().contains("<img src=x"), option.textContent)
                assertTrue(option.textContent.orEmpty().contains("<b>Gruppe</b>"), option.textContent)
                delay(50)
                assertTrue(js("typeof window.__lapisXss === 'undefined'").unsafeCast<Boolean>(), "no handler ran")
                // the query itself is not interpreted either
                type(input, "<img src=x onerror=window.__lapisXss=2>")
                delay(200)
                assertNull(element().querySelector(".lapis-menu-search img"))
                assertTrue(js("typeof window.__lapisXss === 'undefined'").unsafeCast<Boolean>())
            }
        }

    @Test
    fun arrows_wrapAround_enterGoesToTheActiveHit_andRemembersIt(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "konto")
                val expected =
                    MenuSearchMatcher.search("konto", MenuIndex.entries(), MenuSearchKeywords.BY_ROUTE).shown.map { it.entry }
                assertTrue(expected.size >= 3, "enough hits to move through: ${expected.size}")
                awaitUntil("the hits are drawn") { options(".lapis-menu-search").size == expected.size }
                assertEquals(options(".lapis-menu-search")[0].id, input.getAttribute("aria-activedescendant"))

                key(input, "ArrowDown")
                assertEquals(options(".lapis-menu-search")[1].id, input.getAttribute("aria-activedescendant"))
                key(input, "ArrowUp")
                key(input, "ArrowUp")
                assertEquals(options(".lapis-menu-search").last().id, input.getAttribute("aria-activedescendant"), "wraps to the last")
                key(input, "ArrowDown")
                assertEquals(options(".lapis-menu-search")[0].id, input.getAttribute("aria-activedescendant"), "wraps to the first")

                key(input, "ArrowDown")
                val enter = key(input, "Enter")
                assertTrue(enter.defaultPrevented)
                assertEquals(listOf(expected[1].route), navigated)
                assertEquals(listOf(expected[1].route), MenuRecents.load())
                assertNotEquals(FIELD_INPUT, document.activeElement?.id, "the field gives the focus back")
                assertEquals("false", input.getAttribute("aria-expanded"))
            }
        }

    @Test
    fun aClickOnAHit_navigates_withoutTheFieldLosingTheFocusFirst(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "dokumente")
                awaitUntil("a hit") { options(".lapis-menu-search").isNotEmpty() }
                val option = options(".lapis-menu-search").first()
                val down = MouseEvent("mousedown", MouseEventInit(bubbles = true, cancelable = true))
                option.dispatchEvent(down)
                assertTrue(down.defaultPrevented, "the mouse must not take the focus (the overlay would close before the click)")
                option.click()
                assertEquals(listOf(Routes.DOCUMENTS), navigated)
            }
        }

    @Test
    fun escape_closesTheOverlay_andClearsTheField(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "konto")
                awaitUntil("hits") { options(".lapis-menu-search").isNotEmpty() }
                key(input, "Escape")
                assertEquals("false", input.getAttribute("aria-expanded"))
                assertEquals("", input.value)
                awaitUntil("the overlay is gone") { options(".lapis-menu-search").isEmpty() }
            }
        }

    @Test
    fun anEmptyField_offersTheLastChoices_newestFirst(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                MenuRecents.remember(Routes.DOCUMENTS)
                MenuRecents.remember(Routes.CONTRIBUTIONS)
                val input = fieldInput()
                input.focus()
                awaitUntil("the recents are drawn") { options(".lapis-menu-search").size == 2 }
                assertEquals("Zuletzt besucht", document.querySelector(".lapis-menu-search__heading")?.textContent)
                val titles = options(".lapis-menu-search").map { it.querySelector(".lapis-menu-search__title")?.textContent }
                assertEquals(listOf("Beiträge", "Dokumente"), titles)
            }
        }

    @Test
    fun withoutRecents_anEmptyFieldOffersTheFirstEntriesOfTheMenu(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                fieldInput().focus()
                awaitUntil("the first entries") { options(".lapis-menu-search").size == MenuSearchMatcher.MAX_RESULTS }
                assertNull(document.querySelector(".lapis-menu-search__heading"))
                assertEquals("Dashboard", options(".lapis-menu-search").first().querySelector(".lapis-menu-search__title")?.textContent)
            }
        }

    @Test
    fun noHit_isSaid_andMoreThanEight_isCappedWithAHint(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                val input = fieldInput()
                input.focus()
                type(input, "zzzzqq")
                awaitUntil("the empty message") {
                    element().querySelector(".lapis-menu-search__empty")?.textContent == "Keine Treffer"
                }
                awaitUntil("the live region says it") { liveText(element()) == "Keine Treffer" }

                type(input, "e")
                val total = MenuSearchMatcher.search("e", MenuIndex.entries(), MenuSearchKeywords.BY_ROUTE).total
                assertTrue(total > MenuSearchMatcher.MAX_RESULTS, "'e' matches many entries: $total")
                awaitUntil("eight options and the hint") { options(".lapis-menu-search").size == MenuSearchMatcher.MAX_RESULTS }
                val more = "$total Treffer, ${MenuSearchMatcher.MAX_RESULTS} angezeigt. Suche verfeinern"
                assertEquals(more, element().querySelector(".lapis-menu-search__more")?.textContent)
                awaitUntil("the live region says it") { liveText(element()) == more }
            }
        }

    @Test
    fun liveRegion_announcesTheNumberOfHits_afterTheDebounce(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                val input = fieldInput()
                input.focus()
                type(input, "reise")
                val total = MenuSearchMatcher.search("reise", MenuIndex.entries(), MenuSearchKeywords.BY_ROUTE).total
                assertEquals("", liveText(element()), "not announced before the debounce has run")
                awaitUntil("announced") { liveText(element()) == "$total Treffer" }
                assertEquals("polite", element().querySelector(".lapis-menu-search [role=status]")?.getAttribute("aria-live"))
            }
        }

    private fun liveText(element: HTMLElement): String = element.querySelector(".lapis-menu-search [role=status]")?.textContent.orEmpty()

    @Test
    fun aMemberDoesNotFindAdminScreens_theAdminDoes(): Promise<Unit> =
        formTest {
            withMenu(session(AccountRole.MEMBER)) { _, _, element ->
                val input = fieldInput()
                input.focus()
                type(input, "backup")
                awaitUntil("no hit for a member") {
                    element().querySelector(".lapis-menu-search__empty")?.textContent == "Keine Treffer"
                }
                assertTrue(options(".lapis-menu-search").isEmpty())
            }
            withMenu(admin) { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "backup")
                awaitUntil("the admin finds the backup screen") {
                    options(".lapis-menu-search").any { it.textContent.orEmpty().contains("Backup & Wiederherstellung") }
                }
            }
        }

    @Test
    fun aGuest_findsTheFlatCarpoolLink_andNoGroupEntry(): Promise<Unit> =
        formTest {
            withMenu(session(AccountRole.MEMBER, MemberStatus.GUEST)) { _, _, _ ->
                val input = fieldInput()
                input.focus()
                type(input, "mitfahr")
                awaitUntil("the carpool centre") {
                    options(".lapis-menu-search").any { it.querySelector(".lapis-menu-search__title")?.textContent == "Mitfahrzentrale" }
                }
                val carpool = options(".lapis-menu-search").first { it.textContent.orEmpty().contains("Mitfahrzentrale") }
                assertNull(carpool.querySelector(".lapis-menu-search__path"), "a flat entry has no group")
                type(input, "kontenplan")
                awaitUntil("nothing for the finance screens") { document.querySelector(".lapis-menu-search__empty") != null }
            }
        }

    @Test
    fun theEnglishTitle_andTheGermanKeyword_bothFindTheEntry(): Promise<Unit> =
        formTest {
            withTranslationsAsync(mapOf("Zahlungseingänge" to "Incoming payments", "Finanzen" to "Finance")) {
                withMenu { _, _, _ ->
                    val input = fieldInput()
                    input.focus()
                    type(input, "incoming")
                    awaitUntil("found by the translated title") {
                        options(".lapis-menu-search").any { it.textContent.orEmpty().startsWith("Incoming payments") }
                    }
                    val path = options(".lapis-menu-search").first().querySelector(".lapis-menu-search__path")?.textContent
                    assertEquals("Finance", path)
                    type(input, "Überweisung")
                    awaitUntil("found by the German keyword in an English menu") {
                        options(".lapis-menu-search").any { it.textContent.orEmpty().startsWith("Incoming payments") }
                    }
                }
            }
        }

    @Test
    fun counterLinks_areIndexedWithTheirBaseTitle(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val titles = MenuIndex.entries().map { it.title }
                assertTrue("Reisekosten-Freigaben" in titles)
                assertTrue("Beitragsvergünstigungen" in titles)
                assertTrue("Ehrenamtspauschalen-Freigaben" in titles)
                assertTrue(titles.none { it.contains("(") }, "a counter is never part of the searchable title")
            }
        }

    @Test
    fun ctrlK_andCmdK_openThePalette_withTheFocusInTheInput_andEscapeReturnsTheFocus(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                val before = document.createElement("button") as HTMLElement
                before.textContent = "before"
                element().appendChild(before)
                before.focus()
                val input = openPalette()
                assertEquals(1, dialogs())
                val dialog = document.querySelector("[role=dialog].lapis-command-palette__dialog") as HTMLElement
                assertEquals("true", dialog.getAttribute("aria-modal"))
                assertEquals("Befehlspalette", dialog.getAttribute("aria-label"))
                assertEquals(input.id, document.activeElement?.id)
                key(input, "Escape")
                awaitUntil("the palette closes") { dialogs() == 0 }
                assertSame(before, document.activeElement, "the focus returns to where it was")

                val event = key(document.body!!, "K", meta = true)
                assertTrue(event.defaultPrevented, "the browser must not take the key")
                awaitUntil("Cmd+K opens it too") { dialogs() == 1 }
            }
        }

    @Test
    fun theShortcutAgain_closesThePalette(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = openPalette()
                key(input, "k", ctrl = true)
                awaitUntil("closed again") { dialogs() == 0 }
            }
        }

    @Test
    fun theShortcut_isIgnored_inATextEditor_whenTaken_andWithOtherModifiers(): Promise<Unit> =
        formTest {
            withMenu { _, _, element ->
                val editable = document.createElement("div") as HTMLElement
                editable.setAttribute("contenteditable", "true")
                editable.tabIndex = 0
                element().appendChild(editable)
                val area = document.createElement("textarea") as HTMLElement
                element().appendChild(area)
                key(editable, "k", ctrl = true)
                key(area, "k", ctrl = true)
                // a handler on the element that takes the key first (the mail editor does exactly this for "insert link")
                val taker = document.createElement("div") as HTMLElement
                taker.addEventListener("keydown", { it.preventDefault() })
                element().appendChild(taker)
                key(taker, "k", ctrl = true)
                key(document.body!!, "k", ctrl = true, shift = true)
                key(document.body!!, "k", ctrl = true, alt = true)
                key(document.body!!, "k")
                delay(150)
                assertEquals(0, dialogs(), "none of these opens the palette")

                val plain = document.createElement("input") as HTMLElement
                element().appendChild(plain)
                key(plain, "k", ctrl = true)
                awaitUntil("a plain one-line input does open it") { dialogs() == 1 }
            }
        }

    @Test
    fun inTheSidebarField_theShortcutKeepsTheFocusThere(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = fieldInput()
                input.focus()
                val event = key(input, "k", ctrl = true)
                assertTrue(event.defaultPrevented)
                delay(150)
                assertEquals(0, dialogs())
                assertEquals(FIELD_INPUT, document.activeElement?.id)
            }
        }

    @Test
    fun palette_choosesWithEnter_closes_andRemembers(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = openPalette()
                type(input, "dokumente")
                awaitUntil("a hit") { options(".lapis-command-palette").isNotEmpty() }
                key(input, "Enter")
                assertEquals(listOf(Routes.DOCUMENTS), navigated)
                assertEquals(listOf(Routes.DOCUMENTS), MenuRecents.load())
                awaitUntil("closed after the choice") { dialogs() == 0 }
            }
        }

    @Test
    fun palette_trapsTab_andClosesOnABackdropClick(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                val input = openPalette()
                val tab = key(input, "Tab")
                assertTrue(tab.defaultPrevented, "Tab stays inside the dialog")
                assertEquals(PALETTE_INPUT, document.activeElement?.id)
                val back = key(input, "Tab", shift = true)
                assertTrue(back.defaultPrevented)
                assertEquals(PALETTE_INPUT, document.activeElement?.id)

                // a click INSIDE the dialog does not close it, one on the backdrop does
                val dialog = document.querySelector(".lapis-command-palette__dialog") as HTMLElement
                dialog.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))
                delay(50)
                assertEquals(1, dialogs())
                val backdrop = document.querySelector(".lapis-command-palette") as HTMLElement
                backdrop.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))
                awaitUntil("closed by the backdrop") { dialogs() == 0 }
            }
        }

    @Test
    fun rebuildingTheSidebar_neverAddsAField_orAListener(): Promise<Unit> =
        formTest {
            withMenu { root, panel, _ ->
                repeat(3) { buildSidebar(panel, admin, activeRoute = null) {} }
                CommandPalette.install(root)
                CommandPalette.install(root)
                awaitUntil("one field") { document.querySelectorAll(".lapis-menu-search").length == 1 }
                // one key press must toggle exactly once: a second listener would open and close it again
                key(document.body!!, "k", ctrl = true)
                awaitUntil("open after ONE key press") { dialogs() == 1 }
                delay(100)
                assertEquals(1, dialogs())
                assertTrue(CommandPalette.isOpen)
            }
        }

    @Test
    fun aSignedOutSidebar_hasNothingToSearch_andThePaletteStaysClosed(): Promise<Unit> =
        formTest {
            withMenu { _, panel, _ ->
                clearSidebar(panel)
                assertTrue(MenuIndex.entries().isEmpty())
                key(document.body!!, "k", ctrl = true)
                delay(150)
                assertEquals(0, dialogs())
                assertFalse(CommandPalette.isOpen)
            }
        }

    @Test
    fun afterUninstall_theShortcutDoesNothing(): Promise<Unit> =
        formTest {
            withMenu { _, _, _ ->
                CommandPalette.uninstall()
                key(document.body!!, "k", ctrl = true)
                delay(150)
                assertEquals(0, dialogs())
            }
        }
}
