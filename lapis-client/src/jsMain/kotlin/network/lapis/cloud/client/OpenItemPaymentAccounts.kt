package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.PaymentAccountMappingConflict
import network.lapis.cloud.shared.domain.PaymentCapableAccounts
import network.lapis.cloud.shared.domain.paymentAccountMappingConflictOf

/**
 * Welle V1.4.22 "Zahlungskonto im Offene-Posten-Pfad" -- DOM-freie Entscheidung, WELCHE Konten der
 * Ausgleichen-Dialog überhaupt anbietet und ob die Auswahl ein Pflichtfeld ist. Zwei Bedienfehler,
 * beide live auf Staging gefunden:
 *
 * 1. Die Liste enthielt **alle** ASSET-Konten, also auch `06500 Betriebs- und Geschäftsausstattung`
 *    und `12000 Forderungen aus Lieferungen und Leistungen` -- das Forderungskonto selbst. Der
 *    Filter kommt jetzt aus [PaymentCapableAccounts] (dieselbe Regel, die der Server prüft), nicht
 *    aus einem zweiten, hier erfundenen Typfilter.
 * 2. `"(Standard-Bankkonto der Organisation)"` war vorausgewählt, **auch wenn gar keines
 *    hinterlegt war** -- der Ausgleich scheiterte dann server-seitig, und der Grund war im
 *    generischen Konflikt-Toast nicht zu sehen. Ist kein Standardkonto hinterlegt, gibt es diese
 *    Option nicht mehr ([SettlementBankChoice.selectionRequired]).
 *
 * **Audit-Nachtrag (MAJOR-1/MAJOR-2), beides Sackgassen-Vermeidung:**
 *
 * - Die Standard-Option gilt nur als gültig, wenn das hinterlegte Konto in der (aktiven!)
 *   Kontenliste auch **benutzbar** ist ([SettlementBankChoice.defaultBankAccountConfigured]).
 *   Wurde es inzwischen deaktiviert -- `deactivateLedgerAccount` verhinderte das bis V1.4.22 nicht
 *   -- oder zeigt die Zuordnung auf ein Sammelkonto, dann gäbe es sonst eine vorausgewählte Option,
 *   die der Server jedes Mal ablehnt, ohne Ausweg im Dialog.
 * - Solange Kontenliste ODER Zuordnung nicht vorliegen ([SettlementBankChoice.contextKnown] `false`),
 *   wird **keine halb gefilterte Liste** angeboten: ohne die Zuordnung ist nicht bekannt, welches
 *   Konto das Forderungskonto ist (es ist Kontenklasse 1 wie ein Bankkonto, der Klassenfilter
 *   schließt es also nicht aus). Der Dialog sperrt das Select und sagt, was zu tun ist; der Screen
 *   startet den Abruf beim Öffnen erneut, sodass der nächste Versuch die echte Liste hat.
 *
 * Die Aussage "kein Standardkonto hinterlegt" darf nur fallen, wenn sie wirklich bekannt ist -- genau
 * die Haltung, die `renderDunningDisabledBand` für seinen Schalter schon einnimmt.
 */
internal data class SettlementBankChoice(
    /** Zahlungsfähige Konten in Anzeigereihenfolge; leer, solange [contextKnown] `false` ist. */
    val eligible: List<LedgerAccountDto>,
    /** `true` = es gibt ein hinterlegtes Standard-Bankkonto, und es ist benutzbar. */
    val defaultBankAccountConfigured: Boolean,
    /** `false` = Kontenliste oder Organisationseinstellungen liegen (noch) nicht vor. */
    val contextKnown: Boolean,
    /** Id des hinterlegten Standard-Bankkontos, falls [defaultBankAccountConfigured]. */
    val defaultBankAccountId: String? = null,
) {
    /** Ohne benutzbares Standardkonto gibt es keine gültige Vorauswahl -- der Dialog verlangt eine Wahl. */
    val selectionRequired: Boolean get() = contextKnown && !defaultBankAccountConfigured

    /** Pflichtauswahl, aber es gibt nichts zu wählen: nur ein Administrator kann das beheben. */
    val hasNoChoiceAtAll: Boolean get() = selectionRequired && eligible.isEmpty()
}

/** Die Zuordnung, gegen die [settlementBankChoice] entscheidet -- aus den Organisationseinstellungen. */
internal fun OrganizationSettingsDto.toPaymentAccountMapping(): PaymentAccountMapping =
    PaymentAccountMapping(
        defaultBankAccountId = paymentBankAccountId,
        receivablesAccountId = receivablesAccountId,
        payablesAccountId = payablesAccountId,
    )

/**
 * [accounts] ist die **aktive** Kontenliste (`listLedgerAccounts(activeOnly = true)`); eine leere
 * Liste heißt deshalb "noch nicht geladen", nicht "es gibt keine Konten" -- ein Kontenplan ohne jedes
 * Konto ist in dieser Anwendung kein erreichbarer Zustand (die Buchhaltung wäre komplett leer).
 */
internal fun settlementBankChoice(
    accounts: List<LedgerAccountDto>,
    mapping: PaymentAccountMapping?,
): SettlementBankChoice {
    if (mapping == null || accounts.isEmpty()) {
        return SettlementBankChoice(eligible = emptyList(), defaultBankAccountConfigured = false, contextKnown = false)
    }
    val eligible = PaymentCapableAccounts.paymentCapableAccounts(accounts = accounts, mapping = mapping)
    // Das hinterlegte Konto muss in der aktiven Liste stehen UND die Zahlungskonto-Regel bestehen:
    // `paymentCapableAccounts` nimmt das Standardkonto bewusst von der Kontenklassen-Prüfung aus,
    // lehnt es aber weiterhin als Sammelkonto ab -- genau die zwei Fälle, die sonst eine
    // vorausgewählte, immer scheiternde Option ergeben hätten.
    val usableDefaultId = mapping.defaultBankAccountId?.takeIf { id -> eligible.any { it.id == id } }
    return SettlementBankChoice(
        eligible = eligible,
        defaultBankAccountConfigured = usableDefaultId != null,
        contextKnown = true,
        defaultBankAccountId = usableDefaultId,
    )
}

/**
 * `null` = die Auswahl ist gültig, sonst der (bereits übersetzte) Fehlertext. Leere Auswahl heißt
 * "Standard-Bankkonto der Organisation" -- das ist nur gültig, wenn es ein benutzbares gibt.
 */
internal fun settlementBankAccountProblem(
    selected: String?,
    choice: SettlementBankChoice,
): String? {
    if (!selected.isNullOrBlank()) return null
    if (!choice.selectionRequired) return null
    if (choice.hasNoChoiceAtAll) return noPaymentAccountAvailableMessage()
    return tr("Bitte ein Bankkonto wählen — für die Organisation ist kein Standard-Bankkonto hinterlegt.")
}

/** Dezenter Hinweis im Dialog, sobald die Auswahl zum Pflichtfeld geworden ist. */
internal fun missingDefaultBankAccountHint(choice: SettlementBankChoice): String =
    if (choice.hasNoChoiceAtAll) {
        noPaymentAccountAvailableMessage()
    } else {
        tr(
            "Für die Organisation ist kein Standard-Bankkonto hinterlegt. Ein Administrator kann im Kontenplan " +
                "unter „Kontenzuordnung Zahlungsverkehr\" ein Bankkonto zuordnen.",
        )
    }

/**
 * Hinweis, solange [SettlementBankChoice.contextKnown] `false` ist. Sagt bewusst auch, was hilft: der
 * Screen stößt den Abruf beim Öffnen des Dialogs neu an, ein erneutes Öffnen hat die Liste dann.
 */
internal fun paymentAccountsUnknownHint(): String =
    tr(
        "Konten und Zahlungskonto-Zuordnung sind noch nicht geladen. Es wird das Standard-Bankkonto der " +
            "Organisation verwendet; für eine Auswahl bitte den Dialog erneut öffnen.",
    )

/**
 * Beschriftung einer Konten-Option. Das hinterlegte Standardkonto wird gekennzeichnet -- es steht
 * sonst zweimal zur Wahl (als leere Standard-Option und als eigener Listeneintrag), ohne dass zu
 * sehen ist, dass beides dasselbe Konto bucht (Audit-Nachtrag MINOR-c).
 */
internal fun settlementBankOptionLabel(
    account: LedgerAccountDto,
    choice: SettlementBankChoice,
): String =
    if (account.id == choice.defaultBankAccountId) {
        gettext("%1 · %2 (Standard)", account.accountNumber, account.name)
    } else {
        "${account.accountNumber} · ${account.name}"
    }

private fun noPaymentAccountAvailableMessage(): String =
    tr(
        "Kein Zahlungskonto (Bank oder Kasse) im Kontenplan gefunden. Ein Administrator muss ein Bank- oder " +
            "Kassenkonto anlegen und im Kontenplan zuordnen.",
    )

/**
 * Welle V1.4.22 Audit-Nachtrag (MAJOR-3): Klartext zu einer widersprüchlichen Kontenzuordnung, bevor
 * `updateOrganizationSettings` sie mit `ConflictException` ablehnt -- dessen Meldung erreicht den
 * Browser nie (Kilua RPC überträgt nur den Ausnahmetyp), der Kontenplan-Bildschirm zeigte also den
 * generischen "steht im Konflikt"-Toast für einen reinen Eingabefehler. Entscheidungsregel ist
 * [paymentAccountMappingConflictOf], damit Client und Server nicht auseinanderlaufen.
 *
 * `null` = keine Widersprüche.
 */
internal fun paymentAccountMappingProblem(mapping: PaymentAccountMapping): String? =
    when (paymentAccountMappingConflictOf(mapping)) {
        PaymentAccountMappingConflict.BANK_IS_RECEIVABLES ->
            tr("Das Bankkonto darf nicht dasselbe Konto wie das Forderungskonto (Debitoren) sein.")
        PaymentAccountMappingConflict.BANK_IS_PAYABLES ->
            tr("Das Bankkonto darf nicht dasselbe Konto wie das Verbindlichkeitenkonto (Kreditoren) sein.")
        PaymentAccountMappingConflict.RECEIVABLES_IS_PAYABLES ->
            tr("Forderungskonto (Debitoren) und Verbindlichkeitenkonto (Kreditoren) müssen verschiedene Konten sein.")
        null -> null
    }
