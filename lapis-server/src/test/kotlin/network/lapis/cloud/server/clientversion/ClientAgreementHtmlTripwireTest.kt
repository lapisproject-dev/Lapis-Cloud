package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.196 -- the client never renders Markdown and never builds HTML from an agreement text by itself. HTML reaches the page in ONE place: the
 * `agreementHtml` function of `AgreementTextRendering.kt`, fed only with the `renderedHtml` of a DTO (the server's sanitizing renderer). The
 * privacy tripwires of the encounter and member-finder clients (`ClientEncounterPrivacyTripwireTest`, `ClientMemberFinderPrivacyTripwireTest`)
 * scan the SPELLING `rich = true` in their own files and stay unchanged: those files do not contain it, they call `renderAgreementBody`. This
 * test pins that this stays so.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private fun clientFiles(): List<File> = CLIENT_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

private fun code(file: File): String =
    file
        .readLines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n")

private val RICH = Regex("""\brich\s*=\s*true\b""")

class ClientAgreementHtmlTripwireTest :
    FunSpec({
        test("the scan sees the client (not vacuous)") {
            (clientFiles().size > 100) shouldBe true
        }

        test("agreementHtml is defined once and has exactly one rich site; nobody else calls it") {
            val rendering = code(File(CLIENT_DIR, "AgreementTextRendering.kt"))
            Regex("""fun Container\.agreementHtml\(""").findAll(rendering).count() shouldBe 1
            RICH.findAll(rendering).count() shouldBe 1
            val callers =
                clientFiles()
                    .filter { it.name != "AgreementTextRendering.kt" }
                    .filter { Regex("""\bagreementHtml\s*\(""").containsMatchIn(code(it)) }
                    .map { it.name }
            callers shouldBe emptyList()
        }

        test("the files that show an agreement text never spell rich = true or innerHTML themselves") {
            val shown =
                clientFiles().filter { file ->
                    file.name in
                        setOf(
                            "AgreementTextsScreen.kt",
                            "RegistrationScreen.kt",
                            "FriendRegistrationScreen.kt",
                            "RegistrationFormLayout.kt",
                            "MemberFinderScreen.kt",
                            "EventCaptureConsent.kt",
                            "EncounterEntryPanel.kt",
                            "EncounterPulpitRecordingConsentDialog.kt",
                            // V1.9.199: the Markdown contact hint of the travel-expense page (shown and edited)
                            "TravelExpenseContactBlock.kt",
                            "TravelContactHintEditor.kt",
                        )
                }
            (shown.size >= 10) shouldBe true
            shown.filter { RICH.containsMatchIn(code(it)) || Regex("""\binnerHTML\b""").containsMatchIn(code(it)) }.map { it.name } shouldBe
                emptyList()
        }

        test("the client has no Markdown renderer of its own: no Markdown library is imported or loaded as a JS module") {
            val library =
                Regex(
                    """(import\s+[\w.]*(commonmark|markdown|showdown|marked)\b|@JsModule\([^)]*(commonmark|markdown|showdown|marked)|require\([^)]*(commonmark|markdown|showdown|marked))""",
                    RegexOption.IGNORE_CASE,
                )
            clientFiles().filter { library.containsMatchIn(code(it)) }.map { it.name } shouldBe emptyList()
        }
    })
