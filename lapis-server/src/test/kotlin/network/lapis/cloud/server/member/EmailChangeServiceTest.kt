package network.lapis.cloud.server.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeEmailChangeMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangeStatus
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.EmailChangeAlreadyCurrentException
import network.lapis.cloud.shared.rpc.EmailChangeMailUnavailableException
import network.lapis.cloud.shared.rpc.EmailChangeNotAllowedException
import network.lapis.cloud.shared.rpc.EmailChangePendingNotFoundException
import network.lapis.cloud.shared.rpc.EmailChangeRateLimitedException
import network.lapis.cloud.shared.rpc.EmailChangeRepeatMismatchException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.PeerProtectionDeniedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the domain behaviour of [EmailChangeService] across all paths (A self,
 * B proposal with password, B0 proposal without password, C emergency, D reject/withdraw), the full role matrix, the
 * no-SMTP rule, token handling, duplicates, Keycloak mode, the poller and the audit discipline. Runs on H2; the
 * concurrency scenarios have their own spec (also on PostgreSQL).
 */
class EmailChangeServiceTest :
    FunSpec({
        val fx = EmailChangeFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanUp() }

        fun newAddress() = "ec-new-${Uuid.random().toString().take(12)}@example.org"

        fun outcome(block: () -> Unit): String =
            try {
                block()
                "OK"
            } catch (e: ForbiddenException) {
                "FORBIDDEN"
            } catch (e: EmailChangeNotAllowedException) {
                "NOT_ALLOWED"
            } catch (e: PeerProtectionDeniedException) {
                "PEER_DENIED"
            }

        // ───────────────────────────── role matrix ─────────────────────────────

        test("role matrix: propose -- every caller role against every target kind") {
            val svc = fx.service()
            val callers = listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)
            val targetKinds =
                listOf("accountless", "member", "friend", "board", "treasurer", "admin", "guest", "deceased", "anonymized", "self")
            callers.forEach { callerRole ->
                val callerId = fx.member(role = callerRole)
                val caller = fx.actor(id = callerId, role = callerRole)
                targetKinds.forEach { kind ->
                    val target: Uuid =
                        when (kind) {
                            "accountless" -> fx.member(role = null)
                            "member" -> fx.member()
                            "friend" -> fx.member(status = MemberStatus.FRIEND)
                            "board" -> fx.member(role = AccountRole.BOARD)
                            "treasurer" -> fx.member(role = AccountRole.TREASURER)
                            "admin" -> fx.member(role = AccountRole.ADMIN)
                            "guest" -> fx.member(status = MemberStatus.GUEST)
                            "deceased" -> fx.member(status = MemberStatus.DECEASED)
                            "anonymized" ->
                                fx.member().also { id ->
                                    transaction { MemberTable.update({ MemberTable.id eq id }) { it[anonymizedAt] = fx.now() } }
                                }
                            else -> callerId
                        }
                    val expected =
                        when {
                            callerRole == AccountRole.MEMBER || callerRole == AccountRole.TREASURER -> "FORBIDDEN"
                            kind == "self" -> "NOT_ALLOWED"
                            kind in setOf("guest", "deceased", "anonymized") -> "NOT_ALLOWED"
                            callerRole == AccountRole.BOARD && kind in setOf("board", "treasurer", "admin") -> "FORBIDDEN"
                            else -> "OK"
                        }
                    val addr = newAddress()
                    withClue(clue = "propose caller=$callerRole target=$kind") {
                        outcome {
                            svc.propose(
                                actor = caller,
                                targetIdRaw = target.toString(),
                                newEmail = addr,
                                newEmailRepeat = addr,
                            )
                        } shouldBe
                            expected
                    }
                    // V1.9.57: path C never targets ANOTHER administrator (typed peer denial, not a role violation).
                    val expectedOverride =
                        if (callerRole == AccountRole.ADMIN) {
                            if (kind == "admin") "PEER_DENIED" else expected
                        } else {
                            "FORBIDDEN"
                        }
                    val addr2 = newAddress()
                    withClue(clue = "override caller=$callerRole target=$kind") {
                        outcome {
                            svc.requestOverride(
                                actor = caller,
                                targetIdRaw = target.toString(),
                                newEmail = addr2,
                                newEmailRepeat = addr2,
                                reason = "Telefonisch verifizierter Notfall",
                            )
                        } shouldBe
                            expectedOverride
                    }
                }
            }
        }

        test("role matrix: withdraw and pendingForAdministration respect the initiator rule and the peer boundary") {
            val svc = fx.service()
            val board = fx.initiator(AccountRole.BOARD)
            val otherBoard = fx.initiator(AccountRole.BOARD)
            val target = fx.member()
            val addr = newAddress()
            val pending = svc.propose(actor = board, targetIdRaw = target.toString(), newEmail = addr, newEmailRepeat = addr)

            // visibility
            svc.pendingForAdministration(actor = otherBoard, targetIdRaw = target.toString())?.changeId shouldBe pending.changeId
            svc.pendingForAdministration(actor = EC_SEED_ADMIN, targetIdRaw = target.toString())?.newEmailMasked shouldBe
                pending.newEmailMasked
            shouldThrow<ForbiddenException> { svc.pendingForAdministration(actor = EC_SEED_MEMBER, targetIdRaw = target.toString()) }
            shouldThrow<ForbiddenException> { svc.pendingForAdministration(actor = EC_SEED_TREASURER, targetIdRaw = target.toString()) }
            svc.pendingForAdministration(actor = board, targetIdRaw = board.memberId.toString()) shouldBe null

            // a MEMBER / TREASURER cannot withdraw; a BOARD who is not the initiator cannot either; the initiator and an ADMIN can
            shouldThrow<ForbiddenException> { svc.withdraw(actor = EC_SEED_MEMBER, changeIdRaw = pending.changeId) }
            shouldThrow<ForbiddenException> { svc.withdraw(actor = EC_SEED_TREASURER, changeIdRaw = pending.changeId) }
            shouldThrow<EmailChangePendingNotFoundException> { svc.withdraw(actor = otherBoard, changeIdRaw = pending.changeId) }
            fx.statusOf(pending.changeId) shouldBe "PENDING"
            svc.withdraw(actor = board, changeIdRaw = pending.changeId)
            fx.statusOf(pending.changeId) shouldBe "WITHDRAWN"
            shouldThrow<EmailChangePendingNotFoundException> { svc.withdraw(actor = board, changeIdRaw = pending.changeId) }

            val addr2 = newAddress()
            val again = svc.propose(actor = board, targetIdRaw = target.toString(), newEmail = addr2, newEmailRepeat = addr2)
            svc.withdraw(actor = EC_SEED_ADMIN, changeIdRaw = again.changeId)
            fx.statusOf(again.changeId) shouldBe "WITHDRAWN"
            fx.openCount(target) shouldBe 0L

            // a BOARD caller does not see a change of a BOARD-role target
            val boardTarget = fx.member(role = AccountRole.BOARD)
            val a3 = newAddress()
            svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = boardTarget.toString(), newEmail = a3, newEmailRepeat = a3)
            svc.pendingForAdministration(actor = board, targetIdRaw = boardTarget.toString()) shouldBe null
            svc.pendingForAdministration(actor = EC_SEED_ADMIN, targetIdRaw = boardTarget.toString()) shouldNotBe null
        }

        // ───────────────────────────── path A ─────────────────────────────

        test(
            "path A with SMTP: changes at once, other sessions end, tokens die, old address is informed, one audit entry without any address",
        ) {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val old = "zebrafish.alpha@example.org"
            val id = fx.member(email = old)
            val owner = fx.actor(id = id, role = AccountRole.MEMBER)
            val own = SessionStore.createSession(id)
            val other = SessionStore.createSession(id)
            val resetToken = PasswordResetTokenStore.createToken(id)
            val friendToken = FriendEmailVerificationTokenStore.createToken(id)
            val fresh = "narwhal.beta-${Uuid.random().toString().take(8)}@example.org"

            val result =
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = "  ${fresh.uppercase()} ",
                    newEmailRepeat = fresh,
                    ownRawSessionToken = own.rawToken,
                )

            result.oldAddressNotified shouldBe MailDeliveryState.HANDED_TO_SMTP
            fx.emailOf(id) shouldBe fresh
            fx.verifiedAtOf(id) shouldBe null
            SessionStore.resolve(own.rawToken) shouldNotBe null
            SessionStore.resolve(other.rawToken) shouldBe null
            PasswordResetTokenStore.peekMemberId(resetToken) shouldBe null
            FriendEmailVerificationTokenStore.peekMemberId(friendToken) shouldBe null
            mailer.selfInfoMails shouldHaveSize 1
            mailer.selfInfoMails.single().email shouldBe old
            mailer.selfInfoMails.single().maskedNewEmail shouldStartWith "n***@"
            fx.rowsOf(id).single()[MemberEmailChangeTable.kind] shouldBe "SELF"
            fx.rowsOf(id).single()[MemberEmailChangeTable.status] shouldBe "APPLIED"
            fx.rowsOf(id).single()[MemberEmailChangeTable.openMemberId] shouldBe null

            val audit = fx.auditJsonFor(id)
            audit shouldHaveSize 1
            audit.single().apply {
                shouldNotContain("zebrafish")
                shouldNotContain("narwhal")
                shouldNotContain("example.org")
                shouldNotContain(own.rawToken)
                (contains("\"emailChanged\":true")) shouldBe true
                (contains("\"event\":\"APPLIED\"")) shouldBe true
                (contains("\"kind\":\"SELF\"")) shouldBe true
            }
        }

        test("path A without SMTP: still works with the password, nobody is notified") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(smtp = SmtpConfigState.NotConfigured, mailer = mailer)
            val id = fx.member()
            val fresh = newAddress()
            val result =
                svc.changeOwn(
                    actor = fx.actor(id = id, role = AccountRole.MEMBER),
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            result.oldAddressNotified shouldBe MailDeliveryState.NOT_CONFIGURED
            fx.emailOf(id) shouldBe fresh
            mailer.totalMails shouldBe 0
            svc.capability(fx.actor(id = id, role = AccountRole.MEMBER)).mailDelivery shouldBe MailDeliveryState.NOT_CONFIGURED
            svc.capability(fx.actor(id = id, role = AccountRole.MEMBER)).ownChangeAvailable shouldBe true
        }

        test("path A for a FRIEND re-sends the verification mail to the NEW address and keeps emailVerifiedAt null") {
            val sent = mutableListOf<String>()
            val friendMailer =
                object : network.lapis.cloud.server.mail.FriendVerificationMailer {
                    override fun send(
                        email: String,
                        rawToken: String,
                    ): network.lapis.cloud.shared.domain.DeliveryStatus {
                        sent += email
                        return network.lapis.cloud.shared.domain.DeliveryStatus.SENT
                    }
                }
            val svc = fx.service(friendMailer = friendMailer)
            val id = fx.member(status = MemberStatus.FRIEND)
            val fresh = newAddress()
            svc.changeOwn(
                actor = fx.actor(id = id, role = AccountRole.MEMBER, status = MemberStatus.FRIEND),
                currentPassword = EC_PASSWORD,
                newEmail = fresh,
                newEmailRepeat = fresh,
                ownRawSessionToken = null,
            )
            sent shouldBe listOf(fresh)
            fx.verifiedAtOf(id) shouldBe null

            // an ACTIVE member gets no verification mail
            val active = fx.member()
            val fresh2 = newAddress()
            svc.changeOwn(
                actor = fx.actor(id = active, role = AccountRole.MEMBER),
                currentPassword = EC_PASSWORD,
                newEmail = fresh2,
                newEmailRepeat = fresh2,
                ownRawSessionToken = null,
            )
            sent shouldBe listOf(fresh)
        }

        test("path A friend verification mail is rate limited per target without failing the change") {
            val sent = mutableListOf<String>()
            val friendMailer =
                object : network.lapis.cloud.server.mail.FriendVerificationMailer {
                    override fun send(
                        email: String,
                        rawToken: String,
                    ): network.lapis.cloud.shared.domain.DeliveryStatus {
                        sent += email
                        return network.lapis.cloud.shared.domain.DeliveryStatus.SENT
                    }
                }
            val svc = fx.service(friendMailer = friendMailer, friendMailTarget = FederationInboxRateLimiter(maxRequests = 2))
            val id = fx.member(status = MemberStatus.FRIEND)
            val actor = fx.actor(id = id, role = AccountRole.MEMBER, status = MemberStatus.FRIEND)
            repeat(3) {
                val fresh = newAddress()
                svc.changeOwn(
                    actor = actor,
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
                fx.emailOf(id) shouldBe fresh
            }
            sent shouldHaveSize 2
        }

        test("path A: wrong password, validation, duplicate, unchanged, rate limit and no-account/guest cases") {
            val svc = fx.service(passwordAttempts = LoginRateLimiter(maxFailures = 3))
            val id = fx.member(email = "path-a-before@example.org")
            val owner = fx.actor(id = id, role = AccountRole.MEMBER)
            val fresh = newAddress()

            shouldThrow<InvalidPasswordException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = "wrong-password-xyz",
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            }
            fx.emailOf(id) shouldBe "path-a-before@example.org"
            shouldThrow<EmailChangeRepeatMismatchException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = newAddress(),
                    ownRawSessionToken = null,
                )
            }
            shouldThrow<BadRequestException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = "not-an-address",
                    newEmailRepeat = "not-an-address",
                    ownRawSessionToken = null,
                )
            }
            val overlong = "a".repeat(310) + "@example.org"
            shouldThrow<MemberEmailTooLongException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = overlong,
                    newEmailRepeat = overlong,
                    ownRawSessionToken = null,
                )
            }
            shouldThrow<EmailChangeAlreadyCurrentException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = "  PATH-A-BEFORE@example.org",
                    newEmailRepeat = "path-a-before@example.org",
                    ownRawSessionToken = null,
                )
            }
            val taken = fx.emailOf(fx.member(email = "path-a-taken@example.org"))
            shouldThrow<MemberEmailInUseException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = taken.uppercase(),
                    newEmailRepeat = taken,
                    ownRawSessionToken = null,
                )
            }
            fx.emailOf(id) shouldBe "path-a-before@example.org"

            // the per-member limiter: a successful check above reset it, so three fresh failures trip it
            repeat(3) {
                shouldThrow<InvalidPasswordException> {
                    svc.changeOwn(
                        actor = owner,
                        currentPassword = "wrong-password-xyz",
                        newEmail = fresh,
                        newEmailRepeat = fresh,
                        ownRawSessionToken = null,
                    )
                }
            }
            shouldThrow<EmailChangeRateLimitedException> {
                svc.changeOwn(
                    actor = owner,
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            }
            fx.emailOf(id) shouldBe "path-a-before@example.org"

            // no password account / GUEST -> not allowed, capability says so
            val svc2 = fx.service()
            val accountless = fx.member(role = null)
            shouldThrow<EmailChangeNotAllowedException> {
                svc2.changeOwn(
                    actor = fx.actor(id = accountless, role = AccountRole.MEMBER),
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            }
            svc2.capability(fx.actor(id = accountless, role = AccountRole.MEMBER)).ownChangeAvailable shouldBe false
            val guest = fx.member(status = MemberStatus.GUEST)
            shouldThrow<EmailChangeNotAllowedException> {
                svc2.changeOwn(
                    actor = fx.actor(id = guest, role = AccountRole.MEMBER, status = MemberStatus.GUEST),
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            }
        }

        test("path A supersedes an open proposal; the old links die") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val board = fx.initiator()
            val a = newAddress()
            val pending = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val confirmToken = mailer.confirmMails.single().rawToken
            val revokeToken = mailer.warningMails.single().rawRevokeToken
            val mine = newAddress()
            svc.changeOwn(
                actor = fx.actor(id = id, role = AccountRole.MEMBER),
                currentPassword = EC_PASSWORD,
                newEmail = mine,
                newEmailRepeat = mine,
                ownRawSessionToken = null,
            )
            fx.statusOf(pending.changeId) shouldBe "SUPERSEDED"
            fx.openCount(id) shouldBe 0L
            svc.confirmByLink(rawToken = confirmToken, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            svc.revokeByLink(revokeToken) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldBe mine
        }

        // ───────────────────────────── path B ─────────────────────────────

        test("path B: a proposal changes nothing, login identity stays, the owner accepts in the session with the password") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val old = "path-b-old-${Uuid.random().toString().take(8)}@example.org"
            val id = fx.member(email = old)
            val board = fx.initiator()
            val fresh = newAddress()

            val pending = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            pending.kind shouldBe EmailChangeKind.PROPOSAL
            pending.effectiveAt shouldBe null
            pending.newEmailConfirmed shouldBe false
            pending.newEmailMasked shouldStartWith "e***@"
            fx.emailOf(id) shouldBe old
            mailer.confirmMails.single().apply {
                email shouldBe fresh
                kind shouldBe EmailChangeKind.PROPOSAL
            }
            mailer.warningMails.single().apply {
                email shouldBe old
                maskedNewEmail shouldBe pending.newEmailMasked
            }

            val owner = fx.actor(id = id, role = AccountRole.MEMBER)
            val own = svc.ownPending(owner)!!
            own.newEmail shouldBe fresh
            own.requiresPassword shouldBe true
            own.kind shouldBe EmailChangeKind.PROPOSAL

            // someone else cannot see or accept it
            val stranger = fx.actor(id = fx.member(), role = AccountRole.MEMBER)
            svc.ownPending(stranger) shouldBe null
            shouldThrow<EmailChangePendingNotFoundException> {
                svc.acceptOwn(actor = stranger, changeIdRaw = pending.changeId, currentPassword = EC_PASSWORD, ownRawSessionToken = null)
            }

            shouldThrow<InvalidPasswordException> {
                svc.acceptOwn(
                    actor = owner,
                    changeIdRaw = pending.changeId,
                    currentPassword = "wrong-password-xyz",
                    ownRawSessionToken = null,
                )
            }
            fx.emailOf(id) shouldBe old

            val session = SessionStore.createSession(id)
            val other = SessionStore.createSession(id)
            val dto =
                svc.acceptOwn(
                    actor = owner,
                    changeIdRaw = pending.changeId,
                    currentPassword = EC_PASSWORD,
                    ownRawSessionToken = session.rawToken,
                )
            dto.email shouldBe fresh
            fx.emailOf(id) shouldBe fresh
            fx.verifiedAtOf(id) shouldBe null // accepting inside a session proves no ownership of the new address
            SessionStore.resolve(session.rawToken) shouldNotBe null
            SessionStore.resolve(other.rawToken) shouldBe null
            fx.statusOf(pending.changeId) shouldBe "APPLIED"
            mailer.appliedInfoMails.shouldBeEmpty()
            svc.ownPending(owner) shouldBe null
        }

        test("path B via the link to the new address: token plus password, verified at once") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val board = fx.initiator()
            val fresh = newAddress()
            val pending = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val token = mailer.confirmMails.single().rawToken

            svc.confirmByLink(rawToken = token, password = null) shouldBe LinkResult.INVALID
            svc.confirmByLink(rawToken = token, password = "") shouldBe LinkResult.INVALID
            svc.confirmByLink(rawToken = token, password = "wrong-password-xyz") shouldBe LinkResult.WRONG_PASSWORD
            fx.emailOf(id) shouldNotBe fresh
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.OK
            fx.emailOf(id) shouldBe fresh
            fx.verifiedAtOf(id) shouldNotBe null
            fx.statusOf(pending.changeId) shouldBe "APPLIED"
            // replay
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.INVALID
        }

        test("path B: five wrong passwords on one link burn the change") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer, passwordAttempts = LoginRateLimiter(maxFailures = 5))
            val id = fx.member()
            val fresh = newAddress()
            val pending = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val token = mailer.confirmMails.single().rawToken
            repeat(5) { svc.confirmByLink(rawToken = token, password = "wrong-password-xyz") shouldBe LinkResult.WRONG_PASSWORD }
            // the fifth wrong password burns the change right away -- no sixth attempt needed
            fx.statusOf(pending.changeId) shouldBe "EXPIRED"
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldNotBe fresh
        }

        test("path B: the burn does not decay -- five wrong passwords spread over several rate-limit windows still burn the change") {
            var offset = 0.hours
            val clock =
                object : Clock {
                    override fun now(): Instant = Clock.System.now() + offset
                }
            val mailer = FakeEmailChangeMailer()
            val svc =
                fx.service(
                    mailer = mailer,
                    passwordAttempts = LoginRateLimiter(maxFailures = 5, clock = clock),
                    linkBurn = LoginRateLimiter(maxFailures = 5, window = 8.days, clock = clock),
                )
            val id = fx.member()
            val fresh = newAddress()
            val pending = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val token = mailer.confirmMails.single().rawToken
            repeat(4) {
                svc.confirmByLink(rawToken = token, password = "wrong-password-xyz") shouldBe LinkResult.WRONG_PASSWORD
                offset += 1.hours // far beyond the 15 minute window: the per-window limiter forgets, the burn counter must not
            }
            fx.statusOf(pending.changeId) shouldBe "PENDING"
            svc.confirmByLink(rawToken = token, password = "wrong-password-xyz") shouldBe LinkResult.WRONG_PASSWORD
            fx.statusOf(pending.changeId) shouldBe "EXPIRED"
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldNotBe fresh
        }

        test("path B: a wrong password through the link also counts against the per-member budget") {
            val mailer = FakeEmailChangeMailer()
            val attempts = LoginRateLimiter(maxFailures = 5)
            val svc = fx.service(mailer = mailer, passwordAttempts = attempts)
            val id = fx.member()
            val fresh = newAddress()
            svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val token = mailer.confirmMails.single().rawToken
            svc.confirmByLink(rawToken = token, password = "wrong-password-xyz") shouldBe LinkResult.WRONG_PASSWORD
            repeat(4) { attempts.recordFailure("member:$id") }
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.RATE_LIMITED
            fx.emailOf(id) shouldNotBe fresh
        }

        // ───────────────────────────── path B0 ─────────────────────────────

        test("path B0: an accountless member needs proof of the new address AND the 72 hour warning period") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val old = "path-b0-old-${Uuid.random().toString().take(8)}@example.org"
            val id = fx.member(email = old, role = null)
            val fresh = newAddress()
            val pending = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            pending.kind shouldBe EmailChangeKind.PROPOSAL_NO_ACCOUNT
            (pending.effectiveAt != null) shouldBe true
            val confirmToken = mailer.confirmMails.single().rawToken
            val revokeToken = mailer.warningMails.single().rawRevokeToken
            mailer.confirmMails.single().effectiveAt shouldBe pending.effectiveAt

            svc.confirmByLink(rawToken = confirmToken, password = "a-password") shouldBe LinkResult.INVALID // no password for this kind
            svc.confirmByLink(rawToken = confirmToken, password = null) shouldBe LinkResult.CONFIRMED_PENDING
            // idempotent before the warning period elapsed
            svc.confirmByLink(rawToken = confirmToken, password = null) shouldBe LinkResult.CONFIRMED_PENDING
            fx.emailOf(id) shouldBe old
            svc.pendingForAdministration(actor = EC_SEED_ADMIN, targetIdRaw = id.toString())!!.newEmailConfirmed shouldBe true

            // the poller before the warning period elapsed: nothing happens
            EmailChangePoller(service = svc).tick()
            svc.runDue(fx.nowPlus(10.hours))
            fx.emailOf(id) shouldBe old

            // after 72 hours the poller applies it
            SessionStore.createSession(id)
            fx.liveSessions(id) shouldBe 1L
            svc.runDue(fx.nowPlus(73.hours))
            fx.emailOf(id) shouldBe fresh
            fx.verifiedAtOf(id) shouldNotBe null
            fx.statusOf(pending.changeId) shouldBe "APPLIED"
            fx.liveSessions(id) shouldBe 0L
            mailer.appliedInfoMails.single().email shouldBe old
            svc.revokeByLink(revokeToken) shouldBe LinkResult.INVALID
        }

        test("path B0: a confirmation after the warning period applies at once; an unconfirmed change is never applied by the poller") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val a = fx.member(role = null)
            val b = fx.member(role = null)
            val addrA = newAddress()
            val addrB = newAddress()
            val pa = svc.propose(actor = fx.initiator(), targetIdRaw = a.toString(), newEmail = addrA, newEmailRepeat = addrA)
            val pb = svc.propose(actor = fx.initiator(), targetIdRaw = b.toString(), newEmail = addrB, newEmailRepeat = addrB)
            fx.age(changeId = pa.changeId, by = 80.hours)
            fx.age(changeId = pb.changeId, by = 80.hours)
            val tokenA = mailer.confirmMails.first { it.email == addrA }.rawToken

            svc.runDue(fx.now()) // neither is confirmed -> neither is applied
            fx.statusOf(pa.changeId) shouldBe "PENDING"
            fx.statusOf(pb.changeId) shouldBe "PENDING"

            // warning period already over -> effective immediately
            svc.confirmByLink(rawToken = tokenA, password = null) shouldBe LinkResult.OK
            fx.emailOf(a) shouldBe addrA
            fx.statusOf(pa.changeId) shouldBe "APPLIED"
            fx.statusOf(pb.changeId) shouldBe "PENDING"
        }

        // ───────────────────────────── path C ─────────────────────────────

        test("path C: ADMIN only, needs a reason, takes the same ownership + 72 hour route, even for a member who has a password") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val fresh = newAddress()
            shouldThrow<BadRequestException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "kurz",
                )
            }
            shouldThrow<BadRequestException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "x".repeat(501),
                )
            }
            shouldThrow<BadRequestException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "          ",
                )
            }
            shouldThrow<ForbiddenException> {
                svc.requestOverride(
                    actor = EC_SEED_BOARD,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "Ein ausreichend langer Grund",
                )
            }
            fx.openCount(id) shouldBe 0L

            val pending =
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "Mitglied hat Zugang verloren, Identität telefonisch geprüft",
                )
            pending.kind shouldBe EmailChangeKind.ADMIN_OVERRIDE
            fx.rowsOf(id).single()[MemberEmailChangeTable.reason] shouldBe "Mitglied hat Zugang verloren, Identität telefonisch geprüft"
            mailer.confirmMails.single().kind shouldBe EmailChangeKind.ADMIN_OVERRIDE
            svc.ownPending(fx.actor(id = id, role = AccountRole.MEMBER))!!.requiresPassword shouldBe false

            // an accept with the password is not the route for this kind
            shouldThrow<EmailChangePendingNotFoundException> {
                svc.acceptOwn(
                    actor = fx.actor(id = id, role = AccountRole.MEMBER),
                    changeIdRaw = pending.changeId,
                    currentPassword = EC_PASSWORD,
                    ownRawSessionToken = null,
                )
            }
            svc.confirmByLink(rawToken = mailer.confirmMails.single().rawToken, password = null) shouldBe LinkResult.CONFIRMED_PENDING
            svc.runDue(fx.nowPlus(1.hours))
            fx.emailOf(id) shouldNotBe fresh
            svc.runDue(fx.nowPlus(73.hours))
            fx.emailOf(id) shouldBe fresh
            fx.verifiedAtOf(id) shouldNotBe null

            val audit = fx.auditJsonFor(id)
            audit.map { it.contains("\"kind\":\"ADMIN_OVERRIDE\"") }.count { it } shouldBe 3 // REQUESTED, NEW_ADDRESS_CONFIRMED, APPLIED
            audit.forEach { it.shouldNotContain("example.org") }
        }

        test(
            "V1.9.57: path C is closed against a fellow ADMIN (typed peer denial, no row), BOARD may not propose either, proposals B/B0 by an ADMIN stay open",
        ) {
            val svc = fx.service()
            val adminTarget = fx.member(role = AccountRole.ADMIN)
            val a = newAddress()
            shouldThrow<PeerProtectionDeniedException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = adminTarget.toString(),
                    newEmail = a,
                    newEmailRepeat = a,
                    reason = "Notfall mit ausreichender Begründung",
                )
            }
            fx.rowsOf(adminTarget).shouldBeEmpty()
            val b = newAddress()
            shouldThrow<ForbiddenException> {
                svc.propose(actor = EC_SEED_BOARD, targetIdRaw = adminTarget.toString(), newEmail = b, newEmailRepeat = b)
            }
            // the proposal paths (owner's password, or proof of ownership + warning period) are unchanged for an ADMIN caller
            val c = newAddress()
            svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = adminTarget.toString(), newEmail = c, newEmailRepeat = c).kind shouldBe
                EmailChangeKind.PROPOSAL
        }

        // ───────────────────────────── no SMTP ─────────────────────────────

        test("without SMTP no third-party path exists: typed error, no row, nothing sent; path A still works") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(smtp = SmtpConfigState.NotConfigured, mailer = mailer)
            val id = fx.member()
            val fresh = newAddress()
            shouldThrow<EmailChangeMailUnavailableException> {
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            }
            shouldThrow<EmailChangeMailUnavailableException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    reason = "Ausreichend langer Grund",
                )
            }
            fx.rowsOf(id).shouldBeEmpty()
            mailer.totalMails shouldBe 0
        }

        // ───────────────────────────── reject / withdraw ─────────────────────────────

        test("D1: the old address rejects with its link, the owner declines signed in; both end the change for good") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val owner = fx.actor(id = id, role = AccountRole.MEMBER)
            val a = newAddress()
            val p1 = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val revoke = mailer.warningMails.last().rawRevokeToken
            val confirm = mailer.confirmMails.last().rawToken
            svc.revokeByLink(revoke) shouldBe LinkResult.OK
            fx.statusOf(p1.changeId) shouldBe "REVOKED"
            svc.revokeByLink(revoke) shouldBe LinkResult.INVALID
            svc.confirmByLink(rawToken = confirm, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldNotBe a

            val b = newAddress()
            val p2 = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = b, newEmailRepeat = b)
            svc.declineOwn(actor = owner, changeIdRaw = p2.changeId)
            fx.statusOf(p2.changeId) shouldBe "REVOKED"
            shouldThrow<EmailChangePendingNotFoundException> { svc.declineOwn(actor = owner, changeIdRaw = p2.changeId) }
            // a stranger cannot decline
            val c = newAddress()
            val p3 = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = c, newEmailRepeat = c)
            shouldThrow<EmailChangePendingNotFoundException> {
                svc.declineOwn(actor = fx.actor(id = fx.member(), role = AccountRole.MEMBER), changeIdRaw = p3.changeId)
            }
            fx.statusOf(p3.changeId) shouldBe "PENDING"
        }

        test("a new proposal supersedes the open one: one open change, the old links are dead") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val board = fx.initiator()
            val a = newAddress()
            val b = newAddress()
            val first = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val firstConfirm = mailer.confirmMails.single().rawToken
            val firstRevoke = mailer.warningMails.single().rawRevokeToken
            val second = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = b, newEmailRepeat = b)
            fx.statusOf(first.changeId) shouldBe "SUPERSEDED"
            fx.statusOf(second.changeId) shouldBe "PENDING"
            fx.openCount(id) shouldBe 1L
            svc.confirmByLink(rawToken = firstConfirm, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            svc.revokeByLink(firstRevoke) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldNotBe a
        }

        test("expiry: an overdue change is EXPIRED by the poller, its links die") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val a = newAddress()
            val p = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val confirm = mailer.confirmMails.single().rawToken
            // lazy expiry: an unexpired row is visible, an aged one is not
            svc.ownPending(fx.actor(id = id, role = AccountRole.MEMBER)) shouldNotBe null
            fx.age(changeId = p.changeId, by = (8 * 24).hours)
            svc.ownPending(fx.actor(id = id, role = AccountRole.MEMBER)) shouldBe null
            svc.confirmByLink(rawToken = confirm, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            svc.runDue(fx.now())
            fx.statusOf(p.changeId) shouldBe "EXPIRED"
            fx.openCount(id) shouldBe 0L
        }

        test("resolved rows are purged after the retention period, open ones never") {
            val svc = fx.service()
            val id = fx.member()
            val a = newAddress()
            val p = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            svc.withdraw(actor = EC_SEED_ADMIN, changeIdRaw = p.changeId)
            svc.runDue(fx.now()) // resolved just now -> kept
            fx.rowsOf(id) shouldHaveSize 1
            fx.ageResolved(changeId = p.changeId, by = EmailChangeStore.RESOLVED_RETENTION + 1.hours)
            svc.runDue(fx.now())
            fx.rowsOf(id).shouldBeEmpty()

            val open = fx.member()
            val b = newAddress()
            val openChange = svc.propose(actor = fx.initiator(), targetIdRaw = open.toString(), newEmail = b, newEmailRepeat = b)
            svc.runDue(fx.now())
            fx.rowsOf(open) shouldHaveSize 1
            fx.statusOf(openChange.changeId) shouldBe "PENDING"
        }

        // ───────────────────────────── tokens ─────────────────────────────

        test("tokens: unusable tokens all answer INVALID, only hashes are stored, tokens are 256 bit") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member(role = null)
            val a = newAddress()
            val p = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val confirm = mailer.confirmMails.single().rawToken
            val revoke = mailer.warningMails.single().rawRevokeToken

            (confirm.length >= 43) shouldBe true
            (revoke.length >= 43) shouldBe true
            (confirm != revoke) shouldBe true
            val row = fx.row(p.changeId)
            row[MemberEmailChangeTable.confirmTokenHash] shouldBe EmailChangeTokens.hash(confirm)
            row[MemberEmailChangeTable.revokeTokenHash] shouldBe EmailChangeTokens.hash(revoke)
            row[MemberEmailChangeTable.confirmTokenHash]!!.length shouldBe 64
            (row[MemberEmailChangeTable.confirmTokenHash] == confirm) shouldBe false
            EmailChangeTokens.matches(storedHash = row[MemberEmailChangeTable.confirmTokenHash], raw = confirm) shouldBe true
            EmailChangeTokens.matches(storedHash = row[MemberEmailChangeTable.confirmTokenHash], raw = revoke) shouldBe false

            svc.confirmByLink(rawToken = "garbage-token", password = null) shouldBe LinkResult.INVALID
            svc.confirmByLink(rawToken = revoke, password = null) shouldBe LinkResult.INVALID // the revoke token is not a confirm token
            svc.revokeByLink(confirm) shouldBe LinkResult.INVALID // and vice versa
            svc.revokeByLink("garbage-token") shouldBe LinkResult.INVALID
            fx.statusOf(p.changeId) shouldBe "PENDING"
        }

        test("an applied change by another route invalidates the old proposal's links (the address moved on)") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val a = newAddress()
            val p = svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            val confirm = mailer.confirmMails.single().rawToken
            val mine = newAddress()
            svc.changeOwn(
                actor = fx.actor(id = id, role = AccountRole.MEMBER),
                currentPassword = EC_PASSWORD,
                newEmail = mine,
                newEmailRepeat = mine,
                ownRawSessionToken = null,
            )
            fx.statusOf(p.changeId) shouldBe "SUPERSEDED"
            svc.confirmByLink(rawToken = confirm, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            fx.emailOf(id) shouldBe mine
        }

        // ───────────────────────────── duplicates ─────────────────────────────

        test(
            "duplicates are rejected case-insensitively for every path; an address taken after the proposal ends CONFLICT, the address stays",
        ) {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val takenEmail = "dup-taken-${Uuid.random().toString().take(8)}@example.org"
            fx.member(email = takenEmail)
            val id = fx.member()
            val board = fx.initiator()
            val upper = takenEmail.uppercase()
            shouldThrow<MemberEmailInUseException> {
                svc.propose(
                    actor = board,
                    targetIdRaw = id.toString(),
                    newEmail = upper,
                    newEmailRepeat = upper,
                )
            }
            shouldThrow<MemberEmailInUseException> {
                svc.requestOverride(
                    actor = EC_SEED_ADMIN,
                    targetIdRaw = id.toString(),
                    newEmail = upper,
                    newEmailRepeat = upper,
                    reason = "Ausreichend langer Grund",
                )
            }
            shouldThrow<MemberEmailInUseException> {
                svc.changeOwn(
                    actor = fx.actor(id = id, role = AccountRole.MEMBER),
                    currentPassword = EC_PASSWORD,
                    newEmail = upper,
                    newEmailRepeat = upper,
                    ownRawSessionToken = null,
                )
            }

            // taken between proposal and apply
            val contested = "dup-contested-${Uuid.random().toString().take(8)}@example.org"
            val p = svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = contested, newEmailRepeat = contested)
            val token = mailer.confirmMails.last().rawToken
            val before = fx.emailOf(id)
            fx.member(email = contested)
            svc.confirmByLink(rawToken = token, password = EC_PASSWORD) shouldBe LinkResult.ADDRESS_UNAVAILABLE
            fx.statusOf(p.changeId) shouldBe "CONFLICT"
            fx.emailOf(id) shouldBe before
            fx.openCount(id) shouldBe 0L
        }

        // ───────────────────────────── Keycloak ─────────────────────────────

        test(
            "Keycloak mode: no own change for non-ADMIN, a proposal against a non-ADMIN takes the ownership + 72 hour route, an ADMIN keeps the password path",
        ) {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(keycloak = true, mailer = mailer)
            val member = fx.member()
            val memberActor = fx.actor(id = member, role = AccountRole.MEMBER)
            val fresh = newAddress()
            svc.capability(memberActor).ownChangeAvailable shouldBe false
            shouldThrow<EmailChangeNotAllowedException> {
                svc.changeOwn(
                    actor = memberActor,
                    currentPassword = EC_PASSWORD,
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                    ownRawSessionToken = null,
                )
            }

            val p = svc.propose(actor = fx.initiator(), targetIdRaw = member.toString(), newEmail = fresh, newEmailRepeat = fresh)
            p.kind shouldBe EmailChangeKind.PROPOSAL_NO_ACCOUNT
            (p.effectiveAt != null) shouldBe true

            val admin = fx.member(role = AccountRole.ADMIN)
            val adminActor = fx.actor(id = admin, role = AccountRole.ADMIN)
            svc.capability(adminActor).ownChangeAvailable shouldBe true
            val a2 = newAddress()
            svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = admin.toString(), newEmail = a2, newEmailRepeat = a2).kind shouldBe
                EmailChangeKind.PROPOSAL
            val a3 = newAddress()
            svc.changeOwn(actor = adminActor, currentPassword = EC_PASSWORD, newEmail = a3, newEmailRepeat = a3, ownRawSessionToken = null)
            fx.emailOf(admin) shouldBe a3
        }

        // ───────────────────────────── F8: initiator lost the right ─────────────────────────────

        test("an initiator who lost the role before the owner accepted: the change is discarded (WITHDRAWN), the address stays") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val target = fx.member()
            val boardId = fx.member(role = AccountRole.BOARD)
            val board = fx.actor(id = boardId, role = AccountRole.BOARD)
            val fresh = newAddress()
            val p = svc.propose(actor = board, targetIdRaw = target.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val before = fx.emailOf(target)
            transaction { AccountTable.update({ AccountTable.memberId eq boardId }) { it[role] = AccountRole.MEMBER } }

            shouldThrow<EmailChangePendingNotFoundException> {
                svc.acceptOwn(
                    actor = fx.actor(id = target, role = AccountRole.MEMBER),
                    changeIdRaw = p.changeId,
                    currentPassword = EC_PASSWORD,
                    ownRawSessionToken = null,
                )
            }
            fx.statusOf(p.changeId) shouldBe "WITHDRAWN"
            fx.emailOf(target) shouldBe before
        }

        // ───────────────────────────── mailer failure ─────────────────────────────

        test("a throwing mailer: a third-party proposal is withdrawn (nobody warned), the owner's own change still applies") {
            val svc = fx.service(mailer = FakeEmailChangeMailer(failing = true))
            val id = fx.member()
            val fresh = newAddress()
            shouldThrow<EmailChangeMailUnavailableException> {
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            }
            fx.rowsOf(id).forEach { it[MemberEmailChangeTable.status] shouldBe EmailChangeStatus.WITHDRAWN.name }
            val mine = newAddress()
            svc.changeOwn(
                actor = fx.actor(id = id, role = AccountRole.MEMBER),
                currentPassword = EC_PASSWORD,
                newEmail = mine,
                newEmailRepeat = mine,
                ownRawSessionToken = null,
            )
            fx.emailOf(id) shouldBe mine
        }

        // ───────────────────────────── rate limits ─────────────────────────────

        test("proposal rate limits: per target and per initiator") {
            val svc =
                fx.service(
                    proposalTarget = FederationInboxRateLimiter(maxRequests = 2),
                    proposalActor = FederationInboxRateLimiter(maxRequests = 3),
                )
            val board = fx.initiator()
            val target = fx.member()
            repeat(2) {
                val a = newAddress()
                svc.propose(actor = board, targetIdRaw = target.toString(), newEmail = a, newEmailRepeat = a)
            }
            val over = newAddress()
            shouldThrow<EmailChangeRateLimitedException> {
                svc.propose(actor = board, targetIdRaw = target.toString(), newEmail = over, newEmailRepeat = over)
            }
            // actor budget: 2 + 1 (rejected still counts) = 3 used; the next target is also refused
            val other = fx.member()
            shouldThrow<EmailChangeRateLimitedException> {
                svc.propose(actor = board, targetIdRaw = other.toString(), newEmail = over, newEmailRepeat = over)
            }
            fx.rowsOf(other).shouldBeEmpty()
        }

        test("a warning mail the queue did not accept withdraws the change and the confirm token is never sent") {
            val mailer = FakeEmailChangeMailer(warningStatus = DeliveryStatus.FAILED)
            val svc = fx.service(mailer = mailer)
            val id = fx.member(role = null)
            val a = newAddress()
            shouldThrow<EmailChangeMailUnavailableException> {
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = a, newEmailRepeat = a)
            }
            mailer.confirmMails.shouldBeEmpty()
            fx.rowsOf(id).forEach { it[MemberEmailChangeTable.status] shouldBe EmailChangeStatus.WITHDRAWN.name }
            fx.emailOf(id) shouldNotBe a
        }

        test("a caller who may not touch a target cannot burn its proposal budget") {
            val svc = fx.service(proposalTarget = FederationInboxRateLimiter(maxRequests = 1))
            val adminTarget = fx.member(role = AccountRole.ADMIN)
            val board = fx.initiator()
            repeat(3) {
                val a = newAddress()
                shouldThrow<ForbiddenException> {
                    svc.propose(actor = board, targetIdRaw = adminTarget.toString(), newEmail = a, newEmailRepeat = a)
                }
            }
            val a = newAddress()
            svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = adminTarget.toString(), newEmail = a, newEmailRepeat = a)
        }

        test("LoginRateLimiter.tryAcquire reserves atomically under parallel callers") {
            val limiter = LoginRateLimiter(maxFailures = 5)
            val granted =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val threads = List(40) { Thread { if (limiter.tryAcquire("k")) granted.incrementAndGet() } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            granted.get() shouldBe 5
            limiter.release("k")
            limiter.tryAcquire("k") shouldBe true
            limiter.tryAcquire("k") shouldBe false
        }

        // ───────────────────────────── audit ─────────────────────────────

        test("audit: one MEMBER/UPDATE entry per lifecycle event with the right actor, no address and no token anywhere") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val old = "giraffe.audit@example.org"
            val id = fx.member(email = old, role = null)
            val board = fx.initiator()
            val addr = "okapi.audit-${Uuid.random().toString().take(8)}@example.org"
            svc.propose(actor = board, targetIdRaw = id.toString(), newEmail = addr, newEmailRepeat = addr)
            val confirm = mailer.confirmMails.single().rawToken
            val revoke = mailer.warningMails.single().rawRevokeToken
            svc.confirmByLink(rawToken = confirm, password = null) shouldBe LinkResult.CONFIRMED_PENDING
            svc.revokeByLink(revoke) shouldBe LinkResult.OK

            val entries = fx.auditJsonFor(id)
            entries shouldHaveSize 3 // REQUESTED, NEW_ADDRESS_CONFIRMED, REVOKED
            entries.forEach { json ->
                json.shouldNotContain("giraffe")
                json.shouldNotContain("okapi")
                json.shouldNotContain("example.org")
                json.shouldNotContain(confirm)
                json.shouldNotContain(revoke)
            }
            (entries[0].contains("\"event\":\"REQUESTED\"")) shouldBe true
            (entries[1].contains("\"event\":\"NEW_ADDRESS_CONFIRMED\"")) shouldBe true
            (entries[1].contains("\"linkActor\":\"NEW_ADDRESS\"")) shouldBe true
            (entries[2].contains("\"event\":\"REVOKED\"")) shouldBe true
            (entries[2].contains("\"linkActor\":\"OLD_ADDRESS\"")) shouldBe true
        }

        test("the seed actors of this spec keep their roles") {
            val roles: Map<CurrentMember, AccountRole> =
                mapOf(
                    EC_SEED_ADMIN to AccountRole.ADMIN,
                    EC_SEED_BOARD to AccountRole.BOARD,
                    EC_SEED_TREASURER to AccountRole.TREASURER,
                    EC_SEED_MEMBER to AccountRole.MEMBER,
                )
            roles.forEach { (member, role) ->
                transaction {
                    (MemberTable innerJoin AccountTable).selectAll().where { MemberTable.id eq member.memberId }.single()[AccountTable.role]
                } shouldBe role
            }
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
