import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    // V0.4.2 Letterxpress postal-mail dispatch: first `@Serializable` classes declared directly in
    // this module (LetterxpressPostalMailProvider's request/response wire-shape data classes) --
    // without the compiler plugin, `@Serializable` compiles but generates no serializer at runtime,
    // failing with a SerializationException the first time one of these classes is (de)serialized.
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    // Kept in lockstep with lapis-shared — see the comment there. Needed
    // to load Kilua RPC's JVM-25-compiled classes at runtime.
    jvmToolchain(25)
}

application {
    mainClass.set("network.lapis.cloud.server.ApplicationKt")
    // V1.9.38: pin the JVM default zone to UTC in the generated start script (DEFAULT_JVM_OPTS). Defence in depth next to
    // `ENV TZ=UTC` in the Dockerfile and `TZ: UTC` in the compose files: a `JAVA_TOOL_OPTIONS` set by an operator would
    // silently replace a Dockerfile `ENV JAVA_TOOL_OPTIONS`, but it cannot remove an argument baked into the script.
    // The server code does not depend on it (ServerClock.zone is a constant UTC) -- this only keeps third-party
    // libraries that read the default zone consistent with the storage zone.
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

dependencies {
    implementation(project(":lapis-shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.logback.classic)
    // V0.5.5 DSGVO-Vollausbau: first kotlin-logging use in this module -- house rule (CLAUDE.md
    // "Kotlin-Code-Konvention") is kotlin-logging exclusively, never java.util.logging/direct
    // SLF4J/println. logback-classic above is its SLF4J runtime backend.
    implementation(libs.kotlin.logging.jvm)

    // V0.4.2 Letterxpress postal-mail dispatch — see gradle/libs.versions.toml for why these are
    // new (first outbound-HTTP-client need in this repo). ktor.serialization.kotlinx.json is
    // already declared above and is shared by the client- and server-side content-negotiation
    // plugins alike.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.pdfbox)

    // V1.4.3.2 Veranstaltungs-Ticketing — see gradle/libs.versions.toml for the library-choice
    // rationale (Apache-2.0, :core only, no javase/jai-imageio).
    implementation(libs.zxing.core)

    // V0.7.1 Authentifizierung — see PasswordHasher KDoc for why bcrypt over Argon2id.
    implementation(libs.bcrypt)

    // V1.4.34 Nachrichten-/Artikel-Modul — see gradle/libs.versions.toml for the library-choice
    // rationale (Apache-2.0, pure JVM, no transitive deps).
    implementation(libs.commonmark)

    // V0.8.2 OIDC-Gastzugang-Federation — see gradle/libs.versions.toml for why a library was
    // chosen over hand-rolling (departure from V0.8.1's HTTP-Signatures posture).
    implementation(libs.nimbus.jose.jwt)

    // V1.2.3 Echter SMTP-Versand -- see gradle/libs.versions.toml for the library-choice/license
    // rationale. runtimeOnly for angus-mail: it is discovered via the JavaMail
    // META-INF/services/jakarta.mail.Provider ServiceLoader mechanism at runtime, never referenced
    // by class name from our code -- the compile classpath only needs jakarta.mail-api. Do NOT
    // introduce a shadow/fat-jar for :lapis-server (installDist keeps jars separate) -- a naive
    // jar-merge clobbers that services file and breaks provider lookup at runtime.
    implementation(libs.jakarta.mail.api)
    runtimeOnly(libs.angus.mail)

    // Welle V1.9.7 "SuperMailer" Teil A -- see gradle/libs.versions.toml for the library-choice/
    // license rationale. `implementation`, NOT `runtimeOnly`: MailingHtmlSanitizer references
    // org.jsoup.* types directly.
    implementation(libs.jsoup)

    // V1.4.14 Wave 2 FinTS/HBCI-Live-Kontoabruf -- see gradle/libs.versions.toml for the
    // library-choice/license rationale. `implementation`, NOT `runtimeOnly`: unlike angus-mail we
    // reference `org.kapott.*` types directly (Hbci4jFinTsClient/Hbci4jRawMt940Extractor). ONLY
    // lapis-server -- never lapis-shared/lapis-client, see FinTsClient.kt KDoc "kein hbci4j-Typ
    // erscheint in einer oeffentlichen Signatur".
    implementation(libs.hbci4j.core)

    // V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" — see gradle/libs.versions.toml for the
    // license-/dependency-choice rationale. NUR hier: lapis-shared/lapis-client bekommen dies NICHT
    // (der KVision-Client baut sein DOM über KVision-Komponenten, nicht über HTML-Strings; eine
    // gemeinsame Dependency würde nur die JS-Bundle-Größe erhöhen und einen zweiten, konkurrierenden
    // Renderweg im Client legitimieren). Bewusst NICHT io.ktor:ktor-server-html-builder — siehe
    // SocialPublicHtml.kt Datei-Header für die Begründung (Rendering nach String, nicht direkt in
    // den Response-Stream).
    implementation(libs.kotlinx.html.jvm)

    // V1.4.35 Wiederkehrende Veranstaltungen (RFC 5545 RRULE) -- see gradle/libs.versions.toml for
    // the library-choice rationale. Groovy and jparsec are excluded: both are only needed by
    // ical4j's ContentBuilder DSL / filter-expression features, neither of which this repo uses
    // (only net.fortuna.ical4j.model.Recur) -- Groovy 3 + JDK 25 would be an unnecessary,
    // ungoverned risk to carry onto the runtime classpath. Ical4jClasspathSmokeTest asserts these
    // stay absent.
    implementation(libs.ical4j) {
        exclude(group = "org.apache.groovy")
        exclude(group = "org.codehaus.groovy")
        exclude(group = "org.jparsec")
    }

    // Pre-existing gap found+fixed during V0.7.3 review round 1: h2 was testImplementation-only,
    // so `DatabaseConfig`'s own documented "LAPIS_DB_URL unset -> in-memory H2, zero external
    // setup" default was actually unusable via `./gradlew :lapis-server:run` (H2 driver missing
    // from the runtime classpath -- ClassNotFoundException: org.h2.Driver). runtimeOnly (not
    // implementation) keeps it out of the compile classpath, matching the "test/dev convenience
    // only" posture the KDoc already describes; production deployments still select the real
    // `postgresql` driver via `LAPIS_DB_URL`.
    runtimeOnly(libs.h2)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.h2)
    // Test-only fake HTTP responder for LetterxpressPostalMailProviderTest — see
    // gradle/libs.versions.toml.
    testImplementation(libs.ktor.client.mock)
    // Test-only self-signed-cert generator for FederationIpPinningTest's TLS hostname-verification
    // test (DNS-rebinding fix) — see gradle/libs.versions.toml.
    testImplementation(libs.ktor.network.tls.certificates)

    // kUML MDA persistence pipeline (ADR-0016) — see gradle/libs.versions.toml for why these
    // are test-scoped only. Drives SchemaDriftTest: evaluates src/main/kuml/*.kuml.kts via
    // KumlScriptHost, runs UmlToErmTransformer -> ErmToExposedTransformer / ErmSqlDdlGenerator,
    // and diffs the result against the real H2-migrated schema and the hand-written Table
    // objects (verification-only — the hand-written Table objects remain the compiled/runtime
    // artifact; see docs/architecture/domain-model.adoc "MDA-Pipeline / ADR-0016").
    testImplementation(libs.kuml.core.model)
    testImplementation(libs.kuml.core.dsl)
    testImplementation(libs.kuml.core.script)
    testImplementation(libs.kuml.metamodel.uml)
    testImplementation(libs.kuml.metamodel.erm)
    testImplementation(libs.kuml.profile.api)
    testImplementation(libs.kuml.profile.erm)
    testImplementation(libs.kuml.codegen.api)
    testImplementation(libs.kuml.codegen.m2m)
    testImplementation(libs.kuml.transform.uml.to.erm)
    testImplementation(libs.kuml.codegen.m2m.exposed)
    testImplementation(libs.kuml.gen.sql)
    testImplementation(libs.kotlin.scripting.jvm.host)
    testImplementation(libs.kotlin.scripting.common)
    testImplementation(libs.kotlin.scripting.jvm)
}

// V0.7.1 Authentifizierung -- operator-run, one-time admin bootstrap for a fresh REAL deployment
// (no member-onboarding workflow exists yet, see AdminBootstrap KDoc). Reads
// LAPIS_BOOTSTRAP_ADMIN_EMAIL/LAPIS_BOOTSTRAP_ADMIN_PASSWORD/LAPIS_DB_URL (etc.) from the
// environment, never from Gradle properties (keeps the password out of the Gradle invocation /
// shell history / `ps` output): `LAPIS_BOOTSTRAP_ADMIN_EMAIL=... LAPIS_BOOTSTRAP_ADMIN_PASSWORD=...
// ./gradlew :lapis-server:bootstrapAdmin`. Two modes -- see AdminBootstrap KDoc "Two modes".
tasks.register<JavaExec>("bootstrapAdmin") {
    group = "application"
    description =
        "One-time CLI: sets an existing member's admin password, or (with " +
        "LAPIS_BOOTSTRAP_ADMIN_DISPLAY_NAME also set) creates the very first member+admin " +
        "row on a genuinely fresh deployment (V0.7.1 Authentifizierung)."
    mainClass.set("network.lapis.cloud.server.bootstrap.AdminBootstrapKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Operator-run repair for the recurring "V1__baseline.sql in-place edit changed its checksum"
// situation every wave that widens an existing CHECK/adds a column via that file hits (see
// FlywayRepair KDoc for the full mechanism). Reads LAPIS_DB_URL/LAPIS_DB_USER/LAPIS_DB_PASSWORD
// from the environment, never from Gradle properties, same reasoning as bootstrapAdmin above.
tasks.register<JavaExec>("flywayRepair") {
    group = "application"
    description =
        "One-time CLI: runs Flyway repair() against LAPIS_DB_URL to realign flyway_schema_history " +
        "checksums with the current migration files on disk -- run BEFORE the next deploy whenever " +
        "a wave edits V1__baseline.sql in place (see CHANGELOG.md's recurring OPERATOR NOTE)."
    mainClass.set("network.lapis.cloud.server.bootstrap.FlywayRepairKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// V1.2.11 "Einmaliger CSV-Mitglieder-Import" -- one-time, operator-run CLI for the PdV CRM CSV
// import (PdV instance only, see MemberCsvImport KDoc). Reads ALL inputs from the environment,
// never from Gradle properties -- same reasoning as bootstrapAdmin/flywayRepair above (keeps
// values out of shell history / `ps` output). Default is a Trockenlauf (dry run); only
// LAPIS_MEMBER_IMPORT_COMMIT=true actually writes.
tasks.register<JavaExec>("importMembersFromCsv") {
    group = "application"
    description =
        "One-time CLI: imports a CRM CSV export into the member table (PdV instance only -- see " +
        "MemberCsvImport KDoc). Dry-run unless LAPIS_MEMBER_IMPORT_COMMIT=true."
    mainClass.set("network.lapis.cloud.server.bootstrap.MemberCsvImportKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Settings shared by `test` and `postgresTest` -- kept in ONE place so the two lanes cannot drift.
fun Test.configureLapisTestJvm() {
    useJUnitPlatform()
    // V0.7.1 Authentifizierung -- the ONLY place that sets this JVM system property. Read once by
    // network.lapis.cloud.server.security.AuthTestMode at class-init time to gate the legacy
    // X-Member-Id trusted-header fallback (see that object's KDoc "Two independent locks"). A real
    // server process started outside this Gradle `test` task JVM never has this property set.
    systemProperty("lapis.test.mode", "true")
    // DNS-rebinding fix (feature/dns-rebinding-fix): FederationIpPinningTest's "T1" group proves
    // the rebinding-attack precondition is real by having a custom java.net.spi.InetAddressResolverProvider
    // test double (RebindingSimulationInetAddressResolverProvider) return a different address on
    // successive lookups of a synthetic hostname. Without disabling the JVM's OWN positive
    // address-cache (sun.net.InetAddressCachePolicy, which sits in front of the resolver SPI and
    // would otherwise serve the first lookup's result on every subsequent call within this cache's
    // TTL, masking the very thing T1 needs to observe), the second lookup would come back from that
    // cache instead of hitting the test double's resolver a second time. "sun.net.inetaddr.ttl" is
    // the standard system-property fallback InetAddressCachePolicy reads when the primary
    // java.security property ("networkaddress.cache.ttl") is unset -- setting it here, as a JVM
    // argument, takes effect from JVM startup regardless of which test class in this module happens
    // to trigger the first DNS resolution. Test-JVM-only; a real server process never has this set.
    systemProperty("sun.net.inetaddr.ttl", "0")
    systemProperty("sun.net.inetaddr.negative.ttl", "0")
    // V1.9.38: `-PtestTimeZone=Europe/Berlin` runs the whole suite with a non-UTC process zone (JVM default zone AND the
    // `TZ` environment variable), proving that no server code path depends on the environment's zone.
    providers.gradleProperty("testTimeZone").orNull?.let { zoneId ->
        systemProperty("user.timezone", zoneId)
        environment("TZ", zoneId)
    }
}

tasks.test {
    configureLapisTestJvm()
    // V1.9.37: the Postgres lane (specs tagged "Postgres") runs in its own task below.
    systemProperty("kotest.tags", "!Postgres")
}

// V1.9.37 Postgres test lane -- see docs/architecture/postgres-test-lane.adoc. Needs a DISPOSABLE
// PostgreSQL instance (LAPIS_TEST_POSTGRES_URL/_USER/_PASSWORD from the environment); without the
// variable the task is SKIPPED. The credentials are deliberately NOT read here (never a task input,
// never visible to the configuration cache or a build scan): the forked test worker inherits the
// daemon's environment.
val postgresTest =
    tasks.register<Test>("postgresTest") {
        group = "verification"
        description =
            "Postgres test lane (needs LAPIS_TEST_POSTGRES_URL; skipped otherwise). " +
            "See docs/architecture/postgres-test-lane.adoc"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        configureLapisTestJvm()
        systemProperty("kotest.tags", "Postgres")
        // Kotest instantiates every spec it cannot exclude by annotation to inspect its tags, and some
        // legacy H2 specs touch the database in their constructor -- so the lane's class set is
        // narrowed by NAME as well. Convention: every lane spec has "Postgres" in its class name
        // (enforced by PostgresLaneNamingTest).
        // `-Plane.tests=<pattern>` narrows a local run to a single spec (an extra `--tests` would only ADD to this include).
        filter.includeTestsMatching(providers.gradleProperty("lane.tests").getOrElse("*Postgres*"))
        shouldRunAfter(tasks.test)
        // External database state is not a task input: never up-to-date, never served from the build
        // cache (the build cache is on, also in CI -- a cached result would prove nothing).
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        // Only the PRESENCE of the variable is read (a configuration-cache input), never its value.
        val urlConfigured = providers.environmentVariable("LAPIS_TEST_POSTGRES_URL").isPresent
        onlyIf("LAPIS_TEST_POSTGRES_URL is set") { urlConfigured }
        timeout.set(Duration.ofMinutes(20))
    }

tasks.named("check") { dependsOn(postgresTest) }
