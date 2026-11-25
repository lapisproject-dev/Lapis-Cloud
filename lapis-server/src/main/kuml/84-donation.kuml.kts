// Donation domain -- donation (V174__donation_entity.sql).
//
// Welle V1.9.328 "Spende als eigene Entitaet". A reference-date conversion (19-price-oracle.kuml.kts) converts exactly ONE donation, once, in full. The
// donation is the thing the conversion is tied to: price_oracle_conversion.donation_id.
//
// DECISIONS THAT SHAPE THE TABLE -- do not "repair" any of them:
//   * payment_transaction_id is UNIQUE (a payment is one donation). booking_date is the bank booking day, frozen when the row is created.
//   * Written only by DonationStore (insert only, no update, no delete). created_by is NULL for rows written by the V174 backfill.
//   * Backfill ids equal the payment id (a UUID function in SQL is not portable). Nobody may rely on that.
//   * NOT expressible in the ERM profile and therefore only documented here (see V174): the composite UNIQUE uq_donation_link
//     (id, payment_transaction_id, member_id, amount, currency) -- the target of the composite foreign key of price_oracle_conversion -- and the CHECKs
//     chk_donation_amount (amount > 0) and chk_donation_currency (currency = UPPER(currency)). The index "idx_donation_member" and the unique
//     "uq_donation_payment_transaction" are declared below but, like every index, "not emitted" into the Exposed object.
//
// DSGVO -- member_id and created_by are personal data; network.lapis.cloud.server.dsgvo.PriceOraclePersonalData exports them and retains the row (GoBD).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype
import dev.kuml.uml.Multiplicity

classDiagram(name = "Donation") {
    applyProfile(ermMappingProfile)

    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val paymentTransaction =
        classOf(name = "PaymentTransaction") {
            stereotype("Entity") { "tableName" to "payment_transaction"; "kotlinObjectName" to "PaymentTransactionTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val donation =
        classOf(name = "Donation") {
            stereotype("Entity") { "tableName" to "donation"; "kotlinObjectName" to "DonationTable" }
            stereotype("Index") { "columns" to listOf("payment_transaction_id"); "unique" to true; "name" to "uq_donation_payment_transaction" }
            stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_donation_member" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "paymentTransactionId", type = "UUID") {
                stereotype("Column") { "columnName" to "payment_transaction_id"; "fkEntity" to "PaymentTransaction" }
            }
            attribute(name = "memberId", type = "UUID") {
                stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "amount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(18,2)" }
            }
            attribute(name = "currency", type = "String") {
                stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
            }
            attribute(name = "bookingDate", type = "LocalDate") {
                stereotype("Column") { "columnName" to "booking_date" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "createdBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
            }
        }
}
