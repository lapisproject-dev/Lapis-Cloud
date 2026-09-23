package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.UnlinkedMemberDto
import network.lapis.cloud.shared.rpc.IKeycloakLinkService

/*
 * V1.7.2 sub-wave 2b "Keycloak als externe Benutzerverwaltung -- UI" -- the client half of the
 * ADMIN-only manual account-linking UI. The server+shared RPC layer (`IKeycloakLinkService`,
 * sub-wave 2a) intentionally ships only `listUnlinkedMembers`/`linkMember`/`unlinkMember` -- there is
 * no "list already-linked members" query, so this screen works with exactly that surface rather than
 * inventing a new one (see `IKeycloakLinkService` KDoc "Deliberately typed exceptions" for the wave's
 * own scoping reasoning).
 *
 * Two entry points, deliberately in two different places of `renderMemberAdministrationScreen`:
 *
 *  - `renderKeycloakLinkSection` -- a dedicated panel listing every UNLINKED member (the members an
 *    admin actually needs to act on), each with a "Verknüpfen" action opening `openKeycloakLinkDialog`.
 *  - `renderKeycloakUnlinkAction` -- a per-row action added to the EXISTING member roster
 *    (`renderRosterActions` in `MemberAdministrationScreen.kt`), because unlinking needs no dedicated
 *    list: `IKeycloakLinkService.unlinkMember` is idempotent (a no-op for an already-unlinked member,
 *    see its KDoc), so it is safe to offer on every roster row without first knowing which ones are
 *    actually linked -- the alternative would be a new "list linked members" RPC this wave's scope
 *    explicitly does not call for.
 *
 * Both entry points are ADMIN-only (mirrors `IKeycloakLinkService`'s own role gate) and only rendered
 * at all when `AppState.session?.keycloakMode == true` -- in the classic login mode there is no
 * Keycloak identity to link against, so the server would reject `linkMember`/`unlinkMember` with
 * `BadRequestException`/have nothing useful to remove; the house rule of this file family (see
 * `MemberAdministrationScreen.ESCALATED_ROLES` KDoc) is to never offer an action the server rejects
 * anyway.
 */

/**
 * The "Keycloak-Verknüpfung" panel: lists every member [IKeycloakLinkService.listUnlinkedMembers]
 * returns, each with a "Verknüpfen" action. Mirrors the `dataSection`+`dataTable` pattern already
 * dominant in this screen (`renderPendingApplications`/`renderMemberRoster`) rather than a bespoke
 * list, so this screen does not grow a third table convention.
 */
internal fun renderKeycloakLinkSection(root: SimplePanel) {
    root.h2(tr("Keycloak-Verknüpfung")) { addCssClass("h5") }
    root.p(
        tr(
            "Mitglieder, die die automatische Keycloak-Verknüpfung (Abgleich per E-Mail-Adresse) nicht " +
                "zuordnen konnte -- z. B. weil die Keycloak-Identität eine andere E-Mail-Adresse nutzt. " +
                "Hier kann ein Administrator die Verknüpfung manuell herstellen.",
        ),
    )
    lateinit var section: DataSection
    section =
        root.dataSection<List<UnlinkedMemberDto>>(
            emptyText = tr("Alle Mitglieder sind bereits verknüpft (oder die automatische Verknüpfung hat sie bereits zugeordnet)."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IKeycloakLinkService>().listUnlinkedMembers() } },
            render = { panel, members ->
                panel.dataTable(
                    columns = unlinkedMemberColumns(),
                    rows = members,
                    actions = { actions, member -> renderUnlinkedMemberActions(actions, member, onChanged = { section.reload() }) },
                )
            },
        )
    section.reload()
}

private fun unlinkedMemberColumns(): List<DataColumn<UnlinkedMemberDto>> =
    listOf(
        textColumn(title = tr("Name"), primary = true) { it.displayName },
        textColumn(title = tr("E-Mail")) { it.email },
    )

private fun renderUnlinkedMemberActions(
    actionsCell: Container,
    member: UnlinkedMemberDto,
    onChanged: () -> Unit,
) {
    val linkButton = actionsCell.tableActionButton("fas fa-link", tr("Verknüpfen"), ButtonStyle.OUTLINEPRIMARY)
    linkButton.onClick { openKeycloakLinkDialog(member, onChanged) }
}

/**
 * The link dialog: one required text field (the Keycloak subject), `lapisForm`/`textField` grammar
 * (R24), `form.submit` for the double-click guard (R29) -- same pattern as
 * `MemberAdministrationScreen.rejectApplicationDialog`.
 */
internal fun openKeycloakLinkDialog(
    member: UnlinkedMemberDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = gettext("Keycloak-Verknüpfung -- %1", member.displayName))
    modal.p(
        gettext(
            "Verknüpft %1 (%2) mit einer Keycloak-Identität. Das Subject ist die eindeutige Benutzer-ID " +
                "in Keycloak (nicht die E-Mail-Adresse) -- zu finden im Keycloak-Admin-Konsole unter dem " +
                "Benutzerprofil (\"ID\").",
            member.displayName,
            member.email,
        ),
    )
    val form = modal.lapisForm()
    val subjectField =
        form.textField(
            label = tr("Keycloak-Subject"),
            required = true,
        )
    form.finish()
    val linkButton = Button(tr("Verknüpfen"), style = ButtonStyle.PRIMARY)
    linkButton.onClick {
        form.submit(linkButton) {
            val subject = subjectField.value.trim()
            // linkMember returns Unit -- `memberAdminGuarded` returning non-null (the singleton Unit
            // instance) IS the success signal (see [guarded]'s own KDoc: `null` means failure, a toast
            // was already shown; the same idiom `renderPendingApplicationActions.approveButton` above
            // uses for its own Unit-returning RPC call).
            val result = memberAdminGuarded { rpcService<IKeycloakLinkService>().linkMember(member.memberId, subject) }
            if (result != null) {
                notifySuccess(gettext("%1 wurde mit Keycloak verknüpft.", member.displayName))
                modal.hide()
                onChanged()
            }
        }
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(linkButton)
    modal.show()
}

/**
 * Per-roster-row unlink action (`MemberAdministrationScreen.renderRosterActions`) -- ADMIN-only,
 * Keycloak mode only (see this file's class KDoc). Idempotent server-side, so this is offered on
 * EVERY row regardless of whether that particular member is actually linked (no "list linked
 * members" RPC exists, see class KDoc). `confirmDialog` (R29: [ConfirmOnce] inside it) guards the
 * double click.
 */
internal fun renderKeycloakUnlinkAction(
    actionsCell: Container,
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
) {
    if (!AppState.hasRole(AccountRole.ADMIN) || AppState.session?.keycloakMode != true) return
    val unlinkButton = actionsCell.tableActionButton("fas fa-link-slash", tr("Keycloak-Verknüpfung entfernen"), ButtonStyle.OUTLINEWARNING)
    unlinkButton.onClick {
        confirmDialog(
            title = tr("Keycloak-Verknüpfung entfernen"),
            // Review fix (MAJOR 2): the previous wording ("...kann sich diese Person nicht mehr über
            // Keycloak anmelden...") was FALSE for the common case -- `KeycloakAccountLinker
            // .linkOrResolve` (Wave 1) automatically RE-links any unlinked member whose Keycloak
            // email still matches, on their very next login attempt. An admin trying to cut off a
            // compromised/departing member's access via "unlink" would wrongly believe access was
            // actually revoked. The corrected text names the real behavior and points at the actual
            // way to block access (suspending the membership itself).
            // Security-audit fix: names the two real blocking mechanisms (disable in Keycloak, or a
            // LOGIN_BLOCKED status such as "Ausgetreten" -- "gesperrt/inaktiv" are not MemberStatus
            // values) and the new server-side session revocation + member notice.
            message =
                gettext(
                    "Die Keycloak-Verknüpfung von %1 wird entfernt, alle aktiven Sitzungen dieses Mitglieds " +
                        "werden beendet und das Mitglied wird per E-Mail benachrichtigt. Falls die E-Mail-Adresse " +
                        "in Keycloak weiterhin übereinstimmt, wird die Verknüpfung bei der nächsten Anmeldung " +
                        "automatisch wiederhergestellt. Um den Zugang dauerhaft zu sperren, deaktivieren Sie das " +
                        "Konto in Keycloak oder setzen Sie den Mitgliedsstatus z. B. auf „Ausgetreten“.",
                    row.displayName,
                ),
            confirmLabel = tr("Verknüpfung entfernen"),
        ) {
            // Review fix (MINOR 5): moved onto `runGuardedAction` (matching the established guard
            // pattern e.g. `PoliticianScreen.politicianRevokeConfirmDialog` -> `runGuardedAction`) --
            // this file now counts toward R24_MIGRATED's R29 ratchet (ClientUiGuidelineTripwireTest),
            // so the write must not stay a bare unguarded `AppScope.launch`.
            runGuardedAction(unlinkButton) {
                // Review fix (MINOR 6): `unlinkMember` now reports whether a row actually existed --
                // an honest message instead of always claiming "removed" (this action is offered on
                // every roster row, including members with no link at all, see class KDoc).
                val result = memberAdminGuarded { rpcService<IKeycloakLinkService>().unlinkMember(row.id) }
                if (result != null) {
                    if (result) {
                        notifySuccess(gettext("Keycloak-Verknüpfung von %1 entfernt.", row.displayName))
                    } else {
                        notifyInfo(gettext("Für %1 bestand keine Keycloak-Verknüpfung.", row.displayName))
                    }
                    onChanged()
                }
            }
        }
    }
}
