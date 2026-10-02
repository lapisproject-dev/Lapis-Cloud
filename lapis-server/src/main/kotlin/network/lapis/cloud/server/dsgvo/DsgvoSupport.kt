package network.lapis.cloud.server.dsgvo

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.time.ServerClock

/**
 * Class-A "now" (UTC system timestamp) for the DSGVO package -- pulled out once here because the
 * package has several call sites (contributors, service, routes) that all need "now" for the exact
 * same reason (timestamping an export/erasure/audit event).
 *
 * Since V1.9.38 this really is UTC (it delegates to [ServerClock.now]); before, it used the process
 * default zone via `DbClock`, which was only UTC by accident of the container environment.
 */
internal fun nowUtc(): LocalDateTime = ServerClock.now()
