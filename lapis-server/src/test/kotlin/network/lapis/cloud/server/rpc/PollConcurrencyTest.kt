package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- the poll row lock, the UNIQUE index and the settings-row mutex under real
 * parallelism. **H2 only** (the test database): this does NOT prove the same behaviour on Postgres
 * (listed under "Known limitations" in the CHANGELOG). Same barrier idiom as [ElectionIntegrityTest].
 * A request may legitimately fail with a lock timeout under H2, so the assertions pin the END STATE
 * (row counts, caps), not that every loser is a [ConflictException].
 */
class PollConcurrencyTest :
    FunSpec({
        val data = PollTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { data.deleteAllPolls() }
        afterSpec {
            data.deleteAllPolls()
            data.cleanUp()
        }

        suspend fun <T> parallel(
            n: Int,
            block: suspend (index: Int) -> T,
        ): List<T> {
            val barrier = CyclicBarrier(n)
            return coroutineScope {
                (0 until n)
                    .map { i ->
                        async(Dispatchers.IO) {
                            barrier.await()
                            block(i)
                        }
                    }.map { it.await() }
            }
        }

        fun responseRows(pollId: String): Long =
            transaction {
                PollResponseTable
                    .selectAll()
                    .where {
                        PollResponseTable.pollId eq
                            Uuid.parse(pollId)
                    }.count()
            }

        fun participationRows(pollId: String): Long =
            transaction { PollParticipationTable.selectAll().where { PollParticipationTable.pollId eq Uuid.parse(pollId) }.count() }

        test("the same member answering 8 times at once: exactly one participation and one response row") {
            pollTestApplication {
                val chair = data.chair()
                val voter = data.member(label = "parallel")
                val poll = call(member = chair) { createPoll(pollInput()) }
                val results =
                    parallel(8) { i ->
                        attempt(member = voter) {
                            castPollResponse(
                                PollResponseInput(
                                    pollId = poll.id,
                                    optionId =
                                        poll.options[
                                            i %
                                                2,
                                        ].id,
                                ),
                            )
                        }
                    }
                results.count { it.isSuccess } shouldBe 1
                results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                participationRows(poll.id) shouldBe 1L
                responseRows(poll.id) shouldBe 1L
            }
        }

        test("closePoll racing with answers: no response lands after the close, responses equal participations") {
            pollTestApplication {
                val chair = data.chair()
                repeat(3) { round ->
                    val voters = (0 until 7).map { data.member(label = "race-$round-$it") }
                    val poll = call(member = chair) { createPoll(pollInput(question = "Rennen $round?")) }
                    // index 0 closes; 1..7 answer. The snapshot of the row count is taken right after the close committed.
                    var rowsAtClose = -1L
                    parallel(8) { i ->
                        if (i == 0) {
                            val closed = attempt(member = chair) { closePoll(poll.id) }
                            if (closed.isSuccess) rowsAtClose = responseRows(poll.id)
                            closed
                        } else {
                            attempt(
                                member = voters[i - 1],
                            ) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                        }
                    }
                    val finalRows = responseRows(poll.id)
                    participationRows(poll.id) shouldBe finalRows
                    if (rowsAtClose >= 0) {
                        // closed successfully: nothing may have been added after the close returned
                        rowsAtClose shouldBe finalRows
                        transaction {
                            PollTable
                                .selectAll()
                                .where {
                                    PollTable.id eq
                                        Uuid.parse(
                                            poll.id,
                                        )
                                }.single()[PollTable.status]
                        } shouldBe
                            PollStatus.CLOSED
                    }
                    // and from now on nobody can answer any more
                    attempt(member = data.member(label = "zu-spaet-$round")) {
                        castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id))
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("21 concurrent createPoll calls with 19 polls already open: at most 20 polls are open afterwards") {
            pollTestApplication {
                val chairs = List(4) { data.chair(label = "Vorsitz $it") }
                repeat(PollRules.MAX_OPEN_POLLS - 1) { i ->
                    call(member = chairs[i / PollRules.MAX_OPEN_POLLS_PER_CREATOR]) { createPoll(pollInput(question = "Vorhanden $i?")) }
                }
                val racers = List(21) { data.chair(label = "Rennen $it") }
                val results = parallel(21) { i -> attempt(member = racers[i]) { createPoll(pollInput(question = "Parallel $i?")) } }
                val successes = results.count { it.isSuccess }
                successes shouldBeIn listOf(0, 1) // exactly one slot was left (0 only if a lock timeout hit every request)
                val open = transaction { PollTable.selectAll().where { effectiveOpenPredicate(DbClock.nowLocalDateTime()) }.count() }
                open shouldBe (PollRules.MAX_OPEN_POLLS - 1 + successes).toLong()
                (open <= PollRules.MAX_OPEN_POLLS) shouldBe true
                results.filter { it.isFailure }.size shouldBe 21 - successes
            }
        }
    })
