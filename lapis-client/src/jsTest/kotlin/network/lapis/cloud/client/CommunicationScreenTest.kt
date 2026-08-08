package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MailingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mail-merge/Postal-Dispatch UI wave, design decision D1/D2 -- covers only the pure, DOM-independent
 * functions factored out of `CommunicationScreen.kt`: the `MailingMessageStatus` label/color tables
 * (including the currently-dead `QUEUED` branch, kept and documented per the `legalHoldIndicator`
 * precedent -- see that function's own KDoc) and the permanent stub-delivery honesty caption. Same
 * scope posture as `DsgvoRightsScreenTest.kt` (no DOM/rendering test harness exists in this module).
 */
class CommunicationScreenTest {
    // ---- mailingMessageStatusLabel / mailingMessageStatusColor --------------------------------

    @Test
    fun mailingMessageStatusLabel_coversAllFourStatuses() {
        assertEquals("Entwurf", mailingMessageStatusLabel(MailingMessageStatus.DRAFT))
        assertEquals("Gesendet", mailingMessageStatusLabel(MailingMessageStatus.SENT))
        assertEquals("In Warteschlange", mailingMessageStatusLabel(MailingMessageStatus.QUEUED))
        assertEquals("Fehlgeschlagen", mailingMessageStatusLabel(MailingMessageStatus.FAILED))
    }

    @Test
    fun mailingMessageStatusColor_draftIsWarning_sentIsSuccess_queuedIsSecondary_failedIsDanger() {
        assertEquals("warning", mailingMessageStatusColor(MailingMessageStatus.DRAFT))
        assertEquals("success", mailingMessageStatusColor(MailingMessageStatus.SENT))
        assertEquals("secondary", mailingMessageStatusColor(MailingMessageStatus.QUEUED))
        assertEquals("danger", mailingMessageStatusColor(MailingMessageStatus.FAILED))
    }

    // ---- MAILING_SEND_STUB_CAPTION -------------------------------------------------------------

    @Test
    fun mailingSendStubCaption_namesTheStubDeliveryPosture() {
        // The caption must be honest about what "sent" actually means in this codebase (a
        // delivery-log row, not a real external send) -- see MailingService.sendMailingMessage's own
        // "kein echter Versand-Anbieter angebunden" comment.
        assertTrue(MAILING_SEND_STUB_CAPTION.contains("Protokolleintrag"))
        assertTrue(MAILING_SEND_STUB_CAPTION.contains("keine echte"))
        assertTrue(MAILING_SEND_STUB_CAPTION.contains("Gesendet"))
    }
}
