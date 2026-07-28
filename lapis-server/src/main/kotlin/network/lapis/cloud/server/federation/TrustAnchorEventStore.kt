package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TrustAnchorEventTable
import network.lapis.cloud.shared.domain.TrustAnchorEventDto
import network.lapis.cloud.shared.domain.TrustAnchorEventType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

private const val MAX_LISTED_EVENTS = 200

/**
 * Append-only forensic log for every Trust-Anchor governance action (key provisioning/rotation/
 * revocation, pool-member add/remove, trusted-anchor add/remove) -- see `26-trust-anchor.kuml.kts`
 * file header "trust_anchor_event is deliberately NOT audit_log_entry" paragraph. Mirrors
 * [FederationRelationshipStore.recordEvent]/[listEvents]'s own shape (organization-level actor, no
 * member-actor column), simplified further: no parent-entity FK at all, since there is no single
 * "relationship" row every event here belongs to -- just a flat, timestamped log.
 *
 * **Must always be called from inside the caller's already-open `transaction {}`** -- same contract
 * [AuditLogRecorder.record]/[FederationRelationshipStore] establish, so an event row is written
 * atomically with the fachlich mutation it accompanies.
 */
object TrustAnchorEventStore {
    fun record(
        eventType: TrustAnchorEventType,
        subject: String,
        now: LocalDateTime = trustAnchorNowLocalDateTime(),
    ) {
        TrustAnchorEventTable.insert {
            it[id] = Uuid.random()
            it[occurredAt] = now
            it[TrustAnchorEventTable.eventType] = eventType
            it[TrustAnchorEventTable.subject] = subject
        }
    }

    /** Newest first, capped at [MAX_LISTED_EVENTS] -- mirrors [network.lapis.cloud.server.routes.registerFederationRoutes]'s own `MAX_OUTBOX_ITEMS` capping idiom for an unbounded-growth log. */
    fun listRecent(): List<ResultRow> =
        TrustAnchorEventTable
            .selectAll()
            .orderBy(TrustAnchorEventTable.occurredAt, SortOrder.DESC)
            .limit(MAX_LISTED_EVENTS)
            .toList()

    fun ResultRow.toEventDto(): TrustAnchorEventDto =
        TrustAnchorEventDto(
            id = this[TrustAnchorEventTable.id].toString(),
            occurredAt = this[TrustAnchorEventTable.occurredAt],
            eventType = this[TrustAnchorEventTable.eventType],
            subject = this[TrustAnchorEventTable.subject],
        )
}
