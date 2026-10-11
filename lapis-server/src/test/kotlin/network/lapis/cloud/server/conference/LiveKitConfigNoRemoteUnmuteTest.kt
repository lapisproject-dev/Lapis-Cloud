package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.108 -- LiveKit has an `enable_remote_unmute` switch that lets a server API switch a microphone ON. Lapis never does (a moderator can
 * only ask), so no shipped LiveKit config may enable it. The operator-owned real configs live in the private ops repo; the ones in this
 * repo (local development and the public self-hosting template) are checked here.
 */
class LiveKitConfigNoRemoteUnmuteTest :
    FunSpec({
        fun repoFile(relative: String): File = File("../$relative").takeIf { it.exists() } ?: File(relative)

        val configs = listOf("deploy/local/livekit.yaml", "deploy/example/livekit.yaml.template")

        test("the LiveKit configs shipped in this repo exist (guards against a silently empty check)") {
            configs.forEach { repoFile(it).exists() shouldBe true }
        }

        test("none of them enables enable_remote_unmute") {
            val offenders =
                configs.filter { path ->
                    repoFile(path)
                        .readLines()
                        .map { it.substringBefore('#').trim() }
                        .any { Regex("""^enable_remote_unmute\s*:\s*(true|yes|on|1)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                }
            offenders.shouldBeEmpty()
        }
    })
