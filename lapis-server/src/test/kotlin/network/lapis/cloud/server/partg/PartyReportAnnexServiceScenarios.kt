package network.lapis.cloud.server.partg

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTriState
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * V1.9.269 (R3) -- the separate statements A, B, E and the notes through the RPC service, on H2 AND PostgreSQL: save and read, optimistic locking,
 * the limits (500 rows, 4000 characters, 50 holdings), cross-unit isolation of the donor ids, "not entered" versus 0, and that neither the audit
 * entries nor the log carry a name, an address, an amount or a note text.
 */
abstract class PartyReportAnnexServiceScenarios(
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
        val federalReal = PartgUnit.Federal.key()

        fun freshUnit() = PartgUnit.State(fx.chapter().toString()).key()

        fun donor(
            id: String? = null,
            name: String = "Erika Beispiel",
            cents: Long = 1_000_001L,
            street: String? = "Hauptstrasse 1",
            postalCode: String? = "38100",
            city: String? = "Braunschweig",
            kind: PartgPersonKind = PartgPersonKind.NATURAL,
        ) = PartgLargeDonorInputDto(
            id = id,
            personKind = kind,
            name = name,
            street = street,
            postalCode = postalCode,
            city = city,
            country = "DE",
            contributionKind = PartgContributionKind.DONATION,
            amountCents = cents,
        )

        fun note(
            block: PartgNoteBlock,
            included: Boolean = true,
            body: String? = "Text",
            abs1: PartgTriState? = null,
            abs3: PartgTriState? = null,
        ) = PartgNoteDto(block = block, included = included, body = body, reliefAbs1 = abs1, reliefAbs3 = abs3)

        test("figures: not entered stays null, zero is a value, the revision counts, a no-op keeps it") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                val empty = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                empty.revision shouldBe 0
                empty.deductions.values.all { it == null } shouldBe true
                empty.deductions.keys.toList() shouldContainExactly PartgDeduction.entries
                empty.memberCount shouldBe null
                empty.donors shouldBe emptyList()
                val saved =
                    call(member = board) {
                        service(it).saveAnnexFigures(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            deductions = mapOf(PartgDeduction.CASH_OVER_1000 to 0L, PartgDeduction.OVER_3300 to 250_00L),
                            memberCount = 0,
                        )
                    }
                saved.revision shouldBe 1
                saved.deductions[PartgDeduction.CASH_OVER_1000] shouldBe 0L
                saved.deductions[PartgDeduction.OVER_3300] shouldBe 250_00L
                saved.deductions[PartgDeduction.UNASSIGNABLE] shouldBe null
                saved.memberCount shouldBe 0
                val again =
                    call(member = board) {
                        service(it).saveAnnexFigures(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 1,
                            deductions = saved.deductions,
                            memberCount = 0,
                        )
                    }
                again.revision shouldBe 1
                val stale =
                    attempt(member = board) {
                        service(it).saveAnnexFigures(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            deductions = emptyMap(),
                            memberCount = 5,
                        )
                    }
                stale.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
            }
        }

        test("a save with nothing to store creates no header; out-of-range figures are refused with a code only") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                val dto =
                    call(member = board) {
                        service(
                            it,
                        ).saveAnnexFigures(year = year, unitKey = unit, expectedRevision = 0, deductions = emptyMap(), memberCount = null)
                    }
                dto.revision shouldBe 0
                transaction { PartgAnnexStore.findAnnex(year = year, unit = PartgUnit.parse(unit)!!) } shouldBe null
                val bad =
                    attempt(member = board) {
                        service(it).saveAnnexFigures(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            deductions = mapOf(PartgDeduction.UNASSIGNABLE to -1L),
                            memberCount = null,
                        )
                    }
                bad.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "OUT_OF_RANGE"
                attempt(member = board) {
                    service(
                        it,
                    ).saveAnnexFigures(year = year, unitKey = unit, expectedRevision = 0, deductions = emptyMap(), memberCount = 10_000_001)
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
            }
        }

        test("a stale revision is refused for saving and deleting a donor and for a first figures save on an existing header") {
            val board = fx.member()
            val federal = freshUnit()
            partgTestApplication(fixtures = fx) {
                val created =
                    call(member = board) {
                        service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = 0, donor = donor(name = "Anna"))
                    }
                created.revision shouldBe 1
                attempt(member = board) {
                    service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = 0, donor = donor(name = "Stale"))
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                attempt(member = board) {
                    service(it).deleteLargeDonor(year = year, unitKey = federal, expectedRevision = 0, donorId = created.donors.single().id)
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                // the header exists (revision 1): a figures save that still believes in "no header" must not overwrite
                attempt(member = board) {
                    service(it).saveAnnexFigures(
                        year = year,
                        unitKey = federal,
                        expectedRevision = 0,
                        deductions = mapOf(PartgDeduction.OVER_3300 to 100L),
                        memberCount = 3,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                val after = call(member = board) { service(it).getAnnex(year = year, unitKey = federal) }
                after.revision shouldBe 1
                after.donors.map { it.name } shouldContainExactly listOf("Anna")
                after.memberCount shouldBe null
            }
        }

        test("donors: add, change, delete; sorted by amount descending then name; rows are numbered") {
            val board = fx.member()
            val federal = freshUnit()
            partgTestApplication(fixtures = fx) {
                var dto =
                    call(member = board) {
                        service(
                            it,
                        ).saveLargeDonor(
                            year = year,
                            unitKey = federal,
                            expectedRevision = 0,
                            donor = donor(name = "Berta", cents = 2_000_000L),
                        )
                    }
                dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveLargeDonor(
                            year = year,
                            unitKey = federal,
                            expectedRevision = dto.revision,
                            donor = donor(name = "Anton", cents = 2_000_000L),
                        )
                    }
                dto =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveLargeDonor(
                            year = year,
                            unitKey = federal,
                            expectedRevision = dto.revision,
                            donor = donor(name = "Zora", cents = 5_000_000L),
                        )
                    }
                dto.donors.map { it.name } shouldContainExactly listOf("Zora", "Anton", "Berta")
                dto.donors.map { it.rowNumber } shouldContainExactly listOf(1, 2, 3)
                dto.revision shouldBe 3
                val berta = dto.donors.single { it.name == "Berta" }
                dto =
                    call(member = board) {
                        service(
                            it,
                        ).saveLargeDonor(
                            year = year,
                            unitKey = federal,
                            expectedRevision = dto.revision,
                            donor = donor(id = berta.id, name = "Berta", cents = 9_000_000L, city = null),
                        )
                    }
                dto.donors.map { it.name } shouldContainExactly listOf("Berta", "Zora", "Anton")
                dto.donors.first().city shouldBe null
                dto =
                    call(member = board) {
                        service(
                            it,
                        ).deleteLargeDonor(year = year, unitKey = federal, expectedRevision = dto.revision, donorId = dto.donors.first().id)
                    }
                dto.donors.map { it.name } shouldContainExactly listOf("Zora", "Anton")
            }
        }

        test("the threshold is strict: 10,000.00 EUR is refused (code only, no input), 10,000.01 EUR is stored") {
            val board = fx.member()
            val federal = freshUnit()
            partgTestApplication(fixtures = fx) {
                val refused =
                    attempt(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveLargeDonor(
                            year = year,
                            unitKey = federal,
                            expectedRevision = 0,
                            donor = donor(name = "Grenzfall Geheim", cents = 1_000_000L),
                        )
                    }
                val ex = refused.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                ex.message shouldBe "AMOUNT_NOT_ABOVE_THRESHOLD"
                (ex.message ?: "") shouldNotContain "Geheim"
                call(member = board) {
                    service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = 0, donor = donor(cents = 1_000_001L))
                }.donors.size shouldBe
                    1
            }
        }

        test("the 500th row is accepted, the 501st is refused") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                var revision = 0L
                repeat(500) { i ->
                    revision =
                        call(member = board) {
                            service(
                                it,
                            ).saveLargeDonor(year = year, unitKey = unit, expectedRevision = revision, donor = donor(name = "Person $i"))
                        }.revision
                }
                revision shouldBe 500
                val over =
                    attempt(member = board) {
                        service(
                            it,
                        ).saveLargeDonor(year = year, unitKey = unit, expectedRevision = revision, donor = donor(name = "Person 501"))
                    }
                over.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "TOO_MANY"
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.donors.size shouldBe 500
            }
        }

        test("a donor id of another year or another unit is invisible: NotFound, nothing changes") {
            val board = fx.member()
            val federal = freshUnit()
            val other = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                val mine =
                    call(
                        member = board,
                    ) { service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = 0, donor = donor(name = "Nur hier")) }
                val id = mine.donors.single().id
                val otherUnit =
                    call(
                        member = board,
                    ) { service(it).saveLargeDonor(year = year, unitKey = other, expectedRevision = 0, donor = donor(name = "Anderswo")) }
                attempt(member = board) {
                    service(
                        it,
                    ).saveLargeDonor(
                        year = year,
                        unitKey = other,
                        expectedRevision = otherUnit.revision,
                        donor = donor(id = id, name = "Uebernommen"),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = board,
                ) { service(it).deleteLargeDonor(year = year, unitKey = other, expectedRevision = otherUnit.revision, donorId = id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = board,
                ) { service(it).deleteLargeDonor(year = year - 1, unitKey = federal, expectedRevision = 0, donorId = id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) {
                    service(it).deleteLargeDonor(year = year, unitKey = federal, expectedRevision = mine.revision, donorId = "kein-uuid")
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                call(member = board) { service(it).getAnnex(year = year, unitKey = federal) }.donors.single().name shouldBe "Nur hier"
            }
        }

        test("the audit entries and the log carry no name, address, amount or text") {
            val board = fx.member()
            val federal = freshUnit()
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            try {
                partgTestApplication(fixtures = fx) {
                    var dto =
                        call(member = board) {
                            service(
                                it,
                            ).saveLargeDonor(
                                year = year,
                                unitKey = federal,
                                expectedRevision = 0,
                                donor =
                                    donor(
                                        name = "Geheimname Mustermann",
                                        street = "Geheimweg 7",
                                        city = "Geheimstadt",
                                        cents = 7_654_321L,
                                    ),
                            )
                        }
                    dto =
                        call(member = board) {
                            service(it).saveAnnexFigures(
                                year = year,
                                unitKey = federal,
                                expectedRevision = dto.revision,
                                deductions =
                                    mapOf(
                                        PartgDeduction.UNASSIGNABLE to 1_234_567L,
                                    ),
                                memberCount = 77,
                            )
                        }
                    call(member = board) {
                        service(it).saveNotes(
                            year = year,
                            expectedRevision = 0,
                            notes = listOf(note(PartgNoteBlock.GENERAL_ACCOUNTING, body = "Geheimer Erlaeuterungstext")),
                            holdings =
                                listOf(
                                    PartgHoldingDto(
                                        name = "Geheim GmbH",
                                        seat = "Geheimort",
                                        shareBasisPoints = 5000,
                                        capitalCents = 1_111_111L,
                                        resultCents = -2_222_222L,
                                    ),
                                ),
                        )
                    }
                    val entries =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where {
                                    AuditLogEntryTable.entityType inList
                                        listOf(AuditEntityType.PARTG_REPORT_ANNEX, AuditEntityType.PARTG_REPORT_NOTES)
                                }.filter { it[AuditLogEntryTable.actorMemberId] == board }
                                .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                        }
                    entries.size shouldBe 3
                    val joined = entries.joinToString("|")
                    listOf("Geheim", "7654321", "1234567", "1111111", "2222222", "Braunschweig").forEach { joined shouldNotContain it }
                    appender.list.joinToString("|") { it.formattedMessage }.let { log ->
                        listOf("Geheim", "7654321", "Mustermann").forEach { log shouldNotContain it }
                    }
                }
            } finally {
                root.detachAppender(appender)
            }
        }

        test("notes: five blocks always, the 4000th character is accepted, the 4001st refused, a placeholder is never stored") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                val fresh = call(member = board) { service(it).getNotes(year - 3) }
                fresh.notes.map { it.block } shouldContainExactly PartgNoteBlock.entries
                fresh.notes.none { it.included || it.body != null } shouldBe true
                val ok =
                    call(member = board) {
                        service(
                            it,
                        ).saveNotes(
                            year = year - 3,
                            expectedRevision = 0,
                            notes = listOf(note(PartgNoteBlock.MEDIA_PRODUCTS, body = "x".repeat(4000))),
                            holdings = emptyList(),
                        )
                    }
                ok.revision shouldBe 1
                ok.notes
                    .single { it.block == PartgNoteBlock.MEDIA_PRODUCTS }
                    .body
                    ?.length shouldBe 4000
                ok.notes.single { it.block == PartgNoteBlock.HOLDINGS }.body shouldBe null
                attempt(member = board) {
                    service(
                        it,
                    ).saveNotes(
                        year = year - 3,
                        expectedRevision = 1,
                        notes = listOf(note(PartgNoteBlock.MEDIA_PRODUCTS, body = "x".repeat(4001))),
                        holdings = emptyList(),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "FIELD_TOO_LONG"
                attempt(member = board) {
                    service(
                        it,
                    ).saveNotes(
                        year = year - 3,
                        expectedRevision = 0,
                        notes = listOf(note(PartgNoteBlock.MEDIA_PRODUCTS)),
                        holdings = emptyList(),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
            }
        }

        test("notes: relief fields only in RELIEF_28, 50 holdings accepted, 51 refused, a negative result is kept") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                val holdings =
                    (0 until 50).map {
                        PartgHoldingDto(
                            name = "Firma $it",
                            seat = null,
                            shareBasisPoints = 10_000,
                            capitalCents = 0L,
                            resultCents =
                                if (it ==
                                    0
                                ) {
                                    -500_00L
                                } else {
                                    1L
                                },
                        )
                    }
                val saved =
                    call(member = board) {
                        service(it).saveNotes(
                            year = year - 4,
                            expectedRevision = 0,
                            notes =
                                listOf(
                                    note(PartgNoteBlock.RELIEF_28, abs1 = PartgTriState.YES, abs3 = PartgTriState.NO),
                                    note(PartgNoteBlock.HOLDINGS),
                                ),
                            holdings = holdings,
                        )
                    }
                saved.holdings.size shouldBe 50
                saved.holdings.first().resultCents shouldBe -500_00L
                saved.notes.single { it.block == PartgNoteBlock.RELIEF_28 }.reliefAbs1 shouldBe PartgTriState.YES
                attempt(member = board) {
                    service(it).saveNotes(
                        year = year - 4,
                        expectedRevision = 1,
                        notes = emptyList(),
                        holdings =
                            holdings +
                                PartgHoldingDto(
                                    name = "Zu viel",
                                    seat = null,
                                    shareBasisPoints = null,
                                    capitalCents = null,
                                    resultCents = null,
                                ),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "TOO_MANY"
                attempt(member = board) {
                    service(
                        it,
                    ).saveNotes(
                        year = year - 4,
                        expectedRevision = 1,
                        notes = listOf(note(PartgNoteBlock.HOLDINGS, abs1 = PartgTriState.YES)),
                        holdings = emptyList(),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
            }
        }

        test("the member count suggestion exists only for the current year and a federal or state unit") {
            val board = fx.member()
            val state = PartgUnit.State(fx.chapter().toString()).key()
            val sub = PartgUnit.Subordinate(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                val current = PartgYearRules.maxYear()
                call(member = board) { service(it).getAnnex(year = current, unitKey = federalReal) }.memberCountSuggestion?.count?.let {
                    (it >= 0) shouldBe
                        true
                }
                (
                    call(
                        member = board,
                    ) { service(it).getAnnex(year = current, unitKey = federalReal) }.memberCountSuggestion != null
                ) shouldBe
                    true
                (call(member = board) { service(it).getAnnex(year = current, unitKey = state) }.memberCountSuggestion?.count) shouldBe 0L
                call(member = board) { service(it).getAnnex(year = current, unitKey = sub) }.memberCountSuggestion shouldBe null
                call(member = board) { service(it).getAnnex(year = current - 1, unitKey = federalReal) }.memberCountSuggestion shouldBe null
            }
        }

        test("an invalid year or unit key is refused before anything is read") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                attempt(
                    member = board,
                ) {
                    service(
                        it,
                    ).getAnnex(year = 1999, unitKey = federalReal)
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).getAnnex(year = PartgYearRules.maxYear() + 1, unitKey = federalReal) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).getAnnex(year = year, unitKey = "STATE:nope") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).getNotes(0) }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(
                    member = board,
                ) { service(it).reportFindings(3000) }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                Uuid.random() // keep the import used when the lanes are trimmed
            }
        }
    })
