package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.FormControl
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.AbstractText
import io.kvision.form.text.Password
import io.kvision.form.text.Text
import io.kvision.form.text.TextArea
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.form.upload.Upload
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.HPanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.Element

/*
 * Welle V1.4.28 (W4a) "Formular-Grammatik, Teil 1" -- die Bausteine. Vertrag: docs/architecture/ui-ux-guideline.adoc,
 * Abschnitt "Wave W4a". Kurzfassung:
 *
 *  - Pflicht wird EINMAL je Formular gekennzeichnet (drei Fälle, siehe [LapisForm.buttons]); `aria-required` steht
 *    IMMER am Feld, egal ob ein Stern gezeigt wird.
 *  - Fehler stehen am Feld (`invalid-feedback`, per `aria-describedby` verknüpft), nie als ein einziger Sammelsatz über
 *    dem Knopf (Ausnahme: ein Formular mit mehreren Feldern zeigt ZUSÄTZLICH eine Sammelmeldung als `role="alert"`; ein
 *    Einfeld-Formular meldet nur am Feld). Der Fehlerslot ist DAUERHAFT montiert: ein Fehler ändert nur `content` und eine
 *    Klasse, nie die Struktur -- das ist für Screenreader wichtig (eine Live-Region meldet Textänderungen), NICHT für das
 *    Layout: ein sichtbarer Fehler (`display: block`) verschiebt alles darunter, auch den Absenden-Knopf.
 *  - Deshalb der Mausriegel ([PointerGate]): `blur` feuert schon beim `mousedown` auf einem Knopf. Würde
 *    dort sofort geprüft, erschiene der Fehler, der Knopf rutschte zwischen `mousedown` und `mouseup` weg, `mouseup` fiele auf
 *    ein anderes Element und der `click` ginge verloren -- der erste Klick auf "Absenden" wäre nach einem ungültigen Feld
 *    verschluckt. Solange die Maustaste auf einem Knopf gedrückt ist, wird die `blur`-Prüfung zurückgestellt und erst nach
 *    `mouseup` (samt `click`) nachgeholt. Gewählt statt einer reservierten Fehlerhöhe: die Meldung umbricht je nach Sprache und
 *    Breite unterschiedlich lang, eine feste Höhe garantierte also keine Stabilität und ließe zwischen JEDEM Feldpaar
 *    ein Loch. Tastatur (Tab) ist nicht betroffen: dort gibt es kein `mouseup`, das ein Ziel verfehlen könnte.
 *  - Geprüft wird beim Verlassen eines Feldes NUR, wenn es angefasst wurde (oder schon einmal abgesendet wurde) --
 *    ein durchgetabbtes, nie berührtes Feld bleibt still. Tippen räumt einen Fehler auf, erzeugt nie einen neuen.
 *  - Abbrechen links, bestätigende Aktion rechts (Richtlinie 2.5 / R27); destruktive Aktion in einer eigenen Zone
 *    UNTER der Knopfzeile. Kein Spinner, kein grüner "gültig"-Zustand.
 *  - Attribute laufen ausschließlich über KVisions `Widget.setAttribute` (überlebt Re-Render und Sprachwechsel), nie über
 *    rohes `getElement()?.setAttribute`, und nie über einen Insert-Hook (ClientLateHookRatchetTest).
 */

/** Ergebnis einer Feldregel. [Invalid.message] ist BEREITS aufgelöster Text (`gettext`), nie ein `tr()`-Marker. */
sealed interface FieldCheck {
    data object Ok : FieldCheck

    data class Invalid(
        val message: String,
    ) : FieldCheck
}

/**
 * Fasst mehrere Formulare EINES Dialogs zusammen (der Mitglieder-Editor hat sechs unabhängig gespeicherte Formulare in einem
 * Modal): dieselbe Legende ("* Pflichtfeld") steht dann nur über dem ERSTEN Formular, das sie braucht -- sechsmal derselbe Satz
 * im selben Dialog wäre Rauschen. Die Sterne an den Pflicht-Labels bleiben in jedem Formular. Eine ANDERE Legende ("Alle Felder
 * sind Pflichtfelder." meint nur das eigene Formular) wird nie unterdrückt.
 */
class LegendGroup {
    internal val shown = mutableSetOf<String>()
}

/**
 * Erzeugt ein Formular: `vPanel(spacing = 8)` mit Klasse `lapis-form` und einem (zunächst leeren) Legenden-Slot.
 * [legendGroup]: siehe [LegendGroup].
 */
fun Container.lapisForm(
    legendGroup: LegendGroup? = null,
    init: (LapisForm.() -> Unit)? = null,
): LapisForm {
    val form = LapisForm(vPanel(spacing = 8) { addCssClass("lapis-form") }, legendGroup)
    init?.invoke(form)
    return form
}

private var fieldIdCounter = 0

private const val REVEAL_ICON = "fas fa-eye"
private const val HIDE_ICON = "fas fa-eye-slash"

/** Die Attribute, die Passwortmanager bitten, ein Geheimnisfeld in Ruhe zu lassen. Eine Bitte, keine Garantie. */
private val SECRET_FIELD_ATTRIBUTES: Map<String, String> =
    mapOf(
        "autocomplete" to "off",
        "data-1p-ignore" to "true",
        "data-lpignore" to "true",
        "data-form-type" to "other",
        "spellcheck" to "false",
        "autocapitalize" to "off",
    )

/**
 * Das Schloss-Zeichen neben einem Geheimnisfeld: ein Font-Awesome-Icon (Richtlinie 2.14 verbietet Emoji), für Screenreader
 * unsichtbar -- es trägt keine Information, die nicht schon im Label steht.
 */
fun HPanel.lockIcon() {
    span {
        addCssClasses("fas fa-lock text-muted")
        setAttribute("aria-hidden", "true")
    }
}

private class CrossRule(
    val focusOn: Widget?,
    val check: () -> FieldCheck,
)

/**
 * Mausriegel gegen den verlorenen ersten Klick (siehe Kopfkommentar): zwischen `mousedown` und `mouseup` auf einem Knopf
 * wird keine `blur`-Prüfung angezeigt, sie wird nach dem Klick nachgeholt. Es gibt genau EIN Paar Beobachter am `document`
 * für die gesamte Anwendung (Modal-Fußleisten liegen NICHT im Formular-Panel, und ein verborgenes KVision-Modal bleibt im
 * DOM -- pro Formular registrierte Beobachter würden sich also nie lösen und bei jeder Dialog-Öffnung anwachsen).
 *
 * Der Riegel ist global und wird nie abgebaut -- deshalb darf er nie "hängen bleiben": bleibt das `mouseup` aus (Zeiger außerhalb
 * des Fensters losgelassen, Fensterwechsel per Alt-Tab, Kontextmenü, abgebrochene Zeigergeste), bliebe `pointerDownOnButton`
 * gesetzt und JEDE spätere `blur`-Prüfung der Anwendung würde still zurückgestellt. Deshalb gibt jedes dieser Ereignisse den
 * Riegel frei ([release]), und ein Zeitlimit ([safetyTimeoutMs]) fängt den Rest ab. Freigeben holt die zurückgestellten
 * Prüfungen nach.
 */
internal object PointerGate {
    private var installed = false
    private var pointerDownOnButton = false
    private var safetyTimer: Int? = null
    private val deferredBlurs = linkedSetOf<LapisField>()

    /** Längste Zeit, die ein Knopfdruck den Riegel halten darf; ein Klick dauert Millisekunden, ein Halten über Sekunden ist ein verlorenes `mouseup`. */
    internal var safetyTimeoutMs: Int = 5000

    /** `true`, solange ein Knopfdruck läuft (für Tests). */
    internal val isHeld: Boolean get() = pointerDownOnButton

    fun ensureInstalled() {
        if (installed) return
        installed = true
        document.addEventListener(
            "mousedown",
            { event ->
                // Ein früheres, nie beendetes Drücken (verlorenes `mouseup`) wird zuerst freigegeben.
                if (pointerDownOnButton) release()
                pointerDownOnButton = (event.target as? Element)?.closest("button, .btn, [role=button]") != null
                if (pointerDownOnButton) safetyTimer = window.setTimeout({ release() }, safetyTimeoutMs)
            },
            true,
        )
        document.addEventListener(
            "mouseup",
            {
                if (pointerDownOnButton) {
                    // Nach dem `click`, der im selben Task auf `mouseup` folgt: dann erst ist der Klick sicher zugestellt.
                    window.setTimeout({ release() }, 0)
                }
            },
            true,
        )
        // Ereignisse, nach denen ein `mouseup` ausbleiben kann: das Drücken ist zu Ende, der Riegel muss weg.
        window.addEventListener("blur", { release() })
        document.addEventListener("pointercancel", { release() }, true)
        document.addEventListener("contextmenu", { release() }, true)
        document.addEventListener("dragend", { release() }, true)
        document.documentElement?.addEventListener("mouseleave", { release() })
    }

    private fun release() {
        safetyTimer?.let { window.clearTimeout(it) }
        safetyTimer = null
        pointerDownOnButton = false
        val pending = deferredBlurs.toList()
        deferredBlurs.clear()
        pending.forEach { it.onBlur() }
    }

    fun blurOrDefer(field: LapisField) {
        if (pointerDownOnButton) deferredBlurs += field else field.onBlur()
    }
}

class LapisForm internal constructor(
    /** Der Formular-Container (Klasse `lapis-form`). Weitere Inhalte (Hinweisboxen, Abschnittstitel) dürfen hier hinein. */
    val panel: VPanel,
    private val legendGroup: LegendGroup? = null,
) {
    private val fieldList = mutableListOf<LapisField>()
    private val crossRules = mutableListOf<CrossRule>()

    /** Über dem ersten Feld montiert (Reihenfolge!), aber leer und verborgen, bis [buttons] die Legende entscheidet. */
    private val legendSlot: Div =
        panel.div {
            addCssClasses("text-muted small")
            hide()
        }

    private var alertSlot: Div? = null

    /** `true`, solange die Sammelmeldung der Validierung (nicht ein Servertext!) angezeigt wird -- nur die räumt sich selbst. */
    private var collectiveShown = false

    init {
        PointerGate.ensureInstalled()
    }

    val fields: List<LapisField> get() = fieldList

    internal fun handleBlur(field: LapisField) {
        PointerGate.blurOrDefer(field)
    }

    fun textField(
        label: String,
        type: InputType = InputType.TEXT,
        value: String? = null,
        required: Boolean = false,
        autocomplete: Autocomplete? = null,
        hint: String? = null,
        host: Container = panel,
        requiredMessage: String? = null,
        rule: (String) -> FieldCheck = { FieldCheck.Ok },
        init: ((Text) -> Unit)? = null,
    ): LapisField {
        val control = host.text(type = type, value = value, label = label)
        if (autocomplete != null) control.autocomplete = autocomplete
        init?.invoke(control)
        require(autocomplete == null || control.autocomplete == autocomplete) {
            "textField(autocomplete = ...) and init { autocomplete = ... } must not both set the attribute"
        }
        return wire(control = control, label = label, required = required, hint = hint, requiredMessage = requiredMessage, rule = rule)
    }

    /**
     * Passwortfeld. [autocomplete] `null` setzt kein Attribut. [suppressManagers] ist für Geheimnisse, die KEIN Zugang
     * des Bearbeiters sind (Stream-Schlüssel, ein für einen anderen Menschen erzeugtes Passwort): `autocomplete="off"`
     * plus Manager-Bitten. **Ehrlich:** `autocomplete="off"` wird bei Passwortfeldern browserabhängig ignoriert; die
     * `data-*`-Attribute sind eine Bitte an 1Password/LastPass/Bitwarden, keine Garantie.
     * [reveal] hängt einen Aufdecken-Knopf (`aria-pressed`) unter das Feld. [actions] erhält den Container dieser
     * Knopfzeile für weitere Knöpfe (z. B. "Neu erzeugen").
     */
    fun passwordField(
        label: String,
        value: String? = null,
        required: Boolean = false,
        autocomplete: Autocomplete? = null,
        suppressManagers: Boolean = false,
        hint: String? = null,
        reveal: Boolean = false,
        host: Container = panel,
        requiredMessage: String? = null,
        rule: (String) -> FieldCheck = { FieldCheck.Ok },
        actions: ((HPanel) -> Unit)? = null,
        init: ((Password) -> Unit)? = null,
    ): LapisField {
        // `suppressManagers` setzt `autocomplete="off"` selbst; ein zweiter Wert daneben wäre still wirkungslos oder gegenläufig.
        require(!(suppressManagers && autocomplete != null)) {
            "passwordField: autocomplete and suppressManagers are mutually exclusive (suppressManagers sets autocomplete=off)"
        }
        val control = host.password(value = value, label = label)
        if (autocomplete != null) control.autocomplete = autocomplete
        init?.invoke(control)
        val field =
            wire(
                control = control,
                label = label,
                required = required,
                hint = hint,
                requiredMessage = requiredMessage,
                rule = rule,
                // Die Knopfzeile gehört zum Feld und steht VOR Hinweis und Fehlerslot.
                beforeMessages =
                    if (reveal || actions != null) {
                        { slotHost ->
                            val row = slotHost.hPanel(spacing = 8) { addCssClass("lapis-field-actions") }
                            if (reveal) row.add(revealToggle(control))
                            actions?.invoke(row)
                        }
                    } else {
                        null
                    },
            )
        if (suppressManagers) {
            (control.input as? Widget)?.let { widget ->
                SECRET_FIELD_ATTRIBUTES.forEach { (name, attr) -> widget.setAttribute(name, attr) }
            }
        }
        return field
    }

    fun textAreaField(
        label: String,
        rows: Int? = null,
        value: String? = null,
        required: Boolean = false,
        hint: String? = null,
        host: Container = panel,
        requiredMessage: String? = null,
        rule: (String) -> FieldCheck = { FieldCheck.Ok },
        init: ((TextArea) -> Unit)? = null,
    ): LapisField {
        val control = host.textArea(rows = rows, value = value, label = label)
        init?.invoke(control)
        return wire(control = control, label = label, required = required, hint = hint, requiredMessage = requiredMessage, rule = rule)
    }

    /**
     * Auswahlfeld. Hinweis- und Fehlerslot stehen als GESCHWISTER hinter dem `<select>`, nie darin:
     * `Select.add()` delegiert an das `<select>`, dort würden die Kinder nie gerendert und `aria-describedby` zeigte auf
     * einen Nachkommen (W4a-Audit-Befund). Steht das Auswahlfeld in einer Flex-Zeile ([host], z. B. neben einem Knopf), gehört
     * [slotHost] auf den Container UNTER der Zeile: `.invalid-feedback` hat `width: 100%` und quetschte sonst Feld und Knopf
     * zusammen. [required] verlangt einen nicht-leeren Wert (ein `""`-Eintrag wie
     * "— kein Tarif —" gilt als leer).
     */
    fun selectField(
        label: String,
        options: List<Pair<String, String>>? = null,
        value: String? = null,
        required: Boolean = false,
        hint: String? = null,
        host: Container = panel,
        slotHost: Container = host,
        requiredMessage: String? = null,
        rule: (String) -> FieldCheck = { FieldCheck.Ok },
        init: ((Select) -> Unit)? = null,
    ): LapisField {
        val control = host.select(options = options, value = value, label = label)
        init?.invoke(control)
        return wire(
            control = control,
            label = label,
            required = required,
            hint = hint,
            requiredMessage = requiredMessage,
            rule = rule,
            slotHost = slotHost,
        )
    }

    /**
     * Zustimmungs-/Schalter-Checkbox mit FELDGEBUNDENEM Fehlerslot (löst die W4a-Notlösung "Kreuzregel in die Sammelfläche"
     * ab). [required] `== true` heißt "muss angekreuzt sein" -- NICHT über den Leerwert-Pfad von [LapisField] (KVisions
     * `CheckBox.getValueAsString()` liefert `"false"`, also nicht-leer, und `required` wäre wirkungslos), sondern über eine
     * eigene Prüfung im Feld. `aria-required` wird trotzdem gesetzt.
     */
    fun checkField(
        label: String,
        value: Boolean = false,
        required: Boolean = false,
        hint: String? = null,
        host: Container = panel,
        requiredMessage: String? = null,
        rule: (Boolean) -> FieldCheck = { FieldCheck.Ok },
        init: ((CheckBox) -> Unit)? = null,
    ): LapisField {
        val control = host.checkBox(value = value, label = label)
        init?.invoke(control)
        return wire(
            control = control,
            label = label,
            required = required,
            hint = hint,
            requiredMessage = requiredMessage,
            rule = { text -> rule(text == "true") },
            slotHost = host,
        )
    }

    /**
     * Für Steuerelemente, die die Grammatik nicht selbst baut (`select`, `upload`): markiert Pflicht, hängt Hinweis und
     * Fehlerslot an. [control] muss VOR dem Aufruf in [panel] eingehängt sein (der Fehlerslot folgt ihm direkt).
     */
    fun <C : FormControl> register(
        control: C,
        label: String,
        required: Boolean = false,
        hint: String? = null,
        requiredMessage: String? = null,
        rule: (String) -> FieldCheck = { FieldCheck.Ok },
    ): LapisField = wire(control = control, label = label, required = required, hint = hint, requiredMessage = requiredMessage, rule = rule)

    /**
     * Formularübergreifende Regel (Passwortgleichheit, Zustimmungs-Checkbox). Mit [field] wird der Fehler an DIESEM Feld
     * gezeigt (und bei dessen `blur` geprüft); ohne [field] steht er in der Sammelfläche, [focusOn] bekommt dann den Fokus
     * (immer `control.input`, siehe unten) und [watch] räumt die Sammelmeldung bei Änderung.
     */
    fun crossFieldRule(
        field: LapisField? = null,
        focusOn: Widget? = null,
        watch: List<Widget> = emptyList(),
        check: () -> FieldCheck,
    ) {
        if (field != null) {
            field.extraChecks += check
        } else {
            crossRules += CrossRule(focusOn = focusOn, check = check)
            // Eine Zustimmungs-Checkbox hat kein LapisField: ihre Änderung muss die Sammelmeldung von sich aus räumen können.
            // Zurückgestellt (`setTimeout`), weil KVision den Wert der Checkbox erst in seinem eigenen `change`-Handler nachzieht.
            // [focusOn] muss das ELEMENT sein, das `focus()` wirklich fokussiert (`control.input`, nicht der Wrapper-<div>). [watch]
            // nennt weitere Elemente, deren Änderung die Regel berührt (z. B. die übrigen Checkboxen einer Empfängerliste):
            // `change` bubbelt nur den eigenen Ast hoch, ein Listener am ersten Element sähe die anderen nie.
            (listOfNotNull(focusOn) + watch).distinct().forEach { widget ->
                widget.onEvent { change = { window.setTimeout({ onFieldStateChanged() }, 0) } }
            }
        }
    }

    /**
     * Knopfzeile; SCHLIESST das Formular ab -- erst hier ist die Feldmenge komplett und die Pflicht-Kennzeichnung
     * entscheidbar:
     *
     *  - (a) gemischt (mind. 1 Pflicht UND mind. 1 optional): `*` am Label jedes Pflichtfelds, Legende "* Pflichtfeld";
     *  - (b) mind. 3 Felder, alle Pflicht: keine Sterne, Legende "Alle Felder sind Pflichtfelder.";
     *  - (c) genau 2 Felder, beide Pflicht: weder Sterne noch Legende. Ein EINZELNES Pflichtfeld fällt NICHT unter (c): es wird wie
     *    (a) mit Stern und Legende gekennzeichnet (sonst ist es als Pflicht nicht erkennbar, siehe Audit V1.4.29 M-7).
     *
     * [primary] und [cancel] müssen NEU erzeugt, nicht bereits eingehängt sein. [primary] darf fehlen, wenn die einzige Aktion
     * destruktiv ist (dann steht sie allein in der Zone darunter und das Formular hat KEIN `PRIMARY`; erlaubt, R28 verbietet nur zwei). Reihenfolge: Abbrechen links, Primäraktion
     * rechts; genau EIN `PRIMARY` je Formular (R28, Augenprüfung). [destructive] steht in einer eigenen Zone darunter.
     */
    fun buttons(
        primary: Button?,
        cancel: Button? = null,
        destructive: Button? = null,
    ): HPanel {
        decideRequiredMarking()
        val block = panel.simplePanel()
        mountAlertSlot(block)
        val row = block.hPanel(spacing = 8)
        if (cancel != null) {
            cancel.style = ButtonStyle.OUTLINESECONDARY
            row.add(cancel)
        }
        if (primary != null) row.add(primary)
        if (destructive != null) {
            block.div {
                addCssClass("lapis-form-danger-zone")
                add(destructive)
            }
        }
        return row
    }

    /**
     * Schließt ein Formular ab, dessen Knöpfe NICHT in der Formularfläche stehen -- das Modal mit seiner Fußleiste
     * (`modal.addButton`). Entscheidet die Pflicht-Kennzeichnung und montiert die Sammelfläche am Ende des Formulars;
     * die Knöpfe ruft der Aufrufer selbst über [submit] an.
     */
    fun finish() {
        decideRequiredMarking()
        mountAlertSlot(panel.simplePanel())
    }

    /**
     * Beim Formularbau montiert, leer: Screenreader melden eine TEXTÄNDERUNG in einer schon vorhandenen Live-Region,
     * nicht eine samt Text eingefügte (gleiche Begründung wie `dataStatusRegion`). Im eigenen [host], damit die leere Fläche
     * keinen `spacing`-Abstand des Formulars erzeugt.
     */
    private fun mountAlertSlot(host: Container) {
        alertSlot =
            host.div("") {
                addCssClass("lapis-form-alert")
                setAttribute("role", "alert")
            }
    }

    /**
     * Validieren (force) -> bei Fehlern Fokus/Scroll/Sammelmeldung und KEIN Aufruf; sonst [runBusy] mit `aria-busy` am Knopf
     * (kein Spinner, keine Textänderung).
     */
    fun submit(
        button: Button,
        action: suspend () -> Unit,
    ) {
        if (!validateAndReport()) return
        runBusy(button, action = action)
    }

    /**
     * Der Prüf-Teil von [submit], für Formulare, deren Absenden erst noch eine Bestätigung braucht (Wiederherstellen): alle
     * Felder mit `force` prüfen, den ersten fehlerhaften fokussieren und -- bei mehr als einem Feld -- die Sammelmeldung
     * zeigen. `true`, wenn alles gültig ist. EINE Kopie des Sammelsatzes, nicht eine je Bildschirm.
     *
     * Ein Einfeld-Formular ohne Querregel zeigt KEINE Sammelmeldung: der Fehler steht am Feld, der Fokus liegt dort, und
     * "Bitte korrigieren Sie diese Felder: Bezeichnung." wäre dieselbe Aussage ein zweites Mal.
     */
    fun validateAndReport(): Boolean {
        clearFormError()
        val invalidFields = fieldList.filterNot { it.validate(force = true) }
        // Jede Querregel GENAU EINMAL (die Meldung wird gleich mit verwendet).
        val ruleFailures =
            crossRules.mapNotNull { rule -> (rule.check() as? FieldCheck.Invalid)?.let { rule to resolvedAttributeText(it.message) } }
        if (invalidFields.isEmpty() && ruleFailures.isEmpty()) return true
        val target = invalidFields.firstOrNull()
        if (target != null) {
            target.focus()
        } else {
            ruleFailures.firstNotNullOfOrNull { it.first.focusOn }?.let { focusAndReveal(it) }
        }
        val singleFieldForm = fieldList.size == 1 && crossRules.isEmpty()
        if (!singleFieldForm) {
            showFormError(collectiveMessage(invalidFields, ruleFailures.map { it.second }))
            collectiveShown = true
        }
        return false
    }

    /**
     * `aria-busy` am Knopf + Doppelklick-Schutz ([runGuardedAction]) um [action]; keine Prüfung.
     * [restoreDisabled] wird durchgereicht, siehe [runGuardedAction] KDoc.
     */
    fun runBusy(
        button: Button,
        restoreDisabled: () -> Boolean = { false },
        action: suspend () -> Unit,
    ) {
        button.setAttribute("aria-busy", "true")
        runGuardedAction(button, restoreDisabled) {
            try {
                action()
            } finally {
                button.setAttribute("aria-busy", "false")
            }
        }
    }

    /** Server-/übergreifender Fehler in derselben `role="alert"`-Fläche. */
    fun showFormError(message: String) {
        // Hart scheitern statt still einen `role="alert"` samt Inhalt einzufügen: eine erst beim Fehler montierte Live-Region
        // wird von Screenreadern nicht gemeldet (siehe [mountAlertSlot]) -- der Fehler ginge unbemerkt unter.
        val slot =
            checkNotNull(alertSlot) {
                "LapisForm.showFormError() before buttons()/finish(): the alert region is mounted there, not on demand"
            }
        slot.content = message
        slot.addCssClass("lapis-form-alert--shown")
        collectiveShown = false
    }

    fun clearFormError() {
        collectiveShown = false
        val slot = alertSlot ?: return
        slot.content = ""
        slot.removeCssClass("lapis-form-alert--shown")
    }

    /**
     * Meldet ein Feld wieder ab (der Entfernen-Knopf einer Buchungszeile). Ohne das blockierte das Feld einer entfernten Zeile
     * [validateAndReport] unsichtbar für immer: es steht nicht mehr im DOM, hält aber seinen Fehler und seine Regel. Räumt den
     * Fehler, nimmt das Feld aus [fields] und leert seine Zusatzprüfungen. Idempotent: ein zweiter Aufruf ist wirkungslos.
     *
     * Eine Querregel ([crossFieldRule] ohne `field`), die ein abgemeldetes Feld liest, ist ein Aufruferfehler -- sie bleibt
     * unberührt. Das Entfernen des Widgets bleibt Sache des Aufrufers (`rowsPanel.remove(rowPanel)`); der Fehlerslot verschwindet
     * mit dem Zeilencontainer.
     */
    fun unregister(field: LapisField) {
        // ZUERST aus der Liste, dann räumen: `clearError()` ruft `onFieldStateChanged()`, und das darf das Feld nicht mehr prüfen.
        if (!fieldList.remove(field)) return
        field.detachInternal()
        // Die Sammelmeldung kann durch das Entfernen gültig geworden sein.
        onFieldStateChanged()
    }

    /** Ein Feld hat seinen Fehler verloren / eine Querregel-Eingabe hat sich geändert: die Sammelmeldung räumt sich, sobald alles gültig ist. */
    internal fun onFieldStateChanged() {
        if (!collectiveShown) return
        if (fieldList.any { it.hasVisibleError }) return
        if (crossRules.any { it.check() is FieldCheck.Invalid }) return
        clearFormError()
    }

    /** `true`, wenn alle Feldregeln und Querregeln halten (`force = true` markiert alle Felder als geprüft und zeigt Fehler). */
    fun validateAll(force: Boolean): Boolean {
        var ok = true
        fieldList.forEach { if (!it.validate(force)) ok = false }
        if (crossRules.any { it.check() is FieldCheck.Invalid }) ok = false
        return ok
    }

    private fun collectiveMessage(
        invalidFields: List<LapisField>,
        ruleMessages: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        if (invalidFields.isNotEmpty()) {
            // resolvedLabel, NIE der tr()-Marker: sonst steht "###KvI18nS###E-Mail" in der Meldung.
            parts += gettext("Bitte korrigieren Sie diese Felder: %1.", invalidFields.joinToString(", ") { it.resolvedLabel })
        }
        parts += ruleMessages
        return parts.joinToString(" ")
    }

    private fun decideRequiredMarking() {
        val total = fieldList.size
        val requiredCount = fieldList.count { it.required }
        when {
            // (a) gemischt -- ODER ein Formular mit genau einem Feld, das Pflicht ist: ein einzelnes Pflichtfeld ohne Stern und ohne
            // Legende wäre als Pflicht nicht erkennbar (Alt-Label "Entscheidungsnotiz (Pflicht)"), also wie (a) markieren.
            requiredCount in 1 until total || (total == 1 && requiredCount == 1) -> {
                fieldList.filter { it.required }.forEach { it.appendRequiredMark() }
                if (legendGroup?.shown?.add("* Pflichtfeld") != false) {
                    legendSlot.content = tr("* Pflichtfeld")
                    legendSlot.show()
                }
            }
            requiredCount == total && total >= 3 -> {
                if (legendGroup?.shown?.add("Alle Felder sind Pflichtfelder.") != false) {
                    legendSlot.content = tr("Alle Felder sind Pflichtfelder.")
                    legendSlot.show()
                }
            }
        }
    }

    private fun <C : FormControl> wire(
        control: C,
        label: String,
        required: Boolean,
        hint: String?,
        requiredMessage: String?,
        rule: (String) -> FieldCheck,
        beforeMessages: ((Container) -> Unit)? = null,
        slotHost: Container = panel,
    ): LapisField {
        val inputWidget = control.input as? Widget
        val baseId = inputWidget?.id ?: "lapis-field-${fieldIdCounter++}"
        // Nur Textsteuerelemente nehmen Hinweis und Fehlerslot in ihren eigenen Wrapper: `Select.add()` delegiert an das
        // <select>, dort würden die Kinder nie gerendert (und `aria-describedby` zeigte auf einen Nachkommen). Alles andere
        // (Select, Upload) bekommt die Slots als Geschwister direkt hinter sich im Formular.
        val host: Container = if (control is AbstractText) control else slotHost
        beforeMessages?.invoke(host)
        val describedBy = mutableListOf<String>()
        if (hint != null) {
            val hintId = "$baseId-hint"
            host.div(hint) {
                addCssClass("form-text")
                id = hintId
            }
            describedBy += hintId
        }
        val errorId = "$baseId-error"
        // addCssClasses, nicht addCssClass("a b"): der Wächter verifyNoMultiClassAddCssClass bricht sonst `check`.
        val errorSlot =
            host.div {
                addCssClasses("invalid-feedback lapis-field-error")
                id = errorId
            }
        describedBy += errorId
        inputWidget?.let {
            if (required) it.setAttribute("aria-required", "true")
            it.setAttribute("aria-invalid", "false")
            it.setAttribute("aria-describedby", describedBy.joinToString(" "))
        }
        val field =
            LapisField(
                control = control,
                resolvedLabel = resolvedAttributeText(label),
                required = required,
                requiredMessage = requiredMessage,
                rule = rule,
                errorSlot = errorSlot,
                inputWidget = inputWidget,
                onStateChange = { onFieldStateChanged() },
                owner = this,
            )
        // `onEvent` am Control landet am <input> (AbstractText delegiert), und genau dort feuert blur -- blur bubbelt nicht.
        // Select/CheckBox delegieren NICHT: ihr Wrapper bekäme nie ein `blur`, also hängt der Listener an `control.input`.
        val eventTarget: Widget? = if (control is AbstractText) control as Widget else control.input as? Widget
        eventTarget?.onEvent {
            blur = { handleBlur(field) }
            input = { field.onInput() }
            change = { field.onInput() }
        }
        fieldList += field
        return field
    }

    private fun revealToggle(control: Password): Button {
        val showLabel = tr("Passwort anzeigen")
        val hideLabel = tr("Passwort verbergen")
        val toggle =
            Button("", icon = REVEAL_ICON, style = ButtonStyle.OUTLINESECONDARY) {
                title = showLabel
                setAttribute("aria-label", resolvedAttributeText(showLabel))
                setAttribute("aria-pressed", "false")
            }
        var revealed = false
        toggle.onClick {
            revealed = !revealed
            control.type = if (revealed) InputType.TEXT else InputType.PASSWORD
            val next = if (revealed) hideLabel else showLabel
            toggle.icon = if (revealed) HIDE_ICON else REVEAL_ICON
            toggle.title = next
            toggle.setAttribute("aria-label", resolvedAttributeText(next))
            toggle.setAttribute("aria-pressed", revealed.toString())
        }
        return toggle
    }
}

class LapisField internal constructor(
    val control: FormControl,
    /** [resolvedAttributeText] des Labels -- für Sammelmeldungen, NIE der `tr()`-Marker. Ändert nur [relabel]. */
    resolvedLabel: String,
    internal val required: Boolean,
    private val requiredMessage: String?,
    private val rule: (String) -> FieldCheck,
    private val errorSlot: Div,
    private val inputWidget: Widget?,
    private val onStateChange: () -> Unit = {},
    private val owner: LapisForm? = null,
) {
    var resolvedLabel: String = resolvedLabel
        private set

    /** Das Feld wurde getippt oder geändert. */
    var dirty: Boolean = false
        private set

    private var submitted = false
    private var errorShown = false
    internal val extraChecks = mutableListOf<() -> FieldCheck>()

    val value: String get() = control.getValueAsString().orEmpty()

    /**
     * Setzt den Wert programmatisch (z. B. beim Laden gespeicherter Einstellungen). Das ist kein Tippen: [dirty] bleibt
     * unberührt, und es läuft weder ein `input`-Ereignis noch eine Prüfung -- ein bestehender Fehler bliebe ohne
     * Folgeaufruf STEHEN, obwohl der neue Wert gültig sein kann.
     *
     * Pflicht des Aufrufers: unmittelbar danach [validate] (`force = false`) aufrufen, wenn ein Fehler angezeigt sein
     * könnte (Vorbelegung aus einem Auswahlfeld, "Neu erzeugen"), bzw. [clearError], wenn das Feld gerade geleert und
     * gesperrt wird. Ohne Folgeaufruf nur dort, wo nachweislich noch kein Fehler stehen kann (erstes Laden). Ein Wert
     * wird NIE am [LapisField] vorbei über das Control geschrieben -- das umginge auch diese Pflicht.
     * Siehe `docs/architecture/ui-ux-guideline.adoc`, Zeile "Programmatic writes".
     */
    fun setValue(newValue: String?) {
        // Laut scheitern statt still nichts tun: ein Aufruf, der wirkungslos verpufft, verschwiege dem Aufrufer, dass sein Wert
        // nie im Feld stand. (Ein Upload kennt nur [reset]; eine Checkbox hat keinen Textwert.)
        when (control) {
            is AbstractText -> control.value = newValue
            is Select -> control.value = newValue
            else -> error("LapisField.setValue() supports text and select controls only, not ${control::class.simpleName}")
        }
    }

    /**
     * Leert das Feld nach einer erfolgreichen Aktion und setzt es in den Ausgangszustand zurück (nicht angefasst, nicht
     * abgesendet, kein Fehler) -- sonst meldete das leere Feld beim nächsten Verlassen sofort "muss ausgefüllt werden".
     */
    fun reset() {
        when (control) {
            is Upload -> control.clearInput()
            is CheckBox -> control.value = false
            else -> setValue(null)
        }
        dirty = false
        submitted = false
        clearError()
    }

    /** Kurzform für `form.unregister(this)` -- siehe [LapisForm.unregister]. */
    fun detach() {
        owner?.unregister(this)
    }

    internal fun detachInternal() {
        clearError() // räumt auch `is-invalid` und `aria-invalid`
        // `setAttribute` (KVision), nie rohes `getElement()?.setAttribute`.
        inputWidget?.setAttribute("aria-describedby", "")
        extraChecks.clear()
    }

    /** `true`, solange ein Fehler am Feld angezeigt wird. */
    internal val hasVisibleError: Boolean get() = errorShown

    /** Ob das Feld heute gültig wäre -- ohne Anzeige und ohne Zustandsänderung. */
    fun isValid(): Boolean = evaluate() is FieldCheck.Ok

    /** Prüft und zeigt (bei `force` oder wenn das Feld schon angefasst wurde) einen Fehler. */
    fun validate(force: Boolean): Boolean {
        if (force) submitted = true
        val result = evaluate()
        if (result is FieldCheck.Invalid) {
            if (force || dirty || submitted) showError(result.message)
            return false
        }
        clearError()
        return true
    }

    fun focus() = focusAndReveal(inputWidget)

    /**
     * Blendet ein Textfeld samt Label, Hinweis- und Fehlerslot ein oder aus (bei [AbstractText] liegen alle im Wrapper) --
     * für ein Feld, das nur bei einer bestimmten Auswahl gilt (Sterbedatum bei Zielstatus "Verstorben"). Für `select` und
     * `check` liegen Hinweis- und Fehlerslot als Geschwister außerhalb des Steuerelements; dort würde nur das Steuerelement
     * verschwinden und ein Fehlertext ohne Feld stehen bleiben, deshalb scheitert der Aufruf laut. Ein bereits angezeigter
     * Fehler wird beim Ausblenden geräumt; die Feldregel eines ausgeblendeten Feldes muss der Aufrufer selbst stillegen
     * (sonst blockiert ein unsichtbarer Fehler das Absenden).
     */
    fun setVisible(visible: Boolean) {
        val widget = control as? AbstractText
        check(widget != null) { "LapisField.setVisible() supports text controls only, not ${control::class.simpleName}" }
        if (visible) {
            widget.show()
        } else {
            clearError()
            widget.hide()
        }
    }

    /**
     * Ruft [handler] bei jeder Wertänderung (Tippen ODER [setValue] -- KVisions `subscribe` feuert bei beidem, siehe die
     * Reentrancy-Notiz in `ConferenceScreen`; ein Handler mit Nebenwirkung darf sich also nicht selbst wieder auslösen).
     */
    fun subscribe(handler: (String) -> Unit) {
        when (control) {
            is Select -> control.subscribe { handler(it.orEmpty()) }
            is AbstractText -> control.subscribe { handler(it.orEmpty()) }
            is CheckBox -> control.subscribe { handler(it.toString()) }
            else -> error("LapisField.subscribe() supports text, select and checkbox controls only, not ${control::class.simpleName}")
        }
    }

    fun showError(message: String) {
        // Auch ein tr()-Marker wird hier aufgelöst: die Meldung steht später in einer Sammelmeldung / einem Attribut.
        errorSlot.content = resolvedAttributeText(message)
        errorSlot.addCssClass("lapis-field-error--shown")
        inputWidget?.addCssClass("is-invalid")
        inputWidget?.setAttribute("aria-invalid", "true")
        errorShown = true
    }

    fun clearError() {
        errorSlot.content = null
        errorSlot.removeCssClass("lapis-field-error--shown")
        inputWidget?.removeCssClass("is-invalid")
        inputWidget?.setAttribute("aria-invalid", "false")
        errorShown = false
        onStateChange()
    }

    internal fun onBlur() {
        if (dirty || submitted) validate(force = false)
    }

    /** Tippen räumt einen bestehenden Fehler auf, erzeugt aber nie einen neuen. */
    internal fun onInput() {
        dirty = true
        if (errorShown && evaluate() is FieldCheck.Ok) clearError()
    }

    /**
     * Benennt das Feld um (nummerierte Buchungszeilen: "Betrag · Zeile 2", nachdem Zeile 1 entfernt wurde). Der Pflicht-Stern bleibt:
     * [appendRequiredMark] hat den Labeltext bereits in ein `span` VOR dem Stern verschoben -- nur dieses `span` wird ersetzt, das
     * Label-`content` zu überschreiben ließe den Text doppelt stehen. Auch [resolvedLabel] (die Sammelmeldung) folgt.
     */
    internal fun relabel(text: String) {
        resolvedLabel = resolvedAttributeText(text)
        val label = control.flabel
        val first = label.getChildren().firstOrNull()
        if (first is io.kvision.html.Span) first.content = text else label.content = text
    }

    internal fun appendRequiredMark() {
        val label = control.flabel
        // Tag.render() rendert die KINDER vor dem `content`-Text -- ein `span(" *")` als Kind neben dem Label-Content
        // stünde also VOR dem Labeltext. Deshalb wird auch der Labeltext zum Kind (zuerst), der Stern zum zweiten.
        val marker = label.content
        label.content = null
        if (marker != null) label.span(marker)
        label.span(" *") {
            addCssClass("lapis-required-mark")
            setAttribute("aria-hidden", "true")
        }
    }

    private fun evaluate(): FieldCheck {
        if (control is CheckBox) {
            // KVisions `CheckBox.getValueAsString()` ist `"false"` (nicht leer): die Pflicht heißt hier "angekreuzt".
            if (required && !control.value) return FieldCheck.Invalid(requiredMessage ?: gettext("Bitte dieses Kästchen ankreuzen."))
            val own = rule(control.value.toString())
            if (own is FieldCheck.Invalid) return own
            return extraChecks.map { it() }.firstOrNull { it is FieldCheck.Invalid } ?: FieldCheck.Ok
        }
        val current = value
        if (current.isBlank()) {
            return if (required) FieldCheck.Invalid(requiredMessage ?: gettext("Dieses Feld muss ausgefüllt werden.")) else FieldCheck.Ok
        }
        val own = rule(current)
        if (own is FieldCheck.Invalid) return own
        return extraChecks.map { it() }.firstOrNull { it is FieldCheck.Invalid } ?: FieldCheck.Ok
    }
}

private fun focusAndReveal(widget: Widget?) {
    if (widget == null) return
    widget.focus()
    val element = widget.getElement()
    // Keine Animation: `behavior: "auto"`.
    if (element != null) element.asDynamic().scrollIntoView(js("({ block: 'center', behavior: 'auto' })"))
}

/**
 * S4: Doppelklick-Schutz. `guarded {}` wirft [kotlinx.coroutines.CancellationException] weiter -- ein
 * `button.disabled = false` NACH dem `guarded`-Aufruf liefe bei einem Abbruch nie. Deshalb immer
 * try/finally (Muster `LedgerScreen.saveButton`).
 *
 * Umgezogen aus `OpenItemsScreen.kt` (V1.4.28): ein Formular-Baustein, der im Screen für offene Posten wohnt, wird von
 * niemandem gefunden. Gleiches Paket, gleiche Signatur -- kein Aufrufer ändert sich.
 *
 * **MINOR security fix (V1.8.2 review round 2)**: [restoreDisabled] replaces the previous hardcoded
 * `false` in the `finally`. A caller whose button's enabled state ALSO depends on something else --
 * `AiDraftsScreen.kt`'s `renderReleaseControl` disables its release button while an edit is dirty --
 * used to lose that state unconditionally the instant this `finally` ran, because this was always
 * the LAST writer of `.disabled` for any action wrapped here. The default `{ false }` is the
 * previous, unconditional behavior -- every other caller is unaffected.
 */
internal fun runGuardedAction(
    button: Button?,
    restoreDisabled: () -> Boolean = { false },
    block: suspend () -> Unit,
) {
    // Ein zweiter Aufruf, solange der erste läuft, ist wirkungslos. `disabled` wird am Widget SOFORT gelesen -- am DOM-Knopf käme
    // das Attribut erst mit dem nächsten Render an, ein sehr schneller zweiter Klick liefe also noch durch.
    if (button?.disabled == true) return
    button?.disabled = true
    AppScope.launch {
        try {
            block()
        } finally {
            button?.disabled = restoreDisabled()
        }
    }
}
