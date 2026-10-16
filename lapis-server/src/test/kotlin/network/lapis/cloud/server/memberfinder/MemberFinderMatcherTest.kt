package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.144 -- who can be drawn. A region of six: the sender, ONE candidate `t` (interest BOTH) and four fillers that only count for the threshold
 * (interest GROUP_FOUNDING, so they never match a MEETUP request). Each exclusion is applied to `t` alone and must remove it; the control cases must not.
 */
class MemberFinderMatcherTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        class Cast(
            val sender: Uuid,
            val t: Uuid,
            val fillers: List<Uuid>,
        )

        fun cast(): Cast {
            val sender =
                data
                    .member(
                        label = "sender",
                        postalCode = "38001",
                    ).also { data.consent(memberId = it, interest = MemberFinderInterest.BOTH) }
            val t =
                data
                    .member(
                        label = "target",
                        postalCode = "38002",
                    ).also { data.consent(memberId = it, interest = MemberFinderInterest.BOTH) }
            val fillers =
                (1..4).map { i ->
                    data
                        .member(
                            label = "filler$i",
                            postalCode = "3801$i",
                        ).also { data.consent(memberId = it, interest = MemberFinderInterest.GROUP_FOUNDING) }
                }
            return Cast(sender, t, fillers)
        }

        fun pick(
            cast: Cast,
            purpose: MemberFinderPurpose = MemberFinderPurpose.MEETUP,
            random: MemberFinderRandom = MemberFinderRandom { 0 },
        ): Uuid? =
            transaction {
                val now = ServerClock.now()
                val participants =
                    MemberFinderEligibility.participants(
                        today = OrganizationTimeZone.dateOf(now),
                        current = AgreementTextResolver.current(kind = AgreementTextKind.FINDER),
                    )
                val sender = participants.first { it.memberId == cast.sender }
                MemberFinderMatcher.pick(sender = sender, purpose = purpose, participants = participants, now = now, random = random)
            }

        test("control: without any exclusion the only matching candidate is drawn; a filler that does not match the purpose is not") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                val c = cast()
                pick(c) shouldBe c.t
            }
        }

        test("a region below the REAL threshold draws nobody even if a candidate exists") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                val sender = data.member(label = "s", postalCode = "38001").also { data.consent(memberId = it) }
                val t = data.member(label = "t", postalCode = "38002").also { data.consent(memberId = it) }
                data.region(count = 2, label = "x")
                val c = Cast(sender, t, emptyList())
                pick(c) shouldBe null
            }
        }

        val exclusions: Map<String, (Cast) -> Unit> =
            mapOf(
                "the sender blocked the candidate" to
                    { c ->
                        transaction {
                            MemberFinderBlockStore.block(
                                blockerMemberId = c.sender,
                                blockedMemberId = c.t,
                                now = ServerClock.now(),
                            )
                        }
                    },
                "the candidate blocked the sender" to
                    { c ->
                        transaction {
                            MemberFinderBlockStore.block(
                                blockerMemberId = c.t,
                                blockedMemberId = c.sender,
                                now = ServerClock.now(),
                            )
                        }
                    },
                "an open request sender to candidate" to { c -> data.request(from = c.sender, to = c.t) },
                "an open request candidate to sender" to { c -> data.request(from = c.t, to = c.sender) },
                "a connection in either direction" to { c ->
                    data.request(from = c.t, to = c.sender, status = MemberFinderRequestStatus.ACCEPTED)
                },
                "a declined pair of the last seven days" to { c ->
                    data.request(
                        from = c.sender,
                        to = c.t,
                        status = MemberFinderRequestStatus.DECLINED,
                        decidedAt = ServerClock.now().minusDays(1),
                    )
                },
                "an expired pair of the last seven days" to { c ->
                    data.request(
                        from = c.t,
                        to = c.sender,
                        status = MemberFinderRequestStatus.EXPIRED,
                        decidedAt = ServerClock.now().minusDays(6),
                    )
                },
                "a withdrawn pair of the last seven days" to { c ->
                    data.request(
                        from = c.sender,
                        to = c.t,
                        status = MemberFinderRequestStatus.WITHDRAWN,
                        decidedAt = ServerClock.now().minusDays(2),
                    )
                },
                "a full inbox (three open requests)" to { c ->
                    c.fillers.take(3).forEach { data.request(from = it, to = c.t) }
                },
                "an interest that does not match the purpose" to { c ->
                    transaction {
                        MemberFinderConsentStore.grant(
                            memberId = c.t,
                            version = AgreementTextResolver.current(kind = AgreementTextKind.FINDER).versionLabel,
                            sha256 = AgreementTextResolver.current(kind = AgreementTextKind.FINDER).sha256,
                            interest = MemberFinderInterest.GROUP_FOUNDING,
                            now = ServerClock.now(),
                        )
                    }
                },
            )
        exclusions.forEach { (name, apply) ->
            test("excluded: $name") {
                TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                    val c = cast()
                    apply(c)
                    pick(c) shouldBe null
                }
            }
        }

        test(
            "not excluded: a declined pair eight days ago, two open inbox entries, and a GROUP_FOUNDING interest for a GROUP_FOUNDING purpose",
        ) {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                val c = cast()
                data.request(
                    from = c.sender,
                    to = c.t,
                    status = MemberFinderRequestStatus.DECLINED,
                    decidedAt = ServerClock.now().minusDays(8),
                )
                c.fillers.take(2).forEach { data.request(from = it, to = c.t) }
                pick(c) shouldBe c.t
                // the fillers are GROUP_FOUNDING: for that purpose they are candidates too, sorted by id, the random index decides
                val candidates = (c.fillers + c.t).sorted()
                pick(c, purpose = MemberFinderPurpose.GROUP_FOUNDING, random = MemberFinderRandom { 0 }) shouldBe candidates.first()
            }
        }

        test("the random index chooses among the candidates in id order; every candidate is reachable") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                val c = cast()
                val sorted = (c.fillers + c.t).sorted()
                val seen =
                    sorted.indices
                        .map { index ->
                            pick(
                                c,
                                purpose = MemberFinderPurpose.GROUP_FOUNDING,
                                random =
                                    MemberFinderRandom { bound ->
                                        index %
                                            bound
                                    },
                            )
                        }.toSet()
                seen shouldBe sorted.toSet()
            }
        }
    })
