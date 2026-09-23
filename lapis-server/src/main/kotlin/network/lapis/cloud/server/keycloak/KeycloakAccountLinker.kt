package network.lapis.cloud.server.keycloak

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

// Same idiom as BankAccountStore.kt/BankStatementImportService.kt/EventTicketIssuer.kt/
// EventRegistrationSubmission.kt/ContributionReliefService.kt -- this codebase has no shared
// constants file for Postgres SQL state codes, so each call site declares its own private const.
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

/**
 * The account-linking decision logic for sub-wave 1b "Keycloak as external user management" --
 * implements the vault spec's decision 5 (`Keycloak Externe Benutzerverwaltung.md`) exactly:
 * Keycloak authenticates, but it NEVER creates a member here. A verified `(iss, sub)` either
 * resolves to an already-linked member, auto-links to exactly one still-unlinked member whose
 * email matches (case-insensitively), or the login is rejected -- there is no third outcome.
 *
 * **The subject is authoritative once linked.** Case 1 below is checked FIRST and unconditionally
 * -- a Keycloak-side email change on an already-linked identity never re-points the link, it just
 * keeps resolving to the same local member it always did. This mirrors how `member_id ->
 * (keycloak_issuer, keycloak_subject)` is meant to behave as a durable identity mapping, not a
 * live email lookup performed on every login.
 *
 * **Never creates a member.** Unlike [network.lapis.cloud.server.federation.OidcGuestMemberStore]
 * (which mints a new `Member(status=GUEST)` row for a first-time federated guest), an email miss
 * here is always a hard rejection -- an admin must link manually. This is the entire point of
 * decision 5: prevent a mistyped/collided email from silently vending a Keycloak login into
 * someone else's local membership.
 *
 * Every rejection is a named [LinkOutcome.Rejected] reason, never an exception -- the caller
 * ([network.lapis.cloud.server.routes.KeycloakAuthRoutes]) logs/audits the specific reason and
 * shows the caller a generic error page, same "typed result, not a throw" shape
 * [network.lapis.cloud.server.federation.OidcJwt.VerificationResult] already establishes.
 */
object KeycloakAccountLinker {
    enum class RejectionReason {
        EMAIL_NOT_VERIFIED,
        NO_MATCHING_MEMBER,
        CONFLICTING_LINK,
        MEMBER_LOGIN_BLOCKED,

        /**
         * Review finding 6 fix: the case-insensitive email lookup below now fetches up to 2 rows
         * instead of relying on DB-level uniqueness + `singleOrNull()` (which silently returns
         * `null` -- misread as [NO_MATCHING_MEMBER] -- for more than one match, e.g. two `MemberTable`
         * rows whose emails differ only in case on a case-sensitive collation). More than one match
         * is a distinct, named rejection, never a silent "no match".
         */
        AMBIGUOUS_EMAIL_MATCH,
    }

    sealed interface LinkOutcome {
        data class Linked(
            val memberId: Uuid,
            /**
             * `true` only when this call just INSERTed a brand-new `keycloak_account_link` row
             * (the auto-link case below); `false` when it resolved an already-existing link (case 1
             * above). Lets the caller ([network.lapis.cloud.server.routes.KeycloakAuthRoutes])
             * distinguish `KEYCLOAK_LOGIN_SUCCESS` from the one-time `KEYCLOAK_LINK_CREATED` audit
             * event without a second DB round trip.
             */
            val wasNewLink: Boolean,
        ) : LinkOutcome

        data class Rejected(
            val reason: RejectionReason,
        ) : LinkOutcome
    }

    /**
     * Resolves (or auto-links, or rejects) a verified Keycloak identity to a local member --
     * always inside its own DB transaction (idempotent to call at most once per login attempt;
     * callers should not wrap this in an outer transaction that could partially roll back an
     * intended auto-link on an unrelated later failure).
     */
    fun linkOrResolve(
        issuer: String,
        subject: String,
        email: String,
        emailVerified: Boolean,
        requireVerifiedEmail: Boolean,
    ): LinkOutcome =
        transaction {
            val now = nowLocalDateTime()

            // Case 1: an existing link for this exact (issuer, subject) -- authoritative, checked
            // first and unconditionally (see class KDoc "subject is authoritative once linked").
            val existingLink =
                KeycloakAccountLinkTable
                    .selectAll()
                    .where {
                        (KeycloakAccountLinkTable.keycloakIssuer eq issuer) and
                            (KeycloakAccountLinkTable.keycloakSubject eq subject)
                    }.singleOrNull()
            if (existingLink != null) {
                val memberId = existingLink[KeycloakAccountLinkTable.memberId]
                val statusBlocked = memberStatusBlocksLogin(memberId)
                if (statusBlocked) return@transaction LinkOutcome.Rejected(RejectionReason.MEMBER_LOGIN_BLOCKED)
                KeycloakAccountLinkTable.update({ KeycloakAccountLinkTable.id eq existingLink[KeycloakAccountLinkTable.id] }) {
                    it[lastLoginAt] = now
                }
                return@transaction LinkOutcome.Linked(memberId = memberId, wasNewLink = false)
            }

            // No link yet -- an email match is required. requireVerifiedEmail gates BEFORE the
            // email lookup even runs, per spec.
            if (requireVerifiedEmail && !emailVerified) {
                return@transaction LinkOutcome.Rejected(RejectionReason.EMAIL_NOT_VERIFIED)
            }

            val normalizedEmail = email.trim().lowercase()
            // Review finding 6 fix: `.limit(2)` + count instead of `singleOrNull()` -- Kotlin's
            // `Iterable.singleOrNull()` returns `null` (not an exception) for BOTH "zero rows" and
            // "more than one row", so two members whose emails differ only in case on a
            // case-sensitive DB collation would previously be silently misread as
            // [RejectionReason.NO_MATCHING_MEMBER] instead of the genuinely distinct "which one?"
            // situation.
            val matchingMembers =
                MemberTable
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .limit(2)
                    .toList()
            val matchingMember =
                when (matchingMembers.size) {
                    0 -> return@transaction LinkOutcome.Rejected(RejectionReason.NO_MATCHING_MEMBER)
                    1 -> matchingMembers.single()
                    else -> return@transaction LinkOutcome.Rejected(RejectionReason.AMBIGUOUS_EMAIL_MATCH)
                }
            val matchedMemberId = matchingMember[MemberTable.id]

            // Security-audit fix (MINOR 3a): regardless of the operator's `requireVerifiedEmail`
            // setting, NEVER auto-link an unverified-email Keycloak identity to a member whose
            // account role is escalated (BOARD/TREASURER/ADMIN, see
            // [network.lapis.cloud.server.security.ESCALATED_ROLES]). An operator turning
            // `requireVerifiedEmail` off (e.g. because their Keycloak realm never verifies
            // ordinary-member emails) must not thereby also open a takeover path for a
            // board/treasurer/admin account, which is a categorically higher-stakes target. Checked
            // AFTER the email match (role is only known once a candidate member is identified), but
            // still strictly before any linking/conflict-detection side effect below.
            val matchedRole =
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq matchedMemberId }
                    .singleOrNull()
                    ?.get(AccountTable.role)
            if (!emailVerified && matchedRole != null && matchedRole in ESCALATED_ROLES) {
                return@transaction LinkOutcome.Rejected(RejectionReason.EMAIL_NOT_VERIFIED)
            }

            // The matched member might already have a DIFFERENT link -- never silently re-link.
            val conflictingLink =
                KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq matchedMemberId }.singleOrNull()
            if (conflictingLink != null) {
                return@transaction LinkOutcome.Rejected(RejectionReason.CONFLICTING_LINK)
            }

            if (memberStatusBlocksLoginRow(matchingMember)) {
                return@transaction LinkOutcome.Rejected(RejectionReason.MEMBER_LOGIN_BLOCKED)
            }

            // Review finding 5 fix (round 2: narrowed per review finding N4): two concurrent
            // first-time logins for the same member can both read `conflictingLink == null` above
            // and both reach this INSERT -- the second one violates `uq_keycloak_account_link_member`.
            // Map only that specific unique-violation SQL state to a clean
            // [RejectionReason.CONFLICTING_LINK] instead of letting an [ExposedSQLException] escape
            // `linkOrResolve` and surface as an unhandled 500 in `KeycloakAuthRoutes` -- any OTHER SQL
            // failure (e.g. a connection drop, a constraint violation on an unrelated column) must
            // still propagate instead of being mislabeled as a link conflict. Same idiom as
            // `BankStatementImportService.kt`'s own `sqlState == UNIQUE_VIOLATION_SQL_STATE` check.
            // Review finding F1 fix (round 3): the mapping itself is extracted into
            // [mapLinkInsertFailure] so it is directly unit-testable against real
            // `ExposedSQLException` instances -- the previous regression test only forced an
            // overlong `keycloak_subject`, which Exposed rejects client-side with
            // `IllegalArgumentException` before this catch block is ever reached, so it proved
            // nothing about this mapping.
            val inserted =
                runCatching {
                    KeycloakAccountLinkTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = matchedMemberId
                        it[keycloakIssuer] = issuer
                        it[keycloakSubject] = subject
                        it[linkedAt] = now
                        it[linkedBy] = null
                        it[lastLoginAt] = now
                    }
                }
            inserted.exceptionOrNull()?.let { cause -> return@transaction mapLinkInsertFailure(cause) }
            LinkOutcome.Linked(memberId = matchedMemberId, wasNewLink = true)
        }

    /**
     * Maps an [Throwable] raised by the `keycloak_account_link` insert above to an outcome --
     * extracted from `linkOrResolve` (review finding F1, round 3) so it is directly unit-testable
     * with real `ExposedSQLException`/SQLSTATE fixtures instead of only indirectly through
     * `linkOrResolve`. Returns [RejectionReason.CONFLICTING_LINK] for a genuine unique-violation
     * (SQLSTATE [UNIQUE_VIOLATION_SQL_STATE], the `uq_keycloak_account_link_member` constraint);
     * rethrows every other cause untouched -- any other [ExposedSQLException] SQLSTATE (e.g. a
     * foreign-key violation, a connection drop) and any non-SQL [Throwable] alike.
     */
    internal fun mapLinkInsertFailure(cause: Throwable): LinkOutcome.Rejected {
        if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
            return LinkOutcome.Rejected(RejectionReason.CONFLICTING_LINK)
        }
        throw cause
    }

    private fun memberStatusBlocksLogin(memberId: Uuid): Boolean {
        val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return true
        return memberStatusBlocksLoginRow(row)
    }

    private fun memberStatusBlocksLoginRow(row: org.jetbrains.exposed.v1.core.ResultRow): Boolean =
        row[MemberTable.status] in MemberStatusSets.LOGIN_BLOCKED

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
}
