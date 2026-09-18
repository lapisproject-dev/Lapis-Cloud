package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the ONLY writer of [MemberCardCodeTable]. Every
 * function here must run inside an already-open `transaction {}` (same discipline every other
 * `*Store` in this codebase establishes) -- this object never opens one of its own.
 *
 * **[issueNewCode]/[revokeAndReissue] both lock the owning `member` row** (`forUpdate()`) for
 * their whole read-then-write -- this is what serializes two concurrent callers for the SAME
 * member into "at most one ACTIVE code" without a database constraint (see `52-member-card
 * .kuml.kts` file header "Why no partial unique index").
 */
internal object MemberCardStore {
    /**
     * Mints and persists a fresh active code for a member that does not yet have one. **NOT
     * idempotent** -- throws [IllegalStateException] if the member already has an active
     * (non-revoked) code, it never returns/reuses it. The raw code is never persisted (see class
     * KDoc), so an "active code already exists" reader cannot recover its raw form -- callers
     * needing the raw value again (e.g. a PDF re-download) must mint a new one.
     *
     * **No production caller (as of the PDF wave).** That wave settled the "member wants their card
     * again" question in favour of always rotating -- see
     * [network.lapis.cloud.server.member.MemberCardIssuance] KDoc for the argument -- so the one
     * production issuance path goes through [revokeAndReissue], which handles the has-no-code case
     * identically to this function. This function is retained as the tested primitive for "issue,
     * and fail loudly if one already exists"; a new caller should be sure it wants THAT contract
     * rather than [revokeAndReissue]'s.
     */
    fun issueNewCode(
        memberId: Uuid,
        now: LocalDateTime,
    ): String {
        lockMemberOrThrow(memberId)
        val active =
            MemberCardCodeTable
                .selectAll()
                .where { (MemberCardCodeTable.memberId eq memberId) and (MemberCardCodeTable.revokedAt.isNull()) }
                .firstOrNull()
        if (active != null) {
            error(
                "MemberCardStore.issueNewCode: member $memberId already has an active code -- " +
                    "the raw value cannot be recovered from a hash. Callers must not call this a " +
                    "second time expecting the same raw code back; use revokeAndReissue to rotate.",
            )
        }
        return issue(memberId = memberId, now = now)
    }

    /** Revokes the member's current active code (if any) and issues a fresh one. Always run by BOARD/ADMIN, never self-service. */
    fun revokeAndReissue(
        memberId: Uuid,
        now: LocalDateTime,
    ): String {
        lockMemberOrThrow(memberId)
        MemberCardCodeTable.update({ (MemberCardCodeTable.memberId eq memberId) and (MemberCardCodeTable.revokedAt.isNull()) }) {
            it[revokedAt] = now
        }
        return issue(memberId = memberId, now = now)
    }

    /**
     * Public `/ausweis` lookup: SHA-256 hash the (already-canonicalized) code, look it up by that
     * hash, then classify. [Unknown] and [Revoked] are DELIBERATELY the same-cost, same-shape
     * outcomes for the caller to render identically -- no "code exists but is revoked" oracle for
     * an unauthenticated caller (see 52-member-card.kuml.kts wave plan OF-4).
     *
     * **Read-only -- never allocates a `member_number`.** The number is allocated at issuance
     * time (see [issue]); this function must stay side-effect-free because it is reachable by any
     * unauthenticated caller who knows a valid code, and a write here (`SELECT ... FOR UPDATE` on
     * both the member row and the shared per-year sequence row) would let such a caller hold
     * exclusive locks via a plain read endpoint. If a legacy row somehow has no `member_number`
     * yet, [MEMBER_NUMBER_PENDING_PLACEHOLDER] is returned instead of allocating one here.
     */
    fun resolve(canonicalCode: String): MemberCardResolution {
        val hash = sha256Hex(canonicalCode.toByteArray(Charsets.US_ASCII))
        val row =
            MemberCardCodeTable
                .selectAll()
                .where { MemberCardCodeTable.codeHash eq hash }
                .singleOrNull() ?: return MemberCardResolution.Unknown
        if (row[MemberCardCodeTable.revokedAt] != null) return MemberCardResolution.Revoked
        val memberId = row[MemberCardCodeTable.memberId]
        val memberRow =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull() ?: return MemberCardResolution.Unknown
        return MemberCardResolution.Valid(
            displayName = memberRow[MemberTable.displayName],
            memberNumber = memberRow[MemberTable.memberNumber] ?: MEMBER_NUMBER_PENDING_PLACEHOLDER,
            status = memberRow[MemberTable.status],
            joinedAt = memberRow[MemberTable.joinedAt],
        )
    }

    /** Placeholder for [resolve] when a card code's member somehow has no `member_number` yet -- see that function's KDoc. */
    const val MEMBER_NUMBER_PENDING_PLACEHOLDER: String = "M-PENDING"

    private fun lockMemberOrThrow(memberId: Uuid) {
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
    }

    private fun issue(
        memberId: Uuid,
        now: LocalDateTime,
    ): String {
        // Allocate the member_number HERE, at issuance, under the caller's already-held
        // forUpdate() lock on the member row (see lockMemberOrThrow callers) -- not lazily from
        // the unauthenticated `resolve()` read path (see that function's KDoc for why).
        MemberNumberAllocator.ensureFor(memberId)
        val rawCode = MemberCardPolicy.newRawCode()
        val hash = sha256Hex(rawCode.toByteArray(Charsets.US_ASCII))
        MemberCardCodeTable.insert {
            it[id] = Uuid.random()
            it[MemberCardCodeTable.memberId] = memberId
            it[codeHash] = hash
            it[issuedAt] = now
            it[revokedAt] = null
        }
        return rawCode
    }
}

/** Result of [MemberCardStore.resolve] -- see that function's KDoc for why [Unknown]/[Revoked] must render identically. */
internal sealed interface MemberCardResolution {
    data class Valid(
        val displayName: String,
        val memberNumber: String,
        val status: MemberStatus,
        val joinedAt: LocalDate,
    ) : MemberCardResolution

    data object Revoked : MemberCardResolution

    data object Unknown : MemberCardResolution
}
