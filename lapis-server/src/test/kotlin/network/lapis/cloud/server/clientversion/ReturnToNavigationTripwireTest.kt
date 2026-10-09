package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwire of V1.9.89 (login -> consent round trip, `returnTo`). Sources are scanned as text, in the pattern of [ScrollSurfaceTripwireTest]:
 * comment lines are exempt, every detector proves itself against a positive and a negative example, and the ledger of exceptions is exact.
 *
 * Client (`lapis-client/src/jsMain/kotlin`):
 * - a full-page navigation (`location.assign` / `location.replace` / `location.href =`) exists only in `FullPageNavigation.kt` (exactly one)
 *   and in the named, counted exceptions below (their targets are server/PSP answers, never a `returnTo`);
 * - `fullPageNavigate(` is called exactly once, in `FullPageNavigation.kt`;
 * - `"returnTo"` is read only through the strict parser (never the lenient `parseHashQueryParam` / `hashQueryParam`);
 * - `LoginScreen.kt` has no direct `navigateTo(Routes.DASHBOARD)` outside `completeLogin`.
 *
 * Server: every `respondRedirect(` in `KeycloakAuthRoutes.kt` and `OidcRoutes.kt` takes one of the listed argument forms.
 */
private val CLIENT_MAIN: File =
    File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val SERVER_ROUTES: File =
    File("src/main/kotlin/network/lapis/cloud/server/routes").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/routes")
    }

/** The only places besides `FullPageNavigation.kt` that navigate the whole page, with their exact occurrence count. */
private val NAVIGATION_EXCEPTIONS =
    mapOf(
        "MemberEventsScreen.kt" to 1,
        "PspCheckoutSection.kt" to 1,
        "DonationCheckoutScreen.kt" to 1,
    )

private val FULL_PAGE_NAVIGATION = Regex("""location\.(assign|replace)\(|location\.href\s*=[^=]""")

private val ALLOWED_REDIRECT_ARGUMENTS =
    listOf(
        Regex("""^authorizeUrl$"""),
        Regex("""^"/app#/dashboard"$"""),
        Regex("""^safeReturnTo\([A-Za-z.]+\) \?: "/app#/dashboard"$"""),
        Regex("""^if \(target != null\) "/app#/login\?returnTo=" \+ URLEncoder\.encode\(target, "UTF-8"\) else "/app#/login",?$"""),
        Regex("""^buildRedirectUrl\(.*\),?$"""),
    )

private fun codeLines(text: String): List<String> =
    text.lines().filterNot {
        val t = it.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }

/** The argument text of every `respondRedirect(` call in [source], whitespace-normalised. */
internal fun redirectArguments(source: String): List<String> {
    val code = codeLines(source).joinToString("\n")
    val result = mutableListOf<String>()
    var from = 0
    while (true) {
        val i = code.indexOf("respondRedirect(", from)
        if (i < 0) break
        var depth = 1
        var j = i + "respondRedirect(".length
        val start = j
        while (j < code.length && depth > 0) {
            if (code[j] == '(') depth++
            if (code[j] == ')') depth--
            j++
        }
        result += code.substring(start, j - 1).trim().replace(Regex("""\s+"""), " ")
        from = j
    }
    return result
}

class ReturnToNavigationTripwireTest :
    FunSpec({
        val clientFiles = CLIENT_MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        fun fileNamed(name: String): File = clientFiles.single { it.name == name }

        test("the scan root is found (otherwise every other check is vacuous)") {
            (clientFiles.size > 50) shouldBe true
        }

        test("detectors prove themselves") {
            FULL_PAGE_NAVIGATION.containsMatchIn("window.location.replace(url)") shouldBe true
            FULL_PAGE_NAVIGATION.containsMatchIn("window.location.href = x") shouldBe true
            FULL_PAGE_NAVIGATION.containsMatchIn("val h = window.location.hash") shouldBe false
            FULL_PAGE_NAVIGATION.containsMatchIn("if (window.location.href == x)") shouldBe false
            redirectArguments("call.respondRedirect(\n   a(b)\n)") shouldBe listOf("a(b)")
            redirectArguments("// call.respondRedirect(x)\n* respondRedirect(y)") shouldBe emptyList()
        }

        test("full-page navigation exists only in FullPageNavigation.kt (once) and the named exceptions (counted)") {
            val counts =
                clientFiles
                    .associate { it.name to codeLines(it.readText()).count { line -> FULL_PAGE_NAVIGATION.containsMatchIn(line) } }
                    .filterValues { it > 0 }
            counts shouldBe mapOf("FullPageNavigation.kt" to 1) + NAVIGATION_EXCEPTIONS
        }

        test("fullPageNavigate is called exactly once, inside navigateToReturnTo") {
            val callers =
                clientFiles
                    .associate {
                        it.name to
                            codeLines(it.readText()).count { l -> Regex("""fullPageNavigate\(""").containsMatchIn(l) }
                    }.filterValues { it > 0 }
            callers shouldBe mapOf("FullPageNavigation.kt" to 1)
            val body = fileNamed("FullPageNavigation.kt").readText().substringAfter("fun navigateToReturnTo")
            body.contains("fullPageNavigate(safe)") shouldBe true
        }

        test("returnTo is read only through the strict parser") {
            clientFiles.forEach { file ->
                codeLines(file.readText()).filter { it.contains("\"returnTo\"") }.forEach { line ->
                    withClue("${file.name}: $line") {
                        line.contains("parseHashQueryParamStrict(") shouldBe true
                        Regex("""(?<![A-Za-z])(parseHashQueryParam|hashQueryParam)\(""").containsMatchIn(line) shouldBe false
                    }
                }
            }
        }

        test("LoginScreen.kt navigates to the dashboard only inside completeLogin") {
            val source = fileNamed("LoginScreen.kt").readText()
            val completeLogin = source.substringAfter("internal fun completeLogin").substringBefore("\n}\n")
            val outside = source.replace(completeLogin, "")
            codeLines(outside).none { it.contains("navigateTo(Routes.DASHBOARD)") } shouldBe true
            codeLines(completeLogin).count { it.contains("navigateTo(Routes.DASHBOARD)") } shouldBe 1
            // The one success path of the password form is completeLogin.
            source.contains("leaving = completeLogin(session)") shouldBe true
        }

        test("server redirects in KeycloakAuthRoutes and OidcRoutes take only the allowed argument forms") {
            listOf("KeycloakAuthRoutes.kt", "OidcRoutes.kt").forEach { name ->
                val arguments = redirectArguments(File(SERVER_ROUTES, name).readText())
                (arguments.isNotEmpty()) shouldBe true
                arguments.forEach { argument ->
                    withClue("$name: respondRedirect($argument)") {
                        ALLOWED_REDIRECT_ARGUMENTS.any { it.matches(argument) } shouldBe true
                    }
                }
            }
        }
    })
