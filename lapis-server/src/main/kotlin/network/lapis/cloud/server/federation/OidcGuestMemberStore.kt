package network.lapis.cloud.server.federation
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Claims about a guest, extracted from a verified home-server ID Token -- see `network.lapis.cloud.server.routes.OidcRoutes` KDoc "RP callback". */
data class OidcGuestClaims(
    val issuer: String,
    val subject: String,
    val name: String?,
    val picture: String?,
    val preferredUsername: String?,
    val homeserverUrl: String,
    val membershipStatus: String?,
)

/**
 * Thrown by [OidcGuestMemberStore.resolveOrCreateGuestMember] when the `(issuer, subject)` pair
 * is already bound to a member whose status is no longer `GUEST` -- see the call site's KDoc for
 * why this must never fall through to "create a new guest". Caught by
 * [network.lapis.cloud.server.routes.OidcRoutes]'s RP callback handler and turned into a proper
 * `401` + [network.lapis.cloud.server.audit.OidcLoginAuditRecorder] audit row, same as every other
 * rejection reason in that handler -- never an unhandled `500`.
 */
class GuestIdentityBoundToNonGuestMemberException(
    message: String,
) : IllegalStateException(message)

/**
 * V0.8.2 design decision: a guest visiting this server IS represented as a real `Member` row with
 * `status = GUEST`, paired with a real `Account` row (`role = MEMBER`, `oidc_issuer`/`oidc_subject`
 * populated) -- reusing every existing FK-based mechanism and the exact same
 * [network.lapis.cloud.server.security.SessionStore] session machinery a real local member uses.
 * See `25-oidc-guest-federation.kuml.kts` file header for the full reasoning (why not a separate,
 * disjoint guest-identity concept: `account.oidc_subject` was reserved in V0.7.1 with the explicit
 * stated intent that "an OIDC path can later mint sessions via the same SessionStore", and
 * `MemberStatus.GUEST` already exists, already excluded from every ACTIVE-gated action, for exactly
 * this purpose).
 *
 * **Created once per federated identity, reused on subsequent visits** -- looked up by
 * `(account.oidc_issuer, account.oidc_subject)` before ever inserting, not one row per login
 * (unbounded row growth / DSGVO-hygiene concern otherwise).
 *
 * **Synthetic, deterministic, collision-free email** -- `member.email` is `VARCHAR(320) UNIQUE NOT
 * NULL` and the OIDC minimum ID-Token claim set has no `email` claim. `guest+sha256(iss|sub)[..32]
 * @federation.invalid` -- `.invalid` is the RFC 2606 reserved TLD meaning "never resolvable,
 * guaranteed non-deliverable" (this is not a real contact address), and the hash makes the same
 * federated identity always map to the same synthetic address, guaranteeing no collision with a
 * different `(iss, sub)` pair short of a SHA-256 collision.
 */
object OidcGuestMemberStore {
    /**
     * Resolves an existing guest `Member` for [claims]' `(issuer, subject)`, or creates one if this
     * is the federated identity's first-ever visit. Either way, upserts the [OidcGuestProfileTable]
     * row with the freshest profile data this login round supplied. Returns the guest's `member.id`.
     */
    fun resolveOrCreateGuestMember(
        claims: OidcGuestClaims,
        grantedScope: String,
    ): Uuid =
        transaction {
            val now = nowLocalDateTime()
            // V1.7.1b defensive fix -- looks up by (issuer, subject) alone (NOT narrowed by
            // `status = GUEST` in the WHERE clause) so a row that exists but is no longer GUEST is
            // seen and explicitly rejected below, rather than silently falling through to the
            // "create a new guest" branch. That fallthrough was itself the bug (review finding 8,
            // V1.7.1b review): a narrowed WHERE clause makes an (issuer, subject) pair that ever got
            // reassigned onto a non-GUEST account row (e.g. through a future admin action, or a bug
            // elsewhere) look like "no existing guest", so the code below would try to INSERT a
            // second `Account` row with the SAME (oidc_issuer, oidc_subject) pair -- violating that
            // pair's uniqueness constraint and surfacing as an unhandled 500 instead of a clear
            // error. Rejecting explicitly here, before any INSERT is attempted, closes that gap.
            val existingRow =
                (AccountTable innerJoin MemberTable)
                    .selectAll()
                    .where {
                        (AccountTable.oidcIssuer eq claims.issuer) and
                            (AccountTable.oidcSubject eq claims.subject)
                    }.singleOrNull()
            if (existingRow != null && existingRow[MemberTable.status] != MemberStatus.GUEST) {
                // Review finding N5 fix (round 2): this is caught by name in
                // `OidcRoutes.kt`'s RP callback handler and turned into a `401` + audit row --
                // NOT a bare unhandled `500`. `IllegalStateException`/`error(...)` used to be
                // thrown here uncaught; a dedicated exception type lets the caller distinguish
                // this specific, expected-but-rare edge case from a genuine programming error.
                throw GuestIdentityBoundToNonGuestMemberException(
                    "OIDC guest identity (issuer=${claims.issuer}) is already bound to a non-GUEST " +
                        "member -- refusing to resolve or create a guest session for it",
                )
            }
            val existingMemberId = existingRow?.get(MemberTable.id)

            val memberId =
                existingMemberId ?: run {
                    val newMemberId = Uuid.random()
                    MemberTable.insert {
                        it[id] = newMemberId
                        it[displayName] = claims.name?.takeIf { name -> name.isNotBlank() }
                            ?: claims.preferredUsername?.takeIf { name -> name.isNotBlank() }
                            ?: "Gast"
                        it[email] = syntheticEmail(issuer = claims.issuer, subject = claims.subject)
                        it[status] = MemberStatus.GUEST
                        it[joinedAt] = now.date
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[AccountTable.memberId] = newMemberId
                        it[role] = AccountRole.MEMBER
                        it[passwordHash] = null
                        it[oidcSubject] = claims.subject
                        it[oidcIssuer] = claims.issuer
                    }
                    newMemberId
                }

            val profileExists = OidcGuestProfileTable.selectAll().where { OidcGuestProfileTable.memberId eq memberId }.count() > 0
            if (profileExists) {
                OidcGuestProfileTable.update({ OidcGuestProfileTable.memberId eq memberId }) {
                    it[pictureUrl] = claims.picture
                    it[homeserverUrl] = claims.homeserverUrl
                    it[membershipStatus] = claims.membershipStatus
                    it[OidcGuestProfileTable.grantedScope] = grantedScope
                    it[lastLoginAt] = now
                }
            } else {
                OidcGuestProfileTable.insert {
                    it[id] = Uuid.random()
                    it[OidcGuestProfileTable.memberId] = memberId
                    it[pictureUrl] = claims.picture
                    it[homeserverUrl] = claims.homeserverUrl
                    it[membershipStatus] = claims.membershipStatus
                    it[OidcGuestProfileTable.grantedScope] = grantedScope
                    it[lastLoginAt] = now
                }
            }
            memberId
        }

    /** See class KDoc "Synthetic, deterministic, collision-free email". */
    fun syntheticEmail(
        issuer: String,
        subject: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$issuer|$subject".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        return "guest+$hex@federation.invalid"
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
}
