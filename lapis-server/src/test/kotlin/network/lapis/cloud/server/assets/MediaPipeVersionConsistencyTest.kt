package network.lapis.cloud.server.assets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.routes.MEDIAPIPE_TASKS_VISION_VERSION
import java.io.File

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte, Audit-Befund M3 -- die MediaPipe-Version steht an DREI Stellen,
 * und nichts hielt sie bisher zusammen:
 *
 * 1. `lapis-client/build.gradle.kts` (`mediaPipeTasksVisionVersion`) -- bestimmt, unter welchem Pfad die
 *    WASM-Dateien ins Bundle gestaged werden.
 * 2. `ConferenceBackgroundEffects.kt` (`ConferenceBackgroundAssets.MEDIAPIPE_TASKS_VISION_VERSION`) --
 *    bestimmt, welchen Pfad der Browser anfragt.
 * 3. `ClientAssetRoutes.kt` ([MEDIAPIPE_TASKS_VISION_VERSION], Server) -- bestimmt, welcher Pfad das
 *    Jahres-`Cache-Control` bekommt.
 *
 * Alle drei Dateien fordern in ihrem Kommentar "gemeinsam anheben", aber `verifyMediaPipeVersion` (Gradle)
 * vergleicht nur Stelle 1 mit dem tatsaechlich INSTALLIERTEN npm-Paket. Wird nur der Gradle-Wert angehoben,
 * liegt die WASM unter einem neuen Pfad, waehrend der Client den alten anfragt: 404, Feature tot, ALLE Tests
 * gruen. Dieser Test schliesst die Luecke.
 *
 * Liegt in `lapis-server`, weil `jsTest` unter Karma/ChromeHeadless kein Dateisystem hat (gleiche Begruendung
 * wie [VideoBackgroundAssetsTest] und die i18n-Katalog-Tests); Zwei-Pfad-Fallback wie dort, weil Gradle
 * Server-Tests mit `lapis-server` als Arbeitsverzeichnis startet.
 */
class MediaPipeVersionConsistencyTest :
    FunSpec({
        fun clientFile(relative: String): File =
            File("../lapis-client/$relative").let { if (it.exists()) it else File("lapis-client/$relative") }

        val buildScript = clientFile("build.gradle.kts")
        val effectsSource = clientFile("src/jsMain/kotlin/network/lapis/cloud/client/ConferenceBackgroundEffects.kt")

        /** Erster Treffer der Gruppe 1 von [pattern] in [file]; schlaegt mit klarer Meldung fehl, wenn es keinen gibt. */
        fun extract(
            file: File,
            what: String,
            pattern: Regex,
        ): String {
            file.exists() shouldBe true
            val match = pattern.find(file.readText())
            check(match != null) {
                "$what: Muster ${pattern.pattern} nicht in ${file.path} gefunden -- wurde die Deklaration " +
                    "umbenannt oder umformatiert? Dann diesen Test mit anpassen (er ist der einzige Waechter " +
                    "ueber die drei Versions-Stellen)."
            }
            return match.groupValues[1]
        }

        val gradleVersion =
            extract(
                file = buildScript,
                what = "Gradle-Version",
                pattern = Regex("""val\s+mediaPipeTasksVisionVersion\s*=\s*"([^"]+)""""),
            )
        val clientVersion =
            extract(
                file = effectsSource,
                what = "Client-Version",
                pattern = Regex("""const\s+val\s+MEDIAPIPE_TASKS_VISION_VERSION\s*=\s*"([^"]+)""""),
            )
        val clientFileSet =
            extract(
                file = effectsSource,
                what = "Client-Fileset-Pfad",
                pattern = Regex("""const\s+val\s+TASKS_VISION_FILE_SET\s*=\s*"([^"]+)""""),
            )

        test("Gradle, Client und Server nennen genau dieselbe @mediapipe/tasks-vision-Version") {
            gradleVersion shouldBe MEDIAPIPE_TASKS_VISION_VERSION
            clientVersion shouldBe MEDIAPIPE_TASKS_VISION_VERSION
        }

        test("der vom Client angefragte WASM-Pfad liegt unter dem Praefix, das der Server versioniert ausliefert") {
            // Der Server registriert `/assets/mediapipe/tasks-vision-<version>`; der Client fragt darunter
            // `.../wasm` an. Stimmt das nicht, laeuft die 19-MB-WASM ueber die unversionierte Tages-Route
            // (oder gar den Catch-all ohne Cache-Control) -- bzw. gar nicht.
            //
            // Der Client-Literal ist ein Kotlin-String-Template; die Interpolation wird hier mit der oben
            // extrahierten Client-Version aufgeloest, damit sowohl die interpolierte als auch eine irgendwann
            // ausgeschriebene Form geprueft wird.
            val resolvedFileSet =
                clientFileSet
                    .replace("\${ConferenceBackgroundAssets.MEDIAPIPE_TASKS_VISION_VERSION}", clientVersion)
                    .replace("\${MEDIAPIPE_TASKS_VISION_VERSION}", clientVersion)
                    .replace("\$MEDIAPIPE_TASKS_VISION_VERSION", clientVersion)
            val serverPrefix = "/assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION"
            resolvedFileSet shouldBe "$serverPrefix/wasm"
        }

        test("die Version ist eine schlichte Semver-Form (kein Bereich, kein Praefix)") {
            Regex("""^\d+\.\d+\.\d+$""").matches(MEDIAPIPE_TASKS_VISION_VERSION) shouldBe true
        }

        test("der Kommentar-Hinweis auf die drei Stellen steht noch in allen drei Dateien") {
            // Billige Absicherung gegen "Konstante kopiert, Hinweis vergessen": wer eine vierte Stelle
            // anlegt, soll ueber diesen Test stolpern, nicht erst in Produktion.
            val routesRelative = "src/main/kotlin/network/lapis/cloud/server/routes/ClientAssetRoutes.kt"
            val routes =
                File(routesRelative).let { if (it.exists()) it else File("lapis-server/$routesRelative") }
            listOf(buildScript, effectsSource, routes).forEach { file ->
                file.exists() shouldBe true
                file.readText().contains("MEDIAPIPE_TASKS_VISION_VERSION") shouldBe true
            }
        }
    })
