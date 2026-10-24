package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventRegistrationOverviewDto
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.204 -- the event detail page: deep link, back button above the heading, sections in order, saving, a neutral not-found, no registrations for a plain member. */
class EventDetailScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun event(
        id: String,
        title: String,
        status: EventStatus = EventStatus.PUBLISHED,
        waitlist: Int = 0,
    ) = EventDto(
        id = id,
        slug = "slug-$id",
        title = title,
        description = "Beschreibung",
        locationText = "Halle",
        onlineUrl = null,
        startsAt = LocalDateTime(2099, 11, 1, 18, 0),
        endsAt = LocalDateTime(2099, 11, 1, 20, 0),
        capacity = 10,
        feeAmount = 0.0.toDecimal(),
        feeCurrency = "EUR",
        status = status,
        visibility = EventVisibility.MEMBERS_ONLY,
        registrationClosesAt = null,
        occupiedSeats = 3,
        waitlistCount = waitlist,
        full = false,
        feeEditable = true,
        ownRegistrationStatus = null,
        publicUrl = null,
    )

    private val seedInput =
        EventInput(
            "t",
            "d",
            null,
            null,
            LocalDateTime(2099, 1, 1, 0, 0),
            LocalDateTime(2099, 1, 1, 1, 0),
            null,
            0.0.toDecimal(),
            "EUR",
            EventVisibility.MEMBERS_ONLY,
            null,
            null,
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun emptyOverview(id: String) =
        jsonOf(
            EventRegistrationOverviewDto.serializer(),
            EventRegistrationOverviewDto(
                eventId = id,
                capacity = 10,
                activeCount = 3,
                waitlistedCount = 0,
                inactiveCount = 0,
                freeSeats = 7,
                withMessageCount = 0,
                rows = emptyList(),
            ),
        )

    @Test
    fun theDeepLink_showsBackButtonAboveTheHeading_andTheSectionsInOrder(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val get = routeOf { rpcService<IEventService>().getEvent("1") }
            val rooms = routeOf { rpcService<IEventRoomService>().listRooms() }
            val overview = routeOf { rpcService<IEventService>().getRegistrationOverview("1") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get -> request.answerWith(jsonOf(EventDto.serializer(), event("1", "Sommerfest")))
                        request.rpcRoute == rooms -> request.answerWith(jsonOf(ListSerializer(EventRoomDto.serializer()), emptyList()))
                        request.rpcRoute == overview -> request.answerWith(emptyOverview("1"))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("event-detail-order") { root, element ->
                    renderEventDetailScreen(root, "1")
                    awaitUntil("the registrations section is shown") { element().text().contains("Noch keine Anmeldungen.") }
                    val html = element().innerHTML
                    assertTrue(html.indexOf("Zur Übersicht") in 0 until html.indexOf("<h1"), "back button above the heading")
                    val text = element().text()
                    assertTrue(text.contains("Sommerfest"), "the title is the subtitle")
                    assertTrue(text.indexOf("Bearbeiten") in 0 until text.indexOf("Anmeldungen"), "edit form before the registrations")
                    assertTrue(element().allOf("label").any { it.text().startsWith("Titel") }, "the edit form is open at once")
                }
            }
        }

    @Test
    fun saving_callsUpdateEventWithTheTypedTitle(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val get = routeOf { rpcService<IEventService>().getEvent("1") }
            val update = routeOf { rpcService<IEventService>().updateEvent("1", seedInput) }
            val overview = routeOf { rpcService<IEventService>().getRegistrationOverview("1") }
            val stored = event("1", "Sommerfest")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get -> request.answerWith(jsonOf(EventDto.serializer(), stored))
                        request.rpcRoute == update -> request.answerWith(jsonOf(EventDto.serializer(), stored))
                        request.rpcRoute == overview -> request.answerWith(emptyOverview("1"))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-detail-save") { root, element ->
                    renderEventDetailScreen(root, "1")
                    awaitUntil("the form is built") { element().allOf("label").any { it.text().startsWith("Titel") } }
                    element().typeInto("Titel", "Herbstfest")
                    element().buttonNamed("Änderungen speichern").click()
                    awaitUntil("updateEvent was called") { calls.toRoute(update).size == 1 }
                    assertEquals("Herbstfest", calls.singleCall(update).rpcParam(1).title as String)
                }
            }
        }

    @Test
    fun anUnknownId_showsOneNeutralSentence_neverTheServerMessage(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val get = routeOf { rpcService<IEventService>().getEvent("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get ->
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.NotFoundException")
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("event-detail-missing") { root, element ->
                    renderEventDetailScreen(root, "x")
                    awaitUntil("the neutral sentence") { element().text().contains("Veranstaltung nicht gefunden.") }
                    assertFalse(element().text().contains("simulated for a test"))
                    assertTrue(element().allOf("button").any { it.text().trim() == "Zur Übersicht" })
                }
            }
        }

    @Test
    fun aPlainMember_getsNoRegistrationsSection_andNoOverviewRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val get = routeOf { rpcService<IEventService>().getEvent("1") }
            val overview = routeOf { rpcService<IEventService>().getRegistrationOverview("1") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get -> request.answerWith(jsonOf(EventDto.serializer(), event("1", "Sommerfest")))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-detail-member") { root, element ->
                    renderEventDetailScreen(root, "1")
                    awaitUntil("the page is shown") { element().allOf("label").any { it.text().startsWith("Titel") } }
                    awaitAppScopeIdle("all loads are done")
                    assertFalse(element().text().contains("Anmeldungen"))
                    assertEquals(0, calls.toRoute(overview).size, "the overview is never requested")
                }
            }
        }

    @Test
    fun thePublishAndCancelActions_areTextButtonsOnTheDetailPage(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val get = routeOf { rpcService<IEventService>().getEvent("1") }
            val overview = routeOf { rpcService<IEventService>().getRegistrationOverview("1") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get ->
                            request.answerWith(
                                jsonOf(EventDto.serializer(), event("1", "Entwurf", EventStatus.DRAFT, waitlist = 2)),
                            )
                        request.rpcRoute == overview -> request.answerWith(emptyOverview("1"))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("event-detail-actions") { root, element ->
                    renderEventDetailScreen(root, "1")
                    awaitUntil("the page is shown") { element().text().contains("Noch keine Anmeldungen.") }
                    assertTrue(element().allOf("button").any { it.text().trim() == "Veröffentlichen" })
                    assertTrue(element().allOf("button").any { it.text().trim() == "Absagen" })
                }
            }
        }
}
