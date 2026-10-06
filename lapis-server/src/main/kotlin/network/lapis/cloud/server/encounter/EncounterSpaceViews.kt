package network.lapis.cloud.server.encounter

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTheme
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The canonical CSV stored in `encounter_space.reaction_set` (HAND first, canonical order, deduplicated). */
internal fun reactionSetCsv(options: Collection<EncounterReactionOption>): String =
    EncounterReactionOption.normalize(options).joinToString(",") { it.name }

/**
 * Parses `encounter_space.reaction_set`. Robust against a value a future version no longer knows: unknown tokens are dropped and HAND is
 * forced, so a stored value can never make a room unreadable.
 */
internal fun parseReactionSet(csv: String): List<EncounterReactionOption> =
    EncounterReactionOption.normalize(
        csv.split(',').mapNotNull { token ->
            EncounterReactionOption.entries.firstOrNull {
                it.name ==
                    token.trim()
            }
        },
    )

/** The profile of a space row; an unknown stored value (impossible under the CHECK constraint) falls back to the church profile. */
internal fun profileOf(row: ResultRow): EncounterProfile =
    EncounterProfile.entries.firstOrNull { it.name == row[EncounterSpaceTable.profile] } ?: EncounterProfile.CHURCH_SERVICE

/** The effective participant ceiling of a space: its own limit, clamped to the instance maximum. */
internal fun effectiveMaxParticipants(
    spaceRow: ResultRow,
    config: ConferenceConfig,
): Int = minOf(spaceRow[EncounterSpaceTable.maxParticipants] ?: config.maxParticipants, config.maxParticipants)

/**
 * Welle V1.9.61 -- builds [EncounterSpaceDto]s (batched: a fixed number of queries independent of the number of spaces) and the audit
 * snapshots. Must run INSIDE the caller's open `transaction {}`.
 *
 * Privacy: [toDtos] exposes the NUMBER of people present and the names of the PULPIT office holders -- never the names of the
 * congregation, and no timestamps other than the session's opening time.
 */
internal object EncounterSpaceViews {
    fun toDtos(
        rows: List<ResultRow>,
        current: CurrentMember,
        config: ConferenceConfig,
    ): List<EncounterSpaceDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[EncounterSpaceTable.id] }
        val sessions: Map<Uuid, ResultRow> =
            ConferenceRoomTable
                .selectAll()
                .where { (ConferenceRoomTable.encounterSpaceId inList ids) and ConferenceRoomTable.endedAt.isNull() }
                .associateBy { it[ConferenceRoomTable.encounterSpaceId]!! }
        val countColumn = ConferenceParticipationTable.roomId.count()
        val presentByRoom: Map<Uuid, Int> =
            if (sessions.isEmpty()) {
                emptyMap()
            } else {
                ConferenceParticipationTable
                    .select(ConferenceParticipationTable.roomId, countColumn)
                    .where { ConferenceParticipationTable.roomId inList sessions.values.map { it[ConferenceRoomTable.id] } }
                    .groupBy(ConferenceParticipationTable.roomId)
                    .associate { it[ConferenceParticipationTable.roomId] to it[countColumn].toInt() }
            }
        val officers =
            (EncounterSpaceRoleTable innerJoin MemberTable)
                .selectAll()
                .where { (EncounterSpaceRoleTable.spaceId inList ids) and (MemberTable.status eq MemberStatus.ACTIVE) }
                .toList()
        return rows.map { row ->
            val spaceId = row[EncounterSpaceTable.id]
            val session = sessions[spaceId]
            val spaceOfficers = officers.filter { it[EncounterSpaceRoleTable.spaceId] == spaceId }
            val myRole =
                spaceOfficers
                    .firstOrNull { it[EncounterSpaceRoleTable.memberId] == current.memberId }
                    ?.let { EncounterSpaceRole.valueOf(it[EncounterSpaceRoleTable.role]) }
            EncounterSpaceDto(
                id = spaceId.toString(),
                title = row[EncounterSpaceTable.title],
                description = row[EncounterSpaceTable.description],
                theme = EncounterTheme.valueOf(row[EncounterSpaceTable.themeKey]),
                mode = EncounterSpaceMode.valueOf(row[EncounterSpaceTable.mode]),
                guestPolicy = EncounterGuestPolicy.valueOf(row[EncounterSpaceTable.guestPolicy]),
                closedNotice = row[EncounterSpaceTable.closedNotice],
                open = session != null,
                openedAt = session?.get(ConferenceRoomTable.createdAt),
                presentCount = session?.let { presentByRoom[it[ConferenceRoomTable.id]] } ?: 0,
                maxParticipants = effectiveMaxParticipants(spaceRow = row, config = config),
                pulpitDisplayNames =
                    spaceOfficers
                        .filter { it[EncounterSpaceRoleTable.role] == EncounterSpaceRole.PULPIT.name }
                        .map { it[MemberTable.displayName] }
                        .sorted(),
                myRole = myRole,
                canModerate = current.isPrivileged || myRole != null,
                archived = row[EncounterSpaceTable.archivedAt] != null,
                profile = profileOf(row),
                reactions = parseReactionSet(row[EncounterSpaceTable.reactionSet]),
            )
        }
    }

    /** Audit snapshot of a space's configuration (no member data). */
    fun configSnapshot(row: ResultRow): String =
        buildJsonObject {
            put("title", row[EncounterSpaceTable.title])
            put("theme", row[EncounterSpaceTable.themeKey])
            put("mode", row[EncounterSpaceTable.mode])
            put("profile", row[EncounterSpaceTable.profile])
            put("reactions", row[EncounterSpaceTable.reactionSet])
            put("guestPolicy", row[EncounterSpaceTable.guestPolicy])
            put("maxParticipants", row[EncounterSpaceTable.maxParticipants])
            put("closedNotice", row[EncounterSpaceTable.closedNotice])
            put("archived", row[EncounterSpaceTable.archivedAt] != null)
        }.toString()

    /** Audit snapshot of the office assignment of a space (the office holders -- never the congregation). */
    fun rolesSnapshot(roles: List<Pair<Uuid, String>>): String =
        buildJsonObject {
            put(
                "roles",
                buildJsonArray {
                    roles.sortedBy { it.first.toString() }.forEach { (memberId, role) ->
                        add(
                            buildJsonObject {
                                put("memberId", memberId.toString())
                                put("role", role)
                            },
                        )
                    }
                },
            )
        }.toString()
}
