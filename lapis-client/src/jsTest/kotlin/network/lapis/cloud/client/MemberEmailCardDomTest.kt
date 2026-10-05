package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OwnEmailChangeResultDto
import network.lapis.cloud.shared.domain.OwnPendingEmailChangeDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.EmailChangeMailUnavailableException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun emailMember(email: String = "dana@example.org") =
    MemberDto(
        id = "member-1",
        displayName = "Dana Keller",
        email = email,
        status = MemberStatus.ACTIVE,
        joinedAt = LocalDate(2026, 1, 1),
        role = AccountRole.MEMBER,
    )

private class FakeEmailRpc(
    var capability: EmailChangeCapabilityDto = EmailChangeCapabilityDto(MailDeliveryState.HANDED_TO_SMTP, ownChangeAvailable = true),
    var pending: OwnPendingEmailChangeDto? = null,
) : MemberEmailRpc {
    var loads = 0
    val changeCalls = mutableListOf<List<String>>()
    val acceptCalls = mutableListOf<List<String>>()
    val declineCalls = mutableListOf<String>()
    var failWith: Throwable? = null
    var currentEmail = "dana@example.org"

    override suspend fun getCurrentMember(): MemberDto {
        loads++
        return emailMember(currentEmail)
    }

    override suspend fun capability() = capability

    override suspend fun ownPending() = pending

    override suspend fun changeOwn(
        currentPassword: String,
        newEmail: String,
        newEmailRepeat: String,
    ): OwnEmailChangeResultDto {
        changeCalls += listOf(currentPassword, newEmail, newEmailRepeat)
        failWith?.let { throw it }
        currentEmail = newEmail
        return OwnEmailChangeResultDto(MailDeliveryState.HANDED_TO_SMTP)
    }

    override suspend fun accept(
        changeId: String,
        currentPassword: String,
    ): MemberDto {
        acceptCalls += listOf(changeId, currentPassword)
        failWith?.let { throw it }
        pending = null
        return emailMember(currentEmail)
    }

    override suspend fun decline(changeId: String) {
        declineCalls += changeId
        failWith?.let { throw it }
        pending = null
    }
}

/** Welle V1.9.56 -- [MemberEmailCard] on "Meine Daten", in a real mounted KVision root, against a fake RPC. */
class MemberEmailCardDomTest {
    private val session =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private inline fun withCard(
        rpc: FakeEmailRpc,
        id: String,
        successes: MutableList<String> = mutableListOf(),
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            mountedForm(id) { root, element ->
                renderMemberEmailSection(root, rpc, { successes += it })
                awaitUntil("card rendered") { element().hasText("E-Mail-Adresse") && element().hasText("Aktuelle Adresse") }
                block(element)
            }
        }

    @Test
    fun theCurrentAddress_isShown_andTheChangeFormIsOffered(): Promise<Unit> =
        withCard(FakeEmailRpc(), "ec-card-basic") { el ->
            assertTrue(el().hasText("dana@example.org"))
            assertTrue(el().hasText("E-Mail-Adresse ändern"))
            assertTrue(el().allOf("button").any { it.textContent?.trim() == "Adresse ändern" })
        }

    @Test
    fun changingTheAddress_sendsPasswordAndBothAddresses_trimmed_andReloads(): Promise<Unit> {
        val rpc = FakeEmailRpc()
        val successes = mutableListOf<String>()
        return withCard(rpc, "ec-card-change", successes) { el ->
            el().typeInto("Aktuelles Passwort", "  geheim-passwort-1  ")
            el().typeInto("Neue E-Mail-Adresse", "  neu@example.org ")
            el().typeInto("Neue E-Mail-Adresse wiederholen", " neu@example.org  ")
            el().buttonNamed("Adresse ändern").click()
            awaitUntil("the change call") { rpc.changeCalls.isNotEmpty() }
            awaitUntil("reload") { rpc.loads == 2 }
            // the password is never trimmed, the addresses are
            assertEquals(listOf("  geheim-passwort-1  ", "neu@example.org", "neu@example.org"), rpc.changeCalls.single())
            assertTrue(el().hasText("neu@example.org"))
            assertEquals(1, successes.size)
            // neither the password nor an address went into the toast
            assertFalse(successes.single().contains("neu@example.org"))
            assertFalse(successes.single().contains("geheim"))
        }
    }

    @Test
    fun differentRepeat_isReportedAtTheField_andNothingIsSent(): Promise<Unit> {
        val rpc = FakeEmailRpc()
        return withCard(rpc, "ec-card-mismatch") { el ->
            el().typeInto("Aktuelles Passwort", "geheim-passwort-1")
            el().typeInto("Neue E-Mail-Adresse", "neu@example.org")
            el().typeInto("Neue E-Mail-Adresse wiederholen", "anders@example.org")
            el().buttonNamed("Adresse ändern").click()
            kotlinx.coroutines.delay(150)
            assertEquals(0, rpc.changeCalls.size)
            assertTrue(el().hasText("stimmen nicht überein"))
        }
    }

    @Test
    fun aProposal_isAcceptedWithThePassword_orDeclined(): Promise<Unit> {
        val proposal =
            OwnPendingEmailChangeDto(
                changeId = "change-9",
                newEmail = "vorschlag@example.org",
                kind = EmailChangeKind.PROPOSAL,
                expiresAt = LocalDateTime(2026, 10, 12, 10, 0),
                effectiveAt = null,
                requiresPassword = true,
            )
        val rpc = FakeEmailRpc(pending = proposal)
        return withCard(rpc, "ec-card-proposal") { el ->
            assertTrue(el().hasText("vorschlag@example.org"))
            el().buttonNamed("Übernehmen").click() // empty password -> blocked by the form
            kotlinx.coroutines.delay(150)
            assertEquals(0, rpc.acceptCalls.size)

            el().typeInto("Aktuelles Passwort", "geheim-passwort-1", nth = 0)
            el().buttonNamed("Übernehmen").click()
            awaitUntil("accept call") { rpc.acceptCalls.isNotEmpty() }
            assertEquals(listOf("change-9", "geheim-passwort-1"), rpc.acceptCalls.single())
            awaitUntil("reloaded") { rpc.loads == 2 }
        }
    }

    @Test
    fun aProposal_canBeDeclined_withoutAPassword(): Promise<Unit> {
        val proposal =
            OwnPendingEmailChangeDto(
                "change-8",
                "vorschlag@example.org",
                EmailChangeKind.PROPOSAL,
                LocalDateTime(2026, 10, 12, 10, 0),
                null,
                true,
            )
        val rpc = FakeEmailRpc(pending = proposal)
        return withCard(rpc, "ec-card-decline") { el ->
            el().buttonNamed("Ablehnen").click()
            awaitUntil("decline call") { rpc.declineCalls.isNotEmpty() }
            assertEquals(listOf("change-8"), rpc.declineCalls)
            assertEquals(0, rpc.acceptCalls.size)
        }
    }

    @Test
    fun anAdministratorInitiatedChange_showsTheEffectiveTime_andOnlyOffersDecline(): Promise<Unit> {
        val pending =
            OwnPendingEmailChangeDto(
                "change-7",
                "notfall@example.org",
                EmailChangeKind.ADMIN_OVERRIDE,
                LocalDateTime(2026, 10, 12, 10, 0),
                LocalDateTime(2026, 10, 8, 10, 0),
                requiresPassword = false,
            )
        val rpc = FakeEmailRpc(pending = pending)
        return withCard(rpc, "ec-card-override") { el ->
            assertTrue(el().hasText("administrative Person"))
            assertTrue(el().hasText("wirksam"))
            assertEquals(0, el().allOf("button").count { it.textContent?.trim() == "Übernehmen" })
            assertEquals(1, el().allOf("button").count { it.textContent?.trim() == "Ablehnen" })
        }
    }

    @Test
    fun withoutOwnChange_noFormIsShown_andTheHintExplainsWhy(): Promise<Unit> =
        withCard(
            FakeEmailRpc(capability = EmailChangeCapabilityDto(MailDeliveryState.HANDED_TO_SMTP, ownChangeAvailable = false)),
            "ec-card-nochange",
        ) { el ->
            assertFalse(el().allOf("button").any { it.textContent?.trim() == "Adresse ändern" })
            assertTrue(el().hasText("nicht selbst ändern") || el().hasText("Anmeldung Ihrer Organisation"))
        }

    @Test
    fun keycloakMode_showsTheIdentityProviderHint(): Promise<Unit> =
        formTest {
            AppState.setSession(session.copy(keycloakMode = true))
            val rpc = FakeEmailRpc(capability = EmailChangeCapabilityDto(MailDeliveryState.HANDED_TO_SMTP, ownChangeAvailable = false))
            mountedForm("ec-card-keycloak") { root, element ->
                renderMemberEmailSection(root, rpc)
                awaitUntil("hint") { element().hasText("Anmeldung Ihrer Organisation") }
                assertFalse(element().allOf("button").any { it.textContent?.trim() == "Adresse ändern" })
            }
        }

    @Test
    fun aFailedWrite_reloadsTheCard_andShowsNoServerText(): Promise<Unit> {
        val rpc = FakeEmailRpc().apply { failWith = EmailChangeMailUnavailableException("Geheimtext vom Server") }
        return withCard(rpc, "ec-card-fail") { el ->
            el().typeInto("Aktuelles Passwort", "geheim-passwort-1")
            el().typeInto("Neue E-Mail-Adresse", "neu@example.org")
            el().typeInto("Neue E-Mail-Adresse wiederholen", "neu@example.org")
            el().buttonNamed("Adresse ändern").click()
            awaitUntil("reloaded after the failure") { rpc.loads == 2 }
            assertFalse(el().hasText("Geheimtext"))
            assertTrue(el().hasText("dana@example.org"))
        }
    }

    @Test
    fun aGuestSession_getsNoCard(): Promise<Unit> =
        formTest {
            AppState.setSession(session.copy(isGuest = true))
            val rpc = FakeEmailRpc()
            mountedForm("ec-card-guest") { root, element ->
                renderMemberEmailSection(root, rpc)
                kotlinx.coroutines.delay(100)
                assertEquals(0, rpc.loads)
                assertFalse(element().hasText("E-Mail-Adresse"))
            }
        }

    @Test
    fun aForgedI18nMarkerInAnAddress_isNotRenderedAsATranslation(): Promise<Unit> {
        val rpc = FakeEmailRpc().apply { currentEmail = "###KvI18nS###\u0001forged@example.org" }
        return withCard(rpc, "ec-card-forged") { el ->
            assertFalse(el().hasText("###KvI18nS###"))
        }
    }

    @Test
    fun theButtons_carryTheirVerbIcons(): Promise<Unit> =
        withCard(FakeEmailRpc(), "ec-card-icons") { el ->
            assertButtonIcon(el(), "Adresse ändern", "fa-floppy-disk")
        }
}
