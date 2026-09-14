package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- Review fix (MEDIUM, test coverage): pins
 * [finTsActiveStatusVariant]/[finTsActiveStatusIconClass] against the four (lastSuccess,
 * persistentError) combinations [BankAccountsScreen]'s `FinTsStatus.ACTIVE` branch can actually
 * observe. The load-bearing case is [lastSuccess_and_error_stillReportsLastSuccess]: an account
 * that succeeded once and is NOW failing must keep showing the "letzter Abruf" text (an ADMIN
 * needs to see WHEN it last worked, not just that it is currently broken) while STILL getting the
 * warning triangle, not the green check -- see [BankAccountLabels.kt] KDoc.
 */
class BankAccountLabelsTest {
    @Test
    fun noSuccess_noError_isNoErrorNoSuccessVariant() {
        assertEquals(
            FinTsActiveStatusVariant.NO_ERROR_NO_SUCCESS,
            finTsActiveStatusVariant(hasLastSuccess = false, hasPersistentError = false),
        )
    }

    @Test
    fun noSuccess_withError_isErrorNoSuccessVariant() {
        assertEquals(
            FinTsActiveStatusVariant.ERROR_NO_SUCCESS,
            finTsActiveStatusVariant(hasLastSuccess = false, hasPersistentError = true),
        )
    }

    @Test
    fun lastSuccess_noError_isLastSuccessVariant() {
        assertEquals(
            FinTsActiveStatusVariant.LAST_SUCCESS,
            finTsActiveStatusVariant(hasLastSuccess = true, hasPersistentError = false),
        )
    }

    @Test
    fun lastSuccess_and_error_stillReportsLastSuccess() {
        // Der Kernfall: ein Konto, das schon erfolgreich abgerufen hat und JETZT scheitert (z.B.
        // durch den neuen FETCH_WINDOW_GAP-Marker oder einen transienten Bankfehler), muss weiter
        // den "letzter Abruf"-Text zeigen -- LAST_SUCCESS gewinnt bewusst gegen ERROR_NO_SUCCESS.
        assertEquals(
            FinTsActiveStatusVariant.LAST_SUCCESS,
            finTsActiveStatusVariant(hasLastSuccess = true, hasPersistentError = true),
        )
    }

    @Test
    fun iconClass_noPersistentError_isSuccessCheck() {
        assertEquals("fas fa-check text-success", finTsActiveStatusIconClass(hasPersistentError = false))
    }

    @Test
    fun iconClass_persistentError_isWarningTriangle() {
        assertEquals("fas fa-triangle-exclamation text-warning", finTsActiveStatusIconClass(hasPersistentError = true))
    }

    @Test
    fun iconClass_matchesVariant_evenWhenLastSuccessAlsoTrue() {
        // Das Icon haengt NUR an hasPersistentError, nicht am gewaehlten Text-Variant -- ein Konto
        // mit letztem Erfolg UND aktuellem Fehler zeigt den LAST_SUCCESS-Text, aber trotzdem das
        // Warndreieck, nie den gruenen Haken.
        assertEquals(
            "fas fa-triangle-exclamation text-warning",
            finTsActiveStatusIconClass(hasPersistentError = true),
        )
        assertEquals(
            FinTsActiveStatusVariant.LAST_SUCCESS,
            finTsActiveStatusVariant(hasLastSuccess = true, hasPersistentError = true),
        )
    }
}
