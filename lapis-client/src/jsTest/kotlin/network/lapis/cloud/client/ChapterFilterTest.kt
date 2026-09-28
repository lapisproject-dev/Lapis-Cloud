package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- pure, DOM-free coverage
 * for [ChapterFilter]/[ChapterFilter.toQueryFields]/[chapterFilterFromSelectValue]/[rosterQuery].
 * The core invariant these guard: `regionalChapterId` and `unassignedOnly` are NEVER both set at
 * once (the server throws `BadRequestException` if they are, see `MemberAdminQuery
 * .regionalChapterId` KDoc) -- [ChapterFilter] makes that state structurally unrepresentable.
 */
class ChapterFilterTest {
    @Test
    fun toQueryFields_all_setsNeitherField() {
        assertEquals(null to false, ChapterFilter.All.toQueryFields())
    }

    @Test
    fun toQueryFields_unassigned_setsOnlyUnassignedOnly() {
        assertEquals(null to true, ChapterFilter.Unassigned.toQueryFields())
    }

    @Test
    fun toQueryFields_chapter_setsOnlyTheChapterId() {
        assertEquals("c1" to false, ChapterFilter.Chapter("c1").toQueryFields())
    }

    @Test
    fun chapterFilterFromSelectValue_parsesTheThreeShapes() {
        assertEquals(ChapterFilter.All, chapterFilterFromSelectValue(""))
        assertEquals(ChapterFilter.All, chapterFilterFromSelectValue(null))
        assertEquals(ChapterFilter.Unassigned, chapterFilterFromSelectValue(CHAPTER_FILTER_UNASSIGNED_VALUE))
        assertEquals(
            ChapterFilter.Chapter("11111111-1111-1111-1111-111111111111"),
            chapterFilterFromSelectValue("11111111-1111-1111-1111-111111111111"),
        )
    }

    @Test
    fun rosterQuery_threadsTheChapterFilterThroughToTheQueryFields() {
        val allQuery = rosterQuery(RosterState(chapterFilter = ChapterFilter.All))
        assertNull(allQuery.regionalChapterId)
        assertEquals(false, allQuery.unassignedOnly)

        val unassignedQuery = rosterQuery(RosterState(chapterFilter = ChapterFilter.Unassigned))
        assertNull(unassignedQuery.regionalChapterId)
        assertEquals(true, unassignedQuery.unassignedOnly)

        val chapterQuery = rosterQuery(RosterState(chapterFilter = ChapterFilter.Chapter("c1")))
        assertEquals("c1", chapterQuery.regionalChapterId)
        assertEquals(false, chapterQuery.unassignedOnly)
    }

    @Test
    fun rosterQuery_alsoCarriesTheOtherFilterFields() {
        val state =
            RosterState(
                search = "  Meier  ",
                statuses = setOf(MemberStatus.ACTIVE),
                sort = MemberAdminSort.JOINED_DESC,
                offset = 25,
                chapterFilter = ChapterFilter.Chapter("c1"),
            )
        val query = rosterQuery(state)
        assertEquals("  Meier  ".ifBlank { null }, query.search)
        assertEquals(setOf(MemberStatus.ACTIVE), query.statuses)
        assertEquals(MemberAdminSort.JOINED_DESC, query.sort)
        assertEquals(25, query.offset)
        assertEquals("c1", query.regionalChapterId)
    }
}
