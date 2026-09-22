package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
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
 * every [network.lapis.cloud.shared.domain.MemberStatus.ACTIVE] member
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
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Sitzungen"))

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val committeeFilterSelect = filterRow.select(options = listOf("" to tr("Alle Gremien")), value = "", label = tr("Gremium"))
    val statusFilterOptions =
        listOf("" to tr("Alle Status")) + MeetingStatus.entries.map { it.name to meetingStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Status"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val meetingListPanel = root.vPanel(spacing = 6)

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Sitzung oben auswählen, um Details zu sehen."))

    root.h2(tr("Neue Sitzung anlegen")) { addCssClass("h5") }
    val creationPanel = root.vPanel(spacing = 6)
    creationPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

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
                meetingListPanel.p(tr("Noch keine Sitzungen vorhanden."))
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

        creationPanel.removeAll()
        if (manageableCommittees.isEmpty()) {
            creationPanel.p(tr("Keine Berechtigung, neue Sitzungen anzulegen."))
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
    // Security audit W6b follow-up round 3 (major finding A): meeting title is member-editable free text
    // rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(meeting.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))
    row.div(gettext("%1 · %2 · %3", meeting.committeeName, meetingFormatLabel(meeting.format), meeting.scheduledAt)) {
        addCssClasses("text-muted small")
    }
    meeting.location?.takeIf { it.isNotBlank() }?.let { location ->
        row.div(gettext("Ort: %1", location)) { addCssClasses("text-muted small") }
    }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.button(tr("Details anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(meeting) }
}

internal fun renderMeetingCreationForm(
    panel: SimplePanel,
    committees: List<CommitteeDto>,
    memberCandidates: List<MemberSummaryDto>,
    onCreated: () -> Unit,
) {
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
    val memberOptions = listOf("" to tr("-- keine --")) + untrustedOptions(memberCandidates.map { it.id to it.displayName })
    val chairField = form.selectField(label = tr("Sitzungsleitung"), options = memberOptions, value = "")
    val minuteTakerField = form.selectField(label = tr("Protokollführung"), options = memberOptions, value = "")

    val createButton = Button(tr("Sitzung anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)
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
                onCreated()
            }
        }
    }
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
    // Security audit W6b follow-up round 3 (major finding A): see the sanitization in `renderMeetingRow` above --
    // same untrusted `meeting.title`, same widget-content path.
    headerRow.h2(sanitizeUntrustedI18nText(meeting.title)) { addCssClasses("h5 flex-grow-1") }
    headerRow.statusBadge(meetingStatusLabel(meeting.status), meetingStatusColor(meeting.status))

    panel.div(
        gettext(
            "Gremium: %1 · %2 · Termin: %3",
            meeting.committeeName,
            meetingFormatLabel(meeting.format),
            meeting.scheduledAt,
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
    eligibleMembers: List<MemberSummaryDto>,
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
    val checkboxesByMember =
        eligibleMembers.associateWith { member -> recipientsPanel.checkBox(label = member.displayName) }

    fun selectedRecipients(): List<MemberSummaryDto> = checkboxesByMember.filterValues { it.value }.keys.toList()
    // `.input`, nicht der Wrapper-<div> der CheckBox: nur das <input> nimmt `focus()` an. `watch`: jede Checkbox räumt die
    // Sammelmeldung (ein Listener nur an der ersten sähe Klicks auf die übrigen nie).
    val recipientInputs = checkboxesByMember.values.mapNotNull { it.input as? Widget }
    form.crossFieldRule(focusOn = recipientInputs.firstOrNull(), watch = recipientInputs) {
        if (selectedRecipients().isEmpty()) {
            FieldCheck.Invalid(gettext("Bitte mindestens eine Empfängerin/einen Empfänger auswählen."))
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

    val downloadButton = Button(tr("Als PDF herunterladen"), style = ButtonStyle.OUTLINEPRIMARY)
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
        val postalButton = host.button(tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
        postalButton.onClick {
            if (!form.validateAndReport()) return@onClick
            val recipients = selectedRecipients()
            val title = titleField.value.trim()
            val eventDateTime = LocalDateTime.parse(eventDateTimeField.value.trim())
            val location = locationField.value.trim()
            val bodyText = bodyTextField.value.trim()
            if (recipients.size > MAX_POSTAL_INVITATION_RECIPIENTS_UI) {
                form.showFormError(
                    gettext(
                        "Postversand ist auf %1 Empfänger begrenzt (aktuell ausgewählt: %2) -- für mehr " +
                            "Empfänger bitte das PDF herunterladen und selbst verteilen.",
                        MAX_POSTAL_INVITATION_RECIPIENTS_UI,
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
    panel.h2(tr("Tagesordnung")) { addCssClass("h5") }
    val agenda = detail.agenda.sortedBy { it.position }
    if (agenda.isEmpty()) {
        panel.p(tr("Noch keine Tagesordnungspunkte."))
    } else {
        agenda.forEach { item ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            val description = if (item.description.isNullOrBlank()) "" else " -- ${item.description}"
            row.div(gettext("%1. %2%3", item.position, item.title, description)) { addCssClasses("flex-grow-1") }
            item.presenterDisplayName?.let { presenter ->
                row.div(gettext("Vortragend: %1", presenter)) { addCssClasses("text-muted small") }
            }
            if (canManage) {
                val removeButton = row.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
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
        renderAddAgendaItemForm(panel, detail.meeting.id, nextAgendaPosition(agenda), eligibleMembers, onChanged)
    }
}

private fun nextAgendaPosition(agenda: List<AgendaItemDto>): Int = (agenda.maxOfOrNull { it.position } ?: 0) + 1

internal fun renderAddAgendaItemForm(
    panel: SimplePanel,
    meetingId: String,
    nextPosition: Int,
    eligibleMembers: List<MemberSummaryDto>,
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
    val presenterOptions = listOf("" to tr("-- kein --")) + untrustedOptions(eligibleMembers.map { it.id to it.displayName })
    val presenterField = form.selectField(label = tr("Vortragend"), options = presenterOptions, value = "")

    val addButton = Button(tr("Hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY)
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

private fun renderAttendanceSection(
    panel: SimplePanel,
    detail: MeetingDetailDto,
    canManage: Boolean,
    eligibleMembers: List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    panel.h2(tr("Anwesenheit")) { addCssClass("h5") }
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
        }
    }

    renderQuorumRow(panel, detail.quorum)

    if (canManage) {
        renderAttendanceRecordingForm(panel, detail.meeting.id, eligibleMembers, detail.attendance, onChanged)
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
            form.selectField(
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
        val saveButton = Button(tr("Speichern"), style = ButtonStyle.OUTLINEPRIMARY)
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
    headerRow.div(gettext("%1: %2", resolution.number, resolution.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(resolutionStatusLabel(resolution.status), resolutionStatusColor(resolution.status))
    headerRow.typeBadge(resolutionModeLabel(resolution.resolutionMode), resolutionModeColor(resolution.resolutionMode))
    row.untrustedP(resolution.text, className = "mb-0")
    row.div(
        gettext(
            "Ja: %1 · Nein: %2 · Enthaltung: %3 · Quorum %4 · entschieden am %5 von %6",
            resolution.votesYes,
            resolution.votesNo,
            resolution.votesAbstain,
            if (resolution.quorumMet) gettext("erreicht") else gettext("nicht erreicht"),
            resolution.decidedAt,
            resolution.recordedByDisplayName,
        ),
    ) { addCssClasses("text-muted small") }
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

    val saveButton = Button(tr("Beschluss speichern"), style = ButtonStyle.PRIMARY)
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
    printArea.h2(gettext("Protokoll: %1", draft.meeting.title)) { addCssClass("h5") }
    val locationSuffix = if (draft.meeting.location.isNullOrBlank()) "" else " · Ort: ${draft.meeting.location}"
    printArea.div(
        gettext(
            "Gremium: %1 · %2 · Termin: %3%4",
            draft.meeting.committeeName,
            meetingFormatLabel(draft.meeting.format),
            draft.meeting.scheduledAt,
            locationSuffix,
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
            printArea.div(
                gettext(
                    "%1: %2%3%4",
                    attendance.memberDisplayName,
                    attendanceStatusLabel(attendance.status),
                    representedSuffix,
                    noteSuffix,
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
            printArea.div(gettext("%1. %2%3%4", item.position, item.title, descriptionSuffix, presenterSuffix)) {
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
    printArea.div(gettext("Entwurf erstellt am %1", draft.generatedAt)) { addCssClasses("text-muted small mt-2") }

    val printButton = panel.button(tr("Drucken"), style = ButtonStyle.OUTLINESECONDARY)
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
        if (quorum.met) tr("Quorum erreicht") else tr("Quorum nicht erreicht"),
        if (quorum.met) "success" else "danger",
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
