package network.lapis.cloud.server.social

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private fun LocalDateTime.minusHours(hours: Long): LocalDateTime = (toInstant(TimeZone.UTC) - hours.hours).toLocalDateTime(TimeZone.UTC)

private fun LocalDateTime.minusMinutes(minutes: Long): LocalDateTime =
    (toInstant(TimeZone.UTC) - minutes.minutes).toLocalDateTime(TimeZone.UTC)

/**
 * Welle V1.8.2b (MINOR-3) -- [PostDraftRetention.deleteDueRows] boundary coverage, rein
 * datengetrieben über einen fest übergebenen `now`, same "no wall-clock dependency" posture as
 * `ContributionReliefRedactionTest`. Rows are inserted directly (not via [PostDraftStore]) so
 * `status_changed_at`/`updated_at` can be pinned exactly at each test's boundary.
 */
class PostDraftRetentionTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val fixedNow = LocalDateTime(2028, 1, 1, 0, 0, 0)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                McpPostDraftTable.deleteWhere { memberId inList createdMemberIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Retention Testmitglied"
                    it[email] = "post-draft-retention-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertDraft(
            memberId: Uuid,
            status: McpPostDraftStatus,
            statusChangedAt: LocalDateTime?,
            updatedAt: LocalDateTime,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                McpPostDraftTable.insert {
                    it[McpPostDraftTable.id] = id
                    it[McpPostDraftTable.memberId] = memberId
                    it[tokenId] = null
                    it[agentLabel] = "Retention Test Agent"
                    it[content] = if (status == McpPostDraftStatus.RELEASED) "" else "Testinhalt"
                    it[visibility] = SocialPostVisibility.PUBLIC
                    it[McpPostDraftTable.status] = status
                    it[createdAt] = updatedAt
                    it[McpPostDraftTable.updatedAt] = updatedAt
                    it[McpPostDraftTable.statusChangedAt] = statusChangedAt
                    it[releasedPostId] = null
                }
            }
            return id
        }

        fun stillExists(id: Uuid): Boolean = transaction { McpPostDraftTable.selectAll().where { McpPostDraftTable.id eq id }.count() > 0L }

        test("DISCARDED at 6d23h old is kept; at 7d1min old it is deleted") {
            val memberId = newMember()
            val kept =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.DISCARDED,
                    statusChangedAt = fixedNow.minusHours(6 * 24 + 23),
                    updatedAt = fixedNow,
                )
            val deleted =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.DISCARDED,
                    statusChangedAt = fixedNow.minusMinutes(7 * 24 * 60 + 1),
                    updatedAt = fixedNow,
                )

            PostDraftRetention.deleteDueRows(fixedNow)

            stillExists(kept) shouldBe true
            stillExists(deleted) shouldBe false
        }

        test("RELEASED at 89 days old is kept; at 91 days old it is deleted") {
            val memberId = newMember()
            val kept =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.RELEASED,
                    statusChangedAt = fixedNow.minusHours(89 * 24),
                    updatedAt = fixedNow,
                )
            val deleted =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.RELEASED,
                    statusChangedAt = fixedNow.minusHours(91 * 24),
                    updatedAt = fixedNow,
                )

            PostDraftRetention.deleteDueRows(fixedNow)

            stillExists(kept) shouldBe true
            stillExists(deleted) shouldBe false
        }

        test("OPEN is never deleted, even 400 days old") {
            val memberId = newMember()
            val ancient =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.OPEN,
                    statusChangedAt = null,
                    updatedAt = fixedNow.minusHours(400 * 24),
                )

            PostDraftRetention.deleteDueRows(fixedNow)

            stillExists(ancient) shouldBe true
        }

        test("a DISCARDED row with statusChangedAt = NULL falls back to updatedAt") {
            val memberId = newMember()
            val deleted =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.DISCARDED,
                    statusChangedAt = null,
                    updatedAt = fixedNow.minusHours(30 * 24),
                )

            val (deletedDiscarded, _) = PostDraftRetention.deleteDueRows(fixedNow)

            deletedDiscarded shouldBe 1
            stillExists(deleted) shouldBe false
        }

        // Review fix (V1.8.2b follow-up): the two tests below cover the DoS-Deckel/backlog
        // interaction that the pre-fix version got wrong -- the cutoff predicate was evaluated in
        // Kotlin AFTER an unfiltered `LIMIT`, so a status with more not-yet-due rows than the cap
        // could make the `LIMIT`ed subset consist entirely of rows that are not actually due,
        // silently deleting nothing forever regardless of how many rows genuinely were due. Both
        // tests call [PostDraftRetention.deleteDueForStatus] directly with a small `limit` --
        // exercising the real production cap (5,000) would mean inserting 5,001+ rows per test, see
        // that function's own KDoc for why the cap itself cannot simply be shrunk to a test-friendly
        // constant the way `AccountingExportPoller.MAX_ITEMS_PER_TICK` is.
        //
        // Both start with a sweep at a huge `limit` and the SAME `cutoff = fixedNow` the assertions
        // below use: every earlier boundary test above deliberately leaves its "kept" row behind
        // (never deleted -- that IS what "kept" means, e.g. the 6d23h-old DISCARDED row or the
        // 89-day-old RELEASED row), and every one of those is far older than `fixedNow` itself, so
        // each is "due" under this narrower, same-day cutoff even though none of them were due under
        // its OWN test's real 7-/90-day retention window. Left in place, such a leftover row would be
        // the OLDEST due row of its status once this test's own `orderBy(statusChangedAt ASC)`-driven
        // deletes start -- silently "stealing" a slot from a `limit` this small and making the
        // assertions below fail on cross-test pollution instead of the actual cap/backlog mechanism.

        test("far more not-yet-due rows than the cap do not crowd out a genuinely due one") {
            PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.DISCARDED, cutoff = fixedNow, limit = 1000)
            val memberId = newMember()
            val due =
                insertDraft(
                    memberId = memberId,
                    status = McpPostDraftStatus.DISCARDED,
                    statusChangedAt = fixedNow.minusHours(1),
                    updatedAt = fixedNow.minusHours(1),
                )
            val notDue =
                (1..10).map {
                    insertDraft(
                        memberId = memberId,
                        status = McpPostDraftStatus.DISCARDED,
                        statusChangedAt = fixedNow,
                        updatedAt = fixedNow,
                    )
                }

            // cutoff == fixedNow: `due` (statusChangedAt 1h before cutoff) is due, all ten `notDue`
            // rows (statusChangedAt == cutoff, not strictly before it) are not -- with a `limit` of 1,
            // the pre-fix version's unfiltered-then-Kotlin-filtered `LIMIT` could easily have grabbed
            // one of the ten `notDue` rows instead and deleted nothing at all.
            val deletedCount = PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.DISCARDED, cutoff = fixedNow, limit = 1)

            deletedCount shouldBe 1
            stillExists(due) shouldBe false
            notDue.forEach { stillExists(it) shouldBe true }
        }

        test("the cap bounds a single call, and the remainder is cleared over repeated calls (backlog progress)") {
            PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.RELEASED, cutoff = fixedNow, limit = 1000)
            val memberId = newMember()
            val dueRows =
                (1..5).map { i ->
                    insertDraft(
                        memberId = memberId,
                        status = McpPostDraftStatus.RELEASED,
                        statusChangedAt = fixedNow.minusHours(i.toLong()),
                        updatedAt = fixedNow.minusHours(i.toLong()),
                    )
                }

            val firstCall = PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.RELEASED, cutoff = fixedNow, limit = 2)
            firstCall shouldBe 2
            dueRows.count { stillExists(it) } shouldBe 3

            val secondCall = PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.RELEASED, cutoff = fixedNow, limit = 2)
            secondCall shouldBe 2
            dueRows.count { stillExists(it) } shouldBe 1

            // Backlog fully cleared on the third call -- same "any excess is picked up by the next
            // daily tick" guarantee this class's KDoc makes, just exercised across three calls
            // instead of one real day each.
            val thirdCall = PostDraftRetention.deleteDueForStatus(status = McpPostDraftStatus.RELEASED, cutoff = fixedNow, limit = 2)
            thirdCall shouldBe 1
            dueRows.forEach { stillExists(it) shouldBe false }
        }
    })
