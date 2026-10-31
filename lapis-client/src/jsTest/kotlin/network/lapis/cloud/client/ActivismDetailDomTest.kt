package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionSummaryDto
import network.lapis.cloud.shared.domain.ActivismAreaSumDto
import network.lapis.cloud.shared.domain.ActivismContributionsViewDto
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTextBlockDto
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IActivismService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.246 -- the action pages: other people's texts are text and never markup, one h1, the notice and "Melden" in their place, no member id in the DOM. */
class ActivismDetailDomTest {
    private val evil = "<img src=x onerror=alert(1)> Fremd"

    private fun detail(
        role: ActivismViewerRole = ActivismViewerRole.MEMBER,
        status: ActivismStatus = ActivismStatus.RUNNING,
    ) = ActivismActionDetailDto(
        id = "a1",
        title = evil,
        description = "<b>fett</b>",
        goalText = null,
        location = "Marktplatz",
        startsAt = LocalDateTime(2026, 11, 20, 10, 0),
        endsAt = null,
        kind = ActivismKind.INFO_STAND,
        formAppointment = true,
        formProgress = true,
        formSignal = true,
        goalQuantity = 100,
        unit = "Flyer",
        confirmContributions = false,
        status = status,
        viewerRole = role,
        progressReported = 25,
        progressConfirmed = null,
        signal = ActivismSignalStateDto(countAtLeast5 = null, mine = false),
        noticeText = null,
        ownNotice = "Eigener Hinweis",
        tasks = emptyList(),
        textBlocks = listOf(ActivismTextBlockDto("t1", 1, "Titel", "<script>x</script>")),
        material = emptyList(),
        eventShifts = null,
        result = null,
        responsibles = null,
        withdrawnReason = null,
        reviewRejectReason = null,
        costCenterId = null,
        costCenterName = null,
        eventId = null,
        revision = 3,
    )

    private suspend fun scenario(
        detail: ActivismActionDetailDto,
        role: AccountRole = AccountRole.MEMBER,
        block: suspend (HTMLElement) -> Unit,
    ) {
        AppState.setSession(
            SessionInfoDto(
                memberId = "member-secret-id",
                displayName = "Mia Mitglied",
                role = role,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )
        val get = routeOf { rpcService<IActivismService>().getAction("a1") }
        val contributions = routeOf { rpcService<IActivismService>().listContributions("a1") }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == get -> request.answerWith(jsonOf(ActivismActionDetailDto.serializer(), detail))
                    request.rpcRoute == contributions ->
                        request.answerWith(
                            jsonOf(
                                ActivismContributionsViewDto.serializer(),
                                ActivismContributionsViewDto(
                                    own = emptyList(),
                                    areaSums = listOf(ActivismAreaSumDto("Nord", 25)),
                                    all = null,
                                ),
                            ),
                        )
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("activism-detail-screen") { root, element ->
                renderActivismDetailScreen(root, "a1")
                awaitAppScopeIdle("the page finished loading")
                block(element())
            }
        }
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    @Test
    fun textsOfOtherPeopleAreTextNeverMarkup(): Promise<Unit> =
        test {
            scenario(detail()) { page ->
                assertNull(page.querySelector("img"), "no element may be created from a title")
                assertNull(page.querySelector("script"))
                assertNull(page.querySelector("b"))
                val text = page.textContent.orEmpty()
                assertTrue(text.contains("<img src=x onerror=alert(1)> Fremd"))
                assertTrue(text.contains("<b>fett</b>"))
                assertTrue(text.contains("<script>x</script>"))
            }
        }

    @Test
    fun theNoticeShowsTheStandardTextTheOwnNoticeAndTheDisclaimer(): Promise<Unit> =
        test {
            scenario(detail()) { page ->
                val text = page.textContent.orEmpty()
                assertTrue(text.contains(ActivismTexts.defaultNotice(ActivismKind.INFO_STAND)))
                assertTrue(text.contains("Eigener Hinweis"))
                assertTrue(text.contains("Keine Rechtsberatung."))
            }
        }

    @Test
    fun oneH1_aNativeProgressBar_andMeldenIsTheLastButton(): Promise<Unit> =
        test {
            scenario(detail()) { page ->
                assertEquals(1, page.querySelectorAll("h1").length)
                val bar = assertNotNull(page.querySelector("progress") as? HTMLElement)
                assertEquals("25", bar.getAttribute("value"))
                assertNull(bar.getAttribute("style"), "no inline style on the progress bar")
                val buttons = page.querySelectorAll("button")
                val last = buttons.item(buttons.length - 1) as HTMLElement
                assertTrue(last.textContent.orEmpty().contains("Melden"))
            }
        }

    @Test
    fun noMemberIdAppearsInTheDom(): Promise<Unit> =
        test {
            scenario(detail()) { page ->
                assertTrue(!page.innerHTML.contains("member-secret-id"))
                assertTrue(!page.innerHTML.contains("Mia Mitglied"))
            }
        }

    @Test
    fun theSignalSaysLessThanFiveInsteadOfANumber(): Promise<Unit> =
        test {
            scenario(detail()) { page ->
                assertTrue(page.textContent.orEmpty().contains("Weniger als 5"))
            }
        }

    @Test
    fun aPlainMemberHasNoStatusButtons_aResponsiblePersonHas(): Promise<Unit> =
        test {
            scenario(detail(role = ActivismViewerRole.MEMBER)) { page ->
                assertTrue(!page.textContent.orEmpty().contains("Setzen auf:"))
                assertTrue(!page.textContent.orEmpty().contains("Bearbeiten"))
            }
            scenario(detail(role = ActivismViewerRole.RESPONSIBLE)) { page ->
                assertTrue(page.textContent.orEmpty().contains("Setzen auf:"))
                assertTrue(page.textContent.orEmpty().contains("Bearbeiten"))
            }
        }

    @Test
    fun theListRowsAreTextToo(): Promise<Unit> =
        test {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m",
                    displayName = "Mia",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            val list =
                routeOf {
                    rpcService<IActivismService>().listActions(
                        network.lapis.cloud.shared.domain
                            .ActivismListFilter(),
                    )
                }
            val row =
                ActivismActionSummaryDto(
                    id = "a1",
                    title = evil,
                    kind = ActivismKind.POSTERING,
                    formAppointment = false,
                    formProgress = true,
                    formSignal = false,
                    startsAt = null,
                    endsAt = null,
                    status = ActivismStatus.RUNNING,
                    unit = "Plakate",
                    goalQuantity = 500,
                    progressReported = 40,
                    progressConfirmed = null,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(ListSerializer(ActivismActionSummaryDto.serializer()), listOf(row)),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("activism-screen") { root, element ->
                    renderActivismScreen(root)
                    awaitAppScopeIdle("the page finished loading")
                    val page = element()
                    assertNull(page.querySelector("img"))
                    assertTrue(page.textContent.orEmpty().contains("<img src=x onerror=alert(1)> Fremd"))
                    assertEquals(1, page.querySelectorAll("h1").length)
                }
            }
        }
}
