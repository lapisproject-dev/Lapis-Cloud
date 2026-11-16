package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.member.FoundingMemberStore
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FoundingMemberRules
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V154 founding member marker through a real `MemberService`: who may set it, the date rule, clearing (also of the consent), the member's own
 * consent (flag + ACTIVE + current wording), expiry of the consent on removal and on status loss, the audit entries and the live "publicly
 * effective" condition (SQL and Kotlin twin agree). H2 only.
 */
class FoundingMemberRpcTest :
    FunSpec({
        val fx = PeerFixture()
        val mailer = FakePeerNotificationMailer()
        var visibilityLimiter = FederationInboxRateLimiter(maxRequests = 1000)

        beforeSpec { DatabaseConfig.connect() }
        val createdChapterIds = mutableListOf<Uuid>()
        afterSpec {
            transaction {
                RegionalChapterOfficerTable.deleteWhere { regionalChapterId inList createdChapterIds }
                MemberTable.update({ MemberTable.id inList fx.createdMemberIds }) { it[regionalChapterId] = null }
                RegionalChapterTable.deleteWhere { id inList createdChapterIds }
            }
            fx.cleanUp()
        }
        beforeTest {
            fx.isolateAdmins()
            visibilityLimiter = FederationInboxRateLimiter(maxRequests = 1000)
        }
        afterTest { fx.restoreAdmins() }

        fun memberService(call: ApplicationCall): MemberService =
            MemberService(
                call = call,
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = configuredSmtp(),
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberAddressAdminReadRateLimiter = visibilityLimiter,
                peerNotificationMailer = mailer,
            )

        fun Application.setup() {
            install(StatusPages) {
                exception<AbstractServiceException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause::class.simpleName!!, status = HttpStatusCode.Conflict)
                }
            }
            routing {
                post("/t/fm/{id}/{on}") {
                    val since = call.request.queryParameters["since"]?.let { LocalDate.parse(it) }
                    val dto =
                        memberService(call).setFoundingMember(
                            memberId = call.parameters["id"]!!,
                            foundingMember = call.parameters["on"] == "on",
                            since = since,
                        )
                    call.respondText("OK:${dto.foundingMember}|${dto.foundingMemberSince}|${dto.foundingMemberPublicConsent}")
                }
                post("/t/roster") {
                    val page =
                        memberService(call).listMembersForAdministration(
                            MemberAdminQuery(
                                foundingMemberOnly = call.request.queryParameters["fo"] == "1",
                                limit = 100,
                            ),
                        )
                    call.respondText(
                        "${page.totalCount}|" + page.rows.joinToString(";") { "${it.id}:${it.foundingMember}" },
                    )
                }
                post("/t/fmv/{vis}") {
                    val visibility =
                        network.lapis.cloud.shared.domain.FoundingMemberVisibility
                            .valueOf(call.parameters["vis"]!!)
                    val dto =
                        memberService(call).setOwnFoundingMemberVisibility(
                            visibility = visibility,
                            consentTextVersion = call.request.queryParameters["v"],
                        )
                    call.respondText("OK:${dto.foundingMember}|${dto.visibility}|${dto.consentOutdated}")
                }
            }
        }

        suspend fun HttpClient.act(
            path: String,
            actor: Uuid,
        ): Pair<HttpStatusCode, String> {
            val r = post(path) { header("X-Member-Id", actor.toString()) }
            return r.status to r.bodyAsText()
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun isMarked(id: Uuid) = row(id)[MemberTable.foundingMember]

        fun auditOf(id: Uuid): String = fx.auditJsonFor(id).joinToString("\n")

        fun events(id: Uuid): Int = fx.auditJsonFor(id).count { it.contains("member.founding_member.") }

        val v = FoundingMemberRules.CONSENT_TEXT_VERSION

        fun parseRoster(body: String): Pair<Int, Map<String, Boolean>> {
            val (total, rows) = body.split("|", limit = 2)
            return total.toInt() to
                rows
                    .split(";")
                    .filter { it.isNotBlank() }
                    .associate { it.substringBefore(":") to it.substringAfter(":").toBoolean() }
        }

        test("BOARD sets, changes and clears the marker; each real change writes one audit entry, a no-op none") {
            testApplication {
                application { setup() }
                val m = fx.member()
                val board = fx.member(role = AccountRole.BOARD)
                client.act("/t/fm/$m/on?since=2019-03-01", board) shouldBe (HttpStatusCode.OK to "OK:true|2019-03-01|false")
                events(m) shouldBe 1
                client.act("/t/fm/$m/on?since=2019-03-01", board).first shouldBe HttpStatusCode.OK
                events(m) shouldBe 1
                client.act("/t/fm/$m/on?since=2018-01-01", board).second shouldBe "OK:true|2018-01-01|false"
                auditOf(m) shouldContain "member.founding_member.since_changed"
                client.act("/t/fm/$m/off", board).second shouldBe "OK:false|null|false"
                row(m)[MemberTable.foundingMemberSince] shouldBe null
                auditOf(m) shouldContain "member.founding_member.cleared"
                client.act("/t/fm/$m/off", board).first shouldBe HttpStatusCode.OK
                events(m) shouldBe 3
                auditOf(m) shouldNotContain FoundingMemberRules.CONSENT_TEXT
            }
        }

        test("an ADMIN may mark; MEMBER and TREASURER are Forbidden, also on their own row; nothing is written") {
            testApplication {
                application { setup() }
                val m = fx.member()
                val plain = fx.member()
                val treasurer = fx.member(role = AccountRole.TREASURER)
                client.act("/t/fm/$m/on", fx.member(role = AccountRole.ADMIN)).first shouldBe HttpStatusCode.OK
                val other = fx.member()
                client.act("/t/fm/$other/on", plain) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/fm/$plain/on", plain) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/fm/$other/on", treasurer) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/fm/$treasurer/on", treasurer) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                isMarked(other) shouldBe false
                isMarked(plain) shouldBe false
            }
        }

        test("nobody marks their own row, even BOARD (four-eyes principle)") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                client.act("/t/fm/$board/on", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                isMarked(board) shouldBe false
            }
        }

        test("a date in the future, an anonymised target and a non-member target are a Conflict; clearing a former member works") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/fm/$m/on?since=2999-01-01", board) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                isMarked(m) shouldBe false
                val anon = fx.member()
                transaction { MemberTable.update({ MemberTable.id eq anon }) { it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0) } }
                client.act("/t/fm/$anon/on", board) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                val friend = fx.member(status = MemberStatus.FRIEND)
                client.act("/t/fm/$friend/on", board) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                val former = fx.member(status = MemberStatus.WITHDRAWN)
                transaction { MemberTable.update({ MemberTable.id eq former }) { it[foundingMember] = true } }
                client.act("/t/fm/$former/off", board).second shouldBe "OK:false|null|false"
            }
        }

        test("peer protection: BOARD cannot mark an ADMIN, an ADMIN can") {
            testApplication {
                application { setup() }
                val admin = fx.member(role = AccountRole.ADMIN)
                client.act("/t/fm/$admin/on", fx.member(role = AccountRole.BOARD)) shouldBe
                    (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                isMarked(admin) shouldBe false
                client.act("/t/fm/$admin/on", fx.admin(displayName = "Anderer Admin")).first shouldBe HttpStatusCode.OK
                isMarked(admin) shouldBe true
            }
        }

        test("consent: needs the marker, ACTIVE and the current wording; PRIVATE always works; a repeat is a no-op without audit") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/fmv/PUBLIC?v=$v", m) shouldBe (HttpStatusCode.Conflict to "FoundingMemberNotSetException")
                client.act("/t/fm/$m/on", board).first shouldBe HttpStatusCode.OK
                client.act("/t/fmv/PUBLIC", m) shouldBe (HttpStatusCode.Conflict to "FoundingMemberConsentOutdatedException")
                client.act("/t/fmv/PUBLIC?v=0", m) shouldBe (HttpStatusCode.Conflict to "FoundingMemberConsentOutdatedException")
                client.act("/t/fmv/PUBLIC?v=$v", m) shouldBe (HttpStatusCode.OK to "OK:true|PUBLIC|false")
                FoundingMemberStore.isPublicEffective(row(m)) shouldBe true
                val before = events(m)
                client.act("/t/fmv/PUBLIC?v=$v", m).first shouldBe HttpStatusCode.OK
                events(m) shouldBe before
                client.act("/t/fmv/PRIVATE", m) shouldBe (HttpStatusCode.OK to "OK:true|PRIVATE|false")
                FoundingMemberStore.isPublicEffective(row(m)) shouldBe false
                auditOf(m) shouldContain "member.founding_member.unpublished"
                val friend = fx.member(status = MemberStatus.FRIEND)
                client.act("/t/fmv/PUBLIC?v=$v", friend) shouldBe (HttpStatusCode.Conflict to "FoundingMemberNotEligibleException")
            }
        }

        test("the board removing the marker ends the consent, and marking again later stays private") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/fm/$m/on", board)
                client.act("/t/fmv/PUBLIC?v=$v", m).first shouldBe HttpStatusCode.OK
                client.act("/t/fm/$m/off", board).first shouldBe HttpStatusCode.OK
                auditOf(m) shouldContain "member.founding_member.publication_revoked"
                row(m)[MemberTable.foundingMemberConsentGrantedAt] shouldBe null
                client.act("/t/fm/$m/on", board)
                row(m)[MemberTable.foundingMemberConsentGrantedAt] shouldBe null
                FoundingMemberStore.isPublicEffective(row(m)) shouldBe false
            }
        }

        test("a status loss deletes the consent; a later reactivation does not publish again") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/fm/$m/on", board)
                client.act("/t/fmv/PUBLIC?v=$v", m).first shouldBe HttpStatusCode.OK
                transaction {
                    MemberTable.update({ MemberTable.id eq m }) { it[status] = MemberStatus.WITHDRAWN }
                    // the live condition already hides it; the hook removes the consent itself
                    FoundingMemberStore.isPublicEffective(MemberTable.selectAll().where { MemberTable.id eq m }.single()) shouldBe false
                    FoundingMemberStore.revokePublicationOnStatusLoss(
                        memberId = m,
                        newStatus = MemberStatus.WITHDRAWN,
                        actorMemberId = board,
                        actorRole = AccountRole.BOARD,
                        now = LocalDateTime(2026, 11, 16, 12, 0),
                    )
                    MemberTable.update({ MemberTable.id eq m }) { it[status] = MemberStatus.ACTIVE }
                }
                row(m)[MemberTable.foundingMemberConsentGrantedAt] shouldBe null
                FoundingMemberStore.isPublicEffective(row(m)) shouldBe false
                auditOf(m) shouldContain "member.founding_member.publication_revoked_by_status_change"
            }
        }

        test("the consent for an older wording is not effective; the self RPC is rate limited") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/fm/$m/on", board)
                client.act("/t/fmv/PUBLIC?v=$v", m).first shouldBe HttpStatusCode.OK
                transaction { MemberTable.update({ MemberTable.id eq m }) { it[foundingMemberConsentVersion] = "0" } }
                FoundingMemberStore.isPublicEffective(row(m)) shouldBe false
                client.act("/t/fmv/PRIVATE", m).second shouldBe "OK:true|PRIVATE|false"
                visibilityLimiter = FederationInboxRateLimiter(maxRequests = 2)
                client.act("/t/fmv/PRIVATE", m).first shouldBe HttpStatusCode.OK
                client.act("/t/fmv/PRIVATE", m).first shouldBe HttpStatusCode.OK
                client.act("/t/fmv/PRIVATE", m) shouldBe (HttpStatusCode.Conflict to "FoundingMemberRateLimitedException")
            }
        }

        test("roster filter foundingMemberOnly: BOARD sees only marked members, total count matches, off lists unmarked too") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val marked = fx.member()
                val plain = fx.member()
                transaction { MemberTable.update({ MemberTable.id eq marked }) { it[foundingMember] = true } }
                val (total, rows) = parseRoster(client.act("/t/roster?fo=1", board).second)
                (marked.toString() in rows) shouldBe true
                (plain.toString() in rows) shouldBe false
                rows.values.all { it } shouldBe true
                total shouldBe
                    transaction {
                        MemberTable
                            .selectAll()
                            .where { MemberTable.foundingMember eq true }
                            .count()
                            .toInt()
                    }
                val (_, unfiltered) = parseRoster(client.act("/t/roster", board).second)
                (plain.toString() in unfiltered) shouldBe true
            }
        }

        test("roster filter foundingMemberOnly: a chapter-scoped officer sees only marked members of their own chapter") {
            testApplication {
                application { setup() }
                val chapterA = Uuid.random()
                val chapterB = Uuid.random()
                transaction {
                    for (c in listOf(chapterA, chapterB)) {
                        RegionalChapterTable.insert {
                            it[RegionalChapterTable.id] = c
                            it[name] = "FM-Chapter-$c"
                            it[nameKey] = "fm-chapter-$c".lowercase()
                            it[createdAt] = DbClock.nowLocalDateTime()
                        }
                        createdChapterIds += c
                    }
                }
                val officer = fx.member()
                transaction { MemberTable.update({ MemberTable.id eq officer }) { it[regionalChapterId] = chapterA } }
                transaction {
                    RegionalChapterOfficerTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = officer
                        it[regionalChapterId] = chapterA
                        it[grantedAt] = DbClock.nowLocalDateTime()
                        it[grantedByMemberId] = officer
                        it[revokedAt] = null
                        it[activeForMemberId] = officer
                    }
                }
                val markedA = fx.member()
                val plainA = fx.member()
                val markedB = fx.member()
                transaction {
                    MemberTable.update({ MemberTable.id eq markedA }) {
                        it[regionalChapterId] = chapterA
                        it[foundingMember] = true
                    }
                    MemberTable.update({ MemberTable.id eq plainA }) { it[regionalChapterId] = chapterA }
                    MemberTable.update({ MemberTable.id eq markedB }) {
                        it[regionalChapterId] = chapterB
                        it[foundingMember] = true
                    }
                }
                val (total, rows) = parseRoster(client.act("/t/roster?fo=1", officer).second)
                rows.keys shouldBe setOf(markedA.toString())
                total shouldBe 1
                val (_, unfiltered) = parseRoster(client.act("/t/roster", officer).second)
                (plainA.toString() in unfiltered) shouldBe true
                (markedB.toString() in unfiltered) shouldBe false
            }
        }

        test("the SQL condition and its Kotlin twin agree over the whole matrix") {
            val ids = mutableListOf<Uuid>()
            for (status in listOf(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN)) {
                for (flag in listOf(true, false)) {
                    for (consent in listOf<String?>(null, "1", "0")) {
                        if (!flag && consent != null) continue
                        for (anon in listOf(false, true)) {
                            val id = fx.member(status = status)
                            ids += id
                            transaction {
                                MemberTable.update({ MemberTable.id eq id }) {
                                    it[foundingMember] = flag
                                    if (consent != null) {
                                        it[foundingMemberConsentGrantedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                                        it[foundingMemberConsentVersion] = consent
                                    }
                                    if (anon) it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                                }
                            }
                        }
                    }
                }
            }
            transaction {
                val sql =
                    MemberTable
                        .selectAll()
                        .where { FoundingMemberStore.publicEffectiveCondition() }
                        .map { it[MemberTable.id] }
                        .filter { it in ids }
                        .toSet()
                val kotlinSide =
                    MemberTable
                        .selectAll()
                        .toList()
                        .filter { it[MemberTable.id] in ids && FoundingMemberStore.isPublicEffective(it) }
                        .map { it[MemberTable.id] }
                        .toSet()
                sql shouldBe kotlinSide
                sql.size shouldBe 1
            }
        }
    })
