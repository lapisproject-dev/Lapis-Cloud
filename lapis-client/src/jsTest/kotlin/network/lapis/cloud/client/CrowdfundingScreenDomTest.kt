package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAttachedImageService
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.222 -- the project card shows cover image, title, status, weight and likes; the rest sits behind a closed "Details" disclosure.
 * */
class CrowdfundingScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-9",
            displayName = "Bea Board",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun project(cover: String?) =
        CrowdfundingProjectDto(
            id = "p1",
            title = "Lastenrad",
            description = "BESCHREIBUNG-IM-DETAIL",
            submitterMemberId = "member-1",
            submitterDisplayName = "Dana Keller",
            initialWeightLtr = 5.0.toDecimal(),
            currentWeightLtr = 4.0.toDecimal(),
            status = CrowdfundingProjectStatus.PENDING,
            effectiveStatus = CrowdfundingProjectStatus.PENDING,
            isAutoApproved = false,
            rejectionReason = null,
            reviewedById = null,
            reviewedByDisplayName = null,
            reviewedAt = null,
            submittedAt = LocalDateTime(2026, 9, 1, 10, 0),
            likeCount = 3,
            dislikeCount = 1,
            basketTotal = 2,
            coverImageUrl = cover,
        )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    @Test
    fun theCardKeepsTheRestBehindDetails_andLoadsThePicturesOnlyOnTheFirstOpen(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<ICrowdfundingService>().listProjects() }
            val distributions = routeOf { rpcService<ICrowdfundingService>().listDistributions() }
            val gallery =
                routeOf {
                    rpcService<IAttachedImageService>().list(
                        network.lapis.cloud.shared.domain.AttachedImageOwnerType.CROWDFUNDING_PROJECT,
                        "p1",
                    )
                }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(ListSerializer(CrowdfundingProjectDto.serializer()), listOf(project("/api/images/c1?v=abc12345"))),
                            )
                        request.rpcRoute == distributions ->
                            request.answerWith(jsonOf(ListSerializer(CrowdfundingDistributionDto.serializer()), emptyList()))
                        request.rpcRoute == gallery ->
                            request.answerWith(
                                jsonOf(
                                    network.lapis.cloud.shared.domain.AttachedImageGalleryDto
                                        .serializer(),
                                    gallery(listOf(storedImage("c1", 0, mine = false)), canEdit = false),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v1922-crowdfunding-card") { root, element ->
                    renderCrowdfundingScreen(root)
                    awaitUntil("the card") { element().shows("Lastenrad") }
                    val screen = element()
                    val cover = screen.allOf("img.lapis-gallery-card-cover").single() as HTMLImageElement
                    assertTrue(cover.src.contains("/api/images/c1"))
                    assertEquals("Titelbild von Lastenrad", cover.alt)
                    assertTrue(screen.shows("Like: 3"), "likes stay on the card")
                    assertTrue(screen.shows("Ursprüngliches Gewicht"), "the weight stays on the card")
                    assertTrue(screen.shows("Pending") || screen.shows("Ausstehend"), "the status stays on the card")
                    val toggle = screen.buttonNamed("Details")
                    assertEquals("false", toggle.getAttribute("aria-expanded"))
                    assertFalse(screen.shows("BESCHREIBUNG-IM-DETAIL"), "the description is behind Details")
                    assertFalse(screen.shows("Vorstandsentscheidung"), "the board panel is behind Details")
                    assertTrue(calls.toRoute(gallery).isEmpty(), "no gallery request before the disclosure was opened")
                    toggle.click()
                    awaitUntil("open") { toggle.getAttribute("aria-expanded") == "true" }
                    assertTrue(screen.shows("BESCHREIBUNG-IM-DETAIL"))
                    assertTrue(screen.shows("Vorstandsentscheidung"))
                    awaitUntil("the pictures are shown") { screen.allOf(".lapis-gallery-large").size == 1 }
                    assertEquals(1, calls.toRoute(gallery).size)
                    toggle.click()
                    toggle.click()
                    assertEquals(1, calls.toRoute(gallery).size, "opened again: not loaded again")
                    assertEquals("Treuhänder-Werkzeuge".let { screen.shows(it) }, true, "a BOARD member is also a treasury role")
                    assertFalse(screen.shows("Verteilung berechnen"), "the treasury tools are closed by default")
                }
            }
        }
}
