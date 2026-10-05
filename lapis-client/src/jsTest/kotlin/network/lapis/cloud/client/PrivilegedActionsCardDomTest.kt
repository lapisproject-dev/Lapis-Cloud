package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SECRET_PASSWORD = "abcd-efgh-jkmn-pqrs"

private fun request(
    id: String,
    action: PrivilegedActionKind = PrivilegedActionKind.DEMOTE,
    status: PrivilegedActionStatus = PrivilegedActionStatus.PENDING,
    actor: String = "Anna Admin",
    target: String = "Bert Bestand",
    notBefore: LocalDateTime? = null,
    executeUntil: LocalDateTime? = null,
) = PrivilegedActionRequestDto(
    id = id,
    action = action,
    actorMemberId = "actor-1",
    actorDisplayName = actor,
    targetMemberId = "target-1",
    targetDisplayName = target,
    requestedRole = if (action == PrivilegedActionKind.DEMOTE) AccountRole.MEMBER else null,
    requestedStatus = if (action == PrivilegedActionKind.SUSPEND) MemberStatus.WITHDRAWN else null,
    reason = "Telefonat protokolliert, Grund liegt vor",
    status = status,
    createdAt = LocalDateTime(2026, 10, 5, 10, 0),
    expiresAt = LocalDateTime(2026, 10, 8, 10, 0),
    notBefore = notBefore,
    executeUntil = executeUntil,
)

private class FakeCardRpc(
    var overview: PrivilegedActionOverviewDto = PrivilegedActionOverviewDto(),
) : PrivilegedActionRpc {
    var overviewCalls = 0
    val approved = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val withdrawn = mutableListOf<String>()
    val executed = mutableListOf<String>()
    var approveResult: ((String) -> PrivilegedActionRequestDto)? = null

    override suspend fun overview(): PrivilegedActionOverviewDto {
        overviewCalls++
        return overview
    }

    override suspend fun approve(requestId: String): PrivilegedActionRequestDto {
        approved += requestId
        return approveResult?.invoke(requestId) ?: request(requestId, status = PrivilegedActionStatus.EXECUTED)
    }

    override suspend fun reject(requestId: String): PrivilegedActionRequestDto {
        rejected += requestId
        return request(requestId, status = PrivilegedActionStatus.REJECTED)
    }

    override suspend fun withdraw(requestId: String): PrivilegedActionRequestDto {
        withdrawn += requestId
        return request(requestId, status = PrivilegedActionStatus.WITHDRAWN)
    }

    override suspend fun executeTemporaryPassword(requestId: String): PrivilegedPasswordResultDto {
        executed += requestId
        return PrivilegedPasswordResultDto(SECRET_PASSWORD, revokedSessionCount = 2, memberNotified = MailDeliveryState.HANDED_TO_SMTP)
    }

    override suspend fun decisions(memberId: String): PeerActionDecisionsDto = error("not used by the card")

    override suspend fun requestTemporaryPassword(
        memberId: String,
        reason: String,
    ): PrivilegedActionRequestDto = error("not used by the card")

    override suspend fun requestDemotion(
        memberId: String,
        newRole: AccountRole,
        reason: String,
    ): PrivilegedActionRequestDto = error("not used by the card")

    override suspend fun requestSuspension(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): PrivilegedActionRequestDto = error("not used by the card")
}

private class CardConfirms : AdminActionConfirm {
    val messages = mutableListOf<String>()
    private val pending = mutableListOf<() -> Unit>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        messages += message
        pending += onConfirm
    }

    fun confirmAll() = pending.toList().also { pending.clear() }.forEach { it() }
}

/** Welle V1.9.57 -- the card "Ausstehende Freigaben" in a real mounted root with an injected RPC. */
class PrivilegedActionsCardDomTest {
    private fun session() =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Caller",
            role = AccountRole.ADMIN,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private fun HTMLElement.cardHost(): HTMLElement? = querySelector("[data-privileged-actions-card]") as? HTMLElement

    private inline fun withCard(
        id: String,
        rpc: FakeCardRpc,
        confirms: CardConfirms = CardConfirms(),
        noinline now: () -> LocalDateTime = { LocalDateTime(2026, 10, 5, 12, 0) },
        crossinline block: suspend (() -> HTMLElement, () -> Unit) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm(id) { root, element ->
                val reload = renderPrivilegedActionsCard(root, rpc = rpc, confirm = confirms, now = now)
                block(element, reload)
            }
        }

    @Test
    fun theCardIsHiddenWhileNothingIsInIt_andAppearsAfterAReload(): Promise<Unit> {
        val rpc = FakeCardRpc()
        return withCard("pac-empty", rpc) { el, reload ->
            awaitAppScopeIdle("first load")
            assertTrue(el().cardHost()?.let { it.offsetParent == null } ?: true, "empty: nothing is shown")
            assertFalse(el().hasText("Ausstehende Freigaben"))
            rpc.overview = PrivilegedActionOverviewDto(awaitingMyApproval = listOf(request("r1")))
            reload()
            awaitUntil("card shown") { el().hasText("Ausstehende Freigaben") }
            assertTrue(el().cardHost()?.let { it.offsetParent != null } ?: true)
        }
    }

    @Test
    fun theTwoSections_showWhoAsksWhatForWhom_andTheReason(): Promise<Unit> {
        val rpc =
            FakeCardRpc(
                PrivilegedActionOverviewDto(
                    awaitingMyApproval = listOf(request("r1", actor = "Anna Admin", target = "Bert Bestand")),
                    requestedByMe = listOf(request("r2", action = PrivilegedActionKind.SUSPEND, target = "Cora Ziel")),
                ),
            )
        return withCard("pac-sections", rpc) { el, _ ->
            awaitUntil("both sections") { el().hasText("Wartet auf Ihre Freigabe") && el().hasText("Von Ihnen beantragt") }
            assertTrue(el().hasText("Rolle entziehen (neue Rolle:"))
            assertTrue(el().hasText("Bert Bestand"))
            assertTrue(el().hasText("Beantragt von Anna Admin am"))
            assertTrue(el().hasText("Begründung: Telefonat protokolliert"))
            assertTrue(el().hasText("Zugang sperren (neuer Status:"))
            assertTrue(el().hasText("Cora Ziel"))
        }
    }

    @Test
    fun approving_asksFirst_thenCallsTheRpcOnce_andReloads(): Promise<Unit> {
        val rpc = FakeCardRpc(PrivilegedActionOverviewDto(awaitingMyApproval = listOf(request("r1"))))
        val confirms = CardConfirms()
        return withCard("pac-approve", rpc, confirms) { el, _ ->
            awaitUntil("row") { el().hasText("Freigabe erteilen") }
            el().buttonNamed("Freigabe erteilen").click()
            assertEquals(1, confirms.messages.size)
            assertTrue(confirms.messages.single().contains("Bert Bestand"))
            delay(80)
            assertTrue(rpc.approved.isEmpty(), "nothing before the confirmation")
            confirms.confirmAll()
            awaitUntil("approved") { rpc.approved.size == 1 }
            assertEquals(listOf("r1"), rpc.approved)
            awaitUntil("reloaded") { rpc.overviewCalls >= 2 }
        }
    }

    @Test
    fun anInvalidatedApproval_isSaidInAFixedSentence(): Promise<Unit> {
        val rpc = FakeCardRpc(PrivilegedActionOverviewDto(awaitingMyApproval = listOf(request("r1"))))
        rpc.approveResult = { request(it, status = PrivilegedActionStatus.INVALIDATED) }
        val confirms = CardConfirms()
        return withCard("pac-invalid", rpc, confirms) { el, _ ->
            awaitUntil("row") { el().hasText("Freigabe erteilen") }
            el().buttonNamed("Freigabe erteilen").click()
            confirms.confirmAll()
            awaitUntil("approved") { rpc.approved.size == 1 }
            delay(100)
            assertTrue(rpc.executed.isEmpty())
        }
    }

    @Test
    fun rejectingAndWithdrawing_callTheirRpcs(): Promise<Unit> {
        val rpc =
            FakeCardRpc(
                PrivilegedActionOverviewDto(
                    awaitingMyApproval = listOf(request("r1")),
                    requestedByMe = listOf(request("r2")),
                ),
            )
        return withCard("pac-reject-withdraw", rpc) { el, _ ->
            awaitUntil("rows") { el().hasText("Ablehnen") && el().hasText("Zurückziehen") }
            el().buttonNamed("Ablehnen").click()
            awaitUntil("rejected") { rpc.rejected == listOf("r1") }
            awaitAppScopeIdle("reloaded after the rejection")
            el().buttonNamed("Zurückziehen").click()
            awaitUntil("withdrawn") { rpc.withdrawn == listOf("r2") }
        }
    }

    @Test
    fun aTemporaryPasswordWaitsForItsObjectionPeriod_thenOffersGenerationOnce_andShowsThePasswordOnce(): Promise<Unit> {
        val waiting =
            request(
                "r3",
                action = PrivilegedActionKind.TEMP_PASSWORD,
                status = PrivilegedActionStatus.APPROVED_WAITING,
                notBefore = LocalDateTime(2026, 10, 6, 10, 0),
                executeUntil = LocalDateTime(2026, 10, 9, 10, 0),
            )
        val rpc = FakeCardRpc(PrivilegedActionOverviewDto(requestedByMe = listOf(waiting)))
        return formTest {
            AppState.setSession(session())
            // before not_before: only the waiting sentence, no generation button
            mountedForm("pac-pw-wait") { root, element ->
                renderPrivilegedActionsCard(root, rpc = rpc, now = { LocalDateTime(2026, 10, 5, 12, 0) })
                awaitUntil("row") { element().hasText("Das Passwort kann ab") }
                assertFalse(element().allOf("button").any { it.textContent?.trim() == "Passwort jetzt erzeugen" })
            }
            // after not_before: the button, one click, one call, the password is shown once in a dialog that stays
            mountedForm("pac-pw-ready") { root, element ->
                renderPrivilegedActionsCard(root, rpc = rpc, now = { LocalDateTime(2026, 10, 7, 12, 0) })
                awaitUntil("button") { element().allOf("button").any { it.textContent?.trim() == "Passwort jetzt erzeugen" } }
                element().buttonNamed("Passwort jetzt erzeugen").click()
                awaitUntil("generated") { rpc.executed == listOf("r3") }
                awaitUntil("receipt") { lastOpenModal().hasText(SECRET_PASSWORD) }
                assertTrue(lastOpenModal().hasText("Wird nicht erneut angezeigt"))
                delay(400)
                assertTrue(lastOpenModal().hasText(SECRET_PASSWORD), "the receipt does not fade away on its own")
                assertEquals(1, rpc.executed.size)
            }
        }
    }

    @Test
    fun memberSuppliedNamesAndReasons_neverCarryAnI18nMarker(): Promise<Unit> {
        val rpc =
            FakeCardRpc(
                PrivilegedActionOverviewDto(
                    awaitingMyApproval = listOf(request("r1", actor = "###KvI18nS###Boese", target = "Ziel###KvI18nS###")),
                ),
            )
        return withCard("pac-marker", rpc) { el, _ ->
            awaitUntil("row") { el().hasText("Beantragt von") }
            assertFalse(el().hasText("###KvI18nS###"))
        }
    }
}
