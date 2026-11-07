package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgStatementRules
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.283 (R3b) -- the ONLY reader and writer of `partg_report_sponsor`, `partg_report_sponsoring_entry` and `partg_report_youth_org`
 * (pinned by `PartgAnnexStoreTripwireTest`; the one exception is `PartgReportPersonalData`). Statement C and F. Since V1.9.287 (R3c) also
 * `partg_report_enterprise` (statement D).
 *
 * Every function runs inside the caller's transaction. The caller (`PartgAnnexWriter`) holds the lock of the annex header and bumps its revision
 * in the same transaction. Sponsor rows are PERSONAL DATA OF THIRD PARTIES: this object never logs and never puts a field into an exception.
 * Sorting happens in Kotlin, never in SQL, so that H2 and PostgreSQL produce the same order.
 */
internal object PartgStatementStore {
    data class EntryRow(
        val id: Uuid,
        val sponsorId: Uuid,
        val date: LocalDate,
        val amountCents: Long,
        val purpose: String?,
    )

    data class SponsorRow(
        val id: Uuid,
        val annexId: Uuid,
        val personKind: PartgPersonKind,
        val name: String,
        val street: String?,
        val postalCode: String?,
        val city: String?,
        val country: String,
        /** Sorted like [sortEntries]. */
        val entries: List<EntryRow>,
    ) {
        val yearlySumCents: Long get() = PartgStatementRules.yearlySum(entries.map { it.amountCents })
    }

    data class YouthOrgRow(
        val id: Uuid,
        val annexId: Uuid,
        val name: String,
        val relationship: String?,
        val incomeCents: Long?,
        val expensesCents: Long?,
    )

    /** V1.9.287 (R3c): one enterprise of statement D. [createdAt] is the sort key (then the id). */
    data class EnterpriseRow(
        val id: Uuid,
        val annexId: Uuid,
        val name: String,
        val legalForm: String?,
        val seat: String?,
        val shareBasisPoints: Int?,
        val incomeCents: Long?,
        val expensesCents: Long?,
        val createdAt: LocalDateTime,
    )

    /** Yearly sum descending, name, id. */
    fun sortSponsors(list: List<SponsorRow>): List<SponsorRow> =
        list.sortedWith(
            compareByDescending<SponsorRow> { it.yearlySumCents }
                .thenBy { it.name }
                .thenBy { it.id.toString() },
        )

    /** Date, id. */
    fun sortEntries(list: List<EntryRow>): List<EntryRow> = list.sortedWith(compareBy<EntryRow> { it.date }.thenBy { it.id.toString() })

    /** Name, id. */
    fun sortYouthOrgs(list: List<YouthOrgRow>): List<YouthOrgRow> =
        list.sortedWith(compareBy<YouthOrgRow> { it.name }.thenBy { it.id.toString() })

    /** Created at, id (stable, independent of what is typed). */
    fun sortEnterprises(list: List<EnterpriseRow>): List<EnterpriseRow> =
        list.sortedWith(compareBy<EnterpriseRow> { it.createdAt }.thenBy { it.id.toString() })

    private fun toEnterprise(row: ResultRow) =
        EnterpriseRow(
            id = row[PartgReportEnterpriseTable.id],
            annexId = row[PartgReportEnterpriseTable.annexId],
            name = row[PartgReportEnterpriseTable.name],
            legalForm = row[PartgReportEnterpriseTable.legalForm],
            seat = row[PartgReportEnterpriseTable.seat],
            shareBasisPoints = row[PartgReportEnterpriseTable.shareBasisPoints],
            incomeCents = row[PartgReportEnterpriseTable.incomeCents],
            expensesCents = row[PartgReportEnterpriseTable.expensesCents],
            createdAt = row[PartgReportEnterpriseTable.createdAt],
        )

    private fun toEntry(row: ResultRow) =
        EntryRow(
            id = row[PartgReportSponsoringEntryTable.id],
            sponsorId = row[PartgReportSponsoringEntryTable.sponsorId],
            date = row[PartgReportSponsoringEntryTable.sponsoredOn],
            amountCents = row[PartgReportSponsoringEntryTable.amountCents],
            purpose = row[PartgReportSponsoringEntryTable.purpose],
        )

    private fun toSponsor(
        row: ResultRow,
        entries: List<EntryRow>,
    ) = SponsorRow(
        id = row[PartgReportSponsorTable.id],
        annexId = row[PartgReportSponsorTable.annexId],
        personKind = row[PartgReportSponsorTable.personKind],
        name = row[PartgReportSponsorTable.name],
        street = row[PartgReportSponsorTable.street],
        postalCode = row[PartgReportSponsorTable.postalCode],
        city = row[PartgReportSponsorTable.city],
        country = row[PartgReportSponsorTable.country],
        entries = sortEntries(entries),
    )

    private fun toYouthOrg(row: ResultRow) =
        YouthOrgRow(
            id = row[PartgReportYouthOrgTable.id],
            annexId = row[PartgReportYouthOrgTable.annexId],
            name = row[PartgReportYouthOrgTable.name],
            relationship = row[PartgReportYouthOrgTable.relationship],
            incomeCents = row[PartgReportYouthOrgTable.incomeCents],
            expensesCents = row[PartgReportYouthOrgTable.expensesCents],
        )

    // ---------------------------------------------------------------- sponsors

    /** The sponsors of this annex with their entries, sorted. Two statements. */
    fun sponsors(annexId: Uuid): List<SponsorRow> = sponsorsByAnnex(listOf(annexId))[annexId].orEmpty()

    /** The sponsors of all [annexIds], grouped and sorted. Two statements. */
    fun sponsorsByAnnex(annexIds: List<Uuid>): Map<Uuid, List<SponsorRow>> {
        if (annexIds.isEmpty()) return emptyMap()
        val heads = PartgReportSponsorTable.selectAll().where { PartgReportSponsorTable.annexId inList annexIds }.toList()
        if (heads.isEmpty()) return emptyMap()
        val entriesBySponsor =
            PartgReportSponsoringEntryTable
                .selectAll()
                .where { PartgReportSponsoringEntryTable.sponsorId inList heads.map { it[PartgReportSponsorTable.id] } }
                .map(::toEntry)
                .groupBy { it.sponsorId }
        return heads
            .map { toSponsor(row = it, entries = entriesBySponsor[it[PartgReportSponsorTable.id]].orEmpty()) }
            .groupBy { it.annexId }
            .mapValues { sortSponsors(it.value) }
    }

    fun sponsorCount(annexId: Uuid): Int =
        PartgReportSponsorTable
            .selectAll()
            .where { PartgReportSponsorTable.annexId eq annexId }
            .count()
            .toInt()

    /** All entries of the annex (join sponsor -> entry), for the per-unit cap. */
    fun entryCountOfAnnex(annexId: Uuid): Int {
        val sponsorIds =
            PartgReportSponsorTable
                .selectAll()
                .where { PartgReportSponsorTable.annexId eq annexId }
                .map { it[PartgReportSponsorTable.id] }
        if (sponsorIds.isEmpty()) return 0
        return PartgReportSponsoringEntryTable
            .selectAll()
            .where { PartgReportSponsoringEntryTable.sponsorId inList sponsorIds }
            .count()
            .toInt()
    }

    /** The sponsor of this annex only; a sponsor of another annex (year or unit) is invisible. */
    fun findSponsor(
        annexId: Uuid,
        sponsorId: Uuid,
    ): SponsorRow? {
        val head =
            PartgReportSponsorTable
                .selectAll()
                .where { (PartgReportSponsorTable.id eq sponsorId) and (PartgReportSponsorTable.annexId eq annexId) }
                .singleOrNull() ?: return null
        val entries =
            PartgReportSponsoringEntryTable
                .selectAll()
                .where { PartgReportSponsoringEntryTable.sponsorId eq sponsorId }
                .map(::toEntry)
        return toSponsor(row = head, entries = entries)
    }

    fun insertSponsor(
        sponsor: SponsorRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportSponsorTable.insert {
            it[id] = sponsor.id
            it[annexId] = sponsor.annexId
            it[personKind] = sponsor.personKind
            it[name] = sponsor.name
            it[street] = sponsor.street
            it[postalCode] = sponsor.postalCode
            it[city] = sponsor.city
            it[country] = sponsor.country
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun updateSponsorHead(
        sponsor: SponsorRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportSponsorTable.update(
            { (PartgReportSponsorTable.id eq sponsor.id) and (PartgReportSponsorTable.annexId eq sponsor.annexId) },
        ) {
            it[personKind] = sponsor.personKind
            it[name] = sponsor.name
            it[street] = sponsor.street
            it[postalCode] = sponsor.postalCode
            it[city] = sponsor.city
            it[country] = sponsor.country
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    /**
     * Makes the entries of ONE sponsor equal to [entries]: ids present in the input are updated, new ones inserted, missing ones deleted.
     * Never touches another sponsor (every statement is scoped to [sponsorId]).
     */
    fun replaceEntries(
        sponsorId: Uuid,
        entries: List<EntryRow>,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        val existing =
            PartgReportSponsoringEntryTable
                .selectAll()
                .where { PartgReportSponsoringEntryTable.sponsorId eq sponsorId }
                .map { it[PartgReportSponsoringEntryTable.id] }
                .toSet()
        val keep = entries.map { it.id }.toSet()
        val removed = existing - keep
        if (removed.isNotEmpty()) {
            PartgReportSponsoringEntryTable.deleteWhere {
                (PartgReportSponsoringEntryTable.sponsorId eq sponsorId) and (PartgReportSponsoringEntryTable.id inList removed.toList())
            }
        }
        entries.forEach { entry ->
            if (entry.id in existing) {
                PartgReportSponsoringEntryTable.update(
                    { (PartgReportSponsoringEntryTable.id eq entry.id) and (PartgReportSponsoringEntryTable.sponsorId eq sponsorId) },
                ) {
                    it[sponsoredOn] = entry.date
                    it[amountCents] = entry.amountCents
                    it[purpose] = entry.purpose
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            } else {
                PartgReportSponsoringEntryTable.insert {
                    it[id] = entry.id
                    it[PartgReportSponsoringEntryTable.sponsorId] = sponsorId
                    it[sponsoredOn] = entry.date
                    it[amountCents] = entry.amountCents
                    it[purpose] = entry.purpose
                    it[createdAt] = now
                    it[createdBy] = actor
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
        }
    }

    /** Entries first, then the head: there is no ON DELETE CASCADE. */
    fun deleteSponsor(
        annexId: Uuid,
        sponsorId: Uuid,
    ) {
        if (findSponsor(annexId = annexId, sponsorId = sponsorId) == null) return
        PartgReportSponsoringEntryTable.deleteWhere { PartgReportSponsoringEntryTable.sponsorId eq sponsorId }
        PartgReportSponsorTable.deleteWhere { (PartgReportSponsorTable.id eq sponsorId) and (PartgReportSponsorTable.annexId eq annexId) }
    }

    // ---------------------------------------------------------------- youth organisations

    fun youthOrgs(annexId: Uuid): List<YouthOrgRow> = youthOrgsByAnnex(listOf(annexId))[annexId].orEmpty()

    fun youthOrgsByAnnex(annexIds: List<Uuid>): Map<Uuid, List<YouthOrgRow>> {
        if (annexIds.isEmpty()) return emptyMap()
        return PartgReportYouthOrgTable
            .selectAll()
            .where { PartgReportYouthOrgTable.annexId inList annexIds }
            .map(::toYouthOrg)
            .groupBy { it.annexId }
            .mapValues { sortYouthOrgs(it.value) }
    }

    fun youthOrgCount(annexId: Uuid): Int =
        PartgReportYouthOrgTable
            .selectAll()
            .where { PartgReportYouthOrgTable.annexId eq annexId }
            .count()
            .toInt()

    fun findYouthOrg(
        annexId: Uuid,
        youthOrgId: Uuid,
    ): YouthOrgRow? =
        PartgReportYouthOrgTable
            .selectAll()
            .where { (PartgReportYouthOrgTable.id eq youthOrgId) and (PartgReportYouthOrgTable.annexId eq annexId) }
            .singleOrNull()
            ?.let(::toYouthOrg)

    fun insertYouthOrg(
        youthOrg: YouthOrgRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportYouthOrgTable.insert {
            it[id] = youthOrg.id
            it[annexId] = youthOrg.annexId
            it[name] = youthOrg.name
            it[relationship] = youthOrg.relationship
            it[incomeCents] = youthOrg.incomeCents
            it[expensesCents] = youthOrg.expensesCents
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun updateYouthOrg(
        youthOrg: YouthOrgRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportYouthOrgTable.update(
            { (PartgReportYouthOrgTable.id eq youthOrg.id) and (PartgReportYouthOrgTable.annexId eq youthOrg.annexId) },
        ) {
            it[name] = youthOrg.name
            it[relationship] = youthOrg.relationship
            it[incomeCents] = youthOrg.incomeCents
            it[expensesCents] = youthOrg.expensesCents
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun deleteYouthOrg(
        annexId: Uuid,
        youthOrgId: Uuid,
    ) {
        PartgReportYouthOrgTable.deleteWhere {
            (PartgReportYouthOrgTable.id eq youthOrgId) and (PartgReportYouthOrgTable.annexId eq annexId)
        }
    }

    // ---------------------------------------------------------------- enterprises (statement D)

    fun enterprises(annexId: Uuid): List<EnterpriseRow> = enterprisesByAnnex(listOf(annexId))[annexId].orEmpty()

    fun enterprisesByAnnex(annexIds: List<Uuid>): Map<Uuid, List<EnterpriseRow>> {
        if (annexIds.isEmpty()) return emptyMap()
        return PartgReportEnterpriseTable
            .selectAll()
            .where { PartgReportEnterpriseTable.annexId inList annexIds }
            .map(::toEnterprise)
            .groupBy { it.annexId }
            .mapValues { sortEnterprises(it.value) }
    }

    fun enterpriseCount(annexId: Uuid): Int =
        PartgReportEnterpriseTable
            .selectAll()
            .where { PartgReportEnterpriseTable.annexId eq annexId }
            .count()
            .toInt()

    /** The enterprise of this annex only; one of another annex (year or unit) is invisible. */
    fun findEnterprise(
        annexId: Uuid,
        enterpriseId: Uuid,
    ): EnterpriseRow? =
        PartgReportEnterpriseTable
            .selectAll()
            .where { (PartgReportEnterpriseTable.id eq enterpriseId) and (PartgReportEnterpriseTable.annexId eq annexId) }
            .singleOrNull()
            ?.let(::toEnterprise)

    fun insertEnterprise(
        enterprise: EnterpriseRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportEnterpriseTable.insert {
            it[id] = enterprise.id
            it[annexId] = enterprise.annexId
            it[name] = enterprise.name
            it[legalForm] = enterprise.legalForm
            it[seat] = enterprise.seat
            it[shareBasisPoints] = enterprise.shareBasisPoints
            it[incomeCents] = enterprise.incomeCents
            it[expensesCents] = enterprise.expensesCents
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    /** `created_*` stay unchanged. */
    fun updateEnterprise(
        enterprise: EnterpriseRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportEnterpriseTable.update(
            { (PartgReportEnterpriseTable.id eq enterprise.id) and (PartgReportEnterpriseTable.annexId eq enterprise.annexId) },
        ) {
            it[name] = enterprise.name
            it[legalForm] = enterprise.legalForm
            it[seat] = enterprise.seat
            it[shareBasisPoints] = enterprise.shareBasisPoints
            it[incomeCents] = enterprise.incomeCents
            it[expensesCents] = enterprise.expensesCents
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun deleteEnterprise(
        annexId: Uuid,
        enterpriseId: Uuid,
    ) {
        PartgReportEnterpriseTable.deleteWhere {
            (PartgReportEnterpriseTable.id eq enterpriseId) and (PartgReportEnterpriseTable.annexId eq annexId)
        }
    }
}
