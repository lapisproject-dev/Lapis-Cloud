package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICrmService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the duplicated contact rows: KVision `subscribe` emits once on registration, so the three filter controls plus the
 * explicit first load started four overlapping loads; each cleared the list and then appended a full page. One contact was shown four
 * times. Only the newest load may render now.
 */
class CrmContactsListRaceDomTest {
    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun contact(
        id: String,
        name: String,
    ) = CrmContactDto(
        id = id,
        displayName = name,
        email = null,
        phone = null,
        street = null,
        postalCode = null,
        city = null,
        country = null,
        contactType = CrmContactType.INTERESSENT,
        lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
        consentSource = null,
        consentGivenAt = null,
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

    private fun page(rows: List<CrmContactDto>) = jsonOf(CrmContactPageDto.serializer(), CrmContactPageDto(rows, rows.size))

    private fun HTMLElement.detailButtons(): List<HTMLElement> = allOf("button").filter { it.textContent?.trim() == "Details anzeigen" }

    @Test
    fun oneContact_isListedExactlyOnce_afterTheOverlappingInitialLoads(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            val rows = listOf(contact("c1", "Einziger Kontakt"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(rows))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("crm-list-race") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the contact is listed") { element().detailButtons().isNotEmpty() }
                    // every overlapping load answers within a few ticks; wait until the calls are in and the DOM has settled
                    awaitUntil("the list was requested") { calls.toRoute(list).isNotEmpty() }
                    delay(300)
                    assertEquals(1, element().detailButtons().size, "one contact is one row, however many loads overlapped")
                }
            }
        }
}
