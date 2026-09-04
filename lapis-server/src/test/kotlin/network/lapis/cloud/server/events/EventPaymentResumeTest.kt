package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Review MAJOR fix coverage -- a waitlist registrant promoted to `PENDING_PAYMENT` on a PAID event
 * (`EventWaitlist.promoteWhileCapacityFree`) used to have NO way to ever pay for the seat: the
 * promotion mail said "please complete payment" but no code path anywhere let them. This file proves
 * BOTH halves of the fix: (1) the promotion mints a `paymentResumeUrl` whose embedded token actually
 * authenticates the SAME registration via `EventStore.findByCancelTokenHash` (the same lookup
 * `registerEventPublicRoutes`'s `POST /veranstaltung/{slug}/zahlung` performs), and (2)
 * `EventRegistrationSubmission.resumeCheckout` -- the function that route calls -- genuinely produces
 * a fresh Stripe checkout session for that registration.
 */
class EventPaymentResumeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            // This codebase's payment gateway config is a single global row (see
            // PaymentGatewayCheckoutServiceTest's own `afterTest` for the same discipline) -- reset
            // it so a later, unrelated test suite never inherits "gateway enabled" from this one.
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
            }
        }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    val registrationIds =
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId inList createdEventIds }
                            .map { it[EventRegistrationTable.id] }
                    if (registrationIds.isNotEmpty()) {
                        PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList registrationIds }
                    }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPaymentResumeTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun enableGateway() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember("event-payment-resume-ack-${Uuid.random()}@example.org")
                    it[acknowledgedAt] = LocalDateTime(2030, 1, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        fun testPspConfigState(): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_event_payment_resume"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_event_payment_resume"
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        /** Stripe-response-shaped MockEngine client, same house style `PaymentGatewayCheckoutServiceTest.fakeSuccessfulCheckoutClient` establishes. */
        fun fakeSuccessfulCheckoutClient(pspConfigState: PspConfigState.Configured): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"cs_test_resume_fake","url":"https://checkout.stripe.com/c/pay/cs_test_resume_fake"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        /**
         * Same shape as [fakeSuccessfulCheckoutClient] but with a caller-chosen Stripe session id --
         * [fakeSuccessfulCheckoutClient]'s own id is a fixed literal another test in this same spec
         * already persists a `payment_checkout_session` row for (cleanup is `afterSpec`-only, not
         * `afterTest`), so any SECOND real Stripe call reusing that same id inside one spec run would
         * hit `uq_payment_checkout_session_provider_session`. Used by the session-reuse test below,
         * which deliberately wants a UNIQUE fake session id so a reuse-logic bug (an unwanted second
         * real Stripe call) fails on an assertion instead of masquerading as this unrelated constraint.
         */
        fun fakeSuccessfulCheckoutClient(
            pspConfigState: PspConfigState.Configured,
            sessionId: String,
        ): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"$sessionId","url":"https://checkout.stripe.com/c/pay/$sessionId"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        /** Same shape as [fakeSuccessfulCheckoutClient] but Stripe rejects the call (a plain 402, no special meaning). */
        fun fakeFailingCheckoutClient(pspConfigState: PspConfigState.Configured): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"error":{"message":"card_declined (test fixture)"}}""",
                                HttpStatusCode.PaymentRequired,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createPaidCapacityOneEvent(
            createdBy: Uuid,
            startsAt: LocalDateTime = farFutureStartsAt,
            endsAt: LocalDateTime = farFutureEndsAt,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "payment-resume-test-$id"
                    it[title] = "Payment-Resume-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = 1
                    it[feeAmount] = BigDecimal("25.00")
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertRegistration(
            eventId: Uuid,
            memberId: Uuid,
            status: EventRegistrationStatus,
            feeAmount: BigDecimal,
            waitlistPosition: Int? = null,
            // chk_event_registration_hold (V18__events.sql) requires a non-null hold_expires_at
            // whenever status = PENDING_PAYMENT -- default to a far-future value for exactly that
            // status so callers creating a PENDING_PAYMENT fixture don't have to think about it,
            // while every other status keeps the column null as before.
            holdExpiresAt: LocalDateTime? =
                if (status == EventRegistrationStatus.PENDING_PAYMENT) LocalDateTime(2035, 1, 1, 0, 0) else null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventRegistrationTable.insert {
                    it[EventRegistrationTable.id] = id
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$memberId"
                    it[EventRegistrationTable.status] = status
                    it[EventRegistrationTable.feeAmount] = feeAmount
                    it[EventRegistrationTable.holdExpiresAt] = holdExpiresAt
                    it[EventRegistrationTable.waitlistPosition] = waitlistPosition
                    it[cancelTokenSha256] = null
                    it[registeredAt] = now
                    it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) now else null
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
            return id
        }

        fun registrationRow(id: Uuid) =
            transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq id }.single() }

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        test(
            "promoting a WAITLISTED registrant on a PAID event mints a paymentResumeUrl whose token authenticates via findByCancelTokenHash",
        ) {
            val organizer = createMember("payment-resume-organizer-${Uuid.random()}@example.org")
            val payerEmail = "payment-resume-payer-${Uuid.random()}@example.org"
            val payer = createMember(payerEmail)
            val waitlistedEmail = "payment-resume-waitlisted-${Uuid.random()}@example.org"
            val waitlisted = createMember(waitlistedEmail)
            val eventId = createPaidCapacityOneEvent(organizer)
            val payerRegistrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal("25.00"),
                )
            val waitlistedRegistrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = waitlisted,
                    status = EventRegistrationStatus.WAITLISTED,
                    feeAmount = BigDecimal("25.00"),
                    waitlistPosition = 1,
                )

            val now = DbClock.nowLocalDateTime()
            // Cancel the payer directly and re-sweep, same shape EventService.cancelOwnRegistration's
            // own withEventLock block establishes -- proves the promotion side of the fix without
            // needing the full RPC/HTTP stack.
            val (_, promotions) =
                EventCapacityGuard.withEventLock(eventId = eventId, now = now) { _ ->
                    EventStore.cancelRegistration(id = payerRegistrationId, now = now)
                }

            promotions.size shouldBe 1
            val promotion = promotions.single()
            promotion.registrationId shouldBe waitlistedRegistrationId
            promotion.payNowRequired shouldBe true
            val url = promotion.paymentResumeUrl
            checkNotNull(url) { "expected a non-null paymentResumeUrl for a paid-event promotion" }
            url shouldContain "${FederationConfig.publicBaseUrl.trimEnd('/')}/veranstaltung/"
            url shouldContain "/zahlung?token="

            // Extract the token exactly as the route would (query-string tail) and prove it
            // authenticates THIS SAME registration via the same lookup the public route uses.
            val token = url.substringAfter("token=")
            val tokenHash = sha256Hex(token.toByteArray(Charsets.US_ASCII))
            val found = transaction { EventStore.findByCancelTokenHash(eventId = eventId, cancelTokenSha256 = tokenHash) }
            checkNotNull(found) { "the token embedded in paymentResumeUrl must resolve back to a registration" }
            found[EventRegistrationTable.id] shouldBe waitlistedRegistrationId
            found[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT

            // Review MINOR fix coverage: the SAME rotated token must also back a storno link -- the
            // promotion mail's ONLY chance for a guest (no member RPC cancel path) to actively
            // withdraw, since the token rotation above just invalidated their original waitlist mail's
            // storno link.
            val cancelUrl = promotion.cancelUrl
            checkNotNull(cancelUrl) { "expected a non-null cancelUrl alongside a payNowRequired promotion" }
            cancelUrl shouldContain "/storno?token=$token"
        }

        test("resumeCheckout on a genuinely PENDING_PAYMENT registration produces a Stripe redirect and persists a checkout session") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-rc-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-rc-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val result = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            (result is EventRegistrationResult.PaymentRequired) shouldBe true
            val redirectUrl = (result as EventRegistrationResult.PaymentRequired).redirectUrl
            redirectUrl shouldBe "https://checkout.stripe.com/c/pay/cs_test_resume_fake"

            val sessionRow =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .single()
                }
            sessionRow[PaymentCheckoutSessionTable.intent] shouldBe PaymentIntent.EVENT_FEE
            sessionRow[PaymentCheckoutSessionTable.provider] shouldBe PaymentProvider.STRIPE

            // Still PENDING_PAYMENT -- resumeCheckout only creates a NEW checkout session, it never
            // changes the registration's own status (that happens on the eventual webhook).
            registrationRow(registrationId)[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
        }

        test("resumeCheckout on an already-CONFIRMED registration returns EventNotAvailable, never contacts Stripe") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-confirmed-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-confirmed-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal("25.00"),
                )

            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val result = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            result shouldBe EventRegistrationResult.EventNotAvailable

            val sessionCount =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .count()
                }
            sessionCount shouldBe 0
        }

        test("resumeCheckout with the gateway disabled returns GatewayUnavailable without touching the registration") {
            val organizer = createMember("payment-resume-gw-off-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-gw-off-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val result = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            result shouldBe EventRegistrationResult.GatewayUnavailable
            registrationRow(registrationId)[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
        }

        // Runde-3 review fixes below -- see EventCapacityGuard/EventRegistrationSubmission/EventWaitlist KDocs.

        test(
            "resumeCheckout reuses an existing non-expired CREATED checkout session instead of minting a second one",
        ) {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-reuse-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-reuse-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState, sessionId = "cs_test_resume_reuse_fake"),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val first = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            val second = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            (first is EventRegistrationResult.PaymentRequired) shouldBe true
            (second is EventRegistrationResult.PaymentRequired) shouldBe true
            (first as EventRegistrationResult.PaymentRequired).redirectUrl shouldBe
                (second as EventRegistrationResult.PaymentRequired).redirectUrl

            // Exactly ONE session must ever have been persisted -- two clicks on the same
            // payment-resume link must never mint two competing Stripe sessions for the same seat.
            val sessionCount =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .count()
                }
            sessionCount shouldBe 1L
        }

        test(
            "resumeCheckout reuses the session across a gap beyond the old flat 30-minute window, for a waitlist-promotion's real (48h) hold (Review MINOR fix: session-dedup window)",
        ) {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-dedup-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-dedup-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            // Well before farFutureStartsAt (2030-01-01T18:00) -- both this call and the
            // two-hours-later second call below must land before the event starts, or
            // EventPolicy.isRegistrationOpen would (correctly) refuse both regardless of this test's
            // actual subject (the session-reuse window).
            val promotionMoment = LocalDateTime(2029, 6, 1, 12, 0)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                    // A waitlist promotion's real hold (EventPolicy.WAITLIST_OFFER_WINDOW, 48h) --
                    // NOT the flat 30-minute EventPolicy.STANDARD_HOLD a fresh registration gets.
                    // Before the fix, startStripeCheckout always minted the local checkout session's
                    // expires_at as `now + STANDARD_HOLD` regardless of this, so a second click on
                    // the same payment-resume link more than 30 minutes later found no reusable
                    // session and minted an avoidable, real second Stripe session.
                    holdExpiresAt = promotionMoment.plusDuration(EventPolicy.WAITLIST_OFFER_WINDOW),
                )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState, sessionId = "cs_test_dedup_window_fake"),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val first =
                runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId, now = promotionMoment) }
            // Two hours later -- well past the old flat 30-minute window, but nowhere near the
            // registration's real 48h hold or Stripe's own ~24h session lifetime.
            val twoHoursLater = promotionMoment.plusDuration(2.hours)
            val second =
                runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId, now = twoHoursLater) }

            (first is EventRegistrationResult.PaymentRequired) shouldBe true
            (second is EventRegistrationResult.PaymentRequired) shouldBe true
            (first as EventRegistrationResult.PaymentRequired).redirectUrl shouldBe
                (second as EventRegistrationResult.PaymentRequired).redirectUrl

            // Exactly ONE session must ever have been persisted -- the whole point of this fix.
            val sessionCount =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .count()
                }
            sessionCount shouldBe 1L
        }

        test(
            "resumeCheckout on a Stripe rejection leaves the registration PENDING_PAYMENT and does NOT free the seat to the waitlist",
        ) {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-fail-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-fail-payer-${Uuid.random()}@example.org")
            val waitlisted = createMember("payment-resume-fail-waitlisted-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                )
            // A second, WAITLISTED registrant behind the paying one -- proves a failed resume does
            // NOT free the seat and hand it onward (Review MAJOR fix).
            insertRegistration(
                eventId = eventId,
                memberId = waitlisted,
                status = EventRegistrationStatus.WAITLISTED,
                feeAmount = BigDecimal("25.00"),
                waitlistPosition = 1,
            )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeFailingCheckoutClient(pspConfigState),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val result = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            (result is EventRegistrationResult.StripeFailed) shouldBe true

            registrationRow(registrationId)[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
            val waitlistedCount =
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where {
                            (EventRegistrationTable.eventId eq eventId) and
                                (EventRegistrationTable.status eq EventRegistrationStatus.WAITLISTED)
                        }.count()
                }
            waitlistedCount shouldBe 1L
        }

        test("resumeCheckout on an event that has already started returns EventNotAvailable and never contacts Stripe") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val organizer = createMember("payment-resume-started-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-started-payer-${Uuid.random()}@example.org")
            val pastStartsAt = LocalDateTime(2020, 1, 1, 18, 0)
            val pastEndsAt = LocalDateTime(2020, 1, 1, 22, 0)
            val eventId = createPaidCapacityOneEvent(organizer, startsAt = pastStartsAt, endsAt = pastEndsAt)
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                )
            val submission =
                EventRegistrationSubmission(
                    pspConfigState = pspConfigState,
                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState),
                    baseUrl = "https://example.org",
                    mailDispatcher = noOpMailDispatcher(),
                )

            val result = runBlocking { submission.resumeCheckout(eventId = eventId, registrationId = registrationId) }
            result shouldBe EventRegistrationResult.EventNotAvailable

            val sessionCount =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .count()
                }
            sessionCount shouldBe 0
        }

        test(
            "expireHoldAndSweep leaves a PENDING_PAYMENT registration alone when its OWN hold_expires_at has not elapsed yet",
        ) {
            val organizer = createMember("payment-resume-guard-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-guard-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val now = DbClock.nowLocalDateTime()
            // Simulates a waitlist-promotion hold (WAITLIST_OFFER_WINDOW, 48h) that outlives
            // Stripe's own ~24h default session lifetime -- PspWebhookIngestion.ingestCheckoutExpired
            // fires this exact call the moment STRIPE's session expires, which must NOT be treated
            // as proof that THIS REGISTRATION's own, longer hold has also run out.
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                    holdExpiresAt = LocalDateTime(2035, 1, 1, 0, 0),
                )

            val promotions = EventCapacityGuard.expireHoldAndSweep(eventId = eventId, registrationId = registrationId, now = now)

            promotions shouldBe emptyList()
            registrationRow(registrationId)[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
        }

        test("expireHoldAndSweep DOES expire a PENDING_PAYMENT registration once its own hold_expires_at has actually elapsed") {
            val organizer = createMember("payment-resume-guard-expired-organizer-${Uuid.random()}@example.org")
            val payer = createMember("payment-resume-guard-expired-payer-${Uuid.random()}@example.org")
            val eventId = createPaidCapacityOneEvent(organizer)
            val now = DbClock.nowLocalDateTime()
            val registrationId =
                insertRegistration(
                    eventId = eventId,
                    memberId = payer,
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("25.00"),
                    holdExpiresAt = LocalDateTime(2020, 1, 1, 0, 0),
                )

            EventCapacityGuard.expireHoldAndSweep(eventId = eventId, registrationId = registrationId, now = now)

            registrationRow(registrationId)[EventRegistrationTable.status] shouldBe EventRegistrationStatus.EXPIRED
        }
    })
