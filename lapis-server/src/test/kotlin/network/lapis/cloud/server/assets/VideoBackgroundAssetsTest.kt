package network.lapis.cloud.server.assets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.security.MessageDigest

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- Waechter fuer die eingecheckten Fremd-/Eigen-Artefakte des
 * Clients (`lapis-client/src/jsMain/webAssets`). Liegt in `lapis-server`, weil `jsTest` unter
 * Karma/ChromeHeadless kein Dateisystem hat (gleiche Begruendung wie `LapisAttributionTest` und die
 * i18n-Katalog-Tests); Zwei-Pfad-Fallback wie dort.
 *
 * Das Segmentierungsmodell ist ein Fremd-Artefakt (Apache-2.0): Groesse und SHA-256 stehen in
 * `PROVENANCE.adoc`, dieser Test haelt Datei und Dokumentation zusammen.
 */
class VideoBackgroundAssetsTest :
    FunSpec({
        val webAssets: File =
            File("../lapis-client/src/jsMain/webAssets")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/webAssets") }

        val modelFile = File(webAssets, "mediapipe/selfie_segmenter.tflite")
        val provenance = File(webAssets, "mediapipe/PROVENANCE.adoc")
        val backgroundsDir = File(webAssets, "video-backgrounds")

        val expectedModelSize = 249_537L
        val expectedModelSha256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"

        // Muss genau der Whitelist ConferenceBackgroundEffect (BG_*) im Client entsprechen.
        val whitelistedImageIds =
            listOf("bg-warm-grey", "bg-cool-blue", "bg-sage", "bg-sandstone", "bg-midnight", "bg-studio")

        test("das Segmentierungsmodell existiert mit der dokumentierten Groesse und SHA-256") {
            modelFile.exists() shouldBe true
            modelFile.length() shouldBe expectedModelSize
            val digest = MessageDigest.getInstance("SHA-256").digest(modelFile.readBytes())
            digest.joinToString("") { "%02x".format(it) } shouldBe expectedModelSha256
        }

        test("alle sechs Hintergrundbilder existieren, sind kleiner als 150 KiB und echte WebP-Dateien") {
            whitelistedImageIds.forEach { id ->
                val file = File(backgroundsDir, "$id.webp")
                file.exists() shouldBe true
                (file.length() < 150 * 1024) shouldBe true
                val header = file.readBytes().copyOfRange(0, 12)
                String(header, 0, 4, Charsets.US_ASCII) shouldBe "RIFF"
                String(header, 8, 4, Charsets.US_ASCII) shouldBe "WEBP"
            }
        }

        test("die Menge der .webp-Dateien entspricht genau der Whitelist (kein Bild ohne Eintrag und umgekehrt)") {
            val onDisk =
                backgroundsDir
                    .listFiles { f -> f.isFile && f.name.endsWith(".webp") }
                    ?.map { it.name.removeSuffix(".webp") }
                    .orEmpty()
            onDisk shouldContainExactlyInAnyOrder whitelistedImageIds
        }

        test("PROVENANCE.adoc existiert und nennt die Pruefsumme und Groesse des Modells") {
            provenance.exists() shouldBe true
            val text = provenance.readText()
            text shouldContain expectedModelSha256
            text shouldContain "249 537"
            text shouldContain "Apache-2.0"
        }
    })
