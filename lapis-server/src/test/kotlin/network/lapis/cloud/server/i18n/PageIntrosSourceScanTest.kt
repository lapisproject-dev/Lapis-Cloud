package network.lapis.cloud.server.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** The views that may carry a `more` part, each with its reason. Closed on purpose: a new entry needs a design decision, not a habit. */
private val MORE_ALLOWED: Map<String, String> =
    mapOf("audit-log" to "the GoBD statement about the log is legal wording that must stay one click away, never in the headline text")

/** Words that make a promise the software cannot keep: banned in `text` AND `more`. */
private val BANNED_ANYWHERE = listOf("rechtssicher", "gesetzeskonform", "garantiert", "automatisch erfüllt", "steuerfrei")

/** Legal vocabulary that belongs in `more`, if anywhere: banned in `text` only. */
private val BANNED_IN_TEXT = listOf("GoBD", "revisionssicher", "§")

/** The `more = tr(...)` calls of the registry: each balanced call cut out of the code, so their literals can be told from the texts. */
private fun moreCalls(code: String): List<String> =
    Regex("""\bmore\s*=\s*tr\(""")
        .findAll(code)
        .map { match ->
            val start = match.range.last - 2
            var depth = 0
            var i = start
            var inString = false
            while (i < code.length) {
                val c = code[i]
                if (inString) {
                    if (c == '\\') {
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                } else if (c == '"') {
                    inString = true
                } else if (c == '(') {
                    depth++
                } else if (c == ')') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            code.substring(start, i + 1)
        }.toList()

/** viewKey -> does the entry pass a `more`. Each `PageIntroDef(` chunk starts with its view key. */
private fun viewKeysWithMore(code: String): Set<String> =
    code
        .split("PageIntroDef(")
        .drop(1)
        .filter { Regex("""\bmore\s*=""").containsMatchIn(it) }
        .map { Regex(""""([a-z]+(?:-[a-z]+)*)"""").find(it)!!.groupValues[1] }
        .toSet()

/**
 * V1.9.187/V1.9.188: source scan of the page intros (`PageIntros.kt`, `PageIntro.kt`, the screens) -- the half of the guard that the client DOM tests
 * cannot see:
 *  - every route with an entry in the registry is really passed to `pageHeader` by some screen (`PageIntros.forRoute(Routes.X)`), and every
 *    route a screen asks for has an entry (a registry entry nobody draws, or a call that always gets `null`, is a silent gap);
 *  - every intro text is at most 220 characters and at most two sentences (the rule of the page intro);
 *  - the hand-built intro of "Meine Daten" is gone (it moved into the registry), and the intro text is plain text, never `rich`;
 *  - V1.9.188 (Part 2): 79 views (V1.9.231: Kommunikation split in Nachrichten + Mailinglisten) (V1.9.225: Rechenschaftsbericht) (V1.9.221: Organisation) (V1.9.202: Ausschreibungen); the optional `more` part exists only for the views named in [MORE_ALLOWED], is at most 360 characters
 *    and two sentences, and its sentence is not repeated in any screen; no registry sentence is also hand-built in a screen; and the
 *    wording rules: no "rechtssicher"/"gesetzeskonform"/"garantiert"/"automatisch erfüllt"/"steuerfrei" in `text` or `more`, no "GoBD"/
 *    "revisionssicher"/paragraph sign in `text`, no gendered pairs.
 */
class PageIntrosSourceScanTest :
    FunSpec({
        val registryFile by lazy { File(CLIENT_KOTLIN_DIR, "PageIntros.kt") }
        val registryCode by lazy { withoutCommentLines(registryFile.readText()) }
        val registryRoutes by lazy {
            Regex("""(?m)^\s*Routes\.([A-Z_]+)\s*->""").findAll(registryCode).map { it.groupValues[1] }.toSet()
        }
        val listedRoutes by lazy {
            val list = registryCode.substringAfter("val routes: List<String> =")
            Regex("""Routes\.([A-Z_]+)""").findAll(list).map { it.groupValues[1] }.toSet()
        }
        val usedRoutes by lazy {
            clientKotlinFiles()
                .filter { it.name != "PageIntros.kt" }
                .flatMap { file ->
                    Regex("""PageIntros\.forRoute\(\s*Routes\.([A-Z_]+)\s*\)""")
                        .findAll(withoutCommentLines(file.readText()))
                        .map { it.groupValues[1] }
                        .toList()
                }.toSet()
        }

        test("the registry is read (not vacuous): 79 views (23 of Part 1, 56 of Part 2)") {
            registryRoutes.size shouldBe 79
            listedRoutes shouldBe registryRoutes
        }

        test("every registry route is passed to pageHeader by a screen, and every asked route has an entry") {
            withClue("registry entries no screen draws") { (registryRoutes - usedRoutes).shouldBeEmpty() }
            withClue("screens that ask for a route without an entry") { (usedRoutes - registryRoutes).shouldBeEmpty() }
        }

        val allMessages by lazy {
            extractMessages(fileName = registryFile.name, text = registryFile.readText(), constants = clientStringConstants())
        }
        val moreMessages by lazy {
            moreCalls(registryCode).flatMap { call ->
                extractMessages(fileName = registryFile.name, text = call, constants = clientStringConstants())
            }
        }
        val textMessages by lazy { allMessages.filter { message -> moreMessages.none { it.msgid == message.msgid } } }

        test("the messages of the registry are its routes plus the more parts") {
            moreMessages.size shouldBe moreCalls(registryCode).size
            allMessages.size shouldBe registryRoutes.size + moreMessages.size
            textMessages.size shouldBe registryRoutes.size
        }

        test("every intro text is at most 220 characters, at most two sentences and a real sentence") {
            textMessages.forEach { message ->
                val text = message.msgid
                withClue("\"$text\" (${text.length} characters)") { text.length shouldBeLessThanOrEqual 220 }
                val sentences = Regex("""[.!?](?:\s|$)""").findAll(text).count()
                withClue("\"$text\" has $sentences sentences") { sentences shouldBeLessThanOrEqual 2 }
                withClue("\"$text\" is not a real sentence") { text.length shouldBeGreaterThan 40 }
            }
        }

        test("a more part exists only for the allowed views, is at most 360 characters and two sentences") {
            withClue("views with a more part") { viewKeysWithMore(registryCode) shouldBe MORE_ALLOWED.keys }
            MORE_ALLOWED.values.forEach { it.isNotBlank() shouldBe true }
            moreMessages.forEach { message ->
                val text = message.msgid
                withClue("\"$text\" (${text.length} characters)") { text.length shouldBeLessThanOrEqual 360 }
                val sentences = Regex("""[.!?](?:\s|$)""").findAll(text).count()
                withClue("\"$text\" has $sentences sentences") { sentences shouldBeLessThanOrEqual 2 }
            }
        }

        test("the more sentence is not repeated in any screen, and no registry sentence is also hand-built in a screen") {
            val registryIds = allMessages.map { it.msgid }.toSet()
            val repeated =
                clientKotlinFiles()
                    .filter { it.name != registryFile.name }
                    .flatMap { file ->
                        extractMessages(fileName = file.name, text = file.readText(), constants = clientStringConstants())
                            .filter { it.msgid in registryIds }
                            .map { "${file.name}: \"${it.msgid.take(60)}\"" }
                    }
            withClue("sentences that the registry owns but a screen still builds itself") { repeated.shouldBeEmpty() }
        }

        test("the wording rules: no promise words anywhere, no legal vocabulary in the text, no gendered pairs") {
            val bannedAnywhere =
                (textMessages + moreMessages).flatMap { m ->
                    BANNED_ANYWHERE.filter { m.msgid.contains(it, ignoreCase = true) }.map { "\"$it\" in \"${m.msgid.take(60)}\"" }
                }
            withClue("promise words") { bannedAnywhere.shouldBeEmpty() }
            val bannedInText =
                textMessages.flatMap { m ->
                    BANNED_IN_TEXT.filter { m.msgid.contains(it, ignoreCase = true) }.map { "\"$it\" in \"${m.msgid.take(60)}\"" }
                }
            withClue("legal vocabulary in a text (it belongs in more)") { bannedInText.shouldBeEmpty() }
            val gendered =
                (textMessages + moreMessages).filter { m ->
                    Regex("""\w+innen und \w+""").containsMatchIn(m.msgid) ||
                        Regex("""[A-Za-zäöü][*:_/]in(nen)?\b""").containsMatchIn(m.msgid)
                }
            withClue("gendered forms (the house style does not gender)") { gendered.map { it.msgid.take(60) }.shouldBeEmpty() }
        }

        test("the hand-built intro line of Meine Daten moved into the registry") {
            val screen = File(CLIENT_KOTLIN_DIR, "DsgvoRightsScreen.kt").readText()
            screen shouldNotContain "Ihr Recht auf Auskunft"
            clientKotlinFiles().forEach { file -> file.readText() shouldNotContain "Ihr Recht auf Auskunft (Art. 15/20 DSGVO)" }
            usedRoutes.contains("DSGVO_RIGHTS") shouldBe true
        }

        test("the intro text is plain text: no rich content in PageIntro.kt") {
            val code = withoutCommentLines(File(CLIENT_KOTLIN_DIR, "PageIntro.kt").readText())
            Regex("""\brich\s*=\s*true""").containsMatchIn(code) shouldBe false
            code shouldNotContain "RichText"
            code shouldNotContain "innerHTML"
        }

        test("every localStorage access of the page intro storage is guarded by runCatching") {
            val code = withoutCommentLines(File(CLIENT_KOTLIN_DIR, "PageIntro.kt").readText())
            val unguarded =
                code.lines().filter { line ->
                    line.contains("localStorage.") && !line.contains("runCatching") && !line.contains("fun ")
                }
            // the multi-line guarded blocks keep `runCatching {` on the line before; allow exactly those continuation lines
            val lines = code.lines()
            val reallyUnguarded =
                unguarded.filter { line ->
                    val index = lines.indexOf(line)
                    (maxOf(0, index - 2)..index).none { lines[it].contains("runCatching") }
                }
            reallyUnguarded.shouldBeEmpty()
        }
    })
