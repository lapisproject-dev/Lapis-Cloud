package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.events.PublicGuestRegistration

/**
 * V1.9.260 -- pure unit test of [embedEventResponseFor], no DB/HTTP involved. Covers all five [PublicGuestRegistration.Outcome]
 * branches, including the ones that are not end-to-end reachable (a full waitlist needs 500 rows, `PaymentFailed` is unreachable with
 * `deferCheckout = true`).
 */
class EmbedEventOutcomeMappingTest :
    FunSpec({
        test("Accepted -> 200 {\"outcome\":\"ACCEPTED\"} and nothing else on the wire") {
            val (status, response) = embedEventResponseFor(PublicGuestRegistration.Outcome.Accepted)
            status shouldBe HttpStatusCode.OK
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.ACCEPTED)
            Json { explicitNulls = false }.encodeToString(EmbedEventRegistrationResponse.serializer(), response) shouldBe
                """{"outcome":"ACCEPTED"}"""
        }

        test("NotAvailable -> 404 NOT_AVAILABLE") {
            val (status, response) = embedEventResponseFor(PublicGuestRegistration.Outcome.NotAvailable)
            status shouldBe HttpStatusCode.NotFound
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.NOT_AVAILABLE)
        }

        test("WaitlistFull -> 409 WAITLIST_FULL") {
            val (status, response) = embedEventResponseFor(PublicGuestRegistration.Outcome.WaitlistFull)
            status shouldBe HttpStatusCode.Conflict
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLIST_FULL)
        }

        test("GatewayUnavailable -> 503 UNAVAILABLE") {
            val (status, response) = embedEventResponseFor(PublicGuestRegistration.Outcome.GatewayUnavailable)
            status shouldBe HttpStatusCode.ServiceUnavailable
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.UNAVAILABLE)
        }

        test("PaymentFailed -> 502 GATEWAY_ERROR") {
            val (status, response) = embedEventResponseFor(PublicGuestRegistration.Outcome.PaymentFailed)
            status shouldBe HttpStatusCode.BadGateway
            response shouldBe EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.GATEWAY_ERROR)
        }
    })
