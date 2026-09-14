package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountFinTsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.payment.fints.FakeFinTsSetupClient
import network.lapis.cloud.server.payment.fints.FinTsCredentials
import network.lapis.cloud.server.payment.fints.FinTsErrorCode
import network.lapis.cloud.server.payment.fints.FinTsSetupClient
import network.lapis.cloud.server.payment.fints.FinTsSetupOutcome
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Own throwaway routing calling
 * [BankAccountService] directly -- same house style [BankAccountServiceTest] already establishes.
 * [FakeFinTsSetupClient] stands in for hbci4j -- no real bank, no network.
 */
class BankAccountFinTsServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                if (createdAccountIds.isNotEmpty()) {
                    BankAccountFinTsAcknowledgmentTable.deleteWhere {
                        BankAccountFinTsAcknowledgmentTable.bankAccountId inList
                            createdAccountIds
                    }
                    BankAccountTable.deleteWhere { BankAccountTable.id inList createdAccountIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = null
                    it[bankBic] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                createdMemberIds.forEach {
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
            createdAccountIds.clear()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BankAccountFinTsService-Testmitglied"
                    it[email] = "bank-account-fints-svc-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createAccount(admin: Uuid): String {
            val id = Uuid.random()
            transaction {
                BankAccountTable.insert {
                    it[BankAccountTable.id] = id
                    it[label] = "Testkonto"
                    it[iban] = "DE${(20_000_000 + createdAccountIds.size).toString().padStart(20, '0')}"
                    it[bic] = null
                    it[bankName] = null
                    it[isDefault] = false
                    it[defaultMarker] = null
                    it[createdBy] = admin
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[updatedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
            createdAccountIds += id
            return id.toString()
        }

        fun randomSecretBox(): SecretBox = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))

        test("getFinTsComplianceDisclaimer: TREASURER/BOARD/ADMIN all pass, MEMBER is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = FakeFinTsSetupClient(), secretBox = randomSecretBox()) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val member = createMember(AccountRole.MEMBER)

                client.get("/test/fints/disclaimer") { header("X-Member-Id", treasurer.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/fints/disclaimer") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "beginFinTsSetup/submitFinTsTan/cancelFinTsSetup/disableFinTs: TREASURER and BOARD are forbidden, only ADMIN passes the gate",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = FakeFinTsSetupClient(), secretBox = randomSecretBox()) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val board = createMember(AccountRole.BOARD)
                val admin = createMember(AccountRole.ADMIN)
                val accountId = createAccount(admin)

                client.post("/test/fints/$accountId/begin") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/fints/$accountId/begin") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/fints/tan/some-handle") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/fints/cancel/some-handle") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/fints/$accountId/disable") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("beginFinTsSetup: wrong disclaimerVersion/sha256 is rejected with 409 before touching the setup client") {
            testApplication {
                val setupClient = FakeFinTsSetupClient()
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = setupClient, secretBox = randomSecretBox()) }
                }
                val admin = createMember(AccountRole.ADMIN)
                val accountId = createAccount(admin)

                client
                    .post("/test/fints/$accountId/begin?disclaimerVersion=WRONG") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
                setupClient.beginCalls.size shouldBe 0
            }
        }

        test("beginFinTsSetup: secretBox == null -> 409 ENCRYPTION_KEY_MISSING, never reaches the setup client") {
            testApplication {
                val setupClient = FakeFinTsSetupClient()
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = setupClient, secretBox = null) }
                }
                val admin = createMember(AccountRole.ADMIN)
                val accountId = createAccount(admin)

                client.post("/test/fints/$accountId/begin") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                setupClient.beginCalls.size shouldBe 0
            }
        }

        test("beginFinTsSetup Verified: activates the account, and the returned/listed JSON never contains the plaintext PIN or userId") {
            testApplication {
                val credentialsHolder = mutableListOf<FinTsCredentials>()
                val setupClient =
                    object : FinTsSetupClient {
                        override fun begin(credentials: FinTsCredentials): FinTsSetupOutcome {
                            credentialsHolder += credentials
                            return FinTsSetupOutcome.Verified(credentials = credentials, statementCount = 0)
                        }

                        override fun submitTan(
                            handle: String,
                            tan: String,
                        ): FinTsSetupOutcome = FinTsSetupOutcome.Failed(FinTsErrorCode.BANK_UNAVAILABLE)

                        override fun cancel(handle: String) = Unit
                    }
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = setupClient, secretBox = randomSecretBox()) }
                }
                val admin = createMember(AccountRole.ADMIN)
                val accountId = createAccount(admin)

                val beginResponse =
                    client.post("/test/fints/$accountId/begin?userId=S3cretUser&pin=S3cretPin%21") {
                        header("X-Member-Id", admin.toString())
                    }
                beginResponse.status shouldBe HttpStatusCode.OK

                val listResponse = client.get("/test/bank-accounts-json") { header("X-Member-Id", admin.toString()) }
                val listJson = listResponse.bodyAsText()
                listJson shouldNotContain "S3cretUser"
                listJson shouldNotContain "S3cretPin!"
                listJson shouldNotContain "v1:"

                transaction {
                    BankAccountTable
                        .selectAll()
                        .where { BankAccountTable.id eq Uuid.parse(accountId) }
                        .single()[BankAccountTable.fintsStatus] shouldBe network.lapis.cloud.shared.domain.FinTsStatus.ACTIVE
                }
            }
        }

        test("disableFinTs: clears all four credential columns and resets to NOT_CONFIGURED") {
            testApplication {
                val setupClient =
                    object : FinTsSetupClient {
                        override fun begin(credentials: FinTsCredentials): FinTsSetupOutcome =
                            FinTsSetupOutcome.Verified(credentials = credentials, statementCount = 0)

                        override fun submitTan(
                            handle: String,
                            tan: String,
                        ): FinTsSetupOutcome = FinTsSetupOutcome.Failed(FinTsErrorCode.BANK_UNAVAILABLE)

                        override fun cancel(handle: String) = Unit
                    }
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = setupClient, secretBox = randomSecretBox()) }
                }
                val admin = createMember(AccountRole.ADMIN)
                val accountId = createAccount(admin)
                client.post("/test/fints/$accountId/begin?userId=u&pin=p") { header("X-Member-Id", admin.toString()) }

                client.post("/test/fints/$accountId/disable") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK

                transaction {
                    val row = BankAccountTable.selectAll().where { BankAccountTable.id eq Uuid.parse(accountId) }.single()
                    row[BankAccountTable.fintsStatus] shouldBe network.lapis.cloud.shared.domain.FinTsStatus.NOT_CONFIGURED
                    row[BankAccountTable.fintsBlz] shouldBe null
                    row[BankAccountTable.fintsUserIdCiphertext] shouldBe null
                    row[BankAccountTable.fintsPinCiphertext] shouldBe null
                    row[BankAccountTable.fintsActivatedBy] shouldBe null
                }
            }
        }

        test("malformed bankAccountId is a 400, not a 404/500") {
            testApplication {
                application {
                    install(StatusPages) { installFinTsTestExceptionHandlers() }
                    routing { registerFinTsTestRoutes(setupClient = FakeFinTsSetupClient(), secretBox = randomSecretBox()) }
                }
                val admin = createMember(AccountRole.ADMIN)

                client
                    .post("/test/fints/not-a-uuid/begin") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun Route.registerFinTsTestRoutes(
    setupClient: FinTsSetupClient,
    secretBox: SecretBox?,
) {
    fun service(callCtx: ApplicationCall) = BankAccountService(call = callCtx, finTsSetupClient = setupClient, secretBox = secretBox)

    get("/test/fints/disclaimer") {
        service(call).getFinTsComplianceDisclaimer()
        call.respondText("ok")
    }
    get("/test/bank-accounts-json") {
        val dtos = service(call).listBankAccounts()
        call.respondText(
            kotlinx.serialization.json.Json
                .encodeToString(dtos),
        )
    }
    post("/test/fints/{id}/begin") {
        val id = call.parameters["id"]!!
        val disclaimerVersion = call.request.queryParameters["disclaimerVersion"] ?: FinTsComplianceDisclaimer.VERSION
        val userId = call.request.queryParameters["userId"] ?: "test-user"
        val pin = call.request.queryParameters["pin"] ?: "test-pin"
        val result =
            service(call).beginFinTsSetup(
                network.lapis.cloud.shared.domain.FinTsSetupInput(
                    bankAccountId = id,
                    blz = "12345678",
                    url = "https://example.com/hbci",
                    userId = userId,
                    pin = pin,
                    disclaimerVersion = disclaimerVersion,
                    disclaimerSha256 = FinTsComplianceDisclaimer.SHA256,
                ),
            )
        call.respondText(result::class.simpleName ?: "unknown")
    }
    post("/test/fints/tan/{handle}") {
        val handle = call.parameters["handle"]!!
        service(call).submitFinTsTan(handle = handle, tan = "123456")
        call.respondText("ok")
    }
    post("/test/fints/cancel/{handle}") {
        val handle = call.parameters["handle"]!!
        service(call).cancelFinTsSetup(handle)
        call.respondText("ok")
    }
    post("/test/fints/{id}/disable") {
        val id = call.parameters["id"]!!
        service(call).disableFinTs(id)
        call.respondText("ok")
    }
}

private fun StatusPagesConfig.installFinTsTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
