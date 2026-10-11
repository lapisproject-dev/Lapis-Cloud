package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.P
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
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
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.InvitationAudience
import network.lapis.cloud.shared.domain.MAX_INVITATION_PDF_PART_SIZE
import network.lapis.cloud.shared.domain.MAX_POSTAL_INVITATION_RECIPIENTS
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
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
 * Attendance/agenda-presenter member pickers (design: "per-eligible-member status recording"):
 * for any Committee EXCEPT the General Assembly, they are sourced from that Committee's active roster
 * (`listCommitteeMembers`, reused for the [GovernanceAuthzUi] check above, so no extra round trip) -- "active as
 * of today", not "as of the meeting's date", an accepted simplification, exactly the one `CommitteesScreen`'s own
 * roster view makes. V1.9.101: for a [CommitteeType.GENERAL_ASSEMBLY] Committee the screen NEVER loads the member
 * directory: every active member belongs to it (`generalAssemblyCondition`, the one predicate behind the quorum), so
 * the screen asks only for N (`countGeneralAssemblyEligible`, see [MeetingAudience]) and finds people through the
 * server-side search ([RemoteMemberPicker], `searchCommitteeCandidates`). Attendance of the assembly is recorded
 * person by person through ONE form that "Bearbeiten" pre-fills, not one mini form per member.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decisions D5/D6: [renderEinladungSection] adds a free
 * PDF download ([MailmergeHttp.submitEinladungPdfDownload], `GOVERNANCE_DOC_ROLES`) and a real
 * Letterxpress postal dispatch ([IPostalMailService.dispatchEinladungByPost],
 * `GOVERNANCE_DISPATCH_ROLES`) entry point -- both strictly BOARD/ADMIN, narrower than this screen's
 * own per-Committee `canManage` (which also admits CHAIR/DEPUTY_CHAIR/SECRETARY). The section only
 * renders at all when `canManage` is true (same gate as Agenda/Attendance/Resolution editing); a
 * Committee officer who can manage the meeting but is not globally BOARD/ADMIN sees a plain-language
 * explanation instead of a vanished control. Recipients are sourced from the same [MeetingAudience]
 * this screen already computes for attendance -- no new RPC call needed (the assembly sends to "all active
 * members" resolved on the server, or to a searched selection).
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
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    val header = root.pageHeader(tr("Sitzungen"))
    // V1.9.40 (R36B): the create form is collapsed; its button sits in the title row and the form opens right under the header.
    val createHost = root.vPanel(spacing = 6)

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val committeeFilterSelect = filterRow.select(options = listOf("" to tr("Alle Gremien")), value = "", label = tr("Gremium"))
    val statusFilterOptions =
        listOf("" to tr("Alle Status")) + MeetingStatus.entries.map { it.name to meetingStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Status"))
    val refreshButton = filterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val meetingListPanel = root.vPanel(spacing = 6)

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Sitzung oben auswählen, um Details zu sehen."))

    var committees: List<CommitteeDto> = emptyList()
    var currentDetailMeetingId: String? = null
    // Known only after the committee rosters are loaded (below), i.e. usually AFTER the first list render: the empty-state text is
    // corrected in place once it is (see [emptyNote]).
    var canCreate = false
    var emptyNote: P? = null

    fun emptyText(): String =
        if (canCreate) tr("Noch keine Sitzungen. Mit \"Neue Sitzung\" legen Sie eine an.") else tr("Noch keine Sitzungen vorhanden.")

    fun refreshDetail() {
        val meetingId = currentDetailMeetingId ?: return
        renderMeetingDetail(detailPanel, meetingId, currentMemberId, isBoardOrAdmin, committees) {
            refreshDetail()
        }
    }

    fun refreshMeetings() {
        meetingListPanel.removeAll()
        emptyNote = null
        AppScope.launch {
            val committeeId = committeeFilterSelect.value?.takeIf { it.isNotBlank() }
            val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { MeetingStatus.valueOf(it) }
            val meetings = guarded { rpcService<IGovernanceService>().listMeetings(committeeId, status) } ?: return@launch
            if (meetings.isEmpty()) {
                emptyNote = meetingListPanel.p(emptyText())
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
        committeeFilterSelect.options = listOf("" to tr("Alle Gremien")) + untrustedOptions(committees.map { it.id to it.name })
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

        // No permission: no button and no empty action area (R36B) -- the screen simply offers nothing to create.
        if (manageableCommittees.isNotEmpty()) {
            // V1.9.101: no member directory is loaded any more -- chair and minute-taker are found by the server-side search
            // ([RemoteMemberPicker]), so the page does not carry every member just to offer two optional fields. The button lives
            // outside the list refresh, so reloading the list never throws away a form someone is filling in.
            collapsibleCreateForm<Unit>(
                actionSlot = header.actionSlot,
                formHost = createHost,
                buttonLabel = tr("Neue Sitzung"),
                formId = "lapis-create-meeting",
            ) { _, close -> renderMeetingCreationForm(this, manageableCommittees, { refreshMeetings() }, close) }
            canCreate = true
            emptyNote?.content = emptyText()
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
    // Security audit W6b follow-up round 3 (major finding A): meeting title is member-editable free text
    // rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(meeting.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))
    row.div(
        gettext("%1 · %2 · %3", meeting.committeeName, meetingFormatLabel(meeting.format), formatDateTime(meeting.scheduledAt)),
    ) {
        addCssClasses("text-muted small")
    }
    meeting.location?.takeIf { it.isNotBlank() }?.let { location ->
        row.div(gettext("Ort: %1", location)) { addCssClasses("text-muted small") }
    }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.actionButton(ActionIcon.VIEW, tr("Details anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(meeting) }
}

internal fun renderMeetingCreationForm(
    panel: SimplePanel,
    committees: List<CommitteeDto>,
    onCreated: () -> Unit,
    collapse: ((saved: Boolean) -> Unit)? = null,
): FormSnapshot {
    // Formular-Grammatik (V1.4.29, W4b): Titel und Termin sind Pflicht, Ort und die beiden Rollen optional => Fall (a).
    val form = panel.lapisForm()
    val committeeOptions = untrustedOptions(committees.map { it.id to it.name })
    val committeeField =
        form.selectField(label = tr("Gremium"), options = committeeOptions, value = committees.firstOrNull()?.id, required = true)
    val titleField = form.textField(label = tr("Titel"), required = true)
    val scheduledAtField =
        form.textField(
            label = tr("Termin"),
            required = true,
            hint = gettext("Beispiel: 2026-08-15T18:00."),
            rule = { FormRules.localDateTime(value = it) },
        )
    val locationField = form.textField(label = tr("Ort"))
    val formatOptions = MeetingFormat.entries.map { it.name to meetingFormatLabel(it) }
    val formatField =
        form.selectField(label = tr("Format"), options = formatOptions, value = MeetingFormat.IN_PERSON.name, required = true)
    // V1.9.101: the people are searched on the server for the committee chosen ABOVE (the right to search is that committee's).
    // Without a committee the pickers are off; changing the committee clears both choices (a person found for one committee
    // is not a validated choice for another).
    val searchChosenCommittee: suspend (String, Int) -> MemberPickPageDto? = { query, page ->
        committeeField.value.takeIf { it.isNotBlank() }?.let { committeeCandidateSearch(it)(query, page) }
    }
    val chairField = form.remoteMemberPickerField(label = tr("Sitzungsleitung"), search = searchChosenCommittee)
    val minuteTakerField = form.remoteMemberPickerField(label = tr("Protokollführung"), search = searchChosenCommittee)
    var lastCommitteeId = committeeField.value

    fun syncPickersToCommittee() {
        val chosen = committeeField.value.isNotBlank()
        val note = if (chosen) null else gettext("Bitte zuerst ein Gremium wählen.")
        (chairField.control as RemoteMemberPicker).setUnavailable(note)
        (minuteTakerField.control as RemoteMemberPicker).setUnavailable(note)
    }
    syncPickersToCommittee()
    committeeField.subscribe { newCommitteeId ->
        if (newCommitteeId != lastCommitteeId) {
            lastCommitteeId = newCommitteeId
            chairField.setValue("")
            minuteTakerField.setValue("")
            syncPickersToCommittee()
        }
    }

    val createButton = newActionButton(ActionIcon.ADD, tr("Sitzung anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })
    createButton.onClick {
        form.submit(createButton) {
            val title = titleField.value.trim()
            val result =
                guarded {
                    rpcService<IGovernanceService>().createMeeting(
                        MeetingInput(
                            committeeId = committeeField.value,
                            title = title,
                            scheduledAt = LocalDateTime.parse(scheduledAtField.value.trim()),
                            location = locationField.value.trim().takeIf { it.isNotBlank() },
                            format = MeetingFormat.valueOf(formatField.value),
                            chairMemberId = chairField.value.takeIf { it.isNotBlank() },
                            minuteTakerMemberId = minuteTakerField.value.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Sitzung \"%1\" wurde angelegt.", title))
                titleField.reset()
                scheduledAtField.reset()
                locationField.reset()
                chairField.reset()
                minuteTakerField.reset()
                onCreated()
                collapse?.invoke(true)
            }
        }
    }
    return form.snapshot()
}

/**
 * Who a meeting's forms offer as people (V1.9.101): a committee's own active roster (already in hand, a short list), or -- for the
 * General Assembly -- every active member, which is never loaded: the screen knows only N and finds people by server-side search.
 */
internal sealed interface MeetingAudience {
    data class Roster(
        val members: List<MemberSummaryDto>,
    ) : MeetingAudience

    /** [eligibleCount] is `null` when the count could not be fetched. */
    data class GeneralAssembly(
        val committeeId: String,
        val eligibleCount: Int?,
    ) : MeetingAudience
}

/**
 * Loads [MeetingDetailDto] plus this specific meeting's Committee roster (for the
 * [GovernanceAuthzUi] check and, for a non-General-Assembly Committee, the eligible-member
 * picker source) and, for a General-Assembly Committee, the full ACTIVE member directory --
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
    panel.p(tr("Wird geladen …"))
    AppScope.launch {
        val detail = guarded { rpcService<IGovernanceService>().getMeetingDetail(meetingId) } ?: return@launch
        val roster =
            guarded {
                rpcService<IGovernanceService>().listCommitteeMembers(detail.meeting.committeeId, activeOnly = true)
            } ?: emptyList()
        val canManage =
            GovernanceAuthzUi.canRecordForMeeting(isBoardOrAdmin, currentMemberId, detail.meeting.committeeId, roster)
        val committee = committees.find { it.id == detail.meeting.committeeId }
        // V1.9.101: the General Assembly is every active member -- never load them all. Only N (a single number) is fetched; a
        // failed count must not break the page, the invitation then says so instead of showing a figure.
        val audience: MeetingAudience =
            if (committee?.type == CommitteeType.GENERAL_ASSEMBLY) {
                val count =
                    runCatching {
                        rpcService<IGovernanceService>().countGeneralAssemblyEligible(
                            detail.meeting.committeeId,
                        )
                    }.getOrNull()
                MeetingAudience.GeneralAssembly(committeeId = detail.meeting.committeeId, eligibleCount = count)
            } else {
                MeetingAudience.Roster(roster.map { MemberSummaryDto(id = it.memberId, displayName = it.memberDisplayName) })
            }

        panel.removeAll()
        renderMeetingMeta(panel, detail.meeting, canManage, onChanged)
        renderEinladungSection(panel, detail.meeting, canManage, isBoardOrAdmin, audience)
        renderAgendaSection(panel, detail, canManage, audience, onChanged)
        renderAttendanceSection(panel, detail, canManage, audience, onChanged)
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
    // Security audit W6b follow-up round 3 (major finding A): see the sanitization in `renderMeetingRow` above --
    // same untrusted `meeting.title`, same widget-content path.
    headerRow.h2(sanitizeUntrustedI18nText(meeting.title)) { addCssClasses("h5 flex-grow-1") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))

    panel.div(
        gettext(
            "Gremium: %1 · %2 · Termin: %3",
            meeting.committeeName,
            meetingFormatLabel(meeting.format),
            formatDateTime(meeting.scheduledAt),
        ),
    ) {
        addCssClasses("text-muted small")
    }
    meeting.location?.takeIf { it.isNotBlank() }?.let { location ->
        panel.div(gettext("Ort: %1", location)) { addCssClasses("text-muted small") }
    }
    meeting.chairDisplayName?.let { chair ->
        panel.div(gettext("Sitzungsleitung: %1", chair)) { addCssClasses("text-muted small") }
    }
    meeting.minuteTakerDisplayName?.let { taker ->
        panel.div(gettext("Protokollführung: %1", taker)) { addCssClasses("text-muted small") }
    }

    // Status transitions: only meaningful from PLANNED. HELD is a forward/completing transition
    // (no confirm dialog, mirrors `updateMeetingStatus -> HELD` being unwrapped per the design
    // review D7); CANCELLED is destructive and gets the real confirm step, per the same D7 list.
    if (canManage && meeting.status == MeetingStatus.PLANNED) {
        val actionRow = panel.hPanel(spacing = 8)
        val heldButton = actionRow.button(tr("Als durchgeführt markieren"), style = ButtonStyle.SUCCESS)
        heldButton.onClick {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().updateMeetingStatus(meeting.id, MeetingStatus.HELD) }
                if (result != null) {
                    notifySuccess(tr("Sitzung als durchgeführt markiert."))
                    onChanged()
                }
            }
        }
        val cancelButton = actionRow.button(tr("Absagen"), style = ButtonStyle.OUTLINEDANGER)
        cancelButton.onClick {
            confirmDialog(
                title = tr("Sitzung absagen"),
                message = gettext("\"%1\" wirklich absagen?", meeting.title),
                confirmLabel = tr("Absagen"),
            ) {
                AppScope.launch {
                    val result =
                        guarded { rpcService<IGovernanceService>().updateMeetingStatus(meeting.id, MeetingStatus.CANCELLED) }
                    if (result != null) {
                        notifyInfo(tr("Sitzung abgesagt."))
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
internal fun renderEinladungSection(
    panel: SimplePanel,
    meeting: MeetingDto,
    canManage: Boolean,
    isBoardOrAdminGlobal: Boolean,
    audience: MeetingAudience,
) {
    if (!canManage) return
    panel.h2(tr("Einladung")) { addCssClass("h5") }

    if (!isBoardOrAdminGlobal) {
        panel.div(
            tr(
                "Der Versand von Einladungen ist Vorstand und Administration vorbehalten -- als " +
                    "Sitzungsleitung/Protokollführung dieses Gremiums können Sie die Sitzung verwalten, aber " +
                    "keine Einladungen verschicken.",
            ),
        ) { addCssClasses("text-muted small") }
        return
    }

    when (audience) {
        is MeetingAudience.Roster -> renderRosterEinladungForm(panel, meeting, audience.members)
        is MeetingAudience.GeneralAssembly -> renderGeneralAssemblyEinladungForm(panel, meeting, audience)
    }
}

private fun renderRosterEinladungForm(
    panel: SimplePanel,
    meeting: MeetingDto,
    eligibleMembers: List<MemberSummaryDto>,
) {
    panel.div(
        tr(
            "Versand einer Einladung an ausgewählte Mitglieder -- als PDF zum Herunterladen (kostenlos, bis zu " +
                "1.000 Empfänger) oder per Post (kostenpflichtig über Letterxpress, bis zu 50 Empfänger, " +
                "erfordert eine vollständige Anschrift jedes Empfängers).",
        ),
    ) { addCssClasses("text-muted small") }

    if (eligibleMembers.isEmpty()) {
        panel.p(tr("Keine berechtigten Mitglieder gefunden."))
        return
    }

    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    // Formular-Grammatik (V1.4.29): vier Pflichtfelder => Fall (b). Die Empfängerliste ist eine Auswahlliste, keine Feldgruppe
    // (ein Fehlerslot je Mitglied wäre absurd): "mindestens ein Empfänger" ist eine Querregel in der Sammelfläche.
    val form = formPanel.lapisForm()
    val titleField = form.textField(label = tr("Titel"), value = meeting.title, required = true)
    val eventDateTimeField =
        form.textField(
            label = tr("Termin"),
            value = meeting.scheduledAt.toString(),
            required = true,
            hint = gettext("Beispiel: 2026-08-15T18:00."),
            rule = { FormRules.localDateTime(value = it) },
        )
    val locationField = form.textField(label = tr("Ort"), value = meeting.location.orEmpty(), required = true)
    val bodyTextField = form.textAreaField(label = tr("Einladungstext"), rows = 4, required = true)

    form.panel.p(tr("Empfänger")) { addCssClasses("fw-bold mb-1") }
    val recipientsPanel = form.panel.vPanel(spacing = 2) { addCssClasses("border rounded p-2") }
    val quickToggleRow = recipientsPanel.hPanel(spacing = 8)
    // dataNavigo = false auf beiden: rein lokale Checkbox-Toggle, keine Route (V1.2.4-Audit,
    // dataNavigo-Sweep) -- siehe LoginScreen.kt-Kommentar zum globalen Default.
    val selectAllLink = quickToggleRow.link(tr("Alle auswählen"), url = "javascript:void(0)", dataNavigo = false)
    val deselectAllLink = quickToggleRow.link(tr("Alle abwählen"), url = "javascript:void(0)", dataNavigo = false)
    // Unchecked by default -- a costly/PII-sharing action must never default to "everyone selected".
    // Security audit follow-up (untrusted-text sanitization gaps): member.displayName is member-controlled free
    // text -- CheckBox.label reaches the same Widget/I18n render path as div/span/p content, but is outside the
    // widget-text tripwire's regex (it only matches div/span/p/h1..h6/link), so this was found only by manual
    // review. Sanitize before it becomes the checkbox's label.
    val checkboxesByMember =
        eligibleMembers.associateWith { member ->
            recipientsPanel.checkBox(label = sanitizeUntrustedI18nText(member.displayName))
        }

    fun selectedRecipients(): List<MemberSummaryDto> = checkboxesByMember.filterValues { it.value }.keys.toList()
    // `.input`, nicht der Wrapper-<div> der CheckBox: nur das <input> nimmt `focus()` an. `watch`: jede Checkbox räumt die
    // Sammelmeldung (ein Listener nur an der ersten sähe Klicks auf die übrigen nie).
    val recipientInputs = checkboxesByMember.values.mapNotNull { it.input as? Widget }
    form.crossFieldRule(focusOn = recipientInputs.firstOrNull(), watch = recipientInputs) {
        if (selectedRecipients().isEmpty()) {
            FieldCheck.Invalid(gettext("Bitte mindestens einen Empfänger auswählen."))
        } else {
            FieldCheck.Ok
        }
    }
    // Programmatisches Setzen löst kein DOM-`change` aus: die Sammelmeldung räumt sich hier von Hand.
    selectAllLink.onClick {
        checkboxesByMember.values.forEach { checkbox -> checkbox.value = true }
        form.onFieldStateChanged()
    }
    deselectAllLink.onClick {
        checkboxesByMember.values.forEach { checkbox -> checkbox.value = false }
        form.onFieldStateChanged()
    }

    val outcomePanel = form.panel.vPanel(spacing = 4)

    val downloadButton = newActionButton(ActionIcon.DOWNLOAD, tr("Als PDF herunterladen"), style = ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = downloadButton)
    downloadButton.onClick {
        if (!form.validateAndReport()) return@onClick
        MailmergeHttp.submitEinladungPdfDownload(
            titleField.value.trim(),
            LocalDateTime.parse(eventDateTimeField.value.trim()),
            locationField.value.trim(),
            bodyTextField.value.trim(),
            selectedRecipients().map { it.id },
        )
    }

    // D7: the postal-dispatch button is fetched-and-populated asynchronously (whether
    // postalMailEnabled is true) -- the free-PDF download button above is never gated by this flag,
    // since it never touches Letterxpress.
    val postalActionPanel = form.panel.vPanel(spacing = 4)
    postalActionPanel.renderPostalMailGate { host ->
        val postalButton = host.actionButton(ActionIcon.SEND, tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
        postalButton.onClick {
            if (!form.validateAndReport()) return@onClick
            val recipients = selectedRecipients()
            val title = titleField.value.trim()
            val eventDateTime = LocalDateTime.parse(eventDateTimeField.value.trim())
            val location = locationField.value.trim()
            val bodyText = bodyTextField.value.trim()
            if (recipients.size > MAX_POSTAL_INVITATION_RECIPIENTS) {
                form.showFormError(
                    gettext(
                        "Postversand ist auf %1 Empfänger begrenzt (aktuell ausgewählt: %2) -- für mehr " +
                            "Empfänger bitte das PDF herunterladen und selbst verteilen.",
                        MAX_POSTAL_INVITATION_RECIPIENTS,
                        recipients.size,
                    ),
                )
                return@onClick
            }

            postalEinladungDispatchConfirmDialog(recipients.map { it.displayName }) {
                outcomePanel.removeAll()
                form.runBusy(postalButton) {
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
                    if (results != null) {
                        val sentCount = results.count { it.status == PostalDeliveryStatus.SENT }
                        if (sentCount == results.size) {
                            notifySuccess(gettext("%1 von %2 Briefen erfolgreich übergeben.", sentCount, results.size))
                        } else {
                            notifyError(
                                gettext(
                                    "%1 von %2 Briefen fehlgeschlagen -- Details unten.",
                                    results.size - sentCount,
                                    results.size,
                                ),
                            )
                        }
                        results.forEach { log -> outcomePanel.renderPostalDispatchOutcome(log) }
                    }
                }
            }
        }
    }
}

/**
 * V1.9.101 -- the invitation form of the General Assembly. Every active member is a possible recipient, so the people are never
 * listed: "all active members" is resolved ON THE SERVER (the form sends only audience, committee and part), and an explicit
 * selection is made with the server-side search ([RemoteMemberPicker]) and shown as removable chips.
 *
 *  - PDF: free; one download per part of at most [MAX_INVITATION_PDF_PART_SIZE] letters (`ceil(N / 1000)` buttons).
 *  - Post: paid, only offered while N is known and at most [MAX_POSTAL_INVITATION_RECIPIENTS] (the server enforces the cap again).
 *  - No e-mail is ever sent from here.
 */
private fun renderGeneralAssemblyEinladungForm(
    panel: SimplePanel,
    meeting: MeetingDto,
    audience: MeetingAudience.GeneralAssembly,
) {
    val committeeId = audience.committeeId
    val n = audience.eligibleCount
    panel.div(tr("Einladungen werden als PDF oder per Brief erstellt; eine E-Mail wird nicht verschickt.")) {
        addCssClasses("text-muted small")
    }

    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val form = formPanel.lapisForm()
    val titleField = form.textField(label = tr("Titel"), value = meeting.title, required = true)
    val eventDateTimeField =
        form.textField(
            label = tr("Termin"),
            value = meeting.scheduledAt.toString(),
            required = true,
            hint = gettext("Beispiel: 2026-08-15T18:00."),
            rule = { FormRules.localDateTime(value = it) },
        )
    val locationField = form.textField(label = tr("Ort"), value = meeting.location.orEmpty(), required = true)
    val bodyTextField = form.textAreaField(label = tr("Einladungstext"), rows = 4, required = true)

    val allActiveLabel = if (n != null) gettext("An alle aktiven Mitglieder (%1)", n) else gettext("An alle aktiven Mitglieder")
    val audienceField =
        form.selectField(
            label = tr("Empfänger"),
            options =
                listOf(
                    InvitationAudience.ALL_ACTIVE.name to allActiveLabel,
                    InvitationAudience.SELECTED.name to gettext("Ausgewählte Mitglieder"),
                ),
            value = InvitationAudience.ALL_ACTIVE.name,
            required = true,
        )

    fun allActive(): Boolean = audienceField.value == InvitationAudience.ALL_ACTIVE.name

    val picked = linkedMapOf<String, MemberPickDto>()
    val pickerField =
        form.remoteMemberPickerField(label = tr("Mitglied hinzufügen"), search = committeeCandidateSearch(committeeId))
    val picker = pickerField.control as RemoteMemberPicker
    val chipsPanel = form.panel.vPanel(spacing = 2)

    // The download buttons are (re)built from the current choice into this row; the postal action sits below it.
    val buttonRow = form.buttons(primary = null)
    if (n == null) {
        form.panel.div(
            tr(
                "Die Mitgliederzahl konnte nicht ermittelt werden -- heruntergeladen wird der erste Teil; " +
                    "der Server prüft die Teilzahl.",
            ),
        ) { addCssClasses("text-muted small") }
    }
    val postalActionPanel = form.panel.vPanel(spacing = 4)
    val postalLimitNote = form.panel.div { addCssClasses("text-muted small") }
    var postalButton: Button? = null
    val outcomePanel = form.panel.vPanel(spacing = 4)

    fun refreshPostal() {
        val button = postalButton
        if (button == null) {
            postalLimitNote.hide()
            return
        }
        if (allActive()) {
            if (n != null && n <= MAX_POSTAL_INVITATION_RECIPIENTS) {
                button.show()
                postalLimitNote.hide()
            } else {
                button.hide()
                if (n != null) {
                    postalLimitNote.content =
                        gettext(
                            "Postversand ist auf %1 Empfänger begrenzt; die Mitgliederversammlung hat aktuell %2 -- bitte das PDF herunterladen.",
                            MAX_POSTAL_INVITATION_RECIPIENTS,
                            n,
                        )
                    postalLimitNote.show()
                } else {
                    postalLimitNote.hide()
                }
            }
        } else {
            button.show()
            postalLimitNote.hide()
        }
    }

    fun downloadButton(
        label: String,
        part: Int?,
    ): Button {
        val button = buttonRow.actionButton(ActionIcon.DOWNLOAD, label, style = ButtonStyle.OUTLINEPRIMARY)
        button.onClick {
            if (!form.validateAndReport()) return@onClick
            MailmergeHttp.submitEinladungPdfDownload(
                title = titleField.value.trim(),
                eventDateTime = LocalDateTime.parse(eventDateTimeField.value.trim()),
                location = locationField.value.trim(),
                bodyText = bodyTextField.value.trim(),
                recipientMemberIds = if (allActive()) emptyList() else picked.keys.toList(),
                audience = if (allActive()) InvitationAudience.ALL_ACTIVE else InvitationAudience.SELECTED,
                committeeId = if (allActive()) committeeId else null,
                part = if (allActive()) part ?: 0 else null,
            )
        }
        return button
    }

    fun renderDownloadButtons() {
        buttonRow.removeAll()
        if (allActive()) {
            val parts = if (n == null) 1 else maxOf(1, (n + MAX_INVITATION_PDF_PART_SIZE - 1) / MAX_INVITATION_PDF_PART_SIZE)
            if (parts == 1) {
                downloadButton(tr("Als PDF herunterladen"), part = 0)
            } else {
                for (k in 1..parts) downloadButton(gettext("Teil %1 von %2 herunterladen", k, parts), part = k - 1)
            }
        } else {
            downloadButton(tr("Als PDF herunterladen"), part = null)
        }
    }

    fun renderChips() {
        chipsPanel.removeAll()
        picked.values.forEach { pick ->
            val chip = chipsPanel.hPanel(spacing = 6) { addCssClasses("align-items-center border rounded px-2") }
            chip.untrustedDiv(pick.displayName, className = "flex-grow-1")
            val remove = chip.actionButton(ActionIcon.REMOVE, gettext("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
            remove.addCssClass("lapis-chip-remove")
            remove.onClick {
                picked.remove(pick.id)
                renderChips()
                form.onFieldStateChanged()
            }
        }
    }

    fun applyAudience() {
        val explicit = !allActive()
        pickerField.setVisible(explicit)
        if (explicit) chipsPanel.show() else chipsPanel.hide()
        renderDownloadButtons()
        refreshPostal()
        form.onFieldStateChanged()
    }

    // A person chosen in the search becomes a chip and the field is emptied for the next search.
    pickerField.subscribe { id ->
        if (id.isNotBlank()) {
            picker.selectedPick?.let { picked[it.id] = it }
            picker.clear()
            renderChips()
            form.onFieldStateChanged()
        }
    }
    form.crossFieldRule(focusOn = picker.input, watch = listOfNotNull(audienceField.control.input as? Widget)) {
        when {
            allActive() -> FieldCheck.Ok
            picked.isEmpty() -> FieldCheck.Invalid(gettext("Bitte mindestens einen Empfänger auswählen."))
            picked.size > MAX_INVITATION_PDF_PART_SIZE ->
                FieldCheck.Invalid(
                    gettext(
                        "Höchstens %1 Empfänger je PDF -- bitte die Auswahl verkleinern oder \"An alle aktiven Mitglieder\" wählen.",
                        MAX_INVITATION_PDF_PART_SIZE,
                    ),
                )
            else -> FieldCheck.Ok
        }
    }
    audienceField.subscribe { applyAudience() }
    applyAudience()

    // D7: the postal-dispatch button is fetched-and-populated asynchronously (whether postalMailEnabled is true) -- the free-PDF
    // download is never gated by this flag, since it never touches Letterxpress.
    postalActionPanel.renderPostalMailGate { host ->
        val button = host.actionButton(ActionIcon.SEND, tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
        postalButton = button
        refreshPostal()
        button.onClick {
            if (!form.validateAndReport()) return@onClick
            val title = titleField.value.trim()
            val eventDateTime = LocalDateTime.parse(eventDateTimeField.value.trim())
            val location = locationField.value.trim()
            val bodyText = bodyTextField.value.trim()
            val everyone = allActive()
            val chosen = picked.values.toList()
            val count = if (everyone) n else chosen.size
            if (count == null || count > MAX_POSTAL_INVITATION_RECIPIENTS) {
                form.showFormError(
                    gettext(
                        "Postversand ist auf %1 Empfänger begrenzt; die Mitgliederversammlung hat aktuell %2 -- bitte das PDF herunterladen.",
                        MAX_POSTAL_INVITATION_RECIPIENTS,
                        count ?: 0,
                    ),
                )
                return@onClick
            }
            val confirmed: () -> Unit = {
                outcomePanel.removeAll()
                form.runBusy(button) {
                    val results =
                        guarded {
                            rpcService<IPostalMailService>().dispatchEinladungByPost(
                                PostalInvitationDispatchInput(
                                    title = title,
                                    eventDateTime = eventDateTime,
                                    location = location,
                                    bodyText = bodyText,
                                    recipientMemberIds = if (everyone) emptyList() else chosen.map { it.id },
                                    audience = if (everyone) InvitationAudience.ALL_ACTIVE else InvitationAudience.SELECTED,
                                    committeeId = if (everyone) committeeId else null,
                                ),
                            )
                        }
                    if (results != null) {
                        val sentCount = results.count { it.status == PostalDeliveryStatus.SENT }
                        if (sentCount == results.size) {
                            notifySuccess(gettext("%1 von %2 Briefen erfolgreich übergeben.", sentCount, results.size))
                        } else {
                            notifyError(
                                gettext("%1 von %2 Briefen fehlgeschlagen -- Details unten.", results.size - sentCount, results.size),
                            )
                        }
                        results.forEach { log -> outcomePanel.renderPostalDispatchOutcome(log) }
                    }
                }
            }
            if (everyone) {
                postalEinladungDispatchConfirmDialogForCount(count, confirmed)
            } else {
                postalEinladungDispatchConfirmDialog(chosen.map { it.displayName }, confirmed)
            }
        }
    }
}

private fun renderAgendaSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    audience: MeetingAudience,
    onChanged: () -> Unit,
) {
    panel.h2(tr("Tagesordnung")) { addCssClass("h5") }
    val agenda = detail.agenda.sortedBy { it.position }
    if (agenda.isEmpty()) {
        panel.p(tr("Noch keine Tagesordnungspunkte."))
    } else {
        agenda.forEach { item ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            val description = if (item.description.isNullOrBlank()) "" else " -- ${item.description}"
            // Security audit follow-up (untrusted-text sanitization gaps): item.title/item.description are
            // member-editable free text composed into gettext(...) strings -- sanitize the whole composed result.
            row.div(sanitizeUntrustedI18nText(gettext("%1. %2%3", item.position, item.title, description))) {
                addCssClasses("flex-grow-1")
            }
            item.presenterDisplayName?.let { presenter ->
                // presenter is a member display name -- same untrusted-field category as subscriber.memberDisplayName
                // (CommunicationScreen.kt) / mandate.memberDisplayName elsewhere, sanitize before rendering.
                row.div(sanitizeUntrustedI18nText(gettext("Vortragend: %1", presenter))) { addCssClasses("text-muted small") }
            }
            if (canManage) {
                val removeButton = row.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
                removeButton.onClick {
                    AppScope.launch {
                        val result = guarded { rpcService<IGovernanceService>().removeAgendaItem(item.id) }
                        if (result != null) {
                            notifySuccess(tr("Tagesordnungspunkt entfernt."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }

    if (canManage) {
        val presenterSource =
            when (audience) {
                is MeetingAudience.Roster -> PresenterSource.Options(audience.members)
                is MeetingAudience.GeneralAssembly -> PresenterSource.Remote(audience.committeeId)
            }
        renderAddAgendaItemForm(panel, detail.meeting.id, nextAgendaPosition(agenda), presenterSource, onChanged)
    }
}

private fun nextAgendaPosition(agenda: List<AgendaItemDto>): Int = (agenda.maxOfOrNull { it.position } ?: 0) + 1

/** Where the agenda form's "Vortragend" picker gets its people from (V1.9.101). */
internal sealed interface PresenterSource {
    /** A committee's own short roster, offered as a searchable list. */
    data class Options(
        val members: List<MemberSummaryDto>,
    ) : PresenterSource

    /** The General Assembly: searched on the server for [committeeId]. */
    data class Remote(
        val committeeId: String,
    ) : PresenterSource
}

internal fun renderAddAgendaItemForm(
    panel: SimplePanel,
    meetingId: String,
    nextPosition: Int,
    presenterSource: PresenterSource,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p(tr("Tagesordnungspunkt hinzufügen")) { addCssClass("fw-bold") }
    // Formular-Grammatik (V1.4.29): Position und Titel sind Pflicht, Beschreibung und Vortragende optional => Fall (a).
    val form = formPanel.lapisForm()
    val positionField =
        form.textField(
            label = tr("Position"),
            value = nextPosition.toString(),
            required = true,
            rule = { FormRules.wholeNumber(value = it) },
        )
    val titleField = form.textField(label = tr("Titel"), required = true)
    val descriptionField = form.textField(label = tr("Beschreibung"))
    val presenterField =
        when (presenterSource) {
            is PresenterSource.Options -> {
                val presenterOptions =
                    listOf("" to tr("-- kein --")) + untrustedOptions(presenterSource.members.map { it.id to it.displayName })
                form.searchableSelectField(label = tr("Vortragend"), options = presenterOptions, value = "")
            }
            is PresenterSource.Remote ->
                form.remoteMemberPickerField(label = tr("Vortragend"), search = committeeCandidateSearch(presenterSource.committeeId))
        }

    val addButton = newActionButton(ActionIcon.ADD, tr("Hinzufügen"), ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = addButton)
    addButton.onClick {
        form.submit(addButton) {
            val title = titleField.value.trim()
            val result =
                guarded {
                    rpcService<IGovernanceService>().addAgendaItem(
                        meetingId,
                        AgendaItemInput(
                            position = positionField.value.trim().toInt(),
                            title = title,
                            description = descriptionField.value.trim().takeIf { it.isNotBlank() },
                            presenterMemberId = presenterField.value.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Tagesordnungspunkt \"%1\" hinzugefügt.", title))
                onChanged()
            }
        }
    }
}

internal fun renderAttendanceSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    audience: MeetingAudience,
    onChanged: () -> Unit,
) {
    panel.h2(tr("Anwesenheit")) { addCssClass("h5") }
    // The assembly's form is built BELOW the rows, but each row's "Bearbeiten" must fill it: the callback is wired afterwards.
    var prefill: ((AttendanceDto) -> Unit)? = null
    val editable = canManage && audience is MeetingAudience.GeneralAssembly
    if (detail.attendance.isEmpty()) {
        panel.p(tr("Noch keine Anwesenheit erfasst."))
    } else {
        detail.attendance.forEach { attendance ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            row.untrustedDiv(attendance.memberDisplayName, className = "flex-grow-1")
            row.statusBadge(attendanceStatusLabel(attendance.status), attendanceStatusColor(attendance.status))
            attendance.representedByDisplayName?.let { representative ->
                row.div(gettext("vertreten durch %1", representative)) { addCssClasses("text-muted small") }
            }
            attendance.note?.takeIf { it.isNotBlank() }?.let { note ->
                row.div(note) { addCssClasses("text-muted small") }
            }
            if (editable) {
                val edit = row.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
                edit.addCssClass("lapis-attendance-edit")
                edit.onClick { prefill?.invoke(attendance) }
            }
        }
    }

    renderQuorumRow(panel, detail.quorum)

    if (canManage) {
        when (audience) {
            is MeetingAudience.Roster ->
                renderAttendanceRecordingForm(panel, detail.meeting.id, audience.members, detail.attendance, onChanged)
            is MeetingAudience.GeneralAssembly ->
                prefill = renderGeneralAssemblyAttendanceForm(panel, detail.meeting.id, audience.committeeId, onChanged)
        }
    }
}

/**
 * V1.9.101 -- the attendance form of the General Assembly: ONE form (never one per member -- there can be thousands of members).
 * The person and the representative are found by server-side search; "Bearbeiten" on an existing row fills this same form
 * (the returned function), saving goes through the unchanged `recordAttendance` (an upsert per member).
 *
 * @return the function that pre-fills the form from an existing attendance row.
 */
internal fun renderGeneralAssemblyAttendanceForm(
    panel: SimplePanel,
    meetingId: String,
    committeeId: String,
    onChanged: () -> Unit,
): (AttendanceDto) -> Unit {
    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p(tr("Anwesenheit erfassen")) { addCssClass("fw-bold") }
    val form = formPanel.lapisForm()
    val search = committeeCandidateSearch(committeeId)
    val memberField = form.remoteMemberPickerField(label = tr("Mitglied"), search = search, required = true)
    val memberPicker = memberField.control as RemoteMemberPicker
    val statusOptions = AttendanceStatus.entries.map { it.name to attendanceStatusLabel(it) }
    val statusField =
        form.selectField(
            label = tr("Status"),
            options = statusOptions,
            value = AttendanceStatus.PRESENT.name,
            required = true,
        )
    val representedByField =
        form.remoteMemberPickerField(label = tr("Vertreten durch (nur bei \"Vertreten\")"), search = search)
    val representedByPicker = representedByField.control as RemoteMemberPicker
    val noteField = form.textField(label = tr("Notiz"))
    form.crossFieldRule(
        focusOn = representedByField.control.input as? Widget,
        watch = listOfNotNull(statusField.control.input as? Widget),
    ) {
        if (statusField.value == AttendanceStatus.REPRESENTED.name && representedByField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bitte bei \"Vertreten\" angeben, durch wen."))
        } else {
            FieldCheck.Ok
        }
    }
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val status = parseOptionalEnum<AttendanceStatus>(statusField.value) ?: return@submit
            val memberName = memberPicker.selectedPick?.displayName.orEmpty()
            val result =
                guarded {
                    rpcService<IGovernanceService>().recordAttendance(
                        meetingId,
                        AttendanceInput(
                            memberId = memberField.value,
                            status = status,
                            representedByMemberId = representedByField.value.takeIf { it.isNotBlank() },
                            note = noteField.value.trim().takeIf { it.isNotBlank() },
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Anwesenheit von %1 gespeichert.", memberName))
                memberField.reset()
                representedByField.reset()
                noteField.reset()
                statusField.setValue(AttendanceStatus.PRESENT.name)
                onChanged()
            }
        }
    }
    return { attendance ->
        memberPicker.offer(MemberPickDto(attendance.memberId, attendance.memberDisplayName))
        memberField.setValue(attendance.memberId)
        statusField.setValue(attendance.status.name)
        val representedId = attendance.representedByMemberId
        if (representedId != null) {
            representedByPicker.offer(MemberPickDto(representedId, attendance.representedByDisplayName.orEmpty()))
        }
        representedByField.setValue(representedId)
        noteField.setValue(attendance.note.orEmpty())
        memberField.validate(force = false)
        representedByField.validate(force = false)
        formPanel.getElement()?.scrollIntoView()
    }
}

internal fun renderAttendanceRecordingForm(
    panel: SimplePanel,
    meetingId: String,
    eligibleMembers: List<MemberSummaryDto>,
    existingAttendance: List<AttendanceDto>,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p(tr("Anwesenheit erfassen")) { addCssClass("fw-bold") }
    if (eligibleMembers.isEmpty()) {
        formPanel.p(tr("Keine berechtigten Mitglieder gefunden."))
        return
    }
    val existingByMember = existingAttendance.associateBy { it.memberId }
    val statusOptions = AttendanceStatus.entries.map { it.name to attendanceStatusLabel(it) }
    val representedOptions = listOf("" to gettext("-- keine --")) + untrustedOptions(eligibleMembers.map { it.id to it.displayName })

    eligibleMembers.forEach { member ->
        val existing = existingByMember[member.id]
        val row = formPanel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
        // Ein Mini-Formular je Person (V1.4.29): ein Auswahlfeld hat hier immer einen Wert, kein Feld ist Pflicht => keine
        // Sterne, keine Legende. Die Zeile "Status" steht neben dem Namen, deshalb `host = topRow`.
        val form = row.lapisForm()
        val topRow = form.panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        // Security audit W6b follow-up round 3 (major finding A): a member display name is untrusted free text
        // rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
        topRow.div(sanitizeUntrustedI18nText(member.displayName)) { addCssClasses("flex-grow-1") }
        val statusField =
            form.selectField(
                label = tr("Status"),
                options = statusOptions,
                value = (existing?.status ?: AttendanceStatus.PRESENT).name,
                host = topRow,
                slotHost = form.panel,
            )
        val representedByField =
            form.searchableSelectField(
                label = tr("Vertreten durch (nur bei \"Vertreten\")"),
                options = representedOptions,
                value = existing?.representedByMemberId.orEmpty(),
            )
        val noteField = form.textField(label = tr("Notiz"), value = existing?.note)
        // "Vertreten" verlangt eine Vertretung -- ein leeres Auswahlfeld ist sonst gültig, also eine Querregel.
        form.crossFieldRule(
            focusOn = representedByField.control.input as? Widget,
            watch = listOfNotNull(statusField.control.input as? Widget),
        ) {
            if (statusField.value == AttendanceStatus.REPRESENTED.name && representedByField.value.isBlank()) {
                FieldCheck.Invalid(gettext("Bitte bei \"Vertreten\" angeben, durch wen."))
            } else {
                FieldCheck.Ok
            }
        }
        val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.OUTLINEPRIMARY)
        form.buttons(primary = saveButton)
        saveButton.onClick {
            form.submit(saveButton) {
                // Fail-safe wie im Alt-Code: ein leerer Wert (nicht Pflicht, hier nie zu erwarten) sendet nichts statt zu werfen.
                val status = parseOptionalEnum<AttendanceStatus>(statusField.value) ?: return@submit
                val result =
                    guarded {
                        rpcService<IGovernanceService>().recordAttendance(
                            meetingId,
                            AttendanceInput(
                                memberId = member.id,
                                status = status,
                                representedByMemberId = representedByField.value.takeIf { it.isNotBlank() },
                                note = noteField.value.trim().takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(gettext("Anwesenheit von %1 gespeichert.", member.displayName))
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
    panel.h2(tr("Beschlüsse")) { addCssClass("h5") }
    if (detail.resolutions.isEmpty()) {
        panel.p(tr("Noch keine Beschlüsse erfasst."))
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
    // Security audit follow-up (untrusted-text sanitization gaps): resolution.title is member-authored free
    // text (resolution.number is a server-generated "<CommitteeType>-<year>-<running number>" identifier, not
    // free text -- see nextResolutionNumber in ResolutionBook.kt -- but sanitizing the whole composed result is
    // harmless either way and matches this file's established idiom).
    headerRow.div(sanitizeUntrustedI18nText(gettext("%1: %2", resolution.number, resolution.title))) {
        addCssClasses("flex-grow-1 fw-bold")
    }
    headerRow.statusBadge(resolutionStatusLabel(resolution.status), resolutionStatusColor(resolution.status))
    headerRow.typeBadge(resolutionModeLabel(resolution.resolutionMode), resolutionModeColor(resolution.resolutionMode))
    row.untrustedP(resolution.text, className = "mb-0")
    val quorumText = if (resolution.quorumMet) gettext("erreicht") else gettext("nicht erreicht")
    val decisionLine =
        if (resolution.figuresWithheld) {
            // V1.9.53: a secret election below the minimum participation -- no figures, only the rule.
            gettext(
                "Stimmenzahlen nicht veröffentlicht (geheime Wahl mit weniger als %1 Stimmzetteln) · Quorum %2 · entschieden am %3 von %4",
                DisclosureRules.MIN_ANONYMOUS_RESPONSES,
                quorumText,
                formatSystemDateTime(resolution.decidedAt),
                resolution.recordedByDisplayName,
            )
        } else {
            gettext(
                "Ja: %1 · Nein: %2 · Enthaltung: %3 · Quorum %4 · entschieden am %5 von %6",
                resolution.votesYes,
                resolution.votesNo,
                resolution.votesAbstain,
                quorumText,
                formatSystemDateTime(resolution.decidedAt),
                resolution.recordedByDisplayName,
            )
        }
    row.div(decisionLine) { addCssClasses("text-muted small") }
}

internal fun renderRecordResolutionForm(
    panel: SimplePanel,
    meetingId: String,
    agenda: List<AgendaItemDto>,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    formPanel.p(tr("Beschluss erfassen (Gremienbeschluss)")) { addCssClass("fw-bold") }
    // Formular-Grammatik (V1.4.29): die Abstimmung eines Gremiums (Ja/Nein/Enthaltungen, Status). Alle Felder außer dem
    // Tagesordnungspunkt sind Pflicht.
    val form = formPanel.lapisForm()
    val agendaOptions =
        listOf("" to tr("-- kein Tagesordnungspunkt --")) +
            agenda.sortedBy { it.position }.map { it.id to gettext("%1. %2", it.position, it.title) }
    val agendaField = form.selectField(label = tr("Tagesordnungspunkt"), options = agendaOptions, value = "")
    val titleField = form.textField(label = tr("Titel"), required = true)
    val textField = form.textAreaField(label = tr("Beschlusstext"), rows = 3, required = true)
    val votesYesField =
        form.textField(label = tr("Ja-Stimmen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val votesNoField =
        form.textField(label = tr("Nein-Stimmen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val votesAbstainField =
        form.textField(label = tr("Enthaltungen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val statusOptions = ResolutionStatus.entries.map { it.name to resolutionStatusLabel(it) }
    val statusField =
        form.selectField(label = tr("Status"), options = statusOptions, value = ResolutionStatus.ADOPTED.name, required = true)

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Beschluss speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val title = titleField.value.trim()
            val result =
                guarded {
                    rpcService<IGovernanceService>().recordResolution(
                        meetingId,
                        ResolutionInput(
                            agendaItemId = agendaField.value.takeIf { it.isNotBlank() },
                            title = title,
                            text = textField.value.trim(),
                            votesYes = votesYesField.value.trim().toInt(),
                            votesNo = votesNoField.value.trim().toInt(),
                            votesAbstain = votesAbstainField.value.trim().toInt(),
                            status = ResolutionStatus.valueOf(statusField.value),
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Beschluss \"%1\" wurde erfasst.", title))
                titleField.reset()
                textField.reset()
                votesYesField.setValue("0")
                votesNoField.setValue("0")
                votesAbstainField.setValue("0")
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
    panel.h2(tr("Protokoll")) { addCssClass("h5") }
    val actionRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val generateButton = actionRow.button(tr("Protokollentwurf erzeugen"), style = ButtonStyle.OUTLINESECONDARY)
    actionRow.div(tr("Sichtbar für alle Mitglieder.")) { addCssClasses("text-muted small") }
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
    // Security audit follow-up (untrusted-text sanitization gaps): draft.meeting.title is the same
    // member-editable free text sanitized via meeting.title elsewhere in this file (renderMeetingHeader etc.).
    printArea.h2(sanitizeUntrustedI18nText(gettext("Protokoll: %1", draft.meeting.title))) { addCssClass("h5") }
    val locationSuffix = if (draft.meeting.location.isNullOrBlank()) "" else " · Ort: ${draft.meeting.location}"
    // draft.meeting.committeeName is admin-editable free text (same category as committee.name in
    // CommitteesScreen.kt) composed into this gettext(...) string -- sanitize the whole composed result.
    printArea.div(
        sanitizeUntrustedI18nText(
            gettext(
                "Gremium: %1 · %2 · Termin: %3%4",
                draft.meeting.committeeName,
                meetingFormatLabel(draft.meeting.format),
                formatDateTime(draft.meeting.scheduledAt),
                locationSuffix,
            ),
        ),
    )

    printArea.p(tr("Anwesenheit")) { addCssClasses("fw-bold mb-1") }
    if (draft.attendance.isEmpty()) {
        printArea.p(tr("Keine Anwesenheit erfasst.")) { addCssClass("small") }
    } else {
        draft.attendance.forEach { attendance ->
            val representedName = attendance.representedByDisplayName
            val representedSuffix = if (representedName == null) "" else " (vertreten durch $representedName)"
            val noteSuffix = if (attendance.note.isNullOrBlank()) "" else " -- ${attendance.note}"
            // Security audit follow-up (untrusted-text sanitization gaps): memberDisplayName/
            // representedByDisplayName/note are member-controlled free text -- sanitize the whole composed result.
            printArea.div(
                sanitizeUntrustedI18nText(
                    gettext(
                        "%1: %2%3%4",
                        attendance.memberDisplayName,
                        attendanceStatusLabel(attendance.status),
                        representedSuffix,
                        noteSuffix,
                    ),
                ),
            ) { addCssClass("small") }
        }
    }

    printArea.p(tr("Tagesordnung")) { addCssClasses("fw-bold mb-1 mt-2") }
    if (draft.agenda.isEmpty()) {
        printArea.p(tr("Keine Tagesordnungspunkte.")) { addCssClass("small") }
    } else {
        draft.agenda.sortedBy { it.position }.forEach { item ->
            val descriptionSuffix = if (item.description.isNullOrBlank()) "" else " -- ${item.description}"
            val presenterName = item.presenterDisplayName
            val presenterSuffix = if (presenterName == null) "" else " (Vortragend: $presenterName)"
            // Security audit follow-up (untrusted-text sanitization gaps): item.title/item.description/
            // presenterName are member-editable free text -- sanitize the whole composed result, same as the
            // agenda section above in this file.
            printArea.div(
                sanitizeUntrustedI18nText(gettext("%1. %2%3%4", item.position, item.title, descriptionSuffix, presenterSuffix)),
            ) {
                addCssClass("small")
            }
        }
    }

    printArea.p(tr("Beschlüsse")) { addCssClasses("fw-bold mb-1 mt-2") }
    if (draft.resolutions.isEmpty()) {
        printArea.p(tr("Keine Beschlüsse.")) { addCssClass("small") }
    } else {
        draft.resolutions.forEach { resolution -> renderResolutionRow(printArea, resolution) }
    }

    renderQuorumRow(printArea, draft.quorum)
    printArea.div(
        gettext("Entwurf erstellt am %1", formatSystemDateTime(draft.generatedAt)),
    ) { addCssClasses("text-muted small mt-2") }

    val printButton = panel.actionButton(ActionIcon.PRINT, tr("Drucken"), style = ButtonStyle.OUTLINESECONDARY)
    printButton.onClick { window.print() }
}

/** Design decision D4: solid pass/fail badge, immediately followed by plain numbers -- no
 * checkmark/✕ icon (the German label already carries the signal, no icon-font precedent exists
 * in this codebase besides the one deliberately-scoped `GuestBadge` SVG). Reused verbatim by both
 * [renderAttendanceSection] and [renderProtocolPreview] (design decision D5). Colour since V1.9.34:
 * `secondary` (reached) / `warning` (not reached) -- see [quorumBadgeColor]; green stays reserved for "accepted". */
private fun renderQuorumRow(
    panel: SimplePanel,
    quorum: QuorumResultDto,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.statusBadge(
        if (quorum.met) tr("Quorum erreicht") else tr("Quorum nicht erreicht"),
        quorumBadgeColor(quorum.met),
    )
    row.div(
        gettext(
            "%1 von %2 anwesend (erforderlich: %3, %4%).",
            quorum.presentCount,
            quorum.eligibleMemberCount,
            quorum.requiredCount,
            quorum.quorumPercent,
        ),
    )
    panel.quorumExplanation()
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
        MeetingStatus.PLANNED -> gettext("Geplant")
        MeetingStatus.HELD -> gettext("Durchgeführt")
        MeetingStatus.CANCELLED -> gettext("Abgesagt")
    }

fun meetingStatusColor(status: MeetingStatus): String =
    when (status) {
        MeetingStatus.PLANNED -> "secondary"
        MeetingStatus.HELD -> "success"
        MeetingStatus.CANCELLED -> "danger"
    }

fun meetingFormatLabel(format: MeetingFormat): String =
    when (format) {
        MeetingFormat.IN_PERSON -> gettext("Präsenz")
        MeetingFormat.ONLINE -> gettext("Online")
        MeetingFormat.HYBRID -> gettext("Hybrid")
    }

fun attendanceStatusLabel(status: AttendanceStatus): String =
    when (status) {
        AttendanceStatus.PRESENT -> gettext("Anwesend")
        AttendanceStatus.EXCUSED -> gettext("Entschuldigt")
        AttendanceStatus.UNEXCUSED -> gettext("Unentschuldigt")
        AttendanceStatus.REPRESENTED -> gettext("Vertreten")
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
        ResolutionStatus.ADOPTED -> gettext("Angenommen")
        ResolutionStatus.REJECTED -> gettext("Abgelehnt")
        ResolutionStatus.POSTPONED -> gettext("Zurückgestellt")
    }

fun resolutionStatusColor(status: ResolutionStatus): String =
    when (status) {
        ResolutionStatus.ADOPTED -> "success"
        ResolutionStatus.REJECTED -> "danger"
        ResolutionStatus.POSTPONED -> "warning"
    }

fun resolutionModeLabel(mode: ResolutionMode): String =
    when (mode) {
        ResolutionMode.COMMITTEE_QUORUM -> gettext("Gremienbeschluss")
        ResolutionMode.MERITOCRATIC -> gettext("Meritokratische Vote")
        ResolutionMode.DEMOCRATIC -> gettext("Demokratische Wahl")
        ResolutionMode.SYSTEMIC_CONSENSUS -> gettext("Systemisches Konsensieren")
    }

fun resolutionModeColor(mode: ResolutionMode): String =
    when (mode) {
        ResolutionMode.COMMITTEE_QUORUM -> "secondary"
        ResolutionMode.MERITOCRATIC -> "primary"
        ResolutionMode.DEMOCRATIC -> "info"
        ResolutionMode.SYSTEMIC_CONSENSUS -> "dark"
    }
