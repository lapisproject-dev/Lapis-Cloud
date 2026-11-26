package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.DonationChannel
import network.lapis.cloud.shared.domain.PaymentProvider

/** V1.9.329 -- the channel label of a donation. Information only: no rule of the conversion depends on it. */
internal object DonationChannelRules {
    /** A booking day means a bank line backs the donation; otherwise the payment provider tells (a manual payment without a bank line is "other"). */
    fun channelOf(
        bookingDate: LocalDate?,
        provider: PaymentProvider,
    ): DonationChannel =
        when {
            bookingDate != null -> DonationChannel.BANK
            provider == PaymentProvider.PAYPAL -> DonationChannel.PAYPAL
            provider == PaymentProvider.STRIPE -> DonationChannel.STRIPE
            else -> DonationChannel.OTHER
        }
}
