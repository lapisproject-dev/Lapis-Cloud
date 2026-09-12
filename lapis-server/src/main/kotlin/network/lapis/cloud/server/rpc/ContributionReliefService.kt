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
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionExemptionStateDto
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefRequestInput
import network.lapis.cloud.shared.domain.ContributionReliefSnapshot
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionReliefStatusSets
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val RELIEF_DECISION_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val logger = KotlinLogging.logger {}

/** Plausibilitätsdeckel gegen Tippfehler, keine Rechtsvorgabe (siehe Q-5 im Plan). */
private const val MAX_DEFERRAL_DAYS = 730L
private const val MAX_EXEMPTION_BACKDATE_DAYS = 365L
private const val MAX_REASON_TEXT_LENGTH = 500

/**
 * DoS-Deckel (Security-Prüfliste "Pagination-Caps"), gleiche Posture/gleicher Name wie
 * `AuctionService.MAX_LIST_RESULTS` -- [listReliefRequests] hatte ihn ursprünglich NICHT (Review
 * finding: `selectAll()` ohne `limit`/`orderBy` materialisiert die gesamte Tabelle, unbegrenzt
 * wachsend). [listMyReliefRequests] braucht dieselbe Grenze nicht separat, weil es bereits durch
 * `subjectMemberId`/`requestedBy` auf genau EIN Mitglied beschränkt ist.
 *
 * Als reiner Hard-Cap OHNE Seitennavigation (die erste Fassung dieses Fixes) hätte dieser Deckel
 * für jede über die Grenze hinaus wachsende, nie wieder kleiner werdende Teilmenge -- nicht nur die
 * ASC-sortierte "Warteschlange" selbst, sondern z. B. auch `status = EXECUTED`/`REJECTED`/
 * `WITHDRAWN`, die nur per Insert wachsen und nie eine Zeile verlieren -- irgendwann permanent
 * unerreichbare Zeilen erzeugt, egal in welche Richtung sortiert wird (Review finding Runde 4: die
 * ASC-Umstellung allein loeste die Warteschlangen-Unerreichbarkeit, kippte aber die zuvor
 * erreichbaren "neueste zuerst"-Ansichten der monoton wachsenden Terminal-Status ins
 * Unerreichbare). [listReliefRequests] hat deshalb jetzt echte Keyset-Pagination
 * (`afterRequestedAt`/`afterId`, siehe [IContributionReliefService.listReliefRequests] KDoc) --
 * dieser Deckel ist die Seitengröße, kein absoluter Abschneidepunkt mehr. Die Query bleibt ASC
 * (älteste zuerst) sortiert: für die Warteschlangen-Fälle (`reviewDueOnly = true` bzw.
 * `status in ContributionReliefStatusSets.BLOCKS_NEW_REQUEST`) ist das weiterhin die fachlich
 * richtige erste Seite, und für die monoton wachsenden Terminal-Mengen bleibt jede Zeile über
 * fortgesetzte Cursor-Aufrufe erreichbar -- nur eben nicht mehr auf der ersten Seite.
 */
private const val MAX_LIST_RESULTS = 200

/** ANSI SQL `unique_violation` -- see `requestRelief`'s own `catch (e: ExposedSQLException)` KDoc. */
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" (Stundung / Befreiung / Sozialermäßigung).
 *
 * **Zustandsautomat** (lückenlos, jeder Übergang genau einmal implementiert):
 * | von | nach | Methode | Bedingung |
 * |---|---|---|---|
 * | — | REQUESTED | [requestRelief] | Payload-Validierung + kein aktiver Antrag (DB-Index) |
 * | REQUESTED | WITHDRAWN | [withdrawReliefRequest] | Aufrufer = Antragsteller oder Subjekt |
 * | REQUESTED | REJECTED | [decideReliefRequest] (approve=false) | BOARD/ADMIN, nicht Subjekt |
 * | REQUESTED | EXECUTED | [decideReliefRequest] (approve=true) | + Ausführung erfolgreich |
 * | REQUESTED | APPROVED | [decideReliefRequest] (approve=true) | + Ausführung am Recheck gescheitert |
 * | APPROVED | EXECUTED | [retryReliefExecution] | Ausführung erfolgreich |
 * | APPROVED | APPROVED | [retryReliefExecution] | erneut gescheitert, `executionError` aktualisiert |
 * | APPROVED | REJECTED | [decideReliefRequest] (approve=false) | BOARD/ADMIN, nicht Subjekt -- der einzige Ausweg aus einem dauerhaft nicht ausführbaren APPROVED-Antrag (siehe unten) |
 *
 * **Keine Sackgasse**: ohne die letzte Zeile oben gäbe es für einen APPROVED-Antrag, dessen
 * Ausführung dauerhaft am Recheck scheitert (z. B. die Beitragszeile wurde zwischen Genehmigung und
 * Ausführung final bezahlt), keinen Weg mehr in einen Endzustand -- weder [retryReliefExecution]
 * (scheitert für immer am selben Grund) noch [decideReliefRequest] mit `approve=true` (nur aus
 * REQUESTED erlaubt) noch [withdrawReliefRequest] (nur aus REQUESTED erlaubt) hätten einen Ausgang,
 * und der `uq_crr_active_request`-Unique-Index würde dem Mitglied dauerhaft JEDEN weiteren Antrag
 * derselben Art verwehren, ohne Admin-Notausgang. `approve=false` aus APPROVED heraus schließt genau
 * diese Lücke.
 *
 * Jeder andere Aufruf wirft [ConflictException] mit dem tatsächlichen Ist-Status in der Meldung --
 * dieselbe "doesn't exist" ([NotFoundException]) vs. "exists but wrong state" ([ConflictException])
 * Unterscheidung, die `ContributionService.markContributionPaid` bereits etabliert.
 *
 * **Art. 9 DSGVO**: [ContributionReliefRequestInput.reasonText] kann Angaben zu Gesundheit
 * enthalten ("seit der Chemotherapie arbeitsunfähig") -> besondere Kategorie nach Art. 9 Abs. 1
 * DSGVO. [ContributionReliefReason.ILLNESS_DISABILITY] ist ein Satzungs-Tatbestand, keine Diagnose,
 * wird aber genauso geschützt. Rechtsgrundlage: Art. 9 Abs. 2 lit. a (ausdrückliche Einwilligung
 * durch die freiwillige Angabe) i. V. m. Art. 6 Abs. 1 lit. b (Mitgliedschaftsvertrag). Maßnahmen:
 * (1) Kategorie Pflicht, Freitext optional, max. [MAX_REASON_TEXT_LENGTH] Zeichen; (2) sichtbar nur
 * für BOARD/ADMIN und das Subjekt selbst -- [toDto] liefert `reasonText` sonst als `null`, eine
 * Feld-Level-Redaktion, nicht nur ein Endpunkt-Gate; (3) niemals in einem Log oder einer
 * Exception-Message; (4) automatische Redaktion nach 12 Monaten, siehe
 * `network.lapis.cloud.server.contribution.ContributionReliefRedaction`; (5) auf Erasure-Antrag
 * sofort genullt, siehe `network.lapis.cloud.server.dsgvo.ContributionReliefPersonalData`.
 */
class ContributionReliefService(
    private val call: ApplicationCall,
) : IContributionReliefService {
    override suspend fun requestRelief(
        subjectMemberId: String,
        input: ContributionReliefRequestInput,
    ): ContributionReliefRequestDto {
        val current = resolveCurrentMember(call)
        val subjectId = subjectMemberId.toReliefUuid("subjectMemberId")
        // IDOR-Gate: MEMBER nur für sich selbst, BOARD/ADMIN auch im Namen eines fremden Mitglieds.
        if (subjectId != current.memberId) current.requireRole(*RELIEF_DECISION_ROLES)
        val now = DbClock.nowLocalDateTime()
        val reasonText = input.reasonText?.trim()?.ifBlank { null }
        if (reasonText != null && reasonText.length > MAX_REASON_TEXT_LENGTH) {
            throw BadRequestException("reasonText must be at most $MAX_REASON_TEXT_LENGTH characters")
        }

        return transaction {
            val subjectRow =
                MemberTable.selectAll().where { MemberTable.id eq subjectId }.singleOrNull()
                    ?: throw NotFoundException("Member $subjectMemberId not found")
            if (subjectRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer request a contribution relief")
            }
            if (subjectRow[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) {
                throw ConflictException("Cannot request a contribution relief for a member whose membership has ended")
            }

            val payload = validatePayload(input = input, subjectId = subjectId, subjectRow = subjectRow, now = now)

            val id = Uuid.random()
            try {
                ContributionReliefRequestTable.insert {
                    it[ContributionReliefRequestTable.id] = id
                    it[ContributionReliefRequestTable.subjectMemberId] = subjectId
                    it[kind] = input.kind
                    it[status] = ContributionReliefStatus.REQUESTED
                    it[reasonCategory] = input.reasonCategory
                    it[ContributionReliefRequestTable.reasonText] = reasonText
                    it[deferralContributionId] = payload.deferralContributionId
                    it[deferralNewDueDate] = payload.deferralNewDueDate
                    it[exemptionFrom] = payload.exemptionFrom
                    it[exemptionUntil] = payload.exemptionUntil
                    it[reductionTargetTierId] = payload.reductionTargetTierId
                    // reviewDueOn ist nur fuer EXEMPTION/REDUCTION fachlich sinnvoll -- fuer
                    // DEFERRAL wird ein versehentlich mitgesendeter Wert stillschweigend verworfen.
                    it[reviewDueOn] = if (input.kind == ContributionReliefKind.DEFERRAL) null else input.reviewDueOn
                    it[requestedAt] = now
                    it[requestedBy] = current.memberId
                    it[activeRequestKey] = activeKeyOf(subjectId = subjectId, kind = input.kind)
                }
            } catch (e: ExposedSQLException) {
                // K-1 / uq_crr_active_request -- S-10: der Uniqueness-Schutz gegen zwei parallele
                // requestRelief-Aufrufe kommt aus der Datenbank, nicht aus Exposed's check{}-DSL.
                // ONLY that specific unique-index violation is treated as "already has an open
                // request" -- same "sqlState-checked backstop, everything else re-thrown and
                // logged" idiom `EventRegistrationSubmission.submit`/`CrmContactStore.create`
                // already establish. Anything else (a CHECK-constraint violation because
                // validatePayload and a check constraint have drifted apart, an FK violation after a
                // migration) is a genuine bug, not a race -- swallowing it here as a misleading 409
                // with no log line would make it practically undiagnosable (Review finding).
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw ConflictException("Member $subjectMemberId already has an open ${input.kind} relief request")
                } else {
                    logger.error(e) {
                        "ContributionReliefService.requestRelief: unexpected ExposedSQLException inserting relief " +
                            "request $id for member $subjectMemberId (kind=${input.kind}) -- not a unique-active-" +
                            "request violation, re-thrown rather than silently treated as ConflictException."
                    }
                    throw e
                }
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONTRIBUTION_RELIEF_REQUEST,
                entityId = id,
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        ContributionReliefSnapshot.serializer(),
                        snapshotOf(
                            id = id,
                            subjectId = subjectId,
                            input = input,
                            status = ContributionReliefStatus.REQUESTED,
                            payload = payload,
                        ),
                    ),
                occurredAt = now,
            )
            logger.info {
                "contribution relief requested: id=$id kind=${input.kind} subjectMemberId=$subjectId requestedBy=${current.memberId}"
            }
            loadDto(id = id, viewer = current)
        }
    }

    override suspend fun listMyReliefRequests(): List<ContributionReliefRequestDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            ContributionReliefRequestTable
                .selectAll()
                .where {
                    (ContributionReliefRequestTable.subjectMemberId eq current.memberId) or
                        (ContributionReliefRequestTable.requestedBy eq current.memberId)
                }.toList()
                .toDtos(viewer = current)
        }
    }

    override suspend fun withdrawReliefRequest(requestId: String): ContributionReliefRequestDto {
        val current = resolveCurrentMember(call)
        val id = requestId.toReliefUuid("requestId")
        return transaction {
            val row =
                ContributionReliefRequestTable
                    .selectAll()
                    .where { ContributionReliefRequestTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("ContributionReliefRequest $requestId not found")
            val subjectId = row[ContributionReliefRequestTable.subjectMemberId]
            val requestedById = row[ContributionReliefRequestTable.requestedBy]
            // Bewusst KEIN isPrivileged-Bypass (siehe IContributionReliefService KDoc): ein Rückzug
            // durch BOARD/ADMIN würde eine Vorstandsentscheidung als "das Mitglied hat es sich
            // anders überlegt" tarnen und die Entscheidungsspur (Notizpflicht, Vier-Augen-Prinzip)
            // umgehen, die decideReliefRequest erzwingt -- der Weg für BOARD/ADMIN ist
            // decideReliefRequest(approve=false).
            if (current.memberId != subjectId && current.memberId != requestedById) {
                throw ForbiddenException()
            }
            val status = row[ContributionReliefRequestTable.status]
            if (status != ContributionReliefStatus.REQUESTED) {
                throw ConflictException("ContributionReliefRequest $requestId is $status, cannot be withdrawn (only REQUESTED can)")
            }
            ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                it[ContributionReliefRequestTable.status] = ContributionReliefStatus.WITHDRAWN
                it[decidedAt] = DbClock.nowLocalDateTime()
                it[decidedBy] = current.memberId
                it[activeRequestKey] = null
            }
            recordTransition(row = row, actor = current, newStatus = ContributionReliefStatus.WITHDRAWN)
            loadDto(id = id, viewer = current)
        }
    }

    override suspend fun listReliefRequests(
        status: ContributionReliefStatus?,
        kind: ContributionReliefKind?,
        reviewDueOnly: Boolean,
        afterRequestedAt: LocalDateTime?,
        afterId: String?,
    ): List<ContributionReliefRequestDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*RELIEF_DECISION_ROLES)
        // Keyset cursor -- see this method's KDoc / [IContributionReliefService.listReliefRequests]
        // KDoc for the "OFFSET starves growing terminal-status sets, not just the queue" rationale.
        // Both halves required, same "one without the other == no cursor, never an error" idiom
        // SocialNetworkService.listReports's beforeReportedAt/beforeId establishes.
        val cursorId = afterId?.toReliefUuid("afterId")
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (status != null) conditions += (ContributionReliefRequestTable.status eq status)
            if (kind != null) conditions += (ContributionReliefRequestTable.kind eq kind)
            if (reviewDueOnly) {
                // "Due", not merely "has a review date set" -- a REDUCTION with review_due_on far
                // in the future must not show up as due today just because the column is non-null
                // (Review finding: the name promises more than an isNotNull()-only filter delivers).
                val today = DbClock.nowLocalDateTime().date
                conditions += ContributionReliefRequestTable.reviewDueOn.isNotNull()
                conditions += (ContributionReliefRequestTable.reviewDueOn lessEq today)
            }
            if (afterRequestedAt != null && cursorId != null) {
                // Strictly AFTER the last-seen row in ASC order: requestedAt greater, OR equal AND
                // id greater (the tiebreaker matching this query's own orderBy below) -- never
                // re-returns the cursor row itself, and never skips a row sharing its timestamp.
                conditions +=
                    (ContributionReliefRequestTable.requestedAt greater afterRequestedAt) or
                    (
                        (ContributionReliefRequestTable.requestedAt eq afterRequestedAt) and
                            (ContributionReliefRequestTable.id greater cursorId)
                    )
            }
            val query = ContributionReliefRequestTable.selectAll()
            (if (conditions.isEmpty()) query else query.where { conditions.reduce { a, b -> a and b } })
                // Oldest first, NOT newest-first (Review fix: this is a decision/review QUEUE, not a
                // recent-activity feed -- for the queue-shaped filters (`reviewDueOnly = true` /
                // `status in ContributionReliefStatusSets.BLOCKS_NEW_REQUEST`) the longest-waiting
                // requests -- the ones a board queue exists to surface -- must land on the FIRST
                // page, not behind however many pages the rest of the queue takes to page through.
                // `id` is a tiebreaker for rows sharing the same `requestedAt` (same idiom as
                // AuditLogService/AuctionService's own multi-column orderBy calls) -- without it,
                // which rows fall on which side of a page boundary would be undefined whenever two
                // requests share a timestamp, and a page already handed out could reshuffle on the
                // next call.
                .orderBy(
                    ContributionReliefRequestTable.requestedAt to SortOrder.ASC,
                    ContributionReliefRequestTable.id to SortOrder.ASC,
                ).limit(MAX_LIST_RESULTS)
                .toList()
                .toDtos(viewer = current)
        }
    }

    override suspend fun decideReliefRequest(
        requestId: String,
        approve: Boolean,
        note: String?,
    ): ContributionReliefRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*RELIEF_DECISION_ROLES)
        val id = requestId.toReliefUuid("requestId")
        val trimmedNote = note?.trim()?.ifBlank { null }
        if (approve && trimmedNote == null) {
            // S-11: DB-seitig ohnehin per chk_crr_approved_needs_note erzwungen -- hier VORAB als
            // saubere BadRequestException geprueft, damit eine leere Notiz nicht als rohe
            // ExposedSQLException durchschlaegt.
            throw BadRequestException("A decision note is required to approve a contribution relief request")
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                ContributionReliefRequestTable
                    .selectAll()
                    .where { ContributionReliefRequestTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("ContributionReliefRequest $requestId not found")
            val status = row[ContributionReliefRequestTable.status]
            // approve=true nur aus REQUESTED (siehe IContributionReliefService KDoc). approve=false
            // aus jedem Status erlaubt, der noch einen aktiven Antrag darstellt
            // ([ContributionReliefStatusSets.BLOCKS_NEW_REQUEST] == REQUESTED/APPROVED) -- der
            // einzige Ausweg aus einem dauerhaft nicht ausfuehrbaren APPROVED-Antrag (siehe
            // Klassen-KDoc "Keine Sackgasse").
            val allowedFromStatuses =
                if (approve) setOf(ContributionReliefStatus.REQUESTED) else ContributionReliefStatusSets.BLOCKS_NEW_REQUEST
            if (status !in allowedFromStatuses) {
                val verb = if (approve) "approved" else "rejected"
                val allowedDescription = allowedFromStatuses.joinToString(" or ")
                throw ConflictException("ContributionReliefRequest $requestId is $status, cannot be $verb (only $allowedDescription can)")
            }
            val subjectId = row[ContributionReliefRequestTable.subjectMemberId]
            // Vier-Augen-Prinzip: kein Selbst-Genehmigen/-Ablehnen, ohne Ausnahme.
            if (current.memberId == subjectId) throw ForbiddenException()

            if (!approve) {
                ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                    it[ContributionReliefRequestTable.status] = ContributionReliefStatus.REJECTED
                    it[decidedAt] = now
                    it[decidedBy] = current.memberId
                    // GoBD/Review fix: `approve=false` is also reachable from APPROVED (see class
                    // KDoc "Keine Sackgasse"), and an APPROVED row already carries a MANDATORY
                    // decision note (chk_crr_approved_needs_note) that may be the board's only
                    // record of WHY it approved -- ContributionReliefSnapshot deliberately never
                    // carries decisionNote (see ContributionRelief.kt KDoc), so this column is the
                    // sole place that text survives. `note` is OPTIONAL when rejecting (see the
                    // `approve && trimmedNote == null` guard above), so a reject call made WITHOUT a
                    // note must never blank out a note a prior approve already wrote -- only a
                    // caller-supplied non-blank note replaces it.
                    it[decisionNote] = trimmedNote ?: row[ContributionReliefRequestTable.decisionNote]
                    it[activeRequestKey] = null
                    // chk_crr_execution_error_state requires execution_error IS NULL unless
                    // status = APPROVED -- reachable here (not just from REQUESTED) since
                    // approve=false is now also valid from APPROVED (see class KDoc "Keine
                    // Sackgasse"): a request being rejected out of APPROVED-with-executionError
                    // must have that error cleared, or this very UPDATE violates the constraint.
                    it[executionError] = null
                }
                recordTransition(row = row, actor = current, newStatus = ContributionReliefStatus.REJECTED)
                return@transaction loadDto(id = id, viewer = current)
            }

            when (val outcome = ContributionReliefExecution.execute(request = row, actor = current, now = now)) {
                is ReliefExecutionOutcome.Executed -> {
                    ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                        it[ContributionReliefRequestTable.status] = ContributionReliefStatus.EXECUTED
                        it[decidedAt] = now
                        it[decidedBy] = current.memberId
                        it[decisionNote] = trimmedNote
                        it[executedAt] = now
                        it[executionError] = null
                        it[deferralPreviousDueDate] = outcome.previousDueDate
                        it[activeRequestKey] = null
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = ContributionReliefStatus.EXECUTED,
                        executedPreviousDueDate = outcome.previousDueDate,
                    )
                }
                is ReliefExecutionOutcome.Failed -> {
                    // APPROVED-mit-executionError -- KEIN Throw, siehe ContributionReliefExecution
                    // KDoc: ein Throw wuerde die Genehmigung selbst mit zurueckrollen.
                    ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                        it[ContributionReliefRequestTable.status] = ContributionReliefStatus.APPROVED
                        it[decidedAt] = now
                        it[decidedBy] = current.memberId
                        it[decisionNote] = trimmedNote
                        it[executionError] = outcome.reason
                        // activeRequestKey bleibt gesetzt -- APPROVED ist weiterhin in
                        // ContributionReliefStatusSets.BLOCKS_NEW_REQUEST.
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = ContributionReliefStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id = id, viewer = current)
        }
    }

    override suspend fun retryReliefExecution(requestId: String): ContributionReliefRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*RELIEF_DECISION_ROLES)
        val id = requestId.toReliefUuid("requestId")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                ContributionReliefRequestTable
                    .selectAll()
                    .where { ContributionReliefRequestTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("ContributionReliefRequest $requestId not found")
            val status = row[ContributionReliefRequestTable.status]
            if (status != ContributionReliefStatus.APPROVED) {
                throw ConflictException("ContributionReliefRequest $requestId is $status, cannot be retried (only APPROVED can)")
            }
            when (val outcome = ContributionReliefExecution.execute(request = row, actor = current, now = now)) {
                is ReliefExecutionOutcome.Executed -> {
                    ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                        it[ContributionReliefRequestTable.status] = ContributionReliefStatus.EXECUTED
                        it[executedAt] = now
                        it[executionError] = null
                        it[deferralPreviousDueDate] = outcome.previousDueDate
                        it[activeRequestKey] = null
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = ContributionReliefStatus.EXECUTED,
                        executedPreviousDueDate = outcome.previousDueDate,
                    )
                }
                is ReliefExecutionOutcome.Failed -> {
                    ContributionReliefRequestTable.update({ ContributionReliefRequestTable.id eq id }) {
                        it[executionError] = outcome.reason
                    }
                    recordTransition(
                        row = row,
                        actor = current,
                        newStatus = ContributionReliefStatus.APPROVED,
                        newExecutionError = outcome.reason,
                    )
                }
            }
            loadDto(id = id, viewer = current)
        }
    }

    override suspend fun getExemptionState(memberId: String): ContributionExemptionStateDto {
        val current = resolveCurrentMember(call)
        val id = memberId.toReliefUuid("memberId")
        if (!current.isPrivileged && current.role != AccountRole.TREASURER && current.memberId != id) {
            throw ForbiddenException()
        }
        return transaction {
            val row =
                MemberTable.selectAll().where { MemberTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Member $memberId not found")
            ContributionExemptionStateDto(
                memberId = memberId,
                exemptFrom = row[MemberTable.contributionExemptFrom],
                exemptUntil = row[MemberTable.contributionExemptUntil],
                sourceRequestId = row[MemberTable.contributionExemptRequestId]?.toString(),
            )
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────────

    private data class ValidatedPayload(
        val deferralContributionId: Uuid? = null,
        val deferralNewDueDate: LocalDate? = null,
        val exemptionFrom: LocalDate? = null,
        val exemptionUntil: LocalDate? = null,
        val reductionTargetTierId: Uuid? = null,
    )

    private fun validatePayload(
        input: ContributionReliefRequestInput,
        subjectId: Uuid,
        subjectRow: ResultRow,
        now: LocalDateTime,
    ): ValidatedPayload =
        when (input.kind) {
            ContributionReliefKind.DEFERRAL -> {
                requirePayloadExclusivity(input = input, kind = ContributionReliefKind.DEFERRAL)
                val contributionId =
                    input.deferralContributionId?.toReliefUuid("deferralContributionId")
                        ?: throw BadRequestException("deferralContributionId is required for kind=DEFERRAL")
                val newDueDate = input.deferralNewDueDate ?: throw BadRequestException("deferralNewDueDate is required for kind=DEFERRAL")
                val contributionRow =
                    ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.singleOrNull()
                        ?: throw BadRequestException("Contribution $contributionId not found")
                if (contributionRow[ContributionTable.memberId] != subjectId) {
                    throw BadRequestException("Contribution $contributionId does not belong to the subject member")
                }
                if (contributionRow[ContributionTable.status] !in ContributionStatusSets.DEFERRABLE) {
                    throw BadRequestException(
                        "Contribution $contributionId is not deferrable (status=${contributionRow[ContributionTable.status]})",
                    )
                }
                val currentDueDate = contributionRow[ContributionTable.dueDate]
                if (newDueDate <= now.date) throw BadRequestException("deferralNewDueDate must be in the future")
                if (newDueDate <=
                    currentDueDate
                ) {
                    throw BadRequestException("deferralNewDueDate must be after the contribution's current due date")
                }
                if (newDueDate > now.date.plus(MAX_DEFERRAL_DAYS.toInt(), DateTimeUnit.DAY)) {
                    throw BadRequestException("deferralNewDueDate must be at most $MAX_DEFERRAL_DAYS days in the future")
                }
                ValidatedPayload(deferralContributionId = contributionId, deferralNewDueDate = newDueDate)
            }
            ContributionReliefKind.EXEMPTION -> {
                requirePayloadExclusivity(input = input, kind = ContributionReliefKind.EXEMPTION)
                val from = input.exemptionFrom ?: throw BadRequestException("exemptionFrom is required for kind=EXEMPTION")
                val until = input.exemptionUntil
                if (until != null && until < from) throw BadRequestException("exemptionUntil must not be before exemptionFrom")
                if (from < now.date.minus(MAX_EXEMPTION_BACKDATE_DAYS.toInt(), DateTimeUnit.DAY)) {
                    throw BadRequestException("exemptionFrom must not be more than $MAX_EXEMPTION_BACKDATE_DAYS days in the past")
                }
                ValidatedPayload(exemptionFrom = from, exemptionUntil = until)
            }
            ContributionReliefKind.REDUCTION -> {
                requirePayloadExclusivity(input = input, kind = ContributionReliefKind.REDUCTION)
                val tierId =
                    input.reductionTargetTierId?.toReliefUuid("reductionTargetTierId")
                        ?: throw BadRequestException("reductionTargetTierId is required for kind=REDUCTION")
                val tierRow =
                    MembershipTierTable.selectAll().where { MembershipTierTable.id eq tierId }.singleOrNull()
                        ?: throw BadRequestException("MembershipTier $tierId not found")
                if (!tierRow[MembershipTierTable.active]) throw BadRequestException("MembershipTier $tierId is not active")
                val currentTierId = subjectRow[MemberTable.membershipTierId]
                // Review fix: a member with NO tier assignment (`membershipTierId == null`) is a
                // family DEPENDENT billed through the family's payer, never directly (see
                // MemberFamilyService.addFamilyMember: `MembershipTierAssignment.apply(newTierId =
                // null, ...)`, comment "wird ueber den Zahler der Familie abgerechnet, nicht
                // direkt") -- REDUCTION assigning them ANY tier would start billing them directly
                // IN ADDITION to the family invoice, silently breaking that invariant. Both the
                // "already current tier" and "not more expensive" checks below are vacuously true
                // for `currentTierId == null` (neither can ever fire), so this guard must come
                // FIRST and reject outright rather than falling through.
                if (currentTierId == null) {
                    throw BadRequestException(
                        "Member $subjectId has no membership tier of their own (likely a family dependent billed " +
                            "through the family's payer) -- a REDUCTION request is not applicable",
                    )
                }
                if (currentTierId == tierId) throw BadRequestException("Target tier is already the member's current tier")
                val currentAmount =
                    MembershipTierTable
                        .selectAll()
                        .where { MembershipTierTable.id eq currentTierId }
                        .singleOrNull()
                        ?.get(MembershipTierTable.contributionAmount)
                if (currentAmount != null && tierRow[MembershipTierTable.contributionAmount] > currentAmount) {
                    throw BadRequestException("Target tier's contribution amount must not exceed the member's current tier's amount")
                }
                ValidatedPayload(reductionTargetTierId = tierId)
            }
        }

    private fun requirePayloadExclusivity(
        input: ContributionReliefRequestInput,
        kind: ContributionReliefKind,
    ) {
        val deferralFieldsSet = input.deferralContributionId != null || input.deferralNewDueDate != null
        val exemptionFieldsSet = input.exemptionFrom != null || input.exemptionUntil != null
        val reductionFieldsSet = input.reductionTargetTierId != null
        val violatesExclusivity =
            when (kind) {
                ContributionReliefKind.DEFERRAL -> exemptionFieldsSet || reductionFieldsSet
                ContributionReliefKind.EXEMPTION -> deferralFieldsSet || reductionFieldsSet
                ContributionReliefKind.REDUCTION -> deferralFieldsSet || exemptionFieldsSet
            }
        if (violatesExclusivity) throw BadRequestException("kind=$kind must not carry payload fields of another kind")
    }

    // ── Read / mapping helpers ─────────────────────────────────────────────────────────────

    private fun loadDto(
        id: Uuid,
        viewer: CurrentMember,
    ): ContributionReliefRequestDto =
        ContributionReliefRequestTable
            .selectAll()
            .where { ContributionReliefRequestTable.id eq id }
            .single()
            .toDto(viewer = viewer)

    /**
     * @param namesOverride precomputed `memberId -> displayName` map, for batch callers ([toDtos])
     *   that resolved every row's names in ONE query -- `null` (the single-row callers: [loadDto])
     *   makes this look up just this row's own three ids, same as before this became batchable.
     * @param tierNamesOverride precomputed `reductionTargetTierId -> name` map, same batching
     *   rationale.
     */
    private fun ResultRow.toDto(
        viewer: CurrentMember,
        namesOverride: Map<Uuid, String>? = null,
        tierNamesOverride: Map<Uuid, String>? = null,
    ): ContributionReliefRequestDto {
        val subjectId = this[ContributionReliefRequestTable.subjectMemberId]
        val requestedById = this[ContributionReliefRequestTable.requestedBy]
        val decidedById = this[ContributionReliefRequestTable.decidedBy]
        val names =
            namesOverride ?: displayNamesOf(listOfNotNull(subjectId, requestedById, decidedById))
        val tierId = this[ContributionReliefRequestTable.reductionTargetTierId]
        val tierName = tierId?.let { id -> if (tierNamesOverride != null) tierNamesOverride[id] else tierNameOf(id) }
        val canSeeReasonText = viewer.isPrivileged || viewer.memberId == subjectId
        val reasonText = if (canSeeReasonText) this[ContributionReliefRequestTable.reasonText] else null
        return ContributionReliefRequestDto(
            id = this[ContributionReliefRequestTable.id].toString(),
            subjectMemberId = subjectId.toString(),
            subjectDisplayName = names[subjectId] ?: "",
            kind = this[ContributionReliefRequestTable.kind],
            status = this[ContributionReliefRequestTable.status],
            reasonCategory = this[ContributionReliefRequestTable.reasonCategory],
            reasonText = reasonText,
            reasonRedactedAt = this[ContributionReliefRequestTable.reasonRedactedAt],
            deferralContributionId = this[ContributionReliefRequestTable.deferralContributionId]?.toString(),
            deferralNewDueDate = this[ContributionReliefRequestTable.deferralNewDueDate],
            deferralPreviousDueDate = this[ContributionReliefRequestTable.deferralPreviousDueDate],
            exemptionFrom = this[ContributionReliefRequestTable.exemptionFrom],
            exemptionUntil = this[ContributionReliefRequestTable.exemptionUntil],
            reductionTargetTierId = this[ContributionReliefRequestTable.reductionTargetTierId]?.toString(),
            reductionTargetTierName = tierName,
            reviewDueOn = this[ContributionReliefRequestTable.reviewDueOn],
            requestedAt = this[ContributionReliefRequestTable.requestedAt],
            requestedBy = requestedById.toString(),
            requestedByDisplayName = names[requestedById] ?: "",
            decidedBy = decidedById?.toString(),
            decidedByDisplayName = decidedById?.let { names[it] },
            decidedAt = this[ContributionReliefRequestTable.decidedAt],
            decisionNote = this[ContributionReliefRequestTable.decisionNote],
            executedAt = this[ContributionReliefRequestTable.executedAt],
            executionError = this[ContributionReliefRequestTable.executionError],
            effectDescription =
                effectDescriptionOf(
                    kind = this[ContributionReliefRequestTable.kind],
                    newDueDate = this[ContributionReliefRequestTable.deferralNewDueDate],
                    previousDueDate = this[ContributionReliefRequestTable.deferralPreviousDueDate],
                    exemptionFrom = this[ContributionReliefRequestTable.exemptionFrom],
                    exemptionUntil = this[ContributionReliefRequestTable.exemptionUntil],
                    tierName = tierName,
                ),
        )
    }

    /**
     * Batch mapper for [listReliefRequests]/[listMyReliefRequests] -- resolves EVERY row's
     * `displayName`/tier-name lookups in exactly two queries total, instead of [ResultRow.toDto]'s
     * own per-row default resolving them via [displayNamesOf]/[tierNameOf] once per row (an N+1
     * pattern for a result set of N rows, Review finding).
     */
    private fun List<ResultRow>.toDtos(viewer: CurrentMember): List<ContributionReliefRequestDto> {
        if (isEmpty()) return emptyList()
        val memberIds = mutableSetOf<Uuid>()
        val tierIds = mutableSetOf<Uuid>()
        forEach { row ->
            memberIds += row[ContributionReliefRequestTable.subjectMemberId]
            memberIds += row[ContributionReliefRequestTable.requestedBy]
            row[ContributionReliefRequestTable.decidedBy]?.let { memberIds += it }
            row[ContributionReliefRequestTable.reductionTargetTierId]?.let { tierIds += it }
        }
        val names = displayNamesOf(memberIds)
        val tierNames = tierNamesOf(tierIds)
        return map { it.toDto(viewer = viewer, namesOverride = names, tierNamesOverride = tierNames) }
    }

    private fun displayNamesOf(ids: Collection<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList ids.distinct() }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    private fun tierNameOf(tierId: Uuid): String? =
        MembershipTierTable
            .selectAll()
            .where { MembershipTierTable.id eq tierId }
            .singleOrNull()
            ?.get(MembershipTierTable.name)

    private fun tierNamesOf(ids: Collection<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return MembershipTierTable
            .selectAll()
            .where { MembershipTierTable.id inList ids.distinct() }
            .associate { it[MembershipTierTable.id] to it[MembershipTierTable.name] }
    }

    private fun effectDescriptionOf(
        kind: ContributionReliefKind,
        newDueDate: LocalDate?,
        previousDueDate: LocalDate?,
        exemptionFrom: LocalDate?,
        exemptionUntil: LocalDate?,
        tierName: String?,
    ): String =
        when (kind) {
            ContributionReliefKind.DEFERRAL ->
                if (previousDueDate != null) "Fälligkeit $previousDueDate -> $newDueDate" else "Neue Fälligkeit: $newDueDate"
            ContributionReliefKind.EXEMPTION ->
                if (exemptionUntil !=
                    null
                ) {
                    "Beitragsbefreiung $exemptionFrom bis $exemptionUntil"
                } else {
                    "Beitragsbefreiung ab $exemptionFrom (unbefristet)"
                }
            ContributionReliefKind.REDUCTION -> "Neuer Beitragssatz: ${tierName ?: "?"}"
        }

    /**
     * @param executedPreviousDueDate for a DEFERRAL transitioning to [ContributionReliefStatus.EXECUTED]
     *   only: the contribution's due date immediately before this execution overwrote it
     *   ([ReliefExecutionOutcome.Executed.previousDueDate]). [row] is a `forUpdate()` snapshot taken
     *   BEFORE the caller's own `ContributionReliefRequestTable.update` in this same transaction, so
     *   `row[ContributionReliefRequestTable.deferralPreviousDueDate]` never reflects a write the
     *   caller makes after fetching it -- this parameter is the only way the actually-persisted old
     *   due date reaches the audit snapshot for that one transition.
     * @param newExecutionError the `execution_error` value the caller's OWN `update` in this same
     *   transaction persists alongside [newStatus] -- defaults to `null` because every transition
     *   EXCEPT "APPROVED with a failed under-lock recheck" (decideReliefRequest/retryReliefExecution's
     *   own `ReliefExecutionOutcome.Failed` branches) clears it. Those two call sites pass
     *   `outcome.reason` explicitly (Review fix: without this, a repeated failed
     *   [network.lapis.cloud.server.rpc.IContributionReliefService.retryReliefExecution] on the
     *   SAME request wrote `before == after` audit entries -- APPROVED -> APPROVED, nothing else in
     *   [ContributionReliefSnapshot] ever changes for that transition -- indistinguishable from a
     *   no-op to a Kassenprüfer).
     */
    private fun recordTransition(
        row: ResultRow,
        actor: CurrentMember,
        newStatus: ContributionReliefStatus,
        executedPreviousDueDate: LocalDate? = null,
        newExecutionError: String? = null,
    ) {
        val id = row[ContributionReliefRequestTable.id]
        val subjectId = row[ContributionReliefRequestTable.subjectMemberId]
        val before =
            ContributionReliefSnapshot(
                requestId = id.toString(),
                subjectMemberId = subjectId.toString(),
                kind = row[ContributionReliefRequestTable.kind],
                status = row[ContributionReliefRequestTable.status],
                reasonCategory = row[ContributionReliefRequestTable.reasonCategory],
                // Requested-but-not-yet-executed due date -- NOT the previous one (see K-fix note
                // above: reading the wrong column here used to land the requested date in
                // `previousDueDate` while leaving `newDueDate` null).
                newDueDate = row[ContributionReliefRequestTable.deferralNewDueDate],
                exemptFrom = row[ContributionReliefRequestTable.exemptionFrom],
                exemptUntil = row[ContributionReliefRequestTable.exemptionUntil],
                targetTierId = row[ContributionReliefRequestTable.reductionTargetTierId]?.toString(),
                executionError = row[ContributionReliefRequestTable.executionError],
            )
        val after =
            before.copy(
                status = newStatus,
                previousDueDate = executedPreviousDueDate,
                executionError = newExecutionError,
            )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.CONTRIBUTION_RELIEF_REQUEST,
            entityId = id,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(ContributionReliefSnapshot.serializer(), before),
            after = Json.encodeToString(ContributionReliefSnapshot.serializer(), after),
        )
    }

    private fun snapshotOf(
        id: Uuid,
        subjectId: Uuid,
        input: ContributionReliefRequestInput,
        status: ContributionReliefStatus,
        payload: ValidatedPayload,
    ): ContributionReliefSnapshot =
        ContributionReliefSnapshot(
            requestId = id.toString(),
            subjectMemberId = subjectId.toString(),
            kind = input.kind,
            status = status,
            reasonCategory = input.reasonCategory,
            newDueDate = payload.deferralNewDueDate,
            exemptFrom = payload.exemptionFrom,
            exemptUntil = payload.exemptionUntil,
            targetTierId = payload.reductionTargetTierId?.toString(),
        )

    private fun activeKeyOf(
        subjectId: Uuid,
        kind: ContributionReliefKind,
    ): String = "$subjectId:$kind"
}

private fun String.toReliefUuid(paramName: String): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid $paramName: $this") }
