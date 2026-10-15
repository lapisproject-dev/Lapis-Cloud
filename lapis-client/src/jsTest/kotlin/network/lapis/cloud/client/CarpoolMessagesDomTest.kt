package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolMessageDto
import network.lapis.cloud.shared.domain.CarpoolMessagePageDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICarpoolService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.139 -- the ride messages as a person sees them under "Meine Mitfahrten": the button only on a CONFIRMED ride (also a past one), the fold-out
 * with the fixed hint and the empty state, the order and the labels of the messages, the text as a TEXT NODE (never HTML), the send path
 * with the counter and the disabled button, the ended state.
 */
class CarpoolMessagesDomTest {
    private fun session(status: MemberStatus = MemberStatus.GUEST) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = status,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun ride(
        id: String,
        status: CarpoolParticipationStatus,
        past: Boolean = false,
    ) = CarpoolMyParticipationDto(
        id = id,
        postingId = "p-$id",
        fromPlace = "Start-$id",
        toPlace = "Ende-$id",
        departureDate = LocalDate(2099, 6, 1),
        departureTime = null,
        driverDisplayName = "Fabian Fahrer",
        status = status,
        initiator = CarpoolParticipationInitiator.PASSENGER,
        awaitingMe = false,
        meetingNote = null,
        isPast = past,
        decidedAt = null,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private fun message(
        id: String,
        mine: Boolean,
        body: String,
        minute: Int,
    ) = CarpoolMessageDto(id, mine, if (mine) "" else "Fabian Fahrer", body, LocalDateTime(2026, 1, 1, 10, minute))

    private fun page(
        items: List<CarpoolMessageDto> = emptyList(),
        ended: Boolean = false,
        endedByMe: Boolean = false,
    ) = CarpoolMessagePageDto(items, hasMore = false, ended = ended, endedByMe = endedByMe, writable = !ended, totalCount = items.size)

    private fun pastOffer() =
        CarpoolPostingDto(
            id = "own",
            type = CarpoolPostingType.OFFER,
            fromPlace = "Ort-own",
            toPlace = "Ziel-own",
            departureDate = LocalDate(2020, 5, 1),
            departureTime = null,
            seatsOffered = 3,
            notes = null,
            authorDisplayName = "Dana Keller",
            isOwn = true,
            isPast = true,
            createdAt = LocalDateTime(2020, 1, 1, 10, 0),
        )

    private fun participant(
        id: String,
        status: CarpoolParticipationStatus,
    ) = CarpoolParticipantDto(
        id = id,
        displayName = "Fahrgast-$id",
        status = status,
        initiator = CarpoolParticipationInitiator.PASSENGER,
        awaitingMe = false,
        decidedAt = null,
        createdAt = LocalDateTime(2020, 1, 1, 10, 0),
    )

    private class Routes(
        val mine: String,
        val participants: String,
        val rides: String,
        val messages: String,
        val send: String,
        val end: String,
    )

    private suspend fun withStub(
        rides: List<CarpoolMyParticipationDto>,
        thread: () -> CarpoolMessagePageDto,
        mine: List<CarpoolPostingDto> = emptyList(),
        participants: List<CarpoolParticipantDto> = emptyList(),
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                mine = routeOf { rpcService<ICarpoolService>().listMyPostings() },
                participants = routeOf { rpcService<ICarpoolService>().listParticipants("x") },
                rides = routeOf { rpcService<ICarpoolService>().listMyParticipations() },
                messages = routeOf { rpcService<ICarpoolService>().carpoolMessages("x", null) },
                send = routeOf { rpcService<ICarpoolService>().sendCarpoolMessage("x", "y") },
                end = routeOf { rpcService<ICarpoolService>().endCarpoolThread("x") },
            )
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.mine -> request.answerWith(jsonOf(ListSerializer(CarpoolPostingDto.serializer()), mine))
                    request.rpcRoute == routes.participants ->
                        request.answerWith(jsonOf(ListSerializer(CarpoolParticipantDto.serializer()), participants))
                    request.rpcRoute == routes.rides ->
                        request.answerWith(jsonOf(ListSerializer(CarpoolMyParticipationDto.serializer()), rides))
                    request.rpcRoute in setOf(routes.messages, routes.send, routes.end) ->
                        request.answerWith(jsonOf(CarpoolMessagePageDto.serializer(), thread()))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.buttonLabels(): List<String> = allOf("button").map { it.textContent.orEmpty().trim() }

    @Test
    fun theButtonExistsOnlyOnAConfirmedRide_alsoWhenThePastTripIsOver(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withStub(
                rides =
                    listOf(
                        ride("open", CarpoolParticipationStatus.OPEN),
                        ride("declined", CarpoolParticipationStatus.DECLINED),
                        ride("withdrawn", CarpoolParticipationStatus.WITHDRAWN),
                        ride("yes", CarpoolParticipationStatus.CONFIRMED),
                        ride("old", CarpoolParticipationStatus.CONFIRMED, past = true),
                    ),
                thread = { page() },
            ) { _, _ ->
                mountedForm("carpool-messages-button") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the rides are shown") { element().shows("Start-yes") }
                    assertEquals(2, element().buttonLabels().count { it == "Nachrichten" }, "the confirmed and the confirmed past ride")
                }
            }
        }

    @Test
    fun unfoldingLoadsOnceShowsTheHintAndTheEmptyState(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withStub(rides = listOf(ride("yes", CarpoolParticipationStatus.CONFIRMED)), thread = { page() }) { calls, routes ->
                mountedForm("carpool-messages-unfold") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the ride is shown") { element().shows("Start-yes") }
                    val screen = element()
                    val toggle = screen.buttonNamed("Nachrichten")
                    assertEquals("false", toggle.getAttribute("aria-expanded"))
                    assertTrue(calls.toRoute(routes.messages).isEmpty(), "nothing is loaded before the first unfold")
                    toggle.click()
                    awaitUntil("the history is loaded") {
                        calls.toRoute(routes.messages).size == 1 &&
                            screen.shows("Noch keine Nachrichten.")
                    }
                    assertEquals("true", toggle.getAttribute("aria-expanded"))
                    assertTrue(screen.shows("Nur Sie beide sehen diesen Verlauf; er wird 8 Tage nach der Fahrt gelöscht."))
                    assertEquals(1, calls.toRoute(routes.messages).size)
                }
            }
        }

    @Test
    fun messagesShowOldestFirstWithSenderAndAsPlainText(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val newest = message("2", mine = true, body = "<b>fett</b>\nzweite Zeile", minute = 5)
            val oldest = message("1", mine = false, body = "Treffpunkt ist der Bahnhof", minute = 1)
            withStub(rides = listOf(ride("yes", CarpoolParticipationStatus.CONFIRMED)), thread = { page(listOf(newest, oldest)) }) { _, _ ->
                mountedForm("carpool-messages-list") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the ride is shown") { element().shows("Start-yes") }
                    val screen = element()
                    screen.buttonNamed("Nachrichten").click()
                    awaitUntil("the messages are shown") { screen.shows("Treffpunkt ist der Bahnhof") }
                    val text = screen.textContent.orEmpty()
                    assertTrue(text.indexOf("Treffpunkt ist der Bahnhof") < text.indexOf("zweite Zeile"), "oldest on top")
                    assertTrue(screen.shows("Fabian Fahrer"), "the other party by name")
                    assertTrue(screen.allOf(".lapis-pre-wrap").any { it.shows("<b>fett</b>") }, "the markup shows literally")
                    assertTrue(screen.allOf(".lapis-pre-wrap b").isEmpty(), "and is no element")
                }
            }
        }

    @Test
    fun theSendButtonIsDisabledWhileEmpty_theCounterCounts_andSendingCallsTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withStub(rides = listOf(ride("yes", CarpoolParticipationStatus.CONFIRMED)), thread = { page() }) { calls, routes ->
                mountedForm("carpool-messages-send") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the ride is shown") { element().shows("Start-yes") }
                    val screen = element()
                    screen.buttonNamed("Nachrichten").click()
                    awaitUntil("the form is there") { screen.allOf("textarea").isNotEmpty() }
                    val send = screen.buttonNamed("Senden") as HTMLButtonElement
                    assertTrue(send.disabled, "empty: disabled")
                    assertTrue(screen.shows("0 / 1000") || screen.shows("0 / 1.000"))
                    assertEquals("1000", screen.allOf("textarea").single().getAttribute("maxlength"))
                    screen.typeInto("Nachricht", "Hallo zusammen")
                    awaitUntil("enabled") { !(screen.buttonNamed("Senden") as HTMLButtonElement).disabled }
                    assertTrue(screen.shows("14 / 1000") || screen.shows("14 / 1.000"))
                    screen.buttonNamed("Senden").click()
                    awaitUntil("sent") { calls.toRoute(routes.send).size == 1 }
                    assertEquals("yes", calls.singleCall(routes.send).rpcParam(0) as String)
                    assertEquals("Hallo zusammen", calls.singleCall(routes.send).rpcParam(1) as String)
                }
            }
        }

    @Test
    fun anEndedHistoryHasNoInputAndSaysWho(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withStub(
                rides = listOf(ride("yes", CarpoolParticipationStatus.CONFIRMED)),
                thread = { page(listOf(message("1", mine = false, body = "Hallo", minute = 1)), ended = true, endedByMe = false) },
            ) { _, _ ->
                mountedForm("carpool-messages-ended") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the ride is shown") { element().shows("Start-yes") }
                    val screen = element()
                    screen.buttonNamed("Nachrichten").click()
                    awaitUntil("the ended text") { screen.shows("Der Verlauf wurde beendet.") }
                    assertFalse(screen.allOf("textarea").any { it.isRendered() }, "no input once it has ended")
                    assertFalse(screen.buttonLabels().contains("Verlauf beenden"), "nothing left to end")
                }
            }
        }

    @Test
    fun theDriverReachesTheHistoryOfAPastOffer_withoutAnyWritingControl(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            withStub(
                rides = emptyList(),
                thread = { page(listOf(message("1", mine = false, body = "Schirm vergessen", minute = 1))) },
                mine = listOf(pastOffer()),
                participants =
                    listOf(
                        participant("c", CarpoolParticipationStatus.CONFIRMED),
                        participant("o", CarpoolParticipationStatus.OPEN),
                    ),
            ) { _, _ ->
                mountedForm("carpool-messages-driver-past") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the past offer is shown") { element().shows("Ort-own") }
                    val screen = element()
                    assertFalse(screen.buttonLabels().contains("Mitfahrer verwalten"), "no management after the trip")
                    screen.buttonNamed("Mitfahrer").click()
                    awaitUntil("the passengers are listed") { screen.shows("Fahrgast-c") }
                    assertEquals(1, screen.buttonLabels().count { it == "Nachrichten" }, "only the confirmed passenger has a history")
                    assertFalse(screen.buttonLabels().contains("Entfernen"), "nobody is removed after the trip")
                    assertFalse(screen.shows("Mitfahrer hinzufügen"), "no invitation after the trip")
                    screen.buttonNamed("Nachrichten").click()
                    awaitUntil("the message is shown") { screen.shows("Schirm vergessen") }
                }
            }
        }

    @Test
    fun endingTheHistoryRemovesTheEndControlAndItsQuestion(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var ended = false
            withStub(
                rides = listOf(ride("yes", CarpoolParticipationStatus.CONFIRMED)),
                thread = { if (ended) page(ended = true, endedByMe = true) else page() },
            ) { calls, routes ->
                mountedForm("carpool-messages-end") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the ride is shown") { element().shows("Start-yes") }
                    val screen = element()
                    screen.buttonNamed("Nachrichten").click()
                    awaitUntil("the end control") { screen.buttonLabels().contains("Verlauf beenden") }
                    screen.buttonNamed("Verlauf beenden").click()
                    awaitUntil("the question") { screen.shows("Wirklich beenden?") }
                    ended = true
                    screen.buttonNamed("Endgültig beenden").click()
                    awaitUntil("the history has ended") { screen.shows("Sie haben den Verlauf beendet.") }
                    awaitUntil("the end control is gone") {
                        !screen.shows("Wirklich beenden?") &&
                            !screen.buttonLabels().contains("Verlauf beenden") &&
                            !screen.buttonLabels().contains("Endgültig beenden")
                    }
                    assertEquals(1, calls.toRoute(routes.end).size, "ended exactly once")
                }
            }
        }
}
