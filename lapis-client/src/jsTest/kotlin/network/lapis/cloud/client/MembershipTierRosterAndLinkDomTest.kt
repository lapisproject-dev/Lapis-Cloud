package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IContributionService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.18 -- the two places OUTSIDE the tier administration that changed: the contributions screen (the old tier list and
 * free-text date form are gone, a link to the new screen replaces them, for TREASURER/ADMIN only) and the member roster's tier
 * dialog (a closed tier is not offered, except as the member's own current one).
 */
class MembershipTierRosterAndLinkDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(memberId = "m-$role", displayName = "Test", role = role, expiresAt = LocalDateTime(2030, 1, 1, 12, 0))

    private fun tier(
        id: String,
        name: String,
        active: Boolean = true,
    ) = MembershipTierDto(id, name, "", 10.0.toDecimal(), BillingInterval.MONTHLY, active, 14)

    private val contributionsHashLink = "a[href='#${Routes.MEMBERSHIP_TIERS}']"

    private fun HTMLElement.tierLinks() = allOf(contributionsHashLink)

    @Test
    fun contributionsScreen_linksToTheTierAdministration_forTreasurerAndAdmin(): Promise<Unit> =
        formTest {
            listOf(AccountRole.TREASURER, AccountRole.ADMIN).forEach { role ->
                AppState.setSession(session(role))
                withFetchStub {
                    mountedForm("contrib-link-$role") { root, element ->
                        renderContributionsScreen(root)
                        awaitUntil("the link for $role") { element().tierLinks().isNotEmpty() }
                        assertEquals(
                            "Mitgliedschaftsstufen verwalten",
                            element()
                                .tierLinks()
                                .single()
                                .textContent
                                ?.trim(),
                        )
                    }
                }
            }
        }

    @Test
    fun contributionsScreen_hasNoTierLinkForBoardOrMember_andNoLongerTheOldInlineAdministration(): Promise<Unit> =
        formTest {
            listOf(AccountRole.BOARD, AccountRole.MEMBER, AccountRole.TREASURER).forEach { role ->
                AppState.setSession(session(role))
                withFetchStub {
                    mountedForm("contrib-nolink-$role") { root, element ->
                        renderContributionsScreen(root)
                        // give the screen's own coroutines time to finish rendering
                        awaitUntil("the page header") { element().allOf("h1").isNotEmpty() }
                        kotlinx.coroutines.delay(200)
                        if (role != AccountRole.TREASURER) assertEquals(0, element().tierLinks().size, "$role must not get the link")
                        val text = element().textContent.orEmpty()
                        assertFalse(text.contains("Beitragssätze und Beitragsgenerierung"), "the old inline block is gone")
                        assertFalse(text.contains("Periodenbeginn"), "the free-text date form is gone")
                        assertFalse(text.contains("Beiträge generieren"), "the old button is gone")
                    }
                }
            }
        }

    // ── roster dialog ──

    private fun row(tierId: String?) =
        MemberAdminRowDto(
            id = "member-5",
            displayName = "Amara Okafor",
            email = "amara@example.org",
            status = MemberStatus.ACTIVE,
            role = AccountRole.MEMBER,
            joinedAt = LocalDate(2026, 1, 1),
            anonymized = false,
            membershipTierId = tierId,
            membershipTierName = null,
        )

    private suspend fun tierOptionsFor(
        callerRole: AccountRole,
        currentTierId: String?,
        id: String,
    ): List<Pair<String, String>> {
        AppState.setSession(session(callerRole))
        val listRoute = routeOf { rpcService<IContributionService>().listMembershipTiers() }
        val tiers =
            listOf(tier("open", "Offen"), tier("closed-current", "Alt", active = false), tier("closed-other", "Weg", active = false))
        var options: List<Pair<String, String>> = emptyList()
        withFetchStub(
            respond = { request ->
                if (request.isRpc && request.rpcRoute == listRoute) {
                    request.answerWith(jsonOf(ListSerializer(MembershipTierDto.serializer()), tiers))
                } else if (request.isRpc) {
                    request.answerWith("null")
                } else {
                    StubResponse()
                }
            },
        ) {
            mountedForm(id) { _, _ ->
                openMemberEditorDialog(row(currentTierId), onChanged = {})
                val modal = lastOpenModal()
                val select =
                    modal.controlOf(
                        if (callerRole ==
                            AccountRole.ADMIN
                        ) {
                            "Mitgliedschaftsstufe"
                        } else {
                            "Neue Mitgliedschaftsstufe"
                        },
                    ) as HTMLSelectElement
                awaitUntil("the options") { select.options.length > (if (callerRole == AccountRole.ADMIN) 1 else 0) }
                options =
                    (0 until select.options.length).map { index ->
                        val option = select.options.item(index) as HTMLOptionElement
                        option.value to option.text
                    }
            }
        }
        return options
    }

    @Test
    fun rosterDialog_admin_doesNotOfferAClosedTier_exceptTheMembersOwnCurrentOne(): Promise<Unit> =
        formTest {
            val none = tierOptionsFor(AccountRole.ADMIN, currentTierId = null, id = "roster-admin-none")
            assertEquals(listOf("", "open"), none.map { it.first }, "the free entry plus the open tier only: $none")
            val current = tierOptionsFor(AccountRole.ADMIN, currentTierId = "closed-current", id = "roster-admin-current")
            assertEquals(listOf("", "open", "closed-current"), current.map { it.first })
            assertTrue(current.last().second.contains("geschlossen"), "marked as closed: ${current.last().second}")
        }

    @Test
    fun rosterDialog_treasurer_doesNotOfferAClosedTier_either(): Promise<Unit> =
        formTest {
            val options = tierOptionsFor(AccountRole.TREASURER, currentTierId = null, id = "roster-treasurer")
            assertEquals(listOf("open"), options.map { it.first }, "$options")
        }
}
