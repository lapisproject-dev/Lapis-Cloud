package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.149 -- this repository is public; the topology (host names, addresses) lives in the private operations repository. The load-test
 * tool and its run sheet must therefore carry no host name and no IP address except loopback and the reserved example/test names.
 * Allowed: `127.0.0.1`, `localhost`, `*.test`, the Java package `network.lapis.cloud.*`, `example.org|com|net` (and subdomains), the RFC 5737 documentation ranges.
 * Not scanned: `node_modules`, `results`, the generated `package-lock.json`.
 */
class LoadTestNoHostLiteralTripwireTest :
    FunSpec({
        val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
        val hostName =
            Regex(
                """\b((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:com|org|net|de|dev|io|eu|cloud|app|ge|info|ai|co|me|tv))\b""",
                RegexOption.IGNORE_CASE,
            )

        fun allowedHost(host: String): Boolean {
            val h = host.lowercase()
            return h.endsWith(".test") ||
                h == "network.lapis.cloud" ||
                h.startsWith("network.lapis.cloud.") ||
                // Java package names of the exception classes, not hosts
                h == "example.org" ||
                h == "example.com" ||
                h == "example.net" ||
                h.endsWith(".example.org") ||
                h.endsWith(".example.com") ||
                h.endsWith(".example.net")
        }

        fun allowedIp(match: MatchResult): Boolean {
            val ip = match.value
            return ip == "127.0.0.1" ||
                ip.startsWith("192.0.2.") ||
                ip.startsWith("198.51.100.") ||
                ip.startsWith("203.0.113.") ||
                // version-like triples such as 1.9.149 are not four octets; four-part versions do not occur here
                match.groupValues.drop(1).any { it.toInt() > 255 }
        }

        fun scanned(): List<File> {
            val root = LoadTestFiles.root("scripts/loadtest")
            val tool =
                root
                    .walkTopDown()
                    .onEnter { it.name != "node_modules" && it.name != "results" }
                    .filter { it.isFile && it.name != "package-lock.json" }
                    .toList()
            return tool + LoadTestFiles.root("docs/architecture/encounter-load-test.adoc")
        }

        test("the tool and the run sheet contain no host name and no IP address except loopback and reserved example names") {
            val files = scanned()
            (files.size > 20) shouldBe true
            val offenders = mutableListOf<String>()
            for (file in files) {
                file.readLines().forEachIndexed { index, line ->
                    hostName.findAll(line).filterNot { allowedHost(it.groupValues[1]) }.forEach {
                        offenders += "${file.name}:${index + 1}: ${it.value}"
                    }
                    ipv4.findAll(line).filterNot { allowedIp(it) }.forEach { offenders += "${file.name}:${index + 1}: ${it.value}" }
                }
            }
            offenders shouldBe emptyList()
        }
    })
