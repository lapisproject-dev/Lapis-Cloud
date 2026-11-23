package network.lapis.cloud.server.auction

import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsField
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsInvalidException
import java.net.URI

/**
 * V1.9.320 -- validates the seller's payment details of a Fiat auction. Every refusal is an [AuctionPaymentDetailsInvalidException] that names only
 * the FIELD, never the value (the value is personal bank data and an exception message ends up in logs).
 *
 * - **Holder:** trimmed, 2..70 characters, no control characters.
 * - **IBAN (optional):** formally valid (country, length, Mod-97-10 via [IbanValidator]) and, for the pilot, a German one (`DE`, 22 characters).
 * - **Link (optional):** at most 500 characters, an `https` URL with an ASCII host (punycode for an international name -- a homograph defence), no
 *   user-info, no blanks or control characters. Lapis never FETCHES the link (no SSRF); the winner's browser opens it on an explicit click.
 * - At least one of IBAN/link.
 */
internal object AuctionPaymentDetailsValidator {
    const val HOLDER_MIN = 2
    const val HOLDER_MAX = 70
    const val LINK_MAX = 500
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$")
    private val IPV4_LIKE = Regex("^[0-9.]+$")

    fun validate(input: AuctionPaymentDetailsInput): SellerPaymentDetails {
        val holder = input.accountHolder.trim()
        if (holder.length < HOLDER_MIN || holder.length > HOLDER_MAX || holder.any { it.isISOControl() }) {
            throw AuctionPaymentDetailsInvalidException(field = AuctionPaymentDetailsField.ACCOUNT_HOLDER)
        }
        val iban =
            input.iban?.takeIf { it.isNotBlank() }?.let { raw ->
                val normalized = IbanValidator.normalize(raw)
                if (!normalized.startsWith("DE") || normalized.length != 22 || !IbanValidator.isValid(normalized)) {
                    throw AuctionPaymentDetailsInvalidException(field = AuctionPaymentDetailsField.IBAN)
                }
                normalized
            }
        val link =
            input.paymentLink?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                validateLink(raw)
                raw
            }
        if (iban == null && link == null) throw AuctionPaymentDetailsInvalidException(field = AuctionPaymentDetailsField.MISSING)
        return SellerPaymentDetails(accountHolder = holder, iban = iban, paymentLink = link)
    }

    /** The ASCII host of an already validated [link]. */
    fun hostOf(link: String): String? = runCatching { URI(link).host }.getOrNull()

    private fun validateLink(raw: String) {
        fun invalid(): Nothing = throw AuctionPaymentDetailsInvalidException(field = AuctionPaymentDetailsField.PAYMENT_LINK)
        if (raw.length > LINK_MAX || raw.any { it.isWhitespace() || it.isISOControl() || it.code > 126 }) invalid()
        val uri = runCatching { URI(raw) }.getOrNull() ?: invalid()
        if (!"https".equals(uri.scheme, ignoreCase = true)) invalid()
        if (uri.userInfo != null) invalid()
        val host = uri.host ?: invalid()
        if (!HOST.matches(host) || IPV4_LIKE.matches(host)) invalid()
    }
}
