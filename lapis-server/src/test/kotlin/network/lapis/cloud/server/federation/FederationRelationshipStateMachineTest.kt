package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.shared.domain.FederationEventType
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val T0 = LocalDateTime(2026, 1, 1, 0, 0)
private val T1 = LocalDateTime(2026, 1, 2, 0, 0)

/**
 * Direct, network-free tests of [FederationRelationshipStore] -- the persistence layer both
 * [network.lapis.cloud.server.rpc.FederationService] (outbound) and
 * [network.lapis.cloud.server.routes.registerFederationRoutes]'s inbox handler (inbound) build the
 * Follow/Accept/Reject/Undo state machine on top of. Pins the load-bearing invariant
 * [FederationRelationshipStore.upsertByRemoteActorUri] KDoc documents: `remote_actor_uri` has a
 * hard DB `UNIQUE` constraint, so re-establishing federation after a terminal
 * (`REJECTED`/`UNDONE`) status must UPDATE the same row, never `INSERT` a second one.
 */
class FederationRelationshipStateMachineTest :
    FunSpec({
        val createdIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            transaction {
                FederationRelationshipEventTable.deleteWhere { FederationRelationshipEventTable.relationshipId inList createdIds }
                FederationRelationshipTable.deleteWhere { FederationRelationshipTable.id inList createdIds }
            }
            createdIds.clear()
        }

        fun track(id: Uuid): Uuid {
            createdIds += id
            return id
        }

        test("upsertByRemoteActorUri: inserts a fresh PENDING row when none exists yet") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        FederationRelationshipDirection.OUTBOUND,
                        remoteActorUri,
                        "https://remote.example/inbox",
                        "PEM",
                        "activity-1",
                        T0,
                    )
                }
            (id != null) shouldBe true
            track(id!!)
            val row = transaction { FederationRelationshipStore.findById(id)!! }
            row[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.PENDING
            row[FederationRelationshipTable.direction] shouldBe FederationRelationshipDirection.OUTBOUND
        }

        test("upsertByRemoteActorUri: returns null (no mutation) when a PENDING row already exists for the same remoteActorUri") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    track(
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!,
                    )
                }
            val second =
                transaction {
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        FederationRelationshipDirection.INBOUND,
                        remoteActorUri,
                        "https://remote.example/inbox",
                        "PEM",
                        "activity-2",
                        T1,
                    )
                }
            second shouldBe null
            // The original row is untouched.
            val row = transaction { FederationRelationshipStore.findById(id)!! }
            row[FederationRelationshipTable.direction] shouldBe FederationRelationshipDirection.OUTBOUND
            row[FederationRelationshipTable.initiatedActivityId] shouldBe "activity-1"
        }

        test("upsertByRemoteActorUri: returns null (no mutation) when an ACTIVE row already exists") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    val inserted =
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!
                    FederationRelationshipStore.updateStatus(inserted, FederationRelationshipStatus.ACTIVE, T0)
                    track(inserted)
                }
            val second =
                transaction {
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        FederationRelationshipDirection.INBOUND,
                        remoteActorUri,
                        "https://remote.example/inbox",
                        "PEM",
                        "activity-2",
                        T1,
                    )
                }
            second shouldBe null
            transaction { FederationRelationshipStore.findById(id)!![FederationRelationshipTable.status] } shouldBe
                FederationRelationshipStatus.ACTIVE
        }

        test(
            "upsertByRemoteActorUri: a TERMINAL (REJECTED) row is UPDATED back to PENDING, never a second row inserted (unique constraint)",
        ) {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val originalId =
                transaction {
                    val inserted =
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!
                    FederationRelationshipStore.updateStatus(inserted, FederationRelationshipStatus.REJECTED, T0)
                    track(inserted)
                }

            val reestablishedId =
                transaction {
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        FederationRelationshipDirection.INBOUND,
                        remoteActorUri,
                        "https://remote.example/inbox-v2",
                        "PEM2",
                        "activity-2",
                        T1,
                    )
                }

            reestablishedId shouldBe originalId // same row, UPDATEd -- not a new id
            val row = transaction { FederationRelationshipStore.findById(originalId)!! }
            row[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.PENDING
            row[FederationRelationshipTable.direction] shouldBe FederationRelationshipDirection.INBOUND
            row[FederationRelationshipTable.initiatedActivityId] shouldBe "activity-2"
            row[FederationRelationshipTable.remoteInboxUri] shouldBe "https://remote.example/inbox-v2"

            // Exactly one row for this remoteActorUri -- the unique constraint was never violated.
            transaction {
                FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }.count()
            } shouldBe 1L
        }

        test("upsertByRemoteActorUri: a TERMINAL (UNDONE) row is likewise UPDATED back to PENDING") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val originalId =
                transaction {
                    val inserted =
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!
                    FederationRelationshipStore.updateStatus(inserted, FederationRelationshipStatus.UNDONE, T0)
                    track(inserted)
                }

            val reestablishedId =
                transaction {
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        FederationRelationshipDirection.OUTBOUND,
                        remoteActorUri,
                        "https://remote.example/inbox",
                        "PEM",
                        "activity-3",
                        T1,
                    )
                }
            reestablishedId shouldBe originalId
            transaction { FederationRelationshipStore.findById(originalId)!![FederationRelationshipTable.status] } shouldBe
                FederationRelationshipStatus.PENDING
        }

        test("recordEvent writes exactly one event row with the correct eventType per call") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    track(
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!,
                    )
                }
            transaction {
                FederationRelationshipStore.recordEvent(id, FederationEventType.FOLLOW_SENT, "activity-1", "{}", T0)
                FederationRelationshipStore.updateStatus(id, FederationRelationshipStatus.ACTIVE, T1)
                FederationRelationshipStore.recordEvent(id, FederationEventType.ACCEPT_RECEIVED, "activity-2", "{}", T1)
            }

            val events = transaction { FederationRelationshipStore.listEvents(id) }
            events.map { it[FederationRelationshipEventTable.eventType] } shouldBe
                listOf(FederationEventType.ACCEPT_RECEIVED, FederationEventType.FOLLOW_SENT) // newest first
        }

        // ── Concurrency (round-1 review fix) ────────────────────────────────

        test("updateStatusIfCurrently: succeeds and mutates when the row's status still matches expectedStatus") {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    track(
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!,
                    )
                }
            val applied =
                transaction {
                    FederationRelationshipStore.updateStatusIfCurrently(
                        id,
                        FederationRelationshipStatus.PENDING,
                        FederationRelationshipStatus.ACTIVE,
                        T1,
                    )
                }
            applied shouldBe true
            transaction { FederationRelationshipStore.findById(id)!![FederationRelationshipTable.status] } shouldBe
                FederationRelationshipStatus.ACTIVE
        }

        test(
            "updateStatusIfCurrently: fails (no mutation) when the row's status no longer matches expectedStatus " +
                "-- the lost-update guard",
        ) {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    val inserted =
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!
                    // Already decided (REJECTED) by the time this CAS runs -- simulates a second,
                    // concurrent decision (or a duplicate/replayed delivery) landing after the first
                    // one already committed.
                    FederationRelationshipStore.updateStatus(inserted, FederationRelationshipStatus.REJECTED, T0)
                    track(inserted)
                }
            val applied =
                transaction {
                    FederationRelationshipStore.updateStatusIfCurrently(
                        id,
                        FederationRelationshipStatus.PENDING,
                        FederationRelationshipStatus.ACTIVE,
                        T1,
                    )
                }
            applied shouldBe false
            // Status is untouched -- still REJECTED, never silently overwritten to ACTIVE.
            transaction { FederationRelationshipStore.findById(id)!![FederationRelationshipTable.status] } shouldBe
                FederationRelationshipStatus.REJECTED
        }

        test(
            "upsertByRemoteActorUri: two concurrent first-Follow attempts for the same never-before-seen " +
                "remote actor never surface a raw ExposedSQLException/500 and converge to a single row",
        ) {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val results = runConcurrentUpsert(remoteActorUri)

            // Neither thread may propagate the raw unique-constraint violation the unguarded race
            // would have produced -- see FederationRelationshipStore KDoc "Concurrency" point 3.
            results.failures.isEmpty() shouldBe true
            results.ids.forEach { track(it) }

            val rowCount =
                transaction {
                    FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }.count()
                }
            rowCount shouldBe 1L
            // Both threads' upsertByRemoteActorUri calls resolve to the SAME relationship id --
            // one inserted it, the other deferred to it via the ExposedSQLException backstop.
            results.ids.toSet().size shouldBe 1
        }

        test(
            "two concurrent updateStatusIfCurrently CAS calls racing PENDING -> ACTIVE / PENDING -> REJECTED on the " +
                "SAME row: exactly one wins, the row never ends up corrupted or double-transitioned",
        ) {
            val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
            val id =
                transaction {
                    track(
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.INBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-1",
                            T0,
                        )!!,
                    )
                }

            val results = runConcurrentCas(id)

            results.failures.isEmpty() shouldBe true
            // Exactly ONE of the two racing decisions may win the compare-and-swap -- the other must
            // observe `false` (no mutation), never both `true` (which would mean both an Accept AND
            // a Reject silently landed on the same relationship).
            results.applied.count { it } shouldBe 1

            val finalStatus = transaction { FederationRelationshipStore.findById(id)!![FederationRelationshipTable.status] }
            (finalStatus == FederationRelationshipStatus.ACTIVE || finalStatus == FederationRelationshipStatus.REJECTED) shouldBe true
        }
    })

/** Result of [runConcurrentUpsert]. */
private data class ConcurrentUpsertResult(
    val ids: List<Uuid>,
    val failures: List<Throwable>,
)

/**
 * Fires two concurrent [FederationRelationshipStore.upsertByRemoteActorUri] calls for the SAME
 * [remoteActorUri] (no pre-existing row) from two independent OS threads, synchronized via a
 * [java.util.concurrent.CountDownLatch] so both are issued as close to simultaneously as possible
 * -- exercises the "first-Follow race" documented on [FederationRelationshipStore]'s own KDoc.
 * Mirrors [network.lapis.cloud.server.rpc.PoliticianServiceTest]'s own `runConcurrentFirstGrant`
 * two-OS-thread shape, at the store level (no HTTP/RPC layer needed to exercise this).
 */
private fun runConcurrentUpsert(
    remoteActorUri: String,
    timeoutSeconds: Long = 20,
): ConcurrentUpsertResult {
    val startLatch = java.util.concurrent.CountDownLatch(2)
    val doneLatch = java.util.concurrent.CountDownLatch(2)
    val ids = java.util.Collections.synchronizedList(mutableListOf<Uuid>())
    val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    fun upsertThread() =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                val id =
                    transaction {
                        FederationRelationshipStore.upsertByRemoteActorUri(
                            FederationRelationshipDirection.OUTBOUND,
                            remoteActorUri,
                            "https://remote.example/inbox",
                            "PEM",
                            "activity-${Uuid.random()}",
                            T0,
                        )
                    }
                if (id != null) ids += id
            } catch (t: Throwable) {
                failures += t
            } finally {
                doneLatch.countDown()
            }
        }

    val threadA = upsertThread()
    val threadB = upsertThread()
    threadA.start()
    threadB.start()
    val completed = doneLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
    check(completed) { "Concurrent upsert did not complete within ${timeoutSeconds}s -- likely deadlock" }
    return ConcurrentUpsertResult(ids.toList(), failures.toList())
}

/** Result of [runConcurrentCas]. */
private data class ConcurrentCasResult(
    val applied: List<Boolean>,
    val failures: List<Throwable>,
)

/**
 * Fires an Accept-shaped (`PENDING` -> `ACTIVE`) and a Reject-shaped (`PENDING` -> `REJECTED`)
 * [FederationRelationshipStore.updateStatusIfCurrently] CAS call against the SAME relationship
 * [id] from two independent OS threads, synchronized via a
 * [java.util.concurrent.CountDownLatch] -- exercises the exact "two servers/two rapid requests
 * deciding the same relationship concurrently" scenario the CAS guard exists for.
 */
private fun runConcurrentCas(
    id: Uuid,
    timeoutSeconds: Long = 20,
): ConcurrentCasResult {
    val startLatch = java.util.concurrent.CountDownLatch(2)
    val doneLatch = java.util.concurrent.CountDownLatch(2)
    val applied = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    fun casThread(newStatus: FederationRelationshipStatus) =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                val result =
                    transaction {
                        FederationRelationshipStore.updateStatusIfCurrently(id, FederationRelationshipStatus.PENDING, newStatus, T1)
                    }
                applied += result
            } catch (t: Throwable) {
                failures += t
            } finally {
                doneLatch.countDown()
            }
        }

    val threadA = casThread(FederationRelationshipStatus.ACTIVE)
    val threadB = casThread(FederationRelationshipStatus.REJECTED)
    threadA.start()
    threadB.start()
    val completed = doneLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
    check(completed) { "Concurrent CAS did not complete within ${timeoutSeconds}s -- likely deadlock" }
    return ConcurrentCasResult(applied.toList(), failures.toList())
}
