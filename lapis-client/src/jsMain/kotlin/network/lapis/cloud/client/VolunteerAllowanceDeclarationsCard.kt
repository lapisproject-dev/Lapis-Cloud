package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.cell
import io.kvision.table.row
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberSelectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService

/** The RPC surface of the declarations overview -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface VolunteerAllowanceDeclarationsRpc {
    suspend fun listDeclarations(
        memberId: String?,
        calendarYear: Int?,
    ): List<VolunteerAllowanceSelfDeclarationDto>

    suspend fun listMembersForSelection(): List<MemberSelectionDto>
}

internal fun liveVolunteerAllowanceDeclarationsRpc(): VolunteerAllowanceDeclarationsRpc =
    object : VolunteerAllowanceDeclarationsRpc {
        override suspend fun listDeclarations(
            memberId: String?,
            calendarYear: Int?,
        ) = rpcService<IVolunteerAllowanceService>().listDeclarations(memberId, calendarYear)

        override suspend fun listMembersForSelection() = rpcService<IMemberService>().listMembersForSelection()
    }

/** Mirrors `MAX_LIST_RESULTS` in the server's `VolunteerAllowanceService.kt`. */
internal const val VOLUNTEER_ALLOWANCE_DECLARATIONS_LIST_CAP = 200

/** Mirrors `MAX_MEMBER_SELECTION` in the server's `MemberService.kt`. */
internal const val MEMBER_SELECTION_CAP = 5000

/**
 * The picker label of one person: the sanitized name, plus " (Status)" for everyone who is not ACTIVE (a withdrawn or deceased
 * member can still have declarations). The status label comes from [memberStatusLabel]; the composition happens OUTSIDE
 * `tr`/`gettext`, so a name can never become part of a translation key.
 */
internal fun memberSelectionLabel(member: MemberSelectionDto): String {
    val name = sanitizeUntrustedI18nText(member.displayName)
    return if (member.status == MemberStatus.ACTIVE) name else name + " (" + memberStatusLabel(member.status) + ")"
}

/** Visibility only -- the server decides (`VOLUNTEER_ALLOWANCE_DECISION_ROLES`) and rejects anyone else. */
internal fun canViewOthersVolunteerAllowanceDeclarations(): Boolean = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

private fun currentCalendarYear(): Int = organizationToday().year

/**
 * Welle V1.9.34 -- overview of the volunteer-allowance self-declarations: "your own" for every member, and for BOARD/ADMIN an expandable
 * look at another member's. It only shows WHICH declarations exist -- never an amount, a cap or a sum (those stay with the payment).
 * Loads once on creation; the returned handle reloads the own list (the screen calls it after a declaration was written).
 */
internal fun renderVolunteerAllowanceDeclarationsCard(
    container: SimplePanel,
    rpc: VolunteerAllowanceDeclarationsRpc = liveVolunteerAllowanceDeclarationsRpc(),
    thisYear: Int = currentCalendarYear(),
): DataSection {
    container.h2(tr("Meine Erklärungen zur Ehrenamts- und Übungsleiterpauschale")) { addCssClasses("h5 mt-3") }
    val own =
        container.dataSection<List<VolunteerAllowanceSelfDeclarationDto>>(
            emptyText = tr("Keine Erklärung erfasst."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpc.listDeclarations(null, null) } },
            render = { panel, declarations -> renderDeclarationsTable(panel, declarations) },
        )
    own.reload()
    if (canViewOthersVolunteerAllowanceDeclarations()) renderOthersDisclosure(container, rpc, thisYear)
    container.div(
        tr("Diese Liste zeigt nur, welche Erklärungen erfasst sind. Beträge und Jahresgrenzen stehen bei der jeweiligen Zahlung."),
    ) { addCssClasses("text-muted small") }
    return own
}

private fun renderOthersDisclosure(
    container: SimplePanel,
    rpc: VolunteerAllowanceDeclarationsRpc,
    thisYear: Int,
) {
    val toggle = container.button(tr("Erklärungen anderer Mitglieder ansehen"), style = ButtonStyle.LINK)
    toggle.setAttribute("aria-expanded", "false")
    val body = container.vPanel(spacing = 8)
    var open = false
    toggle.onClick {
        open = !open
        toggle.setAttribute("aria-expanded", open.toString())
        body.removeAll()
        if (!open) return@onClick
        // `listMembersForSelection` is only loaded now, on expand -- and only ever reached by BOARD/ADMIN.
        body
            .dataSection<List<MemberSelectionDto>>(
                emptyText = tr("Keine Mitglieder gefunden."),
                isEmpty = { it.isEmpty() },
                load = { guarded { rpc.listMembersForSelection() } },
                render = { panel, members -> renderOthersPicker(panel, rpc, members, thisYear) },
            ).reload()
    }
}

private fun renderOthersPicker(
    panel: SimplePanel,
    rpc: VolunteerAllowanceDeclarationsRpc,
    members: List<MemberSelectionDto>,
    thisYear: Int,
) {
    if (members.size >= MEMBER_SELECTION_CAP) {
        panel.div(tr("Nicht alle Mitglieder werden angezeigt – bitte suchen.")) { addCssClasses("text-muted small") }
    }
    val controls = panel.hPanel(spacing = 8) { addCssClass("align-items-end") }
    val memberSelect =
        controls.searchableSelect(
            options = untrustedOptions(members.map { it.id to memberSelectionLabel(it) }),
            label = tr("Mitglied"),
        )
    val yearOptions = listOf("" to tr("Alle Jahre")) + (thisYear downTo thisYear - 6).map { it.toString() to it.toString() }
    val yearSelect = controls.select(options = yearOptions, value = "", label = tr("Jahr"))
    val hint = panel.div(tr("Bitte ein Mitglied wählen.")) { addCssClasses("text-muted small") }
    val resultHost = panel.vPanel(spacing = 4)
    val results =
        resultHost.dataSection<List<VolunteerAllowanceSelfDeclarationDto>>(
            emptyText = tr("Keine Erklärung erfasst."),
            isEmpty = { it.isEmpty() },
            load = {
                val memberId = memberSelect.value?.takeIf { it.isNotBlank() }
                if (memberId == null) {
                    emptyList()
                } else {
                    guarded { rpc.listDeclarations(memberId, yearSelect.value?.toIntOrNull()) }
                }
            },
            render = { host, declarations -> renderDeclarationsTable(host, declarations) },
        )

    fun apply() {
        if (memberSelect.value.isNullOrBlank()) {
            hint.show()
            resultHost.hide()
        } else {
            hint.hide()
            resultHost.show()
            results.reload()
        }
    }
    memberSelect.subscribe { apply() }
    yearSelect.subscribe { apply() }
}

private fun renderDeclarationsTable(
    panel: SimplePanel,
    declarations: List<VolunteerAllowanceSelfDeclarationDto>,
) {
    val table =
        panel.standardTable(
            headers =
                listOf(
                    TableHeader(title = tr("Jahr"), numeric = true),
                    TableHeader(title = tr("Art")),
                    TableHeader(title = tr("Abgegeben")),
                    TableHeader(title = tr("Unterschrieben am")),
                    TableHeader(title = tr("Erfasst von")),
                ),
        )
    declarations.forEach { declaration ->
        table.row {
            numCell(declaration.calendarYear.toString())
            textCell(trusted(volunteerAllowanceCategoryLabel(declaration.category)))
            cell {
                typeBadge(volunteerAllowanceDeclarationSourceLabel(declaration.source), "secondary")
                div(formatSystemDateTime(declaration.declaredAt)) { addCssClasses("text-muted small") }
            }
            textCell(trusted(declaration.signedOn?.let { formatDate(it) } ?: "–"))
            textCell(declaration.recordedByDisplayName)
        }
    }
    if (declarations.size >= VOLUNTEER_ALLOWANCE_DECLARATIONS_LIST_CAP) {
        panel.div(tr("Es werden höchstens 200 Einträge angezeigt. Bitte nach Jahr filtern.")) { addCssClasses("text-muted small") }
    }
}
