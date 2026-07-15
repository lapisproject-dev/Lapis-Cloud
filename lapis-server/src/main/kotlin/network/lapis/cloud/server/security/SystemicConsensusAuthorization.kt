package network.lapis.cloud.server.security

import kotlin.uuid.Uuid

/**
 * Authorization helpers for Systemic Consensus (V0.2.5). [canManageSystemicConsensus] reuses
 * [canRecordForMeeting]'s Committee-leadership-or-privileged rule verbatim -- managing a
 * SystemicConsensus's lifecycle (opening it, freezing options, closing/reopening Rating,
 * evaluating, aborting) is the same kind of "who runs this Committee's business" decision
 * [canRecordForMeeting] already governs for Meetingen/Resolutions/Voteen/Electionen. `committeeId`
 * here is always the hosting Motion's own target Committee, mirroring
 * [network.lapis.cloud.server.security.canManageElection]'s convention. Participation checks
 * (`addOption`/`castResistanceBallot`) are done directly against the live/frozen eligibility set in
 * `SystemicConsensusService` itself -- same house style as `ElectionService.castElectionBallot`'s inline
 * `ElectionEligibleVoterTable` check -- rather than as a separate extension function here, since
 * there is no standalone "is eligible" predicate reused outside that one call site.
 */
fun CurrentMember.canManageSystemicConsensus(committeeId: Uuid): Boolean = canRecordForMeeting(committeeId)
