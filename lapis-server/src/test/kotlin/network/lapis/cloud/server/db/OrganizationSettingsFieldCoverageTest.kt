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
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
 *    verified by direct inspection of that file's source at the time this test was written (V1.4.12),
 *    extended to nine in V1.4.21 (audit finding M1 -- `receivablesAccountId`/`payablesAccountId` from
 *    V1.4.15 had never been added, so the riegel did not cover them).
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
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
                    it[isPoliticalParty] = false
                    it[isKleinunternehmer] = false
                    it[receivableDunningEnabled] = false
                    it[vatEnabled] = false
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

        test("KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS has exactly 9 entries -- the number changes ONLY with a deliberate list edit above") {
            KNOWN_LEDGER_ACCOUNT_MAPPING_FIELDS.size shouldBe 9
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
                // Audit-Fund M1 (V1.4.21): the two V1.4.15 mapping fields + the V1.4.15 boolean.
                val receivablesAccount = newLedgerAccount(LedgerAccountType.ASSET)
                val payablesAccount = newLedgerAccount(LedgerAccountType.LIABILITY)

                val accountQuery =
                    "paymentBankAccountId=$bankAccount&paymentFeeAccountId=$feeAccount" +
                        "&contributionIncomeAccountId=$contributionAccount&donationIncomeAccountId=$donationAccount" +
                        "&eventIncomeAccountId=$eventAccount&travelExpenseAccountId=$travelAccount" +
                        "&volunteerAllowanceAccountId=$volunteerAccount" +
                        "&receivablesAccountId=$receivablesAccount&payablesAccountId=$payablesAccount"

                val updated =
                    client
                        .post(
                            "/test/coverage/update?$accountQuery&isPoliticalParty=false&isKleinunternehmer=true" +
                                "&receivableDunningEnabled=true",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")

                withClue(
                    clue =
                        "field order: bank|fee|contribution|donation|event|travel|volunteer|receivables|payables|" +
                            "isKleinunternehmer|receivableDunningEnabled",
                ) {
                    updated[0] shouldBe bankAccount.toString()
                    updated[1] shouldBe feeAccount.toString()
                    updated[2] shouldBe contributionAccount.toString()
                    updated[3] shouldBe donationAccount.toString()
                    updated[4] shouldBe eventAccount.toString()
                    updated[5] shouldBe travelAccount.toString()
                    updated[6] shouldBe volunteerAccount.toString()
                    updated[7] shouldBe receivablesAccount.toString()
                    updated[8] shouldBe payablesAccount.toString()
                    updated[9] shouldBe "true"
                    updated[10] shouldBe "true"
                }

                // Toggle an UNRELATED field (isPoliticalParty) and re-send the SAME account ids AND
                // the same isKleinunternehmer=true -- every prior instance of this bug class
                // silently dropped exactly one field at exactly this kind of "mostly unrelated
                // update" call site (Security Round 1's own MAJOR finding was a BOOLEAN field
                // dropped this way -- folded in here, not just covered by its own isolated
                // round-trip test, so this "toggle something else, resend the rest" idiom also
                // exercises a non-Uuid field, not only the nine Column<Uuid?> mapping fields).
                val afterToggle =
                    client
                        .post(
                            "/test/coverage/update?$accountQuery&isPoliticalParty=true&isKleinunternehmer=true" +
                                "&receivableDunningEnabled=true",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                withClue(
                    clue =
                        "after toggling isPoliticalParty, every mapping field AND isKleinunternehmer AND " +
                            "receivableDunningEnabled must still be present",
                ) {
                    afterToggle[0] shouldBe bankAccount.toString()
                    afterToggle[1] shouldBe feeAccount.toString()
                    afterToggle[2] shouldBe contributionAccount.toString()
                    afterToggle[3] shouldBe donationAccount.toString()
                    afterToggle[4] shouldBe eventAccount.toString()
                    afterToggle[5] shouldBe travelAccount.toString()
                    afterToggle[6] shouldBe volunteerAccount.toString()
                    afterToggle[7] shouldBe receivablesAccount.toString()
                    afterToggle[8] shouldBe payablesAccount.toString()
                    afterToggle[9] shouldBe "true"
                    afterToggle[10] shouldBe "true"
                }

                val readBack = client.post("/test/coverage/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                withClue(clue = "a fresh read reflects the same nine mapping fields plus the two booleans") {
                    readBack[0] shouldBe bankAccount.toString()
                    readBack[6] shouldBe volunteerAccount.toString()
                    readBack[7] shouldBe receivablesAccount.toString()
                    readBack[8] shouldBe payablesAccount.toString()
                    readBack[9] shouldBe "true"
                    readBack[10] shouldBe "true"
                }

                // Reset for the next test in this class (shared DevSeedData row).
                client.post(
                    "/test/coverage/update?isPoliticalParty=false&isKleinunternehmer=false&receivableDunningEnabled=false",
                ) { header("X-Member-Id", ADMIN_ID) }
            }
        }

        test("isKleinunternehmer round-trips through updateOrganizationSettings -> getOrganizationSettings (V1.4.13)") {
            testApplication {
                routing { registerFieldCoverageTestRoutes() }

                val afterEnable =
                    client
                        .post("/test/coverage/update-vat?isKleinunternehmer=true") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                afterEnable shouldBe "true"

                val readBack = client.post("/test/coverage/get-vat") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                readBack shouldBe "true"

                // Reset for the next test in this class.
                client.post("/test/coverage/update-vat?isKleinunternehmer=false") { header("X-Member-Id", ADMIN_ID) }
            }
        }

        test(
            "vatEnabled is structurally read-only from updateOrganizationSettings -- " +
                "OrganizationSettingsInput has no such field (V1.4.13)",
        ) {
            // OrganizationSettingsInput does not carry vatEnabled at all -- this is a compile-time
            // guarantee (see class KDoc point 1's "structural riegel" idiom, here without needing a
            // separate allowlist because the field is simply absent from the type). A direct DB read
            // after any update through this test class's own route below proves updateOrganizationSettings
            // never touches vat_enabled.
            testApplication {
                routing { registerFieldCoverageTestRoutes() }
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[vatEnabled] = true
                    }
                }
                client.post("/test/coverage/update-vat?isKleinunternehmer=true") { header("X-Member-Id", ADMIN_ID) }
                val stillEnabled =
                    transaction {
                        OrganizationSettingsTable
                            .selectAll()
                            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                            .single()[OrganizationSettingsTable.vatEnabled]
                    }
                stillEnabled shouldBe true

                // Reset.
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[vatEnabled] = false
                        it[isKleinunternehmer] = false
                    }
                }
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
        // Audit-Fund M1 (V1.4.21): the two V1.4.15 mapping fields were missing from this list, so the
        // "structural riegel" this class exists for did not cover them at all -- and the wave right
        // after their introduction promptly shipped the exact bug class it guards against (three
        // client-side wholesale-replace helpers silently reset them, see CHANGELOG [Unreleased]).
        "receivablesAccountId",
        "payablesAccountId",
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

/** The one field order both `/test/coverage/update` and `/test/coverage/get` answer in. */
private fun OrganizationSettingsDto.toCoverageLine(): String =
    listOf(
        paymentBankAccountId ?: "-",
        paymentFeeAccountId ?: "-",
        contributionIncomeAccountId ?: "-",
        donationIncomeAccountId ?: "-",
        eventIncomeAccountId ?: "-",
        travelExpenseAccountId ?: "-",
        volunteerAllowanceAccountId ?: "-",
        receivablesAccountId ?: "-",
        payablesAccountId ?: "-",
        isKleinunternehmer.toString(),
        receivableDunningEnabled.toString(),
    ).joinToString("|")

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
                    // Security Round 2 (HARDENING): folded in here too -- see this test class'
                    // "every known ledger-account mapping field round-trips" test, extended to also
                    // carry isKleinunternehmer through the SAME wholesale-update + "toggle an
                    // unrelated field" round trip as the nine Uuid mapping fields, rather than
                    // ONLY its own isolated round-trip test. The MAJOR finding this fixed (Security
                    // Round 1) was exactly a BOOLEAN field silently dropped at a wholesale-replace
                    // call site -- an isolated round-trip test alone would not have caught that
                    // shape of regression the way this combined one now does.
                    isKleinunternehmer = q["isKleinunternehmer"]?.toBoolean() ?: false,
                    // Audit-Fund M1 (V1.4.21): the two V1.4.15 mapping fields plus the V1.4.15
                    // boolean, folded into the SAME wholesale-replace + "toggle an unrelated field"
                    // round trip as the seven older ones.
                    receivablesAccountId = q["receivablesAccountId"],
                    payablesAccountId = q["payablesAccountId"],
                    receivableDunningEnabled = q["receivableDunningEnabled"]?.toBoolean() ?: false,
                ),
            )
        call.respondText(dto.toCoverageLine())
    }
    post("/test/coverage/get") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().toCoverageLine())
    }
    // V1.4.13 -- isKleinunternehmer round-trip + vatEnabled read-only regression guard.
    post("/test/coverage/update-vat") {
        val service = OrganizationSettingsService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateOrganizationSettings(
                OrganizationSettingsInput(
                    name = "Feldabdeckungs-Testverein e.V.",
                    isKleinunternehmer = q["isKleinunternehmer"]?.toBoolean() ?: false,
                ),
            )
        call.respondText(dto.isKleinunternehmer.toString())
    }
    post("/test/coverage/get-vat") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().isKleinunternehmer.toString())
    }
}
