package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditMarkers
import network.lapis.cloud.shared.domain.CountryLegacyCountsDto
import network.lapis.cloud.shared.domain.CountryLegacyMapping
import network.lapis.cloud.shared.domain.CountryLegacyMappingReportDto
import network.lapis.cloud.shared.domain.CountryLegacyMappingResultDto
import network.lapis.cloud.shared.domain.LegacyLineDto
import network.lapis.cloud.shared.domain.LegacyOutcome
import network.lapis.cloud.shared.domain.LegacySectionDto
import network.lapis.cloud.shared.domain.RareSummaryDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.121 -- bulk reconciliation of the legacy free-text `member.country` / `member.nationality` against the selection lists.
 * A board/admin action in two steps: [preview] is a pure read; [apply] re-computes the plan, refuses it when the plan changed since
 * the dry run ([ConflictException], nothing written) and then writes row by row with a compare-and-set against the legacy text.
 *
 * What it never does: rewrite or clear the legacy columns (the code is added next to the legacy text; a later explicit clean-up
 * is a separate decision), touch members with an ADMIN account other than the caller (peer protection; those are counted as
 * `protectedSkippedMembers` and fixed individually), send mail, or log/audit a single value -- the audit entry carries only counts.
 */
internal object CountryLegacyMappingEngine {
    private class MemberFacts(
        val id: Uuid,
        val country: String?,
        val nationality: String?,
        val countryCode: String?,
        val hasNationalityRows: Boolean,
        val role: AccountRole?,
    )

    /** One planned (or open) legacy value of one member. */
    private class PlanEntry(
        val memberId: Uuid,
        val raw: String,
        val key: String,
        val outcome: LegacyOutcome,
        val targets: List<String>,
    )

    private class SectionPlan(
        val entries: List<PlanEntry>,
        val protectedSkipped: Int,
    )

    private class Plan(
        val country: SectionPlan,
        val nationality: SectionPlan,
    )

    // ---- read ------------------------------------------------------------------------------------------------------------------

    private fun loadFacts(): List<MemberFacts> {
        val withNationality =
            MemberNationalityTable
                .selectAll()
                .map { it[MemberNationalityTable.memberId] }
                .toHashSet()
        val roleByMember =
            AccountTable
                .selectAll()
                .associate { it[AccountTable.memberId] to it[AccountTable.role] }
        return MemberTable
            .selectAll()
            .where { MemberTable.anonymizedAt.isNull() }
            .orderBy(MemberTable.id to SortOrder.ASC)
            .map {
                MemberFacts(
                    id = it[MemberTable.id],
                    country = it[MemberTable.country],
                    nationality = it[MemberTable.nationality],
                    countryCode = it[MemberTable.countryCode],
                    hasNationalityRows = it[MemberTable.id] in withNationality,
                    role = roleByMember[it[MemberTable.id]],
                )
            }
    }

    private fun buildPlan(
        facts: List<MemberFacts>,
        actorId: Uuid,
    ): Plan {
        val countryEntries = mutableListOf<PlanEntry>()
        val nationalityEntries = mutableListOf<PlanEntry>()
        var countryProtected = 0
        var nationalityProtected = 0
        for (m in facts) {
            val protectedTarget = m.role == AccountRole.ADMIN && m.id != actorId
            val countryRaw = m.country?.takeIf { it.isNotBlank() }
            if (m.countryCode == null && countryRaw != null) {
                if (protectedTarget) {
                    countryProtected++
                } else {
                    countryEntries +=
                        entryOf(memberId = m.id, raw = countryRaw, resolution = LegacyCountryDictionary.resolveCountry(countryRaw))
                }
            }
            val nationalityRaw = m.nationality?.takeIf { it.isNotBlank() }
            if (!m.hasNationalityRows && nationalityRaw != null) {
                if (protectedTarget) {
                    nationalityProtected++
                } else {
                    nationalityEntries +=
                        entryOf(
                            memberId = m.id,
                            raw = nationalityRaw,
                            resolution = LegacyCountryDictionary.resolveNationality(nationalityRaw),
                        )
                }
            }
        }
        return Plan(
            country = SectionPlan(entries = countryEntries, protectedSkipped = countryProtected),
            nationality = SectionPlan(entries = nationalityEntries, protectedSkipped = nationalityProtected),
        )
    }

    private fun entryOf(
        memberId: Uuid,
        raw: String,
        resolution: LegacyCountryDictionary.Resolution,
    ): PlanEntry {
        val key = LegacyCountryDictionary.normalize(raw)
        return when (resolution) {
            is LegacyCountryDictionary.Resolution.Recognised ->
                PlanEntry(
                    memberId = memberId,
                    raw = raw,
                    key = key,
                    outcome = LegacyOutcome.RECOGNISED,
                    targets = resolution.codes,
                )
            LegacyCountryDictionary.Resolution.Ambiguous ->
                PlanEntry(
                    memberId = memberId,
                    raw = raw,
                    key = key,
                    outcome = LegacyOutcome.AMBIGUOUS,
                    targets = emptyList(),
                )
            LegacyCountryDictionary.Resolution.Unrecognised ->
                PlanEntry(
                    memberId = memberId,
                    raw = raw,
                    key = key,
                    outcome = LegacyOutcome.UNRECOGNISED,
                    targets = emptyList(),
                )
        }
    }

    // ---- preview ---------------------------------------------------------------------------------------------------------------

    /** Pure read; writes nothing. Values shared by fewer than [CountryLegacyMapping.MIN_GROUP_SIZE] members never appear verbatim. */
    fun preview(actorId: Uuid): CountryLegacyMappingReportDto =
        transaction {
            val plan = buildPlan(facts = loadFacts(), actorId = actorId)
            CountryLegacyMappingReportDto(
                country = section(plan.country),
                nationality = section(plan.nationality),
                fingerprint = fingerprint(plan),
            )
        }

    private fun section(plan: SectionPlan): LegacySectionDto {
        val groups = plan.entries.groupBy { it.key }
        val lines = mutableListOf<LegacyLineDto>()
        var rareMembers = 0
        var rareRecognised = 0
        for ((_, entries) in groups) {
            val first = entries.minBy { it.raw }
            if (entries.size >= CountryLegacyMapping.MIN_GROUP_SIZE) {
                lines += LegacyLineDto(value = first.raw, outcome = first.outcome, targets = first.targets, count = entries.size)
            } else {
                rareMembers += entries.size
                if (first.outcome == LegacyOutcome.RECOGNISED) rareRecognised += entries.size
            }
        }
        return LegacySectionDto(
            totalMembersWithLegacy = plan.entries.size,
            recognisedMembers = plan.entries.count { it.outcome == LegacyOutcome.RECOGNISED },
            ambiguousMembers = plan.entries.count { it.outcome == LegacyOutcome.AMBIGUOUS },
            unrecognisedMembers = plan.entries.count { it.outcome == LegacyOutcome.UNRECOGNISED },
            protectedSkippedMembers = plan.protectedSkipped,
            lines = lines.sortedWith(compareByDescending<LegacyLineDto> { it.count }.thenBy { it.value }),
            rare = RareSummaryDto(members = rareMembers, recognised = rareRecognised),
        )
    }

    /**
     * SHA-256 over the whole plan: for every distinct normalised legacy value (rare ones included) its outcome and target codes,
     * sorted; NO counts and no member ids. A fresh [MessageDigest] per call (digests are not thread-safe).
     */
    private fun fingerprint(plan: Plan): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun feed(
            label: String,
            section: SectionPlan,
        ) {
            section.entries
                .map { "$label|${it.key}|${it.outcome}|${it.targets.joinToString(",")}" }
                .distinct()
                .sorted()
                .forEach { digest.update((it + "\n").toByteArray(Charsets.UTF_8)) }
            digest.update("$label|protected|${section.protectedSkipped > 0}\n".toByteArray(Charsets.UTF_8))
        }
        feed("c", plan.country)
        feed("n", plan.nationality)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- apply -----------------------------------------------------------------------------------------------------------------

    /**
     * Applies exactly the plan the dry run showed. ONE transaction: the plan is re-computed, a different fingerprint is a
     * [ConflictException]("report outdated") and nothing is written; at most [CountryLegacyMapping.MAX_APPLY_ROWS] rows. Per member,
     * ascending id: row lock first ([forMemberUpdate]), then a compare-and-set -- the code is only set while the member still has no
     * code AND the legacy text is still exactly the value the plan was made for.
     */
    fun apply(
        actor: CurrentMember,
        expectedFingerprint: String,
        now: LocalDateTime,
        maxRows: Int = CountryLegacyMapping.MAX_APPLY_ROWS,
    ): CountryLegacyMappingResultDto =
        transaction {
            relaxSessionTimeouts()
            val plan = buildPlan(facts = loadFacts(), actorId = actor.memberId)
            if (!constantTimeEquals(a = fingerprint(plan), b = expectedFingerprint)) throw ConflictException("report outdated")
            val countryTodo = plan.country.entries.filter { it.outcome == LegacyOutcome.RECOGNISED }
            val nationalityTodo = plan.nationality.entries.filter { it.outcome == LegacyOutcome.RECOGNISED }
            if (countryTodo.size + nationalityTodo.size > maxRows) throw ConflictException("too many rows")

            val countryApplied = applyCountry(countryTodo)
            val nationalityApplied = applyNationality(nationalityTodo)

            val countryCounts = counts(plan = plan.country, applied = countryApplied)
            val nationalityCounts = counts(plan = plan.nationality, applied = nationalityApplied)
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.MEMBER,
                entityId = actor.memberId,
                action = AuditAction.UPDATE,
                after =
                    "${AuditMarkers.MEMBER_COUNTRY_LEGACY_MAPPED} " +
                        "country=${countryCounts.recognised}/${countryCounts.applied}/${countryCounts.open}/${countryCounts.ambiguous} " +
                        "nationality=${nationalityCounts.recognised}/${nationalityCounts.applied}/${nationalityCounts.open}/${nationalityCounts.ambiguous}",
                occurredAt = now,
            )
            logger.info {
                "Country legacy mapping applied: country applied=${countryCounts.applied}, nationality applied=${nationalityCounts.applied}"
            }
            CountryLegacyMappingResultDto(country = countryCounts, nationality = nationalityCounts)
        }

    private fun JdbcTransaction.applyCountry(todo: List<PlanEntry>): Int {
        var applied = 0
        for (entry in todo.sortedBy { it.memberId }) {
            val row =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq entry.memberId }
                    .forMemberUpdate()
                    .singleOrNull() ?: continue
            if (row[MemberTable.countryCode] != null || row[MemberTable.country] != entry.raw) continue
            MemberCountryStore.setCountryKeepLegacy(memberId = entry.memberId, code = entry.targets.single())
            applied++
        }
        return applied
    }

    private fun JdbcTransaction.applyNationality(todo: List<PlanEntry>): Int {
        var applied = 0
        for (entry in todo.sortedBy { it.memberId }) {
            val row =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq entry.memberId }
                    .forMemberUpdate()
                    .singleOrNull() ?: continue
            if (row[MemberTable.nationality] != entry.raw || MemberCountryStore.hasNationality(entry.memberId)) continue
            MemberCountryStore.replaceNationalities(memberId = entry.memberId, codes = entry.targets, clearLegacy = false)
            applied++
        }
        return applied
    }

    private fun counts(
        plan: SectionPlan,
        applied: Int,
    ): CountryLegacyCountsDto =
        CountryLegacyCountsDto(
            recognised = plan.entries.count { it.outcome == LegacyOutcome.RECOGNISED },
            applied = applied,
            open = plan.entries.size - applied,
            ambiguous = plan.entries.count { it.outcome == LegacyOutcome.AMBIGUOUS },
        )

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
