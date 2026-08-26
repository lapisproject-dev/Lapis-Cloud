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
     * V1.2.11 (PdV-CSV-Import, security fix): now requires an authenticated caller, same as every
     * other method on this interface. Historically this was deliberately callable WITHOUT
     * authentication — it was the bootstrap for a legacy "current member" picker used *before* an
     * `X-Member-Id` was chosen, back when that trusted header was the only auth mechanism this
     * codebase had (V0.1.2-V0.1.4). Real session-cookie auth (V0.7.1 Authentifizierung, V0.7.3
     * Basis-Mehrseiten-UI) replaced that picker everywhere in the client — every screen that calls
     * this method today (`MemberAdministrationScreen`, `CommitteesScreen`, `MeetingsScreen`,
     * `SepaMandatesScreen`, `LtrLedgerScreen`, and others) is already behind the app's own
     * `requireAuth`-tier routing, so gating this call server-side costs nothing functionally. What
     * it fixes: before this wave, an unauthenticated caller could enumerate id + displayName of
     * every ACTIVE member with a single unauthenticated HTTP request — harmless when the member
     * count was a handful of demo rows, but a real exposure of a political party's membership list
     * (Art. 9 Abs. 1 DSGVO special-category data) once V1.2.11's CSV import populates real member
     * rows. Still returns only id + displayName (never email/role — PII/authorization-relevant
     * fields); use [getCurrentMember] for the full [MemberDto].
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
