package network.lapis.cloud.server.member

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditMarkers
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.9.121 -- country and nationality codes on the real engine, as scenarios so the same assertions run on H2 and on PostgreSQL:
 * the `CHAR(2)` column and its CHECKs on the real Flyway chain, the compare-and-set of the reconciliation against a concurrent human
 * choice (it waits for the member row lock and then must NOT overwrite), and the lock order (member row first, audit chain second) of
 * the reconciliation against parallel country writes -- no deadlock, no lost update.
 */
abstract class MemberCountryScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PeerFixture()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun legacyMember(
            country: String?,
            nationality: String? = null,
        ): Uuid {
            val id = fx.member()
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[MemberTable.country] = country
                    it[MemberTable.nationality] = nationality
                }
            }
            return id
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        test("CHAR(2) round-trips exactly two characters; shape CHECKs, the composite key and the FK hold on the real engine") {
            val m = fx.member()
            transaction {
                MemberCountryStore.setCountry(memberId = m, code = "PL")
                MemberCountryStore.replaceNationalities(memberId = m, codes = listOf("PL", "DE"))
            }
            row(m)[MemberTable.countryCode] shouldBe "PL"
            transaction {
                MemberNationalityTable
                    .selectAll()
                    .where { MemberNationalityTable.memberId eq m }
                    .map { it[MemberNationalityTable.countryCode] }
                    .sorted()
            } shouldBe listOf("DE", "PL")

            fun fails(sql: String): Boolean = runCatching { transaction { exec(sql) } }.isFailure
            fails("UPDATE member SET country_code = 'pl' WHERE id = '$m'") shouldBe true
            fails("UPDATE member SET country_code = 'POL' WHERE id = '$m'") shouldBe true
            fails("INSERT INTO member_nationality (member_id, country_code) VALUES ('$m', 'de')") shouldBe true
            fails("INSERT INTO member_nationality (member_id, country_code) VALUES ('$m', 'DE')") shouldBe true // duplicate key
            fails("INSERT INTO member_nationality (member_id, country_code) VALUES ('${Uuid.random()}', 'DE')") shouldBe true // no member
            fails("DELETE FROM member WHERE id = '$m'") shouldBe true // no CASCADE
            row(m)[MemberTable.countryCode] shouldBe "PL"
        }

        test("the reconciliation waits for a concurrent human choice and then does NOT overwrite it (compare-and-set under the row lock)") {
            val actor = fx.admin()
            val contested = legacyMember(country = "Polska")
            val others = (1..3).map { legacyMember(country = "Polska") }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val locked = CountDownLatch(1)
                val human =
                    pool.submit {
                        transaction {
                            MemberTable
                                .selectAll()
                                .where { MemberTable.id eq contested }
                                .forMemberUpdate()
                                .single()
                            MemberCountryStore.setCountry(memberId = contested, code = "AT")
                            locked.countDown()
                            Thread.sleep(400)
                        }
                    }
                locked.await(10, TimeUnit.SECONDS) shouldBe true
                val report = CountryLegacyMappingEngine.preview(actor)
                val result =
                    CountryLegacyMappingEngine.apply(
                        actor = fx.actor(id = actor),
                        expectedFingerprint = report.fingerprint,
                        now = DbClock.nowLocalDateTime(),
                    )
                human.get(30, TimeUnit.SECONDS)
                (result.country.applied >= 3) shouldBe true
                row(contested)[MemberTable.countryCode] shouldBe "AT"
                // the human's choice cleared the legacy text; the reconciliation (which would have KEPT it) did not run on this row
                row(contested)[MemberTable.country] shouldBe null
                others.forEach { row(it)[MemberTable.countryCode] shouldBe "PL" }
            } finally {
                pool.shutdownNow()
            }
        }

        test("reconciliation and parallel country writes (member lock, then audit chain) never deadlock and lose no write") {
            val actor = fx.admin()
            val targets = (1..8).map { legacyMember(country = "Polska") }
            val humans = targets.take(4)
            val pool = Executors.newFixedThreadPool(5)
            val barrier = CyclicBarrier(5)
            try {
                val report = CountryLegacyMappingEngine.preview(actor)
                val futures =
                    humans.map { id ->
                        pool.submit<Unit> {
                            barrier.await(20, TimeUnit.SECONDS)
                            transaction {
                                MemberTable
                                    .selectAll()
                                    .where { MemberTable.id eq id }
                                    .forMemberUpdate()
                                    .single()
                                MemberCountryStore.setCountry(memberId = id, code = "CH")
                                AuditLogRecorder.record(
                                    actorMemberId = id,
                                    actorRole = AccountRole.MEMBER,
                                    entityType = AuditEntityType.MEMBER,
                                    entityId = id,
                                    action = AuditAction.UPDATE,
                                    after = AuditMarkers.MEMBER_ADDRESS_UPDATED,
                                    occurredAt = DbClock.nowLocalDateTime(),
                                )
                            }
                        }
                    }
                val applying =
                    pool.submit<Result<Unit>> {
                        barrier.await(20, TimeUnit.SECONDS)
                        runCatching {
                            // the plan may have changed under the humans' writes: a refused plan is a legal outcome, a deadlock is not
                            CountryLegacyMappingEngine.apply(
                                actor = fx.actor(id = actor),
                                expectedFingerprint = report.fingerprint,
                                now = DbClock.nowLocalDateTime(),
                            )
                            Unit
                        }
                    }
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
                val outcome = applying.get(60, TimeUnit.SECONDS)
                (outcome.exceptionOrNull() == null || outcome.exceptionOrNull()?.javaClass?.simpleName == "ConflictException") shouldBe true
            } finally {
                pool.shutdownNow()
            }
            // every human choice survived: either the human's code (CH) or -- if the reconciliation ran first -- the human wrote over it
            humans.forEach { row(it)[MemberTable.countryCode] shouldBe "CH" }
        }
    })

class MemberCountryTest : MemberCountryScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberCountryPostgresTest : MemberCountryScenarios(TestDatabase.Postgres())
