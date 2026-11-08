package network.lapis.cloud.server.partg.report

/** V1.9.256 -- the fixed German texts of the exported report draft. No server-side gettext: the document is one language by design. */
internal object PartgReportTexts {
    const val FEDERAL = "Bundesverband"
    const val STATE_PREFIX = "Landesverband"
    const val SUBORDINATE_PREFIX = "Nachgeordnete Gebietsverbände in"
    const val SUBORDINATE_SUFFIX = "(zusammengefasst)"
    const val WHOLE_PARTY = "Summe der Einheiten (nicht konsolidiert)"
    const val LEVEL_FEDERAL = "Bundesverband"
    const val LEVEL_STATE = "Landesverbände (Summe)"
    const val LEVEL_SUBORDINATE = "Nachgeordnete Gebietsverbände (Summe)"

    const val NOT_ENTERED = "n. e."
    const val NOT_COMPUTABLE = "n. b."
    const val INCOMPLETE_SHORT = "(unvollst.)"
    const val NOT_ENTERED_LONG = "nicht erfasst"
    const val NOT_COMPUTABLE_LONG = "nicht berechenbar"
    const val INCOMPLETE_LONG = "unvollständig"

    const val TITLE = "Rechenschaftsbericht"
    const val DRAFT_MARK = "ENTWURF"
    const val HEADER_SUFFIX = "ENTWURF – nicht amtlich"
    const val WATERMARK = "ENTWURF"

    const val COMPLETENESS = "Vollständigkeit"
    const val SUMMARY = "Zusammenfassung (§ 24 Abs. 9 PartG)"
    const val INCOME = "Einnahmenrechnung (§ 24 Abs. 4 PartG)"
    const val EXPENSES = "Ausgabenrechnung (§ 24 Abs. 5 PartG)"
    const val ASSETS = "Vermögensbilanz (§ 24 Abs. 6 PartG)"
    const val BY_LEVEL = "Ergebnis je Ebene"
    const val NOTES = "Hinweise"

    const val SUBSIDY_NOTE =
        "Einschließlich innerparteilicher Zuschüsse (Einnahmen Nr. 10, Ausgaben Nr. 3), nicht bereinigt. Die Bereinigung steht im " +
            "Abschnitt Konsolidierung des Gesamtberichts."
    const val DRAFT_NOTICE =
        "Dies ist ein Entwurf als Arbeitshilfe. Er ist nicht amtlich, nicht geprüft und ersetzt weder den geprüften " +
            "Rechenschaftsbericht noch eine Rechtsberatung."
    const val LEGEND =
        "n. e. = nicht erfasst · n. b. = nicht berechenbar · (unvollst.) = nicht alle Positionen erfasst · Beträge in Euro"
    const val MATRIX_STAR = "* = unvollständig, nicht alle Positionen erfasst"

    const val COL_NUMBER = "Nr."
    const val COL_POSITION = "Position"
    const val COL_UNIT = "Gliederung"
    const val COL_CURRENT = "Berichtsjahr EUR"
    const val COL_PRIOR = "Vorjahr EUR"
    const val COL_PERCENT = "%"
    const val COL_NOTE = "Hinweis"

    const val ALL_COMPLETE = "Alle Positionen sind erfasst."

    // V1.9.269 (R3)
    const val ANNEX_A = "Gesonderter Ausweis A: Abzüge bei der Spendenbehandlung (§ 25 PartG)"
    const val ANNEX_B = "Gesonderter Ausweis B: Spenden und Beiträge über 10.000 EUR je Person und Jahr (§ 25 Abs. 3 PartG)"
    const val ANNEX_E = "Mitgliederzahl zum 31.12."
    const val NOTES_TITLE = "Erläuterungen"
    const val NO_ENTRIES = "Keine Einträge erfasst"
    const val DEDUCTION_UNASSIGNABLE = "Nicht zuordenbare Spenden"
    const val DEDUCTION_CASH = "Bar-Spenden über 1.000 EUR"
    const val DEDUCTION_OVER_3300 = "Spenden über 3.300 EUR ohne Ausweis"
    const val DEDUCTION_WRONG = "Fälschlich gemeldete Spenden"
    const val COL_PERSON_KIND = "Art"
    const val COL_NAME = "Name"
    const val COL_ADDRESS = "Anschrift"
    const val COL_COUNTRY = "Land"
    const val COL_CONTRIBUTION = "Zuwendung"
    const val COL_ROW = "Zeile"
    const val COL_AMOUNT = "Betrag EUR"
    const val COL_MEMBERS = "Mitglieder"
    const val PERSON_NATURAL = "natürlich"
    const val PERSON_LEGAL = "juristisch"
    const val CONTRIBUTION_DONATION = "Spende"
    const val CONTRIBUTION_FEE = "Beitrag"
    const val CONTRIBUTION_BOTH = "Spende und Beitrag"
    const val B_NOTE =
        "Aufgenommen sind nur Zeilen über 10.000,00 EUR je Gebietsverband und Jahr. Beträge einer Person an mehrere Gebietsverbände " +
            "werden hier nicht zusammengeführt; die Zusammenführung steht im Abschnitt Konsolidierung des Gesamtberichts."
    const val E_NOTE = "Die Mitgliederzahl wird je Gliederung erfasst und nicht addiert."
    const val E_NOTE_ALL = "Bei der Summe der Einheiten steht der Wert des Bundesverbands."
    const val NOTE_GENERAL = "Allgemeine Angaben zur Rechnungslegung"
    const val NOTE_RELIEF = "Erleichterungen nach § 28 PartG"
    const val NOTE_HOLDINGS = "Beteiligungen"
    const val NOTE_MEDIA = "Medienerzeugnisse"
    const val NOTE_REAL_ESTATE = "Bewertung von Grundstücken"
    const val RELIEF_ABS1 = "Abs. 1"
    const val RELIEF_ABS3 = "Abs. 3"
    const val YES = "ja"
    const val NO = "nein"
    const val UNSPECIFIED = "nicht angegeben"
    const val HOLDING_NAME = "Unternehmen"
    const val HOLDING_SEAT = "Sitz"
    const val HOLDING_SHARE = "Anteil %"
    const val HOLDING_CAPITAL = "Kapital EUR"
    const val HOLDING_RESULT = "Ergebnis EUR"
    const val FINDING_COL_CODE = "Hinweis"
    const val FINDING_COL_LEVEL = "Stufe"
    const val FINDING_COL_UNIT = "Gliederung"
    const val FINDING_COL_ROWS = "Zeilen"
    const val FINDING_COL_POSITION = "Position"
    const val FINDING_COL_TEXT = "Bedeutung"
    const val LEVEL_CHECK = "Bitte prüfen"
    const val LEVEL_INFO = "Zur Info"
    const val ORGANIZATION = "Organisation"
    const val FINDINGS_NONE = "Keine Hinweise."

    // V1.9.283 (R3b): the headings carry no section number on purpose (the official layout is not verified)
    const val ANNEX_C = "Gesonderter Ausweis C: Sponsoring (Entwurf)"
    const val ANNEX_D = "Gesonderter Ausweis D: Unternehmenstätigkeit (Entwurf)"
    const val D_NOTE = "Eigene Geschäftsbetriebe der Gliederung. Beteiligungen stehen unter Erläuterungen."
    const val D_COMPLETENESS = "Ausweis D: %d Unternehmen erfasst"
    const val COL_LEGAL_FORM = "Rechtsform"
    const val COL_SEAT = "Sitz"
    const val COL_SHARE = "Anteil %"
    const val COL_TOTAL = "Summe"
    const val ANNEX_F = "Gesonderter Ausweis F: Jugendorganisationen (Entwurf)"
    const val C_NOTE =
        "Aufgenommen ist ein Sponsor mit einer Leistung über 750,00 EUR oder mit mehr als 6.000,00 EUR im Jahr bei dieser Gliederung. " +
            "Einzeln aufgeführt sind Leistungen über 750,00 EUR. Leistungen an mehrere Gliederungen werden nicht zusammengeführt."
    const val COL_YEARLY_SUM = "Jahressumme EUR"
    const val COL_ENTRY_COUNT = "Anzahl Leistungen"
    const val COL_REASON = "Grund"
    const val COL_DATE = "Datum"
    const val COL_PURPOSE = "Zweck"
    const val COL_RELATIONSHIP = "Verhältnis zur Partei"
    const val COL_INCOME = "Einnahmen EUR"
    const val COL_EXPENSES = "Ausgaben EUR"
    const val COL_RESULT = "Ergebnis EUR"
    const val REASON_SINGLE = "Einzelleistung"
    const val REASON_YEARLY = "Jahressumme"
    const val REASON_BOTH = "beides"

    fun reason(q: network.lapis.cloud.shared.domain.PartgSponsorQualification): String =
        when (q) {
            network.lapis.cloud.shared.domain.PartgSponsorQualification.SINGLE -> REASON_SINGLE
            network.lapis.cloud.shared.domain.PartgSponsorQualification.YEARLY -> REASON_YEARLY
            network.lapis.cloud.shared.domain.PartgSponsorQualification.BOTH -> REASON_BOTH
            network.lapis.cloud.shared.domain.PartgSponsorQualification.NONE -> ""
        }

    fun finding(code: network.lapis.cloud.shared.domain.PartgFindingCode): String =
        when (code) {
            network.lapis.cloud.shared.domain.PartgFindingCode.P01 -> "Anschrift fehlt vollständig"
            network.lapis.cloud.shared.domain.PartgFindingCode.P02 -> "Anschrift unvollständig"
            network.lapis.cloud.shared.domain.PartgFindingCode.P03 -> "Bar-Spenden über 1.000 EUR erfasst"
            network.lapis.cloud.shared.domain.PartgFindingCode.P04 -> "Juristische Person"
            network.lapis.cloud.shared.domain.PartgFindingCode.P05 -> "Deutliche Abweichung zum Vorjahr"
            network.lapis.cloud.shared.domain.PartgFindingCode.P06 -> "Angabe fehlt"
            network.lapis.cloud.shared.domain.PartgFindingCode.P07 -> "Anschrift fehlt vollständig (Sponsor)"
            network.lapis.cloud.shared.domain.PartgFindingCode.P08 -> "Anschrift unvollständig (Sponsor)"
            network.lapis.cloud.shared.domain.PartgFindingCode.P09 -> "Sponsor unter beiden Grenzen, nicht im Bericht"
            network.lapis.cloud.shared.domain.PartgFindingCode.P10 -> "Jugendorganisation ohne Zahlen"
            network.lapis.cloud.shared.domain.PartgFindingCode.P11 -> "Unternehmen weichen von Position ab"
            network.lapis.cloud.shared.domain.PartgFindingCode.P12 -> "Unternehmen ohne vollständige Zahlen"
            network.lapis.cloud.shared.domain.PartgFindingCode.P13 -> "Position erfasst, aber kein Unternehmen in Ausweis D"
            network.lapis.cloud.shared.domain.PartgFindingCode.P14 -> "Konten mit Buchungen ohne Zuordnung zu einer Position"
            network.lapis.cloud.shared.domain.PartgFindingCode.P15 -> "Buchungen vorhanden, Buchführung steht auf Modus C"
            network.lapis.cloud.shared.domain.PartgFindingCode.P16 -> "Wert aus Buchungen weicht vom aktuellen Vorschlag ab"
            network.lapis.cloud.shared.domain.PartgFindingCode.P17 -> "Übertragungen weichen vom Wert der Position ab"
            network.lapis.cloud.shared.domain.PartgFindingCode.P18 -> "Übertragung nur einseitig oder Einheit ohne Daten"
            network.lapis.cloud.shared.domain.PartgFindingCode.P19 -> "Position erfasst, aber keine Übertragung eingetragen"
            network.lapis.cloud.shared.domain.PartgFindingCode.P20 -> "Mögliche Zusammenführungen von Großspendern"
            network.lapis.cloud.shared.domain.PartgFindingCode.P21 -> "Zusammenführung von Großspendern prüfen"
        }

    // V1.9.290 (R5): the consolidated whole party. A draft, like everything else in this document.
    const val CONSOLIDATION_TITLE = "Gesamtpartei (konsolidiert) – Entwurf, nicht geprüft"
    const val CONSOLIDATION_NOTE =
        "Konsolidiert heißt: Übertragungen zwischen den Gliederungen sind herausgerechnet, soweit beide Seiten erfasst sind. " +
            "Nicht berechenbare Werte (n. b.) sind nicht 0. Dies ist kein amtlicher Bericht."
    const val CONSOLIDATION_INCLUDED = "Einbezogene Gliederungen"
    const val CONSOLIDATION_EXCLUDED = "Nicht einbezogen"
    const val CONSOLIDATION_REASON_NO_DATA = "keine Daten"
    const val CONSOLIDATION_REASON_BOOKINGS = "Buchungen vorhanden, nicht übernommen"
    const val CONSOLIDATION_PRIOR_NOTE =
        "Vorjahr: Für das Vorjahr sind keine Übertragungen erfasst; die Spalte Vorjahr ist nicht bereinigt."
    const val COL_UNIT_SUM = "Summe der Einheiten EUR"
    const val COL_ELIMINATED = "Eliminiert EUR"
    const val COL_CONSOLIDATED = "Konsolidiert EUR"
    const val RECONCILIATION = "Abgleich der Übertragungen"
    const val COL_KIND = "Art"
    const val COL_PAYER = "Zahler"
    const val COL_PAYEE = "Empfänger"
    const val COL_PAYER_AMOUNT = "Betrag Zahler EUR"
    const val COL_PAYEE_AMOUNT = "Betrag Empfänger EUR"
    const val COL_STATE = "Stand"
    const val COL_DIFFERENCE = "Differenz EUR"
    const val KIND_SUBSIDY = "Zuschuss"
    const val KIND_BALANCE = "Forderung/Verbindlichkeit"
    const val MATCH_MATCH = "abgeglichen"
    const val MATCH_DIFFERS = "weicht ab"
    const val MATCH_MISSING = "Gegenseite fehlt"
    const val UNMATCHED_TITLE = "Nicht abgeglichen"
    const val ANNEX_B_CONSOLIDATED = "Ausweis B (konsolidiert): Spenden und Beiträge über 10.000 EUR je Person und Jahr (Entwurf)"
    const val COL_UNITS = "Gliederungen"
    const val B_CONSOLIDATED_NOTE =
        "Zusammengeführt sind nur Zeilen, die der Vorstand von Hand zusammengeführt hat. " +
            "Teilbeträge unter 10.000,00 EUR je Gliederung werden nicht erfasst; ein Fall wie 6.000 + 6.000 EUR bleibt unerkannt. " +
            "Ob die Schwelle für die ganze Partei gilt, ist offen."
    const val SHEET_CONSOLIDATION = "Konsolidierung"
    const val SHEET_RECONCILIATION = "Abgleich"
    const val SHEET_B_CONSOLIDATED = "Ausweis B (konsolidiert)"

    // V1.9.292 (R4b): the unit part a chapter treasurer downloads
    const val UNIT_PART_FOOTER = "Entwurf · nicht geprüft · kein amtliches Formular"
    const val SUBSIDY_NOTE_UNIT = "Einschließlich innerparteilicher Zuschüsse (Einnahmen Nr. 10, Ausgaben Nr. 3), nicht bereinigt."

    fun unitPartTitle(
        unitLabel: String,
        year: Int,
    ): String = "Teilbericht $unitLabel $year – Entwurf"

    // V1.9.291 (R6): the footer line of every exported page / sheet
    const val WORKING_STATE_FOOTER = "Arbeitsstand, keine festgehaltene Version"

    /**
     * The footer of a KEPT version: number, day it was kept, the first 12 characters of the checksum and the status. [final] only means the
     * internal mark; the sentence never says the report was filed.
     */
    fun versionFooter(
        versionNumber: Int,
        keptOn: String,
        hash12: String,
        final: Boolean,
    ): String =
        "Version $versionNumber · festgehalten $keptOn · Prüfsumme $hash12 · " +
            if (final) "Intern final, nicht eingereicht" else "Entwurf als Arbeitshilfe, nicht eingereicht"
}
