package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.142 "Kamera-Filter" -- the privacy tripwires of the client side, as source scans (the jsTest tests prove the behaviour; Karma has no
 * file system). The promise of the feature is that the camera picture, the detected face and its keypoints stay in the browser tab:
 *
 *  1. **No network, no foreign host.** The filter files contain no URL with a scheme and no `fetch`, `XMLHttpRequest`, `sendBeacon`,
 *     `WebSocket` or `EventSource`. The face detector's WASM and model are loaded from this server's own paths only.
 *  2. **Nothing is kept.** No image or face data is written anywhere: no `toDataURL`, `toBlob`, `convertToBlob`, IndexedDB, object URL,
 *     Cache API, `sessionStorage`. `localStorage` is touched in exactly one file (the controller, five slider values) and only inside `try`.
 *  3. **No logging of values.** The filter files do not log at all -- no `console`, no kotlin-logging, no `println` -- so a box, a keypoint or
 *     a slider value can never reach a log.
 *  4. **One place decides where the detector's files come from.**
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val FILTER_FILES =
    listOf(
        "ConferenceCameraFilters.kt",
        "CameraFilterMath.kt",
        "ConferenceCameraFilterController.kt",
        "ConferenceCameraFilterStage.kt",
        "ConferenceCameraFilterSection.kt",
        "livekit/FaceDetectorJs.kt",
    )

/** Source without comments, strings kept (the strings are what is under test). */
private fun withoutComments(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    var inString = false
    var inTriple = false
    while (i < src.length) {
        when {
            inTriple -> {
                if (src.startsWith("\"\"\"", i)) {
                    inTriple = false
                    out.append("\"\"\"")
                    i += 3
                } else {
                    out.append(src[i++])
                }
            }
            inString -> {
                if (src[i] == '\\' && i + 1 < src.length) {
                    out.append(src[i]).append(src[i + 1])
                    i += 2
                } else {
                    if (src[i] == '"') inString = false
                    out.append(src[i++])
                }
            }
            src.startsWith("\"\"\"", i) -> {
                inTriple = true
                out.append("\"\"\"")
                i += 3
            }
            src[i] == '"' -> {
                inString = true
                out.append(src[i++])
            }
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
            src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                repeat(src.substring(i, end).count { it == '\n' }) { out.append('\n') }
                i = end
            }
            else -> out.append(src[i++])
        }
    }
    return out.toString()
}

private fun code(name: String): String = withoutComments(File(CLIENT_DIR, name).readText())

class CameraFilterClientTripwireTest :
    FunSpec({
        test("the scanner really sees the files (guards against a silently empty scan)") {
            FILTER_FILES.forEach { name -> withClue(name) { File(CLIENT_DIR, name).isFile shouldBe true } }
            (code("ConferenceCameraFilterStage.kt").length > 3000) shouldBe true
            (code("ConferenceCameraFilters.kt").length > 3000) shouldBe true
        }

        test("the filter files contain no URL with a scheme and make no network call") {
            val forbidden =
                listOf(
                    Regex("""https?://"""),
                    Regex("""wss?://"""),
                    Regex("""\.fetch\(|\bfetch\("""),
                    Regex("""XMLHttpRequest"""),
                    Regex("""sendBeacon"""),
                    Regex("""\bWebSocket\b"""),
                    Regex("""\bEventSource\b"""),
                    Regex("""RequestCredentials"""),
                )
            val offenders =
                FILTER_FILES.flatMap { name ->
                    val src = code(name)
                    forbidden.filter { it.containsMatchIn(src) }.map { "$name: ${it.pattern}" }
                }
            offenders.shouldBeEmpty()
        }

        test("no image or face data is stored, exported or turned into a URL") {
            val forbidden =
                listOf(
                    Regex("""toDataURL"""),
                    Regex("""\btoBlob\b|convertToBlob"""),
                    Regex("""indexedDB|IndexedDB"""),
                    Regex("""createObjectURL"""),
                    Regex("""\bcaches\b"""),
                    Regex("""sessionStorage"""),
                    Regex("""FileSystem"""),
                    Regex("""\bBlob\("""),
                    Regex("""getImageData"""),
                    Regex("""readPixels"""),
                )
            val offenders =
                FILTER_FILES.flatMap { name ->
                    val src = code(name)
                    forbidden.filter { it.containsMatchIn(src) }.map { "$name: ${it.pattern}" }
                }
            offenders.shouldBeEmpty()
        }

        test("localStorage is touched only by the controller, and every access sits inside a try") {
            for (name in FILTER_FILES.filter { it != "ConferenceCameraFilterController.kt" }) {
                withClue(name) { code(name) shouldNotContain "localStorage" }
            }
            val lines = code("ConferenceCameraFilterController.kt").lines()
            val accesses = lines.withIndex().filter { (_, line) -> Regex("""localStorage\[""").containsMatchIn(line) }
            (accesses.size >= 2) shouldBe true
            for ((index, line) in accesses) {
                val before = lines.subList(maxOf(0, index - 2), index + 1).joinToString("\n")
                withClue("line ${index + 1}: $line") { before shouldContain "try {" }
            }
            code("ConferenceCameraFilterController.kt") shouldContain "CAMERA_FILTER_STORAGE_KEY"
        }

        test("the filter files never log (a box, a keypoint or a slider value can not reach a log)") {
            val logging = Regex("""console\.|KotlinLogging|\blogger\b|println\(|\bprint\(""")
            val offenders = FILTER_FILES.filter { logging.containsMatchIn(code(it)) }
            offenders.shouldBeEmpty()
        }

        test("the face detector's files come from this server's own paths, decided in one place") {
            val stage = code("ConferenceCameraFilterStage.kt")
            stage shouldContain "const val FACE_MODEL_PATH = \"/assets/mediapipe/blaze_face_short_range.tflite\""
            stage shouldContain "FilesetResolver.forVisionTasks(ConferenceBackgroundAssets.TASKS_VISION_FILE_SET)"
            stage shouldContain "modelAssetPath = CameraFilterAssets.FACE_MODEL_PATH"
            // the externals have no overload without a path, and nobody else creates a detector
            val externals = code("livekit/FaceDetectorJs.kt")
            externals shouldContain "fun forVisionTasks(basePath: String)"
            val creators =
                CLIENT_DIR
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name != "FaceDetectorJs.kt" }
                    .filter { Regex("""FaceDetector\.createFromOptions""").containsMatchIn(withoutComments(it.readText())) }
                    .map { it.name }
                    .toList()
            creators shouldBe listOf("ConferenceCameraFilterStage.kt")
        }

        test("the panel is built only for a member session, and the decision is made in one pure function") {
            val screen = File(CLIENT_DIR, "ConferenceScreen.kt").readText()
            screen shouldContain "offered = cameraFilterOfferedFor(AppState.session)"
            code("ConferenceCameraFilterSection.kt") shouldContain "if (offered) {"
            code("ConferenceCameraFilters.kt") shouldContain "session != null && !isConferenceNonMember(session)"
        }

        test("the frame stage never touches the camera track itself (one processor per track, owned by the background controller)") {
            val offenders =
                FILTER_FILES.filter {
                    Regex("""\.setProcessor\(|\.stopProcessor\(|\.getProcessor\(""").containsMatchIn(code(it))
                }
            offenders.shouldBeEmpty()
        }
    })
