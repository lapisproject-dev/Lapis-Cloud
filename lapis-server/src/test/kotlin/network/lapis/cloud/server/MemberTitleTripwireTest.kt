package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * V151 tripwire: `display_name` is never recomposed from the title, and title and name are composed in exactly one place
 * (`MemberNames.withTitle`). A production file that writes `displayName` from a `title` expression, or concatenates the two itself,
 * fails here.
 */
class MemberTitleTripwireTest :
    FunSpec({
        fun productionSources(): List<File> =
            listOf(File("src/main/kotlin"), File("../lapis-shared/src/commonMain"), File("../lapis-client/src/jsMain/kotlin"))
                .filter { it.exists() }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .filterNot { it.name == "MemberNames.kt" }

        test("no production code assigns display_name from a title or concatenates title and display name itself") {
            val offenders =
                productionSources().filter { f ->
                    f.readLines().any { line ->
                        val code = line.substringBefore("//")
                        (
                            code.contains(
                                "MemberTable.displayName]",
                            ) &&
                                Regex("""=\s*.*\btitle\b""").containsMatchIn(code.substringAfter("]"))
                        ) ||
                            code.contains("\${title} \${displayName}") ||
                            code.contains("\$title \$displayName") ||
                            code.contains("\${title.orEmpty()} \${displayName}")
                    }
                }
            offenders.map { it.path }.shouldBeEmpty()
        }
    })
