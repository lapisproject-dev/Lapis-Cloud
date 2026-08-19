package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Implements [IPaymentGatewayService] -- see that interface's KDoc. Welle V1.2.1
 * "Zahlungs-Fundament": ONLY the disclaimer-acknowledgment opt-in gate, exact mirror of
 * [AuctionService]'s own `enableAuction`/`disableAuction`/`getAuctionComplianceDisclaimer`/
 * `getAuctionSettings` shape.
 */
class PaymentGatewayService(
    private val call: ApplicationCall,
) : IPaymentGatewayService {
    override suspend fun getPaymentGatewayComplianceDisclaimer(): PaymentGatewayComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return PaymentGatewayComplianceDisclaimerDto(
            version = PaymentGatewayComplianceDisclaimer.VERSION,
            text = PaymentGatewayComplianceDisclaimer.TEXT,
            sha256 = PaymentGatewayComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enablePaymentGateway(
        provider: PaymentProvider,
        acknowledgment: PaymentGatewayComplianceAcknowledgmentInput,
    ): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (provider == PaymentProvider.MANUAL) {
            throw BadRequestException("provider must be PAYPAL or STRIPE, never MANUAL")
        }
        if (!PaymentGatewayComplianceDisclaimer.matches(
                version = acknowledgment.disclaimerVersion,
                sha256 = acknowledgment.disclaimerSha256,
            )
        ) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 do not match the current PaymentGatewayComplianceDisclaimer -- " +
                    "call getPaymentGatewayComplianceDisclaimer again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            PaymentGatewayComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = acknowledgment.disclaimerVersion
                it[disclaimerSha256] = acknowledgment.disclaimerSha256
                it[PaymentGatewayComplianceAcknowledgmentTable.provider] = provider
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = true
                it[paymentGatewayProvider] = provider
            }
            loadPaymentGatewaySettingsDto()
        }
    }

    override suspend fun disablePaymentGateway(): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = false
                // Security Round 1 (2026-08-19, nit): previously left the stale provider in place, so
                // a disabled gateway still reported a `paymentGatewayProvider` -- cleared here so
                // "disabled" and "no provider configured" are the same, unambiguous state again.
                it[paymentGatewayProvider] = null
            }
            loadPaymentGatewaySettingsDto()
        }
    }

    override suspend fun getPaymentGatewaySettings(): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadPaymentGatewaySettingsDto() }
    }

    private fun loadPaymentGatewaySettingsDto(): PaymentGatewaySettingsDto {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        val lastAck =
            PaymentGatewayComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return PaymentGatewaySettingsDto(
            paymentGatewayEnabled = settingsRow[OrganizationSettingsTable.paymentGatewayEnabled],
            paymentGatewayProvider = settingsRow[OrganizationSettingsTable.paymentGatewayProvider],
            lastAcknowledgedByDisplayName =
                lastAck?.let { memberDisplayName(it[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId]) },
            lastAcknowledgedAt = lastAck?.get(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt),
            lastDisclaimerVersion = lastAck?.get(PaymentGatewayComplianceAcknowledgmentTable.disclaimerVersion),
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]
}

/**
 * Security Round 1 (2026-08-19, SHOULD-3): `true` iff the most recently written
 * [PaymentGatewayComplianceAcknowledgmentTable] row's `disclaimerVersion` equals the CURRENT
 * [PaymentGatewayComplianceDisclaimer.VERSION] -- see [network.lapis.cloud.server.rpc
 * .sepaDisclaimerIsCurrentlyAcknowledged] KDoc for the identical rationale, mechanism, and "no
 * current call site" status this exact mirror shares.
 */
fun paymentGatewayDisclaimerIsCurrentlyAcknowledged(): Boolean =
    transaction {
        PaymentGatewayComplianceAcknowledgmentTable
            .selectAll()
            .orderBy(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(PaymentGatewayComplianceAcknowledgmentTable.disclaimerVersion) == PaymentGatewayComplianceDisclaimer.VERSION
    }
