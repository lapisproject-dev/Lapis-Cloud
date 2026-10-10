package network.lapis.cloud.server.assets

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.routes.ENCOUNTER_SOUNDS_ASSET_DIR
import java.io.File
import java.security.MessageDigest

/**
 * V1.9.97 -- guard for the two recorded bells (call and blessing) under `lapis-client/src/jsMain/webAssets/encounter-sounds-v1`.
 * They are served `immutable` for a year, so their bytes are pinned (size and SHA-256, also checked by the Gradle build on the staging and the
 * webpack copy), their provenance is documented, and the three places that name the directory are kept together. Lives in `lapis-server`
 * because `jsTest` has no file system (same reason and two-path fallback as `VideoBackgroundAssetsTest`).
 */
class EncounterSoundAssetsTest :
    FunSpec({
        val clientRoot: File = File("../lapis-client").let { if (it.exists()) it else File("lapis-client") }
        val soundsDir = File(clientRoot, "src/jsMain/webAssets/encounter-sounds-v1")
        val provenance = File(soundsDir, "PROVENANCE.adoc")

        val expected =
            mapOf(
                "call-bell.mp3" to Pair(48_690L, "b41a4b3e8a6bc490c815a3398767bf2b9a39ec9048692587f174398430434405"),
                "blessing-bell.mp3" to Pair(48_713L, "7eebd9ed890b21ea230dfa9c3cec8ace23e728e77c1c8d8438abd22b4d91d267"),
            )
        val versionRule =
            "These files are served immutable for a year: if the bytes change, rename the directory to encounter-sounds-v2 " +
                "(client constant, ClientAssetRoutes.kt, build.gradle.kts) and update this test."

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        test("both recordings exist with the documented size and SHA-256") {
            expected.forEach { (name, pinned) ->
                val file = File(soundsDir, name)
                file.isFile shouldBe true
                withClue("$name size. $versionRule") { file.length() shouldBe pinned.first }
                withClue("$name SHA-256. $versionRule") { sha256(file) shouldBe pinned.second }
            }
        }

        test("both recordings are MP3 (an ID3 tag or an MPEG frame sync) and well below the client's size limit") {
            expected.keys.forEach { name ->
                val bytes = File(soundsDir, name).readBytes()
                val id3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
                val frameSync = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0
                (id3 || frameSync) shouldBe true
                (bytes.size < 262_144) shouldBe true
            }
        }

        test("the directory holds exactly the two recordings and the provenance, nothing else") {
            soundsDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .shouldContainExactlyInAnyOrder("call-bell.mp3", "blessing-bell.mp3", "PROVENANCE.adoc")
        }

        test("PROVENANCE.adoc names both hashes, both sources, both authors and the licence") {
            val text = provenance.readText()
            expected.values.forEach { text shouldContain it.second }
            listOf(
                "https://freesound.org/people/MJPtack/sounds/753412/",
                "https://freesound.org/people/HMTSCCSound/sounds/554655/",
                "MJPtack",
                "HMTSCCSound",
                "CC0",
                "encounter-sounds-v2",
            ).forEach { text shouldContain it }
            text shouldContain "Pixabay"
        }

        test("the directory name stands in the three places and nowhere drifts: client constant, server route and Gradle") {
            ENCOUNTER_SOUNDS_ASSET_DIR shouldBe "encounter-sounds-v1"
            soundsDir.name shouldBe ENCOUNTER_SOUNDS_ASSET_DIR
            val clientSource =
                File(clientRoot, "src/jsMain/kotlin/network/lapis/cloud/client/encounter/EncounterSoundFetch.kt").readText()
            clientSource shouldContain "ENCOUNTER_SOUNDS_DIR = \"/assets/$ENCOUNTER_SOUNDS_ASSET_DIR\""
            val gradle = File(clientRoot, "build.gradle.kts").readText()
            gradle shouldContain "into(\"$ENCOUNTER_SOUNDS_ASSET_DIR\")"
            expected.keys.forEach { name ->
                gradle shouldContain "\"$ENCOUNTER_SOUNDS_ASSET_DIR/$name\""
                gradle shouldContain expected.getValue(name).second
            }
            // the provenance file is not shipped
            gradle shouldContain "src/jsMain/webAssets/encounter-sounds-v1"
        }

        test("the route file does no logging (the access path is the only trace, as documented in dsgvo.adoc)") {
            val route = File("src/main/kotlin/network/lapis/cloud/server/routes/ClientAssetRoutes.kt")
            val fallback = File("lapis-server/src/main/kotlin/network/lapis/cloud/server/routes/ClientAssetRoutes.kt")
            val source = (if (route.exists()) route else fallback).readText()
            source shouldNotContain "logger"
            source shouldNotContain "KotlinLogging"
            source shouldNotContain "println"
        }
    })
