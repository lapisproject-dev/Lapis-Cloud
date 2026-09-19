package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.4.21 -- Atkinson-Ruling: "Verrechnen" nur für die angezeigte Vorschau ([canExecuteNetting]). */
class OpenItemNettingUiTest {
    private fun item(
        id: String,
        direction: OpenItemDirection,
    ) = OpenItemDto(
        id = id,
        direction = direction,
        counterpartyName = "Muster GmbH",
        counterpartyKey = "muster gmbh",
        itemDate = LocalDate(2026, 1, 1),
        dueDate = LocalDate(2026, 2, 1),
        amount = 100.0.toDecimal(),
        openAmount = 100.0.toDecimal(),
        contraAccountId = "acc-1",
        contraAccountNumber = "50000",
        contraAccountName = "Konto",
        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        status = OpenItemStatus.OPEN,
        daysOverdue = 0,
        asOf = LocalDate(2026, 1, 15),
        createdByMemberId = "m-1",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private fun candidate(
        payableId: String = "p-1",
        receivableId: String = "r-1",
    ) = NettingCandidateDto(
        counterpartyKey = "muster gmbh",
        counterpartyDisplayName = "Muster GmbH",
        matchedByNameOnly = true,
        payable = item(payableId, OpenItemDirection.PAYABLE),
        receivable = item(receivableId, OpenItemDirection.RECEIVABLE),
        maxNettableAmount = 100.0.toDecimal(),
    )

    private val base = nettingPreviewToken(candidate(), 50.0.toDecimal())

    @Test
    fun token_carriesTheTripel() {
        assertEquals("p-1", base.payableItemId)
        assertEquals("r-1", base.receivableItemId)
    }

    @Test
    fun canExecute_sameTripel_isTrue() {
        assertTrue(canExecuteNetting(base, nettingPreviewToken(candidate(), 50.0.toDecimal())))
    }

    @Test
    fun canExecute_changedAmount_isFalse() {
        assertFalse(canExecuteNetting(nettingPreviewToken(candidate(), 40.0.toDecimal()), base))
    }

    @Test
    fun canExecute_otherPairWithSameAmount_isFalse() {
        assertFalse(canExecuteNetting(nettingPreviewToken(candidate(payableId = "p-2"), 50.0.toDecimal()), base))
        assertFalse(canExecuteNetting(nettingPreviewToken(candidate(receivableId = "r-2"), 50.0.toDecimal()), base))
    }

    @Test
    fun canExecute_withoutAPreviewOrWithoutACurrentSelection_isFalse() {
        assertFalse(canExecuteNetting(base, null))
        assertFalse(canExecuteNetting(null, base))
        assertFalse(canExecuteNetting(null, null))
    }
}
