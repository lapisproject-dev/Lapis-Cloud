package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rules.RuleReferences
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- who belongs to a rule-based committee: every block, ALL/ANY, the guard (ACTIVE, not anonymised, not deceased), the
 * parity of the set form ([eligibleMemberIds]) and the point form ([isCommitteeEligible]) over all three branches, the fail-closed handling
 * of a broken stored rule and the reference counter. Creates its own rows only; every rule is isolated from the dev seed's members by an
 * `ALL` over a manual test committee.
 */
class RuleCommitteeEligibilityTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val mailingListIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction { MailingListTable.deleteWhere { MailingListTable.id inList mailingListIds } }
            rc.cleanUp()
            fx.cleanUp()
        }

        fun members(committee: Uuid): Set<Uuid> =
            transaction {
                eligibleMemberIds(committeeRow = fx.committeeRow(committee), scheduledDate = LocalDate(2026, 6, 1))
            }

        /** A manual committee seating [count] fresh ACTIVE members; returns the committee and the members. */
        fun seatedGroup(
            count: Int,
            role: AccountRole? = AccountRole.MEMBER,
        ): Pair<Uuid, List<Uuid>> {
            val manual = fx.committee()
            val people = (1..count).map { fx.member(role = role) }
            people.forEach { fx.seat(committee = manual, member = it) }
            return manual to people
        }

        test("a rule selects exactly the seated members it describes: seat, role, join date, ALL and ANY") {
            val (manual, people) = seatedGroup(3, role = AccountRole.MEMBER)
            val (a, b, c) = people
            transaction { AccountTable.update({ AccountTable.memberId eq b }) { it[role] = AccountRole.TREASURER } }
            transaction { MemberTable.update({ MemberTable.id eq c }) { it[joinedAt] = rc.today.minus(10, DateTimeUnit.DAY) } }

            members(rc.ruleCommittee(ruleJson = rc.seatedIn(manual))) shouldContainExactlyInAnyOrder listOf(a, b, c)
            members(
                rc.ruleCommittee(
                    ruleJson =
                        rc.rule(
                            op = RuleOp.ALL,
                            RuleCondition.Committee(setOf(manual.toString())),
                            RuleCondition.Role(setOf(AccountRole.TREASURER)),
                        ),
                ),
            ) shouldContainExactlyInAnyOrder listOf(b)
            members(
                rc.ruleCommittee(
                    ruleJson =
                        rc.rule(
                            op = RuleOp.ALL,
                            RuleCondition.Committee(setOf(manual.toString())),
                            RuleCondition.JoinedWithinDays(30),
                        ),
                ),
            ) shouldContainExactlyInAnyOrder listOf(c)
            // ANY over two blocks of the same isolated group: the seat block alone already selects all three
            val any =
                rc.ruleCommittee(
                    ruleJson =
                        rc.rule(
                            op = RuleOp.ANY,
                            RuleCondition.Committee(setOf(manual.toString())),
                            RuleCondition.JoinedWithinDays(1),
                        ),
                )
            members(any).containsAll(listOf(a, b, c)) shouldBe true
            // ALL [] selects every ACTIVE member -- at least the three
            members(rc.ruleCommittee(ruleJson = rc.rule(op = RuleOp.ALL))).containsAll(listOf(a, b, c)) shouldBe true
        }

        test("a seat that ended in the past, or has not started yet, does not count for the Committee block") {
            val manual = fx.committee()
            val current = fx.member()
            val ended = fx.member()
            val future = fx.member()
            fx.seat(committee = manual, member = current)
            fx.seat(committee = manual, member = ended, until = rc.today.minus(1, DateTimeUnit.DAY))
            fx.seat(committee = manual, member = future, since = LocalDate(2999, 1, 1))
            members(rc.ruleCommittee(ruleJson = rc.seatedIn(manual))) shouldContainExactlyInAnyOrder listOf(current)
        }

        test("a status change takes the member out of the committee and back in (live, no stored copy)") {
            val (manual, people) = seatedGroup(2)
            val rule = rc.ruleCommittee(ruleJson = rc.seatedIn(manual))
            members(rule) shouldContainExactlyInAnyOrder people
            transaction { MemberTable.update({ MemberTable.id eq people[0] }) { it[status] = MemberStatus.WITHDRAWN } }
            members(rule) shouldContainExactlyInAnyOrder listOf(people[1])
            transaction { MemberTable.update({ MemberTable.id eq people[0] }) { it[status] = MemberStatus.ACTIVE } }
            members(rule) shouldContainExactlyInAnyOrder people
        }

        test("the guard: only ACTIVE members; applicants, guests, friends, donors, deceased and anonymised members never belong") {
            val manual = fx.committee()
            val ok = fx.member()
            val others =
                MemberStatus.entries.filter { it != MemberStatus.ACTIVE }.associateWith { fx.member(status = it) }
            val anonymised = fx.member()
            (others.values + anonymised + ok).forEach { fx.seat(committee = manual, member = it) }
            transaction {
                MemberTable.update({ MemberTable.id eq anonymised }) { it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0) }
            }
            members(rc.ruleCommittee(ruleJson = rc.seatedIn(manual))) shouldContainExactlyInAnyOrder listOf(ok)
        }

        test("a stored rule that no longer parses is a Conflict, never an empty (quorum-met) set") {
            val (manual, people) = seatedGroup(1)
            val broken = rc.ruleCommittee(ruleJson = rc.seatedIn(manual))
            transaction {
                CommitteeTable.update({ CommitteeTable.id eq broken }) {
                    it[ruleJson] =
                        """{"v":1,"op":"ALL","conditions":[{"type":"BOGUS"}]}"""
                }
            }
            shouldThrow<ConflictException> { members(broken) }
            shouldThrow<ConflictException> {
                transaction {
                    isCommitteeEligible(committeeRow = fx.committeeRow(broken), scheduledDate = rc.today, memberId = people.single())
                }
            }
            shouldThrow<ConflictException> { transaction { countEligible(committeeRow = fx.committeeRow(broken)) } }
        }

        test("parity: the set form and the point form agree for every member, on the General Assembly, a manual and a rule committee") {
            val (manual, people) = seatedGroup(3)
            val outsider = fx.member()
            transaction { MemberTable.update({ MemberTable.id eq people[2] }) { it[status] = MemberStatus.WITHDRAWN } }
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            val rule = rc.ruleCommittee(ruleJson = rc.seatedIn(manual))
            val probe = people + outsider
            listOf(assembly, manual, rule).forEach { committee ->
                val row = fx.committeeRow(committee)
                val set = transaction { eligibleMemberIds(committeeRow = row, scheduledDate = rc.today) }
                probe.forEach { member ->
                    val point = transaction { isCommitteeEligible(committeeRow = row, scheduledDate = rc.today, memberId = member) }
                    point shouldBe (member in set)
                }
            }
            // N of the rule committee is the very size of the set
            transaction { countEligible(committeeRow = fx.committeeRow(rule)) } shouldBe members(rule).size
        }

        test("the reference counter parses stored rules: a rule committee and a dynamic mailing list count, a text coincidence does not") {
            val target = fx.committee()
            val other = fx.committee()
            val author = fx.member()
            rc.ruleCommittee(ruleJson = rc.seatedIn(target))
            rc.ruleCommittee(ruleJson = rc.seatedIn(other))
            val listId = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[id] = listId
                    it[name] = "Regel-Referenz ${Uuid.random()}"
                    it[createdBy] = author
                    it[kind] = MailingListKind.DYNAMIC
                    it[ruleJson] = MemberRuleCodec.encode(MemberRuleCodec.parse(text = rc.seatedIn(target), context = RuleContext.MAILING))
                    it[mandatory] = false
                }
            }
            mailingListIds += listId
            transaction { RuleReferences.countReferencesTo(target) } shouldBe 2
            transaction { RuleReferences.countReferencesTo(other) } shouldBe 1
            // a stored rule that does not parse references nothing in effect (evaluation fails closed), and an unknown id is referenced by nobody
            val unreadable = rc.ruleCommittee(ruleJson = rc.seatedIn(other))
            transaction {
                CommitteeTable.update({ CommitteeTable.id eq unreadable }) {
                    it[ruleJson] =
                        """{"v":1,"op":"ALL","conditions":[{"type":"X"}]}"""
                }
            }
            transaction { RuleReferences.countReferencesTo(other) } shouldBe 1
            transaction { RuleReferences.countReferencesTo(Uuid.random()) } shouldBe 0
        }
    })
