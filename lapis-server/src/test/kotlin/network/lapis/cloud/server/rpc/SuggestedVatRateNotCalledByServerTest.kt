package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Welle V1.4.13 "USt-Voranmeldung" -- structural guard, source-text scan (same "reading the
 * repo's own source in a test" idiom as `network.lapis.cloud.server.db.KumlModelLoader`'s
 * `kumlSourceDir` resolution).
 *
 * `network.lapis.cloud.shared.domain.suggestedVatRate` is a CLIENT-ONLY heuristic (see its own
 * KDoc). This test proves, by scanning every `.kt` file under `lapis-server/src/main`, that no
 * server-side production code EVER calls it -- not as validation, not as a fallback, not as
 * plausibilisation. If this test ever fails, someone reintroduced exactly the "server infers/
 * checks the VAT rate from the sphere" mechanism the design deliberately rejected.
 */
class SuggestedVatRateNotCalledByServerTest :
    FunSpec({
        test("no lapis-server production source references suggestedVatRate") {
            val root =
                File("src/main/kotlin").let { relative ->
                    if (relative.exists()) relative else File("lapis-server/src/main/kotlin")
                }
            check(root.exists()) { "could not resolve lapis-server/src/main/kotlin from working dir ${File(".").absolutePath}" }

            val offendingFiles =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.readText().contains("suggestedVatRate") }
                    .map { it.relativeTo(root).path }
                    .toList()

            offendingFiles.shouldBeEmpty()
        }
    })
