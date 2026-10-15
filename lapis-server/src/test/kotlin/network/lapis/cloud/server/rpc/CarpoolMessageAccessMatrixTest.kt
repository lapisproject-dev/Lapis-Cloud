package network.lapis.cloud.server.rpc

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.carpool.CarpoolFriendConfig
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.CarpoolMessageSide
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/**
 * V1.9.139 -- who may use the ride messages: exactly the driver and the person of a CONFIRMED participation, and nobody else, with ONE constant
 * not-found answer for everything that is not that (no oracle for unknown ids, other people's rides, or the state of a ride).
 */
class CarpoolMessageAccessMatrixTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        /** Runs all three methods as [who] on [pid] and returns the failures (class + message) -- `null` entries are successes. */
        suspend fun CarpoolApp.allThree(
            who: Uuid,
            pid: String,
        ): List<Throwable?> =
            listOf(
                attempt(member = who) { sendCarpoolMessage(participationId = pid, body = "Hallo") }.exceptionOrNull(),
                attempt(member = who) { carpoolMessages(participationId = pid, beforeId = null) }.exceptionOrNull(),
                attempt(member = who) { endCarpoolThread(pid) }.exceptionOrNull(),
            )

        test("the driver and the confirmed person (ACTIVE, GUEST and FRIEND alike) may send, read and end") {
            carpoolTestApplication {
                listOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.FRIEND).forEach { status ->
                    withClue(clue = status.name) {
                        val driver = data.member(label = "am-d-$status")
                        val person = data.member(label = "am-p-$status", status = status)
                        val offer = data.posting(author = driver)
                        val pid =
                            data
                                .participation(
                                    postingId = offer,
                                    personId = person,
                                    status = CarpoolParticipationStatus.CONFIRMED,
                                ).toString()

                        val sent = call(member = person) { sendCarpoolMessage(participationId = pid, body = "Hallo vom Mitfahrer $status") }
                        sent.items.single().mine shouldBe true
                        sent.writable shouldBe true
                        val read = call(member = driver) { carpoolMessages(participationId = pid, beforeId = null) }
                        read.items.single().mine shouldBe false
                        read.items.single().senderLabel shouldBe "Carpool-Test am-p-$status"
                        read.items.single().body shouldBe "Hallo vom Mitfahrer $status"
                        call(member = driver) { sendCarpoolMessage(participationId = pid, body = "Antwort") }.items.size shouldBe 2
                        val ended = call(member = driver) { endCarpoolThread(pid) }
                        ended.ended shouldBe true
                        ended.endedByMe shouldBe true
                        call(member = person) { carpoolMessages(participationId = pid, beforeId = null) }.endedByMe shouldBe false
                    }
                }
            }
        }

        test("an OPEN, DECLINED or WITHDRAWN participation gives the same not-found answer for all three methods") {
            carpoolTestApplication {
                val driver = data.member(label = "am2-driver")
                val failures = mutableListOf<Throwable?>()
                listOf(
                    CarpoolParticipationStatus.OPEN,
                    CarpoolParticipationStatus.DECLINED,
                    CarpoolParticipationStatus.WITHDRAWN,
                ).forEach { status ->
                    val person = data.member(label = "am2-p-$status", status = MemberStatus.GUEST)
                    val offer = data.posting(author = driver)
                    val pid = data.participation(postingId = offer, personId = person, status = status).toString()
                    failures += allThree(who = person, pid = pid)
                    failures += allThree(who = driver, pid = pid)
                }
                failures.forEach { it.shouldBeInstanceOf<NotFoundException>() }
                failures.map { it!!.message }.toSet() shouldBe setOf(CarpoolParticipations.NOT_FOUND_MESSAGE)
            }
        }

        test("a third person -- ACTIVE, GUEST, FRIEND, even one confirmed on the same offer -- gets not-found") {
            carpoolTestApplication {
                val driver = data.member(label = "am3-driver")
                val person = data.member(label = "am3-person", status = MemberStatus.GUEST)
                val offer = data.posting(author = driver, seats = 4)
                val pid = data.participation(postingId = offer, personId = person, status = CarpoolParticipationStatus.CONFIRMED).toString()
                call(member = person) { sendCarpoolMessage(participationId = pid, body = "Privat") }
                val strangers =
                    listOf(
                        data.member(label = "am3-active"),
                        data.member(label = "am3-guest", status = MemberStatus.GUEST),
                        data.member(label = "am3-friend", status = MemberStatus.FRIEND),
                        data.member(label = "am3-copassenger", status = MemberStatus.GUEST).also {
                            data.participation(postingId = offer, personId = it, status = CarpoolParticipationStatus.CONFIRMED)
                        },
                    )
                strangers.forEach { stranger ->
                    allThree(who = stranger, pid = pid).forEach {
                        it.shouldBeInstanceOf<NotFoundException>()
                        it.message shouldBe CarpoolParticipations.NOT_FOUND_MESSAGE
                    }
                }
                // ... and nothing a stranger tried changed the history.
                call(member = driver) { carpoolMessages(participationId = pid, beforeId = null) }.items.single().body shouldBe "Privat"
            }
        }

        test("unknown, malformed and deleted ids and a foreign cursor are one and the same answer (no oracle)") {
            carpoolTestApplication {
                val driver = data.member(label = "am4-driver")
                val person = data.member(label = "am4-person", status = MemberStatus.GUEST)
                val other = data.member(label = "am4-other", status = MemberStatus.GUEST)
                val offer = data.posting(author = driver)
                val otherOffer = data.posting(author = driver)
                val pid = data.participation(postingId = offer, personId = person, status = CarpoolParticipationStatus.CONFIRMED).toString()
                val otherPid = data.participation(postingId = otherOffer, personId = other, status = CarpoolParticipationStatus.CONFIRMED)
                val foreignMessage = data.message(participationId = otherPid, side = CarpoolMessageSide.PASSENGER, body = "fremd")
                // A cursor of somebody else's history must be not-found (checked BEFORE that history is deleted with its offer).
                val foreignCursor =
                    attempt(member = person) { carpoolMessages(participationId = pid, beforeId = foreignMessage.toString()) }
                call(member = driver) { deletePosting(otherOffer.toString()) }

                val answers =
                    listOf(
                        foreignCursor,
                        attempt(member = person) { carpoolMessages(participationId = Uuid.random().toString(), beforeId = null) },
                        attempt(member = person) { carpoolMessages(participationId = "not-a-uuid", beforeId = null) },
                        attempt(member = person) { sendCarpoolMessage(participationId = "../x", body = "Hallo") },
                        attempt(member = person) { endCarpoolThread("") },
                        // the ride of a deleted offer
                        attempt(member = other) { carpoolMessages(participationId = otherPid.toString(), beforeId = null) },
                        attempt(member = person) { carpoolMessages(participationId = pid, beforeId = "garbage") },
                    ).map { it.exceptionOrNull() }
                answers.forEach { it.shouldBeInstanceOf<NotFoundException>() }
                answers.map { it!!.message }.toSet() shouldBe setOf(CarpoolParticipations.NOT_FOUND_MESSAGE)
            }
        }

        test("with the e-mail duty on, the driver loses the access to the history of an unverified FRIEND") {
            val previous = CarpoolFriendConfig.provider
            try {
                carpoolTestApplication {
                    val driver = data.member(label = "am5-driver")
                    val friend = data.member(label = "am5-friend", status = MemberStatus.FRIEND, emailVerified = false)
                    val offer = data.posting(author = driver)
                    val pid =
                        data
                            .participation(
                                postingId = offer,
                                personId = friend,
                                status = CarpoolParticipationStatus.CONFIRMED,
                            ).toString()
                    CarpoolFriendConfig.provider = {
                        FriendRegistrationConfig.load { key -> if (key == "LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION") "true" else null }
                    }
                    // The friend's own refusal is the passenger guard (environment-based, covered by MembershipGuardsTest); what is pinned HERE is
                    // the other side: the driver loses the access, because the counter-party is not eligible any more.
                    allThree(who = driver, pid = pid).forEach { it.shouldBeInstanceOf<NotFoundException>() }
                }
            } finally {
                CarpoolFriendConfig.provider = previous
            }
        }

        test("a blocked or anonymised party ends the access for BOTH sides; so does a driver who is no longer an active member") {
            carpoolTestApplication {
                // passenger leaves (WITHDRAWN member status)
                val driver = data.member(label = "am6-driver")
                val person = data.member(label = "am6-person", status = MemberStatus.GUEST)
                val offer = data.posting(author = driver)
                val pid = data.participation(postingId = offer, personId = person, status = CarpoolParticipationStatus.CONFIRMED).toString()
                call(member = person) { sendCarpoolMessage(participationId = pid, body = "Hallo") }
                data.setStatus(memberId = person, status = MemberStatus.WITHDRAWN)
                allThree(who = person, pid = pid).forEach { it.shouldBeInstanceOf<ForbiddenException>() }
                allThree(who = driver, pid = pid).forEach { it.shouldBeInstanceOf<NotFoundException>() }

                // anonymised passenger
                val anon = data.member(label = "am6-anon", status = MemberStatus.GUEST, anonymized = true)
                val anonPid =
                    data
                        .participation(
                            postingId = data.posting(author = driver),
                            personId = anon,
                            status = CarpoolParticipationStatus.CONFIRMED,
                        ).toString()
                allThree(who = driver, pid = anonPid).forEach { it.shouldBeInstanceOf<NotFoundException>() }
                allThree(who = anon, pid = anonPid).forEach { it.shouldBeInstanceOf<ForbiddenException>() }

                // the driver is no longer an active member
                val driver2 = data.member(label = "am6-driver2")
                val person2 = data.member(label = "am6-person2", status = MemberStatus.FRIEND)
                val pid2 =
                    data
                        .participation(
                            postingId = data.posting(author = driver2),
                            personId = person2,
                            status = CarpoolParticipationStatus.CONFIRMED,
                        ).toString()
                data.setStatus(memberId = driver2, status = MemberStatus.WITHDRAWN)
                allThree(who = person2, pid = pid2).forEach { it.shouldBeInstanceOf<NotFoundException>() }
                allThree(who = driver2, pid = pid2).forEach { it.shouldBeInstanceOf<ForbiddenException>() }
            }
        }
    })
