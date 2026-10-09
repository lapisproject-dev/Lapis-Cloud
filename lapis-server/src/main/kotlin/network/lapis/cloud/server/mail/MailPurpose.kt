package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.mail.budget.MailLane
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * How the durable outbox (and the budget) treats one kind of system mail.
 *
 * [priority] 0 is delivered before 1. [lane] decides which budget the send is checked against. [outboxTtl] is the longest a mail
 * may wait: a password-reset link that arrives an hour late is useless, and a stale one lying in the queue is a (small) risk.
 */
data class MailPurposePolicy(
    val priority: Int,
    val lane: MailLane,
    val outboxTtl: Duration?,
)

/**
 * Welle V1.9.81 -- the registry of every mail `purpose` string. Replaces freely scattered literals: a tripwire
 * (`MailPurposeRegistryTripwireTest`) fails when a `purpose = "..."` literal in main code is not listed here, and pins the exact set
 * of priority-0 purposes.
 *
 * Priority 0 = security-relevant, short-lived: password reset, e-mail change (confirm / warning / self-info / applied-info), friend
 * e-mail verification, the administrator-triggered password-reset notice, and the Keycloak provisioning mail. TTL 30 minutes.
 * Everything else is priority 1 without TTL. Lane SYSTEM for all of them -- except [EVENT_CANCELLED]: a cancellation can go to hundreds
 * of registrants at once, so it draws from the BULK lane and can never starve a password reset of the reserve (Q4).
 */
object MailPurpose {
    val PRIORITY_ZERO_TTL: Duration = 30.minutes

    private val priorityZero =
        setOf(
            "password-reset",
            "email-change-confirm",
            "email-change-warning",
            "email-change-self-info",
            "email-change-applied-info",
            "friend-email-verification",
            "admin-password-reset-notice",
            "keycloak-member-provisioned",
        )

    /** Priority 1, lane SYSTEM. */
    private val priorityOne =
        setOf(
            "webhook-endpoint-deactivated",
            "keycloak-email-synced",
            "keycloak-link-notice",
            "fints-reauth-required",
            "peer-request-target",
            "peer-approval-needed",
            "peer-executed-target",
            "peer-reset-mail-triggered",
            "peer-protected-data-changed",
            "peer-new-administrator",
            "encounter-entry-notice",
            "event-waitlist-promotion",
            "event-registration",
            "event-ticket-reissue",
            "event-ticket",
        )

    /** Priority 1, lane BULK. */
    private val bulkPurposes = setOf(EVENT_CANCELLED_PURPOSE)

    private val prefixes = listOf("article-review-")

    /** Every exact purpose string the registry knows (the tripwire compares main-code literals against this plus [KNOWN_PREFIXES]). */
    val KNOWN_PURPOSES: Set<String> = priorityZero + priorityOne + bulkPurposes
    val KNOWN_PREFIXES: List<String> = prefixes
    val PRIORITY_ZERO_PURPOSES: Set<String> = priorityZero

    fun isKnown(purpose: String): Boolean = purpose in KNOWN_PURPOSES || prefixes.any { purpose.startsWith(it) }

    fun policyFor(purpose: String): MailPurposePolicy =
        when {
            purpose in priorityZero -> MailPurposePolicy(priority = 0, lane = MailLane.SYSTEM, outboxTtl = PRIORITY_ZERO_TTL)
            purpose in bulkPurposes -> MailPurposePolicy(priority = 1, lane = MailLane.BULK, outboxTtl = null)
            purpose in priorityOne || prefixes.any { purpose.startsWith(it) } ->
                MailPurposePolicy(priority = 1, lane = MailLane.SYSTEM, outboxTtl = null)
            else -> {
                logger.warn { "Unregistered mail purpose '${purpose.take(MAX_LOGGED_PURPOSE)}' -- treated as priority 1, lane SYSTEM" }
                MailPurposePolicy(priority = 1, lane = MailLane.SYSTEM, outboxTtl = null)
            }
        }

    private const val MAX_LOGGED_PURPOSE = 64
}

/** The registry string for the event-cancellation mail (referenced by `EventService`). */
const val EVENT_CANCELLED_PURPOSE = "event-cancelled"
