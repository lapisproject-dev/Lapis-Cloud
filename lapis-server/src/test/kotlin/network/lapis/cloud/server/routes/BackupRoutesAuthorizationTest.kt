package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.backup.MANIFEST_ENTRY_NAME
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.rpc.BackupService
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import java.nio.file.Files
import java.util.zip.ZipInputStream

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [registerBackupRoutes] (`/api/backup/export`/`/api/backup/restore`) and
 * [network.lapis.cloud.shared.rpc.IBackupService.listOperations] end to end: full-organization
 * export/restore is ADMIN-only -- BOARD/TREASURER/MEMBER are all Forbidden, unlike the self-or-ADMIN
 * DSGVO export ([network.lapis.cloud.server.routes.registerDsgvoRoutes]). Same "real
 * `registerXRoutes` function, throwaway `StatusPages` wiring" house style
 * [network.lapis.cloud.server.rpc.DsgvoServiceTest]/[network.lapis.cloud.server.rpc.AuditLogServiceTest]
 * already establish.
 */
class BackupRoutesAuthorizationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test(
            "GET /api/backup/export requires ADMIN -- BOARD/TREASURER/MEMBER Forbidden, unauthenticated Unauthorized, ADMIN gets a real ZIP",
        ) {
            testApplication {
                val storageRoot = Files.createTempDirectory("backup-routes-export-storage").toFile()
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerBackupRoutes(DatabaseConfig.connect(), storageRoot) }
                }

                client.get("/api/backup/export").status shouldBe HttpStatusCode.Unauthorized
                client.get("/api/backup/export") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/api/backup/export") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/api/backup/export") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden

                val response = client.get("/api/backup/export") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Zip

                val entryNames = mutableListOf<String>()
                runBlocking {
                    ZipInputStream(response.bodyAsChannel().toInputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            entryNames += entry.name
                            entry = zip.nextEntry
                        }
                    }
                }
                (MANIFEST_ENTRY_NAME in entryNames) shouldBe true
            }
        }

        test(
            "POST /api/backup/restore requires ADMIN -- rejected before any restore logic runs for BOARD/TREASURER/MEMBER/unauthenticated",
        ) {
            testApplication {
                val storageRoot = Files.createTempDirectory("backup-routes-restore-storage").toFile()
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerBackupRoutes(DatabaseConfig.connect(), storageRoot) }
                }

                client.post("/api/backup/restore") { setBody("irrelevant body") }.status shouldBe HttpStatusCode.Unauthorized
                client
                    .post("/api/backup/restore") {
                        header("X-Member-Id", BOARD_ID)
                        setBody("irrelevant body")
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/api/backup/restore") {
                        header("X-Member-Id", TREASURER_ID)
                        setBody("irrelevant body")
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/api/backup/restore") {
                        header("X-Member-Id", MEMBER_ID)
                        setBody("irrelevant body")
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("IBackupService.listOperations requires ADMIN -- BOARD/TREASURER/MEMBER Forbidden, ADMIN succeeds") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        get("/test/list-operations") {
                            val ops = BackupService(call).listOperations()
                            call.respondText(ops.size.toString())
                        }
                    }
                }

                client.get("/test/list-operations").status shouldBe HttpStatusCode.Unauthorized
                client.get("/test/list-operations") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/list-operations") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/list-operations") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/list-operations") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }
    })
