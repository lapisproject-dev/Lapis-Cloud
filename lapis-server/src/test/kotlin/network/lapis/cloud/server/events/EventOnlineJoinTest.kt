package network.lapis.cloud.server.events

import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- [EventOnlineJoin], the part of "take part online" that mints tokens: the member path (room provisioned on the first join,
 * participation recorded, token pinned to ONE room), the guest path of a GUEST/FRIEND account (needs the notice, leaves a consent row), the
 * path without an account (random `evg-` identity, name with the server-side suffix, short token, NO row about the person), the full-room
 * answer, the time-of-check/time-of-use gap (a registration cancelled while the token is being minted), and the moderator's guest list.
 */
class EventOnlineJoinTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var organizer: Uuid
        lateinit var join: EventOnlineJoin
        lateinit var provisioner: EventConferenceProvisioner

        val open = Instant.parse("2030-06-15T22:30:00Z")

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            val audio = ConferenceAudioGrantState()
            provisioner =
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
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        fun claims(jwt: String): Map<String, Any?> = SignedJWT.parse(jwt).jwtClaimsSet.claims

        @Suppress("UNCHECKED_CAST")
        fun video(jwt: String): Map<String, Any?> = claims(jwt)["video"] as Map<String, Any?>

        fun participationCount(roomId: Uuid): Int =
            transaction {
                ConferenceParticipationTable
                    .selectAll()
                    .where { ConferenceParticipationTable.roomId eq roomId }
                    .count()
                    .toInt()
            }

        test("a member joins: the room is created on the first join, the token is pinned to that room, the participation is recorded") {
            val event = fx.event(createdBy = organizer)
            val member = fx.member(name = "Mia")
            val result =
                runBlocking { join.joinMember(eventId = event, current = fx.current(memberId = member), guestConsent = null, now = open) }
            result.outcome shouldBe EventOnlineOutcome.JOINED
            val token = result.token!!
            token.eventId shouldBe event.toString()
            token.role shouldBe ConferenceRole.PARTICIPANT
            token.identity shouldBe member.toString()
            live.rooms.containsKey(token.livekitRoomName) shouldBe true
            val grant = video(token.token)
            grant["room"] shouldBe token.livekitRoomName
            grant["roomJoin"] shouldBe true
            grant["canPublish"] shouldBe true
            // never an admin / room-creation grant
            grant.containsKey("roomAdmin") shouldBe false
            grant.containsKey("roomCreate") shouldBe false
            grant.containsKey("roomList") shouldBe false
            grant["canUpdateOwnMetadata"] shouldBe false
            participationCount(Uuid.parse(token.roomId)) shouldBe 1
            // the organizer is the moderator of the same room
            val organizerToken =
                runBlocking {
                    join.joinMember(eventId = event, current = fx.current(memberId = organizer), guestConsent = null, now = open)
                }.token!!
            organizerToken.role shouldBe ConferenceRole.MODERATOR
            organizerToken.roomId shouldBe token.roomId
        }

        test("a member's token lives at most until the window closes (and the minimum of a minute)") {
            val event = fx.event(createdBy = organizer)
            val member = fx.member()
            // 24 minutes before the window ends: the configured 4 h would outlive the event
            val late = Instant.parse("2030-06-16T00:36:00Z")
            val result =
                runBlocking { join.joinMember(eventId = event, current = fx.current(memberId = member), guestConsent = null, now = late) }
            val expires =
                SignedJWT
                    .parse(result.token!!.token)
                    .jwtClaimsSet.expirationTime
                    .toInstant()
            (expires.epochSecond - late.epochSeconds) shouldBe 24 * 60L
        }

        test("not yet / over / not allowed: typed outcomes, the opening time only for NOT_YET, no room is created for them") {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val registered = fx.member()
            fx.registration(eventId = event, memberId = registered)
            val stranger = fx.member()
            val before = live.createCalls.get()
            val early =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = registered),
                        guestConsent = null,
                        now = Instant.parse("2030-06-15T20:00:00Z"),
                    )
                }
            early.outcome shouldBe EventOnlineOutcome.NOT_YET
            early.opensAt shouldBe LocalDateTime(2030, 6, 15, 17, 45)
            early.token shouldBe null
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = registered),
                    guestConsent = null,
                    now = Instant.parse("2030-06-16T03:00:00Z"),
                )
            }.outcome shouldBe EventOnlineOutcome.ENDED
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = stranger),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.FORBIDDEN
            runBlocking {
                join.joinMember(
                    eventId = Uuid.random(),
                    current = fx.current(memberId = stranger),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.FORBIDDEN
            live.createCalls.get() shouldBe before
        }

        test("a GUEST/FRIEND account needs the current notice; with it it joins as a guest and a consent row is kept") {
            val event = fx.event(createdBy = organizer)
            val friend = fx.member(status = MemberStatus.FRIEND)
            val current = fx.current(memberId = friend, status = MemberStatus.FRIEND)
            shouldThrow<ConflictException> {
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = current,
                        guestConsent = null,
                        now = open,
                    )
                }
            }
            shouldThrow<ConflictException> {
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = current,
                        guestConsent =
                            ConferenceGuestConsentAcknowledgmentInput(
                                consentVersion = EventGuestNotice.VERSION,
                                consentSha256 = "0".repeat(64),
                            ),
                        now = open,
                    )
                }
            }
            val result =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = current,
                        guestConsent =
                            ConferenceGuestConsentAcknowledgmentInput(
                                consentVersion = EventGuestNotice.VERSION,
                                consentSha256 = EventGuestNotice.SHA256,
                            ),
                        now = open,
                    )
                }
            result.outcome shouldBe EventOnlineOutcome.JOINED
            val roomId = Uuid.parse(result.token!!.roomId)
            transaction {
                val rows =
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where {
                            ConferenceGuestConsentAcknowledgmentTable.roomId eq
                                roomId
                        }.toList()
                rows.size shouldBe 1
                rows.single()[ConferenceGuestConsentAcknowledgmentTable.consentVersion] shouldBe EventGuestNotice.VERSION
            }
            // the TTL is the SHORT guest lifetime (15 min), not the member's 4 h
            val expires =
                SignedJWT
                    .parse(result.token!!.token)
                    .jwtClaimsSet.expirationTime
                    .toInstant()
                    .epochSecond
            (expires - open.epochSeconds) shouldBe 15 * 60L
        }

        test(
            "without an account: a random evg- identity, the name carries the server-side suffix, a short pinned token, and NO row about the person",
        ) {
            val event = fx.event(createdBy = organizer)
            // make the room exist first, so its participation rows can be counted
            val first =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = fx.member()),
                        guestConsent = null,
                        now = open,
                    )
                }
            val roomId = Uuid.parse(first.token!!.roomId)
            val participationBefore = participationCount(roomId)
            val consentBefore =
                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where {
                            ConferenceGuestConsentAcknowledgmentTable.roomId eq
                                roomId
                        }.count()
                }
            val response =
                runBlocking { join.joinAccountless(eventId = event, displayName = "Vorstand (Gast)", ticketCodeSha256 = null, now = open) }
            response.outcome shouldBe EventOnlineOutcome.JOINED
            response.identity!! shouldStartWith EVENT_GUEST_IDENTITY_PREFIX
            response.displayName!! shouldEndWith " (Gast)"
            val jwt = SignedJWT.parse(response.token!!).jwtClaimsSet
            jwt.subject shouldBe response.identity
            (jwt.expirationTime.toInstant().epochSecond - open.epochSeconds) shouldBe 15 * 60L
            val grant = video(response.token!!)
            grant["room"] shouldBe first.token!!.livekitRoomName
            grant.containsKey("roomAdmin") shouldBe false
            grant["canUpdateOwnMetadata"] shouldBe false
            // no participation, no consent row: nothing identifies the person
            participationCount(roomId) shouldBe participationBefore
            transaction {
                ConferenceGuestConsentAcknowledgmentTable
                    .selectAll()
                    .where { ConferenceGuestConsentAcknowledgmentTable.roomId eq roomId }
                    .count()
            } shouldBe consentBefore
            // two guests never share an identity
            val second =
                runBlocking { join.joinAccountless(eventId = event, displayName = "X (Gast)", ticketCodeSha256 = null, now = open) }
            second.identity shouldNotBe response.identity
        }

        test(
            "without an account but with a deadline: a refusal looks like FORBIDDEN and creates no room; the ticket of a confirmed registration admits",
        ) {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val hash = sha256Hex("TICKETCODE0123456".toByteArray(Charsets.US_ASCII))
            fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = hash)
            val before = live.createCalls.get()
            runBlocking {
                join.joinAccountless(
                    eventId = event,
                    displayName = "A (Gast)",
                    ticketCodeSha256 = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.FORBIDDEN
            live.createCalls.get() shouldBe before
            runBlocking {
                join.joinAccountless(
                    eventId = event,
                    displayName = "A (Gast)",
                    ticketCodeSha256 = hash,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.JOINED
        }

        test("a members-only, a draft and an encounter event refuse a person without an account the same way") {
            val membersOnly = fx.event(createdBy = organizer, visibility = network.lapis.cloud.shared.domain.EventVisibility.MEMBERS_ONLY)
            val space = fx.space()
            val encounter = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = space)
            listOf(membersOnly, encounter, Uuid.random()).forEach {
                runBlocking {
                    join.joinAccountless(
                        eventId = it,
                        displayName = "A (Gast)",
                        ticketCodeSha256 = null,
                        now = open,
                    )
                }.outcome shouldBe
                    EventOnlineOutcome.FORBIDDEN
            }
        }

        test("account-less guests are capped per room and keep seats free for members and moderators") {
            val event = fx.event(createdBy = organizer, capacity = 8)
            val member = fx.member()
            val first =
                runBlocking {
                    join.joinMember(eventId = event, current = fx.current(memberId = member), guestConsent = null, now = open)
                }.token!!
            val room = first.livekitRoomName
            // 8 seats, a quarter (2) stays reserved: guests are refused once 6 people (member + 5 guests) are in
            live.seedLive(room = room, identity = member.toString())
            repeat(4) { live.seedLive(room = room, identity = "evg-${Uuid.random()}") }
            runBlocking { join.joinAccountless(eventId = event, displayName = "A (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.JOINED
            live.seedLive(room = room, identity = "evg-${Uuid.random()}")
            runBlocking { join.joinAccountless(eventId = event, displayName = "B (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.FULL
            // members and the moderator still get in
            runBlocking {
                join.joinMember(eventId = event, current = fx.current(memberId = fx.member()), guestConsent = null, now = open)
            }.outcome shouldBe EventOnlineOutcome.JOINED
            runBlocking {
                join.joinMember(eventId = event, current = fx.current(memberId = organizer), guestConsent = null, now = open)
            }.outcome shouldBe EventOnlineOutcome.JOINED
        }

        test("minted but not yet connected guest tokens count against the guest cap") {
            val event = fx.event(createdBy = organizer, capacity = 8)
            val room =
                runBlocking {
                    join.joinMember(eventId = event, current = fx.current(memberId = organizer), guestConsent = null, now = open)
                }.token!!.livekitRoomName
            live.seedLive(room = room, identity = organizer.toString())
            // 8 seats, 2 reserved: 6 seats for guests and members together, 1 taken -> 5 guest tokens, the 6th is refused (nobody connected)
            repeat(5) {
                runBlocking { join.joinAccountless(eventId = event, displayName = "G$it (Gast)", ticketCodeSha256 = null, now = open) }
                    .outcome shouldBe EventOnlineOutcome.JOINED
            }
            runBlocking { join.joinAccountless(eventId = event, displayName = "X (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.FULL
            // members still get in
            runBlocking {
                join.joinMember(eventId = event, current = fx.current(memberId = fx.member()), guestConsent = null, now = open)
            }.outcome shouldBe EventOnlineOutcome.JOINED
        }

        test("connecting and disconnecting minted guest tokens does not free their seats (token stays valid until it expires)") {
            val event = fx.event(createdBy = organizer, capacity = 8)
            val room =
                runBlocking {
                    join.joinMember(eventId = event, current = fx.current(memberId = organizer), guestConsent = null, now = open)
                }.token!!.livekitRoomName
            live.seedLive(room = room, identity = organizer.toString())
            val identities =
                (0 until 5).map {
                    runBlocking { join.joinAccountless(eventId = event, displayName = "G$it (Gast)", ticketCodeSha256 = null, now = open) }
                        .also { r -> r.outcome shouldBe EventOnlineOutcome.JOINED }
                        .identity!!
                }
            // every minted guest connects; the next request used to drop them from the ledger
            identities.forEach { live.seedLive(room = room, identity = it) }
            runBlocking { join.joinAccountless(eventId = event, displayName = "X (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.FULL
            // everybody disconnects: the tokens are still valid, so the seats must stay taken
            live.participants[room]?.removeIf { it.identity in identities }
            runBlocking { join.joinAccountless(eventId = event, displayName = "Y (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.FULL
        }

        test("account-less guests never exceed the non-member ceiling of the room") {
            val event = fx.event(createdBy = organizer, capacity = 1000)
            val room =
                runBlocking {
                    join.joinMember(eventId = event, current = fx.current(memberId = organizer), guestConsent = null, now = open)
                }.token!!.livekitRoomName
            repeat(EVENT_ONLINE_CONFIG.maxNonMemberParticipants) { live.seedLive(room = room, identity = "evg-${Uuid.random()}") }
            runBlocking { join.joinAccountless(eventId = event, displayName = "A (Gast)", ticketCodeSha256 = null, now = open) }
                .outcome shouldBe EventOnlineOutcome.FULL
        }

        test("one ticket admits one live guest: the identity is stable per ticket, so a second use replaces the first connection") {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val hash = sha256Hex("TICKETSHARE0000001".toByteArray(Charsets.US_ASCII))
            val otherHash = sha256Hex("TICKETSHARE0000002".toByteArray(Charsets.US_ASCII))
            fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = hash)
            fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = otherHash)
            val a = runBlocking { join.joinAccountless(eventId = event, displayName = "A (Gast)", ticketCodeSha256 = hash, now = open) }
            val b = runBlocking { join.joinAccountless(eventId = event, displayName = "B (Gast)", ticketCodeSha256 = hash, now = open) }
            val c =
                runBlocking { join.joinAccountless(eventId = event, displayName = "C (Gast)", ticketCodeSha256 = otherHash, now = open) }
            a.identity shouldBe b.identity
            a.identity!! shouldStartWith "evg-"
            c.identity shouldNotBe a.identity
            // the identity never reveals the ticket hash
            (a.identity!!.contains(hash)) shouldBe false
        }

        test("a full room: FULL for everybody but a moderator (LiveKit enforces the ceiling as well)") {
            val event = fx.event(createdBy = organizer, capacity = 2)
            val a = fx.member()
            val b = fx.member()
            val first =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = a),
                        guestConsent = null,
                        now = open,
                    )
                }.token!!
            live.seedLive(room = first.livekitRoomName, identity = a.toString())
            live.seedLive(room = first.livekitRoomName, identity = "someone-else")
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = b),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.FULL
            runBlocking {
                join.joinAccountless(
                    eventId = event,
                    displayName = "A (Gast)",
                    ticketCodeSha256 = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.FULL
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = organizer),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.JOINED
        }

        test("LiveKit down: UNAVAILABLE, never an exception; disabled conferencing: UNAVAILABLE as well") {
            val event = fx.event(createdBy = organizer)
            val member = fx.member()
            live.failCreate = true
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = member),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.UNAVAILABLE
            runBlocking {
                join.joinAccountless(
                    eventId = event,
                    displayName = "A (Gast)",
                    ticketCodeSha256 = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.UNAVAILABLE
            live.failCreate = false
            runBlocking {
                join.joinMember(
                    eventId = event,
                    current = fx.current(memberId = member),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.JOINED
        }

        test("encounter space: a member is sent on with the space id (no room is created); an archived space is unavailable") {
            val space = fx.space()
            val archived = fx.space(archived = true)
            val event = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = space)
            val gone = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = archived)
            val member = fx.member()
            val before = live.createCalls.get()
            val result =
                runBlocking { join.joinMember(eventId = event, current = fx.current(memberId = member), guestConsent = null, now = open) }
            result.outcome shouldBe EventOnlineOutcome.JOINED
            result.encounterSpaceId shouldBe space.toString()
            result.token shouldBe null
            runBlocking {
                join.joinMember(
                    eventId = gone,
                    current = fx.current(memberId = member),
                    guestConsent = null,
                    now = open,
                )
            }.outcome shouldBe
                EventOnlineOutcome.UNAVAILABLE
            live.createCalls.get() shouldBe before
        }

        test("time of check / time of use: a registration cancelled while the room is being created yields no token and no participation") {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val member = fx.member()
            val registration = fx.registration(eventId = event, memberId = member)
            live.createGate = CompletableDeferred()
            val result =
                runBlocking {
                    val joining =
                        async(
                            Dispatchers.IO,
                        ) { join.joinMember(eventId = event, current = fx.current(memberId = member), guestConsent = null, now = open) }
                    delay(150.milliseconds)
                    // the registration is cancelled while LiveKit is still creating the room
                    transaction { EventRegistrationTable.deleteWhere { EventRegistrationTable.id eq registration } }
                    live.createGate!!.complete(Unit)
                    live.createGate = null
                    joining.await()
                }
            result.outcome shouldBe EventOnlineOutcome.FORBIDDEN
            result.token shouldBe null
            fx.roomIdsOf(event).forEach { participationCount(it) shouldBe 0 }
        }

        test("the moderator's list shows only the account-less guests (evg-), remove refuses any other identity") {
            val event = fx.event(createdBy = organizer)
            val member = fx.member()
            val room =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = member),
                        guestConsent = null,
                        now = open,
                    )
                }.token!!
            live.seedLive(room = room.livekitRoomName, identity = member.toString(), name = "Mia")
            live.seedLive(room = room.livekitRoomName, identity = "evg-aaaa", name = "Gast A (Gast)")
            live.seedLive(room = room.livekitRoomName, identity = "evg-bbbb", name = "Gast B (Gast)")
            runBlocking { join.listGuests(event) }.map { it.identity } shouldContainExactly listOf("evg-aaaa", "evg-bbbb")
            shouldThrow<BadRequestException> { runBlocking { join.removeGuest(eventId = event, identity = member.toString()) } }
            shouldThrow<BadRequestException> { runBlocking { join.removeGuest(eventId = event, identity = "") } }
            runBlocking { join.removeGuest(eventId = event, identity = "evg-aaaa") }
            live.removed.last() shouldBe (room.livekitRoomName to "evg-aaaa")
            runBlocking { join.listGuests(event) }.map { it.identity } shouldContainExactly listOf("evg-bbbb")
            // an event without a conference has no guests
            runBlocking { join.listGuests(fx.event(createdBy = organizer)) }.shouldBeEmpty()
        }

        test("guest names: NFC, control and bidi characters removed, whitespace collapsed, 1..60 characters, emoji kept") {
            EventOnlineJoin.normalizeGuestName("  Anna   Müller  ") shouldBe "Anna Müller"
            // a decomposed "e" + combining acute becomes the precomposed character
            EventOnlineJoin.normalizeGuestName("Anné") shouldBe "Anné"
            // a right-to-left override and a zero-width space are format characters: gone
            EventOnlineJoin.normalizeGuestName("Evil‮gnp.exe") shouldBe "Evilgnp.exe"
            EventOnlineJoin.normalizeGuestName("A\u0000B\u0007C") shouldBe "ABC"
            EventOnlineJoin.normalizeGuestName("Zeile1\nZeile2\tX") shouldBe "Zeile1 Zeile2 X"
            EventOnlineJoin.normalizeGuestName("   ​  ") shouldBe null
            EventOnlineJoin.normalizeGuestName("") shouldBe null
            EventOnlineJoin.normalizeGuestName("x".repeat(60)) shouldBe "x".repeat(60)
            EventOnlineJoin.normalizeGuestName("x".repeat(61)) shouldBe null
            EventOnlineJoin.normalizeGuestName("Jo 😀") shouldBe "Jo 😀"
        }

        test("the notice of a GUEST/FRIEND account is the same constants the shared module pins") {
            EventGuestNotice.SHA256.length shouldBe 64
            sha256Hex(EventGuestNotice.TEXT.toByteArray(Charsets.UTF_8)) shouldBe EventGuestNotice.SHA256
        }

        test("an ADMIN who is not the organizer is a moderator of the event room") {
            val event = fx.event(createdBy = organizer)
            val admin = fx.member(role = AccountRole.ADMIN)
            val token =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = admin, role = AccountRole.ADMIN),
                        guestConsent = null,
                        now = open,
                    )
                }.token!!
            token.role shouldBe ConferenceRole.MODERATOR
            transaction {
                ConferenceRoomTable
                    .selectAll()
                    .where {
                        ConferenceRoomTable.id eq Uuid.parse(token.roomId)
                    }.single()[ConferenceRoomTable.createdByMemberId] shouldBe
                    organizer
            }
        }
    })
