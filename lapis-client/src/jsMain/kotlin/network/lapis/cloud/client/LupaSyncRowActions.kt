package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbFieldDiffDto
import network.lapis.cloud.shared.domain.LupaBbLinkTargetDto
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewRowDto
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException
import org.w3c.dom.HTMLInputElement

/**
 * "Konflikte direkt lösen" -- the actions of one row of the LupaBB comparison: open the member, take over the LupaBB values of a held-back
 * (management role) row, link an ambiguous record to a member found by a search, skip it. The block owns no state beyond the search field;
 * what changed is handed back through [onChanged] (the refreshed page, or `null` when the call failed and only a repaint is needed).
 * Every string that comes from the server goes through [sanitizeUntrustedI18nText]; a link carries only a static label.
 */
internal class LupaSyncRowActions(
    private val api: LupaSyncApi,
    private val token: () -> String?,
    private val onExpired: () -> Unit,
    private val onChanged: (LupaBbPreviewPageDto?) -> Unit,
) {
    fun paint(
        container: Container,
        row: LupaBbPreviewRowDto,
    ) {
        // V1.9.303 (R58 exception): every action of this row is a small button WITH visible text, in a fixed order, "skip" last
        val wrapper = Div(className = "d-flex flex-column gap-2")
        container.add(wrapper)
        val ambiguous = row.category == LupaBbCategory.AMBIGUOUS
        if (ambiguous) {
            row.candidates.forEach { candidate ->
                wrapper.div {
                    addCssClasses("d-flex flex-wrap gap-2 align-items-center")
                    span(sanitizeUntrustedI18nText(candidate.name))
                    span("(${sanitizeUntrustedI18nText(candidate.email)})") { addCssClass("text-muted") }
                    linkButton(this, row, candidate.memberId, candidate.name, candidate.email)
                }
            }
            paintSearch(wrapper, row)
        }
        wrapper.div {
            addCssClasses("d-flex flex-wrap gap-2 align-items-center")
            paintProtected(this, row)
            LupaSyncLogic.memberToOpen(row)?.let { id ->
                val who = if (row.memberId != null) row.name else row.conflictMemberName.orEmpty()
                actionLink(
                    ActionIcon.VIEW,
                    tr("Mitglied öffnen"),
                    url = "#" + LupaSyncLogic.memberAdministrationRoute(id),
                    target = "_blank",
                    accessibleName = sanitizeUntrustedI18nText(gettext("Mitglied öffnen: %1", sanitizeUntrustedI18nText(who))),
                )
            }
            if (ambiguous) {
                actionButton(
                    ActionIcon.NEXT,
                    tr("Für diesen Abgleich überspringen"),
                    style = ButtonStyle.OUTLINESECONDARY,
                    small = true,
                    accessibleName =
                        sanitizeUntrustedI18nText(
                            gettext("%1 für diesen Abgleich überspringen", sanitizeUntrustedI18nText(row.name)),
                        ),
                ) {
                    onClick { confirmSkip(row) }
                }
            }
        }
    }

    // ---- take over the LupaBB values of a held-back row ----

    private fun paintProtected(
        container: Container,
        row: LupaBbPreviewRowDto,
    ) {
        if (row.protectedDiffs.isEmpty()) return
        when {
            row.isOwnRecord -> container.div(lupaReasonText(LupaBbRowReasonCode.OWN_RECORD)) { addCssClasses("small text-muted") }
            row.protectedBlocker != null -> container.div(lupaReasonText(row.protectedBlocker!!)) { addCssClasses("small text-muted") }
            LupaSyncLogic.canApplyProtected(row) -> {
                container.actionButton(
                    ActionIcon.SAVE,
                    tr("LupaBB-Werte übernehmen"),
                    style = ButtonStyle.OUTLINEPRIMARY,
                    small = true,
                    accessibleName =
                        sanitizeUntrustedI18nText(gettext("LupaBB-Werte übernehmen: %1", sanitizeUntrustedI18nText(row.name))),
                ) {
                    onClick { confirmProtected(row) }
                }
            }
        }
    }

    private fun confirmProtected(row: LupaBbPreviewRowDto) {
        val statusDiff = row.protectedDiffs.firstOrNull { it.field == LupaBbField.STATUS }
        confirmDialog(
            title = tr("LupaBB-Werte übernehmen?"),
            message =
                tr(
                    "Die Werte aus LupaBB überschreiben die Werte dieses Mitglieds mit Verwaltungsrolle. Die Rolle bleibt unverändert.",
                ),
            confirmLabel = gettext("%1 Werte übernehmen", row.protectedDiffs.size),
            confirmIcon = ActionIcon.SAVE,
            confirmStyle = LupaSyncLogic.protectedConfirmStyle(row),
            dangerNote =
                statusDiff?.let {
                    gettext("Status: %1 → %2", lupaDiffValue(LupaBbField.STATUS, it.before), lupaDiffValue(LupaBbField.STATUS, it.after))
                },
            body = { modal -> modal.add(lupaDiffTable(row.protectedDiffs)) },
        ) { applyProtected(row) }
    }

    private fun applyProtected(row: LupaBbPreviewRowDto) {
        val current = token() ?: return
        AppScope.launch {
            val result = memberAdminGuarded { expiring { api.applyProtected(current, row.personNumber) } }
            if (result == null) {
                onChanged(null)
                return@launch
            }
            if (result.applied) {
                notifySuccess(tr("Die LupaBB-Werte wurden übernommen."))
            } else if (result.failure == LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW) {
                notifyError(tr("Das Mitglied wurde seit der Vorschau geändert. Bitte laden Sie die Vorschau neu."))
            } else {
                notifyError(lupaReasonText(result.failure ?: LupaBbRowReasonCode.WRITE_FAILED))
            }
            onChanged(result.page)
        }
    }

    // ---- link an ambiguous record ----

    private fun linkButton(
        container: Container,
        row: LupaBbPreviewRowDto,
        memberId: String,
        name: String,
        email: String,
    ) {
        val who = "${sanitizeUntrustedI18nText(name)} (${sanitizeUntrustedI18nText(email)})"
        container.actionButton(
            ActionIcon.LINK,
            tr("Verknüpfen"),
            style = ButtonStyle.PRIMARY,
            small = true,
            accessibleName = sanitizeUntrustedI18nText(gettext("Mit %1 verknüpfen", who)),
        ) {
            onClick { confirmLink(row, memberId, name, email) }
        }
    }

    private fun confirmLink(
        row: LupaBbPreviewRowDto,
        memberId: String,
        name: String,
        email: String,
    ) {
        val emailStays =
            if (row.reasons.any { it.code == LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER }) {
                tr("Die E-Mail-Adresse aus LupaBB wird nicht übernommen, sie gehört bereits einem anderen Mitglied.")
            } else {
                null
            }
        confirmDialog(
            title = tr("Mit diesem Mitglied verknüpfen?"),
            message =
                tr(
                    "Die Personennummer aus LupaBB wird diesem Mitglied zugeordnet. Die Daten werden beim nächsten Abgleich übernommen.",
                ),
            extraLines = listOfNotNull(sanitizeUntrustedI18nText(name), sanitizeUntrustedI18nText(email), emailStays),
            confirmLabel = tr("Verknüpfen"),
            confirmIcon = ActionIcon.LINK,
            confirmStyle = ButtonStyle.PRIMARY,
        ) { link(row, memberId) }
    }

    private fun link(
        row: LupaBbPreviewRowDto,
        memberId: String,
    ) {
        val current = token() ?: return
        AppScope.launch {
            val page = guarded { expiring { api.link(current, row.personNumber, memberId) } }
            if (page != null) notifySuccess(tr("Das Mitglied wurde verknüpft."))
            onChanged(page)
        }
    }

    // ---- search for the member ----

    private fun paintSearch(
        container: Container,
        row: LupaBbPreviewRowDto,
    ) {
        val details = Tag(TAG.DETAILS, className = "small w-100")
        details.add(Tag(TAG.SUMMARY, content = tr("Welche Person ist gemeint?")))
        val hintId = "lapis-lupabb-search-hint-${searchCounter++}"
        details.add(
            Tag(
                TAG.DIV,
                content = tr("Tippen Sie Name oder E-Mail des Mitglieds, das zu dieser LupaBB-Person gehört."),
                className = "small text-muted",
            ).also { it.setAttribute("id", hintId) },
        )
        val box = Tag(TAG.INPUT, className = "form-control form-control-sm")
        box.setAttribute("type", "search")
        box.setAttribute("autocomplete", "off")
        box.setAttribute("maxlength", LupaSyncLogic.MAX_SEARCH_LENGTH.toString())
        val searchLabel = resolvedAttributeText(tr("Name oder E-Mail suchen"))
        box.setAttribute("aria-label", searchLabel)
        box.setAttribute("aria-describedby", hintId)
        box.setAttribute("placeholder", searchLabel)
        val results = Tag(TAG.DIV, className = "mt-1")
        results.setAttribute("aria-live", "polite")
        var timer: Int? = null
        // a late answer of an earlier keystroke must never overwrite the answer of a later one
        var sequence = 0
        val search = { raw: String ->
            sequence++
            val mine = sequence
            val query = raw.trim()
            results.removeAll()
            if (LupaSyncLogic.isSearchQueryUsable(query)) {
                val current = token()
                if (current != null) {
                    AppScope.launch {
                        val found = guarded { expiring { api.searchTargets(current, row.personNumber, query) } }
                        if (mine == sequence && found != null) paintTargets(results, row, found)
                    }
                }
            }
        }
        box.onEvent {
            input = {
                val value = (box.getElement() as? HTMLInputElement)?.value.orEmpty()
                timer?.let { window.clearTimeout(it) }
                timer = window.setTimeout({ search(value) }, SEARCH_DEBOUNCE_MS)
            }
        }
        details.add(box)
        details.add(results)
        container.add(details)
    }

    private fun paintTargets(
        results: Tag,
        row: LupaBbPreviewRowDto,
        found: List<LupaBbLinkTargetDto>,
    ) {
        results.removeAll()
        if (found.isEmpty()) {
            results.div(tr("Kein Mitglied gefunden.")) { addCssClass("text-muted") }
            return
        }
        found.forEach { target ->
            results.div {
                addCssClasses("d-flex flex-wrap gap-2 align-items-center")
                span(sanitizeUntrustedI18nText(target.name))
                span(" (${sanitizeUntrustedI18nText(target.email)})") { addCssClass("text-muted") }
                span(memberStatusLabel(target.status)) { addCssClasses("badge text-bg-light") }
                if (target.linkedElsewhere) {
                    val reasonId = "lapis-lupabb-link-reason-${searchCounter++}"
                    val reason =
                        target.linkedPersonNumber?.let {
                            gettext("Nicht verknüpfbar: bereits mit LupaBB-Person Nr. %1 verknüpft.", LupaSyncLogic.personNumberText(it))
                        } ?: tr("Nicht verknüpfbar: bereits mit einer anderen LupaBB-Person verknüpft.")
                    span(reason) {
                        addCssClasses("text-muted small")
                        setAttribute("id", reasonId)
                    }
                    actionButton(ActionIcon.LINK, tr("Verknüpfen"), style = ButtonStyle.PRIMARY, small = true) {
                        disabled = true
                        setAttribute("aria-describedby", reasonId)
                    }
                } else {
                    linkButton(this, row, target.memberId, target.name, target.email)
                }
            }
        }
    }

    // ---- skip ----

    private fun confirmSkip(row: LupaBbPreviewRowDto) {
        confirmDialog(
            title = tr("Für diesen Abgleich überspringen?"),
            message = tr("Dieser Datensatz wird in diesem Abgleich nicht übernommen. Beim nächsten Abgleich erscheint er wieder."),
            confirmLabel = tr("Überspringen"),
            confirmIcon = ActionIcon.NEXT,
            confirmStyle = ButtonStyle.PRIMARY,
        ) { skip(row) }
    }

    private fun skip(row: LupaBbPreviewRowDto) {
        val current = token() ?: return
        AppScope.launch {
            val page = guarded { expiring { api.skip(current, row.personNumber) } }
            onChanged(page)
        }
    }

    private suspend fun <T> expiring(block: suspend () -> T): T =
        try {
            block()
        } catch (e: LupaBbPreviewExpiredException) {
            onExpired()
            throw e
        }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300

        /** V1.9.303 -- DOM-id counter: never a member id or a person number in an id. */
        var searchCounter = 0
    }
}

/** The "field | before | after" table of a comparison (shared by the details of a row and the take-over dialog). */
internal fun lupaDiffTable(diffs: List<LupaBbFieldDiffDto>): Tag {
    val table = Tag(TAG.TABLE, className = "lapis-lupabb-diff")
    val head = Tag(TAG.THEAD)
    val headRow = Tag(TAG.TR)
    listOf(tr("Feld"), tr("bisher"), tr("neu")).forEach { headRow.add(Tag(TAG.TH, content = it)) }
    head.add(headRow)
    table.add(head)
    val body = Tag(TAG.TBODY)
    diffs.forEach { diff ->
        val tableRow = Tag(TAG.TR)
        tableRow.add(Tag(TAG.TD, content = lupaFieldLabel(diff.field)))
        tableRow.add(
            Tag(
                TAG.TD,
                content = sanitizeUntrustedI18nText(lupaDiffValue(diff.field, diff.before)),
                className = "lapis-lupabb-diff-old",
            ),
        )
        tableRow.add(Tag(TAG.TD, content = sanitizeUntrustedI18nText(lupaDiffValue(diff.field, diff.after))))
        body.add(tableRow)
    }
    table.add(body)
    return table
}
