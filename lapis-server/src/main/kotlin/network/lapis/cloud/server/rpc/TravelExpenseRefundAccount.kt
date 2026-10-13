package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import kotlin.uuid.Uuid

/**
 * Welle V1.9.123 -- sealing of a member's refund IBAN. The IBAN is stored ONLY as a [SecretBox] ciphertext whose AAD is bound to the
 * report id, so a ciphertext copied onto another report row can never be opened. A fresh [SecretBox] state per call (it is
 * thread-safe by construction); no plaintext fallback exists -- without a key the caller refuses with 409.
 */
internal object TravelExpenseRefundAccount {
    const val MAX_HOLDER_LENGTH: Int = 70

    fun aad(reportId: Uuid): String = "travel_expense_report.refund_iban:$reportId"

    fun seal(
        box: SecretBox,
        reportId: Uuid,
        normalizedIban: String,
    ): String = box.seal(plaintext = normalizedIban, aad = aad(reportId))

    /** @throws ConflictException when the ciphertext cannot be opened (wrong key/report/tampering) -- never with any content. */
    fun open(
        box: SecretBox,
        reportId: Uuid,
        ciphertext: String,
    ): String =
        try {
            box.open(sealed = ciphertext, aad = aad(reportId))
        } catch (_: SecretBoxException) {
            throw ConflictException("refund_iban_unreadable")
        }

    /** Trim; `null`/blank -> `null`; 1..70 characters, no control characters, else [BadRequestException]. */
    fun normalizeHolder(raw: String?): String? {
        val trimmed = raw?.trim()?.ifBlank { null } ?: return null
        if (trimmed.length > MAX_HOLDER_LENGTH || trimmed.any { it.isISOControl() }) throw BadRequestException("refund_holder_invalid")
        return trimmed
    }
}
