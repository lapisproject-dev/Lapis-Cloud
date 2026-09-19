package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.21, Audit-Fund (zweiter Durchgang): der Doppelklick-Riegel der beiden geldbewegenden
 * Inline-Bestätigungen ([InlineConfirmGate]). Bewusst DOM-frei -- genau deshalb wurde der Zustand aus
 * `OpenItemDialogs.kt` herausgezogen: die Dialoge selbst brauchen KVision-Widgets und ein Modal und
 * sind unter Karma nicht ohne Weiteres ansteuerbar, der Zustandsautomat dahinter schon.
 *
 * Der eigentliche Fehlerfall ist [outerButtonCannotOpenASecondConfirmationWhileTheRequestIsInFlight]:
 * genau dort entstand vorher über `confirmBox.removeAll()` ein frischer, entsperrter Endgültig-Knopf
 * und damit ein zweiter echter Teil-Ausgleich.
 */
class InlineConfirmGateTest {
    @Test
    fun freshGateBlocksNothing() {
        val gate = InlineConfirmGate()
        assertFalse(gate.blocked)
        assertFalse(gate.confirming)
        assertFalse(gate.inFlight)
    }

    @Test
    fun openingAConfirmationBlocksTheOuterButton() {
        val gate = InlineConfirmGate()
        assertTrue(gate.openConfirmation())
        assertTrue(gate.confirming)
        assertTrue(gate.blocked)
    }

    @Test
    fun aSecondOuterClickCannotOpenASecondConfirmation() {
        val gate = InlineConfirmGate()
        assertTrue(gate.openConfirmation())
        assertFalse(gate.openConfirmation())
    }

    @Test
    fun goingBackReleasesTheOuterButton() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        assertTrue(gate.cancelConfirmation())
        assertFalse(gate.confirming)
        assertFalse(gate.blocked)
        // Und danach ist eine neue Bestätigung wieder erlaubt.
        assertTrue(gate.openConfirmation())
    }

    @Test
    fun theFinalButtonSendsOnlyOnceWhileTheRequestIsInFlight() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        assertTrue(gate.beginRequest())
        assertFalse(gate.beginRequest())
        assertTrue(gate.inFlight)
    }

    @Test
    fun theFinalButtonSendsNothingWithoutAnOpenConfirmation() {
        val gate = InlineConfirmGate()
        assertFalse(gate.beginRequest())
        assertFalse(gate.inFlight)
    }

    @Test
    fun outerButtonCannotOpenASecondConfirmationWhileTheRequestIsInFlight() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        gate.beginRequest()
        assertFalse(gate.openConfirmation())
        assertTrue(gate.blocked)
    }

    @Test
    fun theConfirmationIsNotPulledAwayUnderARunningRequest() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        gate.beginRequest()
        assertFalse(gate.cancelConfirmation())
        assertTrue(gate.confirming)
    }

    @Test
    fun aSuccessfulRequestClosesEverything() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        gate.beginRequest()
        gate.endRequest(succeeded = true)
        assertFalse(gate.inFlight)
        assertFalse(gate.confirming)
        assertFalse(gate.blocked)
    }

    @Test
    fun aFailedRequestKeepsTheConfirmationOpenForARetryButNotInFlight() {
        val gate = InlineConfirmGate()
        gate.openConfirmation()
        gate.beginRequest()
        gate.endRequest(succeeded = false)
        assertFalse(gate.inFlight)
        assertTrue(gate.confirming)
        assertTrue(gate.blocked)
        // Der Endgültig-Knopf darf es erneut versuchen (die erste Buchung fand nicht statt) ...
        assertTrue(gate.beginRequest())
        gate.endRequest(succeeded = false)
        // ... und "Zurück" ist erreichbar, also ist der Zustand nicht verklemmt.
        assertTrue(gate.cancelConfirmation())
        assertFalse(gate.blocked)
    }
}
