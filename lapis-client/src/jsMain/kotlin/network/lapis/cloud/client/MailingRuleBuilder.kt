package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService

// V1.9.111 "Dynamische Mailinglisten" -- the rule builder of the create / edit form. Since V1.9.112 ("regelbasierte Gremien") the SAME builder
// serves the committee context too: what differs (the group's name, the fixed "always" line, the count sentence, the presets and where the
// live count comes from) is passed in ([RuleBuilderWording], [previewer]); everything else -- rows, validation, debounce, announcements --
// is shared, so the two forms can never drift apart. The file keeps its name (catalog references, ledgers). Design-team decisions it implements: a sentence head
// ("Mitglieder, die alle / mindestens eine der folgenden Bedingungen erfüllen") instead of a query language, ready-made starting points
// as chips, one row per condition with a kind select and plain checkboxes / a number field for its values, a fixed line saying who is
// ALWAYS left out, and a live count of the people the rule reaches -- debounced, announced politely (one announcement per pause, not per
// keystroke), and immune to answers that arrive late (sequence number).

/** The pause after the last change before the live count is asked for (a typing person produces one request, not one per key). */
internal const val RULE_PREVIEW_DEBOUNCE_MS = 400L

/**
 * V1.9.112 -- what differs between the contexts. All texts are FINISHED strings (the caller builds them with gettext at render time, so
 * the language is the current one). [countText] gets the number of the live count and returns the announced sentence (a plural text).
 */
internal class RuleBuilderWording(
    val groupLabel: String,
    val alwaysNote: String,
    val computingText: String,
    val extraNote: String? = null,
    val presets: List<MailingRulePreset> = MailingRulePresets.ALL,
    val countText: (Int) -> String,
)

/** One answer of the live count: the number and an optional second line (mailing: who is left out). `null` from the previewer = unavailable. */
internal class RulePreview(
    val count: Int,
    val note: String = "",
)

/** The wording of the mailing-list form (V1.9.111, unchanged texts). */
internal fun mailingRuleWording(): RuleBuilderWording =
    RuleBuilderWording(
        groupLabel = tr("Empfänger"),
        alwaysNote = tr("Immer: nur aktive Mitglieder mit E-Mail-Adresse, ohne Abmeldung."),
        computingText = gettext("Empfänger werden berechnet …"),
        countText = { n -> ngettext("%1 Empfänger wird erreicht", "%1 Empfänger werden erreicht", n, n) },
    )

/** The live count of the mailing form: the list (if it exists) lets the server count its opt-outs, [mandatory] is read at preview time. */
internal fun mailingPreviewer(
    listId: String?,
    mandatory: () -> Boolean,
): suspend (String) -> RulePreview? =
    { ruleJson ->
        val result = rpcService<IMailingService>().previewDynamicRecipients(ruleJson, listId, mandatory())
        RulePreview(
            count = result.eligible,
            note =
                if (result.excludedOptedOut + result.excludedNoEmail > 0) {
                    gettext("Nicht enthalten: %1 abgemeldet, %2 ohne E-Mail-Adresse", result.excludedOptedOut, result.excludedNoEmail)
                } else {
                    ""
                },
        )
    }

/** The live count of the committee form: a number only (the server never names a person). [committeeId] is `null` while creating. */
internal fun committeePreviewer(committeeId: String?): suspend (String) -> RulePreview? =
    { ruleJson -> RulePreview(count = rpcService<IGovernanceService>().previewCommitteeRule(committeeId, ruleJson).eligible) }

/**
 * The committees a rule may reference (V1.9.112): active, manual, not the General Assembly and not [selfId] -- the server refuses all
 * others (a rule-based committee and the assembly have no seats, a reference would address nobody); the picker simply does not offer them.
 * [keep] are ids an already stored rule references: they stay visible so the person can still take them out of the rule.
 */
internal fun committeesForRules(
    committees: List<CommitteeDto>,
    selfId: String? = null,
    keep: Set<String> = emptySet(),
): List<CommitteeDto> =
    committees.filter {
        it.id in keep ||
            (
                it.active &&
                    it.type != CommitteeType.GENERAL_ASSEMBLY &&
                    it.membershipMode == CommitteeMembershipMode.MANUAL &&
                    it.id != selfId
            )
    }

/** The wording of the committee form (V1.9.112): the fixed line says who is ALWAYS left out, the extra line what freezing means. */
internal fun committeeRuleWording(): RuleBuilderWording =
    RuleBuilderWording(
        groupLabel = tr("Mitglieder"),
        alwaysNote = tr("Immer: nur aktive Mitglieder, nicht anonymisiert, nicht verstorben."),
        computingText = gettext("Mitglieder werden berechnet …"),
        extraNote = tr("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben."),
        presets = listOf(MailingRulePreset.BOARD_ROLES, MailingRulePreset.COMMITTEE, MailingRulePreset.NEW_MEMBERS_30),
        countText = { n -> ngettext("%1 Mitglied gehört dazu", "%1 Mitglieder gehören dazu", n, n) },
    )

private enum class ConditionKind { ROLE, JOINED_WITHIN_DAYS, COMMITTEE, TIER }

private class ConditionRow(
    var kind: ConditionKind,
    var roles: MutableSet<AccountRole> = linkedSetOf(),
    var days: String = "",
    var committees: MutableSet<String> = linkedSetOf(),
    var tiers: MutableSet<String> = linkedSetOf(),
)

/** What a caller gets back from [renderRuleBuilder]. */
internal class RuleBuilderHandle(
    private val currentRule: () -> MemberRule,
    private val validation: () -> String?,
    private val replace: (MemberRule) -> Unit,
    private val refreshPreview: () -> Unit,
    private val stop: () -> Unit,
) {
    /** The rule as the form shows it right now (a row that is not filled in yet is left out -- check [validationError] first). */
    fun rule(): MemberRule = currentRule()

    /** `null` if every row is complete; otherwise the (finished) sentence to show. */
    fun validationError(): String? = validation()

    /** Replaces the builder's content with [rule] (a preset, or the stored rule of an edit). */
    fun setRule(rule: MemberRule) = replace(rule)

    /** Asks for a new live count (the mandatory flag changed, which changes who is counted). */
    fun refresh() = refreshPreview()

    /** Cancels the pending preview and detaches every field of the builder from its form (the widget's destroy hook only cancels). */
    fun dispose() = stop()
}

/**
 * Builds the rule builder into [host] and wires its fields into [form] (so a missing value blocks the submit like any other field).
 * [previewer] asks the server for the live count of a canonical rule (the mailing form binds its list and mandatory flag into it, the
 * committee form its committee id); [wording] carries every context-specific text. [onChange] hears every REAL change of the rule.
 */
internal fun renderRuleBuilder(
    form: LapisForm,
    host: Container,
    initial: MemberRule,
    committees: List<CommitteeDto>,
    tiers: List<MembershipTierDto>,
    wording: RuleBuilderWording,
    previewer: suspend (ruleJson: String) -> RulePreview?,
    onChange: (MemberRule) -> Unit = {},
): RuleBuilderHandle {
    var stopHook: () -> Unit = {}
    // The destroy hook is registered BEFORE the panel is added (addWithLifecycle), so the preview timer dies with the widget.
    val root = host.addWithLifecycle(VPanel(spacing = 8), onDestroy = { stopHook() })
    // Constants as locals: a qualified field as a direct text argument looks like an unformatted value to the temporal tripwire.
    val maxConditions = MemberRuleCodec.MAX_CONDITIONS
    val daysFrom = MemberRuleCodec.DAYS_RANGE.first
    val daysTo = MemberRuleCodec.DAYS_RANGE.last
    var op = initial.op
    val rows = mutableListOf<ConditionRow>()
    val rowFields = mutableListOf<LapisField>()
    val kindFields = mutableListOf<LapisField>()
    var lastSignature = ""
    var previewJob: Job? = null
    var sequence = 0

    // ---- sentence head -----------------------------------------------------------------------------------------------------------
    val opField =
        form.selectField(
            label = wording.groupLabel,
            options =
                listOf(
                    RuleOp.ALL.name to gettext("Mitglieder, die alle folgenden Bedingungen erfüllen"),
                    RuleOp.ANY.name to gettext("Mitglieder, die mindestens eine der folgenden Bedingungen erfüllen"),
                ),
            value = op.name,
            host = root,
        )

    // ---- presets -----------------------------------------------------------------------------------------------------------------
    root.div(tr("Vorlagen")) { addCssClasses("small fw-bold") }
    val chips = root.buttonRow()
    chips.setAttribute("role", "group")
    chips.setAttribute("aria-label", gettext("Vorlagen für die Regel"))

    val rowsHost = root.vPanel(spacing = 8)
    val addHost = root.div()
    val limitHint = root.div(gettext("Höchstens %1 Bedingungen.", maxConditions)) { addCssClasses("text-muted small") }
    limitHint.hide()
    // The wording texts are finished gettext results of the caller (trusted), held in locals: no DTO field reaches a widget here.
    val alwaysNote = wording.alwaysNote
    val extraNote = wording.extraNote
    val countText = wording.countText
    val computingText = wording.computingText
    root.div(alwaysNote) { addCssClasses("text-muted small") }
    extraNote?.let { note -> root.div(note) { addCssClasses("text-muted small") } }
    val previewBox = root.div { addCssClasses("border rounded p-2") }
    previewBox.setAttribute("role", "status")
    previewBox.setAttribute("aria-live", "polite")
    val previewMain = previewBox.div { addCssClasses("fs-5 fw-bold") }
    val previewSub = previewBox.div { addCssClasses("text-muted small") }

    // ---- state -> rule -------------------------------------------------------------------------------------------------------------
    fun conditionOf(row: ConditionRow): RuleCondition? =
        when (row.kind) {
            ConditionKind.ROLE -> row.roles.takeIf { it.isNotEmpty() }?.let { RuleCondition.Role(it.toSet()) }
            ConditionKind.JOINED_WITHIN_DAYS ->
                row.days
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in MemberRuleCodec.DAYS_RANGE }
                    ?.let { RuleCondition.JoinedWithinDays(it) }
            ConditionKind.COMMITTEE -> row.committees.takeIf { it.isNotEmpty() }?.let { RuleCondition.Committee(it.toSet()) }
            ConditionKind.TIER -> row.tiers.takeIf { it.isNotEmpty() }?.let { RuleCondition.Tier(it.toSet()) }
        }

    fun currentRule(): MemberRule = MemberRule(op = op, conditions = rows.mapNotNull { conditionOf(it) })

    fun validationError(): String? =
        when {
            rows.any { conditionOf(it) == null } -> gettext("Bitte bei jeder Bedingung einen gültigen Wert festlegen.")
            else -> null
        }

    fun signature(): String = "${op.name}|" + rows.joinToString(";") { "${it.kind}:${it.roles}:${it.days}:${it.committees}:${it.tiers}" }

    // ---- live preview ----------------------------------------------------------------------------------------------------------------
    fun showPreview(result: RulePreview?) {
        if (result == null) {
            previewMain.content = gettext("Die Vorschau ist gerade nicht verfügbar.")
            previewSub.content = ""
            return
        }
        val count = result.count
        val note = result.note
        previewMain.content = countText(count)
        previewSub.content = note
    }

    fun schedulePreview() {
        previewJob?.cancel()
        val problem = validationError()
        sequence++
        if (problem != null) {
            previewMain.content = problem
            previewSub.content = ""
            return
        }
        val mine = sequence
        previewMain.content = computingText
        previewSub.content = ""
        val ruleJson = MemberRuleCodec.encode(currentRule())
        previewJob =
            AppScope.launch {
                delay(RULE_PREVIEW_DEBOUNCE_MS)
                val result =
                    try {
                        previewer(ruleJson)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        null
                    }
                // An answer for an older state of the form must never overwrite the answer for the current one.
                if (mine == sequence) showPreview(result)
            }
    }

    fun changed() {
        val now = signature()
        if (now == lastSignature) return
        lastSignature = now
        schedulePreview()
        if (validationError() == null) onChange(currentRule())
    }

    // ---- rows ------------------------------------------------------------------------------------------------------------------------
    lateinit var addButton: io.kvision.html.Button

    fun kindLabel(kind: ConditionKind): String =
        when (kind) {
            ConditionKind.ROLE -> gettext("Rolle")
            ConditionKind.JOINED_WITHIN_DAYS -> gettext("Beitritt in den letzten Tagen")
            ConditionKind.COMMITTEE -> gettext("Gremium")
            ConditionKind.TIER -> gettext("Mitgliedschaftsstufe")
        }

    fun updateAddState() {
        val full = rows.size >= MemberRuleCodec.MAX_CONDITIONS
        addButton.disabled = full
        if (full) limitHint.show() else limitHint.hide()
    }

    lateinit var renderRows: (focusIndex: Int?) -> Unit

    fun removeRow(index: Int) {
        rows.removeAt(index)
        renderRows(null)
        // Focus: the previous row's kind select, or the add button when no row is left.
        if (rows.isEmpty()) {
            addButton.focus()
        } else {
            kindFields[(index - 1).coerceAtLeast(0)].focus()
        }
        changed()
    }

    fun checkGroup(
        box: Container,
        legend: String,
        entries: List<Pair<String, String>>,
        selected: MutableSet<String>,
        emptyText: String,
    ) {
        val group = box.div { addCssClasses("d-flex flex-column") }
        group.setAttribute("role", "group")
        group.setAttribute("aria-label", legend)
        if (entries.isEmpty()) {
            group.div(emptyText) { addCssClasses("text-muted small") }
            return
        }
        entries.forEach { (value, label) ->
            val field = form.checkField(label = sanitizeUntrustedI18nText(label), value = value in selected, host = group)
            rowFields += field
            field.subscribe { state ->
                if (state == "true") selected += value else selected -= value
                changed()
            }
        }
    }

    renderRows = { focusIndex ->
        rowFields.forEach { it.detach() }
        rowFields.clear()
        kindFields.forEach { it.detach() }
        kindFields.clear()
        rowsHost.removeAll()
        rows.forEachIndexed { index, row ->
            val box = rowsHost.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
            val head = box.lapisToolbar()
            val kindField =
                form.selectField(
                    label = gettext("Bedingung %1", index + 1),
                    options = ConditionKind.entries.map { it.name to kindLabel(it) },
                    value = row.kind.name,
                    host = head,
                    slotHost = box,
                )
            kindFields += kindField
            val removeButton =
                head.actionButton(
                    ActionIcon.REMOVE,
                    gettext("Bedingung entfernen"),
                    ButtonStyle.OUTLINESECONDARY,
                    iconOnly = true,
                )
            removeButton.onClick { removeRow(rows.indexOf(row)) }
            when (row.kind) {
                ConditionKind.ROLE ->
                    checkGroup(
                        box = box,
                        legend = gettext("Rollen"),
                        entries = AccountRole.entries.map { it.name to accountRoleLabel(it) },
                        selected = proxyRoles(row),
                        emptyText = "",
                    )
                ConditionKind.JOINED_WITHIN_DAYS -> {
                    val days =
                        form.textField(
                            label = tr("Tage"),
                            type = InputType.NUMBER,
                            value = row.days,
                            hint = gettext("Zahl von %1 bis %2", daysFrom, daysTo),
                            host = box,
                            rule = { text ->
                                val n = text.trim().toIntOrNull()
                                if (n != null && n in MemberRuleCodec.DAYS_RANGE) {
                                    FieldCheck.Ok
                                } else {
                                    FieldCheck.Invalid(gettext("Bitte eine Zahl von %1 bis %2 eingeben.", daysFrom, daysTo))
                                }
                            },
                        )
                    rowFields += days
                    days.subscribe { text ->
                        row.days = text
                        changed()
                    }
                }
                ConditionKind.COMMITTEE ->
                    checkGroup(
                        box = box,
                        legend = gettext("Gremien"),
                        entries = untrustedOptions(committees.map { it.id to it.name }),
                        selected = row.committees,
                        emptyText = gettext("Es gibt noch keine aktiven Gremien."),
                    )
                ConditionKind.TIER ->
                    checkGroup(
                        box = box,
                        legend = gettext("Mitgliedschaftsstufen"),
                        entries = untrustedOptions(tiers.map { it.id to it.name }),
                        selected = row.tiers,
                        emptyText = gettext("Es gibt noch keine Mitgliedschaftsstufen."),
                    )
            }
            kindField.subscribe { value ->
                val kind = ConditionKind.entries.firstOrNull { it.name == value } ?: return@subscribe
                if (kind != row.kind) {
                    row.kind = kind
                    renderRows(rows.indexOf(row))
                    kindFields[rows.indexOf(row)].focus()
                    changed()
                }
            }
        }
        updateAddState()
        focusIndex?.let { kindFields.getOrNull(it)?.focus() }
    }

    fun addRow() {
        rows += ConditionRow(kind = ConditionKind.ROLE)
        renderRows(null)
        // Focus: the new row's kind select.
        kindFields.last().focus()
        changed()
    }

    addButton = newActionButton(ActionIcon.ADD, tr("Bedingung hinzufügen"), ButtonStyle.OUTLINEPRIMARY)
    addHost.add(addButton)
    addButton.onClick { if (rows.size < MemberRuleCodec.MAX_CONDITIONS) addRow() }

    fun load(rule: MemberRule) {
        op = rule.op
        opField.setValue(op.name)
        rows.clear()
        rule.conditions.forEach { condition ->
            rows +=
                when (condition) {
                    is RuleCondition.Role -> ConditionRow(ConditionKind.ROLE, roles = condition.values.toMutableSet())
                    is RuleCondition.JoinedWithinDays -> ConditionRow(ConditionKind.JOINED_WITHIN_DAYS, days = condition.days.toString())
                    is RuleCondition.Committee -> ConditionRow(ConditionKind.COMMITTEE, committees = condition.committeeIds.toMutableSet())
                    is RuleCondition.Tier -> ConditionRow(ConditionKind.TIER, tiers = condition.tierIds.toMutableSet())
                    // Not offered for mailing lists; the server never stores one.
                    is RuleCondition.Status -> null
                } ?: return@forEach
        }
        renderRows(null)
        lastSignature = signature()
        schedulePreview()
    }

    opField.subscribe { value ->
        val chosen = RuleOp.entries.firstOrNull { it.name == value } ?: return@subscribe
        if (chosen != op) {
            op = chosen
            changed()
        }
    }

    wording.presets.forEach { preset ->
        val presetRule = MailingRulePresets.rule(preset, firstCommitteeId = committees.firstOrNull()?.id)
        if (presetRule != null) {
            val chip = chips.button(MailingRulePresets.label(preset), style = ButtonStyle.OUTLINESECONDARY)
            chip.onClick {
                load(presetRule)
                onChange(currentRule())
            }
        }
    }

    load(initial)

    fun stop() {
        previewJob?.cancel()
        previewJob = null
        sequence++
    }

    fun teardown() {
        stop()
        rowFields.forEach { it.detach() }
        rowFields.clear()
        kindFields.forEach { it.detach() }
        kindFields.clear()
        opField.detach()
    }
    stopHook = ::stop
    return RuleBuilderHandle(
        currentRule = ::currentRule,
        validation = ::validationError,
        replace = { rule ->
            load(rule)
            onChange(currentRule())
        },
        refreshPreview = ::schedulePreview,
        stop = ::teardown,
    )
}

/** A live view of [ConditionRow.roles] as ids (the check group works on strings). */
private fun proxyRoles(row: ConditionRow): MutableSet<String> =
    object : AbstractMutableSet<String>() {
        override val size: Int get() = row.roles.size

        override fun iterator(): MutableIterator<String> {
            val inner = row.roles.iterator()
            return object : MutableIterator<String> {
                override fun hasNext() = inner.hasNext()

                override fun next() = inner.next().name

                override fun remove() = inner.remove()
            }
        }

        override fun add(element: String): Boolean =
            AccountRole.entries.firstOrNull { it.name == element }?.let { row.roles.add(it) } ?: false

        override fun remove(element: String): Boolean =
            AccountRole.entries.firstOrNull { it.name == element }?.let { row.roles.remove(it) } ?: false

        override fun contains(element: String): Boolean = row.roles.any { it.name == element }
    }
