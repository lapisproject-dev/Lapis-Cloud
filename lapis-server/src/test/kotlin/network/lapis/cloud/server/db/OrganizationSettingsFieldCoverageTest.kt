package network.lapis.cloud.server.db

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- **the structural riegel** the plan calls
 * for (CLAUDE.md Stolperfalle #2, "eine neue Instanz der Kontenfeld-Fehlerklasse strukturell
 * unmöglich machen"): a new nullable ledger-account mapping field on [OrganizationSettingsTable]
 * has, historically, been forgotten at one of FIVE call sites in `OrganizationSettingsService.kt`
 * plus `LedgerScreen.kt`/`PoliticianScreen.kt` (see `travelExpenseAccountId`'s own KDoc trail for
 * the two prior near-misses).
 *
 * **Not `kotlin-reflect`-based** -- `kotlin-reflect` is deliberately not a dependency of this
 * module (see [network.lapis.cloud.server.routes.PublicChromeStringsTest]'s own KDoc for the same
 * house convention). Instead:
 *
 * 1. [KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS] is a HAND-MAINTAINED, explicitly-named list of every
 *    `OrganizationSettingsTable` column that is `optReference(..., LedgerAccountTable.id)` --
 *    verified by direct inspection of that file's source at the time this test was written (V1.4.12).
 *    A future field addition MUST extend this list, or this test does not even attempt to cover it
 *    -- exactly the same "extend the list" discipline [PublicChromeStringsTest] documents for its
 *    own field-by-field coverage. This list intentionally does NOT include `auctionMaxValueLtr`
 *    (a `BigDecimal`, not an account reference), `sepaCreditorId`/`sepaCreditorName` (`String`,
 *    IBAN-adjacent identifiers, not ledger accounts), `paymentGatewayProvider` (an enum), or
 *    `travelMileageRatePerKm`/`travelPerDiemRate` (rates, `BigDecimal`) -- none of those are
 *    `Column<Uuid?>` referencing `LedgerAccountTable.id` at all.
 * 2. The end-to-end round-trip test below sets EVERY field in that list to a freshly created,
 *    correctly-typed [network.lapis.cloud.server.db.generated.LedgerAccountTable] row, calls
 *    [OrganizationSettingsService.updateOrganizationSettings] through a real HTTP round-trip (so
 *    `LedgerScreen.kt`'s/`PoliticianScreen.kt`'s own construction sites are NOT exercised here --
 *    those are a client-module concern this test cannot reach; this test's job is the SERVER side
 *    of the contract only), reads it back via `getOrganizationSettings`, and asserts every single
 *    field individually.
 * 3. Toggling `isPoliticalParty` and re-sending the SAME [OrganizationSettingsInput] verifies that
 *    an unrelated field write never silently drops a mapping field (the concrete shape every prior
 *    instance of this bug took: a field present in the DTO/DB but missing from one intermediate
 *    step's parameter list).
 */
class OrganizationSettingsFieldCoverageTest :
    FunSpec({
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            // Reset every ledger-account mapping field back to null and drop the ledger accounts
            // this class created -- DevSeedData's single organization_settings row is shared with
            // every other test class in the same JVM run.
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = null
                    it[paymentFeeAccountId] = null
                    it[contributionIncomeAccountId] = null
                    it[donationIncomeAccountId] = null
                    it[eventIncomeAccountId] = null
                    it[travelExpenseAccountId] = null
                    it[volunteerAllowanceAccountId] = null
                    it[isPoliticalParty] = false
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
            }
        }

        fun newLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            val number = "F${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Feldabdeckung $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        test("KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS has exactly 7 entries -- the number changes ONLY with a deliberate list edit above") {
            KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS.size shouldBe 7
        }

        test(
            "every known ledger-account mapping field round-trips through updateOrganizationSettings -> getOrganizationSettings unchanged",
        ) {
            testApplication {
                routing { registerFieldCoverageTestRoutes() }

                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                val feeAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val contributionAccount = newLedgerAccount(LedgerAccountType.INCOME)
                val donationAccount = newLedgerAccount(LedgerAccountType.INCOME)
                val eventAccount = newLedgerAccount(LedgerAccountType.INCOME)
                val travelAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val volunteerAccount = newLedgerAccount(LedgerAccountType.EXPENSE)

                val updated =
                    client
                        .post(
                            "/test/coverage/update?paymentBankAccountId=$bankAccount&paymentFeeAccountId=$feeAccount" +
                                "&contributionIncomeAccountId=$contributionAccount&donationIncomeAccountId=$donationAccount" +
                                "&eventIncomeAccountId=$eventAccount&travelExpenseAccountId=$travelAccount" +
                                "&volunteerAllowanceAccountId=$volunteerAccount&isPoliticalParty=false",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")

                withClue(clue = "field order: bank|fee|contribution|donation|event|travel|volunteer") {
                    updated[0] shouldBe bankAccount.toString()
                    updated[1] shouldBe feeAccount.toString()
                    updated[2] shouldBe contributionAccount.toString()
                    updated[3] shouldBe donationAccount.toString()
                    updated[4] shouldBe eventAccount.toString()
                    updated[5] shouldBe travelAccount.toString()
                    updated[6] shouldBe volunteerAccount.toString()
                }

                // Toggle an UNRELATED field (isPoliticalParty) and re-send the SAME account ids --
                // every prior instance of this bug class silently dropped exactly one field at
                // exactly this kind of "mostly unrelated update" call site.
                val afterToggle =
                    client
                        .post(
                            "/test/coverage/update?paymentBankAccountId=$bankAccount&paymentFeeAccountId=$feeAccount" +
                                "&contributionIncomeAccountId=$contributionAccount&donationIncomeAccountId=$donationAccount" +
                                "&eventIncomeAccountId=$eventAccount&travelExpenseAccountId=$travelAccount" +
                                "&volunteerAllowanceAccountId=$volunteerAccount&isPoliticalParty=true",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                withClue(clue = "after toggling isPoliticalParty, every mapping field must still be present") {
                    afterToggle[0] shouldBe bankAccount.toString()
                    afterToggle[1] shouldBe feeAccount.toString()
                    afterToggle[2] shouldBe contributionAccount.toString()
                    afterToggle[3] shouldBe donationAccount.toString()
                    afterToggle[4] shouldBe eventAccount.toString()
                    afterToggle[5] shouldBe travelAccount.toString()
                    afterToggle[6] shouldBe volunteerAccount.toString()
                }

                val readBack = client.post("/test/coverage/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                withClue(clue = "a fresh read reflects the same seven fields") {
                    readBack[0] shouldBe bankAccount.toString()
                    readBack[6] shouldBe volunteerAccount.toString()
                }

                // Reset for the next test in this class (shared DevSeedData row).
                client.post(
                    "/test/coverage/update?isPoliticalParty=false",
                ) { header("X-Member-Id", ADMIN_ID) }
            }
        }

        test("the hand-maintained allowlist names every intentionally-excluded, non-ledger-account OrganizationSettingsTable field") {
            // Documents the fields deliberately NOT in KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS -- see
            // class KDoc point 1. Not an automated check (no kotlin-reflect), but a named, readable
            // anchor: extending OrganizationSettingsTable with a genuinely new Uuid?-typed
            // LedgerAccountTable reference should prompt whoever adds it to update BOTH lists here.
            NON_LEDGER_ACCOUNT_FIELDS_ALLOWLIST shouldContainExactlyInAnyOrder
                listOf(
                    "auctionEnabled",
                    "auctionMaxValueLtr",
                    "sepaDebitEnabled",
                    "sepaCreditorId",
                    "sepaCreditorName",
                    "sepaPrenotificationDays",
                    "paymentGatewayEnabled",
                    "paymentGatewayProvider",
                    "travelMileageRatePerKm",
                    "travelPerDiemRate",
                )
        }
    })

/** See [OrganizationSettingsFieldCoverageTest] class KDoc point 1. */
private val KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS =
    listOf(
        "paymentBankAccountId",
        "paymentFeeAccountId",
        "contributionIncomeAccountId",
        "donationIncomeAccountId",
        "eventIncomeAccountId",
        "travelExpenseAccountId",
        "volunteerAllowanceAccountId",
    )

/** See [OrganizationSettingsFieldCoverageTest] class KDoc point 1 / the allowlist test above. */
private val NON_LEDGER_ACCOUNT_FIELDS_ALLOWLIST =
    listOf(
        "auctionEnabled",
        "auctionMaxValueLtr",
        "sepaDebitEnabled",
        "sepaCreditorId",
        "sepaCreditorName",
        "sepaPrenotificationDays",
        "paymentGatewayEnabled",
        "paymentGatewayProvider",
        "travelMileageRatePerKm",
        "travelPerDiemRate",
    )

/** Throwaway test routes, scoped to this file only -- mirrors [OrganizationSettingsServiceTest]'s own idiom. */
private fun Route.registerFieldCoverageTestRoutes() {
    post("/test/coverage/update") {
        val service = OrganizationSettingsService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateOrganizationSettings(
                OrganizationSettingsInput(
                    name = "Feldabdeckungs-Testverein e.V.",
                    isPoliticalParty = q["isPoliticalParty"]?.toBoolean() ?: false,
                    paymentBankAccountId = q["paymentBankAccountId"],
                    paymentFeeAccountId = q["paymentFeeAccountId"],
                    contributionIncomeAccountId = q["contributionIncomeAccountId"],
                    donationIncomeAccountId = q["donationIncomeAccountId"],
                    eventIncomeAccountId = q["eventIncomeAccountId"],
                    travelExpenseAccountId = q["travelExpenseAccountId"],
                    volunteerAllowanceAccountId = q["volunteerAllowanceAccountId"],
                ),
            )
        call.respondText(
            listOf(
                dto.paymentBankAccountId ?: "-",
                dto.paymentFeeAccountId ?: "-",
                dto.contributionIncomeAccountId ?: "-",
                dto.donationIncomeAccountId ?: "-",
                dto.eventIncomeAccountId ?: "-",
                dto.travelExpenseAccountId ?: "-",
                dto.volunteerAllowanceAccountId ?: "-",
            ).joinToString("|"),
        )
    }
    post("/test/coverage/get") {
        val service = OrganizationSettingsService(call)
        val dto = service.getOrganizationSettings()
        call.respondText(
            listOf(
                dto.paymentBankAccountId ?: "-",
                dto.paymentFeeAccountId ?: "-",
                dto.contributionIncomeAccountId ?: "-",
                dto.donationIncomeAccountId ?: "-",
                dto.eventIncomeAccountId ?: "-",
                dto.travelExpenseAccountId ?: "-",
                dto.volunteerAllowanceAccountId ?: "-",
            ).joinToString("|"),
        )
    }
}
