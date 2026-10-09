package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.outbox.MailOutboxStatus
import network.lapis.cloud.server.mail.outbox.MailRecipientHasher
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Owns [MailOutboxTable] (Welle V1.9.81, durable system-mail outbox) for the data-subject paths (Art. 15 export, Art. 17 erasure).
 *
 * **Only OPEN rows (`QUEUED`/`SENDING`) can concern a person**: every final row has its payload and its lookup hash cleared in the same
 * `UPDATE` that ends it (DB CHECK `chk_mail_outbox_payload_final`), so a final row holds nothing but a purpose string, a status and
 * instants. Open rows are found WITHOUT decrypting anything, through `recipient_lookup_hash` = HMAC(lower-cased address) under a purpose-separated
 * sub-key ([MailRecipientHasher]). The addresses tried for a subject are the member's current e-mail AND the pending address of an open
 * e-mail change (a confirmation mail goes to the NEW address); for a CRM contact its e-mail.
 *
 * **Export** lists purpose, status and creation time of the subject's open rows -- never the payload. **Erasure** deletes them (a queued
 * mail that has not left yet is simply withdrawn; a row that is `SENDING` at that instant may still be delivered, the fenced completion
 * then updates 0 rows).
 *
 * **Order matters**: this contributor is registered BEFORE `FoundationPersonalData` in [PersonalDataRegistry.contributors] because erasure
 * anonymizes `member.email` -- once that ran, the address to look up would be gone.
 *
 * The hasher is installed at startup ([install]), exactly like `WebhookEventPublisher.install`; without it (no outbox configured) there
 * are no open rows -- startup closes any leftover ones (`MailOutbox.closeOrphanedRows`) -- and both paths do nothing.
 *
 * **Known gap (documented in `PersonalDataRegistry.knownUncoveredSubjectRoots`)**: a guest who is neither a member nor a CRM contact has
 * no subject root, hence no Art. 15/17 path for this table. Mitigation by design: ciphertext only, 30 minute TTL for security mails, rows
 * cleared when final.
 */
object MailOutboxPersonalData : PersonalDataContributor {
    override val sectionKey = "mailOutbox"
    override val displayName = "Wartende System-E-Mails"
    override val coveredTables = setOf(MailOutboxTable)
    override val handledSubjects = setOf(DsgvoSubjectKind.MEMBER, DsgvoSubjectKind.CRM_CONTACT)

    @Volatile
    private var hasher: MailRecipientHasher? = null

    /** Called once at startup with the outbox's hasher, or `null` when no durable queue is configured. */
    fun install(hasher: MailRecipientHasher?) {
        this.hasher = hasher
    }

    private fun lookupHashes(subject: DataSubject): List<String> {
        val h = hasher ?: return emptyList()
        val addresses =
            when (subject) {
                is DataSubject.Member -> {
                    val current =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq subject.id }
                            .singleOrNull()
                            ?.get(MemberTable.email)
                    val pending =
                        MemberEmailChangeTable
                            .selectAll()
                            .where { (MemberEmailChangeTable.memberId eq subject.id) and MemberEmailChangeTable.resolvedAt.isNull() }
                            .map { it[MemberEmailChangeTable.pendingEmail] }
                    listOfNotNull(current) + pending
                }
                is DataSubject.CrmContact ->
                    listOfNotNull(
                        CrmContactTable
                            .selectAll()
                            .where { CrmContactTable.id eq subject.id }
                            .singleOrNull()
                            ?.get(CrmContactTable.email),
                    )
            }
        return addresses.filter { it.isNotBlank() }.map(h::hash).distinct()
    }

    override fun export(subject: DataSubject): JsonElement {
        val hashes = lookupHashes(subject)
        return buildJsonArray {
            if (hashes.isEmpty()) return@buildJsonArray
            MailOutboxTable
                .selectAll()
                .where { (MailOutboxTable.status inList MailOutboxStatus.OPEN) and (MailOutboxTable.recipientLookupHash inList hashes) }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("purpose", row[MailOutboxTable.purpose])
                            put("status", row[MailOutboxTable.status])
                            put("createdAt", row[MailOutboxTable.createdAt].toString())
                        },
                    )
                }
        }
    }

    override fun erase(
        subject: DataSubject,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val hashes = lookupHashes(subject)
        if (hashes.isEmpty()) return emptyList()
        val deleted =
            MailOutboxTable.deleteWhere {
                (status inList MailOutboxStatus.OPEN) and (recipientLookupHash inList hashes)
            }
        return listOf(TableErasureOutcome(table = "mail_outbox", rowsDeleted = deleted))
    }
}
