package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.38 -- the server never reads "its" time zone from the environment. Every clock read and zone conversion goes through
 * `network.lapis.cloud.server.time.ServerClock` (storage zone fixed to UTC) or an organization zone resolved by
 * `OrganizationTimeZone`. A forbidden API anywhere else in `lapis-server/src/main` fails this test (budget 0); comment lines
 * are exempt. `ZoneId.of(<stored zone>)` and `TimeZone.UTC`/`TimeZone.of(...)` stay legal (explicit).
 */
private val SERVER_MAIN_DIR = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }

private val FORBIDDEN_IMPLICIT_ZONE =
    Regex(
        """currentSystemDefault|ZoneId\.systemDefault|TimeZone\.getDefault|Clock\.systemDefaultZone|""" +
            """\b(?:LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\.now\(\)|""" +
            """\bjava\.util\.Calendar\.getInstance\(\)""",
    )

private fun isCommentLine(line: String) = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

class ServerImplicitZoneTripwireTest :
    FunSpec({
        test("Z1: no implicit process-zone API in lapis-server/src/main outside ServerClock") {
            val offenders =
                SERVER_MAIN_DIR
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name != "ServerClock.kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .withIndex()
                            .filter { (_, line) ->
                                !isCommentLine(line) && FORBIDDEN_IMPLICIT_ZONE.containsMatchIn(line)
                            }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
                    }.toList()
            withClue("Use ServerClock.now()/nowIn(zone)/todayIn(zone) or OrganizationTimeZone instead:\n${offenders.joinToString("\n")}") {
                offenders.shouldBeEmpty()
            }
        }

        test("Z2 (self-test): the pattern matches each forbidden form and nothing legal") {
            listOf(
                "TimeZone.currentSystemDefault()",
                "ZoneId.systemDefault()",
                "java.util.TimeZone.getDefault()",
                "Clock.systemDefaultZone()",
                "LocalDateTime.now()",
                "LocalDate.now()",
            ).forEach { FORBIDDEN_IMPLICIT_ZONE.containsMatchIn(it) shouldBe true }
            listOf(
                "TimeZone.UTC",
                "ZoneId.of(row[EventSeriesTable.timezone])",
                "LocalDateTime.now(clock)",
                "ServerClock.nowIn(orgZone)",
            ).forEach { FORBIDDEN_IMPLICIT_ZONE.containsMatchIn(it) shouldBe false }
        }

        test("Z3: ServerClock itself pins the storage zone to UTC") {
            val text = File(SERVER_MAIN_DIR, "network/lapis/cloud/server/time/ServerClock.kt").readText()
            Regex("""val zone: TimeZone = TimeZone\.UTC""").containsMatchIn(text) shouldBe true
        }
    })
