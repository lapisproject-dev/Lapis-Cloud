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

    // V0.7.1 Authentifizierung — see PasswordHasher KDoc for why bcrypt over Argon2id.
    implementation(libs.bcrypt)

    // V0.8.2 OIDC-Gastzugang-Federation — see gradle/libs.versions.toml for why a library was
    // chosen over hand-rolling (departure from V0.8.1's HTTP-Signatures posture).
    implementation(libs.nimbus.jose.jwt)

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

// V0.7.1 Authentifizierung -- operator-run, one-time admin-password bootstrap for a fresh REAL
// deployment (no member-onboarding workflow exists yet, see AdminBootstrap KDoc). Reads
// LAPIS_BOOTSTRAP_ADMIN_EMAIL/LAPIS_BOOTSTRAP_ADMIN_PASSWORD/LAPIS_DB_URL (etc.) from the
// environment, never from Gradle properties (keeps the password out of the Gradle invocation /
// shell history / `ps` output): `LAPIS_BOOTSTRAP_ADMIN_EMAIL=... LAPIS_BOOTSTRAP_ADMIN_PASSWORD=...
// ./gradlew :lapis-server:bootstrapAdmin`.
tasks.register<JavaExec>("bootstrapAdmin") {
    group = "application"
    description = "One-time CLI to set an existing member's initial admin password (V0.7.1 Authentifizierung)."
    mainClass.set("network.lapis.cloud.server.bootstrap.AdminBootstrapKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.test {
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
}
