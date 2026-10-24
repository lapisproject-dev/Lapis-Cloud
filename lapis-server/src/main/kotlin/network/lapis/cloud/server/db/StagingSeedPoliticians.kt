package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianRaterType
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Sample data for the politician ranking (V1.9.205): profiles in the states the model knows (with and without the public-listing consent, one
 * former politician) and a few reactions of members and one guest. No LTR is involved.
 *
 * Writes what `PoliticianService.grantPoliticianStatus` / `revokePoliticianStatus` / `castRating` and `PublicRankingConsentStore.grant` leave
 * behind. A profile is written only when the member has none yet (checked beforehand, never caught: a unique violation would poison a PostgreSQL
 * transaction). A revoked profile has no reactions (the revoke removes them). No photos: they need a file in storage, an effect outside the
 * transaction. `politicianRankingEnabled` is NOT switched on (an administrator's decision); the public page shows the listed profiles once it is.
 */
internal object StagingSeedPoliticians : StagingSeedPart {
    override val name: String = "politicians"

    private const val GRANTED_BY = 1

    data class PlannedProfile(
        val index: Int,
        val member: Int,
        val status: PoliticianProfileStatus,
        val mandate: String?,
        val grantedDaysAgo: Int,
        val revokedDaysAgo: Int?,
        val listingConsent: Boolean,
    ) {
        val id: Uuid get() = SeedIds.politicians(index)
    }

    data class PlannedReaction(
        val index: Int,
        val profile: Int,
        val rater: Int,
        val value: PoliticianReactionValue,
        val daysAgo: Int,
    )

    /** Append-only: the index is the id. */
    val profiles: List<PlannedProfile> =
        listOf(
            PlannedProfile(
                index = 1,
                member = 2,
                status = PoliticianProfileStatus.ACTIVE,
                mandate = "Gemeinderat Musterstadt (fiktiv, Demodaten)",
                grantedDaysAgo = 100,
                revokedDaysAgo = null,
                listingConsent = true,
            ),
            PlannedProfile(
                index = 2,
                member = 10,
                status = PoliticianProfileStatus.ACTIVE,
                mandate = "Kreistagskandidat Musterkreis (fiktiv, Demodaten)",
                grantedDaysAgo = 80,
                revokedDaysAgo = null,
                listingConsent = true,
            ),
            PlannedProfile(
                index = 3,
                member = 5,
                status = PoliticianProfileStatus.ACTIVE,
                mandate = null,
                grantedDaysAgo = 60,
                revokedDaysAgo = null,
                listingConsent = false,
            ),
            PlannedProfile(
                index = 4,
                member = 20,
                status = PoliticianProfileStatus.ACTIVE,
                mandate = "Ortsbeirat Musterdorf (fiktiv, Demodaten)",
                grantedDaysAgo = 40,
                revokedDaysAgo = null,
                listingConsent = false,
            ),
            PlannedProfile(
                index = 5,
                member = 19,
                status = PoliticianProfileStatus.FORMER,
                mandate = "Frueher Stadtrat Musterstadt (fiktiv, Demodaten)",
                grantedDaysAgo = 200,
                revokedDaysAgo = 50,
                listingConsent = false,
            ),
        )

    val reactions: List<PlannedReaction> =
        listOf(
            PlannedReaction(index = 1, profile = 1, rater = 4, value = PoliticianReactionValue.LIKE, daysAgo = 90),
            PlannedReaction(index = 2, profile = 1, rater = 6, value = PoliticianReactionValue.LIKE, daysAgo = 85),
            PlannedReaction(index = 3, profile = 1, rater = 9, value = PoliticianReactionValue.LIKE, daysAgo = 70),
            PlannedReaction(index = 4, profile = 1, rater = 8, value = PoliticianReactionValue.DISLIKE, daysAgo = 30),
            PlannedReaction(index = 5, profile = 2, rater = 1, value = PoliticianReactionValue.LIKE, daysAgo = 60),
            PlannedReaction(index = 6, profile = 2, rater = 3, value = PoliticianReactionValue.LIKE, daysAgo = 55),
            PlannedReaction(index = 7, profile = 2, rater = 5, value = PoliticianReactionValue.DISLIKE, daysAgo = 20),
            PlannedReaction(index = 8, profile = 2, rater = 17, value = PoliticianReactionValue.LIKE, daysAgo = 15),
            PlannedReaction(index = 9, profile = 3, rater = 4, value = PoliticianReactionValue.LIKE, daysAgo = 40),
            PlannedReaction(index = 10, profile = 4, rater = 21, value = PoliticianReactionValue.DISLIKE, daysAgo = 10),
        )

    /** `true` when the member already has any listing consent row: the store would supersede (UPDATE) it, which the seed never does. */
    private fun JdbcTransaction.hasListingConsentRow(member: Int): Boolean =
        !PublicRankingConsentEventTable
            .selectAll()
            .where {
                (PublicRankingConsentEventTable.memberId eq SeedIds.member(member)) and
                    (PublicRankingConsentEventTable.rankingKind eq PublicRankingKind.POLITICIAN_LISTING)
            }.empty()

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(profiles.map { it.member } + reactions.map { it.rater } + GRANTED_BY)
        var inserted = 0
        var skipped = 0
        profiles.forEach { profile ->
            val free =
                PoliticianProfileTable.selectAll().where { PoliticianProfileTable.id eq profile.id }.empty() &&
                    PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq SeedIds.member(profile.member) }.empty()
            if (!(free && profile.member in statuses && GRANTED_BY in statuses)) {
                skipped++
                return@forEach
            }
            val grantedAt = clock.utcAt(daysAgo = profile.grantedDaysAgo, hour = 11)
            PoliticianProfileTable.insert {
                it[id] = profile.id
                it[memberId] = SeedIds.member(profile.member)
                it[status] = profile.status
                it[mandateText] = profile.mandate
                it[PoliticianProfileTable.grantedAt] = grantedAt
                it[grantedByMemberId] = SeedIds.member(GRANTED_BY)
                it[revokedAt] = profile.revokedDaysAgo?.let { d -> clock.utcAt(daysAgo = d, hour = 11) }
                it[revokedByMemberId] = if (profile.revokedDaysAgo != null) SeedIds.member(GRANTED_BY) else null
            }
            if (profile.listingConsent && !hasListingConsentRow(profile.member)) {
                val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.POLITICIAN_LISTING)
                PublicRankingConsentStore.grant(
                    memberId = SeedIds.member(profile.member),
                    kind = PublicRankingKind.POLITICIAN_LISTING,
                    version = disclaimer.version,
                    sha256 = disclaimer.sha256,
                    now = grantedAt,
                )
            }
            inserted++
        }
        reactions.forEach { reaction ->
            val profile = profiles.first { it.index == reaction.profile }
            val profileId = profile.id
            val reactionId = SeedIds.politicians(0x2000 + reaction.index)
            val raterStatus = statuses[reaction.rater]
            val profileRow = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.id eq profileId }.singleOrNull()
            val ok =
                profileRow != null &&
                    profileRow[PoliticianProfileTable.status] == PoliticianProfileStatus.ACTIVE &&
                    raterStatus != null &&
                    raterStatus in MemberStatusSets.POLITICIAN_RATER &&
                    PoliticianReactionTable.selectAll().where { PoliticianReactionTable.id eq reactionId }.empty() &&
                    PoliticianReactionTable
                        .selectAll()
                        .where {
                            (PoliticianReactionTable.politicianProfileId eq profileId) and
                                (PoliticianReactionTable.raterMemberId eq SeedIds.member(reaction.rater))
                        }.empty()
            if (!ok) {
                skipped++
                return@forEach
            }
            PoliticianReactionTable.insert {
                it[id] = reactionId
                it[politicianProfileId] = profileId
                it[reactionValue] = reaction.value
                it[castAt] = clock.utcAt(daysAgo = reaction.daysAgo, hour = 15)
                it[raterMemberId] = SeedIds.member(reaction.rater)
                it[raterType] = if (raterStatus == MemberStatus.GUEST) PoliticianRaterType.GAST else PoliticianRaterType.MEMBER
            }
            inserted++
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
