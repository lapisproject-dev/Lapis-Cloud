package network.lapis.cloud.shared.rpc

import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.MemberDto

/**
 * V1.9.118 -- what happened to the optional notification mail of a membership decision
 * ([IRegistrationService.approveApplication] / [IRegistrationService.rejectApplication]).
 *
 * The decision itself has already been committed whatever the outcome. [QUEUED] only means "the mail is in the outbox", never
 * "delivered".
 */
@Serializable
enum class DecisionNotificationOutcome {
    /** The mail was handed to the outbox. */
    QUEUED,

    /** The caller switched the notification off. */
    NOT_REQUESTED,

    /** The server has no SMTP delivery configured. */
    MAIL_DISABLED,

    /** The applicant's stored address is blank or not a valid mailbox address. */
    NO_VALID_ADDRESS,

    /** The outbox refused the mail (budget, discarded) or the mailer threw. */
    FAILED,
}

/** V1.9.118 -- result of a membership decision: the updated [member] plus the [notification] outcome. */
@Serializable
data class MemberDecisionResultDto(
    val member: MemberDto,
    val notification: DecisionNotificationOutcome,
)
