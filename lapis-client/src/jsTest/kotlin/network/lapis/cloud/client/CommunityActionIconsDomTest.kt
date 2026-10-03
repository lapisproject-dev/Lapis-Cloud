package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICrmService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegionalChapterService
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.48 -- rule R57 for the community group: the buttons that got a standard icon keep their visible text (so the accessible name is
 * unchanged), carry the decorative icon with `aria-hidden`, and the pager keeps its plain text. Covers the buttons that are reachable
 * without opening a dialog.
 */
class CommunityActionIconsDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    /** The named button must read exactly [name], carry a decorative icon of the class [iconClass], and the icon must be hidden from assistive technology. */
    private fun HTMLElement.assertIconButton(
        name: String,
        iconClass: String,
    ) {
        val button = buttonNamed(name)
        val icon = assertNotNull(button.querySelector("[aria-hidden='true']"), "'$name' has no hidden icon")
        assertTrue(icon.classList.contains(iconClass), "'$name': expected $iconClass, got ${icon.className}")
        assertEquals("true", icon.getAttribute("aria-hidden"), "'$name': the icon is decoration")
    }

    private fun contact(consent: Boolean) =
        CrmContactDto(
            id = "c1",
            displayName = "Altkontakt",
            email = null,
            phone = null,
            street = null,
            postalCode = null,
            city = null,
            country = null,
            contactType = CrmContactType.INTERESSENT,
            lawfulBasis = CrmLawfulBasis.CONSENT,
            consentSource = "Infostand",
            consentGivenAt = if (consent) LocalDateTime(2026, 1, 1, 0, 0) else null,
            consentWithdrawnAt = null,
            externalDonorId = null,
            memberId = null,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
            createdBy = "member-1",
            lastInteractionAt = null,
            retentionReviewDueAt = LocalDateTime(2030, 1, 1, 0, 0),
            archivedAt = null,
            mayReceiveEmail = false,
        )

    @Test
    fun crmDetail_revokeAndEraseButtonsCarryTheirIcons(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            val get = routeOf { rpcService<ICrmService>().getContact("c1") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(CrmContactPageDto.serializer(), CrmContactPageDto(listOf(contact(true)), 1)))
                        request.rpcRoute == get -> request.answerWith(jsonOf(CrmContactDto.serializer(), contact(true)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("icons-crm") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the list") { element().textContent.orEmpty().contains("Altkontakt") }
                    element().buttonNamed("Details anzeigen").click()
                    awaitUntil("the detail") { element().textContent.orEmpty().contains("Löschen nach Art. 17 DSGVO") }
                    element().assertIconButton("Einwilligung widerrufen", "fa-ban")
                    element().assertIconButton("Löschen nach Art. 17 DSGVO", "fa-trash")
                    element().assertIconButton("Details anzeigen", "fa-eye")
                }
            }
        }

    @Test
    fun regionalChapters_revokeUploadAndSaveButtonsCarryTheirIcons(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IRegionalChapterService>().listChapters() }
            val chapter = RegionalChapterDto("c1", "Nord", 0, 0, 0)
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(
                            jsonOf(RegionalChapterOverviewDto.serializer(), RegionalChapterOverviewDto(listOf(chapter), 0)),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("icons-chapters") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the chapter card") { element().textContent.orEmpty().contains("Öffentliche Darstellung") }
                    element().assertIconButton("Wappen hochladen", "fa-upload")
                    element().assertIconButton("Beschreibung speichern", "fa-floppy-disk")
                    element().assertIconButton("Landesverband anlegen", "fa-plus")
                }
            }
        }

    @Test
    fun memberAdministration_approveButtonAndRosterPagerKeepTheirNames(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery()) }
            val pending = routeOf { rpcService<IRegistrationService>().listPendingApplications() }
            val applicant =
                MemberDto("m-app", "Anna Antrag", "anna@example.org", MemberStatus.APPLICATION, LocalDate(2026, 9, 1), AccountRole.MEMBER)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == pending -> request.answerWith(jsonOf(ListSerializer(MemberDto.serializer()), listOf(applicant)))
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(MemberAdminPageDto.serializer(), MemberAdminPageDto(emptyList(), 0, emptyMap(), 25, 0)),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("icons-members") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the pending application") { element().textContent.orEmpty().contains("Anna Antrag") }
                    element().assertIconButton("Annehmen", "fa-circle-check")
                    element().assertIconButton("Mitglied direkt anlegen", "fa-plus")
                }
            }
        }
}
