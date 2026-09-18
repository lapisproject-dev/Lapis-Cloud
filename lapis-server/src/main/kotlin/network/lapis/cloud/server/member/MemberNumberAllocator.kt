package network.lapis.cloud.server.member

import network.lapis.cloud.server.db.generated.MemberNumberSequenceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- allocates `member.member_number`
 * ("M-<Beitrittsjahr>-<5-stellig>", e.g. "M-2026-00042"). Year is [network.lapis.cloud.shared
 * .domain.MemberDto.joinedAt]'s year, NOT "today" -- this keeps the number stable and fachlich
 * sprechend for backfilled/imported members regardless of when their card is first requested.
 *
 * **Deliberately row-locked, not `MAX(next_value)+1`.** [ensureFor] locks the `member` row with
 * `forUpdate()` first (idempotency check + the only place `member_number` is ever written), then
 * the year's `member_number_sequence` row, also `forUpdate()` -- the second lock is what
 * serializes two concurrent first-allocations for the SAME year; a naive `MAX()+1` read would race
 * under concurrent callers. Both locks are held for the remainder of the caller's own transaction.
 *
 * **No `MAX_ATTEMPTS` collision-retry loop** (unlike [network.lapis.cloud.server.payment
 * .bankstatement.PaymentReferenceAllocator]): that allocator's `SecureRandom`+retry design exists
 * because a Postgres `SEQUENCE` was rejected for reasons that do not apply here (see that class's
 * own KDoc) -- this allocator's row-lock IS the serialization primitive, so there is nothing to
 * retry against; a collision cannot occur while the lock is held.
 */
internal object MemberNumberAllocator {
    /** 99,999 numbers/year in "%05d" -- practically unreachable, but an explicit ceiling beats a silent format break. */
    const val MAX_PER_YEAR: Int = 99_999

    fun format(
        year: Int,
        sequence: Int,
    ): String = String.format(Locale.ROOT, "M-%d-%05d", year, sequence)

    /**
     * Must run inside an already-open transaction. Returns [memberId]'s existing `member_number`
     * if already set (idempotent, no rotation), otherwise allocates and persists a fresh one based
     * on that member's `joined_at` year.
     */
    fun ensureFor(memberId: Uuid): String {
        val memberRow =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Member $memberId not found")
        memberRow[MemberTable.memberNumber]?.let { return it }

        val year = memberRow[MemberTable.joinedAt].year
        // Ensure the sequence row exists before locking it -- insertIgnore is a no-op if another
        // transaction already created it (or if this same year has been allocated before).
        MemberNumberSequenceTable.insertIgnore {
            it[allocationYear] = year
            it[nextValue] = 1
        }
        val sequenceRow =
            MemberNumberSequenceTable
                .selectAll()
                .where { MemberNumberSequenceTable.allocationYear eq year }
                .forUpdate()
                .single()
        val sequence = sequenceRow[MemberNumberSequenceTable.nextValue]
        check(sequence <= MAX_PER_YEAR) {
            "MemberNumberAllocator: exhausted $MAX_PER_YEAR member numbers for year $year"
        }
        MemberNumberSequenceTable.update({ MemberNumberSequenceTable.allocationYear eq year }) {
            it[nextValue] = sequence + 1
        }
        val memberNumber = format(year = year, sequence = sequence)
        MemberTable.update({ MemberTable.id eq memberId }) {
            it[MemberTable.memberNumber] = memberNumber
        }
        return memberNumber
    }
}
