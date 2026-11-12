package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.containsLikePattern
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbLinkTargetDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightSource
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.MessageDigest
import kotlin.uuid.Uuid

internal data class LapisProfile(
    val givenName: String?,
    val familyName: String?,
    val nameAffix: String?,
    val company: String?,
    val personType: String?,
    val phone: String?,
    val mobile: String?,
    val addressSupplement: String?,
    val poBox: String?,
)

/** The Lapis side of the comparison: exactly the columns the sync reads or writes, plus the account role. */
internal data class LapisMember(
    val id: Uuid,
    val email: String,
    val displayName: String,
    /** V151 -- `member.title`, the single source of truth for the title (no longer in the profile side table). */
    val title: String? = null,
    val status: MemberStatus,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String?,
    val dateOfBirth: LocalDate?,
    val regionalChapterId: Uuid?,
    val personNumber: Long?,
    val missingSince: LocalDate?,
    val anonymized: Boolean,
    val role: AccountRole?,
    val profile: LapisProfile?,
    /** The CSV-import reference (`member.external_reference`): for members of the LupaBB export this IS the LupaBB person number. */
    val externalReference: String? = null,
    /** V1.9.298 -- `member.voting_right` and who set it; both are part of the [fingerprint]. */
    val votingRight: Boolean = true,
    val votingRightSource: VotingRightSource = VotingRightSource.DEFAULT,
) {
    /**
     * SHA-256 over the whole Lapis side. The apply step recomputes it under the member row lock: a different value means somebody changed the
     * member (or its account role) after the comparison, and the record is NOT written (`CHANGED_SINCE_PREVIEW`).
     */
    fun fingerprint(): String {
        val p = profile
        val parts =
            listOf(
                id,
                email,
                displayName,
                title,
                status,
                street,
                postalCode,
                city,
                countryCode,
                dateOfBirth,
                regionalChapterId,
                personNumber,
                missingSince,
                anonymized,
                role,
                p?.givenName,
                p?.familyName,
                p?.nameAffix,
                p?.company,
                p?.personType,
                p?.phone,
                p?.mobile,
                p?.addressSupplement,
                p?.poBox,
                votingRight,
                votingRightSource,
            )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(parts.joinToString("\u0001") { it?.toString().orEmpty() }.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Reads the [LapisMember]s. Plain reads, no locking; callers that need consistency hold the member row lock themselves. */
internal object LupaBbMemberLoader {
    /**
     * The members a LupaBB record can be linked to by hand: not anonymised, [lowerQuery] (already trimmed and lower-cased) in the name or the
     * e-mail address (LIKE wildcards escaped), at most [limit], by name. Reads name, e-mail, status and the link flag only, no address column.
     * Inside a transaction.
     */
    fun searchForLink(
        lowerQuery: String,
        limit: Int,
    ): List<LupaBbLinkTargetDto> {
        val pattern = containsLikePattern(lowerQuery)
        return MemberTable
            .selectAll()
            .where {
                MemberTable.anonymizedAt.isNull() and
                    ((MemberTable.displayName.lowerCase() like pattern) or (MemberTable.email.lowerCase() like pattern))
            }.orderBy(MemberTable.displayName to SortOrder.ASC)
            .limit(limit)
            .map {
                LupaBbLinkTargetDto(
                    memberId = it[MemberTable.id].toString(),
                    name = it[MemberTable.displayName],
                    email = it[MemberTable.email],
                    status = it[MemberTable.status],
                    linkedElsewhere = it[MemberTable.lupabbPersonNumber] != null,
                    linkedPersonNumber = it[MemberTable.lupabbPersonNumber],
                )
            }
    }

    private fun query() =
        MemberTable
            .join(AccountTable, JoinType.LEFT, MemberTable.id, AccountTable.memberId)
            .join(MemberLupaBbProfileTable, JoinType.LEFT, MemberTable.id, MemberLupaBbProfileTable.memberId)

    fun loadAll(): List<LapisMember> =
        query()
            .select(columns())
            .orderBy(MemberTable.id to SortOrder.ASC)
            .map { it.toMember() }

    fun loadOne(id: Uuid): LapisMember? =
        query()
            .select(columns())
            .where { MemberTable.id eq id }
            .singleOrNull()
            ?.toMember()

    private fun columns() =
        listOf(
            MemberTable.id,
            MemberTable.email,
            MemberTable.displayName,
            MemberTable.title,
            MemberTable.status,
            MemberTable.street,
            MemberTable.postalCode,
            MemberTable.city,
            MemberTable.countryCode,
            MemberTable.dateOfBirth,
            MemberTable.regionalChapterId,
            MemberTable.lupabbPersonNumber,
            MemberTable.lupabbMissingSince,
            MemberTable.anonymizedAt,
            MemberTable.externalReference,
            MemberTable.votingRight,
            MemberTable.votingRightSource,
            AccountTable.role,
            MemberLupaBbProfileTable.memberId,
            MemberLupaBbProfileTable.givenName,
            MemberLupaBbProfileTable.familyName,
            MemberLupaBbProfileTable.nameAffix,
            MemberLupaBbProfileTable.company,
            MemberLupaBbProfileTable.personType,
            MemberLupaBbProfileTable.phone,
            MemberLupaBbProfileTable.mobile,
            MemberLupaBbProfileTable.addressSupplement,
            MemberLupaBbProfileTable.poBox,
        )

    private fun ResultRow.toMember(): LapisMember {
        val hasProfile = this.getOrNull(MemberLupaBbProfileTable.memberId) != null
        return LapisMember(
            id = this[MemberTable.id],
            email = this[MemberTable.email],
            displayName = this[MemberTable.displayName],
            title = this[MemberTable.title],
            status = this[MemberTable.status],
            street = this[MemberTable.street],
            postalCode = this[MemberTable.postalCode],
            city = this[MemberTable.city],
            countryCode = this[MemberTable.countryCode],
            dateOfBirth = this[MemberTable.dateOfBirth],
            regionalChapterId = this[MemberTable.regionalChapterId],
            personNumber = this[MemberTable.lupabbPersonNumber],
            missingSince = this[MemberTable.lupabbMissingSince],
            anonymized = this[MemberTable.anonymizedAt] != null,
            externalReference = this[MemberTable.externalReference],
            votingRight = this[MemberTable.votingRight],
            votingRightSource = this[MemberTable.votingRightSource],
            role = this.getOrNull(AccountTable.role),
            profile =
                if (hasProfile) {
                    LapisProfile(
                        givenName = this[MemberLupaBbProfileTable.givenName],
                        familyName = this[MemberLupaBbProfileTable.familyName],
                        nameAffix = this[MemberLupaBbProfileTable.nameAffix],
                        company = this[MemberLupaBbProfileTable.company],
                        personType = this[MemberLupaBbProfileTable.personType],
                        phone = this[MemberLupaBbProfileTable.phone],
                        mobile = this[MemberLupaBbProfileTable.mobile],
                        addressSupplement = this[MemberLupaBbProfileTable.addressSupplement],
                        poBox = this[MemberLupaBbProfileTable.poBox],
                    )
                } else {
                    null
                },
        )
    }
}
