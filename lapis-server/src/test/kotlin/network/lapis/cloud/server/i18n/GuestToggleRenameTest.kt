package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.139 -- the moderator's switch of a conference room was renamed from "Gäste anderer Server zulassen" / badge "Gäste zugelassen" to
 * "Gäste und Freunde zulassen" / "Gäste und Freunde zugelassen" (friend accounts count as guests; the old label hid that). The API, the database and
 * the field `allowFederationGuests` are unchanged.
 *
 * What is NOT searched, on purpose: the sentence "... lässt derzeit keine Gäste anderer Server zu", the reason shown to a FEDERATED guest
 * (`ConferenceGuestJoinUiTest` pins it), and `CHANGELOG.md` (history).
 */
class GuestToggleRenameTest :
    FunSpec({
        val repoRoot: File =
            File("..").let { if (File(it, "lapis-client").exists()) it else File(".") }.canonicalFile
        val oldLabel = "Gäste anderer Server zulassen"
        val oldBadge = Regex("\"Gäste zugelassen\"")
        val newLabel = "Gäste und Freunde zulassen"
        val newBadge = "Gäste und Freunde zugelassen"
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        fun scanned(): List<File> {
            val roots =
                listOf(
                    "lapis-client/src/jsMain",
                    "lapis-client/src/jsTest",
                    "lapis-server/src/main",
                    "docs",
                    "deploy",
                ).map { File(repoRoot, it) } + listOf(File(repoRoot, "README.adoc"))
            return roots
                .filter { it.exists() }
                .flatMap { root -> if (root.isFile) listOf(root) else root.walkTopDown().filter { it.isFile }.toList() }
                .filter { it.extension in setOf("kt", "po", "pot", "adoc", "md", "html", "yml", "yaml", "css", "properties") }
        }

        test("the old label and the old badge text appear nowhere in the client, the catalogs, the docs and the deploy files") {
            val files = scanned()
            (files.size > 100) shouldBe true // the scan sees the code base
            val offenders =
                files.flatMap { file ->
                    val text = file.readText()
                    buildList {
                        if (oldLabel in text) add("${file.relativeTo(repoRoot)}: old label")
                        if (oldBadge.containsMatchIn(text)) add("${file.relativeTo(repoRoot)}: old badge")
                    }
                }
            offenders.shouldBeEmpty()
        }

        test("the new label and badge exist in the source and in all seven catalogs with a non-empty translation") {
            val screen =
                File(repoRoot, "lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/ConferenceScreen.kt").readText()
            screen.contains("tr(\"$newLabel\")") shouldBe true
            screen.contains("tr(\"$newBadge\")") shouldBe true
            val i18n = File(repoRoot, "lapis-client/src/jsMain/resources/modules/i18n")
            listOf(newLabel, newBadge).forEach { msgid ->
                languages.forEach { lang ->
                    val lines = File(i18n, "messages-$lang.po").readLines()
                    val at = lines.indexOf("msgid \"$msgid\"")
                    (at >= 0) shouldBe true
                    val msgstr = lines[at + 1]
                    (msgstr.startsWith("msgstr \"") && msgstr.length > "msgstr \"\"".length) shouldBe true
                }
                File(i18n, "messages.pot").readLines().contains("msgid \"$msgid\"") shouldBe true
            }
        }
    })
