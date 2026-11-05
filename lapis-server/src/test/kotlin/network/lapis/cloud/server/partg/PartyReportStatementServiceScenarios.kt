package network.lapis.cloud.server.partg

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * V1.9.283 (R3b) -- statements C (sponsoring) and F (youth organisations) through the RPC service on H2 AND PostgreSQL: save/change/delete, the
 * revision fence, the caps (500 sponsors, 100 entries per sponsor, 2000 per unit, 20 youth organisations), foreign ids, the sorting, the
 * per-unit rule of the limits, and that neither the audit entries nor the log carry a name, a purpose or an amount.
 */
abstract class PartyReportStatementServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        fun freshUnit() = PartgUnit.State(fx.chapter().toString()).key()

        fun entry(
            cents: Long,
            id: String? = null,
            date: LocalDate = LocalDate(year, 3, 1),
            purpose: String? = null,
        ) = PartgSponsoringEntryInputDto(id = id, date = date, amountCents = cents, purpose = purpose)

        fun sponsor(
            name: String = "Sponsor GmbH",
            entries: List<PartgSponsoringEntryInputDto> = listOf(entry(80_000L)),
            id: String? = null,
        ) = PartgSponsorInputDto(
            id = id,
            personKind = PartgPersonKind.LEGAL,
            name = name,
            street = "Hauptstrasse 1",
            postalCode = "38100",
            city = "Braunschweig",
            country = "DE",
            entries = entries,
        )

        test("a sponsor: add, change (entry ids are kept), delete; the revision counts; the qualification is computed") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            sponsor = sponsor(entries = listOf(entry(80_000L), entry(1_000L))),
                        )
                    }
                dto.revision shouldBe 1
                val s = dto.sponsors.single()
                s.qualification shouldBe PartgSponsorQualification.SINGLE
                s.yearlySumCents shouldBe 81_000L
                s.entries.size shouldBe 2
                val keep = s.entries.first { it.amountCents == 80_000L }
                dto =
                    call(member = board) {
                        service(it).saveSponsor(
                            year = year,
                            unitKey = unit,
                            expectedRevision = dto.revision,
                            sponsor =
                                sponsor(
                                    id = s.id,
                                    entries = listOf(entry(id = keep.id, cents = 75_000L)) + List(8) { entry(75_000L) },
                                ),
                        )
                    }
                val changed = dto.sponsors.single()
                changed.entries.any { it.id == keep.id && it.amountCents == 75_000L } shouldBe true
                changed.entries.none { it.id == s.entries.first { e -> e.amountCents == 1_000L }.id } shouldBe true
                changed.qualification shouldBe PartgSponsorQualification.YEARLY
                dto =
                    call(
                        member = board,
                    ) { service(it).deleteSponsor(year = year, unitKey = unit, expectedRevision = dto.revision, sponsorId = changed.id) }
                dto.sponsors shouldBe emptyList()
                dto.revision shouldBe 3
            }
        }

        test("a stale revision is refused for saving and deleting a sponsor and a youth organisation") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                val created =
                    call(member = board) { service(it).saveSponsor(year = year, unitKey = unit, expectedRevision = 0, sponsor = sponsor()) }
                attempt(
                    member = board,
                ) { service(it).saveSponsor(year = year, unitKey = unit, expectedRevision = 0, sponsor = sponsor(name = "Stale")) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
                attempt(member = board) {
                    service(it).deleteSponsor(year = year, unitKey = unit, expectedRevision = 0, sponsorId = created.sponsors.single().id)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
                attempt(member = board) {
                    service(
                        it,
                    ).saveYouthOrg(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        youthOrg =
                            PartgYouthOrgInputDto(
                                id = null,
                                name = "Jugend",
                                relationship = null,
                                incomeCents = null,
                                expensesCents = null,
                            ),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.sponsors.map { it.name } shouldContainExactly
                    listOf("Sponsor GmbH")
            }
        }

        test("refused inputs carry a code and no input; a date outside the year, no entries, a duplicate entry id") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                suspend fun code(input: PartgSponsorInputDto): String? =
                    (
                        attempt(
                            member = board,
                        ) {
                            service(it).saveSponsor(year = year, unitKey = unit, expectedRevision = 0, sponsor = input)
                        }.exceptionOrNull() as? PartyReportInvalidInputException
                    )?.message
                code(sponsor(entries = emptyList())) shouldBe "ENTRIES_EMPTY"
                code(sponsor(entries = listOf(entry(1L, date = LocalDate(year - 1, 12, 31))))) shouldBe "DATE_OUTSIDE_YEAR"
                code(sponsor(entries = listOf(entry(1L, date = LocalDate(year + 1, 1, 1))))) shouldBe "DATE_OUTSIDE_YEAR"
                code(sponsor(entries = listOf(entry(0L)))) shouldBe "OUT_OF_RANGE"
                code(sponsor(entries = listOf(entry(1L, purpose = "x".repeat(501))))) shouldBe "FIELD_TOO_LONG"
                val dup = Uuid.random().toString()
                code(sponsor(entries = listOf(entry(1L, id = dup), entry(2L, id = dup)))) shouldBe "DUPLICATE_ID"
                // the first and the last day of the year are accepted
                val edge = freshUnit()
                call(member = board) {
                    service(it).saveSponsor(
                        year = year,
                        unitKey = edge,
                        expectedRevision = 0,
                        sponsor =
                            sponsor(
                                entries = listOf(entry(1L, date = LocalDate(year, 12, 31)), entry(1L, date = LocalDate(year, 1, 1))),
                            ),
                    )
                }.sponsors.single().entries.size shouldBe 2
            }
        }

        test("the same entry id in two spellings is a duplicate and changes nothing") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                val dto =
                    call(member = board) {
                        service(it).saveSponsor(year = year, unitKey = unit, expectedRevision = 0, sponsor = sponsor())
                    }
                val s = dto.sponsors.single()
                val entryId = s.entries.single().id
                val failure =
                    attempt(member = board) {
                        service(it).saveSponsor(
                            year = year,
                            unitKey = unit,
                            expectedRevision = dto.revision,
                            sponsor =
                                sponsor(
                                    id = s.id,
                                    entries = listOf(entry(1_000L, id = entryId.lowercase()), entry(2_000L, id = entryId.uppercase())),
                                ),
                        )
                    }.exceptionOrNull() as? PartyReportInvalidInputException
                failure?.message shouldBe "DUPLICATE_ID"
                val after = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                after.revision shouldBe dto.revision
                after.sponsors
                    .single()
                    .entries
                    .single()
                    .amountCents shouldBe 80_000L
            }
        }

        test("an entry id of another sponsor, a sponsor id of another year or unit: NotFound, nothing changes") {
            val board = fx.member()
            val unitA = freshUnit()
            val unitB = freshUnit()
            partgTestApplication(fixtures = fx) {
                val a =
                    call(
                        member = board,
                    ) { service(it).saveSponsor(year = year, unitKey = unitA, expectedRevision = 0, sponsor = sponsor(name = "In A")) }
                val b =
                    call(
                        member = board,
                    ) { service(it).saveSponsor(year = year, unitKey = unitB, expectedRevision = 0, sponsor = sponsor(name = "In B")) }
                val aSponsor = a.sponsors.single()
                val aEntry = aSponsor.entries.single()
                // sponsor of A addressed through unit B
                attempt(member = board) {
                    service(
                        it,
                    ).saveSponsor(year = year, unitKey = unitB, expectedRevision = b.revision, sponsor = sponsor(id = aSponsor.id))
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = board,
                ) { service(it).deleteSponsor(year = year, unitKey = unitB, expectedRevision = b.revision, sponsorId = aSponsor.id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = board,
                ) { service(it).deleteSponsor(year = year - 1, unitKey = unitA, expectedRevision = 0, sponsorId = aSponsor.id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                // entry of A inside a sponsor of B
                val bSponsor = b.sponsors.single()
                attempt(member = board) {
                    service(
                        it,
                    ).saveSponsor(
                        year = year,
                        unitKey = unitB,
                        expectedRevision = b.revision,
                        sponsor = sponsor(id = bSponsor.id, entries = listOf(entry(5L, id = aEntry.id))),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = board,
                ) { service(it).deleteSponsor(year = year, unitKey = unitA, expectedRevision = a.revision, sponsorId = "kein-uuid") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                call(member = board) { service(it).getAnnex(year = year, unitKey = unitA) }
                    .sponsors
                    .single()
                    .entries
                    .single()
                    .amountCents shouldBe 80_000L
                call(member = board) { service(it).getAnnex(year = year, unitKey = unitB) }
                    .sponsors
                    .single()
                    .entries
                    .single()
                    .id shouldBe
                    bSponsor.entries.single().id
            }
        }

        test("the caps: 500 sponsors, 100 entries per sponsor, 2000 entries per unit, 20 youth organisations") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var revision = 0L
                // 20 sponsors with 100 entries = 2000 entries, then one more entry is too many
                repeat(20) { i ->
                    revision =
                        call(member = board) {
                            service(
                                it,
                            ).saveSponsor(
                                year = year,
                                unitKey = unit,
                                expectedRevision = revision,
                                sponsor =
                                    sponsor(
                                        name = "S$i",
                                        entries =
                                            List(
                                                100,
                                            ) {
                                                entry(1L)
                                            },
                                    ),
                            )
                        }.revision
                }
                attempt(
                    member = board,
                ) {
                    service(
                        it,
                    ).saveSponsor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = revision,
                        sponsor = sponsor(name = "Zu viel", entries = listOf(entry(1L))),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                    .message shouldBe "TOO_MANY"
                attempt(member = board) {
                    service(
                        it,
                    ).saveSponsor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = revision,
                        sponsor = sponsor(entries = List(101) { entry(1L) }),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                    .message shouldBe "TOO_MANY"
                // changing a sponsor to fewer entries is fine and frees room
                val first = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.sponsors.first()
                revision =
                    call(member = board) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unit,
                            expectedRevision = revision,
                            sponsor = sponsor(id = first.id, name = first.name, entries = listOf(entry(1L))),
                        )
                    }.revision
                call(member = board) {
                    service(
                        it,
                    ).saveSponsor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = revision,
                        sponsor = sponsor(name = "Passt", entries = listOf(entry(1L))),
                    )
                }
                val youthUnit = freshUnit()
                var yrev = 0L
                repeat(20) { i ->
                    yrev =
                        call(
                            member = board,
                        ) {
                            service(
                                it,
                            ).saveYouthOrg(
                                year = year,
                                unitKey = youthUnit,
                                expectedRevision = yrev,
                                youthOrg =
                                    PartgYouthOrgInputDto(
                                        id = null,
                                        name = "J$i",
                                        relationship = null,
                                        incomeCents = null,
                                        expensesCents = null,
                                    ),
                            )
                        }.revision
                }
                attempt(
                    member = board,
                ) {
                    service(
                        it,
                    ).saveYouthOrg(
                        year = year,
                        unitKey = youthUnit,
                        expectedRevision = yrev,
                        youthOrg =
                            PartgYouthOrgInputDto(
                                id = null,
                                name = "J21",
                                relationship = null,
                                incomeCents = null,
                                expensesCents = null,
                            ),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                    .message shouldBe "TOO_MANY"
            }
        }

        test("sorting: yearly sum descending, then name, then id; entries by date; limits are per unit, nothing is added up across units") {
            val board = fx.member()
            val unitA = freshUnit()
            val unitB = freshUnit()
            partgTestApplication(fixtures = fx) {
                var dto =
                    call(member = board) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unitA,
                            expectedRevision = 0,
                            sponsor = sponsor(name = "Berta", entries = listOf(entry(10L))),
                        )
                    }
                dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unitA,
                            expectedRevision = dto.revision,
                            sponsor = sponsor(name = "Anton", entries = listOf(entry(10L))),
                        )
                    }
                dto =
                    call(member = board) {
                        service(it).saveSponsor(
                            year = year,
                            unitKey = unitA,
                            expectedRevision = dto.revision,
                            sponsor =
                                sponsor(
                                    name = "Zora",
                                    entries = listOf(entry(5L, date = LocalDate(year, 5, 1)), entry(500L, date = LocalDate(year, 2, 1))),
                                ),
                        )
                    }
                dto.sponsors.map { it.name } shouldContainExactly listOf("Zora", "Anton", "Berta")
                dto.sponsors.map { it.rowNumber } shouldContainExactly listOf(1, 2, 3)
                dto.sponsors
                    .first()
                    .entries
                    .map { it.date.month.ordinal + 1 } shouldContainExactly listOf(2, 5)
                // the same name with 599,999 cents in each of two units: both stay under the yearly limit
                // (eight services of 749.99 EUR = 5,999.92 EUR in each unit; 11,999.84 EUR together would be above the yearly limit)
                val small = List(8) { entry(74_999L) }
                val a =
                    call(member = board) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unitA,
                            expectedRevision = dto.revision,
                            sponsor = sponsor(name = "Doppelt", entries = small),
                        )
                    }
                val b =
                    call(member = board) {
                        service(
                            it,
                        ).saveSponsor(
                            year = year,
                            unitKey = unitB,
                            expectedRevision = 0,
                            sponsor = sponsor(name = "Doppelt", entries = small),
                        )
                    }
                a.sponsors.single { it.name == "Doppelt" }.qualification shouldBe PartgSponsorQualification.NONE
                b.sponsors.single().qualification shouldBe PartgSponsorQualification.NONE
            }
        }

        test("youth organisations: not entered stays null, zero is a value, sorted by name") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveYouthOrg(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            youthOrg =
                                PartgYouthOrgInputDto(
                                    id = null,
                                    name = "Zeta",
                                    relationship = "Jugendverband",
                                    incomeCents = null,
                                    expensesCents = null,
                                ),
                        )
                    }
                dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveYouthOrg(
                            year = year,
                            unitKey = unit,
                            expectedRevision = dto.revision,
                            youthOrg =
                                PartgYouthOrgInputDto(
                                    id = null,
                                    name = "Alpha",
                                    relationship = null,
                                    incomeCents = 0L,
                                    expensesCents = 12_300L,
                                ),
                        )
                    }
                dto.youthOrgs.map { it.name } shouldContainExactly listOf("Alpha", "Zeta")
                dto.youthOrgs.first().incomeCents shouldBe 0L
                dto.youthOrgs.last().incomeCents shouldBe null
                val alpha = dto.youthOrgs.first()
                dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveYouthOrg(
                            year = year,
                            unitKey = unit,
                            expectedRevision = dto.revision,
                            youthOrg =
                                PartgYouthOrgInputDto(
                                    id = alpha.id,
                                    name = "Alpha",
                                    relationship = "neu",
                                    incomeCents = 5L,
                                    expensesCents = null,
                                ),
                        )
                    }
                dto.youthOrgs.first().relationship shouldBe "neu"
                dto.youthOrgs.first().expensesCents shouldBe null
                attempt(
                    member = board,
                ) {
                    service(
                        it,
                    ).saveYouthOrg(
                        year = year,
                        unitKey = unit,
                        expectedRevision = dto.revision,
                        youthOrg =
                            PartgYouthOrgInputDto(
                                id = null,
                                name = "Neg",
                                relationship = null,
                                incomeCents = -1L,
                                expensesCents = null,
                            ),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                dto =
                    call(
                        member = board,
                    ) { service(it).deleteYouthOrg(year = year, unitKey = unit, expectedRevision = dto.revision, youthOrgId = alpha.id) }
                dto.youthOrgs.map { it.name } shouldContainExactly listOf("Zeta")
                attempt(member = board) {
                    service(
                        it,
                    ).deleteYouthOrg(year = year, unitKey = unit, expectedRevision = dto.revision, youthOrgId = Uuid.random().toString())
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("the audit entries and the log carry no name, address, purpose or amount") {
            val board = fx.member()
            val unit = freshUnit()
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            try {
                partgTestApplication(fixtures = fx) {
                    var dto =
                        call(member = board) {
                            service(it).saveSponsor(
                                year = year,
                                unitKey = unit,
                                expectedRevision = 0,
                                sponsor = sponsor(name = "Geheimsponsor", entries = listOf(entry(7_654_321L, purpose = "Geheimzweck"))),
                            )
                        }
                    dto =
                        call(member = board) {
                            service(
                                it,
                            ).saveYouthOrg(
                                year = year,
                                unitKey = unit,
                                expectedRevision = dto.revision,
                                youthOrg =
                                    PartgYouthOrgInputDto(
                                        id = null,
                                        name = "Geheimjugend",
                                        relationship = "Geheimrolle",
                                        incomeCents = 1_234_567L,
                                        expensesCents = 7_654_322L,
                                    ),
                            )
                        }
                    dto =
                        call(member = board) {
                            service(
                                it,
                            ).deleteSponsor(
                                year = year,
                                unitKey = unit,
                                expectedRevision = dto.revision,
                                sponsorId = dto.sponsors.single().id,
                            )
                        }
                    val entries =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where { AuditLogEntryTable.entityType inList listOf(AuditEntityType.PARTG_REPORT_ANNEX) }
                                .filter { it[AuditLogEntryTable.actorMemberId] == board }
                                .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                        }
                    entries.size shouldBe 3
                    val joined = entries.joinToString("|")
                    listOf("Geheim", "7654321", "1234567", "Braunschweig", "Hauptstrasse").forEach { joined shouldNotContain it }
                    joined.contains("SPONSOR_ADDED") shouldBe true
                    joined.contains("YOUTH_ORG_ADDED") shouldBe true
                    joined.contains("SPONSOR_DELETED") shouldBe true
                    appender.list.joinToString("|") { it.formattedMessage }.let { log ->
                        listOf("Geheim", "7654321").forEach { log shouldNotContain it }
                    }
                    dto.sponsors shouldBe emptyList()
                }
            } finally {
                root.detachAppender(appender)
            }
        }

        if (db.isPostgres) {
            test("two concurrent sponsor saves with the same revision: exactly one wins") {
                val board = fx.member()
                val unit = freshUnit()
                partgTestApplication(fixtures = fx) {
                    val results =
                        kotlinx.coroutines.coroutineScope {
                            (1..2)
                                .map { n ->
                                    async {
                                        attempt(
                                            member = board,
                                        ) {
                                            service(
                                                it,
                                            ).saveSponsor(
                                                year = year,
                                                unitKey = unit,
                                                expectedRevision = 0,
                                                sponsor = sponsor(name = "Parallel $n"),
                                            )
                                        }
                                    }
                                }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe 1
                    results.count { it.exceptionOrNull() is PartyReportRevisionConflictException } shouldBe 1
                }
            }
        }
    })
