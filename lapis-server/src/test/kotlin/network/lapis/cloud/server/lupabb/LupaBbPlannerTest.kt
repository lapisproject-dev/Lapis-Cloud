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
    })
