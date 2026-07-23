package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditHashChain
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.domain.BoardMembershipInput
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartyDonationVerdictSnapshot
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Exercises the V0.5.3 GoBD audit-log wave end to end: [AuditLogRecorder]'s hash-chain/tamper-
 * evidence guarantees, [AuditLogService]'s read/pagination/authorization surface, and the actual
 * wiring into [AccountingService]/[GovernanceService]/[BoardMembershipService]. Same "throwaway
 * routes calling the service class directly" house style as [AccountingServiceTest]/
 * [BoardMembershipServiceTest].
 *
 * Tamper-detection/concurrency tests use [AuditLogRecorder.record] directly with
 * `actorMemberId = null` and a freshly [Uuid.random]'d `entityId` per row -- `audit_log_entry
 * .entity_id` deliberately carries no FK constraint (see `14-audit-log.kuml.kts` file header), so
 * these rows need no Accounting/Governance/BoardMembership fixture at all, and are isolated from
 * whatever the rest of this Spec (or a concurrently-run one) writes into the same, genuinely
 * global, single-chain table by scoping every `verifyChainIntegrity`/`listAuditLog` assertion to
 * the exact `sequenceNumber` range or `entityId`/`entityType` this test itself produced.
 */
class AuditLogServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            cleanUpAuditLogTestData(createdMemberIds, createdLedgerAccountIds, createdCommitteeIds)
        }

        fun createTestMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "AuditLog Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
            isCashRegister: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "AuditLog Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[LedgerAccountTable.isCashRegister] = isCashRegister
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun createCommittee(name: String): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "AuditLog Testcommittee"
                    it[active] = true
                    it[quorumPercent] = 0
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun createMeeting(committeeId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "AuditLog Testmeeting"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
                }
            }
            return id
        }

        fun setIsPoliticalParty(value: Boolean) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[isPoliticalParty] = value
                }
            }
        }

        /** Writes one throwaway, FK-free audit entry (see class KDoc) and returns its `sequenceNumber`. */
        fun writeTestEntry(
            entityId: Uuid = Uuid.random(),
            entityType: AuditEntityType = AuditEntityType.RESOLUTION,
            action: AuditAction = AuditAction.CREATE,
            after: String? = "test-after",
        ): Long =
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = entityType,
                    entityId = entityId,
                    action = action,
                    before = null,
                    after = after,
                )
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq entityId }
                    .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                    .limit(1)
                    .single()[AuditLogEntryTable.sequenceNumber]
            }

        // ── AuditLogRecorder: chain mechanics ────────────────────────────────────────

        test(
            "record() assigns gapless sequential sequenceNumbers, each row's stored entryHash matches a fresh AuditHashChain recomputation",
        ) {
            val entityIds = (1..5).map { Uuid.random() }
            val seqs = entityIds.map { eid -> writeTestEntry(entityId = eid) }
            (1 until seqs.size).forEach { i -> seqs[i] shouldBe seqs[i - 1] + 1 }

            val rows =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId inList entityIds }
                        .orderBy(AuditLogEntryTable.sequenceNumber)
                        .toList()
                }
            rows.forEach { row ->
                val recomputed =
                    AuditHashChain.computeHash(
                        AuditHashChain.ChainInput(
                            sequenceNumber = row[AuditLogEntryTable.sequenceNumber],
                            occurredAt = row[AuditLogEntryTable.occurredAt],
                            actorMemberId = row[AuditLogEntryTable.actorMemberId],
                            actorRole = row[AuditLogEntryTable.actorRole],
                            entityType = row[AuditLogEntryTable.entityType],
                            entityId = row[AuditLogEntryTable.entityId],
                            action = row[AuditLogEntryTable.action],
                            beforeSnapshot = row[AuditLogEntryTable.beforeSnapshot],
                            afterSnapshot = row[AuditLogEntryTable.afterSnapshot],
                            previousEntryHash = row[AuditLogEntryTable.previousEntryHash],
                        ),
                    )
                recomputed shouldBe row[AuditLogEntryTable.entryHash]
            }
        }

        test("record() outside of an open transaction throws") {
            val ex =
                runCatching {
                    AuditLogRecorder.record(
                        actorMemberId = null,
                        actorRole = null,
                        entityType = AuditEntityType.RESOLUTION,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                    )
                }.exceptionOrNull()
            (ex is IllegalStateException) shouldBe true
        }

        test("concurrent record() calls produce distinct, gapless sequenceNumbers with no duplicates") {
            val n = 12
            val entityIds = (1..n).map { Uuid.random() }
            coroutineScope {
                entityIds
                    .map { eid -> async { writeTestEntry(entityId = eid) } }
                    .map { it.await() }
            }
            val seqs =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId inList entityIds }
                        .map { it[AuditLogEntryTable.sequenceNumber] }
                        .sorted()
                }
            seqs.size shouldBe n
            seqs.toSet().size shouldBe n // no duplicates
            (1 until seqs.size).forEach { i -> seqs[i] shouldBe seqs[i - 1] + 1 } // gapless within this batch
        }

        test("verifyChainIntegrity over an exact range detects entryHash tampering (afterSnapshot mutated)") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val admin = createTestMember("audit-tamper-hash-admin@example.org", role = AccountRole.ADMIN)
                val seqs = (1..4).map { writeTestEntry(after = "payload-$it") }
                val tamperedSeq = seqs[1]

                val before =
                    client
                        .get("/test/verify?from=${seqs.first()}&to=${seqs.last()}") { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                before shouldBe "true:4:null"

                transaction {
                    AuditLogEntryTable.update({ AuditLogEntryTable.sequenceNumber eq tamperedSeq }) {
                        it[afterSnapshot] = "TAMPERED"
                    }
                }

                val after =
                    client
                        .get("/test/verify?from=${seqs.first()}&to=${seqs.last()}") { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                // index 0 (seqs[0]) passes untouched; index 1 (seqs[1], the tampered row) passes its
                // gap/previousEntryHash checks (only afterSnapshot was mutated, not previousEntryHash
                // itself) but fails the entryHash recomputation -- checkedCount=2 counts both rows
                // examined up to and including the failing one.
                after shouldBe "false:2:$tamperedSeq"
            }
        }

        test("verifyChainIntegrity over an exact range detects a deleted row (sequenceNumber gap)") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val admin = createTestMember("audit-tamper-gap-admin@example.org", role = AccountRole.ADMIN)
                val seqs = (1..4).map { writeTestEntry(after = "gap-payload-$it") }
                val deletedSeq = seqs[1]

                transaction {
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.sequenceNumber eq deletedSeq }
                }

                val result =
                    client
                        .get("/test/verify?from=${seqs.first()}&to=${seqs.last()}") { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                // 3 rows remain in range; the gap is detected at the row immediately after the hole.
                result shouldBe "false:1:${seqs[2]}"
            }
        }

        test(
            "verifyChainIntegrity on a windowed range (not starting at sequenceNumber 1) still verifies the link via the predecessor row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val admin = createTestMember("audit-window-admin@example.org", role = AccountRole.ADMIN)
                val seqs = (1..3).map { writeTestEntry(after = "window-payload-$it") }

                // Verify only the LAST two rows -- window does not start at sequenceNumber 1, so the
                // anchor-row mechanism (see AuditLogService.verifyRows KDoc) must fetch seqs[0] itself
                // to validate seqs[1]'s previousEntryHash link.
                val result =
                    client
                        .get("/test/verify?from=${seqs[1]}&to=${seqs[2]}") { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                result shouldBe "true:2:null"
            }
        }

        // ── AuditLogService: authorization ───────────────────────────────────────────

        test(
            "listAuditLog/getAuditLogEntry/verifyChainIntegrity require TREASURER/BOARD/ADMIN -- MEMBER Forbidden, unauthenticated Unauthorized",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val plainMember = createTestMember("audit-auth-member@example.org", role = AccountRole.MEMBER)

                client.get("/test/list") { header("X-Member-Id", plainMember.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/get/${Uuid.random()}") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/verify?from=1&to=1") { header("X-Member-Id", plainMember.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden

                client.get("/test/list").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getAuditLogEntry throws NotFound for an unknown id") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val treasurer = createTestMember("audit-notfound-treasurer@example.org", role = AccountRole.TREASURER)
                client
                    .get("/test/get/${Uuid.random()}") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── AuditLogService: filtering + pagination ──────────────────────────────────

        test("listAuditLog filters by entityType/entityId and is newest-first") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val treasurer = createTestMember("audit-filter-treasurer@example.org", role = AccountRole.TREASURER)
                val targetEntityId = Uuid.random()
                writeTestEntry(entityId = targetEntityId, entityType = AuditEntityType.BOARD_MEMBERSHIP, action = AuditAction.CREATE)
                writeTestEntry(entityId = targetEntityId, entityType = AuditEntityType.BOARD_MEMBERSHIP, action = AuditAction.UPDATE)
                writeTestEntry() // noise, different entityId/type

                val response =
                    client
                        .get("/test/list?entityType=BOARD_MEMBERSHIP&entityId=$targetEntityId") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val actions = response.split(";").filter { it.isNotBlank() }.map { it.substringAfterLast(":") }
                actions shouldBe listOf("UPDATE", "CREATE") // newest-first
            }
        }

        test("listAuditLog limit is capped server-side at MAX_PAGE_SIZE regardless of the requested limit") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val treasurer = createTestMember("audit-cap-treasurer@example.org", role = AccountRole.TREASURER)
                val sharedEntityId = Uuid.random()
                // 205 rows, all sharing one entityId/entityType so the entityId filter isolates
                // exactly this test's rows regardless of what else is in the (shared, global) table.
                repeat(205) { writeTestEntry(entityId = sharedEntityId, entityType = AuditEntityType.RESOLUTION) }

                val response =
                    client
                        .get("/test/list?entityType=RESOLUTION&entityId=$sharedEntityId&limit=100000") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val count = response.split(";").count { it.isNotBlank() }
                count shouldBe 200 // MAX_PAGE_SIZE, not the requested 100000 nor the true total of 205
            }
        }

        test("listAuditLog keyset pagination (beforeSequenceNumber) returns the next page without gap or overlap") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogTestRoutes() }
                }
                val treasurer = createTestMember("audit-keyset-treasurer@example.org", role = AccountRole.TREASURER)
                val sharedEntityId = Uuid.random()
                val seqs = (1..10).map { writeTestEntry(entityId = sharedEntityId, entityType = AuditEntityType.RESOLUTION) }

                val firstPage =
                    client
                        .get("/test/list?entityType=RESOLUTION&entityId=$sharedEntityId&limit=4") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.substringBefore(":").toLong() }
                firstPage shouldBe seqs.reversed().take(4)

                val secondPage =
                    client
                        .get("/test/list?entityType=RESOLUTION&entityId=$sharedEntityId&limit=4&beforeSequenceNumber=${firstPage.last()}") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.substringBefore(":").toLong() }
                secondPage shouldBe seqs.reversed().drop(4).take(4)
                (firstPage.toSet() intersect secondPage.toSet()) shouldBe emptySet() // no overlap
            }
        }

        // ── Real service wiring: AccountingService ───────────────────────────────────

        test("AccountingService.postJournalEntry writes exactly one JOURNAL_ENTRY CREATE audit entry with a matching snapshot") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogAccountingTestRoutes() }
                }
                val treasurer = createTestMember("audit-je-treasurer@example.org", role = AccountRole.TREASURER)
                val kasse = createLedgerAccount("AL-16000", LedgerAccountType.ASSET)
                val ertrag = createLedgerAccount("AL-40000", LedgerAccountType.INCOME)

                val response =
                    client
                        .post(
                            "/test/post-entry?desc=AuditLog-Testbuchung&voucher=BELEG-AL-1&kasse=$kasse&ertrag=$ertrag",
                        ) { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val journalEntryId = response.substringBefore(":")

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(journalEntryId))
                            }.toList()
                    }
                rows.size shouldBe 1
                transaction { rows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                transaction { rows.single()[AuditLogEntryTable.actorMemberId] } shouldBe treasurer
                transaction { rows.single()[AuditLogEntryTable.beforeSnapshot] } shouldBe null

                val snapshot =
                    transaction {
                        Json.decodeFromString(JournalEntrySnapshot.serializer(), rows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                snapshot.description shouldBe "AuditLog-Testbuchung"
                snapshot.status shouldBe JournalEntryStatus.POSTED
                snapshot.postings.size shouldBe 2
            }
        }

        test("AccountingService.saveDraftEntry + postDraftEntry write a CREATE then a POST audit entry for the same JournalEntry") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogAccountingTestRoutes() }
                }
                val treasurer = createTestMember("audit-draft-treasurer@example.org", role = AccountRole.TREASURER)
                val kasse = createLedgerAccount("AL-16001", LedgerAccountType.ASSET)
                val ertrag = createLedgerAccount("AL-40001", LedgerAccountType.INCOME)

                val draftResponse =
                    client
                        .post(
                            "/test/save-draft?desc=AuditLog-Entwurf&voucher=BELEG-AL-2&kasse=$kasse&ertrag=$ertrag",
                        ) { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val journalEntryId = draftResponse.substringBefore(":")

                client.post("/test/post-draft/$journalEntryId") { header("X-Member-Id", treasurer.toString()) }

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(journalEntryId))
                            }.orderBy(AuditLogEntryTable.sequenceNumber)
                            .toList()
                    }
                rows.size shouldBe 2
                transaction { rows[0][AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                transaction { rows[1][AuditLogEntryTable.action] } shouldBe AuditAction.POST

                val beforeSnapshot =
                    transaction {
                        Json.decodeFromString(JournalEntrySnapshot.serializer(), rows[1][AuditLogEntryTable.beforeSnapshot]!!)
                    }
                val afterSnapshot =
                    transaction {
                        Json.decodeFromString(JournalEntrySnapshot.serializer(), rows[1][AuditLogEntryTable.afterSnapshot]!!)
                    }
                beforeSnapshot.status shouldBe JournalEntryStatus.DRAFT
                afterSnapshot.status shouldBe JournalEntryStatus.POSTED
            }
        }

        test(
            "an ALLOWED party donation writes a PARTY_DONATION_VERDICT audit entry pointing at the JournalEntry; a PROHIBITED attempt writes NO JournalEntry audit row at all",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogAccountingTestRoutes() }
                }
                val treasurer = createTestMember("audit-party-treasurer@example.org", role = AccountRole.TREASURER)
                val kasse = createLedgerAccount("AL-16002", LedgerAccountType.ASSET)
                val ertrag = createLedgerAccount("AL-40002", LedgerAccountType.INCOME)
                setIsPoliticalParty(true)
                try {
                    // ALLOWED: a small natural-person donation.
                    val allowedResponse =
                        client
                            .post(
                                "/test/post-donation-entry?desc=AuditLog-Spende&voucher=BELEG-AL-3&kasse=$kasse&ertrag=$ertrag" +
                                    "&donorMemberId=$treasurer&donorCategory=GERMAN_NATURAL_PERSON&amount=50.00",
                            ) { header("X-Member-Id", treasurer.toString()) }
                            .bodyAsText()
                    val allowedJournalEntryId = allowedResponse.substringBefore(":")

                    val verdictRows =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where {
                                    (AuditLogEntryTable.entityType eq AuditEntityType.PARTY_DONATION_VERDICT) and
                                        (AuditLogEntryTable.entityId eq Uuid.parse(allowedJournalEntryId))
                                }.toList()
                        }
                    verdictRows.size shouldBe 1
                    val verdictSnapshot =
                        transaction {
                            Json.decodeFromString(
                                PartyDonationVerdictSnapshot.serializer(),
                                verdictRows.single()[AuditLogEntryTable.afterSnapshot]!!,
                            )
                        }
                    verdictSnapshot.verdict shouldBe "ALLOWED"
                    verdictSnapshot.donorCategory shouldBe DonorCategory.GERMAN_NATURAL_PERSON

                    // PROHIBITED: a PUBLIC_LAW_CORPORATION donor is always prohibited by category
                    // alone (amount-independent), see PartyDonationComplianceCalculator KDoc.
                    val beforeCount = transaction { AuditLogEntryTable.selectAll().count() }
                    val rejected =
                        client.post(
                            "/test/post-donation-entry?desc=AuditLog-Verbotene-Spende&voucher=BELEG-AL-4&kasse=$kasse&ertrag=$ertrag" +
                                "&donorMemberId=$treasurer&donorCategory=PUBLIC_LAW_CORPORATION&amount=50.00",
                        ) { header("X-Member-Id", treasurer.toString()) }
                    rejected.status shouldBe HttpStatusCode.Conflict
                    val afterCount = transaction { AuditLogEntryTable.selectAll().count() }
                    afterCount shouldBe beforeCount // the whole transaction rolled back, no audit row survived
                } finally {
                    setIsPoliticalParty(false)
                }
            }
        }

        // ── Real service wiring: GovernanceService ───────────────────────────────────

        test("GovernanceService.recordResolution writes exactly one RESOLUTION CREATE audit entry with a matching snapshot") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogGovernanceTestRoutes() }
                }
                val admin = createTestMember("audit-resolution-admin@example.org", role = AccountRole.ADMIN)
                val committeeId = createCommittee("AuditLog Governance Committee")
                val meetingId = createMeeting(committeeId)

                val response =
                    client
                        .post("/test/record-resolution/$meetingId?title=AuditLog-Beschluss&text=Text") {
                            header("X-Member-Id", admin.toString())
                        }.bodyAsText()
                val resolutionId = response.substringBefore(":")

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.RESOLUTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(resolutionId))
                            }.toList()
                    }
                rows.size shouldBe 1
                transaction { rows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val snapshot =
                    transaction {
                        Json.decodeFromString(ResolutionSnapshot.serializer(), rows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                snapshot.title shouldBe "AuditLog-Beschluss"
            }
        }

        // ── Real service wiring: BoardMembershipService ──────────────────────────────

        test("BoardMembershipService.appointBoardMember writes CREATE, endBoardMembership writes UPDATE (endedAt null -> set)") {
            testApplication {
                application {
                    install(StatusPages) { installAuditLogExceptionHandlers() }
                    routing { registerAuditLogBoardMembershipTestRoutes() }
                }
                val admin = createTestMember("audit-board-admin@example.org", role = AccountRole.ADMIN)
                val target = createTestMember("audit-board-target@example.org", role = AccountRole.MEMBER)

                val appointResponse =
                    client
                        .post("/test/appoint?memberId=$target&committeeRole=MEMBER&startedAt=2026-02-01") {
                            header("X-Member-Id", admin.toString())
                        }.bodyAsText()
                val boardMembershipId = appointResponse.substringBefore(":")

                val createRow =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(boardMembershipId))
                            }.single()
                    }
                transaction { createRow[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val createSnapshot =
                    transaction {
                        Json.decodeFromString(BoardMembershipSnapshot.serializer(), createRow[AuditLogEntryTable.afterSnapshot]!!)
                    }
                createSnapshot.endedAt shouldBe null

                client.post("/test/end/$boardMembershipId?endedAt=2026-03-01") { header("X-Member-Id", admin.toString()) }

                val updateRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(boardMembershipId)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.toList()
                    }
                updateRows.size shouldBe 1
                val beforeSnapshot =
                    transaction {
                        Json.decodeFromString(
                            BoardMembershipSnapshot.serializer(),
                            updateRows.single()[AuditLogEntryTable.beforeSnapshot]!!,
                        )
                    }
                val afterSnapshot =
                    transaction {
                        Json.decodeFromString(BoardMembershipSnapshot.serializer(), updateRows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                beforeSnapshot.endedAt shouldBe null
                afterSnapshot.endedAt shouldBe LocalDate(2026, 3, 1)
            }
        }
    })

private fun StatusPagesConfig.installAuditLogExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerAuditLogTestRoutes() {
    get("/test/list") {
        val service = AuditLogService(call)
        val q = call.request.queryParameters
        val list =
            service.listAuditLog(
                AuditLogListQuery(
                    entityType = q["entityType"]?.let { AuditEntityType.valueOf(it) },
                    entityId = q["entityId"],
                    actorMemberId = q["actorMemberId"],
                    limit = q["limit"]?.toInt() ?: 50,
                    beforeSequenceNumber = q["beforeSequenceNumber"]?.toLong(),
                ),
            )
        call.respondText(list.joinToString(";") { "${it.sequenceNumber}:${it.action}" })
    }
    get("/test/get/{id}") {
        val service = AuditLogService(call)
        val dto = service.getAuditLogEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.sequenceNumber}")
    }
    get("/test/verify") {
        val service = AuditLogService(call)
        val q = call.request.queryParameters
        val result = service.verifyChainIntegrity(q["from"]?.toLong(), q["to"]?.toLong())
        call.respondText("${result.valid}:${result.checkedCount}:${result.brokenAtSequenceNumber}")
    }
}

private fun Route.registerAuditLogAccountingTestRoutes() {
    post("/test/post-entry") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.postJournalEntry(
                JournalEntryInput(
                    entryDate = LocalDate(2026, 6, 1),
                    description = q["desc"]!!,
                    voucherReference = q["voucher"],
                    postings =
                        listOf(
                            PostingInput(
                                q["kasse"]!!,
                                PostingSide.DEBIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                q["ertrag"]!!,
                                PostingSide.CREDIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        ),
                ),
            )
        call.respondText("${dto.id}:${dto.status}")
    }
    post("/test/save-draft") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.saveDraftEntry(
                JournalEntryInput(
                    entryDate = LocalDate(2026, 6, 1),
                    description = q["desc"]!!,
                    voucherReference = q["voucher"],
                    postings =
                        listOf(
                            PostingInput(
                                q["kasse"]!!,
                                PostingSide.DEBIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                q["ertrag"]!!,
                                PostingSide.CREDIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        ),
                ),
            )
        call.respondText("${dto.id}:${dto.status}")
    }
    post("/test/post-draft/{id}") {
        val service = AccountingService(call)
        val dto = service.postDraftEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.status}")
    }
    post("/test/post-donation-entry") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.postJournalEntry(
                JournalEntryInput(
                    entryDate = LocalDate(2026, 6, 1),
                    description = q["desc"]!!,
                    voucherReference = q["voucher"],
                    donorMemberId = q["donorMemberId"],
                    donorCategory = DonorCategory.valueOf(q["donorCategory"]!!),
                    postings =
                        listOf(
                            PostingInput(
                                q["kasse"]!!,
                                PostingSide.DEBIT,
                                BigDecimal(q["amount"]!!),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                q["ertrag"]!!,
                                PostingSide.CREDIT,
                                BigDecimal(q["amount"]!!),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        ),
                ),
            )
        call.respondText("${dto.id}:${dto.status}")
    }
}

private fun Route.registerAuditLogGovernanceTestRoutes() {
    post("/test/record-resolution/{meetingId}") {
        val service = GovernanceService(call)
        val q = call.request.queryParameters
        val dto =
            service.recordResolution(
                call.parameters["meetingId"]!!,
                ResolutionInput(
                    title = q["title"]!!,
                    text = q["text"]!!,
                    votesYes = 1,
                    votesNo = 0,
                    votesAbstain = 0,
                    status = ResolutionStatus.ADOPTED,
                ),
            )
        call.respondText("${dto.id}:${dto.title}")
    }
}

private fun Route.registerAuditLogBoardMembershipTestRoutes() {
    post("/test/appoint") {
        val service = BoardMembershipService(call)
        val q = call.request.queryParameters
        val dto =
            service.appointBoardMember(
                BoardMembershipInput(
                    memberId = q["memberId"]!!,
                    committeeRole = CommitteeRole.valueOf(q["committeeRole"]!!),
                    startedAt = LocalDate.parse(q["startedAt"]!!),
                ),
            )
        call.respondText("${dto.id}:${dto.memberId}")
    }
    post("/test/end/{id}") {
        val service = BoardMembershipService(call)
        val endedAt = LocalDate.parse(call.request.queryParameters["endedAt"]!!)
        val dto = service.endBoardMembership(call.parameters["id"]!!, endedAt)
        call.respondText("${dto.id}:${dto.endedAt}")
    }
}

private fun cleanUpAuditLogTestData(
    memberIds: List<Uuid>,
    ledgerAccountIds: List<Uuid>,
    committeeIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && ledgerAccountIds.isEmpty() && committeeIds.isEmpty()) return
    transaction {
        // AuditLogEntryTable rows are deliberately NEVER cleaned up -- append-only by design (see
        // AuditLogRecorder KDoc), and this Spec's own rows carry no FK back to the members/ledger
        // accounts/committees deleted below (actor_member_id is nullable and left dangling-free by
        // simply nulling it out here, mirroring TransparenzregisterReminderTable's own resolvedBy
        // cleanup idiom -- entity_id has no FK at all, so nothing else needs adjusting).
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
        }

        val journalEntryIds =
            if (memberIds.isNotEmpty()) {
                JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList memberIds }.map { it[JournalEntryTable.id] }
            } else {
                emptyList()
            }
        if (journalEntryIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.ledgerAccountId inList ledgerAccountIds }
        }
        if (journalEntryIds.isNotEmpty()) {
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ledgerAccountIds }
        }

        if (memberIds.isNotEmpty()) {
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.resolvedBy inList memberIds }) {
                it[resolvedBy] = null
            }
            TransparenzregisterReminderTable.deleteWhere {
                (TransparenzregisterReminderTable.memberId inList memberIds) or
                    (TransparenzregisterReminderTable.resolvedBy inList memberIds)
            }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
        }

        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        if (committeeIds.isNotEmpty()) {
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }

        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
