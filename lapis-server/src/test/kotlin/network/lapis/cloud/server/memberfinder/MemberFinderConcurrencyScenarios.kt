package network.lapis.cloud.server.memberfinder

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.144 -- the finder's rules that no constraint can state (the inbox limit of three, one final state per request, "after a switch-off nothing is
 * left") are upheld by ONE global lock (`MemberFinderSettingsStore.lockAndLoad`) that every finder write takes first. Proven with real parallel requests,
 * on H2 (smoke) and on a real PostgreSQL (the actual proof, including "no deadlock").
 *
 * H2 note: its lock timeout can turn a losing request into a lock-timeout error; the INVARIANT is asserted on both lanes.
 */
abstract class MemberFinderConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = FinderTestData()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterTest { data.reset() }
        afterSpec { db.deactivate() }

        test("four senders race for an addressee with two open requests: never more than three open requests reach them") {
            data.enableFinder()
            val t =
                data.member(label = "target", postalCode = "38002").also {
                    data.consent(memberId = it, interest = MemberFinderInterest.GROUP_FOUNDING)
                }
            val senders =
                (1..4).map { i ->
                    data
                        .member(
                            label = "s$i",
                            postalCode = "3801$i",
                        ).also { data.consent(memberId = it, interest = MemberFinderInterest.BOTH) }
                }
            val filler =
                data.member(label = "filler", postalCode = "38020").also {
                    data.consent(memberId = it, interest = MemberFinderInterest.MEETUP)
                }
            // the senders must never draw each other: block every pair, so that t is the ONLY candidate
            transaction {
                for (a in senders) {
                    for (b in senders) {
                        if (a !=
                            b
                        ) {
                            MemberFinderBlockStore.block(
                                blockerMemberId = a,
                                blockedMemberId = b,
                                now =
                                    network.lapis.cloud.server.time.ServerClock
                                        .now(),
                            )
                        }
                    }
                }
                for (s in senders) {
                    MemberFinderBlockStore.block(
                        blockerMemberId = s,
                        blockedMemberId = filler,
                        now =
                            network.lapis.cloud.server.time.ServerClock
                                .now(),
                    )
                }
            }
            data.computeBands()
            finderTestApplication {
                val results =
                    coroutineScope {
                        senders
                            .map { s ->
                                async(
                                    Dispatchers.IO,
                                ) { attempt(member = s) { sendRequest(purpose = MemberFinderPurpose.GROUP_FOUNDING, note = null) } }
                            }.awaitAll()
                    }
                val delivered = data.requests().count { it.toMemberId == t }
                (delivered <= 3) shouldBe true
                // every sender was answered; on PostgreSQL there is no timeout, so all four succeed and exactly one of them is a phantom
                if (db.isPostgres) {
                    results.count { it.isSuccess } shouldBe 4
                    delivered shouldBe 3
                }
            }
        }

        test("an addressee accepting races the sender withdrawing: exactly one of them wins and the end state matches the winner") {
            data.enableFinder()
            val six = data.region(count = 6, label = "six")
            data.computeBands()
            val sender = six[0]
            val x = six[1]
            val id = data.request(from = sender, to = x)
            finderTestApplication {
                val results =
                    coroutineScope {
                        listOf(
                            async(
                                Dispatchers.IO,
                            ) { attempt(member = x) { respondToRequest(requestId = id.toString(), accept = true, block = false) } },
                            async(Dispatchers.IO) { attempt(member = sender) { withdrawRequest(id.toString()) } },
                        ).awaitAll()
                    }
                val status = data.requests().single { it.id == id }.status
                if (db.isPostgres) {
                    results.count { it.isSuccess } shouldBe 1
                    results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
                (status == MemberFinderRequestStatus.ACCEPTED || status == MemberFinderRequestStatus.WITHDRAWN) shouldBe true
                // the winner and the end state agree
                if (db.isPostgres) (status == MemberFinderRequestStatus.ACCEPTED) shouldBe results[0].isSuccess
            }
        }

        test(
            "the instance switch goes off while requests are sent: afterwards no request, no block, no band and no effective consent is left",
        ) {
            data.enableFinder()
            val admin = data.member(label = "admin", role = AccountRole.ADMIN, postalCode = "99999")
            val six = data.region(count = 6, label = "six")
            data.computeBands()
            finderTestApplication {
                val outcomes =
                    coroutineScope {
                        val sends =
                            six.map { s ->
                                async(
                                    Dispatchers.IO,
                                ) { attempt(member = s) { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) } }
                            }
                        val off =
                            async(
                                Dispatchers.IO,
                            ) {
                                attemptSettings(
                                    member = admin,
                                ) { updateMemberFinderEnabled(enabled = false, acknowledgedNoticeVersion = null) }
                            }
                        listOf(off.await()) to sends.awaitAll()
                    }
                outcomes.first.single().isSuccess shouldBe true
                data.requests().size shouldBe 0
                data.bandRows() shouldBe 0
                data.blockCount() shouldBe 0
                for (m in six) transaction { MemberFinderConsentStore.openGrant(m) } shouldBe null
                // every send either won before the switch-off (and was deleted by it) or was refused with the same type afterwards
                outcomes.second.forEach { r ->
                    (r.isSuccess || r.exceptionOrNull() is MemberFinderUnavailableException || !db.isPostgres) shouldBe true
                }
                // and the switch is really off for everybody now
                attempt(member = six[0]) { getConsentText() }.exceptionOrNull().shouldBeInstanceOf<MemberFinderUnavailableException>()
            }
        }

        test("opting in twice in parallel leaves exactly one open consent") {
            data.enableFinder()
            val m = data.member(label = "double")
            finderTestApplication {
                val text = call(member = m) { getConsentText() }
                val results =
                    coroutineScope {
                        (1..4)
                            .map {
                                async(
                                    Dispatchers.IO,
                                ) {
                                    attempt(member = m) {
                                        optIn(
                                            consentVersion = text.versionLabel,
                                            consentSha256 = text.sha256,
                                            interest = MemberFinderInterest.BOTH,
                                            adultConfirmed = true,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                if (db.isPostgres) results.forEach { it.isSuccess shouldBe true }
                transaction { MemberFinderConsentStore.openGrant(m) }?.interest shouldBe MemberFinderInterest.BOTH
                // exactly one open row, however the parallel calls interleaved
                data.openConsentRows(m) shouldBe 1
            }
        }
    })

class MemberFinderConcurrencyTest : MemberFinderConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberFinderConcurrencyPostgresTest : MemberFinderConcurrencyScenarios(TestDatabase.Postgres())
