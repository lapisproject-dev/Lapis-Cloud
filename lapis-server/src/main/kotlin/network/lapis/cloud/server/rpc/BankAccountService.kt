package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankAccountFinTsAcknowledgmentTable
import network.lapis.cloud.server.payment.bankstatement.BankAccountStore
import network.lapis.cloud.server.payment.fints.FinTsCredentials
import network.lapis.cloud.server.payment.fints.FinTsErrorCode
import network.lapis.cloud.server.payment.fints.FinTsSetupClient
import network.lapis.cloud.server.payment.fints.FinTsSetupOutcome
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.webhook.WebhookUrlCheck
import network.lapis.cloud.server.webhook.checkWebhookUrl
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.FinTsComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.FinTsSetupInput
import network.lapis.cloud.shared.domain.FinTsSetupResultDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IBankAccountService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Read roles for `IBankAccountService` -- TREASURER/BOARD/ADMIN, same tier `BANK_STATEMENT_READ_ROLES` establishes. */
internal val BANK_ACCOUNT_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Write roles for `IBankAccountService` -- TREASURER/ADMIN, same narrower tier `BANK_STATEMENT_WRITE_ROLES` establishes (deliberately excludes BOARD). */
internal val BANK_ACCOUNT_WRITE_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/**
 * RPC surface for `IBankAccountService` -- see that interface's KDoc. Thin role-gating wrapper
 * around `BankAccountStore` (Wave 1 methods) and, since Wave 2, [finTsSetupClient]/[secretBox]/
 * [FinTsComplianceDisclaimer] (the five FinTS methods, all ADMIN-only except [getFinTsComplianceDisclaimer]).
 */
internal class BankAccountService(
    private val call: ApplicationCall,
    private val finTsSetupClient: FinTsSetupClient,
    private val secretBox: SecretBox?,
) : IBankAccountService {
    override suspend fun listBankAccounts(): List<BankAccountDto> {
        resolveCurrentMember(call).requireRole(*BANK_ACCOUNT_READ_ROLES)
        return BankAccountStore.listDtos().map { it.copy(finTsAvailable = secretBox != null) }
    }

    override suspend fun createBankAccount(input: BankAccountInput): BankAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore.create(input = input, actorMemberId = current.memberId, actorRole = current.role).withFinTsAvailability()
    }

    override suspend fun updateBankAccount(
        bankAccountId: String,
        input: BankAccountInput,
    ): BankAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore
            .update(
                bankAccountId = bankAccountId.toBankAccountUuid(),
                input = input,
                actorMemberId = current.memberId,
                actorRole = current.role,
            ).withFinTsAvailability()
    }

    override suspend fun deleteBankAccount(bankAccountId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        BankAccountStore.delete(
            bankAccountId = bankAccountId.toBankAccountUuid(),
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    override suspend fun setDefaultBankAccount(bankAccountId: String): List<BankAccountDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore
            .setDefault(bankAccountId = bankAccountId.toBankAccountUuid(), actorMemberId = current.memberId, actorRole = current.role)
            .map { it.copy(finTsAvailable = secretBox != null) }
    }

    // ============================================================================================
    // Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- all ADMIN-only except the disclaimer read.
    // ============================================================================================

    override suspend fun getFinTsComplianceDisclaimer(): FinTsComplianceDisclaimerDto {
        resolveCurrentMember(call).requireRole(*BANK_ACCOUNT_READ_ROLES)
        return FinTsComplianceDisclaimerDto(
            version = FinTsComplianceDisclaimer.VERSION,
            text = FinTsComplianceDisclaimer.TEXT,
            sha256 = FinTsComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun beginFinTsSetup(input: FinTsSetupInput): FinTsSetupResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)

        if (!FinTsComplianceDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 stimmen nicht mit dem aktuellen FinTsComplianceDisclaimer ueberein -- " +
                    "getFinTsComplianceDisclaimer erneut aufrufen und dessen AKTUELLE version/sha256 unveraendert senden.",
            )
        }
        if (secretBox == null) {
            throw ConflictException("LAPIS_SECRET_ENCRYPTION_KEY ist nicht konfiguriert -- FinTS kann nicht aktiviert werden.")
        }

        val bankAccountId = input.bankAccountId.toBankAccountUuid()
        val existing =
            BankAccountStore.dtoOrNull(bankAccountId = bankAccountId, finTsAvailable = true)
                ?: throw NotFoundException("BankAccount $bankAccountId not found")

        val urlCheck = checkWebhookUrl(raw = input.url, allowInsecureHttp = false)
        if (urlCheck is WebhookUrlCheck.Rejected) {
            return FinTsSetupResultDto.Failed(code = FinTsErrorCode.URL_REJECTED.name, message = "Die angegebene URL wurde abgelehnt.")
        }

        // Ack-Zeile VOR dem eigentlichen Setup-Versuch schreiben -- append-only, ueberlebt einen
        // spaeteren disableFinTs + Reaktivierung als Historie (siehe V33-Migration KDoc).
        transaction {
            BankAccountFinTsAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[BankAccountFinTsAcknowledgmentTable.bankAccountId] = bankAccountId
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = DbClock.nowLocalDateTime()
                it[disclaimerVersion] = input.disclaimerVersion
                it[disclaimerSha256] = input.disclaimerSha256
            }
        }

        val credentials =
            FinTsCredentials(
                bankAccountId = bankAccountId,
                blz = input.blz,
                url = input.url,
                userId = input.userId,
                pin = input.pin,
                iban = existing.iban,
            )
        // Review fix (MAJOR): FinTsClient.kt's documented contract requires every caller to wrap
        // blocking hbci4j calls in Dispatchers.IO -- FinTsPoller already does (see fetch there),
        // this call site was the one that didn't, and Hbci4jFinTsClient.begin() really does block
        // the calling thread on a CountDownLatch for up to dialogTimeoutSeconds (default 60s, up to
        // 300s), which would otherwise tie up a live Ktor call-handling thread for the whole wait.
        return when (val outcome = withContext(Dispatchers.IO) { finTsSetupClient.begin(credentials) }) {
            is FinTsSetupOutcome.Verified -> persistVerified(outcome = outcome, actorMemberId = current.memberId, actorRole = current.role)
            is FinTsSetupOutcome.TanRequested ->
                FinTsSetupResultDto.TanRequested(handle = outcome.handle, bankPrompt = outcome.bankPrompt, expiresAt = outcome.expiresAt)
            is FinTsSetupOutcome.Failed -> FinTsSetupResultDto.Failed(code = outcome.code.name, message = failedMessage(outcome.code))
        }
    }

    override suspend fun submitFinTsTan(
        handle: String,
        tan: String,
    ): FinTsSetupResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        // Review fix (MAJOR): same Dispatchers.IO contract as beginFinTsSetup above --
        // Hbci4jFinTsClient.submitTan() blocks on a CompletableFuture.get(dialogTimeoutSeconds).
        return when (val outcome = withContext(Dispatchers.IO) { finTsSetupClient.submitTan(handle = handle, tan = tan) }) {
            is FinTsSetupOutcome.Verified -> persistVerified(outcome = outcome, actorMemberId = current.memberId, actorRole = current.role)
            is FinTsSetupOutcome.TanRequested ->
                FinTsSetupResultDto.TanRequested(handle = outcome.handle, bankPrompt = outcome.bankPrompt, expiresAt = outcome.expiresAt)
            is FinTsSetupOutcome.Failed -> FinTsSetupResultDto.Failed(code = outcome.code.name, message = failedMessage(outcome.code))
        }
    }

    override suspend fun cancelFinTsSetup(handle: String) {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        finTsSetupClient.cancel(handle)
    }

    override suspend fun disableFinTs(bankAccountId: String): BankAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return BankAccountStore.disableFinTs(
            bankAccountId = bankAccountId.toBankAccountUuid(),
            actorMemberId = current.memberId,
            actorRole = current.role,
            finTsAvailable = secretBox != null,
        )
    }

    private fun persistVerified(
        outcome: FinTsSetupOutcome.Verified,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): FinTsSetupResultDto.Verified {
        val box =
            secretBox ?: throw ConflictException("LAPIS_SECRET_ENCRYPTION_KEY ist nicht konfiguriert -- FinTS kann nicht aktiviert werden.")
        val bankAccountId = outcome.credentials.bankAccountId
        val userIdCiphertext = box.seal(plaintext = outcome.credentials.userId, aad = bankAccountId.toString())
        val pinCiphertext = box.seal(plaintext = outcome.credentials.pin, aad = bankAccountId.toString())
        val dto =
            BankAccountStore.activateFinTs(
                bankAccountId = bankAccountId,
                blz = outcome.credentials.blz,
                url = outcome.credentials.url,
                userIdCiphertext = userIdCiphertext,
                pinCiphertext = pinCiphertext,
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                finTsAvailable = true,
            )
        return FinTsSetupResultDto.Verified(account = dto, statementCount = outcome.statementCount)
    }

    private fun failedMessage(code: FinTsErrorCode): String =
        when (code) {
            FinTsErrorCode.URL_REJECTED -> "Die angegebene URL wurde abgelehnt."
            FinTsErrorCode.AUTH_FAILED -> "Anmeldung fehlgeschlagen -- bitte Benutzerkennung/PIN pruefen."
            FinTsErrorCode.TAN_REQUIRED -> "Der Vorgang wurde abgebrochen, bevor eine TAN eingegeben wurde."
            FinTsErrorCode.BANK_UNAVAILABLE -> "Die Bank war nicht erreichbar -- bitte spaeter erneut versuchen."
            FinTsErrorCode.TIMEOUT -> "Zeitueberschreitung bei der Verbindung zur Bank."
            FinTsErrorCode.PROTOCOL_ERROR -> "Es ist ein Protokollfehler aufgetreten."
            FinTsErrorCode.STATEMENT_FORMAT_UNSUPPORTED -> "Diese Bank wird nicht unterstuetzt (kein MT940-Format)."
            FinTsErrorCode.ENCRYPTION_KEY_MISSING -> "LAPIS_SECRET_ENCRYPTION_KEY ist nicht konfiguriert."
        }

    private fun BankAccountDto.withFinTsAvailability(): BankAccountDto = copy(finTsAvailable = secretBox != null)

    private fun String.toBankAccountUuid(): Uuid =
        runCatching {
            Uuid.parse(this)
        }.getOrElse { throw BadRequestException("Invalid bankAccountId") }
}
