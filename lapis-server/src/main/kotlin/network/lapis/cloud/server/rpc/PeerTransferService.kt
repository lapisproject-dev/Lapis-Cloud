package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArbitrationTransferInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.PeerTransferResultDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPeerTransferService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val PEER_TRANSFER_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [LtrLedgerService]'s own `MIN_MINT_LTR`. Pure Spamschutz floor, not a fachlich-motivated minimum transfer size. */
private val MIN_TRANSFER_LTR = BigDecimal("0.01")

private val logger = KotlinLogging.logger {}

/**
 * V0.6.3 direkte LTR-Peer-to-Peer-Uebertragung -- see [IPeerTransferService] KDoc and
 * `18-peer-transfer.kuml.kts` file header for the full fachlich model. [ltrBalanceProvider]
 * defaults to [LedgerBackedLtrBalanceProvider], same seam [GovernanceService]/[CrowdfundingService]
 * use.
 *
 * **Two-account locking**: unlike every prior LTR-debiting call site (which only ever locks the
 * single, already-authenticated `current.memberId`), a transfer locks BOTH the sender's and the
 * recipient's [MemberTable] row -- see [lockBothAccounts] KDoc for the canonical lock-order this
 * requires to avoid a deadlock between two concurrent, opposite-direction transfers (A->B and
 * B->A). Because [MemberTable.selectAll]/[org.jetbrains.exposed.v1.core.eq]-based existence
 * checks must run BEFORE that lock for any client-supplied (not already-authenticated) id -- see
 * [requireMemberExists] KDoc -- this is also the first call site in this codebase to lock a
 * member row that was never resolved via [resolveCurrentMember].
 */
class PeerTransferService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : IPeerTransferService {
    override suspend fun transferLtr(input: PeerTransferInput): PeerTransferResultDto {
        val current = resolveCurrentMember(call)
        val recipientId = input.recipientMemberId.toMemberUuidOrThrow()
        if (recipientId == current.memberId) throw ConflictException("Cannot transfer LTR to yourself")
        val normalizedAmount = validateAndNormalizeAmount(input.amountLtr)
        val now = nowLocalDateTime()
        return transaction {
            // current.memberId is already authenticated via resolveCurrentMember and therefore
            // always exists -- checked anyway as defense in depth, symmetric with the recipient
            // check right below, so lockBothAccounts never receives an unverified id.
            requireMemberExists(current.memberId)
            requireMemberExists(recipientId)
            lockBothAccounts(current.memberId, recipientId)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (normalizedAmount > freeBalance) {
                throw ConflictException("amountLtr $normalizedAmount exceeds free LTR balance $freeBalance")
            }
            executeTransfer(
                senderId = current.memberId,
                recipientId = recipientId,
                amount = normalizedAmount,
                characterizationInput = input.characterization,
                purposeText = input.purpose,
                initiatedByMemberId = null,
                now = now,
            )
        }
    }

    override suspend fun executeArbitrationTransfer(input: ArbitrationTransferInput): PeerTransferResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PEER_TRANSFER_TREASURY_ROLES)
        val senderId = input.senderMemberId.toMemberUuidOrThrow()
        val recipientId = input.recipientMemberId.toMemberUuidOrThrow()
        if (senderId == recipientId) throw ConflictException("senderMemberId and recipientMemberId must differ")
        if (input.purpose.isBlank()) {
            throw ConflictException("executeArbitrationTransfer requires a non-blank purpose (Schiedsanordnungs-Referenz)")
        }
        val normalizedAmount = validateAndNormalizeAmount(input.amountLtr)
        val now = nowLocalDateTime()
        return transaction {
            requireMemberExists(senderId)
            requireMemberExists(recipientId)
            lockBothAccounts(senderId, recipientId)
            val freeBalance = ltrBalanceProvider.freeBalance(senderId)
            if (normalizedAmount > freeBalance) {
                throw ConflictException("amountLtr $normalizedAmount exceeds free LTR balance $freeBalance for sender $senderId")
            }
            val result =
                executeTransfer(
                    senderId = senderId,
                    recipientId = recipientId,
                    amount = normalizedAmount,
                    characterizationInput = input.characterization,
                    purposeText = input.purpose,
                    initiatedByMemberId = current.memberId,
                    now = now,
                )
            logger.info {
                "Arbitration transfer executed by ${current.memberId}: $normalizedAmount LTR from $senderId to $recipientId " +
                    "(transferId=${result.transferId}, purpose='${input.purpose}')"
            }
            result
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Canonical lock order: the two ids are compared lexicographically by [Uuid.toString] (a
     * plain, deterministic total order both transactions agree on regardless of which side is
     * sender vs. recipient in EITHER of them), the "smaller" one locked first. This closes the
     * classic A-to-B / B-to-A deadlock: two concurrent, opposite-direction transfers between the
     * same two members always attempt their locks in the SAME global order, so one blocks on the
     * other's first lock instead of each holding one lock and waiting on the other's second.
     * Reuses [LtrBalanceProvider.lockForDebit] for BOTH accounts -- its own KDoc only describes
     * the debit (sender) case, but the underlying mechanism (`SELECT ... FOR UPDATE` on the
     * member row) is exactly what a recipient-side credit also needs serialized against any other
     * concurrent write touching that same member's balance-defining ledger rows.
     */
    private fun lockBothAccounts(
        a: Uuid,
        b: Uuid,
    ) {
        val (first, second) = if (a.toString() <= b.toString()) a to b else b to a
        ltrBalanceProvider.lockForDebit(first)
        ltrBalanceProvider.lockForDebit(second)
    }

    /**
     * Must run BEFORE [lockBothAccounts] for any id that did not come from [resolveCurrentMember]
     * -- [LtrBalanceProvider.lockForDebit] throws a raw, non-RPC-safe `IllegalStateException` for
     * an unknown member (see its KDoc), not [NotFoundException]. Every prior LTR-debiting call
     * site only ever locked the already-authenticated caller, so this gap never mattered before;
     * a peer transfer's recipient (and, for [executeArbitrationTransfer], BOTH ids) is the first
     * ungated client input reaching that lock.
     */
    private fun requireMemberExists(memberId: Uuid) {
        MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
            ?: throw NotFoundException("Member $memberId not found")
    }

    private fun validateAndNormalizeAmount(amount: BigDecimal): BigDecimal {
        if (amount.scale() > 2) throw ConflictException("amountLtr must have at most 2 decimal places")
        val normalized = amount.setScale(2, RoundingMode.UNNECESSARY)
        if (normalized < MIN_TRANSFER_LTR) throw ConflictException("amountLtr must be at least $MIN_TRANSFER_LTR")
        return normalized
    }

    /**
     * Writes `peer_transfer` plus both signed `ltr_ledger_entry` rows atomically. Caller's
     * `transaction {}` block; must run AFTER both accounts are locked ([lockBothAccounts]) and
     * the sender's free balance has been verified sufficient.
     *
     * Parameter names deliberately avoid [PeerTransferTable]'s own column property names
     * (`characterization`/`purpose`/`initiatedBy`) -- inside `PeerTransferTable.insert { ... }`
     * below, that Table is the lambda's implicit receiver, so a same-named outer parameter would
     * be shadowed by the Table's own column property for any bare reference in that scope
     * (Kotlin resolves the closer, lambda-receiver-introduced scope first), silently producing
     * `it[col] = col` instead of `it[col] = <the intended value>` -- a compile-time type mismatch
     * at best, an easy-to-miss footgun at worst. Explicit, non-colliding names sidestep the whole
     * question rather than relying on remembering the shadowing rule at every call site.
     */
    private fun executeTransfer(
        senderId: Uuid,
        recipientId: Uuid,
        amount: BigDecimal,
        characterizationInput: PeerTransferCharacterization,
        purposeText: String?,
        initiatedByMemberId: Uuid?,
        now: LocalDateTime,
    ): PeerTransferResultDto {
        val transferId = Uuid.random()
        PeerTransferTable.insert {
            it[id] = transferId
            it[amountLtr] = amount
            it[characterization] = characterizationInput
            it[purpose] = purposeText
            it[senderMemberId] = senderId
            it[recipientMemberId] = recipientId
            it[initiatedBy] = initiatedByMemberId
            it[createdAt] = now
        }
        val outEntryId = Uuid.random()
        LtrLedgerEntryTable.insert {
            it[LtrLedgerEntryTable.id] = outEntryId
            it[memberId] = senderId
            it[entryType] = LtrLedgerEntryType.PEER_TRANSFER_OUT
            it[amountLtr] = amount.negate()
            it[referenceType] = LtrLedgerReferenceType.PEER_TRANSFER
            it[referenceId] = transferId
            it[note] = purposeText
            it[createdBy] = initiatedByMemberId
            it[createdAt] = now
        }
        val inEntryId = Uuid.random()
        LtrLedgerEntryTable.insert {
            it[LtrLedgerEntryTable.id] = inEntryId
            it[memberId] = recipientId
            it[entryType] = LtrLedgerEntryType.PEER_TRANSFER_IN
            it[amountLtr] = amount
            it[referenceType] = LtrLedgerReferenceType.PEER_TRANSFER
            it[referenceId] = transferId
            it[note] = purposeText
            it[createdBy] = initiatedByMemberId
            it[createdAt] = now
        }
        return PeerTransferResultDto(
            transferId = transferId.toString(),
            senderMemberId = senderId.toString(),
            senderDisplayName = memberDisplayName(senderId),
            recipientMemberId = recipientId.toString(),
            recipientDisplayName = memberDisplayName(recipientId),
            amountLtr = amount,
            characterization = characterizationInput,
            purpose = purposeText,
            initiatedById = initiatedByMemberId?.toString(),
            initiatedByDisplayName = initiatedByMemberId?.let { memberDisplayName(it) },
            outEntryId = outEntryId.toString(),
            inEntryId = inEntryId.toString(),
            createdAt = now,
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
