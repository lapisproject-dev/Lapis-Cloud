package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- behaviour of [PollService] end to end (real H2, real ledger, real audit chain):
 * happy path, results only after closing, the disclosure thresholds, the LTR weight snapshot, lazy
 * deadline expiry, the open-poll cap, validation, list/batch limits, abort and the audit trail.
 */
class PollServiceTest :
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

        // `closesAt` is a class-B wall-clock of the ORGANIZATION zone (V1.9.38), so a deadline "in <duration>" is
        // built on that zone's clock -- not on the zone of the test process.
        fun inFuture(duration: Duration): LocalDateTime {
            val zone = OrganizationTimeZone.current()
            return ServerClock
                .nowIn(zone)
                .toInstant(zone)
                .plus(duration)
                .toLocalDateTime(zone)
        }

        /** Moves a poll's whole timeline into the past (creation 2 days ago, deadline 1 day ago) -- the deadline has passed. */
        fun expire(pollId: String) {
            val zone = TimeZone.currentSystemDefault()
            val now = DbClock.nowLocalDateTime(zone).toInstant(zone)
            transaction {
                PollTable.update({ PollTable.id eq Uuid.parse(pollId) }) {
                    it[createdAt] = now.plus((-2).days).toLocalDateTime(zone)
                    it[closesAt] = now.plus((-1).days).toLocalDateTime(zone)
                }
            }
        }

        fun members(
            n: Int,
            balances: List<String>? = null,
        ): List<Uuid> =
            (0 until n).map { i ->
                data.member(label = "Mitglied $i").also { m -> balances?.getOrNull(i)?.let { b -> data.mint(memberId = m, amount = b) } }
            }

        fun storedStatus(pollId: String): PollStatus =
            transaction { PollTable.selectAll().where { PollTable.id eq Uuid.parse(pollId) }.single()[PollTable.status] }

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

        fun auditActions(pollId: String): List<AuditAction> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.POLL) and (
                            AuditLogEntryTable.entityId eq
                                Uuid.parse(
                                    pollId,
                                )
                        )
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .map { it[AuditLogEntryTable.action] }
            }

        test("normal case: a chair creates a poll, six active members answer, the chair closes it, the result is disclosed") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6)
                val poll =
                    createAndAnswer(
                        creator = chair,
                        answers =
                            listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 1, 5 to 1).map {
                                voters[it.first] to
                                    it.second
                            },
                    )
                poll.status shouldBe PollStatus.OPEN
                poll.binding shouldBe false
                poll.options.map { it.text } shouldBe listOf("Ja", "Nein")
                poll.options.map { it.position } shouldBe listOf(0, 1)

                val closed = call(member = chair) { closePoll(poll.id) }
                closed.status shouldBe PollStatus.CLOSED
                closed.resultAvailable shouldBe true
                closed.responseCount shouldBe 6
                closed.closedAt?.let { true } shouldBe true

                val result = call(member = voters[0]) { getPollResult(poll.id) }
                result.responseCount shouldBe 6
                result.headResultAvailable shouldBe true
                result.headResult.map { it.count } shouldBe listOf(4, 2)
                result.headResult.map { it.optionId } shouldBe poll.options.map { it.id }
            }
        }

        test("the result exists only after closing: while OPEN getPollResult is a Conflict and no count is shown") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6)
                val poll = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                call(member = voters[0]) { getPoll(poll.id) }.responseCount.shouldBeNull()
                call(member = chair) { listPolls() }.single().responseCount.shouldBeNull()
                attempt(member = voters[0]) { getPollResult(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                call(member = chair) { closePoll(poll.id) }
                call(member = voters[0]) { getPollResult(poll.id) }.responseCount shouldBe 6
            }
        }

        test("a poll below the minimum participation shows the response count but no head count, and withholds the weighted result") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(4, listOf("10.00", "10.00", "10.00", "10.00"))
                val poll = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                val closed = call(member = chair) { closePoll(poll.id) }
                closed.resultAvailable shouldBe false
                closed.responseCount shouldBe 4
                val result = call(member = voters[0]) { getPollResult(poll.id) }
                result.responseCount shouldBe 4
                result.headResultAvailable shouldBe false
                result.headResult shouldHaveSize 0
                result.weightedResultAvailable shouldBe false
                result.weightedWithheldReason shouldBe PollWeightedWithheldReason.TOO_FEW_RESPONSES
            }
        }

        test("head and weighted result are computed from real ledger balances (incl. a member with balance 0)") {
            pollTestApplication {
                val chair = data.chair()
                // Ja: 30+30+30 and one member with balance 0; Nein: 10+10+20.
                val ja = members(4, listOf("30.00", "30.00", "30.00"))
                val nein = members(3, listOf("10.00", "10.00", "20.00"))
                val poll = createAndAnswer(creator = chair, answers = ja.map { it to 0 } + nein.map { it to 1 })
                call(member = chair) { closePoll(poll.id) }
                val result = call(member = chair) { getPollResult(poll.id) }
                result.responseCount shouldBe 7
                result.headResult.map { it.count } shouldBe listOf(4, 3)
                result.weightedResultAvailable shouldBe true
                // 90 : 40 -> 69.23 / 30.77 -> 69 / 31 (largest remainder)
                result.weightedResult.map { it.sharePercent } shouldBe listOf(69, 31)
            }
        }

        test("the weight is a snapshot of the balance at response time -- a later ledger change does not move the result") {
            pollTestApplication {
                val chair = data.chair()
                val ja = members(4, listOf("30.00", "30.00", "30.00", "0.00"))
                val nein = members(3, listOf("10.00", "10.00", "20.00"))
                val poll = createAndAnswer(creator = chair, answers = ja.map { it to 0 } + nein.map { it to 1 })
                // after everyone answered: a Nein member receives a huge amount, a Ja member spends everything
                data.mint(memberId = nein[0], amount = "100000.00", at = DbClock.nowLocalDateTime())
                data.mint(memberId = ja[0], amount = "-30.00", at = DbClock.nowLocalDateTime())
                call(member = chair) { closePoll(poll.id) }
                call(member = chair) { getPollResult(poll.id) }.weightedResult.map { it.sharePercent } shouldBe listOf(69, 31)
            }
        }

        test("a negative free balance is clamped to weight 0") {
            val negative =
                object : LtrBalanceProvider {
                    override fun freeBalance(memberId: Uuid): BigDecimal = BigDecimal("-50.00")

                    override fun balanceAsOf(
                        memberId: Uuid,
                        asOf: LocalDateTime,
                    ): BigDecimal = BigDecimal("-50.00")

                    override fun lockForDebit(memberId: Uuid) = Unit
                }
            pollTestApplication(ltrBalanceProvider = negative) {
                val chair = data.chair()
                val voters = members(5)
                val poll = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                call(member = chair) { closePoll(poll.id) }
                val stored =
                    transaction {
                        PollResponseTable
                            .selectAll()
                            .where {
                                PollResponseTable.pollId eq
                                    Uuid.parse(
                                        poll.id,
                                    )
                            }.map { it[PollResponseTable.weightLtr] }
                    }
                stored.all { it.compareTo(BigDecimal.ZERO) == 0 } shouldBe true
                val result = call(member = chair) { getPollResult(poll.id) }
                result.headResultAvailable shouldBe true
                result.weightedWithheldReason shouldBe PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT
            }
        }

        test("responding writes NO ledger entry and changes no balance") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(3, listOf("12.00", "5.00", "0.00"))
                val poll = call(member = chair) { createPoll(pollInput()) }
                val ledgerBefore = data.ledgerCount()
                voters.forEach { v ->
                    call(member = v) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                }
                data.ledgerCount() shouldBe ledgerBefore
                val sums =
                    transaction {
                        voters.map { v ->
                            LtrLedgerEntryTable
                                .selectAll()
                                .where {
                                    LtrLedgerEntryTable.memberId eq v
                                }.sumOf { it[LtrLedgerEntryTable.amountLtr] }
                        }
                    }
                sums.map { it.setScale(2) } shouldBe listOf(BigDecimal("12.00"), BigDecimal("5.00"), BigDecimal("0.00"))
            }
        }

        test("a second response of the same member is a Conflict and adds no row; an option of ANOTHER poll is a BadRequest") {
            pollTestApplication {
                val chair = data.chair()
                val voter = data.member(label = "doppelt")
                val poll = call(member = chair) { createPoll(pollInput()) }
                val other = call(member = chair) { createPoll(pollInput(question = "Andere Frage?")) }
                call(
                    member = voter,
                ) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }.hasResponded shouldBe
                    true
                attempt(member = voter) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[1].id)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                responseRows(poll.id) shouldBe 1L
                participationRows(poll.id) shouldBe 1L
                attempt(member = data.member(label = "falsche-option")) {
                    castPollResponse(PollResponseInput(pollId = poll.id, optionId = other.options[0].id))
                }.exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                attempt(
                    member = data.member(label = "kaputte-option"),
                ) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = "not-a-uuid")) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                responseRows(poll.id) shouldBe 1L
            }
        }

        test(
            "a poll whose deadline passed is CLOSED for everybody: no response, no close/abort, stored status stays OPEN, result readable",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6)
                val poll =
                    createAndAnswer(
                        creator = chair,
                        answers = voters.dropLast(1).map { it to 0 },
                        input = pollInput(closesAt = inFuture(1.days)),
                    )
                expire(poll.id)

                attempt(member = voters.last()) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                val read = call(member = voters[0]) { getPoll(poll.id) }
                read.status shouldBe PollStatus.CLOSED
                read.responseCount shouldBe 5
                read.resultAvailable shouldBe true
                read.closedAt shouldBe read.closesAt
                attempt(member = chair) { closePoll(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                attempt(member = chair) { abortPoll(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                storedStatus(poll.id) shouldBe PollStatus.OPEN // lazy: never materialised
                call(member = chair) { getPollResult(poll.id) }.headResultAvailable shouldBe true
                call(member = voters.last()) { getPollParticipation(poll.id) }.canRespond shouldBe false
                auditActions(poll.id) shouldBe listOf(AuditAction.CREATE) // expiry is no event, nothing written
                call(member = chair) { listPolls(status = PollStatus.OPEN) }.map { it.id } shouldBe emptyList()
                call(member = chair) { listPolls(status = PollStatus.CLOSED) }.map { it.id } shouldBe listOf(poll.id)
            }
        }

        test("the open-poll cap: 20 effectively open polls, the 21st is a Conflict, an expired one does not count") {
            pollTestApplication {
                val chairs = List(4) { data.chair(label = "Vorsitz $it") }
                val first = call(member = chairs[0]) { createPoll(pollInput(question = "Frage 0", closesAt = inFuture(1.days))) }
                repeat(PollRules.MAX_OPEN_POLLS - 1) { i ->
                    call(
                        member = chairs[(i + 1) / PollRules.MAX_OPEN_POLLS_PER_CREATOR],
                    ) { createPoll(pollInput(question = "Frage ${i + 1}")) }
                }
                val extraChair = data.chair(label = "Vorsitz extra")
                attempt(
                    member = extraChair,
                ) { createPoll(pollInput(question = "Zu viel")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                expire(first.id)
                call(member = extraChair) { createPoll(pollInput(question = "Jetzt passt es")) }.status shouldBe PollStatus.OPEN
                attempt(
                    member = extraChair,
                ) { createPoll(pollInput(question = "Wieder voll")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
            }
        }

        test("per-creator cap: one creator cannot hold more than 5 open polls, other creators are unaffected") {
            pollTestApplication {
                val chair = data.chair()
                val other = data.chair(label = "Anderer Vorsitz")
                repeat(PollRules.MAX_OPEN_POLLS_PER_CREATOR) { call(member = chair) { createPoll(pollInput(question = "Eigene $it")) } }
                attempt(
                    member = chair,
                ) { createPoll(pollInput(question = "Eine zu viel")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                call(member = other) { createPoll(pollInput(question = "Andere Person")) }.status shouldBe PollStatus.OPEN
            }
        }

        test("create/abort loop is rate limited per creator") {
            pollTestApplication {
                val chair = data.chair()
                repeat(PollRules.MAX_POLLS_CREATED_PER_WINDOW) {
                    val poll = call(member = chair) { createPoll(pollInput(question = "Schleife $it")) }
                    call(member = chair) { abortPoll(poll.id) }
                }
                attempt(
                    member = chair,
                ) { createPoll(pollInput(question = "Zu oft")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
            }
        }

        test("LTR transferred after the poll opened does not raise the recipient's weight") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(5, listOf("10.00", "10.00", "10.00", "10.00", "10.00"))
                val poll = call(member = chair) { createPoll(pollInput()) }
                // a big amount arrives only AFTER the poll was opened (e.g. a peer transfer)
                data.mint(memberId = voters[0], amount = "100000.00", at = inFuture(1.minutes))
                voters.forEach { v ->
                    call(member = v) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                }
                val stored =
                    transaction {
                        PollResponseTable
                            .selectAll()
                            .where {
                                PollResponseTable.pollId eq
                                    Uuid.parse(
                                        poll.id,
                                    )
                            }.map { it[PollResponseTable.weightLtr] }
                    }
                stored.all { it.compareTo(BigDecimal("10.00")) == 0 } shouldBe true
            }
        }

        test("validation: lengths, option count, duplicates (case/whitespace), deadline window") {
            pollTestApplication {
                val chair = data.chair()

                suspend fun bad(input: network.lapis.cloud.shared.domain.PollCreateInput) =
                    attempt(member = chair) { createPoll(input) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()

                bad(pollInput(question = "   "))
                bad(pollInput(question = "x".repeat(PollRules.MAX_QUESTION_LENGTH + 1)))
                call(member = chair) { createPoll(pollInput(question = "x".repeat(PollRules.MAX_QUESTION_LENGTH))) }
                bad(pollInput(description = "d".repeat(PollRules.MAX_DESCRIPTION_LENGTH + 1)))
                call(member = chair) { createPoll(pollInput(description = "d".repeat(PollRules.MAX_DESCRIPTION_LENGTH))) }
                bad(pollInput(options = listOf("nur eine")))
                bad(pollInput(options = emptyList()))
                bad(pollInput(options = (1..11).map { "Option $it" }))
                call(member = chair) { createPoll(pollInput(options = (1..10).map { "Option $it" })) }.options shouldHaveSize 10
                bad(pollInput(options = listOf("Ja", "")))
                bad(pollInput(options = listOf("Ja", "   ")))
                bad(pollInput(options = listOf("Ja", "o".repeat(PollRules.MAX_OPTION_LENGTH + 1))))
                bad(pollInput(options = listOf("Ja", "JA")))
                bad(pollInput(options = listOf("Ja Nein", "  ja   nein ")))
                bad(pollInput(question = "Steuerzeichen\u0000", options = listOf("a", "b")))
                // deadline: too early, in the past, too late
                bad(pollInput(closesAt = inFuture(Duration.parse("5m"))))
                bad(pollInput(closesAt = inFuture((-1).days)))
                bad(pollInput(closesAt = inFuture(366.days)))
                call(member = chair) { createPoll(pollInput(closesAt = inFuture(Duration.parse("20m")))) }.closesAt?.let { true } shouldBe
                    true
                call(member = chair) { createPoll(pollInput(closesAt = inFuture(364.days))) }.closesAt?.let { true } shouldBe true
            }
        }

        test("question, option and description are normalised (trim + collapsed whitespace); a blank description becomes null") {
            pollTestApplication {
                val chair = data.chair()
                val poll =
                    call(
                        member = chair,
                    ) {
                        createPoll(
                            pollInput(question = "  Soll   das\tgehen?  ", options = listOf("  Ja  gern ", "Nein"), description = "   "),
                        )
                    }
                poll.question shouldBe "Soll das gehen?"
                poll.options.map { it.text } shouldBe listOf("Ja gern", "Nein")
                poll.description.shouldBeNull()
            }
        }

        test(
            "list and batch limits: offset outside 0..10000 is a BadRequest, 101 ids a Conflict, unknown ids are skipped, bad ids NotFound",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val a = call(member = chair) { createPoll(pollInput(question = "A?")) }
                val b = call(member = chair) { createPoll(pollInput(question = "B?")) }
                attempt(member = chair) { listPolls(offset = -1) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                attempt(
                    member = chair,
                ) { listPolls(offset = PollRules.MAX_LIST_OFFSET + 1) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                call(member = chair) { listPolls(limit = 0) } shouldHaveSize 1 // clamped to 1
                call(member = chair) { listPolls(limit = 1000) } shouldHaveSize 2 // clamped to 100, only 2 exist
                call(member = chair) { listPolls(limit = 1, offset = 1) }.single().id shouldBe a.id // newest (b) first, a second
                call(member = chair) { listPolls(limit = 10) }.map { it.id } shouldBe listOf(b.id, a.id)

                val voter = data.member(label = "batch")
                attempt(member = voter) { listPollParticipations((1..101).map { Uuid.random().toString() }) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                call(member = voter) { listPollParticipations((1..100).map { Uuid.random().toString() }) } shouldHaveSize 0
                call(
                    member = voter,
                ) { listPollParticipations(listOf(a.id, Uuid.random().toString(), a.id, b.id)) }.map { it.pollId } shouldBe
                    listOf(a.id, b.id)
                attempt(member = voter) { listPollParticipations(listOf("nope")) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = voter) { getPoll("nope") }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = voter) { getPoll(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("an aborted poll never discloses anything: Conflict on the result, no response count, status ABORTED") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6)
                val poll = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                val aborted = call(member = chair) { abortPoll(poll.id) }
                aborted.status shouldBe PollStatus.ABORTED
                aborted.responseCount.shouldBeNull()
                aborted.resultAvailable shouldBe false
                attempt(member = voters[0]) { getPollResult(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                call(member = voters[0]) { getPoll(poll.id) }.responseCount.shouldBeNull()
                responseRows(poll.id) shouldBe 6L // kept, never disclosed
                attempt(member = voters[0]) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                attempt(member = chair) { closePoll(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
            }
        }

        test(
            "audit: CREATE and UPDATE for a closed poll, CREATE and VOID for an aborted one, NEVER an entry for a response, no counters in snapshots",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6, listOf("10.00", "10.00", "10.00", "10.00", "10.00", "10.00"))
                val closedPoll = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                call(member = chair) { closePoll(closedPoll.id) }
                val abortedPoll = call(member = chair) { createPoll(pollInput(question = "Wird abgebrochen?")) }
                call(
                    member = voters[0],
                ) { castPollResponse(PollResponseInput(pollId = abortedPoll.id, optionId = abortedPoll.options[0].id)) }
                call(member = chair) { abortPoll(abortedPoll.id) }

                auditActions(closedPoll.id) shouldContainExactly listOf(AuditAction.CREATE, AuditAction.UPDATE)
                auditActions(abortedPoll.id) shouldContainExactly listOf(AuditAction.CREATE, AuditAction.VOID)
                val snapshots =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.POLL }
                            .flatMap { listOfNotNull(it[AuditLogEntryTable.beforeSnapshot], it[AuditLogEntryTable.afterSnapshot]) }
                    }
                snapshots.forEach { json ->
                    json.lowercase().let {
                        it shouldNotContain "weight"
                        it shouldNotContain "count"
                        it shouldNotContain "member"
                        it shouldNotContain "response"
                    }
                }
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.actorMemberId eq voters[0] }
                        .count()
                } shouldBe 0L
            }
        }

        test("participation: eligible / hasResponded / canRespond reflect only the caller's own state") {
            pollTestApplication {
                val chair = data.chair()
                val a = data.member(label = "a")
                val b = data.member(label = "b")
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = a) { getPollParticipation(poll.id) }.let {
                    it.eligible shouldBe true
                    it.hasResponded shouldBe false
                    it.canRespond shouldBe true
                }
                call(member = a) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[1].id)) }.let {
                    it.hasResponded shouldBe true
                    it.canRespond shouldBe false
                }
                call(member = a) { getPollParticipation(poll.id) }.hasResponded shouldBe true
                call(member = b) { getPollParticipation(poll.id) }.hasResponded shouldBe false
                call(member = b) { listPollParticipations(listOf(poll.id)) }.single().hasResponded shouldBe false
            }
        }

        test("canManage is a UI hint: creator and privileged members yes, a plain member no, nobody once the poll is closed") {
            pollTestApplication {
                val chair = data.chair()
                val admin = data.member(label = "admin", role = AccountRole.ADMIN)
                val plain = data.member(label = "plain")
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = chair) { getPoll(poll.id) }.canManage shouldBe true
                call(member = admin) { getPoll(poll.id) }.canManage shouldBe true
                call(member = plain) { getPoll(poll.id) }.canManage shouldBe false
                call(member = chair) { closePoll(poll.id) }.canManage shouldBe false
            }
        }

        test("PollDto never carries per-response data: the serialized form has no weight or respondent field") {
            pollTestApplication {
                val chair = data.chair()
                val voters = members(6, listOf("1.00", "2.00", "3.00", "4.00", "5.00", "6.00"))
                val poll: PollDto = createAndAnswer(creator = chair, answers = voters.map { it to 0 })
                val json =
                    kotlinx.serialization.json.Json
                        .encodeToString(PollDto.serializer(), call(member = chair) { closePoll(poll.id) })
                json.lowercase().let {
                    it shouldNotContain "weight"
                    it shouldNotContain "ltr"
                }
            }
        }
    })
