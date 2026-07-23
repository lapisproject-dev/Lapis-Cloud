package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class MemberService(
    private val call: ApplicationCall,
) : IMemberService {
    // Deliberately unauthenticated (bootstrap for the "current member" picker before an
    // X-Member-Id is chosen — see IMemberService KDoc). Only id + displayName are selected,
    // so email and role (PII / authorization-relevant) never leave the server for this call.
    //
    // V0.7.2: tightened to AKTIV only -- was previously unfiltered (every member regardless of
    // status). Once self-registration (IRegistrationService.registerApplication) starts producing
    // real ANTRAG/ABGELEHNT/AUSGETRETEN rows, an unfiltered picker would list a not-yet-approved
    // applicant's, a rejected applicant's, or a departed former member's display name to an
    // UNAUTHENTICATED caller -- actively wrong for a login-picker, and for a political party, a
    // real exposure (listing who applied/was rejected/left to anyone, no login required).
    override suspend fun listMembers(): List<MemberSummaryDto> =
        transaction {
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
                .where { MemberTable.status eq MemberStatus.AKTIV }
                .map {
                    MemberSummaryDto(
                        id = it[MemberTable.id].toString(),
                        displayName = it[MemberTable.displayName],
                    )
                }
        }

    override suspend fun getCurrentMember(): MemberDto {
        val current = resolveCurrentMember(call)
        return transaction {
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq current.memberId }
                .single()
                .toMemberDto()
        }
    }

    override suspend fun updateMemberAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        return transaction {
            val updated =
                MemberTable.update({ MemberTable.id eq targetId }) {
                    it[MemberTable.street] = street
                    it[MemberTable.postalCode] = postalCode
                    it[MemberTable.city] = city
                    it[MemberTable.country] = country
                }
            if (updated == 0) throw NotFoundException("Member $memberId not found")
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq targetId }
                .single()
                .toMemberDto()
        }
    }

    override suspend fun updateMemberBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        return transaction {
            val updated =
                MemberTable.update({ MemberTable.id eq targetId }) {
                    it[MemberTable.dateOfBirth] = dateOfBirth
                    it[MemberTable.nationality] = nationality
                }
            if (updated == 0) throw NotFoundException("Member $memberId not found")
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq targetId }
                .single()
                .toMemberDto()
        }
    }
}

fun ResultRow.toMemberDto(): MemberDto =
    MemberDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        joinedAt = this[MemberTable.joinedAt],
        role = this[AccountTable.role],
        street = this[MemberTable.street],
        postalCode = this[MemberTable.postalCode],
        city = this[MemberTable.city],
        country = this[MemberTable.country],
        dateOfBirth = this[MemberTable.dateOfBirth],
        nationality = this[MemberTable.nationality],
        reviewedById = this[MemberTable.reviewedBy]?.toString(),
        reviewedAt = this[MemberTable.reviewedAt],
        rejectionReason = this[MemberTable.rejectionReason],
    )
