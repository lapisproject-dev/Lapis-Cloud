package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic via [CompletableDeferred] handshakes -- never `Thread.sleep`. A fresh
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per test, exactly [MailDispatcher]'s own
 * default shape, injected explicitly so each test's fake [MailTransport] behaviour is isolated.
 */
class MailDispatcherTest :
    FunSpec({
        test("enqueue returns before the transport call completes") {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        started.complete(Unit)
                        release.await()
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher =
                MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

            // enqueue() itself never suspends -- it returns immediately regardless of whether the
            // launched coroutine has even started yet, let alone finished.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")

            runBlocking { withTimeout(5.seconds) { started.await() } }
            release.isCompleted shouldBe false // proves send() genuinely blocked past enqueue()'s return
            release.complete(Unit)
        }

        test("a throwing transport never propagates to the caller") {
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome = throw IllegalStateException("boom")
                }
            val dispatcher =
                MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

            // Must not throw -- enqueue() itself is synchronous and never even reaches the throwing
            // body before returning.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
        }

        test("an Error thrown by transport.send does not kill the worker -- the next queued mail is still sent") {
            class SimulatedJvmError : Error("simulated JVM error")

            val sendInvocations = AtomicInteger(0)
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        val invocation = sendInvocations.incrementAndGet()
                        if (invocation == 1) {
                            // A java.lang.Error, not an Exception -- e.g. what an OutOfMemoryError
                            // building the MimeMessage, or a NoClassDefFoundError from a broken
                            // Angus-provider classpath, would look like from sendOne()'s perspective.
                            throw SimulatedJvmError()
                        }
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher =
                MailDispatcher(
                    transport = transport,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    maxConcurrentSends = 1,
                    queueCapacity = 8,
                )

            // Single worker (maxConcurrentSends = 1): the first mail's transport.send throws a
            // java.lang.Error. If MailDispatcher.sendOne caught only `Exception` (the Round 3
            // regression this test guards against), the Error would propagate out of sendOne, out
            // of the worker's `for (mail in queue)` loop, and permanently kill the sole worker --
            // the second mail would then sit in the queue forever and this test would time out.
            // With the current `catch (t: Throwable)`, the worker survives and picks up the second
            // mail.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")

            runBlocking {
                withTimeout(5.seconds) {
                    while (sendInvocations.get() < 2) kotlinx.coroutines.yield()
                }
            }
            sendInvocations.get() shouldBe 2 // the worker survived the Error and sent the second mail
        }

        test("never more than maxConcurrentSends in flight -- the rest wait in the queue, none are dropped") {
            val inFlight = AtomicInteger(0)
            val maxObservedInFlight = AtomicInteger(0)
            val sendInvocations = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        // Update `inFlight`/`maxObservedInFlight` BEFORE `sendInvocations` -- the
                        // wait loop below polls `sendInvocations`, so this ordering guarantees that
                        // by the time it observes `sendInvocations == 2`, `maxObservedInFlight` has
                        // deterministically already been bumped to reflect the second concurrent
                        // invocation. Doing it the other way round races two independent atomics
                        // across two worker coroutines: the second worker could increment
                        // `sendInvocations` and get preempted before touching `inFlight`, letting the
                        // main thread read `maxObservedInFlight == 1` and fail spuriously even though
                        // the dispatcher enforced the concurrency bound correctly.
                        val now = inFlight.incrementAndGet()
                        maxObservedInFlight.updateAndGet { current -> maxOf(current, now) }
                        sendInvocations.incrementAndGet()
                        release.await()
                        inFlight.decrementAndGet()
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher =
                MailDispatcher(
                    transport = transport,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    maxConcurrentSends = 2,
                    queueCapacity = 8,
                )

            // 5 mails, only 2 workers -- the other 3 sit in the queue (capacity 8, nothing dropped)
            // until a worker frees up.
            repeat(5) {
                dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
            }

            runBlocking {
                withTimeout(5.seconds) {
                    while (sendInvocations.get() < 2) kotlinx.coroutines.yield()
                }
            }
            maxObservedInFlight.get() shouldBe 2 // never more than maxConcurrentSends concurrently

            release.complete(Unit)
            runBlocking {
                withTimeout(5.seconds) {
                    while (sendInvocations.get() < 5) kotlinx.coroutines.yield()
                }
            }
            sendInvocations.get() shouldBe 5 // all 5 eventually reached the transport -- none were dropped
        }

        test("queue full -- the overflow is dropped, everything that fit is still sent") {
            val release = CompletableDeferred<Unit>()
            val sendInvocations = AtomicInteger(0)
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sendInvocations.incrementAndGet()
                        release.await()
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher =
                MailDispatcher(
                    transport = transport,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    maxConcurrentSends = 1,
                    queueCapacity = 1,
                )

            // First mail: the single worker picks it up and blocks in `release.await()` inside
            // `send()` -- wait for that deterministically (rather than assume it happened before
            // the next enqueue() calls run) so the channel's 1-slot buffer is provably empty again
            // before the next two mails are sent.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
            runBlocking { withTimeout(5.seconds) { while (sendInvocations.get() < 1) kotlinx.coroutines.yield() } }

            // Second mail fills the (now empty) 1-slot queue buffer. Third mail finds the buffer
            // still full (the worker is still blocked on the first mail) -- `trySend` runs
            // SYNCHRONOUSLY inside enqueue(), so by the time this call returns the drop has
            // deterministically already happened.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")

            release.complete(Unit)
            runBlocking {
                withTimeout(5.seconds) {
                    while (sendInvocations.get() < 2) kotlinx.coroutines.yield()
                }
            }
            sendInvocations.get() shouldBe 2 // never 3 -- the overflow never reached the transport at all
        }

        test("timeout path ends in a logged failure, never a hung job") {
            val cancelledSignal = CompletableDeferred<Unit>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelledSignal.complete(Unit)
                        }
                    }
                }
            val dispatcher =
                MailDispatcher(
                    transport = transport,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    perSendTimeout = 50.milliseconds,
                )

            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")

            // If MailDispatcher's own withTimeout did not actually cancel the stuck send(), this
            // would hang until the outer 5s timeout fires and the test fails -- it does not hang.
            runBlocking { withTimeout(5.seconds) { cancelledSignal.await() } }
        }

        test("enqueue after shutdown is silently dropped, never throws") {
            val sendInvocations = AtomicInteger(0)
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sendInvocations.incrementAndGet()
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher =
                MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

            dispatcher.shutdown()

            // `queue.trySend` on an already-closed Channel fails the same synchronous way it does
            // on a full queue (Channel.trySend never throws on a closed channel, it returns a
            // failed ChannelResult) -- enqueue() must not surface that as an exception to the
            // caller (AuthRoutes/RegistrationService, both unauthenticated call sites).
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")

            // Nothing was ever handed to the transport -- the closed channel rejected the mail
            // before a worker could ever dequeue it.
            sendInvocations.get() shouldBe 0
        }

        test("shutdown actually ends the worker coroutines, even one stuck mid-send -- not just the channel") {
            val started = CompletableDeferred<Unit>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        started.complete(Unit)
                        awaitCancellation() // never returns on its own -- only cancellation ends this
                    }
                }
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val dispatcher = MailDispatcher(transport = transport, scope = scope, maxConcurrentSends = 2, queueCapacity = 4)

            // Drive one worker into an in-flight send() BEFORE calling shutdown() -- this is the
            // scenario the KDoc's idempotency/lifecycle claim actually needs to hold for: a worker
            // that is not idly waiting on the (then-closed) channel, but stuck inside `sendOne`.
            // If `shutdown()` only called `queue.close()` without `scope.cancel()` (the regression
            // this test guards against), closing the channel would not affect this already-
            // dequeued, in-flight mail at all -- the worker, and with it the coroutine, would run
            // for the life of the JVM.
            dispatcher.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "test")
            runBlocking { withTimeout(5.seconds) { started.await() } }

            dispatcher.shutdown()

            // job.join() only completes once every child coroutine (all maxConcurrentSends workers,
            // including the one stuck in send()) has completed -- structured concurrency's own
            // guarantee. If shutdown() failed to cancel the scope, this hangs until the outer 5s
            // timeout fires and the test fails.
            runBlocking { withTimeout(5.seconds) { job.join() } }
            scope.isActive shouldBe false
        }

        test("shutdown is idempotent -- calling it twice never throws") {
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome = MailSendOutcome.Sent
                }
            val dispatcher =
                MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

            // Channel.close()/CoroutineScope.cancel() are themselves documented as no-ops when
            // already closed/cancelled -- this test exists so a future change that swaps either for
            // a throwing primitive (e.g. Channel.close(CancellationException(...)) called twice
            // with different causes) is caught here rather than in a flaky ApplicationStopping path
            // exercised only by tests that call `module()` more than once.
            dispatcher.shutdown()
            dispatcher.shutdown()
        }
    })
