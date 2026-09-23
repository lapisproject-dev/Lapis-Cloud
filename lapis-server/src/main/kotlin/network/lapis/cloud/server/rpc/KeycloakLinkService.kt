package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.OidcLoginAuditRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.keycloak.KeycloakAccountLinker
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.KeycloakLinkChange
import network.lapis.cloud.server.mail.KeycloakLinkNotificationMailer
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.extractSessionToken
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.OidcLoginEventType
import network.lapis.cloud.shared.domain.UnlinkedMemberDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IKeycloakLinkService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * V1.7.2 sub-wave 2a "Keycloak als externe Benutzerverwaltung -- UI (Server-Seite)" -- see
 * [IKeycloakLinkService] KDoc for the ADMIN-only manual counterpart to
 * [KeycloakAccountLinker]'s automatic email-match linking. Every method requires ADMIN, checked
 * first via [requireAdmin] before any DB work, same discipline [TrustAnchorService] already
 * establishes for its own ADMIN-only surface.
 */
class KeycloakLinkService internal constructor(
    private val call: ApplicationCall,
    /**
     * Default-constructs its own [KeycloakConfig.load] like [RegistrationService]'s/
     * [AuthService]'s own `keycloakConfig` parameter does, so a test/call site that doesn't pass
     * one keeps working unchanged.
     */
    private val keycloakConfig: KeycloakConfig = KeycloakConfig.load(),
    /**
     * Security-audit fix -- the member-facing security notice on every manual link/unlink, see
     * [KeycloakLinkNotificationMailer] KDoc. No default value on purpose, same discipline
     * `MemberService`'s own `adminPasswordResetNotificationMailer` parameter establishes: a call
     * site that forgot to wire it must not compile into a silently notice-less deployment.
     */
    private val notificationMailer: KeycloakLinkNotificationMailer,
) : IKeycloakLinkService {
    override suspend fun listUnlinkedMembers(): List<UnlinkedMemberDto> {
        requireAdmin()
        return transaction {
            // Two plain queries instead of a NOT IN (subquery) -- keycloak_account_link is small by
            // construction (at most one row per member, see that table's own uq_..._member index)
            // so materializing its member ids first is cheap and keeps this query unambiguous.
            val linkedMemberIds = KeycloakAccountLinkTable.selectAll().map { it[KeycloakAccountLinkTable.memberId] }.toSet()
            MemberTable
                .selectAll()
                .where { MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED }
                .orderBy(MemberTable.displayName to SortOrder.ASC)
                .filter { it[MemberTable.id] !in linkedMemberIds }
                .map {
                    UnlinkedMemberDto(
                        memberId = it[MemberTable.id].toString(),
                        displayName = it[MemberTable.displayName],
                        email = it[MemberTable.email],
                    )
                }
        }
    }

    override suspend fun linkMember(
        memberId: String,
        keycloakSubject: String,
    ) {
        requireAdmin()
        val adminMemberId = resolveCurrentMember(call).memberId
        if (!keycloakConfig.enabled) {
            throw BadRequestException("This deployment is not in Keycloak mode -- there is no Keycloak issuer to link against")
        }
        val issuer = keycloakConfig.issuerUrl ?: throw BadRequestException("Keycloak issuer is not configured")
        // Review fix (MINOR 4): the client already trims, but this is an RPC boundary -- a caller
        // that skips the client trim, or an overlong value, must not reach `mapLinkInsertFailure` as
        // an unhandled `IllegalArgumentException` (-> 500). Rejected as a clean 400 instead, BEFORE
        // any linking logic runs. 255 matches `KeycloakAccountLinkTable.keycloakSubject`'s column width.
        val subject = keycloakSubject.trim()
        if (subject.isEmpty()) throw BadRequestException("keycloakSubject must not be blank")
        if (subject.length > 255) throw BadRequestException("keycloakSubject must not exceed 255 characters")
        val targetMemberId = memberId.toMemberUuid()
        val now = DbClock.nowLocalDateTime()
        val targetEmail =
            transaction {
                val memberRow =
                    MemberTable.selectAll().where { MemberTable.id eq targetMemberId }.singleOrNull()
                        ?: throw NotFoundException("Member '$memberId' not found")
                // Security-audit fix: listUnlinkedMembers already hides these rows, but the RPC
                // itself accepted them. An anonymized row's address is no longer the member's, and a
                // LOGIN_BLOCKED member (incl. DECEASED) can never log in through the link anyway --
                // linking them only produces a dormant identity mapping that silently becomes live
                // the moment the status is flipped back, and a security notice to what is in
                // practice a relative's mailbox (same DECEASED reasoning as
                // IMemberService.setTemporaryPasswordForMember).
                if (memberRow[MemberTable.anonymizedAt] != null) {
                    throw ConflictException("Member has been anonymized and can no longer be linked")
                }
                if (memberRow[MemberTable.status] in MemberStatusSets.LOGIN_BLOCKED) {
                    throw ConflictException("Member's status blocks login -- a Keycloak link would have no effect")
                }
                if (KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq targetMemberId }.any()) {
                    throw ConflictException("Member '$memberId' already has a Keycloak account link")
                }
                val existingByIdentity =
                    KeycloakAccountLinkTable
                        .selectAll()
                        .where {
                            (KeycloakAccountLinkTable.keycloakIssuer eq issuer) and
                                (KeycloakAccountLinkTable.keycloakSubject eq subject)
                        }.any()
                if (existingByIdentity) {
                    throw ConflictException("This Keycloak identity is already linked to a different member")
                }
                val inserted =
                    runCatching {
                        KeycloakAccountLinkTable.insert {
                            it[id] = Uuid.random()
                            it[KeycloakAccountLinkTable.memberId] = targetMemberId
                            it[keycloakIssuer] = issuer
                            it[KeycloakAccountLinkTable.keycloakSubject] = subject
                            it[linkedAt] = now
                            it[linkedBy] = adminMemberId
                            it[lastLoginAt] = null
                        }
                    }
                // Reuses KeycloakAccountLinker's own established SQLSTATE-23505-vs-everything-else
                // mapping instead of duplicating it -- see that function's KDoc. It returns a
                // LinkOutcome.Rejected(CONFLICTING_LINK) for a genuine unique-violation race (two
                // concurrent admin link attempts for the same member/identity) and rethrows anything
                // else untouched.
                inserted.exceptionOrNull()?.let { cause ->
                    KeycloakAccountLinker.mapLinkInsertFailure(cause)
                    throw ConflictException("This member or Keycloak identity was linked concurrently by another request")
                }
                // Security-audit fix: the audit row is written INSIDE the same transaction as the
                // link itself (OidcLoginAuditRecorder's own `transaction {}` joins this one -- no
                // nested-transaction mode is configured anywhere in this codebase), so a link can
                // never commit without its audit event, and vice versa. It previously ran as a
                // separate transaction after commit, where a failed insert left an unaudited link.
                OidcLoginAuditRecorder.record(
                    eventType = OidcLoginEventType.KEYCLOAK_LINK_MANUAL,
                    memberId = targetMemberId,
                    remoteParty = issuer,
                    reason = keycloakLinkAuditReason(adminMemberId = adminMemberId, subject = subject),
                )
                memberRow[MemberTable.email]
            }
        // AFTER commit, never ergebnisrelevant -- same placement/posture as
        // MemberService.notifyMemberOfAdminPasswordReset. See KeycloakLinkNotificationMailer KDoc.
        notifyMember(targetId = targetMemberId, email = targetEmail, change = KeycloakLinkChange.LINKED, occurredAt = now)
    }

    override suspend fun unlinkMember(memberId: String): Boolean {
        requireAdmin()
        val current = resolveCurrentMember(call)
        val adminMemberId = current.memberId
        val targetMemberId = memberId.toMemberUuid()
        val now = DbClock.nowLocalDateTime()
        // null = nothing was linked (idempotent no-op); otherwise the address to notify, or "" when
        // the member must not be mailed (anonymized/DECEASED).
        val notifyEmail: String? =
            transaction {
                val memberRow =
                    MemberTable.selectAll().where { MemberTable.id eq targetMemberId }.singleOrNull()
                        ?: throw NotFoundException("Member '$memberId' not found")
                val linkRow =
                    KeycloakAccountLinkTable
                        .selectAll()
                        .where { KeycloakAccountLinkTable.memberId eq targetMemberId }
                        .forUpdate()
                        .singleOrNull() ?: return@transaction null
                KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId eq targetMemberId }
                // Security-audit fix: same-transaction audit (see linkMember), now also carrying the
                // fingerprint of the REMOVED subject -- the row itself is gone after this, so the
                // audit event is the only remaining record of which identity had been linked.
                OidcLoginAuditRecorder.record(
                    eventType = OidcLoginEventType.KEYCLOAK_LINK_MANUAL_REMOVED,
                    memberId = targetMemberId,
                    remoteParty = linkRow[KeycloakAccountLinkTable.keycloakIssuer],
                    reason =
                        keycloakLinkAuditReason(
                            adminMemberId = adminMemberId,
                            subject = linkRow[KeycloakAccountLinkTable.keycloakSubject],
                        ),
                )
                val mailable =
                    memberRow[MemberTable.anonymizedAt] == null && memberRow[MemberTable.status] != MemberStatus.DECEASED
                if (mailable) memberRow[MemberTable.email] else ""
            }
        // Idempotent -- see IKeycloakLinkService.unlinkMember KDoc "deliberately diverges". Only a
        // genuine removal gets an audit event / side effects; a no-op leaves no trace.
        if (notifyEmail == null) return false
        // Security-audit fix: the typical reason to unlink is "the WRONG Keycloak identity was
        // linked" -- which means that identity may hold a live 8h session as this member right now.
        // Removing the row alone does not end it (SessionStore.resolve does not consult
        // keycloak_account_link), so every session of the target is revoked AFTER commit (SessionStore
        // writes its own transaction), same placement as setTemporaryPasswordForMember. An admin
        // unlinking their OWN record keeps the session this very request is running on.
        SessionStore.revokeAllForMember(
            memberId = targetMemberId,
            exceptRawToken = if (targetMemberId == adminMemberId) extractSessionToken(call) else null,
        )
        if (notifyEmail.isNotEmpty()) {
            notifyMember(targetId = targetMemberId, email = notifyEmail, change = KeycloakLinkChange.UNLINKED, occurredAt = now)
        }
        // Review fix (MINOR 6): lets the caller (client) distinguish "actually removed a link" from
        // "no-op, nothing existed" instead of always claiming success -- see
        // IKeycloakLinkService.unlinkMember KDoc.
        return true
    }

    /**
     * Security-audit fix -- deliberately NO rate limiter (unlike
     * MemberService.notifyMemberOfAdminPasswordReset's target-side pool): every send corresponds to
     * a real, audited state change (a link row inserted or deleted) that only an ADMIN can cause, so
     * it cannot be spammed for free; and any finite per-target budget would let a rogue ADMIN burn it
     * with a few link/unlink cycles first and then perform the one link that matters silently --
     * exactly the suppression scenario V1.4.9's own review rounds had to close twice.
     */
    private fun notifyMember(
        targetId: Uuid,
        email: String,
        change: KeycloakLinkChange,
        occurredAt: LocalDateTime,
    ) {
        runCatching { notificationMailer.send(email = email, change = change, occurredAt = occurredAt) }
            .onFailure { e -> logger.error { "keycloak-link notice ($change) for member=$targetId threw: ${e::class.simpleName}" } }
    }

    private suspend fun requireAdmin() {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
    }
}

private val logger = KotlinLogging.logger {}

/**
 * Security-audit fix -- `reason` payload for [OidcLoginEventType.KEYCLOAK_LINK_MANUAL]/
 * [OidcLoginEventType.KEYCLOAK_LINK_MANUAL_REMOVED]: the acting admin AND which Keycloak identity
 * was (un)linked. The subject is recorded as a SHA-256 fingerprint (first 16 hex chars = 64 bits),
 * not verbatim: `oidc_guest_login_event` is registered in `PersonalDataRegistry` as referencing
 * people only by unconstrained UUIDs and is excluded from the DSGVO erasure walk, so it must not
 * become a second, un-erasable copy of the raw foreign identifier (`keycloak_account_link` itself
 * IS erased via `KeycloakLinkPersonalData`). A fingerprint is still sufficient for forensics: an
 * investigator hashes the candidate subject(s) from the Keycloak admin console and compares.
 * Worst case ~70 characters, well within the column's 255.
 */
internal fun keycloakLinkAuditReason(
    adminMemberId: Uuid,
    subject: String,
): String = "admin=$adminMemberId subjectSha256=${keycloakSubjectFingerprint(subject)}"

/** Fresh [MessageDigest] per call -- thread-safe, see codebase security checklist "Kryptografie". */
internal fun keycloakSubjectFingerprint(subject: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(subject.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

private fun String.toMemberUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
