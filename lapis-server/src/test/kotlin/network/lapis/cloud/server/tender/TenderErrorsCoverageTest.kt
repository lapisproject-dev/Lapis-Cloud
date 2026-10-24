package network.lapis.cloud.server.tender

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private val serverRoot: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }
private val clientTenderErrors: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/TenderErrors.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/TenderErrors.kt") }

/**
 * V1.9.202 -- every refusal the tender code can throw has a German text on the client (`TenderErrors.kt`). The scan reads the `ConflictException(...)`
 * of the tender files (a literal, a constant, or a literal with `$LIMIT` templates; a `#` in the client key stands for the number) and fails the build as
 * soon as a new message has no entry -- before a person ever sees the generic fallback for something that has a proper sentence.
 */
class TenderErrorsCoverageTest :
    FunSpec({
        val tenderFiles =
            listOf(
                File(serverRoot, "rpc/TenderService.kt"),
                File(serverRoot, "tender/TenderMotionBinding.kt"),
                File(serverRoot, "tender/TenderConflicts.kt"),
                File(serverRoot, "tender/TenderVisibilityPolicy.kt"),
            )
        val sources = tenderFiles.associateWith { it.readText() }

        /** `const val NAME = "..."` of the tender files (a value on the next line is read as well). */
        val constants: Map<String, String> =
            sources.values
                .flatMap { code ->
                    Regex("""(?:private |internal )?const val ([A-Z_]+)\s*=\s*"((?:[^"\\]|\\.)*)"""").findAll(code).map {
                        it.groupValues[1] to
                            it.groupValues[2]
                    }
                }.toMap()

        fun normalise(raw: String): String = raw.replace(Regex("""\$\{?[A-Za-z_]+}?"""), "#")

        val messages: Set<String> =
            sources.values
                .flatMap { code ->
                    Regex("""ConflictException\(\s*(?:"((?:[^"\\]|\\.)*)"|([A-Za-z_.]+))\s*\)""").findAll(code).mapNotNull { match ->
                        val literal = match.groupValues[1]
                        val name = match.groupValues[2].substringAfterLast('.')
                        when {
                            literal.isNotEmpty() -> normalise(literal)
                            name in constants -> normalise(constants.getValue(name))
                            else -> null
                        }
                    }
                }.toSet()

        test("the scan sees the refusals of the tender code") {
            messages.size shouldBeGreaterThan 25
            clientTenderErrors.exists() shouldBe true
        }

        test("every refusal of the tender code has a German text in TenderErrors.kt") {
            val client = clientTenderErrors.readText()
            val missing = messages.filter { "\"$it\"" !in client }
            withClue("messages without an entry in TenderErrors.kt (exact or numbered with #)") { missing.shouldBeEmpty() }
        }
    })
