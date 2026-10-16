package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.agreementtexts.CurrentAgreementText
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** A member the finder may use right now: effective consent, ACTIVE, not anonymized, of age (or age unknown), with a derivable region. */
internal data class MemberFinderParticipant(
    val memberId: Uuid,
    val regionKey: String,
    val interest: MemberFinderInterest,
)

/** What the finder derives from a member's OWN row. [regionKey] is `null` when no German region can be derived. */
internal data class MemberFinderBasis(
    val regionKey: String?,
    val minor: Boolean,
    val active: Boolean,
) {
    val eligible: Boolean get() = active && !minor && regionKey != null
}

/**
 * V1.9.144 -- the ONLY reader of postal code, country and birth date for the finder. Every query filters LIVE by ACTIVE, not anonymized and
 * of age (E13a), so a status change takes effect even before any cleanup ran. Region and age are derived in Kotlin; nothing of it is stored.
 * Must run inside a `transaction {}`.
 */
internal object MemberFinderEligibility {
    private const val ADULT_YEARS = 18

    /** A known birth date younger than 18 years on [today] is a minor; an unknown one is not (the member confirms being of age instead). */
    fun isMinor(
        dateOfBirth: LocalDate?,
        today: LocalDate,
    ): Boolean = dateOfBirth != null && dateOfBirth.plus(ADULT_YEARS, DateTimeUnit.YEAR) > today

    /** Everybody with an effective consent of the CURRENT text who may use the finder today. One query. */
    fun participants(
        today: LocalDate,
        current: CurrentAgreementText,
    ): List<MemberFinderParticipant> =
        MemberTable
            .join(MemberFinderConsentEventTable, JoinType.INNER, MemberTable.id, MemberFinderConsentEventTable.memberId)
            .select(
                MemberTable.id,
                MemberTable.postalCode,
                MemberTable.country,
                MemberTable.countryCode,
                MemberTable.dateOfBirth,
                MemberFinderConsentEventTable.interest,
            ).where {
                (MemberFinderConsentEventTable.eventType eq MemberFinderConsentEventType.GRANT) and
                    MemberFinderConsentEventTable.supersededAt.isNull() and
                    (MemberFinderConsentEventTable.consentVersion eq current.versionLabel) and
                    (MemberFinderConsentEventTable.consentSha256 eq current.sha256) and
                    (MemberTable.status inList MemberStatusSets.ORGANIZATION_MEMBER) and
                    MemberTable.anonymizedAt.isNull()
            }.mapNotNull { row ->
                if (isMinor(dateOfBirth = row[MemberTable.dateOfBirth], today = today)) return@mapNotNull null
                val region =
                    MemberFinderRegion.keyOf(
                        postalCode = row[MemberTable.postalCode],
                        countryCode = row[MemberTable.countryCode],
                        legacyCountry = row[MemberTable.country],
                    ) ?: return@mapNotNull null
                val interest = row[MemberFinderConsentEventTable.interest] ?: return@mapNotNull null
                MemberFinderParticipant(memberId = row[MemberTable.id], regionKey = region, interest = interest)
            }

    /** The caller's own basis; `null` if the member row does not exist. */
    fun basisOf(
        memberId: Uuid,
        today: LocalDate,
    ): MemberFinderBasis? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.let { row ->
                MemberFinderBasis(
                    regionKey =
                        MemberFinderRegion.keyOf(
                            postalCode = row[MemberTable.postalCode],
                            countryCode = row[MemberTable.countryCode],
                            legacyCountry = row[MemberTable.country],
                        ),
                    minor = isMinor(dateOfBirth = row[MemberTable.dateOfBirth], today = today),
                    active = row[MemberTable.status] == MemberStatus.ACTIVE && row[MemberTable.anonymizedAt] == null,
                )
            }

    /** Members with an open GRANT that are no longer eligible at all (not ACTIVE, anonymized, or a minor) -- for the cleanup. */
    fun grantedButIneligible(today: LocalDate): List<Uuid> =
        MemberFinderConsentStore.membersWithOpenGrant().filter { id ->
            val basis = basisOf(memberId = id, today = today)
            basis == null || !basis.active || basis.minor
        }
}
