package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.sepa.BicValidator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.payment.sepa.SepaBatchItemSpec
import network.lapis.cloud.server.payment.sepa.SepaBatchSpec
import network.lapis.cloud.server.payment.sepa.SepaCharacterSet
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.server.payment.sepa.SepaMandateReferenceGenerator
import network.lapis.cloud.server.payment.sepa.SepaPain008Writer
import network.lapis.cloud.server.payment.sepa.SepaPrenotificationCalculator
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SepaComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsInput
import network.lapis.cloud.shared.domain.SepaCreditorSettingsSnapshot
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchExclusionDto
import network.lapis.cloud.shared.domain.SepaDebitBatchInput
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewDto
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewItemDto
import network.lapis.cloud.shared.domain.SepaDebitBatchSnapshot
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitExclusionReason
import network.lapis.cloud.shared.domain.SepaDebitItemDto
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateInput
import network.lapis.cloud.shared.domain.SepaMandateSnapshot
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaPrenotificationDto
import network.lapis.cloud.shared.domain.SepaReturnDto
import network.lapis.cloud.shared.domain.SepaReturnInput
import network.lapis.cloud.shared.domain.SepaReturnReasonSets
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.domain.SepaSettingsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ISepaService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val SEPA_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val SEPA_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
private val SEPA_BATCH_IN_FLIGHT_STATUSES =
    listOf(SepaDebitBatchStatus.DRAFT, SepaDebitBatchStatus.NOTIFIED, SepaDebitBatchStatus.GENERATED)

/**
 * V1.2.2 "SEPA-Lastschriftmandate" ceiling on a single run -- named constant with justification, same
 * precedent as `MAX_INVITATION_RECIPIENTS`/`MAX_POSTAL_INVITATION_RECIPIENTS`. A DoS/misoperation
 * guard, not a business rule -- if an organization genuinely needs more it is a follow-up wave, not a
 * silent unbounded query.
 */
private const val MAX_BATCH_ITEMS = 5_000

/**
 * German SEPA creditor-identifier format: country code + check digits + business-area code +
 * national identifier. Format check ONLY -- the check digit follows the same Mod-97-10 procedure as
 * an IBAN, but this wave deliberately does NOT implement it (see `updateSepaCreditorSettings` KDoc):
 * a wrongly implemented CI check digit that rejects a genuinely valid id would be worse than none.
 */
private val CREDITOR_ID_REGEX = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{3}[A-Z0-9]{1,28}$")

private val sepaMessageIdRandom = SecureRandom()

private val logger = KotlinLogging.logger {}

/**
 * Implements [ISepaService] -- see that interface's KDoc. Welle V1.2.1 "Zahlungs-Fundament" shipped
 * only the disclaimer-acknowledgment opt-in gate (the four methods above). Welle V1.2.2
 * "SEPA-Lastschriftmandate" (vault plan "sepa_v1.2.2_plan.md") adds real mandate lifecycle
 * management, pain.008 batch generation, and return processing -- additively, on this SAME class, per
 * that interface's own KDoc.
 *
 * ## Framework rules for every new method here (plan Teil 7.0)
 *
 * 1. Role gate first, before any lookup.
 * 2. Feature gate via [requireSepaUsable] (writes) or [requireSepaReadable] (reads).
 * 3. [network.lapis.cloud.server.audit.AuditLogRecorder.record] is the LAST lock-taking operation of
 *    every writing transaction, at most once per transaction.
 * 4. [NotFoundException], never [network.lapis.cloud.shared.rpc.ForbiddenException], for a foreign
 *    resource a caller has no legitimate reason to probe -- no existence oracle.
 * 5. The full IBAN never leaves this class -- no DTO, no log, no exception message, no audit
 *    snapshot ever carries it.
 *
 * ## Rate limiting (Security Round 1, 2026-08-20, MINOR-4)
 *
 * [mandateWriteRateLimiter] throttles [grantMandate]/[revokeMandate] -- both are member-reachable,
 * unaudited-for-volume mutations that each take a lock on the SAME global audit-chain row that
 * serializes every audited write in the whole application (see
 * [network.lapis.cloud.server.audit.AuditLogRecorder] KDoc), so an unthrottled member could
 * otherwise flood that single hot row. Reuses [FederationInboxRateLimiter] as a plain per-member
 * REQUEST-rate limiter, same "many legitimate calls must not each look like a failure" reasoning
 * [ConferenceStreamingService]'s own `mutateRateLimiter` KDoc gives -- NOT [LoginRateLimiter]
 * (failure-count-only, wrong shape here: a legitimate member retrying a typo'd IBAN a few times in
 * a row must not be treated as an attacker).
 *
 * ### Constructor default exists for tests only -- production MUST pass a shared instance
 *
 * Kilua RPC's `registerService` factory lambda constructs a brand-new [SepaService] on EVERY RPC
 * dispatch (this class' own one-instance-per-call shape, see [ConferenceStreamingService]'s own
 * "Constructor defaults exist for tests only" KDoc for the full reasoning) -- [mandateWriteRateLimiter]
 * MUST therefore be threaded through `Application.module` as an explicit, shared, module-scoped
 * `val`. The default below exists purely so [SepaServiceTest]'s throwaway test routes can omit it;
 * it must never be relied upon by `Application.module` itself.
 * internal, not private (Security Round 2, 2026-08-20, NEW-2): [PreparedBatchFile] below needs that
 * visibility because [SepaServiceTest]'s own NEW-2 test calls [SepaService.finalizeGeneratedBatchFile]
 * directly with a deliberately stale instance -- see that function's own KDoc.
 */
internal data class PreparedBatchFile(
    val spec: SepaBatchSpec,
    val remainingCount: Int,
    val remainingTotal: BigDecimal,
    val messageId: String,
)

class SepaService(
    private val call: ApplicationCall,
    private val sepaConfig: SepaConfig = SepaConfig.load(),
    private val documentStorageRoot: File = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage"),
    private val mandateWriteRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
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

    // ════════════════════════════════════════════════════════════════════
    // V1.2.2 -- Configuration
    // ════════════════════════════════════════════════════════════════════

    override suspend fun getSepaCreditorSettings(): SepaCreditorSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadSepaCreditorSettingsDto() }
    }

    /**
     * ADMIN-only. `sepaCreditorId`'s check digit is NOT computed here -- see [CREDITOR_ID_REGEX]
     * KDoc. Writes an [AuditEntityType.ORGANIZATION_SETTINGS] entry (the EXISTING literal, no new one
     * needed) only when something actually changed -- same narrowing
     * `OrganizationSettingsService.updateOrganizationSettings` already applies.
     */
    override suspend fun updateSepaCreditorSettings(input: SepaCreditorSettingsInput): SepaCreditorSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (input.sepaPrenotificationDays !in 1..30) {
            throw ConflictException("Die Vorabankuendigungsfrist muss zwischen 1 und 30 Tagen liegen.")
        }
        val sanitizedCreditorId =
            input.sepaCreditorId?.let { raw ->
                val sanitized = SepaCharacterSet.sanitize(raw = raw, maxLength = 35)
                if (!CREDITOR_ID_REGEX.matches(sanitized)) {
                    throw ConflictException(
                        "Die Glaeubiger-Identifikationsnummer hat nicht das erwartete Format " +
                            "(Laendercode + Pruefziffer + Geschaeftsbereichskennung + nationale Kennung).",
                    )
                }
                sanitized
            }
        val sanitizedCreditorName = input.sepaCreditorName?.let { SepaCharacterSet.sanitize(raw = it, maxLength = 70) }

        return transaction {
            val before = loadSepaCreditorSettingsDto()

            // Security Round 1 (2026-08-20, MAJOR-4 safeguard): a batch already NOTIFIED/GENERATED
            // has pre-notified its members under the CURRENT creditor id/name (now frozen onto the
            // batch itself at createDebitBatch time, see generateBatchFile/listMyPrenotifications
            // KDoc) -- changing the org's creditor identity while such a batch is open no longer
            // causes silent divergence (the frozen columns protect the FILE/pre-notification
            // content), but it would still leave a stale, misleading value in
            // `getSepaCreditorSettings`/the admin UI mid-flight. Rejected outright rather than a
            // silent allow -- same fail-closed posture this class applies elsewhere (e.g.
            // `requireValidPaymentAccountMapping`) for "this change could affect in-flight state".
            // Scoped to an ACTUAL change of sepaCreditorId/sepaCreditorName (not
            // sepaPrenotificationDays, which is NOT frozen onto a batch -- a NOTIFIED batch's own
            // requiredNoticeDays was already fixed at notifyBatch time, so changing the org-wide
            // default afterward only affects FUTURE batches and is safe to allow).
            val creditorIdentityChanging = sanitizedCreditorId != before.sepaCreditorId || sanitizedCreditorName != before.sepaCreditorName
            if (creditorIdentityChanging) {
                val inFlightBatchExists =
                    SepaDebitBatchTable
                        .selectAll()
                        .where { SepaDebitBatchTable.status inList SEPA_BATCH_IN_FLIGHT_STATUSES }
                        .limit(1)
                        .any()
                if (inFlightBatchExists) {
                    throw ConflictException(
                        "Die Glaeubiger-Identifikationsnummer/der Glaeubigername koennen nicht geaendert werden, solange " +
                            "ein SEPA-Lastschriftlauf im Status DRAFT/NOTIFIED/GENERATED offen ist. Bitte den Lauf zuerst " +
                            "abschliessen (SUBMITTED/SETTLED) oder stornieren (CANCELLED).",
                    )
                }
            }

            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[sepaCreditorId] = sanitizedCreditorId
                it[sepaCreditorName] = sanitizedCreditorName
                it[sepaPrenotificationDays] = input.sepaPrenotificationDays
            }
            val after = loadSepaCreditorSettingsDto()
            if (before != after) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(SepaCreditorSettingsSnapshot.serializer(), before.toSnapshot()),
                    after = Json.encodeToString(SepaCreditorSettingsSnapshot.serializer(), after.toSnapshot()),
                )
            }
            after
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V1.2.2 -- Mandates
    // ════════════════════════════════════════════════════════════════════

    /**
     * The most delicate method of this wave -- see plan Teil 7.2. Known limit: under READ COMMITTED,
     * `SELECT ... FOR UPDATE` against zero matching rows locks nothing, so the FIRST mandate for a
     * member is not itself serialized by [existingActive]'s own lock -- the actual serialization for
     * that case comes from `requireMembershipStatusIn(forUpdate = true)`'s row lock on `member`
     * below, which every concurrent [grantMandate] call for the same member must also acquire.
     */
    override suspend fun grantMandate(input: SepaMandateInput): SepaMandateDto {
        val current = resolveCurrentMember(call)
        val targetMemberId = input.memberId?.let { it.toSepaMemberUuid() } ?: current.memberId
        val onBehalf = targetMemberId != current.memberId
        if (onBehalf) current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        requireWithinRate(limiter = mandateWriteRateLimiter, memberId = current.memberId)
        if (!input.mandateTextAcknowledged) {
            throw ConflictException("Das SEPA-Lastschriftmandat muss ausdruecklich bestaetigt werden.")
        }
        val normalizedIban =
            try {
                IbanValidator.requireValid(input.debtorIban)
            } catch (e: IllegalArgumentException) {
                throw ConflictException(e.message ?: "Die IBAN ist ungueltig.")
            }
        val debtorName = SepaCharacterSet.sanitize(raw = input.debtorName, maxLength = 70)
        if (debtorName.isBlank()) throw ConflictException("Der Kontoinhabername darf nicht leer sein.")
        val today = DbClock.nowLocalDateTime().date
        if (input.signatureDate > today || input.signatureDate < today.minus(1, DateTimeUnit.YEAR)) {
            throw ConflictException("Das Unterschriftsdatum ist ungueltig.")
        }
        input.debtorBic?.let {
            if (!BicValidator.isValid(it)) throw ConflictException("Der BIC hat kein gueltiges Format.")
        }
        val secretBox = SecretBox(requireNotNull(sepaConfig.secretEncryptionKey))

        return transaction {
            requireMembershipStatusIn(memberId = targetMemberId, allowed = MemberStatusSets.ORGANIZATION_MEMBER, forUpdate = true)

            val existingActive =
                SepaMandateTable
                    .selectAll()
                    .where { (SepaMandateTable.memberId eq targetMemberId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                    .forUpdate()
                    .singleOrNull()
            if (existingActive != null) {
                throw ConflictException(
                    "Es besteht bereits ein aktives SEPA-Lastschriftmandat fuer dieses Mitglied. " +
                        "Bitte zuerst widerrufen, dann ein neues erteilen.",
                )
            }

            val mandateId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val ciphertext = secretBox.seal(plaintext = normalizedIban, aad = mandateId.toString())

            var reference: String? = null
            repeat(5) {
                if (reference != null) return@repeat
                val candidate = SepaMandateReferenceGenerator.generate(memberId = targetMemberId, signatureDate = input.signatureDate)
                val inserted =
                    SepaMandateTable.insertIgnore {
                        it[id] = mandateId
                        it[memberId] = targetMemberId
                        it[mandateReference] = candidate
                        it[SepaMandateTable.debtorName] = debtorName
                        it[debtorIbanCiphertext] = ciphertext
                        it[debtorIbanSetAt] = now
                        it[debtorIbanLast4] = IbanValidator.last4(normalizedIban)
                        it[debtorBic] = input.debtorBic
                        it[signatureDate] = input.signatureDate
                        it[sequenceType] = SepaSequenceType.FRST
                        it[status] = SepaMandateStatus.ACTIVE
                        it[grantedAt] = now
                        it[revokedAt] = null
                        it[revokedBy] = null
                        it[revocationReason] = null
                        it[lastUsedAt] = null
                        it[lastDebitedAmount] = null
                        it[createdBy] = current.memberId
                    }
                if (inserted.insertedCount > 0) reference = candidate
            }
            if (reference == null) {
                throw ConflictException("Es konnte keine eindeutige Mandatsreferenz erzeugt werden. Bitte erneut versuchen.")
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_MANDATE,
                entityId = mandateId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        SepaMandateSnapshot.serializer(),
                        SepaMandateSnapshot(
                            memberId = targetMemberId.toString(),
                            mandateReference = requireNotNull(reference),
                            status = SepaMandateStatus.ACTIVE,
                            sequenceType = SepaSequenceType.FRST,
                            signatureDate = input.signatureDate,
                            lastUsedAt = null,
                            createdBySelf = !onBehalf,
                        ),
                    ),
            )
            loadMandateDto(mandateId)
        }
    }

    override suspend fun revokeMandate(
        mandateId: String,
        reason: String?,
    ): SepaMandateDto {
        val current = resolveCurrentMember(call)
        val id = mandateId.toSepaMandateUuid()
        requireWithinRate(limiter = mandateWriteRateLimiter, memberId = current.memberId)
        return transaction {
            val row =
                SepaMandateTable
                    .selectAll()
                    .where { SepaMandateTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Mandat nicht gefunden.")
            val ownerMemberId = row[SepaMandateTable.memberId]
            if (ownerMemberId != current.memberId && current.role !in SEPA_TREASURY_ROLES) {
                throw NotFoundException("Mandat nicht gefunden.")
            }
            if (row[SepaMandateTable.status] != SepaMandateStatus.ACTIVE) {
                throw ConflictException("Mandat ist bereits ${row[SepaMandateTable.status]}.")
            }
            val before = mandateSnapshotFrom(row)

            val now = DbClock.nowLocalDateTime()
            SepaMandateTable.update({ SepaMandateTable.id eq id }) {
                it[status] = SepaMandateStatus.REVOKED
                it[revokedAt] = now
                it[revokedBy] = current.memberId
                it[revocationReason] = reason?.take(500)
            }

            // Laufende Batches: der Widerruf gilt sofort. Positionen in noch nicht eingereichten
            // Batches werden storniert, die Contribution lebt wieder auf. SUBMITTED-Batches bleiben
            // unangetastet -- die Datei ist bei der Bank. Security Round 2 (2026-08-20, NEW-1): this
            // used to be inlined here only -- now the SAME shared [resetGeneratedBatchesForUnusableMandate]
            // helper is called from every place a mandate transitions to REVOKED or EXPIRED, see its
            // own KDoc. Human-initiated revocation -- the resulting batch-reset audit entry (if any)
            // is attributed to the ACTUAL caller, unlike this function's three SYSTEM-driven call sites.
            resetGeneratedBatchesForUnusableMandate(mandateId = id, actorMemberId = current.memberId, actorRole = current.role)

            val after = mandateSnapshotFrom(SepaMandateTable.selectAll().where { SepaMandateTable.id eq id }.single())
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_MANDATE,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaMandateSnapshot.serializer(), before),
                after = Json.encodeToString(SepaMandateSnapshot.serializer(), after),
            )
            loadMandateDto(id)
        }
    }

    override suspend fun getMyMandate(): List<SepaMandateDto> {
        val current = resolveCurrentMember(call)
        requireSepaReadable()
        return transaction {
            // Minor (Review Round 1, 2026-08-19): already robust to >1 row (`.map`, not
            // `.singleOrNull()`). Review Round 2 (2026-08-20, MINOR): corrected -- there is NO
            // `uq_sepa_mandate_member_active` DB index; it was investigated and deliberately
            // DEFERRED as cross-dialect-infeasible (see the comment in V8__sepa_mandates.sql). The
            // "empty if none, else exactly one" contract documented on the interface is currently
            // enforced ONLY at the application level, by grantMandate's `SELECT ... FOR UPDATE` +
            // ConflictException guard -- if that lock is ever removed or weakened, multi-row ACTIVE
            // states become possible again with nothing in the DB to stop them. Kept `.map` rather
            // than truncating to `.take(1)` here so such a state (should it ever occur) stays fully
            // visible rather than being silently hidden, unlike buildPreview's lookup above which
            // needs exactly one value to branch on.
            SepaMandateTable
                .selectAll()
                .where { (SepaMandateTable.memberId eq current.memberId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                .orderBy(SepaMandateTable.grantedAt, SortOrder.DESC)
                .map { mandateRowToDto(it) }
        }
    }

    override suspend fun listMandates(
        status: SepaMandateStatus?,
        limit: Int,
        beforeGrantedAt: LocalDateTime?,
    ): List<SepaMandateDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_READ_ROLES)
        requireSepaReadable()
        val effectiveLimit = limit.coerceIn(1, 200)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (status != null) conditions += (SepaMandateTable.status eq status)
            if (beforeGrantedAt != null) conditions += (SepaMandateTable.grantedAt less beforeGrantedAt)
            val query = SepaMandateTable.selectAll()
            val filtered = if (conditions.isEmpty()) query else query.where { conditions.reduce { a, b -> a and b } }
            filtered.orderBy(SepaMandateTable.grantedAt, SortOrder.DESC).limit(effectiveLimit).map { mandateRowToDto(it) }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V1.2.2 -- Direct-debit runs
    // ════════════════════════════════════════════════════════════════════

    override suspend fun previewDebitBatch(input: SepaDebitBatchInput): SepaDebitBatchPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        return transaction { buildPreview(input) }
    }

    /**
     * Sets `contribution.status = DEBIT_SCHEDULED` here, not first at `notifyBatch` -- see plan D-11:
     * a contribution left visible for a second, concurrent run between "created" and "announced"
     * would defeat the whole point of the lock below.
     */
    override suspend fun createDebitBatch(input: SepaDebitBatchInput): SepaDebitBatchDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val today = DbClock.nowLocalDateTime().date
        if (input.requestedCollectionDate <= today) throw ConflictException("Das Einzugsdatum muss in der Zukunft liegen.")

        return transaction {
            val preview = buildPreview(input)
            if (preview.items.isEmpty()) {
                throw ConflictException("Kein einziger faelliger Beitrag mit aktivem SEPA-Mandat gefunden.")
            }
            if (preview.items.size > MAX_BATCH_ITEMS) {
                throw ConflictException("Der Lauf haette mehr als $MAX_BATCH_ITEMS Positionen -- bitte den Zeitraum eingrenzen.")
            }

            // Fixed lock order (sorted by id) across both tables -- deadlock avoidance between two
            // concurrent createDebitBatch calls. Re-checked AFTER locking -- this is the actual
            // TOCTOU protection; the preview above is only a candidate list.
            val candidateContributionIds = preview.items.map { Uuid.parse(it.contributionId) }.sortedBy { it.toString() }
            val lockedContributions =
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id inList candidateContributionIds }
                    .orderBy(ContributionTable.id)
                    .forUpdate()
                    .associateBy { it[ContributionTable.id] }

            val memberIds =
                lockedContributions.values
                    .map { it[ContributionTable.memberId] }
                    .distinct()
                    .sortedBy { it.toString() }
            val lockedMandatesByMember =
                if (memberIds.isEmpty()) {
                    emptyMap()
                } else {
                    SepaMandateTable
                        .selectAll()
                        .where { (SepaMandateTable.memberId inList memberIds) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                        .orderBy(SepaMandateTable.id)
                        .forUpdate()
                        .associateBy { it[SepaMandateTable.memberId] }
                }
            // M-5 (Review Round 1, 2026-08-19, MAJOR): mandate expiry (36 months without use) and
            // membership-status auto-revocation are otherwise enforced ONLY inside SepaBatchPoller,
            // which defaults to DISABLED (LAPIS_SEPA_POLLER_ENABLED=false) -- on such a deployment a
            // legally lapsed or withdrawn-member mandate would stay usable indefinitely without this
            // synchronous re-check. createDebitBatch is the chosen choke point: it is the EARLIEST
            // point a stale mandate could otherwise enter a batch at all (every later step --
            // notifyBatch/generateBatchFile/settleBatch -- only ever operates on items already
            // created here). Locked under the SAME forUpdate() as the mandate/contribution rows above
            // -- no second, unlocked read.
            val lockedMembersById =
                if (memberIds.isEmpty()) {
                    emptyMap()
                } else {
                    MemberTable.selectAll().where { MemberTable.id inList memberIds }.orderBy(MemberTable.id).forUpdate().associateBy {
                        it[MemberTable.id]
                    }
                }

            val eligible =
                candidateContributionIds.mapNotNull { contributionId ->
                    val contributionRow = lockedContributions[contributionId] ?: return@mapNotNull null
                    if (contributionRow[ContributionTable.status] !in ContributionStatusSets.OUTSTANDING) return@mapNotNull null
                    val memberId = contributionRow[ContributionTable.memberId]
                    val mandateRow = lockedMandatesByMember[memberId] ?: return@mapNotNull null
                    val mandateExpiresAt =
                        SepaConfig.mandateExpiryDate(
                            grantedAt = mandateRow[SepaMandateTable.grantedAt].date,
                            lastUsedAt = mandateRow[SepaMandateTable.lastUsedAt],
                        )
                    if (mandateExpiresAt < today) return@mapNotNull null
                    val memberStatus = lockedMembersById[memberId]?.get(MemberTable.status)
                    if (memberStatus == null || memberStatus !in MemberStatusSets.ORGANIZATION_MEMBER) return@mapNotNull null
                    contributionRow to mandateRow
                }
            if (eligible.isEmpty()) {
                throw ConflictException(
                    "Kein einziger faelliger Beitrag mit aktivem SEPA-Mandat gefunden " +
                        "(Mandate wurden zwischenzeitlich widerrufen oder Beitraege sind bereits in einem anderen Lauf gebunden).",
                )
            }

            val batchId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val messageId = sepaBatchMessageId(now)
            val paymentInfoId = "$messageId-P1"
            val sequenceType =
                if (eligible.all { (_, mandateRow) -> mandateRow[SepaMandateTable.lastUsedAt] == null }) {
                    SepaSequenceType.FRST
                } else {
                    SepaSequenceType.RCUR
                }
            val totalAmount =
                eligible.fold(BigDecimal.ZERO) { acc, (contributionRow, _) ->
                    acc +
                        contributionRow[ContributionTable.amountDue]
                }

            // Security Round 1 (2026-08-20, MAJOR-4): snapshot the CURRENT creditor identity onto
            // the batch itself, rather than letting generateBatchFile/listMyPrenotifications read
            // organization_settings LIVE at their own, later point in time. Without this, an ADMIN
            // changing sepa_creditor_id/sepa_creditor_name/bank_iban/bank_bic during the mandatory
            // notice window (between notifyBatch and generateBatchFile) would silently debit members
            // under a DIFFERENT creditor identity than the one they were legally pre-notified about
            // -- see generateBatchFile/listMyPrenotifications KDoc for how the frozen values are
            // consumed. Nullable/possibly-null here on purpose: an organization is allowed to
            // createDebitBatch before its SEPA creditor settings are fully configured (unchanged
            // behavior, see readyForFileGeneration) -- generateBatchFile still throws its own
            // actionable ConflictException if the FROZEN value is null at generation time, exactly
            // mirroring the pre-fix behavior for a live-but-missing value.
            val settingsRow =
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
            val creditorId = settingsRow[OrganizationSettingsTable.sepaCreditorId]
            val creditorName = settingsRow[OrganizationSettingsTable.sepaCreditorName]
            val creditorIban = settingsRow[OrganizationSettingsTable.bankIban]
            val creditorBic = settingsRow[OrganizationSettingsTable.bankBic]

            SepaDebitBatchTable.insert {
                it[id] = batchId
                it[SepaDebitBatchTable.messageId] = messageId
                it[SepaDebitBatchTable.paymentInfoId] = paymentInfoId
                it[requestedCollectionDate] = input.requestedCollectionDate
                it[SepaDebitBatchTable.sequenceType] = sequenceType
                it[status] = SepaDebitBatchStatus.DRAFT
                it[itemCount] = eligible.size
                it[SepaDebitBatchTable.totalAmount] = totalAmount
                it[createdBy] = current.memberId
                it[createdAt] = now
                it[notifiedAt] = null
                it[requiredNoticeDays] = null
                it[generatedAt] = null
                it[generatedDocumentId] = null
                it[prenotificationDocumentId] = null
                it[submittedAt] = null
                it[submittedNote] = null
                it[settledAt] = null
                it[cancelledAt] = null
                it[cancellationReason] = null
                it[SepaDebitBatchTable.creditorId] = creditorId
                it[SepaDebitBatchTable.creditorName] = creditorName
                it[SepaDebitBatchTable.creditorIban] = creditorIban
                it[SepaDebitBatchTable.creditorBic] = creditorBic
            }

            eligible.forEach { (contributionRow, mandateRow) ->
                val contributionId = contributionRow[ContributionTable.id]
                val mandateId = mandateRow[SepaMandateTable.id]
                val amount = contributionRow[ContributionTable.amountDue]
                val endToEndId = contributionId.toString().replace("-", "").uppercase()
                val remittance =
                    SepaCharacterSet.sanitize(
                        raw =
                            "Mitgliedsbeitrag ${contributionRow[ContributionTable.periodStart]}-" +
                                "${contributionRow[ContributionTable.periodEnd]} Mandat ${mandateRow[SepaMandateTable.mandateReference]}",
                        maxLength = 140,
                    )
                SepaDebitItemTable.insert {
                    it[id] = Uuid.random()
                    it[SepaDebitItemTable.batchId] = batchId
                    it[SepaDebitItemTable.contributionId] = contributionId
                    it[SepaDebitItemTable.mandateId] = mandateId
                    it[SepaDebitItemTable.endToEndId] = endToEndId
                    it[SepaDebitItemTable.amount] = amount
                    it[remittanceInformation] = remittance
                    it[status] = SepaDebitItemStatus.PENDING
                    it[settleableAt] = null
                    it[journalEntryId] = null
                }
                ContributionTable.update({ ContributionTable.id eq contributionId }) {
                    it[status] = ContributionStatus.DEBIT_SCHEDULED
                    it[paymentMethod] = ContributionPaymentMethod.SEPA_DEBIT
                    it[sepaMandateId] = mandateId
                }
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                entityId = batchId,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(batchId)),
            )
            loadBatchDto(batchId)
        }
    }

    override suspend fun notifyBatch(batchId: String): SepaDebitBatchDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val id = batchId.toSepaBatchUuid()
        return transaction {
            val batchRow =
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.DRAFT) {
                throw ConflictException("Lauf ist bereits ${batchRow[SepaDebitBatchTable.status]}.")
            }
            val before = batchSnapshotFrom(batchRow)

            val configuredDays =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.sepaPrenotificationDays]
            val itemAmounts =
                (SepaDebitItemTable innerJoin SepaMandateTable)
                    .selectAll()
                    .where { (SepaDebitItemTable.batchId eq id) and (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING) }
                    .map { row -> row[SepaMandateTable.lastDebitedAmount] to row[SepaDebitItemTable.amount] }
            val requiredDays =
                SepaPrenotificationCalculator.requiredNoticeDaysForBatch(items = itemAmounts, configuredDays = configuredDays)

            val now = DbClock.nowLocalDateTime()
            SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq id }) {
                it[status] = SepaDebitBatchStatus.NOTIFIED
                it[notifiedAt] = now
                it[requiredNoticeDays] = requiredDays
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
                after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(id)),
            )
            loadBatchDto(id)
        }
    }

    /**
     * Three hard gates: creditor id/name/IBAN configured (E-11), the pre-notification period elapsed,
     * and every mandate re-checked ACTIVE (locked, TOCTOU). Deviation from the plan's own wireframe:
     * this wave does NOT generate a `SepaVorabankuendigungPdfGenerator` collective PDF alongside the
     * XML (`prenotification_document_id` stays `null`) -- the in-app pre-notification
     * ([listMyPrenotifications]) already satisfies the four legally required disclosures, and adding a
     * second PDF-generation surface within an already large wave was cut for time. Documented in the
     * CHANGELOG "Known limitations" -- a real gap, not a silent omission.
     *
     * **Three phases, not one transaction (Security Round 1, 2026-08-20, MAJOR-2 non-atomic-write
     * fix).** The pre-fix version ran the ENTIRE method -- lock, validation, IBAN decryption, XML
     * generation, disk write, status transition, audit -- inside a single `transaction {}`, so the
     * pain.008 file was written to disk WHILE the transaction was open: if anything downstream threw
     * and the transaction rolled back, the file (containing every debited member's plaintext IBAN)
     * stayed on disk with no DB row pointing at it -- an orphaned, invisible blob. Restructured to
     * follow the SAME "generate ids/bytes outside the transaction, finalize in a short one" pattern
     * [network.lapis.cloud.server.routes.archiveGeneratedFile] already establishes for
     * [network.lapis.cloud.server.conference.RecordingPoller]:
     * 1. **Phase 1** (locked `transaction {}`): validate status/notice-period, re-check every
     *    mandate ACTIVE (TOCTOU), cancel stale items, decrypt IBANs, build the in-memory
     *    [SepaBatchSpec]. Committing here on its own is safe -- item cancellations/contribution
     *    reverts are a correct, standalone state even if a later phase fails or is retried.
     * 2. **Phase 2** (no transaction open): write the pain.008 XML, seal it with [SecretBox]
     *    (MAJOR-2's OWN "encrypt the archive at rest" fix -- see [PaymentsPersonalData] KDoc
     *    "Security Round 1 MAJOR-2" for why the plaintext-on-disk retention gap could not simply be
     *    left alone), and archive it via [archiveGeneratedFile] (which itself never holds a DB
     *    transaction open across the disk write).
     * 3. **Phase 3** (locked `transaction {}`): re-lock the batch and re-check it is STILL
     *    `NOTIFIED` -- guards against a concurrent duplicate `generateBatchFile` call racing between
     *    phase 1's commit and this phase (phase 1 deliberately does NOT flip the status early,
     *    unlike [RecordingPoller]'s own earlier `PROCESSING` flip, because a human-facing RPC that
     *    got stuck in `GENERATED` with no document on a phase-2 failure -- with no automatic retry
     *    mechanism, unlike the poller -- would be a strictly worse failure mode than the rare
     *    orphaned-document case this re-check accepts instead). If a concurrent call won the race,
     *    the document phase 2 just archived becomes an orphan too -- a narrower, documented trade-off
     *    than the original "ANY downstream failure orphans a file" hazard, not a full elimination of
     *    it. Security Round 2 (2026-08-20, NEW-2): phase 3 ALSO re-counts the live PENDING items for
     *    this batch under the SAME lock and compares against `prepared.remainingCount` (what phase 1
     *    actually embedded into the file) -- phase 1's mandate-row locks were released the moment its
     *    own transaction committed, so a `revokeMandate` landing in the sub-second gap between that
     *    commit and this phase's lock would otherwise go uncaught here (though still eventually
     *    caught by [markBatchSubmitted]'s own divergence check -- but only after a treasurer may
     *    already have downloaded the stale file). Mirrors [markBatchSubmitted]'s own check.
     */
    override suspend fun generateBatchFile(batchId: String): SepaDebitBatchDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val id = batchId.toSepaBatchUuid()
        val secretBox = SecretBox(requireNotNull(sepaConfig.secretEncryptionKey))

        val prepared = prepareBatchFileGeneration(id = id, secretBox = secretBox)

        // Phase 2 -- pure computation + disk I/O, deliberately OUTSIDE any DB transaction.
        val bytes =
            try {
                SepaPain008Writer.write(prepared.spec)
            } catch (e: IllegalArgumentException) {
                throw ConflictException(
                    "Die SEPA-Lastschriftdatei konnte nicht erzeugt werden (${e.message}) -- bitte die " +
                        "SEPA-/Organisationseinstellungen pruefen.",
                )
            }
        val sealedBytes = secretBox.seal(plaintext = bytes.toString(Charsets.UTF_8), aad = id.toString()).toByteArray(Charsets.UTF_8)
        val tempFile = File.createTempFile("sepa-lastschrift-", ".xml.enc")
        val documentId =
            try {
                tempFile.writeBytes(sealedBytes)
                archiveGeneratedFile(
                    storageRoot = documentStorageRoot,
                    folderName = "SEPA-Lastschriften",
                    fileName = "sepa-lastschrift-${prepared.messageId}.xml.enc",
                    title = "SEPA-Lastschriftdatei ${prepared.messageId}",
                    sourceFile = tempFile,
                    // Ciphertext, not real XML -- see class KDoc "Phase 2". The DOWNLOAD route
                    // (registerSepaRoutes) decrypts before ever setting a Content-Type for the caller.
                    mimeType = "application/octet-stream",
                    uploadedBy = current.memberId,
                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                )
            } finally {
                tempFile.delete()
            }

        // Phase 3 -- finalize.
        return finalizeGeneratedBatchFile(id = id, prepared = prepared, documentId = documentId, current = current)
    }

    /**
     * Phase 3 of [generateBatchFile] -- see that method's own KDoc "Phase 3"/NEW-2. Extracted into
     * its own `internal` function (Security Round 2, 2026-08-20) purely for testability: the NEW-2
     * live-PENDING-count divergence guard below is otherwise only reachable through a genuine
     * multi-thread race landing in the sub-second gap between phase 1's commit and this phase's own
     * lock -- exactly the kind of race a fast, in-memory test database makes UNRELIABLY reproducible
     * (confirmed empirically: a true-concurrency version of this test flaked in roughly 1 of every 4-5
     * runs). Calling this function directly with a deliberately STALE [prepared] (captured via a real
     * [prepareBatchFileGeneration] call, then invalidated by a real intervening [revokeMandate] call)
     * lets [SepaServiceTest]'s own NEW-2 test reproduce the exact DB state this guard exists for,
     * deterministically, with zero timing dependency -- see that test's own KDoc. No behavior change
     * versus the pre-extraction inline version; [generateBatchFile] itself still always calls this
     * with a freshly-captured, non-stale [prepared] in production use.
     */
    internal fun finalizeGeneratedBatchFile(
        id: Uuid,
        prepared: PreparedBatchFile,
        documentId: Uuid,
        current: CurrentMember,
    ): SepaDebitBatchDto =
        transaction {
            val batchRow =
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.NOTIFIED) {
                // See class KDoc "Phase 3" -- a concurrent duplicate call already finished first; the
                // document phase 2 just archived is now a harmless, documented orphan.
                throw ConflictException(
                    "Lauf wurde zwischenzeitlich bereits verarbeitet (Status: ${batchRow[SepaDebitBatchTable.status]}) -- " +
                        "vermutlich ein zweiter, gleichzeitiger Erzeugungsversuch.",
                )
            }
            // Security Round 2 (2026-08-20, NEW-2): mirrors markBatchSubmitted's own divergence
            // check below -- phase 1's mandate-row locks were released once ITS OWN transaction
            // committed (see class KDoc "Phase 1"/"Phase 3"), leaving a narrow window for a
            // concurrent revokeMandate to cancel one of this batch's items between phase 1 and this
            // lock. Re-count the LIVE PENDING items now, under THIS lock, and compare against what
            // phase 1 actually embedded into the file just archived (prepared.remainingCount) -- a
            // mismatch means that file already disagrees with the DB and must not be attached.
            val livePendingItemCount =
                SepaDebitItemTable
                    .selectAll()
                    .where { (SepaDebitItemTable.batchId eq id) and (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING) }
                    .toList()
                    .size
            if (livePendingItemCount != prepared.remainingCount) {
                throw ConflictException(
                    "Der Lauf wurde zwischen Datei-Erzeugung und Abschluss veraendert (aktuelle offene Positionen: " +
                        "$livePendingItemCount, bei der Datei-Erzeugung erfasst: ${prepared.remainingCount}) -- " +
                        "vermutlich wurde ein Mandat in der Zwischenzeit widerrufen. Bitte den Lauf erneut erzeugen.",
                )
            }
            val before = batchSnapshotFrom(batchRow)
            val now = DbClock.nowLocalDateTime()
            SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq id }) {
                it[status] = SepaDebitBatchStatus.GENERATED
                it[generatedAt] = now
                it[generatedDocumentId] = documentId
                it[itemCount] = prepared.remainingCount
                it[totalAmount] = prepared.remainingTotal
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
                after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(id)),
            )
            loadBatchDto(id)
        }

    /** Phase 1 of [generateBatchFile] -- see that method's own KDoc "Phase 1". */
    internal fun prepareBatchFileGeneration(
        id: Uuid,
        secretBox: SecretBox,
    ): PreparedBatchFile =
        transaction {
            val batchRow =
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.NOTIFIED) {
                throw ConflictException("Lauf ist bereits ${batchRow[SepaDebitBatchTable.status]}.")
            }

            // Security Round 1 (2026-08-20, MAJOR-4): read the FROZEN creditor identity off the
            // batch row itself (snapshotted at createDebitBatch time), never organization_settings
            // LIVE -- see createDebitBatch/listMyPrenotifications KDoc for why. The same actionable
            // ConflictException messages as before the fix, since a batch created before the org's
            // SEPA settings were configured still legitimately carries `null` here.
            val creditorId =
                batchRow[SepaDebitBatchTable.creditorId] ?: throw ConflictException(
                    "Es ist keine SEPA-Glaeubiger-Identifikationsnummer hinterlegt. Sie muss bei der Deutschen " +
                        "Bundesbank beantragt und unter Einstellungen > SEPA eingetragen werden, bevor eine " +
                        "Lastschriftdatei erzeugt werden kann. Hinweis: massgeblich ist der Stand zum Zeitpunkt der " +
                        "Lauf-Erstellung -- eine zwischenzeitliche Aenderung wirkt erst auf einen NEUEN Lauf.",
                )
            val creditorName =
                batchRow[SepaDebitBatchTable.creditorName]
                    ?: throw ConflictException("Es ist kein SEPA-Glaeubigername hinterlegt.")
            val creditorIban =
                batchRow[SepaDebitBatchTable.creditorIban]
                    ?: throw ConflictException("Es ist keine IBAN der Organisation hinterlegt (Einstellungen > Organisation).")
            val creditorBic = batchRow[SepaDebitBatchTable.creditorBic]
            // Security Round 1 (2026-08-20, MINOR-5): format-check the creditor BIC BEFORE it ever
            // reaches SepaPain008Writer -- previously unchecked entirely (unlike debtorBic at
            // grantMandate), so a malformed value would either sail unchecked into the bank file or
            // (once SepaPain008Writer.validate ran) throw a raw, unmapped IllegalArgumentException.
            creditorBic?.let {
                if (!BicValidator.isValid(it)) {
                    throw ConflictException(
                        "Die hinterlegte BIC der Organisation (Einstellungen > Organisation) hat kein gueltiges Format.",
                    )
                }
            }

            val notifiedAt = requireNotNull(batchRow[SepaDebitBatchTable.notifiedAt])
            val requiredDays = requireNotNull(batchRow[SepaDebitBatchTable.requiredNoticeDays])
            val allowedFrom = notifiedAt.date.plus(requiredDays, DateTimeUnit.DAY)
            val requestedCollectionDate = batchRow[SepaDebitBatchTable.requestedCollectionDate]
            if (requestedCollectionDate < allowedFrom) {
                throw ConflictException(
                    "Die Vorabankuendigungsfrist von $requiredDays Kalendertagen ist noch nicht gewahrt: der Einzug " +
                        "darf fruehestens am $allowedFrom erfolgen, angefordert ist $requestedCollectionDate.",
                )
            }

            val itemRows =
                (SepaDebitItemTable innerJoin SepaMandateTable)
                    .selectAll()
                    .where { (SepaDebitItemTable.batchId eq id) and (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING) }
                    .toList()
            val mandateIds = itemRows.map { it[SepaMandateTable.id] }.distinct().sortedBy { it.toString() }
            val lockedMandates =
                if (mandateIds.isEmpty()) {
                    emptyMap()
                } else {
                    SepaMandateTable
                        .selectAll()
                        .where { SepaMandateTable.id inList mandateIds }
                        .orderBy(SepaMandateTable.id)
                        .forUpdate()
                        .associateBy { it[SepaMandateTable.id] }
                }

            // M-5 (Review Round 1, 2026-08-19, MAJOR) -- defense in depth alongside createDebitBatch's
            // own synchronous check: a mandate could still be ACTIVE at createDebitBatch time (which
            // is the primary choke point, see that function's KDoc) but cross its 36-month expiry
            // during the pre-notification waiting period before generateBatchFile runs. Re-derived
            // from the SAME SepaConfig.mandateExpiryDate helper, never a second inline calculation.
            val today = DbClock.nowLocalDateTime().date
            val remaining = mutableListOf<ResultRow>()
            itemRows.forEach { itemRow ->
                val mandateId = itemRow[SepaMandateTable.id]
                val mandateRow = lockedMandates[mandateId]
                val mandateStatus = mandateRow?.get(SepaMandateTable.status)
                val mandateExpired =
                    mandateRow != null &&
                        SepaConfig.mandateExpiryDate(
                            grantedAt = mandateRow[SepaMandateTable.grantedAt].date,
                            lastUsedAt = mandateRow[SepaMandateTable.lastUsedAt],
                        ) < today
                if (mandateStatus != SepaMandateStatus.ACTIVE || mandateExpired) {
                    val itemId = itemRow[SepaDebitItemTable.id]
                    val contributionId = itemRow[SepaDebitItemTable.contributionId]
                    SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) { it[status] = SepaDebitItemStatus.CANCELLED }
                    ContributionTable.update({ ContributionTable.id eq contributionId }) {
                        it[status] = ContributionStatus.OPEN
                        it[sepaMandateId] = null
                    }
                } else {
                    remaining += itemRow
                }
            }
            if (remaining.isEmpty()) {
                throw ConflictException("Alle Positionen dieses Laufs wurden storniert (Mandate widerrufen oder verfallen).")
            }

            val itemSpecs =
                remaining.map { row ->
                    val mandateRow = lockedMandates.getValue(row[SepaMandateTable.id])
                    val debtorIban =
                        secretBox.open(
                            sealed = mandateRow[SepaMandateTable.debtorIbanCiphertext],
                            aad = mandateRow[SepaMandateTable.id].toString(),
                        )
                    SepaBatchItemSpec(
                        endToEndId = row[SepaDebitItemTable.endToEndId],
                        amount = row[SepaDebitItemTable.amount],
                        mandateReference = mandateRow[SepaMandateTable.mandateReference],
                        mandateSignatureDate = mandateRow[SepaMandateTable.signatureDate],
                        debtorName = mandateRow[SepaMandateTable.debtorName],
                        debtorIban = debtorIban,
                        debtorBic = mandateRow[SepaMandateTable.debtorBic],
                        remittanceInformation = row[SepaDebitItemTable.remittanceInformation],
                    )
                }

            val spec =
                SepaBatchSpec(
                    version = sepaConfig.pain008Version,
                    messageId = batchRow[SepaDebitBatchTable.messageId],
                    creationDateTime = DbClock.nowLocalDateTime(),
                    initiatingPartyName = creditorName,
                    paymentInfoId = batchRow[SepaDebitBatchTable.paymentInfoId],
                    sequenceType = batchRow[SepaDebitBatchTable.sequenceType],
                    requestedCollectionDate = requestedCollectionDate,
                    creditorName = creditorName,
                    creditorIban = creditorIban,
                    creditorBic = creditorBic,
                    creditorSchemeId = creditorId,
                    items = itemSpecs,
                )

            PreparedBatchFile(
                spec = spec,
                remainingCount = remaining.size,
                remainingTotal = remaining.fold(BigDecimal.ZERO) { acc, row -> acc + row[SepaDebitItemTable.amount] },
                messageId = batchRow[SepaDebitBatchTable.messageId],
            )
        }

    override suspend fun markBatchSubmitted(
        batchId: String,
        note: String?,
    ): SepaDebitBatchDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val id = batchId.toSepaBatchUuid()
        return transaction {
            val batchRow =
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.GENERATED) {
                throw ConflictException("Lauf ist bereits ${batchRow[SepaDebitBatchTable.status]}.")
            }

            val items =
                SepaDebitItemTable
                    .selectAll()
                    .where {
                        (SepaDebitItemTable.batchId eq id) and
                            (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING)
                    }.toList()
            // Security Round 1 (2026-08-20, MAJOR-3 hardening): batchRow.itemCount was frozen to the
            // EXACT number of items that went into the pain.008 file at generateBatchFile time. If it
            // no longer matches the currently-live PENDING item count, something changed the item set
            // AFTER the file was generated without going through a fresh generateBatchFile (e.g. a
            // recordReturn called prematurely against a still-GENERATED, not-yet-submitted batch --
            // recordReturn does not recalculate batch totals) -- silently submitting only the
            // currently-PENDING subset would under-submit relative to what the treasurer is about to
            // upload to the bank, with no trace in the DB of the discrepancy. Surfaced as an error
            // instead, directing the treasurer to investigate/regenerate rather than proceeding blind.
            if (items.size != batchRow[SepaDebitBatchTable.itemCount]) {
                throw ConflictException(
                    "Die Anzahl der offenen Positionen (${items.size}) weicht von der bei der Dateierstellung " +
                        "erwarteten Anzahl (${batchRow[SepaDebitBatchTable.itemCount]}) ab -- vermutlich wurde eine " +
                        "Position nach der Dateierstellung veraendert (z. B. ein Ruecklaeufer erfasst). Bitte den " +
                        "Lauf pruefen, ggf. stornieren und neu erzeugen, bevor er als eingereicht markiert wird.",
                )
            }

            // Security Round 3 (2026-08-20, F-1a): markBatchSubmitted is the LAST point before a
            // treasurer confirms a real pain.008 file was uploaded to the bank -- every OTHER
            // lifecycle step that touches a mandate's validity (createDebitBatch, this class' own
            // prepareBatchFileGeneration/generateBatchFile "Phase 1", mandateRowToDto, and
            // buildPreview) re-derives expiry from the SAME SepaConfig.mandateExpiryDate helper --
            // this one did not, at all. Concrete failure this closes: a batch sits GENERATED for
            // hours/days/weeks (entirely normal -- treasurer generates Friday, submits Monday) while a
            // mandate crosses its 36-month non-use expiry boundary; if LAPIS_SEPA_POLLER_ENABLED is
            // false (the default), the mandate row never even flips to EXPIRED in the DB, yet the
            // mandate is legally expired regardless -- this method would otherwise accept the
            // submission unconditionally (the item-count divergence check above cannot catch this,
            // since the item SET is unchanged) and even advance lastUsedAt/sequenceType on the
            // now-expired mandate below as if it were a legitimate collection. Deliberately
            // independent of [SepaBatchPoller] -- see [resetGeneratedBatchesForUnusableMandate] /
            // [SepaBatchPoller.runPhaseA] for that fix's defense-in-depth counterpart, which does NOT
            // substitute for this synchronous check (the poller is disabled by default). Mandates
            // locked forUpdate(), ordered by id -- same deadlock-avoidance discipline
            // createDebitBatch/prepareBatchFileGeneration already apply to their own mandate locks.
            val today = DbClock.nowLocalDateTime().date
            val submitMandateIds = items.map { it[SepaDebitItemTable.mandateId] }.distinct().sortedBy { it.toString() }
            val lockedSubmitMandates =
                if (submitMandateIds.isEmpty()) {
                    emptyMap()
                } else {
                    SepaMandateTable
                        .selectAll()
                        .where { SepaMandateTable.id inList submitMandateIds }
                        .orderBy(SepaMandateTable.id)
                        .forUpdate()
                        .associateBy { it[SepaMandateTable.id] }
                }
            val invalidMandateMembers =
                submitMandateIds.mapNotNull { mandateId ->
                    val mandateRow = lockedSubmitMandates[mandateId] ?: return@mapNotNull null
                    val expired =
                        SepaConfig.mandateExpiryDate(
                            grantedAt = mandateRow[SepaMandateTable.grantedAt].date,
                            lastUsedAt = mandateRow[SepaMandateTable.lastUsedAt],
                        ) < today
                    if (mandateRow[SepaMandateTable.status] != SepaMandateStatus.ACTIVE || expired) {
                        memberDisplayName(mandateRow[SepaMandateTable.memberId])
                    } else {
                        null
                    }
                }
            if (invalidMandateMembers.isNotEmpty()) {
                throw ConflictException(
                    "Der Lauf kann nicht als eingereicht markiert werden: fuer folgende Mitglieder ist das " +
                        "SEPA-Mandat zwischenzeitlich nicht mehr aktiv oder abgelaufen " +
                        "(${invalidMandateMembers.joinToString(", ")}) -- vermutlich seit der Dateierstellung " +
                        "widerrufen oder verfallen. Bitte den Lauf pruefen, ggf. stornieren und neu erzeugen.",
                )
            }

            val before = batchSnapshotFrom(batchRow)
            val requestedCollectionDate = batchRow[SepaDebitBatchTable.requestedCollectionDate]
            val now = DbClock.nowLocalDateTime()
            SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq id }) {
                it[status] = SepaDebitBatchStatus.SUBMITTED
                it[submittedAt] = now
                it[submittedNote] = note?.take(1000)
            }

            items.forEach { itemRow ->
                val contributionId = itemRow[SepaDebitItemTable.contributionId]
                val mandateId = itemRow[SepaDebitItemTable.mandateId]
                val amount = itemRow[SepaDebitItemTable.amount]
                ContributionTable.update({ ContributionTable.id eq contributionId }) { it[status] = ContributionStatus.DEBIT_SUBMITTED }
                // FRST -> RCUR and last_used_at/last_debited_amount are set at SUBMISSION, not
                // confirmed collection -- deliberate simplification, see plan Teil 7.7.
                SepaMandateTable.update({ SepaMandateTable.id eq mandateId }) {
                    it[lastUsedAt] = requestedCollectionDate
                    it[lastDebitedAmount] = amount
                    it[sequenceType] = SepaSequenceType.RCUR
                }
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
                after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(id)),
            )
            loadBatchDto(id)
        }
    }

    override suspend fun cancelBatch(
        batchId: String,
        reason: String,
    ): SepaDebitBatchDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val id = batchId.toSepaBatchUuid()
        return transaction {
            val batchRow =
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            val status = batchRow[SepaDebitBatchTable.status]
            if (status !in SEPA_BATCH_IN_FLIGHT_STATUSES) {
                throw ConflictException("Lauf im Status $status kann nicht storniert werden.")
            }
            val before = batchSnapshotFrom(batchRow)
            val items =
                SepaDebitItemTable
                    .selectAll()
                    .where {
                        (SepaDebitItemTable.batchId eq id) and
                            (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING)
                    }.toList()
            items.forEach { itemRow ->
                val itemId = itemRow[SepaDebitItemTable.id]
                val contributionId = itemRow[SepaDebitItemTable.contributionId]
                SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) {
                    it[SepaDebitItemTable.status] =
                        SepaDebitItemStatus.CANCELLED
                }
                ContributionTable.update({ ContributionTable.id eq contributionId }) {
                    it[ContributionTable.status] = ContributionStatus.OPEN
                    it[sepaMandateId] = null
                }
            }
            val now = DbClock.nowLocalDateTime()
            SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq id }) {
                it[SepaDebitBatchTable.status] = SepaDebitBatchStatus.CANCELLED
                it[cancelledAt] = now
                it[cancellationReason] = reason.take(500)
                it[itemCount] = 0
                it[totalAmount] = BigDecimal.ZERO
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
                after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(id)),
            )
            loadBatchDto(id)
        }
    }

    /**
     * No single big transaction (plan Teil 7.0 rule 4: at most one [AuditLogRecorder.record] per
     * transaction, never held across a loop) -- one short transaction PER settleable item, then a
     * final one closing the batch. The `sepa_debit_item` row's own lock is already held via
     * `forUpdate()` at the top of each item's transaction, so the trailing status/journalEntryId
     * write after the bridge call re-uses that SAME lock rather than acquiring a new one -- it does
     * not violate the bridge's "nothing else may lock a row after calling this function" contract,
     * which concerns NEW locks, not a write to an already-held one.
     */
    override suspend fun settleBatch(batchId: String): SepaDebitBatchDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val id = batchId.toSepaBatchUuid()

        val settleableItemIds =
            transaction {
                val batchRow =
                    SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("Lauf nicht gefunden.")
                if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.SUBMITTED) {
                    throw ConflictException(
                        "Nur ein eingereichter Lauf kann abgerechnet werden (Status: ${batchRow[SepaDebitBatchTable.status]}).",
                    )
                }
                SepaDebitItemTable
                    .selectAll()
                    .where { (SepaDebitItemTable.batchId eq id) and (SepaDebitItemTable.status eq SepaDebitItemStatus.SETTLEABLE) }
                    .map { it[SepaDebitItemTable.id] }
            }
        if (settleableItemIds.isEmpty()) {
            throw ConflictException(
                "Fuer diesen Lauf ist noch keine Position abrechnungsreif. Die achtwoechige SEPA-Rueckgabefrist ist noch nicht abgelaufen.",
            )
        }

        // M-4 (Review Round 1, 2026-08-19, MAJOR): collect which items' postings actually FAILED
        // (settleOneItem returns false only when its own transaction threw ConflictException --
        // requireBalanced/CashRegisterGuard rejecting the posting) so the caller can see this in the
        // response instead of a silently all-green result while money silently fails to book. Each
        // item's own transaction still rolls back independently -- the per-item, not whole-batch,
        // rollback semantics (documented on settleOneItem below) are unchanged.
        val failedItemIds = settleableItemIds.filterNot { itemId -> settleOneItem(itemId = itemId, current = current) }

        transaction {
            val nonTerminalCount =
                SepaDebitItemTable
                    .selectAll()
                    .where {
                        (SepaDebitItemTable.batchId eq id) and
                            (SepaDebitItemTable.status inList listOf(SepaDebitItemStatus.PENDING, SepaDebitItemStatus.SETTLEABLE))
                    }.count()
            if (nonTerminalCount == 0L) {
                val batchRow =
                    SepaDebitBatchTable
                        .selectAll()
                        .where { SepaDebitBatchTable.id eq id }
                        .forUpdate()
                        .singleOrNull()
                if (batchRow != null && batchRow[SepaDebitBatchTable.status] == SepaDebitBatchStatus.SUBMITTED) {
                    val before = batchSnapshotFrom(batchRow)
                    val now = DbClock.nowLocalDateTime()
                    SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq id }) {
                        it[status] = SepaDebitBatchStatus.SETTLED
                        it[settledAt] = now
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                        entityId = id,
                        action = AuditAction.UPDATE,
                        before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
                        after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(id)),
                    )
                }
            }
        }
        val detail = getBatch(batchId)
        return if (failedItemIds.isEmpty()) detail else detail.copy(failedItemIds = failedItemIds.map { it.toString() })
    }

    /**
     * Returns `true` on success (including the legitimate no-op paths below: item already gone,
     * already non-SETTLEABLE, or the contribution was closed out-of-band -- none of those are
     * posting FAILURES), `false` only when the posting itself was rejected. See [settleBatch] M-4 KDoc.
     */
    private fun settleOneItem(
        itemId: Uuid,
        current: CurrentMember,
    ): Boolean {
        try {
            transaction {
                val itemRow =
                    SepaDebitItemTable
                        .selectAll()
                        .where { SepaDebitItemTable.id eq itemId }
                        .forUpdate()
                        .singleOrNull()
                        ?: return@transaction
                if (itemRow[SepaDebitItemTable.status] != SepaDebitItemStatus.SETTLEABLE) return@transaction
                val contributionId = itemRow[SepaDebitItemTable.contributionId]
                val amount = itemRow[SepaDebitItemTable.amount]
                val settleableAt = requireNotNull(itemRow[SepaDebitItemTable.settleableAt])
                val paidAt = LocalDateTime(settleableAt.year, settleableAt.monthNumber, settleableAt.dayOfMonth, 0, 0)

                val contributionUpdated =
                    ContributionTable.update({
                        (ContributionTable.id eq contributionId) and (ContributionTable.status notInList ContributionStatusSets.SETTLED)
                    }) {
                        it[status] = ContributionStatus.PAID
                        it[ContributionTable.paidAt] = paidAt
                        it[paidAmount] = amount
                    }
                if (contributionUpdated == 0) {
                    // Zwischenzeitlich manuell auf PAID/WAIVED gesetzt -- Position schliessen, NICHT buchen.
                    SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) {
                        it[status] = SepaDebitItemStatus.SETTLED
                        it[journalEntryId] = null
                    }
                    return@transaction
                }
                val batchRow =
                    SepaDebitBatchTable
                        .selectAll()
                        .where { SepaDebitBatchTable.id eq itemRow[SepaDebitItemTable.batchId] }
                        .single()
                val journalEntryId =
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = contributionId,
                        paidAmount = amount,
                        paidAt = paidAt,
                        source = ContributionPaymentMethod.SEPA_DEBIT,
                        providerFee = null,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        voucherReference = "SEPA-${batchRow[SepaDebitBatchTable.messageId]}-${itemRow[SepaDebitItemTable.endToEndId]}",
                    )
                SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) {
                    it[status] = SepaDebitItemStatus.SETTLED
                    it[SepaDebitItemTable.journalEntryId] = journalEntryId
                }
                // Welle V1.3.2 "Webhooks" (ausgehend) -- see ContributionPaymentEvents KDoc. The
                // SEPA debit item's own id is the transactionId (no payment_transaction row exists
                // for this settlement source).
                ContributionPaymentEvents.publishPaid(
                    contributionId = contributionId,
                    paidAt = paidAt,
                    amount = amount,
                    transactionId = itemId.toString(),
                )
            }
            return true
        } catch (e: ConflictException) {
            // requireBalanced/CashRegisterGuard can throw -- this ONE position rolls back, the rest
            // continue (settleBatch iterates the whole settleableItemIds list regardless). M-4
            // (Review Round 1, 2026-08-19, MAJOR): this used to be a completely empty catch block --
            // no log line anywhere, and the caller had no way to learn a position silently failed to
            // post. Now logged AND surfaced via settleBatch's failedItemIds.
            logger.warn(e) { "settleBatch: item $itemId could not be posted: ${e.message}" }
            return false
        }
    }

    override suspend fun listBatches(
        status: SepaDebitBatchStatus?,
        limit: Int,
        beforeCreatedAt: LocalDateTime?,
    ): List<SepaDebitBatchDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_READ_ROLES)
        requireSepaReadable()
        val effectiveLimit = limit.coerceIn(1, 200)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (status != null) conditions += (SepaDebitBatchTable.status eq status)
            if (beforeCreatedAt != null) conditions += (SepaDebitBatchTable.createdAt less beforeCreatedAt)
            val query = SepaDebitBatchTable.selectAll()
            val filtered = if (conditions.isEmpty()) query else query.where { conditions.reduce { a, b -> a and b } }
            filtered.orderBy(SepaDebitBatchTable.createdAt, SortOrder.DESC).limit(effectiveLimit).map { batchRowToDto(it) }
        }
    }

    override suspend fun getBatch(batchId: String): SepaDebitBatchDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_READ_ROLES)
        requireSepaReadable()
        val id = batchId.toSepaBatchUuid()
        return transaction {
            val batchRow =
                SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Lauf nicht gefunden.")
            val items = SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId eq id }.map { itemRowToDto(it) }
            SepaDebitBatchDetailDto(batch = batchRowToDto(batchRow), items = items)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V1.2.2 -- Returns
    // ════════════════════════════════════════════════════════════════════

    override suspend fun recordReturn(input: SepaReturnInput): SepaReturnDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_TREASURY_ROLES)
        requireSepaUsable()
        val today = DbClock.nowLocalDateTime().date
        if (input.returnedAt > today) throw ConflictException("Das Rueckgabedatum darf nicht in der Zukunft liegen.")
        val returnFee = input.returnFee
        if (returnFee != null && (returnFee.signum() <= 0 || returnFee.scale() > 2)) {
            throw ConflictException("Das Rueckgabeentgelt ist ungueltig.")
        }
        val itemId = input.debitItemId.toSepaItemUuid()

        return transaction {
            val itemRow =
                SepaDebitItemTable
                    .selectAll()
                    .where { SepaDebitItemTable.id eq itemId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Position nicht gefunden.")
            if (itemRow[SepaDebitItemTable.status] !in setOf(SepaDebitItemStatus.PENDING, SepaDebitItemStatus.SETTLEABLE)) {
                throw ConflictException(
                    "Fuer diese Position kann kein Rueckläufer erfasst werden (Status: ${itemRow[SepaDebitItemTable.status]}).",
                )
            }

            val returnId = Uuid.random()
            val inserted =
                SepaReturnTable.insertIgnore {
                    it[id] = returnId
                    it[debitItemId] = itemId
                    it[returnedAt] = input.returnedAt
                    it[reasonCode] = input.reasonCode
                    it[reasonText] = input.reasonText?.take(500)
                    it[SepaReturnTable.returnFee] = returnFee
                    it[recordedBy] = current.memberId
                    it[recordedAt] = DbClock.nowLocalDateTime()
                }
            if (inserted.insertedCount == 0) throw ConflictException("Fuer diese Position ist bereits ein Rueckläufer erfasst.")

            val contributionId = itemRow[SepaDebitItemTable.contributionId]
            val mandateId = itemRow[SepaDebitItemTable.mandateId]
            SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) { it[status] = SepaDebitItemStatus.RETURNED }
            ContributionTable.update({ ContributionTable.id eq contributionId }) { it[status] = ContributionStatus.RETURNED }

            // M-6 (Review Round 1, 2026-08-19, MAJOR): EVERY return code -- not only MD01/MD06/MD07 --
            // now excludes the mandate from future automatic batch-candidate selection
            // (buildPreview/getMyMandate/createDebitBatch only ever look up an ACTIVE mandate). Before
            // this fix, a return for a "structural account problem" code (AC01/AC04/AC06/AC13/AG01/
            // AM04/MS02/MS03/SL01/OTHER -- i.e. every code that is NOT in
            // SepaReturnReasonSets.FORCES_MANDATE_REVOCATION) left the mandate ACTIVE, so the SAME
            // contribution (status RETURNED is itself in ContributionStatusSets.OUTSTANDING) would
            // silently re-enter the very next debit run against a dead/blocked account -- a fresh bank
            // return fee every cycle, with no cap, cooldown, or exclusion.
            //
            // Chosen mechanism: reuse the EXISTING SepaMandateStatus.REVOKED state rather than mint a
            // new enum literal -- a new literal would also require widening the
            // sepa_mandate.status VARCHAR(7) column, the CHECK constraint, and the kUML/schema-drift
            // pin (PaymentsSchemaDriftTest), for a distinction that only matters for the human-facing
            // reason text. The practical effect for a treasurer is identical either way: "this mandate
            // no longer produces batch candidates until a human reviews it and, if appropriate, the
            // member grants a NEW mandate via grantMandate". Only revocationReason differs, so a
            // treasurer reviewing the mandate list can still tell "structural account problem, no
            // mandate defect" apart from "mandate itself is invalid" (MD01/MD06/MD07). Deliberately
            // blunt for AM04 ("insufficient funds", arguably transient) too -- see class-level
            // disclosure discipline (same as [SepaReturnReasonSets.FORCES_MANDATE_REVOCATION]'s own
            // MD07 KDoc): this is this wave's own risk-management choice, not a claim that every code
            // in the excluded set is equally permanent; a treasurer who judges a specific case
            // recoverable can have the member grant a fresh mandate immediately.
            val forcesMandateProblem = input.reasonCode in SepaReturnReasonSets.FORCES_MANDATE_REVOCATION
            val revocationReasonText =
                if (forcesMandateProblem) {
                    "Automatisch widerrufen nach Rueckläufer ${input.reasonCode}"
                } else {
                    "Automatisch von zukuenftigen Laeufen ausgeschlossen nach Rueckläufer ${input.reasonCode} " +
                        "-- kein Mandatsfehler im engeren Sinn, zur manuellen Pruefung (Review Round 1, M-6)"
                }
            val mandateRow =
                SepaMandateTable
                    .selectAll()
                    .where { SepaMandateTable.id eq mandateId }
                    .forUpdate()
                    .single()
            val before = mandateSnapshotFrom(mandateRow)
            if (mandateRow[SepaMandateTable.status] == SepaMandateStatus.ACTIVE) {
                SepaMandateTable.update({ SepaMandateTable.id eq mandateId }) {
                    it[status] = SepaMandateStatus.REVOKED
                    it[revokedAt] = DbClock.nowLocalDateTime()
                    // Review Round 2 (2026-08-20, M-6 consistency fix, MINOR): revokedBy = null, NOT
                    // current.memberId -- this is a SYSTEM-driven auto-revocation triggered by the
                    // return's reason code (the M-6 policy above), not a human decision to revoke.
                    // The treasurer recorded the RETURN (see SepaReturnTable.recordedBy above, which
                    // correctly stays current.memberId); they did not personally decide to revoke
                    // this mandate. Matches the convention SepaBatchPoller.runPhaseB already uses for
                    // its own auto-revocation (membership withdrawal) -- see that KDoc and its "system
                    // actor, not a human" test assertion.
                    it[revokedBy] = null
                    it[revocationReason] = revocationReasonText
                }
                // Security Round 2 (2026-08-20, NEW-1): this mandate may have OTHER PENDING items in
                // a DRAFT/NOTIFIED/GENERATED batch besides the one THIS return concerns -- that one is
                // already handled directly above (RETURNED, not CANCELLED, so it is correctly excluded
                // from the query inside the shared helper below, which only looks at still-PENDING
                // items). Same actor convention as the mandate update just above: actorMemberId/
                // actorRole = null, NOT current.memberId/current.role -- this is a SYSTEM-driven
                // consequence of the M-6 policy, not the treasurer's own decision. See
                // [resetGeneratedBatchesForUnusableMandate] KDoc for the full multi-call-site picture.
                resetGeneratedBatchesForUnusableMandate(mandateId = mandateId, actorMemberId = null, actorRole = null)
            }
            ContributionTable.update({ ContributionTable.id eq contributionId }) {
                it[paymentMethod] = ContributionPaymentMethod.MANUAL
                it[sepaMandateId] = null
            }

            val updatedMandateRow = SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SEPA_MANDATE,
                entityId = mandateId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(SepaMandateSnapshot.serializer(), before),
                after = Json.encodeToString(SepaMandateSnapshot.serializer(), mandateSnapshotFrom(updatedMandateRow)),
            )
            loadReturnDto(returnId)
        }
    }

    override suspend fun listReturns(
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<SepaReturnDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_READ_ROLES)
        requireSepaReadable()
        val effectiveLimit = limit.coerceIn(1, 500)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (from != null) conditions += (SepaReturnTable.returnedAt greaterEq from)
            if (to != null) conditions += (SepaReturnTable.returnedAt lessEq to)
            val query = SepaReturnTable.selectAll()
            val filtered = if (conditions.isEmpty()) query else query.where { conditions.reduce { a, b -> a and b } }
            filtered.orderBy(SepaReturnTable.returnedAt, SortOrder.DESC).limit(effectiveLimit).map { loadReturnDto(it[SepaReturnTable.id]) }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V1.2.2 -- Member self-service
    // ════════════════════════════════════════════════════════════════════

    /**
     * Review Round 2 (2026-08-20, CRITICAL): the bare, implicit-join chain `SepaDebitItemTable
     * innerJoin SepaDebitBatchTable innerJoin ContributionTable innerJoin SepaMandateTable` threw
     * `IllegalStateException` at RUNTIME ("multiple primary key <-> foreign key references") on
     * its last step -- `SepaMandateTable.id` is referenced by TWO foreign keys within this join
     * set, `SepaDebitItemTable.mandateId` AND `ContributionTable.sepaMandateId`, so Exposed cannot
     * auto-infer which one this join means. This is the exact same bug class as Phase B's join in
     * [network.lapis.cloud.server.payment.sepa.SepaBatchPoller.runPhaseB], fixed earlier in this
     * same review round -- made `listMyPrenotifications` throw HTTP 500 on every call, for every
     * authenticated member, since the wave's first commit. Fixed by naming the join column
     * explicitly with the same `.join(Table, JoinType.INNER, col1, col2)` idiom, disambiguating in
     * favor of [SepaDebitItemTable.mandateId] -- the mandate actually used for this specific debit
     * item, not [ContributionTable.sepaMandateId] which may have since been repointed at a newer
     * mandate. The item/batch and batch/contribution steps each have only one FK path and remain
     * implicit joins.
     */
    override suspend fun listMyPrenotifications(): List<SepaPrenotificationDto> {
        val current = resolveCurrentMember(call)
        requireSepaReadable()
        return transaction {
            (SepaDebitItemTable innerJoin SepaDebitBatchTable innerJoin ContributionTable)
                .join(SepaMandateTable, JoinType.INNER, SepaDebitItemTable.mandateId, SepaMandateTable.id)
                .selectAll()
                .where {
                    (ContributionTable.memberId eq current.memberId) and
                        (
                            SepaDebitBatchTable.status inList
                                listOf(SepaDebitBatchStatus.NOTIFIED, SepaDebitBatchStatus.GENERATED, SepaDebitBatchStatus.SUBMITTED)
                        ) and
                        (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING)
                }
                // Security Round 1 (2026-08-20, MAJOR-4): reads the batch's OWN frozen creditorId/
                // creditorName (snapshotted at createDebitBatch time), never organization_settings
                // LIVE -- otherwise a member's pre-notification screen would retroactively show a
                // creditor identity that changed AFTER the batch was notified, silently masking the
                // exact divergence this fix exists to prevent (see createDebitBatch/generateBatchFile
                // KDoc). A batch created before the org's SEPA settings were fully configured can
                // legitimately carry `null` here -- such a row is skipped (`mapNotNull`) rather than
                // surfaced with a non-null-but-wrong DTO field.
                .mapNotNull { row ->
                    val creditorId = row[SepaDebitBatchTable.creditorId] ?: return@mapNotNull null
                    val creditorName = row[SepaDebitBatchTable.creditorName] ?: return@mapNotNull null
                    SepaPrenotificationDto(
                        batchId = row[SepaDebitBatchTable.id].toString(),
                        contributionId = row[ContributionTable.id].toString(),
                        mandateReference = row[SepaMandateTable.mandateReference],
                        creditorId = creditorId,
                        creditorName = creditorName,
                        amount = row[SepaDebitItemTable.amount],
                        requestedCollectionDate = row[SepaDebitBatchTable.requestedCollectionDate],
                        notifiedAt = requireNotNull(row[SepaDebitBatchTable.notifiedAt]),
                        debtorIbanLast4 = row[SepaMandateTable.debtorIbanLast4],
                    )
                }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Full write gate: (a) feature enabled, (b) current disclaimer acknowledged, (c) encryption key present.
     *
     * Review Round 1 (2026-08-19, CRITICAL, discovered via M-1's new test coverage -- see class
     * KDoc): the `OrganizationSettingsTable` read below was NOT wrapped in its own `transaction {}`
     * -- every call site (`grantMandate`/`previewDebitBatch`/`createDebitBatch`/`notifyBatch`/
     * `generateBatchFile`/`markBatchSubmitted`/`cancelBatch`/`settleBatch`/`recordReturn`) invokes
     * this function BEFORE opening its own `transaction { ... }` block, so this line always threw
     * `IllegalStateException("No transaction in context")` on every real invocation -- this made
     * every SEPA write path in this wave completely non-functional from the very first release
     * commit onward, exactly the class of bug M-1's own finding predicted zero test coverage would
     * hide. Fixed by making this function self-contained, same pattern
     * [sepaDisclaimerIsCurrentlyAcknowledged] (called two lines below) already used correctly.
     */
    private fun requireSepaUsable() {
        val settingsRow =
            transaction {
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
            }
        if (!settingsRow[OrganizationSettingsTable.sepaDebitEnabled]) {
            throw ConflictException("SEPA-Lastschrift ist fuer diese Organisation nicht aktiviert.")
        }
        if (!sepaDisclaimerIsCurrentlyAcknowledged()) {
            throw ConflictException("Der aktuelle SEPA-Rechtshinweis wurde noch nicht (erneut) bestaetigt.")
        }
        if (sepaConfig.secretEncryptionKey == null) {
            throw ConflictException(
                "LAPIS_SECRET_ENCRYPTION_KEY ist nicht gesetzt -- SEPA-Mandatsoperationen sind nicht verfuegbar.",
            )
        }
    }

    /** Read gate: only (a) -- a disabled key must not hide what already exists. Same "no transaction in context" fix as [requireSepaUsable]. */
    private fun requireSepaReadable() {
        val enabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.sepaDebitEnabled]
            }
        if (!enabled) throw ConflictException("SEPA-Lastschrift ist fuer diese Organisation nicht aktiviert.")
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

    private fun loadSepaCreditorSettingsDto(): SepaCreditorSettingsDto {
        val row = OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
        val creditorId = row[OrganizationSettingsTable.sepaCreditorId]
        val creditorName = row[OrganizationSettingsTable.sepaCreditorName]
        return SepaCreditorSettingsDto(
            sepaCreditorId = creditorId,
            sepaCreditorName = creditorName,
            sepaPrenotificationDays = row[OrganizationSettingsTable.sepaPrenotificationDays],
            readyForFileGeneration = creditorId != null && creditorName != null,
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun mandateRowToDto(row: ResultRow): SepaMandateDto {
        val memberId = row[SepaMandateTable.memberId]
        val createdBy = row[SepaMandateTable.createdBy]
        val grantedAt = row[SepaMandateTable.grantedAt]
        val lastUsedAt = row[SepaMandateTable.lastUsedAt]
        val expiresAt = SepaConfig.mandateExpiryDate(grantedAt = grantedAt.date, lastUsedAt = lastUsedAt)
        return SepaMandateDto(
            id = row[SepaMandateTable.id].toString(),
            memberId = memberId.toString(),
            memberDisplayName = memberDisplayName(memberId),
            mandateReference = row[SepaMandateTable.mandateReference],
            debtorName = row[SepaMandateTable.debtorName],
            debtorIbanLast4 = row[SepaMandateTable.debtorIbanLast4],
            debtorBic = row[SepaMandateTable.debtorBic],
            signatureDate = row[SepaMandateTable.signatureDate],
            sequenceType = row[SepaMandateTable.sequenceType],
            status = row[SepaMandateTable.status],
            grantedAt = grantedAt,
            revokedAt = row[SepaMandateTable.revokedAt],
            revocationReason = row[SepaMandateTable.revocationReason],
            lastUsedAt = lastUsedAt,
            lastDebitedAmount = row[SepaMandateTable.lastDebitedAmount],
            expiresAt = expiresAt,
            createdByMemberId = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            createdBySelf = createdBy == memberId,
        )
    }

    private fun loadMandateDto(mandateId: Uuid): SepaMandateDto =
        mandateRowToDto(SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single())

    private fun mandateSnapshotFrom(row: ResultRow): SepaMandateSnapshot =
        SepaMandateSnapshot(
            memberId = row[SepaMandateTable.memberId].toString(),
            mandateReference = row[SepaMandateTable.mandateReference],
            status = row[SepaMandateTable.status],
            sequenceType = row[SepaMandateTable.sequenceType],
            signatureDate = row[SepaMandateTable.signatureDate],
            lastUsedAt = row[SepaMandateTable.lastUsedAt],
            createdBySelf = row[SepaMandateTable.createdBy] == row[SepaMandateTable.memberId],
        )

    private fun batchRowToDto(row: ResultRow): SepaDebitBatchDto {
        val notifiedAt = row[SepaDebitBatchTable.notifiedAt]
        val requiredNoticeDays = row[SepaDebitBatchTable.requiredNoticeDays]
        val fileGenerationAllowedFrom =
            if (notifiedAt != null &&
                requiredNoticeDays != null
            ) {
                notifiedAt.date.plus(requiredNoticeDays, DateTimeUnit.DAY)
            } else {
                null
            }
        val submittedAt = row[SepaDebitBatchTable.submittedAt]
        val settlementEligibleFrom = submittedAt?.date?.plus(SepaConfig.RETURN_WINDOW_DAYS, DateTimeUnit.DAY)
        return SepaDebitBatchDto(
            id = row[SepaDebitBatchTable.id].toString(),
            messageId = row[SepaDebitBatchTable.messageId],
            paymentInfoId = row[SepaDebitBatchTable.paymentInfoId],
            requestedCollectionDate = row[SepaDebitBatchTable.requestedCollectionDate],
            sequenceType = row[SepaDebitBatchTable.sequenceType],
            status = row[SepaDebitBatchTable.status],
            itemCount = row[SepaDebitBatchTable.itemCount],
            totalAmount = row[SepaDebitBatchTable.totalAmount],
            createdByDisplayName = memberDisplayName(row[SepaDebitBatchTable.createdBy]),
            createdAt = row[SepaDebitBatchTable.createdAt],
            notifiedAt = notifiedAt,
            requiredNoticeDays = requiredNoticeDays,
            fileGenerationAllowedFrom = fileGenerationAllowedFrom,
            generatedAt = row[SepaDebitBatchTable.generatedAt],
            generatedDocumentId = row[SepaDebitBatchTable.generatedDocumentId]?.toString(),
            prenotificationDocumentId = row[SepaDebitBatchTable.prenotificationDocumentId]?.toString(),
            submittedAt = submittedAt,
            submittedNote = row[SepaDebitBatchTable.submittedNote],
            settledAt = row[SepaDebitBatchTable.settledAt],
            settlementEligibleFrom = settlementEligibleFrom,
            cancelledAt = row[SepaDebitBatchTable.cancelledAt],
            cancellationReason = row[SepaDebitBatchTable.cancellationReason],
        )
    }

    private fun loadBatchDto(batchId: Uuid): SepaDebitBatchDto =
        batchRowToDto(SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single())

    private fun itemRowToDto(row: ResultRow): SepaDebitItemDto {
        val itemId = row[SepaDebitItemTable.id]
        val mandateRow = SepaMandateTable.selectAll().where { SepaMandateTable.id eq row[SepaDebitItemTable.mandateId] }.single()
        val returnRow = SepaReturnTable.selectAll().where { SepaReturnTable.debitItemId eq itemId }.singleOrNull()
        return SepaDebitItemDto(
            id = itemId.toString(),
            batchId = row[SepaDebitItemTable.batchId].toString(),
            contributionId = row[SepaDebitItemTable.contributionId].toString(),
            memberDisplayName = memberDisplayName(mandateRow[SepaMandateTable.memberId]),
            mandateId = row[SepaDebitItemTable.mandateId].toString(),
            mandateReference = mandateRow[SepaMandateTable.mandateReference],
            debtorIbanLast4 = mandateRow[SepaMandateTable.debtorIbanLast4],
            endToEndId = row[SepaDebitItemTable.endToEndId],
            amount = row[SepaDebitItemTable.amount],
            remittanceInformation = row[SepaDebitItemTable.remittanceInformation],
            status = row[SepaDebitItemTable.status],
            settleableAt = row[SepaDebitItemTable.settleableAt],
            journalEntryId = row[SepaDebitItemTable.journalEntryId]?.toString(),
            returnReason = returnRow?.get(SepaReturnTable.reasonCode),
        )
    }

    private fun loadReturnDto(returnId: Uuid): SepaReturnDto {
        val returnRow = SepaReturnTable.selectAll().where { SepaReturnTable.id eq returnId }.single()
        val itemId = returnRow[SepaReturnTable.debitItemId]
        val itemRow = SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single()
        val mandateRow = SepaMandateTable.selectAll().where { SepaMandateTable.id eq itemRow[SepaDebitItemTable.mandateId] }.single()
        return SepaReturnDto(
            id = returnRow[SepaReturnTable.id].toString(),
            debitItemId = itemId.toString(),
            batchId = itemRow[SepaDebitItemTable.batchId].toString(),
            contributionId = itemRow[SepaDebitItemTable.contributionId].toString(),
            memberDisplayName = memberDisplayName(mandateRow[SepaMandateTable.memberId]),
            returnedAt = returnRow[SepaReturnTable.returnedAt],
            reasonCode = returnRow[SepaReturnTable.reasonCode],
            reasonText = returnRow[SepaReturnTable.reasonText],
            returnFee = returnRow[SepaReturnTable.returnFee],
            recordedByDisplayName = memberDisplayName(returnRow[SepaReturnTable.recordedBy]),
            recordedAt = returnRow[SepaReturnTable.recordedAt],
            // M-6 (Review Round 1, 2026-08-19): reflects the mandate's ACTUAL resulting status rather
            // than being purely derived from the reason-code set -- since that fix, recordReturn
            // revokes the mandate for EVERY reason code (not only MD01/MD06/MD07), so deriving this
            // purely from the code would make the field always `true` and therefore meaningless. Reads
            // the current row rather than mandateRow (fetched before this return's own possible
            // revocation) so it is correct regardless of call order.
            mandateRevoked =
                SepaMandateTable
                    .selectAll()
                    .where { SepaMandateTable.id eq mandateRow[SepaMandateTable.id] }
                    .single()[SepaMandateTable.status] == SepaMandateStatus.REVOKED,
        )
    }

    /** See class KDoc "Rate limiting". Throws [ConflictException] once [limiter] is exceeded for [memberId]. */
    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    /** Selection logic shared by [previewDebitBatch] and [createDebitBatch]. Purely read-only. */
    private fun buildPreview(input: SepaDebitBatchInput): SepaDebitBatchPreviewDto {
        val today = DbClock.nowLocalDateTime().date
        val tierId = input.membershipTierId?.toSepaTierUuid()
        val relevantStatuses = (ContributionStatusSets.OUTSTANDING + ContributionStatusSets.DEBIT_IN_FLIGHT).toList()
        val conditions =
            mutableListOf<Op<Boolean>>(
                ContributionTable.status inList relevantStatuses,
                ContributionTable.dueDate lessEq input.dueOnOrBefore,
            )
        if (tierId != null) conditions += (ContributionTable.membershipTierId eq tierId)
        val candidates =
            (ContributionTable innerJoin MemberTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .toList()

        val items = mutableListOf<SepaDebitBatchPreviewItemDto>()
        val excluded = mutableListOf<SepaDebitBatchExclusionDto>()
        for (row in candidates) {
            val contributionId = row[ContributionTable.id]
            val memberId = row[ContributionTable.memberId]
            val amountDue = row[ContributionTable.amountDue]
            val memberDisplay = row[MemberTable.displayName]
            val memberStatus = row[MemberTable.status]
            when {
                row[ContributionTable.status] in ContributionStatusSets.DEBIT_IN_FLIGHT ->
                    excluded +=
                        SepaDebitBatchExclusionDto(
                            contributionId = contributionId.toString(),
                            memberDisplayName = memberDisplay,
                            reason = SepaDebitExclusionReason.ALREADY_IN_FLIGHT,
                        )
                amountDue.signum() <= 0 ->
                    excluded +=
                        SepaDebitBatchExclusionDto(
                            contributionId = contributionId.toString(),
                            memberDisplayName = memberDisplay,
                            reason = SepaDebitExclusionReason.AMOUNT_NOT_POSITIVE,
                        )
                memberStatus != MemberStatus.ACTIVE ->
                    excluded +=
                        SepaDebitBatchExclusionDto(
                            contributionId = contributionId.toString(),
                            memberDisplayName = memberDisplay,
                            reason = SepaDebitExclusionReason.MEMBER_NOT_ACTIVE,
                        )
                else -> {
                    // Minor (Review Round 1, 2026-08-19): .singleOrNull() on Kotlin's Iterable
                    // returns `null` for BOTH zero AND more-than-one matches -- it does not throw for
                    // the latter. Review Round 2 (2026-08-20, MINOR): corrected -- there is NO
                    // `uq_sepa_mandate_member_active` DB index (investigated and deliberately
                    // DEFERRED as cross-dialect-infeasible, see the comment in V8__sepa_mandates.sql).
                    // Two ACTIVE mandates for the same member (a bug, or a race predating
                    // grantMandate's own forUpdate() guard, which is the ONLY thing currently
                    // enforcing "at most one ACTIVE mandate per member" -- removing or weakening it
                    // would reopen the race) would silently make this branch treat the member as
                    // having NO active mandate at all -- wrongly excluding someone who actually has
                    // one. This ordered/limited lookup is defense in depth for exactly that case, not
                    // a fallback for a rare legacy pre-index row.
                    val mandateRow =
                        SepaMandateTable
                            .selectAll()
                            .where { (SepaMandateTable.memberId eq memberId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                            .orderBy(SepaMandateTable.grantedAt, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    // Review Round 2 (2026-08-20, N-4, MINOR/UX): Round 1's fix added the mandate
                    // expiry re-check (36 months without use) to createDebitBatch's own eligibility
                    // loop, but never mirrored it here in the preview-only path -- a treasurer would
                    // preview N members, create the batch, and silently get fewer than N with no
                    // indication which member or why for the expiry case specifically. Reuses the
                    // SAME SepaConfig.mandateExpiryDate helper as createDebitBatch/getMyMandate --
                    // never a second inline date calculation.
                    val mandateExpiresAt =
                        mandateRow?.let {
                            SepaConfig.mandateExpiryDate(
                                grantedAt = it[SepaMandateTable.grantedAt].date,
                                lastUsedAt = it[SepaMandateTable.lastUsedAt],
                            )
                        }
                    if (mandateRow == null) {
                        excluded +=
                            SepaDebitBatchExclusionDto(
                                contributionId = contributionId.toString(),
                                memberDisplayName = memberDisplay,
                                reason = SepaDebitExclusionReason.NO_ACTIVE_MANDATE,
                            )
                    } else if (mandateExpiresAt != null && mandateExpiresAt < today) {
                        excluded +=
                            SepaDebitBatchExclusionDto(
                                contributionId = contributionId.toString(),
                                memberDisplayName = memberDisplay,
                                reason = SepaDebitExclusionReason.MANDATE_EXPIRED,
                            )
                    } else {
                        val lastDebited = mandateRow[SepaMandateTable.lastDebitedAmount]
                        items +=
                            SepaDebitBatchPreviewItemDto(
                                contributionId = contributionId.toString(),
                                memberDisplayName = memberDisplay,
                                amount = amountDue,
                                mandateReference = mandateRow[SepaMandateTable.mandateReference],
                                debtorIbanLast4 = mandateRow[SepaMandateTable.debtorIbanLast4],
                                amountIncreased = lastDebited != null && amountDue.compareTo(lastDebited) > 0,
                            )
                    }
                }
            }
        }
        val total = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
        return SepaDebitBatchPreviewDto(itemCount = items.size, totalAmount = total, items = items, excluded = excluded)
    }
}

private fun batchSnapshotFrom(row: ResultRow): SepaDebitBatchSnapshot =
    SepaDebitBatchSnapshot(
        messageId = row[SepaDebitBatchTable.messageId],
        status = row[SepaDebitBatchTable.status],
        sequenceType = row[SepaDebitBatchTable.sequenceType],
        requestedCollectionDate = row[SepaDebitBatchTable.requestedCollectionDate],
        itemCount = row[SepaDebitBatchTable.itemCount],
        totalAmount = row[SepaDebitBatchTable.totalAmount],
        requiredNoticeDays = row[SepaDebitBatchTable.requiredNoticeDays],
        notifiedAt = row[SepaDebitBatchTable.notifiedAt],
        generatedDocumentId = row[SepaDebitBatchTable.generatedDocumentId]?.toString(),
    )

private fun batchSnapshot(batchId: Uuid): SepaDebitBatchSnapshot =
    batchSnapshotFrom(SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single())

private fun recalculateBatchTotals(batchId: Uuid) {
    val items =
        SepaDebitItemTable
            .selectAll()
            .where {
                (SepaDebitItemTable.batchId eq batchId) and
                    (SepaDebitItemTable.status neq SepaDebitItemStatus.CANCELLED)
            }.toList()
    val total = items.fold(BigDecimal.ZERO) { acc, row -> acc + row[SepaDebitItemTable.amount] }
    SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq batchId }) {
        it[itemCount] = items.size
        it[totalAmount] = total
    }
}

/**
 * Security Round 1 (2026-08-20, MAJOR-3). Called (originally only from [SepaService.revokeMandate],
 * now via the shared [resetGeneratedBatchesForUnusableMandate] below from every REVOKED- or
 * EXPIRED-transition call site, see that function's own KDoc -- Security Round 2, 2026-08-20, NEW-1,
 * generalized from "revoked" to "unusable" in Security Round 3, 2026-08-20, F-1b) when a mandate that
 * just became unusable (REVOKED or EXPIRED) had a PENDING item inside a batch already in
 * [SepaDebitBatchStatus.GENERATED] status -- the pain.008 XML file for that batch was ALREADY written
 * to disk (see [SepaService.generateBatchFile]) and still contains a debit instruction for the
 * now-unusable mandate's account. Leaving the batch in GENERATED status would let a treasurer,
 * unaware of the revocation/expiry, upload that STALE file (still authorizing the bank to debit the
 * account) and confirm submission via [SepaService.markBatchSubmitted] -- which only iterates PENDING
 * items, so it would silently skip the now-CANCELLED item, leaving the DB's own record of "what was
 * submitted" inconsistent with what the uploaded file actually contains. (As of Security Round 3,
 * F-1a, [SepaService.markBatchSubmitted] ALSO re-checks mandate validity itself as a second,
 * poller-independent line of defense -- this function remains worth calling promptly for
 * defense-in-depth/UX, not as the only guard.)
 *
 * **Chosen fix: reset, not reject.** Two options were on the table (see the finding this KDoc
 * answers): (a) reject the revocation outright and require manual [SepaService.cancelBatch]
 * intervention, or (b) reset the batch back to [SepaDebitBatchStatus.NOTIFIED], forcing a fresh
 * [SepaService.generateBatchFile] call before it can be submitted again. (b) was chosen: a member's
 * self-service [SepaService.revokeMandate] call must never be able to fail because of an UNRELATED
 * batch a treasurer happens to be running -- rejecting the revocation would either block a member's
 * own mandate withdrawal (a real, not merely UX, problem: DSGVO/BGB withdrawal rights do not bend
 * to a treasurer's workflow timing) or require this method to special-case "member revoking
 * their own mandate" vs. "treasury on behalf" differently, which is more surface area than the
 * reset. A reset is also strictly less destructive than [SepaService.cancelBatch] (which would drop
 * EVERY remaining item, not just the revoked one) -- the pre-notification period has already elapsed
 * for a GENERATED batch (see [SepaService.generateBatchFile]'s own gate), so `NOTIFIED` is the correct
 * predecessor state to return to: `notifiedAt`/`requiredNoticeDays` stay untouched, so the
 * treasurer can regenerate immediately without waiting through the notice period again.
 *
 * The stale [DocumentTable] row is soft-deleted (`isDeleted = true`) in the SAME step, not left
 * dangling -- [network.lapis.cloud.server.routes.registerSepaRoutes]'s download route now
 * checks `isDeleted` (Security Round 1 MAJOR-1), so the stale file becomes un-downloadable the
 * instant this runs, closing the exact "treasurer re-uploads the stale file" window this fix
 * targets. The bytes themselves are deliberately NOT deleted from disk here -- consistent with
 * this wave's "no purge job this round" retention posture (see [PaymentsPersonalData] KDoc
 * "Security Round 1 MAJOR-2").
 *
 * Writes its OWN [AuditEntityType.SEPA_DEBIT_BATCH] audit entry -- the triggering mandate's own
 * revocation/expiry already gets its [AuditEntityType.SEPA_MANDATE] entry at its own call site; a
 * batch-level state change (status transition + document invalidation) needs its own batch-level
 * entry so the GoBD trail shows the actual consequence, not just the triggering mandate event. This
 * is a deliberate, narrow exception to [SepaService]'s "at most one [AuditLogRecorder.record] call
 * per transaction" framework rule (Teil 7.0, rule 3) -- every caller's OWN mandate-level `record`
 * call remains the transaction's LAST lock-taking operation either way, since this function only
 * ever runs earlier in the same transaction, before that final call.
 *
 * [actorMemberId]/[actorRole] attribute this entry -- see [resetGeneratedBatchesForUnusableMandate]
 * KDoc for who passes what and why.
 */
private fun resetGeneratedBatchAfterMandateBecameUnusable(
    batchId: Uuid,
    actorMemberId: Uuid?,
    actorRole: AccountRole?,
) {
    val batchRow =
        SepaDebitBatchTable
            .selectAll()
            .where { SepaDebitBatchTable.id eq batchId }
            .forUpdate()
            .singleOrNull() ?: return
    if (batchRow[SepaDebitBatchTable.status] != SepaDebitBatchStatus.GENERATED) return
    val before = batchSnapshotFrom(batchRow)
    val staleDocumentId = batchRow[SepaDebitBatchTable.generatedDocumentId]

    SepaDebitBatchTable.update({ SepaDebitBatchTable.id eq batchId }) {
        it[status] = SepaDebitBatchStatus.NOTIFIED
        it[generatedAt] = null
        it[generatedDocumentId] = null
    }
    if (staleDocumentId != null) {
        DocumentTable.update({ DocumentTable.id eq staleDocumentId }) { it[isDeleted] = true }
    }

    AuditLogRecorder.record(
        actorMemberId = actorMemberId,
        actorRole = actorRole,
        entityType = AuditEntityType.SEPA_DEBIT_BATCH,
        entityId = batchId,
        action = AuditAction.UPDATE,
        before = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), before),
        after = Json.encodeToString(SepaDebitBatchSnapshot.serializer(), batchSnapshot(batchId)),
    )
}

/**
 * Shared "an item's mandate just became unusable ([SepaMandateStatus.REVOKED] or
 * [SepaMandateStatus.EXPIRED])" batch-consistency effect -- Security Round 2 (2026-08-20, NEW-1),
 * generalized from REVOKED-only to REVOKED-or-EXPIRED in Security Round 3 (2026-08-20, F-1b/F-2).
 * Cancels every still-PENDING [SepaDebitItemTable] item of [mandateId] that sits in a
 * DRAFT/NOTIFIED/GENERATED batch (reviving the item's contribution back to OPEN, exactly
 * [SepaService.revokeMandate]'s own pre-existing "laufende Batches" comment already documented),
 * recalculates each affected batch's totals, and -- for any batch that was already
 * [SepaDebitBatchStatus.GENERATED] -- resets it via [resetGeneratedBatchAfterMandateBecameUnusable]
 * so a treasurer can never submit a pain.008 file that still authorizes a debit against this
 * now-unusable mandate.
 *
 * **Called from EVERY place a mandate transitions to REVOKED or EXPIRED**, not only from
 * [SepaService.revokeMandate] where this logic originally lived inline (Security Round 1,
 * 2026-08-20, MAJOR-3):
 * - [SepaService.recordReturn]'s M-6 auto-revocation branch,
 * - [network.lapis.cloud.server.payment.sepa.SepaBatchPoller.runPhaseB]'s membership-withdrawal
 *   auto-revocation (wired in Security Round 2, NEW-1), and
 * - [network.lapis.cloud.server.payment.sepa.SepaBatchPoller.runPhaseA]'s 36-month expiry auto-flip
 *   (wired in Security Round 3, F-1b -- before this fix, Phase A flipped a mandate to EXPIRED via a
 *   bare table update and wrote only its own mandate-level audit entry, leaving any of that
 *   mandate's still-PENDING items in an already-GENERATED batch completely untouched)
 *
 * ALSO transition a mandate to REVOKED/EXPIRED via a bare `SepaMandateTable.update`, and each one
 * that skips this function reopens exactly the harm [resetGeneratedBatchAfterMandateBecameUnusable]
 * was built to close, through a door it did not cover. A return recorded against ONE item of a
 * mandate does not, by itself, touch any of that SAME mandate's OTHER pending items sitting in a
 * DIFFERENT (already-GENERATED) batch; neither does the poller's membership-withdrawal check or its
 * expiry check. This function is the single place that sweeps ALL of an unusable mandate's still-open
 * exposure, regardless of which of the four call sites triggered it.
 *
 * Must be called from inside the caller's ALREADY-open `transaction {}`, immediately AFTER the
 * mandate row's own `status = REVOKED`/`EXPIRED` update -- this function only REACTS to that state,
 * it does not perform the mandate-level transition itself (each call site keeps its own
 * reason/actor semantics for that update).
 *
 * [actorMemberId]/[actorRole] attribute the resulting batch-reset audit entr(y/ies), if any:
 * - [SepaService.revokeMandate] passes the ACTUAL caller (`current.memberId`/`current.role`) --
 *   human-initiated, whether self-service or on-behalf-of.
 * - [SepaService.recordReturn]'s M-6 branch and both
 *   [network.lapis.cloud.server.payment.sepa.SepaBatchPoller] phases pass `null`/`null` --
 *   SYSTEM-driven auto-transition, not a human decision, matching the SAME convention each of those
 *   call sites already applies to the mandate-level audit entry it writes for its OWN
 *   revocation/expiry. [SepaBatchPoller] in particular runs in a background coroutine with no
 *   authenticated caller at all -- nullable parameters are required here, not merely a convenience,
 *   for that call site to be able to call this function at all.
 */
internal fun resetGeneratedBatchesForUnusableMandate(
    mandateId: Uuid,
    actorMemberId: Uuid?,
    actorRole: AccountRole?,
) {
    // Security Round 3 (2026-08-20, F-2, LOW/MINOR): this GENERATED-detection read now takes its own
    // row lock (`.forUpdate()`, ordered by item id -- same fixed-lock-order discipline as
    // createDebitBatch/prepareBatchFileGeneration). Before this fix, the read was unlocked, so under
    // READ COMMITTED a narrow window existed where it could miss a batch concurrently transitioning
    // through [SepaService.generateBatchFile]'s own "Phase 3" `FOR UPDATE` lock on the SAME batch
    // row -- e.g. this function's caller commits a mandate REVOKED/EXPIRED transition in the same
    // instant generateBatchFile's Phase 3 is mid-flight for a batch holding one of this mandate's
    // items, and this read runs BEFORE Phase 3's own write is visible, missing the now-GENERATED
    // batch entirely. The lock genuinely serializes this read against that Phase 3 lock on the same
    // rows, closing the window -- a mismatch is then still caught by
    // [SepaService.markBatchSubmitted]'s own item-count divergence check and (Security Round 3,
    // F-1a) its own mandate-validity re-check, but closing the window here is strictly better than
    // relying on that later, narrower backstop alone.
    val affectedItems =
        (SepaDebitItemTable innerJoin SepaDebitBatchTable)
            .selectAll()
            .where {
                (SepaDebitItemTable.mandateId eq mandateId) and
                    (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING) and
                    (SepaDebitBatchTable.status inList SEPA_BATCH_IN_FLIGHT_STATUSES)
            }.orderBy(SepaDebitItemTable.id)
            .forUpdate()
            .toList()
    val affectedBatchIds = mutableSetOf<Uuid>()
    val batchesNeedingRegeneration = mutableSetOf<Uuid>()
    affectedItems.forEach { itemRow ->
        val itemId = itemRow[SepaDebitItemTable.id]
        val contributionId = itemRow[SepaDebitItemTable.contributionId]
        val batchId = itemRow[SepaDebitItemTable.batchId]
        SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) { it[status] = SepaDebitItemStatus.CANCELLED }
        ContributionTable.update({ ContributionTable.id eq contributionId }) {
            it[status] = ContributionStatus.OPEN
            it[sepaMandateId] = null
        }
        affectedBatchIds += batchId
        if (itemRow[SepaDebitBatchTable.status] == SepaDebitBatchStatus.GENERATED) {
            batchesNeedingRegeneration += batchId
        }
    }
    affectedBatchIds.forEach { recalculateBatchTotals(it) }
    batchesNeedingRegeneration.forEach { batchId ->
        resetGeneratedBatchAfterMandateBecameUnusable(batchId = batchId, actorMemberId = actorMemberId, actorRole = actorRole)
    }
}

private fun sepaBatchMessageId(now: LocalDateTime): String {
    // Review Round 2 (2026-08-20, N-3, MINOR): Locale.ROOT added explicitly -- without it, %d
    // renders under the JVM's default locale, which under certain locale configurations (e.g.
    // Arabic-Indic digit extensions) can produce non-ASCII digits in this internal message ID.
    // (%02X below is unaffected -- Java's Formatter applies no localization to hex conversions.)
    val stamp =
        "%04d%02d%02d%02d%02d%02d".format(
            Locale.ROOT,
            now.year,
            now.monthNumber,
            now.dayOfMonth,
            now.hour,
            now.minute,
            now.second,
        )
    val randomBytes = ByteArray(3)
    sepaMessageIdRandom.nextBytes(randomBytes)
    val randomHex = randomBytes.joinToString("") { "%02X".format(it) }
    return "LC-DD-$stamp-$randomHex"
}

private fun SepaCreditorSettingsDto.toSnapshot() =
    SepaCreditorSettingsSnapshot(
        sepaCreditorId = sepaCreditorId,
        sepaCreditorName = sepaCreditorName,
        sepaPrenotificationDays = sepaPrenotificationDays,
    )

private fun String.toSepaMandateUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Mandat nicht gefunden.") }

private fun String.toSepaBatchUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Lauf nicht gefunden.") }

private fun String.toSepaItemUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Position nicht gefunden.") }

private fun String.toSepaMemberUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Mitglied nicht gefunden.") }

private fun String.toSepaTierUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Beitragsstufe nicht gefunden.") }

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
 * Welle V1.2.2 is the first real caller of this V1.2.1-built gate -- see [SepaService.requireSepaUsable].
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
