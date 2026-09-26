package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.McpPostDraftDto
import network.lapis.cloud.shared.domain.McpPostDraftEditInput
import network.lapis.cloud.shared.domain.McpPostDraftReleaseInput
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import kotlin.time.Duration.Companion.days

/**
 * Welle V1.8.2b -- the member's own "KI-Entwürfe" screen: every [McpPostDraftStatus.OPEN] and
 * still-restorable [McpPostDraftStatus.DISCARDED] draft an MCP agent created via `create_post_draft`
 * (`docs/architecture/mcp-server.adoc` "The draft lifecycle"). NOT reachable by an agent itself --
 * this is the ordinary member-facing RPC surface ([ISocialNetworkService.listMyPostDrafts] et al.),
 * session-authenticated like every other screen (see that interface's own KDoc "no switch gates
 * these").
 *
 * **Card list, not [dataTable]** -- deliberate design-team decision (`docs/architecture/
 * ui-ux-guideline.adoc`): at most [network.lapis.cloud.server.social.PostDraftStore
 * .MAX_OPEN_DRAFTS_PER_MEMBER] (10) open drafts plus a handful of still-restorable discarded ones,
 * no sorting/filtering/pagination need, and the card must visually anticipate the post it becomes --
 * same card shape [renderSocialPostCard] already uses for a real, published post.
 *
 * **Reachable regardless of `mcpEnabled`/`mcpWriteEnabled`** -- only the SIDEBAR entry is gated
 * ([NavVisibility.showsAiDrafts]), never the route itself (see `Routes.AI_DRAFTS` KDoc): a member
 * with existing drafts must always be able to reach, edit, release, or discard them, even after an
 * operator switches the agent interface off entirely.
 */
fun renderAiDraftsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }

    val session = AppState.session
    val mcpEnabled = session?.mcpEnabled == true
    val mcpWriteEnabled = session?.mcpWriteEnabled == true

    root.pageHeader(
        tr("KI-Entwürfe"),
        banners = {
            if (mcpEnabled && !mcpWriteEnabled) {
                div {
                    addCssClasses("alert alert-warning small mb-0")
                    icon("fas fa-wand-magic-sparkles") { setAttribute("aria-hidden", "true") }
                    span(" ")
                    span(
                        tr(
                            "Der Betreiber hat Schreibwerkzeuge für KI-Agenten abgeschaltet. Vorhandene " +
                                "Entwürfe können Sie weiterhin bearbeiten, freigeben oder verwerfen.",
                        ),
                    )
                }
            }
        },
    )

    val listPanel = root.vPanel(spacing = 10)
    lateinit var section: DataSection
    section =
        listPanel.dataSection<List<McpPostDraftDto>>(
            emptyText =
                if (mcpEnabled) {
                    tr("Noch keine Entwürfe. Ein verbundener KI-Agent kann hier Beitrags-Entwürfe für Sie ablegen.")
                } else {
                    tr("Der Betreiber hat die Agenten-Schnittstelle abgeschaltet.")
                },
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<ISocialNetworkService>().listMyPostDrafts() } },
            render = { panel, drafts -> drafts.forEach { draft -> renderDraftCard(panel, draft) { section.reload() } } },
        )
    section.reload()
}

private fun renderDraftCard(
    panel: SimplePanel,
    draft: McpPostDraftDto,
    onChanged: () -> Unit,
) {
    val isDiscarded = draft.status == McpPostDraftStatus.DISCARDED
    val card =
        panel.vPanel(spacing = 6) {
            addCssClasses("border rounded p-3")
            if (isDiscarded) addCssClasses("opacity-50")
        }

    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.icon("fas fa-wand-magic-sparkles") { setAttribute("aria-hidden", "true") }
    headerRow.statusBadge(mcpPostDraftStatusLabel(draft.status), mcpPostDraftStatusColor(draft.status))
    headerRow.statusBadge(socialPostVisibilityLabel(draft.visibility), socialPostVisibilityColor(draft.visibility))
    // Kares Regel: die Agenten-Herkunft ist niemals eine Überschrift, niemals fett, niemals ein
    // `title`-Attribut -- ein selbst gewählter, ungeprüfter Name darf nie wie eine vertrauenswürdige
    // Kennzeichnung wirken.
    headerRow.span(tr("Entworfen von Agent")) { addCssClasses("text-muted small") }
    headerRow.untrustedSpan("„${draft.agentLabel}“", className = "text-muted small text-truncate") { maxWidth = 240.px }

    card.untrustedDiv(draft.content, className = "small")

    when (draft.status) {
        McpPostDraftStatus.OPEN -> {
            // MAJOR security fix (V1.8.2 wave 3, release-integrity): [renderReleaseControl]'s button
            // must never publish an unsaved edit's PREVIOUS (server-stored) content -- see that
            // function's KDoc. `dirtyGate` is the wiring: [renderDraftEditForm] reports every
            // dirty/clean transition into it, [renderReleaseControl] subscribes to disable/hide its
            // own button and show a hint while an edit is unsaved. A plain callback pair (not a
            // return value) because the edit form's panel must render ABOVE the release panel
            // (visual order, K4/D3) while the release control still needs to react to a dirty state
            // that is only known once the edit form exists -- subscribing after publish is fine, the
            // gate replays its current value to every new subscriber.
            val dirtyGate = DraftDirtyGate()
            renderDraftEditForm(card, draft, onChanged, dirtyGate::setDirty)
            renderReleaseControl(card, draft, onChanged, dirtyGate::onDirtyChanged)
            renderDiscardControl(card, draft, onChanged)
        }
        McpPostDraftStatus.DISCARDED -> renderDiscardedFooter(card, draft, onChanged)
        McpPostDraftStatus.RELEASED -> Unit // listMyPostDrafts() never returns a RELEASED draft, see PostDraftStore.listVisible KDoc.
    }
}

/**
 * Tiny pub/sub for one draft card's "unsaved edit" state -- see [renderDraftCard]'s `OPEN` branch
 * KDoc for why this indirection exists instead of a direct return value. [onDirtyChanged] replays
 * the CURRENT value immediately to a new subscriber (matters here: [renderReleaseControl] always
 * subscribes after [renderDraftEditForm] has already run, so it must not miss whatever state
 * already holds -- though in practice that is always `false`, since nothing marks the gate dirty
 * before the user's first keystroke).
 */
private class DraftDirtyGate {
    private var dirty = false
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun setDirty(value: Boolean) {
        dirty = value
        listeners.forEach { it(value) }
    }

    fun onDirtyChanged(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(dirty)
    }
}

/** Edit an `OPEN` draft's content/visibility -- no auto-save, the button is only active once a value actually changed. */
private fun renderDraftEditForm(
    card: SimplePanel,
    draft: McpPostDraftDto,
    onChanged: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
) {
    val callerStatus = AppState.session?.status
    val form = card.lapisForm()
    val contentField =
        form.textAreaField(label = tr("Beitragstext"), rows = 6, value = draft.content, required = true, rule = { value ->
            if (Validation.isNonBlank(value)) FieldCheck.Ok else FieldCheck.Invalid(gettext("Bitte einen Beitragstext angeben."))
        })
    val visibilityOptions = SocialComposerVisibility.allowedVisibilities(callerStatus).map { it.name to socialPostVisibilityLabel(it) }
    val visibilityField =
        form.selectField(label = tr("Sichtbarkeit"), options = visibilityOptions, value = draft.visibility.name, required = true)

    val saveButton = Button(tr("Änderungen speichern"), style = ButtonStyle.OUTLINEPRIMARY).apply { disabled = true }
    form.buttons(primary = saveButton)

    fun currentlyChanged(): Boolean = contentField.value.trim() != draft.content || visibilityField.value != draft.visibility.name

    fun refreshSaveEnabled() {
        val changed = currentlyChanged()
        saveButton.disabled = !changed
        onDirtyChanged(changed)
    }
    contentField.subscribe { refreshSaveEnabled() }
    visibilityField.subscribe { refreshSaveEnabled() }

    saveButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val visibility = parseOptionalEnum<SocialPostVisibility>(visibilityField.value) ?: draft.visibility
        form.runBusy(saveButton) {
            val result =
                guarded {
                    rpcService<ISocialNetworkService>().updateMyPostDraft(
                        McpPostDraftEditInput(draftId = draft.id, content = contentField.value.trim(), visibility = visibility),
                    )
                }
            if (result != null) {
                notifySuccess(tr("Entwurf gespeichert."))
                onChanged()
            }
        }
    }
}

/**
 * Freigeben -- D3-Reihenfolge ([renderMyLtrBalanceInline] über dem Feld, wie
 * `SocialNetworkScreen.kt`s Compose-Formular), LTR-Betrag über die Formular-Grammatik (S2), dann
 * [confirmDialog] (Tier 1 "Kostenpflichtig", Hausmuster wie `renderBoostControl`/`AuctionScreen`).
 *
 * **MAJOR security fix (V1.8.2 wave 3, release-integrity)**: [ISocialNetworkService
 * .releaseMyPostDraft] publishes the SERVER-STORED draft content -- it takes no `content`/
 * `visibility` of its own, the server reads both off the `mcp_post_draft` row. If the member had
 * unsaved edits in [renderDraftEditForm]'s textarea (deleted a sentence, say) but never clicked
 * "Änderungen speichern", clicking this button used to publish the UNCHANGED agent text anyway --
 * and once published, a `social_post` has no `updatePost` (see `ISocialNetworkService` KDoc "the
 * post is immutable after release"). `subscribeDirty` (wired from [DraftDirtyGate] in
 * [renderDraftCard]) disables this button and shows a hint the instant the edit form goes dirty,
 * so the member is forced to save (or discard) their edit first -- what gets published is always
 * exactly what the confirm dialog's own text describes.
 *
 * **MINOR security fix (V1.8.2 review round 2, restlücke in the MAJOR fix above)**: `.disabled` used
 * to have two independent writers -- the `subscribeDirty` listener below, and `runGuardedAction`'s
 * (`FormGrammar.kt`) own `finally`, which unconditionally reset it to `false` and, being the LAST
 * writer for the duration of a release click, always won. Because [confirmDialog] hides its modal
 * BEFORE running its confirm callback (`ConfirmDialog.kt`), the edit textarea stays interactive for
 * the entire release round-trip: an edit made (or reverted) while the request is in flight, or a
 * request that fails (`guarded {}` returns `null` -- no [onChanged]/reload happens), used to leave
 * the button re-enabled regardless of the CURRENT dirty state, contradicting the KDoc paragraph
 * above. `draftIsDirty`/`releaseInFlight` below are combined into the one function that is now the
 * ONLY writer of `.disabled`, and `restoreDisabled = { draftIsDirty }` makes sure the gate's own
 * finally has the last word instead of a hardcoded `false`.
 */
private fun renderReleaseControl(
    card: SimplePanel,
    draft: McpPostDraftDto,
    onChanged: () -> Unit,
    subscribeDirty: (onDirtyChanged: (Boolean) -> Unit) -> Unit,
) {
    val releasePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    releasePanel.renderMyLtrBalanceInline()
    val unsavedHint =
        releasePanel.div(
            tr(
                "Sie haben ungespeicherte Änderungen am Beitragstext. Speichern Sie diese zuerst -- " +
                    "veröffentlicht wird sonst der ursprüngliche Entwurf, nicht Ihre Änderung.",
            ),
        ) {
            addCssClasses("alert alert-warning small mb-0")
        }
    val form = releasePanel.lapisForm()
    val weightField =
        form.textField(
            label = tr("Einsatz (LTR)"),
            required = true,
            rule = { value ->
                if (Validation.isPositiveDecimal(value)) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(gettext("Bitte einen positiven Betrag (LTR) angeben."))
                }
            },
        )
    val releaseButton = Button(tr("Entwurf veröffentlichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = releaseButton)

    // See the MINOR-fix KDoc paragraph above: these two flags are the ONLY inputs to `.disabled`,
    // and `applyReleaseButtonGate` the ONLY place that writes it.
    var draftIsDirty = false
    var releaseInFlight = false

    fun applyReleaseButtonGate() {
        releaseButton.disabled = draftIsDirty || releaseInFlight
    }
    subscribeDirty { dirty ->
        draftIsDirty = dirty
        unsavedHint.visible = dirty
        applyReleaseButtonGate()
    }
    releaseButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val amount = Validation.roundToTwoDecimalPlaces(weightField.value.trim().toDouble()).toDecimal()
        confirmDialog(
            title = tr("Entwurf veröffentlichen"),
            message =
                gettext(
                    "Sie setzen %1 aus Ihrem freien LTR-Guthaben ein. Der Beitrag wird sofort veröffentlicht.",
                    formatLtr(amount),
                ),
            confirmLabel = tr("Jetzt veröffentlichen"),
        ) {
            // Set SYNCHRONOUSLY, in this same click-handler tick, BEFORE `form.runBusy(...)` -- not
            // inside the suspend block it wraps. `AppScope.launch` (`runGuardedAction`) does not run
            // its body immediately; a dirty-gate change landing between this click and that
            // coroutine's first suspension point would otherwise still see `releaseInFlight == false`
            // and briefly compute the wrong `.disabled`. Deliberately NOT calling
            // `applyReleaseButtonGate()` here too: `.disabled` is still `false` at this instant (the
            // click could only happen while `draftIsDirty` was already `false`), and writing `true`
            // ourselves would make `runGuardedAction`'s own double-click guard
            // (`if (button?.disabled == true) return`) treat this as a stray second click and skip
            // the release entirely. `form.runBusy` below performs that first `.disabled = true` write
            // itself.
            releaseInFlight = true
            // `restoreDisabled = { draftIsDirty }`: when `runGuardedAction`'s own `finally` runs (the
            // LAST write to `.disabled` for this click), it reflects whatever the dirty gate holds at
            // that moment instead of a hardcoded `false` -- closes the MINOR-fix gap for a failed
            // release that leaves an unsaved edit standing.
            form.runBusy(releaseButton, restoreDisabled = { draftIsDirty }) {
                try {
                    val result =
                        guarded {
                            rpcService<ISocialNetworkService>().releaseMyPostDraft(
                                McpPostDraftReleaseInput(draftId = draft.id, initialWeightLtr = amount),
                            )
                        }
                    if (result != null) {
                        notifySuccess(tr("Entwurf veröffentlicht."))
                        onChanged()
                    }
                } finally {
                    releaseInFlight = false
                }
            }
        }
    }
}

/** Verwerfen -- Teslers Moduslosigkeit: KEINE Rückfrage, im Gegensatz zur Freigabe (K4). */
private fun renderDiscardControl(
    card: SimplePanel,
    draft: McpPostDraftDto,
    onChanged: () -> Unit,
) {
    val row = card.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1") }
    val discardButton = row.button(tr("Verwerfen"), style = ButtonStyle.OUTLINESECONDARY)
    discardButton.onClick {
        runGuardedAction(discardButton) {
            // discardMyPostDraft returns Unit -- guarded<Unit> yields Unit on success, null on
            // failure (Unit is never null itself, so this still reliably distinguishes the two).
            val result = guarded { rpcService<ISocialNetworkService>().discardMyPostDraft(draft.id) }
            if (result != null) {
                notifySuccess(tr("Entwurf verworfen."))
                onChanged()
            }
        }
    }
}

/**
 * `DISCARDED` footer -- "Wiederherstellen" plus the deadline, `+`[DRAFT_RESTORE_WINDOW_DAYS] days
 * from [McpPostDraftDto.statusChangedAt]. **[DRAFT_RESTORE_WINDOW_DAYS] MUST stay equal to the
 * server's own `network.lapis.cloud.server.social.PostDraftRetention.DISCARDED_RETENTION_DAYS`** --
 * the server is the actual authority (the poller that deletes the row), this constant only computes
 * a client-side ESTIMATE of that same deadline for display.
 */
private fun renderDiscardedFooter(
    card: SimplePanel,
    draft: McpPostDraftDto,
    onChanged: () -> Unit,
) {
    val row = card.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1 align-items-center flex-wrap") }
    val restoreButton = row.button(tr("Wiederherstellen"), style = ButtonStyle.OUTLINESECONDARY)
    val changedAt = draft.statusChangedAt
    if (changedAt != null) {
        val deadline = changedAt.plusDays(DRAFT_RESTORE_WINDOW_DAYS)
        row.div(gettext("Wiederherstellbar bis %1", deadline.toString())) { addCssClasses("text-muted small") }
    }
    restoreButton.onClick {
        runGuardedAction(restoreButton) {
            val result = guarded { rpcService<ISocialNetworkService>().restoreMyPostDraft(draft.id) }
            if (result != null) {
                notifySuccess(tr("Entwurf wiederhergestellt."))
                onChanged()
            }
        }
    }
}

/** See [renderDiscardedFooter] KDoc -- mirrors the server's own `PostDraftRetention.DISCARDED_RETENTION_DAYS`, does not decide it. */
private const val DRAFT_RESTORE_WINDOW_DAYS = 7L

private fun LocalDateTime.plusDays(days: Long): LocalDateTime = (toInstant(TimeZone.UTC) + days.days).toLocalDateTime(TimeZone.UTC)
