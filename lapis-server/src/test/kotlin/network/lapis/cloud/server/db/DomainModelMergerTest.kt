package network.lapis.cloud.server.db

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.exposed.UmlToExposedViaErmScriptTransformer
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [DomainModelMerger] — see that object's KDoc for the full algorithm rationale.
 *
 * Mirrors [CodegenParitySeedTest]'s call pattern for the real-file integration test (test 1), and
 * uses small hand-built [KumlDiagram]s (no `.kuml.kts` script evaluation needed) for the
 * synthetic unit tests (tests 2-4), since [DomainModelMerger.merge] only depends on the plain
 * [dev.kuml.core.model.KumlDiagram]/[UmlClass]/[UmlAssociation] data model, not on script
 * evaluation.
 */
class DomainModelMergerTest :
    FunSpec({

        // ── Test 1: merging the real 22 domain scripts ───────────────────────────────────

        test(
            "merging the real 29 domain scripts succeeds and the uml-to-erm -> erm-to-exposed chain " +
                "produces exactly one Table file per distinct table name",
        ) {
            val scriptFiles =
                requireNotNull(KumlModelLoader.kumlSourceDir.listFiles { f -> f.name.endsWith(".kuml.kts") }) {
                    "kUML source dir not found or not a directory: ${KumlModelLoader.kumlSourceDir.absolutePath}"
                }.sortedBy { it.name }
            scriptFiles shouldHaveSize 29

            val diagrams = scriptFiles.map { KumlModelLoader.loadUmlDiagram(it) }

            val merged = DomainModelMerger.merge(diagrams)

            // 47 distinct `"tableName" to "..."` values across the 13 .kuml.kts files (verified by
            // grepping `grep -oh '"tableName" to "[a-z_]*"' lapis-server/src/main/kuml/*.kuml.kts |
            // sort -u | wc -l`; 71 total «Entity» declarations minus 24 cross-domain-stub
            // duplicates: member appears in 12 files (11 dropped, every domain stubs it),
            // motion/meeting/resolution each appear in 4 files (3 dropped each), committee appears
            // in 3 files (2 dropped), document and membership_tier each appear in 2 files (1
            // dropped each) -> 11+3+3+3+2+1+1 = 24 dropped. 10-accounting.kuml.kts (V0.3.1) is what
            // pushed member's count up by one file (its own Member stub) versus the pre-V0.3.1
            // 22-dropped baseline; it adds 4 real tables (ledger_account/journal_entry/posting from
            // V0.3.1, plus cost_center added in V0.3.6) and does not touch
            // motion/meeting/resolution/committee/document at all.
            // 11-organization-settings.kuml.kts (V0.4.1) adds exactly one more real table
            // (organization_settings), with NO cross-domain Member stub at all (no FK to member) --
            // so it contributes +1 «Entity» declaration and +0 drops versus the V0.3.1 baseline
            // above (44 -> 45).
            // 12-postal-mail.kuml.kts (V0.4.2) adds exactly one more real table
            // (postal_delivery_log), WITH its own cross-domain Member stub (it has an FK to
            // member) -- so it contributes +2 «Entity» declarations (the stub + the real table)
            // and +1 drop (the stub merges into the existing member entity) versus the V0.4.1
            // baseline above (45 -> 46).
            // 10-accounting.kuml.kts (V0.5.1 §25 PartG) adds exactly one more real table
            // (external_donor) on top of its own already-counted Member stub -- so it contributes
            // +1 «Entity» declaration and +0 drops versus the V0.4.2 baseline above (46 -> 47).
            // 13-transparenzregister.kuml.kts (V0.5.2 §20 GwG) adds exactly two more real tables
            // (board_membership, transparenzregister_reminder), WITH its own cross-domain Member
            // stub (it has an FK to member) -- so it contributes +3 «Entity» declarations (the stub
            // + the two real tables) and +1 drop (the stub merges into the existing member entity)
            // versus the V0.5.1 baseline above (47 -> 49).
            // 14-audit-log.kuml.kts (V0.5.3 GoBD-Revisionssicherheit) adds exactly two more real
            // tables (audit_log_chain_state, audit_log_entry), WITH its own cross-domain Member
            // stub (it has an FK to member via audit_log_entry.actor_member_id) -- so it
            // contributes +3 «Entity» declarations (the stub + the two real tables) and +1 drop
            // (the stub merges into the existing member entity) versus the V0.5.2 baseline above
            // (49 -> 51).
            // 15-backup-export.kuml.kts (V0.5.4 Backup-/Restore-/Datenexport-Garantie) adds exactly
            // one more real table (backup_operation_log), WITH its own cross-domain Member stub (it
            // has an FK to member via backup_operation_log.actor_member_id) -- so it contributes +2
            // «Entity» declarations (the stub + the one real table) and +1 drop (the stub merges
            // into the existing member entity) versus the V0.5.3 baseline above (51 -> 52).
            // 16-dsgvo-compliance.kuml.kts (V0.5.5 DSGVO-Vollausbau) adds exactly four more real
            // tables (processing_agreement, technical_organizational_measure,
            // data_protection_impact_assessment, data_breach_incident), WITH its own cross-domain
            // Member AND Document stubs (processing_agreement.document_id FKs to document) -- so it
            // contributes +6 «Entity» declarations (2 stubs + 4 real tables) and +2 drops (both
            // stubs merge into the already-existing member/document entities) versus the V0.5.4
            // baseline above (52 -> 56).
            // 08-ltr-balance.kuml.kts's V0.6.1 re-modelling (ltr_balance -> ltr_ledger_entry) does
            // NOT change the distinct-table-name count versus the V0.5.5 baseline above: still
            // exactly one real table plus its own Member stub, only the table's internal shape
            // changed (see that file's own header).
            // 17-crowdfunding.kuml.kts (V0.6.1 Internes Crowdfunding) adds exactly four more real
            // tables (crowdfunding_project, crowdfunding_reaction, crowdfunding_distribution,
            // crowdfunding_submission_gate), WITH its own cross-domain Member stub (submitter_
            // member_id/reviewed_by on crowdfunding_project, member_id on crowdfunding_reaction,
            // triggered_by on crowdfunding_distribution all resolve through it) -- so it
            // contributes +5 «Entity» declarations (the stub + 4 real tables) and +1 drop (the
            // stub merges into the already-existing member entity) versus the V0.5.5 baseline
            // above (56 -> 60).
            // 18-peer-transfer.kuml.kts (V0.6.3 direkte LTR-Peer-to-Peer-Uebertragung) adds exactly
            // one more real table (peer_transfer), WITH its own cross-domain Member stub
            // (sender_member_id/recipient_member_id/initiated_by all resolve through it) -- so it
            // contributes +2 «Entity» declarations (the stub + the one real table) and +1 drop (the
            // stub merges into the already-existing member entity) versus the V0.6.1 baseline
            // above (60 -> 61).
            // 19-price-oracle.kuml.kts (V0.6.5 Price-Oracle fuer die Anker-Bindung) adds exactly two
            // more real tables (price_oracle_config, price_oracle_conversion), WITH its own
            // cross-domain Member stub (price_oracle_conversion.member_id/created_by resolve
            // through it) -- so it contributes +3 «Entity» declarations (the stub + 2 real tables)
            // and +1 drop (the stub merges into the already-existing member entity) versus the
            // V0.6.3 baseline above (61 -> 63).
            // 20-politician.kuml.kts (V0.6.4 Politiker-Profile und Politiker-Ranking; renumbered
            // from its original 19-politician.kuml.kts to 20 when merged onto master alongside
            // V0.6.5, since both waves independently claimed the next-free slot 19 off the same
            // V0.6.3 base) adds exactly three more real tables (politician_profile,
            // politician_reaction, politician_weight_snapshot), WITH its own cross-domain Member
            // stub (member_id/granted_by_member_id/revoked_by_member_id on politician_profile,
            // rater_member_id on politician_reaction, computed_by_member_id on
            // politician_weight_snapshot all resolve through it) -- so it contributes +4 «Entity»
            // declarations (the stub + 3 real tables) and +1 drop (the stub merges into the
            // already-existing member entity) versus the V0.6.5 baseline above (63 -> 66).
            // 21-auction.kuml.kts (V0.6.2 LTR-Auktion) adds exactly three more real tables
            // (auction, auction_bid, auction_compliance_acknowledgment), WITH its own cross-domain
            // Member stub (seller_member_id/winner_member_id on auction, bidder_member_id on
            // auction_bid, acknowledged_by_member_id on auction_compliance_acknowledgment all
            // resolve through it) -- so it contributes +4 «Entity» declarations (the stub + 3 real
            // tables) and +1 drop (the stub merges into the already-existing member entity) versus
            // the V0.6.4 baseline above (66 -> 69).
            // 22-session.kuml.kts (V0.7.1 Authentifizierung) adds exactly one more real table
            // (session), WITH its own cross-domain Member stub (session.member_id resolves through
            // it) -- so it contributes +2 «Entity» declarations (the stub + the one real table) and
            // +1 drop (the stub merges into the already-existing member entity) versus the V0.6.2
            // baseline above (69 -> 70).
            // 23-registration.kuml.kts (V0.7.2 Beitritts-/Registrierungs-Workflow) adds exactly two
            // more real tables (membership_agreement_acknowledgment, password_reset_token), WITH
            // its own cross-domain Member stub (both tables' member_id resolve through it) -- so it
            // contributes +3 «Entity» declarations (the stub + 2 real tables) and +1 drop (the stub
            // merges into the already-existing member entity) versus the V0.7.1 baseline above
            // (70 -> 72).
            // 24-federation.kuml.kts (V0.8.1 Federation-Grundgerüst) adds exactly four more real
            // tables (federation_actor_key, federation_relationship, federation_relationship_event,
            // federation_inbox_delivery_log), WITH NO cross-domain Member stub at all -- none of the
            // four tables have any FK to member (the federated Actor is the organization itself, not
            // a Member; federation_relationship_event's only FK is same-file, to
            // federation_relationship) -- so it contributes +4 «Entity» declarations and +0 drops
            // versus the V0.7.2 baseline above (72 -> 76).
            // 25-oidc-guest-federation.kuml.kts (V0.8.2 OIDC-Gastzugang-Federation) adds exactly
            // nine more real tables (oidc_signing_key, oidc_client_registration,
            // oidc_client_redirect_uri, oidc_authorization_code, oidc_issued_token,
            // oidc_home_server_registration, oidc_rp_login_attempt, oidc_guest_profile,
            // oidc_guest_login_event), WITH its own cross-domain Member stub
            // (oidc_authorization_code.member_id/oidc_issued_token.member_id/
            // oidc_guest_profile.member_id all resolve through it; oidc_guest_login_event.member_id
            // is deliberately a plain, non-FK column, see that file's own header) -- so it
            // contributes +10 «Entity» declarations (the stub + 9 real tables) and +1 drop (the
            // stub merges into the already-existing member entity) versus the V0.8.1 baseline
            // above (76 -> 85).
            // 26-trust-anchor.kuml.kts (V0.8.3 Trust-Anchor-Governance) adds exactly four more real
            // tables (trust_anchor_signing_key, trust_anchor_pool_member, trusted_external_anchor,
            // trust_anchor_event), WITH NO cross-domain Member stub at all -- none of the four
            // tables have any FK to member (Trust-Anchor governance is entirely server-to-server/
            // organization-level, same reasoning 24-federation.kuml.kts's own four tables already
            // established) -- so it contributes +4 «Entity» declarations and +0 drops versus the
            // V0.8.2 baseline above (85 -> 89).
            // 27-conference.kuml.kts (V1.0 Videokonferenzen, Wave 1) adds exactly two more real
            // tables (conference_room, conference_participation), WITH its own cross-domain Member
            // stub (conference_room.created_by_member_id/conference_participation.member_id both
            // resolve through it) -- so it contributes +3 «Entity» declarations (the stub + 2 real
            // tables) and +1 drop (the stub merges into the already-existing member entity) versus
            // the V0.8.3 baseline above (89 -> 91).
            // 28-conference-recording.kuml.kts (V1.0 Videokonferenzen, Wave 2 "Aufzeichnung") adds
            // exactly two more real tables (conference_recording, conference_recording_track), WITH
            // THREE cross-domain stubs (Member, ConferenceRoom, Document -- conference_recording
            // has FKs to all three: started_by_member_id, room_id, document_id) -- so it
            // contributes +5 «Entity» declarations (3 stubs + 2 real tables) and +3 drops (all
            // three stubs merge into the already-existing member/conference_room/document
            // entities) versus the V1.0-Wave-1 baseline above (91 -> 93).
            val distinctTableNames = 93

            val result =
                UmlToExposedViaErmScriptTransformer().transform(
                    merged,
                    TransformContext(mapOf("idType" to "uuid", "package" to "network.lapis.cloud.server.db.generated.merged")),
                )
            val files =
                when (result) {
                    is TransformResult.Success -> result.output
                    is TransformResult.Failure ->
                        error(
                            "uml-to-exposed-via-erm transform failed for the merged diagram: " +
                                result.errors.joinToString("; ") { it.message },
                        )
                }

            val fileNames = files.map { it.relativePath }
            fileNames shouldHaveSize distinctTableNames
            fileNames.toSet() shouldHaveSize distinctTableNames // no duplicate kotlinObjectName-derived filenames

            fileNames shouldContainExactlyInAnyOrder
                listOf(
                    "MemberTable.kt",
                    "AccountTable.kt",
                    "MembershipTierTable.kt",
                    "ContributionTable.kt",
                    "DocumentFolderTable.kt",
                    "DocumentTable.kt",
                    "DocumentVersionTable.kt",
                    "MailingListTable.kt",
                    "MailingListSubscriptionTable.kt",
                    "MailingMessageTable.kt",
                    "MailingDeliveryLogTable.kt",
                    "DirectMessageTable.kt",
                    "ErasureRequestTable.kt",
                    "DsgvoAuditLogTable.kt",
                    "CommitteeTable.kt",
                    "CommitteeMembershipTable.kt",
                    "MeetingTable.kt",
                    "AgendaItemTable.kt",
                    "AttendanceTable.kt",
                    "ResolutionTable.kt",
                    "MotionTable.kt",
                    "VoteTable.kt",
                    "VoteOptionTable.kt",
                    "VoteBallotTable.kt",
                    "ElectionTable.kt",
                    "ElectionCandidacyTable.kt",
                    "ElectionOptionTable.kt",
                    "ElectionBoardMemberTable.kt",
                    "ElectionEligibleVoterTable.kt",
                    "ElectionParticipationTable.kt",
                    "ElectionTallyApprovalTable.kt",
                    "ElectionBallotTable.kt",
                    "ElectionBallotSelectionTable.kt",
                    "LtrLedgerEntryTable.kt",
                    "SystemicConsensusTable.kt",
                    "SystemicConsensusOptionTable.kt",
                    "SystemicConsensusEligibleVoterTable.kt",
                    "SystemicConsensusParticipationTable.kt",
                    "SystemicConsensusBallotTable.kt",
                    "SystemicConsensusResistanceTable.kt",
                    "LedgerAccountTable.kt",
                    "JournalEntryTable.kt",
                    "PostingTable.kt",
                    "CostCenterTable.kt",
                    "ExternalDonorTable.kt",
                    "OrganizationSettingsTable.kt",
                    "PostalDeliveryLogTable.kt",
                    "BoardMembershipTable.kt",
                    "TransparenzregisterReminderTable.kt",
                    "AuditLogChainStateTable.kt",
                    "AuditLogEntryTable.kt",
                    "BackupOperationLogTable.kt",
                    "ProcessingAgreementTable.kt",
                    "TechnicalOrganizationalMeasureTable.kt",
                    "DataProtectionImpactAssessmentTable.kt",
                    "DataBreachIncidentTable.kt",
                    "CrowdfundingProjectTable.kt",
                    "CrowdfundingReactionTable.kt",
                    "CrowdfundingDistributionTable.kt",
                    "CrowdfundingSubmissionGateTable.kt",
                    "PeerTransferTable.kt",
                    "PriceOracleConfigTable.kt",
                    "PriceOracleConversionTable.kt",
                    "PoliticianProfileTable.kt",
                    "PoliticianReactionTable.kt",
                    "PoliticianWeightSnapshotTable.kt",
                    "AuctionTable.kt",
                    "AuctionBidTable.kt",
                    "AuctionComplianceAcknowledgmentTable.kt",
                    "SessionTable.kt",
                    "MembershipAgreementAcknowledgmentTable.kt",
                    "PasswordResetTokenTable.kt",
                    "FederationActorKeyTable.kt",
                    "FederationRelationshipTable.kt",
                    "FederationRelationshipEventTable.kt",
                    "FederationInboxDeliveryLogTable.kt",
                    "OidcSigningKeyTable.kt",
                    "OidcClientRegistrationTable.kt",
                    "OidcClientRedirectUriTable.kt",
                    "OidcAuthorizationCodeTable.kt",
                    "OidcIssuedTokenTable.kt",
                    "OidcHomeServerRegistrationTable.kt",
                    "OidcRpLoginAttemptTable.kt",
                    "OidcGuestProfileTable.kt",
                    "OidcGuestLoginEventTable.kt",
                    "TrustAnchorSigningKeyTable.kt",
                    "TrustAnchorPoolMemberTable.kt",
                    "TrustedExternalAnchorTable.kt",
                    "TrustAnchorEventTable.kt",
                    "ConferenceRoomTable.kt",
                    "ConferenceParticipationTable.kt",
                    "ConferenceRecordingTable.kt",
                    "ConferenceRecordingTrackTable.kt",
                )
        }

        // ── Test 2: stub dedup + association end remap ──────────────────────────────────

        test("a full entity and a same-table stub in another diagram merge to one class, and the stub's association end is remapped") {
            val fooFull =
                entityClass(
                    id = "a1",
                    name = "Foo",
                    tableName = "foo",
                    attributeNames = listOf("id", "name", "email"),
                )
            val diagramA = KumlDiagram(name = "A", type = DiagramType.CLASS, elements = listOf(fooFull))

            val fooStub = entityClass(id = "b1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val bar = entityClass(id = "b2", name = "Bar", tableName = "bar", attributeNames = listOf("id"))
            val assoc =
                UmlAssociation(
                    id = "assoc-bar-foo",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "b2", role = "bar"),
                            UmlAssociationEnd(typeId = "b1", role = "fooId"),
                        ),
                )
            val diagramB = KumlDiagram(name = "B", type = DiagramType.CLASS, elements = listOf(fooStub, bar, assoc))

            val merged = DomainModelMerger.merge(listOf(diagramA, diagramB))

            val fooClasses = merged.elements.filterIsInstance<UmlClass>().filter { it.name == "Foo" }
            fooClasses shouldHaveSize 1
            fooClasses.single().id shouldBe "a1"
            fooClasses.single().attributes.map { it.name } shouldContainExactlyInAnyOrder listOf("id", "name", "email")

            val mergedAssoc = merged.elements.filterIsInstance<UmlAssociation>().single { it.id == "assoc-bar-foo" }
            mergedAssoc.ends.map { it.typeId } shouldContainExactlyInAnyOrder listOf("b2", "a1")
            mergedAssoc.ends.single { it.role == "fooId" }.typeId shouldBe "a1"
        }

        // ── Test 3: genuine attribute conflict throws ───────────────────────────────────

        test("a non-subset attribute conflict between two declarations of the same entity throws") {
            val fooA = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id", "name"))
            val fooB = entityClass(id = "b1", name = "Foo", tableName = "foo", attributeNames = listOf("id", "differentAttribute"))

            val diagramA = KumlDiagram(name = "A", type = DiagramType.CLASS, elements = listOf(fooA))
            val diagramB = KumlDiagram(name = "B", type = DiagramType.CLASS, elements = listOf(fooB))

            val exception = shouldThrow<IllegalStateException> { DomainModelMerger.merge(listOf(diagramA, diagramB)) }
            exception.message shouldContain "Foo"
            exception.message shouldContain "differentAttribute"
        }

        // ── Test 4: no duplicates -> plain union, no errors, no drops ───────────────────

        test("diagrams with entirely distinct entities merge to the plain union with no drops") {
            val foo = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val baz = entityClass(id = "b1", name = "Baz", tableName = "baz", attributeNames = listOf("id"))

            val diagramA = KumlDiagram(name = "A", type = DiagramType.CLASS, elements = listOf(foo))
            val diagramB = KumlDiagram(name = "B", type = DiagramType.CLASS, elements = listOf(baz))

            val merged = DomainModelMerger.merge(listOf(diagramA, diagramB))

            merged.elements.filterIsInstance<UmlClass>().map { it.id } shouldContainExactlyInAnyOrder listOf("a1", "b1")
        }

        // ── Test 5 (bonus, not explicitly required but cheap coverage): association dedup ─

        test("two associations that become identical after remapping are deduplicated, but role-distinct ones are kept") {
            val fooFull = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val barA = entityClass(id = "a2", name = "Bar", tableName = "bar", attributeNames = listOf("id"))
            val diagramA =
                KumlDiagram(
                    name = "A",
                    type = DiagramType.CLASS,
                    elements =
                        listOf(
                            fooFull,
                            barA,
                            UmlAssociation(
                                id = "assoc-a",
                                ends =
                                    listOf(
                                        UmlAssociationEnd(typeId = "a2", role = "bar"),
                                        UmlAssociationEnd(typeId = "a1", role = "fooId"),
                                    ),
                            ),
                        ),
                )

            // diagramB re-declares Foo as a stub (dropped) and Bar fully (kept, distinct table), plus
            // the SAME logical association (bar -> foo, same roles) modeled independently, which
            // becomes an exact duplicate of diagramA's association once "b1" remaps to "a1" — and a
            // second, genuinely different association (different role) that must survive dedup.
            val fooStub = entityClass(id = "b1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val diagramB =
                KumlDiagram(
                    name = "B",
                    type = DiagramType.CLASS,
                    elements =
                        listOf(
                            fooStub,
                            UmlAssociation(
                                id = "assoc-b-duplicate",
                                ends =
                                    listOf(
                                        UmlAssociationEnd(typeId = "b1", role = "fooId"),
                                        UmlAssociationEnd(typeId = "a2", role = "bar"),
                                    ),
                            ),
                            UmlAssociation(
                                id = "assoc-b-distinct-role",
                                ends =
                                    listOf(
                                        UmlAssociationEnd(typeId = "b1", role = "reviewedFooId"),
                                        UmlAssociationEnd(typeId = "a2", role = "bar"),
                                    ),
                            ),
                        ),
                )

            val merged = DomainModelMerger.merge(listOf(diagramA, diagramB))
            val associations = merged.elements.filterIsInstance<UmlAssociation>()

            associations shouldHaveSize 2
            associations.map { it.id } shouldContainExactlyInAnyOrder listOf("assoc-a", "assoc-b-distinct-role")
        }
    })

/** Builds a minimal `«Entity»`-stereotyped [UmlClass] for the synthetic tests. */
private fun entityClass(
    id: String,
    name: String,
    tableName: String,
    attributeNames: List<String>,
): UmlClass =
    UmlClass(
        id = id,
        name = name,
        attributes =
            attributeNames.map { attrName ->
                UmlProperty(id = "$id-$attrName", name = attrName, type = UmlTypeRef(name = "String"))
            },
        appliedStereotypes =
            listOf(
                KumlStereotypeApplication(
                    profileNamespace = ErmProfileNames.NAMESPACE,
                    stereotypeName = ErmProfileNames.ENTITY,
                    tags = mapOf(ErmProfileNames.TAG_TABLE_NAME to TagValue.StringVal(tableName)),
                ),
            ),
    )
