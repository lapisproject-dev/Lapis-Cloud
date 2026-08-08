package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AgendaItemInput
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceInput
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.PostalInvitationDispatchInput
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IPostalMailService

/**
 * Governance UI wave, screen 2 of 3 -- "Sitzungen" (Meetings: agenda, attendance, quorum,
 * resolutions, protocol draft), per the approved plan + UI/UX-Design-Team review on
 * `feature/governance-ui`. Mirrors `CommitteesScreen.kt`'s list-then-detail shape (list at the
 * top, a single detail panel below that re-renders per selection, a creation form at the bottom),
 * plus this screen's own multi-section detail view -- `getMeetingDetail` already returns
 * meeting + agenda + attendance + resolutions + quorum together in one [MeetingDetailDto], so the
 * detail panel renders all of those as sub-sections of one load rather than four separate fetches.
 *
 * Role gating (plan §4, verified against `GovernanceService.kt`): `createMeeting`/
 * `updateMeetingStatus`/`addAgendaItem`/`removeAgendaItem`/`recordAttendance`/`recordResolution`
 * all call `current.canRecordForMeeting(committeeId)` -- global BOARD/ADMIN **or** that specific
 * Committee's CHAIR/DEPUTY_CHAIR/SECRETARY, unlike `CommitteesScreen`'s strictly-BOARD/ADMIN-only
 * `createCommittee`/`addCommitteeMember`. This is a genuinely per-Committee check the UI cannot
 * compute from `SessionInfoDto` alone -- see [GovernanceAuthzUi.canRecordForMeeting], which every
 * privileged control on this screen goes through, fed by that Committee's own active roster
 * (`listCommitteeMembers(committeeId, activeOnly = true)`).
 *
 * Read-only reach (`listMeetings`/`getMeetingDetail`/`getAttendance`/`checkQuorum`/
 * `listResolutions`/`generateProtocolDraft`) requires no role at all server-side -- every
 * authenticated member can browse every Committee's meetings and generate its protocol draft.
 * The "Protokoll" section reflects this plainly rather than inventing a client-side-only
 * restriction the backend doesn't enforce (design decision D4) -- see [renderProtocolSection].
 *
 * Attendance/agenda-presenter member pickers (design: "per-eligible-member status recording")
 * are sourced from the same eligibility rule `computeQuorum`/`checkQuorum` use server-side
 * (`CommitteeEligibility.eligibleMemberIds`): for a [CommitteeType.GENERAL_ASSEMBLY] Committee,
 * every [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] member
 * (`IMemberService.listMembers()`, current status -- the server's own General-Assembly path is
 * date-blind too, see that function's KDoc); for any other Committee, that Committee's active
 * roster (`listCommitteeMembers`, reused for the [GovernanceAuthzUi] check above, so no extra
 * round trip). Not a perfect mirror of `eligibleMemberIds`'s date-scoped-to-`scheduledDate`
 * Committee branch (this uses "active as of today", not "active as of the meeting's date") -- an
 * accepted UI-picker simplification for typical near-term meetings, exactly the same simplification
 * `CommitteesScreen`'s own roster view already makes.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decisions D5/D6: [renderEinladungSection] adds a free
 * PDF download ([MailmergeHttp.submitEinladungPdfDownload], `GOVERNANCE_DOC_ROLES`) and a real
 * Letterxpress postal dispatch ([IPostalMailService.dispatchEinladungByPost],
 * `GOVERNANCE_DISPATCH_ROLES`) entry point -- both strictly BOARD/ADMIN, narrower than this screen's
 * own per-Committee `canManage` (which also admits CHAIR/DEPUTY_CHAIR/SECRETARY). The section only
 * renders at all when `canManage` is true (same gate as Agenda/Attendance/Resolution editing); a
 * Committee officer who can manage the meeting but is not globally BOARD/ADMIN sees a plain-language
 * explanation instead of a vanished control. Recipients are sourced from the same `eligibleMembers`
 * this screen already computes for attendance -- no new RPC call needed.
 */
fun renderMeetingsScreen(container: SimplePanel) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val currentMemberId = session.memberId
    val isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1("Sitzungen")

    root.h2("Übersicht")
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val committeeFilterSelect = filterRow.select(options = listOf("" to "Alle Gremien"), value = "", label = "Gremium")
    val statusFilterOptions = listOf("" to "Alle Status") + MeetingStatus.entries.map { it.name to meetingStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = "Status")
    val refreshButton = filterRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val meetingListPanel = root.vPanel(spacing = 6)

    root.h2("Details")
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p("Sitzung oben auswählen, um Details zu sehen.")

    root.h2("Neue Sitzung anlegen")
    val creationPanel = root.vPanel(spacing = 6)
    creationPanel.p("Wird geladen …") { addCssClasses("text-muted small") }

    var committees: List<CommitteeDto> = emptyList()
    var currentDetailMeetingId: String? = null

    fun refreshDetail() {
        val meetingId = currentDetailMeetingId ?: return
        renderMeetingDetail(detailPanel, meetingId, currentMemberId, isBoardOrAdmin, committees) {
            refreshDetail()
        }
    }

    fun refreshMeetings() {
        meetingListPanel.removeAll()
        AppScope.launch {
            val committeeId = committeeFilterSelect.value?.takeIf { it.isNotBlank() }
            val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { MeetingStatus.valueOf(it) }
            val meetings = guarded { rpcService<IGovernanceService>().listMeetings(committeeId, status) } ?: return@launch
            if (meetings.isEmpty()) {
                meetingListPanel.p("Noch keine Sitzungen vorhanden.")
                return@launch
            }
            meetings.forEach { meeting ->
                renderMeetingRow(meetingListPanel, meeting) { selected ->
                    currentDetailMeetingId = selected.id
                    refreshDetail()
                }
            }
        }
    }

    refreshButton.onClick { refreshMeetings() }

    AppScope.launch {
        committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = false) } ?: emptyList()
        committeeFilterSelect.options = listOf("" to "Alle Gremien") + committees.map { it.id to it.name }
        committeeFilterSelect.value = ""
        refreshMeetings()

        // Which Committees the current member may create/manage Meetings for -- BOARD/ADMIN may
        // do so for every active Committee; anyone else only for a Committee where they hold
        // CHAIR/DEPUTY_CHAIR/SECRETARY, computed per-Committee via GovernanceAuthzUi (plan §4/§1 --
        // this really is a per-Committee check, not derivable from SessionInfoDto alone).
        val activeCommittees = committees.filter { it.active }
        val manageableCommittees =
            if (isBoardOrAdmin) {
                activeCommittees
            } else {
                val result = mutableListOf<CommitteeDto>()
                for (committee in activeCommittees) {
                    val roster =
                        guarded {
                            rpcService<IGovernanceService>().listCommitteeMembers(committee.id, activeOnly = true)
                        } ?: emptyList()
                    if (GovernanceAuthzUi.canRecordForMeeting(false, currentMemberId, committee.id, roster)) {
                        result.add(committee)
                    }
                }
                result
            }

        creationPanel.removeAll()
        if (manageableCommittees.isEmpty()) {
            creationPanel.p("Keine Berechtigung, neue Sitzungen anzulegen.")
        } else {
            val memberCandidates = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
            renderMeetingCreationForm(creationPanel, manageableCommittees, memberCandidates) { refreshMeetings() }
        }
    }
}

private fun renderMeetingRow(
    panel: SimplePanel,
    meeting: MeetingDto,
    onSelect: (MeetingDto) -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(meeting.title) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))
    row.div("${meeting.committeeName} · ${meetingFormatLabel(meeting.format)} · ${meeting.scheduledAt}") {
        addCssClasses("text-muted small")
    }
    meeting.location?.takeIf { it.isNotBlank() }?.let { location ->
        row.div("Ort: $location") { addCssClasses("text-muted small") }
    }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.button("Details anzeigen", style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(meeting) }
}

private fun renderMeetingCreationForm(
    panel: SimplePanel,
    committees: List<CommitteeDto>,
    memberCandidates: List<MemberSummaryDto>,
    onCreated: () -> Unit,
) {
    val committeeOptions = committees.map { it.id to it.name }
    val committeeSelect = panel.select(options = committeeOptions, value = committees.firstOrNull()?.id, label = "Gremium")
    val titleInput = panel.text(label = "Titel")
    val scheduledAtInput = panel.text(label = "Termin (JJJJ-MM-TTTHH:MM, z. B. 2026-08-15T18:00)")
    val locationInput = panel.text(label = "Ort (optional)")
    val formatOptions = MeetingFormat.entries.map { it.name to meetingFormatLabel(it) }
    val formatSelect = panel.select(options = formatOptions, value = MeetingFormat.IN_PERSON.name, label = "Format")
    val memberOptions = listOf("" to "-- keine --") + memberCandidates.map { it.id to it.displayName }
    val chairSelect = panel.select(options = memberOptions, value = "", label = "Sitzungsleitung (optional)")
    val minuteTakerSelect = panel.select(options = memberOptions, value = "", label = "Protokollführung (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button("Sitzung anlegen", style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val committeeId = committeeSelect.value
        val title = titleInput.value.orEmpty().trim()
        val scheduledAt = runCatching { LocalDateTime.parse(scheduledAtInput.value.orEmpty().trim()) }.getOrNull()
        val location = locationInput.value?.trim()?.takeIf { it.isNotBlank() }
        val formatValue = formatSelect.value
        val chairId = chairSelect.value?.takeIf { it.isNotBlank() }
        val minuteTakerId = minuteTakerSelect.value?.takeIf { it.isNotBlank() }

        if (committeeId == null || !Validation.isNonBlank(title) || scheduledAt == null || formatValue == null) {
            errorBox.content = "Bitte Gremium, Titel, einen gültigen Termin (JJJJ-MM-TTTHH:MM) und ein Format angeben."
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().createMeeting(
                        MeetingInput(
                            committeeId = committeeId,
                            title = title,
                            scheduledAt = scheduledAt,
                            location = location,
                            format = MeetingFormat.valueOf(formatValue),
                            chairMemberId = chairId,
                            minuteTakerMemberId = minuteTakerId,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("Sitzung \"$title\" wurde angelegt.")
                titleInput.value = null
                scheduledAtInput.value = null
                locationInput.value = null
                onCreated()
            }
        }
    }
}

/**
 * Loads [MeetingDetailDto] plus this specific meeting's Committee roster (for the
 * [GovernanceAuthzUi] check and, for a non-General-Assembly Committee, the eligible-member
 * picker source) and, for a General-Assembly Committee, the full AKTIV member directory --
 * see file KDoc. [committees] is the already-loaded list from [renderMeetingsScreen], reused to
 * look up the meeting's own [CommitteeDto] (for its [CommitteeType]) without an extra round trip.
 */
private fun renderMeetingDetail(
    panel: SimplePanel,
    meetingId: String,
    currentMemberId: String,
    isBoardOrAdmin: Boolean,
    committees: List<CommitteeDto>,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    panel.p("Wird geladen …")
    AppScope.launch {
        val detail = guarded { rpcService<IGovernanceService>().getMeetingDetail(meetingId) } ?: return@launch
        val roster =
            guarded {
                rpcService<IGovernanceService>().listCommitteeMembers(detail.meeting.committeeId, activeOnly = true)
            } ?: emptyList()
        val canManage =
            GovernanceAuthzUi.canRecordForMeeting(isBoardOrAdmin, currentMemberId, detail.meeting.committeeId, roster)
        val committee = committees.find { it.id == detail.meeting.committeeId }
        val eligibleMembers: List<MemberSummaryDto> =
            if (committee?.type == CommitteeType.GENERAL_ASSEMBLY) {
                guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
            } else {
                roster.map { MemberSummaryDto(id = it.memberId, displayName = it.memberDisplayName) }
            }

        panel.removeAll()
        renderMeetingMeta(panel, detail.meeting, canManage, onChanged)
        renderEinladungSection(panel, detail.meeting, canManage, isBoardOrAdmin, eligibleMembers)
        renderAgendaSection(panel, detail, canManage, eligibleMembers, onChanged)
        renderAttendanceSection(panel, detail, canManage, eligibleMembers, onChanged)
        renderResolutionSection(panel, detail, canManage, onChanged)
        renderProtocolSection(panel, detail.meeting.id)
    }
}

private fun renderMeetingMeta(
    panel: SimplePanel,
    meeting: MeetingDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.h2(meeting.title) { addCssClasses("h4 flex-grow-1") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))

    panel.div("Gremium: ${meeting.committeeName} · ${meetingFormatLabel(meeting.format)} · Termin: ${meeting.scheduledAt}") {
        addCssClasses("text-muted small")
    }
    meeting.location?.takeIf { it.isNotBlank() }?.let { location ->
        panel.div("Ort: $location") { addCssClasses("text-muted small") }
    }
    meeting.chairDisplayName?.let { chair -> panel.div("Sitzungsleitung: $chair") { addCssClasses("text-muted small") } }
    meeting.minuteTakerDisplayName?.let { taker -> panel.div("Protokollführung: $taker") { addCssClasses("text-muted small") } }

    // Status transitions: only meaningful from PLANNED. HELD is a forward/completing transition
    // (no confirm dialog, mirrors `updateMeetingStatus -> HELD` being unwrapped per the design
    // review D7); CANCELLED is destructive and gets the real confirm step, per the same D7 list.
    if (canManage && meeting.status == MeetingStatus.PLANNED) {
        val actionRow = panel.hPanel(spacing = 8)
        val heldButton = actionRow.button("Als durchgeführt markieren", style = ButtonStyle.SUCCESS)
        heldButton.onClick {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().updateMeetingStatus(meeting.id, MeetingStatus.HELD) }
                if (result != null) {
                    notifySuccess("Sitzung als durchgeführt markiert.")
                    onChanged()
                }
            }
        }
        val cancelButton = actionRow.button("Absagen", style = ButtonStyle.OUTLINEDANGER)
        cancelButton.onClick {
            confirmDialog(
                title = "Sitzung absagen",
                message = "\"${meeting.title}\" wirklich absagen?",
                confirmLabel = "Absagen",
            ) {
                AppScope.launch {
                    val result =
                        guarded { rpcService<IGovernanceService>().updateMeetingStatus(meeting.id, MeetingStatus.CANCELLED) }
                    if (result != null) {
                        notifyInfo("Sitzung abgesagt.")
                        onChanged()
                    }
                }
            }
        }
    }
}

/**
 * D6: gated on the meeting-level `canManage` (same gate as Agenda/Attendance/Resolution editing) --
 * a plain member with no management role over this meeting never sees this section at all. Inside
 * that gate, both Einladung actions (free PDF and postal dispatch) are further narrowed to global
 * BOARD/ADMIN, which is strictly narrower than `canManage` (a per-Committee CHAIR/DEPUTY_CHAIR/
 * SECRETARY also passes `canManage` but not this narrower check) -- see file KDoc.
 *
 * [meeting.location] is nullable ([MeetingDto.location]) but [PostalInvitationDispatchInput.location]
 * is not -- the form's location field is therefore required regardless of whether the meeting
 * already has one on file, and both submit handlers validate it non-blank before proceeding.
 */
private fun renderEinladungSection(
    panel: SimplePanel,
    meeting: MeetingDto,
    canManage: Boolean,
    isBoardOrAdminGlobal: Boolean,
    eligibleMembers: List<MemberSummaryDto>,
) {
    if (!canManage) return
    panel.h2("Einladung") { addCssClass("h5") }

    if (!isBoardOrAdminGlobal) {
        panel.div(
            "Der Versand von Einladungen ist Vorstand und Administration vorbehalten -- als " +
                "Sitzungsleitung/Protokollführung dieses Gremiums können Sie die Sitzung verwalten, aber " +
                "keine Einladungen verschicken.",
        ) { addCssClasses("text-muted small") }
        return
    }

    panel.div(
        "Versand einer Einladung an ausgewählte Mitglieder -- als PDF zum Herunterladen (kostenlos, bis zu " +
            "1.000 Empfänger) oder per Post (kostenpflichtig über Letterxpress, bis zu 50 Empfänger, " +
            "erfordert eine vollständige Anschrift jedes Empfängers).",
    ) { addCssClasses("text-muted small") }

    if (eligibleMembers.isEmpty()) {
        panel.p("Keine berechtigten Mitglieder gefunden.")
        return
    }

    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val titleInput = formPanel.text(value = meeting.title, label = "Titel")
    val eventDateTimeInput = formPanel.text(value = meeting.scheduledAt.toString(), label = "Termin (JJJJ-MM-TTTHH:MM)")
    val locationInput = formPanel.text(value = meeting.location.orEmpty(), label = "Ort")
    val bodyTextInput = formPanel.textArea(label = "Einladungstext", rows = 4)

    formPanel.p("Empfänger") { addCssClasses("fw-bold mb-1") }
    val recipientsPanel = formPanel.vPanel(spacing = 2) { addCssClasses("border rounded p-2") }
    val quickToggleRow = recipientsPanel.hPanel(spacing = 8)
    val selectAllLink = quickToggleRow.link("Alle auswählen", url = "javascript:void(0)")
    val deselectAllLink = quickToggleRow.link("Alle abwählen", url = "javascript:void(0)")
    // Unchecked by default -- a costly/PII-sharing action must never default to "everyone selected".
    val checkboxesByMember =
        eligibleMembers.associateWith { member -> recipientsPanel.checkBox(label = member.displayName) }
    selectAllLink.onClick { checkboxesByMember.values.forEach { checkbox -> checkbox.value = true } }
    deselectAllLink.onClick { checkboxesByMember.values.forEach { checkbox -> checkbox.value = false } }

    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val outcomePanel = formPanel.vPanel(spacing = 4)

    fun selectedRecipients(): List<MemberSummaryDto> = checkboxesByMember.filterValues { it.value }.keys.toList()

    val actionRow = formPanel.hPanel(spacing = 8)
    val downloadButton = actionRow.button("Als PDF herunterladen", style = ButtonStyle.OUTLINEPRIMARY)
    downloadButton.onClick {
        errorBox.hide()
        val recipients = selectedRecipients()
        val title = titleInput.value.orEmpty().trim()
        val eventDateTimeText = eventDateTimeInput.value.orEmpty().trim()
        val location = locationInput.value.orEmpty().trim()
        val bodyText = bodyTextInput.value.orEmpty().trim()
        val eventDateTime = runCatching { LocalDateTime.parse(eventDateTimeText) }.getOrNull()
        if (
            recipients.isEmpty() ||
            !Validation.isNonBlank(title) ||
            eventDateTime == null ||
            !Validation.isNonBlank(location) ||
            !Validation.isNonBlank(bodyText)
        ) {
            errorBox.content =
                "Bitte mindestens eine Empfängerin/einen Empfänger sowie Titel, einen gültigen Termin " +
                "(JJJJ-MM-TTTHH:MM), Ort und Einladungstext angeben."
            errorBox.show()
            return@onClick
        }
        MailmergeHttp.submitEinladungPdfDownload(title, eventDateTime, location, bodyText, recipients.map { it.id })
    }

    // D7: the postal-dispatch button is fetched-and-populated asynchronously (whether
    // postalMailEnabled is true) -- the free-PDF download button above is never gated by this flag,
    // since it never touches Letterxpress.
    val postalActionPanel = formPanel.vPanel(spacing = 4)
    AppScope.launch {
        if (isPostalMailEnabled()) {
            val postalButton = postalActionPanel.button("Per Post versenden", style = ButtonStyle.OUTLINEDANGER)
            postalButton.onClick {
                errorBox.hide()
                val recipients = selectedRecipients()
                val title = titleInput.value.orEmpty().trim()
                val eventDateTimeText = eventDateTimeInput.value.orEmpty().trim()
                val location = locationInput.value.orEmpty().trim()
                val bodyText = bodyTextInput.value.orEmpty().trim()
                val eventDateTime = runCatching { LocalDateTime.parse(eventDateTimeText) }.getOrNull()
                if (
                    recipients.isEmpty() ||
                    !Validation.isNonBlank(title) ||
                    eventDateTime == null ||
                    !Validation.isNonBlank(location) ||
                    !Validation.isNonBlank(bodyText)
                ) {
                    errorBox.content =
                        "Bitte mindestens eine Empfängerin/einen Empfänger sowie Titel, einen gültigen Termin " +
                        "(JJJJ-MM-TTTHH:MM), Ort und Einladungstext angeben."
                    errorBox.show()
                    return@onClick
                }
                if (recipients.size > MAX_POSTAL_INVITATION_RECIPIENTS_UI) {
                    errorBox.content =
                        "Postversand ist auf $MAX_POSTAL_INVITATION_RECIPIENTS_UI Empfänger begrenzt (aktuell " +
                        "ausgewählt: ${recipients.size}) -- für mehr Empfänger bitte das PDF herunterladen und " +
                        "selbst verteilen."
                    errorBox.show()
                    return@onClick
                }

                postalEinladungDispatchConfirmDialog(recipients.map { it.displayName }) {
                    postalButton.disabled = true
                    outcomePanel.removeAll()
                    AppScope.launch {
                        val results =
                            guarded {
                                rpcService<IPostalMailService>().dispatchEinladungByPost(
                                    PostalInvitationDispatchInput(
                                        title = title,
                                        eventDateTime = eventDateTime,
                                        location = location,
                                        bodyText = bodyText,
                                        recipientMemberIds = recipients.map { it.id },
                                    ),
                                )
                            }
                        postalButton.disabled = false
                        if (results != null) {
                            val sentCount = results.count { it.status == PostalDeliveryStatus.SENT }
                            if (sentCount == results.size) {
                                notifySuccess("$sentCount von ${results.size} Briefen erfolgreich übergeben.")
                            } else {
                                notifyError("${results.size - sentCount} von ${results.size} Briefen fehlgeschlagen -- Details unten.")
                            }
                            results.forEach { log -> outcomePanel.renderPostalDispatchOutcome(log) }
                        }
                    }
                }
            }
        } else {
            postalActionPanel.postalMailDisabledNotice()
        }
    }
}

/**
 * Client-side pre-check mirroring `PostalMailService`'s `MAX_POSTAL_INVITATION_RECIPIENTS` (50) --
 * the server remains authoritative; this only avoids submitting a request the caller can already
 * see will be rejected.
 */
private const val MAX_POSTAL_INVITATION_RECIPIENTS_UI = 50

private fun renderAgendaSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    eligibleMembers: List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    panel.h2("Tagesordnung") { addCssClass("h5") }
    val agenda = detail.agenda.sortedBy { it.position }
    if (agenda.isEmpty()) {
        panel.p("Noch keine Tagesordnungspunkte.")
    } else {
        agenda.forEach { item ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            val description = if (item.description.isNullOrBlank()) "" else " -- ${item.description}"
            row.div("${item.position}. ${item.title}$description") { addCssClasses("flex-grow-1") }
            item.presenterDisplayName?.let { presenter ->
                row.div("Vortragend: $presenter") { addCssClasses("text-muted small") }
            }
            if (canManage) {
                val removeButton = row.button("Entfernen", style = ButtonStyle.OUTLINEDANGER)
                removeButton.onClick {
                    AppScope.launch {
                        val result = guarded { rpcService<IGovernanceService>().removeAgendaItem(item.id) }
                        if (result != null) {
                            notifySuccess("Tagesordnungspunkt entfernt.")
                            onChanged()
                        }
                    }
                }
            }
        }
    }

    if (canManage) {
        renderAddAgendaItemForm(panel, detail.meeting.id, nextAgendaPosition(agenda), eligibleMembers, onChanged)
    }
}

private fun nextAgendaPosition(agenda: List<AgendaItemDto>): Int = (agenda.maxOfOrNull { it.position } ?: 0) + 1

private fun renderAddAgendaItemForm(
    panel: SimplePanel,
    meetingId: String,
    nextPosition: Int,
    eligibleMembers: List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p("Tagesordnungspunkt hinzufügen") { addCssClass("fw-bold") }
    val positionInput = formPanel.text(value = nextPosition.toString(), label = "Position")
    val titleInput = formPanel.text(label = "Titel")
    val descriptionInput = formPanel.text(label = "Beschreibung (optional)")
    val presenterOptions = listOf("" to "-- kein --") + eligibleMembers.map { it.id to it.displayName }
    val presenterSelect = formPanel.select(options = presenterOptions, value = "", label = "Vortragend (optional)")
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val addButton = formPanel.button("Hinzufügen", style = ButtonStyle.OUTLINEPRIMARY)
    addButton.onClick {
        errorBox.hide()
        val position =
            positionInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value?.trim()?.takeIf { it.isNotBlank() }
        val presenterId = presenterSelect.value?.takeIf { it.isNotBlank() }

        if (position == null || !Validation.isNonBlank(title)) {
            errorBox.content = "Bitte eine gültige Position und einen Titel angeben."
            errorBox.show()
            return@onClick
        }

        addButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().addAgendaItem(
                        meetingId,
                        AgendaItemInput(
                            position = position,
                            title = title,
                            description = description,
                            presenterMemberId = presenterId,
                        ),
                    )
                }
            addButton.disabled = false
            if (result != null) {
                notifySuccess("Tagesordnungspunkt \"$title\" hinzugefügt.")
                onChanged()
            }
        }
    }
}

private fun renderAttendanceSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    eligibleMembers: List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    panel.h2("Anwesenheit") { addCssClass("h5") }
    if (detail.attendance.isEmpty()) {
        panel.p("Noch keine Anwesenheit erfasst.")
    } else {
        detail.attendance.forEach { attendance ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            row.div(attendance.memberDisplayName) { addCssClasses("flex-grow-1") }
            row.statusBadge(attendanceStatusLabel(attendance.status), attendanceStatusColor(attendance.status))
            attendance.representedByDisplayName?.let { representative ->
                row.div("vertreten durch $representative") { addCssClasses("text-muted small") }
            }
            attendance.note?.takeIf { it.isNotBlank() }?.let { note ->
                row.div(note) { addCssClasses("text-muted small") }
            }
        }
    }

    renderQuorumRow(panel, detail.quorum)

    if (canManage) {
        renderAttendanceRecordingForm(panel, detail.meeting.id, eligibleMembers, detail.attendance, onChanged)
    }
}

private fun renderAttendanceRecordingForm(
    panel: SimplePanel,
    meetingId: String,
    eligibleMembers: List<MemberSummaryDto>,
    existingAttendance: List<AttendanceDto>,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p("Anwesenheit erfassen") { addCssClass("fw-bold") }
    if (eligibleMembers.isEmpty()) {
        formPanel.p("Keine berechtigten Mitglieder gefunden.")
        return
    }
    val existingByMember = existingAttendance.associateBy { it.memberId }
    val statusOptions = AttendanceStatus.entries.map { it.name to attendanceStatusLabel(it) }
    val representedOptions = listOf("" to "-- keine --") + eligibleMembers.map { it.id to it.displayName }

    eligibleMembers.forEach { member ->
        val existing = existingByMember[member.id]
        val row = formPanel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
        val topRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        topRow.div(member.displayName) { addCssClasses("flex-grow-1") }
        val statusSelect =
            topRow.select(options = statusOptions, value = (existing?.status ?: AttendanceStatus.PRESENT).name, label = "Status")
        val representedBySelect =
            row.select(
                options = representedOptions,
                value = existing?.representedByMemberId.orEmpty(),
                label = "Vertreten durch (nur bei \"Vertreten\")",
            )
        val noteInput = row.text(value = existing?.note, label = "Notiz (optional)")
        val saveButton = row.button("Speichern", style = ButtonStyle.OUTLINEPRIMARY)
        saveButton.onClick {
            val statusValue = statusSelect.value ?: return@onClick
            val status = AttendanceStatus.valueOf(statusValue)
            val representedById = representedBySelect.value?.takeIf { it.isNotBlank() }
            if (status == AttendanceStatus.REPRESENTED && representedById == null) {
                notifyError("Bitte bei \"Vertreten\" angeben, durch wen.")
                return@onClick
            }
            saveButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IGovernanceService>().recordAttendance(
                            meetingId,
                            AttendanceInput(
                                memberId = member.id,
                                status = status,
                                representedByMemberId = representedById,
                                note = noteInput.value?.trim()?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                saveButton.disabled = false
                if (result != null) {
                    notifySuccess("Anwesenheit von ${member.displayName} gespeichert.")
                    onChanged()
                }
            }
        }
    }
}

private fun renderResolutionSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    panel.h2("Beschlüsse") { addCssClass("h5") }
    if (detail.resolutions.isEmpty()) {
        panel.p("Noch keine Beschlüsse erfasst.")
    } else {
        detail.resolutions.forEach { resolution -> renderResolutionRow(panel, resolution) }
    }

    if (canManage) {
        renderRecordResolutionForm(panel, detail.meeting.id, detail.agenda, onChanged)
    }
}

/**
 * Non-private (design decision D6): reused verbatim by `MotionsScreen.kt`'s read-only resolution
 * summary in a Motion's detail view -- a `ResolutionDto` must render identically everywhere it
 * appears, this screen's own resolution book and the Motions screen alike.
 */
fun renderResolutionRow(
    panel: SimplePanel,
    resolution: ResolutionDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div("${resolution.number}: ${resolution.title}") { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(resolutionStatusLabel(resolution.status), resolutionStatusColor(resolution.status))
    headerRow.typeBadge(resolutionModeLabel(resolution.resolutionMode), resolutionModeColor(resolution.resolutionMode))
    row.p(resolution.text) { addCssClass("mb-0") }
    row.div(
        "Ja: ${resolution.votesYes} · Nein: ${resolution.votesNo} · Enthaltung: ${resolution.votesAbstain} · " +
            "Quorum ${if (resolution.quorumMet) "erreicht" else "nicht erreicht"} · " +
            "entschieden am ${resolution.decidedAt} von ${resolution.recordedByDisplayName}",
    ) { addCssClasses("text-muted small") }
}

private fun renderRecordResolutionForm(
    panel: SimplePanel,
    meetingId: String,
    agenda: List<AgendaItemDto>,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p("Beschluss erfassen (Gremienbeschluss)") { addCssClass("fw-bold") }
    val agendaOptions =
        listOf("" to "-- kein Tagesordnungspunkt --") +
            agenda.sortedBy { it.position }.map { it.id to "${it.position}. ${it.title}" }
    val agendaSelect = formPanel.select(options = agendaOptions, value = "", label = "Tagesordnungspunkt (optional)")
    val titleInput = formPanel.text(label = "Titel")
    val textInput = formPanel.textArea(label = "Beschlusstext", rows = 3)
    val votesYesInput = formPanel.text(value = "0", label = "Ja-Stimmen")
    val votesNoInput = formPanel.text(value = "0", label = "Nein-Stimmen")
    val votesAbstainInput = formPanel.text(value = "0", label = "Enthaltungen")
    val statusOptions = ResolutionStatus.entries.map { it.name to resolutionStatusLabel(it) }
    val statusSelect = formPanel.select(options = statusOptions, value = ResolutionStatus.ADOPTED.name, label = "Status")
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = formPanel.button("Beschluss speichern", style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val text = textInput.value.orEmpty().trim()
        val votesYes =
            votesYesInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val votesNo =
            votesNoInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val votesAbstain =
            votesAbstainInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val statusValue = statusSelect.value
        val agendaItemId = agendaSelect.value?.takeIf { it.isNotBlank() }

        if (
            !Validation.isNonBlank(title) ||
            !Validation.isNonBlank(text) ||
            votesYes == null ||
            votesNo == null ||
            votesAbstain == null ||
            votesYes < 0 ||
            votesNo < 0 ||
            votesAbstain < 0 ||
            statusValue == null
        ) {
            errorBox.content = "Bitte Titel, Beschlusstext, Status und nicht-negative Stimmzahlen angeben."
            errorBox.show()
            return@onClick
        }

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().recordResolution(
                        meetingId,
                        ResolutionInput(
                            agendaItemId = agendaItemId,
                            title = title,
                            text = text,
                            votesYes = votesYes,
                            votesNo = votesNo,
                            votesAbstain = votesAbstain,
                            status = ResolutionStatus.valueOf(statusValue),
                        ),
                    )
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess("Beschluss \"$title\" wurde erfasst.")
                titleInput.value = null
                textInput.value = null
                votesYesInput.value = "0"
                votesNoInput.value = "0"
                votesAbstainInput.value = "0"
                onChanged()
            }
        }
    }
}

/**
 * "Protokoll" -- design decision D5: an always-visible "Protokollentwurf erzeugen" button (no
 * privilege gate, matching `generateProtocolDraft` having none server-side either -- design
 * decision D4, `"Sichtbar für alle Mitglieder."` stated plainly rather than the UI inventing a
 * restriction the backend doesn't enforce) plus, once generated, an in-app inline preview and a
 * "Drucken" button. No client-generated downloadable Blob this wave -- deliberately deferred per
 * D5, browser print / "Save as PDF" covers the interim need; a real download deserves a real
 * filename/`Content-Disposition`, which the future Serienbrief-/PDF-Engine (V0.4) will provide
 * via `DocumentHttp`'s pattern, not a second parallel Blob mechanism invented here.
 */
private fun renderProtocolSection(
    panel: SimplePanel,
    meetingId: String,
) {
    panel.h2("Protokoll") { addCssClass("h5") }
    val actionRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val generateButton = actionRow.button("Protokollentwurf erzeugen", style = ButtonStyle.OUTLINESECONDARY)
    actionRow.div("Sichtbar für alle Mitglieder.") { addCssClasses("text-muted small") }
    val previewPanel = panel.vPanel(spacing = 8)

    generateButton.onClick {
        AppScope.launch {
            val draft = guarded { rpcService<IGovernanceService>().generateProtocolDraft(meetingId) } ?: return@launch
            renderProtocolPreview(previewPanel, draft)
        }
    }
}

private fun renderProtocolPreview(
    panel: SimplePanel,
    draft: ProtocolDraftDto,
) {
    panel.removeAll()
    // `.protocol-print-area` -- backs the global `@media print` rule in `index.html`: only this
    // container (not the surrounding "Drucken" button, filters, or navbar) survives to the
    // printed page / "Save as PDF" output. See that file's own KDoc comment for the full rationale.
    val printArea = panel.vPanel(spacing = 6) { addCssClass("protocol-print-area") }
    printArea.h2("Protokoll: ${draft.meeting.title}") { addCssClass("h5") }
    val locationSuffix = if (draft.meeting.location.isNullOrBlank()) "" else " · Ort: ${draft.meeting.location}"
    printArea.div(
        "Gremium: ${draft.meeting.committeeName} · ${meetingFormatLabel(draft.meeting.format)} · " +
            "Termin: ${draft.meeting.scheduledAt}$locationSuffix",
    )

    printArea.p("Anwesenheit") { addCssClasses("fw-bold mb-1") }
    if (draft.attendance.isEmpty()) {
        printArea.p("Keine Anwesenheit erfasst.") { addCssClass("small") }
    } else {
        draft.attendance.forEach { attendance ->
            val representedName = attendance.representedByDisplayName
            val representedSuffix = if (representedName == null) "" else " (vertreten durch $representedName)"
            val noteSuffix = if (attendance.note.isNullOrBlank()) "" else " -- ${attendance.note}"
            printArea.div(
                "${attendance.memberDisplayName}: ${attendanceStatusLabel(attendance.status)}$representedSuffix$noteSuffix",
            ) { addCssClass("small") }
        }
    }

    printArea.p("Tagesordnung") { addCssClasses("fw-bold mb-1 mt-2") }
    if (draft.agenda.isEmpty()) {
        printArea.p("Keine Tagesordnungspunkte.") { addCssClass("small") }
    } else {
        draft.agenda.sortedBy { it.position }.forEach { item ->
            val descriptionSuffix = if (item.description.isNullOrBlank()) "" else " -- ${item.description}"
            val presenterName = item.presenterDisplayName
            val presenterSuffix = if (presenterName == null) "" else " (Vortragend: $presenterName)"
            printArea.div("${item.position}. ${item.title}$descriptionSuffix$presenterSuffix") { addCssClass("small") }
        }
    }

    printArea.p("Beschlüsse") { addCssClasses("fw-bold mb-1 mt-2") }
    if (draft.resolutions.isEmpty()) {
        printArea.p("Keine Beschlüsse.") { addCssClass("small") }
    } else {
        draft.resolutions.forEach { resolution -> renderResolutionRow(printArea, resolution) }
    }

    renderQuorumRow(printArea, draft.quorum)
    printArea.div("Entwurf erstellt am ${draft.generatedAt}") { addCssClasses("text-muted small mt-2") }

    val printButton = panel.button("Drucken", style = ButtonStyle.OUTLINESECONDARY)
    printButton.onClick { window.print() }
}

/** Design decision D4: solid pass/fail badge, immediately followed by plain numbers -- no
 * checkmark/✕ icon (the German label already carries the signal, no icon-font precedent exists
 * in this codebase besides the one deliberately-scoped `GuestBadge` SVG). Reused verbatim by both
 * [renderAttendanceSection] and [renderProtocolPreview] (design decision D5). */
private fun renderQuorumRow(
    panel: SimplePanel,
    quorum: QuorumResultDto,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.statusBadge(
        if (quorum.met) "Quorum erreicht" else "Quorum nicht erreicht",
        if (quorum.met) "success" else "danger",
    )
    row.div(
        "${quorum.presentCount} von ${quorum.eligibleMemberCount} anwesend " +
            "(erforderlich: ${quorum.requiredCount}, ${quorum.quorumPercent}%).",
    )
}

/**
 * German label/badge-color tables for [MeetingStatus]/[MeetingFormat]/[AttendanceStatus]/
 * [ResolutionStatus]/[ResolutionMode] -- this screen's instantiation of the [statusBadge]/
 * [typeBadge] grammar (see `StatusBadge.kt` KDoc and the design review's exact table). Non-private
 * so [MeetingsScreenTest] can cover them directly (same posture as `CommitteesScreen.kt`'s own
 * label/color functions), and so [resolutionStatusLabel]/[resolutionStatusColor]/
 * [resolutionModeLabel]/[resolutionModeColor] can be reused verbatim by `MotionsScreen.kt`'s
 * read-only resolution summary (design decision D6: a `ResolutionDto.resolutionMode` badge must
 * look identical everywhere it appears -- the Meetings-screen resolution book and the Motion
 * detail view alike).
 */
fun meetingStatusLabel(status: MeetingStatus): String =
    when (status) {
        MeetingStatus.PLANNED -> "Geplant"
        MeetingStatus.HELD -> "Durchgeführt"
        MeetingStatus.CANCELLED -> "Abgesagt"
    }

fun meetingStatusColor(status: MeetingStatus): String =
    when (status) {
        MeetingStatus.PLANNED -> "secondary"
        MeetingStatus.HELD -> "success"
        MeetingStatus.CANCELLED -> "danger"
    }

fun meetingFormatLabel(format: MeetingFormat): String =
    when (format) {
        MeetingFormat.IN_PERSON -> "Präsenz"
        MeetingFormat.ONLINE -> "Online"
        MeetingFormat.HYBRID -> "Hybrid"
    }

fun attendanceStatusLabel(status: AttendanceStatus): String =
    when (status) {
        AttendanceStatus.PRESENT -> "Anwesend"
        AttendanceStatus.EXCUSED -> "Entschuldigt"
        AttendanceStatus.UNEXCUSED -> "Unentschuldigt"
        AttendanceStatus.REPRESENTED -> "Vertreten"
    }

fun attendanceStatusColor(status: AttendanceStatus): String =
    when (status) {
        AttendanceStatus.PRESENT -> "success"
        AttendanceStatus.EXCUSED -> "warning"
        AttendanceStatus.UNEXCUSED -> "danger"
        AttendanceStatus.REPRESENTED -> "info"
    }

fun resolutionStatusLabel(status: ResolutionStatus): String =
    when (status) {
        ResolutionStatus.ADOPTED -> "Angenommen"
        ResolutionStatus.REJECTED -> "Abgelehnt"
        ResolutionStatus.POSTPONED -> "Zurückgestellt"
    }

fun resolutionStatusColor(status: ResolutionStatus): String =
    when (status) {
        ResolutionStatus.ADOPTED -> "success"
        ResolutionStatus.REJECTED -> "danger"
        ResolutionStatus.POSTPONED -> "warning"
    }

fun resolutionModeLabel(mode: ResolutionMode): String =
    when (mode) {
        ResolutionMode.COMMITTEE_QUORUM -> "Gremienbeschluss"
        ResolutionMode.MERITOCRATIC -> "Meritokratische Vote"
        ResolutionMode.DEMOCRATIC -> "Demokratische Wahl"
        ResolutionMode.SYSTEMIC_CONSENSUS -> "Systemisches Konsensieren"
    }

fun resolutionModeColor(mode: ResolutionMode): String =
    when (mode) {
        ResolutionMode.COMMITTEE_QUORUM -> "secondary"
        ResolutionMode.MERITOCRATIC -> "primary"
        ResolutionMode.DEMOCRATIC -> "info"
        ResolutionMode.SYSTEMIC_CONSENSUS -> "dark"
    }
