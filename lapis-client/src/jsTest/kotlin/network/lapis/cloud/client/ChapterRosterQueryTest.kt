package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberAdminSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- the client-side
 * "no-widening" anchor for [chapterRosterQuery]: table-driven over search/sort/offset, asserting
 * that `regionalChapterId`, `unassignedOnly` and `statuses` NEVER escape their fixed defaults
 * (`null`/`false`/empty). The actual security boundary is server-side
 * (`network.lapis.cloud.server.security.RegionalChapterVisibility`); this only guarantees the
 * CLIENT never even tries to widen the request -- see `ChapterRosterScreen.kt`'s own class KDoc.
 */
class ChapterRosterQueryTest {
    private val searches = listOf("", "  x ", "a".repeat(200), "!@#$%^&*()", "  ", "Müller-Lüdenscheidt")
    private val offsets = listOf(-5, 0, 25, 10_000)

    @Test
    fun everyCombination_neverSetsChapterOrUnassignedOrStatuses() {
        searches.forEach { search ->
            MemberAdminSort.entries.forEach { sort ->
                offsets.forEach { offset ->
                    val query = chapterRosterQuery(search, sort, offset)
                    assertNull(query.regionalChapterId, "search=$search sort=$sort offset=$offset")
                    assertFalse(query.unassignedOnly, "search=$search sort=$sort offset=$offset")
                    assertTrue(query.statuses.isEmpty(), "search=$search sort=$sort offset=$offset")
                }
            }
        }
    }

    @Test
    fun search_isTrimmedAndBlankBecomesNull() {
        assertNull(chapterRosterQuery("", MemberAdminSort.NAME_ASC, 0).search)
        assertNull(chapterRosterQuery("   ", MemberAdminSort.NAME_ASC, 0).search)
        assertEquals("x", chapterRosterQuery("  x ", MemberAdminSort.NAME_ASC, 0).search)
    }

    @Test
    fun offset_isNeverNegative() {
        assertEquals(0, chapterRosterQuery("", MemberAdminSort.NAME_ASC, -5).offset)
        assertEquals(0, chapterRosterQuery("", MemberAdminSort.NAME_ASC, 0).offset)
        assertEquals(25, chapterRosterQuery("", MemberAdminSort.NAME_ASC, 25).offset)
        assertEquals(10_000, chapterRosterQuery("", MemberAdminSort.NAME_ASC, 10_000).offset)
    }

    @Test
    fun sort_isPassedThroughUnchanged() {
        MemberAdminSort.entries.forEach { sort ->
            assertEquals(sort, chapterRosterQuery("", sort, 0).sort)
        }
    }

    @Test
    fun defaultLimit_isTheSharedMemberAdminQueryDefault() {
        val query = chapterRosterQuery("", MemberAdminSort.NAME_ASC, 0)
        assertEquals(network.lapis.cloud.shared.domain.MemberAdminQuery.DEFAULT_LIMIT, query.limit)
    }
}
