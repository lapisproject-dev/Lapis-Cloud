package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.14 "Mehrere Bankkonten" -- multi-account foundation, see
 * `network.lapis.cloud.server.payment.bankstatement.BankAccountStore` KDoc for the full behaviour
 * (exactly one row is always [BankAccountDto.isDefault], mirrored into
 * [OrganizationSettingsDto.bankIban]/[OrganizationSettingsDto.bankBic]) and
 * `docs/architecture/bank-account.adoc` for Wave 2 "FinTS/HBCI live retrieval" (V1.4.14 Wave 2).
 *
 * [iban] is returned in full (not masked) -- unlike a FinTS PIN or a counterparty's IBAN on a bank
 * statement line, an organization's OWN account IBAN is not a secret (it appears on every invoice
 * letterhead and every SEPA mandate a member signs) and an ADMIN/TREASURER editing this account
 * needs the real value to correct a typo. [ibanMasked] is kept alongside for read-only surfaces
 * that only ever need the `"DE...4711"` display form, same convention
 * [BankStatementImportDto.accountIbanMasked] already establishes.
 *
 * **FinTS fields never carry a secret in cleartext** -- [finTsUserIdMask] is a CONSTANT masked
 * string (never a partial reveal, see `IBankAccountService` KDoc "Kares Entscheidung"), and neither
 * the PIN nor its ciphertext ever appear on this DTO at all.
 */
@Serializable
data class BankAccountDto(
    val id: String,
    val label: String,
    val iban: String,
    val ibanMasked: String,
    val bic: String?,
    val bankName: String?,
    val isDefault: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val finTsStatus: FinTsStatus = FinTsStatus.NOT_CONFIGURED,
    val finTsBlz: String? = null,
    val finTsUrl: String? = null,
    /** Constant `"********"` once configured, `null` for [FinTsStatus.NOT_CONFIGURED] -- never a partial reveal. */
    val finTsUserIdMask: String? = null,
    val finTsPinSetAt: LocalDateTime? = null,
    val finTsLastSuccessAt: LocalDateTime? = null,
    val finTsLastErrorCode: String? = null,
    /** `false` iff `LAPIS_SECRET_ENCRYPTION_KEY` is unconfigured -- see [FinTsErrorCode] `ENCRYPTION_KEY_MISSING`. */
    val finTsAvailable: Boolean = true,
    /**
     * Review fix (MEDIUM, Runde 3): the MOST RECENTLY detected fetch-window gap -- the range that
     * got SKIPPED (`finTsGapFrom..finTsGapTo`), not the range that was actually fetched -- and when
     * it was detected. Unlike [finTsLastErrorCode], these three deliberately do NOT self-clear on
     * the next gap-free poll tick: see `network.lapis.cloud.server.payment.fints.FinTsPoller
     * .handleMt940` KDoc for why a transient, self-healing marker alone made a real gap invisible
     * again within one poll interval. `null` iff no gap has ever been detected for this account.
     */
    val finTsGapFrom: LocalDate? = null,
    val finTsGapTo: LocalDate? = null,
    val finTsGapDetectedAt: LocalDateTime? = null,
)

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- the status machine has EXACTLY three values
 * (structurally enforced by `chk_bank_account_fints_status`, see `V33__bank_account_fints.sql`).
 * There is deliberately no fourth "DISABLED" value -- [network.lapis.cloud.shared.rpc.IBankAccountService.disableFinTs]
 * clears every credential column AND resets this to [NOT_CONFIGURED] atomically, so "disabled with
 * credentials still lying around" cannot exist as a state.
 */
@Serializable
enum class FinTsStatus { NOT_CONFIGURED, ACTIVE, REAUTH_REQUIRED }

/**
 * Input for [network.lapis.cloud.shared.rpc.IBankAccountService.beginFinTsSetup]. [userId]/[pin]
 * are ONLY ever sent, never returned -- see [BankAccountDto] KDoc "never carries a secret".
 */
@Serializable
data class FinTsSetupInput(
    val bankAccountId: String,
    val blz: String,
    val url: String,
    val userId: String,
    val pin: String,
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IBankAccountService.beginFinTsSetup]/[network.lapis.cloud.shared.rpc.IBankAccountService.submitFinTsTan].
 * [Failed.code] is always a [FinTsErrorCode] name -- never a raw bank message, host, or IP (see
 * `network.lapis.cloud.server.payment.fints.FinTsClient` KDoc).
 */
@Serializable
sealed interface FinTsSetupResultDto {
    @Serializable
    data class Verified(
        val account: BankAccountDto,
        val statementCount: Int,
    ) : FinTsSetupResultDto

    /** [handle] is opaque, single-use, valid for 300 seconds (hbci4j's own `kernel.threaded.maxwaittime` default). */
    @Serializable
    data class TanRequested(
        val handle: String,
        val bankPrompt: String,
        val expiresAt: LocalDateTime,
    ) : FinTsSetupResultDto

    @Serializable
    data class Failed(
        val code: String,
        val message: String,
    ) : FinTsSetupResultDto
}

/**
 * The disclaimer an ADMIN must be shown -- and echo back verbatim -- before
 * [network.lapis.cloud.shared.rpc.IBankAccountService.beginFinTsSetup] will accept a setup attempt.
 * Structural twin of `VatComplianceDisclaimerDto`.
 */
@Serializable
data class FinTsComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/** Role: TREASURER/ADMIN (write). [isDefault] is deliberately absent -- see [IBankAccountService.setDefaultBankAccount]. */
@Serializable
data class BankAccountInput(
    val label: String,
    val iban: String,
    val bic: String? = null,
    val bankName: String? = null,
)

/**
 * Audit-Snapshot fuer `AuditEntityType.BANK_ACCOUNT` -- traegt NIEMALS die volle IBAN, nur die
 * maskierte Form (same "before/after never repeats the sensitive value in full" convention every
 * other `*Snapshot` in this codebase already applies, e.g. `ApiKeySnapshot` never carrying the
 * token hash).
 */
@Serializable
data class BankAccountSnapshot(
    val label: String,
    val ibanMasked: String,
    val bic: String?,
    val bankName: String?,
    val isDefault: Boolean,
    /** Welle V1.4.14 Wave 2 -- added for FinTS status-transition audit entries. Never a credential. */
    val finTsStatus: FinTsStatus = FinTsStatus.NOT_CONFIGURED,
)
