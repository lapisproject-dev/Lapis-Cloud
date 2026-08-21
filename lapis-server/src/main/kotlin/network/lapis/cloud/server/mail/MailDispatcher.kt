package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Entkoppelt den SMTP-Sendeversuch vom aufrufenden RPC/Route-Handler -- EIN
 * [CoroutineScope] (`SupervisorJob() + Dispatchers.IO`), dasselbe Idiom wie
 * `network.lapis.cloud.server.payment.sepa.SepaBatchPoller`.
 *
 * **Scope**: only the two single-recipient transactional mailers ([SmtpPasswordResetMailer]/
 * [SmtpFriendVerificationMailer]) go through this class. `network.lapis.cloud.server.rpc
 * .MailingService.sendMailingMessage`'s bulk mailing-list send remains its own pre-existing
 * simulation (writes a `SENT` delivery-log row per subscriber, no real transport) -- deliberately
 * out of scope for this wave, see that method's own inline comment.
 *
 * **Fire-and-forget by design, not merely for UX.** `POST /api/auth/password-reset/request`
 * returns the IDENTICAL response whether or not the email is registered (see `AuthRoutes.kt`
 * KDoc "account-enumeration hardening") -- a synchronous SMTP round-trip (realistic TLS handshake
 * against a real relay: 100-800ms) would only happen on the `accountRow != null` branch, opening a
 * timing side channel that leaks exactly what that hardening is meant to hide. The same reasoning
 * applies to `RegistrationService.registerFriend`'s silent-duplicate-no-op branch. [enqueue]
 * returning immediately keeps both branches timing-indistinguishable, AND means a member whose
 * account was created successfully never sees an error because of an unrelated SMTP timeout.
 *
 * **DoS deckel, zweistufig** (Review-Runde 2 -- Runde 1 hatte nur die erste Stufe, siehe CHANGELOG):
 * [enqueue] never suspends -- it hands the mail to a bounded [queue] ([Channel] with capacity
 * [queueCapacity]) via `trySend`, and exactly [maxConcurrentSends] long-lived worker coroutines
 * drain that queue one mail at a time. Both numbers are finite by design -- an unbounded queue
 * would be exactly the same amplification vector against the configured SMTP relay that an
 * unbounded `launch` per request would be (both call sites this wave wires up,
 * `/api/auth/password-reset/request` and `RegistrationService.registerFriend`, are reachable
 * without authentication). A saturated dispatcher (queue full, all workers busy) drops the newest
 * mail and logs an ERROR rather than growing without bound.
 *
 * **Warum [DEFAULT_MAX_CONCURRENT_SENDS] = 4 und [DEFAULT_QUEUE_CAPACITY] = 64**: this server talks
 * to a single, self-hosted netcup mailbox as its relay -- not a bulk-mail provider built for many
 * parallel connections, so four concurrent SMTP connections is a deliberately conservative ceiling
 * for that relay tier, not an arbitrary placeholder. Round 1 left the dispatcher with concurrency
 * as its ONLY buffer: `SmtpConfig.DEFAULT_CONNECT_TIMEOUT_MS` (10s) means a single transient relay
 * stall (relay refuses new connections for ~10s) can occupy all four workers for the full 10s,
 * during which every [enqueue] call used to be dropped immediately and permanently -- with no
 * retry, that turned a brief relay hiccup into silently lost password-reset/FRIEND-verification
 * mail while the HTTP response the caller saw stayed a plain success (see `AuthRoutes.kt`/
 * `RegistrationService.registerFriend`, both of which persist their token BEFORE calling
 * [enqueue]). The 64-slot queue is the second buffer: combined with the 4 in-flight workers it
 * absorbs up to 68 concurrent [enqueue] calls during such a stall before anything is dropped --
 * comfortably above any realistic organic burst against either call site. This does NOT add a
 * retry -- a mail dropped once the queue itself is full is still gone for good, logged as before;
 * it only shrinks the window in which a short-lived relay stall causes a drop at all.
 *
 * **Lifecycle**: the caller (`Application.module()`) is responsible for calling [shutdown] from an
 * `ApplicationStopping` hook so the underlying [scope] is cancelled deliberately at shutdown
 * instead of leaking for the life of the JVM (relevant for tests and hot-reload alike, since a
 * fresh [MailDispatcher]/scope is otherwise created on every `module()` call without the old one
 * ever being torn down).
 */
class MailDispatcher(
    private val transport: MailTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    maxConcurrentSends: Int = DEFAULT_MAX_CONCURRENT_SENDS,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val perSendTimeout: Duration = DEFAULT_PER_SEND_TIMEOUT,
) {
    private data class QueuedMail(
        val to: String,
        val subject: String,
        val plainTextBody: String,
        val htmlBody: String,
        val purpose: String,
        val maskedTo: String,
    )

    private val queue = Channel<QueuedMail>(capacity = queueCapacity)

    init {
        // Exactly `maxConcurrentSends` long-lived worker coroutines, each pulling and fully
        // sending one mail at a time off `queue` -- the worker COUNT itself is the concurrency
        // bound, replacing Round 1's Semaphore.tryAcquire()/Job.invokeOnCompletion { release() }
        // pair with something that has no separate permit-leak surface to reason about at all.
        repeat(maxConcurrentSends) {
            scope.launch {
                for (mail in queue) {
                    sendOne(mail)
                }
            }
        }
    }

    /**
     * Nimmt die Nachricht zur Zustellung an und kehrt SOFORT zurück -- wirft nie. [purpose] ist
     * nur für Logging gedacht (`"password-reset"`/`"friend-email-verification"`), niemals Teil des
     * versendeten Inhalts.
     */
    fun enqueue(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
        purpose: String,
    ) {
        // maskEmailForLogging: see its own KDoc -- pairing a full recipient address with a purpose
        // like "password-reset"/"friend-email-verification" on every request would otherwise turn
        // the server log into a standing "who reset a password / joined as FRIEND when" record.
        val maskedTo = maskEmailForLogging(to)
        val mail =
            QueuedMail(
                to = to,
                subject = subject,
                plainTextBody = plainTextBody,
                htmlBody = htmlBody,
                purpose = purpose,
                maskedTo = maskedTo,
            )
        // trySend never suspends -- either a free queue slot is claimed synchronously right here,
        // or it fails synchronously (queue full, or the channel is already closed by shutdown()).
        val result = queue.trySend(mail)
        if (result.isFailure) {
            logger.error { "Mail dropped, dispatcher saturated: purpose=$purpose to=$maskedTo" }
        }
    }

    private suspend fun sendOne(mail: QueuedMail) {
        try {
            val outcome =
                withTimeout(perSendTimeout) {
                    transport.send(
                        to = mail.to,
                        subject = mail.subject,
                        plainTextBody = mail.plainTextBody,
                        htmlBody = mail.htmlBody,
                    )
                }
            when (outcome) {
                is MailSendOutcome.Sent -> logger.info { "Mail delivered: purpose=${mail.purpose} to=${mail.maskedTo}" }
                is MailSendOutcome.Skipped ->
                    logger.info { "Mail NOT delivered (no SMTP configured): purpose=${mail.purpose} to=${mail.maskedTo}" }
                is MailSendOutcome.Failed ->
                    logger.error {
                        "Mail delivery FAILED: purpose=${mail.purpose} to=${mail.maskedTo} reason=${outcome.sanitizedErrorMessage}"
                    }
            }
        } catch (t: Throwable) {
            // Deliberately catches (and does NOT rethrow) kotlinx.coroutines.TimeoutCancellationException
            // too, per item 23 of the test plan ("Timeout path ends in Failed, not a hung job") --
            // this coroutine runs in `scope`, a dedicated, request-independent CoroutineScope (never
            // a child of the calling RPC/route handler's own coroutine). Swallowing it here only
            // ends THIS ONE mail's send attempt; the worker's enclosing `for (mail in queue)` loop
            // immediately re-checks `scope`'s own cancellation at its next suspension point (the
            // next `receive`), so a genuine `shutdown()` still stops the worker -- it is never
            // masked or delayed by this catch.
            //
            // Catches Throwable, not just Exception: the workers launched in `init` are LONG-LIVED
            // (`for (mail in queue) { sendOne(mail) }`, not one `scope.launch` per mail as in Round 2)
            // and `scope` uses a SupervisorJob, so an uncaught Throwable here would silently kill only
            // THIS worker -- it would fall out of `sendOne`, fall out of the `for` loop, and end for
            // good, permanently shrinking the pool from `maxConcurrentSends` towards zero with no
            // self-healing (unlike Round 2's per-mail `scope.launch` + `invokeOnCompletion { release() }`,
            // where a dead coroutine's permit still came back). A `java.lang.Error` out of
            // `transport.send` -- e.g. an `OutOfMemoryError` building the `MimeMessage`/multipart body
            // under memory pressure, or an `ExceptionInInitializerError`/`NoClassDefFoundError` from a
            // broken Angus-provider classpath -- must not be allowed to take a worker down.
            logger.error {
                "Mail delivery FAILED: purpose=${mail.purpose} to=${mail.maskedTo} reason=${t::class.simpleName ?: "unknown error"}"
            }
        }
    }

    /**
     * Closes [queue] (no further [enqueue] call is accepted -- each fails the same way a full
     * queue does, logged as dropped) and cancels [scope], and with it any in-flight send plus
     * anything still sitting in the queue. Call from an `ApplicationStopping` hook, never from
     * request-handling code. Idempotent (`Channel.close()`/`CoroutineScope.cancel()` on an
     * already-closed/-cancelled instance is a no-op).
     */
    fun shutdown() {
        queue.close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_SENDS = 4
        const val DEFAULT_QUEUE_CAPACITY = 64
        val DEFAULT_PER_SEND_TIMEOUT: Duration = 60.seconds
    }
}
