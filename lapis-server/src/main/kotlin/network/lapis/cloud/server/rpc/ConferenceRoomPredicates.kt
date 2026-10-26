package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.select

/**
 * V1.9.113 -- `true` for every room that does NOT belong to an event (the ordinary-room list and the lazy reconciliation never see
 * event rooms).
 * Moved here in V1.9.224 so the live counter of the sidebar ([network.lapis.cloud.server.dashboard.NavCountsQuery]) counts exactly the
 * rooms
 * that [ConferenceService.listActiveRooms] lists; there is ONE definition.
 */
internal fun notAnEventRoomPredicate(): Op<Boolean> =
    ConferenceRoomTable.id notInSubQuery
        EventConferenceTable
            .select(EventConferenceTable.conferenceRoomId)
            .where { EventConferenceTable.conferenceRoomId.isNotNull() }
