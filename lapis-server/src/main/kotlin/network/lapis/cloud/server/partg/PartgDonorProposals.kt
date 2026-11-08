package network.lapis.cloud.server.partg

import network.lapis.cloud.server.partg.report.PartgDonorConsolidation
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDonorProposalBlock
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRowDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRules
import network.lapis.cloud.shared.domain.PartgDonorProposalStatus
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import java.security.MessageDigest

/**
 * V1.9.293 (R5b) -- the proposal for statement B of the FEDERAL unit, computed from the donation sums ([PartgDonationYear]), the donor master data
 * and the rows entered so far. PURE: no table access (the readers are [PartgLedgerReader], [PartgDonorMasterData] and [PartgAnnexStore]).
 *
 * Contains personal data of third parties (names, addresses): never logged, never audited, never put into an exception.
 */
internal object PartgDonorProposals {
    private val naturalCategories =
        setOf(
            DonorCategory.GERMAN_NATURAL_PERSON,
            DonorCategory.EU_NATURAL_PERSON,
            DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
        )

    private val displayOrder =
        listOf(
            PartgDonorProposalStatus.NEW,
            PartgDonorProposalStatus.SIMILAR,
            PartgDonorProposalStatus.DIFFERENT,
            PartgDonorProposalStatus.NOT_TRANSFERABLE,
            PartgDonorProposalStatus.UNCHANGED,
            PartgDonorProposalStatus.ENTERED_ONLY,
        )

    /** The donor keys (`M:`/`X:`) whose yearly sum is above the threshold: only for these the master data is needed. */
    fun candidateKeys(donations: PartgDonationYear): List<PartgDonationTotal> =
        donations.totals.filter { PartgDonorProposalRules.aboveThreshold(it.totalCents) }

    fun keyOf(total: PartgDonationTotal): String =
        if (total.donorMemberId !=
            null
        ) {
            "M:${total.donorMemberId}"
        } else {
            "X:${total.externalDonorId}"
        }

    fun compute(
        year: Int,
        annex: PartgAnnexStore.AnnexRow?,
        donors: List<PartgAnnexStore.DonorRow>,
        donations: PartgDonationYear,
        master: Map<String, PartgDonorMaster>,
    ): PartgDonorProposalDto {
        val linkedByKey = donors.filter { it.sourceDonorKey != null }.associateBy { it.sourceDonorKey!! }
        val unlinked = donors.filter { it.sourceDonorKey == null }
        val rows = ArrayList<PartgDonorProposalRowDto>()
        val claimed = HashSet<String>()
        var anonymised = 0
        candidateKeys(donations).forEach { total ->
            val key = keyOf(total)
            val m = master[key]
            if (m?.anonymised == true) {
                anonymised += 1
                // a row already linked to this donor stays claimed: it must not reappear as an ENTERED_ONLY row
                linkedByKey[key]?.let { claimed += it.id.toString() }
                return@forEach
            }
            val kind =
                when {
                    total.categories.all { it in naturalCategories } -> PartgPersonKind.NATURAL
                    total.categories.none { it in naturalCategories } -> PartgPersonKind.LEGAL
                    else -> null
                }
            val linked = linkedByKey[key]
            val masterBlock = masterBlockOf(master = m, kind = kind, bookedCents = total.totalCents)
            val similar =
                if (linked == null && masterBlock == null && m != null && kind != null) {
                    unlinked.filter { similarTo(row = it, master = m, kind = kind) }.map { it.id.toString() }.sorted()
                } else {
                    emptyList()
                }
            val c =
                PartgDonorProposalRules.classify(
                    bookedCents = total.totalCents,
                    linked = linked?.let { it.amountCents to it.contributionKind },
                    similarCount = similar.size,
                    masterBlock = masterBlock,
                )
            linked?.let { claimed += it.id.toString() }
            rows +=
                PartgDonorProposalRowDto(
                    ref = key,
                    status = c.status,
                    personKind = kind,
                    name = m?.name.orEmpty(),
                    street = m?.street,
                    postalCode = m?.postalCode,
                    city = m?.city,
                    country = m?.country,
                    bookedCents = total.totalCents,
                    enteredCents = linked?.amountCents,
                    linkedRowId = linked?.id?.toString(),
                    similarRowIds = similar,
                    allowedActions = c.allowed,
                    preselected = c.preselected,
                    block = c.block,
                )
        }
        donors.filter { it.id.toString() !in claimed }.forEach { d ->
            rows +=
                PartgDonorProposalRowDto(
                    ref = null,
                    status = PartgDonorProposalStatus.ENTERED_ONLY,
                    personKind = d.personKind,
                    name = d.name,
                    street = d.street,
                    postalCode = d.postalCode,
                    city = d.city,
                    country = d.country,
                    bookedCents = null,
                    enteredCents = d.amountCents,
                    linkedRowId = d.id.toString(),
                    allowedActions = emptySet(),
                    preselected = null,
                    linkedBelowThreshold = d.sourceDonorKey != null,
                )
        }
        if (rows.size > PartgDonorProposalRules.MAX_ROWS) throw PartyReportInvalidInputException("TOO_MANY")
        val sorted =
            rows.sortedWith(
                compareBy<PartgDonorProposalRowDto> { displayOrder.indexOf(it.status) }
                    .thenByDescending { it.bookedCents ?: it.enteredCents ?: 0L }
                    .thenBy { it.name }
                    .thenBy { it.ref ?: it.linkedRowId.orEmpty() },
            )
        val revision = annex?.revision ?: 0L
        return PartgDonorProposalDto(
            year = year,
            annexRevision = revision,
            checksum =
                checksum(
                    year = year,
                    revision = revision,
                    donors = donors,
                    rows = sorted,
                    anonymised = anonymised,
                    without = donations.withoutDonorCount,
                ),
            rows = sorted,
            anonymisedCount = anonymised,
            withoutDonorCount = donations.withoutDonorCount,
        )
    }

    private fun masterBlockOf(
        master: PartgDonorMaster?,
        kind: PartgPersonKind?,
        bookedCents: Long,
    ): PartgDonorProposalBlock? {
        if (master == null) return PartgDonorProposalBlock.MASTER_DATA_INVALID
        if (kind == null) return PartgDonorProposalBlock.KIND_UNCLEAR
        val country = master.country ?: return PartgDonorProposalBlock.COUNTRY_MISSING
        val violation =
            PartgAnnexRules.validateDonor(
                PartgLargeDonorInputDto(
                    id = null,
                    personKind = kind,
                    name = master.name,
                    street = master.street,
                    postalCode = master.postalCode,
                    city = master.city,
                    country = country,
                    contributionKind = PartgContributionKind.DONATION,
                    amountCents = bookedCents,
                ),
            )
        return if (violation != null) PartgDonorProposalBlock.MASTER_DATA_INVALID else null
    }

    /** Same kind, same normalised and non-empty name, same non-empty postal code and same country. */
    private fun similarTo(
        row: PartgAnnexStore.DonorRow,
        master: PartgDonorMaster,
        kind: PartgPersonKind,
    ): Boolean {
        if (row.personKind != kind || row.country != master.country) return false
        val name = PartgDonorConsolidation.normalizeName(master.name)
        if (name.isEmpty() || PartgDonorConsolidation.normalizeName(row.name) != name) return false
        val postal = master.postalCode?.takeIf { it.isNotBlank() } ?: return false
        return row.postalCode?.trim().equals(postal.trim(), ignoreCase = true)
    }

    /**
     * SHA-256 (hex, lower case) over a canonical, length-prefixed text of everything the proposal shows plus the entered rows of the unit. A NEW
     * [MessageDigest] per call (not thread-safe).
     */
    private fun checksum(
        year: Int,
        revision: Long,
        donors: List<PartgAnnexStore.DonorRow>,
        rows: List<PartgDonorProposalRowDto>,
        anonymised: Int,
        without: Int,
    ): String {
        val text = StringBuilder()

        fun field(value: Any?) {
            val s = value?.toString()
            if (s == null) {
                text.append("~|")
            } else {
                text
                    .append(s.length)
                    .append(':')
                    .append(s)
                    .append('|')
            }
        }
        field("R5b1")
        field(year)
        field(revision)
        field(anonymised)
        field(without)
        donors.sortedBy { it.id.toString() }.forEach { d ->
            field(d.id)
            field(d.sourceDonorKey)
            field(d.amountCents)
            field(d.name)
            field(d.street)
            field(d.postalCode)
            field(d.city)
            field(d.country)
            field(d.personKind)
            field(d.contributionKind)
            text.append('\n')
        }
        rows.sortedBy { it.ref ?: ("~" + it.linkedRowId.orEmpty()) }.forEach { r ->
            field(r.ref)
            field(r.status)
            field(r.personKind)
            field(r.name)
            field(r.street)
            field(r.postalCode)
            field(r.city)
            field(r.country)
            field(r.bookedCents)
            field(r.enteredCents)
            field(r.linkedRowId)
            field(r.similarRowIds.joinToString(","))
            field(
                r.allowedActions
                    .map { it.name }
                    .sorted()
                    .joinToString(","),
            )
            field(r.preselected)
            field(r.block)
            field(r.linkedBelowThreshold)
            text.append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
