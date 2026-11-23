package network.lapis.cloud.server.auction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * V1.9.320 -- the seller's payment details of one Fiat auction, as validated and normalised by [AuctionPaymentDetailsValidator]. [iban] is stored
 * without blanks, [paymentLink] is an `https` URL. **[toString] is redacted and this is deliberately NOT a data class** (no generated
 * `toString`/`equals`/`componentN` that could print or compare the values by accident). Only [AuctionPaymentDetailsStore] turns it into the sealed blob.
 */
internal class SellerPaymentDetails(
    val accountHolder: String,
    val iban: String?,
    val paymentLink: String?,
) {
    override fun toString(): String = "SellerPaymentDetails(redacted)"

    /** The JSON that gets sealed. Never logged. */
    fun toSealedJson(): String =
        Json.encodeToString(Wire.serializer(), Wire(accountHolder = accountHolder, iban = iban, paymentLink = paymentLink))

    @Serializable
    private class Wire(
        val accountHolder: String,
        val iban: String? = null,
        val paymentLink: String? = null,
    ) {
        override fun toString(): String = "Wire(redacted)"
    }

    companion object {
        fun fromSealedJson(json: String): SellerPaymentDetails {
            val wire = Json.decodeFromString(Wire.serializer(), json)
            return SellerPaymentDetails(accountHolder = wire.accountHolder, iban = wire.iban, paymentLink = wire.paymentLink)
        }
    }
}
