package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- covers [PaymentGatewayAuthzUi], same
 * "the role sets are distinct objects, never collapsed" drift guard [SepaAuthzUiTest] establishes
 * for its own analogous multi-tier structure.
 *
 * MINOR fix (code review, Welle V1.2.8): the former `TREASURY_ROLES` constant (and its two tests
 * here) was removed together with the constant itself -- see [PaymentGatewayAuthzUi]'s own KDoc for
 * why (no production call site, misattributed KDoc).
 */
class PaymentGatewayAuthzUiTest {
    @Test
    fun theRoleSetsAreDistinctObjects() {
        assertTrue(PaymentGatewayAuthzUi.READ_ROLES !== PaymentGatewayAuthzUi.SETTINGS_ROLES)
    }

    @Test
    fun readRoles_additionallyAdmitsBoard() {
        assertTrue(AccountRole.TREASURER in PaymentGatewayAuthzUi.READ_ROLES)
        assertTrue(AccountRole.BOARD in PaymentGatewayAuthzUi.READ_ROLES)
        assertTrue(AccountRole.ADMIN in PaymentGatewayAuthzUi.READ_ROLES)
        assertFalse(AccountRole.MEMBER in PaymentGatewayAuthzUi.READ_ROLES)
    }

    @Test
    fun settingsRoles_isAdminOnly() {
        assertTrue(AccountRole.ADMIN in PaymentGatewayAuthzUi.SETTINGS_ROLES)
        assertFalse(AccountRole.TREASURER in PaymentGatewayAuthzUi.SETTINGS_ROLES)
        assertFalse(AccountRole.BOARD in PaymentGatewayAuthzUi.SETTINGS_ROLES)
        assertFalse(AccountRole.MEMBER in PaymentGatewayAuthzUi.SETTINGS_ROLES)
    }

    @Test
    fun canReadPaymentTransactions_boardIsAllowed_memberAndNullAreDenied() {
        assertTrue(PaymentGatewayAuthzUi.canReadPaymentTransactions(AccountRole.BOARD))
        assertTrue(PaymentGatewayAuthzUi.canReadPaymentTransactions(AccountRole.TREASURER))
        assertTrue(PaymentGatewayAuthzUi.canReadPaymentTransactions(AccountRole.ADMIN))
        assertFalse(PaymentGatewayAuthzUi.canReadPaymentTransactions(AccountRole.MEMBER))
        assertFalse(PaymentGatewayAuthzUi.canReadPaymentTransactions(null))
    }

    @Test
    fun canManageSettings_onlyAdminIsAllowed() {
        assertTrue(PaymentGatewayAuthzUi.canManageSettings(AccountRole.ADMIN))
        assertFalse(PaymentGatewayAuthzUi.canManageSettings(AccountRole.TREASURER))
        assertFalse(PaymentGatewayAuthzUi.canManageSettings(AccountRole.BOARD))
        assertFalse(PaymentGatewayAuthzUi.canManageSettings(AccountRole.MEMBER))
        assertFalse(PaymentGatewayAuthzUi.canManageSettings(null))
    }
}
