package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.tables.AccountTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IMemberService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class MemberService(
    private val call: ApplicationCall,
) : IMemberService {
    // Deliberately unauthenticated (bootstrap for the "current member" picker before an
    // X-Member-Id is chosen — see IMemberService KDoc). Only id + displayName are selected,
    // so email and role (PII / authorization-relevant) never leave the server for this call.
    override suspend fun listMembers(): List<MemberSummaryDto> =
        transaction {
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
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
}

fun ResultRow.toMemberDto(): MemberDto =
    MemberDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        joinedAt = this[MemberTable.joinedAt],
        role = this[AccountTable.role],
    )
