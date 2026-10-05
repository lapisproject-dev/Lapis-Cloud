package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.rpc.ForbiddenException

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the two moderation-presence flags `hasPhoto`/`hasPublicBio`
 * of `MemberAdminRowDto`: filled for BOARD and ADMIN, ALWAYS `false` for a TREASURER (the moderation
 * RPCs are BOARD/ADMIN-only, so the flags would be a pointless existence oracle for anyone else), and
 * never a carrier of content.
 */
class MemberAdminModerationFlagsTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()
        val photoStorage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("roster-flags"))

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup(photoStorage = photoStorage) }

        val routes: io.ktor.server.application.Application.() -> Unit = {
            install(StatusPages) {
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            }
            routing {
                get("/test/flags") {
                    val service =
                        MemberService(
                            call = call,
                            passwordResetMailer = FakePasswordResetMailer(),
                            adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                            smtpConfigState = SmtpConfigState.NotConfigured,
                            adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                            adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                            adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                            memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                            memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                            regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                        )
                    val page =
                        service.listMembersForAdministration(MemberAdminQuery(search = call.request.queryParameters["search"], limit = 50))
                    call.respondText(page.rows.joinToString(";") { "${it.displayName}=${it.hasPhoto}/${it.hasPublicBio}" })
                }
            }
        }

        test("BOARD and ADMIN see the presence of a photo and of a public bio; a member with neither shows false/false") {
            testApplication {
                application(routes)
                val both = fixtures.newMember(displayName = "FlagBeides Berta")
                fixtures.seedPhoto(storage = photoStorage, memberId = both, publish = false)
                fixtures.seedBio(memberId = both, text = "privater Text", publish = false)
                val photoOnly = fixtures.newMember(displayName = "FlagFoto Fritz")
                fixtures.seedPhoto(storage = photoStorage, memberId = photoOnly, publish = true)
                val bioOnly = fixtures.newMember(displayName = "FlagBio Bruno")
                fixtures.seedBio(memberId = bioOnly, text = "noch ein Text", publish = true)
                fixtures.newMember(displayName = "FlagNichts Nina")

                for (actor in listOf(BOARD_ID, ADMIN_ID)) {
                    val body = client.get("/test/flags?search=Flag") { header("X-Member-Id", actor) }.bodyAsText()
                    val rows = body.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }
                    rows["FlagBeides Berta"] shouldBe "true/true"
                    rows["FlagFoto Fritz"] shouldBe "true/false"
                    rows["FlagBio Bruno"] shouldBe "false/true"
                    rows["FlagNichts Nina"] shouldBe "false/false"
                    // Never any content: only the flags travel.
                    body.contains("privater Text") shouldBe false
                    body.contains("noch ein Text") shouldBe false
                }
            }
        }

        test("a TREASURER gets false/false for everybody -- the flags are a BOARD/ADMIN-only signal") {
            testApplication {
                application(routes)
                val both = fixtures.newMember(displayName = "FlagKasse Karl")
                fixtures.seedPhoto(storage = photoStorage, memberId = both, publish = false)
                fixtures.seedBio(memberId = both, text = "Text", publish = true)
                val body = client.get("/test/flags?search=FlagKasse") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                body shouldBe "FlagKasse Karl=false/false"
            }
        }
    })
