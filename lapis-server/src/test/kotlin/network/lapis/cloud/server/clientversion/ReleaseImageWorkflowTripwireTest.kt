package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * V1.9.93 "Published container image instead of server-side builds": text-level tripwires over the release
 * workflow, the CI workflow, the Dockerfile, `.dockerignore` and the example compose files. They pin the
 * supply-chain decisions of the wave (tag-only trigger, the single write permission, SHA-pinned actions, no
 * `latest`, allowlist build context, digest-pinned base images) so that a later edit cannot loosen them silently.
 *
 * No YAML parser on purpose (no new test dependency); the helpers are line based and conservative. Every file is
 * read through [readRepoFile], which throws when the file is missing, so a moved file is a failure and never an
 * empty pass. Full-line comments are dropped before scanning so explanatory comments cannot trip a check.
 */
private val IMAGE_SCAN_ROOT =
    File("..").let { if (File(it, "deploy").exists()) it else File(".") }

private const val RELEASE_WORKFLOW = ".github/workflows/release-image.yml"
private const val CI_WORKFLOW = ".github/workflows/ci.yml"
private const val IMAGE_NAME = "ghcr.io/lapisproject-dev/lapis-cloud"

private fun readRepoFile(rel: String): String {
    val f = File(IMAGE_SCAN_ROOT, rel)
    check(f.isFile) { "$rel not found under ${IMAGE_SCAN_ROOT.absolutePath}" }
    return f.readText()
}

private fun withoutComments(text: String): String = text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

private fun readCode(rel: String): String = withoutComments(readRepoFile(rel))

/** Text of one top-level key's block: from the `key:` line to the next top-level (column 0) key. */
private fun topLevelBlock(
    workflow: String,
    key: String,
): String {
    val lines = workflow.lines()
    val start = lines.indexOfFirst { it.startsWith("$key:") }
    check(start >= 0) { "top-level key '$key' not found" }
    val end =
        (start + 1 until lines.size).firstOrNull { i ->
            lines[i].isNotEmpty() && !lines[i][0].isWhitespace()
        } ?: lines.size
    return lines.subList(start, end).joinToString("\n")
}

private fun onBlock(workflow: String): String = topLevelBlock(workflow = workflow, key = "on")

/** Job key to the text of its block (two-space-indented keys under `jobs:`). */
private fun jobBlocks(workflow: String): Map<String, String> {
    val lines = topLevelBlock(workflow = workflow, key = "jobs").lines().drop(1)
    val keyRegex = Regex("^ {2}([A-Za-z0-9_-]+):\\s*$")
    val result = linkedMapOf<String, String>()
    var current: String? = null
    val buffer = mutableListOf<String>()

    fun flush() {
        current?.let { result[it] = buffer.joinToString("\n") }
        buffer.clear()
    }
    for (line in lines) {
        val m = keyRegex.find(line)
        if (m != null) {
            flush()
            current = m.groupValues[1]
        }
        buffer.add(line)
    }
    flush()
    return result
}

private fun serviceBlock(
    compose: String,
    service: String,
): String {
    val lines = compose.lines()
    val start = lines.indexOfFirst { it == "  $service:" }
    check(start >= 0) { "service '$service' not found" }
    val end =
        (start + 1 until lines.size).firstOrNull { i ->
            Regex("^ {0,2}[A-Za-z0-9_-]+:\\s*$").containsMatchIn(lines[i]) && !lines[i].startsWith("   ")
        } ?: lines.size
    return lines.subList(start, end).joinToString("\n")
}

class ReleaseImageWorkflowTripwireTest :
    FunSpec({
        val release = readCode(RELEASE_WORKFLOW)
        val ci = readCode(CI_WORKFLOW)

        test("release workflow triggers on version tag pushes only") {
            val on = onBlock(release)
            on shouldContain "push:"
            on shouldContain "tags:"
            on shouldContain "v*.*.*"
            listOf(
                "branches",
                "pull_request",
                "pull_request_target",
                "workflow_dispatch",
                "schedule",
                "workflow_run",
                "release:",
                "repository_dispatch",
                "workflow_call",
            ).forEach { forbidden -> on shouldNotContain forbidden }
        }

        test("the registry push exists exactly once, in the image job, behind the tag guard") {
            Regex("push:\\s*true").findAll(release).count() shouldBe 1
            val jobs = jobBlocks(release)
            jobs.keys shouldContainAll listOf("ci", "image")
            val image = jobs.getValue("image")
            image shouldContain "push: true"
            image shouldContain "if: startsWith(github.ref, 'refs/tags/v')"
            image shouldContain "needs: ci"
            release shouldNotContain "docker push"
        }

        test("permissions: read-only by default, packages write only in the image job") {
            val top = topLevelBlock(workflow = release, key = "permissions").lines().map { it.trim() }.filter { it.isNotEmpty() }
            top shouldBe listOf("permissions:", "contents: read")
            Regex("packages:\\s*write").findAll(release).count() shouldBe 1
            val jobs = jobBlocks(release)
            jobs.getValue("image") shouldContain "packages: write"
            jobs.getValue("ci") shouldNotContain "write"
            Regex(":\\s*write\\b").findAll(release).count() shouldBe 1
            release shouldNotContain "write-all"
            listOf("id-token", "attestations").forEach { release shouldNotContain it }
        }

        test("the only secret in either workflow is GITHUB_TOKEN, and nothing is inherited") {
            for ((name, text) in listOf(RELEASE_WORKFLOW to release, CI_WORKFLOW to ci)) {
                val refs = Regex("secrets\\.[A-Za-z_]+").findAll(text).map { it.value }.toList()
                refs.forEach { ref -> (name to ref) shouldBe (name to "secrets.GITHUB_TOKEN") }
                text shouldNotContain "secrets: inherit"
            }
        }

        test("every action is a local workflow or pinned to a 40-hex commit SHA with a version comment") {
            val pinned = Regex("@[0-9a-f]{40}\\s+#\\s*v\\d")
            var found = 0
            for ((name, text) in listOf(RELEASE_WORKFLOW to release, CI_WORKFLOW to ci)) {
                text.lines().filter { Regex("^\\s*(-\\s+)?uses:").containsMatchIn(it) }.forEach { line ->
                    val target = line.substringAfter("uses:").trim()
                    if (!target.startsWith("./.github/workflows/")) {
                        (name to pinned.containsMatchIn(line)) shouldBe (name to true)
                        found++
                    }
                }
            }
            found shouldBeGreaterThan 8
        }

        test("no latest tag, the right image name, amd64 only") {
            release shouldNotContain ":latest"
            release shouldNotContain "latest=true"
            release shouldNotContain "value=latest"
            release shouldContain IMAGE_NAME
            release shouldContain "linux/amd64"
        }

        test("ci.yml stays reusable and keeps its triggers and read-only token") {
            ci shouldContain "workflow_call"
            val on = onBlock(ci)
            on shouldContain "branches: [ master ]"
            Regex("branches:\\s*\\[ master ]").findAll(on).count() shouldBe 2
            ci shouldNotContain "packages:"
            topLevelBlock(workflow = ci, key = "permissions") shouldContain "contents: read"
        }

        test("image content check scans as root and runs on pull requests") {
            val script = readCode("scripts/ci-assert-image-clean.sh")
            script shouldContain "--user 0:0"
            readRepoFile("scripts/test-ci-assert-image-clean.sh") shouldContain "--user 0:0"
            val imageJob = jobBlocks(ci).getValue("image")
            imageJob shouldContain "github.event_name == 'pull_request'"
            imageJob shouldContain "scripts/ci-assert-image-clean.sh"
            imageJob shouldContain "push: false"
        }

        test(".dockerignore is an allowlist with exactly the expected re-includes") {
            val lines =
                readRepoFile(".dockerignore")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            lines.first() shouldBe "*"
            val allowed = lines.filter { it.startsWith("!") }.toSet()
            allowed shouldBe
                setOf(
                    "!gradlew",
                    "!gradlew.bat",
                    "!build.gradle.kts",
                    "!settings.gradle.kts",
                    "!gradle.properties",
                    "!gradle/",
                    "!lapis-shared/",
                    "!lapis-server/",
                    "!lapis-client/",
                    "!lapis-detekt-rules/",
                    "!docs-render/",
                )
            lines shouldContainAll
                listOf(
                    "**/build",
                    "**/.gradle",
                    "**/.kotlin",
                    "**/node_modules",
                    "**/.env",
                    "**/.env*",
                    "**/*.env",
                    "**/*.pem",
                    "**/*.key",
                    "**/*.p12",
                    "**/*.pfx",
                    "**/*.jks",
                    "**/*.keystore",
                    "**/*.dump",
                    "**/*.dump.gz",
                    "**/*.sql.gz",
                    "**/backups/",
                    "**/*.pmtiles",
                    "**/*.hprof",
                    "**/hs_err_pid*.log",
                    "**/.DS_Store",
                    "**/.idea",
                    "**/.claude",
                )
            // Needed by the build: a hidden lockfile directory and a tracked resource must never be excluded.
            lines.none { it.contains(".kotlin-js-store") || it.contains("geodata") } shouldBe true
            lines.filter { it.contains("backup") } shouldBe listOf("**/backups/")
        }

        test(
            "V1.9.97: the recorded bells reach the image -- no .dockerignore exclusion matches them, the bundle is copied whole, nothing removes them",
        ) {
            val soundFiles =
                listOf("call-bell.mp3", "blessing-bell.mp3").map { "lapis-client/src/jsMain/webAssets/encounter-sounds-v1/$it" }
            val exclusions =
                readRepoFile(".dockerignore")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && it != "*" }
                    .map { it.trimEnd('/') }
            soundFiles.forEach { path ->
                val parts = path.split('/')
                // the file itself and every parent directory (an excluded directory excludes its content)
                (1..parts.size).map { parts.take(it).joinToString("/") }.forEach { candidate ->
                    exclusions.forEach { pattern ->
                        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                        withClue("the pattern '$pattern' must not exclude '$candidate'") {
                            matcher.matches(Paths.get(candidate)) shouldBe false
                        }
                    }
                }
                File(IMAGE_SCAN_ROOT, path).isFile shouldBe true
            }
            val dockerfile = readCode("Dockerfile")
            dockerfile shouldContain
                "COPY --from=build /workspace/lapis-client/build/kotlin-webpack/js/productionExecutable ./client"
            dockerfile
                .lines()
                .filter { it.trim().startsWith("RUN rm") }
                .forEach { line ->
                    line shouldNotContain "assets"
                    line shouldNotContain ".mp3"
                }
        }

        test("Dockerfile: digest-pinned bases, no blanket COPY, no ADD, no secret-looking ARG/ENV") {
            val dockerfile = readCode("Dockerfile")
            val lines = dockerfile.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val froms = lines.filter { it.startsWith("FROM ") }
            froms.size shouldBe 2
            froms.forEach { Regex("@sha256:[0-9a-f]{64}").containsMatchIn(it) shouldBe true }
            lines.none { it.startsWith("ADD ") } shouldBe true
            lines.filter { it.startsWith("COPY ") && !it.startsWith("COPY --from") }.forEach { copy ->
                val tokens = copy.removePrefix("COPY ").trim().split(Regex("\\s+"))
                tokens.dropLast(1).forEach { src ->
                    src shouldNotBe "."
                    listOf("deploy", ".env", ".git").forEach { bad -> src.startsWith(bad) shouldBe false }
                }
            }
            val secretLike = Regex("(?i)secret|token|password|passwd|_key\\b")
            lines.filter { it.startsWith("ARG ") || it.startsWith("ENV ") }.forEach { secretLike.containsMatchIn(it) shouldBe false }
        }

        test("both image builds pass the same Node heap build argument, so the push build reuses every cached layer") {
            val workflow = readCode(".github/workflows/release-image.yml")
            Regex("NODE_MAX_OLD_SPACE_MB=8192").findAll(workflow).count() shouldBe 2
            val dockerfile = readCode("Dockerfile")
            dockerfile shouldContain "ARG NODE_MAX_OLD_SPACE_MB="
            dockerfile shouldContain "--max-old-space-size=\${NODE_MAX_OLD_SPACE_MB}"
        }

        test("example compose pulls the published image; the build override has its own local image name") {
            val compose = readCode("deploy/example/docker-compose.yml")
            val server = serviceBlock(compose = compose, service = "lapis-server")
            server shouldContain "image: $IMAGE_NAME:\${LAPIS_IMAGE_TAG:?"
            Regex("^\\s+build:", RegexOption.MULTILINE).containsMatchIn(server) shouldBe false

            val override = readCode("deploy/example/docker-compose.build.yml")
            override shouldContain "build:"
            val imageLine = override.lines().first { it.trim().startsWith("image:") }.trim()
            imageLine.removePrefix("image:").trim().startsWith("ghcr.io/") shouldBe false

            readRepoFile("deploy/example/.env.example").lines() shouldContain "LAPIS_IMAGE_TAG="
        }
    })
