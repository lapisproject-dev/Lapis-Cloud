package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Outcome of one physical-letter dispatch attempt (V0.4.2 Letterxpress postal-mail dispatch).
 * `QUEUED` is reserved for a future async/webhook-based delivery-status-callback follow-up (out
 * of scope this wave, see `network.lapis.cloud.server.rpc.PostalMailService` KDoc) -- this wave's
 * synchronous dispatch-and-record flow only ever writes `SENT` or `FAILED`.
 */
@Serializable
enum class PostalDeliveryStatus { QUEUED, SENT, FAILED }

/**
 * One row of [network.lapis.cloud.server.db.generated.PostalDeliveryLogTable] -- a record that a
 * specific document was handed to Letterxpress for physical delivery to [recipientMemberId] at a
 * point in time, with the outcome Letterxpress's synchronous API response reported.
 * [errorMessage] is always a sanitized, templated string when present -- never the raw
 * provider/HTTP exception message or response body (which could in principle embed something
 * sensitive) -- see `network.lapis.cloud.server.postal.LetterxpressPostalMailProvider` KDoc.
 */
@Serializable
data class PostalDeliveryLogDto(
    val id: String,
    val recipientMemberId: String,
    val recipientDisplayName: String,
    val documentReference: String,
    val dispatchedAt: LocalDateTime,
    val status: PostalDeliveryStatus,
    val providerReference: String?,
    val errorMessage: String?,
)

/**
 * Input for [network.lapis.cloud.shared.rpc.IPostalMailService.dispatchEinladungByPost] --
 * [recipientMemberIds] is capped at `MAX_POSTAL_INVITATION_RECIPIENTS` (see
 * `network.lapis.cloud.server.rpc.PostalMailService`), deliberately much stricter than the free
 * PDF-only Einladung route's cap, since postal dispatch costs real money per letter.
 */
@Serializable
data class PostalInvitationDispatchInput(
    val title: String,
    val eventDateTime: LocalDateTime,
    val location: String,
    val bodyText: String,
    val recipientMemberIds: List<String> = emptyList(),
    /** V1.9.101 -- [InvitationAudience.ALL_ACTIVE] resolves the General Assembly's recipients server-side. */
    val audience: InvitationAudience = InvitationAudience.SELECTED,
    /** V1.9.101 -- required (and only allowed) with [InvitationAudience.ALL_ACTIVE]. */
    val committeeId: String? = null,
)
