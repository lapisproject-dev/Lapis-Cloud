package network.lapis.cloud.server

import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.getAllServiceManagers
import dev.kilua.rpc.initRpc
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources
import network.lapis.cloud.server.mail.NoOpPasswordResetMailer
import network.lapis.cloud.server.postal.LetterxpressPostalMailProvider
import network.lapis.cloud.server.routes.registerAuthRoutes
import network.lapis.cloud.server.routes.registerBackupRoutes
import network.lapis.cloud.server.routes.registerDocumentRoutes
import network.lapis.cloud.server.routes.registerDsgvoRoutes
import network.lapis.cloud.server.routes.registerMailmergeRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuctionService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.AuthService
import network.lapis.cloud.server.rpc.BackupService
import network.lapis.cloud.server.rpc.BoardMembershipService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.DirectMessageService
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.server.rpc.DsgvoComplianceService
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MailingService
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.rpc.PeerTransferService
import network.lapis.cloud.server.rpc.PingService
import network.lapis.cloud.server.rpc.PoliticianService
import network.lapis.cloud.server.rpc.PostalMailService
import network.lapis.cloud.server.rpc.PriceOracleService
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.rpc.SystemicConsensusService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.Greeting
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IAuctionService
import network.lapis.cloud.shared.rpc.IAuditLogService
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IBackupService
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPeerTransferService
import network.lapis.cloud.shared.rpc.IPingService
import network.lapis.cloud.shared.rpc.IPoliticianService
import network.lapis.cloud.shared.rpc.IPostalMailService
import network.lapis.cloud.shared.rpc.IPriceOracleService
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.ISystemicConsensusService
import network.lapis.cloud.shared.rpc.UnauthenticatedException
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

    // V0.7.3 Basis-Mehrseiten-UI: same-origin static serving of the KVision/Kotlin-JS client
    // bundle, replacing the previous "separate origin, no CORS story" gap (see lapis-client's
    // Routing.kt KDoc for why same-origin was chosen over CORS+credentials). Default path assumes
    // this process runs with `lapis-server/` as its working directory (true for
    // `./gradlew :lapis-server:run` and `./gradlew :lapis-server:test`) and the client was built via
    // `./gradlew :lapis-client:jsBrowserDistribution` (verified empirically during V0.7.3 review
    // round 1, against the pinned Kotlin Gradle Plugin version -- NOT `jsBrowserProductionWebpack`,
    // which this KDoc originally named: that task alone only emits `main.bundle.js` under
    // `lapis-client/build/kotlin-webpack/js/productionExecutable/`, WITHOUT `index.html` next to it,
    // so `staticFiles` would 404 on "/" even though the bundle exists. `jsBrowserDistribution`
    // additionally copies the processed `index.html` resource alongside the bundle into
    // `lapis-client/build/dist/js/productionExecutable/` -- the actual directory this default
    // matches. Re-verify against the actual Kotlin Gradle Plugin version if this path ever drifts.
    // Deliberately NOT eagerly validated: Route.staticFiles resolves files lazily per-request, so an
    // empty/missing directory (e.g. in a `./gradlew clean check` run that never built the client) is
    // harmless -- requests to "/" just 404 instead of breaking server startup or the test suite.
    val clientDistRoot = File(System.getenv("LAPIS_CLIENT_DIST_ROOT") ?: "../lapis-client/build/dist/js/productionExecutable")
    clientDistRoot.mkdirs()

    // V0.4.2 Letterxpress postal-mail dispatch -- see LetterxpressPostalMailProvider KDoc for the
    // sandbox/live-mode default and the "wire format not verified" disclosure. Constructed once
    // here (not per-request) with its own env-var-derived defaults, same lifecycle as
    // documentStorageRoot.
    val postalMailProvider = LetterxpressPostalMailProvider()

    // V0.6.5 Price-Oracle fuer die Anker-Bindung -- constructed once here (owns the pooled HTTP
    // client AND the in-memory quote cache, see PriceOracleOrchestrator KDoc "Singleton
    // lifecycle"), same lifecycle as postalMailProvider/documentStorageRoot above.
    val priceOracleOrchestrator = PriceOracleOrchestrator(sources = defaultBitcoinOracleSources())

    // V0.7.1 Authentifizierung -- constructed once here (owns the per-instance in-memory failure
    // map, see LoginRateLimiter KDoc "Known scope-cut"), same lifecycle as the other singletons
    // above. cookieSecure is `true` (Secure cookie attribute set) unless explicitly opted out via
    // LAPIS_COOKIE_SECURE=false -- ONLY for local plaintext-HTTP dev, see registerAuthRoutes KDoc
    // "Cookie transport".
    val loginRateLimiter = LoginRateLimiter()
    val cookieSecure = System.getenv("LAPIS_COOKIE_SECURE")?.equals("false", ignoreCase = true) != true

    // V0.7.2 Beitritts-/Registrierungs-Workflow -- constructed once here, same lifecycle as
    // loginRateLimiter above. passwordResetMailer is the honest, disclosed non-delivery stub (see
    // NoOpPasswordResetMailer KDoc) -- a real SMTP-backed implementation can later replace it here
    // without touching AuthRoutes' call site.
    val registrationRateLimiter = LoginRateLimiter()
    val passwordResetRateLimiter = LoginRateLimiter()
    val passwordResetMailer = NoOpPasswordResetMailer()

    install(CallLogging)
    install(Compression)
    // V0.7.3 Basis-Mehrseiten-UI: PartialContent (HTTP Range, for large JS/asset bundles) and
    // AutoHeadResponse (HEAD for the same GET routes) back the staticFiles() registration below --
    // both dependencies were already declared (see gradle/libs.versions.toml) but unused until now.
    install(PartialContent)
    install(AutoHeadResponse)
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
        registerService(IPostalMailService::class) { call -> PostalMailService(call, documentStorageRoot, postalMailProvider) }
        registerService(IBoardMembershipService::class) { call -> BoardMembershipService(call) }
        registerService(IAuditLogService::class) { call -> AuditLogService(call) }
        registerService(IBackupService::class) { call -> BackupService(call) }
        registerService(IDsgvoComplianceService::class) { call -> DsgvoComplianceService(call) }
        registerService(ILtrLedgerService::class) { call -> LtrLedgerService(call) }
        registerService(ICrowdfundingService::class) { call -> CrowdfundingService(call) }
        registerService(IPeerTransferService::class) { call -> PeerTransferService(call) }
        registerService(IPriceOracleService::class) { call -> PriceOracleService(call, priceOracleOrchestrator) }
        registerService(IPoliticianService::class) { call -> PoliticianService(call) }
        registerService(IAuctionService::class) { call -> AuctionService(call) }
        registerService(IAuthService::class) { call -> AuthService(call) }
        registerService(IRegistrationService::class) { call -> RegistrationService(call, registrationRateLimiter) }
    }

    routing {
        // V0.7.3: was the placeholder `get("/") { respondText(Greeting.message()) }` -- relocated
        // rather than dropped, since ApplicationTest already exercised it as a basic
        // server-is-alive smoke check. "/" itself is now the SPA shell, served by staticFiles below.
        get("/api/ping") {
            call.respondText(Greeting.message())
        }
        registerDocumentRoutes(documentStorageRoot)
        registerDsgvoRoutes()
        registerMailmergeRoutes(documentStorageRoot)
        registerBackupRoutes(DatabaseConfig.connect(), documentStorageRoot)
        registerAuthRoutes(loginRateLimiter, cookieSecure, passwordResetRateLimiter, passwordResetMailer)
        getAllServiceManagers().forEach { applyRoutes(it) }
        // Registered last: literal routes above (/api/..., RPC service paths) always win over this
        // catch-all in Ktor's routing trie regardless of registration order, but keeping it last
        // documents the intent -- this is the fallback for everything not already handled above.
        staticFiles("/", clientDistRoot)
    }
}
