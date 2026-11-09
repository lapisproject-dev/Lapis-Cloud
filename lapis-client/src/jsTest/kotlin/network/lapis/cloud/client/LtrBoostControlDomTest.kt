package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.coroutines.CompletableDeferred
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.INoticeBoardService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.297 -- the boost control the social network and the bulletin board share: the free balance line (loaded ONCE for many controls), the
 * inline amount check, the confirmation text, the disabled button while the call runs, and a boost on a notice end to end.
 */
class LtrBoostControlDomTest {
    private fun source(
        balance: String?,
        counter: IntArray,
    ) = LtrBalanceSource(
        load = {
            counter[0]++
            balance?.toDouble()
        },
    )

    @Test
    fun theBalanceLineShows_andManyControlsOfOneScreenLoadTheBalanceOnce(): Promise<Unit> =
        formTest {
            mountedForm("boost-balance-once") { root, element ->
                val counter = intArrayOf(0)
                val shared = source("12.50", counter)
                repeat(3) {
                    ltrBoostControl(
                        row = root.div(),
                        balance = shared,
                        confirmTitle = "Titel",
                        successText = "Erfolg",
                        onBoost = { true },
                        onChanged = {},
                    )
                }
                awaitUntil("three balance lines") { element().allOf(".lapis-boost-balance").count { it.shows("Freies Guthaben") } == 3 }
                assertEquals(1, counter[0], "one load for three controls")
                assertTrue(element().shows("12,50\u00a0LTR") || element().shows("12.50\u00a0LTR"))
            }
        }

    @Test
    fun aFailedBalanceLoadIsNotCached_andTheLineStaysEmpty(): Promise<Unit> =
        formTest {
            mountedForm("boost-balance-failed") { root, element ->
                val counter = intArrayOf(0)
                val shared = source(null, counter)
                ltrBoostControl(root.div(), shared, "Titel", "Erfolg", { true }) {}
                awaitUntil("asked once") { counter[0] == 1 }
                assertFalse(element().shows("Freies Guthaben"))
                shared.current()
                assertEquals(2, counter[0], "a failure is asked again, not remembered")
            }
        }

    @Test
    fun anInvalidAmountIsRefusedInlineWithoutADialog_andNothingIsSent(): Promise<Unit> =
        formTest {
            mountedForm("boost-invalid") { root, element ->
                var sent = 0
                ltrBoostControl(root.div(), source("5.00", intArrayOf(0)), "Titel", "Erfolg", {
                    sent++
                    true
                }) {}
                val input = element().controlOf("Boost-Betrag (LTR)") as HTMLInputElement
                for (bad in listOf("", "abc", "0", "-3", "1,234")) {
                    element().typeInto("Boost-Betrag (LTR)", bad)
                    element().buttonNamed("Boosten").click()
                    awaitUntil("an error text for '$bad'") { element().allOf(".text-danger").any { it.textContent.orEmpty().isNotBlank() } }
                    assertEquals(
                        0,
                        element().let { document ->
                            kotlinx.browser.document
                                .querySelectorAll(".modal.show")
                                .length
                        },
                        "no dialog for '$bad'",
                    )
                }
                assertEquals(0, sent)
                assertNotNull(input)
            }
        }

    @Test
    fun aValidAmountAsksForConfirmationWithTheExactText_theButtonIsLockedWhileTheCallRuns(): Promise<Unit> =
        formTest {
            mountedForm("boost-confirm") { root, element ->
                val gate = CompletableDeferred<Unit>()
                var sent: String? = null
                var changed = 0
                ltrBoostControl(
                    row = root.div(),
                    balance = source("20.00", intArrayOf(0)),
                    confirmTitle = "Aushang boosten",
                    successText = "Aushang geboostet.",
                    onBoost = { amount ->
                        sent = amount.toString()
                        gate.await()
                        true
                    },
                    onChanged = { changed++ },
                )
                element().typeInto("Boost-Betrag (LTR)", "2,50")
                element().buttonNamed("Boosten").click()
                awaitUntil("the confirmation") {
                    lastOpenModal().shows("Sie zahlen") && lastOpenModal().shows("Dieser Betrag wird NICHT zurückerstattet.")
                }
                assertTrue(lastOpenModal().shows("2,50\u00a0LTR") || lastOpenModal().shows("2.50\u00a0LTR"))
                assertTrue(lastOpenModal().shows("Aushang boosten"))
                assertEquals(null, sent, "nothing is sent before the confirmation")
                lastOpenModal().buttonNamed("Boosten").click()
                awaitUntil("the call is running") { sent != null }
                assertTrue((element().buttonNamed("Boosten") as org.w3c.dom.HTMLButtonElement).disabled, "locked while the call runs")
                gate.complete(Unit)
                awaitUntil("done") { changed == 1 }
            }
        }

    @Test
    fun aBoostOnANoticeGoesToTheBoardService_withTheAmountAndTheId(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            val boostRoute = routeOf { rpcService<INoticeBoardService>().boostNotice("x", 1.0) }
            val balanceRoute = routeOf { rpcService<ILtrLedgerService>().getMyBalance() }
            val listRoute = routeOf { rpcService<INoticeBoardService>().listNotices() }
            val mineRoute = routeOf { rpcService<INoticeBoardService>().listMyNotices() }
            val notice = noticeDto("n1")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == boostRoute -> request.answerWith(jsonOf(NoticeDto.serializer(), notice))
                        request.rpcRoute == balanceRoute ->
                            request.answerWith(
                                jsonOf(
                                    network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
                                        .serializer(),
                                    balanceDto("9.00"),
                                ),
                            )
                        request.rpcRoute == listRoute ->
                            request.answerWith(
                                jsonOf(
                                    network.lapis.cloud.shared.domain.NoticePageDto
                                        .serializer(),
                                    noticePage(items = listOf(notice)),
                                ),
                            )
                        request.rpcRoute == mineRoute -> request.answerWith("[]")
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("boost-notice") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the card with its balance line") { element().shows("Titel-n1") && element().shows("Freies Guthaben") }
                    assertEquals(1, calls.toRoute(balanceRoute).size, "the balance is loaded once for the board")
                    element().typeInto("Boost-Betrag (LTR)", "1,00")
                    element().allOf(".lapis-notice-boost button").single { it.textContent.orEmpty().trim() == "Boosten" }.click()
                    awaitUntil("the confirmation") { lastOpenModal().shows("Aushang boosten") }
                    lastOpenModal().buttonNamed("Boosten").click()
                    awaitUntil("the call") { calls.toRoute(boostRoute).size == 1 }
                    val call = calls.singleCall(boostRoute)
                    assertEquals("n1", call.rpcParam(0) as String)
                    assertTrue(call.rpcParam(1).toString().contains("1"))
                    awaitUntil("the board reloads") { calls.toRoute(listRoute).size >= 2 }
                }
            }
        }
}
