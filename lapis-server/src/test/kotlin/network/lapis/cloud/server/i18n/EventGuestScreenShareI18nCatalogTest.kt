package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.282 -- the texts of the guest screen share: every new msgid is present in all seven catalogs with a non-empty msgstr that keeps its `%1`
 * placeholder, the German source texts are not gendered, and the list below still matches the client source (so it cannot go stale). Lives in
 * `lapis-server` because `jsTest` has no file system (see `BankStatementI18nCatalogTest`).
 */
class EventGuestScreenShareI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")
        val sourceFiles = listOf("EventGuestScreenShare.kt", "ConferenceGuestShare.kt", "EventGuestCallView.kt")

        val msgids =
            listOf(
                "Teilen beenden",
                "Sie dürfen jetzt Ihren Bildschirm teilen.",
                "Die Freigabe zum Bildschirmteilen wurde beendet.",
                "Die Moderation hat Ihre Bildschirmfreigabe beendet.",
                "Bildschirm teilen ist auf diesem Gerät nicht möglich.",
                "Darf den Bildschirm nicht teilen",
                "Darf den Bildschirm teilen",
                "Teilt gerade den Bildschirm",
                "Bildschirm teilen erlauben",
                "Freigabe entziehen",
                "Bildschirm teilen erlauben für %1",
                "Freigabe entziehen für %1",
                "Aufzeichnung und Livestream laufen: Ein geteilter Bildschirm ist in beiden zu sehen.",
                "Die Aufzeichnung läuft: Ein geteilter Bildschirm wird mit aufgezeichnet.",
                "Der Livestream läuft: Ein geteilter Bildschirm ist für alle Zuschauer zu sehen.",
                "Der Gast darf jetzt den Bildschirm teilen.",
                "Die Freigabe wurde entzogen.",
                "Es darf immer nur ein Gast den Bildschirm teilen. Bitte entziehen Sie zuerst die andere Freigabe.",
                "Dieser Gast ist nicht mehr im Raum.",
                "Das Teilen ließ sich nicht beenden. Der Gast wurde aus dem Raum entfernt.",
                // reused, already in the catalogs
                "Bildschirm teilen",
                "Gäste",
            )

        fun catalog(name: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            var id: String? = null
            File(i18nDir, name).forEachLine { raw ->
                val line = raw.trimEnd('\r')
                when {
                    line.startsWith("msgid ") -> id = line.removePrefix("msgid ").trim().removeSurrounding("\"")
                    line.startsWith("msgstr ") && id != null -> {
                        result[id!!] = line.removePrefix("msgstr ").trim().removeSurrounding("\"")
                        id = null
                    }
                }
            }
            return result
        }

        test("every new msgid is in all seven catalogs and the template, non-empty, with its placeholder") {
            val problems = mutableListOf<String>()
            languages.forEach { language ->
                val entries = catalog("messages-$language.po")
                msgids.forEach { msgid ->
                    val translated = entries[msgid]
                    when {
                        translated == null -> problems += "$language: missing \"$msgid\""
                        translated.isBlank() -> problems += "$language: empty \"$msgid\""
                        msgid.contains("%1") && !translated.contains("%1") -> problems += "$language: lost %1 in \"$msgid\""
                    }
                }
            }
            val template = catalog("messages.pot")
            msgids.forEach { if (it !in template) problems += "pot: missing \"$it\"" }
            problems.shouldBeEmpty()
        }

        test("the German source texts and the catalogs' German side carry no gender marks") {
            val gendered = Regex("""\w(\*|:|_|/)innen|\w(\*|:|_|/)in\b""")
            msgids.filter { gendered.containsMatchIn(it) }.shouldBeEmpty()
            val sources = sourceFiles.map { File(clientSrc, it).readText() }
            sources.filter { gendered.containsMatchIn(it) }.shouldBeEmpty()
        }

        test("every listed msgid is really used by the client source of this wave (the list cannot go stale)") {
            val sources = sourceFiles.joinToString("\n") { File(clientSrc, it).readText() }
            msgids.filter { sources.indexOf("\"${it.replace("\"", "\\\"")}\"") < 0 }.shouldBeEmpty()
            (msgids.size > 15) shouldBe true
        }
    })
