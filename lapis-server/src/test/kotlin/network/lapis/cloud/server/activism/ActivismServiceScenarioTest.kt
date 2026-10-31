package network.lapis.cloud.server.activism

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismContributionInput
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismContributionsViewDto
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismListFilter
import network.lapis.cloud.shared.domain.ActivismReportStatus
import network.lapis.cloud.shared.domain.ActivismResultInput
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ActivismInvalidTransitionException
import network.lapis.cloud.shared.rpc.ActivismLimitReachedException
import network.lapis.cloud.shared.rpc.ActivismNotRunningException
import network.lapis.cloud.shared.rpc.ActivismReportOpenException
import network.lapis.cloud.shared.rpc.ActivismResponsibleLimitException
import network.lapis.cloud.shared.rpc.ActivismRevisionConflictException
import network.lapis.cloud.shared.rpc.ActivismSelfApprovalException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val JSON = Json { encodeDefaults = true }

/** V1.9.246 -- the activism service on H2 through a real RPC call context. */
class ActivismServiceScenarioTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipeActivism() }
        afterSpec {
            wipeActivism()
            fx.cleanUp()
        }

        fun member(
            name: String = "Aktions Testperson",
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ) = fx.member(status = status, role = role, displayName = name)

        test("lifecycle: idea by a member, planned by BOARD, running by the responsible person, contributions, completed, result") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val helper = member("Helfer")
            val clock = TestClock()
            withServiceCaller {
                val idea = asMember(who = creator, clock = clock) { it.createAction(activismInput(confirm = true)) }.getOrThrow()
                idea.status shouldBe ActivismStatus.IDEA
                idea.viewerRole shouldBe ActivismViewerRole.RESPONSIBLE
                // A plain member does not see an idea; the creator (responsible) does.
                asMember(who = helper, clock = clock) { it.getAction(idea.id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                // The responsible person cannot plan an idea (BOARD / ADMIN only) -- with the review switch off.
                asMember(who = creator, clock = clock) { it.setActionStatus(id = idea.id, target = ActivismStatus.PLANNED) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismInvalidTransitionException>()
                asMember(
                    who = board,
                    clock = clock,
                ) { it.setActionStatus(id = idea.id, target = ActivismStatus.PLANNED) }.getOrThrow().status shouldBe
                    ActivismStatus.PLANNED
                // Now visible to every participant, but not editable.
                asMember(who = helper, clock = clock) { it.getAction(idea.id) }.getOrThrow().viewerRole shouldBe ActivismViewerRole.MEMBER
                // Contributions only while RUNNING.
                asMember(
                    who = helper,
                    clock = clock,
                ) { it.reportContribution(actionId = idea.id, input = ActivismContributionInput(quantity = 40)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismNotRunningException>()
                asMember(
                    who = creator,
                    clock = clock,
                ) { it.setActionStatus(id = idea.id, target = ActivismStatus.RUNNING) }.getOrThrow().status shouldBe
                    ActivismStatus.RUNNING
                val c1 =
                    asMember(who = helper, clock = clock) {
                        it.reportContribution(actionId = idea.id, input = ActivismContributionInput(quantity = 40, area = "Nord"))
                    }.getOrThrow()
                val c2 =
                    asMember(who = helper, clock = clock) {
                        it.reportContribution(actionId = idea.id, input = ActivismContributionInput(quantity = 10, area = "Nord"))
                    }.getOrThrow()
                c1.status shouldBe ActivismContributionStatus.REPORTED
                c1.memberId.shouldBeNull()
                asMember(
                    who = helper,
                    clock = clock,
                ) {
                    it.decideContribution(contributionId = c1.id, confirm = true, reason = null)
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asMember(who = creator, clock = clock) {
                    it.decideContribution(contributionId = c1.id, confirm = true, reason = null)
                }.getOrThrow().status shouldBe
                    ActivismContributionStatus.CONFIRMED
                asMember(who = creator, clock = clock) {
                    it.decideContribution(contributionId = c2.id, confirm = false, reason = "doppelt")
                }.getOrThrow().status shouldBe
                    ActivismContributionStatus.REJECTED
                // A second decision is refused.
                asMember(
                    who = creator,
                    clock = clock,
                ) {
                    it.decideContribution(
                        contributionId = c1.id,
                        confirm = false,
                        reason = "x",
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                val running = asMember(who = helper, clock = clock) { it.getAction(idea.id) }.getOrThrow()
                running.progressReported shouldBe 40
                running.progressConfirmed shouldBe 40
                // Result only when COMPLETED.
                asMember(
                    who = creator,
                    clock = clock,
                ) { it.recordResult(id = idea.id, input = ActivismResultInput(participants = 7, quantity = 40)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismNotRunningException>()
                asMember(who = creator, clock = clock) { it.setActionStatus(id = idea.id, target = ActivismStatus.COMPLETED) }.getOrThrow()
                val done =
                    asMember(who = creator, clock = clock) {
                        it.recordResult(id = idea.id, input = ActivismResultInput(participants = 7, quantity = 40, text = "Gut gelaufen"))
                    }.getOrThrow()
                done.result.shouldNotBeNull().participants shouldBe 7
                // COMPLETED back to RUNNING is a manager decision.
                asMember(who = creator, clock = clock) { it.setActionStatus(id = idea.id, target = ActivismStatus.RUNNING) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismInvalidTransitionException>()
                asMember(
                    who = board,
                    clock = clock,
                ) { it.setActionStatus(id = idea.id, target = ActivismStatus.RUNNING) }.getOrThrow().status shouldBe
                    ActivismStatus.RUNNING
            }
        }

        test("an unknown and an invisible action answer byte-identically") {
            val creator = member()
            val other = member()
            withServiceCaller {
                val idea = asMember(who = creator) { it.createAction(activismInput()) }.getOrThrow()
                val invisible = asMember(who = other) { it.getAction(idea.id) }.exceptionOrNull().shouldNotBeNull()
                val unknown = asMember(who = other) { it.getAction(Uuid.random().toString()) }.exceptionOrNull().shouldNotBeNull()
                invisible::class shouldBe unknown::class
                invisible.message shouldBe unknown.message
                // The same for the writing methods of a participant.
                val reportInvisible =
                    asMember(
                        who = other,
                    ) { it.reportAction(actionId = idea.id, reason = "Das ist eine Meldung.") }.exceptionOrNull()
                val reportUnknown =
                    asMember(who = other) {
                        it.reportAction(actionId = Uuid.random().toString(), reason = "Das ist eine Meldung.")
                    }.exceptionOrNull()
                reportInvisible.shouldBeInstanceOf<NotFoundException>().message shouldBe
                    reportUnknown.shouldBeInstanceOf<NotFoundException>().message
                asMember(
                    who = other,
                ) { it.setSignal(actionId = idea.id, on = true) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                // And the list does not show it.
                asMember(who = other) { it.listActions(ActivismListFilter(statuses = ActivismStatus.entries)) }.getOrThrow().shouldBeEmpty()
                asMember(
                    who = creator,
                ) { it.listActions(ActivismListFilter(statuses = ActivismStatus.entries)) }.getOrThrow() shouldHaveSize
                    1
            }
        }

        test("FRIEND, GUEST and APPLICATION are refused everywhere") {
            val friend = member(status = MemberStatus.FRIEND)
            val guest = member(status = MemberStatus.GUEST)
            val applicant = member(status = MemberStatus.APPLICATION)
            val admin = member(role = AccountRole.ADMIN)
            withServiceCaller {
                val action = asMember(who = admin) { it.createAction(activismInput()) }.getOrThrow()
                for (who in listOf(friend, guest, applicant)) {
                    asMember(who = who) { it.listActions(ActivismListFilter()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.getAction(action.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.createAction(activismInput()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(
                        who = who,
                    ) { it.setSignal(actionId = action.id, on = true) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(
                        who = who,
                    ) {
                        it.reportAction(
                            actionId = action.id,
                            reason = "Eine Meldung mit Grund.",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.listReports() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.getNoticeSettings() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("manager-only operations are refused for MEMBER, RESPONSIBLE and TREASURER") {
            val creator = member("Ersteller")
            val treasurer = member("Kassenwart", AccountRole.TREASURER)
            val board = member("Vorstand", AccountRole.BOARD)
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput()) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                for (who in listOf(creator, treasurer)) {
                    asMember(
                        who = who,
                    ) {
                        it.setResponsibles(
                            id = a.id,
                            memberIds = listOf(who.toString()),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) {
                        it.setPlanningLinks(id = a.id, costCenterId = null, eventId = null)
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(
                        who = who,
                    ) {
                        it.withdrawAction(
                            id = a.id,
                            reason = "Das ist ein ausreichender Grund.",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.restoreAction(a.id) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.listReports() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.getNoticeSettings() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(
                        who = who,
                    ) {
                        it.setNoticeText(
                            kind = ActivismKind.POSTERING,
                            body = "x",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.setNoticeCountry("AT") }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(who = who) { it.setReviewRequired(true) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asMember(
                        who = who,
                    ) { it.resolveReport(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                // A plain member cannot edit a foreign action; the treasurer is a plain member for the module.
                asMember(
                    who = treasurer,
                ) {
                    it.updateAction(id = a.id, input = activismInput(title = "Neu"), expectedRevision = 0)
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asMember(who = treasurer) {
                    it.addTask(
                        actionId = a.id,
                        input =
                            network.lapis.cloud.shared.domain
                                .ActivismTaskInput(text = "Aufgabe"),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                asMember(who = treasurer) { it.listContributions(a.id) }.getOrThrow().all.shouldBeNull()
            }
        }

        test("review switch: submit, approve by someone else, self-approval refused, reject with reason") {
            val creator = member("Ersteller")
            val boardA = member("Vorstand A", AccountRole.BOARD)
            val boardB = member("Vorstand B", AccountRole.BOARD)
            withServiceCaller {
                asMember(who = boardA) { it.setReviewRequired(true) }.getOrThrow()
                // With the switch on, the direct way is closed even for a manager.
                val mine = asMember(who = boardA) { it.createAction(activismInput(title = "Von A")) }.getOrThrow()
                asMember(who = boardA) {
                    it.setActionStatus(id = mine.id, target = ActivismStatus.PLANNED)
                }.exceptionOrNull().shouldBeInstanceOf<ActivismInvalidTransitionException>()
                asMember(who = boardA) { it.submitForReview(mine.id) }.getOrThrow().status shouldBe ActivismStatus.AWAITING_REVIEW
                asMember(who = boardA) { it.approveAction(mine.id) }.exceptionOrNull().shouldBeInstanceOf<ActivismSelfApprovalException>()
                asMember(who = boardB) { it.approveAction(mine.id) }.getOrThrow().status shouldBe ActivismStatus.PLANNED

                val idea = asMember(who = creator) { it.createAction(activismInput(title = "Von Ersteller")) }.getOrThrow()
                asMember(who = creator) { it.submitForReview(idea.id) }.getOrThrow().status shouldBe ActivismStatus.AWAITING_REVIEW
                // Awaiting review is hidden from plain members and cannot be approved by the creator (not a manager).
                asMember(
                    who = creator,
                ) { it.approveAction(idea.id) }.exceptionOrNull().shouldBeInstanceOf<ActivismInvalidTransitionException>()
                val rejected = asMember(who = boardA) { it.rejectReview(id = idea.id, reason = "Bitte Ort ergänzen") }.getOrThrow()
                rejected.status shouldBe ActivismStatus.IDEA
                rejected.reviewRejectReason shouldBe "Bitte Ort ergänzen"
                asMember(who = boardA) { it.setReviewRequired(false) }.getOrThrow()
                asMember(
                    who = creator,
                ) { it.submitForReview(idea.id) }.exceptionOrNull().shouldBeInstanceOf<ActivismInvalidTransitionException>()
            }
        }

        test("an action needs a form before PLANNED; update checks the revision; the responsible count is 1 to 10") {
            val creator = member()
            val board = member("Vorstand", AccountRole.BOARD)
            val others = (1..10).map { member("Person $it") }
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(progress = false)) }.getOrThrow()
                asMember(
                    who = board,
                ) {
                    it.setActionStatus(
                        id = a.id,
                        target = ActivismStatus.PLANNED,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                val updated =
                    asMember(
                        who = creator,
                    ) {
                        it.updateAction(
                            id = a.id,
                            input = activismInput(title = "Neuer Titel", progress = true),
                            expectedRevision = a.revision,
                        )
                    }.getOrThrow()
                updated.title shouldBe "Neuer Titel"
                updated.revision shouldBe a.revision + 1
                asMember(who = creator) {
                    it.updateAction(id = a.id, input = activismInput(title = "Noch einer"), expectedRevision = a.revision)
                }.exceptionOrNull().shouldBeInstanceOf<ActivismRevisionConflictException>()
                asMember(
                    who = board,
                ) {
                    it.setResponsibles(
                        id = a.id,
                        memberIds = emptyList(),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ActivismResponsibleLimitException>()
                asMember(who = board) { it.setResponsibles(id = a.id, memberIds = (others + creator).map { id -> id.toString() }) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismResponsibleLimitException>()
                asMember(who = board) {
                    it.setResponsibles(
                        id = a.id,
                        memberIds =
                            others.take(3).map { id ->
                                id.toString()
                            },
                    )
                }.getOrThrow().responsibles.shouldNotBeNull() shouldHaveSize
                    3
                // The creator is no longer responsible and the idea is invisible to them now.
                asMember(who = creator) { it.getAction(a.id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("an update cannot remove the last form of a PLANNED action") {
            val creator = member()
            val board = member("Vorstand", AccountRole.BOARD)
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(progress = true)) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                val current = asMember(who = creator) { it.getAction(a.id) }.getOrThrow()
                asMember(who = creator) {
                    it.updateAction(id = a.id, input = activismInput(progress = false), expectedRevision = current.revision)
                }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
            }
        }

        test("contributions: own edit within 24 hours, then closed; rejected ones do not count; the 31st within a day is refused") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val helper = member("Helfer")
            val clock = TestClock()
            withServiceCaller {
                val a = asMember(who = creator, clock = clock) { it.createAction(activismInput()) }.getOrThrow()
                asMember(who = board, clock = clock) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = creator, clock = clock) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                val c =
                    asMember(
                        who = helper,
                        clock = clock,
                    ) { it.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 5)) }.getOrThrow()
                c.editable shouldBe true
                clock.now = ACTIVISM_NOW.plusHours(23)
                asMember(
                    who = helper,
                    clock = clock,
                ) {
                    it.updateOwnContribution(
                        contributionId = c.id,
                        input = ActivismContributionInput(quantity = 6),
                    )
                }.getOrThrow().quantity shouldBe
                    6
                clock.now = ACTIVISM_NOW.plusHours(25)
                asMember(
                    who = helper,
                    clock = clock,
                ) { it.updateOwnContribution(contributionId = c.id, input = ActivismContributionInput(quantity = 7)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                asMember(
                    who = helper,
                    clock = clock,
                ) { it.withdrawOwnContribution(c.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                // Somebody else's contribution looks like an unknown one.
                asMember(
                    who = board,
                    clock = clock,
                ) { it.updateOwnContribution(contributionId = c.id, input = ActivismContributionInput(quantity = 7)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                // 30 per member and action within the rolling 24 hours (one is already 25 h old and does not count).
                clock.now = ACTIVISM_NOW.plusHours(26)
                val limiter =
                    ActivismRateLimiter(
                        participation =
                            network.lapis.cloud.server.federation
                                .FederationInboxRateLimiter(maxRequests = 1000),
                    )
                repeat(30) {
                    asMember(
                        who = helper,
                        limiter = limiter,
                        clock = clock,
                    ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 1)) }.getOrThrow()
                }
                asMember(
                    who = helper,
                    limiter = limiter,
                    clock = clock,
                ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 1)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismLimitReachedException>()
                // Another member is not affected.
                asMember(
                    who = creator,
                    limiter = limiter,
                    clock = clock,
                ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 1)) }.getOrThrow()
                // Withdraw works inside the window, the quantity is validated.
                val fresh =
                    asMember(
                        who = creator,
                        limiter = limiter,
                        clock = clock,
                    ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 2)) }.getOrThrow()
                asMember(who = creator, limiter = limiter, clock = clock) { s -> s.withdrawOwnContribution(fresh.id) }.getOrThrow()
                asMember(
                    who = creator,
                    limiter = limiter,
                    clock = clock,
                ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 0)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                asMember(
                    who = creator,
                    limiter = limiter,
                    clock = clock,
                ) { s -> s.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 10_001)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
            }
        }

        test("what a plain member gets from listContributions contains no foreign name and no foreign id; managers see them") {
            val creator = member("Ersteller Eins")
            val board = member("Vorstand Zwei", AccountRole.BOARD)
            val helperA = member("Helferin Alpha")
            val helperB = member("Helfer Bravo")
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput()) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                asMember(who = helperA) {
                    it.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 11, area = "Süd"))
                }.getOrThrow()
                asMember(who = helperB) {
                    it.reportContribution(
                        actionId = a.id,
                        input = ActivismContributionInput(quantity = 22, area = "Süd", note = "geheime Notiz"),
                    )
                }.getOrThrow()
                val view = asMember(who = helperA) { it.listContributions(a.id) }.getOrThrow()
                view.own shouldHaveSize 1
                view.all.shouldBeNull()
                view.areaSums.single().sum shouldBe 33
                val json = JSON.encodeToString(ActivismContributionsViewDto.serializer(), view)
                json shouldNotContain "Bravo"
                json shouldNotContain helperB.toString()
                json shouldNotContain "geheime Notiz"
                val managerView = asMember(who = board) { it.listContributions(a.id) }.getOrThrow()
                managerView.all.shouldNotBeNull() shouldHaveSize 2
                JSON.encodeToString(ActivismContributionsViewDto.serializer(), managerView) shouldContain "Bravo"
            }
        }

        test("signals: below 5 no number, from 5 the number, revoking works, no read returns the people behind the signals") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val admin = member("Admin Person", AccountRole.ADMIN)
            val signalers = (1..5).map { member("Signalgeber $it") }
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(progress = false, signal = true)) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = signalers[0]) {
                    it.setSignal(actionId = a.id, on = true)
                }.exceptionOrNull().shouldBeInstanceOf<ActivismNotRunningException>()
                asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                for (i in 0..3) {
                    val state = asMember(who = signalers[i]) { it.setSignal(actionId = a.id, on = true) }.getOrThrow()
                    state.countAtLeast5.shouldBeNull()
                    state.mine shouldBe true
                }
                // Idempotent.
                asMember(who = signalers[0]) { it.setSignal(actionId = a.id, on = true) }.getOrThrow().countAtLeast5.shouldBeNull()
                asMember(who = signalers[4]) { it.setSignal(actionId = a.id, on = true) }.getOrThrow().countAtLeast5 shouldBe 5
                // The others see the number but not the persons; nobody sees ids.
                for (reader in listOf(creator, board, admin, signalers[0])) {
                    val detail = asMember(who = reader) { it.getAction(a.id) }.getOrThrow()
                    detail.signal.countAtLeast5 shouldBe 5
                    val json = JSON.encodeToString(ActivismActionDetailDto.serializer(), detail)
                    signalers.forEach { s -> json shouldNotContain s.toString() }
                    signalers.forEach { s -> json shouldNotContain "Signalgeber" }
                }
                asMember(who = signalers[4]) { it.setSignal(actionId = a.id, on = false) }.getOrThrow().countAtLeast5.shouldBeNull()
                asMember(who = admin) { it.getAction(a.id) }.getOrThrow().signal.mine shouldBe false
                // Revoking a signal is allowed after the action ended; setting one is not.
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.COMPLETED) }.getOrThrow()
                asMember(who = signalers[0]) { it.setSignal(actionId = a.id, on = false) }.getOrThrow().mine shouldBe false
                asMember(who = signalers[0]) {
                    it.setSignal(actionId = a.id, on = true)
                }.exceptionOrNull().shouldBeInstanceOf<ActivismNotRunningException>()
            }
            // Signals never reach the audit log.
            transaction {
                AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityType eq AuditEntityType.ACTIVISM_ACTION }.count()
            }.shouldNotBeNull()
        }

        test("a member leaving the organisation lowers the signal count at once") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val signalers = (1..5).map { member("Gehender $it") }
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(progress = false, signal = true)) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                signalers.forEach { s -> asMember(who = s) { it.setSignal(actionId = a.id, on = true) }.getOrThrow() }
                asMember(who = creator) { it.getAction(a.id) }.getOrThrow().signal.countAtLeast5 shouldBe 5
                transaction { MemberTable.update({ MemberTable.id eq signalers[0] }) { it[status] = MemberStatus.WITHDRAWN } }
                asMember(who = creator) { it.getAction(a.id) }
                    .getOrThrow()
                    .signal.countAtLeast5
                    .shouldBeNull()
            }
        }

        test("reports: one open per member and action, 5 per day, withdrawing closes them, the list has no reporter") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val reporter = member("Melderin Geheim")
            val clock = TestClock()
            withServiceCaller {
                val actions =
                    (1..6).map { n ->
                        val a = asMember(who = creator, clock = clock) { it.createAction(activismInput(title = "Aktion $n")) }.getOrThrow()
                        asMember(who = board, clock = clock) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                        a
                    }
                val limiter =
                    ActivismRateLimiter(
                        reports =
                            network.lapis.cloud.server.federation
                                .FederationInboxRateLimiter(maxRequests = 1000),
                    )
                asMember(
                    who = reporter,
                    limiter = limiter,
                    clock = clock,
                ) { it.reportAction(actionId = actions[0].id, reason = "kurz") }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asMember(who = reporter, limiter = limiter, clock = clock) {
                    it.reportAction(actionId = actions[0].id, reason = "Das gehört hier nicht hin.")
                }.getOrThrow()
                asMember(
                    who = reporter,
                    limiter = limiter,
                    clock = clock,
                ) { it.reportAction(actionId = actions[0].id, reason = "Noch einmal dasselbe.") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismReportOpenException>()
                for (i in 1..4) {
                    asMember(who = reporter, limiter = limiter, clock = clock) {
                        it.reportAction(actionId = actions[i].id, reason = "Grund Nummer $i hier.")
                    }.getOrThrow()
                }
                asMember(
                    who = reporter,
                    limiter = limiter,
                    clock = clock,
                ) { it.reportAction(actionId = actions[5].id, reason = "Der sechste Grund hier.") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ActivismLimitReachedException>()
                clock.now = ACTIVISM_NOW.plusHours(25)
                asMember(who = reporter, limiter = limiter, clock = clock) {
                    it.reportAction(actionId = actions[5].id, reason = "Der sechste Grund hier.")
                }.getOrThrow()

                val reports = asMember(who = board, clock = clock) { it.listReports() }.getOrThrow()
                reports shouldHaveSize 6
                JSON
                    .encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            network.lapis.cloud.shared.domain.ActivismReportDto
                                .serializer(),
                        ),
                        reports,
                    ).let { json ->
                        json shouldNotContain reporter.toString()
                        json shouldNotContain "Melderin"
                    }
                asMember(who = board, clock = clock) { it.resolveReport(reports[0].id) }.getOrThrow()
                asMember(who = board, clock = clock) { it.listReports() }.getOrThrow() shouldHaveSize 5
                // Withdrawing closes the open reports of that action and turns it into a 404 for plain members.
                val target = actions[1]
                asMember(
                    who = board,
                    clock = clock,
                ) { it.withdrawAction(id = target.id, reason = "kurz") }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asMember(who = board, clock = clock) {
                    it.withdrawAction(id = target.id, reason = "Verstößt gegen die Regeln.")
                }.getOrThrow().status shouldBe
                    ActivismStatus.WITHDRAWN
                transaction {
                    ActivismReportTable.selectAll().where { ActivismReportTable.actionId eq Uuid.parse(target.id) }.all {
                        it[ActivismReportTable.status] == ActivismReportStatus.RESOLVED
                    }
                } shouldBe true
                asMember(who = reporter, limiter = limiter, clock = clock) {
                    it.getAction(target.id)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                asMember(who = creator, clock = clock) { it.getAction(target.id) }.getOrThrow().withdrawnReason shouldBe
                    "Verstößt gegen die Regeln."
                asMember(who = board, clock = clock) { it.restoreAction(target.id) }.getOrThrow().status shouldBe ActivismStatus.IDEA
            }
        }

        test("texts are stored and returned as plain text; overlong or bidi input is cleaned or refused") {
            val creator = member()
            withServiceCaller {
                val evil = "<script>alert(1)</script> ‮bidi"
                val a =
                    asMember(
                        who = creator,
                    ) { it.createAction(activismInput(title = evil).copy(description = "<img src=x onerror=alert(1)>")) }.getOrThrow()
                a.title shouldBe "<script>alert(1)</script> bidi"
                a.description shouldBe "<img src=x onerror=alert(1)>"
                asMember(
                    who = creator,
                ) { it.createAction(activismInput(title = "x".repeat(121))) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asMember(
                    who = creator,
                ) { it.createAction(activismInput(title = "   ")) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asMember(
                    who = creator,
                ) { it.createAction(activismInput(goal = 0)) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asMember(
                    who = creator,
                ) { it.createAction(activismInput(goal = 1_000_001)) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
            }
        }

        test("checklist, text blocks and notice texts: limits, ordering, reset to the standard") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val member2 = member("Aufgabenperson")
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(kind = ActivismKind.INFO_STAND)) }.getOrThrow()
                val t1 =
                    asMember(
                        who = creator,
                    ) {
                        it.addTask(
                            actionId = a.id,
                            input =
                                network.lapis.cloud.shared.domain
                                    .ActivismTaskInput(text = "Tisch aufbauen"),
                        )
                    }.getOrThrow()
                val t2 =
                    asMember(who = creator) {
                        it.addTask(
                            actionId = a.id,
                            input =
                                network.lapis.cloud.shared.domain
                                    .ActivismTaskInput(text = "Flyer holen", assigneeMemberId = member2.toString()),
                        )
                    }.getOrThrow()
                val t3 =
                    asMember(
                        who = creator,
                    ) {
                        it.addTask(
                            actionId = a.id,
                            input =
                                network.lapis.cloud.shared.domain
                                    .ActivismTaskInput(text = "Aufräumen"),
                        )
                    }.getOrThrow()
                asMember(who = creator) { it.moveTask(taskId = t3.id, newPosition = 1) }.getOrThrow()
                asMember(who = creator) { it.getAction(a.id) }.getOrThrow().tasks.map { it.text } shouldBe
                    listOf("Aufräumen", "Tisch aufbauen", "Flyer holen")
                asMember(who = creator) { it.getAction(a.id) }.getOrThrow().tasks.map { it.position } shouldBe listOf(1, 2, 3)
                // Only the assignee (not responsible, not yet able to see the idea) cannot tick: the idea is invisible; after planning they can.
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = member2) { it.setTaskDone(taskId = t2.id, done = true) }.getOrThrow().done shouldBe true
                asMember(
                    who = member2,
                ) { it.setTaskDone(taskId = t1.id, done = true) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asMember(who = creator) { it.deleteTask(t1.id) }.getOrThrow()
                val block =
                    asMember(
                        who = creator,
                    ) { it.addTextBlock(actionId = a.id, title = "Begrüßung", body = "Hallo\nzusammen") }.getOrThrow()
                block.body shouldBe "Hallo\nzusammen"
                asMember(
                    who = creator,
                ) { it.updateTextBlock(blockId = block.id, title = "Begrüßung 2", body = "Neu") }.getOrThrow().title shouldBe
                    "Begrüßung 2"
                asMember(who = creator) { it.deleteTextBlock(block.id) }.getOrThrow()
                // Notice text: default (null) until the operator writes one; reset restores the standard.
                asMember(who = member2) { it.getAction(a.id) }.getOrThrow().noticeText.shouldBeNull()
                asMember(who = board) { it.setNoticeText(kind = ActivismKind.INFO_STAND, body = "Anmeldepflicht prüfen") }.getOrThrow()
                asMember(who = member2) { it.getAction(a.id) }.getOrThrow().noticeText shouldBe "Anmeldepflicht prüfen"
                asMember(who = board) { it.getNoticeSettings() }.getOrThrow().texts[ActivismKind.INFO_STAND] shouldBe
                    "Anmeldepflicht prüfen"
                asMember(who = board) { it.setNoticeText(kind = ActivismKind.INFO_STAND, body = null) }.getOrThrow()
                asMember(who = member2) { it.getAction(a.id) }.getOrThrow().noticeText.shouldBeNull()
                asMember(who = board) { it.setNoticeCountry("at") }.getOrThrow()
                asMember(who = board) { it.getNoticeSettings() }.getOrThrow().country shouldBe "AT"
                asMember(who = board) { it.setNoticeCountry("Deutschland") }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
            }
        }

        test("audited writes are throttled: the third call within the window is answered with ServiceBusy") {
            val creator = member()
            val board = member(role = AccountRole.BOARD)
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(signal = true)) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()

                fun tight() =
                    ActivismRateLimiter(
                        planning =
                            network.lapis.cloud.server.federation
                                .FederationInboxRateLimiter(maxRequests = 2),
                    )
                val flip = tight()
                repeat(2) { n ->
                    val target = if (n == 0) ActivismStatus.RUNNING else ActivismStatus.PLANNED
                    asMember(who = creator, limiter = flip) { it.setActionStatus(id = a.id, target = target) }.getOrThrow()
                }
                asMember(who = creator, limiter = flip) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ServiceBusyException>()
                // Every other audited writer is throttled by the same planning bucket (the check runs before any lookup).
                val other = tight()
                repeat(2) { asMember(who = creator, limiter = other) { s -> s.submitForReview(a.id) } }
                asMember(
                    who = creator,
                    limiter = other,
                ) { it.submitForReview(a.id) }.exceptionOrNull().shouldBeInstanceOf<ServiceBusyException>()
                asMember(who = creator, limiter = other) { it.recordResult(id = a.id, input = ActivismResultInput(text = "x")) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ServiceBusyException>()
                asMember(
                    who = creator,
                    limiter = other,
                ) { it.decideContribution(contributionId = Uuid.random().toString(), confirm = true, reason = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ServiceBusyException>()
                // Another member has an own bucket.
                asMember(who = board, limiter = other) { it.rejectReview(id = a.id, reason = "nein") }
                    .exceptionOrNull()
                    .shouldNotBeInstanceOf<ServiceBusyException>()
            }
        }

        test("candidate search: a plain participant does not find members who opted out or are blocked; a manager still does") {
            val seeker = member(name = "Suchender Sucher")
            val hidden = member(name = "Zzverborgen Person")
            val visible = member(name = "Zzsichtbar Person")
            val board = member(role = AccountRole.BOARD)
            transaction { MemberTable.update({ MemberTable.id eq hidden }) { it[dmDiscoverable] = false } }
            withServiceCaller {
                asMember(who = seeker) { it.createAction(activismInput()) }.getOrThrow()
                val found = asMember(who = seeker) { it.listActivismCandidates("zzs") }.getOrThrow()
                found.map { it.memberId } shouldBe listOf(visible.toString())
                asMember(who = seeker) { it.listActivismCandidates("zzv") }.getOrThrow().shouldBeEmpty()
                asMember(who = board) { it.listActivismCandidates("zzv") }.getOrThrow().map { it.memberId } shouldBe
                    listOf(hidden.toString())
                // LIKE wildcards are literals, not a match-all.
                asMember(who = board) { it.listActivismCandidates("zz%") }.getOrThrow().shouldBeEmpty()
            }
        }

        test("listContributions: sums come from SQL over all rows, a plain member gets only own rows and no full list") {
            val creator = member()
            val helper = member()
            val board = member(role = AccountRole.BOARD)
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput()) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                asMember(
                    who = helper,
                ) { it.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 3)) }.getOrThrow()
                asMember(
                    who = creator,
                ) { it.reportContribution(actionId = a.id, input = ActivismContributionInput(quantity = 4)) }.getOrThrow()
                val plain = asMember(who = helper) { it.listContributions(a.id) }.getOrThrow()
                plain.own shouldHaveSize 1
                plain.all.shouldBeNull()
                plain.areaSums.sumOf { it.sum } shouldBe 7
                val manager = asMember(who = board) { it.listContributions(a.id) }.getOrThrow()
                manager.all.shouldNotBeNull() shouldHaveSize 2
                manager.areaSums.sumOf { it.sum } shouldBe 7
            }
        }

        test("audit entries carry no free text; the creation of a report and any signal are not audited") {
            val creator = member("Ersteller")
            val board = member("Vorstand", AccountRole.BOARD)
            val other = member("Anderer")
            val reportsBefore =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            AuditLogEntryTable.entityType eq
                                AuditEntityType.ACTIVISM_REPORT
                        }.count()
                }
            withServiceCaller {
                val a = asMember(who = creator) { it.createAction(activismInput(title = "Geheimer Titel XYZ", signal = true)) }.getOrThrow()
                asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                asMember(who = other) { it.setSignal(actionId = a.id, on = true) }.getOrThrow()
                asMember(who = other) { it.reportAction(actionId = a.id, reason = "Geheimer Meldegrund ABC") }.getOrThrow()
                asMember(who = board) { it.setNoticeText(kind = ActivismKind.POSTERING, body = "Geheimer Hinweis QRS") }.getOrThrow()
                asMember(who = board) { it.withdrawAction(id = a.id, reason = "Geheimer Rückzugsgrund TUV") }.getOrThrow()
            }
            val types =
                listOf(
                    AuditEntityType.ACTIVISM_ACTION,
                    AuditEntityType.ACTIVISM_SETTINGS,
                    AuditEntityType.ACTIVISM_REPORT,
                    AuditEntityType.ACTIVISM_CONTRIBUTION,
                )
            val rows = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityType inList types }.toList() }
            rows.shouldNotBeNull()
            val all = rows.joinToString("\n") { "${it[AuditLogEntryTable.entityType]} ${it[AuditLogEntryTable.afterSnapshot]}" }
            all shouldNotContain "Geheim"
            all shouldContain "WITHDRAWN"
            rows.count { it[AuditLogEntryTable.entityType] == AuditEntityType.ACTIVISM_REPORT }.toLong() shouldBe reportsBefore
        }
    })

private fun LocalDateTime.plusHours(hours: Int): LocalDateTime = (toInstant(TimeZone.UTC) + hours.hours).toLocalDateTime(TimeZone.UTC)
