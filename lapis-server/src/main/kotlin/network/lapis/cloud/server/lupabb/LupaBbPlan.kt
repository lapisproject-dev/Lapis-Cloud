package network.lapis.cloud.server.lupabb

import network.lapis.cloud.shared.domain.LupaBbCandidateDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbFieldDiffDto
import network.lapis.cloud.shared.domain.LupaBbNoticeCode
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewRowDto
import network.lapis.cloud.shared.domain.LupaBbReasonDto
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.uuid.Uuid

/** One compared field: the value in Lapis ([before]) and the value LupaBB wants ([after]); `null` = empty. */
internal data class FieldDiff(
    val field: LupaBbField,
    val before: String?,
    val after: String?,
)

internal data class PlanReason(
    val code: LupaBbRowReasonCode,
    val param: String? = null,
)

internal data class PlanCandidate(
    val memberId: Uuid,
    val name: String,
    val email: String,
)

/**
 * V1.9.226 -- one row of the comparison. [record] is set for [LupaBbCategory.NEW] and [LupaBbCategory.CHANGED]; [fingerprint] is the SHA-256 of
 * the Lapis side at the time of the comparison and lets the apply step detect that the member changed in between.
 */
internal data class PlanRow(
    val personNumber: Long,
    val name: String,
    val email: String?,
    val category: LupaBbCategory,
    val memberId: Uuid? = null,
    val record: LupaBbRecord? = null,
    val diffs: List<FieldDiff> = emptyList(),
    val statusFrom: MemberStatus? = null,
    val reasons: List<PlanReason> = emptyList(),
    val candidates: List<PlanCandidate> = emptyList(),
    val fingerprint: String? = null,
    /** The regional chapter to assign (only where the member has none). */
    val chapterId: Uuid? = null,
    /** For a row held back for manual handling (`ADMIN_ROLE_MANUAL`): everything "take over the LupaBB values" would write. Never the role. */
    val protectedDiffs: List<FieldDiff> = emptyList(),
    /** The chapter that belongs to [protectedDiffs] (the unfiltered one). */
    val protectedChapterId: Uuid? = null,
    val isOwnRecord: Boolean = false,
    val protectedBlocker: LupaBbRowReasonCode? = null,
    /** V1.9.303 -- for `EMAIL_BELONGS_TO_OTHER` (member already linked): the member holding the e-mail. ADMIN-only RPC data, never logged or audited. */
    val conflictMemberId: Uuid? = null,
    val conflictMemberName: String? = null,
    val conflictPersonNumber: Long? = null,
    val conflictLikelyDuplicate: Boolean = false,
) {
    val statusTo: MemberStatus?
        get() =
            record?.status?.takeIf {
                statusFrom != null &&
                    it != statusFrom &&
                    (category != LupaBbCategory.CHANGED || diffs.any { d -> d.field == LupaBbField.STATUS })
            }

    fun toDto(): LupaBbPreviewRowDto =
        LupaBbPreviewRowDto(
            personNumber = personNumber,
            name = name,
            email = email,
            category = category,
            diffs = diffs.map { LupaBbFieldDiffDto(field = it.field, before = it.before, after = it.after) },
            reasons = reasons.map { LupaBbReasonDto(code = it.code, param = it.param) },
            candidates = candidates.map { LupaBbCandidateDto(memberId = it.memberId.toString(), name = it.name, email = it.email) },
            memberId = memberId?.toString(),
            protectedDiffs = protectedDiffs.map { LupaBbFieldDiffDto(field = it.field, before = it.before, after = it.after) },
            isOwnRecord = isOwnRecord,
            protectedBlocker = protectedBlocker,
            conflictMemberId = conflictMemberId?.toString(),
            conflictMemberName = conflictMemberName,
            conflictPersonNumber = conflictPersonNumber,
            conflictLikelyDuplicate = conflictLikelyDuplicate,
        )
}

/** The complete comparison behind a preview token. */
internal class LupaBbPlan(
    val rows: List<PlanRow>,
    val notices: List<LupaBbNoticeCode>,
    val dataSha256: String,
) {
    fun counts(): Map<LupaBbCategory, Int> = LupaBbCategory.entries.associateWith { c -> rows.count { it.category == c } }
}

/** A row needs an administrator's decision: ambiguous, an error, or held back for manual handling. */
internal fun PlanRow.needsAction(): Boolean =
    category == LupaBbCategory.AMBIGUOUS ||
        category == LupaBbCategory.ERROR ||
        reasons.any { it.code == LupaBbRowReasonCode.ADMIN_ROLE_MANUAL }

internal fun List<PlanRow>.filtered(filter: LupaBbPreviewFilter): List<PlanRow> =
    when (filter) {
        LupaBbPreviewFilter.ALL -> this
        LupaBbPreviewFilter.ACTION_NEEDED ->
            filter { it.needsAction() }
        LupaBbPreviewFilter.NEW -> filter { it.category == LupaBbCategory.NEW }
        LupaBbPreviewFilter.CHANGED -> filter { it.category == LupaBbCategory.CHANGED }
        LupaBbPreviewFilter.UNCHANGED -> filter { it.category == LupaBbCategory.UNCHANGED }
        LupaBbPreviewFilter.AMBIGUOUS -> filter { it.category == LupaBbCategory.AMBIGUOUS }
        LupaBbPreviewFilter.SKIPPED -> filter { it.category == LupaBbCategory.SKIPPED }
        LupaBbPreviewFilter.ERROR -> filter { it.category == LupaBbCategory.ERROR }
        LupaBbPreviewFilter.MISSING -> filter { it.category == LupaBbCategory.MISSING }
    }
