package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.translation.ScriptedLlm
import network.lapis.cloud.server.translation.TranslationFixtures
import network.lapis.cloud.server.translation.TranslationIpRateLimiter
import network.lapis.cloud.server.translation.TranslationService
import network.lapis.cloud.server.translation.config.TranslateConfig
import network.lapis.cloud.server.translation.latchedResponder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val ORIGIN = "https://partei.example"
private const val PATH = "/api/embed/v1/events"

/**
 * V1.9.343 (T2) -- the language choice of `GET /api/embed/v1/events`. The model is a [ScriptedLlm]; nothing touches the network. The shared test
 * database may hold other entries, so every assertion looks only at the events this spec created.
 */
class EmbedEventsFeedTranslationTest :
    FunSpec({
        val db = TestDatabase.H2
        val fixtures = TranslationFixtures()
        val services = mutableListOf<TranslationService>()
        val json = Json

        beforeSpec { db.activate() }
        afterTest {
            services.forEach { it.close() }
            services.clear()
            fixtures.cleanup()
        }
        afterSpec { db.deactivate() }

        fun generous() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val config =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = ORIGIN, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        fun feature(
            llm: ScriptedLlm?,
            cfg: TranslateConfig = TranslateConfig.operationalForTest(waitTimeoutMs = 20_000),
            perMinute: Int = 1_000,
        ): EmbedFeedTranslation {
            val service = TranslationService(config = cfg, llm = llm).also { services += it }
            return EmbedFeedTranslation(service = service, ipLimiter = TranslationIpRateLimiter(perMinute = perMinute))
        }

        suspend fun app(
            translation: EmbedFeedTranslation,
            feedLimiter: FederationInboxRateLimiter = generous(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    registerEmbedEventsFeedRoutes(
                        config = config,
                        baseUrl = "https://lapis.example",
                        feedRateLimiter = feedLimiter,
                        preflightRateLimiter = generous(),
                        pastFeedRateLimiter = generous(),
                        translation = translation,
                    )
                }
            }
            block()
        }

        fun slugOf(id: Uuid): String = transaction { EventTable.selectAll().where { EventTable.id eq id }.single()[EventTable.slug] }

        suspend fun ApplicationTestBuilder.get(
            query: String = "",
            acceptLanguage: String? = null,
            path: String = PATH,
            origin: String? = ORIGIN,
        ): HttpResponse =
            client.get("$path$query") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                if (acceptLanguage != null) header(HttpHeaders.AcceptLanguage, acceptLanguage)
            }

        fun entry(
            body: String,
            id: Uuid,
        ): JsonObject {
            val slug = slugOf(id)
            return json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("events")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("slug").jsonPrimitive.content == slug }
        }

        fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.content

        test("?lang beats Accept-Language: the entry is translated into the asked language and marked") {
            val llm = ScriptedLlm()
            val id = fixtures.event(title = "Sommerfest")
            app(feature(llm)) {
                val response = get(query = "?lang=fr", acceptLanguage = "en")
                response.status shouldBe HttpStatusCode.OK
                val item = entry(response.bodyAsText(), id)
                item.str("lang") shouldBe "fr"
                item.str("sourceLang") shouldBe "de"
                item.str("translated") shouldBe "true"
                item.str("machineTranslated") shouldBe "true"
                item.str("translationPending") shouldBe "false"
                item.str("title") shouldBe "EN: Sommerfest"
                llm.requests.last().systemPrompt shouldContain "Fran"
                response.headers.getAll(HttpHeaders.ContentLanguage) shouldBe listOf("fr")
            }
        }

        test("the header decides without ?lang: q-values, unknown language falls back to English") {
            val llm = ScriptedLlm()
            val id = fixtures.event()
            app(feature(llm)) {
                entry(get(acceptLanguage = "it;q=0.4, nl;q=0.8").bodyAsText(), id).str("lang") shouldBe "nl"
                entry(get(query = "?lang=xx").bodyAsText(), id).str("lang") shouldBe "en"
                entry(get(acceptLanguage = "ja").bodyAsText(), id).str("lang") shouldBe "en"
            }
        }

        test("same language as the content, or no wish at all: the original and no model call") {
            val llm = ScriptedLlm()
            val id = fixtures.event(title = "Sommerfest")
            app(feature(llm)) {
                val same = entry(get(acceptLanguage = "de").bodyAsText(), id)
                same.str("lang") shouldBe "de"
                same.str("translated") shouldBe "false"
                same.str("title") shouldBe "Sommerfest"
                val none = get()
                val item = entry(none.bodyAsText(), id)
                item.str("lang") shouldBe "de"
                item.str("translated") shouldBe "false"
                item.str("title") shouldBe "Sommerfest"
                none.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                get(acceptLanguage = "*").bodyAsText().let { entry(it, id).str("translated") shouldBe "false" }
                llm.callCount shouldBe 0
            }
        }

        test("without consent: the original, no model call") {
            val llm = ScriptedLlm()
            val id = fixtures.event(consent = false)
            app(feature(llm)) {
                val item = entry(get(query = "?lang=fr").bodyAsText(), id)
                item.str("translated") shouldBe "false"
                item.str("translationPending") shouldBe "false"
                item.str("lang") shouldBe "de"
                llm.callCount shouldBe 0
            }
        }

        test("feature off: Accept-Language changes nothing byte for byte; an explicit ?lang only adds the five fields at the end") {
            val id = fixtures.event()
            app(feature(llm = null, cfg = TranslateConfig.disabled())) {
                val plain = get()
                val withHeader = get(acceptLanguage = "fr")
                withHeader.bodyAsText() shouldBe plain.bodyAsText()
                withHeader.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                withHeader.headers[HttpHeaders.ContentLanguage] shouldBe null
                val keysPlain = entry(plain.bodyAsText(), id).keys.toList()
                val explicit = get(query = "?lang=en")
                val keys = entry(explicit.bodyAsText(), id).keys.toList()
                keys.take(keysPlain.size) shouldBe keysPlain
                keys.drop(keysPlain.size) shouldBe listOf("lang", "sourceLang", "translated", "machineTranslated", "translationPending")
                entry(explicit.bodyAsText(), id).str("translated") shouldBe "false"
            }
        }

        test("a slow model answers the original with translationPending and no-store, the next call is stored and cacheable") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val llm = ScriptedLlm(latchedResponder(entered = entered, release = release))
            val id = fixtures.event()
            app(feature(llm, TranslateConfig.operationalForTest(waitTimeoutMs = 500))) {
                val first = get(query = "?lang=fr")
                val pending = entry(first.bodyAsText(), id)
                pending.str("translationPending") shouldBe "true"
                pending.str("translated") shouldBe "false"
                pending.str("lang") shouldBe "de"
                first.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                release.complete(Unit)
                withTimeout(60.seconds) {
                    while (fixtures.rowCount(id) < 3) delay(50)
                }
                val second = get(query = "?lang=fr")
                entry(second.bodyAsText(), id).str("translated") shouldBe "true"
                second.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("public, max-age=60")
                llm.callCount shouldBe 1
            }
        }

        test("headers: exactly one Vary and one Cache-Control on 200, 403 and 429; Content-Language is a supported code") {
            val llm = ScriptedLlm()
            fixtures.event()
            app(feature(llm)) {
                val ok = get(query = "?lang=es")
                ok.headers.getAll(HttpHeaders.Vary) shouldBe listOf("Origin, Accept-Language")
                ok.headers.getAll(HttpHeaders.CacheControl)!!.size shouldBe 1
                ok.headers[HttpHeaders.ContentLanguage] shouldBe "es"
                val forbidden = get(query = "?lang=es", origin = "https://evil.example")
                forbidden.status shouldBe HttpStatusCode.Forbidden
                forbidden.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                forbidden.headers.getAll(HttpHeaders.Vary) shouldBe listOf("Origin, Accept-Language")
            }
            val before = llm.callCount
            app(feature(llm), feedLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                get()
                val limited = get(query = "?lang=es")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
            }
            llm.callCount shouldBe before
        }

        test("only the four text fields change: place, price, date, links and flags stay, and never reach the model") {
            val llm = ScriptedLlm()
            val id = fixtures.event(location = "Ort-Geheim 7", fee = java.math.BigDecimal("42.00"))
            app(feature(llm)) {
                val original = entry(get().bodyAsText(), id)
                val translated = entry(get(query = "?lang=en").bodyAsText(), id)
                for (name in listOf(
                    "slug",
                    "startsAt",
                    "endsAt",
                    "locationText",
                    "registrationUrl",
                    "full",
                    "feeAmount",
                    "feeCurrency",
                    "online",
                )) {
                    translated[name] shouldBe original[name]
                }
                translated.str("title") shouldBe "EN: Sommerfest"
                val sent = llm.requests.last().userContent
                sent shouldNotContain "Ort-Geheim"
                sent shouldNotContain "42.00"
            }
        }

        test("rate limit counts only requests that START a job: a cache hit is free when the budget is used up") {
            val llm = ScriptedLlm()
            val id = fixtures.event()
            app(feature(llm, perMinute = 1)) {
                entry(get(query = "?lang=fr").bodyAsText(), id).str("translated") shouldBe "true"
                val second = entry(get(query = "?lang=it").bodyAsText(), id)
                second.str("translationPending") shouldBe "true"
                second.str("translated") shouldBe "false"
                entry(get(query = "?lang=fr").bodyAsText(), id).str("translated") shouldBe "true"
                llm.callCount shouldBe 1
            }
        }

        test("archive and partner entries are untouched, the archive ignores the language wish") {
            val llm = ScriptedLlm()
            fixtures.event()
            app(feature(llm)) {
                val plain = get(path = "$PATH/past").bodyAsText()
                get(path = "$PATH/past", query = "?lang=fr", acceptLanguage = "fr").bodyAsText() shouldBe plain
                val withPartners = get(query = "?quelle=alle&lang=fr").bodyAsText()
                json.parseToJsonElement(withPartners).jsonObject["partnerEvents"]?.jsonArray?.forEach {
                    it.jsonObject.containsKey("translated") shouldBe false
                }
            }
        }

        test("hostile input: an oversized header is ignored, an injection attempt never appears in body or headers") {
            val llm = ScriptedLlm()
            val id = fixtures.event()
            app(feature(llm)) {
                val big = get(acceptLanguage = "fr;q=0.5,".repeat(900))
                big.status shouldBe HttpStatusCode.OK
                entry(big.bodyAsText(), id).str("translated") shouldBe "false"
                llm.callCount shouldBe 0
                for (attack in listOf("%3Cscript%3Ealert(1)%3C%2Fscript%3E", "de%0D%0AX-Evil%3A1")) {
                    val response = get(query = "?lang=$attack")
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldNotContain "<script"
                    response.bodyAsText() shouldNotContain "X-Evil"
                    response.headers[HttpHeaders.ContentLanguage] shouldBe "en"
                    response.headers.contains("X-Evil") shouldBe false
                }
            }
        }
    })
