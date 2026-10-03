package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.EventSeriesImpactDto
import network.lapis.cloud.shared.domain.MonthlyWeekdayRule
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.IEventService

/**
 * Follow-up wave "Wiederkehrende Veranstaltungen: Admin-UI" -- the recurrence-rule editor +
 * server-driven live preview `EventsScreen`'s creation form embeds. Kept in its own file (not folded
 * into the already-large `EventsScreen.kt`) because this is a self-contained widget with its own
 * local UI state machine (preset dropdown <-> "Benutzerdefiniert" expander <-> live sentence).
 *
 * **Respects the already-decided UX spec** (`docs/architecture/event-series.adoc`,
 * `RecurrenceSentence`/`EventSeriesScopeEngine` KDocs) -- this file never invents its own recurrence
 * grammar or its own client-side sentence formatting:
 * - The ONLY thing ever sent to the server is a structured [RecurrenceRuleInput] -- no raw RRULE
 *   string field exists anywhere in this UI.
 * - The dropdown offers Google-Calendar-style presets derived from the chosen start date/time
 *   (täglich / wöchentlich am Wochentag / monatlich am n-ten Wochentag oder am Tag im Monat /
 *   jährlich), plus an aufklappbares "Benutzerdefiniert" for the full `RecurrenceRuleInput` shape.
 * - The live sentence under the picker is EXCLUSIVELY `IEventService.previewSeries`'s own
 *   `SeriesPreviewDto.sentence` (server-built by `RecurrenceSentence`) -- debounced 400ms client-side
 *   (mirroring `EventSeriesLimits.PREVIEW_RATE_PER_MINUTE`'s own KDoc, which documents that exact
 *   debounce as the assumption its rate budget is sized against), never re-derived here.
 * - Serie-Ende is mandatory: the end-type selector offers only "Anzahl der Termine" / "Enddatum",
 *   never an unbounded option, mirroring `RecurrenceRuleBuilder.build`'s own
 *   `hasCount == hasUntil` rejection.
 * - The start weekday's chip is rendered permanently checked+disabled in the WEEKLY custom editor --
 *   see [renderWeekdayChips] KDoc for why (a rule that omits it is unconditionally rejected server-
 *   side, `RecurrenceRuleBuilder.build`'s "DTSTART must itself be an occurrence" check).
 *
 * [CLIENT_MAX_INSTANCES_PER_SERIES]/[CLIENT_MAX_HORIZON_MONTHS]/[CLIENT_MAX_INTERVAL] mirror
 * `network.lapis.cloud.server.events.series.EventSeriesLimits` (an `internal` server-module object,
 * unreachable from `:lapis-client`) -- shown as hints only, NEVER enforced as the actual gate; the
 * server re-validates every limit authoritatively regardless (same "loose mirror, not the security
 * boundary" posture `EventFormValidation.kt`'s own KDoc documents for the rest of this form).
 */
private const val CLIENT_MAX_INSTANCES_PER_SERIES = 104
private const val CLIENT_MAX_HORIZON_MONTHS = 24
private const val CLIENT_MAX_INTERVAL = 12
private const val PREVIEW_DEBOUNCE_MS = 400

private enum class RecurrencePreset { DAILY, WEEKLY_ON_START_DAY, MONTHLY, YEARLY, CUSTOM }

private enum class MonthlyVariant { ON_DAY, ON_WEEKDAY }

private enum class SeriesEndType { COUNT, UNTIL }

/** Public handle [EventsScreen]'s creation form reads from at submit time. */
class RecurrenceEditor internal constructor(
    private val enabledCheck: CheckBox,
    private val ruleSupplier: () -> RecurrenceRuleInput?,
) {
    val isEnabled: Boolean get() = enabledCheck.value

    /** `null` if [isEnabled] is `false`, or if the current form state cannot even be assembled into a [RecurrenceRuleInput] (e.g. a non-numeric interval) -- the caller shows a generic "bitte Wiederholung prüfen" error in that case, the server is never asked to validate a nonsensical shape. */
    fun currentRule(): RecurrenceRuleInput? = if (isEnabled) ruleSupplier() else null
}

/**
 * Builds the "Wiederkehrend?" checkbox + (once checked) preset dropdown/custom-editor/live-sentence
 * block, appended to [root]. [startsAtInput]/[endsAtInput] are the SAME `Text` widgets
 * [EventsScreen.buildEventFormFields] already built for the plain start/end fields -- this editor
 * subscribes to both (to re-derive preset labels/weekday-lock and to re-run the live preview) rather
 * than owning its own copies.
 */
fun renderRecurrenceEditor(
    root: SimplePanel,
    startsAtInput: Text,
    endsAtInput: Text,
): RecurrenceEditor {
    val enabledCheck = root.checkBox(value = false, label = tr("Wiederkehrende Veranstaltung (Serie)"))
    val body =
        root.vPanel(spacing = 6) {
            addCssClasses("border rounded p-2")
            hide()
        }

    val presetSelect = body.select(options = emptyList(), label = tr("Wiederholung"))

    val customPanel = body.vPanel(spacing = 6) { hide() }
    val intervalRow = customPanel.lapisToolbar()
    val frequencySelect =
        intervalRow.select(
            options =
                listOf(
                    RecurrenceFrequency.DAILY.name to tr("Tag(e)"),
                    RecurrenceFrequency.WEEKLY.name to tr("Woche(n)"),
                    RecurrenceFrequency.MONTHLY.name to tr("Monat(e)"),
                    RecurrenceFrequency.YEARLY.name to tr("Jahr(e)"),
                ),
            value = RecurrenceFrequency.WEEKLY.name,
            label = tr("Alle …"),
        )
    val intervalInput = intervalRow.text(label = tr("Intervall")).apply { value = "1" }
    customPanel.div(gettext("Intervall: 1 bis %1.", CLIENT_MAX_INTERVAL)) { addCssClasses("text-muted small") }

    val weekdayChipsHolder = customPanel.vPanel(spacing = 4) { hide() }
    val weekdayChips = mutableMapOf<RecurrenceWeekday, Button>()

    val monthlyVariantHolder = customPanel.vPanel(spacing = 4) { hide() }
    val monthlyVariantSelect =
        monthlyVariantHolder.select(
            options =
                listOf(
                    MonthlyVariant.ON_DAY.name to tr("Am gleichen Tag im Monat"),
                    MonthlyVariant.ON_WEEKDAY.name to tr("Am gleichen Wochentag im Monat"),
                ),
            value = MonthlyVariant.ON_DAY.name,
            label = tr("Monatliche Variante"),
        )

    val endRow = body.lapisToolbar()
    val endTypeSelect =
        endRow.select(
            options =
                listOf(
                    SeriesEndType.COUNT.name to tr("Nach Anzahl der Termine"),
                    SeriesEndType.UNTIL.name to tr("Am Enddatum"),
                ),
            value = SeriesEndType.COUNT.name,
            label = tr("Serie endet …"),
        )
    val countInput = endRow.text(label = tr("Anzahl der Termine")).apply { value = "10" }
    val untilInput = endRow.text(type = InputType.DATE, label = tr("Enddatum")).apply { hide() }
    body.div(
        gettext(
            "Eine Serie umfasst höchstens %1 Termine, innerhalb von höchstens %2 Monaten ab dem Beginn.",
            CLIENT_MAX_INSTANCES_PER_SERIES,
            CLIENT_MAX_HORIZON_MONTHS,
        ),
    ) { addCssClasses("text-muted small") }

    val sentenceBox = body.div { addCssClasses("fw-bold") }
    val errorBox =
        body.div().apply {
            addCssClass("text-danger")
            hide()
        }

    fun startsAtOrNull(): LocalDateTime? = runCatching { LocalDateTime.parse(startsAtInput.value.orEmpty().trim()) }.getOrNull()

    fun endsAtOrNull(): LocalDateTime? = runCatching { LocalDateTime.parse(endsAtInput.value.orEmpty().trim()) }.getOrNull()

    fun currentFrequency(): RecurrenceFrequency =
        RecurrenceFrequency.entries.firstOrNull { it.name == frequencySelect.value } ?: RecurrenceFrequency.WEEKLY

    fun currentMonthlyVariant(): MonthlyVariant =
        MonthlyVariant.entries.firstOrNull { it.name == monthlyVariantSelect.value } ?: MonthlyVariant.ON_DAY

    fun currentPreset(): RecurrencePreset =
        RecurrencePreset.entries.firstOrNull { it.name == presetSelect.value } ?: RecurrencePreset.CUSTOM

    /** Refreshes the WEEKLY custom-editor's weekday chips against the CURRENT start date -- rebuilt every time, so a start-date change always locks the (possibly new) start weekday, never a stale one. */
    fun rebuildWeekdayChips() {
        weekdayChipsHolder.removeAll()
        weekdayChips.clear()
        val startsAt = startsAtOrNull()
        val startWeekday = startsAt?.date?.dayOfWeek?.toRecurrenceWeekday()
        val row = weekdayChipsHolder.hPanel(spacing = 4) { addCssClasses("flex-wrap") }
        row.div(tr("Wochentage:")) { addCssClasses("me-2 align-self-center") }
        for (weekday in RecurrenceWeekday.entries) {
            val isStartDay = weekday == startWeekday
            lateinit var chip: Button
            chip =
                row.button(
                    recurrenceWeekdayShortLabel(weekday),
                    style = if (isStartDay) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY,
                ) {
                    addCssClass("btn-sm")
                }
            // Starttag nicht abwählbar (siehe Datei-KDoc): permanently selected+disabled, its own
            // click handler never runs -- RecurrenceRuleBuilder.build unconditionally rejects a WEEKLY
            // rule whose DTSTART weekday is missing from byWeekdays.
            if (isStartDay) {
                chip.disabled = true
            } else {
                var selected = false
                chip.onClick {
                    selected = !selected
                    chip.style = if (selected) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
                }
            }
            weekdayChips[weekday] = chip
        }
    }

    fun selectedWeekdays(): Set<RecurrenceWeekday> = weekdayChips.filterValues { it.style == ButtonStyle.PRIMARY }.keys

    fun refreshCustomVisibility() {
        val freq = currentFrequency()
        weekdayChipsHolder.visible = freq == RecurrenceFrequency.WEEKLY
        monthlyVariantHolder.visible = freq == RecurrenceFrequency.MONTHLY
        if (freq == RecurrenceFrequency.WEEKLY) rebuildWeekdayChips()
    }

    fun buildCustomFrequencyPart(): Triple<RecurrenceFrequency, Set<RecurrenceWeekday>, Pair<Int?, MonthlyWeekdayRule?>> {
        val freq = currentFrequency()
        val startsAt = startsAtOrNull()
        return when (freq) {
            RecurrenceFrequency.WEEKLY -> Triple(freq, selectedWeekdays(), null to null)
            RecurrenceFrequency.MONTHLY -> {
                val variant = currentMonthlyVariant()
                val monthDayVsWeekday =
                    if (variant == MonthlyVariant.ON_DAY) {
                        (startsAt?.date?.dayOfMonth) to null
                    } else {
                        null to startsAt?.date?.let { monthlyWeekdayRuleFor(it) }
                    }
                Triple(freq, emptySet(), monthDayVsWeekday)
            }
            RecurrenceFrequency.DAILY, RecurrenceFrequency.YEARLY -> Triple(freq, emptySet(), null to null)
        }
    }

    fun buildEnd(): Pair<Int?, LocalDate?> {
        val endType = SeriesEndType.entries.firstOrNull { it.name == endTypeSelect.value } ?: SeriesEndType.COUNT
        return if (endType == SeriesEndType.COUNT) {
            (countInput.value?.trim()?.toIntOrNull()) to null
        } else {
            null to runCatching { LocalDate.parse(untilInput.value.orEmpty().trim()) }.getOrNull()
        }
    }

    fun currentRule(): RecurrenceRuleInput? {
        val preset = currentPreset()
        val startsAt = startsAtOrNull() ?: return null
        val (count, until) = buildEnd()
        val (frequency, byWeekdays, monthlyPair) =
            when (preset) {
                RecurrencePreset.DAILY -> Triple(RecurrenceFrequency.DAILY, emptySet(), null to null)
                RecurrencePreset.WEEKLY_ON_START_DAY ->
                    Triple(RecurrenceFrequency.WEEKLY, setOf(startsAt.date.dayOfWeek.toRecurrenceWeekday()), null to null)
                RecurrencePreset.MONTHLY -> {
                    val ordinal = monthlyWeekdayRuleFor(startsAt.date)
                    Triple(RecurrenceFrequency.MONTHLY, emptySet(), null to ordinal)
                }
                RecurrencePreset.YEARLY -> Triple(RecurrenceFrequency.YEARLY, emptySet(), null to null)
                RecurrencePreset.CUSTOM -> buildCustomFrequencyPart()
            }
        val interval = if (preset == RecurrencePreset.CUSTOM) (intervalInput.value?.trim()?.toIntOrNull() ?: return null) else 1
        return RecurrenceRuleInput(
            frequency = frequency,
            interval = interval.coerceIn(1, CLIENT_MAX_INTERVAL),
            byWeekdays = byWeekdays,
            byMonthDay = monthlyPair.first,
            byMonthlyWeekday = monthlyPair.second,
            count = count,
            until = until,
        )
    }

    fun refreshPresetOptions() {
        val startsAt = startsAtOrNull()
        val previousPreset = currentPreset()
        val options =
            if (startsAt == null) {
                listOf(RecurrencePreset.CUSTOM.name to tr("Benutzerdefiniert…"))
            } else {
                listOf(
                    RecurrencePreset.DAILY.name to tr("Täglich"),
                    RecurrencePreset.WEEKLY_ON_START_DAY.name to
                        gettext("Wöchentlich am %1", recurrenceWeekdayLabel(startsAt.date.dayOfWeek.toRecurrenceWeekday())),
                    RecurrencePreset.MONTHLY.name to monthlyPresetLabel(startsAt.date),
                    RecurrencePreset.YEARLY.name to gettext("Jährlich am %1", formatDayMonth(startsAt.date)),
                    RecurrencePreset.CUSTOM.name to tr("Benutzerdefiniert…"),
                )
            }
        presetSelect.options = options
        presetSelect.value = previousPreset.name.takeIf { name -> options.any { it.first == name } } ?: options.first().first
        customPanel.visible = currentPreset() == RecurrencePreset.CUSTOM
        if (currentPreset() == RecurrencePreset.CUSTOM) refreshCustomVisibility()
    }

    var previewHandle: Int? = null

    fun schedulePreview() {
        previewHandle?.let { window.clearTimeout(it) }
        previewHandle =
            window.setTimeout({
                AppScope.launch {
                    errorBox.hide()
                    val startsAt = startsAtOrNull()
                    val endsAt = endsAtOrNull()
                    val rule = currentRule()
                    if (startsAt == null || endsAt == null || rule == null) {
                        sentenceBox.content = ""
                        return@launch
                    }
                    val preview = runCatching { rpcService<IEventService>().previewSeries(startsAt, endsAt, rule) }.getOrNull()
                    if (preview == null) {
                        sentenceBox.content = ""
                        return@launch
                    }
                    if (preview.valid) {
                        untrustedContent(sentenceBox, preview.sentence)
                    } else {
                        sentenceBox.content = ""
                        if (preview.errors.isNotEmpty()) {
                            untrustedContent(errorBox, preview.errors.joinToString("; "))
                            errorBox.show()
                        }
                    }
                }
            }, PREVIEW_DEBOUNCE_MS)
    }

    enabledCheck.subscribe { value ->
        body.visible = value
        if (value) {
            refreshPresetOptions()
            schedulePreview()
        }
    }
    presetSelect.subscribe {
        customPanel.visible = currentPreset() == RecurrencePreset.CUSTOM
        if (currentPreset() == RecurrencePreset.CUSTOM) refreshCustomVisibility()
        schedulePreview()
    }
    frequencySelect.subscribe {
        refreshCustomVisibility()
        schedulePreview()
    }
    monthlyVariantSelect.subscribe { schedulePreview() }
    intervalInput.subscribe { schedulePreview() }
    endTypeSelect.subscribe { value ->
        val isCount = value == SeriesEndType.COUNT.name
        countInput.visible = isCount
        untilInput.visible = !isCount
        schedulePreview()
    }
    countInput.subscribe { schedulePreview() }
    untilInput.subscribe { schedulePreview() }
    startsAtInput.subscribe {
        if (enabledCheck.value) {
            refreshPresetOptions()
            schedulePreview()
        }
    }
    endsAtInput.subscribe { if (enabledCheck.value) schedulePreview() }

    return RecurrenceEditor(enabledCheck = enabledCheck, ruleSupplier = ::currentRule)
}

private fun monthlyPresetLabel(date: LocalDate): String {
    val rule = monthlyWeekdayRuleFor(date)
    return if (rule.ordinal == -1) {
        gettext("Monatlich am letzten %1", recurrenceWeekdayLabel(rule.weekday))
    } else {
        gettext("Monatlich am %1 %2", ordinalWord(rule.ordinal), recurrenceWeekdayLabel(rule.weekday))
    }
}

/** Mirrors `RecurrenceSentence.monthlyPhrase`'s "nth weekday" derivation, computed from a concrete calendar date instead of a stored rule. */
private fun monthlyWeekdayRuleFor(date: LocalDate): MonthlyWeekdayRule {
    val occurrenceIndex = (date.dayOfMonth - 1) / 7 + 1
    val isLastOccurrence = date.dayOfMonth + 7 > daysInMonth(date.year, date.monthNumber)
    val ordinal = if (occurrenceIndex >= 5 || isLastOccurrence) -1 else occurrenceIndex
    return MonthlyWeekdayRule(ordinal = ordinal, weekday = date.dayOfWeek.toRecurrenceWeekday())
}

private fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 30
    }

private fun ordinalWord(ordinal: Int): String =
    when (ordinal) {
        1 -> tr("ersten")
        2 -> tr("zweiten")
        3 -> tr("dritten")
        4 -> tr("vierten")
        else -> "$ordinal."
    }

private fun DayOfWeek.toRecurrenceWeekday(): RecurrenceWeekday =
    when (this) {
        DayOfWeek.MONDAY -> RecurrenceWeekday.MO
        DayOfWeek.TUESDAY -> RecurrenceWeekday.TU
        DayOfWeek.WEDNESDAY -> RecurrenceWeekday.WE
        DayOfWeek.THURSDAY -> RecurrenceWeekday.TH
        DayOfWeek.FRIDAY -> RecurrenceWeekday.FR
        DayOfWeek.SATURDAY -> RecurrenceWeekday.SA
        DayOfWeek.SUNDAY -> RecurrenceWeekday.SU
    }

private fun recurrenceWeekdayLabel(weekday: RecurrenceWeekday): String =
    when (weekday) {
        RecurrenceWeekday.MO -> tr("Montag")
        RecurrenceWeekday.TU -> tr("Dienstag")
        RecurrenceWeekday.WE -> tr("Mittwoch")
        RecurrenceWeekday.TH -> tr("Donnerstag")
        RecurrenceWeekday.FR -> tr("Freitag")
        RecurrenceWeekday.SA -> tr("Samstag")
        RecurrenceWeekday.SU -> tr("Sonntag")
    }

private fun recurrenceWeekdayShortLabel(weekday: RecurrenceWeekday): String =
    when (weekday) {
        RecurrenceWeekday.MO -> tr("Mo")
        RecurrenceWeekday.TU -> tr("Di")
        RecurrenceWeekday.WE -> tr("Mi")
        RecurrenceWeekday.TH -> tr("Do")
        RecurrenceWeekday.FR -> tr("Fr")
        RecurrenceWeekday.SA -> tr("Sa")
        RecurrenceWeekday.SU -> tr("So")
    }

/**
 * The THIS/FOLLOWING/ALL scope choice `EventsScreen`'s edit/cancel actions show for an event that
 * still belongs to a non-detached series ([EventDto.seriesId] non-null, [EventDto.seriesDetached]
 * `false`). Implemented as a [io.kvision.form.select.Select] rather than literal HTML radio inputs --
 * this codebase has no existing radio-button precedent to build on (grep of the whole `:lapis-client`
 * source tree came up empty), while `Select` is the codebase's established single-choice-from-a-list
 * widget everywhere else; the UX behaviour the implementation plan actually asks for (a mutually
 * exclusive choice, the harmless THIS option preselected, live impact numbers per option, a reduced
 * two-option set on the series' very first occurrence) is preserved exactly, only the concrete form
 * control differs from the plan's literal "Radiobuttons" wording.
 *
 * **Fetch order**: [EventSeriesEditScope.THIS]'s impact is ALWAYS fetched first and alone -- its
 * response's [EventSeriesImpactDto.isFirstOccurrence] is what decides whether [EventSeriesEditScope
 * .FOLLOWING] even gets its own option (omitted on the very first occurrence, where it would be
 * identical to [EventSeriesEditScope.ALL] -- see `EventSeriesScopeEngine.plan`'s own
 * `targetIndex == 0` short-circuit). [EventSeriesEditScope.ALL]'s impact is always fetched.
 *
 * This dialog IS the confirmation step the implementation plan asks for before any multi-instance
 * change ("Bestätigungsdialog mit Anzahl betroffener Termine + Angemeldeter") -- the affected-event/
 * affected-registration counts are shown directly in each option's own label rather than in a second,
 * nested confirmation step, so picking FOLLOWING/ALL and clicking [confirmLabel] already IS an
 * informed confirmation.
 */
fun seriesEditScopeDialog(
    eventId: String,
    title: String,
    message: String,
    confirmLabel: String,
    confirmStyle: ButtonStyle = ButtonStyle.PRIMARY,
    onConfirm: (EventSeriesEditScope) -> Unit,
) {
    val modal = Modal(caption = title)
    modal.div(message)
    val loadingBox = modal.div(tr("Auswirkungen werden geladen…")) { addCssClasses("text-muted small") }
    val formHolder = modal.vPanel(spacing = 6) { hide() }

    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val once = ConfirmOnce()
    val confirmButton = Button(confirmLabel, style = confirmStyle).apply { disabled = true }
    modal.addButton(cancelButton)
    modal.addButton(confirmButton)
    modal.show()

    AppScope.launch {
        val thisImpact = guarded { rpcService<IEventService>().impactOfSeriesEdit(eventId, EventSeriesEditScope.THIS) }
        if (thisImpact == null) {
            loadingBox.content = tr("Auswirkungen konnten nicht geladen werden -- bitte erneut versuchen.")
            return@launch
        }
        val impacts = linkedMapOf(EventSeriesEditScope.THIS to thisImpact)
        if (!thisImpact.isFirstOccurrence) {
            guarded { rpcService<IEventService>().impactOfSeriesEdit(eventId, EventSeriesEditScope.FOLLOWING) }
                ?.let { impacts[EventSeriesEditScope.FOLLOWING] = it }
        }
        guarded { rpcService<IEventService>().impactOfSeriesEdit(eventId, EventSeriesEditScope.ALL) }
            ?.let { impacts[EventSeriesEditScope.ALL] = it }

        loadingBox.hide()
        val options = impacts.entries.map { (scope, impact) -> scope.name to seriesScopeOptionLabel(scope, impact) }
        val scopeSelect = formHolder.select(options = options, value = EventSeriesEditScope.THIS.name, label = tr("Betrifft"))
        formHolder.show()
        confirmButton.disabled = false
        confirmButton.onClick {
            val chosen = EventSeriesEditScope.entries.firstOrNull { it.name == scopeSelect.value } ?: EventSeriesEditScope.THIS
            once.run(confirmButton) {
                modal.hide()
                onConfirm(chosen)
            }
        }
    }
}

private fun seriesScopeOptionLabel(
    scope: EventSeriesEditScope,
    impact: EventSeriesImpactDto,
): String =
    when (scope) {
        EventSeriesEditScope.THIS ->
            gettext("Nur dieser Termin (%1 Angemeldete)", impact.affectedRegistrationCount)
        EventSeriesEditScope.FOLLOWING ->
            gettext(
                "Dieser und alle folgenden Termine (%1 Termine, %2 Angemeldete)",
                impact.affectedEventCount,
                impact.affectedRegistrationCount,
            )
        EventSeriesEditScope.ALL ->
            gettext(
                "Alle Termine der Serie (%1 Termine, %2 Angemeldete)",
                impact.affectedEventCount,
                impact.affectedRegistrationCount,
            )
    }
