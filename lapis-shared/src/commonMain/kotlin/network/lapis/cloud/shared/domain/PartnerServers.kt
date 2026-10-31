package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * Welle V1.9.244 "Partnerserver P1" -- the wire types between the client and `IPartnerServerService`. See docs/features/partner-servers.adoc.
 * Every text a person reads is an enum CODE here; the client translates it (the server never sends a free text). Names and hosts that come
 * from a FOREIGN server are data, never markup: the client sets them only through the `untrusted*` helpers.
 * New values go to the END of every enum.
 */

/** The outcome of the last fetch of a partner. Stored by name in `partner_server.last_status` (VARCHAR(5)). */
@Serializable
enum class PartnerFetchStatus { OK, ERROR, NEVER }

/** Why a fetch (or the first probe) of a partner failed. Stored by name in `partner_server.last_error`; never carries an address or a body. */
@Serializable
enum class PartnerFetchErrorCode {
    BLOCKED,
    NOT_PUBLIC_ADDRESS,
    TIMEOUT,
    REDIRECT,
    TOO_LARGE,
    WRONG_CONTENT_TYPE,
    HTTP_STATUS,
    INVALID_FEED,
    OWN_SERVER_ID,
    SERVER_ID_CHANGED,
    SERVER_ID_TAKEN,
    UNREACHABLE,
    TLS,
    DUPLICATE,
    SERVER_ID_CONFLICT,
}

/** Why an address typed by the admin was not accepted (checked before any network access). */
@Serializable
enum class PartnerAddressRejectionCode {
    NOT_HTTPS,
    MALFORMED,
    PORT_NOT_ALLOWED,
    USERINFO,
    IP_LITERAL,
    LOCAL_NAME,
    NO_DOT,
    OWN_HOST,
    TOO_LONG,
}

/** One partner row of the admin list. [host] and the names are foreign-influenced text. */
@Serializable
data class PartnerServerDto(
    val id: String,
    val host: String,
    val displayName: String?,
    val remoteName: String?,
    val active: Boolean,
    val blocked: Boolean,
    val status: PartnerFetchStatus,
    val lastFetchAt: LocalDateTime?,
    val nextFetchAt: LocalDateTime?,
    val consecutiveFailures: Int,
    val eventCount: Int,
    val lastError: PartnerFetchErrorCode?,
    /** V1.9.245: servers / events reached THROUGH this partner (the "Ueber diesen Partner" line). */
    val discoveredServerCount: Int = 0,
    val discoveredEventCount: Int = 0,
)

@Serializable
data class PartnerBlockDto(
    val id: String,
    val pattern: String,
)

@Serializable
data class PartnerServerOverviewDto(
    val partners: List<PartnerServerDto>,
    val blocklist: List<PartnerBlockDto>,
    val partnerListHidden: Boolean,
)

/** Result of "probe and save": either [saved] with the normalised [host] and the number of events found, or one of the two reasons. */
@Serializable
data class PartnerProbeResultDto(
    val saved: Boolean,
    val host: String?,
    val eventCount: Int,
    val error: PartnerFetchErrorCode?,
    val addressRejection: PartnerAddressRejectionCode?,
)

/** Result of "block": [invalid] = the pattern is not `host` or `*.host`; otherwise [block] is the (possibly already existing) entry. */
@Serializable
data class PartnerBlockResultDto(
    val block: PartnerBlockDto?,
    val invalid: Boolean,
)

/** What the partner admin actions record: the audit snapshot of [AuditEntityType.PARTNER_SERVER] / [AuditEntityType.PARTNER_BLOCKLIST]. */
@Serializable
enum class PartnerAuditKind {
    ADDED,
    ACTIVATED,
    DEACTIVATED,
    REMOVED,
    BLOCK_ADDED,
    BLOCK_REMOVED,
    LIST_HIDDEN,
    LIST_SHOWN,
    RECURSION_ENABLED,
    RECURSION_DISABLED,
    RECURSION_DEPTH_CHANGED,
}

/** After-snapshot: a host or pattern (the admin's own input, normalised) and the kind. Never a name from a foreign feed. */
@Serializable
data class PartnerAuditSnapshot(
    val kind: PartnerAuditKind,
    val subject: String?,
)

/** V1.9.245 "Partnerserver P2": the counters of the last recursion run. Index 0 of the by-hop lists = hop 2, 1 = hop 3, 2 = hop 4. Numbers only. */
@Serializable
data class PartnerRecursionSummaryDto(
    val reachedByHop: List<Int> = emptyList(),
    val eventsByHop: List<Int> = emptyList(),
    val skippedBlocked: Int = 0,
    val skippedHiddenList: Int = 0,
    val skippedVisited: Int = 0,
    val skippedCap: Int = 0,
    val serverIdConflicts: Int = 0,
    val errors: Int = 0,
    val durationMs: Long = 0,
    /** The time budget of the run was hit; the rest follows in the next run. */
    val aborted: Boolean = false,
)

@Serializable
data class PartnerRecursionSettingsDto(
    val enabled: Boolean,
    val depth: Int,
    val lastRunAt: LocalDateTime?,
    val summary: PartnerRecursionSummaryDto?,
)

/** One server reached through a direct partner. [host], [name] and [viaChain] are foreign-influenced text (client: `untrusted*` helpers only). */
@Serializable
data class DiscoveredPartnerServerDto(
    val host: String,
    val name: String?,
    val hops: Int,
    val viaChain: List<String>,
    val rootPartnerId: String,
    val rootName: String,
    val eventCount: Int,
    val lastStatus: PartnerFetchStatus,
    val lastError: PartnerFetchErrorCode?,
    val lastFetchAt: LocalDateTime?,
)
