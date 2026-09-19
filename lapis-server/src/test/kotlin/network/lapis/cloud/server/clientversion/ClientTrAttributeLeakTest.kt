package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwire for a bug found live on staging (V1.4.20): KVision's `tr()` returns a string wrapped in an
 * internal `###KvI18nS###` marker that is only resolved inside KVision's own vnode patch cycle. Feeding
 * a `tr(...)` result straight into `setAttribute(...)` therefore leaks the marker into the rendered
 * `title` / `aria-label` (a screen reader would announce "###KvI18nS###Ausblenden"). Use `gettext(...)`
 * for raw attributes. Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val TR_INTO_SET_ATTRIBUTE = Regex("""setAttribute\([^)\n]*\btr\(""")

class ClientTrAttributeLeakTest :
    FunSpec({
        test("no client source feeds tr() directly into setAttribute()") {
            val offenders =
                CLIENT_SOURCES
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (TR_INTO_SET_ATTRIBUTE.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                        }
                    }.toList()
            offenders.shouldBeEmpty()
        }

        test("the scanned client source directory exists (tripwire is not vacuous)") {
            CLIENT_SOURCES.isDirectory shouldBe true
        }
    })
