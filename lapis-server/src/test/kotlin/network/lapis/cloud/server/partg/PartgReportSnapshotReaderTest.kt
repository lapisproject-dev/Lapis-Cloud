package network.lapis.cloud.server.partg

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.269 -- the revision fence of the consistent read: a write that lands between the reads of one attempt is detected (the revisions before and
 * after differ), the read is repeated and returns the new, consistent state; a write in EVERY attempt ends in a revision conflict after three tries.
 */
class PartgReportSnapshotReaderTest :
    FunSpec({
        val db = TestDatabase.H2
        val fx = PartgFixtures()

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            PartgReportSnapshotReader.betweenReadsHook = {}
            fx.cleanUp()
            db.deactivate()
        }
        afterTest { PartgReportSnapshotReader.betweenReadsHook = {} }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }
        val unit = PartgUnit.Federal

        fun writeFigure(
            memberCount: Int,
            memberId: kotlin.uuid.Uuid,
        ) {
            val revision = transaction { PartgAnnexStore.findAnnex(year = year, unit = unit)?.revision ?: 0L }
            PartgAnnexWriter.saveFigures(
                current = fx.currentOf(memberId = memberId),
                year = year,
                unit = unit,
                expectedRevision = revision,
                deductions = emptyMap<PartgDeduction, Long?>(),
                memberCount = memberCount,
                now = ServerClock.now(),
            ) {}
        }

        test("a quiet read needs one attempt and returns what is stored") {
            val member = fx.member()
            writeFigure(memberCount = 11, memberId = member)
            var attempts = 0
            PartgReportSnapshotReader.betweenReadsHook = { attempts++ }
            val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
            attempts shouldBe 1
            snapshot.annex[year]?.get(PartgUnit.FEDERAL_KEY)?.memberCount shouldBe 11
        }

        test("a write between the reads makes the read repeat and return the new state") {
            val member = fx.member()
            var attempts = 0
            PartgReportSnapshotReader.betweenReadsHook = { attempt ->
                attempts++
                if (attempt == 0) {
                    // the concurrent writer: its own transaction on another thread, so it really is a separate commit
                    val worker = Thread { writeFigure(memberCount = 22, memberId = member) }
                    worker.start()
                    worker.join()
                }
            }
            val snapshot = PartgReportSnapshotReader.read(listOf(year))
            attempts shouldBe 2
            snapshot.annex[year]?.get(PartgUnit.FEDERAL_KEY)?.memberCount shouldBe 22
        }

        test("V1.9.283: a sponsor saved between the reads makes the read repeat; sponsors and youth organisations are in the annex data") {
            val member = fx.member()

            fun writeSponsor(
                name: String,
                revisionOffset: Long = 0L,
            ) {
                val revision = transaction { PartgAnnexStore.findAnnex(year = year, unit = unit)?.revision ?: 0L } + revisionOffset
                PartgAnnexWriter.saveSponsor(
                    current = fx.currentOf(memberId = member),
                    year = year,
                    unit = unit,
                    expectedRevision = revision,
                    sponsor =
                        network.lapis.cloud.shared.domain.PartgSponsorInputDto(
                            id = null,
                            personKind = network.lapis.cloud.shared.domain.PartgPersonKind.LEGAL,
                            name = name,
                            street = null,
                            postalCode = null,
                            city = null,
                            country = "DE",
                            entries =
                                listOf(
                                    network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto(
                                        id = null,
                                        date = kotlinx.datetime.LocalDate(year, 2, 2),
                                        amountCents = 90_000L,
                                        purpose = null,
                                    ),
                                ),
                        ),
                    now = ServerClock.now(),
                ) {}
            }
            writeSponsor("Erster")
            var attempts = 0
            PartgReportSnapshotReader.betweenReadsHook = { attempt ->
                attempts++
                if (attempt == 0) {
                    val worker = Thread { writeSponsor("Zweiter") }
                    worker.start()
                    worker.join()
                }
            }
            val snapshot = PartgReportSnapshotReader.read(listOf(year))
            attempts shouldBe 2
            snapshot.annex[year]
                ?.get(PartgUnit.FEDERAL_KEY)
                ?.sponsors
                ?.map { it.name }
                ?.toSet() shouldBe setOf("Erster", "Zweiter")
            snapshot.annex[year]?.get(PartgUnit.FEDERAL_KEY)?.youthOrgs shouldBe emptyList()
        }

        test("a write in every attempt ends in a revision conflict after exactly three attempts") {
            val member = fx.member()
            var attempts = 0
            var next = 100
            PartgReportSnapshotReader.betweenReadsHook = {
                attempts++
                val value = next++
                val worker = Thread { writeFigure(memberCount = value, memberId = member) }
                worker.start()
                worker.join()
            }
            shouldThrow<PartyReportRevisionConflictException> { PartgReportSnapshotReader.read(listOf(year)) }
            attempts shouldBe PartgReportSnapshotReader.MAX_ATTEMPTS
        }
    })
