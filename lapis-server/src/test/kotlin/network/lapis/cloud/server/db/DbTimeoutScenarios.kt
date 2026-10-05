package network.lapis.cloud.server.db

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.JsonRpcResponse
import dev.kilua.rpc.RpcSerialization
import dev.kilua.rpc.registerRpcServiceExceptions
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.installRpcErrorSanitizer
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Welle V1.9.55 -- a lock timeout / a unique violation travels through the REAL error pipeline (Hikari exception override ->
 * per-call holder -> send-pipeline sanitizer) exactly the way Kilua RPC would send it: the route below reproduces Kilua's
 * catch block (verified in the 0.0.45 bytecode: `JsonRpcResponse(error = e.message, exceptionType = <class>, exceptionJson = null)`
 * for every non-typed exception).
 */
abstract class DbTimeoutScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec {
            db.activate()
            registerRpcServiceExceptions()
        }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                if (createdLedgerAccountIds.isNotEmpty()) LedgerAccountTable.deleteWhere { id inList createdLedgerAccountIds }
            }
            db.deactivate()
        }

        /** Reproduces Kilua RPC's request handler error branch (see class KDoc). */
        suspend fun ApplicationCall.respondLikeKilua(block: () -> Unit) {
            val response =
                try {
                    block()
                    JsonRpcResponse(id = 1, result = "\"ok\"")
                } catch (e: Exception) {
                    JsonRpcResponse(
                        id = 1,
                        error = e.message ?: "Error",
                        exceptionType = e.javaClass.canonicalName,
                        exceptionJson =
                            if (e is AbstractServiceException) {
                                RpcSerialization.getJson().encodeToString<AbstractServiceException>(e)
                            } else {
                                null
                            },
                    )
                }
            respond(response)
        }

        val json = Json { ignoreUnknownKeys = true }

        test("a lock timeout reaches the client as a typed ServiceBusyException with no SQL text") {
            val attempts = AtomicInteger()
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val holder =
                pool.submit {
                    transaction {
                        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                            it[paymentGatewayEnabled] = false
                        }
                        holding.countDown()
                        release.await(90, TimeUnit.SECONDS)
                    }
                }
            try {
                holding.await(20, TimeUnit.SECONDS) shouldBe true
                testApplication {
                    application {
                        // Production order: the sanitizer is installed BEFORE initRpc (and thus before ContentNegotiation).
                        installRpcErrorSanitizer()
                        install(ContentNegotiation) { json(RpcSerialization.getJson()) }
                        routing {
                            get("/probe/lock") {
                                call.respondLikeKilua {
                                    transaction {
                                        attempts.incrementAndGet()
                                        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                                            it[paymentGatewayEnabled] = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val response = client.get("/probe/lock")
                    response.status shouldBe HttpStatusCode.OK
                    val text = response.bodyAsText()
                    val parsed = json.decodeFromString(JsonRpcResponse.serializer(), text)
                    val decoded = RpcSerialization.getJson().decodeFromString<AbstractServiceException>(parsed.exceptionJson!!)
                    (decoded is ServiceBusyException) shouldBe true
                    parsed.error shouldBe ""
                    // No driver/ORM/SQL text, no table or column names anywhere in the body.
                    listOf(
                        "PSQL",
                        "ERROR",
                        "org.postgresql",
                        "org.h2",
                        "organization_settings",
                        "UPDATE",
                        "Timeout trying to lock",
                    ).forEach {
                        text shouldNotContain it
                    }
                }
                // Documented multiplication (Exposed's default maxAttempts = 3): one request runs the block 3 times.
                attempts.get() shouldBe 3
            } finally {
                release.countDown()
                holder.get(30, TimeUnit.SECONDS)
                pool.shutdownNow()
            }
        }

        test("a unique violation is NOT a busy error and never leaks its PSQL/H2 text or values") {
            val accountNumber = "T${Uuid.random().toString().take(8)}"
            val firstId = Uuid.random()

            fun insertAccount(id: Uuid) =
                transaction {
                    LedgerAccountTable.insert {
                        it[LedgerAccountTable.id] = id
                        it[LedgerAccountTable.accountNumber] = accountNumber
                        it[name] = "DbTimeout Konto"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.ASSET
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = false
                    }
                }
            insertAccount(firstId)
            createdLedgerAccountIds += firstId
            testApplication {
                application {
                    installRpcErrorSanitizer()
                    install(ContentNegotiation) { json(RpcSerialization.getJson()) }
                    routing { get("/probe/unique") { call.respondLikeKilua { insertAccount(Uuid.random()) } } }
                }
                val text = client.get("/probe/unique").bodyAsText()
                val parsed = json.decodeFromString(JsonRpcResponse.serializer(), text)
                parsed.exceptionJson shouldBe null
                parsed.error shouldBe ""
                text shouldNotContain accountNumber
                text shouldNotContain "ledger_account"
                text shouldNotContain "duplicate key"
            }
        }

        test("a deliberate untyped non-SQL message survives the sanitizer") {
            testApplication {
                application {
                    installRpcErrorSanitizer()
                    install(ContentNegotiation) { json(RpcSerialization.getJson()) }
                    routing { get("/probe/plain") { call.respondLikeKilua { require(false) { "amount must be positive" } } } }
                }
                val parsed = json.decodeFromString(JsonRpcResponse.serializer(), client.get("/probe/plain").bodyAsText())
                parsed.error shouldContain "amount must be positive"
            }
        }
    })

class DbTimeoutTest : DbTimeoutScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DbTimeoutScenariosPostgresTest :
    DbTimeoutScenarios(
        TestDatabase.Postgres(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 5_000, idleInTransactionTimeoutMs = 120_000)),
    )
