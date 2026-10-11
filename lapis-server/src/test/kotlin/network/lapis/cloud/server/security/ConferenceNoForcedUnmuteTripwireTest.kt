package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.108 -- a moderator may MUTE a microphone or ASK for it to be switched on; nothing in the server may ever switch a microphone ON
 * (privacy: nobody must be turned into an open microphone by somebody else). This scans `lapis-server/src/main/kotlin` and fails the
 * build if a forced-unmute path appears:
 *
 *  1. a request that sends `muted = false` (any spelling: JSON text, named argument, assignment);
 *  2. an identifier that talks about "unmute", other than the allow-list below (`requestUnmute` = the ASK, `unmute_request` = its packet,
 *     `participantsMayUnmute` = the room lock flag);
 *  3. any construction of `LiveKitMutePublishedTrackRequest` that passes a `muted` argument at all.
 *
 * Comments are ignored. The response DTO `LiveKitTrackInfo` declares `val muted: Boolean = false` -- that is what LiveKit REPORTS about a
 * track (default for a missing field), not something this server sends, and it has a type annotation so none of the patterns below match
 * it; [the allow-list test] pins that it is the only such declaration.
 */
class ConferenceNoForcedUnmuteTripwireTest :
    FunSpec({
        /** Comments removed, string literals KEPT (the JSON body `"muted":false` lives inside a string). */
        fun stripComments(src: String): String {
            val out = StringBuilder(src.length)
            var i = 0
            val n = src.length
            while (i < n) {
                when {
                    src.startsWith("//", i) -> while (i < n && src[i] != '\n') i++
                    src.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < n) {
                            if (src.startsWith("/*", i)) {
                                depth++
                                i += 2
                            } else if (src.startsWith("*/", i)) {
                                depth--
                                i += 2
                                if (depth == 0) break
                            } else {
                                if (src[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                    src.startsWith("\"\"\"", i) -> {
                        val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n - 3 else it }
                        out.append(src, i, end + 3)
                        i = end + 3
                    }
                    src[i] == '"' -> {
                        out.append(src[i++])
                        while (i < n && src[i] != '"') {
                            if (src[i] == '\\' && i + 1 < n) out.append(src[i++])
                            out.append(src[i++])
                        }
                        if (i < n) out.append(src[i++])
                    }
                    else -> out.append(src[i++])
                }
            }
            return out.toString()
        }

        val files = SourceScan.mainFiles()

        val forcedMutedFalse =
            listOf(
                Regex("""\\?"muted\\?"\s*:\s*false"""),
                Regex("""\bmuted\s*=\s*false\b"""),
                Regex("""\bmuted\s*=\s*!"""),
            )
        val allowedUnmuteIdentifier =
            Regex(
                "(?i)requestUnmute|unmuteRequest|UNMUTE_REQUEST|MayUnmute|MAY_UNMUTE",
            )

        test("the scanner really sees the LiveKit client (guards against a silently empty scan)") {
            val client = files.single { it.name == "LiveKitAdminClient.kt" }
            stripComments(client.readText()).contains("LiveKitMutePublishedTrackRequest") shouldBe true
            files.size shouldBe files.map { it.absolutePath }.toSet().size
            (files.size > 100) shouldBe true
        }

        test("no request in the server sends muted=false, in any spelling") {
            val offenders =
                files.flatMap { f ->
                    val text = stripComments(f.readText())
                    forcedMutedFalse.filter { it.containsMatchIn(text) }.map { "${f.name}: ${it.pattern}" }
                }
            offenders.shouldBeEmpty()
        }

        test("no identifier names an unmute other than the allow-listed ask / lock names") {
            val offenders = mutableListOf<String>()
            files.forEach { f ->
                val code = SourceScan.blank(f.readText())
                Regex("""[A-Za-z0-9_]*(?i:unmute)[A-Za-z0-9_]*""").findAll(code).forEach { m ->
                    if (!allowedUnmuteIdentifier.containsMatchIn(m.value)) offenders += "${f.name}: ${m.value}"
                }
            }
            offenders.shouldBeEmpty()
        }

        test("LiveKitMutePublishedTrackRequest is never constructed with a muted argument") {
            val offenders = mutableListOf<String>()
            files.forEach { f ->
                val code = stripComments(f.readText())
                Regex("""LiveKitMutePublishedTrackRequest\(""").findAll(code).forEach { m ->
                    if (code.substring(maxOf(0, m.range.first - 6), m.range.first).contains("class ")) return@forEach
                    var depth = 0
                    var end = m.range.last
                    for (i in m.range.last until code.length) {
                        if (code[i] == '(') depth++
                        if (code[i] == ')') depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                    if (Regex("""\bmuted\b""").containsMatchIn(code.substring(m.range.first, end + 1))) offenders += f.name
                }
            }
            offenders.shouldBeEmpty()
        }

        test("allow-list pin: the only 'muted: Boolean = false' declaration is the RESPONSE dto LiveKitTrackInfo, not a request") {
            val declarations =
                files.filter { Regex("""\bval\s+muted\s*:\s*Boolean\s*=\s*false""").containsMatchIn(stripComments(it.readText())) }
            declarations.map(File::getName) shouldBe listOf("LiveKitAdminClient.kt")
            val text = stripComments(declarations.single().readText())
            val declaration = Regex("""data class (\w+)\([^)]*\bval\s+muted\s*:\s*Boolean\s*=\s*false""").find(text)!!.groupValues[1]
            declaration shouldBe "LiveKitTrackInfo"
        }
    })
