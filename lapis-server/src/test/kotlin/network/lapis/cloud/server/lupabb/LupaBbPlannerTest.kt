package network.lapis.cloud.server.lupabb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbNoticeCode
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.226 -- the comparison: matching rules, field hoheit, protected members, status rules, missing members. READ ONLY. */
class LupaBbPlannerTest :
    FunSpec({
        val fx = LupaBbFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterTest { fx.cleanUp() }

        fun unique() = "Testfeld${Uuid.random().toString().take(8)}"

        fun LupaBbPlan.reasons(n: Long) = row(n).reasons.map { it.code }

        test("a person number on a member finds that member; other differences show as a field list, never the e-mail") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Erika $family", personNumber = 9_000_000_001, email = "${TEST_EMAIL_PREFIX}a@example.org")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_001, family = family, email = "${TEST_EMAIL_PREFIX}other@example.org"),
                        ),
                    actor = admin,
                )
            val row = plan.row(9_000_000_001)
            row.category shouldBe LupaBbCategory.CHANGED
            row.diffs.map { it.field } shouldContain LupaBbField.STREET
            row.diffs.map { it.field } shouldNotContain LupaBbField.PERSON_NUMBER
            row.reasons.map { it.code } shouldContain LupaBbRowReasonCode.EMAIL_DIFFERS
            // the address is not a field of the comparison
            row.diffs.any { it.after == "${TEST_EMAIL_PREFIX}other@example.org" } shouldBe false
        }

        test("e-mail AND name equal, no person number yet: linked (both name orders), the link is a field difference") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}b1@example.org")
            fx.member(displayName = "$family, Anna", email = "${TEST_EMAIL_PREFIX}b2@example.org")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_002, family = family, email = "${TEST_EMAIL_PREFIX}b1@example.org"),
                            wireMember(
                                personNumber = 9_000_000_003,
                                given = "Anna",
                                family = family,
                                email = "${TEST_EMAIL_PREFIX}b2@example.org",
                            ),
                        ),
                    actor = admin,
                )
            for (n in listOf(9_000_000_002L, 9_000_000_003L)) {
                plan.row(n).category shouldBe LupaBbCategory.CHANGED
                plan.row(n).diffs.map { it.field } shouldContain LupaBbField.PERSON_NUMBER
                (plan.row(n).memberId != null) shouldBe true
            }
        }

        test("same e-mail, other name: ambiguous with the member as candidate") {
            val admin = fx.admin()
            val member = fx.member(displayName = "Ganz Anderer", email = "${TEST_EMAIL_PREFIX}c@example.org")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_004, family = unique(), email = "${TEST_EMAIL_PREFIX}c@example.org"),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_004).category shouldBe LupaBbCategory.AMBIGUOUS
            plan.reasons(9_000_000_004) shouldContain LupaBbRowReasonCode.NAME_MISMATCH
            plan.row(9_000_000_004).candidates.map { it.memberId } shouldContain member
        }

        test("two members of the same name and no e-mail match: ambiguous, at most five candidates") {
            val family = unique()
            val admin = fx.admin()
            repeat(7) { fx.member(displayName = "Erika $family") }
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_005, family = family)), actor = admin)
            plan.row(9_000_000_005).category shouldBe LupaBbCategory.AMBIGUOUS
            plan.reasons(9_000_000_005) shouldContain LupaBbRowReasonCode.MULTIPLE_CANDIDATES
            plan.row(9_000_000_005).candidates.size shouldBe LupaBbPlanner.MAX_CANDIDATES
        }

        test("the e-mail of a member that carries another person number: ambiguous, never taken over") {
            val admin = fx.admin()
            fx.member(displayName = "Jemand", email = "${TEST_EMAIL_PREFIX}d@example.org", personNumber = 9_000_000_099)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_006, family = unique(), email = "${TEST_EMAIL_PREFIX}d@example.org"),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_006).category shouldBe LupaBbCategory.AMBIGUOUS
            plan.reasons(9_000_000_006) shouldContain LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER
            // a linked member cannot be linked again: nothing to offer
            plan.row(9_000_000_006).candidates.shouldBeEmpty()
        }

        test("nobody matches: new; without an e-mail address skipped; with an invalid one an error") {
            val admin = fx.admin()
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_007, family = unique()),
                            wireMember(personNumber = 9_000_000_008, family = unique(), email = null),
                            wireMember(personNumber = 9_000_000_009, family = unique(), email = "kaputt"),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_007).category shouldBe LupaBbCategory.NEW
            plan.row(9_000_000_008).category shouldBe LupaBbCategory.SKIPPED
            plan.reasons(9_000_000_008) shouldContain LupaBbRowReasonCode.NO_EMAIL
            plan.row(9_000_000_009).category shouldBe LupaBbCategory.ERROR
            plan.reasons(9_000_000_009) shouldContain LupaBbRowReasonCode.INVALID_EMAIL
        }

        test("an anonymised member and an erased person (suppression list) are skipped") {
            val admin = fx.admin()
            fx.member(personNumber = 9_000_000_010, anonymized = true)
            LupaBbSuppression.configure(TEST_BOX)
            transaction { LupaBbSuppression.add(personNumber = 9_000_000_011, now = ServerClock.now()) }
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_010, family = unique()),
                            wireMember(personNumber = 9_000_000_011, family = unique()),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_010).category shouldBe LupaBbCategory.SKIPPED
            plan.reasons(9_000_000_010) shouldContain LupaBbRowReasonCode.ANONYMIZED
            plan.row(9_000_000_011).category shouldBe LupaBbCategory.SKIPPED
            plan.reasons(9_000_000_011) shouldContain LupaBbRowReasonCode.ERASED_IN_LAPIS
        }

        test("the suppression list stores an HMAC, not the person number") {
            LupaBbSuppression.configure(TEST_BOX)
            transaction { LupaBbSuppression.add(personNumber = 9_000_000_012, now = ServerClock.now()) }
            val stored = transaction { LupaBbSuppression.allHmacs() }
            stored shouldContain LupaBbSuppression.hmac(secretBox = TEST_BOX, personNumber = 9_000_000_012)
            stored.any { it.contains("9000000012") } shouldBe false
        }

        test("a member with a management role keeps its core fields: only the link, the profile and the missing hint are planned") {
            val family = unique()
            fx.admin()
            fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_013)
            fx.member(role = AccountRole.TREASURER, displayName = "Erika $family", personNumber = 9_000_000_014)
            val self = fx.member(role = AccountRole.MEMBER, displayName = "Erika $family", personNumber = 9_000_000_015)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_013, family = family, statusId = 6),
                            wireMember(personNumber = 9_000_000_014, family = family),
                            wireMember(personNumber = 9_000_000_015, family = family),
                        ),
                    actor = self,
                )
            for (n in listOf(9_000_000_013L, 9_000_000_014L, 9_000_000_015L)) {
                val row = plan.row(n)
                row.category shouldBe LupaBbCategory.CHANGED
                plan.reasons(n) shouldContain LupaBbRowReasonCode.ADMIN_ROLE_MANUAL
                row.diffs.map { it.field } shouldContain LupaBbField.GIVEN_NAME
                row.diffs.map { it.field } shouldNotContain LupaBbField.STREET
                row.diffs.map { it.field } shouldNotContain LupaBbField.STATUS
            }
            plan.row(9_000_000_013).statusTo shouldBe null
        }

        test("a management member that is not linked yet IS linked by the sync (person number), its core fields go to manual handling") {
            val family = unique()
            val admin = fx.admin()
            val board = fx.member(role = AccountRole.BOARD, displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}board@example.org")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_016, family = family, email = "${TEST_EMAIL_PREFIX}board@example.org"),
                        ),
                    actor = admin,
                )
            val row = plan.row(9_000_000_016)
            row.category shouldBe LupaBbCategory.CHANGED
            row.memberId shouldBe board
            row.diffs.map { it.field } shouldContain LupaBbField.PERSON_NUMBER
            row.diffs.map { it.field } shouldNotContain LupaBbField.STREET
            plan.reasons(9_000_000_016) shouldContain LupaBbRowReasonCode.ADMIN_ROLE_MANUAL
        }

        test(
            "the CSV-import reference is the person number: such a member is found even when e-mail and name differ, never created twice",
        ) {
            val admin = fx.admin()
            val imported =
                fx.member(displayName = "Voellig Anders", email = "${TEST_EMAIL_PREFIX}ref@example.org", externalReference = "9000000060")
            val plan =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_060, family = unique())), actor = admin)
            val row = plan.row(9_000_000_060)
            row.category shouldBe LupaBbCategory.CHANGED
            row.memberId shouldBe imported
            row.diffs.map { it.field } shouldContain LupaBbField.PERSON_NUMBER
        }

        test("two members with the same import reference: ambiguous, never a new member") {
            val admin = fx.admin()
            fx.member(displayName = "Eins", externalReference = "9000000061")
            fx.member(displayName = "Zwei", externalReference = " 9000000061 ")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_061, family = unique())), actor = admin)
            plan.row(9_000_000_061).category shouldBe LupaBbCategory.AMBIGUOUS
            plan.row(9_000_000_061).candidates.size shouldBe 2
        }

        test("status changes follow the transition table of the member administration") {
            val family = unique()
            val admin = fx.admin()
            fx.member(status = MemberStatus.ACTIVE, displayName = "Erika $family", personNumber = 9_000_000_020)
            fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_021)
            fx.member(status = MemberStatus.DECEASED, displayName = "Erika $family", personNumber = 9_000_000_022)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_020, statusId = 6, family = family),
                            wireMember(personNumber = 9_000_000_021, statusId = 1, family = family),
                            wireMember(personNumber = 9_000_000_022, statusId = 1, family = family),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_020).category shouldBe LupaBbCategory.CHANGED
            plan.row(9_000_000_020).statusTo shouldBe MemberStatus.WITHDRAWN
            plan.row(9_000_000_021).category shouldBe LupaBbCategory.ERROR
            plan.reasons(9_000_000_021) shouldContain LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            plan.row(9_000_000_022).category shouldBe LupaBbCategory.CHANGED
            plan.reasons(9_000_000_022) shouldContain LupaBbRowReasonCode.LEAVES_DECEASED
        }

        test(
            "a legacy FRIEND reported as Foerderer, Spender or Nur Zahler becomes DONOR (sync-only exception); other targets stay errors",
        ) {
            val family = unique()
            val admin = fx.admin()
            val numbers = listOf(9_000_000_320L, 9_000_000_321L, 9_000_000_322L, 9_000_000_323L)
            numbers.forEach { fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = it) }
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = numbers[0], statusId = 3, family = family),
                            wireMember(personNumber = numbers[1], statusId = 4, family = family),
                            wireMember(personNumber = numbers[2], statusId = 9, family = family),
                            wireMember(personNumber = numbers[3], statusId = 12, family = family),
                        ),
                    actor = admin,
                )
            numbers.take(3).forEach { n ->
                plan.row(n).category shouldBe LupaBbCategory.CHANGED
                plan.row(n).statusTo shouldBe MemberStatus.DONOR
                plan.row(n).diffs.single { it.field == LupaBbField.STATUS }.let {
                    it.before shouldBe MemberStatus.FRIEND.name
                    it.after shouldBe MemberStatus.DONOR.name
                }
            }
            plan.row(numbers[3]).category shouldBe LupaBbCategory.ERROR
            plan.reasons(numbers[3]) shouldContain LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
        }

        test("a self-registered FRIEND without a person number matched by e-mail and name never becomes DONOR") {
            val family = unique()
            val admin = fx.admin()
            val numbers = listOf(9_000_000_070L, 9_000_000_071L, 9_000_000_072L)
            numbers.indices.forEach { i ->
                fx.member(
                    status = MemberStatus.FRIEND,
                    displayName = "Erika $family$i",
                    email = "${TEST_EMAIL_PREFIX}selfreg$i@example.org",
                )
            }
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            *numbers
                                .mapIndexed { i, n ->
                                    wireMember(
                                        personNumber = n,
                                        statusId = listOf(3L, 4L, 9L)[i],
                                        family = "$family$i",
                                        email = "${TEST_EMAIL_PREFIX}selfreg$i@example.org",
                                    )
                                }.toTypedArray(),
                        ),
                    actor = admin,
                )
            numbers.forEach { n ->
                plan.row(n).category shouldBe LupaBbCategory.ERROR
                plan.reasons(n) shouldContain LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            }
        }

        test("a FRIEND with a management role reported as Foerderer stays a manual case without a status difference") {
            val family = unique()
            val admin = fx.admin()
            val roles = listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)
            roles.forEachIndexed { i, role ->
                fx.member(role = role, status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_330L + i)
            }
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            *roles.indices
                                .map {
                                    wireMember(
                                        personNumber = 9_000_000_330L + it,
                                        statusId = 3,
                                        family = family,
                                    )
                                }.toTypedArray(),
                        ),
                    actor = admin,
                )
            roles.indices.forEach { i ->
                val n = 9_000_000_330L + i
                plan.reasons(n) shouldContain LupaBbRowReasonCode.ADMIN_ROLE_MANUAL
                plan.row(n).diffs.none { it.field == LupaBbField.STATUS } shouldBe true
            }
        }

        test("an empty LupaBB value clears the Lapis field; an unresolvable country leaves it alone") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Erika $family", personNumber = 9_000_000_023, street = "Alte Strasse 5")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_023, family = family, street = null, houseNumber = null),
                            wireMember(personNumber = 9_000_000_024, family = unique(), countryId = 2),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_023).diffs.single { it.field == LupaBbField.STREET }.let {
                it.before shouldBe "Alte Strasse 5"
                it.after shouldBe null
            }
            plan.reasons(9_000_000_024).contains(LupaBbRowReasonCode.COUNTRY_AMBIGUOUS) shouldBe false
            plan.row(9_000_000_024).diffs.map { it.field } shouldNotContain LupaBbField.COUNTRY
        }

        test("regional chapter: assigned only where none is set; a label without a chapter is a remark; no association data is a notice") {
            val family = unique()
            val admin = fx.admin()
            val chapter = fx.chapter("Testland-Nord")
            val withChapter = fx.member(displayName = "Erika $family", personNumber = 9_000_000_025)
            val taken = fx.member(displayName = "Erika $family", personNumber = 9_000_000_026, chapterId = chapter)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_025, family = family, associationId = 5),
                            wireMember(personNumber = 9_000_000_026, family = family, associationId = 5),
                            wireMember(personNumber = 9_000_000_027, family = unique(), associationId = 6),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_025).chapterId shouldBe chapter
            plan.row(9_000_000_025).diffs.map { it.field } shouldContain LupaBbField.REGIONAL_CHAPTER
            plan.row(9_000_000_026).chapterId shouldBe null
            plan.reasons(9_000_000_027) shouldContain LupaBbRowReasonCode.CHAPTER_NOT_FOUND
            plan.notices.shouldBeEmpty()
            val without = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_025, family = family)), actor = admin)
            without.notices shouldContain LupaBbNoticeCode.CHAPTER_NOT_SUPPLIED
            withChapter.toString().isNotEmpty() shouldBe true
            taken.toString().isNotEmpty() shouldBe true
        }

        test("with the chapter duty on, a new ACTIVE member without a chapter is an error") {
            val admin = fx.admin()
            fx.chapter("Testland-Nord")
            val plan =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_028, family = unique())), actor = admin, enforced = true)
            plan.row(9_000_000_028).category shouldBe LupaBbCategory.ERROR
            plan.reasons(9_000_000_028) shouldContain LupaBbRowReasonCode.CHAPTER_REQUIRED
            val ok =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_028, family = unique(), associationId = 5)),
                    actor = admin,
                    enforced = true,
                )
            ok.row(9_000_000_028).category shouldBe LupaBbCategory.NEW
        }

        test("a linked member that LupaBB no longer lists is MISSING once; a reappearing one clears the flag") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Erika $family", personNumber = 9_000_000_029)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_030, family = unique())), actor = admin)
            plan.row(9_000_000_029).category shouldBe LupaBbCategory.MISSING
            plan
                .row(9_000_000_029)
                .diffs
                .single()
                .field shouldBe LupaBbField.MISSING_FLAG
        }

        test("an empty answer while members are linked is an error, never 'everybody left'") {
            val admin = fx.admin()
            fx.member(personNumber = 9_000_000_031)
            val e = shouldThrow<LupaBbFetchException> { planOf(dataset = datasetOf(), actor = admin) }
            e.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE
        }

        test("the planner writes nothing: the member rows are exactly as before") {
            val family = unique()
            val admin = fx.admin()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_032)
            val before = LupaBbMemberLoader.let { transaction { it.loadOne(id)!!.fingerprint() } }
            planOf(
                dataset = datasetOf(wireMember(personNumber = 9_000_000_032, family = family, street = "Neu", statusId = 6)),
                actor = admin,
            )
            transaction { LupaBbMemberLoader.loadOne(id)!!.fingerprint() } shouldBe before
        }

        test("a FRIEND linked by person number whose record says withdrawn or deceased is a status change, not an error") {
            val family = unique()
            val admin = fx.admin()
            fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_500)
            fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_501)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_500, family = family, statusId = 6),
                            wireMember(personNumber = 9_000_000_501, family = family, statusId = 5),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_500).category shouldBe LupaBbCategory.CHANGED
            plan.row(9_000_000_500).statusTo shouldBe MemberStatus.WITHDRAWN
            plan.row(9_000_000_501).category shouldBe LupaBbCategory.CHANGED
            plan.row(9_000_000_501).statusTo shouldBe MemberStatus.DECEASED
        }

        test("a FRIEND found by e-mail and name or by import reference stays an error for withdrawn or deceased") {
            val family = unique()
            val admin = fx.admin()
            fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}fr-a@example.org")
            fx.member(status = MemberStatus.FRIEND, displayName = "Anders", externalReference = "9000000503")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(
                                personNumber = 9_000_000_502,
                                family = family,
                                statusId = 6,
                                email = "${TEST_EMAIL_PREFIX}fr-a@example.org",
                            ),
                            wireMember(personNumber = 9_000_000_503, family = unique(), statusId = 5),
                        ),
                    actor = admin,
                )
            for (n in listOf(9_000_000_502L, 9_000_000_503L)) {
                plan.row(n).category shouldBe LupaBbCategory.ERROR
                plan.reasons(n) shouldContain LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            }
        }

        test("a held-back row carries everything a manual take-over would write, never the role, and appears in the action-needed filter") {
            val family = unique()
            val admin = fx.admin()
            val board =
                fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_504, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_504, family = family, statusId = 6)), actor = admin)
            val row = plan.row(9_000_000_504)
            row.category shouldBe LupaBbCategory.CHANGED
            row.memberId shouldBe board
            row.isOwnRecord shouldBe false
            row.protectedBlocker shouldBe null
            row.protectedDiffs.map { it.field } shouldContain LupaBbField.STREET
            row.protectedDiffs.map { it.field } shouldContain LupaBbField.STATUS
            row.diffs.map { it.field } shouldNotContain LupaBbField.STREET
            row.toDto().protectedDiffs.size shouldBe row.protectedDiffs.size
            row.toDto().memberId shouldBe board.toString()
            plan.rows
                .filtered(network.lapis.cloud.shared.domain.LupaBbPreviewFilter.ACTION_NEEDED)
                .map { it.personNumber } shouldContain 9_000_000_504L
            row.needsAction() shouldBe true
        }

        test("the blocker of a held-back row: own record, ADMIN target with a login-blocking status, donor with a management role") {
            val family = unique()
            val self = fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_505, street = "Alt")
            fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_506, street = "Alt")
            fx.member(role = AccountRole.TREASURER, displayName = "Erika $family", personNumber = 9_000_000_507, street = "Alt")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_505, family = family),
                            wireMember(personNumber = 9_000_000_506, family = family, statusId = 6),
                            wireMember(personNumber = 9_000_000_507, family = family, statusId = 4),
                        ),
                    actor = self,
                )
            plan.row(9_000_000_505).isOwnRecord shouldBe true
            plan.row(9_000_000_505).protectedBlocker shouldBe LupaBbRowReasonCode.OWN_RECORD
            plan.row(9_000_000_506).protectedBlocker shouldBe LupaBbRowReasonCode.PEER_APPROVAL_REQUIRED
            plan.row(9_000_000_507).protectedBlocker shouldBe LupaBbRowReasonCode.ROLE_BLOCKS_STATUS
        }

        test("the title is compared against member.title: a difference is a TITLE field, equal values (after trimming) are none") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Erika $family", personNumber = 9_000_000_061, title = "Prof.")
            fx.member(displayName = "Dr. Erika $family", personNumber = 9_000_000_062, title = "Dr.")
            fx.member(displayName = "Erika $family", personNumber = 9_000_000_063, title = "Dr.")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_061, family = family, title = " Dr. "),
                            wireMember(personNumber = 9_000_000_062, family = family, title = " Dr. "),
                            wireMember(personNumber = 9_000_000_063, family = family, title = null),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_061).diffs.single { it.field == LupaBbField.TITLE }.let {
                it.before shouldBe "Prof."
                it.after shouldBe "Dr."
            }
            plan.row(9_000_000_062).diffs.map { it.field } shouldNotContain LupaBbField.TITLE
            // V1.9.303: the display name never carries the title, and a legacy "<title> <name>" is tolerated: no DISPLAY_NAME difference
            plan.row(9_000_000_061).diffs.map { it.field } shouldNotContain LupaBbField.DISPLAY_NAME
            plan.row(9_000_000_062).diffs.map { it.field } shouldNotContain LupaBbField.DISPLAY_NAME
            // a title set by hand and none in LupaBB is a difference to empty (the sync's single-source rule), never silently kept
            plan
                .row(9_000_000_063)
                .diffs
                .single { it.field == LupaBbField.TITLE }
                .after shouldBe null
        }

        // ---- V1.9.303: the title is its own field ----

        fun LupaBbPlan.fields(n: Long) = row(n).diffs.map { it.field }

        test("V1.9.303 a legacy display name with the same title is unchanged: no display name and no title difference") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Dipl.-Des. Marco $family", personNumber = 9_000_000_301, title = "Dipl.-Des.")
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_301, given = "Marco", family = family, title = "Dipl.-Des.")),
                    actor = admin,
                )
            plan.fields(9_000_000_301) shouldNotContain LupaBbField.DISPLAY_NAME
            plan.fields(9_000_000_301) shouldNotContain LupaBbField.TITLE
        }

        test("V1.9.303 a changed title shows two rows: the display name without the title AND the new title") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Dr. Erika $family", personNumber = 9_000_000_302, title = "Dr.")
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_302, family = family, title = "Prof. Dr.")),
                    actor = admin,
                )
            plan.row(9_000_000_302).diffs.single { it.field == LupaBbField.DISPLAY_NAME }.let {
                it.before shouldBe "Dr. Erika $family"
                it.after shouldBe "Erika $family"
            }
            plan.row(9_000_000_302).diffs.single { it.field == LupaBbField.TITLE }.let {
                it.before shouldBe "Dr."
                it.after shouldBe "Prof. Dr."
            }
        }

        test("V1.9.303 a title only in the display name: just the TITLE difference, null to the title") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Dr. Erika $family", personNumber = 9_000_000_303, title = null)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_303, family = family, title = "Dr.")), actor = admin)
            plan.fields(9_000_000_303) shouldNotContain LupaBbField.DISPLAY_NAME
            plan.row(9_000_000_303).diffs.single { it.field == LupaBbField.TITLE }.let {
                it.before shouldBe null
                it.after shouldBe "Dr."
            }
        }

        test("V1.9.303 LupaBB without a title: a title in the Lapis display name is a difference (the tolerance does not apply)") {
            val family = unique()
            val admin = fx.admin()
            fx.member(displayName = "Dr. Erika $family", personNumber = 9_000_000_304, title = "Dr.")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_304, family = family, title = null)), actor = admin)
            plan
                .row(9_000_000_304)
                .diffs
                .single { it.field == LupaBbField.DISPLAY_NAME }
                .after shouldBe "Erika $family"
        }

        test("V1.9.303 a new member with a title: display name without it, the title as its own field") {
            val family = unique()
            val admin = fx.admin()
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_305, family = family, title = "Dr.")), actor = admin)
            plan.row(9_000_000_305).category shouldBe LupaBbCategory.NEW
            plan
                .row(9_000_000_305)
                .diffs
                .single { it.field == LupaBbField.DISPLAY_NAME }
                .after shouldBe "Erika $family"
            plan
                .row(9_000_000_305)
                .diffs
                .single { it.field == LupaBbField.TITLE }
                .after shouldBe "Dr."
        }

        test("V1.9.303 the candidate search finds a member whose name carries the title, also when LupaBB delivers the name without it") {
            val family = unique()
            val admin = fx.admin()
            val withTitle = fx.member(displayName = "Dipl.-Des. Marco $family", title = "Dipl.-Des.")
            val plain = fx.member(displayName = "Anna $family")
            val doctor = fx.member(displayName = "Dr. Erika $family")
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_306, given = "Marco", family = family, title = "Dipl.-Des."),
                            wireMember(personNumber = 9_000_000_307, given = "Anna", family = family, title = "Prof."),
                            wireMember(personNumber = 9_000_000_308, given = "Anna", family = family, title = null),
                            wireMember(personNumber = 9_000_000_309, given = "Erika", family = family, title = "Dr."),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_306).candidates.map { it.memberId } shouldContain withTitle
            plan.row(9_000_000_307).candidates.map { it.memberId } shouldContain plain
            plan.row(9_000_000_308).candidates.map { it.memberId } shouldContain plain
            plan.row(9_000_000_309).candidates.map { it.memberId } shouldContain doctor
        }

        test("V1.9.303 an e-mail of a linked member: the conflict names the member and its number, a likely double entry is flagged") {
            val family = unique()
            val admin = fx.admin()
            val holder =
                fx.member(
                    displayName = "Dr. Erika $family",
                    title = "Dr.",
                    email = "${TEST_EMAIL_PREFIX}dup@example.org",
                    personNumber = 9_000_000_399,
                )
            val other =
                fx.member(displayName = "Jemand Anderes", email = "${TEST_EMAIL_PREFIX}dup2@example.org", personNumber = 9_000_000_398)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_310, family = family, email = "${TEST_EMAIL_PREFIX}dup@example.org"),
                            wireMember(personNumber = 9_000_000_311, family = unique(), email = "${TEST_EMAIL_PREFIX}dup2@example.org"),
                        ),
                    actor = admin,
                )
            plan.row(9_000_000_310).let {
                it.conflictMemberId shouldBe holder
                it.conflictMemberName shouldBe "Dr. Erika $family"
                it.conflictPersonNumber shouldBe 9_000_000_399L
                it.conflictLikelyDuplicate shouldBe true
            }
            plan.row(9_000_000_311).let {
                it.conflictMemberId shouldBe other
                it.conflictMemberName shouldBe "Jemand Anderes"
                it.conflictPersonNumber shouldBe 9_000_000_398L
                it.conflictLikelyDuplicate shouldBe false
            }
        }

        test("V1.9.303 an e-mail of an anonymised member: no conflict details at all") {
            val admin = fx.admin()
            fx.member(displayName = "Anonym", email = "${TEST_EMAIL_PREFIX}anon@example.org", anonymized = true)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_312, family = unique(), email = "${TEST_EMAIL_PREFIX}anon@example.org"),
                        ),
                    actor = admin,
                )
            plan.reasons(9_000_000_312) shouldContain LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER
            plan.row(9_000_000_312).let {
                it.conflictMemberId shouldBe null
                it.conflictMemberName shouldBe null
                it.conflictPersonNumber shouldBe null
                it.conflictLikelyDuplicate shouldBe false
            }
        }

        test("V1.9.303 a management member with a legacy display name: the old spelling alone is no manual difference") {
            val family = unique()
            val admin = fx.admin()
            fx.member(role = AccountRole.BOARD, displayName = "Dr. Erika $family", title = "Dr.", personNumber = 9_000_000_313)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_313, family = family, title = "Dr.")), actor = admin)
            plan.row(9_000_000_313).protectedDiffs.map { it.field } shouldNotContain LupaBbField.DISPLAY_NAME
        }
    })
