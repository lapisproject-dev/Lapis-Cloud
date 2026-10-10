package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.100 -- i18n guard for the one new client sentence (the third paragraph of the MCP access card, the
 * article-draft hint): present in the template and in all seven language catalogs with a non-empty `msgstr`, and
 * really used by `McpAccessCard.kt`. `jsTest` has no file system, hence this lives in `lapis-server`.
 */
class McpArticleDraftI18nCatalogTest :
    FunSpec({
        val msgid =
            "Nur wenn Sie Redaktionsrechte haben (Vorstand oder Administration): Agenten können außerdem Artikel-Entwürfe anlegen. " +
                "Der Agent legt nur Entwürfe an; einreichen können nur Sie selbst, freigegeben wird wie jeder Artikel durch ein anderes Vorstandsmitglied."

        fun msgstrOf(
            file: File,
            id: String,
        ): String? {
            val lines = file.readLines()
            val index = lines.indexOfFirst { it == "msgid \"$id\"" }
            if (index < 0) return null
            val next = lines.getOrNull(index + 1) ?: return null
            return next.removePrefix("msgstr \"").removeSuffix("\"")
        }

        test("the sentence is used verbatim by McpAccessCard.kt (string parts joined with + concatenate to the msgid)") {
            val source = File(CLIENT_KOTLIN_DIR, "McpAccessCard.kt").readText().replace(Regex("\"\\s*\\+\\s*\""), "")
            source.contains(msgid) shouldBe true
        }

        test("the template and all seven catalogs carry the sentence with a non-empty translation") {
            msgstrOf(File(CATALOG_DIR, "messages.pot"), msgid) shouldBe ""
            val missing =
                CATALOG_LANGUAGES.filter { lang ->
                    val translated = msgstrOf(File(CATALOG_DIR, "messages-$lang.po"), msgid)
                    translated.isNullOrBlank()
                }
            missing.shouldBeEmpty()
        }
    })
