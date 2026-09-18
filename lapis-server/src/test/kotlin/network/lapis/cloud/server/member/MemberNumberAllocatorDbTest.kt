package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberNumberSequenceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- direct unit coverage of
 * [MemberNumberAllocator.ensureFor]: format, per-year isolation, idempotency, lazy backfill for a
 * pre-existing member, and the "single most important test" real-thread race idiom (mirrors
 * [network.lapis.cloud.server.events.EventVolunteerCapacityGuardTest]'s own house style) proving
 * the row-lock actually serializes two concurrent first-allocations for the SAME year without a
 * duplicate/skip.
 */
class MemberNumberAllocatorDbTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val touchedYears = mutableListOf<Int>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
                if (touchedYears.isNotEmpty()) MemberNumberSequenceTable.deleteWhere { allocationYear inList touchedYears }
            }
        }

        fun createMember(joinedAt: LocalDate): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MemberNumberAllocatorDbTest Mitglied"
                    it[email] = "membernumberallocator-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[MemberTable.joinedAt] = joinedAt
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            touchedYears += joinedAt.year
            return id
        }

        test("first allocation of a fresh year formats as M-<year>-00001") {
            val memberId = createMember(LocalDate(2031, 3, 1))
            val number = transaction { MemberNumberAllocator.ensureFor(memberId) }
            number shouldBe "M-2031-00001"
        }

        test("second member of the same year gets the next sequence value") {
            val first = createMember(LocalDate(2032, 1, 1))
            val second = createMember(LocalDate(2032, 6, 1))
            transaction { MemberNumberAllocator.ensureFor(first) } shouldBe "M-2032-00001"
            transaction { MemberNumberAllocator.ensureFor(second) } shouldBe "M-2032-00002"
        }

        test("different joined_at years get independent counters") {
            val a = createMember(LocalDate(2033, 1, 1))
            val b = createMember(LocalDate(2034, 1, 1))
            transaction { MemberNumberAllocator.ensureFor(a) } shouldBe "M-2033-00001"
            transaction { MemberNumberAllocator.ensureFor(b) } shouldBe "M-2034-00001"
        }

        test("ensureFor is idempotent -- a second call returns the same number without incrementing the sequence") {
            val memberId = createMember(LocalDate(2035, 1, 1))
            val first = transaction { MemberNumberAllocator.ensureFor(memberId) }
            val second = transaction { MemberNumberAllocator.ensureFor(memberId) }
            first shouldBe second

            val other = createMember(LocalDate(2035, 2, 1))
            transaction { MemberNumberAllocator.ensureFor(other) } shouldBe "M-2035-00002"
        }

        test("lazy backfill for a pre-existing member without a number") {
            val memberId = createMember(LocalDate(2036, 1, 1))
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq memberId }
                    .single()[MemberTable.memberNumber]
            } shouldBe null

            val number = transaction { MemberNumberAllocator.ensureFor(memberId) }
            number shouldBe "M-2036-00001"
            transaction {
                MemberTable
                    .selectAll()
                    .where { (MemberTable.id eq memberId) and (MemberTable.memberNumber eq number) }
                    .count()
            } shouldBe 1L
        }

        test("concurrent first allocation for the same year never duplicates or skips a sequence value") {
            // A year not used by any other test file in this suite (MemberCardStoreDbTest uses
            // 2037) -- two different spec files allocating in the SAME year would otherwise race
            // against each other and make this assertion's exact "1..N" sequence flaky.
            val year = 2091
            val members = (1..8).map { createMember(LocalDate(year, 1, 1)) }
            val pool = Executors.newFixedThreadPool(members.size)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(members.size)
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())

            members.forEach { memberId ->
                pool.submit {
                    startLatch.await()
                    try {
                        results += transaction { MemberNumberAllocator.ensureFor(memberId) }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            doneLatch.await(30, TimeUnit.SECONDS)
            pool.shutdown()

            results.toSet().size shouldBe members.size
            results.toSet() shouldBe (1..members.size).map { "M-%d-%05d".format(year, it) }.toSet()
        }
    })
