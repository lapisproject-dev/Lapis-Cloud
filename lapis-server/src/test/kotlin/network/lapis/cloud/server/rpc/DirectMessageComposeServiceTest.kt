package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.server.messaging.DirectMessageRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DirectMessageRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageGroupTooLargeException
import network.lapis.cloud.shared.rpc.DirectMessageGroupUnavailableException
import network.lapis.cloud.shared.rpc.DirectMessageRateLimitedException
import network.lapis.cloud.shared.rpc.DirectMessageRecipientUnavailableException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.237 "Neue Nachricht" -- the server side: uniform refusal (no oracle), name search, groups, fan-out, blocks, visibility, audit and the
 * rate limiter. Creates its own rows only; the audit entries of the group send are kept by design but lose their actor in [CommitteeSeatFixture.cleanUp].
 */
class DirectMessageComposeServiceTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val token =
            "Qzx" +
                Uuid
                    .random()
                    .toString()
                    .take(6)
                    .replace("-", "")

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                DirectMessageBlockTable.deleteWhere {
                    (blockerMemberId inList fx.memberIds) or (blockedMemberId inList fx.memberIds)
                }
                MemberFinderBlockTable.deleteWhere {
                    (blockerMemberId inList fx.memberIds) or (blockedMemberId inList fx.memberIds)
                }
                DirectMessageTable.deleteWhere { (senderId inList fx.memberIds) or (recipientId inList fx.memberIds) }
            }
            rc.cleanUp()
            fx.cleanUp()
        }

        fun person(
            name: String = "$token Person",
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid = fx.member(status = status, role = role, displayName = name)

        fun limiter() = DirectMessageRateLimiter()

        suspend fun <T> ServiceCaller.asMember(
            who: Uuid,
            limiter: DirectMessageRateLimiter = limiter(),
            action: suspend (DirectMessageService) -> T,
        ): Result<T> = call(caller = who) { action(DirectMessageService(call = it, rateLimiter = limiter)) }

        fun messagesBetween(
            from: Uuid,
            to: Uuid,
        ): Int =
            transaction {
                DirectMessageTable
                    .selectAll()
                    .where { (DirectMessageTable.senderId eq from) and (DirectMessageTable.recipientId eq to) }
                    .count()
                    .toInt()
            }

        test("every refusal of a recipient is ONE uniform exception without an id; only writing to oneself stays a conflict") {
            val sender = person()
            val guest = person(status = MemberStatus.GUEST)
            val left = person(status = MemberStatus.WITHDRAWN)
            val anonymized = person()
            transaction { MemberTable.update({ MemberTable.id eq anonymized }) { it[anonymizedAt] = DbClock.nowLocalDateTime() } }
            val dmBlockedByThem = person()
            val dmBlockedByMe = person()
            val finderBlocked = person()
            transaction {
                val now = DbClock.nowLocalDateTime()
                DirectMessageBlockStore.block(blocker = dmBlockedByThem, blocked = sender, now = now)
                DirectMessageBlockStore.block(blocker = sender, blocked = dmBlockedByMe, now = now)
                MemberFinderBlockStore.block(blockerMemberId = finderBlocked, blockedMemberId = sender, now = now)
            }
            withServiceCaller {
                val unavailable =
                    listOf(
                        Uuid.random().toString(),
                        "not-a-uuid",
                        "",
                        guest.toString(),
                        left.toString(),
                        anonymized.toString(),
                        dmBlockedByThem.toString(),
                        dmBlockedByMe.toString(),
                        finderBlocked.toString(),
                    )
                val limiter = limiter()
                val messages =
                    unavailable.map { recipient ->
                        val plain =
                            asMember(
                                sender,
                                limiter,
                            ) { it.sendDirectMessage(recipientId = recipient, body = "Hallo") }.exceptionOrNull()
                        plain.shouldBeInstanceOf<DirectMessageRecipientUnavailableException>()
                        val withFiles =
                            asMember(
                                sender,
                                limiter,
                            ) { it.sendDirectMessageWithAttachments(recipientId = recipient, body = "Hallo", attachmentIds = emptyList()) }
                        withFiles.exceptionOrNull().shouldBeInstanceOf<DirectMessageRecipientUnavailableException>()
                        plain.message
                    }
                messages.toSet() shouldHaveSize 1
                unavailable.filter { it.isNotEmpty() }.forEach { id -> messages.first() shouldNotContain id }
                asMember(sender) { it.sendDirectMessage(recipientId = sender.toString(), body = "Hallo") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
            }
        }

        test("a block applies to the other direction of the pair too and lifting it restores writing") {
            val a = person()
            val b = person()
            withServiceCaller {
                asMember(a) { it.sendDirectMessage(recipientId = b.toString(), body = "eins") }.isSuccess shouldBe true
                asMember(b) { it.blockMessageSender(a.toString()) }.isSuccess shouldBe true
                asMember(a) { it.sendDirectMessage(recipientId = b.toString(), body = "zwei") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageRecipientUnavailableException>()
                // the blocker cannot write to the blocked either: a block hides the pair from each other
                asMember(b) { it.sendDirectMessage(recipientId = a.toString(), body = "drei") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageRecipientUnavailableException>()
                // blocking twice is a no-op
                asMember(b) { it.blockMessageSender(a.toString()) }.isSuccess shouldBe true
                val blocks = asMember(b) { it.listMessageBlocks() }.getOrThrow()
                blocks shouldHaveSize 1
                // a conversation exists, so the name is filled in
                blocks.single().displayName shouldBe "$token Person"
                asMember(b) { it.unblockMessageSender(blocks.single().blockId) }.isSuccess shouldBe true
                asMember(a) { it.sendDirectMessage(recipientId = b.toString(), body = "vier") }.isSuccess shouldBe true
            }
        }

        test("blocking an unknown or malformed id is a silent no-op; unblocking a foreign or unknown block is a uniform NotFound") {
            val a = person()
            val b = person()
            val c = person()
            withServiceCaller {
                asMember(a) { it.blockMessageSender(Uuid.random().toString()) }.isSuccess shouldBe true
                asMember(a) { it.blockMessageSender("garbage") }.isSuccess shouldBe true
                asMember(a) { it.blockMessageSender(a.toString()) }.isSuccess shouldBe true
                asMember(a) { it.listMessageBlocks() }.getOrThrow().shouldBeEmpty()
                asMember(a) { it.blockMessageSender(b.toString()) }.isSuccess shouldBe true
                // no conversation with b: no name is revealed
                val own = asMember(a) { it.listMessageBlocks() }.getOrThrow().single()
                own.displayName shouldBe ""
                asMember(c) { it.unblockMessageSender(own.blockId) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                asMember(c) { it.unblockMessageSender(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                asMember(c) { it.unblockMessageSender("garbage") }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                asMember(a) { it.unblockMessageSender(own.blockId) }.isSuccess shouldBe true
            }
        }

        test("every new call refuses callers that are not active members") {
            val guest = person(status = MemberStatus.GUEST)
            val friend = person(status = MemberStatus.FRIEND)
            val applicant = person(status = MemberStatus.APPLICATION)
            val left = person(status = MemberStatus.WITHDRAWN)
            withServiceCaller {
                listOf(guest, friend, applicant, left).forEach { who ->
                    val calls: List<suspend (DirectMessageService) -> Any?> =
                        listOf(
                            { it.searchMessageRecipients("$token abc") },
                            { it.listMessageGroups() },
                            { it.sendGroupMessage(committeeId = Uuid.random().toString(), body = "x") },
                            { it.blockMessageSender(Uuid.random().toString()) },
                            { it.getRecipientSearchVisibility() },
                            { it.setRecipientSearchVisibility(false) },
                            { it.listMessageBlocks() },
                            { it.unblockMessageSender(Uuid.random().toString()) },
                            { it.sendDirectMessage(recipientId = Uuid.random().toString(), body = "x") },
                        )
                    calls.forEach { action ->
                        asMember(who) { action(it) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }

        test("search: length bounds, LIKE metacharacters, exclusions, ordering, the cap and hasConversation") {
            val me = person(name = "$token Ich")
            val named = (1..12).map { person(name = "$token Suche ${it.toString().padStart(2, '0')}") }
            val hidden = person(name = "$token Suche Versteckt")
            val blocked = person(name = "$token Suche Gesperrt")
            val guest = person(name = "$token Suche Gast", status = MemberStatus.GUEST)
            val anonymized = person(name = "$token Suche Anonym")
            transaction {
                MemberTable.update({ MemberTable.id eq hidden }) { it[dmDiscoverable] = false }
                MemberTable.update({ MemberTable.id eq anonymized }) { it[anonymizedAt] = DbClock.nowLocalDateTime() }
                DirectMessageBlockStore.block(blocker = blocked, blocked = me, now = DbClock.nowLocalDateTime())
            }
            withServiceCaller {
                val limiter = limiter()

                suspend fun search(q: String) = asMember(me, limiter) { it.searchMessageRecipients(q) }.getOrThrow()
                search("ab").shouldBeEmpty()
                search("   ab   ").shouldBeEmpty()
                search("x".repeat(DirectMessageRules.RECIPIENT_QUERY_MAX + 1)).shouldBeEmpty()
                search("%%%").shouldBeEmpty()
                search("___").shouldBeEmpty()
                val hits = search("$token suche")
                hits shouldHaveSize DirectMessageRules.MAX_RECIPIENT_SEARCH_RESULTS
                hits.map { it.displayName } shouldContainExactly
                    named.indices.map { "$token Suche ${(it + 1).toString().padStart(2, '0')}" }.take(10)
                search("$token Suche Versteckt").shouldBeEmpty()
                search("$token Suche Gesperrt").shouldBeEmpty()
                search("$token Suche Gast").shouldBeEmpty()
                search("$token Suche Anonym").shouldBeEmpty()
                // a hidden member looks exactly like a name that does not exist
                search("$token Suche Versteckt") shouldBe search("$token Nichtvorhanden")
                // never the caller himself
                search("$token Ich").shouldBeEmpty()
                // searching is case-insensitive and finds a conversation partner with hasConversation = true
                asMember(me, limiter) { it.sendDirectMessage(recipientId = named[0].toString(), body = "Hallo") }.isSuccess shouldBe true
                val again = search(token.uppercase() + " SUCHE 01")
                again.single().hasConversation shouldBe true
                search("$token Suche 02").single().hasConversation shouldBe false
            }
        }

        test("visibility: default true, own row only, hiding removes the search hit but sending by id still works") {
            val a = person(name = "$token Sichtbar")
            val b = person(name = "$token Sucher")
            withServiceCaller {
                asMember(a) { it.getRecipientSearchVisibility() }.getOrThrow() shouldBe true
                asMember(b) { it.searchMessageRecipients("$token Sichtbar") }.getOrThrow() shouldHaveSize 1
                asMember(a) { it.setRecipientSearchVisibility(false) }.isSuccess shouldBe true
                asMember(a) { it.getRecipientSearchVisibility() }.getOrThrow() shouldBe false
                asMember(b) { it.getRecipientSearchVisibility() }.getOrThrow() shouldBe true
                asMember(b) { it.searchMessageRecipients("$token Sichtbar") }.getOrThrow().shouldBeEmpty()
                asMember(b) { it.sendDirectMessage(recipientId = a.toString(), body = "per Kennung") }.isSuccess shouldBe true
            }
        }

        test("groups: a member sees and writes to his committee, a former or future member does not, BOARD may write to any active one") {
            val sender = person(name = "$token Absender")
            val other = person(name = "$token Kollege")
            val former = person(name = "$token Ehemalig")
            val future = person(name = "$token Kuenftig")
            val board = person(name = "$token Vorstand", role = AccountRole.BOARD)
            val own = fx.committee(name = "$token Gruppe Eigen")
            val foreign = fx.committee(name = "$token Gruppe Fremd")
            val inactive = fx.committee(name = "$token Gruppe Inaktiv", active = false)
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY, name = "$token Gruppe Versammlung")
            val today = rc.today
            fx.seat(committee = own, member = sender)
            fx.seat(committee = own, member = other)
            fx.seat(committee = own, member = former, since = LocalDate(2025, 1, 1), until = LocalDate(2025, 12, 31))
            fx.seat(committee = own, member = future, since = today.plus(30, DateTimeUnit.DAY))
            fx.seat(committee = foreign, member = other)
            fx.seat(committee = inactive, member = sender)
            withServiceCaller {
                val groups = asMember(sender) { it.listMessageGroups() }.getOrThrow().filter { it.name.startsWith(token) }
                groups.map { it.name } shouldContainExactly listOf("$token Gruppe Eigen")
                // the sender is not counted; the former and the future member are not recipients
                groups.single().memberCount shouldBe 1
                asMember(former) { it.listMessageGroups() }.getOrThrow().filter { it.name.startsWith(token) }.shouldBeEmpty()
                asMember(future) { it.listMessageGroups() }.getOrThrow().filter { it.name.startsWith(token) }.shouldBeEmpty()
                val boardNames = asMember(board) { it.listMessageGroups() }.getOrThrow().map { it.name }
                boardNames.contains("$token Gruppe Eigen") shouldBe true
                boardNames.contains("$token Gruppe Fremd") shouldBe true
                boardNames.contains("$token Gruppe Inaktiv") shouldBe false
                boardNames.contains("$token Gruppe Versammlung") shouldBe false

                listOf(foreign, inactive, assembly, Uuid.random()).forEach { id ->
                    asMember(sender) { it.sendGroupMessage(committeeId = id.toString(), body = "Hallo") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<DirectMessageGroupUnavailableException>()
                }
                asMember(sender) { it.sendGroupMessage(committeeId = "garbage", body = "Hallo") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageGroupUnavailableException>()
                asMember(former) { it.sendGroupMessage(committeeId = own.toString(), body = "Hallo") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageGroupUnavailableException>()
                asMember(board) { it.sendGroupMessage(committeeId = own.toString(), body = "Vom Vorstand") }.isSuccess shouldBe true
                messagesBetween(from = board, to = sender) shouldBe 1
                messagesBetween(from = board, to = other) shouldBe 1
                messagesBetween(from = board, to = former) shouldBe 0
            }
        }

        test("groups: a rule committee is addressable for the members its rule selects, a broken rule is neither listed nor an error") {
            val manual = fx.committee(name = "$token Basis")
            val inRule = person(name = "$token Regel Drin")
            val outside = person(name = "$token Regel Draussen")
            fx.seat(committee = manual, member = inRule)
            val ruleCommittee = rc.ruleCommittee(ruleJson = rc.seatedIn(manual))
            transaction { CommitteeTable.update({ CommitteeTable.id eq ruleCommittee }) { it[name] = "$token Regelgruppe" } }
            val broken = rc.ruleCommittee(ruleJson = rc.seatedIn(manual))
            transaction {
                CommitteeTable.update({ CommitteeTable.id eq broken }) {
                    it[name] = "$token Kaputt"
                    it[ruleJson] = "{not json"
                }
            }
            val board = person(name = "$token Vorstand Regel", role = AccountRole.BOARD)
            withServiceCaller {
                val seen = asMember(inRule) { it.listMessageGroups() }.getOrThrow().map { it.name }.filter { it.startsWith(token) }
                seen.contains("$token Regelgruppe") shouldBe true
                seen.contains("$token Kaputt") shouldBe false
                asMember(outside) { it.listMessageGroups() }.getOrThrow().map { it.name }.contains("$token Regelgruppe") shouldBe false
                asMember(outside) { it.sendGroupMessage(committeeId = ruleCommittee.toString(), body = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageGroupUnavailableException>()
                // even BOARD gets the uniform refusal for a broken rule, and the list does not fail
                asMember(board) { it.listMessageGroups() }.getOrThrow().map { it.name }.contains("$token Kaputt") shouldBe false
                asMember(board) { it.sendGroupMessage(committeeId = broken.toString(), body = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageGroupUnavailableException>()
            }
        }

        test(
            "fan-out: one row per recipient with the same sentAt, blocked recipients skipped silently, memberCount unchanged, audit holds the count only",
        ) {
            val sender = person(name = "$token FanOut Absender")
            val a = person()
            val b = person()
            val blockedMe = person()
            val inactiveSeat = person(status = MemberStatus.WITHDRAWN)
            val committee = fx.committee(name = "$token FanOut Gruppe")
            listOf(sender, a, b, blockedMe, inactiveSeat).forEach { fx.seat(committee = committee, member = it) }
            transaction { DirectMessageBlockStore.block(blocker = blockedMe, blocked = sender, now = DbClock.nowLocalDateTime()) }
            val mark = fx.auditMark()
            withServiceCaller {
                val before = asMember(sender) { it.listMessageGroups() }.getOrThrow().single { it.name == "$token FanOut Gruppe" }
                before.memberCount shouldBe 3
                val result =
                    asMember(
                        sender,
                    ) { it.sendGroupMessage(committeeId = committee.toString(), body = "  Hallo zusammen  ") }.getOrThrow()
                result.committeeName shouldBe "$token FanOut Gruppe"
                listOf(a, b).forEach { messagesBetween(from = sender, to = it) shouldBe 1 }
                messagesBetween(from = sender, to = blockedMe) shouldBe 0
                messagesBetween(from = sender, to = inactiveSeat) shouldBe 0
                messagesBetween(from = sender, to = sender) shouldBe 0
                val rows =
                    transaction {
                        DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender }.map {
                            it[DirectMessageTable.sentAt] to
                                it[DirectMessageTable.body]
                        }
                    }
                rows.map { it.first }.toSet() shouldBe setOf(result.sentAt)
                rows.map { it.second }.toSet() shouldBe setOf("Hallo zusammen")
                // blocks are not subtracted from the advertised count
                asMember(sender) { it.listMessageGroups() }.getOrThrow().single { it.name == "$token FanOut Gruppe" }.memberCount shouldBe 3
            }
            val audit = fx.auditSince(mark = mark, type = AuditEntityType.DIRECT_MESSAGE_GROUP_SEND)
            audit shouldHaveSize 1
            audit.single()[AuditLogEntryTable.entityId] shouldBe committee
            val snapshot = audit.single()[AuditLogEntryTable.afterSnapshot].orEmpty()
            snapshot shouldBe """{"deliveredCount":2}"""
            snapshot shouldNotContain "Hallo"
            snapshot shouldNotContain sender.toString()
        }

        test("fan-out: exactly 50 recipients work, 51 are refused with nothing written, a bad body writes nothing") {
            val sender = person(name = "$token Gross Absender")
            val big = fx.committee(name = "$token Gross 50")
            val tooBig = fx.committee(name = "$token Gross 51")
            fx.seat(committee = big, member = sender)
            fx.seat(committee = tooBig, member = sender)
            val people = (1..51).map { person() }
            people.take(DirectMessageRules.MAX_RECIPIENTS_PER_SEND).forEach { fx.seat(committee = big, member = it) }
            people.forEach { fx.seat(committee = tooBig, member = it) }
            withServiceCaller {
                asMember(sender) { it.sendGroupMessage(committeeId = tooBig.toString(), body = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageGroupTooLargeException>()
                transaction { DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender }.count() } shouldBe 0
                asMember(sender) {
                    it.sendGroupMessage(committeeId = big.toString(), body = "   ")
                }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                asMember(
                    sender,
                ) { it.sendGroupMessage(committeeId = big.toString(), body = "x".repeat(DIRECT_MESSAGE_MAX_BODY_LENGTH + 1)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                transaction { DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender }.count() } shouldBe 0
                asMember(sender) { it.sendGroupMessage(committeeId = big.toString(), body = "ok") }.isSuccess shouldBe true
                transaction { DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender }.count() } shouldBe 50
            }
        }

        test("the group send is rate limited by recipients, and a refused attempt writes nothing") {
            val sender = person(name = "$token Limit Absender")
            val committee = fx.committee(name = "$token Limit Gruppe")
            fx.seat(committee = committee, member = sender)
            (1..40).forEach { fx.seat(committee = committee, member = person()) }
            withServiceCaller {
                val shared = limiter()
                repeat(3) {
                    asMember(sender, shared) { it.sendGroupMessage(committeeId = committee.toString(), body = "x") }.isSuccess shouldBe
                        true
                }
                // 3 x 40 = 120 recipients so far; one more group of 40 would exceed 150 per hour
                asMember(sender, shared) { it.sendGroupMessage(committeeId = committee.toString(), body = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageRateLimitedException>()
                transaction { DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender }.count() } shouldBe 120
            }
        }

        test("limiter: 30 sends per 10 minutes, 150 recipients per hour, 60 searches per minute, reset after the window") {
            var now = Instant.parse("2026-03-01T10:00:00Z")
            val limiter = DirectMessageRateLimiter(now = { now })
            val me = Uuid.random()
            repeat(30) { limiter.checkAndRecordSend(memberId = me, recipients = 1) shouldBe true }
            limiter.checkAndRecordSend(memberId = me, recipients = 1) shouldBe false
            now += 11.minutes
            limiter.checkAndRecordSend(memberId = me, recipients = 1) shouldBe true
            // recipients in the hour so far: 30 + 1; another 119 make exactly 150, one more is too many
            limiter.checkAndRecordSend(memberId = me, recipients = 119) shouldBe true
            limiter.checkAndRecordSend(memberId = me, recipients = 1) shouldBe false
            now += 61.minutes
            limiter.checkAndRecordSend(memberId = me, recipients = 50) shouldBe true
            repeat(60) { limiter.checkAndRecordSearch(memberId = me) shouldBe true }
            limiter.checkAndRecordSearch(memberId = me) shouldBe false
            limiter.checkAndRecordSearch(memberId = Uuid.random()) shouldBe true
            now += 2.minutes
            limiter.checkAndRecordSearch(memberId = me) shouldBe true
        }

        test("the search is rate limited (61st call per minute) and a refused search is the typed exception") {
            val me = person(name = "$token Suchlimit")
            withServiceCaller {
                val shared = limiter()
                repeat(60) { asMember(me, shared) { it.searchMessageRecipients("$token abc") }.isSuccess shouldBe true }
                asMember(me, shared) { it.searchMessageRecipients("$token abc") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<DirectMessageRateLimitedException>()
            }
        }

        test("the dm_discoverable column exists with default true on a freshly inserted member") {
            val id = person()
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.dmDiscoverable] } shouldBe true
            (id != Uuid.NIL) shouldNotBe false
        }
    })
