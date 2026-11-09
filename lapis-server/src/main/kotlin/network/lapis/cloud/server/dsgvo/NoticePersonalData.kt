package network.lapis.cloud.server.dsgvo

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.bulletin.NoticeRetention
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.297 "Schwarzes Brett" -- owns [BulletinNoticeTable] and [BulletinNoticeBoostTable] (see `81-bulletin-board.kuml.kts` file header "DSGVO").
 * A notice is personal data of its AUTHOR (free text, a picture).
 *
 * **Export** (Art. 15/20): the member's own notices with every field (including a removal reason and note the board gave), per own notice the
 * NUMBER and the SUM of the boosts it received from OTHER members (never a name or an id of a booster), and the member's own boosts (notice id,
 * amount, time).
 *
 * **Erasure** deletes the member's own notices, the boost rows on them, their picture rows and the picture FILES in EVERY [ErasureMode]: a notice
 * has no counterpart copy that somebody else has an interest in (same posture as the carpool). The LTR ledger rows of other members' boosts stay
 * (booking history). The member's OWN boosts on somebody else's notices are RETAINED with a reason (part of the LTR booking history, GoBD, same
 * reason as `social_post_boost`). [BulletinNoticeTable.pinnedBy] / [BulletinNoticeTable.removedBy] of OTHER members' notices are set to `NULL`
 * under [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] (the moderation time and reason stay); under [ErasureMode.ANONYMIZE] they stay (the member
 * row is anonymized, the reference then resolves to the placeholder).
 *
 * The picture files are deleted INSIDE the caller's transaction, exactly like `AttachedImagePersonalData` (the documented exception: a
 * contributor cannot run code after the commit). Its `selectByUploader` walk then no longer finds these rows, so no file is orphaned whichever
 * contributor runs first.
 */
object NoticePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "bulletinBoard"
    override val displayName = "Schwarzes Brett"
    override val coveredTables = setOf(BulletinNoticeTable, BulletinNoticeBoostTable)

    override fun exportMember(memberId: Uuid): JsonElement {
        val own =
            BulletinNoticeTable
                .selectAll()
                .where { BulletinNoticeTable.authorMemberId eq memberId }
                .orderBy(BulletinNoticeTable.createdAt)
                .toList()
        val ownIds = own.map { it[BulletinNoticeTable.id] }
        val received = HashMap<Uuid, Pair<Int, BigDecimal>>()
        ownIds.chunked(CHUNK).forEach { chunk ->
            BulletinNoticeBoostTable
                .selectAll()
                .where { (BulletinNoticeBoostTable.noticeId inList chunk) and (BulletinNoticeBoostTable.memberId neq memberId) }
                .forEach {
                    val id = it[BulletinNoticeBoostTable.noticeId]
                    val (count, sum) = received[id] ?: (0 to BigDecimal.ZERO)
                    received[id] = (count + 1) to (sum + it[BulletinNoticeBoostTable.amountLtr])
                }
        }
        return buildJsonObject {
            putJsonArray("notices") {
                own.forEach { row ->
                    val id = row[BulletinNoticeTable.id]
                    add(
                        buildJsonObject {
                            put("id", id.toString())
                            put("category", row[BulletinNoticeTable.category].name)
                            put("title", row[BulletinNoticeTable.title])
                            put("body", row[BulletinNoticeTable.body])
                            put("status", row[BulletinNoticeTable.status].name)
                            put("createdAt", row[BulletinNoticeTable.createdAt].toString())
                            put("updatedAt", row[BulletinNoticeTable.updatedAt]?.toString())
                            put("expiresAt", row[BulletinNoticeTable.expiresAt].toString())
                            put("pinnedAt", row[BulletinNoticeTable.pinnedAt]?.toString())
                            put("withdrawnAt", row[BulletinNoticeTable.withdrawnAt]?.toString())
                            put("removedAt", row[BulletinNoticeTable.removedAt]?.toString())
                            put("removalReason", row[BulletinNoticeTable.removalReasonCode]?.name)
                            put("removalNote", row[BulletinNoticeTable.removalNote])
                            put("boostsReceivedFromOthersCount", received[id]?.first ?: 0)
                            put("boostsReceivedFromOthersSumLtr", (received[id]?.second ?: BigDecimal.ZERO).toPlainString())
                        },
                    )
                }
            }
            putJsonArray("boostsGiven") {
                BulletinNoticeBoostTable
                    .selectAll()
                    .where { BulletinNoticeBoostTable.memberId eq memberId }
                    .orderBy(BulletinNoticeBoostTable.boostedAt)
                    .forEach {
                        add(
                            buildJsonObject {
                                put("noticeId", it[BulletinNoticeBoostTable.noticeId].toString())
                                put("amountLtr", it[BulletinNoticeBoostTable.amountLtr].toPlainString())
                                put("boostedAt", it[BulletinNoticeBoostTable.boostedAt].toString())
                            },
                        )
                    }
            }
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val ownIds =
            BulletinNoticeTable
                .select(BulletinNoticeTable.id)
                .where { BulletinNoticeTable.authorMemberId eq memberId }
                .map { it[BulletinNoticeTable.id] }
        // Boost rows on the member's own notices go with the notice; the member's own boosts on those notices are among them.
        val boostsOnOwn =
            ownIds.chunked(CHUNK).sumOf { chunk ->
                BulletinNoticeBoostTable
                    .selectAll()
                    .where { BulletinNoticeBoostTable.noticeId inList chunk }
                    .count()
                    .toInt()
            }
        val keys = ownIds.flatMap { NoticeRetention.deleteNoticeInTx(it) }
        if (keys.isNotEmpty()) {
            val storage = AttachedImageStorage.fromEnvironment()
            keys.forEach { key ->
                runCatching { storage.delete(key) }
                    .onFailure { e ->
                        logger.warn(e) { "NoticePersonalData: failed to delete a notice picture file (DB row is authoritative)" }
                    }
            }
        }
        if (mode == ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) {
            BulletinNoticeTable.update({ BulletinNoticeTable.pinnedBy eq memberId }) { it[pinnedBy] = null }
            BulletinNoticeTable.update({ BulletinNoticeTable.removedBy eq memberId }) { it[removedBy] = null }
        }
        val retained =
            BulletinNoticeBoostTable
                .selectAll()
                .where { BulletinNoticeBoostTable.memberId eq memberId }
                .count()
                .toInt()
        return listOf(
            TableErasureOutcome(table = "bulletin_notice", rowsDeleted = ownIds.size),
            TableErasureOutcome(table = "bulletin_notice_boost", rowsDeleted = boostsOnOwn),
            TableErasureOutcome(
                table = "bulletin_notice_boost",
                rowsRetained = retained,
                retentionReason =
                    "Ein Boost auf den Aushang eines anderen Mitglieds ist Teil der LTR-Buchungshistorie (GoBD-Aufbewahrung, dieselbe " +
                        "Haltung wie jeder andere ltr_ledger_entry-Eintrag und wie social_post_boost) -- Betrag und Zeitpunkt bleiben unangetastet.",
            ),
        )
    }

    private const val CHUNK = 500
}
