package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.173 "date and time pickers everywhere": the rules that keep the ONE mechanism (`LocalInputs.kt`, `LocalDateField.kt`, `LocalTimeField.kt`)
 * the only way to ask for a date or a time. Client sources are read as text; comment lines are exempt; every detector has a positive and a
 * negative example and a red proof on a copied, deliberately violating source.
 *
 *  - P1: a raw date input is never unwrapped back to its `Text` (`localDateInput(...).input`): a screen that does so reads `.value` as locale text
 *    and breaks silently in picker mode (an ISO value). Use `LocalDateInput.dateOrNull()`/`.text`.
 *  - P2: `picker = false` is a test and fallback lever of the three files; no screen opts out.
 *  - T9: a plain `text(`/`textField(` whose label or hint speaks of a date or a time is a date field built by hand. Free text, numbers and durations
 *    that merely use the vocabulary are listed with a reason, and the list only shrinks (ratchet).
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val SANCTIONED = setOf("LocalInputs.kt", "LocalDateField.kt", "LocalTimeField.kt")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun codeText(file: File): String = file.readLines().filterNot { isCommentLine(it) }.joinToString("\n")

// ---- P1 ----------------------------------------------------------------------------------------------------------------------

private val RAW_INPUT_CALL = Regex("""local(?:Date|Time|DateTime)Input\(""")
private val UNWRAP = Regex("""^\s*\.input\b""")

/** Each `localDateInput(...)`/`localTimeInput(...)`/`localDateTimeInput(...)` call (paren-balanced) that is directly followed by `.input`. */
private fun unwrappedSites(text: String): List<String> =
    RAW_INPUT_CALL
        .findAll(text)
        .filter { UNWRAP.containsMatchIn(text.substring(callEnd(text = text, open = it.range.last))) }
        .map { it.value + "..." }
        .toList()

private fun callEnd(
    text: String,
    open: Int,
): Int = open + callArguments(text = text, open = open).length

// ---- P2 ----------------------------------------------------------------------------------------------------------------------

private val PICKER_OPT_OUT = Regex("""\bpicker\s*=\s*false\b""")

// ---- T9 ----------------------------------------------------------------------------------------------------------------------

/** The vocabulary of a date or time field: in a label, a hint or an example. */
private val DATE_TIME_VOCABULARY =
    Regex("""Datum|Uhrzeit|Zeitpunkt|Stichtag|Fälligkeit|Frist\b|Enddatum|Startdatum|TT\.MM|hh:mm|JJJJ|\d{4}-\d{2}-\d{2}""")

/** A call that is not a plain text box: it names a typed input (`type = InputType.NUMBER`, `DATE`, ...). */
private val TYPED_INPUT = Regex("""InputType\.[A-Z_]+""")

private val TEXT_CALL = Regex("""(?<![\w.])(?:\w+\.)?(?:textField|text)\(""")

/** The text of a call from its `(` to the matching `)`, string literals skipped. */
private fun callArguments(
    text: String,
    open: Int,
): String {
    var depth = 0
    var i = open
    var inString = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') {
                i++
            } else if (c == '"') {
                inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        i++
    }
    return text.substring(open)
}

private fun stringLiterals(args: String): List<String> = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(args).map { it.groupValues[1] }.toList()

/** The hand-built date/time text boxes of [text]: the call is a plain text call and one of its strings uses the vocabulary. */
private fun handBuiltSites(text: String): List<String> =
    TEXT_CALL
        .findAll(text)
        .filter { !text.substring(maxOf(0, it.range.first - 4), it.range.first).endsWith("fun ") }
        .mapNotNull { m ->
            val args = callArguments(text = text, open = m.range.last)
            val literals = stringLiterals(args)
            val vocabulary = literals.any { DATE_TIME_VOCABULARY.containsMatchIn(it) }
            if (vocabulary &&
                !TYPED_INPUT.containsMatchIn(args)
            ) {
                literals.firstOrNull { DATE_TIME_VOCABULARY.containsMatchIn(it) }
            } else {
                null
            }
        }.toList()

/**
 * Plain text boxes that use the vocabulary but are NOT date or time fields, with the reason. The count per file only shrinks.
 */
private val T9_ALLOWLIST: Map<String, Int> =
    mapOf(
        // "Version" hint "Zum Beispiel 2026-10-15.v1": a free version label, not a date.
        "AgreementTextsScreen.kt" to 1,
        // "Fälligkeitsfrist (Tage)": a number of days.
        "EventCheckInScreen.kt" to 1,
        // "Zahlungsziel (Tage)": a number of days ("bis zur Fälligkeit").
        "MembershipTiersScreen.kt" to 1,
    )

class ClientTemporalPickerTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val screens = files.filter { it.name !in SANCTIONED }

        test("the scan sees the client sources and the three sanctioned files") {
            (files.size > 100) shouldBe true
            files.map { it.name }.containsAll(SANCTIONED) shouldBe true
        }

        test("P1: no raw date/time input is unwrapped to its Text, and the detector sees it") {
            unwrappedSites("""val a = filterRow.localDateInput(label = tr("Von"), hintLine = false).input""").size shouldBe 1
            unwrappedSites("""val a = panel.localDateTimeInput(label = x).input""").size shouldBe 1
            unwrappedSites("""val a = filterRow.localDateInput(label = tr("Von"), hintLine = false)""").size shouldBe 0
            unwrappedSites("""val b = input.input.setAttribute("x", "y")""").size shouldBe 0
            val copy = File.createTempFile("p1-red-proof", ".kt")
            try {
                copy.writeText(
                    codeText(screens.first { it.name == "SepaBatchesScreen.kt" }) + "\nval bad = row.localDateInput(label = \"x\").input\n",
                )
                unwrappedSites(codeText(copy)).size shouldBe 1
            } finally {
                copy.delete()
            }
            screens.flatMap { f -> unwrappedSites(codeText(f)).map { "${f.name}: $it" } } shouldBe emptyList()
        }

        test("P2: no screen opts out of the picker with picker = false, and the detector sees it") {
            PICKER_OPT_OUT.containsMatchIn("""localDateInput(label = x, picker = false)""") shouldBe true
            PICKER_OPT_OUT.containsMatchIn("""localDateInput(label = x, picker = true)""") shouldBe false
            PICKER_OPT_OUT.containsMatchIn("""val pickerMode = true""") shouldBe false
            screens.flatMap { f ->
                codeText(f).lines().filter { PICKER_OPT_OUT.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
            } shouldBe
                emptyList()
        }

        test("T9: no hand-built date/time text box outside the allowlist, and the detector sees it") {
            handBuiltSites("""val f = panel.text(label = tr("Datum der Sitzung"))""").size shouldBe 1
            handBuiltSites("""form.textField(label = tr("Beginn"), hint = tr("Beispiel: 2026-03-14."))""").size shouldBe 1
            handBuiltSites("""form.textField(label = tr("Uhrzeit (hh:mm)"), value = x)""").size shouldBe 1
            // negative: a number field, a typed call, an unrelated label, a function declaration
            handBuiltSites("""form.textField(label = tr("Geschäftsjahr"), type = InputType.NUMBER)""").size shouldBe 0
            handBuiltSites("""form.textField(label = tr("Datum"), type = InputType.DATE)""").size shouldBe 0
            handBuiltSites("""panel.text(label = tr("Beschreibung"))""").size shouldBe 0
            handBuiltSites("""fun textField(label: String = "Datum")""").size shouldBe 0
            // a call over several lines is balanced as a whole
            handBuiltSites("form.textField(\n    label = tr(\"Beschreibung\"),\n    hint = tr(\"Stichtag beachten\"),\n)").size shouldBe 1
            val copy = File.createTempFile("t9-red-proof", ".kt")
            try {
                copy.writeText(
                    codeText(screens.first { it.name == "CarpoolScreen.kt" }) + "\nval bad = form.textField(label = tr(\"Datum\"))\n",
                )
                handBuiltSites(codeText(copy)).size shouldBe
                    1 + handBuiltSites(codeText(screens.first { it.name == "CarpoolScreen.kt" })).size
            } finally {
                copy.delete()
            }
            val findings = screens.associate { it.name to handBuiltSites(codeText(it)).size }.filterValues { it > 0 }
            findings shouldBe T9_ALLOWLIST
        }

        test("T9 ratchet: the allowlist does not grow") {
            T9_ALLOWLIST.values.sum() shouldBe 3
        }
    })
