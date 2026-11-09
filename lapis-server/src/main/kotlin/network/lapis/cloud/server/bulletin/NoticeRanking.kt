package network.lapis.cloud.server.bulletin

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.rpc.SocialPostWeight
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.297 -- the order of the board as a PURE function (no database). Pinned notices first (most recently pinned on top), then the decayed LTR
 * weight descending, on a tie the newest notice first, then the id (so the order is total and deterministic).
 */
internal object NoticeRanking {
    /** What the ranking needs of a notice -- never the text. */
    data class RankRow(
        val id: Uuid,
        val createdAt: LocalDateTime,
        val pinnedAt: LocalDateTime?,
    )

    data class Ranked(
        val id: Uuid,
        val pinnedAt: LocalDateTime?,
        val weight: BigDecimal,
    )

    fun rank(
        rows: List<RankRow>,
        boosts: Map<Uuid, List<SocialPostWeight.BoostContribution>>,
        now: LocalDateTime,
    ): List<Ranked> =
        rows
            .map { row ->
                Ranked(
                    id = row.id,
                    pinnedAt = row.pinnedAt,
                    weight = NoticeWeight.weightOf(boosts = boosts[row.id].orEmpty(), createdAt = row.createdAt, now = now),
                ) to row.createdAt
            }.sortedWith(
                compareByDescending<Pair<Ranked, LocalDateTime>> { it.first.pinnedAt != null }
                    .thenByDescending { it.first.pinnedAt }
                    .thenByDescending { it.first.weight }
                    .thenByDescending { it.second }
                    .thenBy { it.first.id.toString() },
            ).map { it.first }
}
