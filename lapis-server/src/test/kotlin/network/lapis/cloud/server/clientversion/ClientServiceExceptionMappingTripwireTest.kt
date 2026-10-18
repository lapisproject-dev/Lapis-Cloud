package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val SHARED_EXCEPTIONS: File =
    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/ServiceExceptions.kt")
        .let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/ServiceExceptions.kt") }

private val CLIENT_MAIN: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

/**
 * V1.9.158 -- "an unavailable option never fails silently". Kilua RPC transmits only the exception TYPE, so a service exception the client
 * neither maps (`AppState.handleGuardedFailure` / `typedRefusalText`) nor catches itself surfaces as the raw server text -- or as
 * nothing the person can understand. This test makes a NEW exception class impossible to add without that decision:
 *
 * - every `@RpcServiceException` class of `ServiceExceptions.kt` is mapped in `AppState.kt` (`is X`), caught in another client file
 *   (`catch (e: X)` / `is X`), or on the [knownGaps] list WITH its reason;
 * - a [knownGaps] entry that the client does handle by now is stale and fails the test (the list only shrinks);
 * - the exceptions of V1.9.158 are mapped centrally and never on the list.
 */
class ClientServiceExceptionMappingTripwireTest :
    FunSpec({
        /** class -> why the client has no text for it (yet). A new class is NOT added here without a real reason. */
        val knownGaps =
            mapOf(
                "AiFeatureDisabledException" to
                    "answered by the disabled AI assistant stub; the AI UI is not rendered while the feature is off",
                "AiOptInMissingException" to
                    "the AI buttons are only shown after the opt-in; follow-up: a calm text if the opt-in was withdrawn meanwhile",
                "AiDocumentNotReleasableException" to
                    "the client pre-checks the access level (DocumentsScreen); follow-up: a text for the race",
                "ProbeCodedConflictException" to "a test probe of the exception-code wire format, never thrown by a service",
            )

        val exceptionNames =
            Regex(
                """^class (\w+Exception)\(""",
                RegexOption.MULTILINE,
            ).findAll(SHARED_EXCEPTIONS.readText()).map { it.groupValues[1] }.toList()
        val appState = File(CLIENT_MAIN, "AppState.kt").readText()
        val otherClientCode =
            CLIENT_MAIN
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "AppState.kt" }
                .joinToString("\n") { file ->
                    file
                        .readLines()
                        .filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
                        .joinToString("\n")
                }

        fun mappedCentrally(name: String) = Regex("""\bis $name\b""").containsMatchIn(appState)

        fun handledElsewhere(name: String) =
            Regex("""catch \(\w+: $name\)""").containsMatchIn(otherClientCode) || Regex("""\bis $name\b""").containsMatchIn(otherClientCode)

        test("the scan finds the exception classes (not an empty list)") {
            (exceptionNames.size > 80) shouldBe true
        }

        test("every service exception is mapped, caught by the client, or a known gap with a reason") {
            val unhandled = exceptionNames.filterNot { mappedCentrally(it) || handledElsewhere(it) || it in knownGaps }
            withClue("a new @RpcServiceException needs a client text (AppState.typedRefusalText) or a local catch: $unhandled") {
                unhandled.shouldBeEmpty()
            }
        }

        test("the list of known gaps has no stale entry (a class the client handles by now, or that no longer exists)") {
            val stale = knownGaps.keys.filter { it !in exceptionNames || mappedCentrally(it) || handledElsewhere(it) }
            withClue("remove from the list: $stale") { stale.shouldBeEmpty() }
        }

        test("the unavailable-option exceptions of V1.9.158 are mapped centrally, in one place") {
            listOf(
                "EventRoomRecordingAccessLevelNotAllowedException",
                "EventRoomCaptureBlockedByAdminException",
                "EventRoomCaptureBlockedByOrganizerException",
                "ConferenceRecordingAlreadyActiveException",
                "ConferenceRoomEndedException",
                "RateLimitedException",
            ).forEach { name ->
                withClue(name) {
                    (name in exceptionNames) shouldBe true
                    mappedCentrally(name) shouldBe true
                    (name in knownGaps) shouldBe false
                }
            }
        }
    })
