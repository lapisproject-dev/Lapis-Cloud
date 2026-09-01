package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- German label/badge-color tables, same
 * `SepaLabels.kt` grammar: `when` over `entries`, exhaustive, `gettext(...)`.
 */
fun paymentCheckoutSessionStatusLabel(status: PaymentCheckoutSessionStatus): String =
    when (status) {
        PaymentCheckoutSessionStatus.CREATED -> gettext("Erstellt")
        PaymentCheckoutSessionStatus.COMPLETED -> gettext("Abgeschlossen")
        PaymentCheckoutSessionStatus.EXPIRED -> gettext("Abgelaufen")
        PaymentCheckoutSessionStatus.FAILED -> gettext("Fehlgeschlagen")
    }

fun paymentCheckoutSessionStatusColor(status: PaymentCheckoutSessionStatus): String =
    when (status) {
        PaymentCheckoutSessionStatus.CREATED -> "info"
        PaymentCheckoutSessionStatus.COMPLETED -> "success"
        PaymentCheckoutSessionStatus.EXPIRED -> "secondary"
        PaymentCheckoutSessionStatus.FAILED -> "danger"
    }

fun paymentTransactionStatusLabel(status: PaymentTransactionStatus): String =
    when (status) {
        PaymentTransactionStatus.PENDING -> gettext("Ausstehend")
        PaymentTransactionStatus.CAPTURED -> gettext("Erfasst")
        PaymentTransactionStatus.FAILED -> gettext("Fehlgeschlagen")
        PaymentTransactionStatus.REFUNDED -> gettext("Erstattet")
        PaymentTransactionStatus.DISPUTED -> gettext("Angefochten")
    }

fun paymentTransactionStatusColor(status: PaymentTransactionStatus): String =
    when (status) {
        PaymentTransactionStatus.PENDING -> "secondary"
        PaymentTransactionStatus.CAPTURED -> "success"
        PaymentTransactionStatus.FAILED -> "danger"
        PaymentTransactionStatus.REFUNDED -> "warning"
        PaymentTransactionStatus.DISPUTED -> "danger"
    }

fun paymentIntentLabel(intent: PaymentIntent): String =
    when (intent) {
        PaymentIntent.CONTRIBUTION -> gettext("Mitgliedsbeitrag")
        PaymentIntent.DONATION -> gettext("Spende")
    }

fun paymentIntentColor(intent: PaymentIntent): String =
    when (intent) {
        PaymentIntent.CONTRIBUTION -> "primary"
        PaymentIntent.DONATION -> "info"
    }

fun paymentProviderLabel(provider: PaymentProvider): String =
    when (provider) {
        PaymentProvider.PAYPAL -> gettext("PayPal")
        PaymentProvider.STRIPE -> gettext("Stripe")
        PaymentProvider.MANUAL -> gettext("Manuell")
    }

fun paymentProviderColor(provider: PaymentProvider): String =
    when (provider) {
        PaymentProvider.PAYPAL -> "info"
        PaymentProvider.STRIPE -> "primary"
        PaymentProvider.MANUAL -> "secondary"
    }
