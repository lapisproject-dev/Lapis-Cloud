package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionHintResolutionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuctionService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.322 (step c2) -- the landing page of a partner hint link: every case that is not "open and shared" is the SAME page, and the hint id appears
 * nowhere in the DOM (text, attributes, title).
 */
class AuctionHintScreenDomTest {
    private val id = "ab".repeat(32)

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    private suspend fun page(
        teaserId: String?,
        resolution: AuctionHintResolutionDto?,
        failing: Boolean = false,
        block: suspend (HTMLElement) -> Unit,
    ) {
        AppState.setSession(
            SessionInfoDto(
                memberId = "m1",
                displayName = "Mia Mitglied",
                role = AccountRole.MEMBER,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )
        val resolve = routeOf { rpcService<IAuctionService>().resolveAuctionHint("x") }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == resolve ->
                        if (failing) {
                            StubResponse(status = 500)
                        } else {
                            request.answerWith(jsonOf(AuctionHintResolutionDto.serializer(), requireNotNull(resolution)))
                        }
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("auction-hint-screen") { root, element ->
                renderAuctionHintScreen(root, teaserId)
                awaitAppScopeIdle("the landing page finished loading")
                block(element())
            }
        }
    }

    private fun everything(page: HTMLElement): String =
        page.outerHTML +
            page.querySelectorAll("*").let { all ->
                (0 until all.length).joinToString(" ") { (all.item(it) as HTMLElement).getAttribute("aria-label").orEmpty() }
            }

    @Test
    fun everyCaseThatIsNotOpenAndSharedIsTheSameNeutralPage_withoutTheId(): Promise<Unit> =
        test {
            val pages = mutableListOf<String>()
            for (
            (teaser, resolution, failing) in
            listOf(
                Triple(id, AuctionHintResolutionDto.Unavailable, false),
                Triple("kein-hinweis", AuctionHintResolutionDto.Unavailable, false),
                Triple(null, AuctionHintResolutionDto.Unavailable, false),
                Triple(id, AuctionHintResolutionDto.Unavailable, true),
            )
            ) {
                page(teaser, resolution, failing) { el ->
                    val text = el.textContent.orEmpty()
                    assertTrue(text.contains("Diese Auktion ist nicht (mehr) verfügbar."), text)
                    assertTrue(text.contains("Zu den Auktionen"))
                    assertFalse(everything(el).contains(id), "the hint id is nowhere in the page")
                    pages += el.innerHTML
                }
            }
            assertEquals(1, pages.toSet().size, "all four cases render the same page")
        }

    @Test
    fun anAvailableHintLoadsTheAuctionScreenAndNeverShowsTheId(): Promise<Unit> =
        test {
            page(id, AuctionHintResolutionDto.Available(auctionId = "11111111-2222-3333-4444-555555555555")) { el ->
                assertNotNull(el.querySelector("h1, h2, .lapis-page"), "the auction page is shown")
                assertFalse(el.textContent.orEmpty().contains("nicht (mehr) verfügbar"))
                assertFalse(everything(el).contains(id))
            }
        }
}
