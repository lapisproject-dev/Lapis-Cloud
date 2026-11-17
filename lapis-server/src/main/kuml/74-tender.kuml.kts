// Tender domain -- tender / tender_bid / tender_award / tender_acceptance (V115__tenders.sql).
//
// Welle V1.9.201 "Ausschreibungen", wave 1 (foundation, LTR only, server side); wave 3 (V1.9.308, migration V157) adds tender_payout and the statuses PAID / PAYOUT_FAILED. A tender puts a task out to bid: it implements an ADOPTED
// resolution (a motion with status RESOLVED whose resolution is ADOPTED and was taken in the committee the motion is addressed to), participants
// (ACTIVE, FRIEND and GUEST) bid in LTR before a deadline, the board awards (LOWEST_PRICE: an act of the board, the server enforces the cheapest
// bid; BEST_OFFER: a committee resolution, the "award motion"), and finally accepts or rejects the result. Wave 1 moved NO LTR; the payout of wave 3
// (V1.9.308) is an ISSUANCE (one credit row TENDER_PAYOUT on the winner), written only by TenderPayout.kt.
//
// DECISIONS THAT SHAPE THE TABLES -- do not "repair" any of them:
//   * tender.status is the STORED status. PUBLISHED past the deadline is CLOSED for every reader and is persisted by the next WRITING path
//     (lazy close, no scheduler). The deadline is a class B value (wall-clock of the organisation zone).
//   * Currency is LTR only: CHECK (currency = 'LTR') on tender and tender_bid. EUR exists in the enum for the future and is unreachable.
//   * tender_bid is an upsert table (one row per tender and bidder). A withdrawn bid KEEPS its row: a bidder who withdrew is still a conflicted
//     party and may never award or accept on that tender.
//   * tender.award_motion_id is UNIQUE (one award motion belongs to exactly one tender) and differs from binding_motion_id (CHECK). One binding
//     motion may carry SEVERAL tenders (no unique index on binding_motion_id).
//   * binding_motion_id / award_motion_id / resolution_id / member columns are plain «Column» attributes with fkEntity, NO association(...)
//     blocks -- same reasoning as 57-carpool.kuml.kts / 45-travel-expense.kuml.kts (association-to-FK naming would derive different names).
//   * tender_award.conflicted_count stores only a COUNT of the excluded bidders, never their ids.
//
// DSGVO -- tender.created_by/published_by/cancelled_by, tender_bid.bidder_member_id, tender_award.awarded_by and tender_acceptance.decided_by are
// personal data; network.lapis.cloud.server.dsgvo.TenderPersonalData exports/erases them, registered in PersonalDataRegistry.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Tender") {
    applyProfile(ermMappingProfile)

    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val motion =
        classOf(name = "Motion") {
            stereotype("Entity") { "tableName" to "motion"; "kotlinObjectName" to "MotionTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // V1.9.308 (V157): id-only stub of the LTR ledger (the real entity lives in 08-ltr-balance.kuml.kts); tender_payout.ledger_entry_id points at it.
    val ltrLedgerEntry =
        classOf(name = "LtrLedgerEntry") {
            stereotype("Entity") { "tableName" to "ltr_ledger_entry"; "kotlinObjectName" to "LtrLedgerEntryTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val resolution =
        classOf(name = "Resolution") {
            stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // Literal order is load-bearing: the drift test pins the values in exactly this order (= network.lapis.cloud.shared.domain.*).
    val tenderStatus =
        enumOf(name = "TenderStatus") {
            literal(name = "DRAFT")
            literal(name = "PUBLISHED")
            literal(name = "CLOSED")
            literal(name = "AWARDED")
            literal(name = "COMPLETED")
            literal(name = "CANCELLED")
            literal(name = "PAID") // V1.9.308 (migration V157)
            literal(name = "PAYOUT_FAILED") // V1.9.308 (migration V157): board view only
        }

    // V1.9.308 (V157): why the payout could not be booked on the winner's side.
    val tenderPayoutFailureReason =
        enumOf(name = "TenderPayoutFailureReason") {
            literal(name = "WINNER_GUEST")
            literal(name = "WINNER_NOT_LTR_ELIGIBLE")
        }

    val tenderBidStatus =
        enumOf(name = "TenderBidStatus") {
            literal(name = "SUBMITTED")
            literal(name = "WITHDRAWN")
            literal(name = "AWARDED")
            literal(name = "REJECTED")
        }

    val tenderBidVisibility =
        enumOf(name = "TenderBidVisibility") {
            literal(name = "HIDDEN_UNTIL_DEADLINE")
            literal(name = "OPEN")
            literal(name = "OWN_ONLY")
        }

    val tenderAwardCriterion =
        enumOf(name = "TenderAwardCriterion") {
            literal(name = "LOWEST_PRICE")
            literal(name = "BEST_OFFER")
        }

    val tenderCurrency =
        enumOf(name = "TenderCurrency") {
            literal(name = "LTR")
            literal(name = "EUR")
        }

    // V1.9.202 (V116): PLAIN (legacy) or MARKDOWN.
    val tenderDescriptionFormat =
        enumOf(name = "TenderDescriptionFormat") {
            literal(name = "PLAIN")
            literal(name = "MARKDOWN")
        }

    val tenderAcceptanceDecision =
        enumOf(name = "TenderAcceptanceDecision") {
            literal(name = "ACCEPTED")
            literal(name = "REJECTED")
        }

    val tender =
        classOf(name = "Tender") {
            stereotype("Entity") { "tableName" to "tender"; "kotlinObjectName" to "TenderTable" }
            stereotype("Index") { "columns" to listOf("award_motion_id"); "unique" to true; "name" to "uq_tender_award_motion" }
            stereotype("Index") { "columns" to listOf("binding_motion_id"); "name" to "ix_tender_binding_motion" }
            stereotype("Index") { "columns" to listOf("status", "deadline"); "name" to "ix_tender_status_deadline" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "title", type = "String") {
                stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "description", type = "String") {
                stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(10000)" }
            }
            // V1.9.202 (V116): how `description` is read; MARKDOWN is rendered to sanitized HTML by the server.
            attribute(name = "descriptionFormat", type = tenderDescriptionFormat) {
                defaultValue = "PLAIN"
                stereotype("Column") {
                    "columnName" to "description_format"
                    "sqlType" to "VARCHAR(16)"
                    "enumType" to "network.lapis.cloud.shared.domain.TenderDescriptionFormat"
                }
            }
            attribute(name = "expectedResult", type = "String") {
                stereotype("Column") { "columnName" to "expected_result"; "sqlType" to "VARCHAR(4000)" }
            }
            attribute(name = "bindingMotionId", type = "UUID") {
                stereotype("Column") { "columnName" to "binding_motion_id"; "fkEntity" to "Motion" }
            }
            attribute(name = "awardCriterion", type = tenderAwardCriterion) {
                stereotype("Column") { "columnName" to "award_criterion"; "enumType" to "network.lapis.cloud.shared.domain.TenderAwardCriterion" }
            }
            attribute(name = "currency", type = tenderCurrency) {
                defaultValue = "LTR"
                stereotype("Column") { "columnName" to "currency"; "enumType" to "network.lapis.cloud.shared.domain.TenderCurrency" }
            }
            attribute(name = "bidVisibility", type = tenderBidVisibility) {
                stereotype("Column") { "columnName" to "bid_visibility"; "enumType" to "network.lapis.cloud.shared.domain.TenderBidVisibility" }
            }
            attribute(name = "status", type = tenderStatus) {
                stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.TenderStatus" }
            }
            // Class B: wall-clock deadline in the organisation zone.
            attribute(name = "deadline", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "deadline" }
            }
            attribute(name = "createdBy", type = "UUID") {
                stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "publishedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "published_by"; "fkEntity" to "Member" }
            }
            attribute(name = "publishedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "published_at" }
            }
            attribute(name = "closedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "closed_at" }
            }
            attribute(name = "cancelledBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "cancelled_by"; "fkEntity" to "Member" }
            }
            attribute(name = "cancelledAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "cancelled_at" }
            }
            attribute(name = "cancelReason", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "cancel_reason"; "sqlType" to "VARCHAR(2000)" }
            }
            attribute(name = "awardMotionId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "award_motion_id"; "fkEntity" to "Motion" }
            }
            // Written by wave 3 (payout, V1.9.308) together with status PAID; deliberately no foreign key here (reserved in V115), the foreign key
            // lives on tender_payout.ledger_entry_id.
            attribute(name = "payoutLedgerEntryId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "payout_ledger_entry_id" }
            }
            // V1.9.308 (V157): the last failed payout attempt, kept as history after a successful retry.
            attribute(name = "payoutFailureReason", type = tenderPayoutFailureReason) {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") {
                    "columnName" to "payout_failure_reason"
                    "sqlType" to "VARCHAR(23)"
                    "enumType" to "network.lapis.cloud.shared.domain.TenderPayoutFailureReason"
                }
            }
            attribute(name = "payoutFailedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "payout_failed_at" }
            }
            attribute(name = "payoutFailedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "payout_failed_by"; "fkEntity" to "Member" }
            }
        }

    val tenderBid =
        classOf(name = "TenderBid") {
            stereotype("Entity") { "tableName" to "tender_bid"; "kotlinObjectName" to "TenderBidTable" }
            stereotype("Index") {
                "columns" to listOf("tender_id", "bidder_member_id")
                "unique" to true
                "name" to "uq_tender_bid_tender_bidder"
            }
            stereotype("Index") { "columns" to listOf("tender_id"); "name" to "ix_tender_bid_tender" }
            stereotype("Index") { "columns" to listOf("bidder_member_id"); "name" to "ix_tender_bid_bidder" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "tenderId", type = "UUID") {
                stereotype("Column") { "columnName" to "tender_id"; "fkEntity" to "Tender" }
            }
            attribute(name = "bidderMemberId", type = "UUID") {
                stereotype("Column") { "columnName" to "bidder_member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "price", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "price"; "sqlType" to "DECIMAL(18,2)" }
            }
            attribute(name = "currency", type = tenderCurrency) {
                defaultValue = "LTR"
                stereotype("Column") { "columnName" to "currency"; "enumType" to "network.lapis.cloud.shared.domain.TenderCurrency" }
            }
            attribute(name = "text", type = "String") {
                stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(4000)" }
            }
            attribute(name = "status", type = tenderBidStatus) {
                stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.TenderBidStatus" }
            }
            attribute(name = "submittedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "submitted_at" }
            }
            attribute(name = "priceSetAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "price_set_at" }
            }
            attribute(name = "withdrawnAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "withdrawn_at" }
            }
        }

    val tenderAward =
        classOf(name = "TenderAward") {
            stereotype("Entity") { "tableName" to "tender_award"; "kotlinObjectName" to "TenderAwardTable" }
            stereotype("Index") { "columns" to listOf("tender_id"); "unique" to true; "name" to "uq_tender_award_tender" }
            stereotype("Index") { "columns" to listOf("bid_id"); "unique" to true; "name" to "uq_tender_award_bid" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "tenderId", type = "UUID") {
                stereotype("Column") { "columnName" to "tender_id"; "fkEntity" to "Tender" }
            }
            attribute(name = "bidId", type = "UUID") {
                stereotype("Column") { "columnName" to "bid_id"; "fkEntity" to "TenderBid" }
            }
            attribute(name = "awardedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "awarded_by"; "fkEntity" to "Member" }
            }
            attribute(name = "awardedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "awarded_at" }
            }
            attribute(name = "awardMotionId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "award_motion_id"; "fkEntity" to "Motion" }
            }
            attribute(name = "resolutionId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "resolution_id"; "fkEntity" to "Resolution" }
            }
            attribute(name = "justification", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "justification"; "sqlType" to "VARCHAR(4000)" }
            }
            // Only a COUNT of the excluded bidders, never their ids.
            attribute(name = "conflictedCount", type = "Int") {
                stereotype("Column") { "columnName" to "conflicted_count" }
            }
            attribute(name = "quorumEligibleCount", type = "Int") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "quorum_eligible_count" }
            }
        }

    val tenderAcceptance =
        classOf(name = "TenderAcceptance") {
            stereotype("Entity") { "tableName" to "tender_acceptance"; "kotlinObjectName" to "TenderAcceptanceTable" }
            stereotype("Index") { "columns" to listOf("tender_id", "decided_at"); "name" to "ix_tender_acceptance_tender" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "tenderId", type = "UUID") {
                stereotype("Column") { "columnName" to "tender_id"; "fkEntity" to "Tender" }
            }
            attribute(name = "decision", type = tenderAcceptanceDecision) {
                stereotype("Column") { "columnName" to "decision"; "enumType" to "network.lapis.cloud.shared.domain.TenderAcceptanceDecision" }
            }
            attribute(name = "reason", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(4000)" }
            }
            attribute(name = "decidedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "decided_by"; "fkEntity" to "Member" }
            }
            attribute(name = "decidedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "decided_at" }
            }
        }

    // V1.9.308 (V157): the booking record of a payout. PK = tender_id (one payout per tender), UNIQUE ledger_entry_id and bid_id.
    val tenderPayout =
        classOf(name = "TenderPayout") {
            stereotype("Entity") { "tableName" to "tender_payout"; "kotlinObjectName" to "TenderPayoutTable" }
            stereotype("Index") { "columns" to listOf("ledger_entry_id"); "unique" to true; "name" to "uq_tender_payout_ledger_entry" }
            stereotype("Index") { "columns" to listOf("bid_id"); "unique" to true; "name" to "uq_tender_payout_bid" }

            attribute(name = "tenderId", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "tender_id"; "fkEntity" to "Tender" }
            }
            attribute(name = "ledgerEntryId", type = "UUID") {
                stereotype("Column") { "columnName" to "ledger_entry_id"; "fkEntity" to "LtrLedgerEntry" }
            }
            attribute(name = "bidId", type = "UUID") {
                stereotype("Column") { "columnName" to "bid_id"; "fkEntity" to "TenderBid" }
            }
            attribute(name = "amountLtr", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "amount_ltr"; "sqlType" to "DECIMAL(18,2)" }
            }
            attribute(name = "executedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "executed_by"; "fkEntity" to "Member" }
            }
            attribute(name = "executedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "executed_at" }
            }
        }
}
