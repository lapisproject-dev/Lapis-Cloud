package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRefundDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun refund(
    id: String = "r1",
    name: String = "Dana Keller",
    title: String = "Sommerfest",
    amount: Double = 25.0,
    count: Int = 1,
    status: EventRegistrationStatus = EventRegistrationStatus.CANCELLED,
    cancelledAt: LocalDateTime? = LocalDateTime(2026, 9, 20, 10, 0),
) = EventRefundDto(
    registrationId = id,
    eventId = "e1",
    eventTitle = title,
    eventStartsAt = LocalDateTime(2026, 11, 1, 18, 0),
    participantDisplayName = name,
    paidAmount = amount,
    paymentCount = count,
    cancelledAt = cancelledAt,
    status = status,
)

private class FakeRefundsRpc(
    var rows: MutableList<EventRefundDto>,
) : EventRefundsRpc {
    var listCalls = 0
    val marked = mutableListOf<String>()
    var listFailure: Throwable? = null
    var markFailure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun listOpen(): List<EventRefundDto> {
        listCalls++
        listFailure?.let { throw it }
        return rows.toList()
    }

    override suspend fun markRefunded(registrationId: String): EventRefundDto {
        marked += registrationId
        gate?.await()
        markFailure?.let { throw it }
        val done = rows.first { it.registrationId == registrationId }
        rows.remove(done)
        return done
    }
}

private class RefundConfirms : AdminActionConfirm {
    class Shown(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
    )

    val shown = mutableListOf<Shown>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        shown += Shown(title, message, confirmLabel, onConfirm)
    }
}

/** V1.9.35 -- [renderEventRefundsSection] in a real mounted KVision root. */
class EventRefundsSectionDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private class Harness {
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val confirms = RefundConfirms()
    }

    private inline fun withSection(
        id: String,
        rpc: FakeRefundsRpc,
        role: AccountRole = AccountRole.BOARD,
        crossinline block: suspend (() -> HTMLElement, Harness) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            val h = Harness()
            mountedForm(id) { root: SimplePanel, element ->
                renderEventRefundsSection(root, rpc, h.confirms, { h.errors += it }, { h.successes += it })
                delay(150)
                block(element, h)
            }
        }

    @Test
    fun theListShowsHeadingCountAndEveryRow(): Promise<Unit> =
        withSection("refund-list", FakeRefundsRpc(mutableListOf(refund("r1"), refund("r2", name = "Max Muster", count = 2)))) { el, _ ->
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Offene Erstattungen (2)"))
            assertTrue(text.contains("Dana Keller"))
            assertTrue(text.contains("Max Muster"))
            assertTrue(text.contains("Mehrfach bezahlt (2 Zahlungen)"))
            assertTrue(text.contains(formatMoney(25.0)))
            assertEquals(2, el().allOf("button").count { it.textContent?.trim() == "Als erstattet markieren" })
        }

    @Test
    fun anEmptyList_rendersNothing_noHeading_noText(): Promise<Unit> =
        withSection("refund-empty", FakeRefundsRpc(mutableListOf())) { el, _ ->
            assertEquals("", el().textContent.orEmpty().trim())
        }

    @Test
    fun aLoadError_isShown_withRetry(): Promise<Unit> {
        val rpc = FakeRefundsRpc(mutableListOf(refund())).apply { listFailure = IllegalStateException("x") }
        return withSection("refund-error", rpc) { el, _ ->
            assertTrue(el().textContent.orEmpty().contains("Die Daten konnten nicht geladen werden."))
            assertTrue(el().allOf("button").any { it.textContent?.trim() == "Erneut versuchen" })
        }
    }

    @Test
    fun marking_asksFirst_cancelPerformsNoCall(): Promise<Unit> {
        val rpc = FakeRefundsRpc(mutableListOf(refund()))
        return withSection("refund-cancel", rpc) { el, h ->
            el().buttonNamed("Als erstattet markieren").click()
            val dialog = h.confirms.shown.single()
            assertTrue(dialog.message.contains("Dana Keller"))
            assertTrue(dialog.message.contains(formatMoney(25.0)))
            delay(60)
            assertTrue(rpc.marked.isEmpty())
        }
    }

    @Test
    fun confirming_marksOnce_toastsWithoutNameOrAmount_andReloads(): Promise<Unit> {
        val rpc = FakeRefundsRpc(mutableListOf(refund("r1")))
        return withSection("refund-ok", rpc) { el, h ->
            el().buttonNamed("Als erstattet markieren").click()
            h.confirms.shown
                .single()
                .onConfirm()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(listOf("r1"), rpc.marked)
            assertEquals(listOf("Erstattung vermerkt."), h.successes)
            assertFalse(h.successes.single().contains("Dana"))
            assertTrue(h.errors.isEmpty())
            delay(80)
            assertEquals("", el().textContent.orEmpty().trim(), "the last open refund is gone, the section vanishes")
        }
    }

    @Test
    fun aDoubleConfirm_marksOnce(): Promise<Unit> {
        val gate = CompletableDeferred<Unit>()
        val rpc = FakeRefundsRpc(mutableListOf(refund("r1"))).apply { this.gate = gate }
        return withSection("refund-double", rpc) { el, h ->
            el().buttonNamed("Als erstattet markieren").click()
            val confirm =
                h.confirms.shown
                    .single()
                    .onConfirm
            confirm()
            confirm()
            delay(60)
            gate.complete(Unit)
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(1, rpc.marked.size)
        }
    }

    @Test
    fun aConflict_toastsTheFixedSentence_andReloads(): Promise<Unit> {
        val rpc = FakeRefundsRpc(mutableListOf(refund("r1"))).apply { markFailure = ConflictException("Diese Erstattung ist nicht offen.") }
        return withSection("refund-conflict", rpc) { el, h ->
            el().buttonNamed("Als erstattet markieren").click()
            h.confirms.shown
                .single()
                .onConfirm()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(listOf("Diese Erstattung hatte sich bereits geändert. Die Ansicht wurde aktualisiert."), h.errors)
            assertTrue(h.successes.isEmpty())
        }
    }

    @Test
    fun aForgedMarkerInTitleAndName_isSanitized_alsoInTheDialogText(): Promise<Unit> {
        val forged = "###KvI18nS###\u0001evil"
        val rpc = FakeRefundsRpc(mutableListOf(refund(name = "Dana $forged", title = "Fest $forged")))
        return withSection("refund-forged", rpc) { el, h ->
            assertFalse(el().textContent.orEmpty().contains("###KvI18nS###"))
            assertTrue(el().textContent.orEmpty().contains("Fest"))
            el().buttonNamed("Als erstattet markieren").click()
            assertFalse(
                h.confirms.shown
                    .single()
                    .message
                    .contains("###KvI18nS###"),
            )
        }
    }

    @Test
    fun anExpiredReservation_saysSo_insteadOfAWithdrawalDate(): Promise<Unit> =
        withSection(
            "refund-expired",
            FakeRefundsRpc(mutableListOf(refund(status = EventRegistrationStatus.EXPIRED, cancelledAt = null))),
        ) { el, _ ->
            assertTrue(el().textContent.orEmpty().contains("Reservierung abgelaufen"))
            assertFalse(el().textContent.orEmpty().contains("Abgemeldet am"))
        }

    @Test
    fun otherRoles_seeNothing_andTheListIsNeverRequested(): Promise<Unit> =
        formTest {
            listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEachIndexed { i, role ->
                val rpc = FakeRefundsRpc(mutableListOf(refund()))
                AppState.setSession(session(role))
                mountedForm("refund-role-$i") { root, element ->
                    renderEventRefundsSection(root, rpc, RefundConfirms(), {}, {})
                    delay(150)
                    assertEquals(0, rpc.listCalls, role.name)
                    assertEquals("", element().textContent.orEmpty().trim(), role.name)
                }
            }
        }
}
