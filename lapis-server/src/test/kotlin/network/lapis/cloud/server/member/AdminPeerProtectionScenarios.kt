package network.lapis.cloud.server.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NoSecondAdminException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PeerProtectionDeniedException
import network.lapis.cloud.shared.rpc.PrivilegedActionStateException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private const val GOOD_REASON = "Passwort vergessen, Telefonat protokolliert"

/** Runs [block] with the server clock pinned [by] after the real now (the pinned clock does not advance inside the block). */
private inline fun <T> after(
    by: Duration,
    block: () -> T,
): T = TimeTestSupport.withServerClock(instant = (Clock.System.now() + by).toString(), block = block)

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the four-eyes lifecycle ([PrivilegedActionService]) against a real database. Written as
 * scenarios so the SAME assertions run on H2 and on PostgreSQL (stricter there: lock timeouts, the deadlock guard of the lane).
 * Every test isolates the administrators of the database ([PeerFixture.isolateAdmins]) so it controls exactly who may approve.
 */
abstract class AdminPeerProtectionScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PeerFixture()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }
        beforeTest { fx.isolateAdmins() }
        afterTest { fx.restoreAdmins() }

        /** Runs all [tasks] at the same instant; a thrown exception is a failure value. */
        fun <T> race(vararg tasks: () -> T): List<Result<T>> {
            val pool = Executors.newFixedThreadPool(tasks.size)
            try {
                val barrier = CyclicBarrier(tasks.size)
                val futures =
                    tasks.map { task ->
                        pool.submit<Result<T>> {
                            barrier.await(20, TimeUnit.SECONDS)
                            runCatching { task() }
                        }
                    }
                return futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        // ───────────────────────────── no second administrator ─────────────────────────────

        test("with one or two ADMINs every four-eyes request ends in NoSecondAdmin -- nothing is written, nobody is locked out") {
            val a = fx.admin()
            val b = fx.admin()
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            shouldThrow<NoSecondAdminException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            }
            shouldThrow<NoSecondAdminException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<NoSecondAdminException> {
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = GOOD_REASON,
                )
            }
            fx.requestsOf(b).shouldBeEmpty()
            mailer.totalMails shouldBe 0
            fx.roleOf(b) shouldBe AccountRole.ADMIN
            fx.statusOf(b) shouldBe MemberStatus.ACTIVE
        }

        test("an ADMIN cannot request anything against themselves, and a non-ADMIN target uses the direct path") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            shouldThrow<PeerProtectionDeniedException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = a.toString(), reasonRaw = GOOD_REASON)
            }
            shouldThrow<PeerProtectionDeniedException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = a.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            val plain = fx.member()
            shouldThrow<PrivilegedActionStateException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = plain.toString(), reasonRaw = GOOD_REASON)
            }
            val noAccount = fx.member(role = null)
            shouldThrow<PrivilegedActionStateException> {
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = noAccount.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = GOOD_REASON,
                )
            }
            fx.requestsOf(plain).shouldBeEmpty()
            (b != c) shouldBe true
        }

        // ───────────────────────────── temporary password: the full cycle ─────────────────────────────

        test(
            "TEMP_PASSWORD: request, approval by a third ADMIN, objection period, generation once -- sessions and reset tokens end, audit stays clean",
        ) {
            val a = fx.admin(displayName = "Antragsteller")
            val b = fx.admin(displayName = "Ziel Admin")
            val c = fx.admin(displayName = "Freigeber")
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            val oldHash = fx.passwordHashOf(b)

            val requested = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            requested.status shouldBe PrivilegedActionStatus.PENDING
            requested.action shouldBe PrivilegedActionKind.TEMP_PASSWORD
            // the target is warned first and gets the objection link; the only eligible approver is told, the target is not asked to approve
            val warning = mailer.requestMails.single()
            warning.actorName shouldBe "Antragsteller"
            mailer.approvalMails.map { it.targetName }.toSet() shouldBe setOf("Ziel Admin")
            mailer.approvalMails shouldHaveSize 1
            fx.passwordHashOf(b) shouldBe oldHash

            // self-approval and approval by the target are refused
            shouldThrow<ForbiddenException> { svc.approve(actor = fx.actor(id = a), requestIdRaw = requested.id) }
            shouldThrow<ForbiddenException> { svc.approve(actor = fx.actor(id = b), requestIdRaw = requested.id) }

            val approved = svc.approve(actor = fx.actor(id = c), requestIdRaw = requested.id)
            approved.status shouldBe PrivilegedActionStatus.APPROVED_WAITING
            (approved.notBefore != null && approved.executeUntil != null) shouldBe true
            fx.passwordHashOf(b) shouldBe oldHash

            // before the objection period has elapsed nothing can be generated
            shouldThrow<PrivilegedActionStateException> {
                svc.executeTemporaryPassword(
                    actor = fx.actor(id = a),
                    requestIdRaw = requested.id,
                )
            }
            // only the requester may generate it
            after(by = 25.hours) {
                shouldThrow<PrivilegedActionStateException> {
                    svc.executeTemporaryPassword(
                        actor = fx.actor(id = c),
                        requestIdRaw = requested.id,
                    )
                }
                shouldThrow<PrivilegedActionStateException> {
                    svc.executeTemporaryPassword(
                        actor = fx.actor(id = b),
                        requestIdRaw = requested.id,
                    )
                }
            }
            // the target's session and an outstanding reset token exist at the moment of generation (created inside the pinned clock:
            // a session is 8 hours long, so one made at the real "now" would already be expired 25 hours later)
            val result =
                after(by = 25.hours) {
                    val targetSession = SessionStore.createSession(b)
                    val resetToken = PasswordResetTokenStore.createToken(b)
                    SessionStore.resolve(targetSession.rawToken) shouldBe fx.actor(id = b)
                    val generated = svc.executeTemporaryPassword(actor = fx.actor(id = a), requestIdRaw = requested.id)
                    SessionStore.resolve(targetSession.rawToken) shouldBe null
                    PasswordResetTokenStore.peekMemberId(resetToken) shouldBe null
                    generated.revokedSessionCount shouldBe 1
                    generated
                }
            (result.generatedPassword.length >= 12) shouldBe true
            PasswordHasher.verify(rawPassword = result.generatedPassword, storedHash = fx.passwordHashOf(b)) shouldBe true
            fx.statusOfRequest(requested.id) shouldBe "EXECUTED"
            mailer.executedMails.single().event shouldBe PeerExecutedEvent.TEMPORARY_PASSWORD_SET
            fx.openRequests(b) shouldBe 0L

            // a second generation of the same request is impossible
            after(by = 26.hours) {
                shouldThrow<PrivilegedActionStateException> {
                    svc.executeTemporaryPassword(
                        actor = fx.actor(id = a),
                        requestIdRaw = requested.id,
                    )
                }
            }

            // the audit chain tells the story without the reason, a token, the password or an address
            val audit = fx.auditJsonFor(b).joinToString("\n")
            audit shouldContain "\"event\":\"REQUESTED\""
            audit shouldContain "\"event\":\"APPROVED\""
            audit shouldContain "\"event\":\"EXECUTED\""
            audit shouldNotContain GOOD_REASON
            audit shouldNotContain result.generatedPassword
            audit shouldNotContain warning.rawVetoToken
            audit shouldNotContain "@"
        }

        test("TEMP_PASSWORD without outbound mail is refused (no objection channel) and writes nothing") {
            val a = fx.admin()
            val b = fx.admin()
            fx.admin()
            val svc = fx.service(smtp = network.lapis.cloud.server.mail.SmtpConfigState.NotConfigured)
            shouldThrow<PeerProtectionDeniedException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            }
            fx.requestsOf(b).shouldBeEmpty()
        }

        test("a target warning the queue dropped withdraws the request: no confirmable change nobody was warned about") {
            val a = fx.admin()
            val b = fx.admin()
            fx.admin()
            val mailer = FakePeerNotificationMailer(targetStatus = DeliveryStatus.FAILED)
            val svc = fx.service(mailer = mailer)
            shouldThrow<PeerProtectionDeniedException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            }
            fx.requestsOf(b).single()[network.lapis.cloud.server.db.generated.PrivilegedActionRequestTable.status] shouldBe "WITHDRAWN"
            fx.openRequests(b) shouldBe 0L
            mailer.approvalMails.shouldBeEmpty()
        }

        test("the target's objection ends the request at any point; wrong, used and foreign tokens do nothing (one answer)") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            val first = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            val token = mailer.requestMails.last().rawVetoToken
            svc.vetoByLink("not-a-token") shouldBe VetoResult.INVALID
            svc.vetoByLink("") shouldBe VetoResult.INVALID
            fx.statusOfRequest(first.id) shouldBe "PENDING"
            svc.approve(actor = fx.actor(id = c), requestIdRaw = first.id)
            fx.statusOfRequest(first.id) shouldBe "APPROVED_WAITING"
            svc.vetoByLink(token) shouldBe VetoResult.VETOED
            fx.statusOfRequest(first.id) shouldBe "VETOED"
            svc.vetoByLink(token) shouldBe VetoResult.INVALID
            after(by = 30.hours) {
                shouldThrow<PrivilegedActionStateException> {
                    svc.executeTemporaryPassword(
                        actor = fx.actor(id = a),
                        requestIdRaw = first.id,
                    )
                }
            }
            fx.openRequests(b) shouldBe 0L
            // the objection is audited (no actor: the person behind the link is unauthenticated)
            fx.auditJsonFor(b).joinToString("\n") shouldContain "\"event\":\"VETOED\""
            // the next request gets a fresh token; the old one stays dead
            val second = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            svc.vetoByLink(token) shouldBe VetoResult.INVALID
            fx.statusOfRequest(second.id) shouldBe "PENDING"
        }

        // ───────────────────────────── demotion and suspension ─────────────────────────────

        test("DEMOTE takes effect at once on the approval; the target is told; role_changed_at is stamped") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            val requested =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            // no advance warning to a target who could do nothing against it
            mailer.requestMails.shouldBeEmpty()
            mailer.approvalMails shouldHaveSize 1
            fx.roleOf(b) shouldBe AccountRole.ADMIN
            val done = svc.approve(actor = fx.actor(id = c), requestIdRaw = requested.id)
            done.status shouldBe PrivilegedActionStatus.EXECUTED
            fx.roleOf(b) shouldBe AccountRole.MEMBER
            (fx.roleChangedAtOf(b) != null) shouldBe true
            mailer.executedMails.single().event shouldBe PeerExecutedEvent.ROLE_CHANGED
            val audit = fx.auditJsonFor(b).joinToString("\n")
            audit shouldContain "\"event\":\"EXECUTED\""
            audit shouldNotContain GOOD_REASON
        }

        test("SUSPEND takes effect at once on the approval; sessions end; the audit is clean") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            val session = SessionStore.createSession(b)
            val requested =
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = GOOD_REASON,
                )
            svc.approve(actor = fx.actor(id = c), requestIdRaw = requested.id).status shouldBe PrivilegedActionStatus.EXECUTED
            fx.statusOf(b) shouldBe MemberStatus.WITHDRAWN
            SessionStore.resolve(session.rawToken) shouldBe null
            mailer.executedMails.single().event shouldBe PeerExecutedEvent.ACCESS_SUSPENDED
            fx.auditJsonFor(b).joinToString("\n") shouldNotContain GOOD_REASON
        }

        test(
            "the last-admin protection still bites in the approval path (the requester counts only while they are a login-capable ADMIN)",
        ) {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val onB =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            svc.approve(actor = fx.actor(id = c), requestIdRaw = onB.id)
            // B is gone; now a request of A against C needs a SECOND administrator, and there is none (B was demoted)
            shouldThrow<NoSecondAdminException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = c.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            fx.roleOf(a) shouldBe AccountRole.ADMIN
            fx.roleOf(c) shouldBe AccountRole.ADMIN
        }

        // ───────────────────────────── tenure: the strawman cannot approve ─────────────────────────────

        test("an ADMIN who got the role less than 7 days before the request cannot approve; after 7 days they can") {
            val a = fx.admin()
            val b = fx.admin()
            // a strawman created just now (role_changed_at = now)
            val strawman = fx.admin(roleChangedAt = fx.createdAtNow())
            val mailer = FakePeerNotificationMailer()
            val svc = fx.service(mailer = mailer)
            // the only possible approver is the fresh one -- not eligible, so there is none at all
            shouldThrow<NoSecondAdminException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            // 7 days and a second later the same person is a legitimate approver
            after(by = 7.days + 1.hours) {
                val req =
                    svc.requestDemotion(
                        actor = fx.actor(id = a),
                        targetIdRaw = b.toString(),
                        newRole = AccountRole.MEMBER,
                        reasonRaw = GOOD_REASON,
                    )
                // approved BY the strawman, at that time tenured
                svc.approve(actor = fx.actor(id = strawman), requestIdRaw = req.id).status shouldBe PrivilegedActionStatus.EXECUTED
            }
            fx.roleOf(b) shouldBe AccountRole.MEMBER
        }

        test("a strawman created after the request cannot approve it: the tenure is measured against the REQUEST time") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            val strawman = fx.admin(roleChangedAt = fx.createdAtNow())
            shouldThrow<ForbiddenException> { svc.approve(actor = fx.actor(id = strawman), requestIdRaw = req.id) }
            fx.statusOfRequest(req.id) shouldBe "PENDING"
            svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id).status shouldBe PrivilegedActionStatus.EXECUTED
        }

        test("a login-blocked ADMIN and a demoted ADMIN are no approvers") {
            val a = fx.admin()
            val b = fx.admin()
            val blocked = fx.admin(status = MemberStatus.WITHDRAWN)
            val svc = fx.service()
            shouldThrow<NoSecondAdminException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            val c = fx.admin()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            shouldThrow<ForbiddenException> { svc.approve(actor = fx.actor(id = blocked), requestIdRaw = req.id) }
            // C loses the role before approving: the role is re-read under lock, never trusted from the caller object
            transaction {
                network.lapis.cloud.server.db.generated.AccountTable
                    .update({ network.lapis.cloud.server.db.generated.AccountTable.memberId eq c }) {
                        it[role] = AccountRole.BOARD
                    }
            }
            shouldThrow<ForbiddenException> { svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id) }
            fx.statusOfRequest(req.id) shouldBe "PENDING"
        }

        // ───────────────────────────── facts change underneath a request ─────────────────────────────

        test("the target is demoted by another route before the approval: the request becomes INVALIDATED, nothing is executed") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = GOOD_REASON,
                )
            transaction {
                network.lapis.cloud.server.db.generated.AccountTable
                    .update({ network.lapis.cloud.server.db.generated.AccountTable.memberId eq b }) { it[role] = AccountRole.BOARD }
            }
            val result = svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id)
            result.status shouldBe PrivilegedActionStatus.INVALIDATED
            fx.statusOf(b) shouldBe MemberStatus.ACTIVE
            fx.openRequests(b) shouldBe 0L
        }

        test("the requester loses the ADMIN role before the approval: INVALIDATED") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            transaction {
                network.lapis.cloud.server.db.generated.AccountTable
                    .update({ network.lapis.cloud.server.db.generated.AccountTable.memberId eq a }) { it[role] = AccountRole.BOARD }
            }
            svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id).status shouldBe PrivilegedActionStatus.INVALIDATED
            fx.roleOf(b) shouldBe AccountRole.ADMIN
        }

        // ───────────────────────────── expiry, withdraw, reject ─────────────────────────────

        test("a request lapses after 72 hours: approval is refused, the poller finishes it as EXPIRED, a new one can be made") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            after(by = 73.hours) {
                shouldThrow<PrivilegedActionStateException> { svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id) }
                (
                    svc.runDue(
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime(),
                    ) >= 1
                ) shouldBe true
            }
            fx.statusOfRequest(req.id) shouldBe "EXPIRED"
            fx.openRequests(b) shouldBe 0L
            fx.roleOf(b) shouldBe AccountRole.ADMIN
            svc
                .requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                ).status shouldBe
                PrivilegedActionStatus.PENDING
        }

        test("a lapsed request the poller has not touched yet does not block a new one (lazy expiry)") {
            val a = fx.admin()
            val b = fx.admin()
            fx.admin()
            val svc = fx.service()
            val old =
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.DONOR,
                    reasonRaw = GOOD_REASON,
                )
            val fresh =
                after(by = 80.hours) {
                    svc.requestSuspension(
                        actor = fx.actor(id = a),
                        targetIdRaw = b.toString(),
                        newStatus = MemberStatus.DONOR,
                        reasonRaw = GOOD_REASON,
                    )
                }
            fx.statusOfRequest(old.id) shouldBe "EXPIRED"
            fx.statusOfRequest(fresh.id) shouldBe "PENDING"
        }

        test("an approved temporary password that was not generated within its window expires") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id)
            after(by = 24.hours + 72.hours + 1.hours) {
                shouldThrow<PrivilegedActionStateException> {
                    svc.executeTemporaryPassword(
                        actor = fx.actor(id = a),
                        requestIdRaw = req.id,
                    )
                }
                (
                    svc.runDue(
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime(),
                    ) >= 1
                ) shouldBe true
            }
            fx.statusOfRequest(req.id) shouldBe "EXPIRED"
        }

        test("withdraw: only the requester; reject: only an eligible approver and only a PENDING request") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            shouldThrow<PrivilegedActionStateException> { svc.withdraw(actor = fx.actor(id = c), requestIdRaw = req.id) }
            shouldThrow<ForbiddenException> { svc.reject(actor = fx.actor(id = a), requestIdRaw = req.id) }
            shouldThrow<ForbiddenException> { svc.reject(actor = fx.actor(id = b), requestIdRaw = req.id) }
            svc.reject(actor = fx.actor(id = c), requestIdRaw = req.id).status shouldBe PrivilegedActionStatus.REJECTED
            shouldThrow<PrivilegedActionStateException> { svc.withdraw(actor = fx.actor(id = a), requestIdRaw = req.id) }
            val again =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            svc.withdraw(actor = fx.actor(id = a), requestIdRaw = again.id).status shouldBe PrivilegedActionStatus.WITHDRAWN
            shouldThrow<PrivilegedActionStateException> { svc.approve(actor = fx.actor(id = c), requestIdRaw = again.id) }
            fx.roleOf(b) shouldBe AccountRole.ADMIN
        }

        // ───────────────────────────── tamper and input validation ─────────────────────────────

        test("tamper: foreign, unknown and malformed request ids, wrong callers, bad input -- always typed, never a state change") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            val ghost = Uuid.random().toString()
            listOf(ghost, "not-a-uuid", "").forEach { id ->
                shouldThrow<PrivilegedActionStateException> { svc.approve(actor = fx.actor(id = c), requestIdRaw = id) }
                shouldThrow<PrivilegedActionStateException> { svc.reject(actor = fx.actor(id = c), requestIdRaw = id) }
                shouldThrow<PrivilegedActionStateException> { svc.withdraw(actor = fx.actor(id = a), requestIdRaw = id) }
                shouldThrow<PrivilegedActionStateException> { svc.executeTemporaryPassword(actor = fx.actor(id = a), requestIdRaw = id) }
            }
            // a non-ADMIN caller is refused outright
            val board = fx.actor(id = fx.member(role = AccountRole.BOARD), role = AccountRole.BOARD)
            shouldThrow<ForbiddenException> {
                svc.requestTemporaryPassword(
                    actor = board,
                    targetIdRaw = b.toString(),
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<ForbiddenException> { svc.approve(actor = board, requestIdRaw = req.id) }
            shouldThrow<ForbiddenException> { svc.overview(board) }
            // execute on a PENDING (not approved) request, and on a request of another kind
            shouldThrow<PrivilegedActionStateException> { svc.executeTemporaryPassword(actor = fx.actor(id = a), requestIdRaw = req.id) }
            // input validation
            shouldThrow<BadRequestException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.ADMIN,
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<BadRequestException> {
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.ACTIVE,
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<BadRequestException> {
                svc.requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.DECEASED,
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<BadRequestException> {
                svc.requestDemotion(actor = fx.actor(id = a), targetIdRaw = b.toString(), newRole = AccountRole.MEMBER, reasonRaw = "kurz")
            }
            shouldThrow<BadRequestException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = "x".repeat(501),
                )
            }
            shouldThrow<NotFoundException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = "not-a-uuid",
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            shouldThrow<NotFoundException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = Uuid.random().toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            }
            fx.statusOfRequest(req.id) shouldBe "PENDING"
            fx.roleOf(b) shouldBe AccountRole.ADMIN
        }

        test("a second equal request is refused while the first is open; another kind against the same target is allowed") {
            val a = fx.admin()
            val b = fx.admin()
            fx.admin()
            val svc = fx.service()
            svc.requestDemotion(actor = fx.actor(id = a), targetIdRaw = b.toString(), newRole = AccountRole.MEMBER, reasonRaw = GOOD_REASON)
            shouldThrow<PrivilegedActionStateException> {
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.BOARD,
                    reasonRaw = GOOD_REASON,
                )
            }
            svc
                .requestSuspension(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = GOOD_REASON,
                ).status shouldBe
                PrivilegedActionStatus.PENDING
            fx.openRequests(b) shouldBe 2L
        }

        test("request budgets: per requester and per target, consumed only after the authorization passed") {
            val a = fx.admin()
            val b = fx.admin()
            fx.admin()
            val svc =
                fx.service(
                    actorLimiter = FederationInboxRateLimiter(maxRequests = 2),
                    targetLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                )
            // refused calls (self target) must not eat the budget
            repeat(
                5,
            ) {
                shouldThrow<PeerProtectionDeniedException> {
                    svc.requestDemotion(
                        actor = fx.actor(id = a),
                        targetIdRaw = a.toString(),
                        newRole = AccountRole.MEMBER,
                        reasonRaw = GOOD_REASON,
                    )
                }
            }
            svc.requestDemotion(actor = fx.actor(id = a), targetIdRaw = b.toString(), newRole = AccountRole.MEMBER, reasonRaw = GOOD_REASON)
            svc.requestSuspension(
                actor = fx.actor(id = a),
                targetIdRaw = b.toString(),
                newStatus = MemberStatus.WITHDRAWN,
                reasonRaw = GOOD_REASON,
            )
            shouldThrow<network.lapis.cloud.shared.rpc.ConflictException> {
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
            }
        }

        test("overview: the approver sees the request, the requester sees theirs, nobody else sees a thing") {
            val a = fx.admin()
            val b = fx.admin()
            val c = fx.admin()
            val svc = fx.service()
            val req =
                svc.requestDemotion(
                    actor = fx.actor(id = a),
                    targetIdRaw = b.toString(),
                    newRole = AccountRole.MEMBER,
                    reasonRaw = GOOD_REASON,
                )
            svc.overview(fx.actor(id = c)).awaitingMyApproval.map { it.id } shouldBe listOf(req.id)
            svc.overview(fx.actor(id = c)).requestedByMe.shouldBeEmpty()
            svc.overview(fx.actor(id = a)).awaitingMyApproval.shouldBeEmpty()
            svc.overview(fx.actor(id = a)).requestedByMe.map { it.id } shouldBe listOf(req.id)
            svc.overview(fx.actor(id = b)).awaitingMyApproval.shouldBeEmpty()
            svc.overview(fx.actor(id = b)).requestedByMe.shouldBeEmpty()
        }

        test("decisions for the UI: ADMIN target shows the approval path or the reason, a plain target the direct path") {
            val a = fx.admin()
            val b = fx.admin()
            val svc = fx.service()
            val noApprover = svc.decisions(actor = fx.actor(id = a), targetIdRaw = b.toString()).decisions.associateBy { it.action }
            noApprover.getValue(network.lapis.cloud.shared.domain.PeerAction.TEMP_PASSWORD).denyReason shouldBe
                network.lapis.cloud.shared.domain.PeerDenyReason.NO_SECOND_ADMIN
            fx.admin()
            val withApprover = svc.decisions(actor = fx.actor(id = a), targetIdRaw = b.toString()).decisions.associateBy { it.action }
            withApprover.getValue(network.lapis.cloud.shared.domain.PeerAction.TEMP_PASSWORD).kind shouldBe
                network.lapis.cloud.shared.domain.PeerDecisionKind.REQUIRES_APPROVAL
            val plain = fx.member()
            svc
                .decisions(actor = fx.actor(id = a), targetIdRaw = plain.toString())
                .decisions
                .associateBy { it.action }
                .getValue(network.lapis.cloud.shared.domain.PeerAction.TEMP_PASSWORD)
                .kind shouldBe
                network.lapis.cloud.shared.domain.PeerDecisionKind.ALLOW
            // BOARD sees the protection of an ADMIN's data, MEMBER may not ask at all
            val board = fx.actor(id = fx.member(role = AccountRole.BOARD), role = AccountRole.BOARD)
            svc
                .decisions(actor = board, targetIdRaw = b.toString())
                .decisions
                .associateBy { it.action }
                .getValue(network.lapis.cloud.shared.domain.PeerAction.READ_PROTECTED_DATA)
                .kind shouldBe
                network.lapis.cloud.shared.domain.PeerDecisionKind.MASK
            shouldThrow<ForbiddenException> {
                svc.decisions(
                    actor = fx.actor(id = plain, role = AccountRole.MEMBER),
                    targetIdRaw = b.toString(),
                )
            }
        }

        // ───────────────────────────── races ─────────────────────────────

        test(
            "two simultaneous equal requests: exactly one wins, the other is PrivilegedActionState (23505 caught under a savepoint, nothing else)",
        ) {
            repeat(ROUNDS) {
                fx.isolateAdmins()
                val a = fx.admin()
                val b = fx.admin()
                val c = fx.admin()
                val svc = fx.service()
                val results =
                    race(
                        {
                            svc.requestDemotion(
                                actor = fx.actor(id = a),
                                targetIdRaw = b.toString(),
                                newRole = AccountRole.MEMBER,
                                reasonRaw = GOOD_REASON,
                            )
                        },
                        {
                            svc.requestDemotion(
                                actor = fx.actor(id = c),
                                targetIdRaw = b.toString(),
                                newRole = AccountRole.MEMBER,
                                reasonRaw = GOOD_REASON,
                            )
                        },
                    )
                results.count { it.isSuccess } shouldBe 1
                results.filter { it.isFailure }.forEach { it.exceptionOrNull()!!::class shouldBe PrivilegedActionStateException::class }
                fx.openRequests(b) shouldBe 1L
            }
        }

        test("approve and veto at the same time: exactly one wins; a vetoed request is never executed") {
            repeat(ROUNDS) {
                fx.isolateAdmins()
                val a = fx.admin()
                val b = fx.admin()
                val c = fx.admin()
                val mailer = FakePeerNotificationMailer()
                val svc = fx.service(mailer = mailer)
                val req = svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
                val token = mailer.requestMails.last().rawVetoToken
                val results = race({ svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id) }, { svc.vetoByLink(token) })
                // the veto never fails; the approval fails (typed) only when the veto closed the request first
                results[1].exceptionOrNull() shouldBe null
                results[0].exceptionOrNull()?.let { it::class shouldBe PrivilegedActionStateException::class }
                val status = fx.statusOfRequest(req.id)
                // either order is fine -- the veto reaches an APPROVED_WAITING request as well; what must hold: the request is closed or waiting, never both
                (status == "VETOED" || status == "APPROVED_WAITING") shouldBe true
                if (status == "APPROVED_WAITING") {
                    // the veto lost the race for the row but its token is still alive: it ends the waiting request now
                    svc.vetoByLink(token) shouldBe VetoResult.VETOED
                }
                fx.statusOfRequest(req.id) shouldBe "VETOED"
                fx.openRequests(b) shouldBe 0L
            }
        }

        test("approve and withdraw at the same time: exactly one wins") {
            repeat(ROUNDS) {
                fx.isolateAdmins()
                val a = fx.admin()
                val b = fx.admin()
                val c = fx.admin()
                val svc = fx.service()
                val req =
                    svc.requestDemotion(
                        actor = fx.actor(id = a),
                        targetIdRaw = b.toString(),
                        newRole = AccountRole.MEMBER,
                        reasonRaw = GOOD_REASON,
                    )
                val results =
                    race(
                        { svc.approve(actor = fx.actor(id = c), requestIdRaw = req.id) },
                        { svc.withdraw(actor = fx.actor(id = a), requestIdRaw = req.id) },
                    )
                results.count { it.isSuccess } shouldBe 1
                results.filter { it.isFailure }.forEach { it.exceptionOrNull()!!::class shouldBe PrivilegedActionStateException::class }
                val status = fx.statusOfRequest(req.id)
                (status == "EXECUTED" || status == "WITHDRAWN") shouldBe true
                (fx.roleOf(b) == AccountRole.MEMBER) shouldBe (status == "EXECUTED")
            }
        }

        test("three ADMINs demoting each other in a ring, approved concurrently: never zero ADMINs, no deadlock") {
            repeat(ROUNDS) {
                fx.isolateAdmins()
                val a = fx.admin()
                val b = fx.admin()
                val c = fx.admin()
                val svc = fx.service()
                val aOnB =
                    svc.requestDemotion(
                        actor = fx.actor(id = a),
                        targetIdRaw = b.toString(),
                        newRole = AccountRole.MEMBER,
                        reasonRaw = GOOD_REASON,
                    )
                val bOnA =
                    svc.requestDemotion(
                        actor = fx.actor(id = b),
                        targetIdRaw = a.toString(),
                        newRole = AccountRole.MEMBER,
                        reasonRaw = GOOD_REASON,
                    )
                val cOnA =
                    svc.requestSuspension(
                        actor = fx.actor(id = c),
                        targetIdRaw = a.toString(),
                        newStatus = MemberStatus.WITHDRAWN,
                        reasonRaw = GOOD_REASON,
                    )
                // C approves A's request on B; B approves C's request on A; A approves B's request on A... all at once
                val results =
                    race(
                        { svc.approve(actor = fx.actor(id = c), requestIdRaw = aOnB.id) },
                        { svc.approve(actor = fx.actor(id = c), requestIdRaw = bOnA.id) },
                        { svc.approve(actor = fx.actor(id = b), requestIdRaw = cOnA.id) },
                    )
                // a typed refusal or an invalidation is fine, a raw database error (40P01, 25P02) is not
                results.filter { it.isFailure }.forEach { r ->
                    val e = r.exceptionOrNull()!!
                    (
                        e is PrivilegedActionStateException ||
                            e is ForbiddenException ||
                            e is network.lapis.cloud.shared.rpc.LastAdminException
                    ) shouldBe true
                }
                val survivors =
                    listOf(a, b, c).filter {
                        fx.roleOf(it) == AccountRole.ADMIN &&
                            fx.statusOf(it) !in network.lapis.cloud.shared.domain.MemberStatusSets.LOGIN_BLOCKED
                    }
                (survivors.isNotEmpty()) shouldBe true
                // reset for the next round: everybody active and ADMIN again
                listOf(a, b, c).forEach { id ->
                    transaction {
                        network.lapis.cloud.server.db.generated.AccountTable
                            .update(
                                { network.lapis.cloud.server.db.generated.AccountTable.memberId eq id },
                            ) { it[role] = AccountRole.ADMIN }
                        network.lapis.cloud.server.db.generated.MemberTable
                            .update({ network.lapis.cloud.server.db.generated.MemberTable.id eq id }) { it[status] = MemberStatus.ACTIVE }
                    }
                }
            }
        }

        test("the same objection link clicked twice at the same time: one VETOED, one INVALID") {
            repeat(ROUNDS) {
                fx.isolateAdmins()
                val a = fx.admin()
                val b = fx.admin()
                fx.admin()
                val mailer = FakePeerNotificationMailer()
                val svc = fx.service(mailer = mailer)
                svc.requestTemporaryPassword(actor = fx.actor(id = a), targetIdRaw = b.toString(), reasonRaw = GOOD_REASON)
                val token = mailer.requestMails.last().rawVetoToken
                val results = race({ svc.vetoByLink(token) }, { svc.vetoByLink(token) })
                results.forEach { it.exceptionOrNull() shouldBe null }
                results.map { it.getOrThrow() }.sortedBy { it.name } shouldBe listOf(VetoResult.INVALID, VetoResult.VETOED)
            }
        }
    }) {
    private companion object {
        const val ROUNDS = 4
    }
}

class AdminPeerProtectionTest : AdminPeerProtectionScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AdminPeerProtectionPostgresTest : AdminPeerProtectionScenarios(TestDatabase.Postgres())
