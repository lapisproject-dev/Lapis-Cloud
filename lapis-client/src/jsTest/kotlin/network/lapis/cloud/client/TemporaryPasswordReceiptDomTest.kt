package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.MailDeliveryState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The receipt of a temporary password joins a `gettext` count and a notification sentence into one line. A `tr()` sentence
 * in the MIDDLE of a string is never resolved by KVision, so the raw `###KvI18nS###` marker showed up in the dialog
 * ("0 Sitzung(en) beendet. ###KvI18nS###Das Mitglied wurde per E-Mail informiert."). Pins that no state carries it.
 *
 * In this test setup `tr()` prefixes the marker onto its return value (see `TestI18nSetup.kt`), so the pure line function is the
 * reliable place to assert it: the rendered DOM of a widget may or may not show the marker depending on KVision's patch cycle.
 */
class TemporaryPasswordReceiptDomTest {
    @Test
    fun noDeliveryState_carriesTheI18nMarkerInItsSentence() {
        MailDeliveryState.entries.forEach { state ->
            val line = temporaryPasswordNotificationLine(state)
            assertFalse(line.contains("###"), "marker in the sentence for $state: $line")
            assertTrue(line.isNotBlank(), "empty sentence for $state")
        }
    }

    @Test
    fun theRenderedReceipt_showsTheCountAndTheSentenceWithoutAMarker() {
        MailDeliveryState.entries.forEach { state ->
            var text = ""
            withMountedRoot("temp-password-receipt-${state.name}") { root, element ->
                val body = SimplePanel()
                root.add(body)
                renderTemporaryPasswordReceipt(
                    body = body,
                    generatedPassword = "abcd-efgh-ijkl-mnop",
                    revokedSessionCount = 0,
                    memberNotified = state,
                )
                text = element().textContent.orEmpty()
            }
            assertFalse(text.contains("###"), "marker leaked for $state: $text")
            assertTrue(text.contains("Sitzung(en) beendet"), "session line missing for $state: $text")
        }
    }
}
