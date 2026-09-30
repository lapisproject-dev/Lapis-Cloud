package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.isLeaderboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `PublicRankingKind.POLITICIAN_LISTING` is NOT a leaderboard: the
 * "Öffentliche Ranglisten" section of "Meine Daten" iterates only [isLeaderboard] kinds, so the listing
 * switch never appears there (it lives on the "Mein öffentliches Profil" card), and every kind has a label.
 */
class DsgvoRightsScreenPublicProfileDomTest {
    @Test
    fun theLeaderboardSection_listsExactlyTheTwoRankings() {
        val shown = PublicRankingKind.entries.filter { it.isLeaderboard }
        assertEquals(listOf(PublicRankingKind.LTR_HOLDINGS, PublicRankingKind.DONATIONS), shown)
        assertFalse(PublicRankingKind.POLITICIAN_LISTING in shown)
    }

    @Test
    fun everyKindHasANonBlankLabel_andThePoliticianOneIsItsOwn() {
        PublicRankingKind.entries.forEach { kind -> assertTrue(publicRankingKindLabel(kind).isNotBlank(), "label for $kind") }
        assertEquals("Öffentliche Politiker-Seite", publicRankingKindLabel(PublicRankingKind.POLITICIAN_LISTING))
    }
}
