package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticeInput
import network.lapis.cloud.shared.domain.NoticeListQuery
import network.lapis.cloud.shared.domain.NoticePageDto
import network.lapis.cloud.shared.domain.NoticeRemovalInput
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeRules
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.domain.NoticeUpdateInput
import network.lapis.cloud.shared.rpc.INoticeBoardService
import network.lapis.cloud.shared.rpc.NotFoundException

/**
 * V1.9.297 "Schwarzes Brett" -- the members' bulletin board (see `docs/features/bulletin-board.adoc`).
 *
 * Layout: the page header with "Aushang aufgeben" (the collapsible create form, also used for "Bearbeiten"), an optional deep-link block
 * (`?notice=<id>` from the LTR statement), a category switch (symbol plus word, `aria-pressed`), the pinned notices of the board ("Vom Vorstand"),
 * the notices in LTR order (one column, two from the `md` breakpoint) and "Meine Aushänge" (every state, with the removal reason).
 *
 * Titles, texts, names and picture descriptions are free text of another member: every composed display goes through [untrustedDiv] /
 * [sanitizeUntrustedI18nText]; a title or text is NEVER rendered as markup. The weight pill appears only from 0.01 LTR; an expired or withdrawn
 * notice is never greyed out in the list (it is simply not there). A polite live region announces every change.
 */
fun renderNoticeBoardScreen(
    container: SimplePanel,
    hash: String = "#${Routes.NOTICE_BOARD}",
) {
    sharedLtrBalanceSource.invalidate()
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Schwarzes Brett"), intro = PageIntros.forRoute(Routes.NOTICE_BOARD))
    val liveRegion =
        root.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val formHost = root.vPanel(spacing = 6)
    lateinit var formController: CollapsibleCreateFormController<NoticeFormPrefill>
    lateinit var refreshAll: () -> Unit
    var dates = NoticeDates(default = null, min = null, max = null)
    val ui =
        NoticeUi(
            announce = { liveRegion.content = it },
            onChanged = { refreshAll() },
            openForm = { formController.open(it) },
        )

    val focusId = parseHashQueryParam(hash, "notice")
    if (focusId != null) renderDeepLinkedNotice(root.vPanel(spacing = 6), focusId, ui)

    var category: NoticeCategory? = null
    val switchButtons = LinkedHashMap<NoticeCategory?, Button>()
    lateinit var boardSection: DataSection
    val switchRow = root.div(className = "lapis-action-group")
    switchRow.setAttribute("role", "group")
    switchRow.setAttribute("aria-label", gettext("Kategorie"))

    fun syncSwitch() {
        switchButtons.forEach { (key, b) ->
            val pressed = key == category
            b.style = if (pressed) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
            b.setAttribute("aria-pressed", pressed.toString())
        }
    }
    listOf<NoticeCategory?>(null).plus(NoticeCategory.entries).forEach { key ->
        val label = if (key == null) tr("Alle") else noticeCategoryLabel(key)
        val b = switchRow.button(label, icon = key?.let { noticeCategoryIcon(it) }, style = ButtonStyle.OUTLINESECONDARY)
        switchButtons[key] = b
        b.onClick {
            category = key
            syncSwitch()
            boardSection.reload()
        }
    }
    syncSwitch()

    boardSection =
        root.dataSection<NoticePageDto>(
            emptyText = tr("Noch keine Aushänge. Suchen Sie etwas oder bieten Sie etwas an? Hängen Sie den ersten Aushang aus."),
            filterTerm = { category?.let { noticeCategoryLabel(it) } },
            noMatchText = { term -> gettext("Keine Aushänge der Kategorie \"%1\".", term) },
            isEmpty = { it.pinned.isEmpty() && it.items.isEmpty() },
            onSettled = { page ->
                if (page != null) dates = NoticeDates(default = page.defaultExpiresOn, min = page.minExpiresOn, max = page.maxExpiresOn)
            },
            load = { guarded { rpcService<INoticeBoardService>().listNotices(NoticeListQuery(category = category)) } },
            render = { panel, page -> renderBoard(panel, page, category, ui) },
        )

    val mineBlock = root.vPanel(spacing = 6)
    mineBlock.h2(tr("Meine Aushänge")) { addCssClass("h5") }
    val mineSection =
        mineBlock.dataSection<List<NoticeDto>>(
            emptyText = "",
            isEmpty = { it.isEmpty() },
            onSettled = { items -> mineBlock.visible = items == null || items.isNotEmpty() },
            load = { guarded { rpcService<INoticeBoardService>().listMyNotices() } },
            render = { panel, items -> items.forEach { renderNoticeCard(panel, it, ui) } },
        )

    refreshAll = {
        boardSection.reload()
        mineSection.reload()
    }
    formController =
        collapsibleCreateForm<NoticeFormPrefill>(
            actionSlot = header.actionSlot,
            formHost = formHost,
            buttonLabel = tr("Aushang aufgeben"),
            formId = "notice-board-form",
        ) { prefill, close ->
            buildNoticeForm(this, prefill, dates, close, ui)
        }
    refreshAll()
}

/** The date bounds of a new notice as the last board load reported them (the server stays authoritative). */
internal class NoticeDates(
    val default: LocalDate?,
    val min: LocalDate?,
    val max: LocalDate?,
)

/** What a card hands back to the screen: the live region, the reload and the shared form (edit). Passed as functions, no global registry. */
internal class NoticeUi(
    val announce: (String) -> Unit,
    val onChanged: () -> Unit,
    val openForm: (NoticeFormPrefill) -> Unit,
)

/** What "Bearbeiten" hands to the shared form: the notice to edit, or nothing for a new one. */
internal class NoticeFormPrefill(
    val editing: NoticeDto?,
)

private fun renderBoard(
    panel: SimplePanel,
    page: NoticePageDto,
    category: NoticeCategory?,
    ui: NoticeUi,
) {
    if (page.pinned.isNotEmpty()) {
        val pinnedBox = panel.vPanel(spacing = 8) { addCssClasses("lapis-notice-pinned border rounded p-2") }
        val title = pinnedBox.hPanel(spacing = 6) { addCssClasses("align-items-center") }
        title.icon("fas fa-thumbtack") { setAttribute("aria-hidden", "true") }
        title.h2(tr("Vom Vorstand")) { addCssClasses("h6 mb-0") }
        page.pinned.forEach { renderNoticeCard(pinnedBox, it, ui) }
    }
    if (page.items.isNotEmpty()) {
        panel.h2(tr("Aushänge")) { addCssClasses("h5 mt-2") }
        val grid = panel.div(className = "lapis-notice-grid")
        page.items.forEach { renderNoticeCard(grid, it, ui) }
        renderMore(panel, grid, page, category, ui)
    }
}

/** "Weitere Aushänge anzeigen": the next page is fetched and appended below; the button vanishes when everything is shown. */
private fun renderMore(
    panel: SimplePanel,
    grid: Container,
    first: NoticePageDto,
    category: NoticeCategory?,
    ui: NoticeUi,
) {
    var shown = first.items.size
    if (shown >= first.total) return
    lateinit var moreButton: Button
    moreButton = panel.actionButton(ActionIcon.MORE, tr("Weitere Aushänge anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    moreButton.onClick {
        runGuardedAction(moreButton) {
            val next =
                guarded { rpcService<INoticeBoardService>().listNotices(NoticeListQuery(category = category, offset = shown)) }
                    ?: return@runGuardedAction
            next.items.forEach { renderNoticeCard(grid, it, ui) }
            shown += next.items.size
            if (shown >= next.total || next.items.isEmpty()) panel.remove(moreButton)
        }
    }
}

/** The notice the LTR statement linked to, or a calm line when it is gone (expired, withdrawn, removed or deleted: ONE answer). */
private fun renderDeepLinkedNotice(
    host: SimplePanel,
    id: String,
    ui: NoticeUi,
) {
    AppScope.launch {
        val notice =
            try {
                rpcService<INoticeBoardService>().getNotice(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotFoundException) {
                null
            } catch (e: Throwable) {
                host.div(tr("Der Aushang konnte nicht geladen werden.")) { addCssClasses("text-muted small") }
                return@launch
            }
        if (notice == null) {
            host.div(tr("Dieser Aushang ist nicht mehr vorhanden.")) { addCssClasses("text-muted small") }
        } else {
            renderNoticeCard(host, notice, ui)
        }
    }
}

private fun renderNoticeCard(
    panel: Container,
    notice: NoticeDto,
    ui: NoticeUi,
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("lapis-notice-card border rounded p-2") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.icon(noticeCategoryIcon(notice.category)) { setAttribute("aria-hidden", "true") }
    headerRow.typeBadge(noticeCategoryLabel(notice.category), noticeCategoryColor(notice.category))
    if (notice.pinnedAt != null) headerRow.typeBadge(tr("Vom Vorstand"), "dark")
    if (notice.isMine) headerRow.typeBadge(tr("Ihr Aushang"), "secondary")
    if (notice.status != NoticeStatus.ACTIVE) headerRow.statusBadge(noticeStatusLabel(notice.status), "secondary")
    card.untrustedCardTitle(notice.title)

    val body = card.untrustedDiv(notice.body, className = "small lapis-notice-body")
    if (noticeBodyNeedsFold(notice.body)) {
        body.addCssClass("lapis-notice-body--folded")
        body.setAttribute("aria-expanded", "false")
        var open = false
        lateinit var toggle: Button
        toggle =
            card.actionButton(ActionIcon.MORE, tr("Mehr anzeigen"), style = ButtonStyle.LINK) {
                setAttribute("aria-expanded", "false")
            }
        toggle.onClick {
            open = !open
            if (open) body.removeCssClass("lapis-notice-body--folded") else body.addCssClass("lapis-notice-body--folded")
            body.setAttribute("aria-expanded", open.toString())
            toggle.setAttribute("aria-expanded", open.toString())
            toggle.text = if (open) tr("Weniger anzeigen") else tr("Mehr anzeigen")
        }
    }
    notice.image?.let { image ->
        val alt = image.altText?.takeIf { it.isNotBlank() } ?: gettext("Bild zum Aushang \"%1\"", notice.title)
        card.image(image.url, sanitizeUntrustedI18nText(alt)) {
            addCssClass("lapis-notice-image")
            setAttribute("loading", "lazy")
            setAttribute("decoding", "async")
        }
    }

    val meta = card.hPanel(spacing = 8) { addCssClasses("text-muted small flex-wrap align-items-center") }
    meta.untrustedDiv(gettext("von %1", notice.authorDisplayName))
    meta.div(gettext("aufgegeben am %1", formatDate(systemDate(notice.createdAt))))
    if (notice.updatedAt != null) meta.div(tr("bearbeitet"))
    if (notice.status == NoticeStatus.ACTIVE) meta.div(noticeExpiryText(notice))
    if (noticeShowsWeight(notice)) {
        val weight = meta.hPanel(spacing = 4) { addCssClass("align-items-center") }
        weight.div(tr("Gewicht"))
        weight.ltrSpan(notice.weightLtr)
    }
    val removalReason = notice.removalReason
    if (removalReason != null && notice.status == NoticeStatus.REMOVED) {
        card.div(gettext("Entfernt: %1", noticeRemovalReasonLabel(removalReason))) { addCssClasses("small text-danger") }
        notice.removalNote?.takeIf { it.isNotBlank() }?.let { card.untrustedDiv(it, className = "small text-muted") }
    }

    val actions = card.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center mt-1") }
    if (notice.canReply) {
        var replyPanel: SimplePanel? = null
        actions.actionButton(ActionIcon.SEND, tr("Antworten"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            if (replyPanel != null) return@onClick
            replyPanel = renderReplyForm(card, notice, ui) { replyPanel = null }
        }
    }
    if (notice.canBoost) {
        val boostRow = card.div(className = "lapis-notice-boost")
        ltrBoostControl(
            row = boostRow,
            balance = sharedLtrBalanceSource,
            confirmTitle = tr("Aushang boosten"),
            successText = tr("Aushang geboostet."),
            onBoost = { amount -> guarded { rpcService<INoticeBoardService>().boostNotice(notice.id, amount) } != null },
            onChanged = ui.onChanged,
        )
    }
    if (notice.canEdit) {
        actions.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            ui.openForm(NoticeFormPrefill(editing = notice))
        }
    }
    if (notice.canWithdraw) renderWithdrawControl(actions, notice, ui)
    if (notice.canModerate) renderModerationControls(actions, card, notice, ui)
}

private fun renderWithdrawControl(
    actions: Container,
    notice: NoticeDto,
    ui: NoticeUi,
) {
    val withdraw = actions.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
    withdraw.onClick {
        confirmDialog(
            title = tr("Aushang zurückziehen"),
            message = tr("Der Aushang verschwindet vom Schwarzen Brett. Bereits eingesetzte LTR werden NICHT zurückerstattet."),
            confirmLabel = tr("Zurückziehen"),
        ) {
            runGuardedAction(withdraw) {
                if (guarded { rpcService<INoticeBoardService>().withdrawNotice(notice.id) } != null) {
                    val message = gettext("Aushang zurückgezogen.")
                    notifySuccess(message)
                    ui.announce(message)
                    ui.onChanged()
                }
            }
        }
    }
}

private fun renderModerationControls(
    actions: Container,
    card: SimplePanel,
    notice: NoticeDto,
    ui: NoticeUi,
) {
    val pinned = notice.pinnedAt != null
    val pin =
        actions.actionButton(
            ActionIcon.PIN,
            if (pinned) tr("Nicht mehr pinnen") else tr("Pinnen"),
            style = ButtonStyle.OUTLINESECONDARY,
        )
    pin.setAttribute("aria-pressed", pinned.toString())
    pin.onClick {
        runGuardedAction(pin) {
            val result =
                guarded {
                    if (pinned) {
                        rpcService<INoticeBoardService>().unpinNotice(
                            notice.id,
                        )
                    } else {
                        rpcService<INoticeBoardService>().pinNotice(notice.id)
                    }
                }
            if (result != null) {
                val message = if (pinned) gettext("Aushang nicht mehr gepinnt.") else gettext("Aushang gepinnt.")
                notifySuccess(message)
                ui.announce(message)
                ui.onChanged()
            }
        }
    }
    if (!notice.isMine) {
        var removePanel: SimplePanel? = null
        actions.actionButton(ActionIcon.REVOKE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER).onClick {
            if (removePanel != null) return@onClick
            removePanel = renderRemoveForm(card, notice, ui) { removePanel = null }
        }
    }
}

private fun renderReplyForm(
    card: SimplePanel,
    notice: NoticeDto,
    ui: NoticeUi,
    onClosed: () -> Unit,
): SimplePanel {
    val panel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    panel.untrustedDiv(
        gettext("Ihre Nachricht geht als Direktnachricht an den Autor. Betreff: Schwarzes Brett: %1", notice.title),
        className = "small text-muted",
    )
    val form = panel.lapisForm()
    val messageField = form.textAreaField(label = tr("Nachricht"), rows = 3, required = true)
    val sendButton = newActionButton(ActionIcon.SEND, tr("Senden"), ButtonStyle.PRIMARY)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY)
    form.buttons(primary = sendButton, cancel = cancelButton)
    cancelButton.onClick {
        card.remove(panel)
        onClosed()
    }
    sendButton.onClick {
        form.submit(sendButton) {
            if (guarded { rpcService<INoticeBoardService>().replyToNotice(notice.id, messageField.value.trim()) } != null) {
                val message = gettext("Nachricht wurde gesendet. Antworten finden Sie unter Nachrichten.")
                notifySuccess(message)
                ui.announce(message)
                card.remove(panel)
                onClosed()
            }
        }
    }
    return panel
}

private fun renderRemoveForm(
    card: SimplePanel,
    notice: NoticeDto,
    ui: NoticeUi,
    onClosed: () -> Unit,
): SimplePanel {
    val panel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    panel.div(tr("Bereits gebuchte LTR werden nicht erstattet.")) { addCssClasses("small text-muted") }
    val form = panel.lapisForm()
    val reasonField =
        form.selectField(
            label = tr("Grund"),
            options = NoticeRemovalReason.entries.map { it.name to noticeRemovalReasonLabel(it) },
            value = NoticeRemovalReason.SPAM.name,
            required = true,
        )
    val noteField =
        form.textAreaField(
            label = tr("Notiz (optional)"),
            rows = 2,
            hint = gettext("Höchstens %1 Zeichen. Der Autor sieht Grund und Notiz.", NoticeRules.REMOVAL_NOTE_MAX),
        )
    val removeButton = newActionButton(ActionIcon.REVOKE, tr("Entfernen"), ButtonStyle.DANGER)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY)
    form.buttons(primary = removeButton, cancel = cancelButton)
    cancelButton.onClick {
        card.remove(panel)
        onClosed()
    }
    removeButton.onClick {
        form.submit(removeButton) {
            val input =
                NoticeRemovalInput(
                    reason = NoticeRemovalReason.valueOf(reasonField.value.orEmpty()),
                    note = noteField.value.trim().takeIf { it.isNotEmpty() },
                )
            if (guarded { rpcService<INoticeBoardService>().removeNotice(notice.id, input) } != null) {
                val message = gettext("Aushang entfernt.")
                notifySuccess(message)
                ui.announce(message)
                card.remove(panel)
                onClosed()
                ui.onChanged()
            }
        }
    }
    return panel
}

/**
 * The create / edit form, built into the shared host of [collapsibleCreateForm]. A NEW notice may carry an optional LTR stake (balance line,
 * confirmation when it is above zero); an edited one cannot (a stake is a boost, made on the card). The picture comes AFTER the first save: the
 * owner id must exist, so a saved new notice reopens as an edit with the picture editor. A failed upload leaves the notice without a picture.
 */
private fun buildNoticeForm(
    host: SimplePanel,
    prefill: NoticeFormPrefill?,
    dates: NoticeDates,
    close: (saved: Boolean) -> Unit,
    ui: NoticeUi,
): FormSnapshot {
    val editing = prefill?.editing
    val panel = host.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    panel.div(if (editing != null) tr("Aushang bearbeiten") else tr("Neuer Aushang")) { addCssClass("fw-bold") }
    val form = panel.lapisForm()
    val categoryField =
        form.selectField(
            label = tr("Kategorie"),
            options = NoticeCategory.entries.map { it.name to noticeCategoryLabel(it) },
            value = (editing?.category ?: NoticeCategory.OFFER).name,
            required = true,
            hint =
                if (editing?.categoryLocked ==
                    true
                ) {
                    tr("Die Kategorie kann nicht mehr geändert werden, weil andere LTR eingesetzt haben.")
                } else {
                    null
                },
            init = { if (editing?.categoryLocked == true) it.disabled = true },
        )
    val titleField =
        form.textField(
            label = tr("Titel"),
            value = editing?.title,
            required = true,
            hint = gettext("%1 bis %2 Zeichen.", NoticeRules.TITLE_MIN, NoticeRules.TITLE_MAX),
            init = { it.input.setAttribute("maxlength", NoticeRules.TITLE_MAX.toString()) },
        )
    val bodyField =
        form.textAreaField(
            label = tr("Text"),
            rows = 6,
            value = editing?.body,
            required = true,
            hint = gettext("Höchstens %1 Zeichen.", NoticeRules.BODY_MAX),
            init = { it.input.setAttribute("maxlength", NoticeRules.BODY_MAX.toString()) },
        )
    val dateField =
        form.localDateField(
            label = tr("Sichtbar bis (einschließlich)"),
            value = editing?.expiresOn ?: dates.default,
            required = true,
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            min = editing?.minExpiresOn ?: dates.min,
            max = editing?.maxExpiresOn ?: dates.max,
            extraHint = gettext("Höchstens %1 Tage ab dem Aufhängen.", NoticeRules.MAX_DAYS),
        )
    var stakeField: LapisField? = null
    if (editing == null) {
        val balanceLine = panel.div("", className = "text-muted small")
        AppScope.launch {
            val free = sharedLtrBalanceSource.current() ?: return@launch
            balanceLine.content = gettext("Freies Guthaben: %1", formatLtr(free))
        }
        stakeField =
            form.textField(
                label = tr("LTR-Einsatz (optional)"),
                hint = tr("Je mehr LTR Sie einsetzen, desto weiter oben steht der Aushang. Der Betrag wird nicht zurückerstattet."),
                init = { it.input.setAttribute("inputmode", "decimal") },
            )
    }
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton, cancel = collapseCancelButton(close))

    fun save(stake: dev.kilua.rpc.types.Decimal?) {
        runGuardedAction(saveButton) {
            val expiresOn = checkNotNull(dateField.localDateOrNull())
            val category = NoticeCategory.valueOf(categoryField.value.orEmpty())
            val saved =
                guarded {
                    if (editing != null) {
                        rpcService<INoticeBoardService>().updateNotice(
                            editing.id,
                            NoticeUpdateInput(category, titleField.value.trim(), bodyField.value.trim(), expiresOn),
                        )
                    } else {
                        rpcService<INoticeBoardService>().createNotice(
                            NoticeInput(category, titleField.value.trim(), bodyField.value.trim(), expiresOn, stake),
                        )
                    }
                }
            if (saved != null) {
                val message = if (editing != null) gettext("Aushang wurde aktualisiert.") else gettext("Aushang wurde aufgegeben.")
                notifySuccess(message)
                ui.announce(message)
                sharedLtrBalanceSource.invalidate()
                close(true)
                ui.onChanged()
                // the picture needs the notice id: a new notice reopens as an edit, where the picture editor is
                if (editing == null) ui.openForm(NoticeFormPrefill(editing = saved))
            }
        }
    }
    saveButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val stakeText = stakeField?.value?.trim().orEmpty()
        if (stakeText.isEmpty()) {
            save(null)
            return@onClick
        }
        val parsed = parseLtrAmountInput(stakeText)
        if (parsed !is AmountInput.Valid) {
            stakeField?.showError(amountErrorText(parsed, tr("Bitte einen positiven Betrag (LTR) angeben.")))
            return@onClick
        }
        confirmDialog(
            title = tr("LTR einsetzen"),
            message =
                gettext(
                    "Sie zahlen %1 aus Ihrem freien LTR-Guthaben. Dieser Betrag wird NICHT zurückerstattet.",
                    formatLtr(parsed.value),
                ),
            confirmLabel = tr("Aushang aufgeben"),
        ) { save(parsed.value) }
    }
    if (editing != null) {
        val pictureHost = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-1") }
        pictureHost.loadGalleryInto(
            ownerType = AttachedImageOwnerType.NOTICE,
            ownerId = editing.id,
            ownerTitle = gettext("Ihr Aushang"),
            onChange = ui.onChanged,
        )
    }
    return form.snapshot()
}
