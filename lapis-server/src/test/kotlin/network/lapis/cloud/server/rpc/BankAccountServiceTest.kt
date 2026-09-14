package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.14 "Mehrere Bankkonten". Own throwaway routing calling [BankAccountService] directly
 * -- same house style [BankStatementServiceTest]/[SepaServiceTest] already establish (kilua-rpc's
 * own generated dispatch is not exercised here). Every write-path assertion below uses a
 * well-formed but non-existent id: a 403 (not 404) proves the role gate ran BEFORE any store
 * lookup; a 404 for TREASURER/ADMIN proves the request actually reached [BankAccountStore]
 * (`network.lapis.cloud.server.payment.bankstatement.BankAccountStore`).
 */
class BankAccountServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                // Any bank_account row this test's create() route call produced must go BEFORE the
                // owning member is deleted (bank_account.created_by FKs to member) -- and the
                // organization_settings mirror it may have set must be cleared, so a later test
                // FILE (e.g. BankStatementImportServiceTest's own "no bank_account rows -> legacy
                // behaviour" regression test) never sees a stray row/mirrored IBAN this file left
                // behind.
                if (createdMemberIds.isNotEmpty()) {
                    BankAccountTable.deleteWhere { BankAccountTable.createdBy inList createdMemberIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = null
                    it[bankBic] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    // AuditLogRecorder (fired by BankAccountStore.create/update/delete/setDefault)
                    // wrote actor_member_id rows referencing these test members -- null them out
                    // first, same precedent BankStatementServiceTest's own afterEach establishes.
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
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BankAccountService-Testmitglied"
                    it[email] = "bank-account-svc-${Uuid.random()}@example.org"
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

        test("listBankAccounts: BOARD is allowed (read tier), a plain MEMBER is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBankAccountTestExceptionHandlers() }
                    routing { registerBankAccountTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val member = createMember(AccountRole.MEMBER)

                client.get("/test/bank-accounts") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/bank-accounts") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "WRITE methods (create/update/delete/setDefault): BOARD is forbidden despite being a READ role, " +
                "TREASURER/ADMIN pass the gate and reach the store",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installBankAccountTestExceptionHandlers() }
                    routing { registerBankAccountTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val treasurer = createMember(AccountRole.TREASURER)
                val admin = createMember(AccountRole.ADMIN)
                val randomId = Uuid.random().toString()

                client.post("/test/bank-accounts") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/bank-accounts/$randomId/update") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .delete("/test/bank-accounts/$randomId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/bank-accounts/$randomId/set-default") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                // TREASURER/ADMIN pass the gate -- a random id then surfaces as NotFound (update/
                // delete/setDefault) since it was never created via this same test's own /create.
                client
                    .post("/test/bank-accounts/$randomId/update") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client.delete("/test/bank-accounts/$randomId") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                client
                    .post("/test/bank-accounts/$randomId/set-default") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.NotFound

                // Review fix (MAJOR, Review Round 3, security finding): create() passing the RPC
                // gate is no longer the whole story -- the very first account becomes the default
                // and repoints the ADMIN-only organization-wide SEPA-creditor IBAN, so the STORE
                // itself now refuses a TREASURER here (see the dedicated test below for the full
                // create/update/delete/setDefault matrix). ADMIN still creates it directly.
                client.post("/test/bank-accounts") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/bank-accounts") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "MAJOR security finding (Review Round 3): a TREASURER cannot create/update/delete/setDefault " +
                "on the DEFAULT account (it repoints the ADMIN-only organization-wide SEPA-creditor IBAN), " +
                "but CAN manage a NON-default account end to end",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installBankAccountTestExceptionHandlers() }
                    routing { registerBankAccountTestRoutes() }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val admin = createMember(AccountRole.ADMIN)

                // The very first account: TREASURER refused, ADMIN succeeds and becomes the default.
                val treasurerCreateFirst =
                    client.post("/test/bank-accounts?iban=DE02120300000000202051") {
                        header("X-Member-Id", treasurer.toString())
                    }
                treasurerCreateFirst.status shouldBe HttpStatusCode.Forbidden
                val defaultAccountId =
                    client
                        .post("/test/bank-accounts?iban=DE02120300000000202051") { header("X-Member-Id", admin.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()

                // A SECOND, non-default account: ordinary multi-account bookkeeping, TREASURER-reachable.
                val secondAccountId =
                    client
                        .post("/test/bank-accounts?iban=DE12500105170648489890") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()

                // TREASURER may edit the NON-default account...
                val treasurerUpdateNonDefault =
                    client.post("/test/bank-accounts/$secondAccountId/update?iban=DE12500105170648489890") {
                        header("X-Member-Id", treasurer.toString())
                    }
                treasurerUpdateNonDefault.status shouldBe HttpStatusCode.OK
                // ...but NOT the DEFAULT account.
                val treasurerUpdateDefault =
                    client.post("/test/bank-accounts/$defaultAccountId/update?iban=DE02120300000000202051") {
                        header("X-Member-Id", treasurer.toString())
                    }
                treasurerUpdateDefault.status shouldBe HttpStatusCode.Forbidden
                // ADMIN can edit the default account.
                val adminUpdateDefault =
                    client.post("/test/bank-accounts/$defaultAccountId/update?iban=DE02120300000000202051") {
                        header("X-Member-Id", admin.toString())
                    }
                adminUpdateDefault.status shouldBe HttpStatusCode.OK

                // setDefaultBankAccount exists SOLELY to repoint the default -- ADMIN-only, always,
                // even targeting the (currently non-default) second account.
                val treasurerSetDefault =
                    client.post("/test/bank-accounts/$secondAccountId/set-default") {
                        header("X-Member-Id", treasurer.toString())
                    }
                treasurerSetDefault.status shouldBe HttpStatusCode.Forbidden
                val adminSetDefault =
                    client.post("/test/bank-accounts/$secondAccountId/set-default") {
                        header("X-Member-Id", admin.toString())
                    }
                adminSetDefault.status shouldBe HttpStatusCode.OK
                // secondAccountId is now the default, defaultAccountId is not anymore.

                // TREASURER may now delete the (no-longer-default) former default account...
                val treasurerDeleteFormerDefault =
                    client.delete("/test/bank-accounts/$defaultAccountId") { header("X-Member-Id", treasurer.toString()) }
                treasurerDeleteFormerDefault.status shouldBe HttpStatusCode.OK
                // ...but NOT the current default (secondAccountId, the only account left).
                val treasurerDeleteCurrentDefault =
                    client.delete("/test/bank-accounts/$secondAccountId") { header("X-Member-Id", treasurer.toString()) }
                treasurerDeleteCurrentDefault.status shouldBe HttpStatusCode.Forbidden
                val adminDeleteCurrentDefault =
                    client.delete("/test/bank-accounts/$secondAccountId") { header("X-Member-Id", admin.toString()) }
                adminDeleteCurrentDefault.status shouldBe HttpStatusCode.OK
            }
        }

        test("malformed ids: a TREASURER passes the role gate but a non-UUID bankAccountId is a 400, not a 404/500") {
            testApplication {
                application {
                    install(StatusPages) { installBankAccountTestExceptionHandlers() }
                    routing { registerBankAccountTestRoutes() }
                }
                val treasurer = createMember(AccountRole.TREASURER)

                client
                    .delete("/test/bank-accounts/not-a-uuid") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun Route.registerBankAccountTestRoutes() {
    // Welle V1.4.14 Wave 2 -- these two new constructor params are irrelevant to every Wave 1 test
    // in this file (none of it touches a FinTS RPC method), so a fresh no-op Fake/null pair per
    // call is enough; FinTS-specific behaviour has its own dedicated test class.
    fun service(callCtx: ApplicationCall) =
        BankAccountService(
            call = callCtx,
            finTsSetupClient =
                network.lapis.cloud.server.payment.fints
                    .FakeFinTsSetupClient(),
            secretBox = null,
        )

    get("/test/bank-accounts") {
        val dtos = service(call).listBankAccounts()
        call.respondText("${dtos.size}")
    }
    post("/test/bank-accounts") {
        // Review fix (MAJOR, Review Round 3): `iban` overridable via query param (default
        // unchanged) -- the MAJOR-finding negative/positive tests below need to create MULTIPLE
        // distinct accounts through this same route within one test, which the single hardcoded
        // IBAN would otherwise collide on (`uq_bank_account_iban`).
        val iban = call.request.queryParameters["iban"] ?: "DE89370400440532013000"
        val dto = service(call).createBankAccount(BankAccountInput(label = "Testkonto", iban = iban))
        call.respondText(dto.id)
    }
    post("/test/bank-accounts/{id}/update") {
        val id = call.parameters["id"]!!
        val iban = call.request.queryParameters["iban"] ?: "DE89370400440532013000"
        val dto = service(call).updateBankAccount(bankAccountId = id, input = BankAccountInput(label = "Umbenannt", iban = iban))
        call.respondText(dto.id)
    }
    delete("/test/bank-accounts/{id}") {
        val id = call.parameters["id"]!!
        service(call).deleteBankAccount(bankAccountId = id)
        call.respondText("ok")
    }
    post("/test/bank-accounts/{id}/set-default") {
        val id = call.parameters["id"]!!
        val dtos = service(call).setDefaultBankAccount(bankAccountId = id)
        call.respondText("${dtos.size}")
    }
}

private fun StatusPagesConfig.installBankAccountTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
