package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- [PublicRankingConsentPersonalData]'s own
 * export/erase behavior for `public_ranking_consent_event`, mirroring
 * [ConferenceRecordingPersonalDataTest]'s house style (direct `transaction { Contributor.export/
 * erase(...) }` calls, no route/service layer). [PersonalDataCoverageTest] only proves the table is
 * covered by SOME contributor -- this file is the one that actually exercises THIS contributor's
 * "erased, never retained" posture (see class KDoc), the one deliberate departure from the
 * `conference_guest_consent_acknowledgment` retain-with-reason precedent.
 */
class PublicRankingConsentPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    PublicRankingConsentEventTable.deleteWhere { memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PublicRankingConsentPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun writeEvent(
            memberId: Uuid,
            kind: PublicRankingKind,
            eventType: PublicRankingConsentEventType,
        ) {
            val disclaimer = PublicRankingConsentDisclaimer.of(kind)
            transaction {
                PublicRankingConsentEventTable.insert {
                    it[id] = Uuid.random()
                    it[PublicRankingConsentEventTable.memberId] = memberId
                    it[rankingKind] = kind
                    it[PublicRankingConsentEventTable.eventType] = eventType
                    it[occurredAt] = DbClock.nowLocalDateTime()
                    it[supersededAt] = null
                    it[consentVersion] = disclaimer.version
                    it[consentSha256] = disclaimer.sha256
                }
            }
        }

        test("coveredTables is exactly public_ranking_consent_event") {
            PublicRankingConsentPersonalData.coveredTables shouldBe setOf(PublicRankingConsentEventTable)
        }

        test("export includes one events entry per row, with rankingKind/eventType/consentVersion") {
            val member = createTestMember("prc-pd-export@example.org")
            writeEvent(member, PublicRankingKind.LTR_HOLDINGS, PublicRankingConsentEventType.GRANTED)
            writeEvent(member, PublicRankingKind.LTR_HOLDINGS, PublicRankingConsentEventType.REVOKED)

            val export = transaction { PublicRankingConsentPersonalData.exportMember(member) }
            val events = export.jsonObject.getValue("events").jsonArray
            events.size shouldBe 2
            val eventTypes =
                events
                    .map {
                        it.jsonObject
                            .getValue("eventType")
                            .jsonPrimitive.content
                    }.toSet()
            eventTypes shouldBe setOf("GRANTED", "REVOKED")
            events.forEach {
                it.jsonObject
                    .getValue("rankingKind")
                    .jsonPrimitive.content shouldBe "LTR_HOLDINGS"
            }
        }

        test("export for a member with no consent events has an empty events array") {
            val member = createTestMember("prc-pd-export-empty@example.org")
            val export = transaction { PublicRankingConsentPersonalData.exportMember(member) }
            export.jsonObject
                .getValue("events")
                .jsonArray.size shouldBe 0
        }

        test("export sets truncated=false while at/under the cap") {
            val member = createTestMember("prc-pd-export-not-truncated@example.org")
            writeEvent(member, PublicRankingKind.LTR_HOLDINGS, PublicRankingConsentEventType.GRANTED)
            val export = transaction { PublicRankingConsentPersonalData.exportMember(member) }
            export.jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean shouldBe false
        }

        test(
            "Security-Fix (Review, Runde 2): export caps at 2000 newest rows and sets truncated=true once more exist -- " +
                "guards GET /api/dsgvo/members/{id}/export against unbounded heap/serialization pressure from an " +
                "attacker cycling grant/revoke to grow public_ranking_consent_event without bound",
        ) {
            val member = createTestMember("prc-pd-export-truncated@example.org")
            val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.LTR_HOLDINGS)
            val overCapCount = 2_001
            val base = LocalDateTime(2026, 1, 1, 0, 0)
            val ids = (0 until overCapCount).map { Uuid.random() }
            transaction {
                PublicRankingConsentEventTable.batchInsert(ids.withIndex().toList(), shouldReturnGeneratedValues = false) { (index, id) ->
                    this[PublicRankingConsentEventTable.id] = id
                    this[PublicRankingConsentEventTable.memberId] = member
                    this[PublicRankingConsentEventTable.rankingKind] = PublicRankingKind.LTR_HOLDINGS
                    this[PublicRankingConsentEventTable.eventType] = PublicRankingConsentEventType.GRANTED
                    this[PublicRankingConsentEventTable.occurredAt] =
                        (base.toInstant(TimeZone.UTC) + index.seconds).toLocalDateTime(TimeZone.UTC)
                    this[PublicRankingConsentEventTable.supersededAt] = null
                    this[PublicRankingConsentEventTable.consentVersion] = disclaimer.version
                    this[PublicRankingConsentEventTable.consentSha256] = disclaimer.sha256
                }
            }

            val export = transaction { PublicRankingConsentPersonalData.exportMember(member) }
            export.jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean shouldBe true
            export.jsonObject
                .getValue("events")
                .jsonArray.size shouldBe 2_000
        }

        test("erase HARD-DELETES every row for the member -- rowsDeleted == 2, retentionReason == null, table empty afterward") {
            val member = createTestMember("prc-pd-erase@example.org")
            writeEvent(member, PublicRankingKind.LTR_HOLDINGS, PublicRankingConsentEventType.GRANTED)
            writeEvent(member, PublicRankingKind.DONATIONS, PublicRankingConsentEventType.REVOKED)

            val outcomes = transaction { PublicRankingConsentPersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }
            val outcome = outcomes.single()
            outcome.table shouldBe "public_ranking_consent_event"
            outcome.rowsDeleted shouldBe 2
            outcome.rowsAnonymized shouldBe 0
            outcome.rowsRetained shouldBe 0
            outcome.retentionReason shouldBe null

            val remaining =
                transaction {
                    PublicRankingConsentEventTable.selectAll().where { PublicRankingConsentEventTable.memberId eq member }.count()
                }
            remaining shouldBe 0L
        }

        test("erase is a silent no-op (rowsDeleted == 0) for a member with no consent events, for both ErasureMode values") {
            val member = createTestMember("prc-pd-erase-empty@example.org")
            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcome = transaction { PublicRankingConsentPersonalData.eraseMember(memberId = member, mode = mode) }.single()
                outcome.rowsDeleted shouldBe 0
            }
        }
    })
