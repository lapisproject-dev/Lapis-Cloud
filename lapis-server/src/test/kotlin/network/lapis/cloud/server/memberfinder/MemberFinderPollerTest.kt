package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val PINNED = "2026-10-16T10:00:00Z"
private val NOW = LocalDateTime(2026, 10, 16, 10, 0)

/** V1.9.144 -- the housekeeping: expiry, detaching, deletion after the retention periods, the cleanup of members who lost eligibility, idle while off. */
class MemberFinderPollerTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        fun row(id: Uuid) = data.requests().singleOrNull { it.id == id }

        test("an open request ends at expires_at (decided_at = expires_at); an unexpired one stays open") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6)
                val expired = data.request(from = six[0], to = six[1], createdAt = NOW.minusDays(31), expiresAt = NOW.minusDays(1))
                val running = data.request(from = six[2], to = six[3], createdAt = NOW.minusDays(1), expiresAt = NOW.plusDays(29))
                val phantom = data.request(from = six[4], to = null, createdAt = NOW.minusDays(31), expiresAt = NOW.minusDays(1))
                MemberFinderPoller().tick(NOW)
                row(expired)!!.status shouldBe MemberFinderRequestStatus.EXPIRED
                row(expired)!!.decidedAt shouldBe NOW.minusDays(1)
                row(phantom)!!.status shouldBe MemberFinderRequestStatus.EXPIRED
                row(running)!!.status shouldBe MemberFinderRequestStatus.OPEN
            }
        }

        test("an open request whose side is no longer eligible, or who moved region, ends now; the sender's own end date is untouched") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6)
                val withdrawn = data.request(from = six[0], to = six[1])
                val fine = data.request(from = six[2], to = six[3])
                data.setStatus(memberId = six[1], status = MemberStatus.WITHDRAWN)
                MemberFinderPoller().tick(NOW)
                row(withdrawn)!!.status shouldBe MemberFinderRequestStatus.EXPIRED
                row(withdrawn)!!.decidedAt shouldBe NOW
                row(withdrawn)!!.expiresAt shouldBe NOW.plusDays(30)
                row(fine)!!.status shouldBe MemberFinderRequestStatus.OPEN
            }
        }

        test(
            "the counterpart link and the note of a declined or expired request are detached 7 days after the decision, the row goes after max(decision, end) + 7",
        ) {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6)
                // decided 6 days ago: link stays; 7 days ago: detached; 8 days ago but the end is 3 days ago: detached, row stays
                val sixDays =
                    data.request(
                        from = six[0],
                        to = six[1],
                        status = MemberFinderRequestStatus.DECLINED,
                        note = "x",
                        createdAt = NOW.minusDays(20),
                        decidedAt = NOW.minusDays(6),
                    )
                val sevenDays =
                    data.request(
                        from = six[1],
                        to = six[2],
                        status = MemberFinderRequestStatus.DECLINED,
                        note = "y",
                        createdAt = NOW.minusDays(20),
                        expiresAt = NOW.minusDays(1),
                        decidedAt = NOW.minusDays(7),
                    )
                val endsLater =
                    data.request(
                        from = six[2],
                        to = six[3],
                        status = MemberFinderRequestStatus.DECLINED,
                        createdAt = NOW.minusDays(27),
                        expiresAt = NOW.plusDays(3),
                        decidedAt = NOW.minusDays(8),
                    )
                val goneBoth =
                    data.request(
                        from = six[3],
                        to = six[4],
                        status = MemberFinderRequestStatus.EXPIRED,
                        createdAt = NOW.minusDays(50),
                        expiresAt = NOW.minusDays(20),
                        decidedAt = NOW.minusDays(20),
                    )
                MemberFinderPoller().tick(NOW)
                row(sixDays)!!.toMemberId.shouldNotBeNull()
                row(sixDays)!!.note shouldBe "x"
                row(sevenDays)!!.toMemberId.shouldBeNull()
                row(sevenDays)!!.note.shouldBeNull()
                row(endsLater)!!.toMemberId.shouldBeNull()
                row(goneBoth).shouldBeNull()
            }
        }

        test("a withdrawn request goes 7 days after the withdrawal, an accepted one after 90 days") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6)
                val withdrawnOld =
                    data.request(
                        from = six[0],
                        to = six[1],
                        status = MemberFinderRequestStatus.WITHDRAWN,
                        decidedAt = NOW.minusDays(7),
                    )
                val withdrawnNew =
                    data.request(
                        from = six[1],
                        to = six[2],
                        status = MemberFinderRequestStatus.WITHDRAWN,
                        decidedAt = NOW.minusDays(6),
                    )
                val acceptedOld =
                    data.request(
                        from = six[2],
                        to = six[3],
                        status = MemberFinderRequestStatus.ACCEPTED,
                        decidedAt = NOW.minusDays(90),
                    )
                val acceptedNew =
                    data.request(
                        from = six[3],
                        to = six[4],
                        status = MemberFinderRequestStatus.ACCEPTED,
                        decidedAt = NOW.minusDays(89),
                    )
                MemberFinderPoller().tick(NOW)
                row(withdrawnOld).shouldBeNull()
                row(withdrawnNew).shouldNotBeNull()
                row(acceptedOld).shouldBeNull()
                row(acceptedNew).shouldNotBeNull()
            }
        }

        test("a member with an open consent who is no longer ACTIVE, is anonymized or turned out to be a minor is cleaned up") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val leaver = data.member(label = "leaver").also { data.consent(memberId = it) }
                val kid =
                    data
                        .member(
                            label = "kid",
                            dateOfBirth = kotlinx.datetime.LocalDate(2012, 1, 1),
                        ).also { data.consent(memberId = it) }
                val fine = data.member(label = "fine").also { data.consent(memberId = it) }
                data.setStatus(memberId = leaver, status = MemberStatus.DONOR)
                data.request(from = leaver, to = fine)
                MemberFinderPoller().tick(NOW)
                transaction { MemberFinderConsentStore.openGrant(leaver) }.shouldBeNull()
                transaction { MemberFinderConsentStore.openGrant(kid) }.shouldBeNull()
                transaction { MemberFinderConsentStore.openGrant(fine) }.shouldNotBeNull()
                data.requests().none { it.fromMemberId == leaver } shouldBe true
            }
        }

        test("with the instance switch off a tick does nothing at all") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder(false)
                val six = data.region(count = 6)
                val old = data.request(from = six[0], to = six[1], createdAt = NOW.minusDays(40), expiresAt = NOW.minusDays(10))
                MemberFinderPoller().tick(NOW)
                row(old)!!.status shouldBe MemberFinderRequestStatus.OPEN
                data.bandRows() shouldBe 0
            }
        }
    })
