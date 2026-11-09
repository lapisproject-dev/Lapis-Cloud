package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Honors (V1.9.296): four entries in the register, one per category, as `MemberHonorService.recordHonor` leaves them behind. Recorded by the
 * fictitious administrator. A honor is written only when its id is missing and the honored member exists, is not anonymized and has not left
 * (an honor of a person who is no longer a member would be odd on a fresh seed); otherwise it is skipped alone. The award date lies in the past.
 */
internal object StagingSeedHonors : StagingSeedPart {
    override val name: String = "honors"

    private const val RECORDED_BY = 1

    data class PlannedHonor(
        val index: Int,
        val member: Int,
        val category: MemberHonorCategory,
        val title: String,
        val awardedDaysAgo: Int,
        val awardedBy: String,
        val note: String,
    ) {
        val id: Uuid get() = SeedIds.honors(index)
    }

    /** Append-only: the index is the id. */
    val honors: List<PlannedHonor> =
        listOf(
            PlannedHonor(
                index = 1,
                member = 8,
                category = MemberHonorCategory.HONORARY_MEMBERSHIP,
                title = "Ehrenmitgliedschaft (Demodaten)",
                awardedDaysAgo = 300,
                awardedBy = "Mitgliederversammlung (Demodaten)",
                note = "Fuer langjaehrigen Einsatz. Erfundene Demodaten.",
            ),
            PlannedHonor(
                index = 2,
                member = 4,
                category = MemberHonorCategory.SERVICE_AWARD,
                title = "Verdienstnadel in Silber (Demodaten)",
                awardedDaysAgo = 180,
                awardedBy = "Vorstand (Demodaten)",
                note = "Fuer den Aufbau der Ortsgruppe. Erfundene Demodaten.",
            ),
            PlannedHonor(
                index = 3,
                member = 10,
                category = MemberHonorCategory.LOYALTY_AWARD,
                title = "Treueehrung 1 Jahr (Demodaten)",
                awardedDaysAgo = 60,
                awardedBy = "Vorstand (Demodaten)",
                note = "Erfundene Demodaten.",
            ),
            PlannedHonor(
                index = 4,
                member = 5,
                category = MemberHonorCategory.OTHER,
                title = "Dank fuer die Sommerfest-Organisation (Demodaten)",
                awardedDaysAgo = 30,
                awardedBy = "Vorstand (Demodaten)",
                note = "Erfundene Demodaten.",
            ),
        )

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        var inserted = 0
        var skipped = 0
        val statuses = statusesOf(honors.map { it.member } + RECORDED_BY)
        honors.forEach { honor ->
            val exists = !MemberHonorTable.selectAll().where { MemberHonorTable.id eq honor.id }.empty()
            val anonymized =
                !MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.id eq SeedIds.member(honor.member)) and
                            MemberTable.anonymizedAt.isNotNull()
                    }.empty()
            val eligible = statuses[honor.member] == MemberStatus.ACTIVE && statuses[RECORDED_BY] != null && !anonymized
            if (exists || !eligible) {
                skipped++
            } else {
                MemberHonorTable.insert {
                    it[id] = honor.id
                    it[memberId] = SeedIds.member(honor.member)
                    it[category] = honor.category
                    it[title] = honor.title
                    it[awardedAt] = clock.dayAgo(honor.awardedDaysAgo)
                    it[awardedBy] = honor.awardedBy
                    it[note] = honor.note
                    it[recordedBy] = SeedIds.member(RECORDED_BY)
                    it[recordedAt] = clock.utcAt(daysAgo = honor.awardedDaysAgo - 1)
                }
                inserted++
            }
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
