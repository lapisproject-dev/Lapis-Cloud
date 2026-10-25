package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.207 -- source-scan tripwire: every `SessionStore.createSession(` in main code passes a `LoginMethod.` as second argument.
 * The parameter has a default (124 test call sites), so a future fourth login path could silently forget the "last sign-in" stamp.
 */
class LoginMethodStampTripwireTest :
    FunSpec({
        val call = Regex("""SessionStore\.createSession\(([^)]*)\)""")

        fun callSites(): List<Triple<String, Int, String>> {
            val root = SourceScan.mainRoot()
            return SourceScan.mainFiles().flatMap { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val code = SourceScan.blank(file.readText())
                call
                    .findAll(
                        code,
                    ).map { Triple(relative, SourceScan.lineOf(text = code, index = it.range.first), it.groupValues[1]) }
                    .toList()
            }
        }

        test("every production createSession call stamps a LoginMethod") {
            callSites()
                .filter {
                    !Regex(
                        """LoginMethod\.[A-Z_]+""",
                    ).containsMatchIn(it.third)
                }.map { "${it.first}:${it.second}" }
                .shouldBeEmpty()
        }

        test("the scanner is pinned: exactly the three login routes call createSession") {
            callSites().map { it.first }.toSet() shouldBe
                setOf(
                    "network/lapis/cloud/server/routes/AuthRoutes.kt",
                    "network/lapis/cloud/server/routes/KeycloakAuthRoutes.kt",
                    "network/lapis/cloud/server/routes/OidcRoutes.kt",
                )
        }
    })
