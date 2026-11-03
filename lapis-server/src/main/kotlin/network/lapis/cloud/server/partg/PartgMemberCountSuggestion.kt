package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PartgMemberCountSuggestionDto
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.269 (R3) -- the read-only member count suggestion for the member count of statement E. A COUNT only, no personal data leaves the query.
 *
 * Only for the CURRENT year and a federal or state unit: the register knows today's members, not those of 31 December of an earlier year, and it
 * has no subordinate associations. Counts [MemberStatusSets.ORGANIZATION_MEMBER] that are not anonymised; FEDERAL = all, STATE = this chapter.
 * Whether that is the legally relevant member definition is an open question for the board (docs/features/partg-report.adoc).
 */
internal object PartgMemberCountSuggestion {
    fun suggest(
        unit: PartgUnit,
        year: Int,
        today: LocalDate,
        currentYear: Int,
    ): PartgMemberCountSuggestionDto? {
        if (year != currentYear || unit is PartgUnit.Subordinate) return null
        val count =
            MemberTable
                .selectAll()
                .where {
                    val base = (MemberTable.status inList MemberStatusSets.ORGANIZATION_MEMBER) and MemberTable.anonymizedAt.isNull()
                    val chapter = unit.chapterId
                    if (chapter == null) base else base and (MemberTable.regionalChapterId eq Uuid.parse(chapter))
                }.count()
        return PartgMemberCountSuggestionDto(count = count, asOf = today)
    }
}
