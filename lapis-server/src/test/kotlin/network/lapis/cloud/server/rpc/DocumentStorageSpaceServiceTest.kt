package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.documents.DocumentStorageSpaceReader
import network.lapis.cloud.server.encounter.EncounterFixtures
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private const val GB = 1_000_000_000L

/**
 * V1.9.263 -- RPC surface of `IDocumentService.storageSpace`: ADMIN only (checked before the limiter and the file system), 12 calls per
 * minute per member, and a response that carries nothing but level and two numbers. Members are created per test (no reseed of the shared
 * demo data), as in the encounter tests.
 */
class DocumentStorageSpaceServiceTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val fx = EncounterFixtures()
        val root: File = Files.createTempDirectory("storage-space-rpc").toFile()
        afterSpec {
            fx.cleanUp()
            root.deleteRecursively()
        }

        class Fixture {
            val probeCalls = AtomicInteger()
            val reader =
                DocumentStorageSpaceReader(
                    root = root,
                    rateLimiter = FederationInboxRateLimiter(maxRequests = DocumentStorageSpaceReader.MAX_CALLS, window = 1.minutes),
                    usable = {
                        probeCalls.incrementAndGet()
                        15 * GB
                    },
                    total = { 100 * GB },
                )
        }

        test("an ADMIN gets the level and both numbers") {
            val fixture = Fixture()
            val admin = fx.createMember(role = AccountRole.ADMIN)
            encounterApp {
                val dto =
                    client.runAs(
                        member = admin,
                    ) { call -> DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace() }
                dto.getOrThrow() shouldBe StorageSpaceDto(level = StorageSpaceLevel.HINT, freeBytes = 15 * GB, totalBytes = 100 * GB)
            }
        }

        test("MEMBER, BOARD and TREASURER are forbidden, touch no file system and do not use up the limiter") {
            val fixture = Fixture()
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val others = listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.TREASURER).map { fx.createMember(role = it) }
            encounterApp {
                for (id in others) {
                    repeat(DocumentStorageSpaceReader.MAX_CALLS + 3) {
                        client
                            .runAs(member = id) { call ->
                                DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                            }.failure<ForbiddenException>()
                    }
                }
                fixture.probeCalls.get() shouldBe 0
                repeat(DocumentStorageSpaceReader.MAX_CALLS) {
                    client
                        .runAs(member = admin) { call ->
                            DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                        }.getOrThrow()
                }
            }
        }

        test("12 calls per minute pass, the 13th is rate limited, a second ADMIN has its own budget") {
            val fixture = Fixture()
            val first = fx.createMember(role = AccountRole.ADMIN)
            val second = fx.createMember(role = AccountRole.ADMIN)
            encounterApp {
                repeat(DocumentStorageSpaceReader.MAX_CALLS) {
                    client
                        .runAs(member = first) { call ->
                            DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                        }.getOrThrow()
                }
                client
                    .runAs(member = first) { call ->
                        DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                    }.failure<RateLimitedException>()
                fixture.probeCalls.get() shouldBe DocumentStorageSpaceReader.MAX_CALLS
                client
                    .runAs(member = second) { call ->
                        DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                    }.getOrThrow()
            }
        }

        test("the response has exactly level, freeBytes and totalBytes and leaks no path or host name") {
            val fixture = Fixture()
            val admin = fx.createMember(role = AccountRole.ADMIN)
            encounterApp {
                val dto =
                    client
                        .runAs(member = admin) { call ->
                            DocumentService(call = call, storageSpaceReader = fixture.reader).storageSpace()
                        }.getOrThrow()
                val body = Json.encodeToString(StorageSpaceDto.serializer(), dto)
                Json.parseToJsonElement(body).jsonObject.keys shouldBe setOf("level", "freeBytes", "totalBytes")
                body shouldNotContain root.absolutePath
                body shouldNotContain root.name
                body shouldNotContain InetAddress.getLocalHost().hostName
            }
        }

        test("DocumentService(call) without a configured root answers UNKNOWN for an ADMIN") {
            val admin = fx.createMember(role = AccountRole.ADMIN)
            encounterApp {
                val dto = client.runAs(member = admin) { call -> DocumentService(call = call).storageSpace() }.getOrThrow()
                dto shouldBe StorageSpaceDto(level = StorageSpaceLevel.UNKNOWN, freeBytes = null, totalBytes = null)
            }
        }
    })
