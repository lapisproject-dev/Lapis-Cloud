package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.JsonRpcResponse
import dev.kilua.rpc.RpcSerialization
import dev.kilua.rpc.registerRpcServiceExceptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import network.lapis.cloud.server.db.DbFailureHolder
import network.lapis.cloud.server.db.DbFailureKind
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ServiceBusyException

class RpcErrorSanitizerTest :
    FunSpec({
        beforeSpec { registerRpcServiceExceptions() }

        val psql =
            "org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint \"uq_member_email\"\n" +
                "  Detail: Key (email)=(secret@example.org) already exists."

        fun untyped(message: String) = JsonRpcResponse(id = 1, error = message, exceptionType = "java.lang.IllegalStateException")

        test("an untyped SQL error never reaches the client with its text") {
            val holder = DbFailureHolder().also { it.sawSqlException = true }
            val out = sanitizeRpcResponse(response = untyped(psql), holder = holder)
            out.error shouldBe ""
            out.exceptionJson shouldBe null
        }

        test("an untyped message that looks like a driver message is blanked even without a holder") {
            sanitizeRpcResponse(response = untyped(psql), holder = null).error shouldBe ""
            sanitizeRpcResponse(
                response = untyped("org.h2.jdbc.JdbcSQLException: Table \"MEMBER\" not found"),
                holder = null,
            ).error shouldBe
                ""
            sanitizeRpcResponse(
                response = untyped("org.jetbrains.exposed.v1.exceptions.ExposedSQLException: x"),
                holder = null,
            ).error shouldBe
                ""
        }

        test("a deliberate untyped non-DB message stays unchanged") {
            val response = untyped("amount must be positive")
            sanitizeRpcResponse(response = response, holder = DbFailureHolder()) shouldBeSameInstanceAs response
        }

        test("a typed exception is never touched, even if the holder saw a SQL error earlier") {
            val json = RpcSerialization.getJson().encodeToString<AbstractServiceException>(ConflictException("x"))
            val response =
                JsonRpcResponse(id = 1, error = "x", exceptionType = ConflictException::class.java.canonicalName, exceptionJson = json)
            val holder = DbFailureHolder().also { it.sawSqlException = true }
            sanitizeRpcResponse(response = response, holder = holder) shouldBeSameInstanceAs response
        }

        test("a successful response is never touched") {
            val response = JsonRpcResponse(id = 1, result = "\"ok\"")
            sanitizeRpcResponse(response = response, holder = DbFailureHolder().also { it.sawSqlException = true }) shouldBeSameInstanceAs
                response
        }

        test("a recorded timeout becomes a typed ServiceBusyException that decodes exactly like a thrown one") {
            val holder =
                DbFailureHolder().also {
                    it.sawSqlException = true
                    it.kind = DbFailureKind.LOCK_TIMEOUT
                }
            val out = sanitizeRpcResponse(response = untyped(psql), holder = holder)
            out.error shouldBe ""
            out.exceptionType shouldBe ServiceBusyException::class.java.canonicalName
            val json = RpcSerialization.getJson()
            val decoded = json.decodeFromString<AbstractServiceException>(out.exceptionJson!!)
            (decoded is ServiceBusyException) shouldBe true
            // Wire pin: identical to what Kilua itself would produce for a thrown ServiceBusyException.
            out.exceptionJson shouldBe json.encodeToString<AbstractServiceException>(ServiceBusyException())
        }
    })
