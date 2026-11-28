package network.lapis.cloud.server.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.ai.llm.LlmClient
import network.lapis.cloud.server.ai.llm.LlmFailureKind
import network.lapis.cloud.server.ai.llm.LlmRequest
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.db.generated.ContentTranslationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** A programmable [LlmClient]: counts calls, remembers every request, can be held back by a latch. Never touches the network. */
internal class ScriptedLlm(
    var responder: suspend (LlmRequest) -> LlmResult = { echoTranslation(request = it) },
) : LlmClient {
    private val calls = AtomicInteger()
    val requests = CopyOnWriteArrayList<LlmRequest>()
    val callCount: Int get() = calls.get()

    override suspend fun complete(request: LlmRequest): LlmResult {
        requests += request
        calls.incrementAndGet()
        return responder(request)
    }
}

/** A valid "translation": the source values prefixed with `EN: ` (tokens, digits and line structure stay intact). */
internal fun echoTranslation(
    request: LlmRequest,
    prefix: String = "EN: ",
): LlmResult.Success {
    val source: JsonObject = Json.parseToJsonElement(request.userContent).jsonObject
    val answer =
        buildJsonObject {
            source.forEach { (key, value) -> put(key, prefix + (value as JsonPrimitive).content) }
        }
    return LlmResult.Success(text = answer.toString(), tokensIn = 1, tokensOut = 1)
}

internal fun failure(kind: LlmFailureKind = LlmFailureKind.UPSTREAM_ERROR) = LlmResult.Failure(kind)

internal fun latchedResponder(
    entered: CompletableDeferred<Unit>,
    release: CompletableDeferred<Unit>,
): suspend (LlmRequest) -> LlmResult =
    { request ->
        entered.complete(Unit)
        release.await()
        echoTranslation(request = request)
    }

/** Creates members and events for translation tests; [cleanup] removes everything it created, events first (FK order). */
internal class TranslationFixtures {
    private val ai = AiTestFixtures()
    private val eventIds = mutableListOf<Uuid>()
    private var authorId: Uuid? = null

    /** The creating/deciding member; created on first use, removed (and forgotten) by [cleanup]. */
    val author: Uuid get() = authorId ?: ai.member().also { authorId = it }

    fun event(
        title: String = "Sommerfest",
        description: String = "Wir feiern am 21. Juni ab 18 Uhr.\n\nBitte mitbringen: gute Laune.",
        summary: String? = "Ein Fest fuer alle",
        coverImageAlt: String? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC,
        status: EventStatus = EventStatus.PUBLISHED,
        consent: Boolean = true,
        location: String = "Braunschweig, Hauptstrasse 5",
        fee: BigDecimal = BigDecimal("12.50"),
    ): Uuid {
        val id = Uuid.random()
        transaction {
            EventStore.insertEvent(
                id = id,
                slug = "tr-${id.toString().take(12)}",
                title = title,
                description = description,
                locationText = location,
                onlineUrl = null,
                startsAt = LocalDateTime(2031, 6, 21, 18, 0),
                endsAt = LocalDateTime(2031, 6, 21, 22, 0),
                capacity = null,
                feeAmount = fee,
                feeCurrency = "EUR",
                visibility = visibility,
                registrationClosesAt = null,
                createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                createdBy = author,
                status = status,
                summary = summary,
                coverImageAlt = coverImageAlt,
                onlineKind = EventOnlineKind.NONE,
            )
        }
        eventIds += id
        if (consent) grant(eventId = id)
        return id
    }

    fun grant(
        eventId: Uuid,
        now: LocalDateTime = ServerClock.now(),
    ) = transaction { EventTranslationConsent.set(eventId = eventId, consent = true, actorId = author, now = now) }

    fun revoke(
        eventId: Uuid,
        now: LocalDateTime = ServerClock.now(),
    ) = transaction { EventTranslationConsent.set(eventId = eventId, consent = false, actorId = author, now = now) }

    fun rowCount(eventId: Uuid): Long =
        transaction { ContentTranslationTable.selectAll().where { ContentTranslationTable.contentId eq eventId }.count() }

    fun setContentLanguage(code: String) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[contentLanguage] =
                    code
            }
        }
    }

    fun cleanup() {
        setContentLanguage("de")
        transaction {
            if (eventIds.isNotEmpty()) {
                ContentTranslationTable.deleteWhere { contentId inList eventIds }
                eventIds.forEach { EventStore.deleteEventHard(it) }
            }
        }
        eventIds.clear()
        ai.cleanup()
        authorId = null
    }

    fun eventRow(eventId: Uuid) = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single() }
}
