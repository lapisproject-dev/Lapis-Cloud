package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.member.EmailChangeStore
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.MemberNumberAllocator
import network.lapis.cloud.server.member.MemberStatusHistory
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.rpc.MEMBER_EMAIL_MAX_LENGTH
import network.lapis.cloud.server.rpc.keycloakSubjectFingerprint
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.KeycloakIdpAuditEvent
import network.lapis.cloud.shared.domain.KeycloakIdpAuditFacts
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.73 "Keycloak: Just-in-time-Anlage" -- creates a member on the FIRST Keycloak login when the verified ID token carries
 * the configured group and [KeycloakAccountLinker] found no member. **Opt-in** (`LAPIS_KEYCLOAK_AUTO_PROVISION`, default OFF), a
 * separate stage that runs only after the linker answered `NO_MATCHING_MEMBER`; the linker itself still never creates anything.
 *
 * **What is created.** An ACTIVE member (no tier, no regional chapter, no join-contract acknowledgment -- the precedent is
 * `RegistrationService.createMemberDirect`), a status-history row (`KEYCLOAK_JIT`), an account with the LITERAL role
 * [AccountRole.MEMBER] and no password, a `keycloak_account_link` (`linked_by = NULL`), a member number, the `MEMBER_CREATED`
 * webhook and one `MEMBER`/`CREATE` audit entry without any name or address.
 *
 * **Security properties.**
 * - Claims come ONLY from the verified ID token (see [KeycloakProvisioningClaims]). The role is a constant, never derived from a claim.
 * - A member whose address matches (or an existing link for the `(issuer, subject)`) always wins: [Outcome.ExistingFound], never a duplicate.
 * - `email_verified` must be `true` regardless of `LAPIS_KEYCLOAK_REQUIRE_VERIFIED_EMAIL`.
 * - The whole decision runs under a `FOR UPDATE` lock on the organization-settings singleton: re-resolution, the hourly rate count
 *   and the insert are one atomic step per database. SQLSTATE 23505 is only the backstop. The block never retries (`maxAttempts = 1`).
 * - The rate limit is a database count of `KEYCLOAK_JIT` history rows of the last hour, so it survives restarts and spans instances.
 * - Nothing personal is logged: only reason codes and the subject fingerprint.
 *
 * Lock order: `organization_settings` -> new member row -> `member_status_history` -> `account` -> `keycloak_account_link` ->
 * `member_number_sequence` -> webhook outbox -> audit chain (last, as everywhere).
 */
internal class KeycloakMemberProvisioner(
    private val config: KeycloakConfig,
    private val notifier: KeycloakProvisioningNotifier,
) {
    enum class RejectReason {
        DISABLED,
        GROUP_MISSING,
        GROUP_NO_MATCH,
        GROUP_WRONG_TYPE,
        EMAIL_NOT_VERIFIED,
        INVALID_EMAIL,
        NAME_MISSING,
        RATE_LIMITED,
    }

    sealed interface Outcome {
        data class Provisioned(
            val memberId: Uuid,
            val occurredAt: LocalDateTime,
        ) : Outcome

        /** A member or a link for this identity exists (or appeared by a race): the caller resolves through the linker exactly once more. */
        data object ExistingFound : Outcome

        data class Rejected(
            val reason: RejectReason,
        ) : Outcome
    }

    /** Result of the transaction body; only [Outcome]s leave the transaction, never an exception for an expected case. */
    private sealed interface TxResult {
        data object Existing : TxResult

        data object RateLimited : TxResult

        data class Created(
            val memberId: Uuid,
            val now: LocalDateTime,
        ) : TxResult
    }

    fun provision(
        issuer: String,
        subject: String,
        rawEmail: String,
        emailVerified: Boolean,
        claims: JWTClaimsSet,
    ): Outcome {
        val requiredGroup = config.provisionGroup
        if (!config.autoProvision || requiredGroup == null) return Outcome.Rejected(RejectReason.DISABLED)

        when (KeycloakProvisioningClaims.checkGroup(claims = claims, claimName = config.provisionClaim, requiredGroup = requiredGroup)) {
            KeycloakProvisioningClaims.GroupCheck.MATCH -> Unit
            KeycloakProvisioningClaims.GroupCheck.MISSING -> return Outcome.Rejected(RejectReason.GROUP_MISSING)
            KeycloakProvisioningClaims.GroupCheck.NO_MATCH -> return Outcome.Rejected(RejectReason.GROUP_NO_MATCH)
            KeycloakProvisioningClaims.GroupCheck.WRONG_TYPE -> return Outcome.Rejected(RejectReason.GROUP_WRONG_TYPE)
        }
        // Stricter than the linker: independent of LAPIS_KEYCLOAK_REQUIRE_VERIFIED_EMAIL -- a NEW account is never created for an
        // address the identity provider did not verify.
        if (!emailVerified) return Outcome.Rejected(RejectReason.EMAIL_NOT_VERIFIED)
        val email = rawEmail.trim().lowercase()
        if (email.isEmpty() || email.length > MEMBER_EMAIL_MAX_LENGTH || !isValidMailboxAddress(email)) {
            return Outcome.Rejected(RejectReason.INVALID_EMAIL)
        }
        val displayName =
            when (val name = KeycloakProvisioningClaims.displayName(claims)) {
                is KeycloakProvisioningClaims.NameResult.Ok -> name.displayName
                KeycloakProvisioningClaims.NameResult.Missing -> return Outcome.Rejected(RejectReason.NAME_MISSING)
            }

        val result =
            try {
                transaction {
                    // NEVER retry: Exposed would replay the block on any SQLException; the lock below makes one attempt authoritative.
                    maxAttempts = 1
                    // Serializes every just-in-time creation of this database (re-resolution + rate count + insert are one step).
                    OrganizationSettingsTable.selectAll().forUpdate().single()
                    val now = DbClock.nowLocalDateTime()

                    val linkExists =
                        KeycloakAccountLinkTable
                            .selectAll()
                            .where {
                                (KeycloakAccountLinkTable.keycloakIssuer eq issuer) and
                                    (KeycloakAccountLinkTable.keycloakSubject eq subject)
                            }.limit(1)
                            .any()
                    val memberExists =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.email.lowerCase() eq email }
                            .limit(1)
                            .any()
                    if (linkExists || memberExists) return@transaction TxResult.Existing

                    val cutoff = EmailChangeStore.plus(at = now, duration = (-1).hours)
                    val recent =
                        MemberStatusHistoryTable
                            .selectAll()
                            .where {
                                (MemberStatusHistoryTable.sourceKind eq MemberStatusHistorySource.KEYCLOAK_JIT.name) and
                                    (MemberStatusHistoryTable.effectiveFrom greaterEq cutoff) and
                                    (MemberStatusHistoryTable.effectiveFrom lessEq now)
                            }.count()
                    if (recent >= config.provisionRatePerHour) return@transaction TxResult.RateLimited

                    val memberId = Uuid.random()
                    MemberTable.insert {
                        it[id] = memberId
                        it[MemberTable.displayName] = displayName
                        it[MemberTable.email] = email
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = OrganizationTimeZone.dateOf(now)
                        it[membershipTierId] = null
                        it[regionalChapterId] = null
                        // The identity provider verified the address (checked above).
                        it[emailVerifiedAt] = now
                    }
                    MemberStatusHistory.recordLocked(
                        memberId = memberId,
                        newStatus = MemberStatus.ACTIVE,
                        now = now,
                        source = MemberStatusHistorySource.KEYCLOAK_JIT,
                    )
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[AccountTable.memberId] = memberId
                        // A literal constant -- the role is NEVER read from a claim.
                        it[role] = AccountRole.MEMBER
                        it[roleChangedAt] = now
                        it[passwordHash] = null
                    }
                    KeycloakAccountLinkTable.insert {
                        it[id] = Uuid.random()
                        it[KeycloakAccountLinkTable.memberId] = memberId
                        it[keycloakIssuer] = issuer
                        it[keycloakSubject] = subject
                        it[linkedAt] = now
                        it[linkedBy] = null
                        it[lastLoginAt] = now
                    }
                    MemberNumberAllocator.ensureFor(memberId)
                    WebhookEventPublisher.publish(eventType = WebhookEventType.MEMBER_CREATED, entityId = memberId, occurredAt = now)
                    // Last lock-taking operation, as everywhere. No name, no address: only flags and the Keycloak fact.
                    AuditLogRecorder.record(
                        actorMemberId = null,
                        actorRole = null,
                        entityType = AuditEntityType.MEMBER,
                        entityId = memberId,
                        action = AuditAction.CREATE,
                        before = null,
                        after =
                            Json.encodeToString(
                                MemberChangeSnapshot.serializer(),
                                MemberChangeSnapshot(
                                    displayNameChanged = false,
                                    emailChanged = false,
                                    status = MemberStatus.ACTIVE,
                                    role = AccountRole.MEMBER,
                                    keycloakIdp = KeycloakIdpAuditFacts(event = KeycloakIdpAuditEvent.PROVISIONED),
                                ),
                            ),
                        occurredAt = now,
                    )
                    TxResult.Created(memberId = memberId, now = now)
                }
            } catch (e: ExposedSQLException) {
                // The backstop of the lock above (a concurrent writer of another path claiming the address or the identity).
                if (e.isUniqueViolation()) return Outcome.ExistingFound
                throw e
            }

        return when (result) {
            TxResult.Existing -> Outcome.ExistingFound
            TxResult.RateLimited -> {
                logger.warn { "Keycloak JIT rate limit reached (limit=${config.provisionRatePerHour}/h)" }
                Outcome.Rejected(RejectReason.RATE_LIMITED)
            }
            is TxResult.Created -> {
                logger.info {
                    "Keycloak JIT provisioned memberId=${result.memberId} subjectFingerprint=${keycloakSubjectFingerprint(
                        subject,
                    )}"
                }
                // After the commit, best effort: never blocks the login.
                notifier.memberProvisioned(newMemberId = result.memberId, occurredAt = result.now)
                Outcome.Provisioned(memberId = result.memberId, occurredAt = result.now)
            }
        }
    }
}
