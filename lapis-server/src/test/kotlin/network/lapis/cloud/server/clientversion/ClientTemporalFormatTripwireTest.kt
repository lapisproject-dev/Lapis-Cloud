package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * W7 "Zeitstempel-Formatierung app-weit": four rules that keep the ONE date/time formatter (`DateTime.kt`) the only one --
 * the temporal sibling of [ClientMoneyFormatTripwireTest] (M1-M6 for `Money.kt`). Client sources are read as text
 * (`jsTest` under Karma has no file system, so every tripwire lives here); comment lines are exempt, and every rule has a
 * positive and a negative example so a broken regex cannot go silent. Gradle runs server tests with `lapis-server` as the
 * working directory.
 *
 * Review-Befund 2026-09-24: `DateTime.kt`'s own file KDoc claimed a `T6` rule of this exact class name already enforced
 * "Formatting therefore never touches `toInstant`, `TimeZone` or `Clock`" -- this class did not exist at all. What is now
 * T4 below is that rule, made real; T1-T3 close the same kind of gap for the other claims that same KDoc makes.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

/** T1: `Intl.DateTimeFormat`/`toLocaleDateString`/`toLocaleTimeString` depend on the browser's ICU version (flaky golden
 * tests) and this app only ever needs numeric components in a fixed per-language order -- see `DateTime.kt`'s file KDoc
 * "Why no Intl.DateTimeFormat / toLocaleDateString". */
private val FORBIDDEN_DATE_API = Regex("""Intl\.DateTimeFormat|toLocaleDateString\(|toLocaleTimeString\(""")

/** T2: a temporal token concatenated into another string leaks the i18n marker (mirrors `ClientMoneyFormatTripwireTest`'s
 * M3 for the five money/LTR kinds). */
private val TEMPORAL_TOKEN_CALL = Regex("""\b(?:dateToken|dayMonthToken|dateTimeToken|timestampToken|timeToken)\(""")
private val TOKEN_CONCATENATION = Regex(""""\s*\+|\+\s*"|\$\{|\$\w|gettext\(""")

/** T3: no screen re-derives `DateTime.kt`'s own zero-padding of a day/month/hour/minute/second/year component instead of
 * calling its `internal` `pad2`/`pad4` (Review-Befund 2026-09-24: three independent
 * hand-rolled `.toString().padStart(...)` reimplementations existed side by side with `DateTime.kt`'s own, in
 * `PriceHistoryChartData.kt`, `ConferenceScreen.kt` (two sites) and `ConferenceStreamDestinationsScreen.kt` -- plus a
 * fourth, differently-shaped one, `AuctionScreen.kt`'s private `Int.pad2()`, all closed in the same review-fix pass this
 * tripwire was added in). A duration (`conferenceRecordingDurationLabel`'s `minutes`/`secs` from an elapsed second count,
 * not a `LocalDateTime` component) never matches -- the property name right before the dot must be the exact singular
 * `day`/`hour`/`minute`/`second`, not a locally chosen variable name. */
private val HAND_ROLLED_PADDING =
    Regex(
        """\.(?:day|hour|minute|second)\.toString\(\)\.padStart\(2,\s*'0'\)|""" +
            """\.month\.number\.toString\(\)\.padStart\(2,\s*'0'\)|""" +
            """\.year\.toString\(\)\.padStart\(4,\s*'0'\)""",
    )

/** T4: `DateTime.kt`'s own `format*`/`*Components` functions never touch `toInstant`, `TimeZone` or `Clock` -- the load-
 * bearing claim of that file's own KDoc (see this class' own KDoc above). Every persisted temporal field is a "naked"
 * server-local `LocalDate`/`LocalDateTime` with no zone information to convert FROM; re-deriving a zone here would
 * silently disagree with what the server actually stored. Scanned against `DateTime.kt` alone -- other client files
 * legitimately touch these three (e.g. `PriceHistoryChartData.kt`'s UTC-anchored gap-distance epoch millis,
 * `ConferenceScreen.kt`'s `Clock.System.now()` for "right now", `AuctionScreen.kt`'s "price fetched at" timestamp --
 * none of them a re-interpretation of a STORED value for display). */
private val ZONE_OR_INSTANT_API = Regex("""\btoInstant\(|\bTimeZone\b|\bClock\b""")

class ClientTemporalFormatTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val others = files.filter { it.name != "DateTime.kt" }
        val dateTimeKt = files.first { it.name == "DateTime.kt" }

        test("the scan sees the client sources") {
            (files.size > 100) shouldBe true
            files.any { it.name == "DateTime.kt" } shouldBe true
        }

        test("T1: no Intl.DateTimeFormat / toLocaleDateString / toLocaleTimeString in the client, and the detector sees one") {
            files.flatMap { f ->
                codeLines(f).filter { FORBIDDEN_DATE_API.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
            } shouldBe
                emptyList()
            FORBIDDEN_DATE_API.containsMatchIn("""x.asDynamic().toLocaleDateString("de-DE")""") shouldBe true
            FORBIDDEN_DATE_API.containsMatchIn("""Intl.DateTimeFormat("de").format(x)""") shouldBe true
            FORBIDDEN_DATE_API.containsMatchIn("""formatDate(x)""") shouldBe false
        }

        test("T2: a temporal token is never concatenated into a string nor passed to gettext, and the detector sees both") {
            val findings =
                files.flatMap { f ->
                    codeLines(f)
                        .filter { TEMPORAL_TOKEN_CALL.containsMatchIn(it) && TOKEN_CONCATENATION.containsMatchIn(it) }
                        .map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            TOKEN_CONCATENATION.containsMatchIn(""""Termin: " + dateToken(x)""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""gettext("Termin: %1", dateTimeToken(x))""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""val s = "${'$'}{timestampToken(x)} extra"""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""trFormat(tr("Termin: %1"), dateToken(x))""") shouldBe false
            TOKEN_CONCATENATION.containsMatchIn("""span(dateTimeToken(at)) {""") shouldBe false
        }

        test("T3: no screen re-derives DateTime.kt's own zero-padding of a date/time component, and the detector sees one") {
            val findings =
                others.flatMap { f ->
                    codeLines(f).filter { HAND_ROLLED_PADDING.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            HAND_ROLLED_PADDING.containsMatchIn("""val h = startedAt.hour.toString().padStart(2, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val y = dateTime.year.toString().padStart(4, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val m = now.month.number.toString().padStart(2, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val h = pad2(startedAt.hour)""") shouldBe false
            // an elapsed-duration component (a local Long, not a LocalDateTime property) must not false-positive
            HAND_ROLLED_PADDING.containsMatchIn(""""${'$'}hours:${'$'}{minutes.toString().padStart(2, '0')}"""") shouldBe false
        }

        test("T4: DateTime.kt's own formatting never touches toInstant / TimeZone / Clock, and the detector sees one") {
            val findings = codeLines(dateTimeKt).filter { ZONE_OR_INSTANT_API.containsMatchIn(it) }.map { it.trim() }
            findings shouldBe emptyList()
            ZONE_OR_INSTANT_API.containsMatchIn("""at.toInstant(TimeZone.currentSystemDefault())""") shouldBe true
            ZONE_OR_INSTANT_API.containsMatchIn("""val now = Clock.System.now()""") shouldBe true
            ZONE_OR_INSTANT_API.containsMatchIn("""formatDateComponents(year, month, day, locale)""") shouldBe false
        }
    })
