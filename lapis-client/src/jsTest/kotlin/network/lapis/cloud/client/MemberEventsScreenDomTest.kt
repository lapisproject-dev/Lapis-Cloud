package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationResultDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeEventsRpc(
    var rows: List<EventDto>,
) : MemberEventsRpc {
    var listCalls = 0
    val registerCalls = mutableListOf<String>()
    val cancelCalls = mutableListOf<String>()
    var registerResult: EventRegistrationResultDto? = null
    var registerFailure: Throwable? = null
    var cancelFailure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    val resumeCalls = mutableListOf<String>()
    var resumeUrl: String? = "https://pay.example.org/checkout/resume"
    var resumeFailure: Throwable? = null
    var resumeGate: CompletableDeferred<Unit>? = null

    override suspend fun listUpcoming(): EventPageDto {
        listCalls++
        return EventPageDto(rows = rows, totalCount = rows.size, limit = MEMBER_EVENTS_LIMIT, offset = 0)
    }

    private fun registration(
        eventId: String,
        status: EventRegistrationStatus,
    ) = EventRegistrationDto(
        id = "r-$eventId",
        eventId = eventId,
        memberId = "member-1",
        memberDisplayName = "Dana Keller",
        guestName = null,
        guestEmail = null,
        status = status,
        feeAmount = 0.0,
        waitlistPosition = null,
        registeredAt = EVENT_NOW,
        paymentTransactionId = null,
        journalEntryId = null,
    )

    override suspend fun registerSelf(eventId: String): EventRegistrationResultDto {
        registerCalls += eventId
        gate?.await()
        registerFailure?.let { throw it }
        return registerResult ?: EventRegistrationResultDto(registration(eventId, EventRegistrationStatus.CONFIRMED), null)
    }

    override suspend fun resumeOwnEventPayment(eventId: String): EventRegistrationResultDto {
        resumeCalls += eventId
        resumeGate?.await()
        resumeFailure?.let { throw it }
        return EventRegistrationResultDto(registration(eventId, EventRegistrationStatus.PENDING_PAYMENT), resumeUrl)
    }

    override suspend fun cancelOwnRegistration(eventId: String): EventRegistrationDto {
        cancelCalls += eventId
        cancelFailure?.let { throw it }
        return registration(eventId, EventRegistrationStatus.CANCELLED)
    }
}

private class EventDialogs : MemberEventConfirm {
    class Shown(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val extraLines: List<String>,
        val onConfirm: () -> Unit,
    )

    val shown = mutableListOf<Shown>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        extraLines: List<String>,
        onConfirm: () -> Unit,
    ) {
        shown += Shown(title, message, confirmLabel, extraLines, onConfirm)
    }
}

/** Welle V1.9.33 -- [renderMemberEventsScreenWith] in a real mounted KVision root. */
class MemberEventsScreenDomTest {
    private val session =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private class Harness {
        val navigated = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val dialogs = EventDialogs()
    }

    private inline fun withScreen(
        id: String,
        rpc: FakeEventsRpc,
        crossinline block: suspend (() -> HTMLElement, Harness) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            val h = Harness()
            mountedForm(id) { root: SimplePanel, element ->
                renderMemberEventsScreenWith(
                    root = root,
                    rpc = rpc,
                    now = { EVENT_NOW },
                    navigate = { h.navigated += it },
                    confirm = h.dialogs,
                    toastError = { h.errors += it },
                    toastSuccess = { h.successes += it },
                )
                awaitUntil("loaded") { rpc.listCalls >= 1 }
                delay(80)
                block(element, h)
            }
        }

    private fun HTMLElement.hasButton(text: String) = allOf("button").any { it.textContent?.trim() == text }

    @Test
    fun noEvents_showsTheEmptyText(): Promise<Unit> =
        withScreen("ev-empty", FakeEventsRpc(emptyList())) { el, _ ->
            assertTrue(el().textContent.orEmpty().contains("Derzeit sind keine Veranstaltungen geplant."))
        }

    @Test
    fun draftCancelledAndPastEvents_areHidden_theRestIsSortedByStart(): Promise<Unit> {
        val rows =
            listOf(
                testEvent(
                    id = "late",
                    title = "Spaetes Fest",
                    startsAt = LocalDateTime(2026, 12, 1, 18, 0),
                    endsAt = LocalDateTime(2026, 12, 1, 22, 0),
                ),
                testEvent(id = "draft", title = "Entwurfsfest", status = EventStatus.DRAFT),
                testEvent(id = "cancelled", title = "Abgesagtes Fest", status = EventStatus.CANCELLED),
                testEvent(
                    id = "past",
                    title = "Altes Fest",
                    startsAt = LocalDateTime(2026, 9, 1, 18, 0),
                    endsAt = LocalDateTime(2026, 9, 1, 22, 0),
                ),
                testEvent(id = "early", title = "Fruehes Fest"),
            )
        return withScreen("ev-filter", FakeEventsRpc(rows)) { el, _ ->
            val text = el().textContent.orEmpty()
            assertFalse(text.contains("Entwurfsfest"))
            assertFalse(text.contains("Abgesagtes Fest"))
            assertFalse(text.contains("Altes Fest"))
            assertTrue(text.indexOf("Fruehes Fest") in 0 until text.indexOf("Spaetes Fest"))
        }
    }

    @Test
    fun buttonsAndBadges_followTheEventState(): Promise<Unit> {
        val rows =
            listOf(
                testEvent(id = "free", title = "Gratis"),
                testEvent(id = "paid", title = "Bezahlt", fee = 12.0),
                testEvent(id = "full", title = "Voll", full = true),
                testEvent(id = "mine", title = "Meins", own = EventRegistrationStatus.CONFIRMED),
            )
        return withScreen("ev-buttons", FakeEventsRpc(rows)) { el, _ ->
            assertTrue(el().hasButton("Zur Veranstaltung anmelden"))
            assertTrue(el().hasButton("Anmelden und bezahlen (${formatMoney(12.0)})"))
            assertTrue(el().hasButton("Auf die Warteliste"))
            assertTrue(el().hasButton("Von der Veranstaltung abmelden"))
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Ausgebucht"))
            assertTrue(text.contains("Angemeldet"))
        }
    }

    @Test
    fun noRegisterButton_afterTheStart_afterTheDeadline_orWithAnActiveRegistration(): Promise<Unit> {
        val rows =
            listOf(
                testEvent(id = "running", startsAt = LocalDateTime(2026, 10, 2, 11, 0), endsAt = LocalDateTime(2026, 10, 2, 14, 0)),
                testEvent(id = "deadline", registrationClosesAt = LocalDateTime(2026, 10, 2, 11, 0)),
                testEvent(id = "waitlisted", own = EventRegistrationStatus.WAITLISTED),
            )
        return withScreen("ev-nobutton", FakeEventsRpc(rows)) { el, _ ->
            assertFalse(el().hasButton("Zur Veranstaltung anmelden"))
            assertFalse(el().hasButton("Auf die Warteliste"))
            assertEquals(1, el().allOf("button").count { it.textContent?.trim() == "Von der Veranstaltung abmelden" })
        }
    }

    @Test
    fun aPendingPayment_showsTheExpiryHint_andNoPayLink(): Promise<Unit> =
        withScreen("ev-pending", FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT)))) { el, _ ->
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Die Reservierung verfällt automatisch"))
            assertTrue(el().allOf("a").none { it.textContent.orEmpty().contains("Zahlung") })
        }

    @Test
    fun registering_callsTheServerOnce_toastsAndReloads(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent()))
        return withScreen("ev-register", rpc) { el, h ->
            el().buttonNamed("Zur Veranstaltung anmelden").click()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(listOf("e1"), rpc.registerCalls)
            assertEquals(listOf("Sie sind angemeldet."), h.successes)
            assertTrue(h.navigated.isEmpty())
        }
    }

    @Test
    fun aDoubleClick_registersOnce(): Promise<Unit> {
        val gate = CompletableDeferred<Unit>()
        val rpc = FakeEventsRpc(listOf(testEvent())).apply { this.gate = gate }
        return withScreen("ev-double", rpc) { el, _ ->
            val button = el().buttonNamed("Zur Veranstaltung anmelden")
            button.click()
            button.click()
            delay(60)
            gate.complete(Unit)
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(1, rpc.registerCalls.size)
        }
    }

    @Test
    fun aSafePaymentUrl_isNavigatedTo_withoutReload(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent(fee = 12.0)))
        rpc.registerResult =
            EventRegistrationResultDto(
                EventRegistrationDto(
                    "r",
                    "e1",
                    "member-1",
                    null,
                    null,
                    null,
                    EventRegistrationStatus.PENDING_PAYMENT,
                    12.0,
                    null,
                    EVENT_NOW,
                    null,
                    null,
                ),
                "https://pay.example.org/checkout/abc",
            )
        return withScreen("ev-redirect-ok", rpc) { el, h ->
            el().buttonNamed("Anmelden und bezahlen (${formatMoney(12.0)})").click()
            awaitUntil("navigated") { h.navigated.isNotEmpty() }
            assertEquals(listOf("https://pay.example.org/checkout/abc"), h.navigated)
            delay(80)
            assertEquals(1, rpc.listCalls)
        }
    }

    @Test
    fun anUnsafePaymentUrl_isNeverNavigatedTo(): Promise<Unit> =
        formTest {
            val urls = listOf("http://pay.example.org/x", "javascript:alert(1)", "//evil.example/x")
            urls.forEachIndexed { i, url ->
                val rpc = FakeEventsRpc(listOf(testEvent(fee = 12.0)))
                rpc.registerResult =
                    EventRegistrationResultDto(
                        EventRegistrationDto(
                            "r",
                            "e1",
                            "member-1",
                            null,
                            null,
                            null,
                            EventRegistrationStatus.PENDING_PAYMENT,
                            12.0,
                            null,
                            EVENT_NOW,
                            null,
                            null,
                        ),
                        url,
                    )
                AppState.setSession(session)
                val h = Harness()
                mountedForm("ev-redirect-bad-$i") { root, element ->
                    renderMemberEventsScreenWith(root, rpc, { EVENT_NOW }, { h.navigated += it }, h.dialogs, { h.errors += it }, {
                        h.successes +=
                            it
                    })
                    awaitUntil("loaded") { rpc.listCalls >= 1 }
                    delay(80)
                    element().buttonNamed("Anmelden und bezahlen (${formatMoney(12.0)})").click()
                    awaitUntil("reloaded") { rpc.listCalls == 2 }
                    assertTrue(h.navigated.isEmpty(), url)
                    assertEquals(listOf("Die Zahlungsseite konnte nicht geöffnet werden."), h.errors, url)
                }
            }
        }

    @Test
    fun aRegistrationConflict_reloads_withAFixedMessage(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent())).apply { registerFailure = ConflictException("Anmeldung geschlossen") }
        return withScreen("ev-conflict", rpc) { el, h ->
            el().buttonNamed("Zur Veranstaltung anmelden").click()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(1, h.errors.size)
            assertFalse(h.errors.single().contains("geschlossen"))
        }
    }

    @Test
    fun withdrawing_asksFirst_andCancelPerformsNoCall(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent(own = EventRegistrationStatus.CONFIRMED)))
        return withScreen("ev-withdraw-dialog", rpc) { el, h ->
            el().buttonNamed("Von der Veranstaltung abmelden").click()
            val dialog = h.dialogs.shown.single()
            assertTrue(dialog.message.contains("Sommerfest"))
            assertTrue(dialog.extraLines.isEmpty(), "a free event carries no refund note")
            delay(60)
            assertTrue(rpc.cancelCalls.isEmpty())
        }
    }

    @Test
    fun withdrawingFromAPaidConfirmedEvent_carriesTheRefundNote(): Promise<Unit> {
        val rpc =
            FakeEventsRpc(
                listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.CONFIRMED, ownPaid = true, ownPaidAmount = 12.0)),
            )
        return withScreen("ev-withdraw-refund", rpc) { el, h ->
            el().buttonNamed("Von der Veranstaltung abmelden").click()
            val dialog = h.dialogs.shown.single()
            assertEquals(1, dialog.extraLines.size)
            assertTrue(dialog.extraLines.single().contains("offene Erstattung"))
            assertTrue(dialog.extraLines.single().contains(formatMoney(12.0)))
            dialog.onConfirm()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(listOf("e1"), rpc.cancelCalls)
        }
    }

    @Test
    fun withdrawingWhenTheRegistrationAlreadyChanged_reloads(): Promise<Unit> {
        val rpc =
            FakeEventsRpc(listOf(testEvent(own = EventRegistrationStatus.CONFIRMED))).apply {
                cancelFailure = NotFoundException("gone")
            }
        return withScreen("ev-withdraw-notfound", rpc) { el, h ->
            el().buttonNamed("Von der Veranstaltung abmelden").click()
            h.dialogs.shown
                .single()
                .onConfirm()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(1, h.errors.size)
        }
    }

    @Test
    fun aForgedMarkerInTitleAndPlace_isSanitized_alsoInTheDialogText(): Promise<Unit> {
        val forged = "###KvI18nS###\u0001evil"
        val rpc =
            FakeEventsRpc(listOf(testEvent(title = "Fest $forged", locationText = "Ort $forged", own = EventRegistrationStatus.CONFIRMED)))
        return withScreen("ev-forged", rpc) { el, h ->
            assertFalse(el().textContent.orEmpty().contains("###KvI18nS###"))
            assertTrue(el().textContent.orEmpty().contains("Fest"))
            el().buttonNamed("Von der Veranstaltung abmelden").click()
            assertFalse(
                h.dialogs.shown
                    .single()
                    .message
                    .contains("###KvI18nS###"),
            )
        }
    }

    // ── V1.9.35: resume payment, refund state line ──

    @Test
    fun aFeeBearingConfirmedRegistrationWithoutPayment_showsNoRefundNote(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.CONFIRMED)))
        return withScreen("ev-withdraw-no-note", rpc) { el, h ->
            el().buttonNamed("Von der Veranstaltung abmelden").click()
            assertTrue(
                h.dialogs.shown
                    .single()
                    .extraLines
                    .isEmpty(),
            )
        }
    }

    @Test
    fun theResumeButton_showsOnlyForAPendingPayment_withTheAmount(): Promise<Unit> {
        val rows =
            listOf(
                testEvent(id = "pending", title = "Offen", fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT),
                testEvent(id = "confirmed", title = "Fest", fee = 12.0, own = EventRegistrationStatus.CONFIRMED),
                testEvent(id = "free", title = "Frei"),
            )
        return withScreen("ev-resume-visible", FakeEventsRpc(rows)) { el, _ ->
            assertEquals(1, el().allOf("button").count { it.textContent?.trim() == "Zahlung fortsetzen (${formatMoney(12.0)})" })
        }
    }

    @Test
    fun resuming_navigatesToTheHttpsUrl_once_withoutReload(): Promise<Unit> {
        val rpc = FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT)))
        return withScreen("ev-resume-ok", rpc) { el, h ->
            el().buttonNamed("Zahlung fortsetzen (${formatMoney(12.0)})").click()
            awaitUntil("navigated") { h.navigated.isNotEmpty() }
            assertEquals(listOf("https://pay.example.org/checkout/resume"), h.navigated)
            assertEquals(listOf("e1"), rpc.resumeCalls)
            delay(80)
            assertEquals(1, rpc.listCalls)
        }
    }

    @Test
    fun resumingWithAnUnsafeUrl_isNeverNavigatedTo_toastsAndReloads(): Promise<Unit> =
        formTest {
            listOf("http://pay.example.org/x", "javascript:alert(1)", "https://user:pw@pay.example.org/x").forEachIndexed { i, url ->
                val rpc =
                    FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT))).apply { resumeUrl = url }
                AppState.setSession(session)
                val h = Harness()
                mountedForm("ev-resume-bad-$i") { root, element ->
                    renderMemberEventsScreenWith(root, rpc, { EVENT_NOW }, { h.navigated += it }, h.dialogs, { h.errors += it }, {
                        h.successes += it
                    })
                    awaitUntil("loaded") { rpc.listCalls >= 1 }
                    delay(80)
                    element().buttonNamed("Zahlung fortsetzen (${formatMoney(12.0)})").click()
                    awaitUntil("reloaded") { rpc.listCalls == 2 }
                    assertTrue(h.navigated.isEmpty(), url)
                    assertEquals(listOf("Die Zahlungsseite konnte nicht geöffnet werden."), h.errors, url)
                }
            }
        }

    @Test
    fun aResumeConflict_toastsTheFixedSentence_andReloads(): Promise<Unit> {
        val rpc =
            FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT))).apply {
                resumeFailure = ConflictException("anything")
            }
        return withScreen("ev-resume-conflict", rpc) { el, h ->
            el().buttonNamed("Zahlung fortsetzen (${formatMoney(12.0)})").click()
            awaitUntil("reloaded") { rpc.listCalls == 2 }
            assertEquals(listOf("Die Zahlung kann gerade nicht fortgesetzt werden. Die Ansicht wurde aktualisiert."), h.errors)
            assertTrue(h.navigated.isEmpty())
        }
    }

    @Test
    fun aDoubleClickOnResume_callsTheServerOnce(): Promise<Unit> {
        val gate = CompletableDeferred<Unit>()
        val rpc =
            FakeEventsRpc(listOf(testEvent(fee = 12.0, own = EventRegistrationStatus.PENDING_PAYMENT))).apply { resumeGate = gate }
        return withScreen("ev-resume-double", rpc) { el, h ->
            val button = el().buttonNamed("Zahlung fortsetzen (${formatMoney(12.0)})")
            button.click()
            button.click()
            delay(60)
            gate.complete(Unit)
            awaitUntil("navigated") { h.navigated.isNotEmpty() }
            assertEquals(1, rpc.resumeCalls.size)
        }
    }

    @Test
    fun afterAPaidWithdrawal_theCardShowsTheOpenRefundLine(): Promise<Unit> {
        val rows = listOf(testEvent(fee = 12.0, own = null, ownPaid = true, ownPaidAmount = 12.0))
        return withScreen("ev-refund-open", FakeEventsRpc(rows)) { el, _ ->
            assertTrue(el().textContent.orEmpty().contains("Bezahlt – Erstattung noch offen."))
        }
    }

    @Test
    fun afterTheBoardMarkedTheRefund_theCardSaysSo(): Promise<Unit> {
        val rows =
            listOf(
                testEvent(
                    fee = 12.0,
                    own = null,
                    ownPaid = true,
                    ownPaidAmount = 12.0,
                    ownRefundMarkedAt = LocalDateTime(2026, 10, 1, 9, 0),
                ),
            )
        return withScreen("ev-refund-marked", FakeEventsRpc(rows)) { el, _ ->
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Erstattung vom Vorstand als erledigt vermerkt am"))
            assertFalse(text.contains("Erstattung noch offen"))
        }
    }

    @Test
    fun withoutAPayment_noRefundLineAppears(): Promise<Unit> =
        withScreen("ev-refund-none", FakeEventsRpc(listOf(testEvent(fee = 12.0)))) { el, _ ->
            assertFalse(el().textContent.orEmpty().contains("Erstattung"))
        }
}
