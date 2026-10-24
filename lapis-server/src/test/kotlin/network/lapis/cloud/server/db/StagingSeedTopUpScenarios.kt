package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.StagingSeedLtr.applyPlan
import network.lapis.cloud.server.db.StagingSeedLtr.readStatuses
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * The staging LTR top-up (V1.9.197) on both databases. The seed runs ONCE per spec; every scenario starts with [reset], which removes
 * all LTR rows and restores member 4 (the seed itself writes no LTR rows).
 */
abstract class StagingSeedTopUpScenarios(
    private val testDb: TestDatabase,
) : FunSpec({
        val seedPassword = "ein-starkes-testpasswort"
        lateinit var db: Database

        beforeSpec {
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")
            testDb.activate()
            db =
                when (testDb) {
                    is TestDatabase.Postgres -> testDb.db.database
                    TestDatabase.H2 -> IsolatedH2Database.create()
                }
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db, sepaKey = null)
        }
        afterSpec { testDb.deactivate() }
        installLaneGuards(db = testDb)
        beforeTest {
            transaction(db) {
                PeerTransferTable.deleteAll()
                LtrLedgerEntryTable.deleteAll()
                MemberTable.update({ MemberTable.id eq SeedIds.member(4) }) { it[status] = MemberStatus.ACTIVE }
            }
        }

        fun <T> read(block: () -> T): T = transaction(db) { block() }

        fun topUp(): StagingSeedLtr.Result =
            (StagingSeedTopUp.topUpWith(database = db, parts = emptyList()).shouldBeInstanceOf<StagingTopUpOutcome.Applied>()).result

        fun balances(): Map<Int, BigDecimal> =
            read {
                val provider = LedgerBackedLtrBalanceProvider()
                (1..40).associateWith { provider.freeBalance(SeedIds.member(it)) }
            }

        fun fullSnapshot(): List<String> =
            read {
                LtrLedgerEntryTable.selectAll().map { row ->
                    listOf(
                        row[LtrLedgerEntryTable.id],
                        row[LtrLedgerEntryTable.memberId],
                        row[LtrLedgerEntryTable.entryType],
                        row[LtrLedgerEntryTable.amountLtr],
                        row[LtrLedgerEntryTable.referenceType],
                        row[LtrLedgerEntryTable.referenceId],
                        row[LtrLedgerEntryTable.note],
                        row[LtrLedgerEntryTable.createdBy],
                        row[LtrLedgerEntryTable.createdAt],
                    ).joinToString("|")
                } +
                    PeerTransferTable.selectAll().map { row ->
                        listOf(
                            row[PeerTransferTable.id],
                            row[PeerTransferTable.amountLtr],
                            row[PeerTransferTable.characterization],
                            row[PeerTransferTable.purpose],
                            row[PeerTransferTable.senderMemberId],
                            row[PeerTransferTable.recipientMemberId],
                            row[PeerTransferTable.initiatedBy],
                            row[PeerTransferTable.createdAt],
                        ).joinToString("|")
                    }
            }

        fun BigDecimal.eq2(expected: String) = compareTo(BigDecimal(expected)) shouldBe 0

        test("balances as the LTR views compute them equal the plan targets; ineligible members have nothing") {
            topUp()
            val result = balances()
            val statuses = read { MemberTable.selectAll().associate { it[MemberTable.id] to it[MemberTable.status] } }
            StagingSeedLtr.targets.forEach { (member, target) -> result.getValue(member).compareTo(target) shouldBe 0 }
            (1..40).filter { it !in StagingSeedLtr.targets.keys }.forEach { result.getValue(it).eq2("0") }
            read {
                val withRows = LtrLedgerEntryTable.selectAll().map { it[LtrLedgerEntryTable.memberId] }.toSet()
                withRows.forEach { (statuses.getValue(it) in MemberStatusSets.LTR_ELIGIBLE) shouldBe true }
            }
        }

        test("plan members are exactly ACTIVE or FRIEND members of the seed") {
            val planned = StagingSeedLtr.plannedMembers()
            val eligible =
                read {
                    MemberTable
                        .selectAll()
                        .filter { it[MemberTable.status] in MemberStatusSets.LTR_ELIGIBLE }
                        .map { it[MemberTable.id] }
                        .toSet()
                }
            planned.map { SeedIds.member(it) }.toSet() shouldBe eligible
        }

        test("mint plan is append-only: first static entries keep their index, member and amount") {
            StagingSeedLtr.mints
                .take(5)
                .map { Triple(it.index, it.member, it.amount.toPlainString()) } shouldBe
                listOf(
                    Triple(0, 1, "300.00"),
                    Triple(1, 1, "150.00"),
                    Triple(2, 1, "80.00"),
                    Triple(3, 2, "200.00"),
                    Triple(4, 2, "40.00"),
                )
        }

        test("row shape: mint rows, transfer pairs, timestamps in the past and after admission") {
            topUp()
            val zone = read { TimeZone.of(OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.timezone]) }
            val now = DbClock.nowLocalDateTime()
            read {
                val rows = LtrLedgerEntryTable.selectAll().toList()
                val mintRows = rows.filter { it[LtrLedgerEntryTable.entryType] == LtrLedgerEntryType.MINT }
                mintRows.size shouldBe StagingSeedLtr.mints.size
                mintRows.forEach {
                    (it[LtrLedgerEntryTable.amountLtr] > BigDecimal.ZERO) shouldBe true
                    it[LtrLedgerEntryTable.referenceType].shouldBeNull()
                    it[LtrLedgerEntryTable.createdBy] shouldBe SeedIds.member(3)
                }
                val joined = MemberTable.selectAll().associate { it[MemberTable.id] to it[MemberTable.joinedAt] }
                val seedClock = SeedClock(now = now, orgZone = zone)
                rows.forEach { row ->
                    row[LtrLedgerEntryTable.createdAt] shouldBeLessThanOrEqualTo now
                    val start = seedClock.startOfDayUtc(joined.getValue(row[LtrLedgerEntryTable.memberId]))
                    (row[LtrLedgerEntryTable.createdAt] >= start) shouldBe true
                }
                val transferRows = PeerTransferTable.selectAll().toList()
                transferRows.size shouldBe StagingSeedLtr.transfers.size
                transferRows.forEach { transfer ->
                    val pair = rows.filter { it[LtrLedgerEntryTable.referenceId] == transfer[PeerTransferTable.id] }
                    pair.size shouldBe 2
                    val out = pair.single { it[LtrLedgerEntryTable.entryType] == LtrLedgerEntryType.PEER_TRANSFER_OUT }
                    val inn = pair.single { it[LtrLedgerEntryTable.entryType] == LtrLedgerEntryType.PEER_TRANSFER_IN }
                    out[LtrLedgerEntryTable.amountLtr].negate().compareTo(inn[LtrLedgerEntryTable.amountLtr]) shouldBe 0
                    inn[LtrLedgerEntryTable.amountLtr].compareTo(transfer[PeerTransferTable.amountLtr]) shouldBe 0
                    out[LtrLedgerEntryTable.createdAt] shouldBe inn[LtrLedgerEntryTable.createdAt]
                    out[LtrLedgerEntryTable.createdAt] shouldBe transfer[PeerTransferTable.createdAt]
                    out[LtrLedgerEntryTable.memberId] shouldBe transfer[PeerTransferTable.senderMemberId]
                    inn[LtrLedgerEntryTable.memberId] shouldBe transfer[PeerTransferTable.recipientMemberId]
                    transfer[PeerTransferTable.initiatedBy].shouldBeNull()
                    out[LtrLedgerEntryTable.createdBy].shouldBeNull()
                    inn[LtrLedgerEntryTable.createdBy].shouldBeNull()
                    out[LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.PEER_TRANSFER
                }
            }
        }

        test("idempotent: a second run inserts nothing and changes nothing") {
            val first = topUp()
            (first.mintsInserted + first.transfersInserted) shouldBeGreaterThan 0
            val before = fullSnapshot()
            val second = topUp()
            second.mintsInserted shouldBe 0
            second.transfersInserted shouldBe 0
            fullSnapshot() shouldContainExactlyInAnyOrder before
        }

        test("non-empty ledger: hand-made rows stay untouched, the balance is target plus the manual amount") {
            val manualMint = Uuid.random()
            val manualTransfer = Uuid.random()
            val at = DbClock.nowLocalDateTime()
            read {
                LtrLedgerEntryTable.insert {
                    it[id] = manualMint
                    it[memberId] = SeedIds.member(1)
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal("7.00")
                    it[note] = "von Hand"
                    it[createdAt] = at
                }
                PeerTransferTable.insert {
                    it[id] = manualTransfer
                    it[amountLtr] = BigDecimal("1.00")
                    it[characterization] = PeerTransferCharacterization.SONSTIGES
                    it[senderMemberId] = SeedIds.member(2)
                    it[recipientMemberId] = SeedIds.member(5)
                    it[createdAt] = at
                }
            }
            topUp()
            val after = fullSnapshot()
            after.count { it.startsWith("$manualMint|") } shouldBe 1
            after.single { it.startsWith("$manualMint|") }.contains("von Hand") shouldBe true
            read {
                PeerTransferTable.selectAll().where { PeerTransferTable.id eq manualTransfer }.single()[PeerTransferTable.amountLtr].eq2(
                    "1.00",
                )
            }
            balances().getValue(1).eq2("507.00")
            val snapshotAfterFirst = fullSnapshot()
            topUp()
            fullSnapshot() shouldContainExactlyInAnyOrder snapshotAfterFirst
        }

        test("a member who is no longer eligible gets nothing; the transfer to him is skipped and no balance goes negative") {
            read { MemberTable.update({ MemberTable.id eq SeedIds.member(4) }) { it[status] = MemberStatus.WITHDRAWN } }
            val result = topUp()
            (result.skippedIneligible > 0) shouldBe true
            read { LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.memberId eq SeedIds.member(4) }.toList() }.shouldBeEmpty()
            val b = balances()
            b.getValue(1).eq2("530.00")
            b.getValue(4).eq2("0")
            b.values.forEach { (it >= BigDecimal.ZERO) shouldBe true }
        }

        test("missing members: rows pointing to them are never written") {
            val statuses = transaction(db) { readStatuses() } - 4 - 13
            val before = read { LtrLedgerEntryTable.selectAll().count() }
            before shouldBe 0L
            val result = transaction(db) { applyPlan(clock = SeedClock.capture(TimeZone.UTC), statuses = statuses) }
            (result.skippedIneligible > 0) shouldBe true
            read {
                LtrLedgerEntryTable
                    .selectAll()
                    .filter { it[LtrLedgerEntryTable.memberId] in setOf(SeedIds.member(4), SeedIds.member(13)) }
                    .toList()
            }.shouldBeEmpty()
            read { PeerTransferTable.selectAll().count() } shouldBe 2L
        }
    })
