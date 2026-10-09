package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.lastOpenModal
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.62 -- the room a person is INSIDE, in a real mounted root with a stubbed `window.fetch` and a fake session: the listen-only rule,
 * the pews (stable seats), reactions (state and event, throttled, attributed to the SDK identity), chat (text only), the scene and the
 * role-specific controls. The LiveKit transport itself is covered in `LiveKitRoomSessionEncounterTest`.
 */
class EncounterRoomDomTest {
    private var clock = 1_000_000.0

    /** See [withEncounterRoom]; the roster is fixed here. */
    private suspend fun withRoom(
        entry: EncounterEntryDto,
        people: List<EncounterPresentDto>,
        privileged: Boolean = false,
        session: FakeListenerSession = FakeListenerSession(dataAllowed = entry.canPublishData),
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) = withEncounterRoom(
        entry = entry,
        peopleOf = { people },
        clock = { clock },
        privileged = privileged,
        session = session,
        block = block,
    )

    /** The side panel is not in the document while it is closed (KVision does not render a hidden widget): open it on the chat tab first. */
    private suspend fun HTMLElement.openChat() {
        barControl("Chat").click()
        awaitUntil("the side panel is open") { querySelector(".lapis-encounter-chat-log") != null }
    }

    private suspend fun HTMLElement.openPeople() {
        barControl("Chat").click()
        awaitUntil("the side panel is open") { querySelector("[role=tab]") != null }
        allOf("[role=tab]").first { it.textContent.orEmpty().trim() == "Anwesende" }.click()
        awaitUntil("the people tab is shown") {
            querySelector(".lapis-encounter-present") != null &&
                querySelector(".lapis-encounter-person") != null
        }
    }

    private val sixPeople = seatedCrowd()

    // ── listen-only ──────────────────────────────────────────────────────────────

    @Test
    fun theCongregation_hasNoMediaControl_andNothingTouchesTheDevices(): Promise<Unit> =
        formTest {
            val media = js("navigator.mediaDevices")
            val realGetUserMedia = media.getUserMedia
            val realEnumerate = media.enumerateDevices
            var deviceCalls = 0
            media.getUserMedia = { _: dynamic ->
                deviceCalls++
                Promise.reject(IllegalStateException("must not be called"))
            }
            media.enumerateDevices = {
                deviceCalls++
                Promise.resolve(emptyArray<dynamic>())
            }
            try {
                withRoom(testEntry(), sixPeople) { rig, element ->
                    val names = element.barControlNames()
                    assertFalse("Mikrofon" in names, "no microphone button for the congregation, not even a disabled one: $names")
                    assertFalse("Kamera" in names, "no camera button: $names")
                    assertFalse("Übertragung" in names, "the congregation has no transmission control: $names")
                    assertFalse("Türen schließen" in names)
                    assertTrue("Hand heben" in names && "Amen" in names && "Chat" in names, "the congregation's controls: $names")
                    assertEquals(0, deviceCalls, "no getUserMedia/enumerateDevices for a listener")
                    assertFalse(rig.session is EncounterSpeakerSession, "a listener session has no media method at all")
                }
            } finally {
                media.getUserMedia = realGetUserMedia
                media.enumerateDevices = realEnumerate
            }
        }

    // ── office holders ───────────────────────────────────────────────────────────

    @Test
    fun aPulpitPerson_getsTheCameraOnEntering_theMicrophoneStaysOff_andAStatusBandSaysSo(): Promise<Unit> =
        formTest {
            val session = FakeSpeakerSession()
            val entry = testEntry(role = EncounterPresenceRole.PULPIT)
            withRoom(
                entry,
                listOf(testPerson("me", EncounterPresenceRole.PULPIT, "Ich Selbst")) + sixPeople,
                session = session,
            ) { _, element ->
                assertEquals(listOf(true), session.cameraCalls, "the camera is switched on once, in the entering chain")
                assertTrue(session.microphoneCalls.isEmpty(), "the microphone stays off")
                val band = assertNotNull(element.querySelector(".lapis-encounter-mic-band") as? HTMLElement, "the standing mic band")
                assertEquals("status", band.getAttribute("role"))
                assertTrue(band.textContent.orEmpty().contains("Ihr Mikrofon ist aus."))
                val names = element.barControlNames()
                assertTrue("Mikrofon" in names && "Kamera" in names, "the pulpit controls: $names")
                // switching the microphone on is the one explicit click
                band.buttonNamed("Einschalten").click()
                awaitUntil("the microphone was switched on") { session.microphoneCalls == listOf(true) }
                awaitUntil("the band is gone") { !band.isShown() }
            }
        }

    @Test
    fun aSteward_startsWithCameraAndMicrophoneOff(): Promise<Unit> =
        formTest {
            val session = FakeSpeakerSession()
            val entry = testEntry(role = EncounterPresenceRole.STEWARD)
            withRoom(entry, sixPeople, session = session) { _, element ->
                assertTrue(session.cameraCalls.isEmpty(), "a steward does not start any device")
                assertTrue(session.microphoneCalls.isEmpty())
                assertNotNull(element.querySelector(".lapis-encounter-mic-band"))
            }
        }

    // ── pews ─────────────────────────────────────────────────────────────────────

    // V1.9.79 (stage 2a) replaces the B2 pews tests: a seat used to be a non-interactive listitem with the NAME as `title`, assigned
    // automatically by this device. Now it is a real button of the group "Sitzplan", chosen by its occupant and reported by the server.
    // The new checks are stricter than the old ones (no name anywhere in the label, exactly one tab stop, no local assignment).

    @Test
    fun theSeatedCongregation_isShownWithInitialsAndNoName_theOfficesDoNotSit(): Promise<Unit> =
        formTest {
            val people =
                sixPeople +
                    testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul") +
                    testPerson("s1", EncounterPresenceRole.STEWARD, "Steward Sven")
            withRoom(testEntry(), people) { _, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                val plan = assertNotNull(element.querySelector(".lapis-encounter-benches") as? HTMLElement)
                assertEquals("group", plan.getAttribute("role"))
                assertEquals("Sitzplan", plan.getAttribute("aria-label"))
                val described = assertNotNull(element.querySelector("#${plan.getAttribute("aria-describedby")}") as? HTMLElement)
                assertEquals("Die Mikrofone der Gemeinde sind aus.", described.textContent.orEmpty().trim())
                val seat = element.seats().first { it.classList.contains("lapis-encounter-seat--taken") }
                assertEquals("BUTTON", seat.tagName)
                assertEquals("button", seat.getAttribute("type"))
                assertEquals("true", seat.querySelector(".lapis-encounter-seat-hand")?.getAttribute("aria-hidden"))
                assertEquals("GA", seat.querySelector(".lapis-encounter-seat-initials")?.textContent?.trim())
                assertNull(seat.getAttribute("title"), "no tooltip: the name of a person is nowhere on a seat")
                element.seats().forEach { cell ->
                    val label = cell.getAttribute("aria-label").orEmpty()
                    assertFalse(label.contains("Gast"), "a seat's name has no person's name: $label")
                    assertFalse(label.contains("Pfarrer") || label.contains("Sven"), label)
                }
                assertEquals("Reihe 1, Platz 1, besetzt, G A", element.seatLabels()[0], "row, position, state and the spelled initials")
                val pulpit = assertNotNull(element.querySelector(".lapis-encounter-pulpit") as? HTMLElement)
                assertEquals("Kanzel: Pfarrer Paul", pulpit.getAttribute("aria-label"))
                assertTrue(element.textContent.orEmpty().contains("Steward Sven"), "the steward has a tile")
                assertTrue(element.seats().none { it.hasAttribute("title") })
            }
        }

    @Test
    fun nobodyIsSeatedAutomatically_thePeopleWithoutAChoiceStandInTheirOwnRow(): Promise<Unit> =
        formTest {
            val people =
                (1..4).map { testPerson("c$it", name = "Gast $it") } + testPerson("s1", EncounterPresenceRole.STEWARD, "Steward Sven")
            withRoom(testEntry(), people) { _, element ->
                val row = assertNotNull(element.querySelector(".lapis-encounter-unseated") as? HTMLElement)
                awaitUntil("the row of people without a seat is shown") { row.isShown() }
                assertEquals("Noch ohne Platz", row.getAttribute("aria-label"))
                assertEquals(0, element.occupied(), "no seat is taken without a choice")
                assertEquals(4, row.querySelectorAll(".lapis-encounter-unseated-item").length)
                // not operable and no name
                assertEquals(0, row.querySelectorAll("button, a, [tabindex]").length)
                row.querySelectorAll(".lapis-encounter-unseated-item").let { items ->
                    for (i in 0 until items.length) {
                        val label = (items.item(i) as HTMLElement).getAttribute("aria-label").orEmpty()
                        assertFalse(label.contains("Gast"), label)
                    }
                }
            }
        }

    @Test
    fun whenSomeoneLeaves_theirSeatIsFree_nobodyElseMoves_andAStrangerGetsNoSeat(): Promise<Unit> =
        formTest {
            var people = sixPeople
            withEncounterRoom(entry = testEntry(), peopleOf = { people }, clock = { clock }) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                val before = element.seatLabels()
                people = people.filter { it.memberId != "c3" } // the server no longer lists the person
                rig.room.callbacks.onParticipantLeft("c3")
                awaitUntil("one seat is free") { element.occupied() == 5 }
                val after = element.seatLabels()
                val changed = before.indices.filter { before[it] != after[it] }
                assertEquals(listOf(2), changed, "exactly the leaving person's seat changed: $changed")
                assertTrue(after[2].contains("frei"), after[2])
                assertEquals(before.size, after.size, "the pews did not shrink or shift")
                // a LiveKit participant that `listPresent` does not list (an egress bot) never gets a seat or a place in the row
                rig.room.callbacks.onParticipantJoined("egress-bot", "Egress")
                delay(150)
                assertEquals(5, element.occupied())
                assertEquals(0, element.querySelectorAll(".lapis-encounter-unseated-item").length)
            }
        }

    // ── reactions ────────────────────────────────────────────────────────────────

    @Test
    fun aHand_isAStateAtTheSeat_andLowersAgain(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("c2", EncounterReaction.HAND)
                awaitUntil("the hand is up") { element.querySelectorAll(".lapis-encounter-seat-hand.is-on").length == 1 }
                val seat = element.seats()[1]
                assertTrue(seat.getAttribute("aria-label").orEmpty().contains("Hand erhoben"))
                assertEquals(listOf("c2"), rig.room.raisedHandIds)
                rig.room.callbacks.onReaction("c2", EncounterReaction.HAND_LOWERED)
                awaitUntil("the hand is down") { element.querySelectorAll(".lapis-encounter-seat-hand.is-on").length == 0 }
            }
        }

    @Test
    fun aReactionFromSomebodyNotInTheList_isDropped(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("unknown-identity", EncounterReaction.HAND)
                rig.room.callbacks.onReaction("unknown-identity", EncounterReaction.AMEN)
                assertEquals(0, element.querySelectorAll(".lapis-encounter-seat-hand.is-on, .lapis-encounter-seat-event.is-on").length)
                assertTrue(rig.room.raisedHandIds.isEmpty())
            }
        }

    @Test
    fun aReactionFromAPersonWithoutASeat_isShownAtTheirSymbol_notDropped(): Promise<Unit> =
        formTest {
            // V1.9.79: nobody is seated automatically, so "only people who sit react" would have swallowed every hand and amen.
            val people = listOf(testPerson("c1", name = "Anna Unplatziert"), testPerson("c2", name = "Ben Sitzend", seat = 4))
            withRoom(testEntry(), people) { rig, element ->
                awaitUntil("the row without a seat is shown") { element.querySelectorAll(".lapis-encounter-unseated-item").length == 1 }
                rig.room.callbacks.onReaction("c1", EncounterReaction.HAND)
                rig.room.callbacks.onReaction("c1", EncounterReaction.AMEN)
                awaitUntil("the hand and the amen show at the symbol") {
                    element.querySelectorAll(".lapis-encounter-unseated .lapis-encounter-seat-hand.is-on").length == 1 &&
                        element.querySelectorAll(".lapis-encounter-unseated .lapis-encounter-seat-event.is-on").length == 1
                }
                assertEquals(listOf("c1"), rig.room.raisedHandIds)
                val label =
                    rig.room.unseatedRow
                        .labels()
                        .single()
                assertTrue(label.contains("Hand erhoben") && label.contains("Reaktion"), label)
                assertFalse(label.contains("Anna"), label)
            }
        }

    @Test
    fun anAmen_showsASymbolAtTheSeat_neverANumber_andIsAnnouncedAsOneFixedSentence(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("c1", EncounterReaction.AMEN)
                awaitUntil("the amen is shown") { element.querySelectorAll(".lapis-encounter-seat-event.is-on").length == 1 }
                val stage = assertNotNull(element.querySelector(".lapis-encounter-stage") as? HTMLElement)
                assertFalse(Regex("\\d").containsMatchIn(stage.textContent.orEmpty()), "no counter on the stage: ${stage.textContent}")
                val live = element.allOf("[aria-live=polite]").first { it.classList.contains("visually-hidden") }
                awaitUntil("the polite announcement") { live.textContent.orEmpty().contains("Amen aus der Gemeinde") }
                assertFalse(Regex("\\d").containsMatchIn(live.textContent.orEmpty()))
            }
        }

    @Test
    fun theHandButton_togglesAriaPressed_sendsTheState_andAFastSecondClickIsIgnored(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople + testPerson("me", name = "Ich Selbst")) { rig, element ->
                val button = element.barControl("Hand heben")
                assertEquals("false", button.getAttribute("aria-pressed"))
                button.click()
                awaitUntil("the hand state is sent") { rig.session.reactions == listOf(EncounterReaction.HAND) }
                assertEquals("true", button.getAttribute("aria-pressed"))
                button.click() // within the two-second limit: ignored
                delay(100)
                assertEquals(listOf(EncounterReaction.HAND), rig.session.reactions)
                assertEquals("true", button.getAttribute("aria-pressed"))
                clock += 2_500
                button.click()
                awaitUntil(
                    "the hand is lowered",
                ) { rig.session.reactions == listOf(EncounterReaction.HAND, EncounterReaction.HAND_LOWERED) }
                assertEquals("false", button.getAttribute("aria-pressed"))
            }
        }

    @Test
    fun theAmenButton_waitsFiveSeconds_withAVisibleHint(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople + testPerson("me", name = "Ich Selbst")) { rig, element ->
                val button = element.barControl("Amen")
                button.click()
                awaitUntil("the amen is sent") { rig.session.reactions == listOf(EncounterReaction.AMEN) }
                assertTrue(button.hasAttribute("disabled"), "disabled while the limit holds")
                assertTrue(element.textContent.orEmpty().contains("Bitte einen Moment warten."), "a visible reason")
            }
        }

    @Test
    fun aSilencedPerson_canReadButNotWrite_handAmenAndChatAreDisabledWithTheReason(): Promise<Unit> =
        formTest {
            val entry = testEntry(canPublishData = false)
            withRoom(entry, sixPeople) { rig, element ->
                element.openChat()
                assertTrue(element.barControl("Hand heben").hasAttribute("disabled"))
                assertTrue(element.barControl("Amen").hasAttribute("disabled"))
                assertTrue(element.sendButton().hasAttribute("disabled"))
                val field = assertNotNull(element.querySelector("input[aria-label='Nachricht']") as? HTMLInputElement)
                assertTrue(field.disabled)
                assertTrue(element.textContent.orEmpty().contains("Sie wurden von einem Ordner stummgeschaltet."))
                assertTrue(rig.session.reactions.isEmpty())
            }
        }

    // ── chat ─────────────────────────────────────────────────────────────────────

    /** V1.9.66: the send button is icon-only, so its name is the `aria-label`, not its text. */
    private fun HTMLElement.sendButton(): HTMLElement =
        assertNotNull(querySelector(".lapis-chat-composer button[aria-label='Senden']") as? HTMLElement, "no send button")

    private fun HTMLElement.chatLog(): HTMLElement = assertNotNull(querySelector(".lapis-encounter-chat-log") as? HTMLElement)

    @Test
    fun aChatLine_isShownAsText_neverAsMarkupOrLinkOrMarker(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                rig.room.callbacks.onChat("c1", "<b>Anna</b>", "<img src=x onerror=alert(1)> https://example.org/x")
                rig.room.callbacks.onChat("c2", "Ben", "###KvI18nS###Amen")
                element.openChat()
                val log = element.chatLog()
                awaitUntil("both lines shown") { log.querySelectorAll(".lapis-encounter-chat-line").length == 2 }
                assertNull(log.querySelector("img"), "no element is created from a message")
                assertNull(log.querySelector("a"), "a URL is not made clickable")
                assertNull(log.querySelector("b"), "a name is not markup")
                assertTrue(log.textContent.orEmpty().contains("<img src=x onerror=alert(1)>"))
                assertFalse(log.textContent.orEmpty().contains("###KvI18nS###"), "the KVision i18n marker is neutralised")
                assertEquals("log", log.getAttribute("role"))
            }
        }

    @Test
    fun sendingAChatLine_trimsIt_addsItLocally_andAnOverLongOneIsRefused(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                element.openChat()
                val field = assertNotNull(element.querySelector("input[aria-label='Nachricht']") as? HTMLInputElement)
                field.value = "   Guten Morgen  "
                field.dispatchEvent(Event("input"))
                element.sendButton().click()
                awaitUntil("the line was sent") { rig.session.chats == listOf("Guten Morgen") }
                awaitUntil("the line is in the log") {
                    element
                        .chatLog()
                        .textContent
                        .orEmpty()
                        .contains("Guten Morgen")
                }
                awaitUntil("the field is empty again") { field.value.isEmpty() }
                field.value = "x".repeat(ENCOUNTER_CHAT_MAX_CHARS + 1)
                field.dispatchEvent(Event("input"))
                element.sendButton().click()
                delay(150)
                assertEquals(1, rig.session.chats.size, "an over-long line is refused")
            }
        }

    @Test
    fun theChatShowsNoTime(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                rig.room.callbacks.onChat("c1", "Anna", "Hallo")
                element.openChat()
                val log = element.chatLog()
                awaitUntil("the line is shown") { log.querySelectorAll(".lapis-encounter-chat-line").length == 1 }
                assertNull(log.querySelector("time"))
                assertFalse(Regex("\\d{1,2}:\\d{2}").containsMatchIn(log.textContent.orEmpty()), "no clock time: ${log.textContent}")
            }
        }

    // ── scene, panel, keyboard ───────────────────────────────────────────────────

    @Test
    fun theScene_isDecorative_canBeSwitchedOff_andTheChoiceIsTheOnlyStorage(): Promise<Unit> =
        formTest {
            window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            try {
                withRoom(testEntry(), sixPeople) { _, element ->
                    val scene = assertNotNull(element.querySelector(".lapis-encounter-scene") as? HTMLElement)
                    assertEquals("true", scene.getAttribute("aria-hidden"))
                    val root = assertNotNull(element.querySelector(".lapis-encounter") as? HTMLElement)
                    assertFalse(root.classList.contains("lapis-encounter--scene-off"))
                    assertEquals("false", element.barControl("Szene ausblenden").getAttribute("aria-pressed"))
                    element.barControl("Szene ausblenden").click()
                    awaitUntil("the scene is off") { root.classList.contains("lapis-encounter--scene-off") }
                    assertEquals("1", window.localStorage.getItem(ENCOUNTER_SCENE_OFF_KEY))
                    // (theme.css is not loaded under Karma: the `display: none` of the modifier class is pinned by the CSS tripwires)
                    // V1.9.74: the label stays, the state is aria-pressed ("the scene is hidden")
                    assertEquals("true", element.barControl("Szene ausblenden").getAttribute("aria-pressed"))
                    assertEquals(
                        1,
                        window.localStorage.length.let { count ->
                            (0 until count).count {
                                window.localStorage.key(it)?.startsWith("lapis.encounter") ==
                                    true
                            }
                        },
                    )
                }
            } finally {
                window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            }
        }

    @Test
    fun highContrastOrForcedColours_switchTheSceneOffByThemselves(): Promise<Unit> =
        formTest {
            window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            val realMatchMedia = window.asDynamic().matchMedia
            window.asDynamic().matchMedia = { query: String ->
                val result: dynamic = js("({})")
                result.matches = query.contains("forced-colors")
                result.addEventListener = { _: dynamic, _: dynamic -> }
                result.removeEventListener = { _: dynamic, _: dynamic -> }
                result
            }
            try {
                withRoom(testEntry(), sixPeople) { _, element ->
                    val root = assertNotNull(element.querySelector(".lapis-encounter") as? HTMLElement)
                    assertTrue(root.classList.contains("lapis-encounter--scene-off"))
                    assertNull(window.localStorage.getItem(ENCOUNTER_SCENE_OFF_KEY), "an automatic switch-off is not a stored choice")
                }
            } finally {
                window.asDynamic().matchMedia = realMatchMedia
            }
        }

    @Test
    fun theSidePanel_isClosedByDefault_opensWithChat_andEscapeClosesIt(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                assertNull(element.querySelector(".lapis-encounter-side"), "closed by default: not even in the document")
                assertFalse(rig.room.sidePanel.isOpen)
                element.barControl("Chat").click()
                awaitUntil("the panel is open") { element.querySelector(".lapis-encounter-side") != null }
                assertTrue(rig.room.sidePanel.isOpen)
                val panel = assertNotNull(element.querySelector(".lapis-encounter-side") as? HTMLElement)
                panel.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
                awaitUntil("the panel is closed again") { element.querySelector(".lapis-encounter-side") == null }
                assertFalse(rig.room.sidePanel.isOpen)
            }
        }

    @Test
    fun aSingleLetterKey_doesNothing_noShortcutsToMisfire(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                listOf("a", "h", "c", "m", "s").forEach { key ->
                    document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
                    element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
                }
                delay(100)
                assertTrue(rig.session.reactions.isEmpty())
                assertFalse(rig.room.sidePanel.isOpen)
                assertTrue(rig.room.raisedHandIds.isEmpty())
            }
        }

    @Test
    fun afterEntering_theFocusIsOnThePulpit(): Promise<Unit> =
        formTest {
            withRoom(testEntry(), sixPeople) { rig, _ ->
                awaitUntil("the pulpit region has the focus") { document.activeElement === rig.room.pulpitRegion.getElement() }
                assertEquals(
                    "-1",
                    rig.room.pulpitRegion
                        .getElement()
                        ?.getAttribute("tabindex"),
                )
            }
        }

    // ── moderation ───────────────────────────────────────────────────────────────

    private fun HTMLElement.personRows(): List<HTMLElement> = allOf(".lapis-encounter-person")

    private fun HTMLElement.rowOf(name: String): HTMLElement =
        assertNotNull(personRows().firstOrNull { it.textContent.orEmpty().contains(name) }, "no row for $name")

    @Test
    fun aSteward_getsTheMenuForTheCongregationOnly_andSeesTheGuestMarker(): Promise<Unit> =
        formTest {
            val entry = testEntry(role = EncounterPresenceRole.STEWARD)
            val people =
                listOf(
                    testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst"),
                    testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul"),
                    testPerson("s2", EncounterPresenceRole.STEWARD, "Zweiter Ordner"),
                    testPerson("c1", name = "Anna Gast", isGuest = true),
                    testPerson("c2", name = "Ben Mitglied"),
                )
            withRoom(entry, people, session = FakeSpeakerSession()) { _, element ->
                element.openPeople()
                awaitUntil("the people are listed") { element.personRows().size == 5 }

                fun menu(name: String) = element.rowOf(name).allOf("button").map { it.textContent.orEmpty().trim() }
                assertEquals(listOf("Stummschalten", "Entfernen"), menu("Anna Gast"))
                assertEquals(listOf("Stummschalten", "Entfernen"), menu("Ben Mitglied"))
                assertTrue(menu("Pfarrer Paul").isEmpty(), "never against the pulpit")
                assertTrue(menu("Zweiter Ordner").isEmpty(), "never against another steward")
                assertTrue(menu("Ich Selbst").isEmpty(), "never against oneself")
                assertTrue(
                    element
                        .rowOf("Anna Gast")
                        .textContent
                        .orEmpty()
                        .contains("Gast"),
                    "a moderator sees the guest marker",
                )
            }
        }

    @Test
    fun theCongregation_seesNeitherAMenuNorTheGuestMarkerNorTheHands(): Promise<Unit> =
        formTest {
            val people = listOf(testPerson("me", name = "Ich Selbst"), testPerson("c1", name = "Anna Gast", isGuest = true))
            withRoom(testEntry(), people) { rig, element ->
                element.openPeople()
                awaitUntil("the people are listed") { element.personRows().size == 2 }
                assertTrue(element.personRows().all { row -> row.allOf("button").isEmpty() }, "no moderation menu")
                assertEquals(
                    "Anna Gast",
                    element
                        .rowOf("Anna Gast")
                        .textContent
                        .orEmpty()
                        .trim(),
                    "the guest marker is for moderators only",
                )
                rig.room.callbacks.onReaction("c1", EncounterReaction.HAND)
                delay(100)
                assertFalse(element.textContent.orEmpty().contains("Erhobene Hände"), "the congregation sees seats, not a queue")
            }
        }

    @Test
    fun aModerator_seesTheRaisedHandsInTheOrderTheyWentUp(): Promise<Unit> =
        formTest {
            val entry = testEntry(role = EncounterPresenceRole.STEWARD)
            val people =
                listOf(
                    testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst"),
                    testPerson("c1", name = "Anna"),
                    testPerson("c2", name = "Ben"),
                )
            withRoom(entry, people, session = FakeSpeakerSession()) { rig, element ->
                element.openPeople()
                awaitUntil("the people are listed") { element.personRows().size == 3 }
                rig.room.callbacks.onReaction("c2", EncounterReaction.HAND)
                clock += 3_000
                rig.room.callbacks.onReaction("c1", EncounterReaction.HAND)
                awaitUntil("both hands listed") { element.allOf(".lapis-encounter-hand-row").size == 2 }
                assertEquals(listOf("Ben", "Anna"), element.allOf(".lapis-encounter-hand-row").map { it.textContent.orEmpty().trim() })
            }
        }

    @Test
    fun removingAPerson_asksFirst_thenCallsTheServerWithSpaceAndMember(): Promise<Unit> =
        formTest {
            val entry = testEntry(role = EncounterPresenceRole.STEWARD)
            val people = listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst"), testPerson("c1", name = "Anna Gast"))
            withRoom(entry, people, session = FakeSpeakerSession()) { rig, element ->
                element.openPeople()
                awaitUntil("the people are listed") { element.personRows().size == 2 }
                element.rowOf("Anna Gast").buttonNamed("Entfernen").click()
                val modal = lastOpenModal()
                assertTrue(modal.textContent.orEmpty().contains("Diese Person entfernen?"))
                val removeRoute = routeOf { rpcService<IEncounterSpaceService>().removeFromSpace("s", "m") }
                assertEquals(0, rig.requests.count { it.isRpc && it.rpcRoute == removeRoute }, "nothing before the confirmation")
                modal.buttonNamed("Entfernen").click()
                awaitUntil("the removal reached the server") { rig.requests.count { it.isRpc && it.rpcRoute == removeRoute } == 1 }
                val call = rig.requests.first { it.isRpc && it.rpcRoute == removeRoute }
                assertEquals("space-1", call.rpcParam(0).toString())
                assertEquals("c1", call.rpcParam(1).toString())
            }
        }
}
