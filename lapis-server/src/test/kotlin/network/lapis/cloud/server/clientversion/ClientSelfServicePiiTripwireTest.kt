package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.33 -- the three member self-service files handle personal data (address, date of birth, nationality, card state) and event
 * registrations. A tripwire on their source (same heuristic as [ClientMcpAccessCardTripwireTest]; the behavioural evidence is in the
 * Karma tests `MemberAddressCardDomTest`, `MemberCardRevokeCardDomTest`, `MemberEventsScreenDomTest`): none of them may write to the
 * console or to browser storage, read an exception's `message` (Kilua RPC never transmits it, and showing it would leak server text),
 * or hand a personal-data DTO field to a toast.
 */
private val SELF_SERVICE_FILES =
    listOf(
        "MemberAddressCard.kt",
        "MemberCardRevokeCard.kt",
        "MemberEventsScreen.kt",
        // V1.9.35: the board-side files handle other people's address / GwG data, names and payment amounts.
        "MemberAddressAdminDialog.kt",
        "EventRefundsSection.kt",
        "MemberEventPaymentUi.kt",
        "AuditMarkerLabels.kt",
        // V1.9.56 "E-Mail-Änderung absichern": the member's own address and password, the mail-link token, and the (masked) address of
        // someone else's pending change.
        "MemberEmailCard.kt",
        "MemberEmailChangeProposal.kt",
        "EmailChangeDeepLinkScreens.kt",
    )

private val SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun codeLines(file: File): List<String> =
    file.readLines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }

private fun sourceOf(name: String): File = SOURCES.walkTopDown().first { it.isFile && it.name == name }

private val PII_FIELDS = "street|postalCode|city|country|dateOfBirth|nationality|memberNumber|newEmail|pendingEmail|newEmailMasked|email"

class ClientSelfServicePiiTripwireTest :
    FunSpec({
        test("the self-service files never use console, browser storage or an exception message") {
            val forbidden = Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { forbidden.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("no toast receives a personal-data DTO field") {
            val toastWithPii = Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { toastWithPii.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("no toast receives a participant name, an amount, a display name, an event title or a guest name") {
            val toastWithData =
                Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:participantDisplayName|paidAmount|displayName|eventTitle|guestName)\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { toastWithData.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("no personal-data field and no navigation / URL helper meet on one line (no PII in the URL)") {
            val piiInUrl =
                Regex("""(?:window\.location|\bhistory\.|URLSearchParams|navigateTo\(|\.assign\()[^\n]*\b(?:$PII_FIELDS)\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { piiInUrl.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("the V1.9.35 detectors see what they are meant to see") {
            val toastWithData =
                Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:participantDisplayName|paidAmount|displayName|eventTitle|guestName)\b""")
            toastWithData.containsMatchIn("""toastSuccess(refund.participantDisplayName)""") shouldBe true
            toastWithData.containsMatchIn("""toastSuccess(gettext("Erstattung vermerkt."))""") shouldBe false
            val piiInUrl =
                Regex("""(?:window\.location|\bhistory\.|URLSearchParams|navigateTo\(|\.assign\()[^\n]*\b(?:$PII_FIELDS)\b""")
            piiInUrl.containsMatchIn("""window.location.assign("/x?city=" + dto.city)""") shouldBe true
            piiInUrl.containsMatchIn("""window.location.assign(url)""") shouldBe false
        }

        test("V1.9.56: the address-change screens keep the mail-link token out of toasts, logs, storage and any URL helper") {
            val tokenSink =
                Regex(
                    """(?:notify\w*|toast\w*)\([^)]*\btoken\b|\bconsole\.[^\n]*\btoken\b|(?:localStorage|sessionStorage)[^\n]*\btoken\b""",
                )
            val tokenInUrl = Regex("""(?:window\.location|\bhistory\.|URLSearchParams|navigateTo\(|\.assign\()[^\n]*\btoken\b""")
            val findings =
                listOf("EmailChangeDeepLinkScreens.kt", "AuthHttp.kt").flatMap { name ->
                    codeLines(sourceOf(name))
                        .filter { tokenSink.containsMatchIn(it) || tokenInUrl.containsMatchIn(it) }
                        .map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            tokenSink.containsMatchIn("""notifyError(token)""") shouldBe true
            tokenInUrl.containsMatchIn("""window.location.hash = "#/x?token=" + token""") shouldBe true
            tokenInUrl.containsMatchIn("""window.history.replaceState(null, "", "#${'$'}route")""") shouldBe false
        }

        test("V1.9.56: the new address-change screens are part of the scanned set and the scanner finds them") {
            listOf("MemberEmailCard.kt", "MemberEmailChangeProposal.kt", "EmailChangeDeepLinkScreens.kt").forEach { name ->
                (name in SELF_SERVICE_FILES) shouldBe true
                sourceOf(name).isFile shouldBe true
            }
        }

        test("the detector sees what it is meant to see") {
            Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""").containsMatchIn("""console.log(x)""") shouldBe true
            Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""").containsMatchIn("""notify(e.message)""") shouldBe true
            Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""").containsMatchIn("""notifyError(dto.street)""") shouldBe true
            Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""").containsMatchIn("""toastError(gettext("Fehler"))""") shouldBe
                false
        }
    })
