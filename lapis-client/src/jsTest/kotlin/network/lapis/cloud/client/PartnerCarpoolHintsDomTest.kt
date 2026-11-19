package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolFederationReachDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import network.lapis.cloud.shared.domain.PartnerCarpoolHintPageDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.317 -- hint list and reach text in a real mounted root: hostile values stay text, nothing is actionable, the gates hold. */
class PartnerCarpoolHintsDomTest {
    private val day = LocalDate(2026, 12, 1)

    private fun hint(
        host: String = "o.beispiel.de",
        from: String = "38",
        to: String = "30",
    ) = PartnerCarpoolHintDto(day, from, to, host)

    private fun page(
        vararg items: PartnerCarpoolHintDto,
        more: Boolean = false,
    ) = PartnerCarpoolHintPageDto(items.toList(), more)

    private fun HTMLElement.buttons() = querySelectorAll("button").length

    private val hostile =
        listOf(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "x".repeat(5000),
            "a\u0000b",
            "a‮b",
        )

    @Test
    fun hostileValuesStayText_andNothingInARowIsActionable(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-hostile") { root, element ->
                val items = hostile.map { hint(host = it, from = "<b>", to = "‮") }
                PartnerCarpoolHintsSection(root) { page(*items.toTypedArray()) }.start()
                awaitUntil("the rows appear") { element().textContent.orEmpty().contains("Details und Kontakt") }
                val el = element()
                assertNull(el.querySelector("script, img, b, a"), "no element made from a foreign value")
                assertEquals(0, el.buttons(), "no button: nothing to act on")
                assertEquals(0, el.querySelectorAll("a").length)
                // the title attribute carries the host as plain text
                val titled = el.querySelectorAll("[title]")
                assertTrue(titled.length >= 1, "titles: ${el.innerHTML.take(1500)}")
                assertEquals("<script>alert(1)</script>", (titled.item(0) as HTMLElement).getAttribute("title"))
            }
        }

    @Test
    fun loadingThenEmpty_thenAnErrorWithARetry(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-states") { root, element ->
                var calls = 0
                PartnerCarpoolHintsSection(root) {
                    calls++
                    if (calls == 1) error("boom") else page()
                }.start()
                awaitUntil("the error and its retry appear") { element().textContent.orEmpty().contains("konnten nicht geladen werden") }
                val retry =
                    element()
                        .querySelectorAll("button")
                        .let { (0 until it.length).map { i -> it.item(i) as HTMLElement } }
                        .single { it.textContent.orEmpty().contains("Erneut versuchen") }
                retry.click()
                awaitUntil("the empty text appears after the retry") {
                    element().textContent.orEmpty().contains("Zurzeit liegen keine Hinweise von Partnerorganisationen vor.")
                }
                assertEquals(2, calls)
            }
        }

    @Test
    fun aFailedLoadMoreKeepsTheRows_andTheRetryAsksForTheFailedPage(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-retry-more") { root, element ->
                val asked = mutableListOf<Int>()
                var failNext = true
                PartnerCarpoolHintsSection(root) { p ->
                    asked += p
                    when (p) {
                        0 -> page(hint(host = "a.beispiel.de"), more = true)
                        1 -> page(hint(host = "b.beispiel.de"), more = true)
                        else ->
                            if (failNext) {
                                failNext = false
                                error("boom")
                            } else {
                                page(hint(host = "c.beispiel.de"))
                            }
                    }
                }.start()

                fun button(text: String) =
                    element()
                        .querySelectorAll("button")
                        .let { (0 until it.length).map { i -> it.item(i) as HTMLElement } }
                        .single { it.textContent.orEmpty().contains(text) }
                awaitUntil("page 0") { element().textContent.orEmpty().contains("a.beispiel.de") }
                button("Weitere laden").click()
                awaitUntil("page 1") { element().textContent.orEmpty().contains("b.beispiel.de") }
                button("Weitere laden").click()
                awaitUntil("the error appears") { element().textContent.orEmpty().contains("konnten nicht geladen werden") }
                val shown = element().textContent.orEmpty()
                assertTrue(shown.contains("a.beispiel.de") && shown.contains("b.beispiel.de"), "loaded rows stay visible")
                button("Erneut versuchen").click()
                awaitUntil("page 2 appears") { element().textContent.orEmpty().contains("c.beispiel.de") }
                assertEquals(listOf(0, 1, 2, 2), asked)
                val text = element().textContent.orEmpty()
                assertEquals(1, Regex("a\\.beispiel\\.de").findAll(text).count(), "no duplicates")
                assertEquals(1, Regex("b\\.beispiel\\.de").findAll(text).count(), "no duplicates")
            }
        }

    @Test
    fun loadMoreAppendsTheNextPage_andTheFilterNarrowsTheLoadedRows(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-more") { root, element ->
                val asked = mutableListOf<Int>()
                PartnerCarpoolHintsSection(root) { p ->
                    asked += p
                    if (p ==
                        0
                    ) {
                        page(hint(host = "a.beispiel.de", from = "38", to = "30"), more = true)
                    } else {
                        page(hint(host = "b.beispiel.de", from = "10", to = "20"))
                    }
                }.start()
                awaitUntil("page 0") { element().textContent.orEmpty().contains("a.beispiel.de") }
                val more =
                    element()
                        .querySelectorAll("button")
                        .let { (0 until it.length).map { i -> it.item(i) as HTMLElement } }
                        .single { it.textContent.orEmpty().contains("Weitere laden") }
                more.click()
                awaitUntil("page 1 is appended") { element().textContent.orEmpty().contains("b.beispiel.de") }
                assertTrue(element().textContent.orEmpty().contains("a.beispiel.de"), "page 0 stays")
                assertEquals(listOf(0, 1), asked)
                // filter: only the second row has 10/20
                val input = element().querySelector("input") as HTMLInputElement
                input.value = "1x0"
                input.dispatchEvent(Event("input"))
                awaitUntil("the filter narrows the list") {
                    val t = element().textContent.orEmpty()
                    t.contains("b.beispiel.de") && !t.contains("a.beispiel.de")
                }
                assertEquals(listOf(0, 1), asked, "a filter change never asks the server")
                assertEquals("numeric", input.getAttribute("inputmode"))
                assertEquals("2", input.getAttribute("maxlength"))
            }
        }

    @Test
    fun nothingIsMounted_forANonMember_aDisabledExchange_orAFailingAvailability(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-gates") { root, element ->
                mountPartnerCarpoolHints(root, isMember = false, readEnabled = { true }, loadPage = { page() })
                mountPartnerCarpoolHints(root, isMember = true, readEnabled = { false }, loadPage = { page() })
                mountPartnerCarpoolHints(root, isMember = true, readEnabled = { error("forbidden") }, loadPage = { page() })
                settleAppScope()
                assertFalse(element().textContent.orEmpty().contains("Angebote von Partnerorganisationen"))
                assertEquals(0, element().querySelectorAll("input").length)
                mountPartnerCarpoolHints(root, isMember = true, readEnabled = { true }, loadPage = { page(hint()) })
                awaitUntil("the section appears for a member with the exchange on") {
                    element().textContent.orEmpty().contains("Angebote von Partnerorganisationen")
                }
            }
        }

    private fun position(
        a: Node,
        b: Node,
    ) = (a.compareDocumentPosition(b).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt()) != 0

    @Test
    fun theReachTextComesBeforeTheCheckbox_namesStayText_aLongListIsFolded(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-reach") { root, element ->
                val names = listOf("<img src=x onerror=alert(1)>", "B", "C", "D", "E")
                CarpoolFederationOptIn(
                    form = root.lapisForm(),
                    initial = false,
                    currentType = { CarpoolPostingType.OFFER },
                    from = { "" },
                    to = { "" },
                    date = { null },
                ).load(
                    readEnabled = { true },
                    readReach = { CarpoolFederationReachDto(enabled = true, partners = names, maxHops = 2) },
                )
                awaitUntil(
                    "the reach sentences appear",
                ) { element().textContent.orEmpty().contains("Abrufen können ihn 5 Partnerorganisationen.") }
                val el = element()
                val sentence =
                    assertNotNull(
                        el.querySelectorAll("div").let { l ->
                            (0 until l.length)
                                .map {
                                    l.item(
                                        it,
                                    )!!
                                }.firstOrNull { it.textContent.orEmpty().startsWith("Ihr Hinweis verlässt") }
                        },
                    )
                val checkbox = assertNotNull(el.querySelector("input[type=checkbox]"))
                assertTrue(position(sentence, checkbox), "the reach text is above the checkbox in the DOM: ${sentence.nodeName}")
                val toggle =
                    el
                        .querySelectorAll("button")
                        .let { l -> (0 until l.length).map { l.item(it) as HTMLElement } }
                        .single { it.textContent.orEmpty().contains("Partnerorganisationen anzeigen") }
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                assertFalse(
                    el.textContent.orEmpty().contains("<img src=x onerror=alert(1)>"),
                    "folded: the names are not in the document yet",
                )
                toggle.click()
                awaitUntil("the names unfold") { element().textContent.orEmpty().contains("<img src=x onerror=alert(1)>") }
                assertEquals("true", toggle.getAttribute("aria-expanded"))
                assertNull(element().querySelector("img"), "a hostile name stays text")
                assertTrue(document.body != null)
            }
        }

    @Test
    fun aFailedReachLoadShowsTheMessage_andLeavesTheCheckboxEnabled_andARequestNeverCallsIt(): Promise<Unit> =
        formTest {
            mountedForm("partner-hints-reach-fail") { root, element ->
                CarpoolFederationOptIn(
                    form = root.lapisForm(),
                    initial = false,
                    currentType = { CarpoolPostingType.OFFER },
                    from = { "38100 A" },
                    to = { "30159 B" },
                    date = { null },
                ).load(readEnabled = { true }, readReach = { error("down") })
                awaitUntil("the failure text") { element().textContent.orEmpty().contains("Empfänger konnten nicht geladen werden.") }
                val box = element().querySelector("input[type=checkbox]") as HTMLInputElement
                assertFalse(box.disabled, "the consent can still be given or withdrawn")
            }
            mountedForm("partner-hints-reach-request") { root, _ ->
                var reachCalls = 0
                CarpoolFederationOptIn(
                    form = root.lapisForm(),
                    initial = false,
                    currentType = { CarpoolPostingType.REQUEST },
                    from = { "" },
                    to = { "" },
                    date = { null },
                ).load(readEnabled = { true }, readReach = {
                    reachCalls++
                    CarpoolFederationReachDto(true, emptyList(), 1)
                })
                settleAppScope()
                assertEquals(0, reachCalls, "a REQUEST never asks for the reach")
            }
        }
}
