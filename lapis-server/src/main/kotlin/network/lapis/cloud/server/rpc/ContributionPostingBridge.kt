package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * The EINE Stelle, an der ein bezahlter Beitrag zu einem Buchungssatz wird -- egal ob manuell
 * ([network.lapis.cloud.server.rpc.ContributionService.markContributionPaid], the only caller in
 * Welle V1.2.1) or, in later sub-waves, per SEPA-Lastschrift ([source] = `SEPA_DEBIT`) oder über
 * einen Zahlungsdienstleister ([source] = `GATEWAY`). Vor V1.2.1 gab es diese Stelle NICHT:
 * `markContributionPaid` schrieb ausschliesslich ein Statusfeld, ohne jede Berührung mit
 * [AccountingService] (siehe Plan "Lapis Cloud V1.2 -- Zahlungsverkehr" Teil 0, Befund B-1).
 *
 * **Must be called from inside the caller's ALREADY-OPEN `transaction {}`** -- [postContributionPayment]
 * deliberately does NOT open its own `transaction {}`, same "transaction-free by contract" idiom
 * [AuditLogRecorder]/`ResolutionBook`/`BoardMembershipEvents` already establish, so the
 * contribution's status write and this booking commit or roll back together atomically. It must
 * also be the LAST database operation of that transaction that takes a row lock --
 * [AuditLogRecorder.record] (called internally, on success) documents the same deadlock-avoidance
 * contract; nothing else in the caller's transaction may lock a row after calling this function.
 *
 * Bewusst KEIN Aufruf von [AccountingService.postJournalEntry]: der ist rollen-gegated auf einen
 * `CurrentMember` (this bridge instead trusts [actorMemberId]/[actorRole], already resolved+role-
 * checked by the RPC-level caller) UND würde bei einer Parteispende über
 * `requirePartyDonationAllowed` werfen. Ein Mitgliedsbeitrag ist KEINE Spende --
 * `donorCategory`/`donorMemberId`/`externalDonorId` bleiben hier IMMER `null`, der §25-PartG-Pfad
 * wird nie berührt. Eine von einem echten, angemeldeten Mitglied selbst mit `AccountingService
 * .postJournalEntry` erfasste Spende läuft weiterhin über diesen menschlichen Akteur-Pfad. Eine über
 * den Zahlungsdienstleister eingegangene Spende (Welle V1.2.8, `PspWebhookIngestion`) läuft dagegen
 * über den eigenständigen `DonationPostingBridge` -- dasselbe "kein `CurrentMember`, kein Aufruf von
 * `postJournalEntry`" Muster wie hier, aber mit `donorCategory`/`donorMemberId` GESETZT (siehe dessen
 * eigene KDoc); dieser Bridge betrifft ausschliesslich Beiträge.
 *
 * **Verhält sich degradierend statt scheiternd.** Sind [OrganizationSettingsTable.paymentBankAccountId]/
 * `.paymentFeeAccountId`/`.contributionIncomeAccountId` nicht (vollständig) konfiguriert, wird
 * NICHT gebucht: `null` wird zurückgegeben, eine WARN-Zeile geschrieben, und -- entscheidend --
 * KEIN Audit-Log-Eintrag erzeugt (siehe [AuditLogRecorder] "on success" oben). Der Aufrufer (der
 * Contribution-Statuswechsel) gelingt trotzdem. Dieses Verhalten ist für jede Bestandsinstanz
 * (insbesondere `pdv2`) exakt identisch mit dem Vor-V1.2.1-Zustand, solange kein ADMIN die drei
 * Konten zuordnet -- kein Zwangs-Rollout einer Buchungslogik auf laufende Instanzen (Plan § 9.13).
 *
 * Dieselbe Degradierung gilt, wenn ein zugeordnetes Konto zwar konfiguriert, aber inzwischen
 * deaktiviert wurde ([LedgerAccountTable.active] = `false`) -- Review Round 1 (2026-08-19,
 * MAJOR-3): anders als [AccountingService.postJournalEntry] (das über `requireActiveLedgerAccounts`
 * hart mit [ConflictException] ablehnt) darf diese Bridge nicht werfen, weil ein reiner
 * Status-Wechsel weiterhin gelingen muss, selbst wenn ein Treasurer ein Konto deaktiviert hat, ohne
 * die Zuordnung nachzuziehen. Statt eines Wurfs: dieselbe "no-op + WARN, kein Audit-Log-Eintrag"-
 * Behandlung wie beim unkonfigurierten Fall, damit dieser Betriebszustand nicht lautlos bleibt.
 *
 * **GoBD-Kassenbestands-Guard, seit Security Round 1 (2026-08-19, MAJOR-1):** obwohl diese Bridge
 * bewusst NICHT [AccountingService.postJournalEntry] aufruft (siehe oben), MUSS sie trotzdem
 * dieselben zwei GoBD-Guards anwenden, die dessen eigene Guard-Preamble bereits durchsetzt --
 * [CashRegisterGuard.requireVoucherForCashPostings] ("kein Buchen ohne Beleg") und
 * [CashRegisterGuard.requireNonNegativeCashBalances] ("Kassenbestand darf nie negativ werden",
 * inklusive des `SELECT ... FOR UPDATE`-Zeilenlocks, der Nebenläufigkeit mit einem echten
 * `postJournalEntry`/`postDraftEntry`-Aufruf gegen dasselbe Kassenkonto serialisiert). Vor diesem
 * Fund konnte ein ADMIN ein `isCashRegister = true`-Konto als `paymentBankAccountId`/
 * `paymentFeeAccountId`/`contributionIncomeAccountId` zuordnen (siehe auch das SHOULD-1-Gegenstück
 * in [OrganizationSettingsService.updateOrganizationSettings], das genau das jetzt bereits beim
 * Speichern der Zuordnung verhindert) -- danach hätte jeder `markContributionPaid`-Aufruf diese
 * Kasse stillschweigend weiter ins Negative getrieben, ohne die Anwendungs- oder DB-Guards, die
 * [AccountingService] dafür bereithält. Anders als die beiden Degradierungs-Fälle oben wirft dieser
 * Guard bei einer Verletzung sehr wohl [ConflictException] -- siehe die Klausel "gilt NICHT
 * ausnahmslos" oben, dieselbe Behandlung wie [requireBalanced].
 *
 * **"Degradierend statt scheiternd" gilt NICHT ausnahmslos (Review Round 3, 2026-08-19, SHOULD-1
 * clarification):** die beiden Fälle oben (unkonfigurierte Zuordnung, deaktiviertes Konto) sind die
 * einzigen, in denen diese Bridge degradiert statt zu werfen. Ist die Zuordnung dagegen vollständig
 * konfiguriert und aktiv, aber die konstruierten Buchungssätze wären unausgeglichen, wirft
 * [postContributionPayment] sehr wohl [ConflictException] (siehe `requireBalanced` unten, Round-2-
 * Fix) -- und reisst damit die GESAMTE aufrufende Transaktion zurück, inklusive des
 * Contribution-Statuswechsels in [ContributionService.markContributionPaid]. Zukünftige Aufrufer
 * (V1.2.2/V1.2.8) müssen diesen Unterschied kennen, um zu entscheiden, ob sie einen Aufruf dieser
 * Bridge in eigene Fehlerbehandlung einpacken müssen.
 *
 * Buchungssätze (SKR42, Sphäre [GemeinnuetzigkeitSphere.IDEELLER_BEREICH] -- ein Mitgliedsbeitrag
 * ist immer ideeller Bereich, nie eine der anderen drei Sphären):
 * ```
 * Soll  <paymentBankAccountId>          <paidAmount - providerFee>
 * Soll  <paymentFeeAccountId>           <providerFee>              (nur wenn providerFee != null && > 0)
 * Haben <contributionIncomeAccountId>   <paidAmount>                (immer der volle Brutto-Betrag)
 * ```
 * Brutto/Netto bewusst getrennt gebucht, nicht saldiert -- dieselbe Begründung wie bei einer
 * Spendenbescheinigung: der volle zugewendete/geschuldete Betrag muss im Hauptbuch sichtbar sein,
 * eine PSP-Gebühr ist eigener Aufwand der Organisation. [providerFee] bleibt für JEDEN V1.2.8-Aufrufer
 * (`PspWebhookIngestion`, `source = GATEWAY`) `null` -- siehe Welle V1.2.8's eigenen Scope-Cut
 * "PSP-Gebühr wird nicht erfasst" (`checkout.session.completed` trägt keine Balance-Transaction-
 * Gebühr; ein synchroner Zusatzaufruf innerhalb der Webhook-Transaktion wäre nötig, um sie zu
 * erfassen -- bewusst nicht in dieser Welle). Der Parameter existiert bereits seit V1.2.1, damit
 * V1.2.2 (SEPA-Rücklastschriftgebühr) und ein späterer PSP-Gebühren-Abgleich dieselbe Funktion ohne
 * Signaturänderung nutzen können.
 *
 * **V1.2.2/V1.2.4-Anschlussfrage aufgelöst (V1.2.8, kein Schema-Wechsel nötig):** [JournalEntryTable.createdBy]
 * ist weiterhin `NOT NULL` (FK auf `member`) -- [actorMemberId] bleibt deshalb ein nicht-nullbarer
 * Parameter. Der ursprünglich erwogene Sentinel-"System"-Member bzw. eine nullable Spalte war NICHT
 * nötig: `PspWebhookIngestion` (der erste `source = GATEWAY`-Aufrufer dieser Bridge) verlangt eine
 * authentifizierte Session für JEDE Checkout-Erzeugung (`createContributionCheckout`/
 * `createDonationCheckout`), sodass die auslösende `member_id` bereits auf `payment_checkout_session`
 * persistiert ist, BEVOR der Webhook je eintrifft -- diese `member_id` wird beim Webhook-Empfang
 * als `actorMemberId`/`created_by` wiederverwendet. Ein anonymer/nicht angemeldeter Checkout ist
 * daher ein expliziter Scope-Cut dieser Welle (siehe `IPaymentGatewayService` KDoc), nicht nur eine
 * unvollständige Implementierung -- ein künftiger anonymer Spenden-Weg müsste die hier aufgeschobene
 * Sentinel-Member-oder-nullable-Spalten-Entscheidung tatsächlich treffen.
 */
object ContributionPostingBridge {
    fun postContributionPayment(
        contributionId: Uuid,
        paidAmount: BigDecimal,
        paidAt: LocalDateTime,
        source: ContributionPaymentMethod,
        providerFee: BigDecimal?,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        voucherReference: String?,
    ): Uuid? {
        require(paidAmount > BigDecimal.ZERO) { "paidAmount must be positive, was $paidAmount" }
        if (providerFee != null) {
            require(providerFee >= BigDecimal.ZERO) { "providerFee must not be negative, was $providerFee" }
            require(providerFee < paidAmount) { "providerFee ($providerFee) must be less than paidAmount ($paidAmount)" }
        }

        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val bankAccountId = settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)
        val feeAccountId = settingsRow?.get(OrganizationSettingsTable.paymentFeeAccountId)
        val incomeAccountId = settingsRow?.get(OrganizationSettingsTable.contributionIncomeAccountId)

        if (bankAccountId == null ||
            incomeAccountId == null ||
            (providerFee != null && providerFee > BigDecimal.ZERO && feeAccountId == null)
        ) {
            logger.warn {
                "ContributionPostingBridge: contribution $contributionId marked paid, but the account mapping " +
                    "(OrganizationSettings.paymentBankAccountId/paymentFeeAccountId/contributionIncomeAccountId) " +
                    "is not fully configured -- no journal entry was booked. An ADMIN must configure all three " +
                    "(the fee account only matters when a provider fee is actually charged) before this contribution " +
                    "line's payment reaches the general ledger."
            }
            return null
        }

        val referencedAccountIds =
            listOfNotNull(bankAccountId, incomeAccountId, feeAccountId.takeIf { providerFee != null && providerFee > BigDecimal.ZERO })
        val inactiveAccountIds =
            referencedAccountIds.distinct().filter { accountId ->
                val row = LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                row == null || !row[LedgerAccountTable.active]
            }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "ContributionPostingBridge: contribution $contributionId marked paid, but the configured account " +
                    "mapping references LedgerAccount(s) $inactiveAccountIds that are missing or deactivated -- " +
                    "no journal entry was booked. An ADMIN must either reactivate the account(s) or reconfigure " +
                    "OrganizationSettings.paymentBankAccountId/paymentFeeAccountId/contributionIncomeAccountId " +
                    "before this contribution line's payment reaches the general ledger."
            }
            return null
        }

        val netAmount = if (providerFee != null) paidAmount - providerFee else paidAmount

        val postingInputs =
            listOfNotNull(
                PostingInput(
                    ledgerAccountId = bankAccountId.toString(),
                    side = PostingSide.DEBIT,
                    amount = netAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
                if (providerFee != null && providerFee > BigDecimal.ZERO) {
                    PostingInput(
                        ledgerAccountId = requireNotNull(feeAccountId).toString(),
                        side = PostingSide.DEBIT,
                        amount = providerFee,
                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    )
                } else {
                    null
                },
                PostingInput(
                    ledgerAccountId = incomeAccountId.toString(),
                    side = PostingSide.CREDIT,
                    amount = paidAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
            )

        // Review Round 2 (2026-08-19, SHOULD-1): re-establish the Sigma-debit = Sigma-credit
        // invariant that AccountingService.postJournalEntry's normal path enforces via its own
        // (private) requireBalanced/JournalEntryBalance.validateBalanced BEFORE this bridge existed
        // to bypass it. Balanced by construction today (netAmount + providerFee == paidAmount) for
        // any input this wave's only caller (markContributionPaid, providerFee always null) can
        // produce -- but providerFee is a real, non-validated-for-scale parameter a future caller
        // (V1.2.2 Ruecklastschriftgebuehr; a later PSP-fee-reconciliation wave, see V1.2.8's own
        // documented scope cut) WILL pass, and paidAmount/providerFee
        // are caller-supplied BigDecimals with no scale guard above. Reusing
        // JournalEntryBalance.validateBalanced (internal, same package) rather than duplicating its
        // logic also re-establishes the scale<=2 guard for free, matching PostingTable's
        // DECIMAL(15,2) column exactly -- see that object's KDoc "Sub-cent rounding guard".
        requireBalanced(postingInputs)

        // resolvedVoucherReference computed here (rather than immediately before the
        // JournalEntryTable.insert below, where it used to live) so requireVoucherForCashPostings
        // checks the SAME value that actually gets persisted -- never blank by construction (falls
        // back to "CONTRIB-$contributionId"), but the guard must see that fallback applied, not the
        // caller's raw possibly-null voucherReference.
        val resolvedVoucherReference = voucherReference ?: "CONTRIB-$contributionId"

        // Security Round 1 (2026-08-19, MAJOR-1): re-establish the two GoBD cash-register guards
        // AccountingService.postJournalEntry's own guard preamble always applies -- see class KDoc
        // "GoBD-Kassenbestands-Guard" above for the full rationale. referencedAccountIds was already
        // computed above for the active-account check; reused here rather than recomputed.
        val cashAccountIds = CashRegisterGuard.loadCashRegisterAccountIds(referencedAccountIds)
        CashRegisterGuard.requireVoucherForCashPostings(voucherReference = resolvedVoucherReference, cashAccountIds = cashAccountIds)
        CashRegisterGuard.requireNonNegativeCashBalances(postings = postingInputs, cashAccountIds = cashAccountIds)

        val journalEntryId = Uuid.random()
        val entryDate = paidAt.date
        val description = "Mitgliedsbeitrag $contributionId (${source.name.lowercase()})"

        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = entryDate
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = resolvedVoucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = paidAt
            // createdAt is the real Erfassungszeitpunkt (system "now"), NOT the caller-supplied,
            // unvalidated business date paidAt -- Review Round 1 (2026-08-19, MAJOR-4). Matches
            // AccountingService.insertJournalEntry's own createdAt = nowLocalDateTime() exactly.
            it[createdAt] = DbClock.nowLocalDateTime()
            it[donorMemberId] = null
            it[externalDonorId] = null
            it[donorCategory] = null
        }

        val postingSnapshots = mutableListOf<PostingSnapshot>()

        fun insertPosting(
            ledgerAccountId: Uuid,
            side: PostingSide,
            amount: BigDecimal,
        ) {
            PostingTable.insert {
                it[id] = Uuid.random()
                it[PostingTable.journalEntryId] = journalEntryId
                it[PostingTable.ledgerAccountId] = ledgerAccountId
                it[PostingTable.side] = side
                it[PostingTable.amount] = amount
                it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                it[costCenterId] = null
            }
            postingSnapshots +=
                PostingSnapshot(
                    ledgerAccountId = ledgerAccountId.toString(),
                    side = side,
                    amount = amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    costCenterId = null,
                )
        }

        insertPosting(ledgerAccountId = bankAccountId, side = PostingSide.DEBIT, amount = netAmount)
        if (providerFee != null && providerFee > BigDecimal.ZERO) {
            insertPosting(ledgerAccountId = requireNotNull(feeAccountId), side = PostingSide.DEBIT, amount = providerFee)
        }
        insertPosting(ledgerAccountId = incomeAccountId, side = PostingSide.CREDIT, amount = paidAmount)

        // Last locking operation, see class KDoc -- mirrors AccountingService.insertJournalEntry's
        // own "always audit a freshly created JournalEntry" behaviour exactly. entityType stays the
        // EXISTING AuditEntityType.JOURNAL_ENTRY (no new literal, no audit_log_entry CHECK widening
        // needed this wave) -- the general ledger's audit trail must not distinguish "booked via
        // AccountingService.postJournalEntry" from "booked via this bridge".
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.JOURNAL_ENTRY,
            entityId = journalEntryId,
            action = AuditAction.CREATE,
            before = null,
            after =
                Json.encodeToString(
                    JournalEntrySnapshot.serializer(),
                    JournalEntrySnapshot(
                        entryDate = entryDate,
                        description = description,
                        voucherReference = resolvedVoucherReference,
                        status = JournalEntryStatus.POSTED,
                        postedAt = paidAt,
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = postingSnapshots,
                    ),
                ),
            // occurredAt deliberately omitted -- defaults to AuditLogRecorder.record's own
            // nowLocalDateTime(), the real Erfassungszeitpunkt. NOT paidAt (caller-supplied,
            // unvalidated business date) -- Review Round 1 (2026-08-19, MAJOR-4). Every other
            // occurredAt= call site in this codebase (StreamPoller, SecretBallotStreamGuard,
            // RecordingPoller, ConferenceStreamingService, recordPartyDonationVerdictAudit) passes
            // the real current time, never a caller-supplied value -- a backdated/postdated paidAt
            // must never be able to claim it was also the moment the immutable, hash-chained audit
            // log recorded this action.
        )

        return journalEntryId
    }

    /**
     * Throws [ConflictException] naming the imbalance/invalid-scale reason if [postings] does not
     * balance. The underlying validation LOGIC is reused, not duplicated -- both this function and
     * [AccountingService]'s own private `requireBalanced` delegate to the same shared
     * [JournalEntryBalance.validateBalanced] (see line ~165 above). What IS separate is the CALL
     * SITE: this bridge has its own small `requireBalanced` wrapper instead of calling into
     * [AccountingService] itself, for the same reason this whole bridge exists standalone -- see
     * class KDoc "Bewusst KEIN Aufruf von [AccountingService.postJournalEntry]".
     */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }
}
