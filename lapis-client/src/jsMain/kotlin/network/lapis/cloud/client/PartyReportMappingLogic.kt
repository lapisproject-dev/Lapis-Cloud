package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgAccountMappingRowDto
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PartgMappingChangeDto
import network.lapis.cloud.shared.domain.PartgPositions

/** What a person chose for ONE account in the tab "Kontenzuordnung" (V1.9.289, R4a). */
internal sealed interface MappingChoice {
    data class Position(
        val code: String,
    ) : MappingChoice

    /** "Bewusst nicht im Bericht". */
    data object Excluded : MappingChoice

    /** Back to "not mapped". */
    data object Cleared : MappingChoice
}

/** The value of the "not mapped" entry of the select and of the "deliberately not in the report" entry. */
internal const val MAPPING_NONE = ""
internal const val MAPPING_EXCLUDED = "EXCLUDED"

/** The LEAF positions an account of [type] may be mapped to (shared rule); empty for an equity account. */
internal fun partgMappingTargets(type: LedgerAccountType): List<PartgPositions.Position> =
    PartgPositions.ALL.filter { PartgLedgerRules.allowedTarget(type, it) }

/**
 * The draft of the mapping tab. Nothing here talks to the server: it holds the stored rows, the not yet saved choices and the template
 * suggestions that are shown (and which of them are ticked). [changes] is the exact body of `saveAccountMappings`, with the old state each change
 * expects.
 */
internal class PartgMappingDraft(
    rows: List<PartgAccountMappingRowDto>,
) {
    private val byId: Map<String, PartgAccountMappingRowDto> = rows.associateBy { it.accountId }
    private val choices = LinkedHashMap<String, MappingChoice>()
    private val shownSuggestions = LinkedHashSet<String>()
    private val ticked = LinkedHashSet<String>()

    val hasUnsaved: Boolean get() = choices.isNotEmpty()
    val suggestionsShown: Boolean get() = shownSuggestions.isNotEmpty()

    private fun stored(row: PartgAccountMappingRowDto): MappingChoice? =
        when {
            row.excluded -> MappingChoice.Excluded
            row.positionCode != null -> MappingChoice.Position(row.positionCode!!)
            else -> null
        }

    /** The choice that counts right now: the unsaved one, else the stored one (`null` = not mapped). */
    fun effective(accountId: String): MappingChoice? {
        val row = byId[accountId] ?: return null
        return when (val draft = choices[accountId]) {
            null -> stored(row)
            MappingChoice.Cleared -> null
            else -> draft
        }
    }

    /** Sets the choice of one account; a choice that equals the stored state removes the draft. A target the shared rule forbids is ignored. */
    fun choose(
        accountId: String,
        choice: MappingChoice,
    ) {
        val row = byId[accountId] ?: return
        if (choice is MappingChoice.Position && !PartgLedgerRules.allowedTarget(row.type, choice.code)) return
        shownSuggestions.remove(accountId)
        ticked.remove(accountId)
        val storedChoice = stored(row)
        val normalized = if (choice == MappingChoice.Cleared && storedChoice == null) null else choice
        if (normalized == null || normalized == storedChoice) choices.remove(accountId) else choices[accountId] = normalized
    }

    /** Shows the template suggestions for the accounts that are not mapped and have no unsaved choice; returns how many. */
    fun showSuggestions(): Int {
        byId.values.forEach { row ->
            if (stored(row) == null && row.accountId !in choices && suggestionOf(row) != null) shownSuggestions += row.accountId
        }
        return shownSuggestions.size
    }

    fun suggestionOf(row: PartgAccountMappingRowDto): MappingChoice? =
        when {
            row.templateSuggestsExcluded -> MappingChoice.Excluded
            row.templateSuggestion != null -> MappingChoice.Position(row.templateSuggestion!!)
            else -> null
        }

    fun isSuggestionShown(accountId: String): Boolean = accountId in shownSuggestions

    fun isTicked(accountId: String): Boolean = accountId in ticked

    fun toggleTick(accountId: String) {
        if (accountId !in shownSuggestions) return
        if (!ticked.add(accountId)) ticked.remove(accountId)
    }

    /** Sets the tick explicitly (a widget reports its value, also once when it is created: a toggle would flip it back). */
    fun setTick(
        accountId: String,
        value: Boolean,
    ) {
        if (accountId !in shownSuggestions) return
        if (value) ticked += accountId else ticked.remove(accountId)
    }

    fun tickAll() {
        ticked += shownSuggestions
    }

    /** Moves the ticked suggestions into the draft (this only MARKS them; saving is a separate step). Returns how many. */
    fun acceptTicked(): Int {
        val accepted = ticked.toList()
        accepted.forEach { id ->
            val row = byId[id] ?: return@forEach
            suggestionOf(row)?.let { choose(id, it) }
        }
        return accepted.size
    }

    fun changes(): List<PartgMappingChangeDto> =
        choices.map { (accountId, choice) ->
            val row = byId.getValue(accountId)
            val wasMapped = row.excluded || row.positionCode != null
            PartgMappingChangeDto(
                accountId = accountId,
                expectedMapped = wasMapped,
                expectedPositionCode = row.positionCode,
                expectedExcluded = if (wasMapped) row.excluded else null,
                newPositionCode = (choice as? MappingChoice.Position)?.code,
                newExcluded = choice is MappingChoice.Excluded,
                clear = choice is MappingChoice.Cleared,
            )
        }

    fun discard() {
        choices.clear()
        shownSuggestions.clear()
        ticked.clear()
    }
}

/** The not-mapped rows of the block "Nicht zugeordnet": accounts that are active or have postings in the year, largest absolute balance first. */
internal fun partgUnmappedRows(rows: List<PartgAccountMappingRowDto>): List<PartgAccountMappingRowDto> =
    rows
        .filter { it.positionCode == null && !it.excluded && (it.active || it.postingCount > 0) }
        .sortedWith(
            compareByDescending<PartgAccountMappingRowDto> { kotlin.math.abs(it.balanceCents ?: 0L) }.thenBy { it.number },
        )
