package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.server.memberfinder.MemberFinderLifecycle
import network.lapis.cloud.server.memberfinder.MemberFinderRequestRow
import network.lapis.cloud.server.memberfinder.MemberFinderRequestStatus
import network.lapis.cloud.server.memberfinder.MemberFinderRequestStore
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Caps the export of the append-only consent log at this many newest rows (an abuse bound, like `PublicRankingConsentPersonalData`). */
private const val MAX_EXPORTED_EVENTS = 2_000

/** Caps the export of requests per direction. */
private const val MAX_EXPORTED_REQUESTS = 500

/**
 * V1.9.144 "Mitglieder-Finder" -- owns `member_finder_consent_event`, `member_finder_request` and `member_finder_block`.
 *
 * **What the export shows (Art. 15 / 20) and what it must never show.** The member's own consent events; the own OUTGOING requests **only in the
 * sender's view** ([MemberFinderRequestStore.outgoingStateOf]) -- never the stored status DECLINED, which would be an oracle about the decision of the
 * other side; the own INCOMING requests with the member's own decision but **without the sender**; and the NUMBER of connections and of own blocks. Never
 * a foreign id, never a foreign name.
 *
 * **Erased, never retained**: nothing here is organizational and nothing is an accountability record that outlives the person
 * ([MemberFinderLifecycle.purgeMember]): the consent events, the member's own requests and the blocks of BOTH sides are deleted. Requests the member only
 * RECEIVED stay as phantom rows without counterpart and note (the sender must not see a pending request vanish, decision E6).
 */
object MemberFinderPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberFinder"
    override val displayName = "Mitglieder-Finder"
    override val coveredTables = setOf(MemberFinderConsentEventTable, MemberFinderRequestTable, MemberFinderBlockTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val events =
                MemberFinderConsentEventTable
                    .selectAll()
                    .where { MemberFinderConsentEventTable.memberId eq memberId }
                    .orderBy(MemberFinderConsentEventTable.occurredAt, SortOrder.DESC)
                    .limit(MAX_EXPORTED_EVENTS + 1)
                    .toList()
            put("truncated", events.size > MAX_EXPORTED_EVENTS)
            putJsonArray("consentEvents") {
                events.take(MAX_EXPORTED_EVENTS).forEach { row ->
                    add(
                        buildJsonObject {
                            put("eventType", row[MemberFinderConsentEventTable.eventType].name)
                            put("occurredAt", row[MemberFinderConsentEventTable.occurredAt].toString())
                            put("supersededAt", row[MemberFinderConsentEventTable.supersededAt]?.toString())
                            put("consentVersion", row[MemberFinderConsentEventTable.consentVersion])
                            put("consentSha256", row[MemberFinderConsentEventTable.consentSha256])
                            put("interest", row[MemberFinderConsentEventTable.interest]?.name)
                            put("adultConfirmed", row[MemberFinderConsentEventTable.adultConfirmed])
                            put("revokeReason", row[MemberFinderConsentEventTable.revokeReason]?.name)
                        },
                    )
                }
            }
            val now = ServerClock.now()
            putJsonArray("outgoingRequests") {
                MemberFinderRequestTable
                    .selectAll()
                    .where { MemberFinderRequestTable.fromMemberId eq memberId }
                    .orderBy(MemberFinderRequestTable.createdAt, SortOrder.DESC)
                    .limit(MAX_EXPORTED_REQUESTS)
                    .forEach { result ->
                        val row = result.toRequestRow()
                        add(
                            buildJsonObject {
                                put("purpose", row.purpose.name)
                                // The note of an OUTGOING request is deliberately not exported: it is nulled by retention, opt-out and erasure of the
                                // addressee while the sender still sees PENDING, so exporting it would be an oracle about the other side (E6).
                                // The sender's view, never the stored status.
                                put("state", MemberFinderRequestStore.outgoingStateOf(row = row, now = now).name)
                                put("sentAt", row.createdAt.toString())
                                put("expiresAt", row.expiresAt.toString())
                            },
                        )
                    }
            }
            putJsonArray("incomingRequests") {
                MemberFinderRequestTable
                    .selectAll()
                    .where { MemberFinderRequestTable.toMemberId eq memberId }
                    .orderBy(MemberFinderRequestTable.createdAt, SortOrder.DESC)
                    .limit(MAX_EXPORTED_REQUESTS)
                    .forEach { result ->
                        val row = result.toRequestRow()
                        add(
                            buildJsonObject {
                                put("purpose", row.purpose.name)
                                put("note", row.note)
                                // Only the member's OWN decision; a withdrawal or expiry by the other side is not reported.
                                put(
                                    "decision",
                                    when (row.status) {
                                        MemberFinderRequestStatus.ACCEPTED -> "ACCEPTED"
                                        MemberFinderRequestStatus.DECLINED -> "DECLINED"
                                        else -> "NONE"
                                    },
                                )
                                put("receivedAt", row.createdAt.toString())
                            },
                        )
                    }
            }
            put(
                "connectionCount",
                MemberFinderRequestTable
                    .selectAll()
                    .where {
                        (MemberFinderRequestTable.status eq MemberFinderRequestStatus.ACCEPTED) and
                            ((MemberFinderRequestTable.fromMemberId eq memberId) or (MemberFinderRequestTable.toMemberId eq memberId))
                    }.count(),
            )
            put(
                "ownBlockCount",
                MemberFinderBlockTable.selectAll().where { MemberFinderBlockTable.blockerMemberId eq memberId }.count(),
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val events =
            MemberFinderConsentEventTable
                .selectAll()
                .where { MemberFinderConsentEventTable.memberId eq memberId }
                .count()
                .toInt()
        val requests =
            MemberFinderRequestTable
                .selectAll()
                .where { (MemberFinderRequestTable.fromMemberId eq memberId) or (MemberFinderRequestTable.toMemberId eq memberId) }
                .count()
                .toInt()
        val blocks =
            MemberFinderBlockTable
                .selectAll()
                .where { (MemberFinderBlockTable.blockerMemberId eq memberId) or (MemberFinderBlockTable.blockedMemberId eq memberId) }
                .count()
                .toInt()
        MemberFinderLifecycle.purgeMember(memberId)
        return listOf(
            TableErasureOutcome(table = "member_finder_consent_event", rowsDeleted = events),
            TableErasureOutcome(table = "member_finder_request", rowsDeleted = requests),
            TableErasureOutcome(table = "member_finder_block", rowsDeleted = blocks),
        )
    }

    private fun ResultRow.toRequestRow(): MemberFinderRequestRow =
        MemberFinderRequestRow(
            id = this[MemberFinderRequestTable.id],
            fromMemberId = this[MemberFinderRequestTable.fromMemberId],
            toMemberId = this[MemberFinderRequestTable.toMemberId],
            purpose = this[MemberFinderRequestTable.purpose],
            note = this[MemberFinderRequestTable.note],
            status = this[MemberFinderRequestTable.status],
            createdAt = this[MemberFinderRequestTable.createdAt],
            expiresAt = this[MemberFinderRequestTable.expiresAt],
            decidedAt = this[MemberFinderRequestTable.decidedAt],
            senderWithdrawnAt = this[MemberFinderRequestTable.senderWithdrawnAt],
        )
}
