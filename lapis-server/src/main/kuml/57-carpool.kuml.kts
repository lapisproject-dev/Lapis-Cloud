// Carpool domain — carpool_posting (V58__carpool.sql).
//
// Welle V1.9.12 "Mitfahrerzentrale" -- ein Mitglied kann eine Fahrt anbieten (OFFER) oder eine
// Mitfahrgelegenheit suchen (REQUEST). Postfach-Erweiterung statt eigenem Nachrichtensystem:
// Kontaktaufnahme läuft über eine ganz normale direct_message (siehe
// network.lapis.cloud.server.rpc.DirectMessaging.insertDirectMessage), es gibt keine eigene
// Carpool-Kommunikationstabelle (seit V1.9.139 gibt es eine enge Ausnahme, siehe unten). Sichtbarkeit im Feed endet mit dem Abfahrtsdatum
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
// V1.9.139 "Mitfahr-Nachrichten": carpool_message_thread + carpool_message (V105__carpool_message.sql) -- a short history per CONFIRMED
// participation, visible only to the driver and that passenger. sender_side/ended_by_side are DRIVER|PASSENGER (no person reference stored).
// Lifetime: deleted when the participation leaves CONFIRMED, with the posting, by the GDPR erasure, and by CarpoolRetention (departure + 8
// days). EXCLUDED from the organization backup (OrganizationSchemaCatalog.EXCLUDED_TABLES). The participation_id FK is ON DELETE CASCADE in
// the real schema; the generated Exposed tables cannot express it, so CarpoolMessages.deleteFor* delete explicitly. Plain «Column» UUIDs with
// fkEntity, no association(...) block (same reason as above).
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

    // V1.9.313: id-only stub so carpool_teaser_tombstone.partner_id can name its foreign key (the partner domain owns the table, 79-partner-servers).
    val partnerServer =
        classOf(name = "PartnerServer") {
            stereotype("Entity") { "tableName" to "partner_server"; "kotlinObjectName" to "PartnerServerTable" }
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
            // V1.9.311 "Mitfahrzentrale-Foederation" W1 (V159): the owner's per-trip consent to pass a short hint to partner organisations. NOT NULL,
            // default FALSE. Written ONLY by CarpoolFederationStore (setOptIn/clearOptIn) -- never via CarpoolPostingInput.
            attribute(name = "federationOptIn", type = "Boolean") {
                defaultValue = "FALSE"
                stereotype("Column") { "columnName" to "federation_opt_in" }
            }
            // V1.9.313 (V161): when the consent was given (NULL when never opted in). Written ONLY by CarpoolFederationStore together with federationOptIn.
            attribute(name = "federationOptInAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "federation_opt_in_at" }
            }
        }

    // V1.9.313 (V161): the tombstone of a withdrawn teaser -- a pseudonymous id, a scope and a short life, nothing else. Read and written ONLY by
    // CarpoolFederationStore. The foreign key to partner_server is ON DELETE CASCADE in the real schema (the generated table cannot express it,
    // so the store deletes the rows of a removed partner explicitly).
    val carpoolTeaserTombstone =
        classOf(name = "CarpoolTeaserTombstone") {
            stereotype("Entity") { "tableName" to "carpool_teaser_tombstone"; "kotlinObjectName" to "CarpoolTeaserTombstoneTable" }
            stereotype("Index") { "columns" to listOf("teaser_id"); "name" to "idx_carpool_teaser_tombstone_teaser" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_carpool_teaser_tombstone_expires" }
            stereotype("Index") { "columns" to listOf("partner_id"); "name" to "idx_carpool_teaser_tombstone_partner" }
            stereotype("Index") { "columns" to listOf("relay_reporter_partner_id"); "name" to "idx_carpool_teaser_tombstone_relay_reporter" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "partnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "partner_id"; "fkEntity" to "PartnerServer" }
            }
            // V1.9.319 (V165) W7: the provenance of a PASSED-ON tombstone. relayOriginHost NULL = this server's OWN withdrawal. The reporter has
            // deliberately NO foreign key (the cap per reporter keeps counting after the partner is removed).
            attribute(name = "relayOriginHost", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "relay_origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "relayReporterPartnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "relay_reporter_partner_id" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
            }
        }

    // V1.9.314 (V162) W4: a hint of ANOTHER server -- date, two postcode digits and the HMAC pseudonym of its origin, nothing else (no member, no trip,
    // no name, no contact, no place text). Read and written ONLY by CarpoolReceivedStore. The foreign key to partner_server is ON DELETE CASCADE in
    // the real schema (the generated table cannot express it, so the store deletes the rows of a removed partner explicitly).
    val carpoolReceivedHint =
        classOf(name = "CarpoolReceivedHint") {
            stereotype("Entity") { "tableName" to "carpool_received_hint"; "kotlinObjectName" to "CarpoolReceivedHintTable" }
            stereotype("Index") { "columns" to listOf("origin_host", "teaser_id"); "unique" to true; "name" to "uq_carpool_received_hint_origin_teaser" }
            stereotype("Index") { "columns" to listOf("source_partner_id"); "name" to "idx_carpool_received_hint_source" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_carpool_received_hint_expires" }
            stereotype("Index") { "columns" to listOf("teaser_id"); "name" to "idx_carpool_received_hint_teaser" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "originHost", type = "String") {
                stereotype("Column") { "columnName" to "origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "hops", type = "Int") {
                stereotype("Column") { "columnName" to "hops"; "sqlType" to "SMALLINT" }
            }
            attribute(name = "maxHops", type = "Int") {
                stereotype("Column") { "columnName" to "max_hops"; "sqlType" to "SMALLINT" }
            }
            attribute(name = "viaHosts", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "via_hosts"; "sqlType" to "VARCHAR(800)" }
            }
            attribute(name = "sourcePartnerId", type = "UUID") {
                stereotype("Column") { "columnName" to "source_partner_id"; "fkEntity" to "PartnerServer" }
            }
            attribute(name = "departureDate", type = "LocalDate") {
                stereotype("Column") { "columnName" to "departure_date" }
            }
            attribute(name = "fromPostcodeArea", type = "String") {
                stereotype("Column") { "columnName" to "from_postcode_area"; "sqlType" to "VARCHAR(2)" }
            }
            attribute(name = "toPostcodeArea", type = "String") {
                stereotype("Column") { "columnName" to "to_postcode_area"; "sqlType" to "VARCHAR(2)" }
            }
            attribute(name = "originSignature", type = "String") {
                stereotype("Column") { "columnName" to "origin_signature"; "sqlType" to "VARCHAR(1024)" }
            }
            attribute(name = "receivedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "received_at" }
            }
            attribute(name = "lastSeenAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "last_seen_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
            }
        }

    // V1.9.314 (V162) W4: a LOCAL block against re-importing a withdrawn hint of another server over another path; never sent anywhere. Read and
    // written ONLY by CarpoolReceivedStore. The foreign key is ON DELETE SET NULL in the real schema.
    val carpoolReceivedTombstone =
        classOf(name = "CarpoolReceivedTombstone") {
            stereotype("Entity") { "tableName" to "carpool_received_tombstone"; "kotlinObjectName" to "CarpoolReceivedTombstoneTable" }
            stereotype("Index") { "columns" to listOf("origin_host", "teaser_id"); "unique" to true; "name" to "uq_carpool_received_tombstone_origin_teaser" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_carpool_received_tombstone_expires" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "originHost", type = "String") {
                stereotype("Column") { "columnName" to "origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "reportedByPartnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "reported_by_partner_id"; "fkEntity" to "PartnerServer" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
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

    val carpoolMessageSide =
        enumOf(name = "CarpoolMessageSide") {
            literal(name = "DRIVER")
            literal(name = "PASSENGER")
        }

    classOf(name = "CarpoolMessageThread") {
        stereotype("Entity") { "tableName" to "carpool_message_thread"; "kotlinObjectName" to "CarpoolMessageThreadTable" }

        attribute(name = "participationId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "participation_id"; "fkEntity" to "CarpoolParticipation" }
        }
        attribute(name = "endedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at" }
        }
        attribute(name = "endedBySide", type = carpoolMessageSide) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "ended_by_side"
                "enumType" to "network.lapis.cloud.shared.domain.CarpoolMessageSide"
            }
        }
        attribute(name = "lastMailAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_mail_at" }
        }
    }

    classOf(name = "CarpoolMessage") {
        stereotype("Entity") { "tableName" to "carpool_message"; "kotlinObjectName" to "CarpoolMessageTable" }
        stereotype("Index") {
            "columns" to listOf("participation_id", "sent_at", "id")
            "name" to "idx_carpool_message_participation_sent"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "participationId", type = "UUID") {
            stereotype("Column") { "columnName" to "participation_id"; "fkEntity" to "CarpoolParticipation" }
        }
        attribute(name = "senderSide", type = carpoolMessageSide) {
            stereotype("Column") {
                "columnName" to "sender_side"
                "enumType" to "network.lapis.cloud.shared.domain.CarpoolMessageSide"
            }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "sentAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "sent_at" }
        }
    }
}
