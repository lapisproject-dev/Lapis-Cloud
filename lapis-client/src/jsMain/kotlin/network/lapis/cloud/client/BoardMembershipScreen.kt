package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.BoardMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.TransparenzregisterReminderDto
import network.lapis.cloud.shared.domain.TransparenzregisterReportDto
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.time.Clock

/**
 * Compliance UI wave, screen 5 of 5 -- "Vorstand & Transparenzregister" (board roster + §20 GwG
 * Transparenzregister beneficial-owner report/reminders), per the approved plan + UI/UX-Design-Team
 * review on `feature/compliance-ui`. See plan "Screen 5 -- BoardMembershipScreen.kt" and design
 * decisions D9 (Governance-Committee cross-link + "manual entry, not the primary door" framing) and
 * D8(b) (reminder-resolution honesty banner).
 *
 * Role gating (verified against `BoardMembershipService.kt`'s `BOARD_ADMIN_ROLES` constant): every
 * one of [IBoardMembershipService]'s six methods requires exactly BOARD/ADMIN, uniformly -- unlike
 * `DsgvoComplianceScreen.kt`, there is no narrower write tier to additionally gate inside the
 * screen. `Routing.kt` already gates the whole `/board-membership` route on that same BOARD/ADMIN
 * pair, so (mirroring `AuditLogScreen.kt`'s posture, just for a screen that DOES have write actions)
 * every caller who can reach this screen at all can perform every action on it -- no `canManage`
 * split anywhere below.
 *
 * Cross-link relationship (plan, "Governance-Committee vs BoardMembershipService relationship --
 * RESOLVED"): [BoardMembershipDto] is a parallel, committee-agnostic read-model kept in sync with
 * `CommitteeMembershipDto` rows in the `EXECUTIVE_BOARD` committee via `BoardMembershipEvents` --
 * NOT a separate, independently-entered dataset. A change made on `CommitteesScreen.kt` (co-opting/
 * removing an `EXECUTIVE_BOARD` member, or an `ElectionService.tally` seating) equally shows up
 * here. This screen therefore never presents itself as the only place a board seat changes: the
 * header note and every roster row link back to `CommitteesScreen.kt`'s `EXECUTIVE_BOARD` committee
 * (D9), and the "Manuelle Eintragung" appoint form is captioned as the administrative/supplementary
 * path (co-option/gap-correction outside an election), not competing with the election/co-option
 * paths that also populate this same roster.
 */
fun renderBoardMembershipScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Vorstand & Transparenzregister"))
    root.div(BOARD_MEMBERSHIP_HEADER_NOTE) { addCssClasses("text-muted small") }

    var currentBoard: List<BoardMembershipDto> = emptyList()

    // Forward-reference break for the cross-section refresh (appoint/end mutates the roster AND
    // creates a Transparenzregister reminder, so all three panels below need to refresh together)
    // -- same nullable-function-reference-var idiom `LedgerScreen.kt`'s `refreshJournalFn` already
    // establishes for exactly this "each section needs the others, but Kotlin local functions
    // resolve by lexical order" problem.
    var refreshRosterFn: (() -> Unit)? = null
    var refreshReportFn: (() -> Unit)? = null
    var refreshRemindersFn: (() -> Unit)? = null

    fun refreshAll() {
        refreshRosterFn?.invoke()
        refreshReportFn?.invoke()
        refreshRemindersFn?.invoke()
    }

    // ---- Current roster -----------------------------------------------------------------------
    root.h2(tr("Aktueller Vorstand"))
    val rosterPanel = root.vPanel(spacing = 6)

    fun refreshRoster() {
        rosterPanel.removeAll()
        AppScope.launch {
            val board = guarded { rpcService<IBoardMembershipService>().listCurrentBoard() } ?: return@launch
            currentBoard = board
            if (board.isEmpty()) {
                rosterPanel.p(tr("Aktuell keine Vorstandsmitglieder erfasst."))
                return@launch
            }
            board
                .sortedWith(compareBy({ it.committeeRole.ordinal }, { it.memberDisplayName }))
                .forEach { membership -> renderBoardRow(rosterPanel, membership, ::refreshAll) }
        }
    }
    refreshRosterFn = ::refreshRoster
    refreshRoster()

    // ---- Manual appointment (administrative/supplementary path, see D9 KDoc above) -----------
    root.h2(tr("Manuelle Eintragung"))
    root.div(MANUAL_APPOINTMENT_CAPTION) { addCssClasses("text-muted small") }
    renderAppointmentForm(root, currentBoardProvider = { currentBoard }, onAppointed = ::refreshAll)

    // ---- Transparenzregister report ------------------------------------------------------------
    root.h2(tr("Transparenzregister-Bericht"))
    val reportPanel = root.vPanel(spacing = 6)

    fun refreshReport() {
        reportPanel.removeAll()
        AppScope.launch {
            val report = guarded { rpcService<IBoardMembershipService>().getTransparenzregisterReport() } ?: return@launch
            renderTransparenzregisterReport(reportPanel, report)
        }
    }
    refreshReportFn = ::refreshReport
    refreshReport()

    // ---- Reminders ------------------------------------------------------------------------------
    root.h2(tr("Erinnerungen"))
    // D8(b): unconditional, non-dismissible, above the list itself (X2).
    root.div(TRANSPARENZREGISTER_REMINDER_HONESTY_BANNER) { addCssClasses("alert alert-warning") }

    val reminderFilterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeResolvedCheck = reminderFilterRow.checkBox(label = tr("Erledigte anzeigen"))
    val reminderPanel = root.vPanel(spacing = 6)

    fun refreshReminders() {
        reminderPanel.removeAll()
        AppScope.launch {
            val reminders =
                guarded {
                    rpcService<IBoardMembershipService>().listTransparenzregisterReminders(includeResolvedCheck.value)
                } ?: return@launch
            if (reminders.isEmpty()) {
                reminderPanel.p(tr("Keine Erinnerungen vorhanden."))
                return@launch
            }
            reminders.forEach { reminder -> renderReminderRow(reminderPanel, reminder, ::refreshAll) }
        }
    }
    refreshRemindersFn = ::refreshReminders
    val reminderRefreshButton = reminderFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    reminderRefreshButton.onClick { refreshReminders() }
    refreshReminders()
}

// ================================================================================================
// Current roster
// ================================================================================================

private fun renderBoardRow(
    panel: SimplePanel,
    membership: BoardMembershipDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(membership.memberDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(committeeRoleLabel(membership.committeeRole), committeeRoleColor(membership.committeeRole))
    headerRow.div(gettext("seit %1", membership.startedAt)) { addCssClasses("text-muted small") }

    // D9: every roster row links back to the Governance Committees screen's EXECUTIVE_BOARD
    // committee -- this screen is not the only place a board seat changes.
    row.link(BOARD_COMMITTEE_CROSS_LINK_CAPTION, url = "#${Routes.COMMITTEES}") { addCssClasses("text-muted small") }

    val endButton = row.button(tr("Mitgliedschaft beenden"), style = ButtonStyle.OUTLINEDANGER)
    endButton.onClick {
        endBoardMembershipDialog(membership) { until ->
            AppScope.launch {
                val result = guarded { rpcService<IBoardMembershipService>().endBoardMembership(membership.id, until) }
                if (result != null) {
                    notifyInfo(gettext("Mitgliedschaft von %1 wurde beendet.", membership.memberDisplayName))
                    onChanged()
                }
            }
        }
    }
}

/** Plain confirm-with-a-date-input modal -- same shape as `CommitteesScreen.endCommitteeMembershipDialog`
 * (this is not itself the design's irreversible-data-loss tier -- ending a board membership is an
 * ordinary, reversible-by-re-appointing administrative action, unlike Backup-restore/executeErasure). */
private fun endBoardMembershipDialog(
    membership: BoardMembershipDto,
    onConfirm: (LocalDate) -> Unit,
) {
    val modal = Modal(caption = tr("Vorstandsmitgliedschaft beenden"))
    modal.p(
        gettext(
            "Mitgliedschaft von \"%1\" (%2) wirklich beenden?",
            membership.memberDisplayName,
            committeeRoleLabel(membership.committeeRole),
        ),
    )
    val untilInput = modal.text(value = todayIso(), label = tr("Enddatum (JJJJ-MM-TT)"))
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } },
    )
    modal.addButton(
        Button(tr("Mitgliedschaft beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                val until = runCatching { LocalDate.parse(untilInput.value.orEmpty().trim()) }.getOrNull()
                if (until == null) {
                    errorBox.content = tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben.")
                    errorBox.show()
                    return@onClick
                }
                modal.hide()
                onConfirm(until)
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Manual appointment
// ================================================================================================

/**
 * Member picker sourced from `IMemberService.listMembers()` -- same ACTIVE-filtered directory
 * `CommitteesScreen.renderAddCommitteeMemberForm` already uses.
 *
 * Displaced-incumbent heads-up (plan/design, not itself irreversible-data-loss tier): before
 * submitting an appointment into a single-holder role (CHAIR/DEPUTY_CHAIR/SECRETARY) that already
 * has a DIFFERENT holder, [findDisplacedIncumbent] (computed client-side from the already-loaded
 * roster, purely informational -- the server enforces the actual behavior regardless) surfaces a
 * plain [confirmDialog] naming who gets displaced, before calling [IBoardMembershipService
 * .appointBoardMember].
 */
private fun renderAppointmentForm(
    root: SimplePanel,
    currentBoardProvider: () -> List<BoardMembershipDto>,
    onAppointed: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val roleOptions = CommitteeRole.entries.map { it.name to committeeRoleLabel(it) }
    val memberSelect = panel.select(options = emptyList(), label = tr("Mitglied"))
    val roleSelect = panel.select(options = roleOptions, value = CommitteeRole.MEMBER.name, label = tr("Rolle"))
    val startedAtInput = panel.text(value = todayIso(), label = tr("Seit (JJJJ-MM-TT)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    var members: List<MemberSummaryDto> = emptyList()
    AppScope.launch {
        members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        memberSelect.options = members.map { it.id to it.displayName }
        memberSelect.value = members.firstOrNull()?.id
    }

    val appointButton = panel.button(tr("Vorstandsmitglied ernennen"), style = ButtonStyle.PRIMARY)
    appointButton.onClick {
        errorBox.hide()
        val memberId = memberSelect.value
        val roleValue = roleSelect.value
        val startedAt = runCatching { LocalDate.parse(startedAtInput.value.orEmpty().trim()) }.getOrNull()

        if (memberId == null || roleValue == null || startedAt == null) {
            errorBox.content = tr("Bitte Mitglied, Rolle und ein gültiges Datum (JJJJ-MM-TT) angeben.")
            errorBox.show()
            return@onClick
        }

        val role = CommitteeRole.valueOf(roleValue)
        val input = BoardMembershipInput(memberId = memberId, committeeRole = role, startedAt = startedAt)

        fun doAppoint() {
            appointButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IBoardMembershipService>().appointBoardMember(input) }
                appointButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("%1 wurde als %2 ernannt.", result.memberDisplayName, committeeRoleLabel(role)))
                    onAppointed()
                }
            }
        }

        val displaced = findDisplacedIncumbent(currentBoardProvider(), role, memberId)
        if (displaced != null) {
            confirmDialog(
                title = tr("Vorstandsmitglied ernennen"),
                message = displacedIncumbentWarningText(role, displaced.memberDisplayName),
                confirmLabel = tr("Ernennen"),
                onConfirm = ::doAppoint,
            )
        } else {
            doAppoint()
        }
    }
}

/** [CommitteeRole]s that can only be held by one member at a time -- mirrors the server-side
 * `SINGLE_HOLDER_COMMITTEE_ROLES` set in `BoardMembershipEvents.kt` exactly (CHAIR/DEPUTY_CHAIR/
 * SECRETARY are named seats; MEMBER/ASSESSOR are ordinary board seats several people hold
 * concurrently). This client-side copy is purely informational (see [renderAppointmentForm] KDoc) --
 * the server is the actual enforcement point regardless of what this screen shows beforehand. */
private val SINGLE_HOLDER_COMMITTEE_ROLES = setOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY)

/** Pure predicate covered by [BoardMembershipScreenTest] -- returns the currently active holder of
 * [role] (a different member than [targetMemberId]) if [role] is a single-holder seat and one is
 * currently occupied, or `null` otherwise (multi-holder role, vacant seat, or the target member is
 * already the incumbent -- a re-appointment of the same person into the same role never displaces
 * anyone). */
fun findDisplacedIncumbent(
    currentBoard: List<BoardMembershipDto>,
    role: CommitteeRole,
    targetMemberId: String,
): BoardMembershipDto? {
    if (role !in SINGLE_HOLDER_COMMITTEE_ROLES) return null
    return currentBoard.firstOrNull {
        it.committeeRole == role && it.memberId != targetMemberId && it.endedAt == null
    }
}

/** Exact heads-up copy for a displaced incumbent -- see plan "Displaced-incumbent behavior is not
 * hidden". */
fun displacedIncumbentWarningText(
    role: CommitteeRole,
    currentHolderDisplayName: String,
): String =
    "${committeeRoleLabel(role)} ist derzeit von $currentHolderDisplayName besetzt. Eine Ernennung hier beendet " +
        "deren Amtszeit automatisch."

// ================================================================================================
// Transparenzregister report
// ================================================================================================

/** D9/plan: [BeneficialOwnerDataGapDto.memberDisplayName] gaps are the report's most actionable
 * content -- rendered as its own `alert-warning` block whenever non-empty, never a silent list item
 * (plan: "never a silent list item"). [TransparenzregisterReportDto.currentBoard] is deliberately
 * NOT re-rendered here -- it duplicates the roster section above; only the open-reminder COUNT is
 * surfaced, as a pointer down to the "Erinnerungen" section, which is the real detail view for it. */
private fun renderTransparenzregisterReport(
    panel: SimplePanel,
    report: TransparenzregisterReportDto,
) {
    if (report.beneficialOwnerDataGaps.isNotEmpty()) {
        val box = panel.vPanel(spacing = 4) { addCssClasses("alert alert-warning") }
        box.div(beneficialOwnerGapsSummary(report.beneficialOwnerDataGaps.size)) { addCssClass("fw-bold") }
        report.beneficialOwnerDataGaps.forEach { gap -> box.div(beneficialOwnerGapDetail(gap)) { addCssClasses("small") } }
    } else {
        panel.div(tr("Keine fehlenden Angaben für das Transparenzregister.")) { addCssClasses("text-muted small") }
    }
    if (report.openReminders.isNotEmpty()) {
        panel.div(
            gettext(
                "%1 offene Erinnerung(en) -- siehe Abschnitt \"Erinnerungen\" unten.",
                report.openReminders.size,
            ),
        ) {
            addCssClasses("text-muted small")
        }
    }
}

/** Exact summary line for the beneficial-owner-data-gaps `alert-warning` box. */
fun beneficialOwnerGapsSummary(count: Int): String =
    gettext(
        "Fehlende Angaben für das Transparenzregister: %1 Vorstandsmitglied(er) ohne Geburtsdatum und/oder " +
            "Staatsangehörigkeit.",
        count,
    )

/** Per-gap detail line -- names the SPECIFIC missing field(s), not just "unvollständig" (plan: "each
 * gap listed with the specific missing field(s) named, not just 'incomplete'"). */
fun beneficialOwnerGapDetail(gap: BeneficialOwnerDataGapDto): String {
    val missingFields =
        buildList {
            if (gap.missingDateOfBirth) add(gettext("Geburtsdatum"))
            if (gap.missingNationality) add(gettext("Staatsangehörigkeit"))
        }
    return gettext(
        "%1 (%2): fehlt %3",
        gap.memberDisplayName,
        committeeRoleLabel(gap.committeeRole),
        missingFields.joinToString(", "),
    )
}

// ================================================================================================
// Reminders
// ================================================================================================

private fun renderReminderRow(
    panel: SimplePanel,
    reminder: TransparenzregisterReminderDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(boardChangeTypeLabel(reminder.changeType), boardChangeTypeColor(reminder.changeType))
    headerRow.typeBadge(committeeRoleLabel(reminder.committeeRole), committeeRoleColor(reminder.committeeRole))
    headerRow.div(reminder.memberDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(reminderResolutionLabel(reminder.resolved), reminderResolutionColor(reminder.resolved))

    row.div(gettext("Ausgelöst am: %1", reminder.triggeredAt)) { addCssClasses("text-muted small") }

    if (reminder.resolved) {
        row.div(resolvedCaption(reminder)) { addCssClasses("text-muted small") }
    } else {
        // D8(b): the button labels the exact claim being made, not a generic "Erledigt"/"Bestätigen".
        val resolveButton = row.button(RESOLVE_REMINDER_BUTTON_LABEL, style = ButtonStyle.PRIMARY)
        resolveButton.onClick {
            resolveButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IBoardMembershipService>().resolveTransparenzregisterReminder(reminder.id) }
                resolveButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Erinnerung wurde bestätigt."))
                    onChanged()
                }
            }
        }
    }
}

/** `statusBadge` grammar (filled) -- whether a reminder is still awaiting the manual acknowledgement
 * D8(b)'s banner describes, or has already received it. Deliberately a plain resolved/open flag,
 * not reusing [boardChangeTypeColor]'s scale -- these are two different questions ("what changed"
 * vs. "has the register been updated for it"). */
fun reminderResolutionLabel(resolved: Boolean): String = if (resolved) "Erledigt" else "Offen"

fun reminderResolutionColor(resolved: Boolean): String = if (resolved) "success" else "warning"

/** Resolved-row caption naming who acknowledged the register update, and when -- falls back to the
 * raw resolver id if no display name resolved (defensive; every resolver is a real BOARD/ADMIN
 * member today), same "never fabricate a name, show the raw id honestly" posture
 * `DsgvoRightsScreen.kt`'s audit-actor display already establishes. */
fun resolvedCaption(reminder: TransparenzregisterReminderDto): String {
    val who = reminder.resolvedByDisplayName ?: reminder.resolvedById ?: "unbekannt"
    return "Bestätigt von $who am ${reminder.resolvedAt}"
}

/** Today's date as `JJJJ-MM-TT` -- mirrors `CommitteesScreen.todayIso`'s own `kotlin.time.Clock`
 * idiom (this codebase's pinned kotlinx-datetime version only extends the stdlib clock). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

// ================================================================================================
// Pure copy constants -- covered by BoardMembershipScreenTest.kt
// ================================================================================================

/** D9: screen-header note directly under the h1, explaining the roster's dual-source nature before
 * any confusion can start. */
const val BOARD_MEMBERSHIP_HEADER_NOTE =
    "Dieses Vorstandsregister wird automatisch aus Wahlen und aus Gremienmitgliedschaften im Gremium " +
        "„EXECUTIVE_BOARD\" gespeist -- Änderungen dort erscheinen hier ebenso wie Änderungen, die Sie direkt " +
        "auf dieser Seite vornehmen."

/** D9: per-row cross-link caption back to the Governance Committees screen. */
const val BOARD_COMMITTEE_CROSS_LINK_CAPTION = "Sitz im Gremium: EXECUTIVE_BOARD (siehe Gremien)"

/** D9: frames the direct "appoint" action as administrative/supplementary, not competing with the
 * election/co-option paths that also populate this same roster. */
const val MANUAL_APPOINTMENT_CAPTION =
    "Für reguläre Vorstandswahlen nutzen Sie die Wahlfunktion unter „Anträge\". Diese Eintragung ist für Fälle " +
        "gedacht, in denen ein Sitz außerhalb einer Wahl besetzt oder korrigiert werden muss (z. B. Kooptierung, " +
        "Lückenkorrektur)."

/** D8(b): unconditional, non-dismissible honesty banner above the reminder list (X2) -- "Erledigt"
 * means a human acknowledgement, NEVER a verified filing; this system cannot check
 * transparenzregister.de and files nothing automatically. */
const val TRANSPARENZREGISTER_REMINDER_HONESTY_BANNER =
    "„Erledigt\" bedeutet: eine Person mit Vorstands-/Admin-Rolle hat bestätigt, das echte Transparenzregister " +
        "(transparenzregister.de) selbst aktualisiert zu haben. Dieses System kann die tatsächliche Meldung " +
        "nicht prüfen und meldet nichts automatisch."

/** D8(b): the resolve button states the exact claim being made by clicking it, not a generic
 * "Erledigt"/"Bestätigen". */
const val RESOLVE_REMINDER_BUTTON_LABEL = "Ich habe das Register aktualisiert"
