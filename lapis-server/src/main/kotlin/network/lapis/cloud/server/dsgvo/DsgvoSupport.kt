package network.lapis.cloud.server.dsgvo

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock

/**
 * Delegates to [DbClock.nowLocalDateTime] -- pulled out once here because the DSGVO package has
 * several call sites (contributors, service, routes) that all need "now" for the exact same
 * reason (timestamping an export/erasure/audit event).
 *
 * Note: despite the name, this has always used [kotlinx.datetime.TimeZone.currentSystemDefault]
 * (via [DbClock]'s own default), not actual UTC -- a pre-existing naming inaccuracy, not
 * introduced or corrected by the 2026-07-28 timestamp-precision fix (see that fix's CHANGELOG
 * entry). Left as-is here deliberately: renaming risks widening that change's diff and touching
 * DSGVO-export field semantics, which deserves its own reviewed change, not a drive-by rename
 * inside a precision-only bugfix branch.
 */
internal fun nowUtc(): LocalDateTime = DbClock.nowLocalDateTime()
