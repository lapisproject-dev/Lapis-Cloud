package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.io.File
import java.nio.file.Files

private fun tempRoot(): File = Files.createTempDirectory("lapis-client-build-id").toFile().also { it.deleteOnExit() }

class ClientBuildIdTest :
    FunSpec({
        test("no bundle in the dist root -> null, never a throw") {
            val root = tempRoot()
            ClientBuildId.compute(clientDistRoot = root) shouldBe null
        }

        test("nonexistent dist root -> null") {
            ClientBuildId.compute(clientDistRoot = File("/nonexistent-lapis-dist-root-${System.nanoTime()}")) shouldBe null
        }

        test("id has the fixed shape: 16 lower-case hex characters") {
            val root = tempRoot()
            File(root, ClientBuildId.BUNDLE_FILE_NAME).writeText("console.log('a');")
            ClientBuildId.compute(clientDistRoot = root)!! shouldMatch Regex("^[0-9a-f]{16}$")
        }

        test("same bytes -> identical id across calls (stable over a pure restart)") {
            val root = tempRoot()
            File(root, ClientBuildId.BUNDLE_FILE_NAME).writeText("console.log('same');")
            val first = ClientBuildId.compute(clientDistRoot = root)
            val second = ClientBuildId.compute(clientDistRoot = root)
            first shouldNotBe null
            first shouldBe second
        }

        test("changed bytes -> different id") {
            val root = tempRoot()
            val bundle = File(root, ClientBuildId.BUNDLE_FILE_NAME)
            bundle.writeText("console.log('one');")
            val before = ClientBuildId.compute(clientDistRoot = root)
            bundle.writeText("console.log('two');")
            val after = ClientBuildId.compute(clientDistRoot = root)
            before shouldNotBe after
        }

        test("a bundle larger than the read buffer is hashed over all of its bytes") {
            val root = tempRoot()
            val bundle = File(root, ClientBuildId.BUNDLE_FILE_NAME)
            val big = "x".repeat(50_000)
            bundle.writeText(big + "A")
            val a = ClientBuildId.compute(clientDistRoot = root)
            bundle.writeText(big + "B")
            val b = ClientBuildId.compute(clientDistRoot = root)
            a shouldNotBe b
        }

        test("the id does not depend on index.html") {
            val root = tempRoot()
            File(root, ClientBuildId.BUNDLE_FILE_NAME).writeText("bundle")
            val before = ClientBuildId.compute(clientDistRoot = root)
            File(root, "index.html").writeText("<html>changed</html>")
            ClientBuildId.compute(clientDistRoot = root) shouldBe before
        }

        test("isWellFormed accepts exactly 16 lower-case hex characters") {
            ClientBuildId.isWellFormed("0123456789abcdef") shouldBe true
            ClientBuildId.isWellFormed("0123456789ABCDEF") shouldBe false
            ClientBuildId.isWellFormed("0123456789abcde") shouldBe false
            ClientBuildId.isWellFormed("0123456789abcdef0") shouldBe false
            ClientBuildId.isWellFormed("") shouldBe false
            ClientBuildId.isWellFormed(null) shouldBe false
            ClientBuildId.isWellFormed("../etc/passwd") shouldBe false
            ClientBuildId.isWellFormed("0123456789abcde\n") shouldBe false
        }
    })
