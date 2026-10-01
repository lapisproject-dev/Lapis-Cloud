package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
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

private const val DEFAULT_VIABLE_THRESHOLD = 0.2
private const val DEFAULT_WARN_THRESHOLD = 0.5

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
