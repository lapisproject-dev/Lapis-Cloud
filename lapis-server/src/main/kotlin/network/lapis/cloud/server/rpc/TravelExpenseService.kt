package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseRatesSnapshot
import network.lapis.cloud.shared.domain.TravelExpenseReceiptDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import network.lapis.cloud.shared.domain.TravelExpenseSnapshot
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
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
) : ITravelExpenseService {
    override suspend fun getTravelExpenseRates(): TravelExpenseRatesDto {
        resolveCurrentMember(call)
        return transaction { loadRates().toDto() }
    }

    override suspend fun updateTravelExpenseRates(
        mileageRatePerKm: BigDecimal?,
        perDiemRate: BigDecimal?,
    ): TravelExpenseRatesDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (mileageRatePerKm != null) {
            if (mileageRatePerKm <= BigDecimal.ZERO) throw BadRequestException("mileageRatePerKm must be positive")
            if (mileageRatePerKm.scale() > MAX_MILEAGE_RATE_SCALE) {
                throw BadRequestException("mileageRatePerKm must have at most $MAX_MILEAGE_RATE_SCALE fractional digits")
            }
        }
        if (perDiemRate != null) {
            if (perDiemRate <= BigDecimal.ZERO) throw BadRequestException("perDiemRate must be positive")
            if (perDiemRate.scale() > MAX_PER_DIEM_RATE_SCALE) {
                throw BadRequestException("perDiemRate must have at most $MAX_PER_DIEM_RATE_SCALE fractional digits")
            }
        }
        return transaction {
            val beforeRates = loadRates()
            val before = TravelExpenseRatesSnapshot(mileageRatePerKm = beforeRates.mileage, perDiemRate = beforeRates.perDiem)
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[travelMileageRatePerKm] = mileageRatePerKm
                it[travelPerDiemRate] = perDiemRate
            }
            val after = TravelExpenseRatesSnapshot(mileageRatePerKm = mileageRatePerKm, perDiemRate = perDiemRate)
            if (before != after) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(TravelExpenseRatesSnapshot.serializer(), before),
                    after = Json.encodeToString(TravelExpenseRatesSnapshot.serializer(), after),
                )
            }
            loadRates().toDto()
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
            }
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
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[purpose] = input.purpose.trim()
                it[travelFrom] = input.travelFrom
                it[travelTo] = input.travelTo
            }
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
        requireLineShape(input)
        return transaction {
            val report = requireEditableRow(id = id, current = current)
            val existingLineCount = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq id }.count()
            if (existingLineCount >= TravelExpenseAmountRules.MAX_LINES_PER_REPORT) {
                throw ConflictException("Report $reportId already has ${TravelExpenseAmountRules.MAX_LINES_PER_REPORT} lines")
            }
            val description = input.description.trim()
            if (description.isEmpty()) throw BadRequestException("description must not be blank")
            if (description.length > TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH) {
                throw BadRequestException("description must be at most ${TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH} characters")
            }
            val rates = loadRates()
            val travelSpanDays = travelSpanDaysOf(report)
            val validated = validateLine(input = input, description = description, rates = rates, travelSpanDays = travelSpanDays)

            val lineId = Uuid.random()
            TravelExpenseLineTable.insert {
                it[TravelExpenseLineTable.id] = lineId
                it[TravelExpenseLineTable.reportId] = report[TravelExpenseReportTable.id]
                it[kind] = input.kind
                it[TravelExpenseLineTable.description] = description
                it[kilometers] = validated.kilometers
                it[days] = validated.days
                it[rateSnapshot] = validated.rateSnapshot
                it[amount] = validated.amount
                it[createdAt] = now
            }
            recomputeTotal(id)
            logger.info { "travel expense line $lineId added to report $id by ${current.memberId} (kind=${input.kind})" }
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

            val receiptRows =
                TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq lineUuid }.toList()
            receiptRows.forEach { receiptRow -> deleteReceiptFile(receiptRow[TravelExpenseReceiptTable.storageKey]) }
            TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.lineId eq lineUuid }
            TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.id eq lineUuid }
            recomputeTotal(reportId)
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
            if (travelTo > now.date) throw BadRequestException("travelTo must not be in the future")
            if (travelFrom < now.date.minus(TravelExpenseAmountRules.MAX_TRAVEL_BACKDATE_DAYS, DateTimeUnit.DAY)) {
                throw BadRequestException(
                    "travelFrom must not be more than ${TravelExpenseAmountRules.MAX_TRAVEL_BACKDATE_DAYS} days in the past",
                )
            }

            val lineRows = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq id }.toList()
            if (lineRows.isEmpty()) throw BadRequestException("At least one line is required to submit a report")

            val rates = loadRates()
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
                when (kind) {
                    TravelExpenseLineKind.RECEIPTED ->
                        if ((receiptCounts[lineId] ?: 0) == 0) {
                            throw BadRequestException("Line $lineId (RECEIPTED) has no receipt attached")
                        }
                    TravelExpenseLineKind.MILEAGE -> {
                        val rate =
                            rates.mileage ?: throw BadRequestException("travel_mileage_rate_per_km is not configured")
                        val kilometers =
                            requireNotNull(lineRow[TravelExpenseLineTable.kilometers]) {
                                "MILEAGE line $lineId has no kilometers -- chk_tel_kind_shape violated?"
                            }
                        val amount = computeAmount(kind = kind, kilometers = kilometers, days = null, rate = rate)
                        // Review MAJOR fix: the rate freeze re-derives `amount` from the THEN-
                        // current rate, which addLine's own validateLine could not have seen at
                        // the time the line was created -- an ADMIN raising the rate between
                        // addLine and submitReport must not be able to silently freeze an amount
                        // beyond MAX_LINE_AMOUNT (BadRequestException, same as addLine, never the
                        // chk_tel_amount_positive/NUMERIC(12,2) DB constraint surfacing as a 500).
                        requireLineAmountInRange(amount)
                        TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineId }) {
                            it[rateSnapshot] = rate
                            it[TravelExpenseLineTable.amount] = amount
                        }
                    }
                    TravelExpenseLineKind.PER_DIEM -> {
                        val rate = rates.perDiem ?: throw BadRequestException("travel_per_diem_rate is not configured")
                        val days =
                            requireNotNull(lineRow[TravelExpenseLineTable.days]) {
                                "PER_DIEM line $lineId has no days -- chk_tel_kind_shape violated?"
                            }
                        if (days > travelSpanDays) {
                            throw BadRequestException("Line $lineId has $days days, but the travel period is only $travelSpanDays day(s)")
                        }
                        val amount = computeAmount(kind = kind, kilometers = null, days = days, rate = rate)
                        // Review MAJOR fix -- same reasoning as the MILEAGE branch above.
                        requireLineAmountInRange(amount)
                        TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineId }) {
                            it[rateSnapshot] = rate
                            it[TravelExpenseLineTable.amount] = amount
                        }
                    }
                }
            }

            recomputeTotal(id)
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) {
                it[TravelExpenseReportTable.status] = TravelExpenseReportStatus.REQUESTED
                it[submittedAt] = now
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
            // deleteReceiptFilesForReport KDoc.
            deleteReceiptFilesForReport(id)
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
                // deleteReceiptFilesForReport KDoc.
                deleteReceiptFilesForReport(id)
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
    }

    /** Exclusivity, mirrors `chk_tel_kind_shape`. `amount` is forbidden for MILEAGE/PER_DIEM (server computes it). */
    private fun requireLineShape(input: TravelExpenseLineInput) {
        when (input.kind) {
            TravelExpenseLineKind.MILEAGE -> {
                if (input.kilometers == null) throw BadRequestException("kilometers is required for kind=MILEAGE")
                if (input.days != null) throw BadRequestException("days must not be set for kind=MILEAGE")
                if (input.amount != null) throw BadRequestException("amount must not be set for kind=MILEAGE (server-computed)")
            }
            TravelExpenseLineKind.PER_DIEM -> {
                if (input.days == null) throw BadRequestException("days is required for kind=PER_DIEM")
                if (input.kilometers != null) throw BadRequestException("kilometers must not be set for kind=PER_DIEM")
                if (input.amount != null) throw BadRequestException("amount must not be set for kind=PER_DIEM (server-computed)")
            }
            TravelExpenseLineKind.RECEIPTED -> {
                if (input.kilometers != null || input.days != null) {
                    throw BadRequestException("kilometers/days must not be set for kind=RECEIPTED")
                }
                if (input.amount == null) throw BadRequestException("amount is required for kind=RECEIPTED")
            }
        }
    }

    private data class ValidatedLine(
        val kilometers: BigDecimal?,
        val days: Int?,
        val rateSnapshot: BigDecimal?,
        val amount: BigDecimal,
    )

    private fun validateLine(
        input: TravelExpenseLineInput,
        description: String,
        rates: TravelExpenseRates,
        travelSpanDays: Int,
    ): ValidatedLine =
        when (input.kind) {
            TravelExpenseLineKind.MILEAGE -> {
                val kilometers = requireNotNull(input.kilometers)
                if (kilometers <= BigDecimal.ZERO || kilometers > BigDecimal(TravelExpenseAmountRules.MAX_KILOMETERS)) {
                    throw BadRequestException("kilometers must be between 0 and ${TravelExpenseAmountRules.MAX_KILOMETERS}")
                }
                val rate = rates.mileage ?: throw BadRequestException("travel_mileage_rate_per_km is not configured")
                val amount = computeAmount(kind = input.kind, kilometers = kilometers, days = null, rate = rate)
                requireLineAmountInRange(amount)
                ValidatedLine(kilometers = kilometers, days = null, rateSnapshot = rate, amount = amount)
            }
            TravelExpenseLineKind.PER_DIEM -> {
                val days = requireNotNull(input.days)
                if (days < 1 || days > TravelExpenseAmountRules.MAX_DAYS) {
                    throw BadRequestException("days must be between 1 and ${TravelExpenseAmountRules.MAX_DAYS}")
                }
                if (days > travelSpanDays) {
                    throw BadRequestException("days ($days) must not exceed the travel period ($travelSpanDays day(s))")
                }
                val rate = rates.perDiem ?: throw BadRequestException("travel_per_diem_rate is not configured")
                val amount = computeAmount(kind = input.kind, kilometers = null, days = days, rate = rate)
                requireLineAmountInRange(amount)
                ValidatedLine(kilometers = null, days = days, rateSnapshot = rate, amount = amount)
            }
            TravelExpenseLineKind.RECEIPTED -> {
                val amount = requireNotNull(input.amount).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                if (amount <= BigDecimal.ZERO) throw BadRequestException("amount must be positive")
                requireLineAmountInRange(amount)
                ValidatedLine(kilometers = null, days = null, rateSnapshot = null, amount = amount)
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

    private fun computeAmount(
        kind: TravelExpenseLineKind,
        kilometers: BigDecimal?,
        days: Int?,
        rate: BigDecimal,
    ): BigDecimal =
        when (kind) {
            TravelExpenseLineKind.MILEAGE -> requireNotNull(kilometers).multiply(rate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
            TravelExpenseLineKind.PER_DIEM -> rate.multiply(BigDecimal(requireNotNull(days))).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
            TravelExpenseLineKind.RECEIPTED -> error("computeAmount is never called for RECEIPTED")
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

    // ── Rates ───────────────────────────────────────────────────────────────────────────────

    private data class TravelExpenseRates(
        val mileage: BigDecimal?,
        val perDiem: BigDecimal?,
        val expenseAccountId: Uuid?,
        val bankAccountId: Uuid?,
    ) {
        fun toDto(): TravelExpenseRatesDto =
            TravelExpenseRatesDto(
                mileageRatePerKm = mileage,
                perDiemRate = perDiem,
                expenseAccountConfigured = expenseAccountId != null,
                bankAccountConfigured = bankAccountId != null,
            )
    }

    private fun loadRates(): TravelExpenseRates {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        return TravelExpenseRates(
            mileage = row?.get(OrganizationSettingsTable.travelMileageRatePerKm),
            perDiem = row?.get(OrganizationSettingsTable.travelPerDiemRate),
            expenseAccountId = row?.get(OrganizationSettingsTable.travelExpenseAccountId),
            bankAccountId = row?.get(OrganizationSettingsTable.paymentBankAccountId),
        )
    }

    // ── Read / mapping helpers ─────────────────────────────────────────────────────────────

    private fun recomputeTotal(reportId: Uuid): BigDecimal {
        val total =
            TravelExpenseLineTable
                .selectAll()
                .where { TravelExpenseLineTable.reportId eq reportId }
                .toList()
                .fold(BigDecimal.ZERO.setScale(AMOUNT_SCALE)) { acc, row -> acc + row[TravelExpenseLineTable.amount] }
        TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
            it[totalAmount] = total
        }
        return total
    }

    private fun loadDto(id: Uuid): TravelExpenseReportDto =
        TravelExpenseReportTable
            .selectAll()
            .where { TravelExpenseReportTable.id eq id }
            .single()
            .toDto()

    private fun ResultRow.toDto(
        namesOverride: Map<Uuid, String>? = null,
        linesOverride: Map<Uuid, List<TravelExpenseLineDto>>? = null,
    ): TravelExpenseReportDto {
        val id = this[TravelExpenseReportTable.id]
        val subjectId = this[TravelExpenseReportTable.subjectMemberId]
        val requestedById = this[TravelExpenseReportTable.requestedBy]
        val decidedById = this[TravelExpenseReportTable.decidedBy]
        val names = namesOverride ?: displayNamesOf(listOfNotNull(subjectId, requestedById, decidedById))
        val lines = linesOverride?.get(id) ?: linesOf(listOf(id))[id].orEmpty()
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
        )
    }

    /**
     * Batch mapper for [listReports]/[listMyReports] -- resolves every row's `displayName`/line/
     * receipt lookups in exactly three queries total (member names, `travel_expense_line inList
     * reportIds`, `travel_expense_receipt inList lineIds`), never a query per row.
     */
    private fun List<ResultRow>.toDtos(): List<TravelExpenseReportDto> {
        if (isEmpty()) return emptyList()
        val memberIds = mutableSetOf<Uuid>()
        forEach { row ->
            memberIds += row[TravelExpenseReportTable.subjectMemberId]
            memberIds += row[TravelExpenseReportTable.requestedBy]
            row[TravelExpenseReportTable.decidedBy]?.let { memberIds += it }
        }
        val names = displayNamesOf(memberIds)
        val reportIds = map { it[TravelExpenseReportTable.id] }
        val lines = linesOf(reportIds)
        return map { it.toDto(namesOverride = names, linesOverride = lines) }
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
            .mapValues { (_, rows) -> rows.map { it.toLineDto(receipts = receiptsByLine[it[TravelExpenseLineTable.id]].orEmpty()) } }
    }

    private fun ResultRow.toLineDto(receipts: List<ResultRow>): TravelExpenseLineDto =
        TravelExpenseLineDto(
            id = this[TravelExpenseLineTable.id].toString(),
            reportId = this[TravelExpenseLineTable.reportId].toString(),
            kind = this[TravelExpenseLineTable.kind],
            description = this[TravelExpenseLineTable.description],
            kilometers = this[TravelExpenseLineTable.kilometers],
            days = this[TravelExpenseLineTable.days],
            rateSnapshot = this[TravelExpenseLineTable.rateSnapshot],
            amount = this[TravelExpenseLineTable.amount],
            receipts = receipts.map { it.toReceiptDto() },
        )

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
    private fun deleteReceiptFilesForReport(reportId: Uuid) {
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
            )
        val after =
            before.copy(
                status = newStatus,
                postedJournalEntryId = newPostedJournalEntryId?.toString(),
                executionError = newExecutionError,
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

private const val AMOUNT_SCALE = 2
private const val MAX_MILEAGE_RATE_SCALE = 4
private const val MAX_PER_DIEM_RATE_SCALE = 2

private fun String.toTravelExpenseUuid(paramName: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $paramName: $this") }
