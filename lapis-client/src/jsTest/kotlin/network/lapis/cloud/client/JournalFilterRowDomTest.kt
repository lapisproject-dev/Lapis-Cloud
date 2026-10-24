package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.rpc.IAccountingService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.206: the journal filter row is ONE inline row -- search field, labelled status group, both dates and the refresh button are its
 * direct children, there is no nested toolbar, and the controls share one centre line (the old row was a toolbar inside a toolbar and the
 * status switch sat lower, without a label).
 */
class JournalFilterRowDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
        const val TOLERANCE = 2.0
    }

    private fun HTMLElement.mid(): Double = getBoundingClientRect().let { (it.top + it.bottom) / 2 }

    @Test
    fun filterControlsAreDirectChildrenOfOneRow_withoutANestedToolbar_onOneCentreLine(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val listJournal = routeOf { rpcService<IAccountingService>().listJournal(null, null, null, null) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listJournal ->
                            request.answerWith(
                                jsonOf(ListSerializer(JournalEntryDto.serializer()), emptyList()),
                            )
                        else -> request.answerWith(jsonOf(ListSerializer(LedgerAccountDto.serializer()), emptyList()))
                    }
                },
            ) {
                mountedForm("journal-filter-row") { root, element ->
                    renderLedgerJournalTab(root, canManage = false, hintHost = io.kvision.panel.SimplePanel())
                    awaitUntil("the filter row") { element().querySelector(".lapis-inline-row") != null }
                    val row = assertNotNull(element().querySelector(".lapis-inline-row") as? HTMLElement)
                    assertEquals(0, row.querySelectorAll(".lapis-toolbar").length, "no nested toolbar")
                    val search = row.allOf("input").first { it.getAttribute("type") != "date" }
                    val dates = row.allOf("input[type=date]")
                    assertEquals(2, dates.size, "Von and Bis")
                    val status = assertNotNull(row.querySelector("[role=group]") as? HTMLElement)
                    assertTrue(row.textContent.orEmpty().contains("Status"), "the group has a visible label")
                    val refresh = row.buttonNamed("Aktualisieren")
                    // The test page may be narrow, so the row can wrap; controls that SHARE a visual line must share its centre line.
                    val controls = listOf(search, status, refresh) + dates
                    for (a in controls) {
                        for (b in controls) {
                            if (a === b) continue
                            val ra = a.getBoundingClientRect()
                            val rb = b.getBoundingClientRect()
                            val overlap = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
                            if (overlap > 0.5 * minOf(ra.height, rb.height)) {
                                assertTrue(abs(a.mid() - b.mid()) <= TOLERANCE, "same line, one centre: ${a.mid()} vs ${b.mid()}")
                            }
                        }
                    }
                }
            }
        }
}
