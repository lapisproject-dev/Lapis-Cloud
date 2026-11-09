package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Family memberships (V1.9.296): three families, one payer each and one to two dependents, written the way `MemberFamilyService.createFamily` /
 * `addFamilyMember` leave them behind (the PAYER link carries the shadow column `payer_family_id = family_id`, a DEPENDENT link carries NULL, and a
 * DEPENDENT has no tier of its own: `addFamilyMember` clears `member.membership_tier_id` through `MembershipTierAssignment`, so the contribution run
 * bills the family only through the payer).
 *
 * The seed clears the dependents' tier with one narrow compare-and-set UPDATE (WHERE the fixed seed id, `anonymized_at IS NULL` and a tier set),
 * in the same transaction as the link, instead of calling `MembershipTierAssignment` (which needs a signed-in actor). Unlike the service it writes
 * no audit entry. This is the second place, after [StagingSeedMemberDetails], where a part updates an existing `member` row.
 * One dependent is a minor (see [StagingSeedMemberDetails]) so the "upcoming majority" list is not empty.
 *
 * A family is written only as a whole: its id is missing, every involved member exists, is ACTIVE and not anonymized, and none of them is in a
 * family already (`uq_member_family_link_member`, checked beforehand, never caught: a unique violation would poison a PostgreSQL transaction).
 * Otherwise that family is skipped alone. Seed members 1-3 (the login accounts with a management role) are never payers.
 */
internal object StagingSeedFamilies : StagingSeedPart {
    override val name: String = "families"

    private const val CREATED_BY = 1

    data class PlannedFamily(
        val index: Int,
        val name: String,
        val payer: Int,
        val dependents: List<Int>,
        val createdDaysAgo: Int,
    ) {
        val id: Uuid get() = SeedIds.families(index)

        /** Slot 0 is the payer, 1.. the dependents. */
        fun linkId(slot: Int): Uuid = SeedIds.families(0x2000 + index * 0x10 + slot)

        val members: List<Int> get() = listOf(payer) + dependents
    }

    /** Append-only: the index is the id. */
    val families: List<PlannedFamily> =
        listOf(
            PlannedFamily(index = 1, name = "Familie Kulisse (Demodaten)", payer = 19, dependents = listOf(20, 21), createdDaysAgo = 200),
            PlannedFamily(index = 2, name = "Familie Ohnehin (Demodaten)", payer = 23, dependents = listOf(24), createdDaysAgo = 120),
            PlannedFamily(index = 3, name = "Familie Fantasie (Demodaten)", payer = 39, dependents = listOf(40), createdDaysAgo = 5),
        )

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        var inserted = 0
        var skipped = 0
        if (statusesOf(listOf(CREATED_BY))[CREATED_BY] == null) return StagingSeedPartResult(inserted = 0, skipped = families.size)
        families.forEach { family ->
            if (writable(family)) {
                inserted += write(family = family, clock = clock)
            } else {
                skipped++
            }
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }

    private fun JdbcTransaction.writable(family: PlannedFamily): Boolean {
        if (!MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq family.id }.empty()) return false
        val statuses = statusesOf(family.members)
        if (family.members.any { statuses[it] != MemberStatus.ACTIVE }) return false
        val ids = family.members.map { SeedIds.member(it) }
        val anonymized = !MemberTable.selectAll().where { (MemberTable.id inList ids) and MemberTable.anonymizedAt.isNotNull() }.empty()
        if (anonymized) return false
        return MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId inList ids }.empty()
    }

    private fun JdbcTransaction.write(
        family: PlannedFamily,
        clock: SeedClock,
    ): Int {
        val at = clock.utcAt(daysAgo = family.createdDaysAgo)
        MemberFamilyTable.insert {
            it[id] = family.id
            it[name] = family.name
            it[createdBy] = SeedIds.member(CREATED_BY)
            it[createdAt] = at
        }
        family.members.forEachIndexed { slot, member ->
            val payer = slot == 0
            if (!payer) {
                // Dependent: no own tier, billed through the payer (see the class KDoc).
                MemberTable.update({
                    (MemberTable.id eq SeedIds.member(member)) and MemberTable.anonymizedAt.isNull() and
                        MemberTable.membershipTierId.isNotNull()
                }) { it[membershipTierId] = null }
            }
            MemberFamilyLinkTable.insert {
                it[id] = family.linkId(slot)
                it[familyId] = family.id
                it[memberId] = SeedIds.member(member)
                it[role] = if (payer) FamilyMemberRole.PAYER else FamilyMemberRole.DEPENDENT
                it[payerFamilyId] = if (payer) family.id else null
                it[linkedAt] = at
                it[linkedBy] = SeedIds.member(CREATED_BY)
            }
        }
        return 1 + family.members.size
    }
}
