package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.PulpitRecordingControl
import network.lapis.cloud.server.encounter.PulpitRecordingNotice
import network.lapis.cloud.server.encounter.PulpitStopReason
import network.lapis.cloud.server.memberfinder.MemberFinderLifecycle
import network.lapis.cloud.server.memberfinder.MemberFinderNotice
import network.lapis.cloud.server.payment.sepa.BicValidator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.OrganizationSettingsPaymentMappingSnapshot
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.paymentAccountMappingConflictOf
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.MemberFinderNoticeOutdatedException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PulpitRecordingNoticeOutdatedException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Welle V1.4.5.2 "DATEV-Format-Export" -- mirrors `V22__datev_export.sql`'s own CHECK ranges. */
private val DATEV_BERATER_NUMMER_RANGE = 1001..9999999
private val DATEV_MANDANT_NUMMER_RANGE = 1..99999

/**
 * The single seeded [OrganizationSettingsTable] row's fixed id -- see
 * `lapis-server/src/main/resources/db/migration/V1__baseline.sql`'s unconditional seed `INSERT`
 * (not gated behind `LAPIS_SEED_DEMO_DATA`, unlike `network.lapis.cloud.server.db.DevSeedData`'s
 * own sentinel ids -- letterhead data existing at all is a real capability precondition, not
 * demo/sample data) and `11-organization-settings.kuml.kts`'s file header for the full
 * exactly-one-row-by-convention rationale.
 */
val ORGANIZATION_SETTINGS_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f2")

/**
 * Implements [IOrganizationSettingsService] -- see that interface's KDoc. There is no create/
 * delete; both [getOrganizationSettings] and [updateOrganizationSettings] always target the one
 * row seeded at [ORGANIZATION_SETTINGS_ID].
 */
class OrganizationSettingsService(
    private val call: ApplicationCall,
    /**
     * V1.9.143: switching the pulpit recording OFF stops every running recording. A module singleton, WITHOUT a default: a default would hand every
     * request an engine that knows of no running recording and would silently stop nothing.
     */
    private val pulpitRecording: PulpitRecordingControl,
    /** V1.9.143: whether this server has a recording service at all (`LAPIS_RECORDING_ENABLED`); only shown to the admin, never stored. */
    private val recordingInfrastructureAvailable: Boolean = false,
) : IOrganizationSettingsService {
    override suspend fun getOrganizationSettings(): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*READ_ROLES)
        return transaction { loadOrganizationSettings() }.withInfrastructure()
    }

    /**
     * V1.9.124 -- the ONLY way to change [OrganizationSettingsDto.noiseSuppressionDefault] (ADMIN). A change writes one
     * [AuditEntityType.ORGANIZATION_SETTINGS] `UPDATE` entry (before/after as a one-field JSON snapshot, no personal data -- the actor is
     * the audit frame's own); setting the value it already has writes neither a row nor an audit entry. The generic
     * [updateOrganizationSettings] never touches the column.
     */
    override suspend fun updateNoiseSuppressionDefault(enabled: Boolean): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val result =
            transaction {
                val before = NoiseSuppressionDefaultStore.load()
                if (before != enabled) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[noiseSuppressionDefault] = enabled
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                        entityId = ORGANIZATION_SETTINGS_ID,
                        action = AuditAction.UPDATE,
                        before = "{\"noiseSuppressionDefault\":$before}",
                        after = "{\"noiseSuppressionDefault\":$enabled}",
                    )
                }
                loadOrganizationSettings().withInfrastructure() to (before != enabled)
            }
        if (result.second) logger.info { "noise suppression default set to $enabled by ${current.memberId}" }
        return result.first
    }

    /**
     * V1.9.129 -- the ONLY way to change [OrganizationSettingsDto.videoOverlaysOffered] (ADMIN). A change writes one
     * [AuditEntityType.ORGANIZATION_SETTINGS] `UPDATE` entry (before/after as a one-field JSON snapshot, no personal data); setting the
     * value it already has writes neither a row nor an audit entry. The generic [updateOrganizationSettings] never touches the column.
     */
    override suspend fun updateVideoOverlaysOffered(enabled: Boolean): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val result =
            transaction {
                val before = VideoOverlaysOfferedStore.load()
                if (before != enabled) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[videoOverlaysOffered] = enabled
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                        entityId = ORGANIZATION_SETTINGS_ID,
                        action = AuditAction.UPDATE,
                        before = "{\"videoOverlaysOffered\":$before}",
                        after = "{\"videoOverlaysOffered\":$enabled}",
                    )
                }
                loadOrganizationSettings().withInfrastructure() to (before != enabled)
            }
        if (result.second) logger.info { "video overlays offered set to $enabled by ${current.memberId}" }
        return result.first
    }

    /**
     * V1.9.144 -- the ONLY way to switch the optional member finder on or off (ADMIN). Party membership is a special category of data, so switching it
     * ON requires [acknowledgedNoticeVersion] to be the current version of the operator notice ([MEMBER_FINDER_NOTICE_VERSION]); the audit entry records
     * that version and the hash of the German reference text. A stale or missing version is [MemberFinderNoticeOutdatedException]. Setting the value it
     * already has writes nothing -- except acknowledging the CURRENT version again while the stored one is older, which is a change and is audited.
     *
     * Switching OFF takes no parameter and, in the SAME transaction (plain database code, no external effect), deletes every request, block and band and
     * ends every effective consent with a system revoke (decision E8, [MemberFinderLifecycle.onInstanceDisabled]). The audit entry carries NO count of
     * what was deleted. The whole change runs under the global finder lock, so it cannot interleave with a request being sent.
     */
    override suspend fun updateMemberFinderEnabled(
        enabled: Boolean,
        acknowledgedNoticeVersion: String?,
    ): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (enabled && acknowledgedNoticeVersion != MEMBER_FINDER_NOTICE_VERSION) throw MemberFinderNoticeOutdatedException()
        val result =
            transaction {
                val before = MemberFinderSettingsStore.lockAndLoad()
                val newVersion = if (enabled) MEMBER_FINDER_NOTICE_VERSION else before.noticeVersion
                val changed = before.enabled != enabled || before.noticeVersion != newVersion
                if (changed) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[memberFinderEnabled] = enabled
                        it[memberFinderNoticeVersion] = newVersion
                    }
                    if (!enabled && before.enabled) MemberFinderLifecycle.onInstanceDisabled(now = ServerClock.now())
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                        entityId = ORGANIZATION_SETTINGS_ID,
                        action = AuditAction.UPDATE,
                        before = finderSnapshot(enabled = before.enabled, version = before.noticeVersion),
                        after =
                            finderSnapshot(
                                enabled = enabled,
                                version = newVersion,
                                noticeSha256 = if (enabled) MemberFinderNotice.SHA256 else null,
                            ),
                    )
                }
                loadOrganizationSettings().withInfrastructure() to changed
            }
        // No member and no count in the log: only the fact that the switch moved.
        if (result.second) logger.info { "member finder switched ${if (enabled) "on" else "off"}" }
        return result.first
    }

    private fun OrganizationSettingsDto.withInfrastructure(): OrganizationSettingsDto =
        copy(pulpitRecordingInfrastructureAvailable = recordingInfrastructureAvailable)

    /**
     * V1.9.143 -- the ONLY way to switch the optional pulpit recording on or off (ADMIN). The legal admissibility of a recording depends on the
     * country of the instance, so switching it ON requires [acknowledgedNoticeVersion] to be the current version of the operator notice
     * ([PULPIT_RECORDING_NOTICE_VERSION]); the audit entry records that version and the hash of the German reference text. A stale or missing version is
     * [PulpitRecordingNoticeOutdatedException] (the admin is authorized already, so the type reveals nothing). Setting the value it already has
     * writes nothing -- except acknowledging the CURRENT version again while the stored one is older, which is a change and is audited.
     *
     * Switching OFF takes no parameter, keeps the stored version as history and -- AFTER the commit -- stops every running recording. Merely raising the
     * notice version later does NOT stop anything: it only blocks new starts until an admin acknowledges the new text.
     */
    override suspend fun updatePulpitRecordingEnabled(
        enabled: Boolean,
        acknowledgedNoticeVersion: String?,
    ): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (enabled && acknowledgedNoticeVersion != PULPIT_RECORDING_NOTICE_VERSION) throw PulpitRecordingNoticeOutdatedException()
        val result =
            transaction {
                val before = PulpitRecordingSettingsStore.load()
                val newVersion = if (enabled) PULPIT_RECORDING_NOTICE_VERSION else before.noticeVersion
                val changed = before.enabled != enabled || before.noticeVersion != newVersion
                if (changed) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[pulpitRecordingEnabled] = enabled
                        it[pulpitRecordingNoticeVersion] = newVersion
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                        entityId = ORGANIZATION_SETTINGS_ID,
                        action = AuditAction.UPDATE,
                        before = pulpitSnapshot(enabled = before.enabled, version = before.noticeVersion),
                        after =
                            pulpitSnapshot(
                                enabled = enabled,
                                version = newVersion,
                                noticeSha256 = if (enabled) PulpitRecordingNotice.SHA256 else null,
                            ),
                    )
                }
                loadOrganizationSettings().withInfrastructure() to changed
            }
        if (result.second) logger.info { "pulpit recording switched ${if (enabled) "on" else "off"} by ${current.memberId}" }
        // After the commit, never inside the transaction: LiveKit calls are made outside of it.
        if (!enabled) pulpitRecording.stopAll(PulpitStopReason.INSTANCE_DISABLED)
        return result.first
    }

    /**
     * V1.9.143 -- whether the person at the pulpit has to confirm before a recording starts (ADMIN, audited on change). Only while the instance switch is
     * on: without a running feature the setting has no meaning, so a call while it is off is a [ConflictException].
     */
    override suspend fun updatePulpitRecordingRequireConfirmation(required: Boolean): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val before = PulpitRecordingSettingsStore.load()
            if (!before.enabled) throw ConflictException("The pulpit recording is not switched on")
            if (before.requireConfirmation != required) {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[pulpitRecordingRequireConfirmation] = required
                }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = "{\"pulpitRecordingRequireConfirmation\":${before.requireConfirmation}}",
                    after = "{\"pulpitRecordingRequireConfirmation\":$required}",
                )
            }
            loadOrganizationSettings().withInfrastructure()
        }
    }

    /**
     * Security Round 1 (2026-08-19). This method wholesale-replaces EVERY field of the single
     * seeded row -- including the three payment-account-mapping fields
     * ([OrganizationSettingsDto.paymentBankAccountId]/[OrganizationSettingsDto.paymentFeeAccountId]/
     * [OrganizationSettingsDto.contributionIncomeAccountId]) that decide where every FUTURE
     * contribution payment gets booked (see [ContributionPostingBridge] KDoc). Two fixes:
     *
     * **MAJOR-1 (defense-in-depth, "unreachable by construction" half)**: [requireValidPaymentAccountMapping]
     * rejects a mapping target that does not exist, is inactive, is a cash-register account (see
     * [ContributionPostingBridge] KDoc "GoBD-Kassenbestands-Guard" for the other, runtime half of
     * this same fix, which additionally still applies [CashRegisterGuard] at posting time), or has
     * the wrong [LedgerAccountType] for its role (bank -> `ASSET`, fee -> `EXPENSE`, income ->
     * `INCOME`, matching every `ContributionPostingBridgeTest`/`ContributionPaymentRpcTest` fixture's
     * own account-type choice for these three roles). A malformed id string is a
     * [NotFoundException] (this codebase's established "well-formed-ness vs. semantic validity"
     * split, see `ContributionService.toContributionUuid`); an existing-but-wrong-state/-type
     * account is a [ConflictException] (same tier [requireActiveLedgerAccounts] itself uses).
     *
     * **MAJOR-2 (GoBD Nachvollziehbarkeit)**: writes an [AuditEntityType.ORGANIZATION_SETTINGS]
     * `UPDATE` audit entry via [AuditLogRecorder] -- but ONLY when at least one of the three mapping
     * fields actually changed. Deliberately narrower than "audit the whole diff": this method also
     * replaces many purely administrative, non-financial fields (address, bank-IBAN-for-display,
     * tax-exemption authority/date) on every call, and an audit-log entry on every one of those
     * calls would flood the GoBD trail with entries unrelated to the concern
     * [OrganizationSettingsPaymentMappingSnapshot] exists for -- "who repointed which contribution
     * booking, when". If a broader audit trail for the other fields is ever wanted, that is a
     * separate, deliberate future decision, not an oversight of this fix. Must be the LAST database
     * operation of this transaction that takes a row lock (see [AuditLogRecorder] KDoc) -- the
     * `OrganizationSettingsTable.update` below always runs first.
     *
     * **MAJOR-3 (Security Round 2, GoBD Nachvollziehbarkeit)**: writes a second, independent
     * [AuditEntityType.ORGANIZATION_SETTINGS] `UPDATE` audit entry -- ONLY when `isKleinunternehmer`
     * actually changed -- for the exact same reason MAJOR-2 audits the payment-account mapping:
     * `isKleinunternehmer` flips `AccountingService.vatActive()` (`vatEnabled && !isKleinunternehmer`)
     * exactly the same way `vatEnabled` itself does, so from that moment on new bookings are
     * normalized to UNCLASSIFIED/0.00 and the VAT_BEARING_ENTRY export blocker stops firing.
     * `IVatService.enableVat`/`disableVat` already audit their own half of this gate (`vatEnabled`);
     * before this fix, flipping the OTHER half through this generic ADMIN-writable field left no
     * trace at all of who did it or when. Kept as a separate `record` call rather than folded into
     * the mapping snapshot above -- same "narrower than the whole diff" reasoning, this is a
     * different concern from contribution-account routing.
     */
    override suspend fun updateOrganizationSettings(input: OrganizationSettingsInput): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val bankAccountId = input.paymentBankAccountId?.toPaymentAccountUuid("paymentBankAccountId")
        val feeAccountId = input.paymentFeeAccountId?.toPaymentAccountUuid("paymentFeeAccountId")
        val incomeAccountId = input.contributionIncomeAccountId?.toPaymentAccountUuid("contributionIncomeAccountId")
        // Welle V1.2.8 "PSP-Checkout (Stripe)". Named `donationAccountId` (not
        // `donationIncomeAccountId`) deliberately -- inside the `update { }` block below,
        // `it[donationIncomeAccountId] = ...`'s left-hand side resolves the COLUMN via the block's
        // implicit `OrganizationSettingsTable` receiver; reusing the exact same name for this local
        // `Uuid?` would shadow that column reference on the right-hand side too. Same reason the
        // three pre-existing local vals above are named `bankAccountId`/`feeAccountId`/
        // `incomeAccountId` rather than their own column names.
        val donationAccountId = input.donationIncomeAccountId?.toPaymentAccountUuid("donationIncomeAccountId")
        // Welle V1.4.3.1 "Veranstaltungen" (Review MAJOR fix) -- same "id resolved here, the
        // matching column-name-shadowing local val below" reasoning as donationAccountId above.
        val eventAccountId = input.eventIncomeAccountId?.toPaymentAccountUuid("eventIncomeAccountId")
        // Welle V1.4.11 "Reisekostenabrechnung" -- same reasoning again (local named `travelAccountId`,
        // not `travelExpenseAccountId`, to avoid shadowing the column reference below). The FIRST
        // expense-side (not income-side) mapping field in this method.
        val travelAccountId = input.travelExpenseAccountId?.toPaymentAccountUuid("travelExpenseAccountId")
        // Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- same reasoning again (local
        // named `volunteerAccountId`, not `volunteerAllowanceAccountId`, to avoid shadowing the
        // column reference below). The SECOND expense-side mapping field in this method.
        val volunteerAccountId = input.volunteerAllowanceAccountId?.toPaymentAccountUuid("volunteerAllowanceAccountId")
        // Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- two more expense-/asset-side mapping
        // fields, same "local var named to avoid shadowing the column reference below" reasoning.
        // Named receivablesLedgerAccountId/payablesLedgerAccountId (not receivablesAccountId/
        // payablesAccountId) to avoid shadowing the OrganizationSettingsTable column references
        // below -- same reasoning every other local val in this method already documents.
        val receivablesLedgerAccountId = input.receivablesAccountId?.toPaymentAccountUuid("receivablesAccountId")
        val payablesLedgerAccountId = input.payablesAccountId?.toPaymentAccountUuid("payablesAccountId")
        // Security Round 1 (2026-08-20, MINOR-5): `bankIban`/`bankBic` are used as the CREDITOR's own
        // IBAN/BIC in every SEPA pain.008 file generated by SepaService.generateBatchFile (via the
        // frozen `sepa_debit_batch.creditor_iban`/`.creditor_bic` snapshot, see that class' KDoc) --
        // unlike `debtorBic` (validated at `grantMandate`), these were previously persisted with ZERO
        // validation anywhere, so a malformed value would either sail into the bank file unchecked or
        // surface as a raw, unmapped IllegalArgumentException (HTTP 500) once
        // SepaPain008Writer.validate finally ran. Applying the SAME IbanValidator/BicValidator this
        // wave already uses for the debtor side, at the point the value is SAVED -- catching the
        // problem at admin-entry time rather than deep inside a treasurer's later file-generation
        // attempt. normalizedBankIban uses IbanValidator's own canonical (whitespace-stripped,
        // upper-cased) form, same as every other IBAN this codebase persists.
        val normalizedBankIban =
            input.bankIban?.let { raw ->
                try {
                    IbanValidator.requireValid(raw)
                } catch (e: IllegalArgumentException) {
                    throw ConflictException("Die IBAN der Organisation (bankIban) ist ungueltig: ${e.message}")
                }
            }
        input.bankBic?.let {
            if (!BicValidator.isValid(it)) {
                throw ConflictException("Die BIC der Organisation (bankBic) hat kein gueltiges Format.")
            }
        }
        // Welle V1.4.5.2 "DATEV-Format-Export". Serverside range validation, mirroring the
        // IbanValidator/BicValidator checks above -- the DB CHECK constraint (V22__datev_export.sql)
        // is the backstop, not the error message a caller actually sees.
        input.datevBeraterNummer?.let {
            if (it !in DATEV_BERATER_NUMMER_RANGE) {
                throw ConflictException("Die DATEV-Beraternummer muss zwischen 1001 und 9999999 liegen, war $it.")
            }
        }
        input.datevMandantNummer?.let {
            if (it !in DATEV_MANDANT_NUMMER_RANGE) {
                throw ConflictException("Die DATEV-Mandantennummer muss zwischen 1 und 99999 liegen, war $it.")
            }
        }
        return transaction {
            requireValidPaymentAccountMapping(
                role = "paymentBankAccountId",
                accountId = bankAccountId,
                expectedType = LedgerAccountType.ASSET,
            )
            requireValidPaymentAccountMapping(
                role = "paymentFeeAccountId",
                accountId = feeAccountId,
                expectedType = LedgerAccountType.EXPENSE,
            )
            requireValidPaymentAccountMapping(
                role = "contributionIncomeAccountId",
                accountId = incomeAccountId,
                expectedType = LedgerAccountType.INCOME,
            )
            requireValidPaymentAccountMapping(
                role = "donationIncomeAccountId",
                accountId = donationAccountId,
                expectedType = LedgerAccountType.INCOME,
            )
            requireValidPaymentAccountMapping(
                role = "eventIncomeAccountId",
                accountId = eventAccountId,
                expectedType = LedgerAccountType.INCOME,
            )
            // Welle V1.4.11 "Reisekostenabrechnung" -- the first EXPENSE-typed mapping this method
            // validates (every mapping above is INCOME- or ASSET-typed).
            requireValidPaymentAccountMapping(
                role = "travelExpenseAccountId",
                accountId = travelAccountId,
                expectedType = LedgerAccountType.EXPENSE,
            )
            // Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- the second EXPENSE-typed
            // mapping this method validates.
            requireValidPaymentAccountMapping(
                role = "volunteerAllowanceAccountId",
                accountId = volunteerAccountId,
                expectedType = LedgerAccountType.EXPENSE,
            )
            // Welle V1.4.15 -- receivablesAccountId books a debitor's income-side credit (ASSET,
            // mirrors the debit side of every existing income mapping's booking direction: money
            // OWED to the organization sits on an asset account until settled), payablesAccountId
            // a kreditor's counter-posting (LIABILITY, money the organization OWES). See
            // OpenItemPostingBridge KDoc "Buchungssaetze".
            requireValidPaymentAccountMapping(
                role = "receivablesAccountId",
                accountId = receivablesLedgerAccountId,
                expectedType = LedgerAccountType.ASSET,
            )
            requireValidPaymentAccountMapping(
                role = "payablesAccountId",
                accountId = payablesLedgerAccountId,
                expectedType = LedgerAccountType.LIABILITY,
            )
            // Welle V1.4.22 Audit-Nachtrag (MAJOR-3): every check above validates ONE field in
            // isolation, so a self-referential COMBINATION passed -- above all
            // paymentBankAccountId == receivablesAccountId, which every settlement would then book
            // debit and credit against the same account (the staging finding of this wave, from the
            // other end). Shared rule, so the chart-of-accounts screen can say the same thing before
            // the round trip.
            requireConsistentPaymentAccountMapping(
                bankAccountId = bankAccountId,
                receivablesAccountId = receivablesLedgerAccountId,
                payablesAccountId = payablesLedgerAccountId,
            )

            val beforeRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()
            // Security Round 2 (MAJOR, isKleinunternehmer Nachvollziehbarkeit): captured BEFORE the
            // update below so it can be compared against input.isKleinunternehmer afterwards -- same
            // "read old value first, diff after the write" shape MAJOR-2's beforeMapping/afterMapping
            // already establishes for the payment-account mapping.
            val wasKleinunternehmer = beforeRow[OrganizationSettingsTable.isKleinunternehmer]
            // Welle V1.4.14 "Mehrere Bankkonten" -- once at least one `bank_account` row exists,
            // that table (via `BankAccountStore.setDefault`/`create`/`delete`) becomes the SOLE
            // writer of bankIban/bankBic; this generic update path silently keeps whatever value
            // is already there instead of overwriting it with a possibly stale form submission
            // (the client's own OrganizationSettings edit form still shows the mirrored value, so a
            // resubmission is harmless, but a client that never re-reads it would otherwise clobber
            // a change made through the bank-accounts screen a moment earlier). No behaviour change
            // at all while `bank_account` is empty (the pre-wave codepath every existing test
            // exercises).
            val bankAccountRowsExist = BankAccountTable.selectAll().count() > 0
            val effectiveBankIban = if (bankAccountRowsExist) beforeRow[OrganizationSettingsTable.bankIban] else normalizedBankIban
            val effectiveBankBic = if (bankAccountRowsExist) beforeRow[OrganizationSettingsTable.bankBic] else input.bankBic
            val beforeMapping =
                OrganizationSettingsPaymentMappingSnapshot(
                    paymentBankAccountId = beforeRow[OrganizationSettingsTable.paymentBankAccountId]?.toString(),
                    paymentFeeAccountId = beforeRow[OrganizationSettingsTable.paymentFeeAccountId]?.toString(),
                    contributionIncomeAccountId = beforeRow[OrganizationSettingsTable.contributionIncomeAccountId]?.toString(),
                    donationIncomeAccountId = beforeRow[OrganizationSettingsTable.donationIncomeAccountId]?.toString(),
                    eventIncomeAccountId = beforeRow[OrganizationSettingsTable.eventIncomeAccountId]?.toString(),
                    travelExpenseAccountId = beforeRow[OrganizationSettingsTable.travelExpenseAccountId]?.toString(),
                    volunteerAllowanceAccountId = beforeRow[OrganizationSettingsTable.volunteerAllowanceAccountId]?.toString(),
                    receivablesAccountId = beforeRow[OrganizationSettingsTable.receivablesAccountId]?.toString(),
                    payablesAccountId = beforeRow[OrganizationSettingsTable.payablesAccountId]?.toString(),
                )

            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[name] = input.name
                it[street] = input.street
                it[postalCode] = input.postalCode
                it[city] = input.city
                it[country] = input.country
                it[bankIban] = effectiveBankIban
                it[bankBic] = effectiveBankBic
                it[taxExemptionAuthority] = input.taxExemptionAuthority
                it[taxExemptionDate] = input.taxExemptionDate
                it[isPoliticalParty] = input.isPoliticalParty
                it[postalMailEnabled] = input.postalMailEnabled
                it[politicianRankingEnabled] = input.politicianRankingEnabled
                it[paymentBankAccountId] = bankAccountId
                it[paymentFeeAccountId] = feeAccountId
                it[contributionIncomeAccountId] = incomeAccountId
                it[donationIncomeAccountId] = donationAccountId
                it[eventIncomeAccountId] = eventAccountId
                it[eventIncomeSphere] = input.eventIncomeSphere
                it[travelExpenseAccountId] = travelAccountId
                it[volunteerAllowanceAccountId] = volunteerAccountId
                // V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- ordinary ADMIN-writable
                // configuration, same tier as the mapping fields above.
                it[OrganizationSettingsTable.receivablesAccountId] = receivablesLedgerAccountId
                it[OrganizationSettingsTable.payablesAccountId] = payablesLedgerAccountId
                it[receivableDunningEnabled] = input.receivableDunningEnabled
                // V1.9.10 "Mitgliederzahl-Sichtbarkeit" -- ordinary ADMIN-writable configuration,
                // same tier again.
                it[showPublicMemberCount] = input.showPublicMemberCount
                // V1.4.5.2 DATEV-Format-Export -- ordinary ADMIN-writable configuration, same tier
                // as the mapping fields above.
                it[datevBeraterNummer] = input.datevBeraterNummer
                it[datevMandantNummer] = input.datevMandantNummer
                // V1.4.13 "USt-Voranmeldung" -- ordinary ADMIN-writable configuration, same tier
                // again. `vatEnabled` is DELIBERATELY absent from this write-set -- see
                // OrganizationSettingsDto.vatEnabled KDoc. Only IVatService.enableVat (disclaimer-
                // acknowledgment required)/disableVat may flip it.
                it[isKleinunternehmer] = input.isKleinunternehmer
                // auctionEnabled/auctionMaxValueLtr are DELIBERATELY absent from this write-set --
                // see OrganizationSettingsDto.auctionEnabled KDoc. The generic update path must
                // never be able to flip the auction gate; only AuctionService.enableAuction
                // (disclaimer-acknowledgment required)/disableAuction/setAuctionMaxValueLtr may.
                // sepaDebitEnabled/paymentGatewayEnabled/paymentGatewayProvider are likewise
                // DELIBERATELY absent -- see OrganizationSettingsDto.sepaDebitEnabled KDoc. Only
                // SepaService.enableSepaDebit/disableSepaDebit and
                // PaymentGatewayService.enablePaymentGateway/disablePaymentGateway (both
                // disclaimer-acknowledgment-gated) may flip those.
                // V1.9.124: noiseSuppressionDefault is likewise DELIBERATELY absent -- only
                // updateNoiseSuppressionDefault (ADMIN, audited on change) may set it.
                // V1.9.129: videoOverlaysOffered likewise -- only updateVideoOverlaysOffered may set it.
                // V1.9.143: pulpitRecordingEnabled / pulpitRecordingNoticeVersion / pulpitRecordingRequireConfirmation likewise -- only
                // updatePulpitRecordingEnabled / updatePulpitRecordingRequireConfirmation may set them.
                // V1.9.144: memberFinderEnabled / memberFinderNoticeVersion likewise -- only updateMemberFinderEnabled may set them.
            }

            val afterMapping =
                OrganizationSettingsPaymentMappingSnapshot(
                    paymentBankAccountId = bankAccountId?.toString(),
                    paymentFeeAccountId = feeAccountId?.toString(),
                    contributionIncomeAccountId = incomeAccountId?.toString(),
                    donationIncomeAccountId = donationAccountId?.toString(),
                    eventIncomeAccountId = eventAccountId?.toString(),
                    travelExpenseAccountId = travelAccountId?.toString(),
                    volunteerAllowanceAccountId = volunteerAccountId?.toString(),
                    receivablesAccountId = receivablesLedgerAccountId?.toString(),
                    payablesAccountId = payablesLedgerAccountId?.toString(),
                )
            if (beforeMapping != afterMapping) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(OrganizationSettingsPaymentMappingSnapshot.serializer(), beforeMapping),
                    after = Json.encodeToString(OrganizationSettingsPaymentMappingSnapshot.serializer(), afterMapping),
                )
            }

            // Security Round 2 (MAJOR, GoBD Nachvollziehbarkeit): isKleinunternehmer flips
            // AccountingService.vatActive() (vatEnabled && !isKleinunternehmer) exactly the same way
            // vatEnabled itself does -- from that moment on, new bookings are normalized to
            // UNCLASSIFIED/0.00, getVatReturnPreview reports applicable=false, and the
            // VAT_BEARING_ENTRY export blocker no longer fires. VatService.enableVat/disableVat
            // already audit their own side of this same gate; this is the missing other half --
            // separate from the beforeMapping/afterMapping entry above (deliberately -- see that
            // block's own KDoc for why this method does not audit its many purely administrative
            // fields) and, like vatEnabledSnapshotJson, deliberately a plain inline JSON string
            // rather than its own `@Serializable` snapshot type -- there is exactly one field that
            // ever changes here.
            if (wasKleinunternehmer != input.isKleinunternehmer) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = "{\"isKleinunternehmer\":$wasKleinunternehmer}",
                    after = "{\"isKleinunternehmer\":${input.isKleinunternehmer}}",
                )
            }

            loadOrganizationSettings().withInfrastructure()
        }
    }
}

/**
 * SHOULD-1 (2026-08-19): parses a payment-account-mapping id string, same
 * `runCatching { Uuid.parse(...) }.getOrElse { throw NotFoundException(...) }` convention
 * `ContributionService.toContributionUuid`/`AccountingService.toAccountingUuid` already establish
 * for "malformed id" -- a static input-shape problem, not a semantic-validity one (see
 * [requireValidPaymentAccountMapping] for that tier).
 */
private fun String.toPaymentAccountUuid(role: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $role LedgerAccount id: $this") }

/**
 * SHOULD-1 (2026-08-19): [accountId] (a `null` mapping is always valid -- see
 * [OrganizationSettingsDto.paymentBankAccountId] KDoc, an unconfigured mapping degrades
 * [ContributionPostingBridge] to a no-op rather than failing) must reference an existing, active,
 * non-cash-register [LedgerAccountTable] row of [expectedType]. Same "existing entity found but in
 * the wrong state/kind" [ConflictException] tier `AccountingService.requireActiveLedgerAccounts`
 * already uses. The cash-register rejection is this method's MAJOR-1 half (see
 * [ContributionPostingBridge]'s own "GoBD-Kassenbestands-Guard" KDoc for the runtime half):
 * `isCashRegister` accounts model a physical Kassenbuch till, never a bank/fee/income mapping
 * target, so rejecting one here makes the MAJOR-1 failure scenario unreachable by construction, in
 * ADDITION to (not instead of) the [CashRegisterGuard] runtime guard [ContributionPostingBridge]
 * now also applies.
 */
private fun requireValidPaymentAccountMapping(
    role: String,
    accountId: Uuid?,
    expectedType: LedgerAccountType,
) {
    if (accountId == null) return
    val row =
        LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            ?: throw NotFoundException("LedgerAccount $accountId ($role) not found")
    if (!row[LedgerAccountTable.active]) {
        throw ConflictException("LedgerAccount $accountId ($role) is not active")
    }
    if (row[LedgerAccountTable.isCashRegister]) {
        throw ConflictException(
            "LedgerAccount $accountId ($role) is a cash-register account (isCashRegister=true) and must not be used " +
                "as a payment-account mapping target -- see ContributionPostingBridge KDoc",
        )
    }
    if (row[LedgerAccountTable.type] != expectedType) {
        throw ConflictException("LedgerAccount $accountId ($role) must be of type $expectedType, got ${row[LedgerAccountTable.type]}")
    }
}

/**
 * Welle V1.4.22 Audit-Nachtrag (MAJOR-3): rejects a payment-account mapping that contradicts itself --
 * the bank account being the receivables or payables collective account (or those two being the same
 * account). [ConflictException], the same tier [requireValidPaymentAccountMapping] uses for "the
 * account exists but is wrong for this role"; the decision itself lives in
 * [paymentAccountMappingConflictOf] so the client's own pre-check cannot drift from it.
 *
 * The message names the conflict literal rather than the account ids: the client shows its own,
 * translated sentence (Kilua RPC never transmits this message anyway), and the ids would be noise in
 * the server log where the conflict is obvious from the literal.
 */
private fun requireConsistentPaymentAccountMapping(
    bankAccountId: Uuid?,
    receivablesAccountId: Uuid?,
    payablesAccountId: Uuid?,
) {
    val conflict =
        paymentAccountMappingConflictOf(
            PaymentAccountMapping(
                defaultBankAccountId = bankAccountId?.toString(),
                receivablesAccountId = receivablesAccountId?.toString(),
                payablesAccountId = payablesAccountId?.toString(),
            ),
        ) ?: return
    throw ConflictException(
        "Payment-account mapping is self-referential ($conflict) -- the payment bank account must differ from the " +
            "receivables and payables collective accounts",
    )
}

private fun loadOrganizationSettings(): OrganizationSettingsDto =
    OrganizationSettingsTable
        .selectAll()
        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
        .singleOrNull()
        ?.toOrganizationSettingsDto()
        ?: throw NotFoundException("OrganizationSettings row $ORGANIZATION_SETTINGS_ID not found -- baseline seed missing?")

/**
 * Single shared mapper for the whole codebase -- also reused by
 * [network.lapis.cloud.server.routes.registerMailmergeRoutes] (via its private
 * `loadOrganizationSettingsDto` wrapper) so a future field addition to [OrganizationSettingsDto]
 * only ever needs updating here, not duplicated field-by-field at every call site.
 */
fun ResultRow.toOrganizationSettingsDto(): OrganizationSettingsDto =
    OrganizationSettingsDto(
        id = this[OrganizationSettingsTable.id].toString(),
        name = this[OrganizationSettingsTable.name],
        street = this[OrganizationSettingsTable.street],
        postalCode = this[OrganizationSettingsTable.postalCode],
        city = this[OrganizationSettingsTable.city],
        country = this[OrganizationSettingsTable.country],
        bankIban = this[OrganizationSettingsTable.bankIban],
        bankBic = this[OrganizationSettingsTable.bankBic],
        taxExemptionAuthority = this[OrganizationSettingsTable.taxExemptionAuthority],
        taxExemptionDate = this[OrganizationSettingsTable.taxExemptionDate],
        isPoliticalParty = this[OrganizationSettingsTable.isPoliticalParty],
        postalMailEnabled = this[OrganizationSettingsTable.postalMailEnabled],
        politicianRankingEnabled = this[OrganizationSettingsTable.politicianRankingEnabled],
        auctionEnabled = this[OrganizationSettingsTable.auctionEnabled],
        auctionMaxValueLtr = this[OrganizationSettingsTable.auctionMaxValueLtr],
        sepaDebitEnabled = this[OrganizationSettingsTable.sepaDebitEnabled],
        paymentGatewayEnabled = this[OrganizationSettingsTable.paymentGatewayEnabled],
        paymentGatewayProvider = this[OrganizationSettingsTable.paymentGatewayProvider],
        paymentBankAccountId = this[OrganizationSettingsTable.paymentBankAccountId]?.toString(),
        paymentFeeAccountId = this[OrganizationSettingsTable.paymentFeeAccountId]?.toString(),
        contributionIncomeAccountId = this[OrganizationSettingsTable.contributionIncomeAccountId]?.toString(),
        // V1.2.7 Automatisiertes Mahnwesen -- read-only here, same treatment as sepaDebitEnabled
        // above. Settable ONLY via IDunningService.enableDunning/disableDunning.
        dunningEnabled = this[OrganizationSettingsTable.dunningEnabled],
        // V1.2.8 PSP-Checkout (Stripe) -- ordinary ADMIN-writable configuration, same tier as the
        // three V1.2.1 mapping fields above.
        donationIncomeAccountId = this[OrganizationSettingsTable.donationIncomeAccountId]?.toString(),
        // V1.4.3.1 Veranstaltungen (Review MAJOR fix) -- same tier again.
        eventIncomeAccountId = this[OrganizationSettingsTable.eventIncomeAccountId]?.toString(),
        eventIncomeSphere = this[OrganizationSettingsTable.eventIncomeSphere],
        // V1.4.5.2 DATEV-Format-Export -- ordinary ADMIN-writable configuration, same tier again.
        datevBeraterNummer = this[OrganizationSettingsTable.datevBeraterNummer],
        datevMandantNummer = this[OrganizationSettingsTable.datevMandantNummer],
        // V1.4.11 Reisekostenabrechnung -- ordinary ADMIN-writable configuration, same tier again.
        travelExpenseAccountId = this[OrganizationSettingsTable.travelExpenseAccountId]?.toString(),
        // V1.4.12 Übungsleiter- und Ehrenamtspauschale -- ordinary ADMIN-writable configuration, same tier again.
        volunteerAllowanceAccountId = this[OrganizationSettingsTable.volunteerAllowanceAccountId]?.toString(),
        // V1.4.13 USt-Voranmeldung -- vatEnabled read-only here (settable ONLY via IVatService
        // .enableVat/disableVat), isKleinunternehmer ordinary ADMIN-writable configuration.
        vatEnabled = this[OrganizationSettingsTable.vatEnabled],
        isKleinunternehmer = this[OrganizationSettingsTable.isKleinunternehmer],
        // V1.4.15 Kreditoren-/Debitorenbuchhaltung -- ordinary ADMIN-writable configuration,
        // same tier again. receivableDunningEnabled is likewise ordinary (NOT disclaimer-gated,
        // see OrganizationSettingsDto.receivableDunningEnabled KDoc).
        receivablesAccountId = this[OrganizationSettingsTable.receivablesAccountId]?.toString(),
        payablesAccountId = this[OrganizationSettingsTable.payablesAccountId]?.toString(),
        receivableDunningEnabled = this[OrganizationSettingsTable.receivableDunningEnabled],
        // V1.9.10 Mitgliederzahl-Sichtbarkeit -- ordinary ADMIN-writable configuration, same tier
        // again.
        showPublicMemberCount = this[OrganizationSettingsTable.showPublicMemberCount],
        // V1.9.124 -- read-only here, set only through updateNoiseSuppressionDefault.
        noiseSuppressionDefault = this[OrganizationSettingsTable.noiseSuppressionDefault],
        // V1.9.129 -- read-only here, set only through updateVideoOverlaysOffered.
        videoOverlaysOffered = this[OrganizationSettingsTable.videoOverlaysOffered],
        // V1.9.143 -- read-only here, set only through updatePulpitRecordingEnabled / updatePulpitRecordingRequireConfirmation.
        pulpitRecordingEnabled = this[OrganizationSettingsTable.pulpitRecordingEnabled],
        pulpitRecordingAcknowledgedNoticeVersion = this[OrganizationSettingsTable.pulpitRecordingNoticeVersion],
        pulpitRecordingRequireConfirmation = this[OrganizationSettingsTable.pulpitRecordingRequireConfirmation],
        // V1.9.144 -- read-only here, set only through updateMemberFinderEnabled.
        memberFinderEnabled = this[OrganizationSettingsTable.memberFinderEnabled],
        memberFinderAcknowledgedNoticeVersion = this[OrganizationSettingsTable.memberFinderNoticeVersion],
    )

/**
 * V1.9.124 -- the organisation's noise suppression default for the join answers of the video conferences. Must run inside a
 * `transaction {}`; a missing row (never happens, V1 seeds it) means "off".
 */
internal object NoiseSuppressionDefaultStore {
    fun load(): Boolean =
        OrganizationSettingsTable
            .select(OrganizationSettingsTable.noiseSuppressionDefault)
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.get(OrganizationSettingsTable.noiseSuppressionDefault)
            ?: false

    /**
     * For the join answers: opens its own transaction (joining one that is already open) and NEVER fails -- a join must not break because
     * a default could not be read; the safe answer is "off", today's behaviour.
     */
    fun currentValue(): Boolean = runCatching { transaction { load() } }.getOrDefault(false)
}

/**
 * V1.9.129 -- whether members may use video overlays. Must run inside a `transaction {}`; a missing row (never happens, V1 seeds it) means
 * "off".
 */
internal object VideoOverlaysOfferedStore {
    fun load(): Boolean =
        OrganizationSettingsTable
            .select(OrganizationSettingsTable.videoOverlaysOffered)
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.get(OrganizationSettingsTable.videoOverlaysOffered)
            ?: false

    /** For the join answers: opens its own transaction and NEVER fails -- the safe answer is "off", today's behaviour. */
    fun currentValue(): Boolean = runCatching { transaction { load() } }.getOrDefault(false)
}

/** V1.9.143 -- the three settings of the optional pulpit recording. */
internal data class PulpitRecordingSettings(
    val enabled: Boolean,
    /** The version of the operator notice acknowledged when the feature was switched on; `null` until then. */
    val noticeVersion: String?,
    val requireConfirmation: Boolean,
) {
    /** A new recording may start: the switch is on AND the CURRENT version of the notice was acknowledged. */
    fun startAllowed(): Boolean = enabled && noticeVersion == PULPIT_RECORDING_NOTICE_VERSION
}

/**
 * V1.9.143 -- the pulpit recording settings. Must run inside a `transaction {}`; a missing row (never happens, V1 seeds it) means "off". The OFF default
 * is also what [currentValue] answers when the database fails.
 */
internal object PulpitRecordingSettingsStore {
    private val OFF = PulpitRecordingSettings(enabled = false, noticeVersion = null, requireConfirmation = true)

    fun load(): PulpitRecordingSettings =
        OrganizationSettingsTable
            .select(
                OrganizationSettingsTable.pulpitRecordingEnabled,
                OrganizationSettingsTable.pulpitRecordingNoticeVersion,
                OrganizationSettingsTable.pulpitRecordingRequireConfirmation,
            ).where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.let {
                PulpitRecordingSettings(
                    enabled = it[OrganizationSettingsTable.pulpitRecordingEnabled],
                    noticeVersion = it[OrganizationSettingsTable.pulpitRecordingNoticeVersion],
                    requireConfirmation = it[OrganizationSettingsTable.pulpitRecordingRequireConfirmation],
                )
            } ?: OFF

    /** Opens its own transaction and NEVER fails -- the safe answer is "off". */
    fun currentValue(): PulpitRecordingSettings = runCatching { transaction { load() } }.getOrDefault(OFF)
}

private fun pulpitSnapshot(
    enabled: Boolean,
    version: String?,
    noticeSha256: String? = null,
): String =
    "{\"pulpitRecordingEnabled\":$enabled,\"pulpitRecordingNoticeVersion\":${if (version == null) "null" else "\"$version\""}" +
        (if (noticeSha256 == null) "" else ",\"noticeSha256\":\"$noticeSha256\"") + "}"

/** V1.9.144 -- the two settings of the optional member finder. */
internal data class MemberFinderSettings(
    val enabled: Boolean,
    /** The version of the operator notice acknowledged when the feature was switched on; `null` until then. */
    val noticeVersion: String?,
) {
    /** The finder works: the switch is on AND the CURRENT version of the notice was acknowledged. */
    fun active(): Boolean = enabled && noticeVersion == MEMBER_FINDER_NOTICE_VERSION
}

/**
 * V1.9.144 -- the member finder settings. Must run inside a `transaction {}`; a missing row (never happens, V1 seeds it) means "off". The OFF default is
 * also what [currentValue] answers when the database fails.
 */
internal object MemberFinderSettingsStore {
    private val OFF = MemberFinderSettings(enabled = false, noticeVersion = null)

    fun load(): MemberFinderSettings = loadWith(lock = false)

    /**
     * Reads the settings while holding the row lock of the single organization-settings row -- THE global lock of every finder write (decision E9, the
     * `PollService`/`AdminBootstrap` idiom). It serializes a switch-off against a request being sent, the inbox limit of 3 and the duplicate open pair
     * (a partial unique index is not H2-compatible). One lock only, so there is no lock-order problem among finder writers.
     */
    fun lockAndLoad(): MemberFinderSettings = loadWith(lock = true)

    private fun loadWith(lock: Boolean): MemberFinderSettings =
        OrganizationSettingsTable
            .select(OrganizationSettingsTable.memberFinderEnabled, OrganizationSettingsTable.memberFinderNoticeVersion)
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .let { if (lock) it.forUpdate() else it }
            .singleOrNull()
            ?.let {
                MemberFinderSettings(
                    enabled = it[OrganizationSettingsTable.memberFinderEnabled],
                    noticeVersion = it[OrganizationSettingsTable.memberFinderNoticeVersion],
                )
            } ?: OFF

    /** Opens its own transaction and NEVER fails -- the safe answer is "off". */
    fun currentValue(): MemberFinderSettings = runCatching { transaction { load() } }.getOrDefault(OFF)
}

private fun finderSnapshot(
    enabled: Boolean,
    version: String?,
    noticeSha256: String? = null,
): String =
    "{\"memberFinderEnabled\":$enabled,\"memberFinderNoticeVersion\":${if (version == null) "null" else "\"$version\""}" +
        (if (noticeSha256 == null) "" else ",\"noticeSha256\":\"$noticeSha256\"") + "}"
