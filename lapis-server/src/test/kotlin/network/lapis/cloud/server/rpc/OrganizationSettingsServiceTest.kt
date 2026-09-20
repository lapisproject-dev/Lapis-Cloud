package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.payment.bankstatement.BankAccountStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [OrganizationSettingsService] end to end -- same "throwaway routes calling the
 * service class directly" house style as [ServiceIntegrationTest]. Reuses [DevSeedData]'s fixed
 * demo member/account ids (no member-specific state is involved here, unlike
 * [AccountingServiceTest]'s own freshly-created members). Restores the seeded row's original
 * fields in `afterTest` so tests remain order-independent regardless of which runs first.
 */
class OrganizationSettingsServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[name] = "Verein/Partei (bitte in Organisationseinstellungen konfigurieren)"
                    it[street] = null
                    it[postalCode] = null
                    it[city] = null
                    it[country] = null
                    it[bankIban] = null
                    it[bankBic] = null
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                    it[isPoliticalParty] = false
                    it[postalMailEnabled] = false
                    it[politicianRankingEnabled] = false
                    // V1.4.5.2 DATEV-Format-Export -- reset so the range-validation tests below stay
                    // order-independent, same reasoning as every other field reset here.
                    it[datevBeraterNummer] = null
                    it[datevMandantNummer] = null
                }
            }
        }

        test("TREASURER/BOARD/ADMIN can read the seeded row; MEMBER is forbidden; unauthenticated is rejected") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val asTreasurer = client.get("/test/get") { header("X-Member-Id", TREASURER_ID) }
                asTreasurer.status shouldBe HttpStatusCode.OK
                asTreasurer.bodyAsText().split(":")[0] shouldBe ORGANIZATION_SETTINGS_ID.toString()

                val asBoard = client.get("/test/get") { header("X-Member-Id", BOARD_ID) }
                asBoard.status shouldBe HttpStatusCode.OK

                val asAdmin = client.get("/test/get") { header("X-Member-Id", ADMIN_ID) }
                asAdmin.status shouldBe HttpStatusCode.OK

                val asMember = client.get("/test/get") { header("X-Member-Id", MEMBER_ID) }
                asMember.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.get("/test/get")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("ADMIN can update every field; a subsequent get reflects it; BOARD/TREASURER are forbidden to write") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val forbiddenBoard =
                    client.post("/test/update?name=X") { header("X-Member-Id", BOARD_ID) }
                forbiddenBoard.status shouldBe HttpStatusCode.Forbidden

                val forbiddenTreasurer =
                    client.post("/test/update?name=X") { header("X-Member-Id", TREASURER_ID) }
                forbiddenTreasurer.status shouldBe HttpStatusCode.Forbidden

                val updated =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&street=Musterstrasse%201&postalCode=38100" +
                            "&city=Braunschweig&country=Deutschland&bankIban=DE02120300000000202051&bankBic=BYLADEM1001" +
                            "&taxExemptionAuthority=Finanzamt%20Braunschweig&taxExemptionDate=2025-01-15",
                    ) { header("X-Member-Id", ADMIN_ID) }
                updated.status shouldBe HttpStatusCode.OK
                updated.bodyAsText() shouldBe "Testverein e.V.:Musterstrasse 1:Finanzamt Braunschweig:2025-01-15"

                val getAfterUpdate = client.get("/test/get") { header("X-Member-Id", TREASURER_ID) }
                getAfterUpdate.bodyAsText() shouldBe
                    "$ORGANIZATION_SETTINGS_ID:Testverein e.V.:Musterstrasse 1:DE02120300000000202051"
            }
        }

        // Security Round 1 (2026-08-20, MINOR-5): bankIban/bankBic are used as the SEPA CREDITOR's
        // own IBAN/BIC in every generated pain.008 file -- previously persisted with zero validation,
        // so a malformed value would only surface much later, deep inside SepaPain008Writer, as a
        // raw HTTP 500. Now rejected with ConflictException at the point they are SAVED.
        test("updateOrganizationSettings rejects a malformed bankIban/bankBic with ConflictException, not a raw 500") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                // An altered check digit on an otherwise-valid IBAN -- same fixture idiom
                // IbanValidatorTest's own "an altered check digit is rejected" test uses.
                val badIban =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&bankIban=DE89370400440532013001&bankBic=BYLADEM1001",
                    ) { header("X-Member-Id", ADMIN_ID) }
                badIban.status shouldBe HttpStatusCode.Conflict

                // "1234567" is 7 characters -- BicValidator.isValid requires exactly 8 or 11.
                val badBic =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&bankIban=DE02120300000000202051&bankBic=1234567",
                    ) { header("X-Member-Id", ADMIN_ID) }
                badBic.status shouldBe HttpStatusCode.Conflict

                // A valid pair still succeeds -- the guard rejects only malformed values, not every write.
                val good =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&bankIban=DE89370400440532013000&bankBic=COBADEFFXXX",
                    ) { header("X-Member-Id", ADMIN_ID) }
                good.status shouldBe HttpStatusCode.OK
            }
        }

        test("isPoliticalParty defaults to false and round-trips true through update -> get") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val beforeUpdate = client.get("/test/get-is-political-party") { header("X-Member-Id", TREASURER_ID) }
                beforeUpdate.bodyAsText() shouldBe "false"

                client.post("/test/update?name=Partei%20X&isPoliticalParty=true") { header("X-Member-Id", ADMIN_ID) }

                val afterUpdate = client.get("/test/get-is-political-party") { header("X-Member-Id", TREASURER_ID) }
                afterUpdate.bodyAsText() shouldBe "true"
            }
        }

        test("postalMailEnabled defaults to false, round-trips true through update -> get, and is ADMIN-only to set") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val beforeUpdate = client.get("/test/get-postal-mail-enabled") { header("X-Member-Id", TREASURER_ID) }
                beforeUpdate.bodyAsText() shouldBe "false"

                val forbiddenBoard =
                    client.post("/test/update?name=X&postalMailEnabled=true") { header("X-Member-Id", BOARD_ID) }
                forbiddenBoard.status shouldBe HttpStatusCode.Forbidden

                client.post("/test/update?name=Verein%20Y&postalMailEnabled=true") { header("X-Member-Id", ADMIN_ID) }

                val afterUpdate = client.get("/test/get-postal-mail-enabled") { header("X-Member-Id", TREASURER_ID) }
                afterUpdate.bodyAsText() shouldBe "true"
            }
        }

        test(
            "politicianRankingEnabled defaults to false, round-trips true through update -> get, " +
                "and is ADMIN-only to set",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val beforeUpdate =
                    client.get("/test/get-politician-ranking-enabled") { header("X-Member-Id", TREASURER_ID) }
                beforeUpdate.bodyAsText() shouldBe "false"

                val forbiddenBoard =
                    client.post(
                        "/test/update?name=X&politicianRankingEnabled=true",
                    ) { header("X-Member-Id", BOARD_ID) }
                forbiddenBoard.status shouldBe HttpStatusCode.Forbidden

                client.post(
                    "/test/update?name=Verein%20Z&politicianRankingEnabled=true",
                ) { header("X-Member-Id", ADMIN_ID) }

                val afterUpdate =
                    client.get("/test/get-politician-ranking-enabled") { header("X-Member-Id", TREASURER_ID) }
                afterUpdate.bodyAsText() shouldBe "true"
            }
        }
        // V1.4.5.2 "DATEV-Format-Export" (Review-Runde finding, 2026-09): the range-validation
        // branches in updateOrganizationSettings (DATEV_BERATER_NUMMER_RANGE/
        // DATEV_MANDANT_NUMMER_RANGE) had zero test coverage -- neither the happy path nor either
        // rejection branch -- despite the DB CHECK constraint (V22__datev_export.sql) existing as a
        // silent backstop that would surface as a raw HTTP 500 the moment this check ever drifted
        // from it (e.g. an edit that narrows or removes the range). Mirrors the bankIban/bankBic
        // ConflictException test above exactly.
        test("updateOrganizationSettings accepts a valid datevBeraterNummer/datevMandantNummer and round-trips them") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val updated =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&datevBeraterNummer=1001&datevMandantNummer=1",
                    ) { header("X-Member-Id", ADMIN_ID) }
                updated.status shouldBe HttpStatusCode.OK

                val afterUpdate = client.get("/test/get-datev") { header("X-Member-Id", TREASURER_ID) }
                afterUpdate.bodyAsText() shouldBe "1001:1"
            }
        }

        test("updateOrganizationSettings rejects a datevBeraterNummer outside 1001..9999999 with ConflictException") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val tooLow =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&datevBeraterNummer=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                tooLow.status shouldBe HttpStatusCode.Conflict

                val tooHigh =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&datevBeraterNummer=10000000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                tooHigh.status shouldBe HttpStatusCode.Conflict

                // Rejected requests must never persist a partial write -- the row stays unconfigured.
                val afterRejection = client.get("/test/get-datev") { header("X-Member-Id", TREASURER_ID) }
                afterRejection.bodyAsText() shouldBe "null:null"
            }
        }

        test("updateOrganizationSettings rejects a datevMandantNummer outside 1..99999 with ConflictException") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val tooLow =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&datevMandantNummer=0",
                    ) { header("X-Member-Id", ADMIN_ID) }
                tooLow.status shouldBe HttpStatusCode.Conflict

                val tooHigh =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&datevMandantNummer=100000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                tooHigh.status shouldBe HttpStatusCode.Conflict

                val afterRejection = client.get("/test/get-datev") { header("X-Member-Id", TREASURER_ID) }
                afterRejection.bodyAsText() shouldBe "null:null"
            }
        }

        // Welle V1.4.14 "Mehrere Bankkonten" (F5): once at least one `bank_account` row exists, that
        // table (via `BankAccountStore`) becomes the SOLE writer of bankIban/bankBic -- this generic
        // update path must silently keep the existing (mirrored) value instead of overwriting it
        // with whatever a stale client form still submits.
        test("updateOrganizationSettings ignores bankIban/bankBic once a bank_account row exists") {
            val admin = Uuid.parse(ADMIN_ID)
            val bankAccount =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = "DE02120300000000202051", bic = "BYLADEM1001"),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerOrgSettingsTestRoutes() }
                    }

                    // Deliberately a DIFFERENT, still individually valid IBAN/BIC -- if this were
                    // written through, the assertion below would see it instead of the mirrored one.
                    client.post(
                        "/test/update?name=Testverein%20e.V.&bankIban=DE89370400440532013000&bankBic=COBADEFFXXX",
                    ) { header("X-Member-Id", ADMIN_ID) }

                    val afterUpdate = client.get("/test/get") { header("X-Member-Id", TREASURER_ID) }
                    afterUpdate.bodyAsText() shouldBe "$ORGANIZATION_SETTINGS_ID:Testverein e.V.:null:DE02120300000000202051"
                }
            } finally {
                // MUST run even on assertion failure -- otherwise this row survives into every OTHER
                // test file's "zero bank_account rows -> legacy behaviour" assumption.
                transaction {
                    BankAccountTable.deleteWhere { BankAccountTable.id eq Uuid.parse(bankAccount.id) }
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[bankIban] = null
                        it[bankBic] = null
                    }
                }
            }
        }

        /**
         * Welle V1.4.22 Audit-Nachtrag (MAJOR-3): every mapping field was validated in ISOLATION, so
         * `paymentBankAccountId == receivablesAccountId` was configurable -- and every settlement
         * booked against it would debit and credit the same account. Each of the three accounts below
         * passes its own per-field check (active, right type, no cash register); only the COMBINATION
         * is wrong.
         */
        test("a self-referential payment-account mapping is rejected (bank == receivables/payables), a consistent one is accepted") {
            val bank = Uuid.random()
            val receivables = Uuid.random()
            val payables = Uuid.random()
            transaction {
                listOf(bank to LedgerAccountType.ASSET, receivables to LedgerAccountType.ASSET, payables to LedgerAccountType.LIABILITY)
                    .forEachIndexed { index, (id, type) ->
                        LedgerAccountTable.insert {
                            it[LedgerAccountTable.id] = id
                            it[accountNumber] = "M1442$index"
                            it[name] = "Mapping-Testkonto $index"
                            it[accountClass] = 1
                            it[LedgerAccountTable.type] = type
                            it[active] = true
                            it[reserveType] = null
                            it[isCashRegister] = false
                        }
                    }
            }
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ConflictException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Conflict)
                            }
                        }
                        routing { registerOrgSettingsTestRoutes() }
                    }
                    val base = "/test/update?name=Testverein%20e.V."

                    client
                        .post("$base&paymentBankAccountId=$receivables&receivablesAccountId=$receivables") {
                            header("X-Member-Id", ADMIN_ID)
                        }.status shouldBe HttpStatusCode.Conflict
                    client
                        .post("$base&paymentBankAccountId=$payables&payablesAccountId=$payables") { header("X-Member-Id", ADMIN_ID) }
                        .status shouldBe HttpStatusCode.Conflict

                    // The same three accounts, mapped to three different roles: accepted.
                    client
                        .post(
                            "$base&paymentBankAccountId=$bank&receivablesAccountId=$receivables&payablesAccountId=$payables",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .status shouldBe HttpStatusCode.OK
                }
            } finally {
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[paymentBankAccountId] = null
                        it[receivablesAccountId] = null
                        it[payablesAccountId] = null
                    }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList listOf(bank, receivables, payables) }
                }
            }
        }
    })

/** Shared throwaway routes for [OrganizationSettingsServiceTest] -- mirrors [AccountingServiceTest]'s own idiom. */
private fun Route.registerOrgSettingsTestRoutes() {
    get("/test/get") {
        val service = OrganizationSettingsService(call)
        val dto = service.getOrganizationSettings()
        call.respondText("${dto.id}:${dto.name}:${dto.street}:${dto.bankIban}")
    }
    get("/test/get-datev") {
        val service = OrganizationSettingsService(call)
        val dto = service.getOrganizationSettings()
        call.respondText("${dto.datevBeraterNummer}:${dto.datevMandantNummer}")
    }
    get("/test/get-is-political-party") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().isPoliticalParty.toString())
    }
    get("/test/get-postal-mail-enabled") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().postalMailEnabled.toString())
    }
    get("/test/get-politician-ranking-enabled") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().politicianRankingEnabled.toString())
    }
    post("/test/update") {
        val service = OrganizationSettingsService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateOrganizationSettings(
                OrganizationSettingsInput(
                    name = q["name"]!!,
                    street = q["street"],
                    postalCode = q["postalCode"],
                    city = q["city"],
                    country = q["country"],
                    bankIban = q["bankIban"],
                    bankBic = q["bankBic"],
                    taxExemptionAuthority = q["taxExemptionAuthority"],
                    taxExemptionDate = q["taxExemptionDate"]?.let { LocalDate.parse(it) },
                    isPoliticalParty = q["isPoliticalParty"]?.toBoolean() ?: false,
                    postalMailEnabled = q["postalMailEnabled"]?.toBoolean() ?: false,
                    politicianRankingEnabled = q["politicianRankingEnabled"]?.toBoolean() ?: false,
                    datevBeraterNummer = q["datevBeraterNummer"]?.toInt(),
                    datevMandantNummer = q["datevMandantNummer"]?.toInt(),
                    // Welle V1.4.22 Audit-Nachtrag (MAJOR-3): the three mapping fields whose
                    // COMBINATION is now cross-checked (see requireConsistentPaymentAccountMapping).
                    paymentBankAccountId = q["paymentBankAccountId"],
                    receivablesAccountId = q["receivablesAccountId"],
                    payablesAccountId = q["payablesAccountId"],
                ),
            )
        call.respondText("${dto.name}:${dto.street}:${dto.taxExemptionAuthority}:${dto.taxExemptionDate}")
    }
}
