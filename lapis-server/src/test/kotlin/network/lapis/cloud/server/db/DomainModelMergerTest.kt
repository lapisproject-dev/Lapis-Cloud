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

        // ── Test 1: merging the real 9 domain scripts ───────────────────────────────────

        test(
            "merging the real 10 domain scripts succeeds and the uml-to-erm -> erm-to-exposed chain " +
                "produces exactly one Table file per distinct table name",
        ) {
            val scriptFiles =
                requireNotNull(KumlModelLoader.kumlSourceDir.listFiles { f -> f.name.endsWith(".kuml.kts") }) {
                    "kUML source dir not found or not a directory: ${KumlModelLoader.kumlSourceDir.absolutePath}"
                }.sortedBy { it.name }
            scriptFiles shouldHaveSize 10

            val diagrams = scriptFiles.map { KumlModelLoader.loadUmlDiagram(it) }

            val merged = DomainModelMerger.merge(diagrams)

            // 40 distinct `"tableName" to "..."` values across the 10 .kuml.kts files (verified by
            // grepping `grep -oh '"tableName" to "[a-z_]*"' lapis-server/src/main/kuml/*.kuml.kts |
            // sort -u | wc -l`; 58 total «Entity» declarations minus 18 cross-domain-stub
            // duplicates: member appears in 7 files (6 dropped), motion/meeting/resolution each
            // appear in 4 files (3 dropped each), committee appears in 3 files (2 dropped),
            // membership_tier appears in 2 files (1 dropped) -> 6+3+3+3+2+1 = 18 dropped.
            // 09-systemic-consensus.kuml.kts (V0.2.5) is what pushed member/motion/meeting/resolution/
            // committee's counts up by one file each versus the pre-V0.2.5 13-dropped baseline.
            val distinctTableNames = 40

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
                    "LtrBalanceTable.kt",
                    "SystemicConsensusTable.kt",
                    "SystemicConsensusOptionTable.kt",
                    "SystemicConsensusEligibleVoterTable.kt",
                    "SystemicConsensusParticipationTable.kt",
                    "SystemicConsensusBallotTable.kt",
                    "SystemicConsensusResistanceTable.kt",
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
