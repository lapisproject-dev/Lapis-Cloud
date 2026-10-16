package network.lapis.cloud.server.memberfinder

/** The two kinds of consent event. Stored by name (`VARCHAR(6)`). */
enum class MemberFinderConsentEventType { GRANT, REVOKE }

/** Why a consent ended. `MEMBER` = the member's own opt-out, `STATUS_LOST` = no longer an ACTIVE member, `INSTANCE_OFF` = the switch went off. */
enum class MemberFinderRevokeReason { MEMBER, STATUS_LOST, INSTANCE_OFF }

/** The STORED status of a request. A sender never sees it; see [network.lapis.cloud.shared.domain.MemberFinderOutgoingState]. */
enum class MemberFinderRequestStatus { OPEN, ACCEPTED, DECLINED, WITHDRAWN, EXPIRED }
