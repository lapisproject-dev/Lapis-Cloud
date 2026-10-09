package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.82 -- the new public-facing fields of the event form: the summary with its live counter, the opt-in checkbox for the
 * public online link (off by default, only usable for an https link), the "shown publicly" hints, the alt text of the cover image with
 * its non-blocking warning, and the "Importiert" badge in the list.
 */
class EventPublicFieldsFormDomTest {
    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun event(
        id: String,
        title: String,
        coverImageUrl: String? = null,
        coverImageAlt: String? = null,
        imported: Boolean = false,
    ) = EventDto(
        id = id,
        slug = "slug-$id",
        title = title,
        description = "Beschreibung",
        locationText = "Halle",
        onlineUrl = null,
        startsAt = LocalDateTime(2099, 11, 1, 18, 0),
        endsAt = LocalDateTime(2099, 11, 1, 20, 0),
        capacity = null,
        feeAmount = 0.0.toDecimal(),
        feeCurrency = "EUR",
        status = EventStatus.PUBLISHED,
        visibility = EventVisibility.PUBLIC,
        registrationClosesAt = null,
        occupiedSeats = 0,
        waitlistCount = 0,
        full = false,
        feeEditable = true,
        ownRegistrationStatus = null,
        publicUrl = null,
        coverImageUrl = coverImageUrl,
        coverImageAlt = coverImageAlt,
        imported = imported,
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

    private fun page(rows: List<EventDto>) = jsonOf(EventPageDto.serializer(), EventPageDto(rows, rows.size, 200, 0))

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.counter(): String? =
        allOf("div.small.text-muted")
            .firstOrNull {
                Regex("""^\d+ / \d+$""").matches(it.text().trim())
            }?.text()
            ?.trim()

    @Test
    fun theSummaryHasALiveCounter_andTheCreateFormShowsThePublicHints(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(emptyList())) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("event-fields-counter") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty list is shown") { element().text().contains("Keine Veranstaltungen gefunden.") }
                    val form =
                        createFormButton(element(), "lapis-create-event").let {
                            it.click()
                            awaitUntil("the form is built") { element().querySelector("[id='lapis-create-event'] label") != null }
                            element().querySelector("[id='lapis-create-event']") as HTMLElement
                        }
                    assertEquals("0 / 300", form.counter())
                    form.typeInto("Kurztext", "abc")
                    awaitUntil("the counter follows the input") { form.counter() == "3 / 300" }
                    form.typeInto("Kurztext", "a".repeat(301))
                    awaitUntil("over the limit") { form.counter() == "301 / 300" }
                    assertTrue(form.allOf("div.small.text-muted.text-danger").isNotEmpty(), "the counter turns red over the limit")
                    assertTrue(form.text().contains("Erscheint auf der Webseite als Vorschautext. Öffentlich."))
                    assertEquals(
                        2,
                        Regex("Wird öffentlich angezeigt\\.").findAll(form.text()).count(),
                        "description and place carry the hint",
                    )
                    assertTrue(form.text().contains("Nur aktivieren, wenn der Link ohne Anmeldung geteilt werden darf."))
                }
            }
        }

    @Test
    fun thePublicOnlineLinkCheckbox_isOffByDefault_andOnlyUsableForHttps(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(emptyList())) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("event-fields-online-link") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty list is shown") { element().text().contains("Keine Veranstaltungen gefunden.") }
                    createFormButton(element(), "lapis-create-event").click()
                    awaitUntil("the form is built") { element().querySelector("[id='lapis-create-event'] label") != null }
                    val form = element().querySelector("[id='lapis-create-event']") as HTMLElement
                    val check = form.controlOf("Online-Link öffentlich anzeigen") as HTMLInputElement
                    assertFalse(check.checked, "off by default")
                    assertTrue(check.disabled, "disabled while there is no link")
                    form.typeInto("Online-Link (optional)", "http://meet.example/x")
                    awaitUntil("still disabled for http") { check.disabled }
                    form.typeInto("Online-Link (optional)", "https://meet.example/x")
                    awaitUntil("enabled for https") { !check.disabled }
                    assertFalse(check.checked, "enabling it does not switch it on")
                    check.click()
                    assertTrue(check.checked)
                    form.typeInto("Online-Link (optional)", "http://meet.example/x")
                    awaitUntil("disabled again and switched off for http") { check.disabled && !check.checked }
                }
            }
        }

    @Test
    fun createEvent_sendsSummaryAndTheOnlineLinkOptIn(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val create = routeOf { rpcService<IEventService>().createEvent(seedInput) }
            val rooms = routeOf { rpcService<IEventRoomService>().listRooms() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(emptyList()))
                        request.rpcRoute == create -> request.answerWith(jsonOf(EventDto.serializer(), event("9", "Neu")))
                        request.rpcRoute == rooms -> request.answerWith("[]")
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-fields-create") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty list is shown") { element().text().contains("Keine Veranstaltungen gefunden.") }
                    createFormButton(element(), "lapis-create-event").click()
                    awaitUntil("the form is built") { element().querySelector("[id='lapis-create-event'] label") != null }
                    val form = element().querySelector("[id='lapis-create-event']") as HTMLElement
                    form.typeInto("Titel", "Sommerfest")
                    form.typeInto("Kurztext", "  Ein kurzer Teaser  ")
                    form.typeInto("Beschreibung", "Ein Abend für alle")
                    form.typeInto("Ort", "Vereinsheim")
                    form.typeInto("Online-Link (optional)", "https://meet.example/x")
                    form.typeInto("Beginn", "2099-11-01T18:00")
                    form.typeInto("Ende", "2099-11-01T20:00")
                    form.tick("Online-Link öffentlich anzeigen")
                    element().buttonNamed("Veranstaltung anlegen").click()
                    awaitUntil("createEvent was called") { calls.toRoute(create).size == 1 }
                    val sent = calls.singleCall(create).rpcParam(0)
                    assertEquals("Ein kurzer Teaser", sent.summary as String)
                    assertEquals(true, sent.onlineUrlPublic as Boolean)
                    assertEquals("https://meet.example/x", sent.onlineUrl as String)
                }
            }
        }

    @Test
    fun theEditForm_warnsAboutAMissingAltText_untilOneIsTyped_andNeverBlocksSaving(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val update = routeOf { rpcService<IEventService>().updateEvent("1", seedInput) }
            val withCover = event("1", "Mit Bild", coverImageUrl = "https://example.org/veranstaltung/slug-1/bild?v=abc12345")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(listOf(withCover)))
                        request.rpcRoute == update -> request.answerWith(jsonOf(EventDto.serializer(), withCover))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-fields-alt") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the row is shown") { element().text().contains("Mit Bild") }
                    element().buttonNamed("Bearbeiten").click()
                    awaitUntil("the edit form is built") { element().allOf("label").any { it.text().startsWith("Bildbeschreibung") } }

                    // KVision drops the vnode of a hidden widget and reuses the DOM element, so the warning is looked up anew each time.
                    fun warningShown() =
                        element().allOf("div.text-warning").any {
                            it.text().contains("Bildbeschreibung") &&
                                it.offsetParent != null
                        }
                    assertTrue(warningShown(), "warning visible while the alt text is empty")
                    element().typeInto("Bildbeschreibung", "Ein Plakat mit Kerzen")
                    awaitUntil("warning hidden") { !warningShown() }
                    element().typeInto("Bildbeschreibung", "")
                    awaitUntil("warning visible again") { warningShown() }
                    // not blocking: saving with an empty alt text works
                    element().buttonNamed("Änderungen speichern").click()
                    awaitUntil("updateEvent was called") { calls.toRoute(update).size == 1 }
                    assertEquals(null, calls.singleCall(update).rpcParam(1).coverImageAlt)
                }
            }
        }

    @Test
    fun theEditForm_hasNoWarningForAnEventWithoutACoverImage_andShowsStoredValues(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val plain = event("2", "Ohne Bild", coverImageAlt = "Gespeicherter Text")
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(listOf(plain))) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("event-fields-alt-nocover") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the row is shown") { element().text().contains("Ohne Bild") }
                    element().buttonNamed("Bearbeiten").click()
                    awaitUntil("the edit form is built") { element().allOf("label").any { it.text().startsWith("Bildbeschreibung") } }
                    assertEquals("Gespeicherter Text", (element().controlOf("Bildbeschreibung") as HTMLInputElement).value)
                    assertTrue(
                        element().allOf("div.text-warning").none { it.text().contains("Bildbeschreibung") && it.offsetParent != null },
                        "no visible warning without a cover image",
                    )
                }
            }
        }

    @Test
    fun anImportedEvent_carriesAnImportedBadgeInTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val rows = listOf(event("3", "Importierte", imported = true), event("4", "Normale"))
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(rows)) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("event-fields-badge") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the rows are shown") { element().text().contains("Importierte") && element().text().contains("Normale") }
                    val badges = element().allOf("div.badge").filter { it.text().trim() == "Importiert" }
                    assertEquals(1, badges.size, "only the imported event has the badge")
                }
            }
        }
}
