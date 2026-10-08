package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.mail.maskEmailForLogging
import network.lapis.cloud.server.member.AddressChangeSideEffects
import network.lapis.cloud.server.member.ApplyOutcome
import network.lapis.cloud.server.member.EmailChangeStore
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.rpc.MEMBER_EMAIL_MAX_LENGTH
import network.lapis.cloud.server.rpc.keycloakSubjectFingerprint
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EmailChangeAuditEvent
import network.lapis.cloud.shared.domain.EmailChangeAuditFacts
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.KeycloakIdpAuditEvent
import network.lapis.cloud.shared.domain.KeycloakIdpAuditFacts
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.73 "Keycloak: optionaler Profil-Abgleich" -- on a successful login of an ALREADY LINKED member, takes the display name
 * and (under strict rules) the address over from the verified ID token. **Opt-in** (`LAPIS_KEYCLOAK_SYNC_PROFILE`, default OFF; with
 * the option off this class is not even instantiated).
 *
 * **Never changed:** role, status, contribution, tier. **Name:** any role, when the token carries one and it differs. **Address:**
 * only when ALL hold -- the identity provider marks it verified; the account role is not BOARD / TREASURER / ADMIN (read under the
 * member lock); no address change is open (V1.9.56 / V1.9.57 flows are never overtaken); the address is syntactically valid and
 * belongs to nobody else. Otherwise the local address stays and only a fact-without-address lands in the audit log.
 *
 * The address is written exclusively through [EmailChangeStore] (`insertAppliedIdpSync` + `applyLocked`), the one writer of an existing
 * member's address; the audit entries carry flags and the change id, never a name or an address. Everything runs in one transaction
 * under the member lock (so two parallel logins produce one sync); the audit writes come last. After the commit, an applied address
 * ends all other sessions and reset tokens and warns the OLD address (masked new one). A failure of the whole sync never blocks the login
 * (the caller catches).
 */
internal class KeycloakProfileSync(
    private val notifier: KeycloakProvisioningNotifier,
) {
    data class Result(
        val nameChanged: Boolean,
        val emailChanged: Boolean,
        /** The skip reason of an address that differed but was not taken over, or null. */
        val skipped: KeycloakIdpAuditEvent?,
    )

    private data class SyncTx(
        val result: Result,
        val oldEmail: String,
        val newEmail: String,
        val now: LocalDateTime,
    )

    fun syncOnLogin(
        memberId: Uuid,
        subject: String,
        rawEmail: String,
        emailVerified: Boolean,
        claims: JWTClaimsSet,
    ): Result {
        val outcome =
            transaction {
                val now = DbClock.nowLocalDateTime()
                val member = EmailChangeStore.lockMember(memberId) ?: return@transaction null
                if (member[MemberTable.anonymizedAt] != null) return@transaction null
                // Read UNDER the member lock: a role change cannot slip in between the check and the write.
                val role =
                    AccountTable
                        .selectAll()
                        .where { AccountTable.memberId eq memberId }
                        .singleOrNull()
                        ?.get(AccountTable.role)
                val status = member[MemberTable.status]
                val before = MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = status, role = role)
                val audits = mutableListOf<MemberChangeSnapshot>()

                // ---- name ----
                var nameChanged = false
                val name = KeycloakProvisioningClaims.displayName(claims)
                if (name is KeycloakProvisioningClaims.NameResult.Ok && name.displayName != member[MemberTable.displayName]) {
                    MemberTable.update({ MemberTable.id eq memberId }) { it[displayName] = name.displayName }
                    nameChanged = true
                    audits +=
                        before.copy(
                            displayNameChanged = true,
                            keycloakIdp = KeycloakIdpAuditFacts(event = KeycloakIdpAuditEvent.NAME_SYNCED),
                        )
                }

                // ---- address ----
                var emailChanged = false
                var skipped: KeycloakIdpAuditEvent? = null
                val oldEmail = member[MemberTable.email]
                val newEmail = rawEmail.trim().lowercase()
                if (newEmail.isNotEmpty() && newEmail != oldEmail.lowercase()) {
                    skipped =
                        when {
                            !emailVerified -> KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_UNVERIFIED
                            role != null && role in ESCALATED_ROLES -> KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_PROTECTED_ROLE
                            EmailChangeStore.openChangeLocked(memberId) != null -> KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_OPEN_CHANGE
                            else -> null
                        }
                    val validAddress = newEmail.length <= MEMBER_EMAIL_MAX_LENGTH && isValidMailboxAddress(newEmail)
                    if (skipped == null && validAddress) {
                        val usedByAnother =
                            MemberTable
                                .selectAll()
                                .where { (MemberTable.email.lowerCase() eq newEmail) and (MemberTable.id neq memberId) }
                                .limit(1)
                                .any()
                        if (usedByAnother) {
                            skipped = KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_COLLISION
                        } else {
                            val changeId = EmailChangeStore.insertAppliedIdpSync(memberId = memberId, pendingEmail = newEmail, now = now)
                            when (
                                EmailChangeStore.applyLocked(
                                    memberId = memberId,
                                    changeId = changeId,
                                    newEmail = newEmail,
                                    verified = true,
                                    now = now,
                                )
                            ) {
                                ApplyOutcome.Applied -> {
                                    emailChanged = true
                                    audits +=
                                        before.copy(
                                            emailChanged = true,
                                            emailChange =
                                                EmailChangeAuditFacts(
                                                    event = EmailChangeAuditEvent.APPLIED,
                                                    kind = EmailChangeKind.IDP_SYNC,
                                                    changeId = changeId.toString(),
                                                ),
                                            keycloakIdp =
                                                KeycloakIdpAuditFacts(
                                                    event = KeycloakIdpAuditEvent.EMAIL_SYNCED,
                                                    emailChangeId = changeId.toString(),
                                                ),
                                        )
                                }
                                // A concurrent claim between the check and the write: applyLocked already finished the row as CONFLICT.
                                ApplyOutcome.Duplicate -> skipped = KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_COLLISION
                            }
                        }
                    }
                    if (skipped != null && !emailChanged) {
                        audits += before.copy(keycloakIdp = KeycloakIdpAuditFacts(event = skipped))
                    }
                }

                // ---- audit LAST (hash chain lock) ----
                audits.forEach { after ->
                    AuditLogRecorder.record(
                        actorMemberId = null,
                        actorRole = null,
                        entityType = AuditEntityType.MEMBER,
                        entityId = memberId,
                        action = AuditAction.UPDATE,
                        before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
                        after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
                        occurredAt = now,
                    )
                }
                SyncTx(
                    result = Result(nameChanged = nameChanged, emailChanged = emailChanged, skipped = skipped),
                    oldEmail = oldEmail,
                    newEmail = newEmail,
                    now = now,
                )
            } ?: return Result(nameChanged = false, emailChanged = false, skipped = null)

        val result = outcome.result
        if (result.skipped == KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_PROTECTED_ROLE) {
            // Operators see it without any personal data; the audit entry carries the member id.
            logger.warn {
                "Keycloak profile sync: address difference NOT taken over for a BOARD/TREASURER/ADMIN account " +
                    "(memberId=$memberId subjectFingerprint=${keycloakSubjectFingerprint(subject)})"
            }
        }
        if (result.emailChanged) {
            // After the commit: the address is the login identifier, so every other session and reset token ends.
            AddressChangeSideEffects.invalidateAfterAddressChange(memberId = memberId, exceptRawToken = null)
            notifier.emailSynced(
                oldEmail = outcome.oldEmail,
                maskedNewEmail = maskEmailForLogging(outcome.newEmail),
                occurredAt = outcome.now,
            )
        }
        return result
    }
}
