package network.lapis.cloud.client

import io.kvision.i18n.tr

/**
 * V1.9.225 -- the German label of a position of the party report. Literal `tr(...)` calls, one per code, so the catalogue extraction finds
 * every text (a `tr(dto.label)` with a variable would not be extracted and so never translated). `PartgPositionLabelsTest` pins that every
 * code of the shared catalogue has a label and that the label equals the catalogue's `labelDe`.
 */
internal fun partgLabel(code: String): String =
    when (code) {
        "E01" -> tr("Mitgliedsbeiträge und ähnliche regelmäßige Beiträge")
        "E02" -> tr("Mandatsträgerbeiträge und ähnliche regelmäßige Beiträge")
        "E03" -> tr("Spenden von natürlichen Personen")
        "E04" -> tr("Spenden von juristischen Personen")
        "E05" -> tr("Einnahmen aus Unternehmenstätigkeit")
        "E05A" -> tr("Einnahmen aus Beteiligungen")
        "E06" -> tr("Einnahmen aus sonstigem Vermögen")
        "E07" ->
            tr(
                "Einnahmen aus Veranstaltungen, Vertrieb von Druckschriften und Veröffentlichungen " +
                    "und sonstiger mit Einnahmen verbundener Tätigkeit",
            )
        "E08" -> tr("Staatliche Mittel")
        "E09" -> tr("Sonstige Einnahmen")
        "E10" -> tr("Zuschüsse von Gliederungen")
        "E11" -> tr("Gesamteinnahmen")
        "A01" -> tr("Ausgaben für Personal")
        "A02A" -> tr("Ausgaben des laufenden Geschäftsbetriebs")
        "A02B" -> tr("Ausgaben für allgemeine politische Arbeit")
        "A02C" -> tr("Ausgaben für Wahlkämpfe")
        "A02D" -> tr("Ausgaben für die Vermögensverwaltung einschließlich sich ergebender Zinsen")
        "A02E" -> tr("Sonstige Zinsen")
        "A02F" -> tr("Ausgaben aus Unternehmenstätigkeit")
        "A02G" -> tr("Sonstige Ausgaben")
        "A02" -> tr("Ausgaben für Sachaufwand insgesamt")
        "A03" -> tr("Zuschüsse an Gliederungen")
        "A04" -> tr("Gesamtausgaben")
        "RES" -> tr("Überschuss bzw. Defizit")
        "BP01" -> tr("Haus- und Grundvermögen")
        "BP02" -> tr("Geschäftsstellenausstattung")
        "BP03" -> tr("Beteiligungen an Unternehmen")
        "BP04" -> tr("Sonstige Finanzanlagen")
        "BP05" -> tr("Forderungen an Gliederungen")
        "BP06" -> tr("Forderungen aus der staatlichen Teilfinanzierung")
        "BP07" -> tr("Geldbestände")
        "BP08" -> tr("Sonstige Vermögensgegenstände")
        "BP-SUM" -> tr("Summe der Besitzposten")
        "SP01" -> tr("Rückstellungen für Pensionsverpflichtungen")
        "SP02" -> tr("Sonstige Rückstellungen")
        "SP03" -> tr("Rückzahlungsverpflichtungen aus der staatlichen Teilfinanzierung")
        "SP04" -> tr("Verbindlichkeiten gegenüber Gebietsverbänden")
        "SP05" -> tr("Verbindlichkeiten gegenüber Kreditinstituten")
        "SP06" -> tr("Verbindlichkeiten gegenüber sonstigen Darlehensgebern")
        "SP07" -> tr("Sonstige Verbindlichkeiten")
        "SP-SUM" -> tr("Summe der Schuldposten")
        "NET" -> tr("Reinvermögen (Besitzposten abzüglich Schuldposten)")
        else -> code
    }
