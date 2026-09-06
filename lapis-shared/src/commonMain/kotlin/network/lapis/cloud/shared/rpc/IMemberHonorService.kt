package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberHonorDto
import network.lapis.cloud.shared.domain.MemberHonorInput
import network.lapis.cloud.shared.domain.MemberHonorLimits
import network.lapis.cloud.shared.domain.MemberHonorPageDto

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- BOARD/ADMIN read/write management
 * surface for `member_honor`, backed directly by `network.lapis.cloud.server.rpc.MemberHonorService`
 * (no separate Store/Policy pair -- see the Welle-Plan §1 "Architektur-Entscheidung" for why this
 * entity's shape does not warrant one, unlike `ICrmService`).
 *
 * **Rollen-Asymmetrie, bewusst**: every read/write method here is BOARD/ADMIN; [deleteHonor] alone
 * is ADMIN-only -- same posture [ICrmService.eraseContact] already establishes. Unlike
 * [ICrmService.eraseContact], this is NOT an Art. 17 erasure path -- it is a plain data-correction
 * DELETE for a mis-entered row (wrong member, duplicate entry), with no `dsgvo_audit_log` write.
 * The actual DSGVO erasure path for an honor's data lives entirely in
 * `network.lapis.cloud.server.dsgvo.MemberHonorPersonalData`, reached only through the member-wide
 * Auskunft/Löschung workflow, never through this interface.
 */
@RpcService
interface IMemberHonorService {
    /** Role: BOARD/ADMIN. [limit] server-capped at [MemberHonorLimits.MAX_LIMIT] regardless of the requested value. */
    suspend fun listHonors(
        memberId: String? = null,
        category: MemberHonorCategory? = null,
        limit: Int = MemberHonorLimits.DEFAULT_LIMIT,
        offset: Int = 0,
    ): MemberHonorPageDto

    /** Role: BOARD/ADMIN. Server validates [input] regardless of any client-side pre-check. */
    suspend fun createHonor(input: MemberHonorInput): MemberHonorDto

    /** Role: BOARD/ADMIN. ALL fields of [input] are editable -- see [MemberHonorInput] KDoc. */
    suspend fun updateHonor(
        id: String,
        input: MemberHonorInput,
    ): MemberHonorDto

    /**
     * Role: **ADMIN**. A real, irreversible hard DELETE of the row -- a data-correction path for a
     * mis-entered honor, NOT an Art. 17 erasure (no `dsgvo_audit_log` entry, no `ErasureMode`). See
     * interface KDoc "Rollen-Asymmetrie".
     */
    suspend fun deleteHonor(id: String)
}
