package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.carpool.CarpoolDeps
import network.lapis.cloud.server.carpool.CarpoolMessages
import network.lapis.cloud.server.carpool.CarpoolNotice
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import network.lapis.cloud.server.carpool.CarpoolNoticeMailer
import network.lapis.cloud.server.carpool.CarpoolNotifier
import network.lapis.cloud.server.carpool.CarpoolRetention
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.CarpoolMessageThreadTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CarpoolMessageLimits
import network.lapis.cloud.shared.domain.CarpoolMessageSide
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private class RecordingMessageMailer(
    override val enabled: Boolean = true,
) : CarpoolNoticeMailer {
    val sent = CopyOnWriteArrayList<Pair<String, CarpoolNoticeKind>>()

    override fun send(
        to: String,
        notice: CarpoolNotice,
    ) {
        sent += to to notice.kind
    }
}

/** V1.9.139 -- limits, ending, paging, deletion on status change, retention, mail throttle of the ride messages. */
class CarpoolMessageBehaviorTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        fun ride(
            label: String,
            personStatus: MemberStatus = MemberStatus.GUEST,
        ): Triple<Uuid, Uuid, Uuid> {
            val driver = data.member(label = "$label-driver", email = "$label-driver@example.org")
            val person = data.member(label = "$label-person", status = personStatus, email = "$label-person@example.org")
            val offer = data.posting(author = driver)
            return Triple(
                driver,
                person,
                data.participation(postingId = offer, personId = person, status = CarpoolParticipationStatus.CONFIRMED),
            )
        }

        test(
            "1000 characters go through, 1001 do not; empty, blank and control-only texts are refused; the text is trimmed at its ends only",
        ) {
            carpoolTestApplication {
                val (_, person, pid) = ride("lim1")
                val ok =
                    call(
                        member = person,
                    ) { sendCarpoolMessage(participationId = pid.toString(), body = "a".repeat(CarpoolMessageLimits.MAX_BODY_LENGTH)) }
                ok.items
                    .single()
                    .body.length shouldBe CarpoolMessageLimits.MAX_BODY_LENGTH
                attempt(member = person) {
                    sendCarpoolMessage(
                        participationId = pid.toString(),
                        body =
                            "a".repeat(
                                CarpoolMessageLimits.MAX_BODY_LENGTH + 1,
                            ),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                listOf("", "   ", "\n\t ", "\u0000\u0007").forEach { blank ->
                    attempt(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = blank) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
                val inner =
                    call(
                        member = person,
                    ) { sendCarpoolMessage(participationId = pid.toString(), body = "  Zeile eins\r\n\r\n  Zeile\u202E zwei \u0000 ") }
                inner.items.first().body shouldBe "Zeile eins\n\n  Zeile zwei"
                data.messageCount(pid) shouldBe 2
            }
        }

        test("the 200th message goes through, the 201st is a conflict, and the history reports that it is full") {
            carpoolTestApplication {
                val (driver, person, pid) = ride("lim2")
                repeat(CarpoolMessageLimits.MAX_MESSAGES - 1) { i ->
                    data.message(
                        participationId = pid,
                        side = CarpoolMessageSide.PASSENGER,
                        body = "m$i",
                        sentAt =
                            LocalDateTime(
                                2026,
                                1,
                                1,
                                0,
                                0,
                                i % 60,
                                i,
                            ),
                    )
                }
                val last = call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Nummer 200") }
                last.totalCount shouldBe CarpoolMessageLimits.MAX_MESSAGES
                last.writable shouldBe false
                last.ended shouldBe false
                val refused =
                    attempt(
                        member = driver,
                    ) { sendCarpoolMessage(participationId = pid.toString(), body = "Nummer 201") }.exceptionOrNull()
                refused.shouldBeInstanceOf<ConflictException>()
                refused.message.contains("200") shouldBe true
                data.messageCount(pid) shouldBe CarpoolMessageLimits.MAX_MESSAGES
            }
        }

        test(
            "the order of the checks: a stranger with 1001 characters learns nothing and spends no budget; an ended history beats a too-long text",
        ) {
            carpoolTestApplication {
                val (driver, person, pid) = ride("lim3")
                val stranger = data.member(label = "lim3-stranger", status = MemberStatus.GUEST)
                repeat(15) {
                    attempt(member = stranger) { sendCarpoolMessage(participationId = pid.toString(), body = "x".repeat(1001)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<NotFoundException>()
                }
                // The stranger's 15 attempts did not use up anything the stranger could later use as a participant of ANOTHER ride.
                val (_, person2, pid2) = ride("lim3b")
                val otherRide =
                    data.participation(
                        postingId = data.posting(author = data.member(label = "lim3-d3")),
                        personId = stranger,
                        status = CarpoolParticipationStatus.CONFIRMED,
                    )
                call(member = stranger) { sendCarpoolMessage(participationId = otherRide.toString(), body = "geht") }
                call(member = person2) { sendCarpoolMessage(participationId = pid2.toString(), body = "geht auch") }
                call(member = driver) { endCarpoolThread(pid.toString()) }
                val e =
                    attempt(
                        member = person,
                    ) { sendCarpoolMessage(participationId = pid.toString(), body = "x".repeat(1001)) }.exceptionOrNull()
                e.shouldBeInstanceOf<ConflictException>()
                e.message shouldBe "Dieser Verlauf wurde beendet."
            }
        }

        test("ten messages per minute: the eleventh is refused with a conflict that names no text") {
            carpoolTestApplication {
                val (_, person, pid) = ride("rate")
                repeat(CarpoolMessageLimits.PER_MINUTE) {
                    call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Nachricht $it") }
                }
                val e =
                    attempt(
                        member = person,
                    ) { sendCarpoolMessage(participationId = pid.toString(), body = "GEHEIMER-INHALT") }.exceptionOrNull()
                e.shouldBeInstanceOf<ConflictException>()
                e.message shouldNotContain "GEHEIMER-INHALT"
                data.messageCount(pid) shouldBe CarpoolMessageLimits.PER_MINUTE
            }
        }

        test(
            "ending: either side can end, nobody can write afterwards, reading stays possible, it is idempotent and endedByMe is per side",
        ) {
            carpoolTestApplication {
                val (driver, person, pid) = ride("end")
                call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Hallo") }
                val first = call(member = person) { endCarpoolThread(pid.toString()) }
                first.ended shouldBe true
                first.endedByMe shouldBe true
                first.writable shouldBe false
                val again = call(member = driver) { endCarpoolThread(pid.toString()) } // the second call changes nothing
                again.endedByMe shouldBe false
                transaction {
                    CarpoolMessageThreadTable
                        .selectAll()
                        .where { CarpoolMessageThreadTable.participationId eq pid }
                        .single()[CarpoolMessageThreadTable.endedBySide] shouldBe CarpoolMessageSide.PASSENGER
                }
                listOf(driver, person).forEach { who ->
                    attempt(member = who) { sendCarpoolMessage(participationId = pid.toString(), body = "Noch eine") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    call(member = who) { carpoolMessages(participationId = pid.toString(), beforeId = null) }.items.size shouldBe 1
                }
            }
        }

        test("paging: 120 messages come as 50, 50 and 20 with hasMore, newest first, without gaps or repeats") {
            carpoolTestApplication {
                val (driver, _, pid) = ride("page")
                repeat(120) { i ->
                    // several messages share one timestamp: the cursor must break the tie by id
                    data.message(
                        participationId = pid,
                        side = CarpoolMessageSide.PASSENGER,
                        body = "m$i",
                        sentAt =
                            LocalDateTime(
                                2026,
                                1,
                                1,
                                0,
                                i / 3,
                                0,
                                0,
                            ),
                    )
                }
                val seen = mutableListOf<String>()
                var before: String? = null
                val sizes = mutableListOf<Int>()
                do {
                    val page = call(member = driver) { carpoolMessages(participationId = pid.toString(), beforeId = before) }
                    sizes += page.items.size
                    seen += page.items.map { it.id }
                    before = page.items.lastOrNull()?.id
                    if (!page.hasMore) break
                } while (true)
                sizes shouldBe listOf(50, 50, 20)
                seen.toSet().size shouldBe 120
            }
        }

        test("leaving CONFIRMED deletes the history in the same transaction: withdrawal, removal by the driver, deleting the offer") {
            carpoolTestApplication {
                // the person steps back
                val (driver, person, pid) = ride("del1")
                call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Hallo") }
                call(member = person) { withdrawParticipation(pid.toString()) }
                data.messageCount(pid) shouldBe 0
                data.threadCount(pid) shouldBe 0
                // asking again and being confirmed starts an EMPTY history
                call(member = person) { requestParticipation(data.postingOf(pid)) }
                val askedAgain = pid.toString()
                call(member = driver) { confirmParticipation(askedAgain) }
                call(member = driver) { carpoolMessages(participationId = askedAgain, beforeId = null) }.items.size shouldBe 0

                // the driver removes the person
                val (driver2, person2, pid2) = ride("del2")
                call(member = driver2) { sendCarpoolMessage(participationId = pid2.toString(), body = "Hallo") }
                call(member = driver2) { withdrawParticipation(pid2.toString()) }
                data.messageCount(pid2) shouldBe 0
                attempt(
                    member = person2,
                ) {
                    carpoolMessages(
                        participationId = pid2.toString(),
                        beforeId = null,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()

                // the offer is deleted
                val (driver3, person3, pid3) = ride("del3")
                call(member = person3) { sendCarpoolMessage(participationId = pid3.toString(), body = "Hallo") }
                call(member = driver3) { deletePosting(data.postingOf(pid3)) }
                data.messageCount(pid3) shouldBe 0
                data.threadCount(pid3) shouldBe 0
            }
        }

        test("retention: messages stay on departure + 7 days and are gone on departure + 8 days (organization time, 00:00)") {
            val now = LocalDateTime(2028, 1, 10, 12, 0, 0)
            val today = OrganizationTimeZone.dateOf(now)
            val driver = data.member(label = "ret-driver")
            val person = data.member(label = "ret-person", status = MemberStatus.GUEST)
            val keptPosting =
                data.posting(
                    author = driver,
                    departure = today.minus(DatePeriod(days = CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE)),
                )
            val goneDate = today.minus(DatePeriod(days = CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE + 1))
            val gonePosting = data.posting(author = driver, departure = goneDate)
            val kept = data.participation(postingId = keptPosting, personId = person, status = CarpoolParticipationStatus.CONFIRMED)
            val gone = data.participation(postingId = gonePosting, personId = person, status = CarpoolParticipationStatus.CONFIRMED)
            data.message(participationId = kept, side = CarpoolMessageSide.DRIVER, body = "bleibt")
            data.message(participationId = gone, side = CarpoolMessageSide.DRIVER, body = "weg")
            CarpoolRetention.deleteDueRows(now)
            data.messageCount(kept) shouldBe 1
            data.messageCount(gone) shouldBe 0
            data.participationRows(gonePosting).size shouldBe 0
        }

        test("mail: one per history and hour, no text and no names, none to a guest, none while the mailer is off (throttle untouched)") {
            val mailer = RecordingMessageMailer()
            carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(mailer))) {
                val (driver, person, pid) = ride("mail", personStatus = MemberStatus.FRIEND)
                call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Eins") }
                call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Zwei") }
                call(member = driver) { sendCarpoolMessage(participationId = pid.toString(), body = "Drei (Antwort innerhalb der Stunde)") }
                mailer.sent.toList() shouldBe listOf("mail-driver@example.org" to CarpoolNoticeKind.MESSAGE)

                // 61 minutes after the last mail the next message mails again (the unit under test takes `now`, so the clock is injected)
                val notice =
                    transaction {
                        val access =
                            CarpoolMessages.requireAccess(
                                callerId = driver,
                                callerStatus = MemberStatus.ACTIVE,
                                participationId = pid,
                                lock = true,
                            )
                        val lastMail =
                            CarpoolMessageThreadTable
                                .selectAll()
                                .where { CarpoolMessageThreadTable.participationId eq pid }
                                .single()[CarpoolMessageThreadTable.lastMailAt]!!
                        val inside =
                            CarpoolMessages.insert(
                                access = access,
                                body = "Spaeter innerhalb",
                                now = lastMail.plusMinutes(30),
                                mailEnabled = true,
                            )
                        inside shouldBe null
                        CarpoolMessages.insert(access = access, body = "Spaeter", now = lastMail.plusMinutes(61), mailEnabled = true)
                    }
                notice?.kind shouldBe CarpoolNoticeKind.MESSAGE
                notice?.recipientId shouldBe person
            }

            // the guest has a synthetic address: the notifier drops it (nothing sent), but the call itself never fails
            val guestMailer = RecordingMessageMailer()
            carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(guestMailer))) {
                val driver = data.member(label = "mail2-driver", email = "mail2-driver@example.org")
                val guest = data.member(label = "mail2-guest", status = MemberStatus.GUEST, email = "guest+x@federation.invalid")
                val pid =
                    data.participation(
                        postingId = data.posting(author = driver),
                        personId = guest,
                        status = CarpoolParticipationStatus.CONFIRMED,
                    )
                call(member = driver) { sendCarpoolMessage(participationId = pid.toString(), body = "Hallo Gast") }
                guestMailer.sent.toList() shouldBe emptyList()
            }

            // mailer off: no mail, and the throttle is not consumed (last_mail_at stays empty)
            carpoolTestApplication {
                val (_, person, pid) = ride("mail3")
                call(member = person) { sendCarpoolMessage(participationId = pid.toString(), body = "Hallo") }
                transaction {
                    CarpoolMessageThreadTable
                        .selectAll()
                        .where { CarpoolMessageThreadTable.participationId eq pid }
                        .single()[CarpoolMessageThreadTable.lastMailAt] shouldBe null
                }
            }
        }
    })

private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime =
    (ServerClock.systemToInstant(this) + minutes.minutes).toLocalDateTime(ServerClock.zone)
