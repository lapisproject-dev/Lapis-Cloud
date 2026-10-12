package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.CountryCodes

private var nationalityIdCounter = 0

/**
 * Welle V1.9.121 -- up to ten nationalities, chosen from the country list: the chosen ones stand above the search field as chips (flag,
 * name, a remove button with its own accessible name), the field below adds the next one.
 *
 * Rules, all announced to screen readers through a polite live region:
 *  - choosing `staatenlos` or `ungeklärt` REPLACES every other entry; choosing a country REPLACES such a special entry;
 *  - at ten entries the field is disabled and says why;
 *  - Backspace edits the search text only, it NEVER removes a chip (a stray key must not delete a legal statement);
 *  - nothing is saved here: the host form's "Speichern" decides, "Abbrechen"/[reset] goes back to the initial state.
 *
 * The widget is not a form control of the grammar (its value is a list, there is no text to validate: every combination it can reach is
 * valid); the host reads [codes] / [subscribe] and compares with the initial codes for its own dirty state.
 */
internal class NationalityMultiSelect(
    private val label: String,
    initial: List<String> = emptyList(),
) : SimplePanel(className = "lapis-nationality") {
    private val uid = "lapis-nat-${nationalityIdCounter++}"
    private var chosen: List<String> =
        initial
            .filter {
                CountryCodes.isNationalityValue(
                    it,
                )
            }.distinct()
            .take(CountryCodes.MAX_NATIONALITIES)
    private val observers = mutableListOf<(List<String>) -> Unit>()

    private val labelTag =
        Tag(TAG.LABEL, content = label, className = "form-label").also {
            it.id = "$uid-label"
            it.setAttribute("for", "$uid-input")
        }
    private val chipList = Tag(TAG.UL, className = "lapis-nationality-chips list-unstyled d-flex flex-wrap gap-2 mb-2")
    private val adder = SearchableSelect(label = null)
    private val limitHint = Div(className = "form-text")
    private val live = Div(className = "visually-hidden lapis-nationality-live")

    /** The chosen codes: sorted, deduplicated, at most ten; `XS` / `ZZ` stand alone. */
    val codes: List<String> get() = chosen.sorted()

    init {
        setAttribute("role", "group")
        setAttribute("aria-labelledby", "$uid-label")
        live.setAttribute("role", "status")
        live.setAttribute("aria-live", "polite")
        adder.input.id = "$uid-input"
        add(labelTag)
        add(chipList)
        add(adder)
        add(limitHint)
        add(live)
        adder.subscribe { picked -> if (!picked.isNullOrEmpty()) onPicked(picked) }
        renderState()
    }

    private fun lang(): String = I18n.language

    private fun onPicked(code: String) {
        val special = code in CountryCodes.NATIONALITY_SPECIAL
        val replaced: List<String>
        val next: List<String>
        when {
            code in chosen -> {
                adder.value = ""
                return
            }
            special -> {
                replaced = chosen
                next = listOf(code)
            }
            chosen.any { it in CountryCodes.NATIONALITY_SPECIAL } -> {
                replaced = chosen
                next = listOf(code)
            }
            chosen.size >= CountryCodes.MAX_NATIONALITIES -> {
                adder.value = ""
                return
            }
            else -> {
                replaced = emptyList()
                next = chosen + code
            }
        }
        chosen = next
        adder.value = ""
        renderState()
        live.content =
            if (replaced.isNotEmpty()) {
                gettext("Ersetzt: %1", replaced.joinToString(", ") { countryDisplayName(it, lang()) })
            } else {
                gettext("%1 hinzugefügt.", countryDisplayName(code, lang()))
            }
        notifyObservers()
    }

    private fun remove(code: String) {
        chosen = chosen - code
        renderState()
        live.content = gettext("%1 entfernt.", countryDisplayName(code, lang()))
        notifyObservers()
    }

    /** Back to [initial] (or any other list), e.g. after "Abbrechen". Not announced as a change by the user. */
    fun reset(initial: List<String>) {
        chosen = initial.filter { CountryCodes.isNationalityValue(it) }.distinct().take(CountryCodes.MAX_NATIONALITIES)
        renderState()
        live.content = ""
        notifyObservers()
    }

    /** Calls [observer] immediately and after every change of [codes]. Returns the unsubscribe function. */
    fun subscribe(observer: (List<String>) -> Unit): () -> Unit {
        observers += observer
        observer(codes)
        return { observers -= observer }
    }

    private fun notifyObservers() {
        val current = codes
        observers.toList().forEach { it(current) }
    }

    private fun renderState() {
        val language = lang()
        chipList.removeAll()
        codes.forEach { code ->
            val name = countryDisplayName(code, language)
            val chip = Tag(TAG.LI, className = "lapis-chip d-inline-flex align-items-center gap-2 border rounded px-2")
            chip.setAttribute("data-code", code)
            (countryFlagImage(code) ?: flagSpacer()).let { chip.add(it) }
            chip.untrustedSpan(name)
            val remove =
                newActionButton(ActionIcon.CLOSE, gettext("Entfernen"), ButtonStyle.LINK, iconOnly = true).apply {
                    addCssClass("lapis-chip-remove")
                    setAttribute("type", "button")
                    setAttribute("aria-label", gettext("Staatsangehörigkeit „%1“ entfernen", name))
                    title = gettext("Entfernen")
                }
            remove.onClick { remove(code) }
            chip.add(remove)
            chipList.add(chip)
        }
        val built = buildCountryListOptions(CountryListMode.NATIONALITY, language)
        adder.maxShown = built.options.size + 1
        adder.searchTexts = built.searchTexts
        adder.separatorAfter = built.separatorAfter
        adder.optionIcon = ::countryDecoration
        adder.options = listOf("" to gettext("— bitte wählen —")) + built.options.filter { it.first !in chosen }
        val full = chosen.size >= CountryCodes.MAX_NATIONALITIES
        adder.disabled = full
        limitHint.content = if (full) gettext("Höchstens %1 Staatsangehörigkeiten.", CountryCodes.MAX_NATIONALITIES.toString()) else null
    }
}

internal fun Container.nationalityMultiSelect(
    label: String,
    initial: List<String> = emptyList(),
    init: (NationalityMultiSelect.() -> Unit)? = null,
): NationalityMultiSelect {
    val widget = NationalityMultiSelect(label, initial)
    init?.invoke(widget)
    add(widget)
    return widget
}
