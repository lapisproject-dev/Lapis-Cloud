package network.lapis.cloud.server

import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.getAllServiceManagers
import dev.kilua.rpc.initRpc
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.routes.registerDocumentRoutes
import network.lapis.cloud.server.routes.registerDsgvoRoutes
import network.lapis.cloud.server.routes.registerMailmergeRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.DirectMessageService
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.MailingService
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.rpc.PingService
import network.lapis.cloud.server.rpc.SystemicConsensusService
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.Greeting
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPingService
import network.lapis.cloud.shared.rpc.ISystemicConsensusService
import java.io.File

fun main() {
    DatabaseConfig.connect()
    DevSeedData.seedIfEmpty()
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Idempotent (see DatabaseConfig/DevSeedData KDoc) — safe to call again here so that
    // ApplicationTest's `testApplication { application { module() } }` also gets a migrated,
    // seeded H2 database without needing its own main()/DB bootstrap.
    DatabaseConfig.connect()
    DevSeedData.seedIfEmpty()

    val documentStorageRoot = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")
    documentStorageRoot.mkdirs()

    install(CallLogging)
    install(Compression)
    install(StatusPages) {
        exception<UnauthenticatedException> { call, cause ->
            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
        }
        exception<ForbiddenException> { call, cause ->
            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
        }
    }

    // initRpc installs its own ContentNegotiation (JSON) plugin internally, configured for the
    // RPC serializers module — installing another one ourselves would collide with it
    // (DuplicatePluginException).
    initRpc {
        registerService(IPingService::class) { PingService() }
        registerService(IMemberService::class) { call -> MemberService(call) }
        registerService(IContributionService::class) { call -> ContributionService(call) }
        registerService(IDocumentService::class) { call -> DocumentService(call) }
        registerService(IMailingService::class) { call -> MailingService(call) }
        registerService(IDirectMessageService::class) { call -> DirectMessageService(call) }
        registerService(IDsgvoService::class) { call -> DsgvoService(call) }
        registerService(IGovernanceService::class) { call -> GovernanceService(call) }
        registerService(IElectionService::class) { call -> ElectionService(call) }
        registerService(ISystemicConsensusService::class) { call -> SystemicConsensusService(call) }
        registerService(IAccountingService::class) { call -> AccountingService(call) }
        registerService(IOrganizationSettingsService::class) { call -> OrganizationSettingsService(call) }
    }

    routing {
        get("/") {
            call.respondText(Greeting.message())
        }
        registerDocumentRoutes(documentStorageRoot)
        registerDsgvoRoutes()
        registerMailmergeRoutes(documentStorageRoot)
        getAllServiceManagers().forEach { applyRoutes(it) }
    }
}
