package network.lapis.cloud.server.webhook

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.shared.domain.WebhookFailureReason
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.concurrent.thread

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun loopbackTarget(): SafeFederationTarget =
    SafeFederationTarget(originalHost = "127.0.0.1", pinnedAddress = InetAddress.getByName("127.0.0.1"))

/**
 * Exercises [WebhookDeliverySender.sendSignedRequest] -- the wire-level POST + response-handling
 * half of [WebhookDeliverySender.sendOnce], factored out (Security-Audit-Fund F1/F2, Runde 1,
 * 2026-09-02) specifically so it is reachable from a test WITHOUT also having to satisfy
 * [checkWebhookUrl]'s SSRF guard first -- that guard, by design, refuses every address a JVM test
 * process could actually bind a local listener to (loopback included), which is exactly why
 * [WebhookServiceTest]/[WebhookDeliveryPollerTest] both document that they cannot exercise a real
 * HTTP response through [WebhookDeliverySender.sendOnce] itself. This suite reaches the real network
 * code by calling [WebhookDeliverySender.sendSignedRequest] directly with a hand-built
 * [SafeFederationTarget] pointed at a local test server -- everything downstream of that call
 * ([webhookHttpClient], `preparePost`/`execute`, the exception mapping) is the SAME production code
 * [sendOnce] uses, unmodified for testing.
 */
class WebhookDeliverySenderTest :
    FunSpec({
        val sender = WebhookDeliverySender(secretBox = SecretBox(randomKey()), allowInsecureHttp = true)

        suspend fun send(url: String): WebhookSendOutcome =
            sender.sendSignedRequest(
                target = loopbackTarget(),
                url = url,
                eventId = "11111111-1111-1111-1111-111111111111",
                eventTypeWireName = "webhook.test",
                attempt = 1,
                signatureHeader = "t=0,v1=deadbeef",
                bodyBytes = "{}".toByteArray(),
            )

        test(
            "F1: a receiver announcing a Content-Length beyond the 64 KiB cap is rejected as PROTOCOL_ERROR " +
                "-- the oversized body is never read",
        ) {
            val bodySize = 100 * 1024 // 100 KiB > MAX_WEBHOOK_RESPONSE_BYTES (64 KiB)
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.createContext("/big") { exchange ->
                val body = ByteArray(bodySize)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val outcome = runBlocking { send("http://127.0.0.1:${server.address.port}/big") }
                outcome shouldBe WebhookSendOutcome.TransportFailure(WebhookFailureReason.PROTOCOL_ERROR)
            } finally {
                server.stop(0)
            }
        }

        test("a normal small 2xx response is unaffected by the response-size cap -- the fix does not break the happy path") {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.createContext("/ok") { exchange ->
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val outcome = runBlocking { send("http://127.0.0.1:${server.address.port}/ok") }
                outcome shouldBe WebhookSendOutcome.Responded(httpStatus = 200)
            } finally {
                server.stop(0)
            }
        }

        test(
            "F2: a malformed HTTP response (invalid status line, io.ktor.http.cio.ParserException) is caught as " +
                "PROTOCOL_ERROR -- before the fix this exception (IllegalStateException, not IOException) escaped " +
                "sendOnce uncaught and left the delivery row stuck DELIVERING forever",
        ) {
            val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            val serverThread =
                thread(start = true) {
                    runCatching {
                        serverSocket.accept().use { socket ->
                            socket.getOutputStream().write("NOT AN HTTP RESPONSE AT ALL\r\n\r\n".toByteArray())
                            socket.getOutputStream().flush()
                        }
                    }
                }
            try {
                val outcome = runBlocking { send("http://127.0.0.1:${serverSocket.localPort}/malformed") }
                outcome shouldBe WebhookSendOutcome.TransportFailure(WebhookFailureReason.PROTOCOL_ERROR)
            } finally {
                serverSocket.close()
                serverThread.join(2_000)
            }
        }

        test(
            "F2: coroutine cancellation while a request is in flight propagates as CancellationException, never " +
                "mapped to a TransportFailure",
        ) {
            // Accepts the connection but never writes a response -- sendSignedRequest is left
            // suspended awaiting the response, which is exactly where the cancellation below lands.
            val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            val serverThread = thread(start = true) { runCatching { serverSocket.accept() } }
            try {
                var caughtCancellation = false
                runBlocking {
                    val job =
                        launch {
                            try {
                                send("http://127.0.0.1:${serverSocket.localPort}/hang")
                            } catch (e: CancellationException) {
                                caughtCancellation = true
                                throw e
                            }
                        }
                    delay(200) // let the request actually reach "connected, awaiting response"
                    job.cancelAndJoin()
                }
                caughtCancellation shouldBe true
            } finally {
                serverSocket.close()
                serverThread.join(2_000)
            }
        }
    })
