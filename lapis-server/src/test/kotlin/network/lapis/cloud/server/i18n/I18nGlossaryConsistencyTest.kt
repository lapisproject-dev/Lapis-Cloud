package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.4.31 (W5): the core terms of `docs/architecture/i18n-glossary.adoc` read the same in every catalog entry. The table of that
 * document is the single source: this test parses it, so the prose and the check cannot drift apart. For every German msgid that
 * contains a term form as a WHOLE word, each of the seven translations must contain an accepted stem of its language.
 *
 * Audit V1.4.31: the table now also holds Vorstand, Protokoll, Satzung, Tagesordnung, Wahl and Mandat (before, only 9 terms were
 * enforced and the six most frequent governance words were not); a second test ([ACCEPTED_COLLISIONS]) checks the opposite direction --
 * two DIFFERENT German msgids of one screen file must not read the same in a language, or a person cannot tell two buttons apart.
 */
private class GlossaryTerm(
    val forms: List<String>,
    val stems: Map<String, List<String>>,
)

/**
 * Nine cells per row, one per line. The old parser chunked ALL `|` lines by nine, so one cell that happened to wrap onto two `|` lines
 * silently shifted every later column. Now each row is a block (rows are separated by a blank line) and a block with other than nine
 * cells fails the build with its first cell named.
 */
private fun parseGlossary(text: String): List<GlossaryTerm> {
    val tableStart = text.indexOf("[cols=\"1,1,1,1,1,1,1,1,2\"")
    val tableEnd = text.indexOf("|===", text.indexOf("|===", tableStart) + 4)
    val table = text.substring(text.indexOf("|===", tableStart) + 4, tableEnd)
    val blocks =
        table.split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.startsWith("|") }.map { it.removePrefix("|").trim() }
        }
    // The header row is ONE line with nine cells (`| German forms | en | ...`); every term is a block of nine lines.
    return blocks.filter { it.isNotEmpty() }.drop(1).map { row ->
        require(row.size == 9) {
            "glossary row starting with '${row.first()}' has ${row.size} cells, expected 9 (German forms, seven languages, notes)"
        }
        GlossaryTerm(
            forms = row[0].split(",").map { it.trim() },
            stems =
                CATALOG_LANGUAGES.withIndex().associate { (index, lang) ->
                    lang to row[index + 1].split(",").map { it.trim().lowercase() }
                },
        )
    }
}

/**
 * Whole msgids that contain a glossary term in ANOTHER sense than the one the table fixes. Each is exempt for ALL languages, with its
 * reason. "Antrag" has three senses: a Governance motion, a workflow request (deletion request, contribution reduction) -- both are
 * in the table -- and an APPLICATION (membership application, "auf Antrag" of the tax law), which is what these two entries mean.
 */
private val GLOSSARY_EXEMPT_MSGIDS: Map<String, String> =
    mapOf(
        "Antrag" to "MemberStatus.APPLICATION: the status of a membership APPLICATION (Application/Domanda), not a motion or a request",
        "Eine Befreiung von der Voranmeldungspflicht ist nur auf Antrag und im Ermessen des Finanzamts möglich." to
            "tax law: an exemption 'on application' (auf Antrag), not a motion or a request",
    )

private fun containsWholeWord(
    text: String,
    word: String,
): Boolean = Regex("(?<![\\p{L}])" + Regex.escape(word) + "(?![\\p{L}])").containsMatchIn(text)

/**
 * Pairs (or triples) of German msgids that legitimately read the same in some language, keyed by the SET of German msgids so the entry
 * holds for every language it occurs in. Each has its reason; a NEW collision breaks the build until it is fixed (a different
 * rendering) or listed here with a reason. Fixed in the audit round instead of listed: Lautsprecher/Sprecher (en), Entfernen/Lösen (en,
 * it), Absagen/Abbrechen (en es fr it), Zugangs-/Zugriffs-/Eingabekontrolle (fr, it), Rückgelastschrift/Storniert (it), Mitglieder/
 * Teilnehmende (ru).
 */
private val ACCEPTED_COLLISIONS: Map<Set<String>, String> =
    mapOf(
        setOf("aktiv", "Aktiv") to "same word, capitalisation (a sentence part vs. a label)",
        setOf("aktiviert", "Aktiviert") to "same word, capitalisation",
        setOf("deaktiviert", "Deaktiviert") to "same word, capitalisation",
        setOf("-- keine --", "-- kein --") to "gender variants of the same 'none' option",
        setOf("Wiederherstellung", "Wiederherstellen") to "noun and verb of one action (restore)",
        setOf("Antwort", "Antworten") to "noun and verb of one action (reply)",
        setOf("Mailinglisten", "Mailingliste") to "plural and singular of one term",
        setOf("Board leeren", "Whiteboard leeren") to "two names of the same board",
        setOf("Art", "Typ") to "two German synonyms for 'kind/type' in one form",
        setOf("Grund für die Stornierung", "Stornogrund") to "two German phrasings of one field",
        setOf("Fälligkeit", "Fällig am") to "the same due date as a noun and as a label",
        setOf("Ehrung gespeichert.", "Ehrung erfasst.") to "two German phrasings of one confirmation (fr: enregistrée)",
        setOf("Speichern", "Erfassen") to "fr: both are 'enregistrer' (record/save) -- one action on that screen",
        setOf("Jetzt live gehen", "Live-Stream starten") to "the same action offered in two places",
        setOf("Sitzung: %1", "Besprechung: %1") to
            "Sitzung (governance meeting) and Besprechung (video meeting) are both 'meeting' in en/nl/pl; each label stands next to its own value",
        setOf("Mikrofon", "Das Mikrofon") to "bare device name and the same with article (pl, ru have no article)",
        setOf("Lautsprecher", "Der Lautsprecher") to "bare device name and the same with article (pl, ru have no article)",
        setOf("Kamera", "Die Kamera") to "bare device name and the same with article (pl, ru have no article)",
        setOf("Beendet", "ist beendet") to "status label and its sentence form (ru)",
        setOf("Unterbrochen", "ist unterbrochen") to "status label and its sentence form (ru)",
        setOf("Bieten", "Gebot abgeben") to "ru: two phrasings of placing a bid",
        setOf("Entfernen", "Löschen") to
            "es nl pl ru: 'remove' and 'delete' share one word; the confirm dialogs name the object (member vs. family)",
        setOf("Zurückziehen", "Entfernen") to "fr: 'retirer' -- a withdrawal and a removal of a line item on different screens",
    )

class I18nGlossaryConsistencyTest :
    FunSpec({
        val terms by lazy { parseGlossary(GLOSSARY_FILE.readText()) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the glossary table has the core terms and a stem for every language (not vacuous)") {
            terms.size shouldBeGreaterThan 7
            terms.forEach { term ->
                term.forms.shouldNotBeEmptyList()
                CATALOG_LANGUAGES.forEach { lang ->
                    term.stems
                        .getValue(lang)
                        .filter { it.isNotBlank() }
                        .shouldNotBeEmptyList()
                }
            }
            terms.flatMap { it.forms } shouldContainAll
                listOf(
                    "Antrag",
                    "Beschluss",
                    "Sitzung",
                    "Gremium",
                    "Mahnwesen",
                    "Beitrag",
                    "Spende",
                    "Mitglied",
                    "Förderer",
                    "Vorstand",
                    "Protokoll",
                    "Satzung",
                    "Tagesordnung",
                    "Wahl",
                    "Mandat",
                )
        }

        test("every catalog entry that contains a glossary term renders it with an accepted stem in all seven languages") {
            val violations =
                terms.flatMap { term ->
                    catalogs
                        .getValue("en")
                        .keys
                        .filter { id ->
                            id.isNotEmpty() && id !in GLOSSARY_EXEMPT_MSGIDS && term.forms.any { containsWholeWord(text = id, word = it) }
                        }.flatMap { id ->
                            CATALOG_LANGUAGES.mapNotNull { lang ->
                                val translation = catalogs.getValue(lang)[id].orEmpty().lowercase()
                                if (term.stems
                                        .getValue(
                                            lang,
                                        ).none { it in translation }
                                ) {
                                    "${term.forms.first()} / $lang: \"$id\" -> \"$translation\""
                                } else {
                                    null
                                }
                            }
                        }
                }
            violations.shouldBeEmpty()
        }

        test("every exempt msgid of the glossary check still exists (no dead exemption)") {
            val ids = catalogs.getValue("en").keys
            GLOSSARY_EXEMPT_MSGIDS.keys.filterNot { it in ids }.shouldBeEmpty()
        }

        val collisions by lazy {
            val constants = clientStringConstants()
            clientKotlinFiles()
                .associate { file ->
                    file.name to
                        extractMessages(fileName = file.name, text = file.readText(), constants = constants).map { it.msgid }.toSet()
                }.flatMap { (file, ids) ->
                    CATALOG_LANGUAGES.flatMap { lang ->
                        ids
                            .filter { catalogs.getValue(lang)[it]?.isNotBlank() == true }
                            .groupBy {
                                catalogs
                                    .getValue(lang)
                                    .getValue(it)
                                    .trim()
                                    .lowercase()
                            }.filterValues { it.size > 1 }
                            .map { (text, group) -> Triple("$file / $lang: \"$text\"", group.toSet(), group.sorted()) }
                    }
                }
        }

        test("the collision scan sees real collisions (not vacuous): the accepted ones are found in the catalogs") {
            collisions.size shouldBeGreaterThan 20
            ACCEPTED_COLLISIONS.keys.count { accepted -> collisions.any { it.second == accepted } } shouldBeGreaterThan 15
        }

        test("two different msgids of one screen file read differently in every language (or the pair is an accepted collision)") {
            val violations =
                collisions
                    .filterNot { (_, group, _) -> ACCEPTED_COLLISIONS.containsKey(group) }
                    .map { (where, _, sorted) -> "$where <- $sorted" }
            violations.shouldBeEmpty()
        }

        test("every accepted collision names msgids that exist, and its reason is not blank") {
            val ids = catalogs.getValue("en").keys
            ACCEPTED_COLLISIONS.forEach { (group, reason) ->
                group.filterNot { it in ids }.shouldBeEmpty()
                reason.isNotBlank() shouldBe true
            }
        }

        test("the whole-word matcher: a listed form matches, a compound and an unlisted plural do not") {
            containsWholeWord(text = "Beitrag boosten", word = "Beitrag") shouldBe true
            containsWholeWord(text = "Kein Beitrag, keine Rechte", word = "Beitrag") shouldBe true
            containsWholeWord(text = "Beitragssatz", word = "Beitrag") shouldBe false
            containsWholeWord(text = "Beiträge", word = "Beitrag") shouldBe false
            containsWholeWord(text = "Checkout-Sitzungen", word = "Sitzungen") shouldBe true
        }
    })

private fun List<String>.shouldNotBeEmptyList() {
    (isNotEmpty()) shouldBe true
}

private infix fun List<String>.shouldContainAll(expected: List<String>) {
    expected.filterNot { it in this }.shouldBeEmpty()
}
