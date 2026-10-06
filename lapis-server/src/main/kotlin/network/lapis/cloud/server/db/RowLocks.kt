package network.lapis.cloud.server.db

import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query

/**
 * Locks the selected `member` row for a write that never changes one of its key columns. PostgreSQL: `FOR NO KEY UPDATE`,
 * **not** `FOR UPDATE`. Any other dialect (the H2 of the normal test task, which has no such option): a plain `FOR UPDATE`.
 *
 * Why this is load-bearing (found by the V1.9.57 Postgres lane, completed by V1.9.60): a plain `FOR UPDATE` on a member row
 * conflicts with the `FOR KEY SHARE` lock that the foreign key of every child row takes on the parent -- above all
 * `audit_log_entry.actor_member_id` on the ACTOR's member row. A transaction that holds `member(a) FOR UPDATE` and then waits
 * for another lock deadlocks against one that holds that other lock and is inserting an audit entry with actor `a`.
 * `FOR NO KEY UPDATE` still excludes every other writer and every other `FOR (NO KEY) UPDATE`, but not the foreign-key check.
 *
 * **Key columns.** PostgreSQL does not look at foreign keys to decide what a "key" is: any column of a unique, non-partial,
 * non-expression index counts. On `member` that is `id`, `email` and `member_number`. A later `UPDATE` of one of those columns in
 * the same transaction silently raises the lock to `FOR UPDATE` again -- so this helper is **never** right in front of a change
 * of `id`, `email` or `member_number` (those sites keep a plain `forUpdate()` with the marker comment
 * `row-lock: FOR UPDATE (key change: ...)`). `MemberRowLockTripwireTest` enforces all of this.
 *
 * See `docs/architecture/row-locks.adoc`.
 */
internal fun Query.forMemberUpdate(): Query =
    if (currentDialect is PostgreSQLDialect) forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate) else forUpdate()
