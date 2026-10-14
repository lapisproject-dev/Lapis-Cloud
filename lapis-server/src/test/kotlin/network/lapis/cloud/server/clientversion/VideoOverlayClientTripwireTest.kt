package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.129 "Video-Overlays" -- tripwires of the client side, as source scans (the jsTest tests prove the behaviour; Karma has no file system):
 *
 *  1. **No canvas taint, no URL injection.** The drawing path (settings, compositor, controller, panel, browser globals) never touches an
 *     `<img>`, `Image(...)`, `crossOrigin`, an object URL or a `blob:` URL: an image reaches the canvas only as an `ImageBitmap` that was
 *     decoded from a same-origin `fetch` BLOB -- a tainted canvas would make `new VideoFrame(canvas)` throw a `SecurityError`. The one place
 *     an overlay URL is made is `videoOverlayAssetPath`; the string `/api/video-overlays` appears in no other file of the drawing path.
 *     Every `fetch(` of the compositor carries `credentials = RequestCredentials.SAME_ORIGIN`.
 *  2. **Never a mirror.** No client source or stylesheet mirrors a video (`scaleX(-1)`, `scale(-1`): what the person sees is what others see.
 *  3. **One processor per track.** Only `ConferenceBackgroundController` calls `setProcessor`/`stopProcessor` on a camera track.
 *  4. **No identifiers in logs.** The overlay files log nothing but fixed sentences and exception class names.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

/** The drawing path: everything that handles an overlay image or its URL. (The admin library screen is not part of it.) */
private val DRAWING_PATH =
    listOf(
        "ConferenceVideoOverlays.kt",
        "ConferenceVideoOverlayCompositor.kt",
        "ConferenceVideoOverlayController.kt",
        "ConferenceVideoOverlaySection.kt",
        "VideoCanvasExternals.kt",
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

class VideoOverlayClientTripwireTest :
    FunSpec({
        test("the scanner really sees the files (guards against a silently empty scan)") {
            DRAWING_PATH.forEach { name -> withClue(name) { File(CLIENT_DIR, name).isFile shouldBe true } }
            (code("ConferenceVideoOverlayCompositor.kt").length > 1000) shouldBe true
            THEME_CSS.isFile shouldBe true
        }

        test("the drawing path never uses an img element, crossOrigin, an object URL or a blob URL (a tainted canvas breaks VideoFrame)") {
            val forbidden =
                listOf(
                    // `Image(...)` as a DOM constructor; the data classes `SceneCorner.Image` / `OverlayDrawOp.Image` are declarations or qualified.
                    Regex("""(?<!class )(?<![\w.])Image\("""),
                    Regex("""HTMLImageElement"""),
                    Regex("""\bcrossOrigin\b"""),
                    Regex("""createObjectURL"""),
                    Regex(""""blob:"""),
                    Regex("""\.src\s*="""),
                    Regex("""\.image\(|io\.kvision\.html\.image"""),
                    Regex("""\bimg\b"""),
                    Regex(""""https?:"""),
                )
            val offenders =
                DRAWING_PATH.flatMap { name ->
                    val src = code(name)
                    forbidden.filter { it.containsMatchIn(src) }.map { "$name: ${it.pattern}" }
                }
            offenders.shouldBeEmpty()
        }

        test("an overlay URL is made in exactly one place: the path regex of ConferenceVideoOverlays.kt") {
            for (name in DRAWING_PATH) {
                val src = code(name)
                val count = Regex("""/api/video-overlays""").findAll(src).count()
                withClue(name) { count shouldBe if (name == "ConferenceVideoOverlays.kt") 1 else 0 }
            }
            val overlays = code("ConferenceVideoOverlays.kt")
            overlays shouldContain "fun videoOverlayAssetPath("
            overlays shouldContain "/api/branding/logo"
            // the compositor loads only what videoOverlayAssetPath returned
            code("ConferenceVideoOverlayCompositor.kt") shouldContain "videoOverlayAssetPath(item)"
        }

        test("every fetch of the compositor is same-origin and authenticated, and the answer goes through a blob into an ImageBitmap") {
            val src = code("ConferenceVideoOverlayCompositor.kt")
            val fetches = Regex("""\.fetch\(""").findAll(src).toList()
            fetches.size shouldBe 1
            val after = src.substring(fetches.single().range.last, minOf(src.length, fetches.single().range.last + 200))
            after shouldContain "credentials = RequestCredentials.SAME_ORIGIN"
            src shouldContain "response.blob()"
            src shouldContain "createImageBitmapOf("
            // the content type and the status are checked before the bytes are used
            src shouldContain "check(response.ok)"
            src shouldContain "ALLOWED_IMAGE_TYPES"
            // no overlay file anywhere else fetches an image
            for (name in DRAWING_PATH.filter { it != "ConferenceVideoOverlayCompositor.kt" }) {
                withClue(name) { code(name) shouldNotContain ".fetch(" }
            }
        }

        test("nothing in the client mirrors a video") {
            val mirror = Regex("""scaleX\(\s*-\s*1|scale\(\s*-\s*1|rotateY\(\s*180""")
            val kotlin =
                CLIENT_DIR
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { mirror.containsMatchIn(withoutComments(it.readText())) }
                    .map { it.name }
                    .toList()
            kotlin.shouldBeEmpty()
            val css = THEME_CSS.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "")
            mirror.containsMatchIn(css) shouldBe false
        }

        test("only the background controller sets or stops a processor on the camera track") {
            val offenders =
                CLIENT_DIR
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name != "ConferenceBackgroundController.kt" }
                    .filter { file ->
                        val src = withoutComments(file.readText())
                        Regex("""\.setProcessor\(|\.stopProcessor\(""").containsMatchIn(src) &&
                            // the interface and the fake-free adapter live in the controller file; nobody else may call the track
                            !file.name.startsWith("LiveKit")
                    }.map { it.name }
                    .toList()
            offenders.shouldBeEmpty()
        }

        test("the overlay files log only fixed sentences and exception class names (no name, path, token or identity)") {
            for (name in DRAWING_PATH + "ConferenceVideoOverlayLibrarySettings.kt") {
                val offending =
                    withoutComments(File(CLIENT_DIR, name).readText())
                        .lines()
                        .filter { Regex("""console\.|overlayLog\(""").containsMatchIn(it) }
                        .filter { Regex("""\$\{?(?!e::class)[a-zA-Z]""").containsMatchIn(it) }
                withClue(name) { offending.shouldBeEmpty() }
            }
        }

        test("the panel is built only when the option is on, and the join token is the only source of that decision") {
            val screen = File(CLIENT_DIR, "ConferenceScreen.kt").readText()
            screen shouldContain "offered = joinToken.videoOverlaysOffered"
            code("ConferenceVideoOverlaySection.kt") shouldContain "if (offered) {"
        }
    })
