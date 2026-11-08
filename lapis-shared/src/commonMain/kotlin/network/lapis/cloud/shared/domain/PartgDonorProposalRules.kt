package network.lapis.cloud.shared.domain

/** The numbers shown on the button: how many rows are created, how many amounts replaced, how many rows linked. */
data class PartgDonorProposalCounts(
    val created: Int,
    val replaced: Int,
    val linked: Int,
)

/**
 * V1.9.293 (R5b) -- the pure rules of the proposal for statement B, shared by server and client (like [PartgLedgerRules]). The threshold is
 * [PartgAnnexRules.LARGE_DONOR_THRESHOLD_CENTS], strictly greater -- no new reading of the law and no paragraph number is added here.
 */
object PartgDonorProposalRules {
    const val MAX_ROWS = 1000
    const val MAX_PICKS = 1000
    const val CHECKSUM_LENGTH = 64

    private val refPattern = Regex("[MX]:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val checksumPattern = Regex("[0-9a-f]{64}")

    fun validRef(ref: String): Boolean = refPattern.matches(ref)

    fun validChecksum(checksum: String): Boolean = checksumPattern.matches(checksum)

    /** `true` iff the yearly sum is listed in statement B at all. */
    fun aboveThreshold(totalCents: Long): Boolean = totalCents > PartgAnnexRules.LARGE_DONOR_THRESHOLD_CENTS

    /** Status, allowed actions and the preselected action of one row; see the table in `docs/features/partg-report.adoc`. */
    data class Classification(
        val status: PartgDonorProposalStatus,
        val allowed: Set<PartgDonorProposalAction>,
        val preselected: PartgDonorProposalAction?,
        val block: PartgDonorProposalBlock?,
    )

    /**
     * @param bookedCents the yearly sum according to the bookings, strictly above the threshold
     * @param linked the entered row that already carries the key of the donor, if any (amount and contribution kind)
     * @param similarCount entered rows without a key that look like the same person
     * @param masterBlock a problem with the master data of the donor, or `null`
     */
    fun classify(
        bookedCents: Long,
        linked: Pair<Long, PartgContributionKind>?,
        similarCount: Int,
        masterBlock: PartgDonorProposalBlock?,
    ): Classification {
        if (linked != null) {
            val (enteredCents, kind) = linked
            return when {
                enteredCents == bookedCents ->
                    Classification(status = PartgDonorProposalStatus.UNCHANGED, allowed = emptySet(), preselected = null, block = null)
                kind != PartgContributionKind.DONATION ->
                    Classification(
                        status = PartgDonorProposalStatus.NOT_TRANSFERABLE,
                        allowed = emptySet(),
                        preselected = null,
                        block = PartgDonorProposalBlock.CONTAINS_FEES,
                    )
                else ->
                    Classification(
                        status = PartgDonorProposalStatus.DIFFERENT,
                        allowed = setOf(PartgDonorProposalAction.REPLACE_AMOUNT),
                        preselected = null,
                        block = null,
                    )
            }
        }
        if (masterBlock != null) {
            return Classification(
                status = PartgDonorProposalStatus.NOT_TRANSFERABLE,
                allowed = emptySet(),
                preselected = null,
                block = masterBlock,
            )
        }
        return if (similarCount > 0) {
            Classification(
                status = PartgDonorProposalStatus.SIMILAR,
                allowed = setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE),
                preselected = null,
                block = null,
            )
        } else {
            Classification(
                status = PartgDonorProposalStatus.NEW,
                allowed = setOf(PartgDonorProposalAction.CREATE),
                preselected = PartgDonorProposalAction.CREATE,
                block = null,
            )
        }
    }

    /** `null` when the picks are acceptable for [rows], otherwise a short error code (never a name or an amount). */
    fun validatePicks(
        rows: List<PartgDonorProposalRowDto>,
        picks: List<PartgDonorProposalPickDto>,
    ): String? {
        if (picks.isEmpty()) return "NO_PICKS"
        if (picks.size > MAX_PICKS) return "TOO_MANY_PICKS"
        val byRef = rows.filter { it.ref != null }.associateBy { it.ref }
        val seenRefs = HashSet<String>()
        val seenTargets = HashSet<String>()
        picks.forEach { pick ->
            if (!seenRefs.add(pick.ref)) return "DUPLICATE_REF"
            val row = byRef[pick.ref] ?: return "UNKNOWN_REF"
            if (pick.action !in row.allowedActions) return "ACTION_NOT_ALLOWED"
            if (pick.action == PartgDonorProposalAction.LINK) {
                val target = pick.targetRowId ?: return "TARGET_MISSING"
                if (target !in row.similarRowIds) return "TARGET_INVALID"
                if (!seenTargets.add(target)) return "DUPLICATE_TARGET"
            } else if (pick.targetRowId != null) {
                return "TARGET_UNEXPECTED"
            }
        }
        return null
    }

    fun counts(picks: List<PartgDonorProposalPickDto>): PartgDonorProposalCounts =
        PartgDonorProposalCounts(
            created = picks.count { it.action == PartgDonorProposalAction.CREATE },
            replaced = picks.count { it.action == PartgDonorProposalAction.REPLACE_AMOUNT },
            linked = picks.count { it.action == PartgDonorProposalAction.LINK },
        )

    /** The origin of an entered row: no take-over date = by hand; changed after the take-over = edited. */
    fun originOf(
        sourceAppliedAt: kotlinx.datetime.LocalDateTime?,
        updatedAt: kotlinx.datetime.LocalDateTime?,
    ): PartgLargeDonorOrigin =
        when {
            sourceAppliedAt == null -> PartgLargeDonorOrigin.MANUAL
            updatedAt == null || updatedAt <= sourceAppliedAt -> PartgLargeDonorOrigin.LEDGER
            else -> PartgLargeDonorOrigin.LEDGER_EDITED
        }
}
