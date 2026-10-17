package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- the ENTRY side of the capture consent: in an event that is released for recording / streaming (instance switch on AND the event released AND a
 * Lapis conference) nobody gets a token without having acknowledged the CURRENT consent text -- a member, a guest account (which also needs the capture
 * variant of the guest notice) and a person WITHOUT an account. The proof is written in the transaction that lets the person in, carries no name and no clock
 * time, and is idempotent. Without a release nothing changes (V1.9.113 behaviour).
 */
class EventCaptureJoinTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var organizer: Uuid
        lateinit var join: EventOnlineJoin

        val open = Instant.parse("2030-06-15T22:30:00Z")
        val captureNotice =
            ConferenceGuestConsentAcknowledgmentInput(
                consentVersion = EventGuestNoticeCapture.VERSION,
                consentSha256 = EventGuestNoticeCapture.SHA256,
            )

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            val audio = ConferenceAudioGrantState()
            val provisioner =
                EventConferenceProvisioner(
                    config = EVENT_ONLINE_CONFIG,
                    liveKit = live,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = live,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = audio,
                        ),
                    retryDelay = 20.milliseconds,
                    maxWaits = 20,
                )
            join = EventOnlineJoin(config = EVENT_ONLINE_CONFIG, liveKit = live, provisioner = provisioner, audioGrantState = audio)
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun member(
            eventId: Uuid,
            consent: EventCaptureAcknowledgmentInput?,
            who: Uuid = fx.member(),
            guestConsent: ConferenceGuestConsentAcknowledgmentInput? = null,
            status: MemberStatus = MemberStatus.ACTIVE,
        ) = runBlocking {
            join.joinMember(
                eventId = eventId,
                current = fx.current(memberId = who, status = status),
                guestConsent = guestConsent,
                captureConsent = consent,
                now = open,
            )
        }

        fun guest(
            eventId: Uuid,
            consent: EventCaptureAcknowledgmentInput?,
            noticeVersion: String = EventGuestNoticeCapture.VERSION,
            ticket: String? = null,
        ) = runBlocking {
            join.joinAccountless(
                eventId = eventId,
                displayName = "Gast (Gast)",
                ticketCodeSha256 = ticket,
                guestNoticeVersion = noticeVersion,
                captureConsent = consent,
                now = open,
            )
        }

        test(
            "a member of a released event is refused without the consent, with a wrong hash and with an outdated version -- and let in with the right one",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            shouldThrow<EventCaptureNoticeRequiredException> { member(event, consent = null) }
            shouldThrow<EventCaptureNoticeOutdatedException> { member(event, consent.copy(consentSha256 = "0".repeat(64))) }
            shouldThrow<EventCaptureNoticeOutdatedException> { member(event, consent.copy(consentVersion = "2026-01-01.other")) }
            // a newer text is published meanwhile: the acknowledgment of the old one no longer counts
            EventCaptureTestState.publishConsent(label = "2026-10-19.test.v2", body = EVENT_CAPTURE_TEST_BODY + " Neu.")
            shouldThrow<EventCaptureNoticeOutdatedException> { member(event, consent) }
            EventCaptureTestState.acknowledgments(event).size shouldBe 0
        }

        test("the proof of a member: version, hash, the event and a DATE -- no name, no clock time; a second entry writes nothing new") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val who = fx.member(name = "Erika Mustermann")
            member(event, consent, who = who).outcome shouldBe EventOnlineOutcome.JOINED
            member(event, consent, who = who).outcome shouldBe EventOnlineOutcome.JOINED
            val rows = EventCaptureTestState.acknowledgments(event)
            rows.size shouldBe 1
            val row = rows.single()
            row[EventCaptureAcknowledgmentTable.memberId] shouldBe who
            row[EventCaptureAcknowledgmentTable.guestKey] shouldBe null
            row[EventCaptureAcknowledgmentTable.consentVersion] shouldBe consent.consentVersion
            row[EventCaptureAcknowledgmentTable.consentSha256] shouldBe consent.consentSha256
            row[EventCaptureAcknowledgmentTable.acknowledgedOn] shouldBe OrganizationTimeZone.today()
            // the table has no column that could hold a name, a room or a time of day
            EventCaptureAcknowledgmentTable.columns.map { it.name }.toSet() shouldBe
                setOf("id", "event_id", "member_id", "guest_key", "consent_version", "consent_sha256", "acknowledged_on")
            row.toString() shouldNotContain "Erika"
        }

        test("without a release -- or with the instance switch off -- nothing changes: no consent needed, no proof written") {
            val consent = EventCaptureTestState.enabledWithText()
            val plain = fx.event(createdBy = organizer)
            member(plain, consent = null).outcome shouldBe EventOnlineOutcome.JOINED
            EventCaptureTestState.acknowledgments(plain).size shouldBe 0
            // released, but the instance switch is off: the release is dormant
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            EventCaptureTestState.instance(enabled = false)
            member(released, consent = null).outcome shouldBe EventOnlineOutcome.JOINED
            // the switch is on, but with an OLDER operator notice than the code knows: not startable, so no consent is asked
            EventCaptureTestState.instance(enabled = true, noticeVersion = "0")
            member(released, consent = null).outcome shouldBe EventOnlineOutcome.JOINED
            EventCaptureTestState.acknowledgments(released).size shouldBe 0
        }

        test("a released event without a published text lets nobody in (the placeholder is never a basis)") {
            EventCaptureTestState.instance(enabled = true)
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            shouldThrow<EventCaptureTextMissingException> { member(event, consent = null) }
            shouldThrow<EventCaptureTextMissingException> { guest(event, consent = null) }
            shouldThrow<EventCaptureTextMissingException> {
                member(
                    event,
                    EventCaptureAcknowledgmentInput(
                        consentVersion = "2026-10-18.event-rec.v1",
                        consentSha256 =
                            AgreementTextHash.of(
                                versionLabel = "2026-10-18.event-rec.v1",
                                body = network.lapis.cloud.server.agreementtexts.EventCaptureConsentDefault.TEXT,
                            ),
                    ),
                )
            }
        }

        test("an event that is no Lapis conference is never released (the flag alone does not matter)") {
            EventCaptureTestState.enabledWithText()
            val onSite = fx.event(createdBy = organizer, kind = EventOnlineKind.NONE, captureAllowed = true)
            val conference = fx.event(createdBy = organizer, captureAllowed = true)
            transaction { EventCaptureRequirement.applies(EventStore.getEventOrNull(onSite)) } shouldBe false
            transaction { EventCaptureRequirement.applies(EventStore.getEventOrNull(conference)) } shouldBe true
        }

        test(
            "a GUEST/FRIEND account of a released event needs the capture variant of the guest notice AND the consent; the old notice is refused",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val friend = fx.member(status = MemberStatus.FRIEND)
            val oldNotice =
                ConferenceGuestConsentAcknowledgmentInput(
                    consentVersion = EventGuestNotice.VERSION,
                    consentSha256 = EventGuestNotice.SHA256,
                )
            // consent given, guest notice of the OLD kind ("Es wird nichts aufgezeichnet.") -- must not be accepted
            shouldThrow<ConflictException> { member(event, consent, who = friend, guestConsent = oldNotice, status = MemberStatus.FRIEND) }
            // the capture variant of the guest notice, but no capture consent
            shouldThrow<EventCaptureNoticeRequiredException> {
                member(event, consent = null, who = friend, guestConsent = captureNotice, status = MemberStatus.FRIEND)
            }
            val result = member(event, consent, who = friend, guestConsent = captureNotice, status = MemberStatus.FRIEND)
            result.outcome shouldBe EventOnlineOutcome.JOINED
            val roomId = Uuid.parse(result.token!!.roomId)
            transaction {
                ConferenceGuestConsentAcknowledgmentTable
                    .selectAll()
                    .where { ConferenceGuestConsentAcknowledgmentTable.roomId eq roomId }
                    .single()[ConferenceGuestConsentAcknowledgmentTable.consentVersion]
            } shouldBe EventGuestNoticeCapture.VERSION
            EventCaptureTestState.acknowledgments(event).single()[EventCaptureAcknowledgmentTable.memberId] shouldBe friend
        }

        test(
            "a GUEST/FRIEND account of an event that is NOT released still needs the plain notice, and the capture variant is refused there",
        ) {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val friend = fx.member(status = MemberStatus.FRIEND)
            shouldThrow<ConflictException> { member(event, null, who = friend, guestConsent = captureNotice, status = MemberStatus.FRIEND) }
            member(
                event,
                null,
                who = friend,
                guestConsent =
                    ConferenceGuestConsentAcknowledgmentInput(
                        consentVersion = EventGuestNotice.VERSION,
                        consentSha256 = EventGuestNotice.SHA256,
                    ),
                status = MemberStatus.FRIEND,
            ).outcome shouldBe EventOnlineOutcome.JOINED
        }

        test(
            "a person without an account: refused without the consent and with the plain guest notice, let in with both; the proof names nobody",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            shouldThrow<EventCaptureNoticeRequiredException> { guest(event, consent = null) }
            shouldThrow<EventCaptureNoticeOutdatedException> { guest(event, consent.copy(consentSha256 = "f".repeat(64))) }
            // the page showed the OLD notice: the guest must reload and see the capture variant
            shouldThrow<EventCaptureNoticeRequiredException> { guest(event, consent, noticeVersion = EventGuestNotice.VERSION) }
            EventCaptureTestState.acknowledgments(event).size shouldBe 0
            val response = guest(event, consent)
            response.outcome shouldBe EventOnlineOutcome.JOINED
            val row = EventCaptureTestState.acknowledgments(event).single()
            row[EventCaptureAcknowledgmentTable.memberId] shouldBe null
            val key = row[EventCaptureAcknowledgmentTable.guestKey]!!
            key shouldMatch Regex("[0-9a-f]{64}")
            key shouldBe
                EventCaptureAcknowledgments.guestKeyOf(identity = response.identity!!, serverSecret = EVENT_ONLINE_CONFIG.apiSecret)
            // neither the identity nor the display name is stored
            key shouldNotBe response.identity
            // keyed: NOT the plain SHA-256 of the identity, which anybody could recompute from a registration's ticket hash
            key shouldNotBe
                network.lapis.cloud.server.encounter.ConsentHashing
                    .sha256Hex(response.identity!!)
            row.toString() shouldNotContain "Gast"
            response.captureLive?.recording shouldBe false
        }

        test("the same ticket (a deterministic identity) re-entering writes ONE proof, not two") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val hash = "ab".repeat(32)
            fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = hash)
            val first = guest(event, consent, ticket = hash)
            val second = guest(event, consent, ticket = hash)
            first.outcome shouldBe EventOnlineOutcome.JOINED
            second.identity shouldBe first.identity
            EventCaptureTestState.acknowledgments(event).size shouldBe 1
        }

        test(
            "a person without an account of an event that is NOT released: the plain notice, no proof, no row about the person (V1.9.113)",
        ) {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            shouldThrow<EventCaptureNoticeRequiredException> { guest(event, consent = null) }
            val response = guest(event, consent = null, noticeVersion = EventGuestNotice.VERSION)
            response.outcome shouldBe EventOnlineOutcome.JOINED
            response.captureLive shouldBe null
            EventCaptureTestState.acknowledgments(event).size shouldBe 0
        }

        test(
            "a release granted while a member's room was being created is noticed under the event lock: nobody is let in without the consent",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val gate = CompletableDeferred<Unit>()
            live.createGate = gate
            try {
                val before = live.createCalls.get()
                val result =
                    runBlocking {
                        val joining = async(Dispatchers.IO) { runCatching { member(event, consent = null) } }
                        // the join sits in createRoom; its first look found the event NOT released
                        while (live.createCalls.get() == before) delay(10.milliseconds)
                        EventCaptureTestState.release(eventId = event)
                        gate.complete(Unit)
                        joining.await()
                    }
                (result.exceptionOrNull() is EventCaptureNoticeRequiredException) shouldBe true
            } finally {
                live.createGate = null
            }
            EventCaptureTestState.acknowledgments(event).size shouldBe 0
            // and no participation row of the refused member was left behind
            fx.roomIdsOf(event).forEach { roomId ->
                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId eq roomId }.count()
                } shouldBe 0
            }
        }

        test("the same for a person without an account: a release granted meanwhile cancels the entry and writes nothing") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val gate = CompletableDeferred<Unit>()
            live.createGate = gate
            try {
                val before = live.createCalls.get()
                val result =
                    runBlocking {
                        val joining =
                            async(Dispatchers.IO) { runCatching { guest(event, consent = null, noticeVersion = EventGuestNotice.VERSION) } }
                        while (live.createCalls.get() == before) delay(10.milliseconds)
                        EventCaptureTestState.release(eventId = event)
                        gate.complete(Unit)
                        joining.await()
                    }
                (result.exceptionOrNull() is EventCaptureNoticeRequiredException) shouldBe true
            } finally {
                live.createGate = null
            }
            EventCaptureTestState.acknowledgments(event).size shouldBe 0
        }
    })
