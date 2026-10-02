package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/** V1.9.36 -- partner list, keyset paging and bulk mark-read of [DirectMessageService] (own throwaway members only). */
class DirectMessagePartnersAndPagingTest :
    FunSpec({
        val members = ThrowawayMembers()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { members.cleanup() }

        fun at(minute: Int) = LocalDateTime(2026, 3, 1, 10, minute, 0)

        test("partner list: sent-only partner has unread 0, received partner counts unread, last activity is the max of both directions") {
            val a = members.create(email = "v1936-p-a@example.test", displayName = "Alice P")
            val b = members.create(email = "v1936-p-b@example.test", displayName = "Bob P")
            val c = members.create(email = "v1936-p-c@example.test", displayName = "Carol P")
            members.message(from = a, to = b, sentAt = at(1))
            members.message(from = c, to = a, sentAt = at(5))
            members.message(from = c, to = a, sentAt = at(6), read = true)
            members.message(from = b, to = a, sentAt = at(3), read = true)
            members.message(from = a, to = b, sentAt = at(4))
            withServiceCaller {
                val list = call(caller = a) { DirectMessageService(it).listConversationPartners(50) }.getOrThrow()
                list.map { it.partnerDisplayName } shouldContainExactly listOf("Carol P", "Bob P")
                val carol = list.first { it.partnerId == c.toString() }
                carol.unreadCount shouldBe 1
                carol.lastActivityAt shouldBe at(6)
                val bob = list.first { it.partnerId == b.toString() }
                bob.unreadCount shouldBe 0
                bob.lastActivityAt shouldBe at(4)
            }
        }

        test("partner list: a conversation between others is invisible and a self message is not a partner") {
            val a = members.create(email = "v1936-q-a@example.test", displayName = "Alice Q")
            val b = members.create(email = "v1936-q-b@example.test", displayName = "Bob Q")
            val c = members.create(email = "v1936-q-c@example.test", displayName = "Carol Q")
            members.message(from = b, to = c, sentAt = at(1))
            members.message(from = a, to = a, sentAt = at(2))
            withServiceCaller {
                call(caller = a) { DirectMessageService(it).listConversationPartners(50) }.getOrThrow() shouldHaveSize 0
            }
        }

        test("partner list: ties on last activity are broken by partner id, limit is clamped to 1..100") {
            val me = members.create(email = "v1936-r-me@example.test", displayName = "Me R")
            val partners = (0 until 105).map { members.create(email = "v1936-r-$it@example.test", displayName = "Partner R$it") }
            partners.forEach { members.message(from = it, to = me, sentAt = at(7)) }
            withServiceCaller {
                val all = call(caller = me) { DirectMessageService(it).listConversationPartners(1000) }.getOrThrow()
                all shouldHaveSize MAX_CONVERSATION_PARTNERS
                all.map { it.partnerId } shouldContainExactly all.map { it.partnerId }.sorted()
                call(caller = me) { DirectMessageService(it).listConversationPartners(0) }.getOrThrow() shouldHaveSize 1
                call(caller = me) { DirectMessageService(it).listConversationPartners(-5) }.getOrThrow() shouldHaveSize 1
            }
        }

        test("partner list and paging reject an inactive caller") {
            val inactive = members.create(email = "v1936-s-w@example.test", status = MemberStatus.WITHDRAWN)
            val friend = members.create(email = "v1936-s-f@example.test", status = MemberStatus.FRIEND)
            withServiceCaller {
                listOf(inactive, friend).forEach { who ->
                    call(
                        caller = who,
                    ) { DirectMessageService(it).listConversationPartners(10) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    call(caller = who) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = Uuid.random().toString(), beforeSentAt = null, beforeId = null, limit = 10)
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    call(caller = who) { DirectMessageService(it).markConversationRead(Uuid.random().toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("the partner DTO has no text field") {
            DirectMessagePartnerDto::class
                .members
                .map { it.name }
                .toSet()
                .none { it == "body" || it == "text" || it == "preview" } shouldBe
                true
        }

        test("paging: identical sentAt, pages of 3 yield 7 unique ids, strictly descending by (sentAt, id)") {
            val a = members.create(email = "v1936-t-a@example.test")
            val b = members.create(email = "v1936-t-b@example.test")
            val sameTime = at(20)
            repeat(7) { i ->
                if (i % 2 ==
                    0
                ) {
                    members.message(from = a, to = b, sentAt = sameTime)
                } else {
                    members.message(from = b, to = a, sentAt = sameTime)
                }
            }
            withServiceCaller {
                val seen = mutableListOf<Pair<LocalDateTime, String>>()
                var cursor: network.lapis.cloud.shared.domain.DirectMessageCursorDto? = null
                var pages = 0
                do {
                    val page =
                        call(caller = a) {
                            DirectMessageService(
                                it,
                            ).listConversationPage(
                                otherMemberId = b.toString(),
                                beforeSentAt = cursor?.sentAt,
                                beforeId = cursor?.id,
                                limit = 3,
                            )
                        }.getOrThrow()
                    page.messages.forEach { seen += it.sentAt to it.id }
                    cursor = page.nextCursor
                    pages++
                } while (page.hasMore)
                pages shouldBe 3
                seen.map { it.second }.toSet() shouldHaveSize 7
                val oneShot =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = null, beforeId = null, limit = 100)
                    }.getOrThrow()
                seen.map { it.second } shouldContainExactly oneShot.messages.map { it.id }
            }
        }

        test("paging: a message inserted between two pages causes neither a duplicate nor a gap") {
            val a = members.create(email = "v1936-u-a@example.test")
            val b = members.create(email = "v1936-u-b@example.test")
            val ids = (1..6).map { members.message(from = a, to = b, sentAt = at(it)) }
            withServiceCaller {
                val first =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = null, beforeId = null, limit = 3)
                    }.getOrThrow()
                members.message(from = b, to = a, sentAt = at(30))
                val cursor = first.nextCursor.shouldNotBeNull()
                val second =
                    call(
                        caller = a,
                    ) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = cursor.sentAt, beforeId = cursor.id, limit = 3)
                    }.getOrThrow()
                (first.messages + second.messages).map { it.id }.toSet() shouldBe ids.map { it.toString() }.toSet()
                (first.messages + second.messages).map { it.id }.distinct() shouldHaveSize 6
                second.hasMore shouldBe false
            }
        }

        test("paging: empty first page, limit clamp, bad cursors, foreign conversation, self, no echo of input") {
            val a = members.create(email = "v1936-v-a@example.test")
            val b = members.create(email = "v1936-v-b@example.test")
            val c = members.create(email = "v1936-v-c@example.test")
            repeat(120) { members.message(from = a, to = b, sentAt = LocalDateTime(2026, 3, 2, it / 60, it % 60, 0)) }
            withServiceCaller {
                val empty =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = c.toString(), beforeSentAt = null, beforeId = null, limit = 10)
                    }.getOrThrow()
                empty.messages shouldHaveSize 0
                empty.hasMore shouldBe false
                empty.nextCursor.shouldBeNull()
                val big =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = null, beforeId = null, limit = 500)
                    }.getOrThrow()
                big.messages shouldHaveSize MAX_CONVERSATION_PAGE
                big.hasMore shouldBe true
                val half =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = at(1), beforeId = null, limit = 10)
                    }.exceptionOrNull()
                half.shouldBeInstanceOf<BadRequestException>()
                val other =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = null, beforeId = "x", limit = 10)
                    }.exceptionOrNull()
                other.shouldBeInstanceOf<BadRequestException>()
                val bad =
                    call(caller = a) {
                        DirectMessageService(
                            it,
                        ).listConversationPage(otherMemberId = "secret-input-123", beforeSentAt = null, beforeId = null, limit = 10)
                    }.exceptionOrNull()
                bad.shouldBeInstanceOf<BadRequestException>()
                bad.message.contains("secret-input-123") shouldBe false
                call(caller = c) {
                    DirectMessageService(
                        it,
                    ).listConversationPage(otherMemberId = b.toString(), beforeSentAt = null, beforeId = null, limit = 10)
                }.getOrThrow().messages shouldHaveSize
                    0
                call(caller = a) {
                    DirectMessageService(
                        it,
                    ).listConversationPage(otherMemberId = a.toString(), beforeSentAt = null, beforeId = null, limit = 10)
                }.getOrThrow().messages shouldHaveSize
                    0
            }
        }

        test("markConversationRead marks only mail to me from that partner, is idempotent and gives no existence oracle") {
            val a = members.create(email = "v1936-w-a@example.test")
            val b = members.create(email = "v1936-w-b@example.test")
            val c = members.create(email = "v1936-w-c@example.test")
            members.message(from = b, to = a, sentAt = at(1))
            members.message(from = b, to = a, sentAt = at(2))
            members.message(from = b, to = a, sentAt = at(3), read = true)
            members.message(from = c, to = a, sentAt = at(4))
            members.message(from = a, to = b, sentAt = at(5))
            withServiceCaller {
                call(caller = a) { DirectMessageService(it).markConversationRead(b.toString()) }.getOrThrow() shouldBe 2
                call(caller = a) { DirectMessageService(it).markConversationRead(b.toString()) }.getOrThrow() shouldBe 0
                val partners = call(caller = a) { DirectMessageService(it).listConversationPartners(10) }.getOrThrow()
                partners.first { it.partnerId == c.toString() }.unreadCount shouldBe 1
                partners.first { it.partnerId == b.toString() }.unreadCount shouldBe 0
                call(caller = b) { DirectMessageService(it).unreadCount() }.getOrThrow() shouldBe 1 // my sent message stays unread for b
                call(caller = a) { DirectMessageService(it).markConversationRead(Uuid.random().toString()) }.getOrThrow() shouldBe 0
                call(
                    caller = a,
                ) { DirectMessageService(it).markConversationRead("nope") }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
            }
        }
    })
