package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- exhaustive `entries.forEach` coverage
 * for every new enum's label/color function, same shape [SepaLabelsTest]/[DunningLabelsTest]
 * establish: every literal has a non-blank label and a non-blank color.
 */
class PaymentLabelsTest {
    @Test
    fun everyPaymentCheckoutSessionStatusHasANonBlankLabelAndColor() {
        PaymentCheckoutSessionStatus.entries.forEach { status ->
            assertTrue(paymentCheckoutSessionStatusLabel(status).isNotBlank(), "label missing for $status")
            assertTrue(paymentCheckoutSessionStatusColor(status).isNotBlank(), "color missing for $status")
        }
    }

    @Test
    fun everyPaymentTransactionStatusHasANonBlankLabelAndColor() {
        PaymentTransactionStatus.entries.forEach { status ->
            assertTrue(paymentTransactionStatusLabel(status).isNotBlank(), "label missing for $status")
            assertTrue(paymentTransactionStatusColor(status).isNotBlank(), "color missing for $status")
        }
    }

    @Test
    fun everyPaymentIntentHasANonBlankLabelAndColor() {
        PaymentIntent.entries.forEach { intent ->
            assertTrue(paymentIntentLabel(intent).isNotBlank(), "label missing for $intent")
            assertTrue(paymentIntentColor(intent).isNotBlank(), "color missing for $intent")
        }
    }

    @Test
    fun everyPaymentProviderHasANonBlankLabelAndColor() {
        PaymentProvider.entries.forEach { provider ->
            assertTrue(paymentProviderLabel(provider).isNotBlank(), "label missing for $provider")
            assertTrue(paymentProviderColor(provider).isNotBlank(), "color missing for $provider")
        }
    }
}

/**
 * Welle V1.2.8, code review round 2 -- regression guard for the §25-PartG donor-category select on
 * `DonationCheckoutScreen`. The select used to pre-select `DonorCategory.entries.first()`
 * (`GERMAN_NATURAL_PERSON`), which (a) let the system make a legally significant self-declaration on
 * the donor's behalf and (b) made the screen's own "Bitte eine Spenderkategorie auswählen"
 * validation unreachable. The contract asserted here is the fix: a leading blank-keyed placeholder,
 * so `DonorCategory.valueOf("")` fails and the required-field branch actually fires.
 */
class DonationCheckoutDonorCategoryOptionsTest {
    @Test
    fun firstOptionIsABlankKeyedPlaceholderSoNoCategoryIsEverPreSelected() {
        val first = donorCategoryOptions().first()
        assertTrue(first.first.isEmpty(), "leading option must have a blank key, was '${first.first}'")
        assertTrue(first.second.isNotBlank(), "leading placeholder needs a visible label")
    }

    @Test
    fun everyDonorCategoryIsStillOfferedExactlyOnce() {
        val keys = donorCategoryOptions().map { it.first }.filter { it.isNotEmpty() }
        assertEquals(DonorCategory.entries.map { it.name }, keys)
    }

    @Test
    fun theBlankPlaceholderKeyDoesNotResolveToADonorCategory() {
        assertNull(runCatching { DonorCategory.valueOf("") }.getOrNull())
    }
}
