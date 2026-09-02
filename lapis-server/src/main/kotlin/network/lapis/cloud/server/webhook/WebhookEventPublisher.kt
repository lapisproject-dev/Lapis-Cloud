package network.lapis.cloud.server.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.WebhookEventType
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- the single fan-out point every event-producing call site
 * (`GovernanceService`/`MemberService`/`RegistrationService`/`ContributionPaymentEvents`/
 * `SepaService`/`PspWebhookIngestion`) calls. Installed once at startup
 * ([WebhookEventPublisher.install], from `Application.kt`) -- without an `install()` call this
 * object is a documented no-op, never a `NullPointerException` (test suites that never call
 * `install` therefore never accidentally publish, and `resetForTests` restores exactly that
 * no-op state between test cases).
 *
 * **Transactional outbox -- OPPOSITE transaction contract to [WebhookEndpointStore]/
 * [WebhookDeliveryQueue]'s OTHER functions in this same package (S1 in the plan's Stolperfallen
 * list, called out explicitly because it is easy to get backwards)**: [publish] MUST be called
 * from INSIDE the caller's own already-open `transaction {}` -- it does not open one itself. This
 * is what makes the business fact (a Resolution recorded, a Contribution marked paid, ...) and the
 * `webhook_delivery` outbox row it produces commit or roll back TOGETHER, atomically: if the
 * surrounding transaction later throws (e.g. `ContributionPostingBridge`'s own unbalanced-postings
 * guard), the webhook row that would have announced the now-reverted fact never exists either.
 *
 * **[publish] never throws outward** (`runCatching` + WARN) -- a webhook-subsystem problem (a bad
 * row, a DB hiccup local to this write) must NEVER roll back a Resolution or a Beitragszahlung;
 * the business transaction the caller is inside of is always more important than this side channel.
 *
 * **Design-Team decision D8, verbatim**: a **Thin** event (`id`/`eventType`/`entityId`/
 * `occurredAt` only) is published ONLY for an entity `/api/v1` immediately, unconditionally
 * afterwards makes visible -- `committee.created`/`.updated`, `meeting.created`/`.held`,
 * `resolution.adopted`, `motion.scheduled` (which additionally requires the new status to be in
 * `network.lapis.cloud.server.routes.PUBLIC_API_MOTION_STATUSES`), `member.created` (requires the
 * member's new status to be `ACTIVE`, see `MemberReads.getActiveMember`). A **Fat** event (a
 * self-contained payload, see [WebhookPayloads] class KDoc) is the ONLY documented exception:
 * `contribution.paid`/`donation.received` -- there is no `/api/v1/payments` endpoint (explicitly
 * out of scope, see `docs/api/public-api-v1.adoc`), so the payload itself carries everything a
 * receiver needs (`amount`/`currency`/`transactionId`) rather than pointing at a resource the
 * receiver could never actually fetch.
 *
 * **Fan-out**: [publish] loads every currently-[WebhookEndpointStore.listActive] endpoint and
 * inserts ONE [WebhookDeliveryQueue] row per endpoint, each with its OWN freshly-random `event_id`
 * (so `Lapis-Webhook-Id`/idempotency keys never collide across two different receivers for what is,
 * conceptually, the same underlying fact) -- but the exact SAME serialized [payload] bytes (see
 * [WebhookPayloads.build] KDoc "built EXACTLY ONCE").
 */
internal object WebhookEventPublisher {
    private var config: WebhookConfig? = null

    /** Called once from `Application.kt` at startup. A `null`/never-called state means [publish] is a documented no-op -- see class KDoc. */
    fun install(config: WebhookConfig) {
        this.config = config
    }

    /** Test-only -- restores the pre-[install] no-op state between test cases. */
    internal fun resetForTests() {
        config = null
    }

    /**
     * See class KDoc for the full contract (must run inside the caller's OWN transaction, never
     * throws, D8 Thin/Fat rule). A no-op, logging nothing, when [WebhookConfig.enabled] is `false`
     * -- no orphaned rows for a disabled feature branch.
     */
    fun publish(
        eventType: WebhookEventType,
        entityId: Uuid,
        occurredAt: LocalDateTime,
        payment: WebhookPayloads.PaymentEventDetails? = null,
    ) {
        val cfg = config ?: return
        if (!cfg.enabled) return
        try {
            val endpoints = WebhookEndpointStore.listActive()
            if (endpoints.isEmpty()) return
            val now = WebhookDeliveryQueue.nowLocalDateTime()
            for (endpoint in endpoints) {
                val eventId = Uuid.random()
                val payload =
                    WebhookPayloads.build(
                        eventId = eventId,
                        eventType = eventType,
                        entityId = entityId,
                        occurredAt = occurredAt,
                        payment = payment,
                    )
                WebhookDeliveryQueue.insert(
                    endpointId = endpoint.id,
                    eventId = eventId,
                    eventType = eventType,
                    entityId = entityId,
                    occurredAt = occurredAt,
                    payload = payload,
                    now = now,
                )
            }
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookEventPublisher: publish failed for eventType=$eventType entityId=$entityId" }
        }
    }
}
