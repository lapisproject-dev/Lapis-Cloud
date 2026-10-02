package network.lapis.cloud.server.rpc

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Throwaway members + DMs shared by the V1.9.36 server tests (own rows only, removed again in [cleanup]). */
internal class ThrowawayMembers {
    val ids = mutableListOf<Uuid>()

    fun create(
        email: String,
        displayName: String = "V1936 Test Mitglied",
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        anonymized: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0, 0)
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        ids += id
        return id
    }

    fun message(
        from: Uuid,
        to: Uuid,
        sentAt: LocalDateTime,
        read: Boolean = false,
        body: String = "hello",
        id: Uuid = Uuid.random(),
    ): Uuid {
        transaction {
            DirectMessageTable.insert {
                it[DirectMessageTable.id] = id
                it[senderId] = from
                it[recipientId] = to
                it[DirectMessageTable.body] = body
                it[DirectMessageTable.sentAt] = sentAt
                if (read) it[readAt] = sentAt
            }
        }
        return id
    }

    fun cleanup() {
        transaction {
            DirectMessageTable.deleteWhere { (senderId inList ids) or (recipientId inList ids) }
            AccountTable.deleteWhere { memberId inList ids }
            MemberTable.deleteWhere { id inList ids }
        }
    }
}

internal class ServiceCaller(
    private val builder: ApplicationTestBuilder,
) {
    var block: suspend (ApplicationCall) -> Any? = {}
    var result: Any? = null
    var error: Throwable? = null

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> call(
        caller: Uuid,
        action: suspend (ApplicationCall) -> T,
    ): Result<T> {
        block = action
        result = null
        error = null
        builder.client.get("/test/run") { header("X-Member-Id", caller.toString()) }
        return error?.let { Result.failure(it) } ?: Result.success(result as T)
    }
}

/** Runs [body] in a test application exposing one `/test/run` route that executes the action given to [ServiceCaller.call]. */
internal fun withServiceCaller(body: suspend ServiceCaller.() -> Unit) {
    testApplication {
        val caller = ServiceCaller(this)
        application {
            routing {
                get("/test/run") {
                    try {
                        caller.result = caller.block(call)
                    } catch (t: Throwable) {
                        caller.error = t
                    }
                    call.respondText("ok")
                }
            }
        }
        caller.body()
    }
}
