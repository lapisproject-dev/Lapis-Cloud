package network.lapis.cloud.server.payment.fints

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import org.kapott.hbci.GV_Result.GVRKUms
import org.kapott.hbci.callback.AbstractHBCICallback
import org.kapott.hbci.callback.HBCICallback
import org.kapott.hbci.callback.HBCICallbackThreaded
import org.kapott.hbci.manager.HBCIHandler
import org.kapott.hbci.manager.HBCIUtils
import org.kapott.hbci.manager.HBCIVersion
import org.kapott.hbci.passport.AbstractHBCIPassport
import org.kapott.hbci.passport.HBCIPassport
import org.kapott.hbci.status.HBCIExecThreadedStatus
import org.kapott.hbci.structures.Konto
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.GregorianCalendar
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Hard cap on simultaneously open TAN dialogs -- see class KDoc "Handle-Tabelle". */
private const val MAX_CONCURRENT_HANDLES = 3

/** hbci4j's own `kernel.threaded.maxwaittime` default (300s) -- see class KDoc. */
private val HANDLE_TTL = 300.seconds

/**
 * The only file in this codebase that imports `org.kapott.*` -- implements both
 * [FinTsStatementFetcher] (held by [FinTsPoller]) and [FinTsSetupClient] (held by
 * `BankAccountService`), see [FinTsClient.kt] KDoc for why one class implementing both interfaces
 * is safe (each caller only ever holds a reference typed to the ONE interface it needs).
 *
 * **Verified against the real hbci4j-core:4.0.0 jar** (2026-09-13, no decompiler available in this
 * environment -- `javap -c -p` bytecode disassembly only), against TWO sources: (a) direct
 * inspection of `HBCIUtils`/`HBCIHandler`/`HBCICallback`/`AbstractHBCIPassport`/`HBCIPassport`'s
 * public method signatures, and (b) the hbci4j project's OWN bundled example,
 * `org.kapott.hbci.examples.UmsatzAbrufPinTan` (+ its inner `MyHBCICallback`), which ships inside
 * this exact jar and demonstrates a complete, working PinTan account-statement retrieval. The
 * passport-configuration idiom below (`HBCIUtils.setParam` for `client.passport.default`/
 * `client.passport.PinTan.init` BEFORE `AbstractHBCIPassport.getInstance(File)`, answering
 * `NEED_BLZ`/`NEED_USERID`/`NEED_CUSTOMERID`/`NEED_PT_PIN`/`NEED_PASSPHRASE_LOAD`/
 * `NEED_PASSPHRASE_SAVE` through the [HBCICallback.callback] `retData` buffer rather than through a
 * passport setter, `NEED_FILTER`/`NEED_PT_SECMECH` left empty) is copied from that example's
 * disassembled bytecode, not invented.
 *
 * **Threaded callback hand-off, TWO independent fixes, verified against the real hbci4j-core:4.0.0
 * bytecode (`javap -p -c -constants`, no decompiler available).**
 *
 * *(1) Two-phase loop (Review fix, CRITICAL, 2026-09-13).* An earlier version of
 * [runSetupDialogOnDedicatedThread] only ever called `handler.initThreaded()` +
 * `continueThreaded()`, never `executeThreaded()`, based on an UNverified assumption that
 * `initThreaded()`'s dialog IS the execute dialog. Bytecode disproves that: `HBCIHandler$1` (the
 * thread `initThreaded()` starts) only calls the private `registerInstitute()`/`registerUser()`
 * helpers and unconditionally syncs `execStatus=null` on every path -- it NEVER calls
 * `HBCIHandler.execute()`. Only `HBCIHandler$2` (the thread `executeThreaded()` starts) calls
 * `execute()` and syncs a real `HBCIExecStatus`. `continueThreaded(String)` itself is phase-agnostic
 * (verified: it reads/writes the same `thread_syncer_main`/`thread_syncer_hbci` keys regardless of
 * which inner thread is currently running), which is what makes [driveThreadedCallbackLoop] safe to
 * reuse for BOTH phases.
 *
 * *(2) Threaded mode was never actually ACTIVATED (Review fix, CRITICAL, 2026-09-14 -- found
 * one review round after (1), in the SAME subsystem).* Fix (1) alone left the setup path
 * unusable for any bank requiring SCA (i.e. every real PSD2 bank with a freshly-issued PIN/TAN
 * passport): `HBCIUtils.initThread(props, callback)` was called with the RAW callback, never
 * wrapped in [HBCICallbackThreaded], and `HBCIHandler` was constructed via its 2-argument
 * constructor, which bytecode shows delegates to the 3-argument one with `threaded=false`.
 * [HBCICallbackThreaded] is the ONLY class in the entire jar that ever calls
 * `HBCICallback.useThreadedCallback` and writes a non-null `callbackData` into the
 * `thread_syncer_main` a running `initThreaded()`/`executeThreaded()` phase is waiting on
 * (`HBCIUtils.initThread`/`HBCIUtilsInternal.getCallback` store/return the callback VERBATIM,
 * no wrapping happens anywhere else) -- without it, `HBCIExecThreadedStatus.isCallback()`
 * (`callbackData != null`) is structurally ALWAYS false, so [driveThreadedCallbackLoop] breaks out
 * of its loop on the FIRST iteration of every phase, [FinTsSetupOutcome.TanRequested] never fires,
 * and the bank's TAN-shaped callback reasons are answered directly by the RAW callback (which only
 * records [bankPrompt] and leaves `retData` empty) -- an EMPTY TAN reaches the bank. Independently,
 * the 3-argument constructor's bytecode (`iload_3; ifne ...`) skips `registerInstitute()`/
 * `registerUser()` ONLY when `threaded=true`; with the 2-arg (`threaded=false`) path those ran
 * SYNCHRONOUSLY inside the constructor, which is why `newJob("SaldoReq")` used to work being called
 * right after construction -- with `threaded=true` those helpers instead run on `initThreaded()`'s
 * OWN worker thread, so `newJob` (which reflectively needs BPD job-restrictions data
 * `registerInstitute()` fetches) must move to AFTER the init-phase loop completes, not before it.
 * Fix: wrap the callback ([HBCICallbackThreaded]) before `HBCIUtils.initThread`, construct
 * `HBCIHandler` with `threaded=true`, and create/queue the `SaldoReq` job only once
 * [driveThreadedCallbackLoop]'s init-phase call has returned non-null.
 *
 * This class's threaded-mode logic is now driven by this verified bytecode, not by an assumption
 * about it -- but its end-to-end behaviour against a REAL bank has still not been (and cannot be)
 * exercised in this repository (no live bank/sandbox available). **No automated test in this
 * codebase claims to verify live-protocol correctness** -- what IS tested: [Hbci4jRawMt940Extractor]
 * against a real, unmodified [GVRKUms] instance, [Throwable.toFinTsErrorCode]'s mapping table, and
 * the passport-file permission/PIN-absence guarantees ([FinTsPassportFileTest]). See
 * `docs/architecture/bank-account.adoc` "FinTS/HBCI live retrieval (Wave 2)" for the full posture
 * and the residual-risk disclosure this implies.
 *
 * **Thread-lokale Initialisierung.** [HBCIUtils.init]/[HBCIUtils.initThread] key their config table
 * by `ThreadGroup`, not `Thread` (verified: `private static Hashtable<ThreadGroup, Properties>
 * configs` field). Every hbci4j operation therefore runs on a dedicated, single-thread
 * [ExecutorService] whose [ThreadFactory] constructs a FRESH [ThreadGroup] per operation, wrapping
 * `HBCIUtils.initThread(props, callback) ... finally { HBCIUtils.doneThread() }`. **No coroutine
 * suspension ever happens inside that block** -- both interfaces are deliberately non-`suspend`
 * (see [FinTsClient.kt] KDoc); callers wrap the blocking call itself in `withContext(Dispatchers.IO)`.
 *
 * **TAN-Behandlung getrennt nach Interface.** [fetch] (the poller path, [abortingCallback]) answers
 * every TAN-shaped reason with an empty buffer and `useThreadedCallback = false` -- hbci4j aborts
 * the dialog, surfaced as [FinTsFetchResult.TanRequired]. There is structurally no parameter through
 * which a TAN could reach this path. [begin]/[submitTan] (the setup path) use hbci4j's own threaded
 * mode; [HANDLE_TTL] mirrors hbci4j's own `kernel.threaded.maxwaittime` default (300s).
 *
 * **Handle-Tabelle.** A pending TAN dialog stays alive on ITS OWN dedicated worker thread, parked on
 * a [LinkedBlockingQueue] between [begin] signalling [FinTsSetupOutcome.TanRequested] and the
 * eventual [submitTan]/[cancel] call -- that SAME physical thread performs BOTH threaded phases
 * (`initThreaded()` then, once init's callback rounds are done, `executeThreaded()`) and every
 * `continueThreaded(tan)` call for either phase, consistent with "Thread-lokale Initialisierung"
 * above. Capped at [MAX_CONCURRENT_HANDLES], swept for expiry by [sweepExpiredHandles] on every
 * [begin]/[submitTan] call AND (Review fix, MINOR) periodically in the background via
 * [startHandleSweeper] -- so an abandoned dialog (ADMIN closes the tab mid-TAN-prompt, no further
 * FinTS RPC ever arrives) is still reclaimed within roughly [HANDLE_TTL], not only "whenever someone
 * else happens to call in".
 *
 * **Fehler-Mapping.** [Throwable.toFinTsErrorCode] is the only place an exception is classified --
 * no hbci4j/bank message, no host, no resolved IP address ever reaches a log line, a DTO, or an
 * exception message thrown from this class.
 *
 * **Nur Lesen.** Only `HKKAZ` (`newJob("KUmsAll")`, [fetch]) and `HKSAL` (`newJob("SaldoReq")`,
 * [runSetupDialogOnDedicatedThread]'s verification probe) are ever queued -- exactly the two jobs
 * the bundled example itself queues. **No** `UebSEPA`/`DauerSEPANew`/`LastSEPA` job is created
 * anywhere in this class.
 */
internal class Hbci4jFinTsClient(
    private val config: FinTsConfig,
) : FinTsStatementFetcher,
    FinTsSetupClient {
    private val handles = ConcurrentHashMap<String, PendingSetup>()

    /**
     * Test-only seam (Review fix, MEDIUM test coverage) -- see [sweepExpiredHandles] KDoc for why
     * this exists at all instead of driving the sweep through a real [begin]/[submitTan] dialog.
     * `internal`, never called from production code (`begin`/[cleanupHandle]/etc. all go through
     * [handles] directly, unchanged by this seam's existence).
     */
    internal fun putHandleForTesting(
        handle: String,
        pending: PendingSetup,
    ) {
        handles[handle] = pending
    }

    /** Test-only seam, see [putHandleForTesting]. */
    internal fun handleCountForTesting(): Int = handles.size

    // ============================================================================================
    // FinTsStatementFetcher -- held ONLY by FinTsPoller.
    // ============================================================================================

    override fun fetch(
        credentials: FinTsCredentials,
        from: LocalDate,
        to: LocalDate,
    ): FinTsFetchResult {
        var tanWasRequested = false
        val callback = readOnlyCallback(credentials = credentials) { tanWasRequested = true }
        return try {
            runOnDedicatedThread(operationName = "fetch", credentials = credentials, callback = callback) { passport ->
                val version = HBCIVersion.HBCI_300.id
                val handler = HBCIHandler(version, passport)
                try {
                    val account = accountFor(credentials = credentials, passport = passport)
                    val job = handler.newJob("KUmsAll")
                    job.setParam("my", account)
                    job.setParam("startdate", from.toJavaUtilDate())
                    job.setParam("enddate", to.toJavaUtilDate())
                    job.addToQueue()

                    val execStatus = handler.execute()
                    if (!execStatus.isOK) {
                        return@runOnDedicatedThread if (tanWasRequested) {
                            FinTsFetchResult.TanRequired
                        } else {
                            FinTsFetchResult.Failed(FinTsErrorCode.PROTOCOL_ERROR)
                        }
                    }

                    val result = job.jobResult
                    if (result !is GVRKUms) {
                        return@runOnDedicatedThread FinTsFetchResult.Failed(FinTsErrorCode.PROTOCOL_ERROR)
                    }
                    val bytes =
                        try {
                            Hbci4jRawMt940Extractor.extract(result)
                        } catch (e: Hbci4jRawMt940FieldMissingException) {
                            logger.error(
                                e,
                            ) { "Hbci4jFinTsClient.fetch: raw MT940 extraction failed for account=${credentials.bankAccountId}" }
                            return@runOnDedicatedThread FinTsFetchResult.Failed(FinTsErrorCode.PROTOCOL_ERROR)
                        }
                    FinTsFetchResult.Mt940(bytes = bytes, statementCount = result.flatData.size)
                } finally {
                    runCatching { handler.close() }
                }
            }
        } catch (e: Throwable) {
            val code = e.toFinTsErrorCode()
            logger.warn { "Hbci4jFinTsClient.fetch failed for account=${credentials.bankAccountId}: $code" }
            FinTsFetchResult.Failed(code)
        }
    }

    // ============================================================================================
    // FinTsSetupClient -- held ONLY by BankAccountService.
    // ============================================================================================

    override fun begin(credentials: FinTsCredentials): FinTsSetupOutcome {
        sweepExpiredHandles()
        if (handles.size >= MAX_CONCURRENT_HANDLES) {
            logger.warn { "Hbci4jFinTsClient.begin: MAX_CONCURRENT_HANDLES reached, refusing new setup dialog" }
            return FinTsSetupOutcome.Failed(FinTsErrorCode.BANK_UNAVAILABLE)
        }

        val handle = UUID.randomUUID().toString()
        val threadGroup = ThreadGroup("fints-setup-$handle")
        val executor = Executors.newSingleThreadExecutor(ThreadFactory { r -> Thread(threadGroup, r, "fints-setup-$handle") })
        val pending = PendingSetup(executor = executor, submissions = LinkedBlockingQueue(1), createdAt = Instant.now())
        handles[handle] = pending

        executor.submit {
            runSetupDialogOnDedicatedThread(handle = handle, credentials = credentials, pending = pending)
        }

        // Review fix (MAJOR): waits on `pending.currentRoundOutcome` -- see [PendingSetup] KDoc for
        // why this single rotating future (rather than the previous fixed firstOutcome/
        // firstCallbackLatch pair) is what makes a SECOND (third, ...) TAN round possible at all.
        return awaitRound(handle = handle, pending = pending, roundOutcome = pending.currentRoundOutcome.get(), operationName = "begin")
    }

    override fun submitTan(
        handle: String,
        tan: String,
    ): FinTsSetupOutcome {
        sweepExpiredHandles()
        val pending = handles[handle] ?: return FinTsSetupOutcome.Failed(FinTsErrorCode.TIMEOUT)
        // Review fix (MAJOR): install THIS round's future BEFORE offering the submission -- the
        // worker thread is parked on `submissions.take()` (see [runSetupDialogOnDedicatedThread])
        // and only reads `pending.currentRoundOutcome` again AFTER that take() returns, so swapping
        // it here first is race-free: the worker can never observe (and complete) the OLD future for
        // a NEW round's result. See [PendingSetup] KDoc.
        val roundOutcome = CompletableFuture<FinTsSetupOutcome>()
        pending.currentRoundOutcome.set(roundOutcome)
        // Review fix (MAJOR): the ADMIN acting on THIS round is activity -- see [PendingSetup
        // .lastActivityAt] KDoc. Touched here (not just on the worker thread once it reaches the
        // NEXT round) so a `sweepExpiredHandles` call racing in right after this line -- e.g. from
        // the periodic background sweeper -- already sees the handle as freshly active, not merely
        // as old as its previous round's start.
        pending.lastActivityAt.set(Instant.now())
        if (!pending.submissions.offer(TanSubmission.Submit(tan))) {
            // Review fix (MINOR, "Nebenbefund"): the queue is capacity-1 -- a genuine race with a
            // concurrent cancel()/another submitTan() on the SAME handle (a UI double-click, or a
            // cancel firing just as the TAN is submitted) could previously lose this offer()
            // silently, because its boolean return value was never checked. Now it is: an unaccepted
            // submission fails the request loudly instead of blocking submitTan() forever on a
            // future the worker thread will never complete.
            cleanupHandle(handle)
            logger.warn { "Hbci4jFinTsClient.submitTan: submissions queue rejected offer for handle=$handle (concurrent cancel?)" }
            return FinTsSetupOutcome.Failed(FinTsErrorCode.TIMEOUT)
        }
        return awaitRound(handle = handle, pending = pending, roundOutcome = roundOutcome, operationName = "submitTan")
    }

    /**
     * Shared by [begin] and [submitTan] -- both wait on exactly one round's [CompletableFuture],
     * cleaning up the handle unless the round's outcome is [FinTsSetupOutcome.TanRequested] (in
     * which case ANOTHER round is expected, so the handle -- and its dedicated worker thread -- must
     * stay alive).
     */
    private fun awaitRound(
        handle: String,
        pending: PendingSetup,
        roundOutcome: CompletableFuture<FinTsSetupOutcome>,
        operationName: String,
    ): FinTsSetupOutcome =
        try {
            val outcome = roundOutcome.get(config.dialogTimeoutSeconds, TimeUnit.SECONDS)
            if (outcome !is FinTsSetupOutcome.TanRequested) cleanupHandle(handle)
            outcome
        } catch (e: Throwable) {
            cleanupHandle(handle)
            val code = e.toFinTsErrorCode()
            logger.warn { "Hbci4jFinTsClient.$operationName failed for handle=$handle: $code" }
            FinTsSetupOutcome.Failed(code)
        }

    override fun cancel(handle: String) {
        val pending = handles[handle] ?: return
        pending.submissions.offer(TanSubmission.Cancel)
        cleanupHandle(handle)
    }

    private fun cleanupHandle(handle: String) {
        val pending = handles.remove(handle) ?: return
        pending.executor.shutdownNow()
    }

    /**
     * Called on every [begin]/[submitTan] entry (best-effort, as documented), and -- Review fix
     * (MINOR) -- ALSO on a periodic background timer via [startHandleSweeper], so an ADMIN who opens
     * the setup modal, receives a TAN prompt, and then simply closes the browser tab (no
     * `cancelFinTsSetup` call, see [network.lapis.cloud.server.rpc.BankAccountService.cancelFinTsSetup])
     * no longer leaves the plaintext-credential-holding worker thread parked indefinitely: the
     * handle is swept away by [HANDLE_TTL] regardless of whether any OTHER FinTS RPC ever arrives
     * again.
     *
     * `internal`, not `private` (Review fix, MEDIUM test coverage): [begin]/[submitTan] cannot be
     * driven through a real multi-round hbci4j dialog in a unit test (no fake bank server in this
     * codebase), so [Hbci4jFinTsClientHandleSweepTest] instead exercises THIS function directly,
     * via [putHandleForTesting]/[handleCountForTesting] below, against a synthetic [PendingSetup]
     * with a controlled [PendingSetup.lastActivityAt] -- the exact cutoff computation the MAJOR fix
     * above changed, real production code, no reimplementation in the test.
     */
    internal fun sweepExpiredHandles() {
        val cutoff = Instant.now().minusSeconds(HANDLE_TTL.inWholeSeconds)
        handles.entries
            // Review fix (MAJOR): keyed on the per-round [PendingSetup.lastActivityAt] (via
            // [isExpiredSince]), NOT the fixed [PendingSetup.createdAt] -- see that property's KDoc
            // for why a multi-round dialog would otherwise get swept out from under an ADMIN who is
            // still acting within the deadline the server itself most recently promised.
            .filter { it.value.isExpiredSince(cutoff) }
            .forEach { (handle, pending) ->
                pending.submissions.offer(TanSubmission.Cancel)
                handles.remove(handle)
                pending.executor.shutdownNow()
                logger.info { "Hbci4jFinTsClient: expired FinTS setup handle" }
            }
    }

    /**
     * Review fix (MINOR): a dedicated single-thread scheduler that calls [sweepExpiredHandles] every
     * [HANDLE_TTL] regardless of whether any [begin]/[submitTan] call ever arrives again for an
     * abandoned dialog -- see [sweepExpiredHandles] KDoc. Idempotent [start]/[stop], same shape as
     * [FinTsPoller]'s own -- wired from `Application.kt` next to `finTsPoller.start()`/`.stop()`.
     * `by lazy` -- every unit test that merely constructs an `Hbci4jFinTsClient` to call an
     * UNRELATED method (`passportFile()`, `fetch()`, ...) without ever calling [startHandleSweeper]
     * must not pay for a spun-up (if idle/daemon) thread pool it never uses.
     */
    private val sweeperExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fints-setup-handle-sweeper").apply { isDaemon = true } }
    }
    private var sweeperFuture: ScheduledFuture<*>? = null

    /** Idempotent -- a second call while already running is a no-op. */
    fun startHandleSweeper() {
        if (sweeperFuture != null) return
        sweeperFuture =
            sweeperExecutor.scheduleWithFixedDelay(
                { runCatching { sweepExpiredHandles() }.onFailure { logger.warn(it) { "Hbci4jFinTsClient: sweepExpiredHandles failed" } } },
                HANDLE_TTL.inWholeSeconds,
                HANDLE_TTL.inWholeSeconds,
                TimeUnit.SECONDS,
            )
    }

    /** Cancels the periodic sweep and releases its thread -- for tests/graceful shutdown. Not restartable after this (matches this codebase's other pollers' start-once/stop-once lifecycle -- see e.g. `FinTsPoller`/`SepaBatchPoller`). A no-op if [startHandleSweeper] was never called (does not force-initialize the lazy executor just to shut down something that was never started). */
    fun stopHandleSweeper() {
        val future = sweeperFuture ?: return
        future.cancel(false)
        sweeperFuture = null
        sweeperExecutor.shutdownNow()
    }

    /**
     * Runs entirely on the executor thread created in [begin]. Completes [PendingSetup
     * .currentRoundOutcome]'s CURRENT future the moment either a TAN is requested OR the dialog
     * finishes without/after one -- [begin]/[submitTan] each block on exactly the future that was
     * current at the moment THEY were called, never on the dialog's eventual full completion. Review
     * fix (MAJOR): this loop can now run through this exact sequence more than once per dialog --
     * before the fix, a SECOND callback round (e.g. `NEED_PT_TANMEDIA` immediately followed by
     * `NEED_PT_TAN`) had no future left to complete (the old single firstOutcome/firstCallbackLatch
     * pair could only ever fire once), so [submitTan] could only time out -- see this class's own
     * `is FinTsSetupOutcome.TanRequested ->` case in
     * [network.lapis.cloud.server.rpc.BankAccountService.submitFinTsTan] for the caller-side branch
     * that used to be unreachable dead code as a result.
     */
    private fun runSetupDialogOnDedicatedThread(
        handle: String,
        credentials: FinTsCredentials,
        pending: PendingSetup,
    ) {
        var wrongPin = false
        var bankPrompt = ""
        val callback =
            object : AbstractHBCICallback() {
                override fun log(
                    msg: String?,
                    level: Int,
                    date: java.util.Date?,
                    trace: StackTraceElement?,
                ) = Unit

                override fun callback(
                    passport: HBCIPassport?,
                    reason: Int,
                    msg: String?,
                    dataType: Int,
                    retData: StringBuffer?,
                ) {
                    when (reason) {
                        HBCICallback.NEED_BLZ -> retData?.fill(credentials.blz)
                        HBCICallback.NEED_USERID, HBCICallback.NEED_CUSTOMERID -> retData?.fill(credentials.userId)
                        HBCICallback.NEED_PT_PIN, HBCICallback.NEED_PASSPHRASE_LOAD, HBCICallback.NEED_PASSPHRASE_SAVE ->
                            retData?.fill(credentials.pin)
                        HBCICallback.NEED_FILTER, HBCICallback.NEED_PT_SECMECH -> retData?.fill("")
                        HBCICallback.NEED_PT_TAN,
                        HBCICallback.NEED_PT_TANMEDIA,
                        HBCICallback.NEED_PT_PHOTOTAN,
                        HBCICallback.NEED_PT_QRTAN,
                        -> bankPrompt = msg.orEmpty().take(MAX_BANK_PROMPT_LENGTH)
                        HBCICallback.WRONG_PIN -> wrongPin = true
                        else -> Unit
                    }
                }

                override fun status(
                    passport: HBCIPassport?,
                    statusTag: Int,
                    o: Array<Any?>?,
                ) = Unit

                override fun useThreadedCallback(
                    passport: HBCIPassport?,
                    reason: Int,
                    msg: String?,
                    dataType: Int,
                    retData: StringBuffer?,
                ): Boolean =
                    reason == HBCICallback.NEED_PT_TAN ||
                        reason == HBCICallback.NEED_PT_TANMEDIA ||
                        reason == HBCICallback.NEED_PT_PHOTOTAN ||
                        reason == HBCICallback.NEED_PT_QRTAN
            }

        val props = hbciInitProperties()
        // Review fix (CRITICAL, Runde 4): wrap the raw callback in `HBCICallbackThreaded` -- see
        // class KDoc "Threaded callback hand-off" fix (2) for the full bytecode-verified reasoning.
        // Without this wrapper, `HBCIExecThreadedStatus.isCallback()` is structurally always false,
        // `driveThreadedCallbackLoop` below breaks out on the FIRST iteration of every phase, and a
        // TAN-shaped bank reason falls through to the RAW callback -- which records `bankPrompt` but
        // leaves `retData` empty, sending an EMPTY TAN to the bank.
        HBCIUtils.initThread(props, HBCICallbackThreaded(callback))
        var handler: HBCIHandler? = null
        try {
            configurePassportParams(credentials)
            val passport = AbstractHBCIPassport.getInstance(passportFile(credentials.bankAccountId.toString()))
            passport.setCountry("DE")
            passport.setHost(credentials.url)
            passport.setPort(FINTS_DEFAULT_PORT)
            passport.setFilterType("Base64")

            // Review fix (CRITICAL, Runde 4): the 3-argument constructor with `threaded = true` --
            // the 2-argument overload bytecode-delegates to this same constructor with `threaded =
            // false`, which (a) makes `HBCICallbackThreaded` a no-op even if the wrap above were
            // missing, since threaded mode is never entered at all, and (b) runs
            // `registerInstitute()`/`registerUser()` SYNCHRONOUSLY in the constructor instead of on
            // `initThreaded()`'s own worker thread -- see class KDoc for why (b) is what forces
            // `newJob("SaldoReq")` below to move to AFTER the init-phase loop completes.
            handler = HBCIHandler(HBCIVersion.HBCI_300.id, passport, true)

            // Review fix (CRITICAL, kept from Runde 3): `initThreaded()`'s dedicated worker thread
            // (hbci4j's `HBCIHandler$1`) NEVER calls `HBCIHandler.execute()` -- only
            // `executeThreaded()`'s OWN worker thread (`HBCIHandler$2`) does, and syncs a real,
            // non-null `HBCIExecStatus`. Reading `threadedStatus.execStatus` after only the
            // init-phase loop was therefore reading a value that is always null by construction. Fix:
            // drive the SAME callback-round loop TWICE -- once for `initThreaded()`, once for
            // `executeThreaded()` -- and only evaluate `execStatus` after the SECOND loop finishes.
            val initStatus =
                driveThreadedCallbackLoop(
                    initial = handler.initThreaded(),
                    handler = handler,
                    handle = handle,
                    pending = pending,
                    bankPrompt = { bankPrompt },
                )
            if (initStatus == null) {
                // ADMIN cancelled mid-init -- driveThreadedCallbackLoop already completed the
                // current round's future with Failed(TAN_REQUIRED).
                return
            }

            val execStatus =
                if (wrongPin) {
                    // The bank already rejected the PIN during init (WRONG_PIN callback) -- no point
                    // starting the real execute phase, matches this class's pre-fix behaviour for
                    // this specific case (execute() was never reachable for it either way).
                    null
                } else {
                    // Review fix (CRITICAL, Runde 4): queuing the job here, only AFTER the init-phase
                    // loop above has returned (i.e. `registerInstitute()`/`registerUser()` have
                    // already run on the worker thread and BPD is populated), not right after
                    // construction -- see the `HBCIHandler(..., true)` comment above for why moving
                    // this earlier would make `newJob` fail: it reflectively builds the GV and reads
                    // job restrictions from BPD, which is empty before `registerInstitute()` runs.
                    val account = accountFor(credentials = credentials, passport = passport)
                    val job = handler.newJob("SaldoReq")
                    job.setParam("my", account)
                    job.addToQueue()

                    val executeStatus =
                        driveThreadedCallbackLoop(
                            initial = handler.executeThreaded(),
                            handler = handler,
                            handle = handle,
                            pending = pending,
                            bankPrompt = { bankPrompt },
                        )
                    if (executeStatus == null) return
                    executeStatus.execStatus
                }

            val outcome =
                if (wrongPin) {
                    FinTsSetupOutcome.Failed(FinTsErrorCode.AUTH_FAILED)
                } else if (execStatus != null && execStatus.isOK) {
                    FinTsSetupOutcome.Verified(credentials = credentials, statementCount = 0)
                } else {
                    FinTsSetupOutcome.Failed(FinTsErrorCode.PROTOCOL_ERROR)
                }
            pending.currentRoundOutcome.get().complete(outcome)
        } catch (e: Throwable) {
            val code = if (wrongPin) FinTsErrorCode.AUTH_FAILED else e.toFinTsErrorCode()
            logger.warn { "Hbci4jFinTsClient.begin (dedicated thread) failed for account=${credentials.bankAccountId}: $code" }
            pending.currentRoundOutcome.get().complete(FinTsSetupOutcome.Failed(code))
        } finally {
            runCatching { handler?.close() }
            HBCIUtils.doneThread()
        }
    }

    /**
     * Drives ONE threaded-mode phase (either the init phase from `handler.initThreaded()`, or the
     * execute phase from `handler.executeThreaded()` -- see [runSetupDialogOnDedicatedThread] KDoc
     * "Review fix (CRITICAL)") to completion, handling as many callback rounds as the bank requires
     * within that phase. Every round touches [PendingSetup.lastActivityAt], completes [PendingSetup
     * .currentRoundOutcome]'s CURRENT future with [FinTsSetupOutcome.TanRequested], and blocks on
     * [PendingSetup.submissions] for the next TAN (or a cancel) -- identical shape for both phases,
     * because `continueThreaded(String)` reads/writes the SAME `thread_syncer_main` regardless of
     * which phase's worker thread is currently running (verified against the real hbci4j-core:4.0.0
     * bytecode: `continueThreaded` itself never branches on which phase is active).
     *
     * Returns the phase's final [HBCIExecThreadedStatus] once it is finished (`isFinished`) or the
     * loop breaks because there is no further callback pending -- OR `null` if the ADMIN cancelled,
     * in which case this function has ALREADY completed the current round's future with
     * [FinTsSetupOutcome.Failed]; the caller must return immediately without completing it again.
     */
    private fun driveThreadedCallbackLoop(
        initial: HBCIExecThreadedStatus,
        handler: HBCIHandler,
        handle: String,
        pending: PendingSetup,
        bankPrompt: () -> String,
    ): HBCIExecThreadedStatus? {
        var threadedStatus = initial
        while (!threadedStatus.isFinished) {
            if (!threadedStatus.isCallback) break
            // Review fix (MAJOR, kept from round 1): touch [PendingSetup.lastActivityAt] at the
            // exact moment this round's deadline is computed below -- so the sweeper's cutoff always
            // matches what `expiresAt` just promised the caller, instead of staying frozen at
            // [PendingSetup.createdAt].
            pending.lastActivityAt.set(Instant.now())
            // Review fix (MAJOR, kept from round 1): complete THIS round's current future -- by the
            // time this line runs on a SECOND+ round, `submitTan` has already installed a fresh one
            // (see its own KDoc for why that ordering is race-free), so this always targets the
            // future the caller of THIS round is actually waiting on, never a round that already
            // finished.
            pending.currentRoundOutcome.get().complete(
                FinTsSetupOutcome.TanRequested(
                    handle = handle,
                    bankPrompt = bankPrompt().ifBlank { "Bitte TAN eingeben." },
                    expiresAt = nowPlusHandleTtl(),
                ),
            )
            when (val submission = pending.submissions.take()) {
                is TanSubmission.Cancel -> {
                    pending.currentRoundOutcome.get().complete(FinTsSetupOutcome.Failed(FinTsErrorCode.TAN_REQUIRED))
                    return null
                }
                is TanSubmission.Submit -> threadedStatus = handler.continueThreaded(submission.tan)
            }
        }
        return threadedStatus
    }

    /** Every hbci4j operation for [fetch] runs here -- see class KDoc "Thread-lokale Initialisierung". */
    private fun <T> runOnDedicatedThread(
        operationName: String,
        credentials: FinTsCredentials,
        callback: HBCICallback,
        block: (HBCIPassport) -> T,
    ): T {
        val threadGroup = ThreadGroup("fints-$operationName-${credentials.bankAccountId}")
        val executor =
            Executors.newSingleThreadExecutor(
                ThreadFactory { r ->
                    Thread(threadGroup, r, "fints-$operationName-${credentials.bankAccountId}")
                },
            )
        return try {
            executor
                .submit<T> {
                    val props = hbciInitProperties()
                    HBCIUtils.initThread(props, callback)
                    try {
                        configurePassportParams(credentials)
                        val passport = AbstractHBCIPassport.getInstance(passportFile(credentials.bankAccountId.toString()))
                        passport.setCountry("DE")
                        passport.setHost(credentials.url)
                        passport.setPort(FINTS_DEFAULT_PORT)
                        passport.setFilterType("Base64")
                        block(passport)
                    } finally {
                        HBCIUtils.doneThread()
                    }
                }.get(config.dialogTimeoutSeconds, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    /** [FinTsStatementFetcher] path -- every TAN-shaped reason aborts (empty answer, no threaded hand-off), see class KDoc "TAN-Behandlung". */
    private fun readOnlyCallback(
        credentials: FinTsCredentials,
        onTanRequested: () -> Unit,
    ): HBCICallback =
        object : AbstractHBCICallback() {
            override fun log(
                msg: String?,
                level: Int,
                date: java.util.Date?,
                trace: StackTraceElement?,
            ) = Unit

            override fun callback(
                passport: HBCIPassport?,
                reason: Int,
                msg: String?,
                dataType: Int,
                retData: StringBuffer?,
            ) {
                when (reason) {
                    HBCICallback.NEED_BLZ -> retData?.fill(credentials.blz)
                    HBCICallback.NEED_USERID, HBCICallback.NEED_CUSTOMERID -> retData?.fill(credentials.userId)
                    HBCICallback.NEED_PT_PIN, HBCICallback.NEED_PASSPHRASE_LOAD, HBCICallback.NEED_PASSPHRASE_SAVE ->
                        retData?.fill(credentials.pin)
                    HBCICallback.NEED_FILTER, HBCICallback.NEED_PT_SECMECH -> retData?.fill("")
                    HBCICallback.NEED_PT_TAN, HBCICallback.NEED_PT_TANMEDIA, HBCICallback.NEED_PT_PHOTOTAN, HBCICallback.NEED_PT_QRTAN ->
                        onTanRequested()
                    else -> Unit
                }
            }

            override fun status(
                passport: HBCIPassport?,
                statusTag: Int,
                o: Array<Any?>?,
            ) = Unit

            override fun useThreadedCallback(
                passport: HBCIPassport?,
                reason: Int,
                msg: String?,
                dataType: Int,
                retData: StringBuffer?,
            ): Boolean = false
        }

    /** `client.passport.default`/`client.passport.PinTan.init`/`.filename` -- set via [HBCIUtils.setParam], matching the bundled example, BEFORE [AbstractHBCIPassport.getInstance]. */
    private fun configurePassportParams(credentials: FinTsCredentials) {
        HBCIUtils.setParam("client.passport.default", "PinTan")
        HBCIUtils.setParam("client.passport.PinTan.init", "1")
        HBCIUtils.setParam("client.passport.PinTan.filename", passportFile(credentials.bankAccountId.toString()).absolutePath)
        HBCIUtils.setParam("client.passport.PinTan.checkcert", "1")
    }

    private fun hbciInitProperties(): Properties =
        Properties().apply {
            setProperty("log.loglevel.default", HBCIUtils.LOG_NONE.toString())
        }

    private fun accountFor(
        credentials: FinTsCredentials,
        passport: HBCIPassport,
    ): Konto {
        val fromPassport = runCatching { passport.getAccount(credentials.iban) }.getOrNull()
        if (fromPassport != null) return fromPassport
        return Konto("DE", credentials.blz).apply { iban = credentials.iban }
    }

    /**
     * Review fix (MINOR): `internal` rather than `private` so [FinTsPassportFileTest] can exercise
     * THIS function directly instead of re-implementing (and therefore only re-verifying) its own
     * copy of the `mkdirs()`/`setPosixFilePermissions` calls -- see that test's own KDoc for the gap
     * this closes.
     */
    internal fun passportFile(bankAccountId: String): File {
        val dir = File(config.passportDir)
        if (!dir.exists()) dir.mkdirs()
        // Review fix (MINOR): narrow the directory's permissions on EVERY call, not only right after
        // dir.mkdirs() -- a LAPIS_FINTS_PASSPORT_DIR pre-provisioned from outside this process (e.g.
        // by deployment tooling under a permissive umask) previously stayed at whatever permissions
        // it already had forever, because this call only ran inside the `!dir.exists()` branch.
        runCatching {
            Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        val file = File(dir, "$bankAccountId.passport")
        if (!file.exists()) {
            runCatching { file.createNewFile() }
        }
        runCatching { Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        return file
    }

    private fun nowPlusHandleTtl(): LocalDateTime =
        (DbClock.nowLocalDateTime().toInstant(TimeZone.UTC) + HANDLE_TTL).toLocalDateTime(TimeZone.UTC)

    private companion object {
        const val MAX_BANK_PROMPT_LENGTH = 500
        const val FINTS_DEFAULT_PORT = 443
    }
}

/**
 * One open TAN dialog -- see [Hbci4jFinTsClient] KDoc "Handle-Tabelle".
 *
 * Review fix (MAJOR): [currentRoundOutcome] replaces the previous fixed `firstCallbackLatch`/
 * `firstOutcome`/`finalOutcome` trio, which could only ever signal ONE callback round -- a bank
 * requiring a SECOND round (e.g. `NEED_PT_TANMEDIA` then `NEED_PT_TAN`) had no future left to
 * complete, so [Hbci4jFinTsClient.submitTan] could only time out. [currentRoundOutcome] instead
 * holds "the future the caller of the MOST RECENT [Hbci4jFinTsClient.begin]/
 * [Hbci4jFinTsClient.submitTan] call is waiting on" -- `submitTan` installs a FRESH one before
 * offering its submission (race-free: the dedicated worker thread only re-reads this reference
 * AFTER it has taken that submission off the queue, i.e. strictly after the swap), and
 * [Hbci4jFinTsClient.runSetupDialogOnDedicatedThread] always completes whichever future is
 * currently installed -- whether that is another [FinTsSetupOutcome.TanRequested] (another round
 * follows) or a final [FinTsSetupOutcome.Verified]/[FinTsSetupOutcome.Failed].
 *
 * `internal`, not `private` (Review fix, MEDIUM test coverage): see [Hbci4jFinTsClient
 * .sweepExpiredHandles] KDoc for why [Hbci4jFinTsClientHandleSweepTest] needs to construct one of
 * these directly.
 */
internal class PendingSetup(
    val executor: ExecutorService,
    val submissions: LinkedBlockingQueue<TanSubmission>,
    val createdAt: Instant,
) {
    val currentRoundOutcome = AtomicReference(CompletableFuture<FinTsSetupOutcome>())

    /**
     * Review fix (MAJOR, follow-up to the multi-round fix documented above): [sweepExpiredHandles]
     * used to key off [createdAt] alone, which stays frozen at the FIRST round's start even though
     * every subsequent [FinTsSetupOutcome.TanRequested] promises the ADMIN a FRESH `now +
     * HANDLE_TTL` deadline via [Hbci4jFinTsClient.nowPlusHandleTtl] -- a bank that requires TAN-
     * medium selection THEN a TAN (two rounds) could display "valid until t+450" while the sweeper
     * (still keyed on t+300) silently killed the handle out from under an ADMIN who acted well
     * inside the deadline the server itself had just shown them. Touched every time a new round
     * starts on the dedicated worker thread (right before promising the next deadline) and every
     * time [Hbci4jFinTsClient.submitTan] accepts a submission for this handle, so the sweep
     * deadline always matches what was actually promised to the caller.
     */
    val lastActivityAt = AtomicReference(createdAt)
}

/** `true` iff no round has been active since before [cutoff] -- see [PendingSetup.lastActivityAt] KDoc. */
private fun PendingSetup.isExpiredSince(cutoff: Instant): Boolean = lastActivityAt.get().isBefore(cutoff)

/** `internal`, not `private` -- see [PendingSetup] KDoc (needed for its `submissions` field's type). */
internal sealed interface TanSubmission {
    data class Submit(
        val tan: String,
    ) : TanSubmission

    data object Cancel : TanSubmission
}

private fun StringBuffer.fill(value: String) {
    replace(0, length, value)
}

private fun LocalDate.toJavaUtilDate(): java.util.Date = GregorianCalendar(year, monthNumber - 1, dayOfMonth).time

/**
 * Central error-mapping function -- the ONLY place [Throwable.message] is ever inspected in this
 * package, and even then only to CLASSIFY, never to propagate the text itself. See
 * [Hbci4jFinTsClient] KDoc "Fehler-Mapping".
 */
internal fun Throwable.toFinTsErrorCode(): FinTsErrorCode {
    // Review fix (MEDIUM): `runOnDedicatedThread`'s `executor.submit<T> { ... }.get(timeout)` (the
    // ONLY caller whose failures can arrive here as something other than the exception the worker
    // itself threw) wraps EVERY exception the submitted block throws in a `java.util.concurrent.
    // ExecutionException` -- without unwrapping it first, NONE of the `is`-branches below could ever
    // match a `fetch()`-path failure (a bank-unreachable `ConnectException`, a `SocketTimeoutException`,
    // ...), and every single one of them silently fell through to the `else -> PROTOCOL_ERROR`
    // catch-all. Only `Future.get(timeout)`'s OWN `TimeoutException` -- thrown directly, never
    // wrapped -- was ever mapped correctly before this fix.
    val cause = if (this is java.util.concurrent.ExecutionException) (this.cause ?: this) else this
    return when (cause) {
        is java.util.concurrent.TimeoutException -> FinTsErrorCode.TIMEOUT
        is java.net.SocketTimeoutException -> FinTsErrorCode.TIMEOUT
        is java.net.ConnectException -> FinTsErrorCode.BANK_UNAVAILABLE
        is java.net.UnknownHostException -> FinTsErrorCode.BANK_UNAVAILABLE
        is java.io.IOException -> FinTsErrorCode.BANK_UNAVAILABLE
        is Hbci4jRawMt940FieldMissingException -> FinTsErrorCode.PROTOCOL_ERROR
        else -> FinTsErrorCode.PROTOCOL_ERROR
    }
}
