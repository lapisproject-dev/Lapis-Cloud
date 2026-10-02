package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- the poll lifecycle's pure helpers.
 *
 * **Two clocks (V1.9.38)**: `poll.closes_at` is a class-B wall-clock typed in by the creator in the
 * organization's zone, so every "now" in this file is the organization-zone wall-clock
 * (`ServerClock.nowIn(OrganizationTimeZone.current())`), never the UTC system stamp.
 *
 * **Lazy expiry**: a poll's deadline passing is a pure READ state. A stored `OPEN` poll whose
 * `closes_at` has passed is `CLOSED` for every reader, but the row is never rewritten (no background
 * job, no "system actor" in the audit log, read paths never write and never take the audit lock).
 * Therefore EVERY status filter, the open-poll cap and `canRespond` must use the effective status --
 * [effectiveStatus] for a loaded row, [effectiveOpenPredicate]/[effectiveClosedPredicate] in SQL.
 */
internal fun effectiveStatus(
    stored: PollStatus,
    closesAt: LocalDateTime?,
    wallNow: LocalDateTime,
): PollStatus = if (stored == PollStatus.OPEN && closesAt != null && wallNow >= closesAt) PollStatus.CLOSED else stored

/** The effective status of a loaded `poll` row as of [wallNow] (organization-zone wall-clock, see below). */
internal fun ResultRow.effectivePollStatus(wallNow: LocalDateTime): PollStatus =
    effectiveStatus(stored = this[PollTable.status], closesAt = this[PollTable.closesAt], wallNow = wallNow)

/**
 * The instant the poll closed, as an organization-zone wall-clock (class B): the manual close instant
 * (a class-A UTC system timestamp, converted here), or the deadline (already a wall-clock) if it merely
 * expired. Returning one class for both cases is what lets the client show the field without converting.
 */
internal fun ResultRow.effectiveClosedAt(
    wallNow: LocalDateTime,
    orgZone: TimeZone,
): LocalDateTime? {
    val manual = this[PollTable.closedAt]
    if (manual != null) return manual.toInstant(ServerClock.zone).toLocalDateTime(orgZone)
    return if (effectivePollStatus(wallNow) == PollStatus.CLOSED) this[PollTable.closesAt] else null
}

/** SQL for "effectively OPEN": stored OPEN and no deadline, or the deadline still ahead. */
internal fun effectiveOpenPredicate(wallNow: LocalDateTime): Op<Boolean> =
    (PollTable.status eq PollStatus.OPEN) and (PollTable.closesAt.isNull() or (PollTable.closesAt greater wallNow))

/** SQL for "effectively CLOSED": stored CLOSED, or stored OPEN with an elapsed deadline. */
internal fun effectiveClosedPredicate(wallNow: LocalDateTime): Op<Boolean> =
    (PollTable.status eq PollStatus.CLOSED) or
        ((PollTable.status eq PollStatus.OPEN) and (PollTable.closesAt lessEq wallNow))

/** SQL for the effective [status]. */
internal fun effectiveStatusPredicate(
    status: PollStatus,
    wallNow: LocalDateTime,
): Op<Boolean> =
    when (status) {
        PollStatus.OPEN -> effectiveOpenPredicate(wallNow)
        PollStatus.CLOSED -> effectiveClosedPredicate(wallNow)
        PollStatus.ABORTED -> PollTable.status eq PollStatus.ABORTED
    }

/**
 * `SELECT ... FOR UPDATE` on the poll row -- serialises cast/close/abort against each other, so no
 * response can land after `closed_at`. MUST run inside an open transaction. [NotFoundException]
 * deliberately carries no id.
 */
internal fun lockPollRow(id: Uuid): ResultRow =
    PollTable
        .selectAll()
        .where { PollTable.id eq id }
        .forUpdate()
        .singleOrNull()
        ?: throw NotFoundException("Poll not found")
