package network.lapis.cloud.server.lupabb

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.digest
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** What the fake answers instead of the normal payload. */
internal enum class FakeMode { NORMAL, REDIRECT, SERVER_ERROR, NOT_FOUND, STATUS_FALSE, BROKEN_JSON, HUGE_WITH_LENGTH, HUGE_CHUNKED }

/**
 * Test double of the LupaBB API on the loopback interface: a real HTTP Digest challenge (MD5, the realm below), the API key accepted as header
 * and/or query parameter, paged members, and the failure modes the client must survive. Fixed test values only; no port is hard coded.
 */
internal class FakeLupaBbServer(
    val username: String = "test-user",
    val password: String = "test-password",
    val apiKey: String = "test-api-key",
) : AutoCloseable {
    var acceptHeader = true
    var acceptQuery = true
    var mode = FakeMode.NORMAL
    var delayMillis = 0L
    var pageSize = 2
    var forcedMax: Int? = null

    /** Leaves the `pagination` object (and so the page count) out of the answer. */
    var omitPagination = false
    var forcedCurrent: Int? = null
    var redirectTarget = "http://127.0.0.1:1/elsewhere"
    var hugeBytes = 6 * 1024 * 1024

    var statuses: List<Pair<Long, String>> =
        listOf(
            1L to "Mitglied",
            2L to "Neumitglied",
            3L to "Förderer",
            4L to "Spender",
            5L to "verstorben",
            6L to "Widerrufen",
            7L to "Ausgeschlossen",
            8L to "Gekündigt",
            9L to "Nur Zahler",
            10L to "Storniert",
            11L to "Ablehnung",
            12L to "in Aufnahme",
        )
    var countries: List<Pair<Long, String>> = listOf(1L to "Deutschland", 2L to "Österreich")
    var associations: List<Pair<Long, String>> = emptyList()
    var members: List<JsonObject> = emptyList()

    val requestCount = AtomicInteger()
    val unauthorizedCount = AtomicInteger()
    val authorizedPaths = ConcurrentLinkedQueue<String>()
    val transports = ConcurrentLinkedQueue<String>()

    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(Authentication) {
                digest("lupabb") {
                    realm = REALM
                    algorithmName = "MD5"
                    digestProvider { user, realm ->
                        if (user == username) MessageDigest.getInstance("MD5").digest("$user:$realm:$password".toByteArray()) else null
                    }
                    validate { credential -> if (credential.userName == username) UserIdPrincipal(credential.userName) else null }
                }
            }
            routing {
                authenticate("lupabb") {
                    get("/api/{path}") {
                        requestCount.incrementAndGet()
                        val header = call.request.headers["X-API-KEY"]
                        val query = call.request.queryParameters["X-API-KEY"]
                        val keyOk = (acceptHeader && header == apiKey) || (acceptQuery && query == apiKey)
                        if (!keyOk) {
                            unauthorizedCount.incrementAndGet()
                            call.respondText(
                                """{"status":false,"message":"key invalid"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Forbidden,
                            )
                            return@get
                        }
                        val path = call.parameters["path"].orEmpty()
                        authorizedPaths += path
                        transports += if (header == apiKey) "HEADER" else "QUERY"
                        if (delayMillis > 0) delay(delayMillis)
                        when (mode) {
                            FakeMode.REDIRECT -> {
                                call.response.header(HttpHeaders.Location, redirectTarget)
                                call.respond(HttpStatusCode.Found)
                            }
                            FakeMode.SERVER_ERROR -> call.respondText("oops", status = HttpStatusCode.InternalServerError)
                            FakeMode.NOT_FOUND -> call.respondText("nope", status = HttpStatusCode.NotFound)
                            FakeMode.STATUS_FALSE -> call.respondText("""{"status":false,"data":[]}""", ContentType.Application.Json)
                            FakeMode.BROKEN_JSON -> call.respondText("{not json", ContentType.Application.Json)
                            FakeMode.HUGE_WITH_LENGTH ->
                                call.respondBytes(ByteArray(hugeBytes) { 'a'.code.toByte() }, ContentType.Application.Json)
                            FakeMode.HUGE_CHUNKED ->
                                call.respondTextWriter(ContentType.Application.Json) {
                                    repeat(hugeBytes / CHUNK) { write("a".repeat(CHUNK)) }
                                }
                            FakeMode.NORMAL ->
                                call.respondText(
                                    payload(path = path, page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1),
                                    ContentType.Application.Json,
                                )
                        }
                    }
                }
            }
        }

    val baseUrl: String

    init {
        server.start(wait = false)
        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        baseUrl = "http://127.0.0.1:$port/api/"
    }

    val endpoint: LupaBbEndpoint get() = LupaBbEndpoint(baseUrl)

    private fun refs(list: List<Pair<Long, String>>): String =
        JsonObject(
            mapOf(
                "status" to JsonPrimitive(true),
                "data" to
                    JsonArray(list.map { JsonObject(mapOf("id" to JsonPrimitive(it.first), "bezeichnung" to JsonPrimitive(it.second))) }),
            ),
        ).toString()

    private fun payload(
        path: String,
        page: Int,
    ): String =
        when (path) {
            "mitgliederstatus" -> refs(statuses)
            "land" -> refs(countries)
            "landesverband" -> refs(associations)
            "mitglieder" -> membersPageFor(page)
            else -> refs(emptyList())
        }

    private fun membersPageFor(page: Int): String {
        val max = forcedMax ?: ((members.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val slice = members.drop((page - 1) * pageSize).take(pageSize)
        return JsonObject(
            mapOf(
                "status" to JsonPrimitive(true),
                "data" to JsonArray(slice),
            ) +
                if (omitPagination) {
                    emptyMap()
                } else {
                    mapOf(
                        "pagination" to
                            JsonObject(
                                mapOf(
                                    "current" to (forcedCurrent?.let { JsonPrimitive(it) } ?: JsonPrimitive(page)),
                                    "max" to JsonPrimitive(max),
                                ),
                            ),
                    )
                },
        ).toString()
    }

    override fun close() {
        server.stop(0, 0)
    }

    companion object {
        const val REALM = "LupaBB"
        private const val CHUNK = 8192

        /** A member record the way the API sends it; [numbersAsStrings] makes the numeric fields arrive quoted (PHP style). */
        fun member(
            personNumber: Long,
            status: Long = 1,
            givenName: String? = "Erika",
            familyName: String? = "Muster",
            email: String? = "erika$personNumber@example.org",
            numbersAsStrings: Boolean = false,
            extra: Map<String, String?> = emptyMap(),
        ): JsonObject {
            fun num(v: Long): JsonPrimitive = if (numbersAsStrings) JsonPrimitive(v.toString()) else JsonPrimitive(v)

            fun str(v: String?) = if (v == null) JsonNull else JsonPrimitive(v)
            val base =
                mutableMapOf(
                    "personennummer" to num(personNumber),
                    "mitglied_status" to num(status),
                    "vorname" to str(givenName),
                    "nachname" to str(familyName),
                    "email" to str(email),
                    "personentyp" to JsonPrimitive("nat"),
                    "strasse" to JsonPrimitive("Musterweg"),
                    "hausnummer" to JsonPrimitive("1"),
                    "postleitzahl" to JsonPrimitive("38100"),
                    "ort" to JsonPrimitive("Braunschweig"),
                    "land" to num(1),
                )
            extra.forEach { (k, v) -> base[k] = str(v) }
            return JsonObject(base)
        }
    }
}
