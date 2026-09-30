package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OwnPublicProfileDto
import network.lapis.cloud.shared.domain.PublicListingPlace
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val CURRENT = MemberPublicBioRules.CONSENT_TEXT_VERSION

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- [MemberPublicProfileService]: eligibility, the consent
 * lifecycle of the short introduction (save / publish / withdraw / delete), "Q3" (a text change keeps
 * the consent), the ex-officer rule (still editable and deletable, never newly creatable), moderation
 * authorization without an existence oracle, audit entries that never carry the text, and rate limits.
 * Driven through a throwaway route -- the house style of the other `*ServiceTest` files.
 */
class MemberPublicProfileServiceTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun routes(
            writeLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 60.minutes),
            moderationLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 60.minutes),
        ): Application.() -> Unit =
            {
                install(ContentNegotiation) { json() }
                routing {
                    post("/t/{op}") {
                        val service =
                            MemberPublicProfileService(
                                call = call,
                                writeRateLimiter = writeLimiter,
                                moderationRateLimiter = moderationLimiter,
                            )
                        val q = call.request.queryParameters
                        try {
                            when (call.parameters["op"]) {
                                "get" -> call.respond(service.getOwnPublicProfile())
                                "save" -> call.respond(service.saveOwnBio(call.receiveText()))
                                "public" -> call.respond(service.setOwnBioPublic(visible = true, consentTextVersion = q["v"]))
                                "private" -> call.respond(service.setOwnBioPublic(visible = false, consentTextVersion = null))
                                "moderate" -> {
                                    service.moderationRemoveBio(q["id"]!!)
                                    call.respondText("ok")
                                }
                                else -> call.respondText("?", status = HttpStatusCode.NotFound)
                            }
                        } catch (e: AbstractServiceException) {
                            call.respondText(e::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                }
            }

        suspend fun HttpClient.op(
            member: Uuid?,
            op: String,
            query: String = "",
            body: String? = null,
        ): HttpResponse =
            post("/t/$op$query") {
                if (member != null) header("X-Member-Id", member.toString())
                if (body != null) setBody(body)
            }

        suspend fun HttpResponse.dto(): OwnPublicProfileDto = Json.decodeFromString(OwnPublicProfileDto.serializer(), bodyAsText())

        test("a board member is eligible: role label, BOARD listed, no bio yet") {
            testApplication {
                application(routes())
                val member = fixtures.newMember(displayName = "Vera Vorsitz")
                fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
                val dto = client.op(member, "get").dto()
                dto.eligible shouldBe true
                dto.isBoardMember shouldBe true
                dto.isPolitician shouldBe false
                dto.roleLabel shouldBe "Vorsitz"
                dto.displayName shouldBe "Vera Vorsitz"
                dto.bioText shouldBe null
                dto.bioPublic shouldBe false
                dto.publicOn shouldContainExactly listOf(PublicListingPlace.BOARD)
                dto.requiredConsentTextVersion shouldBe CURRENT
            }
        }

        test("an ordinary member is not eligible and may not create a bio") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                val dto = client.op(member, "get").dto()
                dto.eligible shouldBe false
                dto.publicOn shouldBe emptyList()
                val saved = client.op(member, "save", body = "Ein Text")
                saved.status shouldBe HttpStatusCode.Conflict
                saved.bodyAsText() shouldBe "MemberPublicBioNotEligibleException"
                fixtures.bioRowCount(member) shouldBe 0L
            }
        }

        test("save, publish, withdraw: the consent is stored with the current version and withdrawal clears it") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                val saved = client.op(member, "save", body = "  Ich bin seit 2010 dabei.  ").dto()
                saved.bioText shouldBe "Ich bin seit 2010 dabei."
                saved.bioPublic shouldBe false

                val published = client.op(member, "public", "?v=$CURRENT").dto()
                published.bioPublic shouldBe true
                published.bioConsentOutdated shouldBe false
                fixtures.bioRow(member)!![MemberPublicBioTable.consentTextVersion] shouldBe CURRENT

                val withdrawn = client.op(member, "private").dto()
                withdrawn.bioPublic shouldBe false
                withdrawn.bioText shouldBe "Ich bin seit 2010 dabei."
                fixtures.bioRow(member)!![MemberPublicBioTable.consentGrantedAt] shouldBe null
                fixtures.bioRow(member)!![MemberPublicBioTable.consentTextVersion] shouldBe null
            }
        }

        test("a stale or missing consent version is refused with the outdated exception") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                client.op(member, "save", body = "Text")
                val stale = client.op(member, "public", "?v=member-bio-public-v0")
                stale.status shouldBe HttpStatusCode.Conflict
                stale.bodyAsText() shouldBe "MemberPublicBioConsentOutdatedException"
                val missing = client.op(member, "public")
                missing.bodyAsText() shouldBe "MemberPublicBioConsentOutdatedException"
                fixtures.bioRow(member)!![MemberPublicBioTable.consentGrantedAt] shouldBe null
            }
        }

        test("publishing without a stored text is refused with the missing exception") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                val response = client.op(member, "public", "?v=$CURRENT")
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "MemberPublicBioMissingException"
            }
        }

        test("Q3: editing the text keeps an existing consent (the author writes and previews it themselves)") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                client.op(member, "save", body = "Erste Fassung")
                client.op(member, "public", "?v=$CURRENT")
                val edited = client.op(member, "save", body = "Zweite Fassung").dto()
                edited.bioText shouldBe "Zweite Fassung"
                edited.bioPublic shouldBe true
            }
        }

        test("a blank text deletes the row INCLUDING the consent, and audits DELETED") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                client.op(member, "save", body = "Text")
                client.op(member, "public", "?v=$CURRENT")
                val deleted = client.op(member, "save", body = "   \n ").dto()
                deleted.bioText shouldBe null
                deleted.bioPublic shouldBe false
                fixtures.bioRowCount(member) shouldBe 0L
                fixtures.memberAuditAfterSnapshots(member).last() shouldContain "DELETED"
            }
        }

        test("validation: too long, too many line breaks and control characters are refused and store nothing") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                for (bad in listOf("a".repeat(501), (1..12).joinToString("\n") { "zeile$it" }, "a\u0000b", "a‮b")) {
                    val response = client.op(member, "save", body = bad)
                    response.status shouldBe HttpStatusCode.Conflict
                    response.bodyAsText() shouldBe "MemberPublicBioValidationException"
                }
                fixtures.bioRowCount(member) shouldBe 0L
                // Exactly 500 emojis (code points) is fine even though it is 1000 UTF-16 units.
                val ok = client.op(member, "save", body = "😀".repeat(500))
                ok.status shouldBe HttpStatusCode.OK
            }
        }

        test("ex-officer rule: a stored text can still be edited and deleted after the mandate ended, but never newly created") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member, until = kotlinx.datetime.LocalDate(2021, 1, 1))
                val notEligible = client.op(member, "get").dto()
                notEligible.eligible shouldBe false
                client.op(member, "save", body = "neu").bodyAsText() shouldBe "MemberPublicBioNotEligibleException"

                fixtures.seedBio(memberId = member, text = "Altbestand")
                val still = client.op(member, "get").dto()
                still.eligible shouldBe false
                still.bioText shouldBe "Altbestand"
                client.op(member, "save", body = "Geaendert").dto().bioText shouldBe "Geaendert"
                client.op(member, "save", body = "").dto().bioText shouldBe null
            }
        }

        test("a member who is no longer ACTIVE may withdraw but not publish") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                fixtures.seedBio(memberId = member, text = "Text", publish = true)
                fixtures.setStatus(memberId = member, status = MemberStatus.WITHDRAWN)
                client.op(member, "public", "?v=$CURRENT").bodyAsText() shouldBe "MemberPublicBioNotEligibleException"
                client.op(member, "private").dto().bioPublic shouldBe false
            }
        }

        test("a politician is eligible: office text shortened to one line, POLITICIANS listed only with an effective listing consent") {
            testApplication {
                application(routes())
                val member = fixtures.newMember(displayName = "Paul Politiker")
                fixtures.makePolitician(memberId = member, mandateText = "Mitglied\nder   Landesregierung " + "x".repeat(400))
                val before = client.op(member, "get").dto()
                before.eligible shouldBe true
                before.isPolitician shouldBe true
                before.isBoardMember shouldBe false
                before.roleLabel shouldBe null
                (before.office!!.startsWith("Mitglied der Landesregierung ")) shouldBe true
                (before.office!!.codePointCount(0, before.office!!.length) <= 200) shouldBe true
                before.politicianListingEffective shouldBe false
                before.publicOn shouldBe emptyList()

                fixtures.grantConsent(memberId = member)
                val after = client.op(member, "get").dto()
                after.politicianListingEffective shouldBe true
                after.publicOn shouldContainExactly listOf(PublicListingPlace.POLITICIANS)

                val stale = fixtures.newMember()
                fixtures.makePolitician(memberId = stale)
                fixtures.grantConsent(memberId = stale, stale = true)
                client.op(stale, "get").dto().politicianListingEffective shouldBe false
            }
        }

        test("a former politician (FORMER) is not eligible") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.makePolitician(memberId = member, status = network.lapis.cloud.shared.domain.PoliticianProfileStatus.FORMER)
                client.op(member, "get").dto().eligible shouldBe false
            }
        }

        test("audit entries carry the action, the state and the version -- never the text") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                val secret = "GEHEIMER-BIO-TEXT-DER-NIE-IM-AUDIT-STEHEN-DARF"
                client.op(member, "save", body = secret)
                client.op(member, "public", "?v=$CURRENT")
                client.op(member, "private")
                client.op(member, "save", body = "")
                val snapshots = fixtures.memberAuditAfterSnapshots(member)
                (snapshots.size >= 4) shouldBe true
                snapshots.forEach { it shouldNotContain secret }
                snapshots.any { it.contains("SAVED") } shouldBe true
                snapshots.any { it.contains("PUBLISHED") } shouldBe true
                snapshots.any { it.contains("UNPUBLISHED") } shouldBe true
                snapshots.any { it.contains("DELETED") } shouldBe true
                snapshots.any { it.contains(CURRENT) } shouldBe true
            }
        }

        test("moderation: BOARD and ADMIN remove, MEMBER and TREASURER are forbidden, an unknown id is an indistinguishable ok") {
            testApplication {
                application(routes())
                val target = fixtures.newMember()
                fixtures.addBoardSeat(memberId = target)
                fixtures.seedBio(memberId = target, text = "Zu entfernen", publish = true)
                val member = fixtures.newMember(role = AccountRole.MEMBER)
                val treasurer = fixtures.newMember(role = AccountRole.TREASURER)
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val admin = fixtures.newMember(role = AccountRole.ADMIN)

                client.op(member, "moderate", "?id=$target").bodyAsText() shouldBe "ForbiddenException"
                client.op(treasurer, "moderate", "?id=$target").bodyAsText() shouldBe "ForbiddenException"
                client.op(null, "moderate", "?id=$target").status shouldBe HttpStatusCode.Conflict
                fixtures.bioRowCount(target) shouldBe 1L

                client.op(board, "moderate", "?id=$target").bodyAsText() shouldBe "ok"
                fixtures.bioRowCount(target) shouldBe 0L
                fixtures.memberAuditAfterSnapshots(target).last() shouldContain "REMOVED_BY_MODERATION"

                // Idempotent and oracle-free: nothing to remove, unknown id, both answer exactly like a success.
                client.op(admin, "moderate", "?id=$target").bodyAsText() shouldBe "ok"
                client.op(admin, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "ok"
                client.op(admin, "moderate", "?id=not-a-uuid").bodyAsText() shouldBe "NotFoundException"
            }
        }

        test("rate limits: the write budget and the moderation budget both answer with the rate-limited exception") {
            testApplication {
                application(
                    routes(
                        writeLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes),
                        moderationLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 60.minutes),
                    ),
                )
                val member = fixtures.newMember()
                fixtures.addBoardSeat(memberId = member)
                client.op(member, "save", body = "eins").status shouldBe HttpStatusCode.OK
                client.op(member, "save", body = "zwei").status shouldBe HttpStatusCode.OK
                client.op(member, "save", body = "drei").bodyAsText() shouldBe "MemberPublicBioRateLimitedException"

                val board = fixtures.newMember(role = AccountRole.BOARD)
                client.op(board, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "ok"
                client.op(board, "moderate", "?id=${Uuid.random()}").bodyAsText() shouldBe "MemberPublicBioRateLimitedException"
            }
        }
    })
