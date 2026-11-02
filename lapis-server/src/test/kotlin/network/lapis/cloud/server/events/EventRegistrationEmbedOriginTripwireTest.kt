package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.261 -- where the partner origin of an embed registration may be touched. `event_registration.embed_origin` is an audit trail: it is written
 * by the insert and read once, by the resume path that re-validates it. `payment_checkout_session.embed_origin` is only ever WRITTEN; nothing reads it.
 * A new reader could hang a redirect, a return URL or a `frame-ancestors` decision on a value that is only as trustworthy as the allowlist of the
 * moment it was stored, so a new place that reads either column turns this red and has to be looked at by a reviewer.
 */
class EventRegistrationEmbedOriginTripwireTest :
    FunSpec({
        val root = File("src/main/kotlin")

        fun isCode(line: String) =
            !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("/*")

        fun filesUsing(token: String): List<String> =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }
                .filter { file -> file.readLines().any { isCode(it) && token in it } }
                .map { it.name }
                .sorted()
                .toList()

        val submissionFile = File("src/main/kotlin/network/lapis/cloud/server/events/EventRegistrationSubmission.kt")

        test(
            "event_registration.embed_origin is touched only by the store (write) and the submission (re-validated read), no DTO, export or route",
        ) {
            filesUsing("EventRegistrationTable.embedOrigin") shouldContainExactlyInAnyOrder
                listOf("EventRegistrationSubmission.kt", "EventStore.kt")
        }

        test("payment_checkout_session.embed_origin is only written, by PspCheckoutSessions, and read by nothing") {
            filesUsing("PaymentCheckoutSessionTable.embedOrigin") shouldContainExactlyInAnyOrder listOf("PspCheckoutSessions.kt")
            val readers = Regex("""\[PaymentCheckoutSessionTable\.embedOrigin\]""")
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }
                .flatMap { f -> f.readLines().filter { isCode(it) && readers.containsMatchIn(it) && "] =" !in it }.asSequence() }
                .toList() shouldBe emptyList()
        }

        test("the stored origin is re-validated in exactly one place, the resume path") {
            val code = submissionFile.readLines().filter { isCode(it) }
            code.count { "EmbedOriginAudit.revalidateForUse(" in it } shouldBe 1
        }

        test("the resume path no longer hard-codes a null origin") {
            val body =
                submissionFile
                    .readText()
                    .substringAfter("private suspend fun resumeCheckoutUnguarded(")
                    .substringBefore("private suspend fun startStripeCheckout(")
            body.lines().filter { isCode(it) }.none { "embedOrigin = null" in it } shouldBe true
        }
    })
