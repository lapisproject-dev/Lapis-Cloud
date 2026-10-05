package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IGovernanceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.54 -- the conflict handling of `resolveMotion` ([motionDecisionGuarded]) and of a bid on a vote that closed under it
 * ([castVoteGuarded] + [RejectedBidNotice]). A conflict is explained by reading the vote, never by reading the exception.
 */
class MotionDecisionGuardDomTest {
    private fun vote(status: VoteStatus) =
        VoteDto(
            id = "v1",
            motionId = "m1",
            meetingId = "s1",
            title = "V",
            status = status,
            options = emptyList(),
            winnerOptionId = null,
            secondPriceLtr = null,
            openedById = "x",
            openedByDisplayName = "X",
            openedAt = LocalDateTime(2026, 3, 1, 18, 0),
            closedAt = null,
            resolutionId = null,
        )

    private val input = VoteBallotInput(voteId = "v1", optionId = "o1", stakeLtr = 5.0.toDecimal())

    @Test
    fun aConflictOfResolveMotion_reloadsOnce_andReturnsNothing(): Promise<Unit> =
        formTest {
            var reloads = 0
            val result =
                motionDecisionGuarded(onConflict = { reloads++ }) {
                    throw ConflictException("Motion 123e4567-e89b-12d3-a456-426614174000 has an active Election")
                }
            assertNull(result)
            assertEquals(1, reloads)
            // any other failure keeps the behaviour of `guarded`: no reload
            val other = motionDecisionGuarded(onConflict = { reloads++ }) { throw ForbiddenException() }
            assertNull(other)
            assertEquals(1, reloads, "a refusal is not a reason to reload")
            assertEquals("ok", motionDecisionGuarded(onConflict = { reloads++ }) { "ok" })
            assertEquals(1, reloads)
        }

    @Test
    fun aBid_onAVoteThatClosedUnderIt_isMarkedNotCounted_andTheNoticeSurvivesTheReload(): Promise<Unit> =
        formTest {
            RejectedBidNotice.clear()
            val castRoute = routeOf { rpcService<IGovernanceService>().castVoteBallot(input) }
            val getRoute = routeOf { rpcService<IGovernanceService>().getVote("v1") }
            var notCounted = 0
            withFetchStub(
                respond = { request ->
                    when (request.rpcRoute) {
                        castRoute -> serviceExceptionResult(request.json.id as Int, CONFLICT_EXCEPTION)
                        getRoute -> request.answerWith(jsonOf(VoteDto.serializer(), vote(VoteStatus.CLOSED)))
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) { calls ->
                val result = castVoteGuarded(input) { notCounted++ }
                assertNull(result)
                assertEquals(1, notCounted, "the closed vote is detected by reading it")
                assertEquals(1, calls.toRoute(getRoute).size)
                RejectedBidNotice.mark("v1")
                // the detail view is rebuilt by the reload: the notice is asked again for the votes of the reloaded motion
                assertTrue(RejectedBidNotice.shownFor(listOf(vote(VoteStatus.CLOSED))))
                assertTrue(RejectedBidNotice.shownFor(listOf(vote(VoteStatus.CLOSED))), "and it stays")
                // another motion's votes clear it
                assertFalse(RejectedBidNotice.shownFor(listOf(vote(VoteStatus.OPEN).copy(id = "other"))))
                assertFalse(RejectedBidNotice.shownFor(listOf(vote(VoteStatus.CLOSED))), "once cleared it stays cleared")
            }
        }

    @Test
    fun aConflict_whileTheVoteIsStillOpen_isAStakeProblem_notANotCountedNotice(): Promise<Unit> =
        formTest {
            RejectedBidNotice.clear()
            val castRoute = routeOf { rpcService<IGovernanceService>().castVoteBallot(input) }
            val getRoute = routeOf { rpcService<IGovernanceService>().getVote("v1") }
            var notCounted = 0
            withFetchStub(
                respond = { request ->
                    when (request.rpcRoute) {
                        castRoute -> serviceExceptionResult(request.json.id as Int, CONFLICT_EXCEPTION)
                        getRoute -> request.answerWith(jsonOf(VoteDto.serializer(), vote(VoteStatus.OPEN)))
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) { calls ->
                assertNull(castVoteGuarded(input) { notCounted++ })
                assertEquals(0, notCounted, "an open vote is not 'not counted': the stake can be corrected and sent again")
                assertEquals(1, calls.toRoute(getRoute).size)
                assertFalse(RejectedBidNotice.shownFor(listOf(vote(VoteStatus.OPEN))))
            }
        }

    @Test
    fun theNotice_isAWarningWithRoleAlert(): Promise<Unit> =
        formTest {
            mountedForm("rejected-bid-notice") { root, element ->
                renderRejectedBidNotice(root)
                val alert = element().allOf("[role=alert]").single()
                assertTrue(alert.className.contains("alert-warning"))
                assertEquals(
                    "Ihr Gebot wurde nicht gezählt: Die Abstimmung ist nicht mehr offen. Die Ansicht wurde aktualisiert.",
                    alert.textContent.orEmpty().trim(),
                )
            }
        }
}
