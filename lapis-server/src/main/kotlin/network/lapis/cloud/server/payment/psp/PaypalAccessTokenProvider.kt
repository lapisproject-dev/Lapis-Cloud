package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Hard cap on how many bytes of a PSP response body are ever read into memory -- shared by every PSP client (Stripe included, via [defaultPspHttpClient]/[readCappedPspBody]). */
private const val MAX_PSP_RESPONSE_BYTES = 64 * 1024

/** Refresh a cached PayPal access token this long before its own reported expiry -- see [PaypalAccessTokenProvider] KDoc. */
private val REFRESH_SKEW = 60.seconds

/**
 * OAuth2-Client-Credentials-Token-Cache für die PayPal-REST-API (Welle V1.2.8b, GitHub Issue #6).
 * Stripe braucht kein Äquivalent (ein statischer Secret Key wird direkt als Bearer-Token gesendet).
 *
 * - `POST {apiBaseUrl}/v1/oauth2/token`, Body `grant_type=client_credentials`
 *   (form-urlencoded), `Authorization: Basic base64("$clientId:$clientSecret")`.
 * - Nur im Speicher gecacht. Wird erneuert, wenn `now >= expiresAt - REFRESH_SKEW` (60s) --
 *   PayPal-Tokens leben ~9h, also höchstens eine Handvoll Aufrufe pro Tag.
 * - Single-Flight: ein [Mutex] stellt sicher, dass ein Token-Stampede (N gleichzeitige
 *   Webhook-Zustellungen auf einem kalten Cache) GENAU EINEN Token-Request auslöst, nicht N.
 * - Loggt NIEMALS das Client-Secret, den Basic-Header, das Access-Token oder irgendeinen Präfix
 *   davon. Bei einem Fehlschlag wird nur der HTTP-Status geloggt.
 */
class PaypalAccessTokenProvider(
    private val config: PaypalConfig,
    private val httpClient: HttpClient,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAt: Instant? = null

    suspend fun accessTokenOrNull(): String? =
        mutex.withLock {
            val token = cachedToken
            val expiry = expiresAt
            if (token != null && expiry != null && clock() < expiry - REFRESH_SKEW) {
                return@withLock token
            }
            val fetched = fetchToken() ?: return@withLock null
            cachedToken = fetched.first
            expiresAt = fetched.second
            fetched.first
        }

    /** Test-/Forced-Refresh-Hook -- verwirft das gecachte Token, sodass der nächste Aufruf neu holt. */
    fun invalidate() {
        cachedToken = null
        expiresAt = null
    }

    private suspend fun fetchToken(): Pair<String, Instant>? {
        val basic = Base64.getEncoder().encodeToString("${config.clientId}:${config.clientSecret}".toByteArray(Charsets.UTF_8))
        val response =
            try {
                httpClient.post("${config.apiBaseUrl}/v1/oauth2/token") {
                    header("Authorization", "Basic $basic")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("grant_type=client_credentials")
                }
            } catch (e: IOException) {
                logger.warn(e) { "PaypalAccessTokenProvider: network failure calling POST /v1/oauth2/token" }
                return null
            }
        val bodyBytes = response.readCappedPspBody()
        if (response.status.value !in 200..299) {
            logger.warn { "PaypalAccessTokenProvider: non-2xx response from POST /v1/oauth2/token (status=${response.status.value})" }
            return null
        }
        val parsed =
            bodyBytes?.let {
                runCatching { PAYPAL_JSON.decodeFromString(PaypalTokenResponse.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
            }
        if (parsed == null) {
            logger.warn { "PaypalAccessTokenProvider: 2xx response but unparseable token body" }
            return null
        }
        val expiresInSeconds = parsed.expiresIn ?: DEFAULT_TOKEN_LIFETIME_SECONDS
        return parsed.accessToken to (clock() + expiresInSeconds.seconds)
    }

    companion object {
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 9L * 60 * 60
    }
}

/**
 * Ein gehärteter [HttpClient], geteilt von JEDEM PSP-Client (Stripe UND PayPal) -- identische Form
 * zu Stripes vormaligem `defaultStripeHttpClient()`: `HttpTimeout` 10000/5000/10000,
 * `expectSuccess = false`, `followRedirects = false`, KEIN `ContentNegotiation`/`Logging`-Plugin
 * (Antworten werden manuell nach einem begrenzten Read dekodiert; ein Logging-Plugin riskiert, dass
 * ein `Authorization`-Header in eine Log-Zeile gerät).
 */
internal fun defaultPspHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
        followRedirects = false
    }

/** Dünner Alias -- Stripe konstruiert weiterhin über diesen Namen, nichts anderes bewegt sich. */
internal fun defaultStripeHttpClient(): HttpClient = defaultPspHttpClient()

/**
 * Begrenzter Read, geteilt von JEDEM PSP-Client -- `null`, wenn [MAX_PSP_RESPONSE_BYTES]
 * überschritten wird, der Body wird verworfen statt teilweise geparst.
 *
 * **Umfang der Garantie** (siehe `StripeCheckoutClient`s vormalige `readCappedStripeBody`-KDoc,
 * Security-Audit-Runde 1 / S3, wortgleich übernommen): der einzige Aufrufer nutzt die
 * nicht-streamende `httpClient.post(...)`-Anfrageform, unter der Ktors interne `SaveBody`-Plugin
 * bereits den GESAMTEN Antwort-Body im Speicher gepuffert hat, bevor diese Funktion überhaupt
 * läuft. Diese Deckelung begrenzt daher nur den anschließenden Kopier-/Parse-Schritt, NICHT wie
 * viel eine einzelne PSP-Antwort die JVM vorher puffern lässt.
 */
internal suspend fun HttpResponse.readCappedPspBody(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_PSP_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_PSP_RESPONSE_BYTES) null else buffer.copyOf(total)
}
