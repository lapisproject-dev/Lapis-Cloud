package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.membermap.MemberMapConfig
import network.lapis.cloud.server.membermap.PmtilesBasemap
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Throwaway routing in this test never runs through the full [network.lapis.cloud.server.module],
 * so it needs its own `StatusPages` mapping for [UnauthenticatedException]/[ForbiddenException] --
 * exactly the mapping `module()`'s own `StatusPages` block installs in production (see
 * [registerMemberMapRoutes] KDoc: this route relies on that mapping, it does not catch these itself).
 */
private fun Application.installMemberMapTestExceptionHandlers() {
    install(StatusPages) {
        exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    }
}

private fun fixturePath(): String =
    checkNotNull(MemberMapRoutesTest::class.java.getResource("/member-map/germany-test-fixture.pmtiles")) {
        "test fixture missing from classpath"
    }.path

/**
 * [registerMemberMapRoutes] itself (auth ordering, 401/403 BEFORE any file existence is revealed,
 * 404-not-500 on every non-[network.lapis.cloud.server.membermap.PmtilesProbe.Available] case)
 * against throwaway routing, plus the real Range/Compression behavior against the FULL
 * [network.lapis.cloud.server.module] (needed because the global `Compression` plugin, which the
 * route's own `call.suppressCompression()` call must be honored by, is only installed there --
 * see [registerMemberMapRoutes] KDoc).
 */
class MemberMapRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            createdMemberIds.clear()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Routentest ${Uuid.random().toString().take(6)}"
                    it[email] = "member-map-route-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        test("no auth -> 401, checked BEFORE any file existence check (not configured here)") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(null)) }
                }
                client.get(MEMBER_MAP_BASEMAP_PATH).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("MEMBER role -> 403, even when a valid fixture IS configured") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(fixturePath())) }
                }
                val member = createMember(AccountRole.MEMBER)
                client.get(MEMBER_MAP_BASEMAP_PATH) { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD + not configured -> 404 (never 500)") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(null)) }
                }
                val board = createMember(AccountRole.BOARD)
                client.get(MEMBER_MAP_BASEMAP_PATH) { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("BOARD + file with wrong magic bytes -> 404 (never 500)") {
            val badFile = kotlin.io.path.createTempFile(suffix = ".pmtiles")
            badFile.toFile().writeBytes(ByteArray(200) { 0x41 })
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(badFile.toString())) }
                }
                val board = createMember(AccountRole.BOARD)
                client.get(MEMBER_MAP_BASEMAP_PATH) { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("BOARD + valid fixture: Range request -> 206, exactly 127 bytes, matches file start, correct Cache-Control") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(fixturePath())) }
                }
                val board = createMember(AccountRole.BOARD)
                val response =
                    client.get(MEMBER_MAP_BASEMAP_PATH) {
                        header("X-Member-Id", board.toString())
                        header(HttpHeaders.Range, "bytes=0-126")
                    }
                response.status shouldBe HttpStatusCode.PartialContent
                response.bodyAsBytes().size shouldBe 127
                response.bodyAsBytes() shouldBe
                    java.io
                        .File(fixturePath())
                        .readBytes()
                        .copyOfRange(0, 127)
                response.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=86400"
            }
        }

        test("BOARD + valid fixture: no Range header -> 200 with the full 389555 bytes") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(fixturePath())) }
                }
                val board = createMember(AccountRole.BOARD)
                val response = client.get(MEMBER_MAP_BASEMAP_PATH) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsBytes().size shouldBe 389_555
            }
        }

        test("BOARD + valid fixture: HEAD -> 200") {
            testApplication {
                application {
                    installMemberMapTestExceptionHandlers()
                    install(io.ktor.server.plugins.autohead.AutoHeadResponse)
                    install(PartialContent)
                    routing { registerMemberMapRoutes(PmtilesBasemap(fixturePath())) }
                }
                val board = createMember(AccountRole.BOARD)
                client.head(MEMBER_MAP_BASEMAP_PATH) { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "against the FULL module(): Range + Accept-Encoding gzip -> 206, NO Content-Encoding header " +
                "(Ktor 3.5.2 does not gzip-wrap 206 responses at all here, with or without suppression -- " +
                "this test alone would stay green even if registerMemberMapRoutes stopped calling " +
                "call.suppressCompression(); see the next test for the assertion that actually exercises it)",
        ) {
            testApplication {
                application {
                    module(
                        aiConfig = AiConfig.load { null },
                        memberMapConfig = MemberMapConfig(pmtilesPath = fixturePath(), invalid = emptyList()),
                    )
                }
                val board = createMember(AccountRole.BOARD)
                val response =
                    client.get(MEMBER_MAP_BASEMAP_PATH) {
                        header("X-Member-Id", board.toString())
                        header(HttpHeaders.Range, "bytes=0-126")
                        header(HttpHeaders.AcceptEncoding, "gzip")
                    }
                response.status shouldBe HttpStatusCode.PartialContent
                response.headers[HttpHeaders.ContentEncoding] shouldBe null
            }
        }

        test(
            "against the FULL module(): NO Range + Accept-Encoding gzip -> 200, NO Content-Encoding header, " +
                "full 389555 bytes (the actual proof that call.suppressCompression() in " +
                "registerMemberMapRoutes works -- the fixture is comfortably over Ktor's default gzip " +
                "minimum size, so without that call this 200 response WOULD be gzip-wrapped; verified by " +
                "temporarily removing the call during development -- this exact test then failed with " +
                "Content-Encoding: gzip)",
        ) {
            testApplication {
                application {
                    module(
                        aiConfig = AiConfig.load { null },
                        memberMapConfig = MemberMapConfig(pmtilesPath = fixturePath(), invalid = emptyList()),
                    )
                }
                val board = createMember(AccountRole.BOARD)
                val response =
                    client.get(MEMBER_MAP_BASEMAP_PATH) {
                        header("X-Member-Id", board.toString())
                        header(HttpHeaders.AcceptEncoding, "gzip")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentEncoding] shouldBe null
                response.bodyAsBytes().size shouldBe 389_555
            }
        }
    })
