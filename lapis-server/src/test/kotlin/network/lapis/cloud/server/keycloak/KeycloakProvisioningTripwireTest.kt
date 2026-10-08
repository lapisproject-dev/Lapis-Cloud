package network.lapis.cloud.server.keycloak

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.member.EmailChangeFixture
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.shared.domain.AccountRole
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.uuid.Uuid

/**
 * Welle V1.9.73 -- source-scan tripwires for the Keycloak provisioning and profile sync, plus a runtime log-capture proof:
 *
 * 1. The provisioner creates exactly one account, with the literal role `MEMBER`, and never mentions another role constant.
 * 2. Claims are read only from the verified `claims` object (no request parameter, no access token, no second source).
 * 3. No log line of the new code interpolates an address, a name, a raw claim or the raw subject.
 * 4. The sync/provisioner never take a `forUpdate()` on `MemberTable` themselves (the member lock is `EmailChangeStore.lockMember`).
 * 5. A real run (creation, refusal, sync) produces no log event that contains the test address or name.
 */
class KeycloakProvisioningTripwireTest :
    FunSpec({
        fun source(name: String): String {
            val file = SourceScan.mainFiles().single { it.invariantSeparatorsPath.endsWith("/keycloak/$name") }
            return SourceScan.blank(file.readText())
        }

        fun rawSource(name: String): String =
            SourceScan.mainFiles().single { it.invariantSeparatorsPath.endsWith("/keycloak/$name") }.readText()

        val newFiles = listOf("KeycloakMemberProvisioner.kt", "KeycloakProfileSync.kt", "KeycloakProvisioningClaims.kt")

        test("the provisioner writes one account with the literal role MEMBER and names no other role") {
            val code = source("KeycloakMemberProvisioner.kt")
            Regex("""AccountTable\.insert""").findAll(code).count() shouldBe 1
            Regex("""\[\s*role\s*]\s*=\s*AccountRole\.MEMBER\b""").findAll(code).count() shouldBe 1
            // every AccountRole.<X> in the file is MEMBER (the audit snapshot repeats it)
            Regex("""AccountRole\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet() shouldBe setOf("MEMBER")
            Regex("""roleChangedAt""").containsMatchIn(code) shouldBe true
        }

        test(
            "no new file reads a claim from anywhere but the verified claims object, and none calls UserInfo or decodes the access token",
        ) {
            newFiles.forEach { name ->
                val code = source(name)
                // getClaim / getStringClaim receivers must be `claims` (the parameter that comes from the verified token)
                Regex("""(\w+)\s*\.\s*get(?:String|Boolean)?Claim\s*\(""").findAll(code).forEach { m ->
                    m.groupValues[1] shouldBe "claims"
                }
                Regex("""(?i)userinfo|access_?token|PlainJWT|JWTParser|SignedJWT\.parse""").containsMatchIn(code) shouldBe false
            }
            val route = SourceScan.mainFiles().single { it.name == "KeycloakAuthRoutes.kt" }.readText()
            // the route hands only the verified claims object to the new stages
            Regex("""claims\s*=\s*claims""").findAll(route).count() shouldBe 2
        }

        test("no log statement of the new code interpolates an address, a name, a raw claim or the subject") {
            val risky = Regex("""\$\{?\s*(rawEmail|email|newEmail|oldEmail|displayName|name|claims|subject|raw)\b""")
            val offenders = mutableListOf<String>()
            (newFiles + "KeycloakStartupCheck.kt").forEach { name ->
                rawSource(name).lines().forEachIndexed { i, line ->
                    val t = line.trim()
                    if ((t.startsWith("logger.") || t.contains("logger.warn") || t.contains("logger.info") || t.contains("logger.error")) &&
                        risky.containsMatchIn(t)
                    ) {
                        offenders += "$name:${i + 1}: $t"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the provisioner and the sync take no forUpdate() on MemberTable of their own") {
            listOf("KeycloakMemberProvisioner.kt", "KeycloakProfileSync.kt").forEach { name ->
                val code = source(name)
                Regex("""MemberTable[^;{]{0,200}\.forUpdate\s*\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(code) shouldBe false
                Regex("""forMemberUpdate""").containsMatchIn(code) shouldBe false
            }
        }

        test("the pinned scan sees the files (self-check)") {
            newFiles.forEach { File(SourceScan.mainRoot(), "network/lapis/cloud/server/keycloak/$it").exists() shouldBe true }
        }

        test("runtime: creating, refusing and syncing log neither the test address nor the test name") {
            DatabaseConfig.connect()
            val fixture = EmailChangeFixture()
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            val previousLevel = root.level
            root.level = ch.qos.logback.classic.Level.ALL
            root.addAppender(appender)
            val secretMail = "log-probe-${Uuid.random().toString().take(10)}@example.org"
            val secretName = "Geheimname Probe ${Uuid.random().toString().take(6)}"
            val secretSubject = "secret-subject-${Uuid.random()}"
            val created = mutableListOf<Uuid>()
            try {
                val notifier =
                    KeycloakProvisioningNotifier(mailer = RecordingProvisioningMailer(failing = true), smtpConfigState = configuredSmtp())
                val provisioner = KeycloakMemberProvisioner(config = jitConfig(ratePerHour = 1000), notifier = notifier)

                fun attempt(
                    p: KeycloakMemberProvisioner,
                    suffix: String,
                    verified: Boolean = true,
                    groups: Any? = listOf("apolda"),
                ) = p.provision(
                    issuer = JIT_ISSUER,
                    subject = secretSubject + suffix,
                    rawEmail = secretMail + suffix,
                    emailVerified = verified,
                    claims = idClaims(email = secretMail + suffix, name = secretName, groups = groups),
                )
                // created (the failing mailer logs by class name only)
                (attempt(provisioner, "") as KeycloakMemberProvisioner.Outcome.Provisioned).memberId.also { created += it }
                // refused: no group, unverified, rate-limited
                attempt(provisioner, "x", groups = null)
                attempt(provisioner, "y", verified = false)
                attempt(KeycloakMemberProvisioner(config = jitConfig(ratePerHour = 1), notifier = notifier), "z")
                // sync: name + address of a protected account (logs the WARN) and of a normal one (applied, failing mailer)
                val sync = KeycloakProfileSync(notifier)

                fun login(
                    id: Uuid,
                    suffix: String,
                ) = sync.syncOnLogin(
                    memberId = id,
                    subject = secretSubject,
                    rawEmail = secretMail + suffix,
                    emailVerified = true,
                    claims = idClaims(email = secretMail + suffix, name = secretName),
                )
                login(fixture.member(role = AccountRole.ADMIN), "adm")
                login(fixture.member(), "plain")
            } finally {
                root.detachAppender(appender)
                root.level = previousLevel
                deleteMembersCompletely(created)
                fixture.cleanUp()
            }
            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            (logged.isNotBlank()) shouldBe true
            logged shouldNotContain secretMail
            logged shouldNotContain secretName
            logged shouldNotContain secretSubject
            logged shouldNotContain "Geheimname"
        }
    })
