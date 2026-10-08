package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberNumberSequenceTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.KeycloakProvisioningMailer
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

internal const val JIT_ISSUER = "https://keycloak-jit-test.example.org/realms/lapis"

/** Test-only [KeycloakProvisioningMailer] that records every notice. [failing] makes every send throw. */
internal class RecordingProvisioningMailer(
    private val failing: Boolean = false,
) : KeycloakProvisioningMailer {
    data class Provisioned(
        val email: String,
        val name: String,
    )

    data class Synced(
        val email: String,
        val maskedNew: String,
    )

    val provisioned = CopyOnWriteArrayList<Provisioned>()
    val synced = CopyOnWriteArrayList<Synced>()

    override fun sendMemberProvisioned(
        email: String,
        newMemberName: String,
        occurredAt: LocalDateTime,
    ) {
        if (failing) error("mailer down")
        provisioned += Provisioned(email = email, name = newMemberName)
    }

    override fun sendEmailSyncedToOldAddress(
        email: String,
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
    ) {
        if (failing) error("mailer down")
        synced += Synced(email = email, maskedNew = maskedNewEmail)
    }
}

/** A Keycloak configuration with the provisioning options as given; always otherwise complete. */
internal fun jitConfig(
    autoProvision: Boolean = true,
    group: String? = "apolda",
    claim: String? = null,
    ratePerHour: Int? = null,
    requireVerifiedEmail: Boolean = true,
    syncProfile: Boolean = false,
): KeycloakConfig {
    val env =
        buildMap {
            put(KeycloakConfig.ENV_ENABLED, "true")
            put(KeycloakConfig.ENV_ISSUER_URL, JIT_ISSUER)
            put(KeycloakConfig.ENV_CLIENT_ID, "lapis-cloud-test-client")
            put(KeycloakConfig.ENV_CLIENT_SECRET, "test-client-secret")
            put(KeycloakConfig.ENV_REQUIRE_VERIFIED_EMAIL, requireVerifiedEmail.toString())
            put(KeycloakConfig.ENV_AUTO_PROVISION, autoProvision.toString())
            put(KeycloakConfig.ENV_SYNC_PROFILE, syncProfile.toString())
            if (group != null) put(KeycloakConfig.ENV_PROVISION_GROUP, group)
            if (claim != null) put(KeycloakConfig.ENV_PROVISION_CLAIM, claim)
            // generous by default so a spec that creates many members never trips the hourly limit by accident
            put(KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR, (ratePerHour ?: 500).toString())
        }
    return KeycloakConfig.load { env[it] }
}

/** Verified-token claims of an identity-provider user. [extra] adds arbitrary claims (e.g. a hostile `roles`). */
internal fun idClaims(
    email: String,
    name: String? = "Erika Muster",
    groups: Any? = listOf("apolda"),
    groupsClaim: String = "groups",
    extra: Map<String, Any?> = emptyMap(),
): JWTClaimsSet {
    val builder = JWTClaimsSet.Builder().subject("jit-sub-${Uuid.random()}")
    builder.claim("email", email)
    if (name != null) builder.claim("name", name)
    if (groups != null) builder.claim(groupsClaim, groups)
    extra.forEach { (k, v) -> builder.claim(k, v) }
    return builder.build()
}

internal fun uniqueEmail(prefix: String = "jit"): String = "$prefix-${Uuid.random().toString().take(12)}@example.org"

/** Every table row that belongs to [memberIds], deleted in FK order. Audit entries stay (hash chain, no FK). */
internal fun deleteMembersCompletely(memberIds: Collection<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        val ids = memberIds.toList()
        MemberEmailChangeTable.deleteWhere { memberId inList ids }
        KeycloakAccountLinkTable.deleteWhere { memberId inList ids }
        AccountTable.deleteWhere { memberId inList ids }
        MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList ids }
        MemberTable.deleteWhere { id inList ids }
    }
}

/** Removes the member-number sequence row of [year] -- a test that pins the clock to an otherwise unused year restores the table. */
internal fun deleteMemberNumberSequence(year: Int) {
    transaction { MemberNumberSequenceTable.deleteWhere { allocationYear eq year } }
}

internal fun memberIdByEmail(email: String): Uuid? =
    transaction {
        MemberTable
            .selectAll()
            .where { MemberTable.email eq email }
            .singleOrNull()
            ?.get(MemberTable.id)
    }

internal fun memberCountByEmail(email: String): Long = transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() }

internal fun memberAuditJson(memberId: Uuid): List<String> =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .where { (AuditLogEntryTable.entityId eq memberId) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
            .orderBy(AuditLogEntryTable.sequenceNumber)
            .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + " " + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
    }
