package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import network.lapis.cloud.shared.domain.WebhookEventType
import java.math.BigDecimal
import kotlin.uuid.Uuid

class WebhookPayloadsTest :
    FunSpec({
        val eventId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val entityId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val occurredAt = LocalDateTime(2026, 9, 2, 11, 14, 51)

        test("a Thin event's JSON has exactly the four fields id/eventType/entityId/occurredAt") {
            val raw =
                WebhookPayloads.build(
                    eventId = eventId,
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payment = null,
                )
            val obj = Json.parseToJsonElement(raw) as JsonObject
            obj.keys shouldBe setOf("id", "eventType", "entityId", "occurredAt")
            obj["eventType"]!!.toString() shouldBe "\"resolution.adopted\""
        }

        test("a Fat (payment) event's JSON has exactly seven fields, amount as a decimal STRING with scale 2") {
            val raw =
                WebhookPayloads.build(
                    eventId = eventId,
                    eventType = WebhookEventType.CONTRIBUTION_PAID,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payment =
                        WebhookPayloads.PaymentEventDetails(
                            amount = BigDecimal("42"),
                            currency = "EUR",
                            transactionId = "tx-1",
                        ),
                )
            val obj = Json.parseToJsonElement(raw) as JsonObject
            obj.keys shouldBe setOf("id", "eventType", "entityId", "occurredAt", "amount", "currency", "transactionId")
            obj["amount"]!!.toString() shouldBe "\"42.00\""
        }

        test("no PII field name ever appears in a payment event's serialized JSON") {
            val raw =
                WebhookPayloads.build(
                    eventId = eventId,
                    eventType = WebhookEventType.DONATION_RECEIVED,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payment =
                        WebhookPayloads.PaymentEventDetails(
                            amount = BigDecimal("5.00"),
                            currency = "EUR",
                            transactionId = "tx-2",
                        ),
                )
            listOf("memberId", "payerReference", "donorCategory", "email", "iban", "displayName").forEach { forbidden ->
                raw shouldNotContain forbidden
            }
        }

        test("the persisted payload string is deterministic for identical inputs (never rebuilt differently on retry)") {
            val first =
                WebhookPayloads.build(
                    eventId = eventId,
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payment = null,
                )
            val second =
                WebhookPayloads.build(
                    eventId = eventId,
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payment = null,
                )
            first shouldBe second
        }
    })
