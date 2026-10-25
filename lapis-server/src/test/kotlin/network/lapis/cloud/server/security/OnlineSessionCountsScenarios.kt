package network.lapis.cloud.server.security

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.MemberStatisticsFixtures
import network.lapis.cloud.server.security.OnlineSessionCountsSupport.FAR_NOW
import network.lapis.cloud.server.security.OnlineSessionCountsSupport.minus
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * V1.9.215 -- [OnlineSessionCounts] against H2 AND PostgreSQL. "Now" is the year 2200, so sessions of other specs (all expired by then) never
 * leak into the absolute numbers.
 */
abstract class OnlineSessionCountsScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fixtures = MemberStatisticsFixtures()
        val now = FAR_NOW

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            OnlineSessionCountsSupport.cleanUp()
            fixtures.cleanUp()
        }
        afterSpec { db.deactivate() }

        fun person(status: MemberStatus = MemberStatus.ACTIVE) = fixtures.member(role = AccountRole.MEMBER, status = status)

        test("an empty situation counts zeros") {
            val (active, signedIn) = OnlineSessionCounts.count(now)
            active.total shouldBe 0
            signedIn.total shouldBe 0
        }

        test("an expired session and a session expiring exactly now do not count") {
            val a = person()
            val b = person()
            OnlineSessionCountsSupport.session(memberId = a, expiresAt = now.minus(1.microseconds), lastUsedAt = now.minus(1.minutes))
            OnlineSessionCountsSupport.session(memberId = b, expiresAt = now, lastUsedAt = now.minus(1.minutes))
            val (active, signedIn) = OnlineSessionCounts.count(now)
            signedIn.total shouldBe 0
            active.total shouldBe 0
        }

        test("a revoked session does not count") {
            val a = person()
            OnlineSessionCountsSupport.session(memberId = a, lastUsedAt = now.minus(1.minutes), revokedAt = now.minus(2.minutes))
            OnlineSessionCounts.count(now).second.total shouldBe 0
        }

        test("a person with three valid sessions counts once") {
            val a = person()
            repeat(3) { OnlineSessionCountsSupport.session(memberId = a, lastUsedAt = now.minus(1.minutes)) }
            val (active, signedIn) = OnlineSessionCounts.count(now)
            signedIn.total shouldBe 1
            active.total shouldBe 1
        }

        test("the active window: 14:59 and exactly 15:00 are active, 15:00 plus one microsecond is not") {
            val a = person()
            val b = person()
            val c = person()
            OnlineSessionCountsSupport.session(memberId = a, lastUsedAt = now.minus(14.minutes + 59.seconds))
            OnlineSessionCountsSupport.session(memberId = b, lastUsedAt = now.minus(15.minutes))
            OnlineSessionCountsSupport.session(memberId = c, lastUsedAt = now.minus(15.minutes + 1.microseconds))
            val (active, signedIn) = OnlineSessionCounts.count(now)
            active.total shouldBe 2
            signedIn.total shouldBe 3
        }

        test("a session never used (last_used_at null) is signed in but not active") {
            val a = person()
            OnlineSessionCountsSupport.session(memberId = a, lastUsedAt = null)
            val (active, signedIn) = OnlineSessionCounts.count(now)
            active.total shouldBe 0
            signedIn.total shouldBe 1
        }

        test("the split: active to members, friend to friends, guest to guests, the rest to other; the sum invariant holds") {
            listOf(
                MemberStatus.ACTIVE,
                MemberStatus.ACTIVE,
                MemberStatus.FRIEND,
                MemberStatus.GUEST,
                MemberStatus.GUEST,
                MemberStatus.GUEST,
                MemberStatus.DONOR,
                MemberStatus.APPLICATION,
            ).forEach { OnlineSessionCountsSupport.session(memberId = person(it), lastUsedAt = now.minus(1.minutes)) }
            val (active, signedIn) = OnlineSessionCounts.count(now)
            listOf(active, signedIn).forEach {
                it.members shouldBe 2
                it.friends shouldBe 1
                it.guests shouldBe 3
                it.other shouldBe 2
                it.total shouldBe 8
                it.total shouldBe it.members + it.friends + it.guests + it.other
            }
        }

        test("groupOf is defined for every status and matches the table") {
            val expected =
                mapOf(
                    MemberStatus.ACTIVE to OnlineGroup.MEMBERS,
                    MemberStatus.FRIEND to OnlineGroup.FRIENDS,
                    MemberStatus.GUEST to OnlineGroup.GUESTS,
                    MemberStatus.APPLICATION to OnlineGroup.OTHER,
                    MemberStatus.WITHDRAWN to OnlineGroup.OTHER,
                    MemberStatus.REJECTED to OnlineGroup.OTHER,
                    MemberStatus.DONOR to OnlineGroup.OTHER,
                    MemberStatus.DECEASED to OnlineGroup.OTHER,
                )
            MemberStatus.entries.forEach { OnlineSessionCounts.groupOf(it) shouldBe expected.getValue(it) }
            expected.size shouldBe MemberStatus.entries.size
        }
    })

/** The H2 run (normal `test` task). */
class OnlineSessionCountsTest : OnlineSessionCountsScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OnlineSessionCountsPostgresTest : OnlineSessionCountsScenarios(TestDatabase.Postgres())
