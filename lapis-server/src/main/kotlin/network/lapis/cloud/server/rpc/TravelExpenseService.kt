package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchItemTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.payment.sepa.SepaPain001Writer
import network.lapis.cloud.server.rpc.TravelExpenseLineRules.orBadRequest
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MultiDayRule
import network.lapis.cloud.shared.domain.RefundAccountPrefillDto
import network.lapis.cloud.shared.domain.SepaDebtorAccountOptionDto
import network.lapis.cloud.shared.domain.TravelContactHintFormat
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseAttachmentDto
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseDeadline
import network.lapis.cloud.shared.domain.TravelExpenseDocumentDto
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseRatesSnapshot
import network.lapis.cloud.shared.domain.TravelExpenseReceiptDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import network.lapis.cloud.shared.domain.TravelExpenseSepaBatchDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaContextDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaReference
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput
import network.lapis.cloud.shared.domain.TravelExpenseSnapshot
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val TRAVEL_EXPENSE_DECISION_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private const val MAX_LIST_RESULTS = 200
private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger".
 *
 * **Zustandsautomat** (lückenlos, jeder Übergang genau einmal implementiert):
 * | von | nach | Methode | Bedingung |
 * |---|---|---|---|
 * | — | DRAFT | [createDraft] | Subjekt aktiv, < [TravelExpenseAmountRules.MAX_OPEN_DRAFTS_PER_MEMBER] offene Entwürfe |
 * | DRAFT | DRAFT | [updateDraft]/[addLine]/[removeLine]/Beleg-Upload/-Löschung | Subjekt oder Antragsteller |
 * | DRAFT | REQUESTED | [submitReport] | volle Submit-Validierung + Satz-Freeze |
 * | DRAFT | WITHDRAWN | [withdrawReport] | Subjekt oder Antragsteller |
 * | REQUESTED | WITHDRAWN | [withdrawReport] | Subjekt oder Antragsteller |
 * | REQUESTED | REJECTED | [decideReport]`(false)` | BOARD/ADMIN, nicht Subjekt und nicht Antragsteller, Notiz Pflicht |
 * | REQUESTED | EXECUTED | [decideReport]`(true)` | + Buchung erfolgreich |
 * | REQUESTED | APPROVED | [decideReport]`(true)` | + Buchung gescheitert -> `executionError` |
 * | APPROVED | EXECUTED | [retryPosting] | + Buchung erfolgreich, nicht Subjekt und nicht Antragsteller |
 * | APPROVED | APPROVED | [retryPosting] | erneut gescheitert, `executionError` aktualisiert, nicht Subjekt und nicht Antragsteller |
 * | APPROVED | REJECTED | [decideReport]`(false)` | einziger Ausweg aus dauerhaft unbuchbar ("keine Sackgasse") |
 *
 * **Vier-Augen lückenlos** ([decideReport], [retryPosting]): the actor in `{subjectMemberId,
 * requestedBy}` -> [ForbiddenException], ohne Ausnahme, in BEIDEN Fällen -- der zweite Zweig
 * schließt genau die "im Namen von"-Lücke, die `ContributionReliefService.decideReliefRequest`
 * (das nur `subjectMemberId` prüft) offenlässt. [retryPosting] enforces the SAME exclusion as
 * [decideReport] (Security-Audit fix, 2026-09-12) -- it is the other method that can produce the
 * booking journal_entry, so the four-eyes guarantee must cover it too, not only the initial
 * approval.
 *
 * **`submitted_at` als Keyset-Spalte ist nullable**: [listReports] filtert deshalb IMMER hart auf
 * `submittedAt.isNotNull()`, egal welcher [TravelExpenseReportStatus]-Filter mitgegeben wird --
 * ein Entwurf ist privat, `status = DRAFT` als Argument wirft [BadRequestException] statt still
 * leer zu liefern.
 */
class TravelExpenseService(
    private val call: ApplicationCall,
    private val receiptStorageRoot: File,
    /** `LAPIS_SECRET_ENCRYPTION_KEY`; `null` == refund IBANs cannot be stored and no SEPA file can be generated (never a plaintext fallback). */
    private val secretEncryptionKey: ByteArray? = null,
    /** V1.9.199: budget of [previewTravelContactHint]; Application passes ONE shared instance (the service itself is created per call). */
    private val previewRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
) : ITravelExpenseService {
    private val sepaExport = TravelExpenseSepaExport(secretEncryptionKey)

    override suspend fun getTravelExpenseRates(): TravelExpenseRatesDto {
        val current = resolveCurrentMember(call)
        return transaction { TravelExpenseSettingsStore.load().toDto(role = current.role, status = current.status) }
    }

    override suspend fun updateTravelExpenseRates(
        mileageRatePerKm: BigDecimal?,
        perDiemRate: BigDecimal?,
    ): TravelExpenseRatesDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (mileageRatePerKm != null) {
            if (mileageRatePerKm <= BigDecimal.ZERO) throw BadRequestException("mileageRatePerKm must be positive")
            // V1.9.122: the calculator works in whole cents, so a mileage rate must be a whole-cent value.
            if (mileageRatePerKm.stripTrailingZeros().scale() > MAX_MILEAGE_RATE_SCALE) {
                throw BadRequestException("mileageRatePerKm must have at most $MAX_MILEAGE_RATE_SCALE fractional digits")
            }
            if (mileageRatePerKm > MAX_MILEAGE_RATE) throw BadRequestException("mileageRatePerKm must be at most $MAX_MILEAGE_RATE")
        }
        if (perDiemRate != null) {
            if (perDiemRate <= BigDecimal.ZERO) throw BadRequestException("perDiemRate must be positive")
            if (perDiemRate.scale() > MAX_PER_DIEM_RATE_SCALE) {
                throw BadRequestException("perDiemRate must have at most $MAX_PER_DIEM_RATE_SCALE fractional digits")
            }
        }
        return transaction {
            val before = TravelExpenseSettingsStore.load()
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[travelMileageRatePerKm] = mileageRatePerKm
                it[travelPerDiemRate] = perDiemRate
            }
            val after = TravelExpenseSettingsStore.load()
            recordSettingsChange(current = current, before = before, after = after)
            after.toDto(role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
        }
    }

    override suspend fun updateTravelExpenseSettings(input: TravelExpenseSettingsInput): TravelExpenseRatesDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val valid = TravelExpenseLineRules.validateSettings(input)
        val validHint = valid.contact.hint
        if (valid.contact.hintFormat == TravelContactHintFormat.MARKDOWN &&
            validHint != null &&
            TravelContactHintMarkdown.renderOrNull(validHint) == null
        ) {
            throw BadRequestException("contact hint is too deeply nested")
        }
        val advanceAccountId = valid.advanceAccountId?.toTravelExpenseUuid("advanceAccountId")
        val waiverAccountId = valid.donationWaiverAccountId?.toTravelExpenseUuid("donationWaiverAccountId")
        return transaction {
            listOfNotNull(advanceAccountId, waiverAccountId).forEach { accountId ->
                val account = LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                if (account == null || !account[LedgerAccountTable.active]) {
                    throw BadRequestException("ledger account $accountId does not exist or is inactive")
                }
            }
            val before = TravelExpenseSettingsStore.load()
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[travelTier1Hours] = valid.tierHours[0]
                it[travelTier2Hours] = valid.tierHours[1]
                it[travelTier3Hours] = valid.tierHours[2]
                it[travelTier1Cents] = valid.tierCents[0].toInt()
                it[travelTier2Cents] = valid.tierCents[1].toInt()
                it[travelTier3Cents] = valid.tierCents[2].toInt()
                it[travelBreakfastPct] = valid.breakfastPercent
                it[travelLunchPct] = valid.lunchPercent
                it[travelDinnerPct] = valid.dinnerPercent
                it[travelPassengerCentsPerKm] = valid.passengerCentsPerKm.toInt()
                it[travelMileageRatePerKm] = valid.mileageCentsPerKm?.centsToRate()
                it[travelPerDiemRate] = valid.perDiemLegacyRate?.setScale(2, RoundingMode.UNNECESSARY)
                it[travelContactName] = valid.contact.name
                it[travelContactEmail] = valid.contact.email
                it[travelContactPhone] = valid.contact.phone
                it[travelContactHint] = valid.contact.hint
                it[travelContactHintFormat] = valid.contact.hintFormat.name
                it[travelAdvanceAccountId] = advanceAccountId
                it[travelDonationWaiverAccountId] = waiverAccountId
            }
            val after = TravelExpenseSettingsStore.load()
            recordSettingsChange(current = current, before = before, after = after)
            logger.info { "travel expense settings updated by ${current.memberId}" }
            after.toDto(role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
        }
    }

    override suspend fun previewTravelContactHint(markdown: String): String {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        val normalized = markdown.replace("\r\n", "\n")
        if (normalized.length > TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH) {
            throw BadRequestException("text exceeds ${TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH} characters")
        }
        // Nothing about the text is logged, not even its length.
        return TravelContactHintMarkdown.renderOrNull(normalized) ?: throw BadRequestException("text is too deeply nested")
    }

    /**
     * Writes the ORGANIZATION_SETTINGS audit entry on an ACTUAL change only. The payload carries
     * numbers/booleans only -- never the contact's name, e-mail or phone. A change of just the contact
     * text still counts as a change (the booleans alone would not show it).
     */
    private fun recordSettingsChange(
        current: CurrentMember,
        before: TravelExpenseSettings,
        after: TravelExpenseSettings,
    ) {
        val beforeSnapshot = before.toAuditSnapshot()
        val afterSnapshot = after.toAuditSnapshot()
        if (beforeSnapshot == afterSnapshot && before.contact == after.contact) return
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.ORGANIZATION_SETTINGS,
            entityId = ORGANIZATION_SETTINGS_ID,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(TravelExpenseRatesSnapshot.serializer(), beforeSnapshot),
            after = Json.encodeToString(TravelExpenseRatesSnapshot.serializer(), afterSnapshot),
        )
    }

    private fun TravelExpenseSettings.toAuditSnapshot(): TravelExpenseRatesSnapshot =
        TravelExpenseRatesSnapshot(
            mileageRatePerKm = mileageRate,
            perDiemRate = perDiemRate,
            tierHours = rateSet.tierHours,
            tierCents = rateSet.tierCents,
            breakfastPercent = rateSet.breakfastPercent,
            lunchPercent = rateSet.lunchPercent,
            dinnerPercent = rateSet.dinnerPercent,
            passengerCentsPerKm = rateSet.passengerCentsPerKm,
            contactNameSet = contact.name != null,
            contactEmailSet = contact.email != null,
            contactPhoneSet = contact.phone != null,
            contactHintSet = contact.hint != null,
            contactHintMarkdown = contact.hint != null && contact.hintFormat == TravelContactHintFormat.MARKDOWN,
            advanceAccountConfigured = advanceAccountId != null,
            donationWaiverAccountConfigured = donationWaiverAccountId != null,
        )

    /** Must run inside a `transaction {}` (reads the document list). */
    private fun TravelExpenseSettings.toDto(
        role: AccountRole,
        status: MemberStatus,
    ): TravelExpenseRatesDto {
        val isAdmin = role == AccountRole.ADMIN
        val canManage = role == AccountRole.ADMIN || role == AccountRole.BOARD
        val hintText = contact.hint
        val hintHtml =
            if (contact.hintFormat == TravelContactHintFormat.MARKDOWN && hintText != null) {
                TravelContactHintMarkdown.renderOrNull(hintText)
            } else {
                null
            }
        return TravelExpenseRatesDto(
            mileageRatePerKm = mileageRate,
            perDiemRate = perDiemRate,
            expenseAccountConfigured = expenseAccountId != null,
            bankAccountConfigured = bankAccountId != null,
            rateSet = rateSet,
            contact = contact.copy(hintHtml = hintHtml),
            canEditSettings = isAdmin,
            advanceAccountId = if (isAdmin) advanceAccountId?.toString() else null,
            donationWaiverAccountId = if (isAdmin) donationWaiverAccountId?.toString() else null,
            // Organisation documents are for ACTIVE members and BOARD/ADMIN only (GUEST/FRIEND/APPLICATION/WITHDRAWN get an empty list).
            documents =
                if (canManage ||
                    status in MemberStatusSets.ORGANIZATION_MEMBER
                ) {
                    loadDocuments(includeUploader = canManage)
                } else {
                    emptyList()
                },
            canManageDocuments = canManage,
        )
    }

    /** Sorted by title (case-insensitive, then id for a stable order). `uploaderName` only when [includeUploader]; `null` == former member. */
    private fun loadDocuments(includeUploader: Boolean): List<TravelExpenseDocumentDto> {
        val rows = TravelExpenseDocumentTable.selectAll().toList()
        val names =
            if (includeUploader) {
                val ids = rows.mapNotNull { it[TravelExpenseDocumentTable.uploadedBy] }.distinct()
                if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id inList ids }
                        .associate { it[MemberTable.id] to it[MemberTable.displayName] }
                }
            } else {
                emptyMap()
            }
        return rows
            .sortedWith(
                compareBy<ResultRow> { it[TravelExpenseDocumentTable.title].lowercase() }.thenBy {
                    it[TravelExpenseDocumentTable.id]
                        .toString()
                },
            ).map { row ->
                TravelExpenseDocumentDto(
                    id = row[TravelExpenseDocumentTable.id].toString(),
                    title = row[TravelExpenseDocumentTable.title],
                    filename = row[TravelExpenseDocumentTable.originalFilename],
                    mimeType = row[TravelExpenseDocumentTable.mimeType],
                    sizeBytes = row[TravelExpenseDocumentTable.sizeBytes],
                    uploadedAt = row[TravelExpenseDocumentTable.uploadedAt],
                    uploaderName = row[TravelExpenseDocumentTable.uploadedBy]?.let { names[it] },
                )
            }
    }

    override suspend fun createDraft(
        subjectMemberId: String,
        input: TravelExpenseReportInput,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val subjectId = subjectMemberId.toTravelExpenseUuid("subjectMemberId")
        // IDOR-Gate: MEMBER nur für sich selbst, BOARD/ADMIN auch im Namen eines fremden Mitglieds.
        if (subjectId != current.memberId) current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        val now = DbClock.nowLocalDateTime()
        requireValidReportHeader(input = input, now = now)

        return transaction {
            val subjectRow =
                MemberTable.selectAll().where { MemberTable.id eq subjectId }.singleOrNull()
                    ?: throw NotFoundException("Member $subjectMemberId not found")
            if (subjectRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer request a travel expense reimbursement")
            }
            if (subjectRow[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) {
                throw ConflictException("Cannot create a travel expense report for a member whose membership has ended")
            }
            val openDrafts =
                TravelExpenseReportTable
                    .selectAll()
                    .where {
                        (TravelExpenseReportTable.subjectMemberId eq subjectId) and
                            (TravelExpenseReportTable.status inList TravelExpenseReportStatusSets.OPEN)
                    }.count()
            if (openDrafts >= TravelExpenseAmountRules.MAX_OPEN_DRAFTS_PER_MEMBER) {
                throw ConflictException(
                    "Member $subjectMemberId already has ${TravelExpenseAmountRules.MAX_OPEN_DRAFTS_PER_MEMBER} " +
                        "open travel expense reports",
                )
            }

            val id = Uuid.random()
            TravelExpenseReportTable.insert {
                it[TravelExpenseReportTable.id] = id
                it[TravelExpenseReportTable.subjectMemberId] = subjectId
                it[status] = TravelExpenseReportStatus.DRAFT
                it[purpose] = input.purpose.trim()
                it[travelFrom] = input.travelFrom
                it[travelTo] = input.travelTo
                it[totalAmount] = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
                it[createdAt] = now
                it[requestedBy] = current.memberId
                it[advanceAmount] = (input.advanceCents ?: 0L).centsToAmount()
                it[payoutChoice] = input.payoutChoice
            }
            TravelExpenseDaySync.sync(reportId = id, travelFrom = input.travelFrom, travelTo = input.travelTo, now = now)
            TravelExpenseDaySync.recomputeReport(reportId = id, rates = TravelExpenseSettingsStore.load().rateSet, strict = false)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.TRAVEL_EXPENSE_REPORT,
                entityId = id,
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        TravelExpenseSnapshot.serializer(),
                        TravelExpenseSnapshot(
                            reportId = id.toString(),
                            subjectMemberId = subjectId.toString(),
                            status = TravelExpenseReportStatus.DRAFT,
                            travelFrom = input.travelFrom,
                            travelTo = input.travelTo,
                            lineCount = 0,
                            lineKinds = emptyList(),
                            totalAmount = BigDecimal.ZERO.setScale(AMOUNT_SCALE),
                            payoutChoice = input.payoutChoice,
                            advanceCents = input.advanceCents ?: 0L,
                            dayLineCount = MultiDayRule.travelDays(from = input.travelFrom, to = input.travelTo).size,
                        ),
                    ),
                occurredAt = now,
            )
            logger.info { "travel expense draft created: id=$id subjectMemberId=$subjectId requestedBy=${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun updateDraft(
        reportId: String,
        input: TravelExpenseReportInput,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        requireValidReportHeader(input = input, now = now)
        return transaction {
            requireEditableRow(id = id, current = current)
            // Accommodation lines carry their own dates: refuse a period change that would orphan one.
            TravelExpenseLineTable
                .selectAll()
                .where { (TravelExpenseLineTable.reportId eq id) and (TravelExpenseLineTable.kind eq TravelExpenseLineKind.ACCOMMODATION) }
                .forEach { row ->
                    TravelExpenseLineRules.requireAccommodationInRange(
                        firstNight = requireNotNull(row[TravelExpenseLineTable.dayDate]),
                        nights = requireNotNull(row[TravelExpenseLineTable.days]),
                        travelFrom = input.travelFrom,
                        travelTo = input.travelTo,
                    )
                }
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[purpose] = input.purpose.trim()
                it[travelFrom] = input.travelFrom
                it[travelTo] = input.travelTo
                // null == unchanged (see TravelExpenseReportInput KDoc)
                if (input.advanceCents != null) it[advanceAmount] = input.advanceCents!!.centsToAmount()
                if (input.payoutChoice != null) it[payoutChoice] = input.payoutChoice
            }
            TravelExpenseDaySync.sync(reportId = id, travelFrom = input.travelFrom, travelTo = input.travelTo, now = now)
            TravelExpenseDaySync.recomputeReport(reportId = id, rates = TravelExpenseSettingsStore.load().rateSet, strict = false)
            logger.info { "travel expense draft $id updated by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun addLine(
        reportId: String,
        input: TravelExpenseLineInput,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        requireAddableKind(input.kind)
        return transaction {
            val report = requireEditableRow(id = id, current = current)
            val otherLineCount =
                TravelExpenseLineTable
                    .selectAll()
                    .where {
                        (TravelExpenseLineTable.reportId eq id) and
                            (TravelExpenseLineTable.kind neq TravelExpenseLineKind.DAY_ALLOWANCE)
                    }.count()
            if (otherLineCount >= TravelExpenseAmountRules.MAX_OTHER_LINES) {
                throw ConflictException("Report $reportId already has ${TravelExpenseAmountRules.MAX_OTHER_LINES} lines")
            }
            val rates = TravelExpenseSettingsStore.load().rateSet
            val validated = validateLine(input = input, rates = rates, report = report)

            val lineId = Uuid.random()
            TravelExpenseLineTable.insert {
                it[TravelExpenseLineTable.id] = lineId
                it[TravelExpenseLineTable.reportId] = report[TravelExpenseReportTable.id]
                it[kind] = input.kind
                it[description] = validated.description
                it[kilometers] = validated.kilometers
                it[days] = validated.days
                it[rateSnapshot] = validated.rateSnapshot
                it[amount] = validated.amountCents.centsToAmount()
                it[createdAt] = now
                it[dayDate] = validated.dayDate
                it[passengerCount] = validated.passengerCount
                it[breakfastIncluded] = validated.breakfastIncluded
                it[breakfastProvided] = false
                it[lunchProvided] = false
                it[dinnerProvided] = false
                it[deductionAmount] = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
            }
            TravelExpenseDaySync.recomputeReport(reportId = id, rates = rates, strict = false)
            logger.info { "travel expense line $lineId added to report $id by ${current.memberId} (kind=${input.kind})" }
            loadDto(id)
        }
    }

    override suspend fun updateLine(
        lineId: String,
        input: TravelExpenseLineInput,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val lineUuid = lineId.toTravelExpenseUuid("lineId")
        return transaction {
            val lineRow =
                TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq lineUuid }.singleOrNull()
                    ?: throw NotFoundException("TravelExpenseLine $lineId not found")
            val reportId = lineRow[TravelExpenseLineTable.reportId]
            val report = requireEditableRow(id = reportId, current = current)
            val existingKind = lineRow[TravelExpenseLineTable.kind]
            if (input.kind != existingKind) throw BadRequestException("the kind of an existing line cannot change")
            if (existingKind == TravelExpenseLineKind.DAY_ALLOWANCE) {
                throw BadRequestException("day lines are maintained via saveDays")
            }
            if (existingKind == TravelExpenseLineKind.PER_DIEM) throw BadRequestException("legacy_kind")
            val rates = TravelExpenseSettingsStore.load().rateSet
            val validated = validateLine(input = input, rates = rates, report = report)
            TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineUuid }) {
                it[description] = validated.description
                it[kilometers] = validated.kilometers
                it[days] = validated.days
                it[rateSnapshot] = validated.rateSnapshot
                it[amount] = validated.amountCents.centsToAmount()
                it[dayDate] = validated.dayDate
                it[passengerCount] = validated.passengerCount
                it[breakfastIncluded] = validated.breakfastIncluded
            }
            TravelExpenseDaySync.recomputeReport(reportId = reportId, rates = rates, strict = false)
            logger.info { "travel expense line $lineUuid updated on report $reportId by ${current.memberId}" }
            loadDto(reportId)
        }
    }

    override suspend fun saveDays(
        reportId: String,
        days: List<TravelExpenseDayInput>,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val report = requireEditableRow(id = id, current = current)
            TravelExpenseDaySync.saveDays(
                reportId = id,
                travelFrom = report[TravelExpenseReportTable.travelFrom],
                travelTo = report[TravelExpenseReportTable.travelTo],
                inputs = days,
                now = now,
            )
            TravelExpenseDaySync.recomputeReport(reportId = id, rates = TravelExpenseSettingsStore.load().rateSet, strict = false)
            logger.info { "travel expense days saved on report $id by ${current.memberId} (count=${days.size})" }
            loadDto(id)
        }
    }

    override suspend fun removeLine(lineId: String): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val lineUuid = lineId.toTravelExpenseUuid("lineId")
        return transaction {
            val lineRow =
                TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq lineUuid }.singleOrNull()
                    ?: throw NotFoundException("TravelExpenseLine $lineId not found")
            val reportId = lineRow[TravelExpenseLineTable.reportId]
            requireEditableRow(id = reportId, current = current)
            if (lineRow[TravelExpenseLineTable.kind] == TravelExpenseLineKind.DAY_ALLOWANCE) {
                throw BadRequestException("day lines cannot be removed individually -- change the travel period instead")
            }

            val receiptRows =
                TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq lineUuid }.toList()
            receiptRows.forEach { receiptRow -> deleteReceiptFile(receiptRow[TravelExpenseReceiptTable.storageKey]) }
            TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.lineId eq lineUuid }
            TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.id eq lineUuid }
            // Removing an overnight stay can lift the breakfast deduction on a day: recompute, not just the total.
            TravelExpenseDaySync.recomputeReport(reportId = reportId, rates = TravelExpenseSettingsStore.load().rateSet, strict = false)
            logger.info { "travel expense line $lineUuid removed from report $reportId by ${current.memberId}" }
            loadDto(reportId)
        }
    }

    override suspend fun submitReport(reportId: String): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val report =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("TravelExpenseReport $reportId not found")
            requireSelfServiceCaller(report = report, current = current)
            val status = report[TravelExpenseReportTable.status]
            if (status != TravelExpenseReportStatus.DRAFT) {
                throw ConflictException("TravelExpenseReport $reportId is $status, cannot be submitted (only DRAFT can)")
            }
            val travelFrom = report[TravelExpenseReportTable.travelFrom]
            val travelTo = report[TravelExpenseReportTable.travelTo]
            if (travelTo > OrganizationTimeZone.dateOf(now)) throw BadRequestException("travelTo must not be in the future")
            if (travelFrom < OrganizationTimeZone.dateOf(now).minus(TravelExpenseAmountRules.MAX_TRAVEL_BACKDATE_DAYS, DateTimeUnit.DAY)) {
                throw BadRequestException(
                    "travelFrom must not be more than ${TravelExpenseAmountRules.MAX_TRAVEL_BACKDATE_DAYS} days in the past",
                )
            }

            val lineRows = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq id }.toList()
            if (lineRows.isEmpty()) throw BadRequestException("At least one line is required to submit a report")
            if (report[TravelExpenseReportTable.payoutChoice] == null) throw BadRequestException("payout_choice_required")

            val settings = TravelExpenseSettingsStore.load()
            val rates = settings.rateSet
            val travelSpanDays = travelSpanDaysOf(report)
            val lineIds = lineRows.map { it[TravelExpenseLineTable.id] }
            val receiptCounts =
                TravelExpenseReceiptTable
                    .selectAll()
                    .where { TravelExpenseReceiptTable.lineId inList lineIds }
                    .toList()
                    .groupingBy { it[TravelExpenseReceiptTable.lineId] }
                    .eachCount()

            for (lineRow in lineRows) {
                val kind = lineRow[TravelExpenseLineTable.kind]
                val lineId = lineRow[TravelExpenseLineTable.id]
                if (kind.requiresReceipt && (receiptCounts[lineId] ?: 0) == 0) {
                    throw BadRequestException("Line $lineId ($kind) has no receipt attached")
                }
                when (kind) {
                    TravelExpenseLineKind.MILEAGE ->
                        if (rates.mileageCentsPerKm == null) throw BadRequestException("travel_mileage_rate_per_km is not configured")
                    TravelExpenseLineKind.PER_DIEM -> {
                        // Pre-V1.9.122 lines only: flat per-day rate, BigDecimal path exactly as before.
                        val rate = settings.perDiemRate ?: throw BadRequestException("travel_per_diem_rate is not configured")
                        val days =
                            requireNotNull(lineRow[TravelExpenseLineTable.days]) {
                                "PER_DIEM line $lineId has no days -- chk_tel_kind_shape violated?"
                            }
                        if (days > travelSpanDays) {
                            throw BadRequestException("Line $lineId has $days days, but the travel period is only $travelSpanDays day(s)")
                        }
                        val amount = rate.multiply(BigDecimal(days)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                        requireLineAmountInRange(amount)
                        TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineId }) {
                            it[rateSnapshot] = rate
                            it[TravelExpenseLineTable.amount] = amount
                        }
                    }
                    else -> Unit
                }
            }

            // The ONE calculation path, strict: day lines and mileage are recomputed with the rates that are
            // frozen below; an incomplete day or an unconfigured rate is a BadRequest, never a silent 0.
            val total = TravelExpenseDaySync.recomputeReport(reportId = id, rates = rates, strict = true)
            TravelExpenseLineTable
                .selectAll()
                .where { (TravelExpenseLineTable.reportId eq id) and (TravelExpenseLineTable.kind neq TravelExpenseLineKind.DAY_ALLOWANCE) }
                .forEach { row ->
                    // Review MAJOR fix (kept): the recomputed amount must still be in range at freeze time.
                    requireLineAmountInRange(row[TravelExpenseLineTable.amount])
                }
            if (total <= BigDecimal.ZERO) throw BadRequestException("The total amount must be greater than 0")
            val advance = report[TravelExpenseReportTable.advanceAmount]
            if (advance > total) throw BadRequestException("advance_exceeds_total")

            val membershipNumber =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq report[TravelExpenseReportTable.subjectMemberId] }
                    .singleOrNull()
                    ?.get(MemberTable.memberNumber)
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.REQUESTED
                it[submittedAt] = now
                it[ratesSnapshot] = travelExpenseSnapshotJson.encodeToString(TravelExpenseRateSet.serializer(), rates)
                it[membershipNumberSnapshot] = membershipNumber
            }
            recordTransition(row = report, actor = current, newStatus = TravelExpenseReportStatus.REQUESTED)
            logger.info { "travel expense report $id submitted by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun listMyReports(): List<TravelExpenseReportDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            TravelExpenseReportTable
                .selectAll()
                .where {
                    (TravelExpenseReportTable.subjectMemberId eq current.memberId) or
                        (TravelExpenseReportTable.requestedBy eq current.memberId)
                }
                // Review MINOR fix: unlike listReports, this has no cursor param on the interface
                // (self-service "my reports", not a board queue) -- MAX_OPEN_DRAFTS_PER_MEMBER
                // only bounds OPEN reports, so a long-tenured member's EXECUTED/REJECTED/WITHDRAWN
                // history would otherwise grow the response unboundedly. Capped at the same
                // MAX_LIST_RESULTS every other listing in this service uses, newest-first so a
                // truncated response still shows the member's most relevant/recent reports.
                .orderBy(
                    TravelExpenseReportTable.createdAt to SortOrder.DESC,
                    TravelExpenseReportTable.id to SortOrder.DESC,
                ).limit(MAX_LIST_RESULTS)
                .toList()
                .toDtos()
        }
    }

    override suspend fun withdrawReport(reportId: String): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("TravelExpenseReport $reportId not found")
            requireSelfServiceCaller(report = row, current = current)
            val status = row[TravelExpenseReportTable.status]
            if (status !in TravelExpenseReportStatusSets.WITHDRAWABLE) {
                throw ConflictException("TravelExpenseReport $reportId is $status, cannot be withdrawn (only DRAFT/REQUESTED can)")
            }
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.WITHDRAWN
                it[decidedAt] = now
                it[decidedBy] = current.memberId
            }
            // Security-Audit fix (2026-09-12, MAJOR "orphaned/unbounded receipt storage") -- see
            // deleteUploadedFilesForReport KDoc.
            deleteUploadedFilesForReport(id)
            recordTransition(row = row, actor = current, newStatus = TravelExpenseReportStatus.WITHDRAWN)
            logger.info { "travel expense report $id withdrawn by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun listReports(
        status: TravelExpenseReportStatus?,
        afterSubmittedAt: LocalDateTime?,
        afterId: String?,
    ): List<TravelExpenseReportDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        if (status == TravelExpenseReportStatus.DRAFT) {
            throw BadRequestException("status=DRAFT reports are never listed here -- a draft is private")
        }
        val cursorId = afterId?.toTravelExpenseUuid("afterId")
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(TravelExpenseReportTable.submittedAt.isNotNull())
            if (status != null) conditions += (TravelExpenseReportTable.status eq status)
            if (afterSubmittedAt != null && cursorId != null) {
                conditions +=
                    (TravelExpenseReportTable.submittedAt greater afterSubmittedAt) or
                    (
                        (TravelExpenseReportTable.submittedAt eq afterSubmittedAt) and
                            (TravelExpenseReportTable.id greater cursorId)
                    )
            }
            TravelExpenseReportTable
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .orderBy(
                    TravelExpenseReportTable.submittedAt to SortOrder.ASC,
                    TravelExpenseReportTable.id to SortOrder.ASC,
                ).limit(MAX_LIST_RESULTS)
                .toList()
                .toDtos()
        }
    }

    override suspend fun decideReport(
        reportId: String,
        approve: Boolean,
        note: String?,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        val id = reportId.toTravelExpenseUuid("reportId")
        val trimmedNote = note?.trim()?.ifBlank { null }
        if (trimmedNote == null) {
            throw BadRequestException("A decision note is required to approve or reject a travel expense report")
        }
        if (trimmedNote.length > TravelExpenseAmountRules.MAX_DECISION_NOTE_LENGTH) {
            throw BadRequestException("decision note must be at most ${TravelExpenseAmountRules.MAX_DECISION_NOTE_LENGTH} characters")
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("TravelExpenseReport $reportId not found")
            val status = row[TravelExpenseReportTable.status]
            val allowedFromStatuses =
                if (approve) setOf(TravelExpenseReportStatus.REQUESTED) else TravelExpenseReportStatusSets.REJECTABLE
            if (status !in allowedFromStatuses) {
                val verb = if (approve) "approved" else "rejected"
                throw ConflictException(
                    "TravelExpenseReport $reportId is $status, cannot be $verb (only " +
                        "${allowedFromStatuses.joinToString(" or ")} can)",
                )
            }
            val subjectId = row[TravelExpenseReportTable.subjectMemberId]
            val requestedById = row[TravelExpenseReportTable.requestedBy]
            // Vier-Augen lückenlos ueber BEIDE Wege -- siehe Klassen-KDoc.
            if (current.memberId == subjectId || current.memberId == requestedById) throw ForbiddenException()

            if (!approve) {
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                    it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.REJECTED
                    it[decidedAt] = now
                    it[decidedBy] = current.memberId
                    it[decisionNote] = trimmedNote
                    it[executionError] = null
                }
                // Security-Audit fix (2026-09-12, MAJOR "orphaned/unbounded receipt storage") -- see
                // deleteUploadedFilesForReport KDoc.
                deleteUploadedFilesForReport(id)
                recordTransition(row = row, actor = current, newStatus = TravelExpenseReportStatus.REJECTED)
                return@transaction loadDto(id)
            }

            when (val outcome = TravelExpenseExecution.execute(report = row, actor = current, now = now)) {
                is TravelExpensePostingOutcome.Posted -> {
                    TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                        it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.EXECUTED
                        it[decidedAt] = now
                        it[decidedBy] = current.memberId
                        it[decisionNote] = trimmedNote
                        it[executedAt] = now
                        it[executionError] = null
                        it[postedJournalEntryId] = outcome.journalEntryId
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = TravelExpenseReportStatus.EXECUTED,
                        newPostedJournalEntryId = outcome.journalEntryId,
                    )
                }
                is TravelExpensePostingOutcome.Failed -> {
                    TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                        it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.APPROVED
                        it[decidedAt] = now
                        it[decidedBy] = current.memberId
                        it[decisionNote] = trimmedNote
                        it[executionError] = outcome.reason
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = TravelExpenseReportStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id)
        }
    }

    override suspend fun retryPosting(reportId: String): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("TravelExpenseReport $reportId not found")
            val status = row[TravelExpenseReportTable.status]
            if (status != TravelExpenseReportStatus.APPROVED ||
                row[TravelExpenseReportTable.executionError] == null ||
                row[TravelExpenseReportTable.postedJournalEntryId] != null
            ) {
                throw ConflictException(
                    "TravelExpenseReport $reportId is $status, cannot be retried (only APPROVED with a failed booking can)",
                )
            }
            // Security-Audit fix (2026-09-12, MINOR "Vier-Augen-Prinzip greift bei retryPosting
            // nicht"): this is the SAME booking-producing action `decideReport(true)` performs --
            // the class KDoc's "Vier-Augen lückenlos ... ohne Ausnahme, in BEIDEN Fällen" applies
            // here just as much, but until this fix only `decideReport` enforced it. Without this
            // check, a BOARD member who is themselves the subject of their own APPROVED-but-failed
            // report could call retryPosting once the underlying cause is fixed, posting the
            // reimbursement journal_entry with `created_by`/the JOURNAL_ENTRY audit row attributed
            // to themselves -- the approval itself still came from a different, independent BOARD
            // member via decideReport, so this does not let anyone manipulate the amount or
            // self-approve, but it lets the beneficiary appear as the creator of the very booking
            // that pays them, defeating the four-eyes guarantee's audit-trail purpose.
            val subjectId = row[TravelExpenseReportTable.subjectMemberId]
            val requestedById = row[TravelExpenseReportTable.requestedBy]
            if (current.memberId == subjectId || current.memberId == requestedById) throw ForbiddenException()
            when (val outcome = TravelExpenseExecution.execute(report = row, actor = current, now = now)) {
                is TravelExpensePostingOutcome.Posted -> {
                    TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                        it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.EXECUTED
                        it[executedAt] = now
                        it[executionError] = null
                        it[postedJournalEntryId] = outcome.journalEntryId
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = TravelExpenseReportStatus.EXECUTED,
                        newPostedJournalEntryId = outcome.journalEntryId,
                    )
                }
                is TravelExpensePostingOutcome.Failed -> {
                    TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                        it[executionError] = outcome.reason
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = TravelExpenseReportStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id)
        }
    }

    // ── V1.9.123 "SEPA-Ueberweisungsdatei" ───────────────────────────────────────────────────

    override suspend fun setRefundAccount(
        reportId: String,
        iban: String?,
        accountHolder: String?,
    ): TravelExpenseReportDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val now = DbClock.nowLocalDateTime()
        val holderInput = TravelExpenseRefundAccount.normalizeHolder(accountHolder)
        val normalizedIban =
            iban?.let {
                val normalized = IbanValidator.normalize(it)
                // Same checks as pain.001 will apply later: mod 97, country length and the SEPA area. The value is never echoed back.
                if (!IbanValidator.isValid(normalized) || normalized.take(2) !in IbanValidator.SEPA_COUNTRIES) {
                    throw BadRequestException("refund_iban_invalid")
                }
                normalized
            }
        if (normalizedIban != null && secretEncryptionKey == null) throw ConflictException("encryption_key_missing")
        return transaction {
            val row =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("TravelExpenseReport $reportId not found")
            val status = row[TravelExpenseReportTable.status]
            // Only the person the money goes to may ever name the account: not the requester (who may file on someone's behalf), not the
            // board. Otherwise a requester could enter an own IBAN in the draft phase without leaving any trace.
            if (current.memberId != row[TravelExpenseReportTable.subjectMemberId]) throw ForbiddenException()
            // Already refunded by hand before the SEPA export existed: an account added now could only cause a second payment.
            if (row[TravelExpenseReportTable.paidOutsideLapis]) throw ConflictException("paid_outside_lapis")
            if (status == TravelExpenseReportStatus.REJECTED || status == TravelExpenseReportStatus.WITHDRAWN) {
                throw ConflictException("TravelExpenseReport $reportId is $status, the refund account can no longer be changed")
            }
            val isDraft = status == TravelExpenseReportStatus.DRAFT
            // After the SEPA export the subject may still correct the account (bank return AC01/AC04/MD07 ...): the export mark stays,
            // the change is flagged and audited, and only a reasoned re-export can use the new account.
            val wasExported = row[TravelExpenseReportTable.sepaExportedAt] != null

            if (normalizedIban == null && holderInput != null) {
                // Holder-only update: the stored IBAN stays untouched.
                if (row[TravelExpenseReportTable.refundIbanCiphertext] == null) throw BadRequestException("refund_account_missing")
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                    it[refundAccountHolder] = holderInput
                    if (!isDraft) it[refundAccountChangedAfterSubmit] = true
                }
                auditRefundAccountChange(row = row, current = current, status = status, isDraft = isDraft, wasExported = wasExported)
                return@transaction loadDto(id)
            }
            if (normalizedIban == null) {
                if (!isDraft) throw ConflictException("refund_account_cannot_be_cleared_after_submit")
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                    it[refundIbanCiphertext] = null
                    it[refundIbanLast4] = null
                    it[refundAccountHolder] = null
                }
                return@transaction loadDto(id)
            }
            val holder =
                holderInput
                    ?: displayNamesOf(listOf(row[TravelExpenseReportTable.subjectMemberId]))[row[TravelExpenseReportTable.subjectMemberId]]
                        ?.trim()
                        ?.take(TravelExpenseRefundAccount.MAX_HOLDER_LENGTH)
                        ?.ifBlank { null }
                    ?: throw BadRequestException("refund_holder_required")
            val sealed =
                TravelExpenseRefundAccount.seal(
                    box = SecretBox(requireNotNull(secretEncryptionKey)),
                    reportId = id,
                    normalizedIban = normalizedIban,
                )
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[refundIbanCiphertext] = sealed
                it[refundIbanLast4] = IbanValidator.last4(normalizedIban)
                it[refundAccountHolder] = holder
                if (!isDraft) it[refundAccountChangedAfterSubmit] = true
            }
            auditRefundAccountChange(row = row, current = current, status = status, isDraft = isDraft, wasExported = wasExported)
            logger.info { "travel expense refund account set: reportId=$id draft=$isDraft" }
            loadDto(id)
        }
    }

    /**
     * One audit entry for the first change after submission, and one for every change after an export; numbers and flags only, never
     * the IBAN or the holder.
     */
    private fun auditRefundAccountChange(
        row: ResultRow,
        current: CurrentMember,
        status: TravelExpenseReportStatus,
        isDraft: Boolean,
        wasExported: Boolean,
    ) {
        if (isDraft) return
        if (row[TravelExpenseReportTable.refundAccountChangedAfterSubmit] && !wasExported) return
        recordTransition(
            row = row,
            actor = current,
            newStatus = status,
            newPostedJournalEntryId = row[TravelExpenseReportTable.postedJournalEntryId],
            newRefundAccountChanged = true,
        )
    }

    override suspend fun getRefundAccountPrefill(reportId: String): RefundAccountPrefillDto {
        val current = resolveCurrentMember(call)
        val id = reportId.toTravelExpenseUuid("reportId")
        val key = secretEncryptionKey ?: return RefundAccountPrefillDto()
        return transaction {
            val row =
                TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq id }.singleOrNull()
                    ?: return@transaction RefundAccountPrefillDto()
            // Only the person the money goes to ever sees a plaintext IBAN -- a requester or board member gets an empty prefill.
            if (row[TravelExpenseReportTable.subjectMemberId] != current.memberId) return@transaction RefundAccountPrefillDto()
            val previous =
                TravelExpenseReportTable
                    .selectAll()
                    .where {
                        (TravelExpenseReportTable.subjectMemberId eq current.memberId) and
                            (TravelExpenseReportTable.id neq id) and
                            TravelExpenseReportTable.refundIbanCiphertext.isNotNull()
                    }.orderBy(TravelExpenseReportTable.createdAt to SortOrder.DESC, TravelExpenseReportTable.id to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction RefundAccountPrefillDto()
            val plain =
                runCatching {
                    TravelExpenseRefundAccount.open(
                        box = SecretBox(key),
                        reportId = previous[TravelExpenseReportTable.id],
                        ciphertext = requireNotNull(previous[TravelExpenseReportTable.refundIbanCiphertext]),
                    )
                }.getOrNull()
                    ?: return@transaction RefundAccountPrefillDto()
            RefundAccountPrefillDto(iban = plain, accountHolder = previous[TravelExpenseReportTable.refundAccountHolder])
        }
    }

    override suspend fun listSepaExportCandidates(): List<TravelExpenseReportDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        return transaction {
            val open =
                TravelExpenseReportTable
                    .selectAll()
                    .where {
                        (TravelExpenseReportTable.status eq TravelExpenseReportStatus.EXECUTED) and
                            (TravelExpenseReportTable.payoutChoice eq TravelExpensePayoutChoice.PAYOUT) and
                            TravelExpenseReportTable.sepaExportedAt.isNull() and
                            (TravelExpenseReportTable.paidOutsideLapis eq false)
                    }.orderBy(TravelExpenseReportTable.executedAt to SortOrder.ASC, TravelExpenseReportTable.id to SortOrder.ASC)
                    .limit(SEPA_CANDIDATE_LIMIT)
                    .toList()
            // Already-exported reports stay visible (with their export mark and the re-export action), newest first, bounded.
            val exportedRoom = minOf(SEPA_EXPORTED_HISTORY_LIMIT, SEPA_CANDIDATE_LIMIT - open.size)
            val exported =
                if (exportedRoom <= 0) {
                    emptyList()
                } else {
                    TravelExpenseReportTable
                        .selectAll()
                        .where {
                            (TravelExpenseReportTable.status eq TravelExpenseReportStatus.EXECUTED) and
                                (TravelExpenseReportTable.payoutChoice eq TravelExpensePayoutChoice.PAYOUT) and
                                TravelExpenseReportTable.sepaExportedAt.isNotNull()
                        }.orderBy(TravelExpenseReportTable.sepaExportedAt to SortOrder.DESC, TravelExpenseReportTable.id to SortOrder.DESC)
                        .limit(exportedRoom)
                        .toList()
                }
            val rows = open + exported
            val blockers = sepaExport.blockersFor(rows = rows, actor = current)
            rows.toDtos().map { dto -> dto.copy(sepaBlocker = blockers[Uuid.parse(dto.id)]) }
        }
    }

    override suspend fun listSepaBatches(limit: Int): List<TravelExpenseSepaBatchDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        val capped = limit.coerceIn(1, MAX_LIST_RESULTS)
        return transaction {
            val batches =
                TravelExpenseSepaBatchTable
                    .selectAll()
                    .orderBy(TravelExpenseSepaBatchTable.createdAt to SortOrder.DESC, TravelExpenseSepaBatchTable.id to SortOrder.DESC)
                    .limit(capped)
                    .toList()
            if (batches.isEmpty()) return@transaction emptyList()
            val names = displayNamesOf(batches.map { it[TravelExpenseSepaBatchTable.createdBy] })
            val items =
                TravelExpenseSepaBatchItemTable
                    .selectAll()
                    .where { TravelExpenseSepaBatchItemTable.batchId inList batches.map { it[TravelExpenseSepaBatchTable.id] } }
                    .toList()
                    .groupBy({ it[TravelExpenseSepaBatchItemTable.batchId] }, { it[TravelExpenseSepaBatchItemTable.reportId].toString() })
            batches.map { b ->
                TravelExpenseSepaBatchDto(
                    id = b[TravelExpenseSepaBatchTable.id].toString(),
                    debtorAccountLabel = b[TravelExpenseSepaBatchTable.debtorAccountLabel],
                    debtorIbanLast4 = b[TravelExpenseSepaBatchTable.debtorIbanLast4],
                    requestedExecutionDate = b[TravelExpenseSepaBatchTable.requestedExecutionDate],
                    transactionCount = b[TravelExpenseSepaBatchTable.transactionCount],
                    controlSumCents = b[TravelExpenseSepaBatchTable.controlSumCents],
                    createdAt = b[TravelExpenseSepaBatchTable.createdAt],
                    createdByDisplayName = names[b[TravelExpenseSepaBatchTable.createdBy]] ?: "",
                    isReexport = b[TravelExpenseSepaBatchTable.isReexport],
                    reportIds = items[b[TravelExpenseSepaBatchTable.id]].orEmpty(),
                )
            }
        }
    }

    override suspend fun getSepaExportContext(): TravelExpenseSepaContextDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TRAVEL_EXPENSE_DECISION_ROLES)
        return transaction {
            val accounts =
                BankAccountTable
                    .selectAll()
                    .orderBy(BankAccountTable.isDefault to SortOrder.DESC, BankAccountTable.label to SortOrder.ASC)
                    .map {
                        SepaDebtorAccountOptionDto(
                            id = it[BankAccountTable.id].toString(),
                            label = it[BankAccountTable.label],
                            bankName = it[BankAccountTable.bankName],
                            ibanLast4 = IbanValidator.last4(it[BankAccountTable.iban]),
                            isDefault = it[BankAccountTable.isDefault],
                        )
                    }
            TravelExpenseSepaContextDto(
                bankAccounts = accounts,
                defaultExecutionDate = TravelExpenseSepaExport.defaultExecutionDate(),
                missingSetup = sepaExport.missingSetup(hasBankAccount = accounts.isNotEmpty()),
                maxTransactions = SepaPain001Writer.MAX_TRANSACTIONS,
            )
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────────

    private fun requireValidReportHeader(
        input: TravelExpenseReportInput,
        now: LocalDateTime,
    ) {
        val purpose = input.purpose.trim()
        if (purpose.isEmpty()) throw BadRequestException("purpose must not be blank")
        if (purpose.length > TravelExpenseAmountRules.MAX_PURPOSE_LENGTH) {
            throw BadRequestException("purpose must be at most ${TravelExpenseAmountRules.MAX_PURPOSE_LENGTH} characters")
        }
        if (input.travelTo < input.travelFrom) throw BadRequestException("travelTo must not be before travelFrom")
        if (input.travelFrom.daysUntil(input.travelTo) + 1 > TravelExpenseAmountRules.MAX_TRAVEL_SPAN_DAYS) {
            throw BadRequestException("The travel period must not exceed ${TravelExpenseAmountRules.MAX_TRAVEL_SPAN_DAYS} days")
        }
        input.advanceCents?.let {
            if (it < 0 || it > TravelExpenseAmountRules.MAX_ADVANCE_CENTS) {
                throw BadRequestException("advanceCents must be between 0 and ${TravelExpenseAmountRules.MAX_ADVANCE_CENTS}")
            }
        }
    }

    /** `addLine` refuses day lines (saveDays) and the two legacy kinds. */
    private fun requireAddableKind(kind: TravelExpenseLineKind) {
        if (kind == TravelExpenseLineKind.DAY_ALLOWANCE) {
            throw BadRequestException("day lines are maintained via saveDays, not addLine")
        }
        if (kind == TravelExpenseLineKind.PER_DIEM || kind == TravelExpenseLineKind.RECEIPTED) {
            throw BadRequestException("legacy_kind")
        }
    }

    private data class ValidatedLine(
        val description: String,
        val kilometers: BigDecimal?,
        val days: Int?,
        val rateSnapshot: BigDecimal?,
        val amountCents: Long,
        val dayDate: LocalDate?,
        val passengerCount: Int?,
        val breakfastIncluded: Boolean?,
    )

    /**
     * Exclusivity (mirrors `chk_tel_kind_shape`) + value rules for one NON-day line, used by both
     * `addLine` and `updateLine`. Stray fields of another kind are a [BadRequestException], never
     * silently dropped (mass-assignment guard). `RECEIPTED` is accepted here only for `updateLine`
     * of a pre-V1.9.122 line.
     */
    private fun validateLine(
        input: TravelExpenseLineInput,
        rates: TravelExpenseRateSet,
        report: ResultRow,
    ): ValidatedLine {
        val description = TravelExpenseLineRules.requireDescription(input)
        return when (input.kind) {
            TravelExpenseLineKind.MILEAGE -> {
                val kmInput = input.kilometers ?: throw BadRequestException("kilometers is required for kind=MILEAGE")
                if (input.days != null || input.nights != null || input.dayDate != null || input.breakfastIncluded != null) {
                    throw BadRequestException("days/nights/dayDate/breakfastIncluded must not be set for kind=MILEAGE")
                }
                if (input.amount != null || input.amountCents != null) {
                    throw BadRequestException("amount must not be set for kind=MILEAGE (server-computed)")
                }
                val km = TravelExpenseLineRules.wholeKilometers(kmInput)
                val passengers = TravelExpenseLineRules.requirePassengerCount(input.passengerCount)
                val rate = rates.mileageCentsPerKm ?: throw BadRequestException("travel_mileage_rate_per_km is not configured")
                val cents = TravelExpenseCalculator.mileage(km = km, passengers = passengers, rates = rates).orBadRequest()
                ValidatedLine(
                    description = description,
                    kilometers = BigDecimal.valueOf(km).setScale(2),
                    days = null,
                    rateSnapshot = rate.centsToRate(),
                    amountCents = cents,
                    dayDate = null,
                    passengerCount = passengers,
                    breakfastIncluded = null,
                )
            }
            TravelExpenseLineKind.PUBLIC_TRANSPORT,
            TravelExpenseLineKind.HOSPITALITY,
            TravelExpenseLineKind.OTHER,
            TravelExpenseLineKind.RECEIPTED,
            -> {
                if (input.kilometers != null ||
                    input.days != null ||
                    input.nights != null ||
                    input.dayDate != null ||
                    input.passengerCount != null ||
                    input.breakfastIncluded != null
                ) {
                    throw BadRequestException(
                        "kilometers/days/nights/dayDate/passengerCount/breakfastIncluded must not be set for kind=${input.kind}",
                    )
                }
                val cents = TravelExpenseCalculator.receipted(TravelExpenseLineRules.receiptedCents(input)).orBadRequest()
                requireLineAmountInRange(cents.centsToAmount())
                ValidatedLine(
                    description = description,
                    kilometers = null,
                    days = null,
                    rateSnapshot = null,
                    amountCents = cents,
                    dayDate = null,
                    passengerCount = null,
                    breakfastIncluded = null,
                )
            }
            TravelExpenseLineKind.ACCOMMODATION -> {
                if (input.kilometers != null || input.days != null || input.passengerCount != null) {
                    throw BadRequestException("kilometers/days/passengerCount must not be set for kind=ACCOMMODATION")
                }
                val firstNight = input.dayDate ?: throw BadRequestException("dayDate (first night) is required for kind=ACCOMMODATION")
                val nights = input.nights ?: throw BadRequestException("nights is required for kind=ACCOMMODATION")
                TravelExpenseLineRules.requireAccommodationInRange(
                    firstNight = firstNight,
                    nights = nights,
                    travelFrom = report[TravelExpenseReportTable.travelFrom],
                    travelTo = report[TravelExpenseReportTable.travelTo],
                )
                val cents = TravelExpenseCalculator.receipted(TravelExpenseLineRules.receiptedCents(input)).orBadRequest()
                requireLineAmountInRange(cents.centsToAmount())
                ValidatedLine(
                    description = description,
                    kilometers = null,
                    days = nights,
                    rateSnapshot = null,
                    amountCents = cents,
                    dayDate = firstNight,
                    passengerCount = null,
                    breakfastIncluded =
                        input.breakfastIncluded ?: false,
                )
            }
            TravelExpenseLineKind.PER_DIEM, TravelExpenseLineKind.DAY_ALLOWANCE -> throw BadRequestException("legacy_kind")
        }
    }

    /**
     * Review MAJOR fix: now also enforces the LOWER bound (`amount > 0`), not just the upper one
     * -- [ITravelExpenseService.submitReport]'s own KDoc claims "every line's amount > 0" is
     * validated at submit, but until this fix no code path actually did that for the
     * rate-recomputed MILEAGE/PER_DIEM branches (only the `chk_tel_amount_positive` DB constraint
     * did, surfacing as a raw `ExposedSQLException`/500 instead of a [BadRequestException]). A
     * positive rate/kilometers/days already make this branch unreachable today, but the check
     * belongs here explicitly rather than resting on that transitive invariant alone.
     */
    private fun requireLineAmountInRange(amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) throw BadRequestException("amount must be positive")
        if (amount > BigDecimal(TravelExpenseAmountRules.MAX_LINE_AMOUNT)) {
            throw BadRequestException("amount must be at most ${TravelExpenseAmountRules.MAX_LINE_AMOUNT}")
        }
    }

    private fun travelSpanDaysOf(report: ResultRow): Int {
        val from = report[TravelExpenseReportTable.travelFrom]
        val to = report[TravelExpenseReportTable.travelTo]
        return from.daysUntil(to) + 1
    }

    // ── Access-control helpers ─────────────────────────────────────────────────────────────

    /** DRAFT only, subject or requester -- no BOARD/ADMIN bypass, see class KDoc state table. */
    private fun requireEditableRow(
        id: Uuid,
        current: CurrentMember,
    ): ResultRow {
        val row =
            TravelExpenseReportTable
                .selectAll()
                .where { TravelExpenseReportTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("TravelExpenseReport $id not found")
        requireSelfServiceCaller(report = row, current = current)
        val status = row[TravelExpenseReportTable.status]
        if (status !in TravelExpenseReportStatusSets.EDITABLE) {
            throw ConflictException("TravelExpenseReport $id is $status, cannot be edited (only DRAFT can)")
        }
        return row
    }

    private fun requireSelfServiceCaller(
        report: ResultRow,
        current: CurrentMember,
    ) {
        val subjectId = report[TravelExpenseReportTable.subjectMemberId]
        val requestedById = report[TravelExpenseReportTable.requestedBy]
        if (current.memberId != subjectId && current.memberId != requestedById) throw ForbiddenException()
    }

    // ── Read / mapping helpers ─────────────────────────────────────────────────────────────

    private fun loadDto(id: Uuid): TravelExpenseReportDto =
        TravelExpenseReportTable
            .selectAll()
            .where { TravelExpenseReportTable.id eq id }
            .single()
            .toDto()

    private fun ResultRow.toDto(
        namesOverride: Map<Uuid, String>? = null,
        linesOverride: Map<Uuid, List<TravelExpenseLineDto>>? = null,
        attachmentsOverride: Map<Uuid, List<TravelExpenseAttachmentDto>>? = null,
    ): TravelExpenseReportDto {
        val id = this[TravelExpenseReportTable.id]
        val subjectId = this[TravelExpenseReportTable.subjectMemberId]
        val requestedById = this[TravelExpenseReportTable.requestedBy]
        val decidedById = this[TravelExpenseReportTable.decidedBy]
        val exportedById = this[TravelExpenseReportTable.sepaExportedBy]
        val names = namesOverride ?: displayNamesOf(listOfNotNull(subjectId, requestedById, decidedById, exportedById))
        val lines = linesOverride?.get(id) ?: linesOf(listOf(id))[id].orEmpty()
        val attachments = attachmentsOverride?.get(id) ?: attachmentsOf(listOf(id))[id].orEmpty()
        val advance = this[TravelExpenseReportTable.advanceAmount]
        val totals =
            TravelExpenseCalculator.totals(
                lineNetCents = lines.map { it.amountCents },
                deductionCents = lines.map { it.deductionCents },
                advanceCents = advance.toCents(),
            )
        return TravelExpenseReportDto(
            id = id.toString(),
            subjectMemberId = subjectId.toString(),
            subjectDisplayName = names[subjectId] ?: "",
            status = this[TravelExpenseReportTable.status],
            purpose = this[TravelExpenseReportTable.purpose],
            travelFrom = this[TravelExpenseReportTable.travelFrom],
            travelTo = this[TravelExpenseReportTable.travelTo],
            totalAmount = this[TravelExpenseReportTable.totalAmount],
            lines = lines,
            createdAt = this[TravelExpenseReportTable.createdAt],
            submittedAt = this[TravelExpenseReportTable.submittedAt],
            requestedBy = requestedById.toString(),
            requestedByDisplayName = names[requestedById] ?: "",
            decidedBy = decidedById?.toString(),
            decidedByDisplayName = decidedById?.let { names[it] },
            decidedAt = this[TravelExpenseReportTable.decidedAt],
            decisionNote = this[TravelExpenseReportTable.decisionNote],
            executedAt = this[TravelExpenseReportTable.executedAt],
            postedJournalEntryId = this[TravelExpenseReportTable.postedJournalEntryId]?.toString(),
            executionError = this[TravelExpenseReportTable.executionError],
            advanceCents = advance.toCents(),
            payoutChoice = this[TravelExpenseReportTable.payoutChoice],
            membershipNumber =
                if (this[TravelExpenseReportTable.status] == TravelExpenseReportStatus.DRAFT) {
                    // A draft shows the LIVE number; everything else the snapshot taken at submit.
                    memberNumberOf(subjectId)
                } else {
                    this[TravelExpenseReportTable.membershipNumberSnapshot]
                },
            grossCents = totals.grossCents,
            deductionCents = totals.deductionCents,
            payoutCents = totals.payoutCents,
            ratesSnapshot =
                this[TravelExpenseReportTable.ratesSnapshot]?.let {
                    runCatching { travelExpenseSnapshotJson.decodeFromString(TravelExpenseRateSet.serializer(), it) }.getOrNull()
                },
            submissionDeadline = TravelExpenseDeadline.deadlineFor(this[TravelExpenseReportTable.travelTo]),
            refundIbanLast4 = this[TravelExpenseReportTable.refundIbanLast4],
            refundAccountHolder = this[TravelExpenseReportTable.refundAccountHolder],
            refundAccountChangedAfterSubmit = this[TravelExpenseReportTable.refundAccountChangedAfterSubmit],
            paidOutsideLapis = this[TravelExpenseReportTable.paidOutsideLapis],
            sepaExportedAt = this[TravelExpenseReportTable.sepaExportedAt],
            sepaExportBatchId = this[TravelExpenseReportTable.sepaExportBatchId]?.toString(),
            sepaExportedByDisplayName = exportedById?.let { names[it] },
            sepaReexportCount = this[TravelExpenseReportTable.sepaReexportCount],
            sepaReference = TravelExpenseSepaReference.of(id.toString()),
            attachments = attachments,
        )
    }

    private fun memberNumberOf(memberId: Uuid): String? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.memberNumber)

    /**
     * Batch mapper for [listReports]/[listMyReports] -- resolves every row's `displayName`/line/
     * receipt lookups in exactly four queries total (member names, `travel_expense_line inList
     * reportIds`, `travel_expense_receipt inList lineIds`, `travel_expense_attachment inList
     * reportIds`), never a query per row.
     */
    private fun List<ResultRow>.toDtos(): List<TravelExpenseReportDto> {
        if (isEmpty()) return emptyList()
        val memberIds = mutableSetOf<Uuid>()
        forEach { row ->
            memberIds += row[TravelExpenseReportTable.subjectMemberId]
            memberIds += row[TravelExpenseReportTable.requestedBy]
            row[TravelExpenseReportTable.decidedBy]?.let { memberIds += it }
            row[TravelExpenseReportTable.sepaExportedBy]?.let { memberIds += it }
        }
        val names = displayNamesOf(memberIds)
        val reportIds = map { it[TravelExpenseReportTable.id] }
        val lines = linesOf(reportIds)
        val attachments = attachmentsOf(reportIds)
        return map { it.toDto(namesOverride = names, linesOverride = lines, attachmentsOverride = attachments) }
    }

    /** V1.9.198: one batch query for the report-level attachments of every report in [reportIds]. */
    private fun attachmentsOf(reportIds: Collection<Uuid>): Map<Uuid, List<TravelExpenseAttachmentDto>> {
        if (reportIds.isEmpty()) return emptyMap()
        return TravelExpenseAttachmentTable
            .selectAll()
            .where { TravelExpenseAttachmentTable.reportId inList reportIds }
            .orderBy(TravelExpenseAttachmentTable.uploadedAt to SortOrder.ASC)
            .groupBy({ it[TravelExpenseAttachmentTable.reportId] }) {
                TravelExpenseAttachmentDto(
                    id = it[TravelExpenseAttachmentTable.id].toString(),
                    reportId = it[TravelExpenseAttachmentTable.reportId].toString(),
                    originalFilename = it[TravelExpenseAttachmentTable.originalFilename],
                    mimeType = it[TravelExpenseAttachmentTable.mimeType],
                    sizeBytes = it[TravelExpenseAttachmentTable.sizeBytes],
                    sha256 = it[TravelExpenseAttachmentTable.sha256],
                    uploadedAt = it[TravelExpenseAttachmentTable.uploadedAt],
                )
            }
    }

    private fun linesOf(reportIds: Collection<Uuid>): Map<Uuid, List<TravelExpenseLineDto>> {
        if (reportIds.isEmpty()) return emptyMap()
        val lineRows =
            TravelExpenseLineTable
                .selectAll()
                .where { TravelExpenseLineTable.reportId inList reportIds }
                .toList()
        if (lineRows.isEmpty()) return emptyMap()
        val lineIds = lineRows.map { it[TravelExpenseLineTable.id] }
        val receiptsByLine =
            TravelExpenseReceiptTable
                .selectAll()
                .where { TravelExpenseReceiptTable.lineId inList lineIds }
                .toList()
                .groupBy { it[TravelExpenseReceiptTable.lineId] }
        return lineRows
            .groupBy { it[TravelExpenseLineTable.reportId] }
            .mapValues { (_, rows) ->
                val forced = TravelExpenseDaySync.forcedBreakfastDatesOf(rows)
                rows
                    .sortedWith(
                        compareBy<ResultRow>(
                            { it[TravelExpenseLineTable.kind] != TravelExpenseLineKind.DAY_ALLOWANCE },
                            { it[TravelExpenseLineTable.dayDate] },
                            { it[TravelExpenseLineTable.createdAt] },
                        ),
                    ).map {
                        it.toLineDto(
                            receipts = receiptsByLine[it[TravelExpenseLineTable.id]].orEmpty(),
                            forcedBreakfastDates = forced,
                        )
                    }
            }
    }

    private fun ResultRow.toLineDto(
        receipts: List<ResultRow>,
        forcedBreakfastDates: Set<LocalDate>,
    ): TravelExpenseLineDto {
        val kind = this[TravelExpenseLineTable.kind]
        val dayDate = this[TravelExpenseLineTable.dayDate]
        val amount = this[TravelExpenseLineTable.amount]
        return TravelExpenseLineDto(
            id = this[TravelExpenseLineTable.id].toString(),
            reportId = this[TravelExpenseLineTable.reportId].toString(),
            kind = kind,
            description = this[TravelExpenseLineTable.description],
            kilometers = this[TravelExpenseLineTable.kilometers],
            days = this[TravelExpenseLineTable.days],
            rateSnapshot = this[TravelExpenseLineTable.rateSnapshot],
            amount = amount,
            receipts = receipts.map { it.toReceiptDto() },
            destination = this[TravelExpenseLineTable.destination],
            dayDate = dayDate,
            timeFrom = this[TravelExpenseLineTable.timeFrom]?.toKotlinLocalTime(),
            timeTo = this[TravelExpenseLineTable.timeTo]?.toKotlinLocalTime(),
            breakfastProvided = this[TravelExpenseLineTable.breakfastProvided],
            lunchProvided = this[TravelExpenseLineTable.lunchProvided],
            dinnerProvided = this[TravelExpenseLineTable.dinnerProvided],
            breakfastForcedByAccommodation =
                kind == TravelExpenseLineKind.DAY_ALLOWANCE && dayDate != null && dayDate in forcedBreakfastDates,
            passengerCount = this[TravelExpenseLineTable.passengerCount],
            breakfastIncluded = this[TravelExpenseLineTable.breakfastIncluded],
            nights = if (kind == TravelExpenseLineKind.ACCOMMODATION) this[TravelExpenseLineTable.days] else null,
            computedMinutes = this[TravelExpenseLineTable.computedMinutes],
            baseCents = this[TravelExpenseLineTable.baseAmount]?.toCents(),
            deductionCents = this[TravelExpenseLineTable.deductionAmount].toCents(),
            amountCents = amount.toCents(),
        )
    }

    private fun ResultRow.toReceiptDto(): TravelExpenseReceiptDto =
        TravelExpenseReceiptDto(
            id = this[TravelExpenseReceiptTable.id].toString(),
            lineId = this[TravelExpenseReceiptTable.lineId].toString(),
            originalFilename = this[TravelExpenseReceiptTable.originalFilename],
            mimeType = this[TravelExpenseReceiptTable.mimeType],
            sizeBytes = this[TravelExpenseReceiptTable.sizeBytes],
            sha256 = this[TravelExpenseReceiptTable.sha256],
            uploadedAt = this[TravelExpenseReceiptTable.uploadedAt],
        )

    private fun displayNamesOf(ids: Collection<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList ids.distinct() }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    private fun deleteReceiptFile(storageKey: String) {
        runCatching {
            val file = receiptStorageRoot.resolve(storageKey)
            if (file.exists()) file.delete()
        }.onFailure { e ->
            logger.warn(e) { "TravelExpenseService: failed to delete receipt file for storageKey=$storageKey (DB row is authoritative)" }
        }
    }

    /**
     * Security-Audit fix (2026-09-12, MAJOR "orphaned/unbounded receipt storage"): a report that
     * just transitioned to WITHDRAWN or REJECTED is TERMINAL
     * ([network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets.TERMINAL]) and, per
     * `chk_ter_posted_entry_state`, is GUARANTEED to have `posted_journal_entry_id == null` -- no
     * `journal_entry`, no Buchungsbeleg, was ever created for it, so its receipt files have no
     * retention basis from this moment on. Without this call, an ordinary MEMBER could grow disk
     * usage without bound: `submitReport` then immediately `withdrawReport` frees the
     * `MAX_OPEN_DRAFTS_PER_MEMBER` slot (WITHDRAWN is not in
     * [network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets.OPEN]) while the up to
     * `MAX_RECEIPTS_PER_REPORT * MAX_RECEIPT_BYTES` (500 MiB) of receipt bytes already written stay
     * on disk forever -- repeatable arbitrarily often, with no rate limit or storage quota
     * elsewhere in this domain to bound it. Deliberately deletes ONLY the file bytes, never the
     * `travel_expense_receipt` DB rows: the metadata (filename/checksum/size/uploadedAt) remains as
     * an audit trail of what was once attached, and the download route already handles a missing
     * file gracefully ("Stored file missing", 404) -- see
     * `network.lapis.cloud.server.routes.registerTravelExpenseReceiptRoutes`. A full purge of the
     * rows themselves (for an actual Art. 17 DSGVO erasure request) is
     * [network.lapis.cloud.server.dsgvo.TravelExpensePersonalData]'s job, not this one's.
     */
    private fun deleteUploadedFilesForReport(reportId: Uuid) {
        // V1.9.198: report-level attachments follow the same rule as receipts (files go, rows stay).
        TravelExpenseAttachmentTable
            .selectAll()
            .where { TravelExpenseAttachmentTable.reportId eq reportId }
            .forEach { deleteReceiptFile(it[TravelExpenseAttachmentTable.storageKey]) }
        val lineIds =
            TravelExpenseLineTable
                .selectAll()
                .where { TravelExpenseLineTable.reportId eq reportId }
                .map { it[TravelExpenseLineTable.id] }
        if (lineIds.isEmpty()) return
        TravelExpenseReceiptTable
            .selectAll()
            .where { TravelExpenseReceiptTable.lineId inList lineIds }
            .forEach { deleteReceiptFile(it[TravelExpenseReceiptTable.storageKey]) }
    }

    /**
     * @param newPostedJournalEntryId non-null only for the ONE transition into EXECUTED --
     *   defaults to `null` because every other transition leaves `posted_journal_entry_id` null.
     * @param newExecutionError defaults to `null` because every transition except "APPROVED with
     *   a failed under-lock recheck" clears it -- those two call sites pass `outcome.reason`
     *   explicitly (same "distinguish repeated failed retries" fix `ContributionReliefService`
     *   already applies, see its own KDoc).
     */
    private fun recordTransition(
        row: ResultRow,
        actor: CurrentMember,
        newStatus: TravelExpenseReportStatus,
        newPostedJournalEntryId: Uuid? = null,
        newExecutionError: String? = null,
        newRefundAccountChanged: Boolean? = null,
    ) {
        val id = row[TravelExpenseReportTable.id]
        val subjectId = row[TravelExpenseReportTable.subjectMemberId]
        val lineRows = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq id }.toList()
        val before =
            TravelExpenseSnapshot(
                reportId = id.toString(),
                subjectMemberId = subjectId.toString(),
                status = row[TravelExpenseReportTable.status],
                travelFrom = row[TravelExpenseReportTable.travelFrom],
                travelTo = row[TravelExpenseReportTable.travelTo],
                lineCount = lineRows.size,
                lineKinds = lineRows.map { it[TravelExpenseLineTable.kind] },
                totalAmount = row[TravelExpenseReportTable.totalAmount],
                postedJournalEntryId = row[TravelExpenseReportTable.postedJournalEntryId]?.toString(),
                executionError = row[TravelExpenseReportTable.executionError],
                payoutChoice = row[TravelExpenseReportTable.payoutChoice],
                advanceCents = row[TravelExpenseReportTable.advanceAmount].toCents(),
                dayLineCount = lineRows.count { it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.DAY_ALLOWANCE },
                sepaExportBatchId = row[TravelExpenseReportTable.sepaExportBatchId]?.toString(),
                sepaReexportCount = row[TravelExpenseReportTable.sepaReexportCount],
                refundAccountChanged = row[TravelExpenseReportTable.refundAccountChangedAfterSubmit],
            )
        val after =
            before.copy(
                status = newStatus,
                postedJournalEntryId = newPostedJournalEntryId?.toString(),
                executionError = newExecutionError,
                refundAccountChanged = newRefundAccountChanged ?: before.refundAccountChanged,
            )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.TRAVEL_EXPENSE_REPORT,
            entityId = id,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(TravelExpenseSnapshot.serializer(), before),
            after = Json.encodeToString(TravelExpenseSnapshot.serializer(), after),
        )
    }
}

private const val SEPA_CANDIDATE_LIMIT = 500
private const val SEPA_EXPORTED_HISTORY_LIMIT = 100
private const val AMOUNT_SCALE = 2
private const val MAX_MILEAGE_RATE_SCALE = 2
private val MAX_MILEAGE_RATE = BigDecimal("1.00")
private const val MAX_PER_DIEM_RATE_SCALE = 2

private fun String.toTravelExpenseUuid(paramName: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $paramName: $this") }
