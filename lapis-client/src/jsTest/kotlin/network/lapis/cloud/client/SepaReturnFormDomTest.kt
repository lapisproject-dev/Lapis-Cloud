package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemDto
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.rpc.ISepaService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.4.30 review fix: the "record a return" form writes the item select programmatically (`LapisField.setValue`) when a batch is
 * chosen. The documented follow-up (`validate(force = false)`) was missing, so an error shown for an empty item select stayed on a
 * field that had just been given a valid value.
 */
class SepaReturnFormDomTest {
    private fun batch(id: String) =
        SepaDebitBatchDto(
            id = id,
            messageId = "m-$id",
            paymentInfoId = "p-$id",
            requestedCollectionDate = LocalDate(2026, 1, 15),
            sequenceType = SepaSequenceType.RCUR,
            status = SepaDebitBatchStatus.SUBMITTED,
            itemCount = 1,
            totalAmount = 10.0.toDecimal(),
            createdByDisplayName = "Kassenwart",
            createdAt = if (id == "b-returned") LocalDateTime(2026, 1, 2, 10, 0) else LocalDateTime(2026, 1, 1, 10, 0),
            notifiedAt = null,
            requiredNoticeDays = null,
            fileGenerationAllowedFrom = null,
            generatedAt = null,
            generatedDocumentId = null,
            prenotificationDocumentId = null,
            submittedAt = null,
            submittedNote = null,
            settledAt = null,
            settlementEligibleFrom = null,
            cancelledAt = null,
            cancellationReason = null,
        )

    private fun detail(
        id: String,
        status: SepaDebitItemStatus,
    ) = SepaDebitBatchDetailDto(
        batch = batch(id),
        items =
            listOf(
                SepaDebitItemDto(
                    id = "i-$id",
                    batchId = id,
                    contributionId = "c-$id",
                    memberDisplayName = "Anna Muster",
                    mandateId = "md",
                    mandateReference = "REF",
                    debtorIbanLast4 = "1234",
                    endToEndId = "e2e",
                    amount = 10.0.toDecimal(),
                    remittanceInformation = "Beitrag",
                    status = status,
                    settleableAt = null,
                    journalEntryId = null,
                    returnReason = null,
                ),
            ),
    )

    @Test
    fun returnForm_choosingABatchWithReturnableItems_clearsTheStandingItemError(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<ISepaService>().listBatches(status = SepaDebitBatchStatus.SUBMITTED, limit = 100) }
            val get = routeOf { rpcService<ISepaService>().getBatch("x") }
            val batches =
                jsonOf(
                    kotlinx.serialization.builtins.ListSerializer(SepaDebitBatchDto.serializer()),
                    listOf(batch("b-returned"), batch("b-open")),
                )
            withFetchStub(respond = { r ->
                when {
                    !r.isRpc -> StubResponse()
                    r.rpcRoute == list ->
                        if (r.rpcParam(0) == "SUBMITTED") r.answerWith(batches) else r.answerWith("[]")
                    r.rpcRoute == get -> {
                        val id = r.rpcParam(0) as String
                        val status = if (id == "b-returned") SepaDebitItemStatus.RETURNED else SepaDebitItemStatus.PENDING
                        r.answerWith(jsonOf(SepaDebitBatchDetailDto.serializer(), detail(id, status)))
                    }
                    else -> r.answerWith("null")
                }
            }) { _ ->
                mountedForm("p3-return-form") { root, element ->
                    renderRecordReturnForm(root) {}
                    // The newest batch is preselected and has no returnable item -> the item select stays empty.
                    awaitUntil("item options loaded", timeoutMs = 1500) {
                        element().controlOf("Lauf").let {
                            (it as org.w3c.dom.HTMLSelectElement).value ==
                                "b-returned"
                        }
                    }
                    delay(120)
                    element().buttonNamed("Rücklastschrift erfassen").click()
                    awaitUntil("item error shown", timeoutMs = 1500) { element().shownErrors().contains("Bitte eine Position auswählen.") }

                    element().chooseIn("Lauf", "b-open")
                    awaitUntil("error cleared", timeoutMs = 1500) { !element().shownErrors().contains("Bitte eine Position auswählen.") }
                    assertEquals("i-b-open", (element().controlOf("Position") as org.w3c.dom.HTMLSelectElement).value)
                    assertTrue(
                        element().controlOf("Position").getAttribute("aria-invalid") != "true",
                        "the item select no longer claims to be invalid",
                    )
                }
            }
        }
}
