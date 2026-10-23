package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/**
 * The static LTR plan of the staging top-up (V1.9.197) and the writer that applies it.
 *
 * Writes the same rows the live paths write -- `LtrLedgerService.mintLtr` (plain MINT insert, no audit, no hash chain) and
 * `PeerTransferService.executeTransfer` (one `peer_transfer` row plus an OUT and an IN ledger row sharing the timestamp) --
 * and nothing else. Strictly additive: rows are inserted when their fixed id is missing, never updated or deleted, so a
 * second run changes nothing and hand-made LTR rows of testers stay untouched (the visible balance is then target + theirs).
 * Members are read from the database at run time: anybody who is missing or no longer in
 * [MemberStatusSets.LTR_ELIGIBLE] gets nothing, and a transfer needs both ends eligible.
 *
 * Plan rule: every outgoing transfer is covered by the sender's own earlier mints, so skipping a transfer can never push a
 * running balance below zero.
 */
internal object StagingSeedLtr {
    private const val NOTE_PREFIX = "[SEED]"
    private const val TRANSFER_BASE = 0x1000
    private const val OUT_BASE = 0x2000
    private const val IN_BASE = 0x3000

    data class PlannedMint(
        val index: Int,
        val member: Int,
        val amount: BigDecimal,
        val daysAgo: Int,
        val note: String,
    )

    data class PlannedTransfer(
        val index: Int,
        val sender: Int,
        val recipient: Int,
        val amount: BigDecimal,
        val daysAgo: Int,
        val characterization: PeerTransferCharacterization,
        val purpose: String,
    )

    data class Result(
        val mintsInserted: Int,
        val transfersInserted: Int,
        val skippedIneligible: Int,
    )

    /** Target balance per member number n (as in `SeedIds.member(n)`). Pinned by `StagingSeedLtrPlanTest`. */
    val targets: Map<Int, BigDecimal> =
        mapOf(
            1 to "500.00",
            2 to "250.00",
            3 to "120.00",
            4 to "60.00",
            5 to "25.00",
            6 to "12.50",
            7 to "5.00",
            8 to "0.05",
            13 to "20.00",
            9 to "80.00",
            10 to "45.00",
            19 to "70.00",
            20 to "35.00",
            21 to "60.00",
            23 to "15.00",
            24 to "50.00",
            25 to "25.00",
            27 to "40.00",
            28 to "30.00",
            29 to "65.00",
            30 to "20.00",
            32 to "55.00",
            33 to "10.00",
            34 to "75.00",
            35 to "12.00",
            37 to "18.00",
            38 to "22.00",
            39 to "14.00",
            40 to "10.00",
        ).mapValues { BigDecimal(it.value) }

    /** Other active members: member number to days since admission (the seed's `joiner(...)` values). */
    private val otherActive: List<Pair<Int, Int>> =
        listOf(
            9 to 640,
            10 to 590,
            19 to 540,
            20 to 500,
            21 to 470,
            23 to 400,
            24 to 370,
            25 to 340,
            27 to 270,
            28 to 240,
            29 to 210,
            30 to 180,
            32 to 120,
            33 to 100,
            34 to 85,
            35 to 70,
            37 to 40,
            38 to 28,
            39 to 18,
            40 to 10,
        )

    val mints: List<PlannedMint> by lazy { buildMints() }

    val transfers: List<PlannedTransfer> =
        listOf(
            PlannedTransfer(
                index = 0,
                sender = 1,
                recipient = 4,
                amount = BigDecimal("30.00"),
                daysAgo = 15,
                characterization = PeerTransferCharacterization.SCHENKUNG,
                purpose = "$NOTE_PREFIX Dank für die Kuchenspende",
            ),
            PlannedTransfer(
                index = 1,
                sender = 3,
                recipient = 2,
                amount = BigDecimal("10.00"),
                daysAgo = 30,
                characterization = PeerTransferCharacterization.HONORAR,
                purpose = "$NOTE_PREFIX Hilfe bei der Kassenprüfung",
            ),
            PlannedTransfer(
                index = 2,
                sender = 8,
                recipient = 7,
                amount = BigDecimal("0.95"),
                daysAgo = 4,
                characterization = PeerTransferCharacterization.SONSTIGES,
                purpose = "$NOTE_PREFIX Anteil Gruppenfahrkarte",
            ),
        )

    /**
     * Builds the mint plan. A mint's fixed row id derives from its list position
     * ([SeedIds.ltr]), so the plan is APPEND-ONLY: never insert, remove or reorder
     * entries before the end, or ids shift and already topped-up instances would
     * double-mint. Pinned by a test over the first static entries.
     */
    private fun buildMints(): List<PlannedMint> {
        val raw = mutableListOf<Triple<Int, Pair<String, Int>, String>>()

        fun add(
            member: Int,
            amount: String,
            daysAgo: Int,
            note: String,
        ) {
            raw += Triple(member, amount to daysAgo, note)
        }
        add(1, "300.00", 200, "$NOTE_PREFIX Startguthaben")
        add(1, "150.00", 90, "$NOTE_PREFIX Anerkennung: Organisation Sommerfest")
        add(1, "80.00", 20, "$NOTE_PREFIX Anerkennung: Protokoll Vorstandssitzung")
        add(2, "200.00", 180, "$NOTE_PREFIX Startguthaben")
        add(2, "40.00", 60, "$NOTE_PREFIX Anerkennung: Vorbereitung Mitgliederversammlung")
        add(3, "100.00", 150, "$NOTE_PREFIX Startguthaben")
        add(3, "30.00", 45, "$NOTE_PREFIX Anerkennung: Jahresabschluss")
        add(4, "30.00", 100, "$NOTE_PREFIX Startguthaben")
        add(5, "20.00", 120, "$NOTE_PREFIX Startguthaben")
        add(5, "5.00", 10, "$NOTE_PREFIX Anerkennung: Kuchen für das Mitgliedertreffen")
        add(6, "10.00", 80, "$NOTE_PREFIX Startguthaben")
        add(6, "2.50", 5, "$NOTE_PREFIX Anerkennung: Aufbau Infostand")
        add(7, "3.00", 70, "$NOTE_PREFIX Startguthaben")
        add(7, "1.05", 7, "$NOTE_PREFIX Anerkennung: Fahrdienst")
        add(8, "1.00", 60, "$NOTE_PREFIX Startguthaben")
        add(13, "15.00", 40, "$NOTE_PREFIX Startguthaben")
        add(13, "5.00", 5, "$NOTE_PREFIX Anerkennung: Mitarbeit im Verein")
        otherActive.forEach { (member, activeSince) ->
            val target = targets.getValue(member)
            val first = target.multiply(BigDecimal("0.7")).setScale(2, RoundingMode.DOWN)
            val second = target.subtract(first)
            val firstDays = (activeSince - 3).coerceIn(2, 200)
            val secondDays = maxOf(1, firstDays / 3)
            add(member, first.toPlainString(), firstDays, "$NOTE_PREFIX Startguthaben")
            add(member, second.toPlainString(), secondDays, "$NOTE_PREFIX Anerkennung: Mitarbeit im Verein")
        }
        return raw.mapIndexed { index, (member, amountAndDays, note) ->
            PlannedMint(
                index = index,
                member = member,
                amount = BigDecimal(amountAndDays.first),
                daysAgo = amountAndDays.second,
                note = note,
            )
        }
    }

    /** Every member number the plan touches. */
    fun plannedMembers(): Set<Int> = mints.map { it.member }.toSet() + transfers.flatMap { listOf(it.sender, it.recipient) }

    /** Current status per planned member number; missing members are absent from the map. */
    fun JdbcTransaction.readStatuses(): Map<Int, MemberStatus> {
        val ids = plannedMembers().associateBy { SeedIds.member(it) }
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList ids.keys }
            .mapNotNull { row -> ids[row[MemberTable.id]]?.let { it to row[MemberTable.status] } }
            .toMap()
    }

    fun JdbcTransaction.applyTopUp(clock: SeedClock): Result = applyPlan(clock = clock, statuses = readStatuses())

    /** Writes the plan for the given [statuses] (member number to current status; absent = missing member). */
    fun JdbcTransaction.applyPlan(
        clock: SeedClock,
        statuses: Map<Int, MemberStatus>,
    ): Result {
        fun eligible(n: Int): Boolean = statuses[n] in MemberStatusSets.LTR_ELIGIBLE
        val createdBy: Uuid? = if (3 in statuses) SeedIds.member(3) else null
        var mintsInserted = 0
        var transfersInserted = 0
        var skipped = 0

        mints.forEach { mint ->
            if (!eligible(mint.member)) {
                skipped++
                return@forEach
            }
            val id = SeedIds.ltr(mint.index)
            if (LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.id eq id }.empty()) {
                insertEntry(
                    id = id,
                    member = mint.member,
                    type = LtrLedgerEntryType.MINT,
                    amount = mint.amount,
                    referenceId = null,
                    note = mint.note,
                    createdBy = createdBy,
                    createdAt = clock.utcAt(daysAgo = mint.daysAgo, hour = 10),
                )
                mintsInserted++
            }
        }

        transfers.forEach { transfer ->
            if (!eligible(transfer.sender) || !eligible(transfer.recipient)) {
                skipped++
                return@forEach
            }
            val transferId = SeedIds.ltr(TRANSFER_BASE + transfer.index)
            if (PeerTransferTable.selectAll().where { PeerTransferTable.id eq transferId }.empty()) {
                val createdAt = clock.utcAt(daysAgo = transfer.daysAgo, hour = 10)
                PeerTransferTable.insert {
                    it[id] = transferId
                    it[amountLtr] = transfer.amount
                    it[characterization] = transfer.characterization
                    it[purpose] = transfer.purpose
                    it[senderMemberId] = SeedIds.member(transfer.sender)
                    it[recipientMemberId] = SeedIds.member(transfer.recipient)
                    it[initiatedBy] = null // self-initiated, as PeerTransferService.transferLtr writes it
                    it[PeerTransferTable.createdAt] = createdAt
                }
                insertEntry(
                    id = SeedIds.ltr(OUT_BASE + transfer.index),
                    member = transfer.sender,
                    type = LtrLedgerEntryType.PEER_TRANSFER_OUT,
                    amount = transfer.amount.negate(),
                    referenceId = transferId,
                    note = transfer.purpose,
                    createdBy = null,
                    createdAt = createdAt,
                )
                insertEntry(
                    id = SeedIds.ltr(IN_BASE + transfer.index),
                    member = transfer.recipient,
                    type = LtrLedgerEntryType.PEER_TRANSFER_IN,
                    amount = transfer.amount,
                    referenceId = transferId,
                    note = transfer.purpose,
                    createdBy = null,
                    createdAt = createdAt,
                )
                transfersInserted++
            }
        }
        return Result(mintsInserted = mintsInserted, transfersInserted = transfersInserted, skippedIneligible = skipped)
    }

    private fun insertEntry(
        id: Uuid,
        member: Int,
        type: LtrLedgerEntryType,
        amount: BigDecimal,
        referenceId: Uuid?,
        note: String,
        createdBy: Uuid?,
        createdAt: LocalDateTime,
    ) {
        LtrLedgerEntryTable.insert {
            it[LtrLedgerEntryTable.id] = id
            it[memberId] = SeedIds.member(member)
            it[entryType] = type
            it[amountLtr] = amount
            it[referenceType] = if (referenceId != null) LtrLedgerReferenceType.PEER_TRANSFER else null
            it[LtrLedgerEntryTable.referenceId] = referenceId
            it[LtrLedgerEntryTable.note] = note
            it[LtrLedgerEntryTable.createdBy] = createdBy
            it[LtrLedgerEntryTable.createdAt] = createdAt
        }
    }
}
