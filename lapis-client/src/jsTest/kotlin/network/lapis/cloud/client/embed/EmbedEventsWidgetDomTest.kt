package network.lapis.cloud.client.embed

import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FALLBACK = """<a href="/veranstaltungen">Fallback</a>"""

private fun hostHtml(attrs: String = ""): String = """<div data-lapis-widget="events" $attrs>$FALLBACK</div>"""

private fun items(root: dynamic): List<dynamic> {
    val list = root.querySelectorAll("li")
    return (0 until (list.length as Int)).map { list.item(it) }
}

private fun text(node: dynamic): String = (node.textContent as String)

private fun ok(body: dynamic): (String, dynamic) -> Promise<dynamic> = { _, _ -> jsonResponse(body) }

private fun events(count: Int): Array<dynamic> {
    val list: dynamic = js("[]")
    for (i in 0 until count) {
        list.push(ownEvent(slug = "e$i", title = "Termin $i", startsAt = "2099-01-" + (i + 1).toString().padStart(2, '0') + "T17:00:00Z"))
    }
    return list.unsafeCast<Array<dynamic>>()
}

private fun feedWithBrokenEvents(): dynamic {
    val d = feedOf(arrayOf())
    d.events = "nope"
    return d
}

/** V1.9.248 -- the real `lapis-widgets.js` in headless Chrome, `data-lapis-widget="events"`. */
class EmbedEventsWidgetDomTest {
    @Test
    fun rendersOwnEventsAsAnAccessibleListWithTheZoneOfTheOrganisation(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml(),
            handler =
                ok(
                    feedOf(
                        arrayOf(
                            ownEvent("a", "Bürgerabend", "2026-11-04T18:00:00Z", location = "Rathaus"),
                            ownEvent("b", "Webinar", "2026-11-05T18:00:00Z", online = true),
                        ),
                    ),
                ),
        ) { h, hosts ->
            val root = h.awaitRoot(hosts[0])
            val ul = root.querySelector("ul[aria-label='Nächste Veranstaltungen']")
            assertNotNull(ul)
            val lis = items(root)
            assertEquals(2, lis.size)
            val time = lis[0].querySelector("time[datetime]")
            assertEquals("2026-11-04T18:00:00Z", time.getAttribute("datetime"))
            assertTrue(text(time).contains("19:00"), "Europe/Berlin wall clock: ${text(time)}")
            assertTrue(text(time).contains("2026") && text(time).endsWith("Uhr"), text(time))
            val link = lis[0].querySelector("a")
            assertEquals("Bürgerabend", text(link))
            assertEquals(h.origin + "/veranstaltung/a", link.getAttribute("href"))
            assertTrue(text(lis[0]).contains("Rathaus"))
            assertTrue(text(lis[1]).contains("Online"))
            val more = root.querySelector("ul + a")
            assertTrue(text(more).startsWith("Alle Veranstaltungen"))
            assertEquals(h.origin + "/veranstaltungen?quelle=eigene", more.getAttribute("href"))
        }

    @Test
    fun dataLimitDefaultsToFiveAndIsClampedToTwenty(): Promise<Unit> =
        embedTest(
            hostsHtml =
                listOf(
                    "",
                    "data-limit=\"3\"",
                    "data-limit=\"99\"",
                    "data-limit=\"abc\"",
                    "data-limit=\"0\"",
                ).joinToString("") { hostHtml(it) },
            handler = ok(feedOf(events(25))),
        ) { h, hosts ->
            val counts = hosts.map { items(h.awaitRoot(it)).size }
            assertEquals(listOf(5, 3, 20, 5, 5), counts)
        }

    @Test
    fun emptyFeedShowsTheDefaultOrTheCustomTextAndTheLink(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml() + hostHtml("data-empty-text=\"Nichts los\" data-more-text=\"Mehr\""),
            handler = ok(feedOf(arrayOf())),
        ) { h, hosts ->
            val a = h.awaitRoot(hosts[0])
            assertTrue(text(a.querySelector("p")).startsWith("Zurzeit sind keine Veranstaltungen"))
            assertTrue(text(a.querySelector("a")).startsWith("Alle Veranstaltungen"))
            val b = h.awaitRoot(hosts[1])
            assertEquals("Nichts los", text(b.querySelector("p")))
            assertEquals("Mehr", text(b.querySelector("a")))
        }

    @Test
    fun theSourceAttributeDecidesTheQueryAndNoCredentialsAreSent(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml() + hostHtml("data-source=\"xyz\"") + hostHtml("data-source=\"all\"") + hostHtml("data-source=\"alle\""),
            handler = ok(feedOf(arrayOf())),
        ) { h, hosts ->
            hosts.forEach { h.awaitRoot(it) }
            assertEquals(4, h.calls.size)
            val urls = h.calls.map { it.url }.sorted()
            assertEquals(2, urls.count { it == h.origin + "/api/embed/v1/events" })
            assertEquals(2, urls.count { it == h.origin + "/api/embed/v1/events?quelle=alle" })
            assertTrue(h.calls.all { it.credentials == "omit" })
        }

    @Test
    fun ownEventComesFirstOnATieAndTheListIsCutAfterMerging(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml("data-source=\"all\" data-limit=\"3\""),
            handler =
                ok(
                    feedOf(
                        arrayOf(
                            ownEvent("o1", "Eigen später", "2099-01-03T10:00:00Z"),
                            ownEvent("o2", "Eigen gleich", "2099-01-02T10:00:00Z"),
                        ),
                        arrayOf(
                            partnerEvent("Partner gleich", "2099-01-02T10:00:00Z"),
                            partnerEvent("Partner früh", "2099-01-01T10:00:00Z"),
                            partnerEvent("Partner spät", "2099-02-01T10:00:00Z"),
                        ),
                    ),
                ),
        ) { h, hosts ->
            val titles = items(h.awaitRoot(hosts[0])).map { text(it.querySelector("a")).substringBefore(" ↗") }
            assertEquals(listOf("Partner früh", "Eigen gleich", "Partner gleich"), titles.map { it.trim() })
        }

    @Test
    fun aPartnerEventNamesItsServerSeriesAndMarksTheExternalLink(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml("data-source=\"all\""),
            handler =
                ok(
                    feedOf(
                        arrayOf(),
                        arrayOf(
                            partnerEvent(
                                "Fremdes Treffen",
                                "2099-03-01T10:00:00Z",
                                location = "Saal",
                                series = "jeden Montag",
                                timezone = "America/New_York",
                            ),
                        ),
                    ),
                ),
        ) { h, hosts ->
            val li = items(h.awaitRoot(hosts[0])).single()
            val a = li.querySelector("a")
            assertEquals("noopener noreferrer external", a.getAttribute("rel"))
            assertEquals("no-referrer", a.getAttribute("referrerpolicy"))
            assertEquals("https://partner.example/veranstaltung/x", a.getAttribute("href"))
            assertNotNull(a.querySelector("span[aria-hidden='true']"))
            assertTrue(text(a.querySelector(".vh")).contains("externe Seite"))
            val all = text(li)
            assertTrue(all.contains("Partnerserver: Partner e.V."), all)
            assertTrue(all.contains("Reihe: jeden Montag"), all)
            assertTrue(all.contains("Saal"), all)
            // another zone than the widget's: the zone name is part of the time, and the plain " Uhr" suffix is gone
            val time = text(li.querySelector("time"))
            assertFalse(time.endsWith("Uhr"), time)
            assertTrue(Regex("""\d{2}:\d{2}\s+\S+""").containsMatchIn(time), time)
        }

    @Test
    fun hostileTextStaysTextAndNeverBecomesMarkup(): Promise<Unit> {
        val evil = "<img src=x onerror=\"window.__pwned=1\">"
        return embedTest(
            hostsHtml = hostHtml("data-source=\"all\""),
            handler =
                ok(
                    feedOf(
                        arrayOf(ownEvent("a", evil, "2099-01-01T10:00:00Z", location = evil)),
                        arrayOf(partnerEvent(evil, "2099-01-02T10:00:00Z", originName = evil, series = evil, location = evil)),
                    ),
                ),
        ) { h, hosts ->
            val root = h.awaitRoot(hosts[0])
            assertEquals(2, items(root).size)
            assertNull(root.querySelector("img"))
            assertNull(root.querySelector("script"))
            assertTrue(text(root).contains("<img src=x"))
            delay(200)
            assertEquals(js("undefined"), window.asDynamic().__pwned)
        }
    }

    @Test
    fun unsafePartnerLinksAndForeignOwnLinksAreDropped(): Promise<Unit> {
        val bad: Array<dynamic> =
            arrayOf(
                partnerEvent("js", "2099-01-01T10:00:00Z", url = "javascript:alert(1)"),
                partnerEvent("http", "2099-01-01T10:00:00Z", url = "http://partner.example/x"),
                partnerEvent("userinfo", "2099-01-01T10:00:00Z", url = "https://u:p@partner.example/x"),
                partnerEvent("otherhost", "2099-01-01T10:00:00Z", url = "https://evil.example/x"),
                partnerEvent("port", "2099-01-01T10:00:00Z", url = "https://partner.example:8443/x"),
                partnerEvent("notastring", "2099-01-01T10:00:00Z", url = 42),
                partnerEvent("good", "2099-01-01T10:00:00Z"),
            )
        val foreignOwn = ownEvent("x", "Fremde Origin", "2099-01-01T10:00:00Z")
        foreignOwn.registrationUrl = "https://evil.example/veranstaltung/x"
        return embedTest(
            hostsHtml = hostHtml("data-source=\"all\" data-limit=\"20\""),
            handler = ok(feedOf(arrayOf(foreignOwn), bad)),
        ) { h, hosts ->
            val titles = items(h.awaitRoot(hosts[0])).map { text(it.querySelector("a")).trim().substringBefore(" ↗") }
            assertEquals(listOf("good"), titles)
        }
    }

    @Test
    fun anUnusableAnswerLeavesTheFallbackAndMountsNothing(): Promise<Unit> {
        val handlers: List<(String, dynamic) -> Promise<dynamic>> =
            listOf(
                { _, _ -> rejectingResponse() },
                { _, _ -> jsonResponse(status = 403, body = "{}") },
                { _, _ -> jsonResponse(status = 429, body = "{}") },
                { _, _ -> jsonResponse(status = 500, body = "{}") },
                { _, _ -> jsonResponse(status = 200, body = "{not json") },
                { _, _ -> jsonResponse(feedWithBrokenEvents()) },
            )
        // the handler is picked by call index: one host per kind of failure
        var next = 0
        return embedTest(
            hostsHtml = handlers.joinToString("") { hostHtml() },
            handler = { u, i -> handlers[next++ % handlers.size](u, i) },
        ) { h, hosts ->
            h.settle()
            assertEquals(handlers.size, h.calls.size)
            hosts.forEach {
                assertFalse(h.hasRoot(it), "no shadow root may cover the fallback")
                assertNotNull(it.querySelector("a"))
            }
        }
    }

    @Test
    fun aHangingRequestIsAbortedAfterFiveSecondsAndALateAnswerNeverMounts(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml() + hostHtml(),
            handler = { _, init -> if (init.signal != null) hangingResponse(init) else hangingResponse(init) },
        ) { h, hosts ->
            delay(5_500)
            assertEquals(2, h.calls.size)
            assertTrue(h.calls.all { it.signal.aborted as Boolean })
            hosts.forEach { assertFalse(h.hasRoot(it)) }
        }

    @Test
    fun aLateAnswerAfterTheTimeoutIsNotMounted(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml(),
            handler = { _, _ -> lateResponse(delayMs = 5_200, body = feedOf(events(2))) },
        ) { h, hosts ->
            delay(6_000)
            assertEquals(1, h.calls.size)
            assertFalse(h.hasRoot(hosts[0]))
        }

    @Test
    fun aMissingPartnerEventsKeyShowsOnlyOwnEvents(): Promise<Unit> =
        embedTest(
            hostsHtml = hostHtml("data-source=\"all\""),
            handler = ok(feedOf(events(2))),
        ) { h, hosts -> assertEquals(2, items(h.awaitRoot(hosts[0])).size) }

    @Test
    fun theOtherWidgetsStillHydrateInTheSameScan(): Promise<Unit> =
        embedTest(
            hostsHtml = """<div data-lapis-widget="join">x</div>""" + hostHtml(),
            handler = ok(feedOf(events(1))),
        ) { h, hosts ->
            val join = h.awaitRoot(hosts[0])
            assertEquals(h.origin + "/app#/register", join.querySelector("a").getAttribute("href"))
            assertEquals(1, items(h.awaitRoot(hosts[1])).size)
        }
}
