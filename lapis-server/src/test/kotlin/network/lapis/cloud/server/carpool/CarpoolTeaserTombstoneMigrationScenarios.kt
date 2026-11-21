package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.313 -- `V161__carpool_teaser_tombstone.sql` on H2 AND PostgreSQL: the exact columns and CHECKs, the foreign key with ON DELETE CASCADE, the
 * derived primary key, the backfill of `federation_opt_in_at` and a second run of the file being harmless. Every refused statement runs in its OWN
 * transaction (on PostgreSQL a failed statement aborts the transaction).
 */
abstract class CarpoolTeaserTombstoneMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            try {
                transaction { CarpoolTeaserTombstoneTable.deleteAll() }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun refused(block: JdbcTransaction.() -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun insertSql(
            id: Uuid,
            teaser: String,
            partner: String?,
            created: String = "2026-11-18 10:00:00",
            expires: String = "2026-12-02 10:00:00",
        ) = "INSERT INTO carpool_teaser_tombstone (id, teaser_id, partner_id, created_at, expires_at) VALUES ('$id', '$teaser', " +
            (partner?.let { "'$it'" } ?: "NULL") + ", TIMESTAMP '$created', TIMESTAMP '$expires')"

        test(
            "the table has exactly the seven columns (five of V161, two provenance columns of V165), a global row (no partner) is accepted, the teaser id must be 64 characters, expiry after creation",
        ) {
            val columns = mutableListOf<String>()
            transaction {
                exec("SELECT column_name FROM information_schema.columns WHERE table_name = 'carpool_teaser_tombstone'") { rs ->
                    while (rs.next()) columns += rs.getString(1).lowercase()
                }
            }
            columns.toSet() shouldBe
                setOf("id", "teaser_id", "partner_id", "relay_origin_host", "relay_reporter_partner_id", "created_at", "expires_at")
            val ok = "a".repeat(64)
            transaction { exec(insertSql(Uuid.random(), ok, null)) }
            refused { exec(insertSql(Uuid.random(), "short", null)) } shouldBe true
            refused { exec(insertSql(Uuid.random(), ok, null, created = "2026-11-18 10:00:00", expires = "2026-11-18 10:00:00")) } shouldBe
                true
            refused { exec(insertSql(Uuid.random(), ok, null, created = "2026-11-18 10:00:00", expires = "2026-11-17 10:00:00")) } shouldBe
                true
            refused { exec(insertSql(Uuid.random(), ok, Uuid.random().toString())) } shouldBe true // no such partner
            transaction { CarpoolTeaserTombstoneTable.deleteAll() }
        }

        test("the foreign key to partner_server cascades: removing the partner removes its scoped rows, global rows stay") {
            val partnerId = Uuid.random()
            transaction {
                exec(
                    "INSERT INTO partner_server (id, host, created_at, next_fetch_at) " +
                        "VALUES ('$partnerId', 'tomb-${partnerId.toString().take(
                            8,
                        )}.example.org', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
                )
                exec(insertSql(Uuid.random(), "b".repeat(64), partnerId.toString()))
                exec(insertSql(Uuid.random(), "c".repeat(64), null))
            }
            transaction { PartnerServerTable.deleteWhere { PartnerServerTable.id eq partnerId } }
            transaction { CarpoolTeaserTombstoneTable.selectAll().count() } shouldBe 1L
            transaction { CarpoolTeaserTombstoneTable.deleteAll() }
        }

        test("the primary key is the only uniqueness: the derived id makes a second insert of the same hint and scope a conflict") {
            val id = CarpoolFederationStore.tombstoneId(teaserId = "d".repeat(64), partnerId = null)
            transaction { exec(insertSql(id, "d".repeat(64), null)) }
            refused { exec(insertSql(id, "d".repeat(64), null)) } shouldBe true
            // the same hint for a partner scope derives ANOTHER key
            (CarpoolFederationStore.tombstoneId(teaserId = "d".repeat(64), partnerId = Uuid.random()) != id) shouldBe true
            // a stable, version-4-shaped UUID
            id.toString()[14] shouldBe '4'
            transaction { CarpoolTeaserTombstoneTable.deleteAll() }
        }

        test(
            "federation_opt_in_at: a trip written without it ends on NULL, setOptIn(true) sets it, setOptIn(false) clears it; the backfill SQL fills old opt-ins",
        ) {
            val author = data.member(label = "tomb-optinat")
            val id = data.posting(author = author, fromPlace = "38100 A", toPlace = "30159 B")

            fun at() =
                transaction {
                    CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq id }.single()[CarpoolPostingTable.federationOptInAt]
                }
            at() shouldBe null
            transaction {
                CarpoolFederationStore.setOptIn(
                    postingId = id,
                    value = true,
                    now = kotlinx.datetime.LocalDateTime(2026, 11, 18, 9, 0, 0),
                )
            }
            at() shouldBe kotlinx.datetime.LocalDateTime(2026, 11, 18, 9, 0, 0)
            transaction { CarpoolFederationStore.setOptIn(postingId = id, value = false) }
            at() shouldBe null
            // the statement of V161 itself, as an old opt-in would meet it
            transaction { exec("UPDATE carpool_posting SET federation_opt_in = TRUE WHERE id = '$id'") }
            transaction {
                exec(
                    "UPDATE carpool_posting SET federation_opt_in_at = created_at WHERE federation_opt_in = TRUE AND federation_opt_in_at IS NULL",
                )
            }
            at() shouldBe kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0, 0)
        }

        test("the migration is repeatable and leaves the existing rows alone") {
            val rowId = Uuid.random()
            transaction { exec(insertSql(rowId, "e".repeat(64), null)) }
            val sql =
                checkNotNull(
                    CarpoolTeaserTombstoneMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V161__carpool_teaser_tombstone.sql",
                    ),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 10) shouldBe true
            transaction { statements.forEach { exec(it) } }
            transaction { CarpoolTeaserTombstoneTable.selectAll().count() } shouldBe 1L
            transaction { CarpoolTeaserTombstoneTable.deleteAll() }
        }
    })
