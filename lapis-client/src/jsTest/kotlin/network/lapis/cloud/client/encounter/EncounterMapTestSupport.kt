package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto
import network.lapis.cloud.shared.domain.EncounterTableDto
import network.lapis.cloud.shared.domain.EncounterTableTokenAnswer
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService

/** V1.9.166: an encounter service of which only the map methods are used by a test; every other method fails loudly. */
internal abstract class MapOnlyEncounterService : IEncounterSpaceService {
    private fun unused(): Nothing = error("not part of the map tests")

    override suspend fun listSpaces(): List<EncounterSpaceDto> = unused()

    override suspend fun getSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun getEntryInfo(spaceId: String): EncounterEntryInfoDto = unused()

    override suspend fun listSpaceRoles(spaceId: String): List<EncounterSpaceRoleDto> = unused()

    override suspend fun createSpace(input: EncounterSpaceInput): EncounterSpaceDto = unused()

    override suspend fun updateSpace(
        spaceId: String,
        input: EncounterSpaceInput,
    ): EncounterSpaceDto = unused()

    override suspend fun archiveSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun setSpaceRoles(
        spaceId: String,
        assignments: List<EncounterSpaceRoleAssignmentInput>,
    ): List<EncounterSpaceRoleDto> = unused()

    override suspend fun openSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun closeSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun enterSpace(
        spaceId: String,
        consent: EncounterConsentInput?,
    ): EncounterEntryDto = unused()

    override suspend fun leaveSpace(spaceId: String) = unused()

    override suspend fun listPresent(spaceId: String): List<EncounterPresentDto> = unused()

    override suspend fun selectSeat(
        spaceId: String,
        seat: Int?,
    ): List<EncounterPresentDto> = unused()

    override suspend fun removeFromSpace(
        spaceId: String,
        memberId: String,
    ) = unused()

    override suspend fun silenceInSpace(
        spaceId: String,
        memberId: String,
    ) = unused()

    override suspend fun joinTable(
        spaceId: String,
        table: Int,
        seat: Int,
    ): EncounterTableTokenDto = unused()

    override suspend fun leaveTable(spaceId: String) = unused()

    override suspend fun tableToken(spaceId: String): EncounterTableTokenAnswer = unused()

    override suspend fun listTables(spaceId: String): List<EncounterTableDto> = unused()

    override suspend fun quietTable(
        spaceId: String,
        table: Int,
        quiet: Boolean,
    ) = unused()

    override suspend fun sendToPlenum(
        spaceId: String,
        memberId: String,
    ) = unused()

    override suspend fun blessSpace(spaceId: String) = unused()

    override suspend fun ringBell(spaceId: String) = unused()

    override suspend fun grantFloor(
        spaceId: String,
        memberId: String,
    ): EncounterFloorDto = unused()

    override suspend fun extendFloor(
        spaceId: String,
        seconds: Int,
    ): EncounterFloorDto = unused()

    override suspend fun endFloor(spaceId: String) = unused()

    override suspend fun floorState(spaceId: String): EncounterFloorDto = unused()
}

/** A scripted map service: records every call, answers from the lambdas the test sets. */
internal class FakeMapService : MapOnlyEncounterService() {
    val calls = mutableListOf<String>()
    var snapshot: () -> EncounterMapSnapshotDto = {
        EncounterMapSnapshotDto(epoch = "aa", seq = 0, positions = emptyList(), self = EncounterMapSelfDto(onMap = false))
    }
    var onEnter: () -> EncounterMapSelfDto = { EncounterMapSelfDto(onMap = true, x = 5, y = 7) }
    var onStep: (EncounterMapFacing) -> EncounterMapSelfDto = { EncounterMapSelfDto(onMap = true, x = 5, y = 6, facing = it) }
    var onTeleport: (EncounterMapAnchor) -> EncounterMapSelfDto = { EncounterMapSelfDto(onMap = true, x = 5, y = 4) }

    override suspend fun mapEnter(spaceId: String): EncounterMapSelfDto {
        calls += "enter"
        return onEnter()
    }

    override suspend fun mapStep(
        spaceId: String,
        direction: EncounterMapFacing,
    ): EncounterMapSelfDto {
        calls += "step:${direction.name}"
        return onStep(direction)
    }

    override suspend fun mapTeleport(
        spaceId: String,
        anchor: EncounterMapAnchor,
    ): EncounterMapSelfDto {
        calls += "teleport:${anchor.name}"
        return onTeleport(anchor)
    }

    override suspend fun mapLeave(spaceId: String) {
        calls += "leave"
    }

    override suspend fun mapSnapshot(spaceId: String): EncounterMapSnapshotDto {
        calls += "snapshot"
        return snapshot()
    }
}
