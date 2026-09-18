package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- outcome of
 * [network.lapis.cloud.shared.rpc.IMemberService.reissueMemberCard].
 *
 * **Carries no raw card code, deliberately.** The raw bearer code exists exactly once, inside the
 * transaction that mints it, and its only legitimate destination is the QR code of the PDF the
 * card-download route streams (`POST /api/members/{memberId}/card.pdf`). Returning it over RPC
 * would put a bearer credential into the browser's JS heap, into any RPC-level logging, and into
 * whatever a client later decides to persist -- for no gain, because the client cannot render a
 * card from it anyway.
 *
 * [previousCardRevoked] is what the UI needs in order to tell the truth afterwards: `true` means an
 * earlier card was invalidated by this call and any printed copy of it is now worthless.
 */
@Serializable
data class MemberCardReissueResultDto(
    val memberId: String,
    val memberNumber: String,
    val issuedAt: LocalDateTime,
    val previousCardRevoked: Boolean,
)
