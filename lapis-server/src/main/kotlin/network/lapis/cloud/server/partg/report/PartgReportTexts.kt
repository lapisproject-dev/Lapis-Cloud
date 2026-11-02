package network.lapis.cloud.server.partg.report

/** V1.9.256 -- the fixed German texts of the exported report draft. No server-side gettext: the document is one language by design. */
internal object PartgReportTexts {
    const val FEDERAL = "Bundesverband"
    const val STATE_PREFIX = "Landesverband"
    const val SUBORDINATE_PREFIX = "Nachgeordnete Gebietsverbände in"
    const val SUBORDINATE_SUFFIX = "(zusammengefasst)"
    const val WHOLE_PARTY = "Gesamtpartei"
    const val LEVEL_FEDERAL = "Bundesverband"
    const val LEVEL_STATE = "Landesverbände (Summe)"
    const val LEVEL_SUBORDINATE = "Nachgeordnete Gebietsverbände (Summe)"

    const val NOT_ENTERED = "n. e."
    const val NOT_COMPUTABLE = "n. b."
    const val INCOMPLETE_SHORT = "(unvollst.)"
    const val NOT_ENTERED_LONG = "nicht erfasst"
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
        "Einschließlich innerparteilicher Zuschüsse (Einnahmen Nr. 10, Ausgaben Nr. 3), nicht bereinigt (folgt in einer späteren Ausbaustufe)."
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
}
