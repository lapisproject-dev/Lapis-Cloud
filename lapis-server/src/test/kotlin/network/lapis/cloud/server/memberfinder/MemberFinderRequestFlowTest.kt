package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.server.dsgvo.MemberFinderPersonalData
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.MemberFinderConsentRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderLimitReachedException
import network.lapis.cloud.shared.rpc.MemberFinderNoteInvalidException
import network.lapis.cloud.shared.rpc.MemberFinderRateLimitedException
import network.lapis.cloud.shared.rpc.MemberFinderRegionNotVisibleException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val PINNED = "2026-10-16T10:00:00Z"

/**
 * V1.9.144 -- the request flow: the neutral answer for the sender (delivered, phantom and "real size below five" look the same; declined, open and
 * phantom read the same in every sender view until the end), the same refusal for every case of somebody else's request, the limits, the notes and the
 * connection after both sides agreed.
 */
class MemberFinderRequestFlowTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        /** Shape of an answer without the random id. */
        fun MemberFinderOutgoingRequestDto.shape() = listOf(purpose, sentOn, expiresOn, state)

        fun rowFrom(sender: Uuid) = data.requests().single { it.fromMemberId == sender }

        test("not consenting, region not visible, purpose outside the own interest: the refusals concern the caller only") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                finderTestApplication {
                    val outsider = data.member(label = "outsider")
                    attempt(member = outsider) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderConsentRequiredException>()
                    val four = data.region(count = 4)
                    data.computeBands()
                    attempt(member = four[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderRegionNotVisibleException>()
                    val meetupOnly =
                        data
                            .member(
                                label = "meetup-only",
                                postalCode = "38501",
                            ).also { data.consent(memberId = it, interest = MemberFinderInterest.MEETUP) }
                    data.computeBands()
                    attempt(member = meetupOnly) { sendRequest(purpose = MemberFinderPurpose.GROUP_FOUNDING, note = null) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("delivered, phantom and 'real size below five' are the same answer; only the stored addressee differs; ids are random v4") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.region(count = 5, postalPrefix = "10", label = "gf", interest = MemberFinderInterest.GROUP_FOUNDING)
                val phantomSender = data.member(label = "phantom-sender", postalCode = "10999").also { data.consent(memberId = it) }
                val five = data.region(count = 5, postalPrefix = "20", label = "five")
                data.computeBands()
                // the published band says five, the real size is four from now on
                transaction {
                    MemberFinderConsentStore.revoke(
                        memberId = five[4],
                        reason = MemberFinderRevokeReason.MEMBER,
                        now = ServerClock.now(),
                    )
                }
                finderTestApplication {
                    val delivered = call(member = six[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = "Hallo") }
                    val phantom = call(member = phantomSender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val below = call(member = five[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    delivered.state shouldBe MemberFinderOutgoingState.PENDING
                    delivered.shape() shouldBe phantom.shape()
                    delivered.shape() shouldBe below.shape()
                    rowFrom(six[0]).toMemberId.shouldNotBeNull()
                    rowFrom(phantomSender).toMemberId.shouldBeNull()
                    rowFrom(five[0]).toMemberId.shouldBeNull()
                    for (dto in listOf(delivered, phantom, below)) {
                        // version nibble of a random UUID is 4 (a v7 id would carry a timestamp)
                        dto.requestId[14] shouldBe '4'
                    }
                }
            }
        }

        test(
            "a delivered-then-declined, a phantom and an open request read the same to their senders until the end; the pending count too",
        ) {
            val clock = MutableTestClock(PINNED)
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.region(count = 5, postalPrefix = "10", label = "gf", interest = MemberFinderInterest.GROUP_FOUNDING)
                val phantomSender = data.member(label = "phantom-sender", postalCode = "10999").also { data.consent(memberId = it) }
                data.computeBands()
                finderTestApplication {
                    val declinedSender = six[0]
                    val openSender = six[1]
                    call(member = declinedSender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val recipient = rowFrom(declinedSender).toMemberId!!
                    val incoming = call(member = recipient) { listIncomingRequests() }
                    incoming shouldHaveSize 1
                    call(
                        member = recipient,
                    ) { respondToRequest(requestId = incoming.single().requestId, accept = false, block = false) }.connection.shouldBeNull()
                    call(member = phantomSender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    call(member = openSender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }

                    for (sender in listOf(declinedSender, phantomSender, openSender)) {
                        val list = call(member = sender) { listOutgoingRequests() }
                        list.single().state shouldBe MemberFinderOutgoingState.PENDING
                        call(member = sender) { getState() }.pendingOutgoing shouldBe 1
                    }
                    clock.advance(31.days)
                    for (sender in listOf(declinedSender, phantomSender, openSender)) {
                        call(member = sender) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.ENDED
                        call(member = sender) { getState() }.pendingOutgoing shouldBe 0
                    }
                }
            }
        }

        test("every refusal about somebody else's request is the same NotFound: unknown, malformed, foreign, decided, expired") {
            val clock = MutableTestClock(PINNED)
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val sender = six[0]
                val x = six[1]
                val x2 = six[2]
                val y = six[3]
                val decided = data.request(from = sender, to = x)
                val stale = data.request(from = sender, to = x2)
                finderTestApplication {
                    call(member = x) { respondToRequest(requestId = decided.toString(), accept = false, block = false) }
                    val answers =
                        listOf(
                            attempt(member = x) { respondToRequest(requestId = Uuid.random().toString(), accept = true, block = false) },
                            attempt(member = x) { respondToRequest(requestId = "not-a-uuid", accept = true, block = false) },
                            attempt(member = y) { respondToRequest(requestId = stale.toString(), accept = true, block = false) },
                            attempt(member = x) { respondToRequest(requestId = decided.toString(), accept = true, block = false) },
                            attempt(member = sender) { respondToRequest(requestId = stale.toString(), accept = true, block = false) },
                        ).map { it.exceptionOrNull() }
                    clock.advance(31.days)
                    val expired =
                        attempt(
                            member = x2,
                        ) { respondToRequest(requestId = stale.toString(), accept = true, block = false) }.exceptionOrNull()
                    (answers + expired).forEach {
                        it.shouldBeInstanceOf<NotFoundException>()
                        (it as NotFoundException).message shouldBe "Not found"
                    }
                    // the stale request was marked EXPIRED by that very answer and the mark committed
                    data.requests().single { it.id.toString() == stale.toString() }.status shouldBe MemberFinderRequestStatus.EXPIRED

                    val withdraws =
                        listOf(
                            attempt(member = sender) { withdrawRequest(Uuid.random().toString()) },
                            attempt(member = sender) { withdrawRequest("garbage") },
                            attempt(member = y) { withdrawRequest(decided.toString()) },
                            // the sender's own request that ended long ago
                            attempt(member = sender) { withdrawRequest(stale.toString()) },
                        ).map { it.exceptionOrNull() }
                    withdraws.forEach {
                        it.shouldBeInstanceOf<NotFoundException>()
                        (it as NotFoundException).message shouldBe "Not found"
                    }
                }
            }
        }

        test("withdrawing: every pending own request (delivered or phantom) becomes WITHDRAWN with the same answer, once") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.region(count = 5, postalPrefix = "10", label = "gf", interest = MemberFinderInterest.GROUP_FOUNDING)
                val phantomSender = data.member(label = "phantom-sender", postalCode = "10999").also { data.consent(memberId = it) }
                data.computeBands()
                finderTestApplication {
                    val delivered = call(member = six[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val phantom = call(member = phantomSender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val recipient = rowFrom(six[0]).toMemberId!!
                    call(member = recipient) { listIncomingRequests() } shouldHaveSize 1
                    call(member = six[0]) { withdrawRequest(delivered.requestId) }
                    call(member = phantomSender) { withdrawRequest(phantom.requestId) }
                    call(member = six[0]) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.WITHDRAWN
                    call(member = phantomSender) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.WITHDRAWN
                    call(member = recipient) { listIncomingRequests() }.shouldBeEmpty()
                    attempt(
                        member = six[0],
                    ) { withdrawRequest(delivered.requestId) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }
        }

        test("withdrawing a stored DECLINED request answers like any other but keeps the addressee's own decision") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val declined = data.request(from = six[0], to = six[1], status = MemberFinderRequestStatus.DECLINED)
                finderTestApplication {
                    call(member = six[0]) { withdrawRequest(declined.toString()) }
                    val row = data.requests().single { it.id == declined }
                    row.status shouldBe MemberFinderRequestStatus.DECLINED
                }
            }
        }

        test(
            "a withdrawn DECLINED, OPEN and phantom request look the same to the sender in every view; the addressee's decision survives",
        ) {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val sender = six[0]
                val declined = data.request(from = sender, to = six[1], status = MemberFinderRequestStatus.DECLINED)
                val open = data.request(from = sender, to = six[2])
                val phantom = data.request(from = sender, to = null)
                finderTestApplication {
                    call(member = sender) { getState() }.pendingOutgoing shouldBe 3
                    for (id in listOf(declined, open, phantom)) {
                        call(member = sender) { withdrawRequest(id.toString()) }
                    }
                    val listed = call(member = sender) { listOutgoingRequests() }
                    listed shouldHaveSize 3
                    listed.forEach { it.state shouldBe MemberFinderOutgoingState.WITHDRAWN }
                    call(member = sender) { getState() }.pendingOutgoing shouldBe 0
                    for (id in listOf(declined, open, phantom)) {
                        attempt(
                            member = sender,
                        ) { withdrawRequest(id.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    }
                    val export = transaction { MemberFinderPersonalData.exportMember(sender) }.toString()
                    export.contains("PENDING") shouldBe false
                    export.contains("DECLINED") shouldBe false
                    data.requests().single { it.id == declined }.status shouldBe MemberFinderRequestStatus.DECLINED
                }
            }
        }

        test("a withdrawn DECLINED, OPEN and phantom request are all deleted at the same moment, seven days after the withdrawal") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val sender = six[0]
                val declined = data.request(from = sender, to = six[1], status = MemberFinderRequestStatus.DECLINED)
                val open = data.request(from = sender, to = six[2])
                val phantom = data.request(from = sender, to = null)
                finderTestApplication {
                    for (id in listOf(declined, open, phantom)) {
                        call(member = sender) { withdrawRequest(id.toString()) }
                    }
                    val stamps = data.requests().map { it.senderWithdrawnAt.shouldNotBeNull() }.toSet()
                    val withdrawnAt = stamps.single()
                    MemberFinderPoller().tick(withdrawnAt.plusDays(6))
                    val remaining = data.requests().map { it.id }.toSet()
                    remaining shouldBe setOf(declined, open, phantom)
                    MemberFinderPoller().tick(withdrawnAt.plusDays(7))
                    data.requests().shouldBeEmpty()
                }
            }
        }

        test("accepting is conditional: a row whose counterpart link was detached concurrently is not turned into ACCEPTED") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val id = data.request(from = six[0], to = six[1])
                transaction {
                    MemberFinderRequestTable.update({ MemberFinderRequestTable.id eq id }) { it[toMemberId] = null }
                }
                transaction {
                    MemberFinderRequestStore.acceptOpen(id = id, toMemberId = six[1], decidedAt = ServerClock.now())
                } shouldBe 0
                data.requests().single { it.id == id }.status shouldBe MemberFinderRequestStatus.OPEN
                val open = data.request(from = six[2], to = six[3])
                transaction {
                    MemberFinderRequestStore.acceptOpen(id = open, toMemberId = six[3], decidedAt = ServerClock.now())
                } shouldBe 1
            }
        }

        test("the sixth pending request is refused even when all five are phantoms; a withdrawal frees a slot") {
            val clock = MutableTestClock(PINNED)
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                data.region(count = 5, postalPrefix = "10", label = "gf", interest = MemberFinderInterest.GROUP_FOUNDING)
                val sender = data.member(label = "sender", postalCode = "10999").also { data.consent(memberId = it) }
                data.computeBands()
                val limiter = MemberFinderRateLimiter(now = { clock.now() })
                finderTestApplication(rateLimiter = limiter) {
                    repeat(3) { call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) } }
                    // the hourly limit of three
                    attempt(
                        member = sender,
                    ) {
                        sendRequest(
                            purpose = MemberFinderPurpose.MEETUP,
                            note = null,
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<MemberFinderRateLimitedException>()
                    clock.advance(61.minutes)
                    repeat(2) { call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) } }
                    call(member = sender) { getState() }.pendingOutgoing shouldBe 5
                    attempt(
                        member = sender,
                    ) {
                        sendRequest(
                            purpose = MemberFinderPurpose.MEETUP,
                            note = null,
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<MemberFinderLimitReachedException>()
                    val first = call(member = sender) { listOutgoingRequests() }.first()
                    call(member = sender) { withdrawRequest(first.requestId) }
                    // the refused attempt above counted against the hourly budget too (three in this hour): wait for a fresh hour
                    clock.advance(61.minutes)
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }.state shouldBe
                        MemberFinderOutgoingState.PENDING
                }
            }
        }

        test("the daily limit of ten sends stops the eleventh even when each one is withdrawn at once") {
            val clock = MutableTestClock(PINNED)
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                data.region(count = 5, postalPrefix = "10", label = "gf", interest = MemberFinderInterest.GROUP_FOUNDING)
                val sender = data.member(label = "sender", postalCode = "10999").also { data.consent(memberId = it) }
                data.computeBands()
                val limiter = MemberFinderRateLimiter(now = { clock.now() })
                finderTestApplication(rateLimiter = limiter) {
                    for (i in 1..10) {
                        val dto = call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                        call(member = sender) { withdrawRequest(dto.requestId) }
                        if (i % 3 == 0) clock.advance(61.minutes)
                    }
                    attempt(
                        member = sender,
                    ) {
                        sendRequest(
                            purpose = MemberFinderPurpose.MEETUP,
                            note = null,
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<MemberFinderRateLimitedException>()
                    // a day later it works again
                    clock.advance(25.hours)
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }.state shouldBe
                        MemberFinderOutgoingState.PENDING
                }
            }
        }

        test("a full inbox of three turns the next request into a phantom; once the addressee answers one, the next is delivered") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val sender =
                    data
                        .member(
                            label = "sender",
                            postalCode = "38001",
                        ).also { data.consent(memberId = it, interest = MemberFinderInterest.GROUP_FOUNDING) }
                val t =
                    data.member(label = "target", postalCode = "38002").also {
                        data.consent(memberId = it, interest = MemberFinderInterest.GROUP_FOUNDING)
                    }
                val fillers =
                    (1..4).map { i ->
                        data
                            .member(
                                label = "f$i",
                                postalCode = "3801$i",
                            ).also { data.consent(memberId = it, interest = MemberFinderInterest.MEETUP) }
                    }
                val inbox = fillers.take(3).map { data.request(from = it, to = t) }
                data.computeBands()
                finderTestApplication {
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.GROUP_FOUNDING, note = null) }
                    rowFrom(sender).toMemberId.shouldBeNull()
                    call(member = t) { listIncomingRequests() } shouldHaveSize 3
                    call(member = t) { respondToRequest(requestId = inbox[0].toString(), accept = false, block = false) }
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.GROUP_FOUNDING, note = null) }
                    data.requests().filter { it.fromMemberId == sender }.count { it.toMemberId == t } shouldBe 1
                }
            }
        }

        test(
            "a note of 201 characters is refused; control and bidirectional characters are removed; the note reaches the addressee, never the sender's views",
        ) {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                finderTestApplication {
                    attempt(member = six[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = "a".repeat(201)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderNoteInvalidException>()
                    call(member = six[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = "  Hallo‮ Welt\n! ") }
                    val recipient = rowFrom(six[0]).toMemberId!!
                    call(member = recipient) { listIncomingRequests() }.single().note shouldBe "Hallo Welt !"
                    call(member = six[1]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = "   ") }
                    data
                        .requests()
                        .single { it.fromMemberId == six[1] }
                        .note
                        .shouldBeNull()
                }
            }
        }

        test("accepting creates the connection with names on both sides, then messages, block and unblock work") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                finderTestApplication {
                    val sender = six[0]
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val x = rowFrom(sender).toMemberId!!
                    val requestId = call(member = x) { listIncomingRequests() }.single().requestId
                    val connection =
                        call(
                            member = x,
                        ) { respondToRequest(requestId = requestId, accept = true, block = false) }.connection.shouldNotBeNull()
                    connection.otherDisplayName shouldBe "Finder-Test six1"
                    connection.endsOn shouldBe connection.connectedOn.plus(DatePeriod(days = 90))
                    call(member = x) { listIncomingRequests() }.shouldBeEmpty()
                    call(member = sender) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.CONNECTED
                    call(member = sender) { listConnections() }.single().otherDisplayName shouldBe data.nameOf(x)
                    call(member = x) { listConnections() }.single().connectionId shouldBe requestId

                    // messages through the existing direct-message mechanism, both ways; a stranger gets the same NotFound
                    call(member = x) { contactConnection(connectionId = requestId, message = "Hallo zusammen") }.shouldNotBeNull()
                    call(member = sender) { contactConnection(connectionId = requestId, message = "Hallo zurück") }.shouldNotBeNull()
                    attempt(member = six[5]) {
                        contactConnection(connectionId = requestId, message = "Hallo")
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()

                    // block the connection: the connection is gone, the block is listed without a name, the pair is never drawn again
                    call(member = x) { blockConnection(requestId) }
                    call(member = x) { listConnections() }.shouldBeEmpty()
                    val blocks = call(member = x) { listBlocks() }
                    blocks shouldHaveSize 1
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    data.requests().filter { it.fromMemberId == sender }.none { it.toMemberId == x } shouldBe true
                    call(member = x) { unblock(blocks.single().blockId) }
                    call(member = x) { listBlocks() }.shouldBeEmpty()
                    attempt(member = x) { unblock(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    attempt(member = sender) { unblock(blocks.single().blockId) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }
        }

        test("declining with a block blocks the unnamed sender; the sender's view is unchanged") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                finderTestApplication {
                    val sender = six[0]
                    call(member = sender) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    val x = rowFrom(sender).toMemberId!!
                    val requestId = call(member = x) { listIncomingRequests() }.single().requestId
                    call(member = x) { respondToRequest(requestId = requestId, accept = false, block = true) }.connection.shouldBeNull()
                    data.blockCount() shouldBe 1
                    call(member = sender) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.PENDING
                }
            }
        }

        test("the limit of the 240 reads per hour is a refusal about the caller's own behaviour") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val limiter = MemberFinderRateLimiter()
                finderTestApplication(rateLimiter = limiter) {
                    val m = data.member(label = "reader")
                    repeat(240) { call(member = m) { listBlocks() } }
                    attempt(member = m) { listBlocks() }.exceptionOrNull().shouldBeInstanceOf<MemberFinderRateLimitedException>()
                    // opting out is never throttled
                    call(member = m) { optOut() }.optedIn shouldBe false
                }
            }
        }

        test("server clock: the request's expiry date is thirty days after the send date") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                finderTestApplication {
                    val dto = call(member = six[0]) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) }
                    dto.expiresOn shouldBe dto.sentOn.plus(DatePeriod(days = 30))
                    rowFrom(six[0]).expiresAt shouldBe ServerClock.now().plusDays(30)
                }
            }
        }
    })
