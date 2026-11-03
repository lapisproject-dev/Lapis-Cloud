package network.lapis.cloud.server.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/** V1.9.266 -- source-scan tripwires: one environment gateway, a complete secret list, preflight first in every main(). */
class SecretEnvTripwireTest :
    FunSpec({
        val gateway = "network/lapis/cloud/server/config/SecretEnv.kt"

        fun rel(f: File) = f.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        val files = SourceScan.mainFiles()

        test("System.getenv appears in main only in SecretEnv.kt") {
            val pattern = Regex("""\bgetenv\b""")
            val offenders = files.filter { pattern.containsMatchIn(SourceScan.blank(it.readText())) }.map(::rel)
            offenders shouldBe listOf(gateway)
        }

        test("every secret-looking LAPIS_ variable is in SECRET_NAMES or consciously allowed") {
            val allowedNonSecrets = setOf("LAPIS_KEYCLOAK_ALLOW_PRIVATE_ISSUER_HOST")
            val looksSecret = Regex("""PASSWORD|PASSPHRASE|SECRET|API_?KEY|_KEY$|TOKEN$|CREDENTIAL|PRIVATE|_PIN$""")
            val literal = Regex(""""(LAPIS_[A-Z0-9_]+)"""")
            val found =
                files
                    .filter { rel(it) != gateway }
                    .flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }
                    .toSet()
            val missing = found.filter { looksSecret.containsMatchIn(it) && it !in SecretEnv.SECRET_NAMES && it !in allowedNonSecrets }
            missing shouldBe emptyList()
        }

        test("every SECRET_NAMES entry is still used outside SecretEnv.kt") {
            val text = files.filter { rel(it) != gateway }.joinToString("\n") { it.readText() }
            SecretEnv.SECRET_NAMES.filter { !text.contains("\"$it\"") } shouldBe emptyList()
        }

        test("_FILE literals and SecretFileException exist only in SecretEnv.kt") {
            val fileLiteral = Regex("""_FILE"""")
            val offenders =
                files
                    .filter { rel(it) != gateway }
                    .filter { f ->
                        val code = f.readText()
                        fileLiteral.containsMatchIn(code) || SourceScan.blank(code).contains("SecretFileException")
                    }.map(::rel)
            offenders shouldBe emptyList()
        }

        test("preflight is the first statement of every main()") {
            listOf(
                "network/lapis/cloud/server/Application.kt",
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt",
                "network/lapis/cloud/server/bootstrap/FlywayRepair.kt",
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt",
            ).forEach {
                val text = SourceScan.blank(File(SourceScan.mainRoot(), it).readText())
                val body = text.substring(text.indexOf("\nfun main() {") + "\nfun main() {".length).trimStart()
                body.startsWith("SecretEnv.preflight()") shouldBe true
            }
            val mains = files.filter { SourceScan.blank(it.readText()).contains(Regex("""^fun main\(""", RegexOption.MULTILINE)) }
            mains.map(::rel).sorted() shouldBe
                listOf(
                    "network/lapis/cloud/server/Application.kt",
                    "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt",
                    "network/lapis/cloud/server/bootstrap/FlywayRepair.kt",
                    "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt",
                )
        }
    })
