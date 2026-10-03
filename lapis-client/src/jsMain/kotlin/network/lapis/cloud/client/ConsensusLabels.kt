package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusRules
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import kotlin.math.roundToInt

/*
 * V1.9.28 "Konsensieren" -- the label and colour tables of the systemic consensus screens, plus the three pure formatters (group
 * conflict in words, the resistance mean, the option text). Everything a person reads goes through gettext; everything a member typed
 * (titles, option texts) goes through the untrusted-text sanitizer.
 */

fun consensusStatusLabel(status: SystemicConsensusStatus): String =
    when (status) {
        SystemicConsensusStatus.COLLECTION -> gettext("Optionen werden gesammelt")
        SystemicConsensusStatus.RATING -> gettext("Bewertung läuft")
        SystemicConsensusStatus.CLOSED -> gettext("Bewertung beendet")
        SystemicConsensusStatus.EVALUATED -> gettext("Ausgewertet")
        SystemicConsensusStatus.ABORTED -> gettext("Abgebrochen")
    }

fun consensusStatusColor(status: SystemicConsensusStatus): String =
    when (status) {
        SystemicConsensusStatus.COLLECTION -> "secondary"
        SystemicConsensusStatus.RATING -> "primary"
        SystemicConsensusStatus.CLOSED -> "warning"
        SystemicConsensusStatus.EVALUATED -> "success"
        SystemicConsensusStatus.ABORTED -> "dark"
    }

fun consensusSecrecyLabel(secret: Boolean): String = if (secret) gettext("Anonym") else gettext("Offen")

fun consensusBindingnessLabel(bindingness: SystemicConsensusBindingness): String =
    when (bindingness) {
        SystemicConsensusBindingness.ADVISORY -> gettext("Sondierung")
        SystemicConsensusBindingness.BINDING -> gettext("Beschluss")
    }

/**
 * A binding consensus has exactly one round: `evaluate` records the resolution at once and `reopenRating` refuses after that, so
 * "round 1 of 3" would promise a revote that can never happen.
 */
fun consensusRoundLabel(consensus: SystemicConsensusDto): String =
    if (consensus.bindingness == SystemicConsensusBindingness.BINDING) {
        gettext("Eine Runde (Beschluss)")
    } else {
        gettext("Runde %1 von %2", consensus.round, consensus.maxRounds)
    }

/**
 * The text of one option. The status quo option is stored with an English label by the server, so it is recognised by its flag and
 * translated here; every other option is a member's free text and is sanitized (a forged i18n marker must never render as a catalog text).
 */
fun consensusOptionText(option: SystemicConsensusOptionDto): String =
    consensusOptionText(isStatusQuoOption = option.isStatusQuoOption, label = option.label)

fun consensusOptionText(
    isStatusQuoOption: Boolean,
    label: String,
): String = if (isStatusQuoOption) gettext("Alles bleibt wie bisher (Passivlösung)") else sanitizeUntrustedI18nText(label)

private const val DEFAULT_VIABLE_THRESHOLD = SystemicConsensusRules.DEFAULT_GROUP_CONFLICT_VIABLE
private const val DEFAULT_WARN_THRESHOLD = SystemicConsensusRules.DEFAULT_GROUP_CONFLICT_WARN

/** The group conflict index in words: below [viable] a viable consensus, up to [warn] (inclusive) with concerns, above it a warning sign. */
fun groupConflictWord(
    index: Double,
    viable: Double = DEFAULT_VIABLE_THRESHOLD,
    warn: Double = DEFAULT_WARN_THRESHOLD,
): String =
    when {
        index < viable -> gettext("Tragfähiger Konsens")
        index <= warn -> gettext("Mit Bedenken")
        else -> gettext("Warnsignal")
    }

/**
 * V1.9.42 -- the group-wide verdict from the two server booleans (they refer to the WINNER); same precedence as
 * [groupConflictWord] by index. Always called with named arguments, so it is never mixed up with the index overload.
 */
fun groupConflictWord(
    consensusViable: Boolean,
    groupConflictWarning: Boolean,
): String =
    when {
        consensusViable -> gettext("Tragfähiger Konsens")
        groupConflictWarning -> gettext("Warnsignal")
        else -> gettext("Mit Bedenken")
    }

/** [value] (not negative) with [places] decimals, rounded half up, with the decimal separator of [language]; never goes through `toFixed`/`Intl`. */
internal fun formatDecimal(
    value: Double,
    places: Int,
    language: String = I18n.language,
): String {
    var factor = 1
    repeat(places) { factor *= 10 }
    val scaled = (value * factor).roundToInt()
    val fraction = (scaled % factor).toString().padStart(places, '0')
    return "${scaled / factor}${moneyLocale(language).decimal}$fraction"
}

/** "Ø 2,4 von 10" -- the mean resistance next to the top of the scale. */
fun formatResistance(
    mean: Double,
    scaleMax: Int,
    language: String = I18n.language,
): String = gettext("Ø %1 von %2", formatDecimal(value = mean, places = 1, language = language), scaleMax)

/**
 * V1.9.39 -- the ONE order in which every place lists the options: the status quo option (P) first, then the real proposals by
 * `(position, id)` (the id breaks a tie between equal positions, so the order is stable). The server keeps P at position 0.
 */
internal fun consensusOrderedOptions(options: List<SystemicConsensusOptionDto>): List<SystemicConsensusOptionDto> {
    val byPosition = compareBy<SystemicConsensusOptionDto>({ it.position }, { it.id })
    return options.filter { it.isStatusQuoOption }.sortedWith(byPosition) +
        options.filterNot { it.isStatusQuoOption }.sortedWith(byPosition)
}

/**
 * V1.9.39 -- option id -> "P" (status quo) or "1".."n": the rank among the real options in [consensusOrderedOptions] order, gap-free.
 * Never derived from `position` directly (removals leave gaps). The numbers are final once the options are frozen (nothing changes the
 * options after that).
 */
internal fun consensusOptionNumbers(options: List<SystemicConsensusOptionDto>): Map<String, String> {
    var next = 0
    return consensusOrderedOptions(options).associate { option ->
        option.id to if (option.isStatusQuoOption) STATUS_QUO_NUMBER else (++next).toString()
    }
}

private const val STATUS_QUO_NUMBER = "P"

/** The fixed-width number plaque: only "P" or digits, hidden from assistive technology (the screen-reader name comes from [consensusNumberSrPrefix]). */
internal fun Container.consensusNumberPlaque(number: String) {
    span(number, className = if (number == STATUS_QUO_NUMBER) "lapis-sk-num lapis-sk-num--p" else "lapis-sk-num") {
        setAttribute("aria-hidden", "true")
    }
}

/** The screen-reader name prefix: "Option 3" for a real option, nothing for P (its text already says "Passivlösung"). */
internal fun Container.consensusNumberSrPrefix(number: String) {
    if (number == STATUS_QUO_NUMBER) return
    span(gettext("Option %1", number), className = "visually-hidden")
}

/** How a rationale is shown: whole, clamped to a few lines with a "show more" switch, or collapsed behind a switch. */
internal enum class RationaleMode { Full, Clamped, Collapsed }

private const val CLAMP_TEXT_LENGTH = 200
private const val CLAMP_LINE_BREAKS = 2

/**
 * V1.9.39 -- the one renderer of an option's rationale. The text is member input: it goes only through `untrustedDiv`
 * (white-space handled by CSS, never `title`/`aria-label`). The toggle's `aria-controls` is a running id (`"$toggleIdPrefix-$index"`),
 * never an option id. [closedLabel] is the label of the collapsed switch ([RationaleMode.Collapsed] only).
 */
internal fun renderOptionRationale(
    parent: Container,
    option: SystemicConsensusOptionDto,
    mode: RationaleMode,
    toggleIdPrefix: String,
    index: Int,
    closedLabel: String? = null,
) {
    if (option.isStatusQuoOption) return
    renderUntrustedExplanation(
        parent = parent,
        raw = option.rationale,
        mode = mode,
        toggleIdPrefix = toggleIdPrefix,
        index = index,
        closedLabel = closedLabel,
    )
}

/**
 * V1.9.41 -- the renderer behind [renderOptionRationale], also used for the explanations of consensus poll options. [raw] is member
 * input (`null` = nothing to show): it goes only through `untrustedDiv`, never into `title`/`aria-label`/`data-*`.
 */
internal fun renderUntrustedExplanation(
    parent: Container,
    raw: String?,
    mode: RationaleMode,
    toggleIdPrefix: String,
    index: Int,
    closedLabel: String? = null,
) {
    if (raw == null) return
    val safe = sanitizeUntrustedI18nText(raw)
    if (safe.isBlank()) return
    val bodyId = "$toggleIdPrefix-$index"
    when (mode) {
        RationaleMode.Full -> parent.untrustedDiv(raw, className = "lapis-sk-why small text-break")
        RationaleMode.Clamped -> {
            val body = parent.untrustedDiv(raw, className = "lapis-sk-why lapis-sk-why--clamped small text-break")
            body.id = bodyId
            // A deliberate heuristic without layout measuring: long text or several line breaks gets the switch.
            if (safe.length > CLAMP_TEXT_LENGTH || safe.count { it == '\n' } > CLAMP_LINE_BREAKS) {
                val toggle = Button(tr("Mehr anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
                toggle.addCssClass("btn-sm")
                toggle.setAttribute("aria-expanded", "false")
                toggle.setAttribute("aria-controls", bodyId)
                parent.add(toggle)
                var open = false
                toggle.onClick {
                    open = !open
                    toggle.setAttribute("aria-expanded", open.toString())
                    toggle.text = if (open) tr("Weniger anzeigen") else tr("Mehr anzeigen")
                    if (open) body.removeCssClass("lapis-sk-why--clamped") else body.addCssClass("lapis-sk-why--clamped")
                }
            }
        }
        RationaleMode.Collapsed -> {
            val label = closedLabel ?: tr("Begründung anzeigen")
            val toggle = Button(label, style = ButtonStyle.OUTLINESECONDARY)
            toggle.addCssClass("btn-sm")
            toggle.setAttribute("aria-expanded", "false")
            toggle.setAttribute("aria-controls", bodyId)
            parent.add(toggle)
            val body = parent.untrustedDiv(raw, className = "lapis-sk-why small text-break")
            body.id = bodyId
            // `hidden` instead of `hide()`: KVision does not render an invisible widget at all, and aria-controls needs its target in the DOM.
            body.setAttribute("hidden", "hidden")
            var open = false
            toggle.onClick {
                open = !open
                toggle.setAttribute("aria-expanded", open.toString())
                toggle.text = if (open) tr("Begründung ausblenden") else label
                if (open) body.removeAttribute("hidden") else body.setAttribute("hidden", "hidden")
            }
        }
    }
}
