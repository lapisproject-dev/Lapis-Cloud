// Carpool domain — carpool_posting (V58__carpool.sql).
//
// Welle V1.9.12 "Mitfahrerzentrale" -- ein Mitglied kann eine Fahrt anbieten (OFFER) oder eine
// Mitfahrgelegenheit suchen (REQUEST). Postfach-Erweiterung statt eigenem Nachrichtensystem:
// Kontaktaufnahme läuft über eine ganz normale direct_message (siehe
// network.lapis.cloud.server.rpc.DirectMessaging.insertDirectMessage), es gibt keine eigene
// Carpool-Kommunikationstabelle. Sichtbarkeit im Feed endet mit dem Abfahrtsdatum
// (CarpoolService.listPostings filtert departure_date >= heute); die endgültige Löschung folgt
// erst 7 Tage später (CarpoolRetention), damit ein Mitglied eine abgelaufene eigene Fahrt kurz
// duplizieren kann.
//
// DSGVO: carpool_posting.author_member_id trägt personenbezogene Daten (Ort/Zeit-Muster eines
// Mitglieds) -- CarpoolPersonalData (network.lapis.cloud.server.dsgvo) exportiert/löscht sie,
// registriert in PersonalDataRegistry. Löschung ist bei eraseMember immer eine harte Löschung
// (kein Redigieren wie bei direct_message), weil ein Carpool-Posting keine Gegenpartei-Kopie hat.
//
// V1.9.125 "Mitfahrten": carpool_participation (V100__carpool_participation.sql) -- one row per (posting, person); initiator DRIVER = the driver
// invited, PASSENGER = the person asked; status OPEN/CONFIRMED/DECLINED/WITHDRAWN. posting_id is ON DELETE CASCADE in the real schema (a
// deleted/expired posting takes its participations along; the generated Exposed table cannot express it, the explicit deletes in
// CarpoolRetention/CarpoolService/CarpoolPersonalData do not rely on it). person_id references member WITHOUT cascade: people are anonymised, not
// deleted, the GDPR erasure removes the rows explicitly. Both FKs are plain «Column» UUID attributes with fkEntity (no association(...) block, same
// reason as author_member_id above). carpool_posting.meeting_note (VARCHAR(200), nullable) carries the meeting point and time, shown only to the
// driver and a CONFIRMED passenger. The audit entity type CARPOOL_PARTICIPATION lives in 14-audit-log.kuml.kts.
//
// Cross-domain stub: ein minimaler id-only Member-Stub (Foundation-owned), gleiches Muster wie in
// jeder anderen Domain-Datei, nur damit UmlToErmTransformer author_member_id auflösen kann.
//
// author_member_id ist ein plain «Column» UUID-Attribut mit fkEntity, KEIN zusätzlicher
// association(...)-Block -- exakt die in 45-travel-expense.kuml.kts's Dateikopf dokumentierte
// Begründung: ein redundanter association(...)-Block neben einem bereits per «Column».fkEntity
// deklarierten FK würde eine ZWEITE, unabhängig benannte FK-Spalte synthetisieren.
//
// departure_time ist vom Typ "LocalTime" -- der einzige Time-Typ-Gebrauch in diesem Repo bisher.
// ErmExposedEmitter kennt für ErmDataType.Time keine dateTimeRepresentation-Verzweigung (anders
// als Date/Timestamp) und emittiert IMMER java.time.LocalTime (org.jetbrains.exposed.v1.javatime.time).
// CarpoolService konvertiert explizit zwischen java.time.LocalTime (DB-Spaltenwert) und
// kotlinx.datetime.LocalTime (Shared-DTO-Feld) über die kotlinx-datetime-Interop-Erweiterungen
// (toJavaLocalTime()/toKotlinLocalTime()), siehe dessen KDoc.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Carpool") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // carpool_posting.author_member_id's association target.
    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val carpoolPostingType =
        enumOf(name = "CarpoolPostingType") {
            literal(name = "OFFER")
            literal(name = "REQUEST")
        }

    val carpoolPosting =
        classOf(name = "CarpoolPosting") {
            stereotype("Entity") { "tableName" to "carpool_posting"; "kotlinObjectName" to "CarpoolPostingTable" }
            stereotype("Index") { "columns" to listOf("departure_date"); "name" to "idx_carpool_posting_departure_date" }
            stereotype("Index") { "columns" to listOf("author_member_id"); "name" to "idx_carpool_posting_author" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see file header
            // (no redundant association(...) block).
            attribute(name = "authorMemberId", type = "UUID") {
                stereotype("Column") { "columnName" to "author_member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "type", type = carpoolPostingType) {
                stereotype("Column") { "columnName" to "type"; "enumType" to "network.lapis.cloud.shared.domain.CarpoolPostingType" }
            }
            attribute(name = "fromPlace", type = "String") {
                stereotype("Column") { "columnName" to "from_place"; "sqlType" to "VARCHAR(60)" }
            }
            attribute(name = "toPlace", type = "String") {
                stereotype("Column") { "columnName" to "to_place"; "sqlType" to "VARCHAR(60)" }
            }
            attribute(name = "departureDate", type = "LocalDate") {
                stereotype("Column") { "columnName" to "departure_date" }
            }
            // See file header -- always java.time.LocalTime once generated/hand-authored per
            // ErmExposedEmitter's ErmDataType.Time branch, regardless of dateTimeRepresentation.
            attribute(name = "departureTime", type = "LocalTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "departure_time" }
            }
            attribute(name = "seatsOffered", type = "Int") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "seats_offered" }
            }
            attribute(name = "notes", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "notes"; "sqlType" to "VARCHAR(500)" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
            // V1.9.125 -- meeting point and time, only for the driver and a CONFIRMED passenger.
            attribute(name = "meetingNote", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "meeting_note"; "sqlType" to "VARCHAR(200)" }
            }
        }

    val carpoolParticipationInitiator =
        enumOf(name = "CarpoolParticipationInitiator") {
            literal(name = "DRIVER")
            literal(name = "PASSENGER")
        }

    val carpoolParticipationStatus =
        enumOf(name = "CarpoolParticipationStatus") {
            literal(name = "OPEN")
            literal(name = "CONFIRMED")
            literal(name = "DECLINED")
            literal(name = "WITHDRAWN")
        }

    val carpoolParticipation =
        classOf(name = "CarpoolParticipation") {
            stereotype("Entity") { "tableName" to "carpool_participation"; "kotlinObjectName" to "CarpoolParticipationTable" }
            stereotype("Index") {
                "columns" to listOf("posting_id", "person_id")
                "unique" to true
                "name" to "uq_carpool_participation_posting_person"
            }
            stereotype("Index") { "columns" to listOf("person_id", "status"); "name" to "idx_carpool_participation_person_status" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "postingId", type = "UUID") {
                stereotype("Column") { "columnName" to "posting_id"; "fkEntity" to "CarpoolPosting" }
            }
            attribute(name = "personId", type = "UUID") {
                stereotype("Column") { "columnName" to "person_id"; "fkEntity" to "Member" }
            }
            attribute(name = "initiator", type = carpoolParticipationInitiator) {
                stereotype("Column") {
                    "columnName" to "initiator"
                    "enumType" to "network.lapis.cloud.shared.domain.CarpoolParticipationInitiator"
                }
            }
            attribute(name = "status", type = carpoolParticipationStatus) {
                stereotype("Column") {
                    "columnName" to "status"
                    "enumType" to "network.lapis.cloud.shared.domain.CarpoolParticipationStatus"
                }
            }
            // Class A (UTC): created, last changed, decided (NULL while OPEN).
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
            attribute(name = "decidedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "decided_at" }
            }
        }
}
