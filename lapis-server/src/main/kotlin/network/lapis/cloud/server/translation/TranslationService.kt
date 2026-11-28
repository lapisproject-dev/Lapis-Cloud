package network.lapis.cloud.server.translation

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.ai.llm.LlmClient
import network.lapis.cloud.server.ai.llm.LlmRequest
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.translation.config.TranslateConfig
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The answer of [TranslationService.translateEvents]: one outcome per requested (distinct, known or unknown) event id, plus the content language. */
internal data class TranslationBatch(
    val sourceLang: String,
    val outcomes: Map<Uuid, TranslationOutcome>,
)

/**
 * Just-in-time translation of the public text of an event (Welle V1.9.342 T1: the core; V1.9.343 T2: the embed events feed is the one caller of [translateEvents]; no UI, off by default).
 *
 * Contract of [translateEvent]: it NEVER throws for an operational reason and ALWAYS answers with a complete set of fields -- the translation
 * or the original, never a mixture. A request waits at most [TranslateConfig.waitTimeoutMs]; a slower job keeps running in the service's own
 * scope and the NEXT request finds its result (the answer of the slow request is the original with `translationPending`).
 *
 * Guarantees, in the order a reviewer should check them:
 *  1. **Nothing leaves the server unless** the feature is operational, the event is public (see [EventTranslationEligibility]) and the author
 *     consented (`event.translation_consent`). Only the four text fields are sent, never place, price, date or link.
 *  2. **Single flight**: any number of concurrent requests for the same (content, language pair, source hashes) share ONE model call.
 *  3. **Re-check at write time**: the job stores its result only if, under the event row lock, the event is still public, the consent is still
 *     given with the SAME decision timestamp (the "generation"), the text hashes are unchanged and the source language is unchanged. A revoke
 *     (also revoke + re-grant) therefore always wins over a running job. [cancelInFlight] is only a best-effort shortcut.
 *  4. **Cost bounds**: a daily character budget, a per-content size cap, a semaphore around the model call, a bounded number of distinct
 *     in-flight jobs, and a cooldown after a failure. There is NO automatic retry: only a later request starts the next attempt.
 *  5. **No model output is trusted**: [TranslationValidator] discards (never repairs) a doubtful answer.
 *
 * Provider HTTP never runs inside a database transaction (V1.9.55 rule).
 */
internal class TranslationService(
    private val config: TranslateConfig,
    private val llm: LlmClient?,
    private val budget: TranslationDailyBudget = TranslationDailyBudget(dailyChars = config.dailyCharBudget),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Instant = { Clock.System.now() },
) : AutoCloseable {
    private data class FlightKey(
        val type: TranslatableContentType,
        val id: Uuid,
        val sourceLang: String,
        val targetLang: String,
        val hashes: Map<TranslatableField, String>,
    )

    private sealed interface JobResult {
        data class Translated(
            val fields: Map<TranslatableField, String>,
        ) : JobResult

        data class Failed(
            val reason: String,
        ) : JobResult

        data object Stale : JobResult
    }

    private class Snapshot(
        val isPublic: Boolean,
        val consent: Boolean,
        val consentAt: LocalDateTime?,
        val sourceLang: String,
        val fields: Map<TranslatableField, String>,
        val hashes: Map<TranslatableField, String>,
    )

    private val inFlight = HashMap<FlightKey, Deferred<JobResult>>()
    private val flightLock = Any()
    private val cooldownUntil = ConcurrentHashMap<FlightKey, Instant>()
    private val callPermits = Semaphore(config.maxConcurrentCalls)
    private val modelProfile: String =
        "${config.provider?.auditName ?: "none"}:${config.model ?: "none"}".take(MODEL_PROFILE_MAX)

    private val operational: Boolean get() = llm != null && config.isOperational

    /** `true` iff a translation could be started at all (feature on, provider complete). */
    val isOperational: Boolean get() = operational

    suspend fun translateEvent(
        eventId: Uuid,
        preferences: List<String>,
    ): TranslationOutcome {
        val requested = TranslationLanguages.resolveTarget(preferences)
        val snapshot = withContext(Dispatchers.IO) { transaction { loadSnapshot(eventId) } }

        fun original(state: TranslationState) = originalOutcome(snapshot = snapshot, requested = requested, state = state)
        decide(snapshot = snapshot, requested = requested)?.let { return original(it) }
        snapshot ?: return original(TranslationState.NOT_FOUND)
        val cached =
            withContext(Dispatchers.IO) {
                transaction {
                    ContentTranslationStore.findAll(
                        type = TranslatableContentType.EVENT,
                        id = eventId,
                        sourceLang = snapshot.sourceLang,
                        targetLang = requested,
                        hashes = snapshot.hashes,
                    )
                }
            }
        val translatedOutcome = { translations: Map<TranslatableField, String> ->
            TranslationOutcome(
                deliveredLang = requested,
                sourceLang = snapshot.sourceLang,
                requestedLang = requested,
                state = TranslationState.TRANSLATED,
                fields = snapshot.fields + translations,
            )
        }
        val missing = snapshot.hashes.filterKeys { it !in cached }
        if (missing.isEmpty()) return translatedOutcome(cached)

        val key =
            FlightKey(
                type = TranslatableContentType.EVENT,
                id = eventId,
                sourceLang = snapshot.sourceLang,
                targetLang = requested,
                hashes = missing,
            )
        cooldownUntil[key]?.let { until ->
            if (now() <
                until
            ) {
                return original(TranslationState.PENDING)
            } else {
                cooldownUntil.remove(key, until)
            }
        }
        val job = startOrJoin(key = key, snapshot = snapshot, allowCreate = true) ?: return original(TranslationState.PENDING)
        val awaited = awaitJob(job = job, timeoutMs = config.waitTimeoutMs)
        return when (val result = awaited) {
            is JobResult.Translated -> translatedOutcome(cached + result.fields)
            else -> original(TranslationState.PENDING)
        }
    }

    /** The language the public content is written in (one settings read). */
    suspend fun contentLanguage(): String = withContext(Dispatchers.IO) { transaction { loadSourceLang() } }

    /**
     * The batch form for feeds (T2). [target] must be one of [TranslationLanguages.SUPPORTED]. One read for all snapshots and the content
     * language, ONE store query for all cache hits. [maySpend] is asked at most ONCE per call, and only right before the first NEW job would
     * be started (never for a cache hit, a joined job or a cooling-down entry); `false` leaves the affected entries pending without a job. At
     * most [maxNewJobs] new jobs start per call, the rest stays pending. All jobs of the call share ONE wait budget of
     * [TranslateConfig.waitTimeoutMs]. Not operational: only the content language is read, every entry is [TranslationState.DISABLED].
     * Same decision rules as [translateEvent] (one private [decide]); the model call stays at its one place under the semaphore.
     */
    suspend fun translateEvents(
        eventIds: List<Uuid>,
        target: String,
        maySpend: () -> Boolean,
        maxNewJobs: Int = MAX_NEW_JOBS_PER_CALL,
    ): TranslationBatch {
        require(target in TranslationLanguages.SUPPORTED) { "unsupported target language" }
        val ids = eventIds.distinct()
        if (!operational) {
            val source = contentLanguage()
            return TranslationBatch(
                sourceLang = source,
                outcomes =
                    ids.associateWith {
                        TranslationOutcome(
                            deliveredLang = source,
                            sourceLang = source,
                            requestedLang = target,
                            state = TranslationState.DISABLED,
                            fields = emptyMap(),
                        )
                    },
            )
        }
        val (sourceLang, snapshots) = withContext(Dispatchers.IO) { transaction { loadSnapshots(ids) } }
        val outcomes = LinkedHashMap<Uuid, TranslationOutcome>()
        val candidates = LinkedHashMap<Uuid, Snapshot>()
        for (id in ids) {
            val snapshot = snapshots[id]
            val state = decide(snapshot = snapshot, requested = target)
            if (state != null) {
                outcomes[id] = originalOutcome(snapshot = snapshot, requested = target, state = state, sourceFallback = sourceLang)
            } else {
                candidates[id] = checkNotNull(snapshot)
            }
        }
        val cached =
            if (candidates.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    transaction {
                        ContentTranslationStore.findAllForMany(
                            type = TranslatableContentType.EVENT,
                            hashesById = candidates.mapValues { it.value.hashes },
                            sourceLang = sourceLang,
                            targetLang = target,
                        )
                    }
                }
            }

        fun translated(
            snapshot: Snapshot,
            translations: Map<TranslatableField, String>,
        ) = TranslationOutcome(
            deliveredLang = target,
            sourceLang = sourceLang,
            requestedLang = target,
            state = TranslationState.TRANSLATED,
            fields = snapshot.fields + translations,
        )

        val jobs = LinkedHashMap<Uuid, Deferred<JobResult>>()
        var started = 0
        var spendAnswer: Boolean? = null
        for ((id, snapshot) in candidates) {
            val have = cached[id] ?: emptyMap()
            val missing = snapshot.hashes.filterKeys { it !in have }
            if (missing.isEmpty()) {
                outcomes[id] = translated(snapshot, have)
                continue
            }
            val key =
                FlightKey(
                    type = TranslatableContentType.EVENT,
                    id = id,
                    sourceLang = sourceLang,
                    targetLang = target,
                    hashes = missing,
                )
            val pending = originalOutcome(snapshot = snapshot, requested = target, state = TranslationState.PENDING)
            val cooldown = cooldownUntil[key]
            if (cooldown != null) {
                if (now() < cooldown) {
                    outcomes[id] = pending
                    continue
                }
                cooldownUntil.remove(key, cooldown)
            }
            // Joining a running job costs nothing; only a NEW job asks the spend question (outside the flight lock).
            val running = startOrJoin(key = key, snapshot = snapshot, allowCreate = false)
            if (running != null) {
                jobs[id] = running
                continue
            }
            if (started >= maxNewJobs) {
                outcomes[id] = pending
                continue
            }
            val allowed = spendAnswer ?: maySpend().also { spendAnswer = it }
            val job = if (allowed) startOrJoin(key = key, snapshot = snapshot, allowCreate = true) else null
            if (job == null) {
                outcomes[id] = pending
                continue
            }
            started++
            jobs[id] = job
        }
        // ONE wait budget for the whole call, not one per entry.
        val deadline = TimeSource.Monotonic.markNow() + config.waitTimeoutMs.milliseconds
        for ((id, job) in jobs) {
            val snapshot = candidates.getValue(id)
            val remainingMs = (-deadline.elapsedNow()).inWholeMilliseconds
            val result = awaitJob(job = job, timeoutMs = remainingMs)
            outcomes[id] =
                if (result is JobResult.Translated) {
                    translated(snapshot, (cached[id] ?: emptyMap()) + result.fields)
                } else {
                    originalOutcome(snapshot = snapshot, requested = target, state = TranslationState.PENDING)
                }
        }
        return TranslationBatch(sourceLang = sourceLang, outcomes = ids.associateWith { outcomes.getValue(it) })
    }

    /**
     * The terminal state of the shared decision rules, or `null` when the content may be translated (cache lookup / job). ONE place for
     * [translateEvent] and [translateEvents], so the rules cannot drift apart.
     */
    private fun decide(
        snapshot: Snapshot?,
        requested: String,
    ): TranslationState? =
        when {
            snapshot == null -> TranslationState.NOT_FOUND
            !snapshot.isPublic -> TranslationState.NOT_PUBLIC
            requested == snapshot.sourceLang -> TranslationState.SAME_LANGUAGE
            !snapshot.consent -> TranslationState.NO_CONSENT
            !operational -> TranslationState.DISABLED
            snapshot.hashes.isEmpty() -> TranslationState.SAME_LANGUAGE
            else -> null
        }

    private fun originalOutcome(
        snapshot: Snapshot?,
        requested: String,
        state: TranslationState,
        sourceFallback: String = TranslationLanguages.normalizeSource(null),
    ): TranslationOutcome {
        val source = snapshot?.sourceLang ?: sourceFallback
        return TranslationOutcome(
            deliveredLang = source,
            sourceLang = source,
            requestedLang = requested,
            state = state,
            fields = if (state == TranslationState.NOT_PUBLIC) emptyMap() else snapshot?.fields ?: emptyMap(),
        )
    }

    /**
     * Waits for [job] at most [timeoutMs]. The job belongs to the service scope: a timeout or cancellation of THIS request only stops the
     * waiting, never the job. `null` = no usable result (slow, cancelled job).
     */
    private suspend fun awaitJob(
        job: Deferred<JobResult>,
        timeoutMs: Long,
    ): JobResult? =
        try {
            if (job.isCompleted) job.await() else withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            // Either THIS request was cancelled (then it must stop) or only the job was (cancelInFlight): the answer is "pending".
            currentCoroutineContext().ensureActive()
            null
        }

    /**
     * Best effort: cancels running jobs of this content. The real guarantee against a late write is the re-check under the row lock (see class
     * KDoc, point 3).
     */
    fun cancelInFlight(
        type: TranslatableContentType,
        id: Uuid,
    ) {
        val victims = synchronized(flightLock) { inFlight.filterKeys { it.type == type && it.id == id }.values.toList() }
        victims.forEach { it.cancel() }
    }

    override fun close() {
        scope.cancel()
    }

    private fun startOrJoin(
        key: FlightKey,
        snapshot: Snapshot,
        allowCreate: Boolean,
    ): Deferred<JobResult>? {
        var created: Deferred<JobResult>? = null
        val deferred =
            synchronized(flightLock) {
                inFlight[key] ?: run {
                    if (!allowCreate || inFlight.size >= MAX_IN_FLIGHT) return@synchronized null
                    val job = scope.async(start = CoroutineStart.LAZY) { runJob(key = key, snapshot = snapshot) }
                    inFlight[key] = job
                    job.invokeOnCompletion { synchronized(flightLock) { inFlight.remove(key, job) } }
                    created = job
                    job
                }
            }
        created?.start()
        return deferred
    }

    private suspend fun runJob(
        key: FlightKey,
        snapshot: Snapshot,
    ): JobResult {
        val started = now()
        val sources = key.hashes.keys.associateWith { snapshot.fields.getValue(it) }
        val chars = sources.values.sumOf { it.length }
        val result =
            try {
                execute(key = key, snapshot = snapshot, sources = sources, chars = chars)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JobResult.Failed("internal")
            }
        if (result is JobResult.Failed) {
            recordCooldown(
                key = key,
                duration = if (result.reason == "budget") BUDGET_COOLDOWN else FAILURE_COOLDOWN,
            )
        }
        val millis = (now() - started).inWholeMilliseconds
        logger.info { "translation event fields=${sources.size} chars=$chars result=${result::class.simpleName} ms=$millis" }
        return result
    }

    private suspend fun execute(
        key: FlightKey,
        snapshot: Snapshot,
        sources: Map<TranslatableField, String>,
        chars: Int,
    ): JobResult {
        val client = llm ?: return JobResult.Failed("disabled")
        // A request that missed the cache just before an identical job finished (or another instance stored the rows) must not call the model again.
        val already =
            withContext(Dispatchers.IO) {
                transaction {
                    ContentTranslationStore.findAll(
                        type = key.type,
                        id = key.id,
                        sourceLang = key.sourceLang,
                        targetLang = key.targetLang,
                        hashes = key.hashes,
                    )
                }
            }
        if (already.keys == key.hashes.keys) return JobResult.Translated(already)
        if (chars > config.maxSourceCharsPerContent) return JobResult.Failed("too-large")
        if (!budget.tryReserve(chars)) return JobResult.Failed("budget")

        val masked =
            sources.mapValues { (_, text) ->
                TranslationValidator.SourceField(
                    original = text,
                    masked = LinkPlaceholders.mask(text),
                )
            }
        val request =
            LlmRequest(
                systemPrompt = TranslationPrompt.systemPrompt(sourceLang = key.sourceLang, targetLang = key.targetLang),
                userContent = TranslationPrompt.userContent(masked.mapValues { it.value.masked.text }),
                maxOutputTokens = TranslationPrompt.maxOutputTokens(configMax = config.maxOutputTokens, sourceChars = chars),
            )
        // Consent, visibility and hashes are re-checked right before the text leaves the server: a job may have waited minutes for a permit.
        val answer =
            callPermits.withPermit {
                val stillValid =
                    withContext(Dispatchers.IO) {
                        transaction {
                            EventStore
                                .getEventOrNull(
                                    key.id,
                                )?.let { stillAllowed(key = key, snapshot = snapshot, current = currentSnapshot(row = it)) }
                                ?: false
                        }
                    }
                if (!stillValid) return JobResult.Stale
                client.complete(request)
            }
        val raw =
            when (answer) {
                is LlmResult.Success -> answer.text
                is LlmResult.Failure -> return JobResult.Failed("llm-${answer.kind.name.lowercase()}")
            }
        val validated = TranslationValidator.validate(source = masked, raw = raw) ?: return JobResult.Failed("invalid-answer")
        return withContext(Dispatchers.IO) { store(key = key, snapshot = snapshot, translations = validated) }
    }

    /** Re-check under the event row lock, then store. Everything that does not hold any more discards the result. */
    private fun store(
        key: FlightKey,
        snapshot: Snapshot,
        translations: Map<TranslatableField, String>,
    ): JobResult =
        transaction {
            val row = EventStore.lockEventForUpdate(key.id) ?: return@transaction JobResult.Stale
            val current = currentSnapshot(row = row)
            if (!stillAllowed(key = key, snapshot = snapshot, current = current)) return@transaction JobResult.Stale
            ContentTranslationStore.insertIgnoringDuplicates(
                translations.map { (field, text) ->
                    ContentTranslationRow(
                        contentType = key.type,
                        contentId = key.id,
                        field = field,
                        sourceSha256 = key.hashes.getValue(field),
                        sourceLang = key.sourceLang,
                        targetLang = key.targetLang,
                        translatedText = text,
                        modelProfile = modelProfile,
                    )
                },
            )
            JobResult.Translated(translations)
        }

    private fun stillAllowed(
        key: FlightKey,
        snapshot: Snapshot,
        current: Snapshot,
    ): Boolean =
        current.isPublic &&
            current.consent &&
            current.consentAt == snapshot.consentAt &&
            current.sourceLang == key.sourceLang &&
            key.hashes.all { (field, hash) -> current.hashes[field] == hash }

    private fun recordCooldown(
        key: FlightKey,
        duration: Duration,
    ) {
        if (cooldownUntil.size >= MAX_COOLDOWN_ENTRIES) {
            val current = now()
            cooldownUntil.entries.removeIf { it.value <= current }
            if (cooldownUntil.size >= MAX_COOLDOWN_ENTRIES) cooldownUntil.clear()
        }
        cooldownUntil[key] = now() + duration
    }

    private fun loadSnapshot(eventId: Uuid): Snapshot? {
        val row = EventStore.getEventOrNull(eventId) ?: return null
        return currentSnapshot(row = row)
    }

    /** All snapshots in ONE query plus the content language (read once). Unknown ids are absent from the map. */
    private fun loadSnapshots(ids: List<Uuid>): Pair<String, Map<Uuid, Snapshot>> {
        val sourceLang = loadSourceLang()
        if (ids.isEmpty()) return sourceLang to emptyMap()
        val rows = EventTable.selectAll().where { EventTable.id inList ids }.toList()
        return sourceLang to rows.associate { it[EventTable.id] to currentSnapshot(row = it, sourceLang = sourceLang) }
    }

    private fun loadSourceLang(): String =
        TranslationLanguages.normalizeSource(
            OrganizationSettingsTable
                .select(OrganizationSettingsTable.contentLanguage)
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.contentLanguage),
        )

    private fun currentSnapshot(
        row: ResultRow,
        sourceLang: String? = null,
    ): Snapshot {
        val fields =
            EventTranslationSource.fields(
                title = row[EventTable.title],
                summary = row[EventTable.summary],
                description = row[EventTable.description],
                coverImageAlt = row[EventTable.coverImageAlt],
            )
        val resolvedSourceLang = sourceLang ?: loadSourceLang()
        return Snapshot(
            isPublic = EventTranslationEligibility.isPublic(row),
            consent = row[EventTable.translationConsent],
            consentAt = row[EventTable.translationConsentAt],
            sourceLang = resolvedSourceLang,
            fields = fields,
            hashes = EventTranslationSource.hashes(fields),
        )
    }

    internal companion object {
        const val MAX_IN_FLIGHT = 64

        /** Upper bound of NEW jobs one feed call may start, so a single call cannot take most of [MAX_IN_FLIGHT] (the feed fills up over several calls). */
        const val MAX_NEW_JOBS_PER_CALL = 8
        const val MAX_COOLDOWN_ENTRIES = 10_000
        private const val MODEL_PROFILE_MAX = 200
        val FAILURE_COOLDOWN: Duration = 10.minutes
        val BUDGET_COOLDOWN: Duration = 60.seconds
    }
}
