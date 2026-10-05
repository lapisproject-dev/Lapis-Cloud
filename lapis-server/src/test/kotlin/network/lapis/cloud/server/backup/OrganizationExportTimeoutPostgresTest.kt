package network.lapis.cloud.server.backup

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DbSessionTimeouts
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

/**
 * Welle V1.9.55 -- `OrganizationExportService.streamTable` streams a cursor into the HTTP sink INSIDE a transaction. A slow client
 * therefore leaves the transaction `idle in transaction` for as long as it takes; with `idle_in_transaction_session_timeout` the
 * server would kill the backend mid-export. The export lifts statement + idle timeouts with `SET LOCAL` and never retries (a retry
 * would append the rows a second time to the ZIP entry).
 *
 * The sink blocks until `pg_stat_activity` shows the export backend `idle in transaction` for LONGER than the pool's idle timeout
 * (polled, bounded) -- so without the relaxation this test fails by construction.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OrganizationExportTimeoutPostgresTest :
    FunSpec({
        lateinit var pg: PgSpecDatabase
        val idleTimeoutMs = 700L
        val storageRoot =
            File.createTempFile("export-timeout-storage", "").also {
                it.delete()
                it.mkdirs()
            }

        beforeSpec {
            pg =
                PostgresTestSupport.createDatabase(
                    timeouts =
                        DbSessionTimeouts(
                            lockTimeoutMs = 300,
                            statementTimeoutMs = 1_500,
                            idleInTransactionTimeoutMs = idleTimeoutMs,
                        ),
                )
        }
        afterSpec {
            runCatching { pg.close() }
            storageRoot.deleteRecursively()
        }

        test("a slow client does not get the export killed by idle_in_transaction_session_timeout, and the bundle is intact") {
            val adminId = Uuid.random()
            transaction(pg.database) {
                MemberTable.insert {
                    it[MemberTable.id] = adminId
                    it[displayName] = "ExportTimeout Admin"
                    it[email] = "export-timeout-$adminId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
                // Enough incompressible rows that the deflater hands bytes to the sink WHILE the cursor is open.
                exec(
                    "INSERT INTO ledger_account (id, account_number, name, account_class, type, active, reserve_type, is_cash_register) " +
                        "SELECT gen_random_uuid(), 'X' || g, " +
                        "md5(random()::text) || md5(random()::text) || md5(random()::text), 0, 'ASSET', true, NULL, false " +
                        "FROM generate_series(1, 6000) g",
                )
            }

            var blockedWhileIdleInTransactionMs = 0L
            var blockEvents = 0

            /** Waits (bounded) until the export backend has been `idle in transaction` for > 2x the idle timeout. */
            fun awaitExportBackendIdleLongerThanTimeout() {
                val deadline = System.nanoTime() + 20_000L * 1_000_000
                while (System.nanoTime() < deadline) {
                    val ageMs =
                        pg.rawConnection().use { c ->
                            c
                                .prepareStatement(
                                    "SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (clock_timestamp() - state_change)) * 1000), -1)::bigint " +
                                        "FROM pg_stat_activity WHERE datname = current_database() " +
                                        "AND state = 'idle in transaction' AND pid <> pg_backend_pid()",
                                ).use { ps ->
                                    ps.executeQuery().use { rs ->
                                        rs.next()
                                        rs.getLong(1)
                                    }
                                }
                        }
                    if (ageMs > idleTimeoutMs * 2) {
                        blockedWhileIdleInTransactionMs = ageMs
                        return
                    }
                    Thread.sleep(50)
                }
                error("export backend never showed idle in transaction for longer than the idle timeout")
            }

            val bundle = ByteArrayOutputStream()
            val slowSink =
                object : OutputStream() {
                    private var armed = true

                    private fun maybeBlock() {
                        if (!armed) return
                        // Only block while the export's cursor transaction is open (idle in transaction from the DB's view).
                        val inTransaction =
                            pg.rawConnection().use { c ->
                                c
                                    .prepareStatement(
                                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                            "AND state = 'idle in transaction' AND pid <> pg_backend_pid()",
                                    ).use { ps ->
                                        ps.executeQuery().use { rs ->
                                            rs.next()
                                            rs.getLong(1)
                                        }
                                    }
                            }
                        if (inTransaction > 0) {
                            armed = false
                            blockEvents++
                            awaitExportBackendIdleLongerThanTimeout()
                        }
                    }

                    override fun write(b: Int) {
                        maybeBlock()
                        bundle.write(b)
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        maybeBlock()
                        bundle.write(b, off, len)
                    }
                }

            OrganizationExportService(database = pg.database, documentStorageRoot = storageRoot)
                .streamExport(
                    actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                    sink = slowSink,
                )

            blockEvents shouldBe 1
            blockedWhileIdleInTransactionMs shouldBeGreaterThan idleTimeoutMs * 2 - 1

            // The bundle is intact: every table entry's digest and row count match the manifest (a retried block would double rows).
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bundle.toByteArray())).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry -> entries[entry.name] = zip.readBytes() }
            }
            val manifest = Json.parseToJsonElement(String(entries.getValue("manifest.json"), Charsets.UTF_8)).jsonObject
            val tables: JsonArray = manifest.getValue("tables").jsonArray
            var checked = 0L
            tables.forEach { t ->
                val table: JsonObject = t.jsonObject
                val name = table.getValue("tableName").jsonPrimitive.content
                val bytes = entries.getValue("data/$name.jsonl")
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                digest shouldBe table.getValue("contentSha256").jsonPrimitive.content
                bytes.count { it == '\n'.code.toByte() }.toLong() shouldBe
                    table
                        .getValue("rowCount")
                        .jsonPrimitive.content
                        .toLong()
                checked++
            }
            checked shouldBeGreaterThan 100L
            val ledgerRows =
                tables.first {
                    it.jsonObject
                        .getValue("tableName")
                        .jsonPrimitive.content == "ledger_account"
                }
            ledgerRows.jsonObject
                .getValue("rowCount")
                .jsonPrimitive.content
                .toLong() shouldBeGreaterThan 5_999L
        }
    })
