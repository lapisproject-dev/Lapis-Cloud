package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MembershipTierOverviewDto
import network.lapis.cloud.shared.domain.MembershipTierRules
import network.lapis.cloud.shared.rpc.IContributionService

/**
 * Welle V1.9.18 "Verwaltung der Mitgliedschaftsstufen" -- TREASURER/ADMIN screen (`Routes.MEMBERSHIP_TIERS`)
 * for what used to be a read-only list plus a free-text date form inside the contributions screen. See
 * `docs/architecture/membership-tiers.adoc` for the rules behind every control.
 *
 * - **No delete.** A tier is referenced by contributions, SEPA batches and the audit trail; it is CLOSED
 *   ("Geschlossen": `active = false`), which only stops NEW assignments -- members already on it keep it and
 *   `generateContributionsForPeriod` keeps invoicing them.
 * - **One route, one gate.** `Routes.MEMBERSHIP_TIERS` is TREASURER/ADMIN, NOT BOARD like most neighbours in
 *   the finance group -- verified against `IContributionService.listMembershipTierOverview`.
 * - **Every name and description is untrusted text** (a treasurer writes it, members read it elsewhere): table
 *   cells go through [cellText], the modal caption through `sanitizeUntrustedI18nText`, the dropdown elsewhere
 *   through `untrustedOptions`. Never a `tr()`/`gettext()` concatenation with a tier name.
 * - The billing interval select is disabled while active members are assigned; a disabled select is not
 *   submitted, so the ORIGINAL interval is sent with every update ([tierInputFrom]).
 */
fun renderMembershipTiersScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    lateinit var section: DataSection
    var overview: MembershipTierOverviewDto? = null

    root.pageHeader(
        tr("Mitgliedschaftsstufen"),
        subtitle = tr("Beitrag, Intervall und Zahlungsziel je Stufe. Eine Stufe wird nicht gelöscht, sondern geschlossen."),
        primaryAction = {
            button(tr("Mitgliedschaftsstufe anlegen"), style = ButtonStyle.PRIMARY).onClick {
                openMembershipTierForm(
                    existing = null,
                    memberCount = 0,
                    allTiers = { overview?.tiers.orEmpty() },
                    onSaved = { section.reload() },
                )
            }
        },
    )

    section =
        root.dataSection<MembershipTierOverviewDto>(
            // ALWAYS false: the empty notice lives inside [renderTierOverview] next to the "without a tier" callout --
            // an instance with no tier but active members must still see that callout.
            isEmpty = { false },
            onSettled = { loaded -> overview = loaded },
            load = { membershipTierGuarded { rpcService<IContributionService>().listMembershipTierOverview() } },
            render = { panel, loaded ->
                panel.renderTierOverview(
                    overview = loaded,
                    onEdit = { tier ->
                        openMembershipTierForm(
                            existing = tier,
                            memberCount = loaded.memberCounts[tier?.id].orZero(),
                            allTiers = { loaded.tiers },
                            onSaved = { section.reload() },
                        )
                    },
                    onGenerate = { tier -> openGenerateContributionsDialog(tier) { section.reload() } },
                )
            },
        )
    section.reload()
}

private fun Int?.orZero(): Int = this ?: 0

/**
 * The content of the screen: the hint for active members without a tier, then the table (or the empty notice).
 * [onEdit] gets the tier to edit; [onGenerate] the tier to generate contributions for.
 */
internal fun Container.renderTierOverview(
    overview: MembershipTierOverviewDto,
    onEdit: (MembershipTierDto?) -> Unit,
    onGenerate: (MembershipTierDto) -> Unit,
) {
    if (overview.activeMembersWithoutTier > 0) {
        div(
            gettext(
                "Aktive Mitglieder ohne Mitgliedschaftsstufe: %1. Für sie wird bei der Beitragserzeugung keine Rechnung erstellt.",
                overview.activeMembersWithoutTier,
            ),
        ) { addCssClasses("alert alert-info mb-0") }
    }
    if (overview.tiers.isEmpty()) {
        p(tr("Noch keine Mitgliedschaftsstufe angelegt.")) { addCssClass("text-muted") }
        return
    }
    dataTable(
        columns = tierColumns(overview.memberCounts),
        rows = sortTiersForDisplay(overview.tiers),
        actions = { actions, tier ->
            val group = actions.tableActionGroup()
            group.tableActionButton(ActionIcon.EDIT, tr("Bearbeiten"), ButtonStyle.OUTLINEPRIMARY).onClick { onEdit(tier) }
            // A free tier is never invoiced -- offering the action would only produce a "0 new contributions" toast.
            if (tier.contributionAmount.toDouble() > 0.0) {
                group.tableActionButton("fas fa-file-invoice", tr("Beiträge erzeugen")).onClick { onGenerate(tier) }
            }
        },
    )
}

/** Open ("Wählbar") tiers first, then closed ones; each group alphabetical by the browser's locale rules. */
internal fun sortTiersForDisplay(tiers: List<MembershipTierDto>): List<MembershipTierDto> =
    tiers.sortedWith(
        compareBy<MembershipTierDto> { !it.active }.thenComparator { a, b -> localeCompareNames(a.name, b.name) },
    )

private fun localeCompareNames(
    a: String,
    b: String,
): Int = a.asDynamic().localeCompare(b) as Int

internal fun billingIntervalLabel(interval: BillingInterval): String =
    when (interval) {
        BillingInterval.MONTHLY -> gettext("Monatlich")
        BillingInterval.QUARTERLY -> gettext("Vierteljährlich")
        BillingInterval.YEARLY -> gettext("Jährlich")
    }

private fun tierColumns(memberCounts: Map<String, Int>): List<DataColumn<MembershipTierDto>> =
    listOf(
        DataColumn(
            title = tr("Name"),
            primary = true,
            cell = { container, tier ->
                container.cellText(tier.name, "fw-bold")
                // Plain description below the name (a blank one adds nothing, cellText renders an empty span).
                if (tier.description.isNotBlank()) container.cellText(tier.description, "text-muted small d-block")
            },
        ),
        DataColumn(
            title = tr("Beitrag"),
            numeric = true,
            cell = { container, tier -> container.moneySpan(tier.contributionAmount) },
        ),
        textColumn(title = tr("Intervall")) { tier: MembershipTierDto -> billingIntervalLabel(tier.billingInterval) },
        textColumn(title = tr("Zahlungsziel (Tage)"), numeric = true) { tier: MembershipTierDto -> tier.paymentTermDays.toString() },
        textColumn(title = tr("Aktive Mitglieder"), numeric = true) { tier: MembershipTierDto ->
            memberCounts.getOrElse(tier.id) { 0 }.toString()
        },
        DataColumn(
            title = tr("Status"),
            cell = { container, tier ->
                container.statusBadge(if (tier.active) tr("Wählbar") else tr("Geschlossen"), if (tier.active) "success" else "secondary")
            },
        ),
    )

// ── Create / edit dialog ─────────────────────────────────────────────────────────────────────

/**
 * The create/edit dialog. [existing] `null` = create. [memberCount] is the number of ACTIVE members on the
 * tier (from the overview): it locks the interval and decides whether the "amount changed" notice is shown.
 * [allTiers] feeds the duplicate-name check and is read when a name is VALIDATED, not when the dialog opens: the "create"
 * button is usable before the first load has finished, and a list that arrives later must still count. The server stays the
 * authority ([network.lapis.cloud.shared.rpc.MembershipTierNameTakenException]).
 */
internal fun openMembershipTierForm(
    existing: MembershipTierDto?,
    memberCount: Int,
    allTiers: () -> List<MembershipTierDto>,
    onSaved: () -> Unit,
) {
    val modal = Modal(caption = if (existing == null) tr("Mitgliedschaftsstufe anlegen") else tr("Mitgliedschaftsstufe bearbeiten"))
    val form = modal.lapisForm()

    val nameField =
        form.textField(
            label = tr("Name"),
            value = existing?.name,
            required = true,
            rule = { tierNameCheck(it, allTiers(), existing?.id) },
        )
    val descriptionField =
        form.textAreaField(
            label = tr("Beschreibung"),
            rows = 3,
            value = existing?.description,
            rule = { tierDescriptionCheck(it) },
        )
    val amountField =
        form.textField(
            label = tr("Betrag"),
            value = existing?.let { formatAmountForInput(it.contributionAmount) },
            required = true,
            hint = gettext("Beispiel: %1.", "12,50"),
            rule = { validateTierAmount(it) },
            init = { it.setAttribute("inputmode", "decimal") },
        )
    val amountNotice = form.panel.p("") { addCssClasses("text-muted small mb-0") }
    amountNotice.hide()

    val intervalField =
        form.selectField(
            label = tr("Intervall"),
            options = BillingInterval.entries.map { it.name to billingIntervalLabel(it) },
            value = (existing?.billingInterval ?: BillingInterval.MONTHLY).name,
            required = true,
            hint =
                if (existing != null && memberCount > 0) {
                    gettext(
                        "Das Intervall ist gesperrt, solange aktive Mitglieder zugeordnet sind (%1). " +
                            "Legen Sie stattdessen eine neue Stufe an.",
                        memberCount,
                    )
                } else {
                    null
                },
        )
    val intervalLocked = existing != null && memberCount > 0
    if (intervalLocked) (intervalField.control as Select).disabled = true

    val paymentTermField =
        form.textField(
            label = tr("Zahlungsziel (Tage)"),
            value = (existing?.paymentTermDays ?: DEFAULT_PAYMENT_TERM_DAYS).toString(),
            required = true,
            hint = tr("Tage bis zur Fälligkeit, gerechnet ab Periodenbeginn."),
            rule = { validatePaymentTermDays(it) },
            init = { it.setAttribute("inputmode", "numeric") },
        )
    val activeField =
        form.checkField(
            label = tr("Für neue Zuordnungen wählbar"),
            value = existing?.active ?: true,
            hint = tr("Geschlossene Stufen bleiben für bestehende Mitglieder gültig und erzeugen weiter Beiträge."),
        )

    // Dynamic notices: a free tier, and (edit only) a changed amount with members on the tier.
    fun refreshAmountNotice(raw: String) {
        val parsed = (parseAmountInput(raw, allowZero = true, enforceMaxAmount = false) as? AmountInput.Valid)?.value
        val notice =
            when {
                parsed == null -> null
                parsed.toDouble() == 0.0 -> tr("Bei einem Betrag von 0 werden keine Beiträge erzeugt.")
                existing != null && memberCount > 0 && amountChanged(existing.contributionAmount, parsed) ->
                    gettext(
                        "Die Änderung gilt für künftig erzeugte Beiträge von %1 aktiven Mitgliedern. " +
                            "Bereits erzeugte Beiträge bleiben unverändert.",
                        memberCount,
                    )
                else -> null
            }
        if (notice == null) {
            amountNotice.hide()
        } else {
            amountNotice.content = notice
            amountNotice.show()
        }
    }
    amountField.subscribe { refreshAmountNotice(it) }
    form.finish()

    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    saveButton.onClick {
        form.submit(saveButton) {
            val input =
                tierInputFrom(
                    name = nameField.value,
                    description = descriptionField.value,
                    amountText = amountField.value,
                    interval = existing?.takeIf { intervalLocked }?.billingInterval ?: BillingInterval.valueOf(intervalField.value),
                    paymentTermText = paymentTermField.value,
                    active = activeField.value == "true",
                ) ?: return@submit
            val saved =
                membershipTierGuarded(
                    onNameTaken = { nameField.showError(tr("Eine Mitgliedschaftsstufe mit diesem Namen existiert bereits.")) },
                ) {
                    if (existing == null) {
                        rpcService<IContributionService>().createMembershipTier(input)
                    } else {
                        rpcService<IContributionService>().updateMembershipTier(existing.id, input)
                    }
                }
            if (saved == null) {
                form.showFormError(tr("Die Mitgliedschaftsstufe konnte nicht gespeichert werden."))
                return@submit
            }
            modal.hide()
            notifySuccess(
                if (existing == null) {
                    gettext("Mitgliedschaftsstufe \"%1\" wurde angelegt.", saved.name)
                } else {
                    gettext("Mitgliedschaftsstufe \"%1\" wurde gespeichert.", saved.name)
                },
            )
            onSaved()
        }
    }
    modal.addButton(saveButton)
    modal.show()
}

internal const val DEFAULT_PAYMENT_TERM_DAYS = 14

/**
 * The RPC input of a filled form, or `null` when the amount/term text does not parse (cannot happen after the
 * field rules passed; a defensive `null` instead of a `!!`). Name and description are normalized exactly like the
 * server does ([MembershipTierRules.normalizeName], trim) -- the server re-normalizes regardless.
 */
internal fun tierInputFrom(
    name: String,
    description: String,
    amountText: String,
    interval: BillingInterval,
    paymentTermText: String,
    active: Boolean,
): MembershipTierInput? {
    val amount = (parseAmountInput(amountText, allowZero = true, enforceMaxAmount = false) as? AmountInput.Valid)?.value ?: return null
    val term = paymentTermText.trim().toIntOrNull() ?: return null
    return MembershipTierInput(
        name = MembershipTierRules.normalizeName(name),
        description = description.trim(),
        contributionAmount = amount,
        billingInterval = interval,
        active = active,
        paymentTermDays = term,
    )
}

// ── Generate contributions dialog ────────────────────────────────────────────────────────────

/**
 * "Beiträge erzeugen": replaces the free-text date form of the contributions screen. Two native date inputs
 * (both required) and one cross-field rule (start not after end); the server re-checks it.
 */
internal fun openGenerateContributionsDialog(
    tier: MembershipTierDto,
    onDone: () -> Unit,
) {
    val modal = Modal(caption = sanitizeUntrustedI18nText(gettext("Beiträge erzeugen: %1", tier.name)))
    val form = modal.lapisForm()
    form.panel.p(
        tr(
            "Erzeugt für alle aktiven Mitglieder dieser Stufe eine Beitragsforderung für den Zeitraum. " +
                "Bereits vorhandene Forderungen werden übersprungen.",
        ),
    ) { addCssClass("text-muted") }
    val startField = form.textField(label = tr("Beginn"), type = InputType.DATE, required = true)
    val endField = form.textField(label = tr("Ende"), type = InputType.DATE, required = true)
    form.crossFieldRule(field = endField) { periodOrderCheck(startField.value, endField.value) }
    form.finish()

    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val generateButton = Button(tr("Beiträge erzeugen"), style = ButtonStyle.PRIMARY)
    generateButton.onClick {
        form.submit(generateButton) {
            val start = runCatching { LocalDate.parse(startField.value.trim()) }.getOrNull() ?: return@submit
            val end = runCatching { LocalDate.parse(endField.value.trim()) }.getOrNull() ?: return@submit
            val created = membershipTierGuarded { rpcService<IContributionService>().generateContributionsForPeriod(tier.id, start, end) }
            if (created == null) {
                form.showFormError(tr("Die Beiträge konnten nicht erzeugt werden."))
                return@submit
            }
            modal.hide()
            notifySuccess(gettext("%1 neue Beiträge erzeugt (bereits vorhandene wurden übersprungen).", created))
            onDone()
        }
    }
    modal.addButton(generateButton)
    modal.show()
}

internal fun periodOrderCheck(
    start: String,
    end: String,
): FieldCheck {
    val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull() ?: return FieldCheck.Ok
    val to = runCatching { LocalDate.parse(end.trim()) }.getOrNull() ?: return FieldCheck.Ok
    return if (to < from) FieldCheck.Invalid(gettext("Ende darf nicht vor dem Beginn liegen.")) else FieldCheck.Ok
}

// ── Pure field rules (DOM-free, directly testable) ───────────────────────────────────────────

/** Name: 1..100 characters after normalizing, no control characters, and not already taken (case-insensitive, other tiers only). */
internal fun tierNameCheck(
    raw: String,
    allTiers: List<MembershipTierDto>,
    ownId: String?,
): FieldCheck {
    val normalized = MembershipTierRules.normalizeName(raw)
    if (!MembershipTierRules.isValidName(normalized)) {
        return FieldCheck.Invalid(gettext("Der Name muss zwischen 1 und %1 Zeichen lang sein.", MembershipTierRules.NAME_MAX_LENGTH))
    }
    val key = MembershipTierRules.nameKey(normalized)
    val taken = allTiers.any { it.id != ownId && MembershipTierRules.nameKey(MembershipTierRules.normalizeName(it.name)) == key }
    return if (taken) FieldCheck.Invalid(gettext("Eine Mitgliedschaftsstufe mit diesem Namen existiert bereits.")) else FieldCheck.Ok
}

internal fun tierDescriptionCheck(raw: String): FieldCheck =
    if (raw.trim().length > MembershipTierRules.DESCRIPTION_MAX_LENGTH) {
        FieldCheck.Invalid(gettext("Höchstens %1 Zeichen.", MembershipTierRules.DESCRIPTION_MAX_LENGTH))
    } else {
        FieldCheck.Ok
    }

/**
 * Amount 0..100000 with at most two decimals. A wrapper instead of a new flag on the shared [parseAmountInput]: that
 * function reports "muss größer als 0 sein" for anything below the lower bound, which is wrong for a field where 0 is
 * valid, and open items depend on its current behaviour. A leading minus gets its own sentence (the shared shape check
 * would only say "enter an amount like 1234,56").
 */
internal fun validateTierAmount(raw: String?): FieldCheck {
    if (raw?.trim()?.startsWith("-") == true) return FieldCheck.Invalid(gettext("Der Betrag darf nicht negativ sein."))
    return when (val parsed = parseAmountInput(raw, allowZero = true, enforceMaxAmount = false)) {
        is AmountInput.Empty -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
        is AmountInput.Valid ->
            if (parsed.value.toDouble() > MembershipTierRules.MAX_CONTRIBUTION_AMOUNT) {
                FieldCheck.Invalid(
                    gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(MembershipTierRules.MAX_CONTRIBUTION_AMOUNT.toDecimal())),
                )
            } else {
                FieldCheck.Ok
            }
    }
}

private val PAYMENT_TERM_SHAPE = Regex("^\\d+$")

/** Payment term: a whole number 0..365 -- digits only, so "1,5", "-1" and "+5" are all rejected with the same sentence. */
internal fun validatePaymentTermDays(raw: String?): FieldCheck {
    val trimmed = raw?.trim().orEmpty()
    val days = if (PAYMENT_TERM_SHAPE.matches(trimmed)) trimmed.toIntOrNull() else null
    return if (days == null || days > MembershipTierRules.MAX_PAYMENT_TERM_DAYS) {
        FieldCheck.Invalid(gettext("Bitte eine ganze Zahl zwischen %1 und %2 eingeben.", 0, MembershipTierRules.MAX_PAYMENT_TERM_DAYS))
    } else {
        FieldCheck.Ok
    }
}

/** The amount as a prefill for the amount field: decimal comma, at least two decimals ("12,5" -> "12,50", "10" -> "10,00"). Pure string work. */
internal fun formatAmountForInput(amount: dev.kilua.rpc.types.Decimal): String {
    val digits = displayDigits(amount)
    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', "").padEnd(2, '0')
    return "$whole,$fraction"
}

/** Whether [entered] differs from [original] by at least half a cent. */
private fun amountChanged(
    original: dev.kilua.rpc.types.Decimal,
    entered: dev.kilua.rpc.types.Decimal,
): Boolean = kotlin.math.abs(original.toDouble() - entered.toDouble()) >= CENT_TOLERANCE
