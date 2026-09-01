package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- pure, DOM-free client-side mirror of
 * the server's own role tiers for the payment-gateway RPC surface (`PaymentGatewayService`). Same
 * "UX nicety on top of the server's real authority" caveat [SepaAuthzUi] documents -- these
 * predicates only govern what the client RENDERS; the server independently re-checks every role on
 * every call.
 *
 * **Two SEPARATE constants, never collapsed** -- mirrors [SepaAuthzUi]'s own "never reuse a role
 * set across two structurally different gates" rule, and exists for the identical reason: a
 * `READ_ROLES`/`SETTINGS_ROLES` merge would be a silent BOARD-can-configure-secrets-adjacent-
 * settings regression the moment someone "simplifies" the two identical-looking sets together.
 *
 * MINOR fix (code review, Welle V1.2.8): a third constant, `TREASURY_ROLES` (TREASURER+ADMIN,
 * deliberately excluding BOARD), was removed here -- it had no production call site (only its own
 * now-deleted test referenced it) and its KDoc incorrectly attributed it to
 * `listPaymentTransactions`, which actually needs [READ_ROLES] (the server's own
 * `TREASURY_READ_ROLES` set INCLUDES BOARD -- see `PaymentGatewayService`). There is currently no
 * TREASURER-only, non-BOARD-readable payment-gateway action to mirror; add a real constant back if
 * one is ever introduced.
 */
object PaymentGatewayAuthzUi {
    /** Mirrors the server's own `TREASURY_READ_ROLES` -- `listPaymentTransactions`/checkout-session ownership fallback. */
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

    /** Mirrors every ADMIN-only method (`enablePaymentGateway`/`disablePaymentGateway`/`getPspConfigStatus`/settings). */
    val SETTINGS_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

    fun canReadPaymentTransactions(role: AccountRole?): Boolean = role in READ_ROLES

    fun canManageSettings(role: AccountRole?): Boolean = role in SETTINGS_ROLES
}
