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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Just-in-time translation of the public text of an event (Welle V1.9.342, T1: the core only -- no route, no UI, off by default).
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

    suspend fun translateEvent(
        eventId: Uuid,
        preferences: List<String>,
    ): TranslationOutcome {
        val requested = TranslationLanguages.resolveTarget(preferences)
        val snapshot = withContext(Dispatchers.IO) { transaction { loadSnapshot(eventId) } }

        fun original(
            state: TranslationState,
            source: String = snapshot?.sourceLang ?: TranslationLanguages.normalizeSource(null),
        ) = TranslationOutcome(
            deliveredLang = source,
            sourceLang = source,
            requestedLang = requested,
            state = state,
            fields = snapshot?.fields ?: emptyMap(),
        )
        if (snapshot == null) return original(TranslationState.NOT_FOUND)
        if (!snapshot.isPublic) return original(TranslationState.NOT_PUBLIC).copy(fields = emptyMap())
        if (requested == snapshot.sourceLang) return original(TranslationState.SAME_LANGUAGE)
        if (!snapshot.consent) return original(TranslationState.NO_CONSENT)
        if (!operational) return original(TranslationState.DISABLED)
        if (snapshot.hashes.isEmpty()) return original(TranslationState.SAME_LANGUAGE)

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
        val job = startOrJoin(key = key, snapshot = snapshot) ?: return original(TranslationState.PENDING)
        // The job belongs to the service scope: a timeout or cancellation of THIS request only stops the waiting, never the job.
        val awaited =
            try {
                withTimeoutOrNull(config.waitTimeoutMs) { job.await() }
            } catch (e: CancellationException) {
                // Either THIS request was cancelled (then it must stop) or only the job was (cancelInFlight): the answer is "pending".
                currentCoroutineContext().ensureActive()
                null
            }
        return when (val result = awaited) {
            is JobResult.Translated -> translatedOutcome(cached + result.fields)
            else -> original(TranslationState.PENDING)
        }
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
    ): Deferred<JobResult>? {
        var created: Deferred<JobResult>? = null
        val deferred =
            synchronized(flightLock) {
                inFlight[key] ?: run {
                    if (inFlight.size >= MAX_IN_FLIGHT) return@synchronized null
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
                                )?.let { stillAllowed(key = key, snapshot = snapshot, current = currentSnapshot(it)) }
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
            val current = currentSnapshot(row)
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
        return currentSnapshot(row)
    }

    private fun currentSnapshot(row: ResultRow): Snapshot {
        val fields =
            EventTranslationSource.fields(
                title = row[EventTable.title],
                summary = row[EventTable.summary],
                description = row[EventTable.description],
                coverImageAlt = row[EventTable.coverImageAlt],
            )
        val sourceLang =
            TranslationLanguages.normalizeSource(
                OrganizationSettingsTable
                    .select(OrganizationSettingsTable.contentLanguage)
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
                    ?.get(OrganizationSettingsTable.contentLanguage),
            )
        return Snapshot(
            isPublic = EventTranslationEligibility.isPublic(row),
            consent = row[EventTable.translationConsent],
            consentAt = row[EventTable.translationConsentAt],
            sourceLang = sourceLang,
            fields = fields,
            hashes = EventTranslationSource.hashes(fields),
        )
    }

    internal companion object {
        const val MAX_IN_FLIGHT = 64
        const val MAX_COOLDOWN_ENTRIES = 10_000
        private const val MODEL_PROFILE_MAX = 200
        val FAILURE_COOLDOWN: Duration = 10.minutes
        val BUDGET_COOLDOWN: Duration = 60.seconds
    }
}
