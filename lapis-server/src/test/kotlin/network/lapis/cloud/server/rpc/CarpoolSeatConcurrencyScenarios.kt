package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- "never more confirmed passengers than seats" and "exactly one row per (posting, person)" are APPLICATION rules upheld by the
 * `SELECT ... FOR UPDATE` on the POSTING row that every write on a trip takes first. Proven with real parallel requests, on H2 (smoke) and on a
 * real PostgreSQL (the actual proof, including "no deadlock").
 *
 * H2 note: its lock timeout can turn the losing request into a lock-timeout error instead of "no seat left"; the INVARIANT is asserted on both
 * lanes, the exact loser message only on PostgreSQL.
 */
abstract class CarpoolSeatConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            if (!db.isPostgres) data.cleanUp()
            db.deactivate()
        }

        fun confirmedCount(posting: Uuid) =
            transaction {
                CarpoolParticipationTable
                    .selectAll()
                    .where {
                        (CarpoolParticipationTable.postingId eq posting) and
                            (CarpoolParticipationTable.status eq CarpoolParticipationStatus.CONFIRMED)
                    }.count()
            }

        test("two drivers' confirmations race for the last seat: exactly one wins") {
            carpoolTestApplication {
                val driver = data.member(label = "race-driver")
                val offer = data.posting(author = driver, seats = 1)
                val a = call(member = data.member(label = "race-a", status = MemberStatus.GUEST)) { requestParticipation(offer.toString()) }
                val b = call(member = data.member(label = "race-b", status = MemberStatus.GUEST)) { requestParticipation(offer.toString()) }
                val results =
                    coroutineScope {
                        listOf(a.id, b.id)
                            .map { id -> async(Dispatchers.IO) { attempt(member = driver) { confirmParticipation(id) } } }
                            .awaitAll()
                    }
                confirmedCount(offer) shouldBe 1L
                results.count { it.isSuccess } shouldBe 1
                if (db.isPostgres) {
                    val loser = results.single { it.isFailure }.exceptionOrNull()
                    loser.shouldBeInstanceOf<ConflictException>().message shouldBe "Für diese Fahrt ist kein Platz mehr frei."
                }
            }
        }

        test("a confirmation races an update that lowers the seats: confirmed never exceeds the seats") {
            carpoolTestApplication {
                val driver = data.member(label = "upd-driver")
                val offer = data.posting(author = driver, seats = 2)
                val first =
                    call(member = data.member(label = "upd-a", status = MemberStatus.GUEST)) { requestParticipation(offer.toString()) }
                val second =
                    call(member = data.member(label = "upd-b", status = MemberStatus.GUEST)) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(first.id) }
                val lower =
                    CarpoolPostingInput(
                        type = CarpoolPostingType.OFFER,
                        fromPlace = "Braunschweig",
                        toPlace = "Hannover",
                        departureDate = data.today().plus(DatePeriod(days = 10)),
                        seatsOffered = 1,
                    )
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { attempt(member = driver) { confirmParticipation(second.id) } },
                        async(Dispatchers.IO) { attempt(member = driver) { updatePosting(id = offer.toString(), input = lower) } },
                    ).awaitAll()
                }
                val seats =
                    transaction {
                        CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq offer }.single()[CarpoolPostingTable.seatsOffered]
                    }!!
                (confirmedCount(offer) <= seats) shouldBe true
            }
        }

        test("the same person asking twice in parallel leaves exactly one row and no unique violation") {
            carpoolTestApplication {
                val driver = data.member(label = "dbl-driver")
                val offer = data.posting(author = driver, seats = 3)
                val guest = data.member(label = "dbl-guest", status = MemberStatus.GUEST)
                val results =
                    coroutineScope {
                        (1..4)
                            .map {
                                async(
                                    Dispatchers.IO,
                                ) { attempt(member = guest) { requestParticipation(offer.toString()) } }
                            }.awaitAll()
                    }
                results.forEach { it.isSuccess shouldBe true }
                data.participationRows(offer).size shouldBe 1
            }
        }
    })

class CarpoolSeatConcurrencyTest : CarpoolSeatConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolSeatConcurrencyPostgresTest : CarpoolSeatConcurrencyScenarios(TestDatabase.Postgres())
