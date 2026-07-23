package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberSummaryDto

/**
 * Foundation stub — see [network.lapis.cloud.shared.domain.MemberStatus] KDoc. Provides just
 * enough member lookup for the V0.1.5 services (contributions/documents/communication) to
 * resolve display names and for the KVision shell to offer a "current member" picker in lieu
 * of real authentication (V0.1.2-V0.1.4).
 */
@RpcService
interface IMemberService {
    /**
     * Deliberately callable without authentication (it's the bootstrap for the "current
     * member" picker used *before* an `X-Member-Id` is chosen) — but that means it must never
     * return PII or authorization-relevant fields. Returns only id + displayName; use
     * [getCurrentMember] (which does require an authenticated caller) for the full [MemberDto]
     * including email and role.
     */
    suspend fun listMembers(): List<MemberSummaryDto>

    /** Resolves the caller's member context from the `X-Member-Id` request header stand-in. */
    suspend fun getCurrentMember(): MemberDto

    /**
     * V0.4.1: the only production write path for [MemberDto.street]/[MemberDto.postalCode]/
     * [MemberDto.city]/[MemberDto.country] -- without this, the postal address required by the
     * Beitragsrechnung/Spendenbescheinigung mailmerge templates (see `MailmergeRoutes`) could only
     * ever be populated via raw SQL. Self-or-privileged: a member may update their own address, and
     * ADMIN/BOARD may update any member's (e.g. when correcting an address on a donor's or fellow
     * member's behalf) -- same `isPrivileged` check `DocumentAccessLevel.BOARD_ONLY` already uses.
     * All four fields are nullable and passed together; passing `null` for a field clears it. Throws
     * [ForbiddenException] if the caller is neither the target member nor privileged, [NotFoundException]
     * if `memberId` does not resolve to an existing member.
     */
    suspend fun updateMemberAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto

    /**
     * V0.5.2: the only production write path for [MemberDto.dateOfBirth]/[MemberDto.nationality]
     * -- the two beneficial-owner fields a Transparenzregister (§20 GwG) entry requires beyond the
     * address fields [updateMemberAddress] already covers (see
     * `network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto`). Same self-or-privileged
     * authorization as [updateMemberAddress]. Both fields are nullable and passed together; passing
     * `null` for a field clears it. Throws [ForbiddenException] if the caller is neither the target
     * member nor privileged, [NotFoundException] if `memberId` does not resolve to an existing
     * member.
     */
    suspend fun updateMemberBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto
}
