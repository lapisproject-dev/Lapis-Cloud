package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolInviteeCandidateDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationRef
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICarpoolService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.125 "Mitfahrten" -- the Mitfahrzentrale as a member, a guest and a friend sees it: the seat line ("2 von 3 Plätzen frei", "Ausgebucht"),
 * the request button only on a foreign, bookable offer, status badges with an icon AND a text and never a danger colour, "Meine Mitfahrten"
 * (waiting items first, hidden while empty), the polite live region, the driver's passenger panel and the exact-name search with its single
 * "nobody" text.
 */
class CarpoolParticipationDomTest {
    private fun session(status: MemberStatus) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = status,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun posting(
        id: String,
        own: Boolean = false,
        seatsOffered: Int? = 3,
        seatsFree: Int? = 2,
        mine: CarpoolMyParticipationRef? = null,
        meetingNote: String? = null,
        openRequests: Int = 0,
        type: CarpoolPostingType = CarpoolPostingType.OFFER,
    ) = CarpoolPostingDto(
        id = id,
        type = type,
        fromPlace = "Ort-$id",
        toPlace = "Ziel-$id",
        departureDate = LocalDate(2099, 5, 1),
        departureTime = null,
        seatsOffered = seatsOffered,
        notes = null,
        authorDisplayName = "Vera Vorstand",
        isOwn = own,
        isPast = false,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        seatsFree = seatsFree,
        myParticipation = mine,
        meetingNote = meetingNote,
        openRequests = openRequests,
    )

    private fun ride(
        id: String,
        status: CarpoolParticipationStatus,
        initiator: CarpoolParticipationInitiator,
        awaiting: Boolean,
        note: String? = null,
    ) = CarpoolMyParticipationDto(
        id = id,
        postingId = "p-$id",
        fromPlace = "Start-$id",
        toPlace = "Ende-$id",
        departureDate = LocalDate(2099, 6, 1),
        departureTime = null,
        driverDisplayName = "Fabian Fahrer",
        status = status,
        initiator = initiator,
        awaitingMe = awaiting,
        meetingNote = note,
        isPast = false,
        decidedAt = null,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private class Routes(
        val mine: String,
        val feed: String,
        val rides: String,
        val request: String,
        val confirm: String,
        val participants: String,
        val find: String,
        val invite: String,
    )

    private suspend fun withCarpoolStub(
        mine: List<CarpoolPostingDto> = emptyList(),
        feed: List<CarpoolPostingDto> = emptyList(),
        rides: List<CarpoolMyParticipationDto> = emptyList(),
        participants: List<CarpoolParticipantDto> = emptyList(),
        hits: List<CarpoolInviteeCandidateDto> = emptyList(),
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                mine = routeOf { rpcService<ICarpoolService>().listMyPostings() },
                feed = routeOf { rpcService<ICarpoolService>().listPostings(null) },
                rides = routeOf { rpcService<ICarpoolService>().listMyParticipations() },
                request = routeOf { rpcService<ICarpoolService>().requestParticipation("x") },
                confirm = routeOf { rpcService<ICarpoolService>().confirmParticipation("x") },
                participants = routeOf { rpcService<ICarpoolService>().listParticipants("x") },
                find = routeOf { rpcService<ICarpoolService>().findInvitee("a", "b") },
                invite = routeOf { rpcService<ICarpoolService>().inviteParticipant("x", "y") },
            )
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.mine -> request.answerWith(jsonOf(ListSerializer(CarpoolPostingDto.serializer()), mine))
                    request.rpcRoute == routes.feed -> request.answerWith(jsonOf(ListSerializer(CarpoolPostingDto.serializer()), feed))
                    request.rpcRoute == routes.rides ->
                        request.answerWith(jsonOf(ListSerializer(CarpoolMyParticipationDto.serializer()), rides))
                    request.rpcRoute == routes.participants ->
                        request.answerWith(jsonOf(ListSerializer(CarpoolParticipantDto.serializer()), participants))
                    request.rpcRoute == routes.find ->
                        request.answerWith(jsonOf(ListSerializer(CarpoolInviteeCandidateDto.serializer()), hits))
                    request.rpcRoute == routes.confirm -> request.answerWith("\"CONFIRMED\"")
                    request.rpcRoute == routes.request ->
                        request.answerWith(
                            jsonOf(
                                CarpoolMyParticipationDto.serializer(),
                                ride("new", CarpoolParticipationStatus.OPEN, CarpoolParticipationInitiator.PASSENGER, awaiting = false),
                            ),
                        )
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.buttonLabels(): List<String> = allOf("button").map { it.textContent.orEmpty().trim() }

    private fun HTMLElement.headingHidden(text: String): Boolean {
        // KVision does not render a hidden widget at all: "hidden" is "absent from the DOM" or "without layout".
        val heading = allOf("h2").firstOrNull { it.textContent.orEmpty().contains(text) } ?: return true
        return !heading.isRendered()
    }

    @Test
    fun aMemberSeesSeatsAndTheRequestButtonOnlyOnAForeignBookableOffer(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            withCarpoolStub(
                mine = listOf(posting("own", own = true, seatsFree = 1)),
                feed =
                    listOf(
                        posting("open", seatsFree = 2),
                        posting("full", seatsFree = 0),
                        posting("own", own = true, seatsFree = 1),
                    ),
            ) { _, _ ->
                mountedForm("carpool-member-seats") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-open") }
                    val screen = element()
                    assertTrue(screen.shows("2 von 3 Plätzen frei"), screen.textContent.orEmpty())
                    assertTrue(screen.shows("Ausgebucht"))
                    assertEquals(1, screen.buttonLabels().count { it == "Mitfahrt anfragen" }, "only the bookable foreign offer")
                    assertTrue(screen.buttonLabels().contains("Mitfahrer verwalten"), "the own offer has the passenger panel")
                    assertTrue(screen.buttonLabels().contains("Kontakt aufnehmen"), "a member may still contact the author")
                    assertTrue(screen.createButtonLabels().contains("Eintrag erstellen"))
                }
            }
        }

    @Test
    fun aGuestSeesNoCreateButtonNoTypeFilterNoContactButtonButMayRequestARide(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.GUEST))
            withCarpoolStub(feed = listOf(posting("open", seatsFree = 2))) { calls, routes ->
                mountedForm("carpool-guest") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-open") }
                    val screen = element()
                    assertTrue(screen.shows("Als Gast können Sie bei angebotenen Fahrten mitfahren."))
                    assertTrue(screen.shows("Den Treffpunkt nennt Ihnen der Fahrer"))
                    assertFalse(screen.shows("per Nachricht"), "the old 'per Nachricht' hint is replaced for a guest")
                    assertTrue(screen.createButtonLabels().isEmpty(), "no 'Eintrag erstellen' for a guest")
                    assertTrue(screen.allOf("select").isEmpty(), "no type filter for a guest")
                    assertFalse(screen.buttonLabels().contains("Kontakt aufnehmen"))
                    assertTrue(calls.toRoute(routes.mine).isEmpty(), "a guest never asks for own postings")
                    screen.buttonNamed("Mitfahrt anfragen").click()
                    awaitUntil("requestParticipation") { calls.toRoute(routes.request).size == 1 }
                    assertEquals("open", calls.singleCall(routes.request).rpcParam(0) as String)
                    awaitUntil("the live region announces it") {
                        screen.allOf("[role='status'][aria-live='polite']").any { it.shows("Mitfahrt angefragt.") }
                    }
                }
            }
        }

    @Test
    fun badgesCarryAnIconAndATextAndNeverTheDangerColour(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.FRIEND))
            val ref = { status: CarpoolParticipationStatus ->
                CarpoolMyParticipationRef("r", status, CarpoolParticipationInitiator.PASSENGER)
            }
            withCarpoolStub(
                feed =
                    listOf(
                        posting("a", mine = ref(CarpoolParticipationStatus.CONFIRMED), meetingNote = "Parkplatz Nord"),
                        posting("b", mine = ref(CarpoolParticipationStatus.OPEN)),
                        posting("c", mine = ref(CarpoolParticipationStatus.DECLINED)),
                        posting("d", mine = ref(CarpoolParticipationStatus.WITHDRAWN)),
                    ),
            ) { _, _ ->
                mountedForm("carpool-badges") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-a") }
                    val screen = element()
                    listOf("Bestätigt", "Wartet auf Bestätigung durch den Fahrer", "Abgelehnt", "Zurückgezogen").forEach {
                        assertTrue(screen.shows(it), "badge text '$it'")
                    }
                    assertTrue(screen.shows("Treffpunkt: Parkplatz Nord"))
                    assertTrue(screen.allOf(".badge i, .badge span[aria-hidden='true']").isNotEmpty(), "badges carry an icon")
                    assertTrue(screen.allOf(".text-bg-danger").isEmpty(), "no danger colour: a declined ride is information, not an error")
                    // A ride declined by the driver cannot be asked again from the card (the server's 24-hour lock); a withdrawn one can.
                    assertEquals(1, screen.buttonLabels().count { it == "Mitfahrt anfragen" })
                }
            }
        }

    @Test
    fun myRidesShowWaitingItemsAndConfirmingAnnouncesTheNewState(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.GUEST))
            withCarpoolStub(
                rides =
                    listOf(
                        ride("w", CarpoolParticipationStatus.OPEN, CarpoolParticipationInitiator.DRIVER, awaiting = true),
                        ride(
                            "c",
                            CarpoolParticipationStatus.CONFIRMED,
                            CarpoolParticipationInitiator.PASSENGER,
                            awaiting = false,
                            note = "Bahnhof 7:30",
                        ),
                    ),
            ) { calls, routes ->
                mountedForm("carpool-rides") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("my rides are shown") { element().shows("Start-w") }
                    val screen = element()
                    assertFalse(screen.headingHidden("Meine Mitfahrten"))
                    assertTrue(screen.shows("Wartet auf Ihre Bestätigung"))
                    assertTrue(screen.shows("Treffpunkt: Bahnhof 7:30"))
                    val confirm = screen.buttonNamed("Bestätigen")
                    confirm.click()
                    awaitUntil("confirmParticipation") { calls.toRoute(routes.confirm).size == 1 }
                    assertEquals("w", calls.singleCall(routes.confirm).rpcParam(0) as String)
                    awaitUntil("announced") { screen.allOf("[role='status'][aria-live='polite']").any { it.shows("Mitfahrt bestätigt.") } }
                }
            }
        }

    @Test
    fun myRidesBlockIsHiddenWhileThereIsNothingToShow(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.GUEST))
            withCarpoolStub(feed = listOf(posting("open"))) { calls, routes ->
                mountedForm("carpool-rides-empty") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the lists were loaded") { calls.toRoute(routes.rides).isNotEmpty() && element().shows("Ort-open") }
                    awaitAppScopeIdle("loaded")
                    assertTrue(element().headingHidden("Meine Mitfahrten"))
                }
            }
        }

    @Test
    fun theDriverExpandsThePassengerPanelAndTheSearchAnswersNobodyWithOneFixedText(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            withCarpoolStub(
                feed = listOf(posting("own", own = true, openRequests = 1)),
                participants =
                    listOf(
                        CarpoolParticipantDto(
                            id = "pp",
                            displayName = "Gustav Gast",
                            status = CarpoolParticipationStatus.OPEN,
                            initiator = CarpoolParticipationInitiator.PASSENGER,
                            awaitingMe = true,
                            decidedAt = null,
                            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                        ),
                    ),
            ) { calls, routes ->
                mountedForm("carpool-owner") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the own offer is shown") { element().shows("Ort-own") }
                    val screen = element()
                    // V1.9.186: the number of open requests is part of the "Mitfahrer verwalten" button, not a badge in the card header.
                    val manage = screen.allOf("button").first { it.textContent.orEmpty().contains("Mitfahrer verwalten") }
                    assertTrue(manage.textContent.orEmpty().contains("1 offene Anfrage"), manage.textContent.orEmpty())
                    assertEquals("Mitfahrer verwalten, 1 offene Anfrage", manage.getAttribute("aria-label"))
                    assertEquals(
                        1,
                        screen.allOf(".badge").count { it.textContent.orEmpty().contains("offene Anfrage") },
                        "exactly one counter: the one inside the button",
                    )
                    assertTrue(manage.classList.contains("btn-sm"), "compact button")
                    assertFalse(manage.classList.contains("w-100"), "not full width")
                    assertTrue(calls.toRoute(routes.participants).isEmpty(), "the passenger list is loaded on the first unfold only")
                    manage.click()
                    awaitUntil("the participants are listed") { screen.shows("Gustav Gast") }
                    assertTrue(screen.shows("Wartet auf Ihre Bestätigung"))
                    // The exact-name search: nobody found -> the one fixed text.
                    screen.typeInto("Vorname", "Maria")
                    screen.typeInto("Nachname", "Muster")
                    screen.buttonNamed("Suchen").click()
                    awaitUntil("findInvitee") { calls.toRoute(routes.find).size == 1 }
                    awaitUntil("the nobody text") { screen.shows("Keine Person mit diesem Namen gefunden.") }
                    // Too short is refused on the client before any call.
                    screen.typeInto("Vorname", "Ma")
                    screen.buttonNamed("Suchen").click()
                    assertEquals(1, calls.toRoute(routes.find).size)
                    assertTrue(screen.shows("Bitte mindestens 3 Zeichen angeben."))
                }
            }
        }

    @Test
    fun theSearchOffersEinladenForAHitAndSendsTheOpaqueToken(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            withCarpoolStub(
                feed = listOf(posting("own", own = true)),
                hits = listOf(CarpoolInviteeCandidateDto(personRef = "opaque-token", displayName = "Gustav Gast")),
            ) { calls, routes ->
                mountedForm("carpool-invite") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the own offer is shown") { element().shows("Ort-own") }
                    val screen = element()
                    screen.buttonNamed("Mitfahrer verwalten").click()
                    awaitUntil("the panel is built") { screen.allOf("label").any { it.shows("Vorname") } }
                    screen.typeInto("Vorname", "Gustav")
                    screen.typeInto("Nachname", "Gast")
                    screen.buttonNamed("Suchen").click()
                    awaitUntil("the hit is shown") { screen.shows("Gefundene Personen") }
                    screen.buttonNamed("Einladen").click()
                    awaitUntil("inviteParticipant") { calls.toRoute(routes.invite).size == 1 }
                    val call = calls.singleCall(routes.invite)
                    assertEquals("own", call.rpcParam(0) as String)
                    assertEquals("opaque-token", call.rpcParam(1) as String)
                    assertNotNull(window.document.body)
                }
            }
        }
}
