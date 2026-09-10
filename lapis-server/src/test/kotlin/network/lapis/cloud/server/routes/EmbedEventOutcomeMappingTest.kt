package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.events.EventRegistrationResult
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.3 -- pure unit test of [embedEventResponseFor], no DB/HTTP involved. Covers all
 * eight [EventRegistrationResult] branches, including [EventRegistrationResult.WaitlistFull]
 * (not end-to-end testable without inserting 500 waitlist rows, see `EventPolicy.MAX_WAITLIST`)
 * and confirms Stripe's own error text never survives the projection.
 */
class EmbedEventOutcomeMappingTest :
    FunSpec({
        val json = Json

        fun body(result: EventRegistrationResult): String {
            val (_, response) = embedEventResponseFor(result)
            return json.encodeToString(EmbedEventRegistrationResponse.serializer(), response)
        }

        test("Confirmed -> 200 {\"outcome\":\"CONFIRMED\"}") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.Confirmed(registrationId = Uuid.random()))
            status shouldBe HttpStatusCode.OK
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.CONFIRMED)
        }

        test("AlreadyRegistered -> 200 {\"outcome\":\"CONFIRMED\"}, byte-identical to Confirmed") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.AlreadyRegistered)
            status shouldBe HttpStatusCode.OK
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.CONFIRMED)
            body(EventRegistrationResult.AlreadyRegistered) shouldBe body(EventRegistrationResult.Confirmed(registrationId = Uuid.random()))
        }

        test("Waitlisted -> 200 {\"outcome\":\"WAITLISTED\"}, no position leaks into the response object") {
            val (status, response) =
                embedEventResponseFor(EventRegistrationResult.Waitlisted(registrationId = Uuid.random(), position = 7))
            status shouldBe HttpStatusCode.OK
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLISTED)
        }

        test("PaymentRequired -> 200 {\"outcome\":\"PAYMENT_REQUIRED\",\"redirectUrl\":...}") {
            val (status, response) =
                embedEventResponseFor(
                    EventRegistrationResult.PaymentRequired(
                        registrationId = Uuid.random(),
                        redirectUrl = "https://checkout.stripe.com/c/pay/cs_test",
                    ),
                )
            status shouldBe HttpStatusCode.OK
            response shouldBe
                EmbedEventRegistrationResponse(
                    outcome = EmbedEventOutcome.PAYMENT_REQUIRED,
                    redirectUrl = "https://checkout.stripe.com/c/pay/cs_test",
                )
        }

        test("EventNotAvailable -> 404 {\"outcome\":\"NOT_AVAILABLE\"}") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.EventNotAvailable)
            status shouldBe HttpStatusCode.NotFound
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.NOT_AVAILABLE)
        }

        test("WaitlistFull -> 409 {\"outcome\":\"WAITLIST_FULL\"}") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.WaitlistFull)
            status shouldBe HttpStatusCode.Conflict
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLIST_FULL)
        }

        test("GatewayUnavailable -> 503 {\"outcome\":\"UNAVAILABLE\"}") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.GatewayUnavailable)
            status shouldBe HttpStatusCode.ServiceUnavailable
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.UNAVAILABLE)
        }

        test("StripeFailed -> 502 {\"outcome\":\"GATEWAY_ERROR\"}, Stripe's own message text never appears anywhere in the result") {
            val (status, response) = embedEventResponseFor(EventRegistrationResult.StripeFailed(message = "geheimer Stripe-Text"))
            status shouldBe HttpStatusCode.BadGateway
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.GATEWAY_ERROR)
            response.toString().contains("geheimer Stripe-Text") shouldBe false
        }
    })
