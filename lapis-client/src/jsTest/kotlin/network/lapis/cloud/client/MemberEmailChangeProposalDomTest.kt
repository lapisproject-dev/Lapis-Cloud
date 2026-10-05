package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeProposalRpc(
    var capability: EmailChangeCapabilityDto = EmailChangeCapabilityDto(MailDeliveryState.HANDED_TO_SMTP, ownChangeAvailable = true),
    var pending: EmailChangePendingDto? = null,
) : MemberEmailProposalRpc {
    var loads = 0
    val proposeCalls = mutableListOf<List<String>>()
    val overrideCalls = mutableListOf<List<String>>()
    val withdrawCalls = mutableListOf<String>()

    override suspend fun capability(): EmailChangeCapabilityDto {
        loads++
        return capability
    }

    override suspend fun pending(memberId: String) = pending

    override suspend fun propose(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto {
        proposeCalls += listOf(memberId, newEmail, newEmailRepeat)
        return EmailChangePendingDto("c1", "n***@example.org", EmailChangeKind.PROPOSAL, LocalDateTime(2026, 10, 12, 10, 0), null, false)
    }

    override suspend fun override(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto {
        overrideCalls += listOf(memberId, newEmail, newEmailRepeat, reason)
        return EmailChangePendingDto(
            "c2",
            "n***@example.org",
            EmailChangeKind.ADMIN_OVERRIDE,
            LocalDateTime(2026, 10, 12, 10, 0),
            null,
            false,
        )
    }

    override suspend fun withdraw(changeId: String) {
        withdrawCalls += changeId
        pending = null
    }
}

/** Welle V1.9.56 -- the address section of the roster editor: proposal form, status strip, no-mail state, emergency switch. */
class MemberEmailChangeProposalDomTest {
    private fun row() =
        MemberAdminRowDto(
            id = "member-5",
            displayName = "Amara Okafor",
            email = "amara@example.org",
            status = MemberStatus.ACTIVE,
            role = AccountRole.MEMBER,
            joinedAt = LocalDate(2026, 1, 1),
            anonymized = false,
        )

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Caller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private fun HTMLElement.buttonIsDisabled(text: String) = (buttonNamed(text) as HTMLButtonElement).disabled

    private inline fun withSection(
        rpc: FakeProposalRpc,
        id: String,
        role: AccountRole = AccountRole.BOARD,
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            mountedForm(id) { root, element ->
                renderEmailChangeProposalSection(root, row(), role, LegendGroup(), rpc, onChanged = {})
                awaitUntil("section rendered") { element().hasText("Änderung vorschlagen") || element().hasText("Änderung ausstehend") }
                block(element)
            }
        }

    @Test
    fun theFormIsHidden_untilTheProposeButtonIsClicked_andNoEmergencyForBoard(): Promise<Unit> =
        withSection(FakeProposalRpc(), "ec-prop-basic") { el ->
            assertEquals(0, el().allOf("input[type=email]").count { it.offsetParent != null }, "the form starts collapsed")
            el().buttonNamed("Änderung vorschlagen").click()
            awaitUntil("form visible") { el().allOf("input[type=email]").any { it.offsetParent != null } }
            assertFalse(el().hasText("Notfall ohne Annahme"), "the emergency switch is for ADMIN only")
        }

    @Test
    fun aBoardProposal_sendsTrimmedAddresses_toPropose(): Promise<Unit> {
        val rpc = FakeProposalRpc()
        return withSection(rpc, "ec-prop-send") { el ->
            el().buttonNamed("Änderung vorschlagen").click()
            el().typeInto("Neue E-Mail-Adresse", "  neu@example.org ")
            el().typeInto("Neue E-Mail-Adresse wiederholen", " neu@example.org  ")
            el().buttonNamed("Vorschlag senden").click()
            awaitUntil("propose call") { rpc.proposeCalls.isNotEmpty() }
            assertEquals(listOf("member-5", "neu@example.org", "neu@example.org"), rpc.proposeCalls.single())
            assertEquals(0, rpc.overrideCalls.size)
            awaitUntil("reloaded") { rpc.loads == 2 }
        }
    }

    @Test
    fun aDifferentRepeat_isReportedAtTheField_andNothingIsSent(): Promise<Unit> {
        val rpc = FakeProposalRpc()
        return withSection(rpc, "ec-prop-mismatch") { el ->
            el().buttonNamed("Änderung vorschlagen").click()
            el().typeInto("Neue E-Mail-Adresse", "neu@example.org")
            el().typeInto("Neue E-Mail-Adresse wiederholen", "anders@example.org")
            el().buttonNamed("Vorschlag senden").click()
            delay(150)
            assertEquals(0, rpc.proposeCalls.size)
            assertTrue(el().hasText("stimmen nicht überein"))
        }
    }

    @Test
    fun anAdmin_getsTheEmergencySwitch_andItNeedsAReason(): Promise<Unit> {
        val rpc = FakeProposalRpc()
        return withSection(rpc, "ec-prop-admin", AccountRole.ADMIN) { el ->
            el().buttonNamed("Änderung vorschlagen").click()
            assertTrue(el().hasText("Notfall ohne Annahme durch das Mitglied"))
            el().typeInto("Neue E-Mail-Adresse", "neu@example.org")
            el().typeInto("Neue E-Mail-Adresse wiederholen", "neu@example.org")
            (el().controlOf("Notfall ohne Annahme durch das Mitglied") as org.w3c.dom.HTMLInputElement).click()
            delay(100)
            // too short a reason: nothing is sent
            el().typeInto("Begründung", "kurz")
            el().buttonNamed("Vorschlag senden").click()
            delay(150)
            assertEquals(0, rpc.overrideCalls.size)
            el().typeInto("Begründung", "  Mitglied hat den Zugang verloren, telefonisch geprüft  ")
            el().buttonNamed("Vorschlag senden").click()
            awaitUntil("override call") { rpc.overrideCalls.isNotEmpty() }
            assertEquals(
                listOf("member-5", "neu@example.org", "neu@example.org", "Mitglied hat den Zugang verloren, telefonisch geprüft"),
                rpc.overrideCalls.single(),
            )
            assertEquals(0, rpc.proposeCalls.size)
        }
    }

    @Test
    fun withoutMail_theButtonIsDisabled_andTheReasonIsShown(): Promise<Unit> =
        withSection(
            FakeProposalRpc(capability = EmailChangeCapabilityDto(MailDeliveryState.NOT_CONFIGURED, ownChangeAvailable = true)),
            "ec-prop-nomail",
        ) { el ->
            assertTrue(el().buttonIsDisabled("Änderung vorschlagen"))
            assertTrue(el().hasText("kein Mailversand eingerichtet"))
        }

    @Test
    fun anOpenChange_showsAMaskedStrip_andCanBeWithdrawn(): Promise<Unit> {
        val pending =
            EmailChangePendingDto(
                "c9",
                "n***@example.org",
                EmailChangeKind.PROPOSAL,
                LocalDateTime(2026, 10, 12, 10, 0),
                null,
                newEmailConfirmed = false,
                withdrawable = true,
            )
        val rpc = FakeProposalRpc(pending = pending)
        return withSection(rpc, "ec-prop-pending") { el ->
            assertTrue(el().hasText("Änderung ausstehend"))
            assertTrue(el().hasText("n***@example.org"))
            assertTrue(el().hasText("Annahme durch das Mitglied"))
            assertFalse(
                el().allOf("button").any { it.textContent?.trim() == "Änderung vorschlagen" },
                "no second proposal while one is open",
            )
            el().buttonNamed("Zurückziehen").click()
            awaitUntil("withdraw call") { rpc.withdrawCalls.isNotEmpty() }
            assertEquals(listOf("c9"), rpc.withdrawCalls)
            awaitUntil("reloaded") { rpc.loads == 2 }
        }
    }

    @Test
    fun aWarningPeriodChange_showsTheEffectiveTime_andTheUnconfirmedHint_andHidesWithdrawForOthers(): Promise<Unit> {
        val pending =
            EmailChangePendingDto(
                "c10",
                "n***@example.org",
                EmailChangeKind.PROPOSAL_NO_ACCOUNT,
                LocalDateTime(2026, 10, 12, 10, 0),
                LocalDateTime(2026, 10, 8, 10, 0),
                newEmailConfirmed = false,
                withdrawable = false,
            )
        return withSection(FakeProposalRpc(pending = pending), "ec-prop-warning") { el ->
            assertTrue(el().hasText("frühestens"))
            assertTrue(el().hasText("noch nicht bestätigt"))
            assertFalse(el().allOf("button").any { it.textContent?.trim() == "Zurückziehen" })
        }
    }

    @Test
    fun aForgedI18nMarkerInTheMaskedAddress_isNotRenderedAsATranslation(): Promise<Unit> {
        val pending =
            EmailChangePendingDto(
                "c11",
                "###KvI18nS###\u0001forged",
                EmailChangeKind.PROPOSAL,
                LocalDateTime(2026, 10, 12, 10, 0),
                null,
                false,
            )
        return withSection(FakeProposalRpc(pending = pending), "ec-prop-forged") { el ->
            assertFalse(el().hasText("###KvI18nS###"))
        }
    }
}
