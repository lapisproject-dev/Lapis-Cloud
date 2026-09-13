package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VatComplianceAcknowledgmentTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.VatComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.VatComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.VatSettingsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IVatService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val VAT_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)". Implements [IVatService] -- structure mirrors
 * [DunningService]'s own "Gate + Rechtshinweis" section line-for-line. Logging via kotlin-logging
 * (Hauskonvention) -- **niemals** Disclaimer-Hash, -Version oder Betraege in Logs.
 */
class VatService(
    private val call: ApplicationCall,
) : IVatService {
    override suspend fun getVatComplianceDisclaimer(): VatComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*VAT_READ_ROLES)
        return VatComplianceDisclaimerDto(
            version = VatComplianceDisclaimer.VERSION,
            text = VatComplianceDisclaimer.TEXT,
            sha256 = VatComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enableVat(input: VatComplianceAcknowledgmentInput): VatSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (!VatComplianceDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 stimmen nicht mit dem aktuellen VatComplianceDisclaimer ueberein -- " +
                    "getVatComplianceDisclaimer erneut aufrufen und dessen AKTUELLE version/sha256 unveraendert senden.",
            )
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            VatComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = input.disclaimerVersion
                it[disclaimerSha256] = input.disclaimerSha256
            }
            val wasEnabled = loadVatEnabled()
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[vatEnabled] = true
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.UPDATE,
                before = vatEnabledSnapshotJson(wasEnabled),
                after = vatEnabledSnapshotJson(true),
            )
            loadVatSettingsDto()
        }
    }

    /**
     * ADMIN. Keine Quittung noetig (Abschalten ist nie das Risiko). `isKleinunternehmer` bleibt
     * UNVERAENDERT stehen -- es ist keine Folgeeinstellung, sondern eine gespeicherte
     * Vorstandsangabe; ein Wieder-Einschalten soll sie nicht verlieren.
     */
    override suspend fun disableVat(): VatSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val wasEnabled = loadVatEnabled()
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[vatEnabled] = false
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.UPDATE,
                before = vatEnabledSnapshotJson(wasEnabled),
                after = vatEnabledSnapshotJson(false),
            )
            loadVatSettingsDto()
        }
    }

    override suspend fun getVatSettings(): VatSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*VAT_READ_ROLES)
        return transaction { loadVatSettingsDto() }
    }

    private fun loadVatEnabled(): Boolean =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .single()[OrganizationSettingsTable.vatEnabled]

    /** Minimal, human-readable-enough `before`/`after` audit snapshot -- a plain boolean flip does
     *  not warrant its own `@Serializable` snapshot type (same "configuration, not a per-member
     *  fact" reasoning [network.lapis.cloud.shared.domain.DunningLevelSnapshot] KDoc uses, just
     *  simpler still: there is exactly one field that ever changes here). */
    private fun vatEnabledSnapshotJson(enabled: Boolean): String = "{\"vatEnabled\":$enabled}"

    private fun loadVatSettingsDto(): VatSettingsDto {
        val settingsRow = OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
        val lastAck =
            VatComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(VatComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        val lastVersion = lastAck?.get(VatComplianceAcknowledgmentTable.disclaimerVersion)
        return VatSettingsDto(
            vatEnabled = settingsRow[OrganizationSettingsTable.vatEnabled],
            isKleinunternehmer = settingsRow[OrganizationSettingsTable.isKleinunternehmer],
            lastDisclaimerVersion = lastVersion,
            lastAcknowledgedAt = lastAck?.get(VatComplianceAcknowledgmentTable.acknowledgedAt),
            disclaimerCurrent = lastVersion == VatComplianceDisclaimer.VERSION,
        )
    }
}
