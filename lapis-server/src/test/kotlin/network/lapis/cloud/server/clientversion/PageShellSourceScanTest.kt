package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.189 tripwire for the one page shell. Every screen root is `pageShell(PageWidth.WIDE|FORM)` (or `dataScreenRoot()`); its width, margin and
 * padding live in `theme.css` (`.lapis-page`). An inline `maxWidth = <n>.px` or the old `mx-auto w-100 px-3` recipe on a root would silently beat
 * the stylesheet and bring back a page of its own width, so a client file may contain either only if it is on the allow-list below, with the reason.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private val INLINE_MAX_WIDTH = Regex("""\bmaxWidth\s*=\s*[\w.]+\.px""")
private val OLD_SHELL_RECIPE = Regex("""mx-auto w-100""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** file name -> why it keeps a width of its own (pre-login/deep-link cards, full-bleed stages, component widths). */
private val ALLOWED_OWN_WIDTH: Map<String, String> =
    mapOf(
        "LoginScreen.kt" to "pre-login card",
        "RegistrationScreen.kt" to "pre-login wide registration form (WIDE_FORM_MAX_WIDTH_PX)",
        "FriendRegistrationScreen.kt" to "pre-login wide registration form",
        "VerifyEmailDeepLinkScreen.kt" to "deep-link card",
        "PasswordResetDeepLinkScreen.kt" to "deep-link card",
        "EmailChangeDeepLinkScreens.kt" to "deep-link card",
        "PrivilegedActionVetoScreen.kt" to "deep-link card",
        "PaymentReturnScreen.kt" to "return card of the payment provider",
        "DonationCheckoutScreen.kt" to "public donation card",
        "EventGuestJoinScreen.kt" to "guest deep-link card",
        "EncounterSpaceScreen.kt" to "full-bleed stage of the encounter space",
        "EncounterServiceView.kt" to "full-bleed stage of the encounter space",
        "AgentOriginLine.kt" to "component width (a truncated label), not a page width",
        "CostCentersScreen.kt" to "component width (a description cell), not a page width",
        "SocialNetworkScreen.kt" to "component width (the boost amount field), not a page width",
    )

class PageShellSourceScanTest :
    FunSpec({
        test("no client file sets an inline page width or the old mx-auto recipe unless it is allow-listed") {
            val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            files.size shouldBeGreaterThan 100
            val offenders =
                files
                    .filter { it.name != "DataScreenLayout.kt" && it.name !in ALLOWED_OWN_WIDTH }
                    .flatMap { file ->
                        file
                            .readLines()
                            .withIndex()
                            .filter { (_, line) ->
                                !isCommentLine(line) &&
                                    (INLINE_MAX_WIDTH.containsMatchIn(line) || OLD_SHELL_RECIPE.containsMatchIn(line))
                            }.map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                    }
            offenders.shouldBeEmpty()
        }

        test("every allow-list entry still exists and still has a reason to be there") {
            val names =
                CLIENT_SOURCES
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.name }
                    .toSet()
            ALLOWED_OWN_WIDTH.keys.filter { it !in names }.shouldBeEmpty()
        }

        test("the page shell is the one source of width: DataScreenLayout and theme.css agree") {
            val layout = File(CLIENT_SOURCES, "network/lapis/cloud/client/DataScreenLayout.kt").readText()
            layout.contains("fun Container.pageShell(") shouldBe true
            layout.contains("fun Container.formSection(") shouldBe true
            val css = THEME_CSS.readText()
            css.contains(".lapis-page--wide {\n  max-width: 100rem;") shouldBe true
            css.contains(".lapis-page--form {\n  max-width: 60rem;") shouldBe true
        }
    })
