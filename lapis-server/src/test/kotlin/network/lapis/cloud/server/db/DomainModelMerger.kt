package network.lapis.cloud.server.db

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass

/**
 * Merges N per-domain [KumlDiagram]s (the 9 `NN-domain.kuml.kts` design models under
 * `src/main/kuml/`, each loaded independently via [KumlModelLoader.loadUmlDiagram]) into a
 * single combined [KumlDiagram], deduplicating cross-domain "stub" entities.
 *
 * ## Background
 *
 * Several domain scripts declare the *same* conceptual entity independently — e.g. `Member`,
 * `Motion`, `Meeting`, `Committee`, `Resolution` each appear, fully or as an id-only stub, in
 * multiple `.kuml.kts` files (a file needs a minimal stub of a foreign-domain entity purely so
 * `UmlToErmTransformer` can resolve an association's target within that single-file evaluation —
 * see e.g. `01-contribution.kuml.kts`'s header comment). Every declaration gets its own
 * [UmlClass.id] (distinct per file, even for the same conceptual entity), but shares the same
 * `«Entity».tableName`/`kotlinObjectName` tags. Naively concatenating all 9 diagrams' `elements`
 * and running them through the existing `uml-to-erm` → `erm-to-exposed` transform chain would
 * therefore produce duplicate, colliding Kotlin object declarations for the same table.
 *
 * ## Algorithm
 *
 * 1. Union all elements from all input diagrams.
 * 2. Group [UmlClass] elements that carry an applied `«Entity»` stereotype (same
 *    stereotype-namespace/tag-reading approach as `UmlToErmTransformer.deriveTableName` —
 *    [ErmProfileNames.ENTITY] / [ErmProfileNames.TAG_TABLE_NAME], reimplemented locally below
 *    because the transformer module's `ermStereotype`/`stringTag` helpers are `internal` to that
 *    module and not visible here) by their `tableName` tag value.
 * 3. For each group with more than one declaration, the *canonical* declaration is the one with
 *    the most attributes — the "owning" domain's full declaration. Ties are broken
 *    deterministically in favor of the first-encountered declaration (stable left-to-right
 *    reduction over the union-of-elements order from step 1).
 * 4. Every non-canonical duplicate's attribute *names* must be a subset of the canonical's
 *    attribute names — otherwise this is a genuine modeling conflict between two domains'
 *    independently-authored declarations of "the same" entity, and [merge] throws
 *    [IllegalStateException] naming the conflicting table/class/attribute rather than silently
 *    resolving it.
 * 5. Every dropped (non-canonical) [UmlClass.id] maps to its group's canonical [UmlClass.id].
 * 6. Every [UmlAssociation] end's `typeId` is rewritten through that map.
 * 7. After remapping, associations that became exact duplicates of each other (same unordered
 *    pair of endpoint type ids *and* same unordered pair of end roles) are deduplicated, keeping
 *    only the first-encountered one. Associations with genuinely different roles/multiplicities
 *    between the same two entities are never deduplicated (multiplicities and aggregation aren't
 *    part of the dedup signature, but role *is*, and role differences are exactly what
 *    distinguishes e.g. two independently-modeled relationships between the same entity pair).
 * 8. Non-canonical duplicate [UmlClass] elements are dropped from the final element list; every
 *    other element (enums, non-duplicate classes, ...) is kept as-is. [dev.kuml.uml.UmlEnumeration]
 *    elements are deliberately never deduplicated, even when the same enum name/literals appear
 *    in multiple files — the downstream Exposed emitter already dedupes generated enum
 *    *references* by name once an external `enumType` tag is set (which every enum column in this
 *    codebase's `.kuml.kts` files already carries), so duplicate enum *declarations* in the merged
 *    model never produce duplicate output.
 *
 * Deliberately test-scoped only, matching [KumlModelLoader]'s convention (kUML is a test-only
 * dependency of `lapis-server` — see `gradle/libs.versions.toml`).
 */
internal object DomainModelMerger {
    /**
     * Merges [diagrams] into one [KumlDiagram] named [mergedName]. Throws [IllegalStateException]
     * if two declarations of "the same" entity (same `«Entity».tableName`) disagree in a way that
     * can't be safely resolved (a non-canonical declaration has an attribute name the canonical
     * declaration lacks).
     */
    fun merge(
        diagrams: List<KumlDiagram>,
        mergedName: String = "LapisCloud",
    ): KumlDiagram {
        val allElements: List<KumlElement> = diagrams.flatMap { it.elements }

        // ── Step 2: group Entity-stereotyped UmlClass elements by tableName tag ────────────
        val entityClassesByTableName: Map<String, List<UmlClass>> =
            allElements
                .filterIsInstance<UmlClass>()
                .mapNotNull { cls -> cls.entityTableName()?.let { tableName -> tableName to cls } }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        // ── Steps 3-5: pick canonical per table, validate subset, build id remap ───────────
        //
        // NOTE: kUML derives UmlClass.id deterministically from the class *name* alone, not
        // diagram-scoped — so e.g. every "Member" declaration across all 9 domain files (the
        // full one and every id-only stub) shares the literal id "Member". Canonical-vs-duplicate
        // bookkeeping below therefore uses *reference identity* (`===`) to distinguish the
        // specific canonical [UmlClass] instance from its duplicates, never `UmlClass.id`
        // equality (which is always true within a group and would make every declaration look
        // like "the canonical one"). The `idRemap` (keyed by the id *string*) is still built and
        // applied to association ends for the general case where ids legitimately do differ
        // (e.g. hand-built diagrams, as in the synthetic tests) — when ids already coincide, the
        // remap is a harmless no-op since every end already points at the (sole surviving) shared id.
        val idRemap = mutableMapOf<String, String>() // dropped declaration's id -> canonical's id
        val canonicalClassByTable = mutableMapOf<String, UmlClass>() // tableName -> the one instance to keep

        for ((tableName, declarations) in entityClassesByTableName) {
            // Step 3: most attributes wins; ties keep the first-encountered declaration (a plain
            // ">" comparison in a left-to-right reduce never replaces the running winner on a tie).
            val canonical =
                declarations.reduce { winner, candidate ->
                    if (candidate.attributes.size >
                        winner.attributes.size
                    ) {
                        candidate
                    } else {
                        winner
                    }
                }
            canonicalClassByTable[tableName] = canonical
            if (declarations.size <= 1) continue

            val canonicalAttrNames = canonical.attributes.map { it.name }.toSet()

            for (dup in declarations) {
                if (dup === canonical) continue

                // Step 4: subset validation.
                val dupAttrNames = dup.attributes.map { it.name }
                val missing = dupAttrNames.filterNot { it in canonicalAttrNames }
                check(missing.isEmpty()) {
                    "Entity merge conflict for table '$tableName' (class '${canonical.name}'): " +
                        "duplicate declaration '${dup.name}' (id='${dup.id}') has attribute(s) " +
                        "${missing.joinToString(prefix = "[", postfix = "]")} not present in the canonical " +
                        "declaration '${canonical.name}' (id='${canonical.id}', attributes=" +
                        "${canonical.attributes.map { it.name }}). This is a real modeling conflict between " +
                        "two domains' independent declarations of the same entity and must be resolved by " +
                        "hand, not silently merged."
                }

                // Step 5: remap (id-keyed, for the general/distinct-id case).
                idRemap[dup.id] = canonical.id
            }
        }

        // ── Steps 6-7: rewrite association ends through idRemap, then dedup associations ──
        val remappedAssociations =
            allElements.filterIsInstance<UmlAssociation>().map { assoc ->
                assoc.copy(ends = assoc.ends.map { end -> end.copy(typeId = idRemap[end.typeId] ?: end.typeId) })
            }

        val seenAssociationSignatures = mutableSetOf<AssociationSignature>()
        val dedupedAssociations =
            remappedAssociations.filter { assoc -> seenAssociationSignatures.add(assoc.dedupSignature()) }

        // ── Step 8: drop non-canonical duplicate UmlClass elements, keep everything else ──
        // Identity check (`===`), not id equality — see the NOTE above steps 3-5.
        val mergedElements =
            allElements.mapNotNull { element ->
                when {
                    element is UmlClass -> {
                        val tableName = element.entityTableName()
                        if (tableName == null || element === canonicalClassByTable[tableName]) element else null
                    }
                    element is UmlAssociation -> null // replaced wholesale by dedupedAssociations below
                    else -> element
                }
            } + dedupedAssociations

        return KumlDiagram(name = mergedName, type = DiagramType.CLASS, elements = mergedElements)
    }

    /**
     * `tableName` tag of this class's applied `«Entity»` stereotype (ERM profile namespace), or
     * `null` if this class isn't Entity-stereotyped / has no `tableName` override.
     *
     * Same namespace-scoped lookup + tag-unwrap approach as `UmlToErmTransformer.deriveTableName`
     * (`kuml-transform-uml-to-erm`'s `ermStereotype`/`stringTag`), reimplemented locally since
     * those helpers are `internal` to that module.
     */
    private fun UmlClass.entityTableName(): String? =
        appliedStereotypes
            .ermStereotype(ErmProfileNames.ENTITY)
            ?.stringTag(ErmProfileNames.TAG_TABLE_NAME)
            ?.takeIf { it.isNotBlank() }

    private fun List<AppliedStereotype>.ermStereotype(name: String): AppliedStereotype? =
        firstOrNull { it.profileNamespace == ErmProfileNames.NAMESPACE && it.stereotypeName.equals(name, ignoreCase = true) }

    private fun AppliedStereotype.stringTag(key: String): String? =
        when (val v = tags[key]) {
            is TagValue.StringVal -> v.v
            is TagValue.EnumVal -> v.valueName
            else -> null
        }

    /**
     * Step 7's dedup key: the *unordered* pair of end type ids and the *unordered* pair of end
     * roles (`null` role normalized to `""` so it sorts/compares deterministically). Two
     * associations with this same signature are considered "the same edge" regardless of which
     * end was modeled as source vs. target in each originating file.
     */
    private data class AssociationSignature(
        val typeIds: List<String>,
        val roles: List<String>,
    )

    private fun UmlAssociation.dedupSignature(): AssociationSignature =
        AssociationSignature(
            typeIds = ends.map { it.typeId }.sorted(),
            roles = ends.map { it.role ?: "" }.sorted(),
        )
}
