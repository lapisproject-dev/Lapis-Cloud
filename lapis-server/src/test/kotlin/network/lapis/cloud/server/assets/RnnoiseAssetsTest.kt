package network.lapis.cloud.server.assets

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.routes.RNNOISE_ASSET_DIR
import java.io.File
import java.security.MessageDigest

/**
 * V1.9.114 -- guard for the RNNoise files (worklet, two WebAssembly builds, licence notices) under `lapis-client/src/jsMain/webAssets/rnnoise-v1`.
 * They are served `immutable` for a year, so their bytes are pinned (size and SHA-256, also checked by the Gradle build on the staging and the
 * webpack copy), their provenance and licences are documented, the three places that name the directory are kept together, and the client
 * code that loads them is scanned for anything that would reach a third party. Lives in `lapis-server` because `jsTest` has no file system
 * (same reason and two-path fallback as `EncounterSoundAssetsTest`).
 */
class RnnoiseAssetsTest :
    FunSpec({
        val clientRoot: File = File("../lapis-client").let { if (it.exists()) it else File("lapis-client") }
        val dir = File(clientRoot, "src/jsMain/webAssets/rnnoise-v1")
        val provenance = File(dir, "PROVENANCE.adoc")
        val clientSources = File(clientRoot, "src/jsMain/kotlin/network/lapis/cloud/client")

        val expected =
            mapOf(
                "rnnoise-worklet.js" to Pair(64_483L, "1e06facbb3d7459b89769aa4247cd4b71263e6662e4da0c54763f3bf06f5893b"),
                "rnnoise.wasm" to Pair(152_656L, "8b60a2ab88fdae2d1a9f940249d0eb072f28ba8e796f7304347b4e07839c8853"),
                "rnnoise_simd.wasm" to Pair(157_234L, "378fd17c294db15ee4e818ba5ef072242a21c8ad7445b483f7f46d7dc4f2c253"),
                "LICENSES.txt" to Pair(13_551L, "042d729a8892f4cdb4d3984a8c1f2612ea5d5771a00372a6010bd2d6fcb6e9b2"),
            )
        val versionRule =
            "These files are served immutable for a year: if the bytes change, rename the directory to rnnoise-v2 " +
                "(NoiseSuppressionAssets.DIR, ClientAssetRoutes.kt, build.gradle.kts) and update this test."

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        fun code(file: File): List<String> =
            file.readLines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

        test("all four files exist with the documented size and SHA-256") {
            expected.forEach { (name, pinned) ->
                val file = File(dir, name)
                file.isFile shouldBe true
                withClue("$name size. $versionRule") { file.length() shouldBe pinned.first }
                withClue("$name SHA-256. $versionRule") { sha256(file) shouldBe pinned.second }
            }
        }

        test("the WebAssembly files carry the WebAssembly magic number and both builds exist (SIMD or not is decided in the browser)") {
            listOf("rnnoise.wasm", "rnnoise_simd.wasm").forEach { name ->
                val bytes = File(dir, name).readBytes()
                bytes.take(4) shouldBe listOf<Byte>(0x00, 0x61, 0x73, 0x6d)
                (bytes.size < 1_048_576) shouldBe true
            }
            (File(dir, "rnnoise.wasm").length() != File(dir, "rnnoise_simd.wasm").length()) shouldBe true
        }

        test("the worklet registers the processor under the name the client asks for and is not a source map") {
            val text = File(dir, "rnnoise-worklet.js").readText()
            text shouldContain "registerProcessor(`@sapphi-red/web-noise-suppressor/rnnoise`"
            val client = File(clientSources, "ConferenceNoiseSuppression.kt").readText()
            client shouldContain "PROCESSOR_NAME = \"@sapphi-red/web-noise-suppressor/rnnoise\""
        }

        test(
            "the directory holds exactly the four files and the provenance, and NO source map (the image check forbids .map in the client)",
        ) {
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .shouldContainExactlyInAnyOrder(
                    "rnnoise-worklet.js",
                    "rnnoise.wasm",
                    "rnnoise_simd.wasm",
                    "LICENSES.txt",
                    "PROVENANCE.adoc",
                )
        }

        test("PROVENANCE.adoc names every hash, the upstream chain and all three licences; LICENSES.txt holds the three licence texts") {
            val text = provenance.readText()
            expected.values.forEach { text shouldContain it.second }
            listOf(
                "@sapphi-red/web-noise-suppressor",
                "0.4.1",
                "https://github.com/xiph/rnnoise",
                "https://github.com/shiguredo/rnnoise-wasm",
                "BSD-3-Clause",
                "Apache-2.0",
                "MIT",
                "rnnoise-v2",
                "2026-10-12",
            ).forEach { text shouldContain it }
            val licences = File(dir, "LICENSES.txt").readText()
            listOf(
                "MIT License",
                "Xiph.Org Foundation",
                "Redistribution and use in source and binary forms",
                "Apache License",
                "Version 2.0, January 2004",
            ).forEach { licences shouldContain it }
        }

        test("the directory name stands in the three places and nowhere drifts: client constant, server route and Gradle") {
            RNNOISE_ASSET_DIR shouldBe "rnnoise-v1"
            dir.name shouldBe RNNOISE_ASSET_DIR
            val clientSource = File(clientSources, "ConferenceNoiseSuppression.kt").readText()
            clientSource shouldContain "const val DIR = \"$RNNOISE_ASSET_DIR\""
            clientSource shouldContain "WORKLET = \"/assets/\$DIR/rnnoise-worklet.js\""
            clientSource shouldContain "WASM = \"/assets/\$DIR/rnnoise.wasm\""
            clientSource shouldContain "WASM_SIMD = \"/assets/\$DIR/rnnoise_simd.wasm\""
            val gradle = File(clientRoot, "build.gradle.kts").readText()
            gradle shouldContain "into(\"$RNNOISE_ASSET_DIR\")"
            gradle shouldContain "src/jsMain/webAssets/rnnoise-v1"
            expected.forEach { (name, pinned) ->
                gradle shouldContain "\"$RNNOISE_ASSET_DIR/$name\""
                gradle shouldContain pinned.second
            }
            // the provenance file is not shipped
            gradle shouldContain "exclude(\"PROVENANCE.adoc\")"
        }

        test(
            "no third party: the filter's client code names no http(s) address and no CDN host, loads only the same-origin constants and never uses eval or a blob URL",
        ) {
            val files =
                listOf(
                    "ConferenceNoiseSuppression.kt",
                    "ConferenceNoiseSuppressionController.kt",
                    "ConferenceNoiseSuppressionSwitch.kt",
                    "livekit/RnnoiseTrackProcessor.kt",
                    "livekit/LocalAudioTrack.kt",
                )
            files.forEach { name ->
                val lines = code(File(clientSources, name))
                lines.isNotEmpty() shouldBe true
                val text = lines.joinToString("\n")
                withClue("$name must not name an absolute address") {
                    text shouldNotContain "http://"
                    text shouldNotContain "https://"
                }
                listOf(
                    "jsdelivr",
                    "unpkg",
                    "cdnjs",
                    "googleapis",
                    "gstatic",
                    "cloudflare",
                    "eval(",
                    "new Function",
                    "createObjectURL",
                    "blob:",
                ).forEach { forbidden ->
                    withClue("$name must not contain '$forbidden'") { text shouldNotContain forbidden }
                }
                withClue("$name must not log or send beacons") {
                    listOf("console.", "sendBeacon", "println", "KotlinLogging").forEach { text shouldNotContain it }
                }
            }
            // the only network calls of the filter are the two same-origin loads of the processor file; their URL arguments are the constants
            val processor = code(File(clientSources, "livekit/RnnoiseTrackProcessor.kt")).joinToString("\n")
            Regex("""\.fetch\(""").findAll(processor).count() shouldBe 1
            processor shouldContain ".fetch(url, init)"
            processor shouldContain "addModule(NoiseSuppressionAssets.WORKLET)"
            processor shouldContain "init.credentials = \"omit\""
            processor shouldContain "init.mode = \"same-origin\""
            processor shouldContain "init.redirect = \"error\""
            // no part of the controller or the switch makes a request
            listOf("ConferenceNoiseSuppressionController.kt", "ConferenceNoiseSuppressionSwitch.kt").forEach { name ->
                code(File(clientSources, name)).joinToString("\n") shouldNotContain "fetch"
            }
        }

        test(
            "echo cancellation and automatic gain are never touched by the filter code (only `noiseSuppression` is set on the raw track)",
        ) {
            val session = code(File(clientSources, "livekit/LiveKitRoomSession.kt")).joinToString("\n")
            val start = session.indexOf("override suspend fun setBrowserNoiseSuppression")
            (start >= 0) shouldBe true
            val body = session.substring(start, session.indexOf("\n    }\n", start))
            body shouldContain "constraints.noiseSuppression = enabled"
            body shouldNotContain "echoCancellation"
            body shouldNotContain "autoGainControl"
            // the room-wide web audio mix stays off (it would route every remote voice through WebAudio)
            session shouldNotContain "webAudioMix"
        }
    })
