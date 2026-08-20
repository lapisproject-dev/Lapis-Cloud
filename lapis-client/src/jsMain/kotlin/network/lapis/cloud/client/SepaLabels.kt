package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitExclusionReason
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaReturnReasonSets
import network.lapis.cloud.shared.domain.SepaSequenceType

/**
 * V1.2.2 SEPA-Client-UI wave -- German label/badge-color tables for every SEPA enum, same
 * `AccountingLabels.kt`/`ComplianceLabels.kt` grammar: `when` over `entries`, exhaustive,
 * `gettext(...)`. [SepaMandateStatus]/[SepaDebitBatchStatus]/[SepaDebitItemStatus] progress over
 * their own lifetime -- rendered with [statusBadge]'s filled grammar wherever a screen shows them.
 * [SepaSequenceType]/[SepaReturnReason]/[SepaDebitExclusionReason] are fixed classifications, not
 * lifecycle status -- rendered with [typeBadge]'s outline grammar (see `StatusBadge.kt` KDoc for
 * the fill-vs-no-fill rule this file inherits unchanged).
 */
fun sepaMandateStatusLabel(status: SepaMandateStatus): String =
    when (status) {
        SepaMandateStatus.ACTIVE -> gettext("Aktiv")
        SepaMandateStatus.REVOKED -> gettext("Widerrufen")
        SepaMandateStatus.EXPIRED -> gettext("Abgelaufen")
    }

fun sepaMandateStatusColor(status: SepaMandateStatus): String =
    when (status) {
        SepaMandateStatus.ACTIVE -> "success"
        SepaMandateStatus.REVOKED -> "danger"
        SepaMandateStatus.EXPIRED -> "secondary"
    }

fun sepaBatchStatusLabel(status: SepaDebitBatchStatus): String =
    when (status) {
        SepaDebitBatchStatus.DRAFT -> gettext("Entwurf")
        SepaDebitBatchStatus.NOTIFIED -> gettext("Angekündigt")
        SepaDebitBatchStatus.GENERATED -> gettext("Datei erzeugt")
        SepaDebitBatchStatus.SUBMITTED -> gettext("Eingereicht")
        SepaDebitBatchStatus.SETTLED -> gettext("Abgerechnet")
        SepaDebitBatchStatus.CANCELLED -> gettext("Storniert")
    }

fun sepaBatchStatusColor(status: SepaDebitBatchStatus): String =
    when (status) {
        SepaDebitBatchStatus.DRAFT -> "secondary"
        SepaDebitBatchStatus.NOTIFIED -> "info"
        SepaDebitBatchStatus.GENERATED -> "primary"
        SepaDebitBatchStatus.SUBMITTED -> "warning"
        SepaDebitBatchStatus.SETTLED -> "success"
        SepaDebitBatchStatus.CANCELLED -> "danger"
    }

fun sepaItemStatusLabel(status: SepaDebitItemStatus): String =
    when (status) {
        SepaDebitItemStatus.PENDING -> gettext("Ausstehend")
        SepaDebitItemStatus.SETTLEABLE -> gettext("Abrechenbar")
        SepaDebitItemStatus.SETTLED -> gettext("Abgerechnet")
        SepaDebitItemStatus.RETURNED -> gettext("Rückgelastschrift")
        SepaDebitItemStatus.CANCELLED -> gettext("Storniert")
    }

fun sepaItemStatusColor(status: SepaDebitItemStatus): String =
    when (status) {
        SepaDebitItemStatus.PENDING -> "secondary"
        SepaDebitItemStatus.SETTLEABLE -> "info"
        SepaDebitItemStatus.SETTLED -> "success"
        SepaDebitItemStatus.RETURNED -> "danger"
        SepaDebitItemStatus.CANCELLED -> "dark"
    }

fun sepaSequenceTypeLabel(type: SepaSequenceType): String =
    when (type) {
        SepaSequenceType.FRST -> gettext("Erstmalig (FRST)")
        SepaSequenceType.RCUR -> gettext("Wiederkehrend (RCUR)")
        SepaSequenceType.OOFF -> gettext("Einmalig (OOFF)")
        SepaSequenceType.FNAL -> gettext("Letztmalig (FNAL)")
    }

fun sepaSequenceTypeColor(type: SepaSequenceType): String =
    when (type) {
        SepaSequenceType.FRST -> "primary"
        SepaSequenceType.RCUR -> "secondary"
        SepaSequenceType.OOFF -> "info"
        SepaSequenceType.FNAL -> "dark"
    }

/** Deutscher Klartext der ISO-20022-R-Transaktionscodes, jeweils mit dem Code selbst in Klammern
 * (nie nur der bloße Code -- ein Schatzmeister ohne ISO-20022-Kenntnis muss den Grund verstehen,
 * ohne nachzuschlagen). */
fun sepaReturnReasonLabel(reason: SepaReturnReason): String =
    when (reason) {
        SepaReturnReason.AC01 -> gettext("Kontonummer ungültig (AC01)")
        SepaReturnReason.AC04 -> gettext("Konto aufgelöst (AC04)")
        SepaReturnReason.AC06 -> gettext("Konto gesperrt (AC06)")
        SepaReturnReason.AC13 -> gettext("Kontoinhaber-Angaben ungültig (AC13)")
        SepaReturnReason.AG01 -> gettext("Transaktion untersagt (AG01)")
        SepaReturnReason.AM04 -> gettext("Unzureichende Kontodeckung (AM04)")
        SepaReturnReason.MD01 -> gettext("Kein gültiges Mandat (MD01)")
        SepaReturnReason.MD06 -> gettext("Widerspruch des Zahlungspflichtigen (MD06)")
        SepaReturnReason.MD07 -> gettext("Zahlungspflichtiger verstorben (MD07)")
        SepaReturnReason.MS02 -> gettext("Vom Zahlungspflichtigen ohne Grundangabe abgelehnt (MS02)")
        SepaReturnReason.MS03 -> gettext("Grund bankseitig nicht spezifiziert (MS03)")
        SepaReturnReason.SL01 -> gettext("Aus rechtlichen Gründen zurückgewiesen (SL01)")
        SepaReturnReason.OTHER -> gettext("Sonstiger Grund (siehe Freitext)")
    }

/** Set-getrieben, nicht Literal-getrieben (Testplan §5): eine spätere Erweiterung von
 * [SepaReturnReasonSets.FORCES_MANDATE_REVOCATION] im Backend zieht diese Farbe automatisch mit,
 * ohne dass dieses `when` angefasst werden muss. */
fun sepaReturnReasonColor(reason: SepaReturnReason): String =
    if (reason in SepaReturnReasonSets.FORCES_MANDATE_REVOCATION) "danger" else "warning"

fun sepaExclusionReasonLabel(reason: SepaDebitExclusionReason): String =
    when (reason) {
        SepaDebitExclusionReason.NO_ACTIVE_MANDATE -> gettext("Kein aktives Mandat")
        SepaDebitExclusionReason.ALREADY_IN_FLIGHT -> gettext("Bereits in einem laufenden Lastschriftlauf")
        SepaDebitExclusionReason.MEMBER_NOT_ACTIVE -> gettext("Mitglied nicht aktiv")
        SepaDebitExclusionReason.AMOUNT_NOT_POSITIVE -> gettext("Betrag nicht positiv")
        SepaDebitExclusionReason.MANDATE_EXPIRED -> gettext("Mandat abgelaufen")
    }

fun sepaExclusionReasonColor(reason: SepaDebitExclusionReason): String =
    when (reason) {
        SepaDebitExclusionReason.NO_ACTIVE_MANDATE -> "warning"
        SepaDebitExclusionReason.ALREADY_IN_FLIGHT -> "info"
        SepaDebitExclusionReason.MEMBER_NOT_ACTIVE -> "secondary"
        SepaDebitExclusionReason.AMOUNT_NOT_POSITIVE -> "danger"
        SepaDebitExclusionReason.MANDATE_EXPIRED -> "warning"
    }

/** Reine Anzeigefunktion -- masks every digit but the last four with `•`. [last4] is already
 * exactly 4 characters server-side ([network.lapis.cloud.server.payment.sepa.IbanValidator.last4]),
 * but this does not assume that -- an unexpected length still renders something reasonable rather
 * than throwing. */
fun formatIbanLast4(last4: String): String = "•••• $last4"

/** Kare/Rams-Kompromiss K10: der deutsche Klartext trägt die Bedeutung, der kleine graue
 * Monospace-Code dahinter bleibt für alle nachschlagbar, die den ISO-20022-Code selbst kennen
 * (z. B. beim Abgleich mit einem camt.054-Kontoauszug). */
fun Container.sepaReturnReasonBadge(reason: SepaReturnReason): Span =
    span {
        addCssClasses("d-inline-flex align-items-center gap-1")
        typeBadge(sepaReturnReasonLabel(reason), sepaReturnReasonColor(reason))
        span(reason.name) { addCssClasses("text-muted small font-monospace") }
    }
