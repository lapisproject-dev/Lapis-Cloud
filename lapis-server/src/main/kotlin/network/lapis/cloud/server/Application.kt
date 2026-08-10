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
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.FfmpegGalleryComposer
import network.lapis.cloud.server.conference.HttpLiveKitAdminClient
import network.lapis.cloud.server.conference.HttpLiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.RecordingComposer
import network.lapis.cloud.server.conference.RecordingPoller
import network.lapis.cloud.server.conference.StreamPoller
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources
import network.lapis.cloud.server.federation.FederationActorKeyProvisioner
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.OidcSigningKeyProvisioner
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyProvisioner
import network.lapis.cloud.server.mail.NoOpPasswordResetMailer
import network.lapis.cloud.server.postal.LetterxpressPostalMailProvider
import network.lapis.cloud.server.routes.registerAuthRoutes
import network.lapis.cloud.server.routes.registerBackupRoutes
import network.lapis.cloud.server.routes.registerConferenceRecordingRoutes
import network.lapis.cloud.server.routes.registerDocumentRoutes
import network.lapis.cloud.server.routes.registerDsgvoRoutes
import network.lapis.cloud.server.routes.registerFederationRoutes
import network.lapis.cloud.server.routes.registerMailmergeRoutes
import network.lapis.cloud.server.routes.registerOidcRoutes
import network.lapis.cloud.server.routes.registerTrustAnchorRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuctionService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.AuthService
import network.lapis.cloud.server.rpc.BackupService
import network.lapis.cloud.server.rpc.BoardMembershipService
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.DirectMessageService
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.server.rpc.DsgvoComplianceService
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.FederationService
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
import network.lapis.cloud.server.rpc.TrustAnchorService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.Greeting
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IAuctionService
import network.lapis.cloud.shared.rpc.IAuditLogService
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IBackupService
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IFederationService
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
import network.lapis.cloud.shared.rpc.ITrustAnchorService
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import java.io.File
import kotlin.time.Duration.Companion.minutes

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

    // V0.8.1 Federation-Grundgerüst -- this server's own ActivityPub Actor keypair must exist from
    // first boot onward (unconditional, not LAPIS_SEED_DEMO_DATA-gated, see
    // FederationActorKeyProvisioner KDoc), and the two in-memory inbox guards are constructed once
    // here (same singleton lifecycle as loginRateLimiter/priceOracleOrchestrator above).
    FederationActorKeyProvisioner.ensureProvisioned(FederationConfig.actorUri)
    FederationConfig.warnIfNotPubliclyReachable()
    val federationInboxRateLimiter = FederationInboxRateLimiter()
    val federationReplayGuard = FederationReplayGuard()

    // V0.8.2 OIDC-Gastzugang-Federation -- this server's own OIDC JWS signing keypair must exist
    // from first boot onward too (unconditional, same reasoning as FederationActorKeyProvisioner
    // above, but a SEPARATE keypair for a SEPARATE cryptographic purpose). The DCR registration
    // endpoint reuses loginRateLimiter's own class (LoginRateLimiter) as a generic per-IP throttle,
    // not because it is a login-failure guard -- see registerOidcRoutes' "/register" handler KDoc.
    OidcSigningKeyProvisioner.ensureProvisioned()
    val oidcRegistrationRateLimiter = LoginRateLimiter()

    // V0.8.3 Trust-Anchor-Governance -- this server's own Trust-Anchor signing key must exist from
    // first boot onward too (unconditional, same reasoning as FederationActorKeyProvisioner/
    // OidcSigningKeyProvisioner above, but a THIRD, separate keypair for a THIRD, separate
    // cryptographic purpose). Provisioning the key does NOT itself activate the Trust Anchor role
    // -- see registerTrustAnchorRoutes KDoc "opt-in via non-empty pool".
    TrustAnchorSigningKeyProvisioner.ensureProvisioned()

    // V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- constructed once here (owns the pooled HTTP
    // client, same singleton lifecycle as postalMailProvider/priceOracleOrchestrator above).
    // Constructed unconditionally, even when ConferenceConfig.enabled is false (blank
    // livekitApiUrl/apiKey/apiSecret) -- it is simply never invoked in that case, because
    // ConferenceService's own requireConferenceEnabled gate short-circuits every LiveKit-touching
    // RPC method first (see that class's own KDoc). This keeps registerService wiring unconditional,
    // no null-checks scattered through initRpc, matching how every other optional-integration
    // provider in this block (postalMailProvider) is also always constructed regardless of whether
    // its own credentials are configured.
    val conferenceConfig = ConferenceConfig.load()
    val liveKitAdminClient: LiveKitAdminClient =
        HttpLiveKitAdminClient(conferenceConfig.livekitApiUrl, conferenceConfig.apiKey, conferenceConfig.apiSecret)
    val conferenceRoomRateLimiter = LoginRateLimiter()

    // Audit-round-1 fix (Wave 1): createRoom's own throttle above does NOT cover
    // joinRoom/leaveRoom/listActiveRooms/getRoom/listParticipants -- each of those funnels into a
    // per-member REQUEST-rate limiter instead (never a failure-counting one, see ConferenceService
    // KDoc "Request-rate throttling beyond createRoom"), reusing FederationInboxRateLimiter's own
    // generic checkAndRecord(key) sliding window, same singleton lifecycle as conferenceRoomRateLimiter
    // above.
    val conferenceJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val conferenceLeaveRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val conferenceListRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

    // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- getGuestJoinInfo's own budget,
    // NOT shared with conferenceListRateLimiter (it makes no outbound LiveKit call, see
    // ConferenceService KDoc "Request-rate throttling beyond createRoom").
    val conferenceGuestInfoRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

    // V1.0 Videokonferenzen, Wave 5 security-audit fix -- setRoomGuestAccess's own, much STRICTER
    // budget (fans out to up to conferenceConfig.maxParticipants outbound LiveKit calls plus an
    // audit write per invocation, see ConferenceService's own DEFAULT_GUEST_ACCESS_RATE_MAX KDoc).
    // Constructed here, NOT left to ConferenceService's own constructor default -- a default-argument
    // instance is minted fresh on EVERY ConferenceService(...) call (one per RPC request, see this
    // block's own file header), which would silently defeat throttling exactly like the other four
    // limiters above already document.
    val conferenceGuestAccessRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- ConferenceRecordingConfig.load()
    // is pure string parsing (no I/O, see that class's own KDoc), so it is safe to call
    // unconditionally here regardless of whether LAPIS_RECORDING_ENABLED is set, same posture
    // conferenceConfig above already establishes. The ffmpeg availability probe, by contrast, IS
    // real I/O (spawns a process) -- run exactly ONCE here at startup (never per-RPC-call, see
    // ConferenceRecordingConfig.probeFfmpegAvailable KDoc) and its result is threaded into every
    // ConferenceRecordingService construction below via the constructor's ffmpegAvailable
    // parameter, not re-probed per request. Degrades honestly (WARN-logged, ffmpegAvailable=false)
    // rather than crashing server startup -- a deployment may legitimately want conferencing
    // without recording.
    val conferenceRecordingConfig = ConferenceRecordingConfig.load()
    val ffmpegAvailable = ConferenceRecordingConfig.probeFfmpegAvailable(conferenceRecordingConfig.ffmpegPath)

    // V1.0 Wave 2 "Aufzeichnung", poller/composition/storage step -- RecordingPoller is the ONLY
    // thing that ever calls a LiveKitEgressClient Twirp method or runs ffmpeg (see that class's own
    // KDoc "Mechanism"). Constructed unconditionally (same "cheap to construct, gated on use"
    // posture as liveKitAdminClient above) but `.start()` is only called when
    // conferenceRecordingConfig.enabled holds -- a deployment with recording disabled must never
    // spawn the poll loop at all, not even one that immediately no-ops every tick.
    val liveKitEgressClient: LiveKitEgressClient =
        HttpLiveKitEgressClient(conferenceConfig.livekitApiUrl, conferenceConfig.apiKey, conferenceConfig.apiSecret)
    val recordingComposer: RecordingComposer =
        FfmpegGalleryComposer(conferenceRecordingConfig.ffmpegPath, conferenceRecordingConfig.composeTimeoutMinutes)
    val recordingPoller =
        RecordingPoller(
            liveKitAdminClient = liveKitAdminClient,
            liveKitEgressClient = liveKitEgressClient,
            recordingConfig = conferenceRecordingConfig,
            documentStorageRoot = documentStorageRoot,
            composer = recordingComposer,
        )
    if (conferenceRecordingConfig.enabled) {
        recordingPoller.start()
    }

    // V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- ConferenceStreamingConfig
    // .load() is pure string parsing (same "safe to call unconditionally" posture
    // conferenceConfig/conferenceRecordingConfig above already establish) EXCEPT it fail-fasts
    // (IllegalStateException) if LAPIS_STREAMING_ENABLED=true but LAPIS_SECRET_ENCRYPTION_KEY is
    // missing/invalid -- see that class's own KDoc "Fail-fast on the encryption key". StreamPoller
    // is the ONLY thing that ever calls a LiveKitEgressClient Twirp method for STREAMING (mirrors
    // RecordingPoller's own "sole caller" posture for recording) -- constructed unconditionally,
    // `.start()` gated on conferenceStreamingConfig.enabled, same reasoning as recordingPoller above.
    val conferenceStreamingConfig = ConferenceStreamingConfig.load()
    val streamPoller = StreamPoller(liveKitEgressClient = liveKitEgressClient, streamingConfig = conferenceStreamingConfig)
    if (conferenceStreamingConfig.enabled) {
        streamPoller.start()
    }

    // Audit-round-2 fix (Wave 3): ConferenceStreamingService's constructor rate-limiter parameters
    // have default values ONLY so ConferenceStreamingServiceTest's test-route helper can omit them
    // (see that class's own KDoc "Constructor defaults exist for tests only"). Because Kilua RPC's
    // registerService factory lambda below constructs a brand-new ConferenceStreamingService on
    // EVERY RPC call, relying on those defaults here would silently give every request a fresh,
    // empty-state limiter -- rate limiting would never trigger. These four must be built once, as
    // module-scoped singletons, and threaded through explicitly, exactly like
    // conferenceRoomRateLimiter/conferenceJoinRateLimiter/conferenceLeaveRateLimiter/
    // conferenceListRateLimiter above already are for IConferenceService.
    val streamingDestinationRateLimiter = LoginRateLimiter()
    val streamingStartStreamRateLimiter = LoginRateLimiter()
    val streamingMutateRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val streamingReadRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

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
        registerService(IFederationService::class) { call -> FederationService(call) }
        registerService(ITrustAnchorService::class) { call -> TrustAnchorService(call) }
        registerService(IConferenceService::class) { call ->
            ConferenceService(
                call,
                liveKitAdminClient,
                conferenceRoomRateLimiter,
                joinRoomRateLimiter = conferenceJoinRateLimiter,
                leaveRoomRateLimiter = conferenceLeaveRateLimiter,
                listRateLimiter = conferenceListRateLimiter,
                guestInfoRateLimiter = conferenceGuestInfoRateLimiter,
                guestAccessRateLimiter = conferenceGuestAccessRateLimiter,
            )
        }
        registerService(IConferenceRecordingService::class) { call ->
            ConferenceRecordingService(call, ffmpegAvailable, config = conferenceConfig, recordingConfig = conferenceRecordingConfig)
        }
        registerService(IConferenceStreamingService::class) { call ->
            ConferenceStreamingService(
                call,
                liveKitEgressClient,
                config = conferenceConfig,
                streamingConfig = conferenceStreamingConfig,
                destinationRateLimiter = streamingDestinationRateLimiter,
                startStreamRateLimiter = streamingStartStreamRateLimiter,
                mutateRateLimiter = streamingMutateRateLimiter,
                readRateLimiter = streamingReadRateLimiter,
            )
        }
    }

    routing {
        // V0.7.3: was the placeholder `get("/") { respondText(Greeting.message()) }` -- relocated
        // rather than dropped, since ApplicationTest already exercised it as a basic
        // server-is-alive smoke check. "/" itself is now the SPA shell, served by staticFiles below.
        get("/api/ping") {
            call.respondText(Greeting.message())
        }
        registerDocumentRoutes(documentStorageRoot)
        registerConferenceRecordingRoutes(documentStorageRoot)
        registerDsgvoRoutes()
        registerMailmergeRoutes(documentStorageRoot)
        registerBackupRoutes(DatabaseConfig.connect(), documentStorageRoot)
        registerAuthRoutes(loginRateLimiter, cookieSecure, passwordResetRateLimiter, passwordResetMailer)
        registerFederationRoutes(federationInboxRateLimiter, federationReplayGuard)
        registerOidcRoutes(cookieSecure, oidcRegistrationRateLimiter)
        registerTrustAnchorRoutes()
        getAllServiceManagers().forEach { applyRoutes(it) }
        // Registered last: literal routes above (/api/..., RPC service paths) always win over this
        // catch-all in Ktor's routing trie regardless of registration order, but keeping it last
        // documents the intent -- this is the fallback for everything not already handled above.
        staticFiles("/", clientDistRoot)
    }
}
