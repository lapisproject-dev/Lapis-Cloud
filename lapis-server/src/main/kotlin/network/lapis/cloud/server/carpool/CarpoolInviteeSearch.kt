package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import java.text.Normalizer
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- the driver's exact-name search for people who may ride along. **Built to resist enumeration**: both name parts must have at
 * least [MIN_PART_LENGTH] characters, the comparison is full equality of the normalised `"first last"` against the normalised display name
 * (no prefix, no substring, no wildcard), at most [MAX_HITS] hits, and only id, display name and status are read (no address; the status is shown to the driver so same-named accounts can be told apart). A `member` row has no separate first/last name columns, only `display_name`: titles, middle names and names shorter than
 * three characters are therefore not findable (documented limit).
 */
internal object CarpoolInviteeSearch {
    const val MIN_PART_LENGTH = 3
    const val MAX_PART_LENGTH = 100
    const val MAX_HITS = 3

    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")

    /**
     * Lower-case, `ä->ae ö->oe ü->ue ß->ss` (BEFORE the decomposition, otherwise `ä` would become `a`), strip the remaining accents,
     * collapse whitespace, trim. `Locale.ROOT` keeps the Turkish dotted/dotless I from depending on the server locale.
     */
    fun normalize(raw: String): String {
        val lowered =
            raw
                .lowercase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
        return Normalizer
            .normalize(lowered, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(whitespace, " ")
            .trim()
    }

    /** One search hit; [status] lets the driver tell same-named accounts apart. */
    data class Hit(
        val memberId: Uuid,
        val displayName: String,
        val status: MemberStatus,
    )

    /** Must run inside the caller's open transaction. Returns at most [MAX_HITS] [Hit]s. */
    fun find(
        driverId: Uuid,
        firstName: String,
        lastName: String,
    ): List<Hit> {
        val first = firstName.trim()
        val last = lastName.trim()
        if (first.length < MIN_PART_LENGTH || last.length < MIN_PART_LENGTH) return emptyList()
        if (first.length > MAX_PART_LENGTH || last.length > MAX_PART_LENGTH) return emptyList()
        val wanted = normalize("$first $last")
        val hits = ArrayList<Hit>(MAX_HITS)
        val requireVerified = CarpoolFriendConfig.requireEmailVerification()
        val rows =
            MemberTable
                .select(MemberTable.id, MemberTable.displayName, MemberTable.status, MemberTable.emailVerifiedAt)
                .where {
                    (MemberTable.status inList MemberStatusSets.CARPOOL_PASSENGER) and
                        MemberTable.anonymizedAt.isNull() and
                        (MemberTable.id neq driverId)
                }
        for (row in rows) {
            val name = row[MemberTable.displayName]
            val status = row[MemberTable.status]
            // An unverified FRIEND cannot act (same rule as the passenger guard of the service) -- it is neither findable nor invitable.
            if (requireVerified && status == MemberStatus.FRIEND && row[MemberTable.emailVerifiedAt] == null) continue
            if (normalize(name) == wanted) {
                hits += Hit(memberId = row[MemberTable.id], displayName = name, status = status)
                if (hits.size >= MAX_HITS) break
            }
        }
        return hits
    }
}
