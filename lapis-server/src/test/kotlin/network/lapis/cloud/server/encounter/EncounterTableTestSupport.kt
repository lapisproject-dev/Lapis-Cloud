package network.lapis.cloud.server.encounter

import io.ktor.server.testing.ApplicationTestBuilder
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import kotlin.uuid.Uuid

/** An ASSEMBLY room with 3 tables of 2 seats, a steward, a pulpit person, a board member and three congregation members. */
internal class TableWorld(
    val rig: EncounterRig,
    val space: Uuid,
    val steward: Uuid,
    val pulpit: Uuid,
    val board: Uuid,
    val a: Uuid,
    val b: Uuid,
    val c: Uuid,
) {
    val spaceId: String get() = space.toString()
}

internal fun EncounterFixtures.tableWorld(
    rig: EncounterRig = EncounterRig(),
    tablesEnabled: Boolean = true,
    profile: EncounterProfile = EncounterProfile.ASSEMBLY,
): TableWorld {
    val board = createMember(role = AccountRole.BOARD, name = "Vorstand")
    val steward = createMember(name = "Steward")
    val pulpit = createMember(name = "Pulpit")
    val space =
        createSpace(
            createdBy = board,
            profile = profile,
            tablesEnabled = tablesEnabled,
            tableCount = 3,
            tableSeats = 2,
        )
    setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
    setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
    return TableWorld(
        rig = rig,
        space = space,
        steward = steward,
        pulpit = pulpit,
        board = board,
        a = createMember(name = "Anna"),
        b = createMember(name = "Berta"),
        c = createMember(name = "Clara"),
    )
}

/** The steward opens the room, then everybody in [people] enters. */
internal suspend fun ApplicationTestBuilder.openAndEnter(
    w: TableWorld,
    vararg people: Uuid,
) {
    w.rig.asMember(client = client, member = w.steward) { it.openSpace(w.spaceId) }.getOrThrow()
    people.forEach { p -> w.rig.asMember(client = client, member = p) { it.enterSpace(spaceId = w.spaceId, consent = null) }.getOrThrow() }
}
