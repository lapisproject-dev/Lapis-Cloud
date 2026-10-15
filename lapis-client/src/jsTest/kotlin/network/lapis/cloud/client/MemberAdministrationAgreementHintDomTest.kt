package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.134 -- the hint on the member administration: ADMIN only, names the texts that are still the built-in placeholder, links to the
 * administration, and is invisible once every text is published (or when the overview cannot be read).
 */
class MemberAdministrationAgreementHintDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun kind(
        kind: AgreementTextKind,
        placeholder: Boolean,
    ) = AgreementTextKindOverviewDto(
        kind = kind,
        placeholderActive = placeholder,
        current =
            if (placeholder) {
                null
            } else {
                AgreementTextVersionSummaryDto(
                    kind,
                    "x.v1",
                    LocalDate(2026, 1, 1),
                    "a".repeat(64),
                    5,
                    "Ada",
                    LocalDateTime(2026, 1, 1, 0, 0),
                    1,
                )
            },
        builtinVersionLabel = "b.v1",
        hasDraft = false,
        olderAcknowledgmentCount = null,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun scenario(
        role: AccountRole,
        membership: Boolean,
        friend: Boolean,
        overviewFails: Boolean = false,
        block: suspend (HTMLElement, List<RecordedRequest>, String) -> Unit,
    ) {
        AppState.setSession(session(role))
        val list =
            routeOf {
                rpcService<IMemberService>().listMembersForAdministration(
                    network.lapis.cloud.shared.domain
                        .MemberAdminQuery(),
                )
            }
        val chapters = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
        val overview = routeOf { rpcService<IAgreementTextAdminService>().getOverview() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list ->
                        request.answerWith(
                            jsonOf(MemberAdminPageDto.serializer(), MemberAdminPageDto(emptyList(), 0, emptyMap(), 25, 0)),
                        )
                    request.rpcRoute == chapters -> request.answerWith("[]")
                    request.rpcRoute == overview ->
                        if (overviewFails) {
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                        } else {
                            request.answerWith(
                                jsonOf(
                                    AgreementTextOverviewDto.serializer(),
                                    AgreementTextOverviewDto(
                                        listOf(kind(AgreementTextKind.MEMBERSHIP, membership), kind(AgreementTextKind.FRIEND, friend)),
                                    ),
                                ),
                            )
                        }
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            mountedForm("agreement-hint-${role.name.lowercase()}-$membership-$friend-$overviewFails") { root, element ->
                renderMemberAdministrationScreen(root)
                awaitAppScopeIdle("the screen finished loading")
                block(element(), calls, overview)
            }
        }
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    @Test
    fun anAdmin_seesBothTexts_whenBothArePlaceholders_withALinkToTheAdministration(): Promise<Unit> =
        test {
            scenario(AccountRole.ADMIN, membership = true, friend = true) { screen, _, _ ->
                assertTrue(screen.shows("Platzhaltertext aktiv: Beitrittsvertrag, Freund-Nutzungsbedingungen"))
                val link = screen.allOf("a").first { it.textContent?.trim() == "Vertragstexte bearbeiten" }
                assertEquals("#${Routes.AGREEMENT_TEXTS}", link.getAttribute("href"))
            }
        }

    @Test
    fun anAdmin_seesOnlyTheTextThatIsStillAPlaceholder(): Promise<Unit> =
        test {
            scenario(AccountRole.ADMIN, membership = false, friend = true) { screen, _, _ ->
                assertTrue(screen.shows("Platzhaltertext aktiv: Freund-Nutzungsbedingungen"))
                assertFalse(screen.shows("Platzhaltertext aktiv: Beitrittsvertrag"))
            }
        }

    @Test
    fun noPlaceholder_noHint(): Promise<Unit> =
        test {
            scenario(AccountRole.ADMIN, membership = false, friend = false) { screen, calls, overview ->
                assertEquals(1, calls.toRoute(overview).size)
                assertFalse(screen.shows("Platzhaltertext aktiv"))
                assertTrue(screen.allOf("a").none { it.textContent?.trim() == "Vertragstexte bearbeiten" })
            }
        }

    @Test
    fun aBoardMember_neverSeesTheHint_andTheOverviewIsNeverRequested(): Promise<Unit> =
        test {
            scenario(AccountRole.BOARD, membership = true, friend = true) { screen, calls, overview ->
                assertEquals(0, calls.toRoute(overview).size, "the ADMIN-only call is not even made")
                assertFalse(screen.shows("Platzhaltertext aktiv"))
            }
        }

    @Test
    fun aFailedOverview_showsNoHint_andNoError(): Promise<Unit> =
        test {
            scenario(AccountRole.ADMIN, membership = true, friend = true, overviewFails = true) { screen, calls, overview ->
                assertEquals(1, calls.toRoute(overview).size)
                assertFalse(screen.shows("Platzhaltertext aktiv"))
            }
        }
}
