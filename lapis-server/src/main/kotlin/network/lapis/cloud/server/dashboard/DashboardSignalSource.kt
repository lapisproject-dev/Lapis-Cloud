package network.lapis.cloud.server.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DASHBOARD_COUNT_CAP
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * One module's contribution to the dashboard. Rules every implementation keeps (see `docs/features/dashboard.adoc`, "How to add a signal"):
 * - a module gate first (instance switch, status via `SignalGates`, role), then the query; "not eligible" is `emptyList()`, never an
 * exception;
 * - the query reads only the caller's OWN rows (or, for the role-based head counts, a bare `COUNT`), is indexed, has an `ORDER BY` with a
 *   unique tiebreaker and a `LIMIT`;
 * - no title, name or amount leaves the source: only counts, dates and (for exactly one own object) its id.
 */
internal interface DashboardSignalSource {
    val moduleKey: String

    /** Runs INSIDE a transaction and a savepoint. */
    fun collect(ctx: SignalContext): List<DashboardSignalDto>
}

/** The instance switches the sources need; the first two come from `organization_settings`, the others from the process configuration. */
internal data class SignalModuleFlags(
    val auctionEnabled: Boolean,
    val conferenceEnabled: Boolean,
    val mcpEnabled: Boolean,
    val dunningEnabled: Boolean,
)

/**
 * Everything a source may use. [status] is read fresh from the database, not from the session. [nowSystem] is the class-A "now" (UTC),
 * [nowWall] the class-B "now" (organization wall-clock), [today] the class-D date; each is compared only with fields of its own class.
 */
internal data class SignalContext(
    val memberId: Uuid,
    val status: MemberStatus,
    val role: AccountRole,
    val nowSystem: LocalDateTime,
    val nowWall: LocalDateTime,
    val today: LocalDate,
    val zone: TimeZone,
    val flags: SignalModuleFlags,
) {
    /** [nowSystem] moved by [d] (class A: UTC). */
    fun systemPlus(d: Duration): LocalDateTime = (nowSystem.toInstant(ServerClock.zone) + d).toLocalDateTime(ServerClock.zone)

    /** [nowWall] moved by [d] (class B: organization zone). */
    fun wallPlus(d: Duration): LocalDateTime = (nowWall.toInstant(zone) + d).toLocalDateTime(zone)

    /** A class-A value (UTC stamp) as the organization wall-clock, the one representation a signal deadline has. */
    fun systemToWall(utc: LocalDateTime): LocalDateTime = ServerClock.systemToWall(utc = utc, orgZone = zone)
}

/** Builds a signal, or `null` for a count of zero. [objectId] is only kept for exactly one object (the cap is applied here, too). */
internal fun signal(
    module: String,
    reason: String,
    count: Int,
    priority: Int,
    params: Map<String, String> = emptyMap(),
    objectId: Uuid? = null,
    deadlineWall: LocalDateTime? = null,
): DashboardSignalDto? {
    if (count <= 0) return null
    return DashboardSignalDto(
        moduleKey = module,
        reasonKey = reason,
        count = count.coerceAtMost(DASHBOARD_COUNT_CAP),
        params = params,
        objectId = if (count == 1) objectId?.toString() else null,
        priority = priority,
        deadline = deadlineWall?.let(::signalIso),
    )
}

/** `yyyy-MM-ddTHH:mm:ss`, always the same shape so deadlines sort as strings. */
internal fun signalIso(value: LocalDateTime): String {
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${value.date}T${two(value.hour)}:${two(value.minute)}:${two(value.second)}"
}
