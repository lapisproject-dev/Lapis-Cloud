package network.lapis.cloud.server

import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.getAllServiceManagers
import dev.kilua.rpc.initRpc
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.BrandingHtml
import network.lapis.cloud.server.branding.BrandingStartupCheck
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard
import network.lapis.cloud.server.conference.FfmpegGalleryComposer
import network.lapis.cloud.server.conference.HttpLiveKitAdminClient
import network.lapis.cloud.server.conference.HttpLiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.RecordingComposer
import network.lapis.cloud.server.conference.RecordingPoller
import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.conference.StreamPoller
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.economy.oracle.OracleSourceConfig
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.PriceOracleStartupCheck
import network.lapis.cloud.server.economy.oracle.defaultOracleSources
import network.lapis.cloud.server.federation.FederationActorKeyProvisioner
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.OidcSigningKeyProvisioner
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyProvisioner
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.JakartaMailTransport
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.mail.SmtpFriendVerificationMailer
import network.lapis.cloud.server.mail.SmtpPasswordResetMailer
import network.lapis.cloud.server.mail.SmtpStartupCheck
import network.lapis.cloud.server.payment.dunning.DunningConfig
import network.lapis.cloud.server.payment.dunning.DunningPoller
import network.lapis.cloud.server.payment.sepa.SepaBatchPoller
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.server.postal.LetterxpressPostalMailProvider
import network.lapis.cloud.server.routes.registerAuthRoutes
import network.lapis.cloud.server.routes.registerBackupRoutes
import network.lapis.cloud.server.routes.registerConferenceRecordingRoutes
import network.lapis.cloud.server.routes.registerDocumentRoutes
import network.lapis.cloud.server.routes.registerDsgvoRoutes
import network.lapis.cloud.server.routes.registerDunningRoutes
import network.lapis.cloud.server.routes.registerFederationRoutes
import network.lapis.cloud.server.routes.registerMailmergeRoutes
import network.lapis.cloud.server.routes.registerOidcRoutes
import network.lapis.cloud.server.routes.registerPublicTransparencyRoutes
import network.lapis.cloud.server.routes.registerSepaRoutes
import network.lapis.cloud.server.routes.registerSocialPublicRoutes
import network.lapis.cloud.server.routes.registerTrustAnchorRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuctionService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.AuthService
import network.lapis.cloud.server.rpc.BackupService
import network.lapis.cloud.server.rpc.BoardMembershipService
import network.lapis.cloud.server.rpc.ConferenceBreakoutService
import network.lapis.cloud.server.rpc.ConferenceNotesService
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.rpc.ConferenceWhiteboardService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.DirectMessageService
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.server.rpc.DsgvoComplianceService
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.rpc.DunningService
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.FederationService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MailingService
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.rpc.PaymentGatewayService
import network.lapis.cloud.server.rpc.PeerTransferService
import network.lapis.cloud.server.rpc.PingService
import network.lapis.cloud.server.rpc.PoliticianService
import network.lapis.cloud.server.rpc.PostalMailService
import network.lapis.cloud.server.rpc.PriceOracleService
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.rpc.SepaService
import network.lapis.cloud.server.rpc.SocialNetworkService
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
import network.lapis.cloud.shared.rpc.IConferenceBreakoutService
import network.lapis.cloud.shared.rpc.IConferenceNotesService
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import network.lapis.cloud.shared.rpc.IConferenceWhiteboardService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.IDunningService
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IFederationService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import network.lapis.cloud.shared.rpc.IPeerTransferService
import network.lapis.cloud.shared.rpc.IPingService
import network.lapis.cloud.shared.rpc.IPoliticianService
import network.lapis.cloud.shared.rpc.IPostalMailService
import network.lapis.cloud.shared.rpc.IPriceOracleService
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.ISepaService
import network.lapis.cloud.shared.rpc.ISocialNetworkService
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

    // V1.2.5 White-Label-Branding -- operator-supplied web-UI title/optional logo (see BrandConfig
    // KDoc). Constructed here, right after clientDistRoot above (cachedIndexHtml below needs it).
    // Deliberately NEVER fail-fast, unlike SmtpConfig/SmtpStartupCheck above -- see
    // BrandingStartupCheck KDoc "why branding is never fail-fast": broken cosmetic configuration
    // (a bad title, a missing/oversized logo file) must never stop this server from starting.
    val brandConfig = BrandConfig.load()
    val resolvedBranding = BrandingStartupCheck.resolve(config = brandConfig)
    // Injected exactly ONCE, not per-request -- branding is process-constant for the lifetime of
    // this deployment (no restart-free hot-reload). Unlike staticFiles' own per-request file
    // resolution, a deployment whose client build appears on disk only AFTER this `by lazy` first
    // resolves would keep serving the pre-build 404 for "/" until the process restarts -- a
    // deliberate, documented divergence (V1.2.5 plan, stolperfalle 8.4), not a bug.
    val cachedIndexHtml: String? by lazy {
        val indexFile = File(clientDistRoot, "index.html")
        if (!indexFile.exists()) {
            null
        } else {
            BrandingHtml.inject(html = indexFile.readText(), brand = resolvedBranding)
        }
    }

    // V0.4.2 Letterxpress postal-mail dispatch -- see LetterxpressPostalMailProvider KDoc for the
    // sandbox/live-mode default and the "wire format not verified" disclosure. Constructed once
    // here (not per-request) with its own env-var-derived defaults, same lifecycle as
    // documentStorageRoot.
    val postalMailProvider = LetterxpressPostalMailProvider()

    // V0.6.5 Price-Oracle fuer die Anker-Bindung, V0.6.6 "Gold- und Fiat-Anker" -- constructed once
    // here (owns the pooled HTTP client AND the in-memory quote cache, see PriceOracleOrchestrator
    // KDoc "Singleton lifecycle"), same lifecycle as postalMailProvider/documentStorageRoot above.
    // Gold sources are key-gated and simply absent when their LAPIS_ORACLE_* env vars are unset
    // (OracleSourceConfig KDoc) -- a BTC-anchored deployment needs no oracle configuration at all,
    // exactly as before this wave.
    val oracleSourceConfig = OracleSourceConfig.load()
    val priceOracleOrchestrator = PriceOracleOrchestrator(sources = defaultOracleSources(config = oracleSourceConfig))
    PriceOracleStartupCheck.logSourceInventory(priceOracleOrchestrator)
    PriceOracleStartupCheck.warnIfActiveAnchorUnderprovisioned(priceOracleOrchestrator)

    // V0.7.1 Authentifizierung -- constructed once here (owns the per-instance in-memory failure
    // map, see LoginRateLimiter KDoc "Known scope-cut"), same lifecycle as the other singletons
    // above. cookieSecure is `true` (Secure cookie attribute set) unless explicitly opted out via
    // LAPIS_COOKIE_SECURE=false -- ONLY for local plaintext-HTTP dev, see registerAuthRoutes KDoc
    // "Cookie transport".
    val loginRateLimiter = LoginRateLimiter()
    val cookieSecure = System.getenv("LAPIS_COOKIE_SECURE")?.equals("false", ignoreCase = true) != true

    // V1.2.3 Echter SMTP-Versand -- EIN Transport für BEIDE Mailer (Passwort-Reset + FRIEND-
    // E-Mail-Verifizierung), siehe SmtpConfig KDoc. Ohne LAPIS_SMTP_*-Env-Vars fällt der
    // Transport auf NoOpMailTransport zurück (loggt, sendet nicht, blockiert den Start nicht) --
    // dieselbe graceful-degradation-Haltung wie OracleSourceConfig/LetterxpressPostalMailProvider.
    // SmtpStartupCheck.verifyAndLog wirft IllegalStateException bei einer unvollständigen
    // Konfiguration -- siehe dessen KDoc "Fail-fast or only loud".
    val smtpConfigState = SmtpConfig.load()
    SmtpStartupCheck.verifyAndLog(smtpConfigState)
    val mailTransport: MailTransport =
        when (smtpConfigState) {
            is SmtpConfigState.Configured -> JakartaMailTransport(config = smtpConfigState.config)
            else -> NoOpMailTransport()
        }
    // V1.2.3 Design-Review -- the white-label sender identity every rendered mail needs (see
    // MailBranding KDoc). Configured: derived from the operator's own LAPIS_SMTP_FROM_NAME/
    // LAPIS_SMTP_REPLY_TO. NotConfigured/Incomplete (Incomplete never reaches here --
    // SmtpStartupCheck.verifyAndLog above already threw): MailBranding.notConfigured() applies,
    // which NoOpMailTransport is the only consumer of (logs a subject line, sends nothing).
    val mailBranding =
        when (smtpConfigState) {
            is SmtpConfigState.Configured ->
                MailBranding(
                    fromDisplayName = smtpConfigState.config.fromDisplayName,
                    replyTo = smtpConfigState.config.replyTo,
                )
            else -> MailBranding.notConfigured()
        }
    val mailDispatcher = MailDispatcher(transport = mailTransport)
    // Bind mailDispatcher's dedicated CoroutineScope to Ktor's own lifecycle -- without this hook
    // the scope is never cancelled deliberately (see MailDispatcher KDoc "Lifecycle"), which lets
    // its worker coroutines and any in-flight/queued sends dangle past shutdown instead of being
    // torn down together with the rest of the application (see MailDispatcher.shutdown KDoc).
    monitor.subscribe(ApplicationStopping) { mailDispatcher.shutdown() }

    // V0.7.2 Beitritts-/Registrierungs-Workflow -- constructed once here, same lifecycle as
    // loginRateLimiter above. passwordResetMailer delegates to mailDispatcher above -- a real
    // SMTP-backed send whenever LAPIS_SMTP_* is configured, an honest disclosed non-delivery stub
    // otherwise (see SmtpConfig/NoOpMailTransport KDoc).
    val registrationRateLimiter = LoginRateLimiter()
    val passwordResetRateLimiter = LoginRateLimiter()
    val passwordResetMailer: PasswordResetMailer =
        SmtpPasswordResetMailer(dispatcher = mailDispatcher, branding = mailBranding)

    // V0.11.0 FRIEND self-registration -- constructed once here, same lifecycle as
    // registrationRateLimiter above. TWO SEPARATE limiter instances (failure-window +
    // hard-request-rate) on purpose: friend-signup spam must never exhaust the membership-
    // application budget (or vice versa) -- see RegistrationService constructor KDoc.
    // friendVerificationMailer uses the SAME mailDispatcher/mailTransport as passwordResetMailer
    // above (V1.2.3: one transport, two thin adapters) -- this parameter used to have a default
    // value and was never actually passed at the registerService(IRegistrationService::class)
    // call site below, silently keeping FRIEND verification mails on the No-Op stub even when a
    // real mailer existed elsewhere; the default is gone now, the compiler enforces the wiring.
    val friendRegistrationRateLimiter = LoginRateLimiter(maxFailures = 3, window = 60.minutes)
    val friendSignupIpRateLimiter = FederationInboxRateLimiter(maxRequests = 5, window = 60.minutes)
    val friendEmailVerifyRateLimiter = LoginRateLimiter()
    val friendVerificationMailer: FriendVerificationMailer =
        SmtpFriendVerificationMailer(dispatcher = mailDispatcher, branding = mailBranding)

    // Security fix (2026-08-27, LOW) -- Welle V1.2.12 `MemberService.updateMemberCoreData` mints
    // the SAME verification-token type as `RegistrationService.registerFriend` above, but had no
    // rate limiter guarding its own outbound send -- see MemberService constructor KDoc
    // "memberCoreDataFriendMailRateLimiter" for the full rationale. This is the TARGET-side cap
    // (per-FRIEND anti-spam protection) -- deliberately tight.
    val memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(maxRequests = 5, window = 60.minutes)

    // Security fix (2026-08-27, LOW, follow-up) -- SEPARATE actor-side cap, deliberately more
    // generous than the target-side one above, see MemberService constructor KDoc
    // "memberCoreDataFriendMailActorRateLimiter" for why a shared cap under both keys silently
    // suppressed verification mails for a legitimate BOARD caller correcting many different
    // FRIENDs in one sitting (e.g. after a `MemberCsvImport`).
    val memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 60.minutes)

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
        HttpLiveKitAdminClient(
            apiUrl = conferenceConfig.livekitApiUrl,
            apiKey = conferenceConfig.apiKey,
            apiSecret = conferenceConfig.apiSecret,
        )
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

    // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- same "constructed here, NOT left to the
    // service's own constructor default" reasoning as conferenceGuestAccessRateLimiter above (the
    // registerService factory lambda below constructs a brand-new ConferenceBreakoutService on
    // EVERY RPC call, so relying on constructor defaults would silently give every request a fresh,
    // empty-state limiter). Budgets/window values match ConferenceBreakoutService's own
    // DEFAULT_CREATE_RATE_MAX/DEFAULT_ASSIGN_RATE_MAX/DEFAULT_RECALL_RATE_MAX/DEFAULT_TOKEN_RATE_MAX.
    val conferenceBreakoutCreateRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
    val conferenceBreakoutAssignRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes)
    val conferenceBreakoutRecallRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
    val conferenceBreakoutTokenRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

    // V1.0 Videokonferenzen, Wave 7 "Whiteboard" -- module-scoped singleton, NOT left to
    // ConferenceWhiteboardService's/ConferenceService's own constructor defaults: registerService's
    // factory lambda constructs a fresh service per RPC call, so a default-argument instance would
    // silently mean every commitStroke lands in its own throwaway, empty map -- see
    // ConferenceService's own `whiteboardState` KDoc and ConferenceWhiteboardService's constructor.
    // Threaded into BOTH ConferenceService (endRoom/reconcileRoomIfDue teardown) and
    // ConferenceWhiteboardService (the actual read/write surface) below -- same shared instance.
    val conferenceWhiteboardState = ConferenceWhiteboardState()
    val conferenceWhiteboardReadRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val conferenceWhiteboardCommitRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes)
    val conferenceWhiteboardClearRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
    val conferenceWhiteboardSaveRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // V1.0 Videokonferenzen, Wave 8 "Geteilte Notizen" -- same module-scoped-singleton reasoning as
    // conferenceWhiteboardState immediately above (registerService's factory lambda constructs a
    // fresh service per RPC call, so a default-argument instance would silently mean every
    // commitBlockEdit lands in its own throwaway, empty map). Threaded into BOTH ConferenceService
    // (endRoom/reconcileRoomIfDue teardown) and ConferenceNotesService (the actual read/write
    // surface) below -- same shared instance.
    val conferenceNotesState = ConferenceNotesState()
    val conferenceNotesReadRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val conferenceNotesCreateRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)
    val conferenceNotesEditRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
    val conferenceNotesDeleteRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes)
    val conferenceNotesSaveRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

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
        HttpLiveKitEgressClient(
            apiUrl = conferenceConfig.livekitApiUrl,
            apiKey = conferenceConfig.apiKey,
            apiSecret = conferenceConfig.apiSecret,
        )
    val recordingComposer: RecordingComposer =
        FfmpegGalleryComposer(
            ffmpegPath = conferenceRecordingConfig.ffmpegPath,
            timeoutMinutes = conferenceRecordingConfig.composeTimeoutMinutes,
        )
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

    // V1.2.2 SEPA-Lastschriftmandate -- SepaConfig.load() is pure string parsing (same
    // "safe to call unconditionally" posture as conferenceConfig/conferenceStreamingConfig above)
    // and deliberately does NOT fail-fast on a missing LAPIS_SECRET_ENCRYPTION_KEY -- see that
    // class's own KDoc "Fail-fast posture deliberately DIFFERENT". SepaBatchPoller is constructed
    // unconditionally, `.start()` gated on sepaConfig.pollerEnabled, same reasoning as
    // recordingPoller/streamPoller above.
    val sepaConfig = SepaConfig.load()
    val sepaBatchPoller = SepaBatchPoller(sepaConfig = sepaConfig)
    if (sepaConfig.pollerEnabled) {
        sepaBatchPoller.start()
    }
    // Security Round 1 (2026-08-20, MINOR-4) -- shared, module-scoped instance for
    // SepaService.grantMandate/revokeMandate; see that class' own "Rate limiting" KDoc for why a
    // constructor-default instance would be non-functional in production.
    val sepaMandateWriteRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // V1.2.7 Automatisiertes Mahnwesen -- DunningConfig.load() is pure string parsing, same
    // deliberately-non-fail-fast posture as SepaConfig (see that class' own KDoc): the feature is
    // DB-flag-gated (organization_settings.dunning_enabled), not env-var-gated. DunningPoller is
    // constructed unconditionally, `.start()` gated on dunningConfig.pollerEnabled, same reasoning
    // as sepaBatchPoller/recordingPoller/streamPoller above. Reuses the SAME postalMailProvider
    // instance as PostalMailService (documentStorageRoot already constructed above) -- see
    // DunningPoller class KDoc "Phase B".
    val dunningConfig = DunningConfig.load()
    val dunningPoller =
        DunningPoller(
            dunningConfig = dunningConfig,
            documentStorageRoot = documentStorageRoot,
            postalMailProvider = postalMailProvider,
        )
    if (dunningConfig.pollerEnabled) {
        dunningPoller.start()
    }
    monitor.subscribe(ApplicationStopping) { dunningPoller.stop() }
    val dunningIssueRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
    // Security review LOW finding -- `POST /api/dunning/contributions/{id}/preview.pdf` had no rate
    // limit at all (see registerDunningRoutes' own `previewRateLimiter` KDoc). Own instance, same
    // budget shape as dunningIssueRateLimiter, because the RPC service and this raw Ktor route are
    // wired independently here.
    val dunningPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" (D6) -- constructed here,
    // NOT left to ElectionService's/SystemicConsensusService's own constructor defaults (there ARE
    // none -- see those classes' own `streamGuard` KDoc): reuses the SAME liveKitEgressClient/
    // conferenceStreamingConfig instances StreamPoller above already uses, no new LiveKit credentials
    // or config surface. StreamPoller itself needs no separate wiring for its own
    // reconcileOrphanedBallotPause path (folded into handlePaused, see that class's own KDoc) --
    // SecretBallotStreamLock is a plain, stateless `object`, and restartEgressForStream already
    // receives liveKitEgressClient as an explicit parameter at every call site, streamPoller's own
    // constructor param above included.
    // Security-audit MAJOR-4 -- resumeRateLimiter is safe as a constructor default here (see
    // DefaultSecretBallotStreamGuard's own KDoc "Resume algorithm": this guard, unlike
    // ConferenceStreamingService, is constructed exactly once) but threaded through explicitly
    // anyway, same consistency/tunability reasoning conferenceMeetingBindRateLimiter below already
    // follows. PAUSE (quiesceStreamsForMeeting) is deliberately never rate-limited.
    val secretBallotResumeRateLimiter = FederationInboxRateLimiter(maxRequests = 5, window = 5.minutes)
    val secretBallotStreamGuard: SecretBallotStreamGuard =
        DefaultSecretBallotStreamGuard(
            liveKitEgressClient = liveKitEgressClient,
            streamingConfig = conferenceStreamingConfig,
            resumeRateLimiter = secretBallotResumeRateLimiter,
        )

    // V1.0 Videokonferenzen, Wave 9 -- ConferenceService.setRoomMeeting's own budget, same
    // "constructed here, NOT left to the service's own constructor default" reasoning as
    // conferenceGuestAccessRateLimiter above.
    val conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

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

    // V1.1.1 Soziales Netzwerk, Welle "Fundament & Post-Kern" -- SocialNetworkService.createPost's
    // own budget. Constructed here, NOT left to the service's own constructor default -- same
    // "registerService's factory lambda constructs a fresh service per RPC call" reasoning as
    // conferenceGuestAccessRateLimiter/every other module-scoped limiter above.
    val socialCreateRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // Review-Fund S1 (2026-08-18): SocialNetworkService.listTimeline/getPost had NO rate limit at
    // all before this fix, unlike every other read path in this codebase -- same module-scoped,
    // never-a-constructor-default reasoning as socialCreateRateLimiter/streamingReadRateLimiter above.
    val socialReadRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

    // V1.1.2 Soziales Netzwerk, Welle "Kommentarbaum, Boosts, rekursive Gesamtgewichtung" --
    // boostPost's OWN budget (30/min), deliberately NOT shared with socialCreateRateLimiter (10/min)
    // -- a boost is a frequent, cheap gesture (one click, plausibly several times per session),
    // while createPost/createComment/hideOwnPost are rarer, more consequential creations. A shared
    // budget would either throttle legitimate boosting or effectively raise the post-spam ceiling to
    // accommodate it. Module-scoped, NEVER a constructor default -- same reasoning as
    // socialCreateRateLimiter/socialReadRateLimiter above (see SocialNetworkService
    // .requireBoostRateLimit KDoc).
    val socialBoostRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

    // V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" -- die ERSTEN IP-gekeyten Limiter dieses
    // Codebase für einen Pfad, der ohne jedes Konto erreichbar ist (Federation-Inbox ist der einzige
    // Präzedenzfall, aber die verlangt eine gültige HTTP-Signatur). Modul-scoped, NIEMALS
    // Konstruktor-Default -- dieselbe Begründung wie bei socialCreateRateLimiter/socialReadRateLimiter
    // oben. maxTrackedKeys deutlich über dem Klassen-Default (10 000): der Schlüsselraum ist hier
    // NICHT durch die Mitgliederzahl begrenzt, sondern durch das Internet -- siehe
    // FederationInboxRateLimiter KDoc "Bounded-eviction hardening" für die zugehörige Härtung.
    //
    // Security-Audit-Fund S-2 (2026-08-18): war ursprünglich 120/min -- DOPPELT so großzügig wie der
    // AUTHENTIFIZIERTE socialReadRateLimiter (60/min) oben, obwohl SocialReadPipeline.SocialReadCaps
    // .PUBLIC's eigene KDoc verlangt, dass der kontenlose Pfad der STRENGSTE Konsument dieser Pipeline
    // sein muss (kein LTR-Einsatz, keine Mitgliedschaft, keine Zurechenbarkeit -- ein Aufrufer hier
    // "bezahlt" für nichts). Auf 30/min gesenkt, deutlich unter den authentifizierten 60/min. Ein
    // gemeinsamer Limiter für sowohl `/s` (Timeline, durch die feste Seitengröße 20 ohnehin billig)
    // als auch `/s/{id}` (potenziell teurer Thread-Render) ist für diese Runde bewusst ausreichend --
    // beide Endpunkte profitieren zusätzlich und unabhängig von den S-1-Härtungen (Byte-Budget +
    // gesenktes `threadMaxNodes`), die die Kosten EINES einzelnen Requests deckeln, unabhängig davon,
    // wie oft er wiederholt wird.
    val socialPublicReadRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes, maxTrackedKeys = 50_000)

    // Eigener, deutlich strengerer Limiter: die Sitemap ist der teuerste öffentliche Endpunkt
    // (gruppierte Aggregat-Query über alle öffentlichen Wurzeln) und für einen Crawler genügt ein
    // Abruf pro Stunde.
    val socialPublicSitemapRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes, maxTrackedKeys = 50_000)

    // V1.1.5 Soziales Netzwerk "Moderation, DSA-Melde-Mechanismus, DSGVO-Content-Hard-Delete" --
    // vier neue, module-scoped Rate-Limiter, NIEMALS als Konstruktor-Default (Stolperfalle 15,
    // dieselbe Begründung wie jeder andere Limiter in diesem Block).
    //
    // BOARD/ADMIN-Moderationsaktionen (removePostForLegalReason/decideReport/decideContentErasure/
    // executeContentErasure) -- seltener, folgenreicher als ein gewöhnlicher Post, 20/min.
    val socialModerationRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes)

    // reportPost (authentifizierter RPC-Pfad, jeder Aufrufer) -- 5/Stunde, deckt eine
    // Missbrauchsspitze, ohne einen legitimen wiederholten Melder auszubremsen.
    val socialReportRateLimiter = FederationInboxRateLimiter(maxRequests = 5, window = 60.minutes)

    // POST /s/{id}/report (öffentlicher, kontenloser Melde-Weg) -- EIGENER, deutlich strengerer
    // IP-gekeyter Limiter als der Lese-Pfad (socialPublicReadRateLimiter), analog zu
    // socialPublicSitemapRateLimiter's Verhältnis zu socialPublicReadRateLimiter.
    val socialPublicReportRateLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 60.minutes, maxTrackedKeys = 50_000)

    // V1.3.0 "Öffentliche Transparenz-Startseite" -- GET /transparenz. Same IP-keyed,
    // account-less-caller reasoning as socialPublicReadRateLimiter above (30/min, maxTrackedKeys
    // bounded the same way), a SEPARATE limiter/bucket rather than reusing that one so a burst
    // against one public route family never eats into the other's budget.
    val publicTransparencyRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes, maxTrackedKeys = 50_000)

    // V1.3.0 -- IDsgvoService.grantPublicRankingConsent, an AUTHENTICATED, member-keyed write path
    // (see DsgvoService's own requireWithinRate-style guard) -- 10/min, same budget
    // sepaMandateWriteRateLimiter/dunningIssueRateLimiter already use for a comparable
    // low-frequency member self-service write.
    val publicRankingConsentWriteRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)

    // Security-Fix (Review): .revokePublicRankingConsent gets its OWN, separate, more generous
    // limiter/bucket -- never publicRankingConsentWriteRateLimiter itself. Art. 7(3) DSGVO requires
    // withdrawing consent to be no harder than giving it; sharing one budget meant a member who
    // exhausted the GRANT budget (e.g. toggling the /dsgvo-rights switch) could have their very
    // next REVOKE rejected, leaving their name publicly visible for up to the window length. See
    // DsgvoService.consentRevokeRateLimiter KDoc.
    val publicRankingConsentRevokeRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

    // Fix (2026-08-14): must be installed BEFORE anything that reads call.request.origin (every
    // plugin/route below, plus every IP-keyed rate limiter in AuthRoutes/RegistrationService/
    // FederationRoutes/OidcRoutes) -- XForwardedHeaders overrides ApplicationRequest.origin from
    // the X-Forwarded-For/-Proto/-Host headers a reverse proxy sets (verified live via tcpdump
    // against the actual VPS 4000 test deployment's Apache config: mod_proxy adds these
    // automatically, no explicit RequestHeader directive needed). Deliberately harmless when there
    // is no reverse proxy in front (local dev, `./gradlew run`): with no X-Forwarded-* headers
    // present, origin falls back to the same raw connection info it always had.
    //
    // Does NOT affect call.request.local, which every caller below explicitly avoids for exactly
    // this reason -- see e.g. AuthRoutes.kt's own ipKey computation.
    //
    // useLastProxy() is REQUIRED, not cosmetic -- the zero-config default (useFirstProxy(), see
    // XForwardedHeadersConfig) takes the FIRST comma-separated X-Forwarded-For entry. Verified live
    // (tcpdump again) that Apache's mod_proxy_http APPENDS to, rather than replaces, an
    // attacker-supplied X-Forwarded-For header: curling the public HTTPS endpoint with a
    // self-supplied "X-Forwarded-For: 6.6.6.6-SPOOFED" arrived at this process as
    // "X-Forwarded-For: 6.6.6.6-SPOOFED, <real client IP>". With the default config that spoofed
    // first value would have become origin.remoteHost -- letting any external client defeat every
    // IP-keyed rate limiter above by sending a fresh fake value per request, the exact opposite of
    // this fix's purpose. useLastProxy() takes the LAST entry instead -- the one Apache itself just
    // appended -- which is correct for and relies on this deployment's topology having exactly ONE
    // trusted hop (Apache, on the same machine) between the internet and this process; if a second
    // reverse proxy is ever added in front of Apache, this needs skipLastProxies(N) instead.
    //
    // V0.11.0 FRIEND-wave review (2026-08-16): pdv2's reverse proxy has since migrated from Apache
    // to Caddy (see the vault's "Infrastruktur PdV" migration notes). Caddy's own `reverse_proxy`
    // directive has the SAME append-not-replace default behaviour for X-Forwarded-For described
    // above (it adds the immediate client's IP onto whatever header arrived, never strips/replaces
    // an attacker-supplied prefix), so useLastProxy()'s "exactly ONE trusted hop" reasoning should
    // still hold unmodified with Caddy in that hop instead of Apache. **This has NOT been
    // re-verified live against the actual Caddy config the way the original fix was tcpdump-
    // verified against Apache** -- do that (curl the public endpoint with a self-supplied
    // X-Forwarded-For, confirm Caddy appends rather than replaces) before relying on this for a new
    // security-sensitive rate limiter, and update this comment (and its Apache-specific wording
    // above) once confirmed.
    install(XForwardedHeaders) {
        useLastProxy()
    }
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
        registerService(
            IMemberService::class,
        ) { call ->
            MemberService(
                call = call,
                friendVerificationMailer = friendVerificationMailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
            )
        }
        registerService(IContributionService::class) { call -> ContributionService(call) }
        registerService(IDocumentService::class) { call -> DocumentService(call) }
        registerService(IMailingService::class) { call -> MailingService(call) }
        registerService(IDirectMessageService::class) { call -> DirectMessageService(call) }
        registerService(
            IDsgvoService::class,
        ) { call ->
            DsgvoService(
                call = call,
                consentRateLimiter = publicRankingConsentWriteRateLimiter,
                consentRevokeRateLimiter = publicRankingConsentRevokeRateLimiter,
            )
        }
        registerService(IGovernanceService::class) { call -> GovernanceService(call = call) }
        registerService(IElectionService::class) { call -> ElectionService(call = call, streamGuard = secretBallotStreamGuard) }
        registerService(
            ISystemicConsensusService::class,
        ) { call -> SystemicConsensusService(call = call, streamGuard = secretBallotStreamGuard) }
        registerService(IAccountingService::class) { call -> AccountingService(call) }
        registerService(IOrganizationSettingsService::class) { call -> OrganizationSettingsService(call) }
        registerService(
            IPostalMailService::class,
        ) { call -> PostalMailService(call = call, storageRoot = documentStorageRoot, postalMailProvider = postalMailProvider) }
        registerService(IBoardMembershipService::class) { call -> BoardMembershipService(call) }
        registerService(IAuditLogService::class) { call -> AuditLogService(call) }
        registerService(IBackupService::class) { call -> BackupService(call) }
        registerService(IDsgvoComplianceService::class) { call -> DsgvoComplianceService(call) }
        registerService(ILtrLedgerService::class) { call -> LtrLedgerService(call = call) }
        registerService(ICrowdfundingService::class) { call -> CrowdfundingService(call = call) }
        registerService(IPeerTransferService::class) { call -> PeerTransferService(call = call) }
        registerService(IPriceOracleService::class) { call -> PriceOracleService(call = call, orchestrator = priceOracleOrchestrator) }
        registerService(IPoliticianService::class) { call -> PoliticianService(call = call) }
        registerService(IAuctionService::class) { call -> AuctionService(call = call) }
        registerService(
            ISepaService::class,
        ) { call ->
            SepaService(
                call = call,
                sepaConfig = sepaConfig,
                documentStorageRoot = documentStorageRoot,
                mandateWriteRateLimiter = sepaMandateWriteRateLimiter,
            )
        }
        registerService(IPaymentGatewayService::class) { call -> PaymentGatewayService(call = call) }
        registerService(
            IDunningService::class,
        ) { call ->
            DunningService(
                call = call,
                dunningConfig = dunningConfig,
                documentStorageRoot = documentStorageRoot,
                issueRateLimiter = dunningIssueRateLimiter,
            )
        }
        registerService(
            ISocialNetworkService::class,
        ) { call ->
            SocialNetworkService(
                call = call,
                createRateLimiter = socialCreateRateLimiter,
                readRateLimiter = socialReadRateLimiter,
                boostRateLimiter = socialBoostRateLimiter,
                moderationRateLimiter = socialModerationRateLimiter,
                reportRateLimiter = socialReportRateLimiter,
            )
        }
        registerService(IAuthService::class) { call -> AuthService(call) }
        registerService(
            IRegistrationService::class,
        ) { call ->
            RegistrationService(
                call = call,
                registrationRateLimiter = registrationRateLimiter,
                friendRegistrationRateLimiter = friendRegistrationRateLimiter,
                friendSignupIpRateLimiter = friendSignupIpRateLimiter,
                friendVerificationMailer = friendVerificationMailer,
            )
        }
        registerService(IFederationService::class) { call -> FederationService(call) }
        registerService(ITrustAnchorService::class) { call -> TrustAnchorService(call) }
        registerService(IConferenceService::class) { call ->
            ConferenceService(
                call = call,
                liveKitAdminClient = liveKitAdminClient,
                createRoomRateLimiter = conferenceRoomRateLimiter,
                joinRoomRateLimiter = conferenceJoinRateLimiter,
                leaveRoomRateLimiter = conferenceLeaveRateLimiter,
                listRateLimiter = conferenceListRateLimiter,
                guestInfoRateLimiter = conferenceGuestInfoRateLimiter,
                guestAccessRateLimiter = conferenceGuestAccessRateLimiter,
                whiteboardState = conferenceWhiteboardState,
                notesState = conferenceNotesState,
                conferenceMeetingBindRateLimiter = conferenceMeetingBindRateLimiter,
            )
        }
        registerService(IConferenceBreakoutService::class) { call ->
            ConferenceBreakoutService(
                call = call,
                liveKitAdminClient = liveKitAdminClient,
                config = conferenceConfig,
                createRateLimiter = conferenceBreakoutCreateRateLimiter,
                assignRateLimiter = conferenceBreakoutAssignRateLimiter,
                recallRateLimiter = conferenceBreakoutRecallRateLimiter,
                tokenRateLimiter = conferenceBreakoutTokenRateLimiter,
            )
        }
        registerService(IConferenceRecordingService::class) { call ->
            ConferenceRecordingService(
                call = call,
                ffmpegAvailable = ffmpegAvailable,
                config = conferenceConfig,
                recordingConfig = conferenceRecordingConfig,
            )
        }
        registerService(IConferenceWhiteboardService::class) { call ->
            ConferenceWhiteboardService(
                call = call,
                documentStorageRoot = documentStorageRoot,
                whiteboardState = conferenceWhiteboardState,
                config = conferenceConfig,
                readRateLimiter = conferenceWhiteboardReadRateLimiter,
                commitRateLimiter = conferenceWhiteboardCommitRateLimiter,
                clearRateLimiter = conferenceWhiteboardClearRateLimiter,
                saveRateLimiter = conferenceWhiteboardSaveRateLimiter,
            )
        }
        registerService(IConferenceNotesService::class) { call ->
            ConferenceNotesService(
                call = call,
                documentStorageRoot = documentStorageRoot,
                notesState = conferenceNotesState,
                config = conferenceConfig,
                readRateLimiter = conferenceNotesReadRateLimiter,
                createRateLimiter = conferenceNotesCreateRateLimiter,
                editRateLimiter = conferenceNotesEditRateLimiter,
                deleteRateLimiter = conferenceNotesDeleteRateLimiter,
                saveRateLimiter = conferenceNotesSaveRateLimiter,
            )
        }
        registerService(IConferenceStreamingService::class) { call ->
            ConferenceStreamingService(
                call = call,
                liveKitEgressClient = liveKitEgressClient,
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
        registerSepaRoutes(documentStorageRoot = documentStorageRoot, sepaConfig = sepaConfig)
        registerDunningRoutes(storageRoot = documentStorageRoot, previewRateLimiter = dunningPreviewRateLimiter)
        registerBackupRoutes(database = DatabaseConfig.connect(), documentStorageRoot = documentStorageRoot)
        registerAuthRoutes(
            rateLimiter = loginRateLimiter,
            cookieSecure = cookieSecure,
            passwordResetRateLimiter = passwordResetRateLimiter,
            passwordResetMailer = passwordResetMailer,
            friendEmailVerifyRateLimiter = friendEmailVerifyRateLimiter,
        )
        registerFederationRoutes(inboxRateLimiter = federationInboxRateLimiter, replayGuard = federationReplayGuard)
        registerOidcRoutes(cookieSecure = cookieSecure, registrationRateLimiter = oidcRegistrationRateLimiter)
        registerTrustAnchorRoutes()
        // V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" -- literal routes (/s, /s/{id}, ...),
        // registered before staticFiles for the same "literal beats catch-all" reasoning documented
        // on that call below; no collision with any RPC service path or the SPA's own routes (see
        // registerSocialPublicRoutes KDoc).
        registerSocialPublicRoutes(
            readRateLimiter = socialPublicReadRateLimiter,
            sitemapRateLimiter = socialPublicSitemapRateLimiter,
            reportRateLimiter = socialPublicReportRateLimiter,
            brandTitle = resolvedBranding.title,
        )
        // V1.3.0 "Öffentliche Transparenz-Startseite" -- literal route (/transparenz), same
        // "registered before staticFiles" reasoning as registerSocialPublicRoutes' own routes.
        registerPublicTransparencyRoutes(
            readRateLimiter = publicTransparencyRateLimiter,
            brandTitle = resolvedBranding.title,
        )
        getAllServiceManagers().forEach { applyRoutes(it) }
        // V1.2.5 White-Label-Branding -- literal routes, registered before staticFiles below for
        // the same "literal beats catch-all" reasoning as registerSocialPublicRoutes' own routes.
        // "/" and "/index.html" replace staticFiles' own handling of the SPA shell so the
        // branding-injected index.html (cachedIndexHtml above) is served instead of the raw file on
        // disk -- every OTHER asset (main.bundle.js, theme.css, ...) still falls through to
        // staticFiles unchanged.
        get("/") { serveIndexHtml(call = call, cachedIndexHtml = cachedIndexHtml) }
        get("/index.html") { serveIndexHtml(call = call, cachedIndexHtml = cachedIndexHtml) }
        get("/api/branding/logo") { serveBrandingLogo(call = call, branding = resolvedBranding) }
        // Registered last: literal routes above (/api/..., RPC service paths) always win over this
        // catch-all in Ktor's routing trie regardless of registration order, but keeping it last
        // documents the intent -- this is the fallback for everything not already handled above.
        staticFiles("/", clientDistRoot)
    }
}

/**
 * V1.2.5 White-Label-Branding -- serves the branding-injected `index.html` (see
 * [BrandingHtml.inject]), or 404 when no client build is present, exactly matching `staticFiles`'
 * own prior behavior for "/" in that case (see `ApplicationTest` "root route 404s when no client
 * build is present" -- that test must stay green after this route replaces `staticFiles` for "/").
 */
private suspend fun serveIndexHtml(
    call: ApplicationCall,
    cachedIndexHtml: String?,
) {
    if (cachedIndexHtml == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    // Explicit charset -- `staticFiles` derives Content-Type/charset from the file extension
    // automatically; this manual handler must set it itself or a default-encoding mismatch could
    // corrupt the umlauts already present in index.html (stolperfalle 8.5).
    call.respondText(text = cachedIndexHtml, contentType = ContentType.parse("text/html; charset=utf-8"))
}

/**
 * V1.2.5 White-Label-Branding -- streams the operator-supplied logo file (never buffers it into
 * memory), exactly the [LocalFileContent] pattern `network.lapis.cloud.server.routes
 * .registerDocumentRoutes` already establishes. 404 whenever [ResolvedBranding.logoAvailable] is
 * false (BrandingStartupCheck already ruled out anything not safely servable) or the path's own
 * extension somehow falls outside the fixed allowlist below (defense-in-depth belt-and-braces,
 * mirrors [BrandConfig]'s own allowlist -- `logoPath` itself is operator-only, never user input).
 */
private suspend fun serveBrandingLogo(
    call: ApplicationCall,
    branding: ResolvedBranding,
) {
    val logoPath = branding.logoPath
    if (!branding.logoAvailable || logoPath == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    val contentType = brandingLogoContentType(logoPath)
    if (contentType == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
    // Ktor's HttpHeaders has no XContentTypeOptions constant -- same literal-string convention
    // `SocialPublicRoutes.kt`'s own `applyPublicPageHeaders` already uses for this exact header.
    call.response.header("X-Content-Type-Options", "nosniff")
    call.respond(LocalFileContent(File(logoPath), contentType))
}

/**
 * Fixed allowlist, NOT `ContentType.parse` on an arbitrary extension -- mirrors
 * [BrandConfig]'s own `ALLOWED_LOGO_EXTENSIONS` (see that class' KDoc); kept as a separate literal
 * map rather than importing that private set, since the two lists serving two different purposes
 * (config-time acceptance vs. response Content-Type) drifting apart would fail closed (404), never
 * open.
 */
private fun brandingLogoContentType(path: String): ContentType? =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "svg" -> ContentType.parse("image/svg+xml")
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType.parse("image/webp")
        else -> null
    }
