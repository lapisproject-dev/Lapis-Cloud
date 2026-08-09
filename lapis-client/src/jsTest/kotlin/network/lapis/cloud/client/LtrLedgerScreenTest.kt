package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers the pure, DOM-independent label/color tables in
 * `LtrLedgerScreen.kt` ([ltrLedgerEntryTypeLabel]/[ltrLedgerEntryTypeColor],
 * [ltrLedgerReferenceTypeLabel], [peerTransferCharacterizationLabel]), same scope posture as
 * [AccountingLabelsTest]/[AuctionScreenTest]. No rendering harness exists in this module (see
 * [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderLtrLedgerScreen` etc. are out of scope
 * here, same as every other screen's `*ScreenTest.kt`.
 */
class LtrLedgerScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun ltrLedgerEntryTypeLabel_isNonBlankForEveryValue() {
        LtrLedgerEntryType.entries.forEach { type ->
            assertTrue(ltrLedgerEntryTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun ltrLedgerEntryTypeColor_isARealBootstrapHueForEveryValue() {
        LtrLedgerEntryType.entries.forEach { type ->
            val color = ltrLedgerEntryTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun ltrLedgerReferenceTypeLabel_isNonBlankForEveryValue() {
        LtrLedgerReferenceType.entries.forEach { type ->
            assertTrue(ltrLedgerReferenceTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun peerTransferCharacterizationLabel_isNonBlankForEveryValue() {
        PeerTransferCharacterization.entries.forEach { characterization ->
            assertTrue(
                peerTransferCharacterizationLabel(characterization).isNotBlank(),
                "expected a non-blank label for $characterization",
            )
        }
    }
}
