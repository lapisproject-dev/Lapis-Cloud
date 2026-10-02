package network.lapis.cloud.server.audit

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.9.37 (Postgres test lane) -- the GoBD audit hash chain under real parallel writers. Every
 * audited mutation in the system funnels through one `SELECT ... FOR UPDATE` on the chain-state row;
 * this proves that lock gives gapless sequence numbers and an intact hash chain, including the very
 * first ("genesis") row, which a `FOR UPDATE` on "the last entry" could never protect.
 *
 * The chain is verified independently here (re-hashing with [AuditHashChain.computeHash]) instead of
 * through `AuditLogService.verifyChainIntegrity`, so a bug in the verifier cannot hide a bug in the writer.
 */
abstract class AuditChainConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec { db.deactivate() }

        fun lastSequence(): Long = transaction { AuditLogChainStateTable.selectAll().single()[AuditLogChainStateTable.lastSequenceNumber] }

        fun lastHash(): String? = transaction { AuditLogChainStateTable.selectAll().single()[AuditLogChainStateTable.lastEntryHash] }

        fun rowsAfter(sequence: Long): List<ResultRow> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.sequenceNumber greater sequence }
                    .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .toList()
            }

        fun writeInParallel(writers: Int) {
            val barrier = CyclicBarrier(writers)
            val pool = Executors.newFixedThreadPool(writers)
            try {
                (0 until writers)
                    .map { i ->
                        pool.submit {
                            barrier.await(30, TimeUnit.SECONDS)
                            transaction {
                                AuditLogRecorder.record(
                                    actorMemberId = null,
                                    actorRole = null,
                                    entityType = AuditEntityType.JOURNAL_ENTRY,
                                    entityId = Uuid.random(),
                                    action = AuditAction.CREATE,
                                    before = null,
                                    after = "{\"writer\":$i}",
                                )
                            }
                        }
                    }.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        /** Independent verification of the window `(baseSequence, baseSequence + expected]` against [anchorHash]. */
        fun assertIntactWindow(
            baseSequence: Long,
            anchorHash: String?,
            expected: Int,
        ) {
            val rows = rowsAfter(baseSequence)
            rows.size shouldBe expected
            val problems = mutableListOf<String>()
            var previousHash = anchorHash
            rows.forEachIndexed { index, row ->
                val expectedSequence = baseSequence + 1 + index
                if (row[AuditLogEntryTable.sequenceNumber] != expectedSequence) {
                    problems += "sequence gap: expected $expectedSequence, got ${row[AuditLogEntryTable.sequenceNumber]}"
                }
                if (row[AuditLogEntryTable.previousEntryHash] != previousHash) {
                    problems += "row $expectedSequence: previous hash does not match the preceding entry"
                }
                val recomputed =
                    AuditHashChain.computeHash(
                        AuditHashChain.ChainInput(
                            sequenceNumber = row[AuditLogEntryTable.sequenceNumber],
                            occurredAt = row[AuditLogEntryTable.occurredAt],
                            actorMemberId = row[AuditLogEntryTable.actorMemberId],
                            actorRole = row[AuditLogEntryTable.actorRole],
                            entityType = row[AuditLogEntryTable.entityType],
                            entityId = row[AuditLogEntryTable.entityId],
                            action = row[AuditLogEntryTable.action],
                            beforeSnapshot = row[AuditLogEntryTable.beforeSnapshot],
                            afterSnapshot = row[AuditLogEntryTable.afterSnapshot],
                            previousEntryHash = row[AuditLogEntryTable.previousEntryHash],
                        ),
                    )
                if (recomputed !=
                    row[AuditLogEntryTable.entryHash]
                ) {
                    problems += "row $expectedSequence: stored hash differs from the recomputed one"
                }
                previousHash = row[AuditLogEntryTable.entryHash]
            }
            problems.shouldBeEmpty()
            // The chain-state counter agrees with the last row written.
            lastSequence() shouldBe baseSequence + expected
            lastHash() shouldBe previousHash
        }

        // Runs first on purpose: on a Postgres lane database the chain is genuinely EMPTY here. On H2 the
        // one shared database already carries other specs' entries, so this scenario is Postgres-only.
        test(
            "genesis race: 8 parallel first writers on an EMPTY chain get sequence 1..8 and a valid chain",
        ).config(enabled = db.isPostgres) {
            lastSequence() shouldBe 0L
            lastHash() shouldBe null
            writeInParallel(8)
            assertIntactWindow(baseSequence = 0, anchorHash = null, expected = 8)
        }

        test("16 parallel writers: gapless sequence numbers, every entry chained to its predecessor, hashes recompute") {
            val base = lastSequence()
            val anchor = lastHash()
            writeInParallel(16)
            assertIntactWindow(baseSequence = base, anchorHash = anchor, expected = 16)
        }
    })

/** The H2 run (normal `test` task). */
class AuditChainConcurrencyTest : AuditChainConcurrencyScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuditChainConcurrencyPostgresTest : AuditChainConcurrencyScenarios(TestDatabase.Postgres())
