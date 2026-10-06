package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.WebhookDeliveryTable
import network.lapis.cloud.server.member.CountPeriod
import network.lapis.cloud.server.member.MemberCountAggregation
import network.lapis.cloud.server.member.MemberStatusHistoryConsistency
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.member.StatusDelta
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.rpc.OptionTally
import network.lapis.cloud.server.rpc.computePollResult
import network.lapis.cloud.server.rpc.electionFiguresWithheld
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.63 -- the richer staging/demo dataset, checked on BOTH databases (H2 and a real PostgreSQL): the same assertions, because the
 * seed runs one big transaction full of row locks, the audit chain and Postgres-only failure modes (a poisoned transaction after a failed
 * statement) that H2 hides.
 *
 * The seed runs ONCE per spec (bcrypt, 100+ postings and audit entries are not free). Every scenario below only reads. The seed is aimed at a
 * private database (isolated H2, or the spec's own Postgres database) via `seedWith(database = ...)`; `module()` and
 * `DatabaseConfig.connect()` are never called on the Postgres side.
 */
abstract class StagingSeedScenarios(
    private val testDb: TestDatabase,
) : FunSpec({
        val seedPassword = "ein-starkes-testpasswort"
        // A test key, never a real one. 32 bytes = AES-256.
        val sepaKey = ByteArray(32) { it.toByte() }
        lateinit var db: Database

        beforeSpec {
            // Same system properties `main()` sets before the seed runs: the series expansion must not try to download time zones.
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")
            testDb.activate()
            db =
                when (testDb) {
                    is TestDatabase.Postgres -> testDb.db.database
                    TestDatabase.H2 -> IsolatedH2Database.create()
                }
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db, sepaKey = sepaKey)
        }
        afterSpec { testDb.deactivate() }
        installLaneGuards(db = testDb)

        fun <T> read(block: () -> T): T = transaction(db) { block() }

        fun orgZone(): TimeZone = read { TimeZone.of(OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.timezone]) }

        val assemblyElectionId = Uuid.parse("0000000a-0000-0000-0000-300000000041")
        val auditorsElectionId = Uuid.parse("0000000a-0000-0000-0000-300000000051")

        // ---- members ---------------------------------------------------------------------------------------------------------

        test(
            "members and identity: unique @staging.invalid addresses, placeholder organization, one admin/board/treasurer with a working hash",
        ) {
            read {
                val rows = MemberTable.selectAll().toList()
                rows.size shouldBe 40
                val emails = rows.map { it[MemberTable.email] }
                emails.forEach { it shouldEndWith "@${StagingSeedData.EMAIL_DOMAIN}" }
                emails.toSet().size shouldBe emails.size
                OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.name] shouldBe StagingSeedData.ORGANIZATION_NAME

                val accounts = AccountTable.selectAll().toList()
                accounts.count { it[AccountTable.role] == AccountRole.ADMIN } shouldBe 1
                accounts.count { it[AccountTable.role] == AccountRole.BOARD } shouldBe 1
                accounts.count { it[AccountTable.role] == AccountRole.TREASURER } shouldBe 1
                accounts.forEach { row ->
                    val hash = row[AccountTable.passwordHash].shouldNotBeNull()
                    PasswordHasher.verify(rawPassword = seedPassword, storedHash = hash) shouldBe true
                    PasswordHasher.verify(rawPassword = DevSeedData.DEMO_PASSWORD, storedHash = hash) shouldBe false
                }
                rows.map { it[MemberTable.status] }.toSet() shouldBe MemberStatus.entries.toSet()
            }
        }

        test("status history: consistent with member.status, strictly ascending per member, every row from the seed") {
            read {
                MemberStatusHistoryConsistency.countMismatches() shouldBe 0L
                val byMember = MemberStatusHistoryTable.selectAll().toList().groupBy { it[MemberStatusHistoryTable.memberId] }
                byMember.size shouldBe 40
                byMember.values.forEach { chain ->
                    val ordered = chain.sortedBy { it[MemberStatusHistoryTable.effectiveFrom] }
                    ordered.zipWithNext().forEach { (a, b) ->
                        b[MemberStatusHistoryTable.effectiveFrom] shouldBeGreaterThan a[MemberStatusHistoryTable.effectiveFrom]
                        b[MemberStatusHistoryTable.previousStatus] shouldBe a[MemberStatusHistoryTable.status]
                    }
                    ordered.first()[MemberStatusHistoryTable.previousStatus].shouldBeNull()
                }
                MemberStatusHistoryTable
                    .selectAll()
                    .map { it[MemberStatusHistoryTable.sourceKind] }
                    .toSet() shouldBe setOf(MemberStatusHistorySource.SEED.name)
            }
        }

        test(
            "member development: the statistics aggregation shows 24 monthly points with real growth, withdrawals and a history of 18+ months",
        ) {
            val zone = orgZone()
            val (periods, counts, earliest) =
                read {
                    val now = DbClock.nowLocalDateTime()
                    val today = ServerClock.todayIn(zone)
                    val from = LocalDate(today.year, today.monthNumber, 1).minus(23, DateTimeUnit.MONTH)
                    val periods: List<CountPeriod> =
                        MemberCountAggregation.periods(
                            from = from,
                            to = today,
                            granularity = MemberCountGranularity.MONTH,
                            orgZone = zone,
                            now = now,
                        )
                    val count = MemberStatusHistoryTable.memberId.count()
                    val deltas =
                        MemberStatusHistoryTable
                            .select(
                                MemberStatusHistoryTable.status,
                                MemberStatusHistoryTable.previousStatus,
                                MemberStatusHistoryTable.effectiveFrom,
                                count,
                            ).groupBy(
                                MemberStatusHistoryTable.status,
                                MemberStatusHistoryTable.previousStatus,
                                MemberStatusHistoryTable.effectiveFrom,
                            ).orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                            .map { row ->
                                StatusDelta(
                                    status = MemberStatus.valueOf(row[MemberStatusHistoryTable.status]),
                                    previous = row[MemberStatusHistoryTable.previousStatus]?.let { MemberStatus.valueOf(it) },
                                    effectiveFrom = row[MemberStatusHistoryTable.effectiveFrom],
                                    count = row[count],
                                )
                            }
                    val counts = MemberCountAggregation.countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
                    Triple(periods, counts, deltas.minOf { it.effectiveFrom })
                }
            periods.size shouldBeGreaterThanOrEqual 24
            // not tied to a calendar day (the zone boundary shifts single events): minimums and shape only
            val active = counts.map { it.getValue(MemberStatus.ACTIVE) }
            active.first() shouldBeLessThan active.last()
            active.toSet().size shouldBeGreaterThanOrEqual 6
            counts.last().getValue(MemberStatus.WITHDRAWN) shouldBeGreaterThanOrEqual 5
            counts.last().getValue(MemberStatus.DECEASED) shouldBe 1
            counts.last().getValue(MemberStatus.REJECTED) shouldBe 1
            counts.last().getValue(MemberStatus.APPLICATION) shouldBe 2
            // the earliest recorded instant is at least 18 months before the first period's end
            earliest shouldBeLessThan periods[5].boundary
        }

        // ---- elections -------------------------------------------------------------------------------------------------------

        test("closed election: TALLIED, figures disclosed (at least 5 ballots), 6/2/1, resolution ADOPTED and motion RESOLVED") {
            read {
                val election = ElectionTable.selectAll().where { ElectionTable.id eq assemblyElectionId }.single()
                election[ElectionTable.status] shouldBe ElectionStatus.TALLIED
                election[ElectionTable.secret] shouldBe true
                val ballots = ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq assemblyElectionId }.toList()
                ballots.size shouldBe 9
                electionFiguresWithheld(secret = true, ballotCount = ballots.size) shouldBe false
                ballots.size shouldBeGreaterThanOrEqual DisclosureRules.MIN_ANONYMOUS_RESPONSES

                val labelById =
                    ElectionOptionTable
                        .selectAll()
                        .where { ElectionOptionTable.electionId eq assemblyElectionId }
                        .associate { it[ElectionOptionTable.id] to it[ElectionOptionTable.label] }
                val perLabel =
                    ElectionBallotSelectionTable
                        .selectAll()
                        .filter { it[ElectionBallotSelectionTable.ballotId] in ballots.map { b -> b[ElectionBallotTable.id] } }
                        .groupingBy { labelById.getValue(it[ElectionBallotSelectionTable.optionId]) }
                        .eachCount()
                perLabel shouldBe mapOf("YES" to 6, "NO" to 2, "ABSTAIN" to 1)

                val resolution = ResolutionTable.selectAll().where { ResolutionTable.electionId eq assemblyElectionId }.single()
                resolution[ResolutionTable.status] shouldBe ResolutionStatus.ADOPTED
                resolution[ResolutionTable.resolutionMode] shouldBe ResolutionMode.DEMOCRATIC
                resolution[ResolutionTable.votesYes] shouldBe 6
                resolution[ResolutionTable.votesNo] shouldBe 2
                resolution[ResolutionTable.votesAbstain] shouldBe 1
                election[ElectionTable.resolutionId] shouldBe resolution[ResolutionTable.id]
                MotionTable.selectAll().where { MotionTable.id eq election[ElectionTable.motionId] }.single().let {
                    it[MotionTable.status] shouldBe MotionStatus.RESOLVED
                    it[MotionTable.resolutionId] shouldBe resolution[ResolutionTable.id]
                }
            }
        }

        test("running election: OPEN, 6 anonymous ballots, none from the three demo logins, nothing tallied") {
            read {
                val election = ElectionTable.selectAll().where { ElectionTable.id eq auditorsElectionId }.single()
                election[ElectionTable.status] shouldBe ElectionStatus.OPEN
                election[ElectionTable.resolutionId].shouldBeNull()
                ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq auditorsElectionId }.count() shouldBe 6L
                ElectionParticipationTable
                    .selectAll()
                    .where {
                        ElectionParticipationTable.electionId eq auditorsElectionId
                    }.count() shouldBe
                    6L

                val loginIds = AccountTable.selectAll().map { it[AccountTable.memberId] }.toSet()
                val roll =
                    ElectionEligibleVoterTable
                        .selectAll()
                        .where { ElectionEligibleVoterTable.electionId eq auditorsElectionId }
                        .map { it[ElectionEligibleVoterTable.memberId] }
                        .toSet()
                // the demo logins are eligible, they just have not voted yet
                roll.containsAll(loginIds) shouldBe true
                val participants =
                    ElectionParticipationTable
                        .selectAll()
                        .where { ElectionParticipationTable.electionId eq auditorsElectionId }
                        .map { it[ElectionParticipationTable.memberId] }
                        .toSet()
                participants.intersect(loginIds).shouldBeEmpty()
            }
        }

        test(
            "ballot secrecy: no ballot carries a member, cast_at is the constant voting_opened_at, the voters are exactly the participations",
        ) {
            read {
                listOf(assemblyElectionId, auditorsElectionId).forEach { electionId ->
                    val election = ElectionTable.selectAll().where { ElectionTable.id eq electionId }.single()
                    val opened = election[ElectionTable.votingOpenedAt].shouldNotBeNull()
                    val ballots = ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq electionId }.toList()
                    ballots.forEach {
                        it[ElectionBallotTable.memberId].shouldBeNull()
                        it[ElectionBallotTable.castAt] shouldBe opened
                    }
                    val participations =
                        ElectionParticipationTable.selectAll().where { ElectionParticipationTable.electionId eq electionId }.toList()
                    participations.size shouldBe ballots.size
                    // the participation time is NOT the ballot time: re-linking by timestamp must be impossible
                    participations.map { it[ElectionParticipationTable.votedAt] }.none { it == opened } shouldBe true
                    // ballot and receipt ids are random, never derived from a member id
                    val memberIds = MemberTable.selectAll().map { it[MemberTable.id] }.toSet()
                    ballots
                        .map { it[ElectionBallotTable.id] }
                        .toSet()
                        .intersect(memberIds)
                        .shouldBeEmpty()
                    // everybody who voted was on the electoral roll
                    val roll =
                        ElectionEligibleVoterTable
                            .selectAll()
                            .where { ElectionEligibleVoterTable.electionId eq electionId }
                            .map { it[ElectionEligibleVoterTable.memberId] }
                            .toSet()
                    roll.containsAll(participations.map { it[ElectionParticipationTable.memberId] }) shouldBe true
                }
            }
        }

        // ---- polls -----------------------------------------------------------------------------------------------------------

        test(
            "polls: one closed, one running, both with a disclosed head result, no member on a response, weighted result withheld without LTR data",
        ) {
            read {
                val polls = PollTable.selectAll().toList()
                polls.size shouldBe 2
                polls.map { it[PollTable.status] }.toSet() shouldBe setOf(PollStatus.CLOSED, PollStatus.OPEN)
                polls.forEach { poll ->
                    val pollId = poll[PollTable.id]
                    val responses = PollResponseTable.selectAll().where { PollResponseTable.pollId eq pollId }.toList()
                    val participations = PollParticipationTable.selectAll().where { PollParticipationTable.pollId eq pollId }.count()
                    responses.size.toLong() shouldBe participations
                    responses.size shouldBeGreaterThanOrEqual DisclosureRules.MIN_ANONYMOUS_RESPONSES
                    val options =
                        PollOptionTable
                            .selectAll()
                            .where { PollOptionTable.pollId eq pollId }
                            .orderBy(
                                PollOptionTable.position,
                            ).toList()
                    val tallies =
                        computePollResult(
                            pollId = pollId,
                            optionsInPositionOrder =
                                options.map { option ->
                                    val own = responses.filter { it[PollResponseTable.optionId] == option[PollOptionTable.id] }
                                    OptionTally(
                                        optionId = option[PollOptionTable.id],
                                        responses = own.size,
                                        weightedResponses = own.count { it[PollResponseTable.weightLtr].signum() > 0 },
                                        weightSum = own.fold(BigDecimal.ZERO) { acc, r -> acc + r[PollResponseTable.weightLtr] },
                                    )
                                },
                        )
                    tallies.headResultAvailable shouldBe true
                    tallies.weightedResultAvailable shouldBe false
                }
                // the running poll: no demo login has answered
                val running = polls.single { it[PollTable.status] == PollStatus.OPEN }
                val answered =
                    PollParticipationTable
                        .selectAll()
                        .where { PollParticipationTable.pollId eq running[PollTable.id] }
                        .map { it[PollParticipationTable.memberId] }
                        .toSet()
                answered.intersect(AccountTable.selectAll().map { it[AccountTable.memberId] }.toSet()).shouldBeEmpty()
            }
        }

        // ---- governance ------------------------------------------------------------------------------------------------------

        test(
            "committees, meetings and motions: every motion status is present, decided motions link to their resolution, held meetings have attendance",
        ) {
            read {
                MotionTable.selectAll().map { it[MotionTable.status] }.toSet() shouldBe MotionStatus.entries.toSet()
                MotionTable
                    .selectAll()
                    .filter {
                        it[MotionTable.status] in
                            setOf(MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.POSTPONED)
                    }.forEach {
                        it[MotionTable.resolutionId].shouldNotBeNull()
                    }
                MotionTable.selectAll().filter { it[MotionTable.status] == MotionStatus.SCHEDULED }.forEach {
                    it[MotionTable.meetingId].shouldNotBeNull()
                    it[MotionTable.agendaItemId].shouldNotBeNull()
                }
                CommitteeTable.selectAll().count() shouldBe 4L
                val meetingIds = MeetingTable.selectAll().map { it[MeetingTable.id] }.toSet()
                MotionTable.selectAll().mapNotNull { it[MotionTable.meetingId] }.forEach { (it in meetingIds) shouldBe true }
                val attended = AttendanceTable.selectAll().map { it[AttendanceTable.meetingId] }.toSet()
                MeetingTable
                    .selectAll()
                    .filter {
                        it[MeetingTable.title].startsWith("Vorstandssitzung Nr.") ||
                            it[MeetingTable.title] == "Ordentliche Mitgliederversammlung"
                    }.forEach { (it[MeetingTable.id] in attended) shouldBe true }
                ResolutionTable.selectAll().count() shouldBe 4L
            }
        }

        // ---- accounting ------------------------------------------------------------------------------------------------------

        test(
            "accounting: every paid contribution has exactly one balanced booking, the open items are booked, all contribution states are present",
        ) {
            read {
                val statuses = ContributionTable.selectAll().map { it[ContributionTable.status] }.toSet()
                statuses shouldContainAll
                    listOf(ContributionStatus.PAID, ContributionStatus.OPEN, ContributionStatus.OVERDUE, ContributionStatus.DEBIT_SCHEDULED)

                val entries = JournalEntryTable.selectAll().toList()
                val byVoucher = entries.groupBy { it[JournalEntryTable.voucherReference] }
                ContributionTable.selectAll().filter { it[ContributionTable.status] == ContributionStatus.PAID }.forEach { paid ->
                    byVoucher["CONTRIB-${paid[ContributionTable.id]}"].shouldNotBeNull().size shouldBe 1
                }

                val postings = PostingTable.selectAll().groupBy { it[PostingTable.journalEntryId] }
                entries.forEach { entry ->
                    val sides = postings.getValue(entry[JournalEntryTable.id])
                    val debit =
                        sides.filter { it[PostingTable.side] == PostingSide.DEBIT }.fold(BigDecimal.ZERO) { a, p ->
                            a +
                                p[PostingTable.amount]
                        }
                    val credit =
                        sides.filter { it[PostingTable.side] == PostingSide.CREDIT }.fold(BigDecimal.ZERO) { a, p ->
                            a +
                                p[PostingTable.amount]
                        }
                    debit.compareTo(credit) shouldBe 0
                }
                // no cash register can have been driven negative: the cash account is untouched by the seed
                val cashIds =
                    LedgerAccountTable
                        .selectAll()
                        .filter { it[LedgerAccountTable.isCashRegister] }
                        .map { it[LedgerAccountTable.id] }
                        .toSet()
                PostingTable.selectAll().none { it[PostingTable.ledgerAccountId] in cashIds } shouldBe true

                val items = OpenItemTable.selectAll().toList()
                items.size shouldBe 2
                items.forEach {
                    it[OpenItemTable.creationJournalEntryId].shouldNotBeNull()
                    it[OpenItemTable.creationPostingError].shouldBeNull()
                }
            }
        }

        test(
            "SEPA with a key: mandates decrypt to valid, never-issued IBANs, one DRAFT batch whose lines are DEBIT_SCHEDULED, no file, no submission",
        ) {
            read {
                OrganizationSettingsTable.selectAll().single().let {
                    it[OrganizationSettingsTable.sepaDebitEnabled] shouldBe true
                    it[OrganizationSettingsTable.sepaCreditorId].shouldNotBeNull()
                    it[OrganizationSettingsTable.bankIban].shouldBeNull()
                }
                val box = SecretBox(sepaKey)
                val mandates = SepaMandateTable.selectAll().toList()
                mandates.size shouldBe 8
                mandates.forEach { m ->
                    m[SepaMandateTable.status] shouldBe SepaMandateStatus.ACTIVE
                    val iban = box.open(sealed = m[SepaMandateTable.debtorIbanCiphertext], aad = m[SepaMandateTable.id].toString())
                    IbanValidator.requireValid(iban) shouldBe iban
                    iban.substring(4, 12) shouldBe "00000000"
                    iban.takeLast(4) shouldBe m[SepaMandateTable.debtorIbanLast4]
                }
                val batch = SepaDebitBatchTable.selectAll().single()
                batch[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.DRAFT
                batch[SepaDebitBatchTable.generatedAt].shouldBeNull()
                batch[SepaDebitBatchTable.generatedDocumentId].shouldBeNull()
                batch[SepaDebitBatchTable.submittedAt].shouldBeNull()
                val items = SepaDebitItemTable.selectAll().toList()
                items.size shouldBe batch[SepaDebitBatchTable.itemCount]
                items.forEach { item ->
                    ContributionTable.selectAll().where { ContributionTable.id eq item[SepaDebitItemTable.contributionId] }.single().let {
                        it[ContributionTable.status] shouldBe ContributionStatus.DEBIT_SCHEDULED
                    }
                }
            }
        }

        test("dunning: three levels, enabled, no letter by post, no notice issued, at least one overdue contribution to dunn") {
            read {
                DunningLevelTable.selectAll().count() shouldBe 3L
                OrganizationSettingsTable.selectAll().single().let {
                    it[OrganizationSettingsTable.dunningEnabled] shouldBe true
                    it[OrganizationSettingsTable.postalMailEnabled] shouldBe false
                }
                DunningNoticeTable.selectAll().count() shouldBe 0L
                ContributionTable.selectAll().count {
                    it[ContributionTable.status] == ContributionStatus.OVERDUE
                } shouldBeGreaterThanOrEqual
                    1
            }
        }

        // ---- community -------------------------------------------------------------------------------------------------------

        test(
            "community: chapters with officers and no crest, articles in three states, empty folder tree, a series of eight dates, future carpool dates",
        ) {
            val today = read { ServerClock.todayIn(orgZone()) }
            read {
                RegionalChapterTable.selectAll().count() shouldBe 3L
                RegionalChapterTable.selectAll().forEach { it[RegionalChapterTable.crestImageId].shouldBeNull() }
                RegionalChapterOfficerTable.selectAll().count() shouldBeGreaterThan 2L

                ArticleTable.selectAll().groupingBy { it[ArticleTable.status] }.eachCount().let {
                    it[network.lapis.cloud.shared.domain.ArticleStatus.PUBLISHED] shouldBe 3
                    it[network.lapis.cloud.shared.domain.ArticleStatus.SUBMITTED] shouldBe 1
                    it[network.lapis.cloud.shared.domain.ArticleStatus.DRAFT] shouldBe 1
                }
                DocumentFolderTable.selectAll().count() shouldBe 4L
                DocumentTable.selectAll().count() shouldBe 0L
                DocumentVersionTable.selectAll().count() shouldBe 0L

                val series = EventTable.selectAll().filter { it[EventTable.seriesId] != null }
                series.size shouldBe 8
                series.map { it[EventTable.seriesId] }.toSet().size shouldBe 1

                CarpoolPostingTable.selectAll().count() shouldBe 3L
                CarpoolPostingTable.selectAll().forEach { it[CarpoolPostingTable.departureDate] shouldBeGreaterThanOrEqualTo today }
            }
        }

        // ---- audit chain, idempotency, external effects ----------------------------------------------------------------------

        test("the audit hash chain is valid after the seed and its head matches the chain state") {
            read {
                val rows = AuditLogEntryTable.selectAll().orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC).toList()
                // the seed audited resolutions, polls, mandates, open items, levels, chapters ... (the baseline alone has none)
                rows.size shouldBeGreaterThan 100
                val verdict = AuditChainVerifier.verify(rows)
                verdict.valid shouldBe true
                verdict.checkedCount shouldBe rows.size
                val head = AuditLogChainStateTable.selectAll().single()
                head[AuditLogChainStateTable.lastSequenceNumber] shouldBe rows.last()[AuditLogEntryTable.sequenceNumber]
                head[AuditLogChainStateTable.lastEntryHash] shouldBe rows.last()[AuditLogEntryTable.entryHash]
            }
        }

        test("idempotent: a second seed run changes no row of any touched table and does not move the audit chain") {
            val before = StagingSeedProbe.snapshot(db)
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db, sepaKey = sepaKey)
            StagingSeedProbe.snapshot(db) shouldBe before
        }

        test("no external effects: no webhook delivery, no letter, no mailing log, no stored document or file") {
            read {
                WebhookDeliveryTable.selectAll().count() shouldBe 0L
                PostalDeliveryLogTable.selectAll().count() shouldBe 0L
                MailingDeliveryLogTable.selectAll().count() shouldBe 0L
                DocumentTable.selectAll().count() shouldBe 0L
                DocumentVersionTable.selectAll().count() shouldBe 0L
            }
        }
    })
