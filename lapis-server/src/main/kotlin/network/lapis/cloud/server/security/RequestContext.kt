package network.lapis.cloud.server.security

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.annotations.RpcServiceException
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.tables.AccountTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Stand-in for the real authentication/session layer that V0.1.2-V0.1.4 have not built yet
 * (see CLAUDE.md "Vorab-Befund"). The caller's member id travels in the `X-Member-Id` request
 * header; every RPC service implementation resolves it exactly once per call via
 * [resolveCurrentMember] and uses the resulting [CurrentMember.role] for authorization checks,
 * so swapping this out for real session-based auth later only touches this one file plus each
 * service constructor's `call` parameter, not each individual authorization check.
 */
@RpcServiceException
class UnauthenticatedException(
    override val message: String = "Missing or unknown X-Member-Id header",
) : AbstractServiceException()

@RpcServiceException
class ForbiddenException(
    override val message: String = "Not authorized for this operation",
) : AbstractServiceException()

data class CurrentMember(
    val memberId: Uuid,
    val role: AccountRole,
)

private const val MEMBER_ID_HEADER = "X-Member-Id"

fun resolveCurrentMember(call: ApplicationCall): CurrentMember {
    val headerValue = call.request.headers[MEMBER_ID_HEADER] ?: throw UnauthenticatedException()
    val memberId = runCatching { Uuid.parse(headerValue) }.getOrElse { throw UnauthenticatedException() }
    return transaction {
        val row =
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull()
                ?: throw UnauthenticatedException()
        CurrentMember(memberId = memberId, role = row[AccountTable.role])
    }
}

fun CurrentMember.requireRole(vararg allowed: AccountRole) {
    if (role !in allowed) throw ForbiddenException()
}

val CurrentMember.isPrivileged: Boolean
    get() = role == AccountRole.ADMIN || role == AccountRole.BOARD

/**
 * Three distinct [DocumentAccessLevel] tiers, three distinct outcomes — ADMIN_ONLY must require
 * the ADMIN role specifically, not just "privileged" (BOARD or ADMIN), otherwise BOARD_ONLY and
 * ADMIN_ONLY collapse into the same check. Used identically by [DocumentAccessLevel]-filtered
 * reads (listDocuments/listVersions) and the HTTP download route so the two never drift apart.
 */
fun CurrentMember.canAccessDocumentAtLevel(level: DocumentAccessLevel): Boolean =
    when (level) {
        DocumentAccessLevel.PUBLIC_MEMBERS -> true
        DocumentAccessLevel.BOARD_ONLY -> isPrivileged
        DocumentAccessLevel.ADMIN_ONLY -> role == AccountRole.ADMIN
    }
