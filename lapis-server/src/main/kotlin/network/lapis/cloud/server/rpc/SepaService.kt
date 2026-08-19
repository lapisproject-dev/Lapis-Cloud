package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SepaComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.SepaSettingsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ISepaService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Implements [ISepaService] -- see that interface's KDoc. Welle V1.2.1 "Zahlungs-Fundament": ONLY
 * the disclaimer-acknowledgment opt-in gate, exact mirror of [AuctionService]'s own
 * `enableAuction`/`disableAuction`/`getAuctionComplianceDisclaimer`/`getAuctionSettings` shape.
 */
class SepaService(
    private val call: ApplicationCall,
) : ISepaService {
    override suspend fun getSepaComplianceDisclaimer(): SepaComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return SepaComplianceDisclaimerDto(
            version = SepaComplianceDisclaimer.VERSION,
            text = SepaComplianceDisclaimer.TEXT,
            sha256 = SepaComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enableSepaDebit(input: SepaComplianceAcknowledgmentInput): SepaSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (!SepaComplianceDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 do not match the current SepaComplianceDisclaimer -- " +
                    "call getSepaComplianceDisclaimer again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            SepaComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = input.disclaimerVersion
                it[disclaimerSha256] = input.disclaimerSha256
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[sepaDebitEnabled] = true
            }
            loadSepaSettingsDto()
        }
    }

    override suspend fun disableSepaDebit(): SepaSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[sepaDebitEnabled] = false
            }
            loadSepaSettingsDto()
        }
    }

    override suspend fun getSepaSettings(): SepaSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadSepaSettingsDto() }
    }

    private fun loadSepaSettingsDto(): SepaSettingsDto {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        val lastAck =
            SepaComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(SepaComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return SepaSettingsDto(
            sepaDebitEnabled = settingsRow[OrganizationSettingsTable.sepaDebitEnabled],
            lastAcknowledgedByDisplayName =
                lastAck?.let { memberDisplayName(it[SepaComplianceAcknowledgmentTable.acknowledgedByMemberId]) },
            lastAcknowledgedAt = lastAck?.get(SepaComplianceAcknowledgmentTable.acknowledgedAt),
            lastDisclaimerVersion = lastAck?.get(SepaComplianceAcknowledgmentTable.disclaimerVersion),
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]
}

/**
 * Security Round 1 (2026-08-19, SHOULD-3): `true` iff the most recently written
 * [SepaComplianceAcknowledgmentTable] row's `disclaimerVersion` equals the CURRENT
 * [SepaComplianceDisclaimer.VERSION] -- i.e. whether `sepaDebitEnabled=true` still rests on an
 * acknowledgment of the disclaimer's CURRENT wording, not a stale prior version (which happens iff
 * [SepaComplianceDisclaimer.TEXT]/[SepaComplianceDisclaimer.VERSION] is revised AFTER an ADMIN
 * already acknowledged an older version -- see that object's own KDoc: a wording change always
 * requires a NEW `VERSION`, never an in-place edit). `false` both when there is no acknowledgment
 * row at all AND when the latest one is stale -- both cases are treated identically to "not
 * currently acknowledged".
 *
 * No V1.2.1 call site gates real behaviour on this yet -- this wave ships ONLY the
 * disclaimer-acknowledgment enable/disable toggle itself, no SEPA mandate/pain.008 functionality
 * exists to gate (same inherited-not-new gap [AuctionComplianceDisclaimer] has always had, not
 * closed here either -- see that object's own KDoc). Exists now as a small, standalone, public
 * helper (own `transaction {}`, safely callable both stand-alone and from inside an already-open
 * one -- Exposed transactions are reentrant per thread) so a LATER wave (SEPA mandate creation,
 * pain.008 batch generation) can call this as its own "is this feature actually usable" gate,
 * without having to remember to add version-staleness checking from scratch once real behaviour
 * lands behind it.
 */
fun sepaDisclaimerIsCurrentlyAcknowledged(): Boolean =
    transaction {
        SepaComplianceAcknowledgmentTable
            .selectAll()
            .orderBy(SepaComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(SepaComplianceAcknowledgmentTable.disclaimerVersion) == SepaComplianceDisclaimer.VERSION
    }
