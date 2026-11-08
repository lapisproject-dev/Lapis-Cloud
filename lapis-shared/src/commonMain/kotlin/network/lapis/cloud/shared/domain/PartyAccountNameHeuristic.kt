package network.lapis.cloud.shared.domain

/** A guess for the position of a ledger account, made from its NAME only. [keyword] is the (normalised) word of the name that triggered it. */
data class NameSuggestion(
    val position: String,
    val keyword: String,
)

/**
 * V1.9.292 (R4b) -- the name heuristic of the account mapping: for an account that has no mapping and no hit in the standard chart, it guesses a
 * report position from the words of the account NAME. A GUESS, never a fact: the user interface labels it "Vermutung nach Kontoname", the user
 * ticks it, and only "save" stores it. It is never applied automatically and never proposed for the "deliberately not in the report" case.
 *
 * Pure and deterministic. The rule table below is an UNCONFIRMED ASSUMPTION: it was NOT reviewed by a tax advisor or by the federal treasurer.
 *
 * How a name is read: lower case, `ä ö ü ß` become `ae oe ue ss`, everything that is not `[a-z0-9]` separates words. A word hits a stem when it
 * BEGINS with it (a stem ending in `$` must equal the whole word, so `kasse$` does not hit "Kassel"). Plural forms need their own stem because the
 * umlaut is spelled out (`zuschuss`, `zuschuesse`). A rule is a list of slots (each slot lists alternative stems): "consecutive" slots must hit
 * neighbouring words in order, otherwise every slot must hit some word anywhere in the name.
 *
 * A position only counts when the account type may be mapped to it ([PartgLedgerRules.allowedTarget]); if after that more than one distinct
 * position remains, there is NO suggestion. A plain "Spenden" is therefore never guessed.
 */
object PartyAccountNameHeuristic {
    private class Rule(
        val position: String,
        val consecutive: Boolean,
        val slots: List<List<String>>,
    )

    private fun anywhere(
        position: String,
        vararg slots: List<String>,
    ) = Rule(position = position, consecutive = false, slots = slots.toList())

    private fun phrase(
        position: String,
        vararg slots: List<String>,
    ) = Rule(position = position, consecutive = true, slots = slots.toList())

    private fun single(
        position: String,
        vararg stems: String,
    ) = phrase(position = position, stems.toList())

    private val CHAPTER = listOf("gliederung", "gebietsverband", "gebietsverbaend", "landesverband", "kreisverband")
    private val SUBSIDY = listOf("zuschuss", "zuschuesse")

    /** Unconfirmed assumption, not reviewed by a tax advisor. Position codes are those of [PartgPositions]. */
    private val RULES: List<Rule> =
        listOf(
            // income
            single(position = "E01", "mitgliedsbeitr"),
            anywhere(position = "E01", listOf("beitr"), listOf("mitglied")),
            single(position = "E02", "mandatstraeger"),
            anywhere(position = "E03", listOf("spend"), listOf("natuerlich")),
            anywhere(position = "E04", listOf("spend"), listOf("juristisch")),
            single(position = "E05", "unternehmenstaetig"),
            single(position = "E05A", "beteiligungsertr", "beteiligungseinnahme"),
            single(position = "E06", "zinsertr", "zinseinnahme", "mieteinnahme", "pachteinnahme"),
            single(position = "E07", "veranstaltung", "druckschrift"),
            phrase(position = "E08", listOf("staatlich"), listOf("mittel")),
            single(position = "E08", "staatsmittel", "teilfinanzierung"),
            phrase(position = "E09", listOf("sonstig"), listOf("einnahme")),
            anywhere(position = "E10", SUBSIDY, CHAPTER),
            // expenses
            single(position = "A01", "personal", "gehalt", "gehaelter", "lohn", "loehne"),
            single(position = "A02A", "buerobedarf", "buerokosten", "raumkosten", "porto", "telefon"),
            phrase(position = "A02A", listOf("laufend"), listOf("geschaeftsbetrieb")),
            single(position = "A02B", "oeffentlichkeitsarbeit"),
            phrase(position = "A02B", listOf("politisch"), listOf("arbeit")),
            single(position = "A02C", "wahlkampf", "wahlkaempf"),
            single(position = "A02D", "vermoegensverwaltung"),
            single(position = "A02E", "zinsaufwand"),
            phrase(position = "A02E", listOf("sonstig"), listOf("zins")),
            single(position = "A02F", "unternehmenstaetig"),
            phrase(position = "A02G", listOf("sonstig"), listOf("ausgabe", "aufwand")),
            anywhere(position = "A03", SUBSIDY, CHAPTER),
            // assets
            single(position = "BP01", "grundstueck", "gebaeude", "immobilie"),
            anywhere(position = "BP01", listOf("haus"), listOf("grundvermoegen")),
            single(position = "BP02", "geschaeftsstellenausstattung", "geschaeftsausstattung", "bueroausstattung"),
            single(position = "BP03", "beteiligung"),
            single(position = "BP04", "finanzanlage", "wertpapier"),
            anywhere(position = "BP05", listOf("forderung"), CHAPTER),
            anywhere(position = "BP06", listOf("forderung"), listOf("teilfinanzierung", "staatlich")),
            single(position = "BP07", "kasse$", "bank$", "bankguthaben", "giro", "tagesgeld", "festgeld", "geldbestand", "kassenbestand"),
            phrase(position = "BP08", listOf("sonstig"), listOf("vermoegen")),
            // liabilities
            anywhere(position = "SP01", listOf("rueckstellung"), listOf("pension")),
            phrase(position = "SP02", listOf("sonstig"), listOf("rueckstellung")),
            anywhere(position = "SP03", listOf("rueckzahlung"), listOf("teilfinanzierung")),
            anywhere(position = "SP04", listOf("verbindlichkeit"), CHAPTER),
            single(position = "SP05", "kreditinstitut", "bankdarlehen"),
            anywhere(position = "SP05", listOf("verbindlichkeit"), listOf("bank", "kreditinstitut")),
            anywhere(position = "SP06", listOf("verbindlichkeit"), listOf("darlehensgeber")),
            phrase(position = "SP07", listOf("sonstig"), listOf("verbindlichkeit")),
            anywhere(position = "SP07", listOf("verbindlichkeit"), listOf("lieferung")),
        )

    private const val MAX_KEYWORD_CHARS = 40
    private val SEPARATOR = Regex("[^a-z0-9]+")

    /** The words of [name], normalised (see the class comment). */
    internal fun normalize(name: String): List<String> =
        name
            .lowercase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .split(SEPARATOR)
            .filter { it.isNotEmpty() }

    private fun hits(
        word: String,
        stem: String,
    ): Boolean = if (stem.endsWith("$")) word == stem.dropLast(1) else word.startsWith(stem)

    private fun hitsAny(
        word: String,
        alternatives: List<String>,
    ): Boolean = alternatives.any { hits(word = word, stem = it) }

    /** The words that made [rule] hit, or `null` when it does not. */
    private fun matched(
        rule: Rule,
        words: List<String>,
    ): List<String>? {
        if (rule.consecutive) {
            for (start in 0..(words.size - rule.slots.size)) {
                if (rule.slots.indices.all { hitsAny(word = words[start + it], alternatives = rule.slots[it]) }) {
                    return words.subList(start, start + rule.slots.size)
                }
            }
            return null
        }
        val found =
            rule.slots.map { slot -> words.firstOrNull { hitsAny(word = it, alternatives = slot) } ?: return null }
        return found.distinct()
    }

    /** The guessed position for an account called [name] of [type], or `null` when there is no single clear guess. */
    fun suggest(
        name: String,
        type: LedgerAccountType,
    ): NameSuggestion? {
        val words = normalize(name)
        if (words.isEmpty()) return null
        val hitsByPosition = LinkedHashMap<String, String>()
        for (rule in RULES) {
            if (!PartgLedgerRules.allowedTarget(accountType = type, positionCode = rule.position)) continue
            val hit = matched(rule = rule, words = words) ?: continue
            if (rule.position !in hitsByPosition) hitsByPosition[rule.position] = hit.joinToString(" ")
        }
        if (hitsByPosition.size != 1) return null
        val (position, keyword) = hitsByPosition.entries.single()
        return NameSuggestion(position = position, keyword = keyword.take(MAX_KEYWORD_CHARS))
    }
}
