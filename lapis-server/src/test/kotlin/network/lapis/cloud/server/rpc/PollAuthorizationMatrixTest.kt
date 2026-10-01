package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- the full authorization matrix of [PollService]: who may create, who may close/abort,
 * who may respond, who may read -- including the "no existence oracle" rule (a caller who may not
 * respond gets Forbidden even for a poll id that does not exist).
 */
class PollAuthorizationMatrixTest :
    FunSpec({
        val data = PollTestData()
        val today: LocalDate =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { data.deleteAllPolls() }
        afterSpec {
            data.deleteAllPolls()
            data.cleanUp()
        }

        fun seated(
            role: CommitteeRole,
            active: Boolean = true,
            since: LocalDate = LocalDate(2020, 1, 1),
            until: LocalDate? = null,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val member = data.member(label = "seat-$role", status = status)
            data.seat(committeeId = data.committee(active = active), memberId = member, role = role, since = since, until = until)
            return member
        }

        suspend fun PollApp.canCreate(member: Uuid): Boolean {
            val result = attempt(member = member) { createPoll(pollInput(question = "Frage ${Uuid.random()}")) }
            result.exceptionOrNull()?.shouldBeInstanceOf<ForbiddenException>()
            return result.isSuccess
        }

        test("create: ADMIN and BOARD may, and so may the five recording roles of an active committee") {
            pollTestApplication {
                canCreate(data.member(label = "admin", role = AccountRole.ADMIN)) shouldBe true
                canCreate(data.member(label = "board", role = AccountRole.BOARD)) shouldBe true
                for (role in listOf(
                    CommitteeRole.CHAIR,
                    CommitteeRole.DEPUTY_CHAIR,
                    CommitteeRole.SECRETARY,
                    CommitteeRole.GENERAL_SECRETARY,
                    CommitteeRole.MANAGING_DIRECTOR,
                )) {
                    withClue(clue = "role $role") { canCreate(seated(role)) shouldBe true }
                }
            }
        }

        test("create: a plain member, the other committee roles, an inactive committee and an out-of-term seat may NOT") {
            pollTestApplication {
                canCreate(data.member(label = "plain")) shouldBe false
                canCreate(data.member(label = "treasurer", role = AccountRole.TREASURER)) shouldBe false
                for (role in listOf(CommitteeRole.MEMBER, CommitteeRole.ASSESSOR, CommitteeRole.PRESS_SPOKESPERSON)) {
                    withClue(clue = "role $role") { canCreate(seated(role)) shouldBe false }
                }
                withClue(clue = "inactive committee") { canCreate(seated(CommitteeRole.CHAIR, active = false)) shouldBe false }
                withClue(clue = "term ended yesterday") {
                    canCreate(seated(CommitteeRole.CHAIR, until = today.minus(DatePeriod(days = 1)))) shouldBe
                        false
                }
                withClue(clue = "term starts tomorrow") {
                    canCreate(seated(CommitteeRole.CHAIR, since = today.plus(DatePeriod(days = 1)))) shouldBe
                        false
                }
                withClue(clue = "term ends today, still in office") { canCreate(seated(CommitteeRole.CHAIR, until = today)) shouldBe true }
                withClue(clue = "a chair who is no longer an ACTIVE member") {
                    canCreate(seated(CommitteeRole.CHAIR, status = MemberStatus.WITHDRAWN)) shouldBe false
                }
            }
        }

        test(
            "close/abort: the creator and privileged members may; another committee's chair, a plain member and a creator who lost the seat may not",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val otherChair = data.chair("anderes Gremium")
                val admin = data.member(label = "admin", role = AccountRole.ADMIN)
                val board = data.member(label = "board", role = AccountRole.BOARD)
                val plain = data.member(label = "plain")

                suspend fun fresh() = call(member = chair) { createPoll(pollInput(question = "Frage ${Uuid.random()}")) }

                val forbidden = fresh()
                for (caller in listOf(otherChair, plain)) {
                    attempt(member = caller) { closePoll(forbidden.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = caller) { abortPoll(forbidden.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                call(member = chair) { closePoll(forbidden.id) }.status.name shouldBe "CLOSED"
                call(member = chair) { abortPoll(fresh().id) }.status.name shouldBe "ABORTED"
                call(member = admin) { closePoll(fresh().id) }.status.name shouldBe "CLOSED"
                call(member = board) { abortPoll(fresh().id) }.status.name shouldBe "ABORTED"

                // a creator who is no longer creator-capable (seat ended) cannot manage his own poll any more
                val ephemeral = data.member(label = "ephemeral")
                val committee = data.committee()
                data.seat(committeeId = committee, memberId = ephemeral, role = CommitteeRole.CHAIR)
                val own = call(member = ephemeral) { createPoll(pollInput(question = "Meine Frage")) }
                transaction {
                    CommitteeMembershipTable.update({ CommitteeMembershipTable.memberId eq ephemeral }) {
                        it[until] = today.minus(DatePeriod(days = 1))
                    }
                }
                attempt(member = ephemeral) { closePoll(own.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(member = ephemeral) { abortPoll(own.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                call(member = admin) { closePoll(own.id) }.status.name shouldBe "CLOSED"
            }
        }

        test(
            "respond: ACTIVE members may; GUEST, FRIEND, APPLICATION, WITHDRAWN, REJECTED may not -- even for a poll id that does not exist",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = data.member(label = "aktiv")) {
                    castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id))
                }.hasResponded shouldBe
                    true
                for (status in listOf(
                    MemberStatus.GUEST,
                    MemberStatus.FRIEND,
                    MemberStatus.APPLICATION,
                    MemberStatus.WITHDRAWN,
                    MemberStatus.REJECTED,
                )) {
                    val member = data.member(label = "status-$status", status = status)
                    withClue(clue = "status $status, existing poll") {
                        attempt(member = member) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ForbiddenException>()
                    }
                    withClue(clue = "status $status, unknown poll (no existence oracle)") {
                        attempt(
                            member = member,
                        ) { castPollResponse(PollResponseInput(pollId = Uuid.random().toString(), optionId = Uuid.random().toString())) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }

        test("read: FRIEND and GUEST are refused by every read method, for an existing and an unknown poll alike") {
            pollTestApplication {
                val chair = data.chair()
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = chair) { closePoll(poll.id) }
                for (status in listOf(MemberStatus.FRIEND, MemberStatus.GUEST)) {
                    val outsider = data.member(label = "outsider-$status", status = status)
                    for (id in listOf(poll.id, Uuid.random().toString())) {
                        attempt(member = outsider) { getPoll(id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        attempt(member = outsider) { getPollResult(id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        attempt(member = outsider) { getPollParticipation(id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        attempt(
                            member = outsider,
                        ) { listPollParticipations(listOf(id)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        attempt(member = outsider) { closePoll(id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        attempt(member = outsider) { abortPoll(id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                    attempt(member = outsider) { listPolls() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = outsider) { createPoll(pollInput()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("read: an ACTIVE plain member sees polls and results") {
            pollTestApplication {
                val chair = data.chair()
                val reader = data.member(label = "leser")
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = reader) { getPoll(poll.id) }.id shouldBe poll.id
                call(member = reader) { listPolls() }.map { it.id } shouldBe listOf(poll.id)
            }
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
