package network.lapis.cloud.server.translation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.ai.llm.LlmFailureKind
import network.lapis.cloud.server.db.generated.ContentTranslationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.translation.config.TranslateConfig
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Scenarios of [TranslationService] against a real database (H2 and, tagged, Postgres). The model is a [ScriptedLlm]; time (cooldown, budget)
 * is a settable clock, so no test depends on wall-clock sleeping except where a real asynchronous hand-over is the point.
 */
abstract class TranslationServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fixtures = TranslationFixtures()
        var clock = Instant.parse("2031-01-01T10:00:00Z")
        val scopes = mutableListOf<CoroutineScope>()
        val services = mutableListOf<TranslationService>()

        fun newService(
            llm: ScriptedLlm?,
            config: TranslateConfig = TranslateConfig.operationalForTest(waitTimeoutMs = 20_000),
            budget: TranslationDailyBudget = TranslationDailyBudget(dailyChars = config.dailyCharBudget) { clock },
        ): TranslationService {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scopes += scope
            return TranslationService(config = config, llm = llm, budget = budget, scope = scope, now = { clock }).also { services += it }
        }

        beforeSpec { db.activate() }
        beforeTest {
            db.assertActive()
            clock = Instant.parse("2031-01-01T10:00:00Z")
        }
        afterTest {
            services.forEach { it.close() }
            services.clear()
            fixtures.cleanup()
        }
        afterSpec { db.deactivate() }

        test("a hit is served from storage: the second request makes no model call") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val id = fixtures.event()
            val first = service.translateEvent(eventId = id, preferences = listOf("en"))
            first.state shouldBe TranslationState.TRANSLATED
            first.deliveredLang shouldBe "en"
            first.sourceLang shouldBe "de"
            first.machineTranslated shouldBe true
            first.fields.getValue(TranslatableField.TITLE) shouldBe "EN: Sommerfest"
            first.fields.keys shouldBe setOf(TranslatableField.TITLE, TranslatableField.SUMMARY, TranslatableField.DESCRIPTION)
            val second = service.translateEvent(eventId = id, preferences = listOf("en-GB"))
            second.state shouldBe TranslationState.TRANSLATED
            second.fields shouldBe first.fields
            llm.callCount shouldBe 1
            fixtures.rowCount(id) shouldBe 3
        }

        test("a failure gives the original plus pending, stores nothing, and cools down for ten minutes without a second call") {
            val llm = ScriptedLlm { failure(LlmFailureKind.UPSTREAM_ERROR) }
            val service = newService(llm)
            val id = fixtures.event()
            val first = service.translateEvent(eventId = id, preferences = listOf("en"))
            first.state shouldBe TranslationState.PENDING
            first.translationPending shouldBe true
            first.deliveredLang shouldBe "de"
            first.fields.getValue(TranslatableField.TITLE) shouldBe "Sommerfest"
            fixtures.rowCount(id) shouldBe 0
            llm.callCount shouldBe 1

            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.PENDING
            llm.callCount shouldBe 1

            clock += 11.minutes
            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.PENDING
            llm.callCount shouldBe 2
        }

        test("a slow model: the request answers pending, the job keeps running, the next request is a hit") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm, TranslateConfig.operationalForTest(waitTimeoutMs = 300))
            val id = fixtures.event()
            val first = service.translateEvent(eventId = id, preferences = listOf("fr"))
            first.state shouldBe TranslationState.PENDING
            first.deliveredLang shouldBe "de"
            release.complete(Unit)
            withTimeout(60.seconds) {
                while (fixtures.rowCount(id) < 3) delay(50)
            }
            val second = service.translateEvent(eventId = id, preferences = listOf("fr"))
            second.state shouldBe TranslationState.TRANSLATED
            second.deliveredLang shouldBe "fr"
            llm.callCount shouldBe 1
        }

        test("a changed text deletes the stale rows, keeps the unchanged field, and the next request translates again") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val id = fixtures.event()
            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.TRANSLATED
            fixtures.rowCount(id) shouldBe 3
            transaction { updateDescription(id = id, description = "Neuer Text am 22. Juni.") }
            // title and summary rows survive, the description row is gone
            fixtures.rowCount(id) shouldBe 2
            val again = service.translateEvent(eventId = id, preferences = listOf("en"))
            again.state shouldBe TranslationState.TRANSLATED
            again.fields.getValue(TranslatableField.DESCRIPTION) shouldBe "EN: Neuer Text am 22. Juni."
            llm.callCount shouldBe 2
            // only the changed field was sent the second time
            llm.requests.last().userContent shouldNotContain "\"title\""
            fixtures.rowCount(id) shouldBe 3
        }

        test("twenty concurrent requests make exactly one model call and all get the same translation") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm)
            val id = fixtures.event()
            val results = (1..20).map { async(Dispatchers.Default) { service.translateEvent(eventId = id, preferences = listOf("es")) } }
            entered.await()
            delay(300)
            release.complete(Unit)
            val outcomes = results.awaitAll()
            outcomes.map { it.state }.toSet() shouldBe setOf(TranslationState.TRANSLATED)
            outcomes.map { it.fields }.toSet() shouldHaveSize 1
            llm.callCount shouldBe 1
        }

        test("an exhausted daily budget makes no call and answers pending") {
            val llm = ScriptedLlm()
            val service =
                newService(
                    llm,
                    TranslateConfig.operationalForTest(dailyCharBudget = 1_000),
                    TranslationDailyBudget(dailyChars = 5) { clock },
                )
            val id = fixtures.event()
            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.PENDING
            llm.callCount shouldBe 0
        }

        test("a too large content makes no call") {
            val llm = ScriptedLlm()
            val service = newService(llm, TranslateConfig.operationalForTest(maxSourceCharsPerContent = 20))
            val id = fixtures.event()
            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.PENDING
            llm.callCount shouldBe 0
        }

        test("no consent, not public, unknown: no call") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val noConsent = fixtures.event(consent = false)
            service.translateEvent(eventId = noConsent, preferences = listOf("en")).state shouldBe TranslationState.NO_CONSENT
            val membersOnly = fixtures.event(visibility = EventVisibility.MEMBERS_ONLY)
            val membersOutcome = service.translateEvent(eventId = membersOnly, preferences = listOf("en"))
            membersOutcome.state shouldBe TranslationState.NOT_PUBLIC
            membersOutcome.fields shouldBe emptyMap()
            service.translateEvent(eventId = fixtures.event(status = EventStatus.DRAFT), preferences = listOf("en")).state shouldBe
                TranslationState.NOT_PUBLIC
            service.translateEvent(eventId = kotlin.uuid.Uuid.random(), preferences = listOf("en")).state shouldBe
                TranslationState.NOT_FOUND
            llm.callCount shouldBe 0
        }

        test("a cancelled public event is translated (it is shown publicly)") {
            val service = newService(ScriptedLlm())
            service.translateEvent(eventId = fixtures.event(status = EventStatus.CANCELLED), preferences = listOf("en")).state shouldBe
                TranslationState.TRANSLATED
        }

        test("same language: no call, also through the fallback") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val id = fixtures.event()
            service.translateEvent(eventId = id, preferences = listOf("de")).state shouldBe TranslationState.SAME_LANGUAGE
            fixtures.setContentLanguage("en")
            val viaFallback = service.translateEvent(eventId = id, preferences = listOf("ja"))
            viaFallback.state shouldBe TranslationState.SAME_LANGUAGE
            viaFallback.sourceLang shouldBe "en"
            llm.callCount shouldBe 0
        }

        test("an unsupported language falls back to English") {
            val service = newService(ScriptedLlm())
            val outcome = service.translateEvent(eventId = fixtures.event(), preferences = listOf("ja", "zh"))
            outcome.deliveredLang shouldBe "en"
            outcome.requestedLang shouldBe "en"
            outcome.state shouldBe TranslationState.TRANSLATED
        }

        test("feature off: the original, DISABLED, and nothing is read from storage even if rows exist") {
            val id = fixtures.event()
            val on = newService(ScriptedLlm())
            on.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.TRANSLATED
            fixtures.rowCount(id) shouldBe 3
            val off = newService(null, TranslateConfig.disabled())
            val outcome = off.translateEvent(eventId = id, preferences = listOf("en"))
            outcome.state shouldBe TranslationState.DISABLED
            outcome.deliveredLang shouldBe "de"
            outcome.fields.getValue(TranslatableField.TITLE) shouldBe "Sommerfest"
            // the same with a configuration that is on but a service without a client
            newService(null).translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.DISABLED
        }

        test("revoking the consent deletes the rows in the same transaction") {
            val service = newService(ScriptedLlm())
            val id = fixtures.event()
            service.translateEvent(eventId = id, preferences = listOf("en"))
            fixtures.rowCount(id) shouldBe 3
            fixtures.revoke(eventId = id)
            fixtures.rowCount(id) shouldBe 0
            service.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.NO_CONSENT
        }

        test("a revoke while the job is running wins: nothing is stored (no cancelInFlight needed)") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm)
            val id = fixtures.event()
            val pending = async(Dispatchers.Default) { service.translateEvent(eventId = id, preferences = listOf("it")) }
            entered.await()
            fixtures.revoke(eventId = id)
            release.complete(Unit)
            pending.await().state shouldBe TranslationState.PENDING
            fixtures.rowCount(id) shouldBe 0
        }

        test("a revoke while the job waits for a call permit: no model call at all") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm, config = TranslateConfig.operationalForTest(waitTimeoutMs = 20_000, maxConcurrentCalls = 1))
            val first = fixtures.event()
            val second = fixtures.event(title = "Herbstfest")
            val firstRequest = async(Dispatchers.Default) { service.translateEvent(eventId = first, preferences = listOf("it")) }
            entered.await()
            val secondRequest = async(Dispatchers.Default) { service.translateEvent(eventId = second, preferences = listOf("it")) }
            // The second job is started and queued behind the only permit while the first call is held.
            delay(1_000)
            fixtures.revoke(eventId = second)
            release.complete(Unit)
            firstRequest.await().state shouldBe TranslationState.TRANSLATED
            secondRequest.await().state shouldBe TranslationState.PENDING
            llm.callCount shouldBe 1
            fixtures.rowCount(second) shouldBe 0
        }

        test("revoke and re-grant while the job is running: the new generation also wins over the old job") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm)
            val id = fixtures.event()
            val pending = async(Dispatchers.Default) { service.translateEvent(eventId = id, preferences = listOf("nl")) }
            entered.await()
            fixtures.revoke(eventId = id, now = LocalDateTime(2031, 1, 1, 10, 0, 1))
            fixtures.grant(eventId = id, now = LocalDateTime(2031, 1, 1, 10, 0, 2))
            release.complete(Unit)
            pending.await().state shouldBe TranslationState.PENDING
            fixtures.rowCount(id) shouldBe 0
        }

        test("a text change while the job is running discards the stale result") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm)
            val id = fixtures.event()
            val pending = async(Dispatchers.Default) { service.translateEvent(eventId = id, preferences = listOf("pl")) }
            entered.await()
            transaction { updateDescription(id = id, description = "Ganz anderer Text.") }
            release.complete(Unit)
            pending.await().state shouldBe TranslationState.PENDING
            fixtures.rowCount(id) shouldBe 0
        }

        test("deleting the event deletes its rows; a draft or members-only switch does too") {
            val service = newService(ScriptedLlm())
            val a = fixtures.event()
            service.translateEvent(eventId = a, preferences = listOf("en"))
            transaction { EventStore.deleteEventHard(a) } shouldBe 1
            fixtures.rowCount(a) shouldBe 0

            val b = fixtures.event()
            service.translateEvent(eventId = b, preferences = listOf("en"))
            fixtures.rowCount(b) shouldBe 3
            transaction { EventStore.setStatus(id = b, status = EventStatus.DRAFT, cancelledAt = null) }
            fixtures.rowCount(b) shouldBe 0

            val c = fixtures.event()
            service.translateEvent(eventId = c, preferences = listOf("en"))
            transaction { updateDescription(id = c, description = "Neu", visibility = EventVisibility.MEMBERS_ONLY) }
            fixtures.rowCount(c) shouldBe 0
        }

        test("a duplicate insert from a second service instance neither rolls back nor poisons the transaction") {
            val id = fixtures.event()
            val one = newService(ScriptedLlm())
            val two = newService(ScriptedLlm())
            one.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.TRANSLATED
            val hashes =
                transaction {
                    EventTranslationSource.hashes(
                        EventTranslationSource.fields(title = "Sommerfest", summary = null, description = "x", coverImageAlt = null),
                    )
                }
            val dup =
                ContentTranslationRow(
                    contentType = TranslatableContentType.EVENT,
                    contentId = id,
                    field = TranslatableField.TITLE,
                    sourceSha256 = hashes.getValue(TranslatableField.TITLE),
                    sourceLang = "de",
                    targetLang = "en",
                    translatedText = "again",
                    modelProfile = "p",
                )
            var runs = 0
            transaction {
                runs++
                ContentTranslationStore.insertIgnoringDuplicates(listOf(dup, dup))
                // the transaction is still usable afterwards
                ContentTranslationTable.selectAll().where { ContentTranslationTable.contentId eq id }.count()
            }
            runs shouldBe 1
            fixtures.rowCount(id) shouldBe 3
            two.translateEvent(eventId = id, preferences = listOf("en")).state shouldBe TranslationState.TRANSLATED
        }

        test("only the four text fields reach the model, never place, price, date or link") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val id = fixtures.event(location = "Geheimweg 99, Braunschweig", fee = BigDecimal("77.77"))
            service.translateEvent(eventId = id, preferences = listOf("en"))
            val sent = llm.requests.single().let { it.systemPrompt + it.userContent }
            sent shouldNotContain "Geheimweg"
            sent shouldNotContain "77.77"
            sent shouldNotContain "2031"
            sent shouldContain "Sommerfest"
        }

        test("cancelling the requesting coroutine does not cancel the job; close() ends the service scope") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm)
            val id = fixtures.event()
            val request = async(Dispatchers.Default) { service.translateEvent(eventId = id, preferences = listOf("ru")) }
            entered.await()
            request.cancel()
            release.complete(Unit)
            withTimeout(60.seconds) {
                while (fixtures.rowCount(id) < 3) delay(50)
            }
            fixtures.rowCount(id) shouldBe 3
            service.close()
        }

        test("batch: mixed events get the right states, one model call per consented event, none for the rest") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val ok1 = fixtures.event(title = "Eins")
            val ok2 = fixtures.event(title = "Zwei")
            val noConsent = fixtures.event(consent = false)
            val draft = fixtures.event(status = EventStatus.DRAFT)
            val unknown = kotlin.uuid.Uuid.random()
            val batch =
                service.translateEvents(eventIds = listOf(ok1, ok2, noConsent, draft, unknown), target = "fr", maySpend = { true })
            batch.sourceLang shouldBe "de"
            batch.outcomes.getValue(ok1).state shouldBe TranslationState.TRANSLATED
            batch.outcomes
                .getValue(ok1)
                .fields
                .getValue(TranslatableField.TITLE) shouldBe "EN: Eins"
            batch.outcomes.getValue(ok2).state shouldBe TranslationState.TRANSLATED
            batch.outcomes.getValue(noConsent).state shouldBe TranslationState.NO_CONSENT
            batch.outcomes.getValue(draft).state shouldBe TranslationState.NOT_PUBLIC
            batch.outcomes.getValue(draft).fields shouldBe emptyMap()
            batch.outcomes.getValue(unknown).state shouldBe TranslationState.NOT_FOUND
            llm.callCount shouldBe 2
        }

        test("batch: cache hits make no call and never ask maySpend") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val a = fixtures.event(title = "A")
            val b = fixtures.event(title = "B")
            service.translateEvents(eventIds = listOf(a, b), target = "it", maySpend = { true })
            llm.callCount shouldBe 2
            var asked = 0
            val again =
                service.translateEvents(eventIds = listOf(a, b), target = "it", maySpend = { asked++ >= 0 })
            again.outcomes.values.all { it.state == TranslationState.TRANSLATED } shouldBe true
            asked shouldBe 0
            llm.callCount shouldBe 2
        }

        test("batch: maySpend is asked at most once, false leaves pending without a call but still serves hits") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val cached = fixtures.event(title = "Schon da")
            service.translateEvents(eventIds = listOf(cached), target = "nl", maySpend = { true })
            llm.callCount shouldBe 1
            val fresh = (1..5).map { fixtures.event(title = "Neu $it") }
            var asked = 0
            val batch =
                service.translateEvents(
                    eventIds = listOf(cached) + fresh,
                    target = "nl",
                    maySpend = {
                        asked++
                        false
                    },
                )
            asked shouldBe 1
            batch.outcomes.getValue(cached).state shouldBe TranslationState.TRANSLATED
            fresh.forEach { batch.outcomes.getValue(it).state shouldBe TranslationState.PENDING }
            llm.callCount shouldBe 1
        }

        test("batch: at most maxNewJobs new jobs start per call, the rest stays pending") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val ids = (1..5).map { fixtures.event(title = "Job $it") }
            val batch = service.translateEvents(eventIds = ids, target = "es", maySpend = { true }, maxNewJobs = 2)
            batch.outcomes.values.count { it.state == TranslationState.TRANSLATED } shouldBe 2
            batch.outcomes.values.count { it.state == TranslationState.PENDING } shouldBe 3
            llm.callCount shouldBe 2
        }

        test("batch: one shared wait budget -- slow jobs do not add up, the next call is served from storage") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val service = newService(llm, TranslateConfig.operationalForTest(waitTimeoutMs = 500, maxConcurrentCalls = 3))
            val ids = (1..3).map { fixtures.event(title = "Langsam $it") }
            val started = System.nanoTime()
            val first = service.translateEvents(eventIds = ids, target = "pl", maySpend = { true })
            val tookMs = (System.nanoTime() - started) / 1_000_000
            (tookMs < 1_400) shouldBe true
            first.outcomes.values.all { it.state == TranslationState.PENDING } shouldBe true
            release.complete(Unit)
            withTimeout(60.seconds) {
                while (ids.any { fixtures.rowCount(it) < 3 }) delay(50)
            }
            val calls = llm.callCount
            val second = service.translateEvents(eventIds = ids, target = "pl", maySpend = { true })
            second.outcomes.values.all { it.state == TranslationState.TRANSLATED } shouldBe true
            llm.callCount shouldBe calls
        }

        test("batch: two parallel batches for the same events make one call per event") {
            val llm = ScriptedLlm()
            val service = newService(llm)
            val ids = (1..3).map { fixtures.event(title = "Parallel $it") }
            val results =
                (1..2)
                    .map { async(Dispatchers.Default) { service.translateEvents(eventIds = ids, target = "fr", maySpend = { true }) } }
                    .awaitAll()
            results.forEach { r -> r.outcomes.values.all { it.state == TranslationState.TRANSLATED } shouldBe true }
            llm.callCount shouldBe 3
        }

        test("batch: feature off answers DISABLED for everything and calls nothing") {
            val llm = ScriptedLlm()
            val off = newService(null, TranslateConfig.disabled())
            val id = fixtures.event()
            val batch = off.translateEvents(eventIds = listOf(id), target = "en", maySpend = { true })
            batch.outcomes.getValue(id).state shouldBe TranslationState.DISABLED
            batch.sourceLang shouldBe "de"
            llm.callCount shouldBe 0
        }

        test("batch: an unsupported target is a programming error") {
            val service = newService(ScriptedLlm())
            var thrown = false
            try {
                service.translateEvents(eventIds = emptyList(), target = "xx", maySpend = { true })
            } catch (_: IllegalArgumentException) {
                thrown = true
            }
            thrown shouldBe true
        }
    })

private fun updateDescription(
    id: kotlin.uuid.Uuid,
    description: String,
    visibility: EventVisibility = EventVisibility.PUBLIC,
) {
    val row = EventStore.getEventOrThrow(id)
    EventStore.updateEvent(
        id = id,
        title = row[EventTable.title],
        description = description,
        locationText = row[EventTable.locationText],
        onlineUrl = null,
        startsAt = row[EventTable.startsAt],
        endsAt = row[EventTable.endsAt],
        capacity = null,
        feeAmount = null,
        feeCurrency = null,
        visibility = visibility,
        registrationClosesAt = null,
        summary = row[EventTable.summary],
        coverImageAlt = row[EventTable.coverImageAlt],
        onlineUrlPublic = false,
        onlineKind = EventOnlineKind.NONE,
        encounterSpaceId = null,
    )
}
