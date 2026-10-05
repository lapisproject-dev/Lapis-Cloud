package network.lapis.cloud.server.payment.psp

import kotlin.uuid.Uuid

/**
 * Welle V1.9.55 -- deterministic provider idempotency keys.
 *
 * The `checkoutSessionId` is this server's own UUIDv4 `payment_checkout_session.id`, created once per logical
 * checkout. Deriving the provider key from it (instead of a fresh random value per HTTP call) means a retried
 * `createCheckout` for the SAME logical checkout is deduplicated by Stripe / PayPal instead of creating a second
 * hosted session. Both providers scope idempotency keys to the API account, so the key is worthless without the
 * API secret. Parsing through [Uuid] enforces a canonical UUID: arbitrary text can never reach the header, and the
 * result is far below the 255-character provider limit.
 */
internal object PspIdempotencyKeys {
    private const val CHECKOUT_PREFIX = "lapis-checkout-v1-"

    fun checkout(checkoutSessionId: String): String = CHECKOUT_PREFIX + Uuid.parse(checkoutSessionId)
}
