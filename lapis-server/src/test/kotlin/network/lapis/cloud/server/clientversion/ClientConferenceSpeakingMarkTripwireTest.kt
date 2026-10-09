package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.85 (active-speaker mark of the video conference): the wiring that cannot run in Karma with a real LiveKit call is guarded as text.
 * The mark is client only and volatile; it must stay independent of the D3 reflow, never be the face of a button (R58), never move a
 * node, never animate, and its one text must stay in all seven catalogs.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val RESOURCES_DIR: File =
    File("../lapis-client/src/jsMain/resources").let { if (it.exists()) it else File("lapis-client/src/jsMain/resources") }

private fun code(relative: String): String =
    File(CLIENT_DIR, relative)
        .readText()
        .lines()
        .filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
        .joinToString("\n")

class ClientConferenceSpeakingMarkTripwireTest :
    FunSpec({
        test("the mark state never touches the D3 map and is DOM free") {
            val state = code("ConferenceSpeakingMark.kt")
            state shouldNotContain "lastSpokeAtMs ="
            state shouldNotContain "document."
            state shouldNotContain "kotlinx.browser"
            state shouldNotContain "localStorage"
            state shouldNotContain "println"
        }

        test("the screen applies the mark synchronously on a report, keeps the D3 line and never reflows from the mark") {
            val screen = code("ConferenceScreen.kt")
            screen shouldContain "identities.forEach { identity -> lastSpokeAtMs[identity] = now }"
            screen shouldContain "speakingMark.onReport(identities, now)"
            val apply = screen.substringAfter("fun applySpeakingMarks").substringBefore("fun stopSpeakingTick")
            apply shouldNotContain "applyConferenceGridReflow"
            apply shouldNotContain "refreshRoster"
            apply shouldNotContain "lastSpokeAtMs"
            val setter = screen.substringAfter("fun setTileSpeaking").substringBefore("fun applySpeakingMarks")
            setter shouldNotContain "refreshRoster"
            setter shouldNotContain "aria-label"
        }

        test("the beat is stopped on every leave path") {
            val screen = code("ConferenceScreen.kt")
            withClue("performLeave, end for all, terminate") {
                Regex("stopSpeakingTick\\(\\)").findAll(screen).count() shouldBe 5 // 3 leave paths + the beat itself + its definition's use
            }
            screen.substringAfter("fun performLeave()").substringBefore("leaveButton.onClick") shouldContain "stopSpeakingTick()"
            screen.substringAfter("override suspend fun terminate").substringBefore("override fun toggleMic") shouldContain
                "stopSpeakingTick()"
        }

        test("the float selection never reads the mark") {
            val selection = code("ConferenceFloatSelection.kt")
            selection.substringAfter("internal fun FloatMediaSource.info()").substringBefore("\n") shouldNotContain "speakingMarked"
            selection.substringAfter("internal fun floatSelectionOf") shouldNotContain "speakingMarked"
        }

        test("the symbol is a state display: no button, no markup from text, no live region") {
            for (file in listOf("ConferenceSpeakingBadge.kt", "ConferenceSpeakingMark.kt")) {
                val text = code(file)
                text shouldNotContain "innerHTML"
                text shouldNotContain "aria-live"
                text shouldNotContain "\"status\""
                text shouldNotContain "actionButton"
                text shouldNotContain "tr(\"spricht\")"
            }
            code("ConferenceSpeakingBadge.kt") shouldContain "gettext(\"spricht\")"
            code("ConferenceSpeakingBadge.kt") shouldContain "aria-hidden"
        }

        test("the selectors of the mark carry no animation and no transition") {
            val css = File(RESOURCES_DIR, "theme.css").readText()
            val rules = Regex("([^{}]+)\\{([^{}]*)}").findAll(css).toList()
            val mine =
                rules.filter {
                    it.groupValues[1].contains("lapis-conference-tile--speaking") ||
                        it.groupValues[1].contains("lapis-conference-speaking") ||
                        it.groupValues[1].contains("lapis-roster-speaking")
                }
            (mine.size >= 5) shouldBe true
            for (rule in mine) {
                withClue(rule.groupValues[1].trim()) {
                    rule.groupValues[2] shouldNotContain "animation"
                    rule.groupValues[2] shouldNotContain "transition"
                }
            }
        }

        test("the one text 'spricht' is in the template and in all seven catalogs") {
            val i18n = File(RESOURCES_DIR, "modules/i18n")
            val files = listOf("messages.pot") + listOf("en", "es", "fr", "it", "nl", "pl", "ru").map { "messages-$it.po" }
            for (name in files) {
                withClue(name) {
                    val text = File(i18n, name).readText()
                    val entry = text.substringAfter("msgid \"spricht\"\n", "")
                    (entry.isNotEmpty()) shouldBe true
                    if (name.endsWith(".po")) {
                        val msgstr =
                            entry
                                .lineSequence()
                                .first()
                                .removePrefix("msgstr \"")
                                .removeSuffix("\"")
                        msgstr.isNotBlank() shouldBe true
                    }
                }
            }
        }
    })
