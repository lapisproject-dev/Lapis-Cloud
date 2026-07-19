package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [JournalEntryTable] -- the only member-FK-bearing table of the accounting domain
 * (V0.3.1): `journal_entry.created_by`. `ledger_account` and `posting` have no member FK at all
 * (an account/posting is never tied to a specific member) and therefore need no
 * [PersonalDataRegistry.noPersonalDataAllowlist] entry either -- `PersonalDataCoverageTest`'s
 * `information_schema` walk only ever inspects FKs that reference `member(id)`.
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared.** This is
 * a stronger retain-with-reason than [ContributionPersonalData]'s own (which still clears the
 * free-text `note` column): every field of a posted journal entry is *itself* the material legal
 * record. Accounting records fall under GoBD (Grundsätze zur ordnungsmäßigen Führung und
 * Aufbewahrung von Büchern) / §257 HGB / §147 AO, all of which mandate a minimum ten-year
 * retention that is immune to a DSGVO erasure request (Art. 17(3)(b) DSGVO -- erasure does not
 * apply where processing is necessary for compliance with a legal obligation). `created_by` stays
 * intact and now resolves to the anonymized [network.lapis.cloud.server.db.generated.MemberTable]
 * row post-erasure, same as every other retain-with-reason contributor (display anonymized via
 * `member.anonymized_at`, the journal FK itself retained) -- see [FoundationPersonalData].
 *
 * V0.4.1: [JournalEntryTable.donorMemberId] (nullable donor attribution, see
 * `10-accounting.kuml.kts` file header) is matched here too, alongside `created_by` -- a donor
 * whose donation is booked by a *different* treasurer must still see that entry in their own
 * export, and it must still be counted as retained on erasure, for the same GoBD/§257 HGB/§147 AO
 * reasoning as every other field of a posted journal entry.
 */
object AccountingPersonalData : PersonalDataContributor {
    override val sectionKey = "accounting"
    override val displayName = "Buchhaltung"
    override val coveredTables = setOf(JournalEntryTable)

    override fun export(memberId: Uuid) =
        buildJsonArray {
            JournalEntryTable
                .selectAll()
                .where { (JournalEntryTable.createdBy eq memberId) or (JournalEntryTable.donorMemberId eq memberId) }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[JournalEntryTable.id].toString())
                            put("entryDate", row[JournalEntryTable.entryDate].toString())
                            put("description", row[JournalEntryTable.description])
                            put("voucherReference", row[JournalEntryTable.voucherReference])
                            put("status", row[JournalEntryTable.status].name)
                            put("postedAt", row[JournalEntryTable.postedAt]?.toString())
                            put("role", if (row[JournalEntryTable.createdBy] == memberId) "createdBy" else "donorMemberId")
                        },
                    )
                }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            JournalEntryTable
                .selectAll()
                .where { (JournalEntryTable.createdBy eq memberId) or (JournalEntryTable.donorMemberId eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "journal_entry",
                rowsRetained = total.toInt(),
                retentionReason =
                    "GoBD/§257 HGB/§147 AO handelsrechtliche Aufbewahrungspflicht (10 Jahre) -- " +
                        "Buchungssaetze sind der materielle Rechnungslegungsnachweis selbst, kein Feld " +
                        "wird geloescht oder anonymisiert.",
            ),
        )
    }
}
