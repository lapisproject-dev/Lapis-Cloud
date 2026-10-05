package network.lapis.cloud.server.testdb

import io.kotest.core.annotation.Condition
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbSessionTimeouts
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.reflect.KClass

/**
 * Welle V1.9.37 -- the database a scenario spec runs against. The scenario bodies live in an
 * abstract spec taking a [TestDatabase]; one thin subclass passes [H2] (normal `test` task), another
 * -- tagged `@Tags("Postgres")` -- passes a fresh [Postgres] (`postgresTest` task). Same assertions,
 * two databases. Scenarios must NEVER call `module()`/[DatabaseConfig.connect] on the Postgres side
 * (the dialect guard in [assertActive] catches a silent fall-back to H2).
 */
sealed interface TestDatabase {
    val isPostgres: Boolean

    /** `beforeSpec`: provision the database and make it Exposed's default. */
    fun activate()

    /** `afterSpec`, AFTER the spec's own data cleanup. */
    fun deactivate()

    /** Dialect + database-identity guard; call from `beforeTest`. */
    fun assertActive()

    data object H2 : TestDatabase {
        override val isPostgres: Boolean = false

        override fun activate() {
            DatabaseConfig.connect()
        }

        override fun deactivate() = Unit

        override fun assertActive() = Unit
    }

    class Postgres internal constructor(
        private val timeouts: DbSessionTimeouts,
    ) : TestDatabase {
        constructor() : this(DbSessionTimeouts.DEFAULTS)

        private var specDb: PgSpecDatabase? = null

        override val isPostgres: Boolean = true

        val db: PgSpecDatabase get() = requireNotNull(specDb) { "Postgres test database not active" }

        override fun activate() {
            val created = PostgresTestSupport.createDatabase(timeouts = timeouts)
            specDb = created
            created.activate()
            assertActive()
        }

        override fun deactivate() {
            specDb?.close()
            specDb = null
        }

        override fun assertActive() {
            val expected = db.name
            var current: String? = null
            val dialect =
                transaction {
                    exec("SELECT current_database()") { rs -> if (rs.next()) current = rs.getString(1) }
                    currentDialect.name
                }
            dialect shouldBe "PostgreSQL"
            current shouldBe expected
        }

        /**
         * Reads `pg_stat_database.deadlocks`. Stats of other backends are flushed when they go idle (at
         * most about once a second), so [deadlockDelta] polls to let them land.
         */
        fun deadlockCount(): Long = readDeadlocks()

        private fun readDeadlocks(): Long =
            db.rawConnection().use { c ->
                c.createStatement().use { st ->
                    runCatching { st.execute("SELECT pg_stat_force_next_flush()") }
                    st.execute("SELECT pg_stat_clear_snapshot()")
                    st.executeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        /**
         * Polls up to [maxWaitMillis] for the deadlock counter to rise above [baseline]; returns the
         * (possibly zero) delta. A delta above zero is a FINDING, never a tolerated condition.
         */
        fun deadlockDelta(
            baseline: Long,
            maxWaitMillis: Long = 2_000,
        ): Long {
            val deadline = System.nanoTime() + maxWaitMillis * 1_000_000
            var delta = readDeadlocks() - baseline
            while (delta == 0L && System.nanoTime() < deadline) {
                Thread.sleep(200)
                delta = readDeadlocks() - baseline
            }
            return delta
        }
    }
}

/** Kotest condition behind `@EnabledIf(PostgresConfigured::class)` -- must be a class with a no-arg constructor. */
class PostgresConfigured : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean {
        val configured = PostgresTestSupport.config != null
        if (!configured) PostgresTestSupport.logSkipOnce()
        return configured
    }
}

/**
 * Registers the lane guards on a scenario spec: the dialect/identity guard before EVERY test, and
 * (on Postgres, for concurrency specs) a zero-new-deadlocks assertion after every test.
 */
internal fun FunSpec.installLaneGuards(
    db: TestDatabase,
    checkDeadlocks: Boolean = false,
) {
    var baseline = 0L
    beforeTest {
        db.assertActive()
        if (checkDeadlocks && db is TestDatabase.Postgres) baseline = db.deadlockCount()
    }
    afterTest {
        if (checkDeadlocks && db is TestDatabase.Postgres) {
            db.deadlockDelta(baseline = baseline) shouldBe 0L
        }
    }
}
