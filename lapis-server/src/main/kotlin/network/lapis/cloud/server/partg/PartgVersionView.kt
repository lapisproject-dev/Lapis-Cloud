package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.partg.PartgVersionStore.VersionMeta
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineRules
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgVersionRules
import network.lapis.cloud.shared.domain.PartgVersionStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- turns the rows of the version store into DTOs. Every function runs inside the caller's transaction. Names are resolved FRESH from
 * `member` (an anonymised member shows the anonymised name; nothing is read from a stored document). The content of a version is never read here.
 */
internal object PartgVersionView {
    const val MAX_OVERVIEW_VERSIONS = 1000

    private fun names(ids: Collection<Uuid>): Map<Uuid, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            MemberTable
                .selectAll()
                .where { MemberTable.id inList ids.distinct() }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        }

    fun dtos(metas: List<VersionMeta>): List<PartgVersionDto> {
        val declarations = PartgVersionStore.declarations(metas.map { it.id })
        val people = names(metas.flatMap { listOfNotNull(it.frozenBy, it.finalizedBy) } + declarations.values.map { it.declaredBy })
        return metas.map { meta ->
            val declaration = declarations[meta.id]
            PartgVersionDto(
                id = meta.id.toString(),
                year = meta.year,
                scopeKey = meta.scopeKey,
                versionNumber = meta.versionNumber,
                status = PartgVersionStore.statusOf(meta = meta, declared = declaration != null),
                frozenByName = people[meta.frozenBy].orEmpty(),
                frozenAt = meta.frozenAt,
                comment = meta.comment,
                contentHash = meta.contentHash,
                byteSize = meta.byteSize,
                hasThirdPartyData = meta.hasThirdPartyData,
                declaredByName = declaration?.let { people[it.declaredBy].orEmpty() },
                declaredAt = declaration?.declaredAt,
                declarationTemplateCode = declaration?.templateCode,
                finalizedByName = meta.finalizedBy?.let { people[it].orEmpty() },
                finalizedAt = meta.finalizedAt,
            )
        }
    }

    fun dto(id: Uuid): PartgVersionDto {
        val meta = PartgVersionStore.versionMeta(id) ?: throw NotFoundException("Version not found")
        return dtos(listOf(meta)).single()
    }

    /** `ALL` first, then units by key; inside a scope the newest version first. */
    fun sorted(dtos: List<PartgVersionDto>): List<PartgVersionDto> =
        dtos.sortedWith(
            compareBy<PartgVersionDto> { if (it.scopeKey == PartgReportScope.ALL_KEY) 0 else 1 }
                .thenBy { it.scopeKey }
                .thenByDescending { it.versionNumber },
        )

    fun yearStatus(year: Int): PartgVersionStatus = yearStatus(year = year, statuses = PartgVersionStore.wholePartyStatuses(listOf(year)))

    /** The status of [year] from an already read map (several years in one query). */
    fun yearStatus(
        year: Int,
        statuses: Map<Int, List<PartgVersionStatus>>,
    ): PartgVersionStatus = PartgVersionRules.yearStatus(statuses[year].orEmpty())

    fun deadline(
        year: Int,
        today: LocalDate,
        status: PartgVersionStatus,
    ): PartgDeadlineDto {
        val stored = PartgVersionStore.deadline(year)
        val date = stored?.deadline ?: PartgDeadlineRules.proposal(year)
        return PartgDeadlineDto(
            year = year,
            deadline = date,
            confirmed = stored != null,
            today = today,
            level = PartgDeadlineRules.level(today = today, deadline = date, final = status == PartgVersionStatus.FINAL),
            daysUntil = PartgDeadlineRules.daysUntil(today = today, deadline = date),
        )
    }

    fun overview(
        year: Int,
        today: LocalDate,
        deviation: PartgDraftDeviation,
    ): PartgVersionOverviewDto {
        val status = yearStatus(year)
        return PartgVersionOverviewDto(
            year = year,
            // bounded: at most 200 versions per scope and one scope per unit, so the list is capped for the screen (whole party first, newest first)
            versions = sorted(dtos(PartgVersionStore.versions(year))).take(MAX_OVERVIEW_VERSIONS),
            yearStatus = status,
            deadline = deadline(year = year, today = today, status = status),
            draftDeviation = deviation,
        )
    }

    /** The newest whole-party version of [year] (highest number), for the deviation check. */
    fun newestWholeParty(year: Int): VersionMeta? = PartgVersionStore.latest(year = year, scopeKey = PartgReportScope.ALL_KEY)
}
