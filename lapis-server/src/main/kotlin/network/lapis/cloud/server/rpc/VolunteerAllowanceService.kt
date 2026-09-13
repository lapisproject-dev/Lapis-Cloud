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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapAcknowledgmentInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapDisclaimerDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceConfigDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSnapshot
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentInput
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatusSets
import network.lapis.cloud.shared.domain.VolunteerAllowanceRules
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceSnapshot
import network.lapis.cloud.shared.domain.VolunteerAllowanceVerdict
import network.lapis.cloud.shared.domain.VolunteerAllowanceYearStatusDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

private val VOLUNTEER_ALLOWANCE_DECISION_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private const val MAX_LIST_RESULTS = 200
private const val AMOUNT_SCALE = 2
private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG).
 *
 * **Zustandsautomat** (lückenlos, jeder Übergang genau einmal implementiert):
 * | von | nach | Methode | Bedingung |
 * |---|---|---|---|
 * | — | DRAFT | [createDraft] | Subjekt aktiv, < [VolunteerAllowanceRules.MAX_OPEN_PER_MEMBER] offene |
 * | DRAFT | DRAFT | [updateDraft] | Subjekt oder Antragsteller; Kategorie unveränderlich |
 * | DRAFT | REQUESTED | [submitPayment] | volle Validierung (Formal, NICHT der Deckel -- Duarte-Ruling) |
 * | DRAFT/REQUESTED | WITHDRAWN | [withdrawPayment] | Subjekt oder Antragsteller |
 * | REQUESTED | REJECTED | [decidePayment]`(false)` | BOARD/ADMIN, Vier-Augen, Notiz Pflicht |
 * | REQUESTED | EXECUTED | [decidePayment]`(true)` | + Erklärung vorhanden + ggf. Cap-Bestätigung + Buchung erfolgreich |
 * | REQUESTED | APPROVED | [decidePayment]`(true)` | + Buchung gescheitert -> `executionError` |
 * | APPROVED | EXECUTED | [retryPosting] | Vier-Augen, Buchung erfolgreich |
 * | APPROVED | APPROVED | [retryPosting] | erneut gescheitert |
 * | APPROVED | REJECTED | [decidePayment]`(false)` | einziger Ausweg ("keine Sackgasse") |
 *
 * **Vier-Augen lückenlos** ([decidePayment], [retryPosting]): the actor in `{subjectMemberId,
 * requestedBy}` -> [ForbiddenException], ohne Ausnahme, in BEIDEN Fällen -- der V1.4.11-Fund
 * (`retryPosting` is the SECOND booking-producing method and must be guarded identically).
 *
 * **Der Freibetragsdeckel wird bei der ENTSCHEIDUNG geprüft, nicht bei `submitPayment`**
 * (Duarte-Ruling): zwischen Antrag und Freigabe kann eine zweite Zahlung gebucht worden sein.
 * [decidePayment] rechnet den Deckel deshalb frisch, [VolunteerAllowanceExecution] rechnet ihn
 * beim tatsächlichen Buchen ERNEUT nach und vergleicht mit dem eingefrorenen Snapshot
 * (`allowance_total_changed_since_decision`).
 *
 * **TOCTOU-Race-Fix (Security-Fund, Welle V1.4.12): der Deckel-Zeilen-Lock ist ein REGION-Lock,
 * kein Einzelzeilen-Lock.** Unter READ COMMITTED (dem JDBC-Default, kein `defaultIsolationLevel`
 * in `DatabaseConfig`) hätte ein `.forUpdate()` nur auf der EIGENEN Zahlungszeile ZWEI
 * GLEICHZEITIGE [decidePayment]-Aufrufe für ZWEI VERSCHIEDENE Zahlungen derselben
 * Person/Kategorie/Jahres nie serialisiert -- beide Transaktionen hätten `priorTotal` berechnet,
 * bevor die jeweils andere ihre EXECUTED-Transition committet, beide `WITHIN_CAP` erhalten und
 * gemeinsam den gesetzlichen Freibetrag überschritten, ohne dass `chk_vap_exceeding_needs_ack`
 * greift (weil in JEDER einzelnen Zeile `exceeding_amount_snapshot = 0`). [decidePayment] und
 * [retryPosting] sperren deshalb über [lockAllowanceYearRows] JEDE Zeile von
 * `(subjectMemberId, category, Kalenderjahr von payment_date)` -- nicht nur die eigene -- bevor
 * der Deckel überhaupt berechnet wird; siehe dessen KDoc (`VolunteerAllowanceAggregation.kt`) für
 * die Deadlock-Analyse, die genau deshalb einen UNGESPERRTEN Peek vor dem Region-Lock verlangt.
 */
class VolunteerAllowanceService(
    private val call: ApplicationCall,
) : IVolunteerAllowanceService {
    override suspend fun getVolunteerAllowanceConfig(): VolunteerAllowanceConfigDto {
        resolveCurrentMember(call)
        return transaction {
            val row =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            VolunteerAllowanceConfigDto(
                instructorCap = VolunteerAllowanceCalculator.INSTRUCTOR_ANNUAL_CAP_EUR,
                honoraryCap = VolunteerAllowanceCalculator.HONORARY_ANNUAL_CAP_EUR,
                expenseAccountConfigured = row?.get(OrganizationSettingsTable.volunteerAllowanceAccountId) != null,
                bankAccountConfigured = row?.get(OrganizationSettingsTable.paymentBankAccountId) != null,
            )
        }
    }

    override suspend fun getCapDisclaimer(): VolunteerAllowanceCapDisclaimerDto {
        resolveCurrentMember(call)
        return VolunteerAllowanceCapDisclaimerDto(
            version = VolunteerAllowanceCapDisclaimer.VERSION,
            text = VolunteerAllowanceCapDisclaimer.TEXT,
            sha256 = VolunteerAllowanceCapDisclaimer.SHA256,
        )
    }

    override suspend fun getYearStatus(
        memberId: String,
        category: VolunteerAllowanceCategory,
        year: Int,
    ): VolunteerAllowanceYearStatusDto {
        val current = resolveCurrentMember(call)
        val targetId = memberId.toVolunteerAllowanceUuid("memberId")
        if (targetId != current.memberId) current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        requireValidCalendarYear(year)
        return transaction {
            val postedTotal =
                priorPostedAllowanceTotalThisYear(memberId = targetId, category = category, year = year, excludePaymentId = null)
            val cap = VolunteerAllowanceCalculator.capFor(category)
            val remaining = (cap - postedTotal).let { if (it.signum() < 0) BigDecimal.ZERO.setScale(AMOUNT_SCALE) else it }
            val declarationRow = declarationRow(memberId = targetId, category = category, year = year)
            VolunteerAllowanceYearStatusDto(
                memberId = targetId.toString(),
                category = category,
                calendarYear = year,
                annualCap = cap,
                postedTotalInThisOrganization = postedTotal,
                remainingInThisOrganization = remaining,
                declaration =
                    declarationRow?.let {
                        toDeclarationDto(
                            row = it,
                            names = displayNamesOf(listOf(it[VolunteerAllowanceSelfDeclarationTable.recordedBy])),
                        )
                    },
            )
        }
    }

    override suspend fun declareSelf(
        category: VolunteerAllowanceCategory,
        calendarYear: Int,
    ): VolunteerAllowanceSelfDeclarationDto {
        val current = resolveCurrentMember(call)
        val now = DbClock.nowLocalDateTime()
        requireSelfDeclarationShape(
            source = VolunteerAllowanceDeclarationSource.IN_APP,
            memberId = current.memberId,
            recordedBy = current.memberId,
            signedOn = null,
            today = now.date,
        )
        requireValidCalendarYear(calendarYear)
        return transaction {
            val existing =
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where {
                        (VolunteerAllowanceSelfDeclarationTable.memberId eq current.memberId) and
                            (VolunteerAllowanceSelfDeclarationTable.category eq category) and
                            (VolunteerAllowanceSelfDeclarationTable.calendarYear eq calendarYear)
                    }.singleOrNull()
            if (existing != null) {
                throw ConflictException(
                    "A declaration for member ${current.memberId}/$category/$calendarYear already exists",
                )
            }
            val id = Uuid.random()
            VolunteerAllowanceSelfDeclarationTable.insert {
                it[VolunteerAllowanceSelfDeclarationTable.id] = id
                it[memberId] = current.memberId
                it[VolunteerAllowanceSelfDeclarationTable.category] = category
                it[VolunteerAllowanceSelfDeclarationTable.calendarYear] = calendarYear
                it[VolunteerAllowanceSelfDeclarationTable.declarationSource] = VolunteerAllowanceDeclarationSource.IN_APP
                it[declaredAt] = now
                it[signedOn] = null
                it[recordedBy] = current.memberId
            }
            recordDeclarationCreated(
                id = id,
                memberId = current.memberId,
                category = category,
                calendarYear = calendarYear,
                source = VolunteerAllowanceDeclarationSource.IN_APP,
                signedOn = null,
                actor = current,
            )
            logger.info {
                "volunteer allowance self declaration $id created (IN_APP) for member ${current.memberId} $category/$calendarYear"
            }
            loadDeclarationDto(id)
        }
    }

    override suspend fun recordPaperDeclaration(input: VolunteerAllowanceSelfDeclarationInput): VolunteerAllowanceSelfDeclarationDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        val targetMemberId = input.memberId.toVolunteerAllowanceUuid("memberId")
        val now = DbClock.nowLocalDateTime()
        if (input.source != VolunteerAllowanceDeclarationSource.ON_PAPER) {
            throw BadRequestException("recordPaperDeclaration only accepts source=ON_PAPER")
        }
        requireSelfDeclarationShape(
            source = VolunteerAllowanceDeclarationSource.ON_PAPER,
            memberId = targetMemberId,
            recordedBy = current.memberId,
            signedOn = input.signedOn,
            today = now.date,
        )
        requireValidCalendarYear(input.calendarYear)
        return transaction {
            val memberRow =
                MemberTable.selectAll().where { MemberTable.id eq targetMemberId }.singleOrNull()
                    ?: throw NotFoundException("Member ${input.memberId} not found")
            if (memberRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer have a paper declaration recorded")
            }
            val existing =
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where {
                        (VolunteerAllowanceSelfDeclarationTable.memberId eq targetMemberId) and
                            (VolunteerAllowanceSelfDeclarationTable.category eq input.category) and
                            (VolunteerAllowanceSelfDeclarationTable.calendarYear eq input.calendarYear)
                    }.singleOrNull()
            if (existing != null) {
                throw ConflictException(
                    "A declaration for member $targetMemberId/${input.category}/${input.calendarYear} already exists",
                )
            }
            val id = Uuid.random()
            VolunteerAllowanceSelfDeclarationTable.insert {
                it[VolunteerAllowanceSelfDeclarationTable.id] = id
                it[memberId] = targetMemberId
                it[category] = input.category
                it[calendarYear] = input.calendarYear
                it[VolunteerAllowanceSelfDeclarationTable.declarationSource] = VolunteerAllowanceDeclarationSource.ON_PAPER
                it[declaredAt] = now
                it[signedOn] = input.signedOn
                it[recordedBy] = current.memberId
            }
            recordDeclarationCreated(
                id = id,
                memberId = targetMemberId,
                category = input.category,
                calendarYear = input.calendarYear,
                source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                signedOn = input.signedOn,
                actor = current,
            )
            logger.info {
                "volunteer allowance self declaration $id recorded (ON_PAPER) for member $targetMemberId ${input.category}/${input.calendarYear} by ${current.memberId}"
            }
            loadDeclarationDto(id)
        }
    }

    override suspend fun listDeclarations(
        memberId: String?,
        calendarYear: Int?,
    ): List<VolunteerAllowanceSelfDeclarationDto> {
        val current = resolveCurrentMember(call)
        val targetId = memberId?.toVolunteerAllowanceUuid("memberId")
        if (targetId != null && targetId != current.memberId) current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        val effectiveId = targetId ?: current.memberId
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(VolunteerAllowanceSelfDeclarationTable.memberId eq effectiveId)
            if (calendarYear != null) conditions += (VolunteerAllowanceSelfDeclarationTable.calendarYear eq calendarYear)
            val rows =
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where { conditions.reduce { a, b -> a and b } }
                    .orderBy(VolunteerAllowanceSelfDeclarationTable.declaredAt to SortOrder.DESC)
                    .limit(MAX_LIST_RESULTS)
                    .toList()
            val names = displayNamesOf(rows.map { it[VolunteerAllowanceSelfDeclarationTable.recordedBy] })
            rows.map { toDeclarationDto(row = it, names = names) }
        }
    }

    /**
     * Security-Fund (INFORMATIONAL: "keine Korrektur-/Widerrufsmöglichkeit für eine falsch oder
     * missbräuchlich erfasste Papier-Selbstauskunft") -- see interface KDoc for the full
     * role/scope contract. ADMIN only, ON_PAPER only, HARD-delete + [AuditAction.VOID] entry.
     */
    override suspend fun voidPaperDeclaration(declarationId: String): Boolean {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = declarationId.toVolunteerAllowanceUuid("declarationId")
        return transaction {
            val row =
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where { VolunteerAllowanceSelfDeclarationTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowanceSelfDeclaration $declarationId not found")
            val source = row[VolunteerAllowanceSelfDeclarationTable.declarationSource]
            if (source != VolunteerAllowanceDeclarationSource.ON_PAPER) {
                throw ConflictException(
                    "VolunteerAllowanceSelfDeclaration $declarationId is $source, only ON_PAPER declarations can be voided " +
                        "-- a member's own IN_APP declaration cannot be removed through this endpoint",
                )
            }
            val snapshot =
                VolunteerAllowanceDeclarationSnapshot(
                    declarationId = id.toString(),
                    memberId = row[VolunteerAllowanceSelfDeclarationTable.memberId].toString(),
                    category = row[VolunteerAllowanceSelfDeclarationTable.category],
                    calendarYear = row[VolunteerAllowanceSelfDeclarationTable.calendarYear],
                    source = source,
                    signedOn = row[VolunteerAllowanceSelfDeclarationTable.signedOn],
                    recordedBy = row[VolunteerAllowanceSelfDeclarationTable.recordedBy].toString(),
                )
            // HARD-delete -- no soft-delete/void column on this table (see AuditAction.VOID KDoc
            // for why a soft-delete would keep uq_vasd_member_category_year blocking the subject's
            // own fresh declareSelf for the same category/year).
            VolunteerAllowanceSelfDeclarationTable.deleteWhere { VolunteerAllowanceSelfDeclarationTable.id eq id }
            // Must be the LAST lock-taking operation of this transaction -- AuditLogRecorder's own
            // "deadlock-avoidance contract".
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.VOLUNTEER_DECLARATION,
                entityId = id,
                action = AuditAction.VOID,
                before = Json.encodeToString(VolunteerAllowanceDeclarationSnapshot.serializer(), snapshot),
            )
            logger.info { "volunteer allowance self declaration $id (ON_PAPER) voided by ${current.memberId}" }
            true
        }
    }

    override suspend fun createDraft(
        subjectMemberId: String,
        input: VolunteerAllowancePaymentInput,
    ): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        val subjectId = subjectMemberId.toVolunteerAllowanceUuid("subjectMemberId")
        // IDOR-Gate: MEMBER nur für sich selbst, BOARD/ADMIN auch im Namen eines fremden Mitglieds.
        if (subjectId != current.memberId) current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        val now = DbClock.nowLocalDateTime()
        requireValidPaymentShape(input = input, now = now)

        return transaction {
            val subjectRow =
                MemberTable.selectAll().where { MemberTable.id eq subjectId }.singleOrNull()
                    ?: throw NotFoundException("Member $subjectMemberId not found")
            if (subjectRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer request an allowance payment")
            }
            if (subjectRow[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) {
                throw ConflictException("Cannot create a volunteer allowance payment for a member whose membership has ended")
            }
            val openCount =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where {
                        (VolunteerAllowancePaymentTable.subjectMemberId eq subjectId) and
                            (VolunteerAllowancePaymentTable.status inList VolunteerAllowancePaymentStatusSets.OPEN)
                    }.count()
            if (openCount >= VolunteerAllowanceRules.MAX_OPEN_PER_MEMBER) {
                throw ConflictException(
                    "Member $subjectMemberId already has ${VolunteerAllowanceRules.MAX_OPEN_PER_MEMBER} open volunteer allowance payments",
                )
            }

            val id = Uuid.random()
            val amount = input.amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
            VolunteerAllowancePaymentTable.insert {
                it[VolunteerAllowancePaymentTable.id] = id
                it[VolunteerAllowancePaymentTable.subjectMemberId] = subjectId
                it[category] = input.category
                it[status] = VolunteerAllowancePaymentStatus.DRAFT
                it[VolunteerAllowancePaymentTable.amount] = amount
                it[activityDescription] = input.activityDescription.trim()
                it[paymentDate] = input.paymentDate
                it[createdAt] = now
                it[requestedBy] = current.memberId
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.VOLUNTEER_ALLOWANCE_PAYMENT,
                entityId = id,
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        VolunteerAllowanceSnapshot.serializer(),
                        VolunteerAllowanceSnapshot(
                            paymentId = id.toString(),
                            subjectMemberId = subjectId.toString(),
                            category = input.category,
                            status = VolunteerAllowancePaymentStatus.DRAFT,
                            paymentDate = input.paymentDate,
                            amount = amount,
                        ),
                    ),
                occurredAt = now,
            )
            logger.info { "volunteer allowance draft created: id=$id subjectMemberId=$subjectId requestedBy=${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun updateDraft(
        paymentId: String,
        input: VolunteerAllowancePaymentInput,
    ): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        val id = paymentId.toVolunteerAllowanceUuid("paymentId")
        val now = DbClock.nowLocalDateTime()
        requireValidPaymentShape(input = input, now = now)
        return transaction {
            val row = requireEditableRow(id = id, current = current)
            // Kategorie UNVERAENDERLICH -- siehe VolunteerAllowanceCategory KDoc.
            if (row[VolunteerAllowancePaymentTable.category] != input.category) {
                throw ConflictException("The category of an existing volunteer allowance payment cannot be changed")
            }
            VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                it[amount] = input.amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                it[activityDescription] = input.activityDescription.trim()
                it[paymentDate] = input.paymentDate
            }
            logger.info { "volunteer allowance draft $id updated by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun submitPayment(paymentId: String): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        val id = paymentId.toVolunteerAllowanceUuid("paymentId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where { VolunteerAllowancePaymentTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            requireSelfServiceCaller(row = row, current = current)
            val status = row[VolunteerAllowancePaymentTable.status]
            if (status != VolunteerAllowancePaymentStatus.DRAFT) {
                throw ConflictException("VolunteerAllowancePayment $paymentId is $status, cannot be submitted (only DRAFT can)")
            }
            VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.REQUESTED
                it[submittedAt] = now
            }
            recordTransition(row = row, actor = current, newStatus = VolunteerAllowancePaymentStatus.REQUESTED)
            logger.info { "volunteer allowance payment $id submitted by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun withdrawPayment(paymentId: String): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        val id = paymentId.toVolunteerAllowanceUuid("paymentId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where { VolunteerAllowancePaymentTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            requireSelfServiceCaller(row = row, current = current)
            val status = row[VolunteerAllowancePaymentTable.status]
            if (status !in VolunteerAllowancePaymentStatusSets.WITHDRAWABLE) {
                throw ConflictException("VolunteerAllowancePayment $paymentId is $status, cannot be withdrawn (only DRAFT/REQUESTED can)")
            }
            VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.WITHDRAWN
                it[decidedAt] = now
                it[decidedBy] = current.memberId
            }
            recordTransition(row = row, actor = current, newStatus = VolunteerAllowancePaymentStatus.WITHDRAWN)
            logger.info { "volunteer allowance payment $id withdrawn by ${current.memberId}" }
            loadDto(id)
        }
    }

    override suspend fun listMyPayments(): List<VolunteerAllowancePaymentDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            VolunteerAllowancePaymentTable
                .selectAll()
                .where {
                    (VolunteerAllowancePaymentTable.subjectMemberId eq current.memberId) or
                        (VolunteerAllowancePaymentTable.requestedBy eq current.memberId)
                }.orderBy(
                    VolunteerAllowancePaymentTable.createdAt to SortOrder.DESC,
                    VolunteerAllowancePaymentTable.id to SortOrder.DESC,
                ).limit(MAX_LIST_RESULTS)
                .toList()
                .toDtos()
        }
    }

    override suspend fun listPayments(
        status: VolunteerAllowancePaymentStatus?,
        afterSubmittedAt: LocalDateTime?,
        afterId: String?,
    ): List<VolunteerAllowancePaymentDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        if (status == VolunteerAllowancePaymentStatus.DRAFT) {
            throw BadRequestException("status=DRAFT payments are never listed here -- a draft is private")
        }
        val cursorId = afterId?.toVolunteerAllowanceUuid("afterId")
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(VolunteerAllowancePaymentTable.submittedAt.isNotNull())
            if (status != null) conditions += (VolunteerAllowancePaymentTable.status eq status)
            if (afterSubmittedAt != null && cursorId != null) {
                conditions +=
                    (VolunteerAllowancePaymentTable.submittedAt greater afterSubmittedAt) or
                    (
                        (VolunteerAllowancePaymentTable.submittedAt eq afterSubmittedAt) and
                            (VolunteerAllowancePaymentTable.id greater cursorId)
                    )
            }
            VolunteerAllowancePaymentTable
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .orderBy(
                    VolunteerAllowancePaymentTable.submittedAt to SortOrder.ASC,
                    VolunteerAllowancePaymentTable.id to SortOrder.ASC,
                ).limit(MAX_LIST_RESULTS)
                .toList()
                .toDtos()
        }
    }

    override suspend fun decidePayment(
        paymentId: String,
        approve: Boolean,
        note: String?,
        capAcknowledgment: VolunteerAllowanceCapAcknowledgmentInput?,
    ): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        val id = paymentId.toVolunteerAllowanceUuid("paymentId")
        val trimmedNote = note?.trim()?.ifBlank { null }
        if (trimmedNote == null) {
            throw BadRequestException("A decision note is required to approve or reject a volunteer allowance payment")
        }
        if (trimmedNote.length > VolunteerAllowanceRules.MAX_DECISION_NOTE_LENGTH) {
            throw BadRequestException("decision note must be at most ${VolunteerAllowanceRules.MAX_DECISION_NOTE_LENGTH} characters")
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Security-Fund (TOCTOU-Race auf dem Jahresfreibetrag) -- UNLOCKED peek first, purely
            // to learn subjectMemberId/category/the calendar year of THIS payment. Locking this
            // payment's OWN row individually here (as before this fix) would reopen the exact
            // AB/BA deadlock `lockAllowanceYearRows`'s KDoc warns against as soon as a second,
            // concurrent decidePayment/retryPosting call targets a sibling payment of the same
            // subject/category/year -- see that KDoc (VolunteerAllowanceAggregation.kt) for why.
            val peek =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where { VolunteerAllowancePaymentTable.id eq id }
                    .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            val lockedRows =
                lockAllowanceYearRows(
                    memberId = peek[VolunteerAllowancePaymentTable.subjectMemberId],
                    category = peek[VolunteerAllowancePaymentTable.category],
                    year = peek[VolunteerAllowancePaymentTable.paymentDate].year,
                )
            val row =
                lockedRows.find { it[VolunteerAllowancePaymentTable.id] == id }
                    ?: VolunteerAllowancePaymentTable
                        .selectAll()
                        .where { VolunteerAllowancePaymentTable.id eq id }
                        .forUpdate()
                        .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            val status = row[VolunteerAllowancePaymentTable.status]
            val allowedFromStatuses =
                if (approve) setOf(VolunteerAllowancePaymentStatus.REQUESTED) else VolunteerAllowancePaymentStatusSets.REJECTABLE
            if (status !in allowedFromStatuses) {
                val verb = if (approve) "approved" else "rejected"
                throw ConflictException(
                    "VolunteerAllowancePayment $paymentId is $status, cannot be $verb (only " +
                        "${allowedFromStatuses.joinToString(" or ")} can)",
                )
            }
            val subjectId = row[VolunteerAllowancePaymentTable.subjectMemberId]
            val requestedById = row[VolunteerAllowancePaymentTable.requestedBy]
            // Vier-Augen lückenlos ueber BEIDE Wege -- siehe Klassen-KDoc.
            if (current.memberId == subjectId || current.memberId == requestedById) throw ForbiddenException()

            if (!approve) {
                VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                    it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.REJECTED
                    it[decidedAt] = now
                    it[decidedBy] = current.memberId
                    it[decisionNote] = trimmedNote
                    it[executionError] = null
                }
                recordTransition(row = row, actor = current, newStatus = VolunteerAllowancePaymentStatus.REJECTED)
                return@transaction loadDto(id)
            }

            val category = row[VolunteerAllowancePaymentTable.category]
            val paymentDate = row[VolunteerAllowancePaymentTable.paymentDate]
            val amount = row[VolunteerAllowancePaymentTable.amount]

            // Raskins Gate: die Erklaerung muss existieren, sonst APPROVED + execution_error
            // (kein Throw -- der Vorstand hat legitim entschieden, siehe Klassen-KDoc).
            val declarationExists =
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where {
                        (VolunteerAllowanceSelfDeclarationTable.memberId eq subjectId) and
                            (VolunteerAllowanceSelfDeclarationTable.category eq category) and
                            (VolunteerAllowanceSelfDeclarationTable.calendarYear eq paymentDate.year)
                    }.count() > 0
            if (!declarationExists) {
                VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                    it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.APPROVED
                    it[decidedAt] = now
                    it[decidedBy] = current.memberId
                    it[decisionNote] = trimmedNote
                    it[executionError] = VOLUNTEER_ALLOWANCE_REASON_SELF_DECLARATION_MISSING
                }
                recordTransition(
                    row = row,
                    actor = current,
                    newStatus = VolunteerAllowancePaymentStatus.APPROVED,
                    newExecutionError = VOLUNTEER_ALLOWANCE_REASON_SELF_DECLARATION_MISSING,
                )
                return@transaction loadDto(id)
            }

            val priorTotal =
                priorPostedAllowanceTotalThisYear(memberId = subjectId, category = category, year = paymentDate.year, excludePaymentId = id)
            val result = VolunteerAllowanceCalculator.check(category = category, amount = amount, priorPostedTotalThisYear = priorTotal)

            if (result.verdict == VolunteerAllowanceVerdict.EXCEEDS_CAP) {
                if (capAcknowledgment == null ||
                    !VolunteerAllowanceCapDisclaimer.matches(
                        version = capAcknowledgment.disclaimerVersion,
                        sha256 = capAcknowledgment.disclaimerSha256,
                    )
                ) {
                    throw ConflictException(
                        "This payment exceeds the annual §3 Nr. 26/26a EStG cap and requires a fresh cap acknowledgment -- " +
                            "call getCapDisclaimer() again and send its CURRENT version/sha256 unchanged.",
                    )
                }
            } else if (capAcknowledgment != null) {
                throw ConflictException(
                    "capAcknowledgment must only be sent when the payment actually exceeds the annual cap",
                )
            }

            // chk_vap_snapshot_shape requires status IN ('APPROVED','EXECUTED','REJECTED','WITHDRAWN')
            // the moment any snapshot column is non-null -- status is therefore flipped to APPROVED
            // in THIS SAME update, atomically with the snapshots (never left at REQUESTED with
            // snapshots already set, even transiently within the same transaction). The subsequent
            // Posted/Failed branch either advances it to EXECUTED or leaves it at APPROVED.
            VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.APPROVED
                it[decidedAt] = now
                it[decidedBy] = current.memberId
                it[decisionNote] = trimmedNote
                it[priorTotalSnapshot] = result.priorTotal
                it[freeAmountSnapshot] = result.freeAmount
                it[exceedingAmountSnapshot] = result.exceedingAmount
                if (result.verdict == VolunteerAllowanceVerdict.EXCEEDS_CAP && capAcknowledgment != null) {
                    it[capDisclaimerVersion] = capAcknowledgment.disclaimerVersion
                    it[capDisclaimerSha256] = capAcknowledgment.disclaimerSha256
                    it[capAcknowledgedBy] = current.memberId
                    it[capAcknowledgedAt] = now
                }
            }

            val refreshedRow =
                VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq id }.single()
            when (val outcome = VolunteerAllowanceExecution.execute(payment = refreshedRow, actor = current, now = now)) {
                is VolunteerAllowancePostingOutcome.Posted -> {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                        it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.EXECUTED
                        it[executedAt] = now
                        it[executionError] = null
                        it[postedJournalEntryId] = outcome.journalEntryId
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = VolunteerAllowancePaymentStatus.EXECUTED,
                        newPostedJournalEntryId = outcome.journalEntryId,
                    )
                }
                is VolunteerAllowancePostingOutcome.Failed -> {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                        it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.APPROVED
                        it[executionError] = outcome.reason
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = VolunteerAllowancePaymentStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id)
        }
    }

    override suspend fun retryPosting(paymentId: String): VolunteerAllowancePaymentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        val id = paymentId.toVolunteerAllowanceUuid("paymentId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Security-Fund (TOCTOU-Race auf dem Jahresfreibetrag) -- same restructuring as
            // decidePayment: UNLOCKED peek to learn subjectMemberId/category/the calendar year,
            // THEN the region lock, THEN pull this payment's own row out of it. `execute()` below
            // recomputes `priorPostedAllowanceTotalThisYear` internally (its own "race guard"
            // re-check) -- without the region lock held BEFORE that call, a concurrent
            // decidePayment/retryPosting on a sibling payment of the same partition could still
            // commit its EXECUTED transition in between, invisible to this recheck under
            // READ COMMITTED. See `lockAllowanceYearRows`'s KDoc (VolunteerAllowanceAggregation.kt)
            // for why locking this payment's OWN row individually first (as before this fix) would
            // reopen an AB/BA deadlock against a concurrent decidePayment/retryPosting instead.
            val peek =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where { VolunteerAllowancePaymentTable.id eq id }
                    .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            val lockedRows =
                lockAllowanceYearRows(
                    memberId = peek[VolunteerAllowancePaymentTable.subjectMemberId],
                    category = peek[VolunteerAllowancePaymentTable.category],
                    year = peek[VolunteerAllowancePaymentTable.paymentDate].year,
                )
            val row =
                lockedRows.find { it[VolunteerAllowancePaymentTable.id] == id }
                    ?: VolunteerAllowancePaymentTable
                        .selectAll()
                        .where { VolunteerAllowancePaymentTable.id eq id }
                        .forUpdate()
                        .singleOrNull()
                    ?: throw NotFoundException("VolunteerAllowancePayment $paymentId not found")
            val status = row[VolunteerAllowancePaymentTable.status]
            if (status != VolunteerAllowancePaymentStatus.APPROVED ||
                row[VolunteerAllowancePaymentTable.executionError] == null ||
                row[VolunteerAllowancePaymentTable.postedJournalEntryId] != null
            ) {
                throw ConflictException(
                    "VolunteerAllowancePayment $paymentId is $status, cannot be retried (only APPROVED with a failed booking can)",
                )
            }
            // Vier-Augen lückenlos, wie decidePayment -- der V1.4.11-Security-Fund, siehe Klassen-KDoc.
            val subjectId = row[VolunteerAllowancePaymentTable.subjectMemberId]
            val requestedById = row[VolunteerAllowancePaymentTable.requestedBy]
            if (current.memberId == subjectId || current.memberId == requestedById) throw ForbiddenException()

            when (val outcome = VolunteerAllowanceExecution.execute(payment = row, actor = current, now = now)) {
                is VolunteerAllowancePostingOutcome.Posted -> {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                        it[VolunteerAllowancePaymentTable.status] = VolunteerAllowancePaymentStatus.EXECUTED
                        it[executedAt] = now
                        it[executionError] = null
                        it[postedJournalEntryId] = outcome.journalEntryId
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = VolunteerAllowancePaymentStatus.EXECUTED,
                        newPostedJournalEntryId = outcome.journalEntryId,
                    )
                }
                is VolunteerAllowancePostingOutcome.Failed -> {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq id }) {
                        it[executionError] = outcome.reason
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = VolunteerAllowancePaymentStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id)
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────────

    private fun requireValidPaymentShape(
        input: VolunteerAllowancePaymentInput,
        now: LocalDateTime,
    ) {
        val description = input.activityDescription.trim()
        if (description.length < VolunteerAllowanceRules.MIN_ACTIVITY_DESCRIPTION_LENGTH) {
            throw BadRequestException(
                "activityDescription must be at least ${VolunteerAllowanceRules.MIN_ACTIVITY_DESCRIPTION_LENGTH} characters",
            )
        }
        if (description.length > VolunteerAllowanceRules.MAX_ACTIVITY_DESCRIPTION_LENGTH) {
            throw BadRequestException(
                "activityDescription must be at most ${VolunteerAllowanceRules.MAX_ACTIVITY_DESCRIPTION_LENGTH} characters",
            )
        }
        if (input.amount.scale() > AMOUNT_SCALE) {
            throw BadRequestException("amount must have at most $AMOUNT_SCALE fractional digits")
        }
        if (input.amount <= BigDecimal.ZERO) throw BadRequestException("amount must be positive")
        if (input.amount > BigDecimal(VolunteerAllowanceRules.MAX_PAYMENT_AMOUNT)) {
            throw BadRequestException("amount must be at most ${VolunteerAllowanceRules.MAX_PAYMENT_AMOUNT}")
        }
        val earliest = now.date.minus(VolunteerAllowanceRules.MAX_BACKDATE_DAYS, DateTimeUnit.DAY)
        val latest = now.date.plus(VolunteerAllowanceRules.MAX_FUTURE_DAYS, DateTimeUnit.DAY)
        if (input.paymentDate < earliest || input.paymentDate > latest) {
            throw BadRequestException(
                "paymentDate must be between $earliest and $latest",
            )
        }
    }

    private fun requireValidCalendarYear(year: Int) {
        if (year < 2000 || year > 2200) throw BadRequestException("calendarYear must be between 2000 and 2200")
    }

    // ── Access-control helpers ─────────────────────────────────────────────────────────────

    /** DRAFT only, subject or requester -- no BOARD/ADMIN bypass, see class KDoc state table. */
    private fun requireEditableRow(
        id: Uuid,
        current: CurrentMember,
    ): ResultRow {
        val row =
            VolunteerAllowancePaymentTable
                .selectAll()
                .where { VolunteerAllowancePaymentTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("VolunteerAllowancePayment $id not found")
        requireSelfServiceCaller(row = row, current = current)
        val status = row[VolunteerAllowancePaymentTable.status]
        if (status !in VolunteerAllowancePaymentStatusSets.EDITABLE) {
            throw ConflictException("VolunteerAllowancePayment $id is $status, cannot be edited (only DRAFT can)")
        }
        return row
    }

    private fun requireSelfServiceCaller(
        row: ResultRow,
        current: CurrentMember,
    ) {
        val subjectId = row[VolunteerAllowancePaymentTable.subjectMemberId]
        val requestedById = row[VolunteerAllowancePaymentTable.requestedBy]
        if (current.memberId != subjectId && current.memberId != requestedById) throw ForbiddenException()
    }

    // ── Read / mapping helpers ─────────────────────────────────────────────────────────────

    private fun declarationRow(
        memberId: Uuid,
        category: VolunteerAllowanceCategory,
        year: Int,
    ): ResultRow? =
        VolunteerAllowanceSelfDeclarationTable
            .selectAll()
            .where {
                (VolunteerAllowanceSelfDeclarationTable.memberId eq memberId) and
                    (VolunteerAllowanceSelfDeclarationTable.category eq category) and
                    (VolunteerAllowanceSelfDeclarationTable.calendarYear eq year)
            }.singleOrNull()

    private fun loadDeclarationDto(id: Uuid): VolunteerAllowanceSelfDeclarationDto {
        val row =
            VolunteerAllowanceSelfDeclarationTable.selectAll().where { VolunteerAllowanceSelfDeclarationTable.id eq id }.single()
        val names = displayNamesOf(listOf(row[VolunteerAllowanceSelfDeclarationTable.recordedBy]))
        return toDeclarationDto(row = row, names = names)
    }

    private fun toDeclarationDto(
        row: ResultRow,
        names: Map<Uuid, String>,
    ): VolunteerAllowanceSelfDeclarationDto =
        VolunteerAllowanceSelfDeclarationDto(
            id = row[VolunteerAllowanceSelfDeclarationTable.id].toString(),
            memberId = row[VolunteerAllowanceSelfDeclarationTable.memberId].toString(),
            category = row[VolunteerAllowanceSelfDeclarationTable.category],
            calendarYear = row[VolunteerAllowanceSelfDeclarationTable.calendarYear],
            source = row[VolunteerAllowanceSelfDeclarationTable.declarationSource],
            declaredAt = row[VolunteerAllowanceSelfDeclarationTable.declaredAt],
            signedOn = row[VolunteerAllowanceSelfDeclarationTable.signedOn],
            recordedByDisplayName = names[row[VolunteerAllowanceSelfDeclarationTable.recordedBy]] ?: "",
        )

    private fun recordDeclarationCreated(
        id: Uuid,
        memberId: Uuid,
        category: VolunteerAllowanceCategory,
        calendarYear: Int,
        source: VolunteerAllowanceDeclarationSource,
        signedOn: LocalDate?,
        actor: CurrentMember,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.VOLUNTEER_DECLARATION,
            entityId = id,
            action = AuditAction.CREATE,
            after =
                Json.encodeToString(
                    VolunteerAllowanceDeclarationSnapshot.serializer(),
                    VolunteerAllowanceDeclarationSnapshot(
                        declarationId = id.toString(),
                        memberId = memberId.toString(),
                        category = category,
                        calendarYear = calendarYear,
                        source = source,
                        signedOn = signedOn,
                        recordedBy = actor.memberId.toString(),
                    ),
                ),
        )
    }

    private fun loadDto(id: Uuid): VolunteerAllowancePaymentDto =
        VolunteerAllowancePaymentTable
            .selectAll()
            .where { VolunteerAllowancePaymentTable.id eq id }
            .single()
            .toDto()

    private fun ResultRow.toDto(namesOverride: Map<Uuid, String>? = null): VolunteerAllowancePaymentDto {
        val id = this[VolunteerAllowancePaymentTable.id]
        val subjectId = this[VolunteerAllowancePaymentTable.subjectMemberId]
        val requestedById = this[VolunteerAllowancePaymentTable.requestedBy]
        val decidedById = this[VolunteerAllowancePaymentTable.decidedBy]
        val capAckById = this[VolunteerAllowancePaymentTable.capAcknowledgedBy]
        val names = namesOverride ?: displayNamesOf(listOfNotNull(subjectId, requestedById, decidedById, capAckById))
        return VolunteerAllowancePaymentDto(
            id = id.toString(),
            subjectMemberId = subjectId.toString(),
            subjectDisplayName = names[subjectId] ?: "",
            category = this[VolunteerAllowancePaymentTable.category],
            status = this[VolunteerAllowancePaymentTable.status],
            amount = this[VolunteerAllowancePaymentTable.amount],
            activityDescription = this[VolunteerAllowancePaymentTable.activityDescription],
            paymentDate = this[VolunteerAllowancePaymentTable.paymentDate],
            createdAt = this[VolunteerAllowancePaymentTable.createdAt],
            submittedAt = this[VolunteerAllowancePaymentTable.submittedAt],
            requestedBy = requestedById.toString(),
            requestedByDisplayName = names[requestedById] ?: "",
            decidedBy = decidedById?.toString(),
            decidedByDisplayName = decidedById?.let { names[it] },
            decidedAt = this[VolunteerAllowancePaymentTable.decidedAt],
            decisionNote = this[VolunteerAllowancePaymentTable.decisionNote],
            executedAt = this[VolunteerAllowancePaymentTable.executedAt],
            postedJournalEntryId = this[VolunteerAllowancePaymentTable.postedJournalEntryId]?.toString(),
            executionError = this[VolunteerAllowancePaymentTable.executionError],
            priorTotalSnapshot = this[VolunteerAllowancePaymentTable.priorTotalSnapshot],
            freeAmountSnapshot = this[VolunteerAllowancePaymentTable.freeAmountSnapshot],
            exceedingAmountSnapshot = this[VolunteerAllowancePaymentTable.exceedingAmountSnapshot],
            capDisclaimerVersion = this[VolunteerAllowancePaymentTable.capDisclaimerVersion],
            capAcknowledgedByDisplayName = capAckById?.let { names[it] },
            capAcknowledgedAt = this[VolunteerAllowancePaymentTable.capAcknowledgedAt],
        )
    }

    private fun List<ResultRow>.toDtos(): List<VolunteerAllowancePaymentDto> {
        if (isEmpty()) return emptyList()
        val memberIds = mutableSetOf<Uuid>()
        forEach { row ->
            memberIds += row[VolunteerAllowancePaymentTable.subjectMemberId]
            memberIds += row[VolunteerAllowancePaymentTable.requestedBy]
            row[VolunteerAllowancePaymentTable.decidedBy]?.let { memberIds += it }
            row[VolunteerAllowancePaymentTable.capAcknowledgedBy]?.let { memberIds += it }
        }
        val names = displayNamesOf(memberIds)
        return map { it.toDto(namesOverride = names) }
    }

    private fun displayNamesOf(ids: Collection<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList ids.distinct() }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    /**
     * @param newPostedJournalEntryId non-null only for the ONE transition into EXECUTED.
     * @param newExecutionError defaults to `null` because every transition except "APPROVED with
     *   a failed under-lock recheck" clears it -- those call sites pass `outcome.reason` (or the
     *   self-declaration-missing reason) explicitly, same fix `ContributionReliefService`/
     *   `TravelExpenseService` already apply.
     */
    private fun recordTransition(
        row: ResultRow,
        actor: CurrentMember,
        newStatus: VolunteerAllowancePaymentStatus,
        newPostedJournalEntryId: Uuid? = null,
        newExecutionError: String? = null,
    ) {
        val id = row[VolunteerAllowancePaymentTable.id]
        val subjectId = row[VolunteerAllowancePaymentTable.subjectMemberId]
        val before =
            VolunteerAllowanceSnapshot(
                paymentId = id.toString(),
                subjectMemberId = subjectId.toString(),
                category = row[VolunteerAllowancePaymentTable.category],
                status = row[VolunteerAllowancePaymentTable.status],
                paymentDate = row[VolunteerAllowancePaymentTable.paymentDate],
                amount = row[VolunteerAllowancePaymentTable.amount],
                priorTotalSnapshot = row[VolunteerAllowancePaymentTable.priorTotalSnapshot],
                freeAmountSnapshot = row[VolunteerAllowancePaymentTable.freeAmountSnapshot],
                exceedingAmountSnapshot = row[VolunteerAllowancePaymentTable.exceedingAmountSnapshot],
                capDisclaimerVersion = row[VolunteerAllowancePaymentTable.capDisclaimerVersion],
                postedJournalEntryId = row[VolunteerAllowancePaymentTable.postedJournalEntryId]?.toString(),
                executionError = row[VolunteerAllowancePaymentTable.executionError],
            )
        val refreshed =
            VolunteerAllowancePaymentTable.selectAll().where { VolunteerAllowancePaymentTable.id eq id }.single()
        val after =
            before.copy(
                status = newStatus,
                priorTotalSnapshot = refreshed[VolunteerAllowancePaymentTable.priorTotalSnapshot],
                freeAmountSnapshot = refreshed[VolunteerAllowancePaymentTable.freeAmountSnapshot],
                exceedingAmountSnapshot = refreshed[VolunteerAllowancePaymentTable.exceedingAmountSnapshot],
                capDisclaimerVersion = refreshed[VolunteerAllowancePaymentTable.capDisclaimerVersion],
                postedJournalEntryId = newPostedJournalEntryId?.toString(),
                executionError = newExecutionError,
            )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.VOLUNTEER_ALLOWANCE_PAYMENT,
            entityId = id,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(VolunteerAllowanceSnapshot.serializer(), before),
            after = Json.encodeToString(VolunteerAllowanceSnapshot.serializer(), after),
        )
    }
}

private fun String.toVolunteerAllowanceUuid(paramName: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $paramName: $this") }
