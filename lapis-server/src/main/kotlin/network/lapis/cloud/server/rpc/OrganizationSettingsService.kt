package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.payment.sepa.BicValidator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.OrganizationSettingsPaymentMappingSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * The single seeded [OrganizationSettingsTable] row's fixed id -- see
 * `lapis-server/src/main/resources/db/migration/V1__baseline.sql`'s unconditional seed `INSERT`
 * (not gated behind `LAPIS_SEED_DEMO_DATA`, unlike `network.lapis.cloud.server.db.DevSeedData`'s
 * own sentinel ids -- letterhead data existing at all is a real capability precondition, not
 * demo/sample data) and `11-organization-settings.kuml.kts`'s file header for the full
 * exactly-one-row-by-convention rationale.
 */
val ORGANIZATION_SETTINGS_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f2")

/**
 * Implements [IOrganizationSettingsService] -- see that interface's KDoc. There is no create/
 * delete; both [getOrganizationSettings] and [updateOrganizationSettings] always target the one
 * row seeded at [ORGANIZATION_SETTINGS_ID].
 */
class OrganizationSettingsService(
    private val call: ApplicationCall,
) : IOrganizationSettingsService {
    override suspend fun getOrganizationSettings(): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*READ_ROLES)
        return transaction { loadOrganizationSettings() }
    }

    /**
     * Security Round 1 (2026-08-19). This method wholesale-replaces EVERY field of the single
     * seeded row -- including the three payment-account-mapping fields
     * ([OrganizationSettingsDto.paymentBankAccountId]/[OrganizationSettingsDto.paymentFeeAccountId]/
     * [OrganizationSettingsDto.contributionIncomeAccountId]) that decide where every FUTURE
     * contribution payment gets booked (see [ContributionPostingBridge] KDoc). Two fixes:
     *
     * **MAJOR-1 (defense-in-depth, "unreachable by construction" half)**: [requireValidPaymentAccountMapping]
     * rejects a mapping target that does not exist, is inactive, is a cash-register account (see
     * [ContributionPostingBridge] KDoc "GoBD-Kassenbestands-Guard" for the other, runtime half of
     * this same fix, which additionally still applies [CashRegisterGuard] at posting time), or has
     * the wrong [LedgerAccountType] for its role (bank -> `ASSET`, fee -> `EXPENSE`, income ->
     * `INCOME`, matching every `ContributionPostingBridgeTest`/`ContributionPaymentRpcTest` fixture's
     * own account-type choice for these three roles). A malformed id string is a
     * [NotFoundException] (this codebase's established "well-formed-ness vs. semantic validity"
     * split, see `ContributionService.toContributionUuid`); an existing-but-wrong-state/-type
     * account is a [ConflictException] (same tier [requireActiveLedgerAccounts] itself uses).
     *
     * **MAJOR-2 (GoBD Nachvollziehbarkeit)**: writes an [AuditEntityType.ORGANIZATION_SETTINGS]
     * `UPDATE` audit entry via [AuditLogRecorder] -- but ONLY when at least one of the three mapping
     * fields actually changed. Deliberately narrower than "audit the whole diff": this method also
     * replaces many purely administrative, non-financial fields (address, bank-IBAN-for-display,
     * tax-exemption authority/date) on every call, and an audit-log entry on every one of those
     * calls would flood the GoBD trail with entries unrelated to the concern
     * [OrganizationSettingsPaymentMappingSnapshot] exists for -- "who repointed which contribution
     * booking, when". If a broader audit trail for the other fields is ever wanted, that is a
     * separate, deliberate future decision, not an oversight of this fix. Must be the LAST database
     * operation of this transaction that takes a row lock (see [AuditLogRecorder] KDoc) -- the
     * `OrganizationSettingsTable.update` below always runs first.
     */
    override suspend fun updateOrganizationSettings(input: OrganizationSettingsInput): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val bankAccountId = input.paymentBankAccountId?.toPaymentAccountUuid("paymentBankAccountId")
        val feeAccountId = input.paymentFeeAccountId?.toPaymentAccountUuid("paymentFeeAccountId")
        val incomeAccountId = input.contributionIncomeAccountId?.toPaymentAccountUuid("contributionIncomeAccountId")
        // Security Round 1 (2026-08-20, MINOR-5): `bankIban`/`bankBic` are used as the CREDITOR's own
        // IBAN/BIC in every SEPA pain.008 file generated by SepaService.generateBatchFile (via the
        // frozen `sepa_debit_batch.creditor_iban`/`.creditor_bic` snapshot, see that class' KDoc) --
        // unlike `debtorBic` (validated at `grantMandate`), these were previously persisted with ZERO
        // validation anywhere, so a malformed value would either sail into the bank file unchecked or
        // surface as a raw, unmapped IllegalArgumentException (HTTP 500) once
        // SepaPain008Writer.validate finally ran. Applying the SAME IbanValidator/BicValidator this
        // wave already uses for the debtor side, at the point the value is SAVED -- catching the
        // problem at admin-entry time rather than deep inside a treasurer's later file-generation
        // attempt. normalizedBankIban uses IbanValidator's own canonical (whitespace-stripped,
        // upper-cased) form, same as every other IBAN this codebase persists.
        val normalizedBankIban =
            input.bankIban?.let { raw ->
                try {
                    IbanValidator.requireValid(raw)
                } catch (e: IllegalArgumentException) {
                    throw ConflictException("Die IBAN der Organisation (bankIban) ist ungueltig: ${e.message}")
                }
            }
        input.bankBic?.let {
            if (!BicValidator.isValid(it)) {
                throw ConflictException("Die BIC der Organisation (bankBic) hat kein gueltiges Format.")
            }
        }
        return transaction {
            requireValidPaymentAccountMapping(
                role = "paymentBankAccountId",
                accountId = bankAccountId,
                expectedType = LedgerAccountType.ASSET,
            )
            requireValidPaymentAccountMapping(
                role = "paymentFeeAccountId",
                accountId = feeAccountId,
                expectedType = LedgerAccountType.EXPENSE,
            )
            requireValidPaymentAccountMapping(
                role = "contributionIncomeAccountId",
                accountId = incomeAccountId,
                expectedType = LedgerAccountType.INCOME,
            )

            val beforeRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()
            val beforeMapping =
                OrganizationSettingsPaymentMappingSnapshot(
                    paymentBankAccountId = beforeRow[OrganizationSettingsTable.paymentBankAccountId]?.toString(),
                    paymentFeeAccountId = beforeRow[OrganizationSettingsTable.paymentFeeAccountId]?.toString(),
                    contributionIncomeAccountId = beforeRow[OrganizationSettingsTable.contributionIncomeAccountId]?.toString(),
                )

            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[name] = input.name
                it[street] = input.street
                it[postalCode] = input.postalCode
                it[city] = input.city
                it[country] = input.country
                it[bankIban] = normalizedBankIban
                it[bankBic] = input.bankBic
                it[taxExemptionAuthority] = input.taxExemptionAuthority
                it[taxExemptionDate] = input.taxExemptionDate
                it[isPoliticalParty] = input.isPoliticalParty
                it[postalMailEnabled] = input.postalMailEnabled
                it[politicianRankingEnabled] = input.politicianRankingEnabled
                it[paymentBankAccountId] = bankAccountId
                it[paymentFeeAccountId] = feeAccountId
                it[contributionIncomeAccountId] = incomeAccountId
                // auctionEnabled/auctionMaxValueLtr are DELIBERATELY absent from this write-set --
                // see OrganizationSettingsDto.auctionEnabled KDoc. The generic update path must
                // never be able to flip the auction gate; only AuctionService.enableAuction
                // (disclaimer-acknowledgment required)/disableAuction/setAuctionMaxValueLtr may.
                // sepaDebitEnabled/paymentGatewayEnabled/paymentGatewayProvider are likewise
                // DELIBERATELY absent -- see OrganizationSettingsDto.sepaDebitEnabled KDoc. Only
                // SepaService.enableSepaDebit/disableSepaDebit and
                // PaymentGatewayService.enablePaymentGateway/disablePaymentGateway (both
                // disclaimer-acknowledgment-gated) may flip those.
            }

            val afterMapping =
                OrganizationSettingsPaymentMappingSnapshot(
                    paymentBankAccountId = bankAccountId?.toString(),
                    paymentFeeAccountId = feeAccountId?.toString(),
                    contributionIncomeAccountId = incomeAccountId?.toString(),
                )
            if (beforeMapping != afterMapping) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(OrganizationSettingsPaymentMappingSnapshot.serializer(), beforeMapping),
                    after = Json.encodeToString(OrganizationSettingsPaymentMappingSnapshot.serializer(), afterMapping),
                )
            }

            loadOrganizationSettings()
        }
    }
}

/**
 * SHOULD-1 (2026-08-19): parses a payment-account-mapping id string, same
 * `runCatching { Uuid.parse(...) }.getOrElse { throw NotFoundException(...) }` convention
 * `ContributionService.toContributionUuid`/`AccountingService.toAccountingUuid` already establish
 * for "malformed id" -- a static input-shape problem, not a semantic-validity one (see
 * [requireValidPaymentAccountMapping] for that tier).
 */
private fun String.toPaymentAccountUuid(role: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $role LedgerAccount id: $this") }

/**
 * SHOULD-1 (2026-08-19): [accountId] (a `null` mapping is always valid -- see
 * [OrganizationSettingsDto.paymentBankAccountId] KDoc, an unconfigured mapping degrades
 * [ContributionPostingBridge] to a no-op rather than failing) must reference an existing, active,
 * non-cash-register [LedgerAccountTable] row of [expectedType]. Same "existing entity found but in
 * the wrong state/kind" [ConflictException] tier `AccountingService.requireActiveLedgerAccounts`
 * already uses. The cash-register rejection is this method's MAJOR-1 half (see
 * [ContributionPostingBridge]'s own "GoBD-Kassenbestands-Guard" KDoc for the runtime half):
 * `isCashRegister` accounts model a physical Kassenbuch till, never a bank/fee/income mapping
 * target, so rejecting one here makes the MAJOR-1 failure scenario unreachable by construction, in
 * ADDITION to (not instead of) the [CashRegisterGuard] runtime guard [ContributionPostingBridge]
 * now also applies.
 */
private fun requireValidPaymentAccountMapping(
    role: String,
    accountId: Uuid?,
    expectedType: LedgerAccountType,
) {
    if (accountId == null) return
    val row =
        LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            ?: throw NotFoundException("LedgerAccount $accountId ($role) not found")
    if (!row[LedgerAccountTable.active]) {
        throw ConflictException("LedgerAccount $accountId ($role) is not active")
    }
    if (row[LedgerAccountTable.isCashRegister]) {
        throw ConflictException(
            "LedgerAccount $accountId ($role) is a cash-register account (isCashRegister=true) and must not be used " +
                "as a payment-account mapping target -- see ContributionPostingBridge KDoc",
        )
    }
    if (row[LedgerAccountTable.type] != expectedType) {
        throw ConflictException("LedgerAccount $accountId ($role) must be of type $expectedType, got ${row[LedgerAccountTable.type]}")
    }
}

private fun loadOrganizationSettings(): OrganizationSettingsDto =
    OrganizationSettingsTable
        .selectAll()
        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
        .singleOrNull()
        ?.toOrganizationSettingsDto()
        ?: throw NotFoundException("OrganizationSettings row $ORGANIZATION_SETTINGS_ID not found -- baseline seed missing?")

/**
 * Single shared mapper for the whole codebase -- also reused by
 * [network.lapis.cloud.server.routes.registerMailmergeRoutes] (via its private
 * `loadOrganizationSettingsDto` wrapper) so a future field addition to [OrganizationSettingsDto]
 * only ever needs updating here, not duplicated field-by-field at every call site.
 */
fun ResultRow.toOrganizationSettingsDto(): OrganizationSettingsDto =
    OrganizationSettingsDto(
        id = this[OrganizationSettingsTable.id].toString(),
        name = this[OrganizationSettingsTable.name],
        street = this[OrganizationSettingsTable.street],
        postalCode = this[OrganizationSettingsTable.postalCode],
        city = this[OrganizationSettingsTable.city],
        country = this[OrganizationSettingsTable.country],
        bankIban = this[OrganizationSettingsTable.bankIban],
        bankBic = this[OrganizationSettingsTable.bankBic],
        taxExemptionAuthority = this[OrganizationSettingsTable.taxExemptionAuthority],
        taxExemptionDate = this[OrganizationSettingsTable.taxExemptionDate],
        isPoliticalParty = this[OrganizationSettingsTable.isPoliticalParty],
        postalMailEnabled = this[OrganizationSettingsTable.postalMailEnabled],
        politicianRankingEnabled = this[OrganizationSettingsTable.politicianRankingEnabled],
        auctionEnabled = this[OrganizationSettingsTable.auctionEnabled],
        auctionMaxValueLtr = this[OrganizationSettingsTable.auctionMaxValueLtr],
        sepaDebitEnabled = this[OrganizationSettingsTable.sepaDebitEnabled],
        paymentGatewayEnabled = this[OrganizationSettingsTable.paymentGatewayEnabled],
        paymentGatewayProvider = this[OrganizationSettingsTable.paymentGatewayProvider],
        paymentBankAccountId = this[OrganizationSettingsTable.paymentBankAccountId]?.toString(),
        paymentFeeAccountId = this[OrganizationSettingsTable.paymentFeeAccountId]?.toString(),
        contributionIncomeAccountId = this[OrganizationSettingsTable.contributionIncomeAccountId]?.toString(),
    )
