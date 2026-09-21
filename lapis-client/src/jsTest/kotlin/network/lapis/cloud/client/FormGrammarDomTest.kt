package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 (W4a): die Formular-Grammatik ([lapisForm]) in einem ECHTEN gemounteten Root -- Karma läuft in einem
 * richtigen Chrome, `blur`/`input` sind echte DOM-Ereignisse. Was ein Text-Test nicht sehen kann: Reihenfolge im DOM,
 * `aria-*`, dass die Meldungsflächen schon VOR dem ersten Fehler montiert sind, und dass kein `tr()`-Marker in einer
 * Sammelmeldung oder einem Attribut landet.
 */
class FormGrammarDomTest {
    // Own scope instead of `AppScope.promise`: a test failure must not depend on the production scope's job (AppScope has had a
    // SupervisorJob since the V1.4.28 audit, but a test scope of its own keeps the tests independent of it).
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.inputs(): List<HTMLInputElement> =
        (0 until querySelectorAll("input").length).map { querySelectorAll("input").item(it) as HTMLInputElement }

    private fun typeInto(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    private fun leave(input: HTMLInputElement) {
        input.dispatchEvent(Event("blur"))
    }

    private fun HTMLElement.alertRegion(): HTMLElement =
        assertNotNull(querySelector("[role=alert]") as? HTMLElement, "no role=alert region")

    /** Zwei Pflichtfelder + ein optionales: Fall (a) -- Sterne und Legende. */
    private fun mixedForm(
        root: io.kvision.panel.Root,
        onSubmit: () -> Unit = {},
    ): Pair<LapisForm, Button> {
        val form = root.lapisForm()
        form.textField(label = tr("E-Mail"), required = true, rule = FormRules::email)
        form.textField(label = tr("Name"), required = true)
        form.textField(label = tr("Notiz"))
        val submit = Button("Absenden", style = ButtonStyle.PRIMARY)
        form.buttons(primary = submit, cancel = Button("Abbrechen"))
        submit.onClick { form.submit(submit) { onSubmit() } }
        return form to submit
    }

    @Test
    fun requiredField_carriesAriaRequired_evenWithoutAStar() {
        withMountedRoot("form-grammar-aria-required") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("E-Mail"), required = true)
            form.textField(label = tr("Passwort"), required = true)
            form.buttons(primary = Button("Anmelden"))
            val inputs = element().inputs()
            assertEquals(listOf("true", "true"), inputs.map { it.getAttribute("aria-required") })
            assertEquals(0, element().querySelectorAll(".lapis-required-mark").length, "case (c): two required fields, no star")
        }
    }

    @Test
    fun mixedForm_putsTheStarAfterTheLabelText() {
        withMountedRoot("form-grammar-star-order") { root, element ->
            mixedForm(root)
            val labels = element().querySelectorAll("label")
            val first = labels.item(0) as HTMLElement
            // Tag.render() rendert Kinder VOR dem content-Text: ein naives span(" *") stünde vor "E-Mail".
            assertEquals("E-Mail *", first.textContent?.trim())
            assertEquals("Notiz", (labels.item(2) as HTMLElement).textContent?.trim(), "the optional field gets no star")
            val mark = assertNotNull(first.querySelector(".lapis-required-mark") as? HTMLElement)
            assertEquals("true", mark.getAttribute("aria-hidden"))
        }
    }

    @Test
    fun legend_dependsOnTheThreeCases() {
        withMountedRoot("form-grammar-legend-a") { root, element ->
            mixedForm(root)
            assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"))
        }
        withMountedRoot("form-grammar-legend-b") { root, element ->
            val form = root.lapisForm()
            listOf("A", "B", "C").forEach { form.textField(label = tr(it), required = true) }
            form.buttons(primary = Button("Weiter"))
            assertTrue(element().textContent.orEmpty().contains("Alle Felder sind Pflichtfelder."))
            assertEquals(0, element().querySelectorAll(".lapis-required-mark").length)
        }
        withMountedRoot("form-grammar-legend-c") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("A"), required = true)
            form.textField(label = tr("B"), required = true)
            form.buttons(primary = Button("Weiter"))
            val text = element().textContent.orEmpty()
            assertFalse(text.contains("Pflichtfeld"), "case (c) has no legend: $text")
        }
    }

    @Test
    fun blurOnADirtyEmptyField_showsTheErrorAtTheField() {
        withMountedRoot("form-grammar-blur-dirty") { root, element ->
            mixedForm(root)
            val input = element().inputs()[0]
            typeInto(input, "x")
            typeInto(input, "")
            leave(input)
            assertTrue(input.classList.contains("is-invalid"))
            assertEquals("true", input.getAttribute("aria-invalid"))
            val errorId = assertNotNull(input.getAttribute("aria-describedby")).split(" ").last()
            val error = assertNotNull(document.getElementById(errorId), "aria-describedby must point at an existing element")
            assertEquals("Dieses Feld muss ausgefüllt werden.", error.textContent?.trim())
            assertTrue(error.classList.contains("lapis-field-error--shown"))
        }
    }

    @Test
    fun blurOnAnUntouchedEmptyField_staysSilent() {
        withMountedRoot("form-grammar-blur-untouched") { root, element ->
            mixedForm(root)
            val input = element().inputs()[0]
            leave(input)
            assertFalse(input.classList.contains("is-invalid"))
            assertEquals("false", input.getAttribute("aria-invalid"))
        }
    }

    @Test
    fun typing_clearsAnExistingError_butNeverCreatesOne() {
        withMountedRoot("form-grammar-typing") { root, element ->
            mixedForm(root)
            val input = element().inputs()[0]
            typeInto(input, "kaputt")
            leave(input)
            assertTrue(input.classList.contains("is-invalid"), "invalid address must be flagged on leaving the field")
            typeInto(input, "kaputt2")
            assertTrue(input.classList.contains("is-invalid"), "still invalid: typing must not clear it yet")
            typeInto(input, "amara@example.org")
            assertFalse(input.classList.contains("is-invalid"), "now valid: typing clears the error")
            assertEquals("false", input.getAttribute("aria-invalid"))
            typeInto(input, "wieder kaputt")
            assertFalse(input.classList.contains("is-invalid"), "typing never creates a new error")
        }
    }

    @Test
    fun theAlertRegion_isMountedBeforeTheFirstError() {
        withMountedRoot("form-grammar-alert-mounted") { root, element ->
            mixedForm(root)
            val region = element().alertRegion()
            assertEquals("", region.textContent.orEmpty().trim())
        }
    }

    @Test
    fun submitWithTwoInvalidFields_focusesTheFirst_namesBothInPlainText_andCallsNothing() {
        withMountedRoot("form-grammar-submit-invalid") { root, element ->
            var calls = 0
            val (form, _) = mixedForm(root) { calls++ }
            val inputs = element().inputs()
            val childCountBefore = (inputs[0].parentElement as HTMLElement).children.length
            form.submit(Button("x")) { calls++ }
            assertEquals(0, calls, "an invalid form must not call the action")
            assertEquals(inputs[0], document.activeElement, "focus goes to the FIRST invalid field")
            val alert =
                element()
                    .alertRegion()
                    .textContent
                    .orEmpty()
                    .trim()
            assertEquals("Bitte korrigieren Sie diese Felder: E-Mail, Name.", alert)
            assertFalse(alert.contains("###KvI18nS###"))
            // Struktur-Invarianz: der Fehlerslot ist dauerhaft montiert, ein Fehler ändert keine Kinderzahl.
            assertEquals(childCountBefore, (inputs[0].parentElement as HTMLElement).children.length)
        }
    }

    @Test
    fun theFieldErrorSlot_isNeverInsertedOrRemoved() {
        withMountedRoot("form-grammar-structure") { root, element ->
            mixedForm(root)
            val control = element().querySelector(".form-group") as HTMLElement
            val before = control.children.length
            val input = element().inputs()[0]
            typeInto(input, "kaputt")
            leave(input)
            assertEquals(before, control.children.length)
            typeInto(input, "amara@example.org")
            assertEquals(before, control.children.length)
        }
    }

    @Test
    fun buttonOrder_isCancelLeft_primaryRight_andDestructiveInItsOwnZoneBelow() {
        withMountedRoot("form-grammar-buttons") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            form.buttons(
                primary = Button("Speichern", style = ButtonStyle.PRIMARY),
                cancel = Button("Abbrechen"),
                destructive = Button("Löschen", style = ButtonStyle.OUTLINEDANGER),
            )
            val texts =
                (0 until element().querySelectorAll("button").length).map {
                    (element().querySelectorAll("button").item(it) as HTMLElement).textContent?.trim()
                }
            assertEquals(listOf("Abbrechen", "Speichern", "Löschen"), texts)
            val zone = assertNotNull(element().querySelector(".lapis-form-danger-zone") as? HTMLElement)
            assertEquals("Löschen", zone.querySelector("button")?.textContent?.trim())
            assertEquals(1, element().querySelectorAll("button.btn-primary").length, "exactly one PRIMARY per form")
        }
    }

    @Test
    fun aValidSubmit_disablesTheButtonWithAriaBusy_andFreesItAfterAThrowingAction(): Promise<Unit> =
        test {
            withMountedRoot("form-grammar-busy") { root, element ->
                val form = root.lapisForm()
                val field = form.textField(label = tr("Name"), required = true)
                val submit = Button("Speichern", style = ButtonStyle.PRIMARY)
                form.buttons(primary = submit)
                field.setValue("Amara")
                var calls = 0
                form.submit(submit) {
                    calls++
                    delay(60)
                    throw CancellationException("test abort")
                }
                val button = element().querySelector("button") as HTMLElement
                assertTrue(button.hasAttribute("disabled"), "the button is locked during the action")
                assertEquals("true", button.getAttribute("aria-busy"))
                assertEquals("Speichern", button.textContent?.trim(), "no spinner, no text change")
                repeat(40) { if (calls == 0) delay(10) }
                assertEquals(1, calls, "a valid form calls the action")
                repeat(40) { if (button.hasAttribute("disabled")) delay(25) }
                assertFalse(button.hasAttribute("disabled"), "a throwing action must release the button")
                assertEquals("false", button.getAttribute("aria-busy"))
            }
        }

    @Test
    fun crossFieldRule_boundToAField_showsItsMessageAtThatField() {
        withMountedRoot("form-grammar-cross-field") { root, element ->
            val form = root.lapisForm()
            val password = form.passwordField(label = tr("Passwort"), required = true)
            val confirm = form.passwordField(label = tr("Passwort bestätigen"), required = true)
            form.crossFieldRule(field = confirm) {
                FormRules.passwordsMatch(password = password.value, confirmation = confirm.value)
            }
            form.buttons(primary = Button("Weiter"))
            val inputs = element().inputs()
            typeInto(inputs[0], "abcdefghijkl")
            typeInto(inputs[1], "abcdefghijkX")
            leave(inputs[1])
            assertTrue(inputs[1].classList.contains("is-invalid"))
            assertFalse(form.validateAll(force = false))
        }
    }

    @Test
    fun noTrMarkerLeaksIntoTheRenderedSubtree_neitherAsTextNorAsAttribute() {
        withMountedRoot("form-grammar-no-marker") { root, element ->
            val (form, _) = mixedForm(root)
            form.submit(Button("x")) { }
            assertFalse(element().innerHTML.contains("###KvI18nS###"), "a tr() marker leaked into text or an attribute")
        }
    }

    // ── finish(): Formular, dessen Knöpfe in einer Modal-Fußleiste stehen (ConferenceStreamDestinationsScreen) ──

    @Test
    fun finish_mountsTheAlertRegion_andAServerErrorLandsInIt() {
        withMountedRoot("form-grammar-finish") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Bezeichnung"), required = true)
            form.textField(label = tr("Adresse"))
            form.finish()
            val region = element().alertRegion()
            assertEquals("", region.textContent.orEmpty().trim(), "the region is mounted empty, before any message")
            assertFalse(region.classList.contains("lapis-form-alert--shown"))
            // Die Pflichtkennzeichnung wird erst durch finish() entschieden: gemischt => Stern + Legende.
            assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"))
            assertEquals(1, element().querySelectorAll(".lapis-required-mark").length)

            form.showFormError("Der Server sagt nein.")
            assertEquals("Der Server sagt nein.", region.textContent?.trim())
            assertTrue(region.classList.contains("lapis-form-alert--shown"))
            assertEquals(1, element().querySelectorAll("[role=alert]").length, "no second region is created on demand")
            form.clearFormError()
            assertFalse(region.classList.contains("lapis-form-alert--shown"))
        }
    }

    @Test
    fun finish_submitWithAnInvalidField_showsTheCollectiveMessageInTheMountedRegion() {
        withMountedRoot("form-grammar-finish-submit") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Bezeichnung"), required = true)
            form.textField(label = tr("Adresse"), required = true)
            form.finish()
            var calls = 0
            form.submit(Button("x")) { calls++ }
            assertEquals(0, calls)
            assertEquals("Bitte korrigieren Sie diese Felder: Bezeichnung, Adresse.", element().alertRegion().textContent?.trim())
        }
    }

    @Test
    fun finish_submitWithAnInvalidSingleField_reportsAtTheFieldOnly() {
        withMountedRoot("form-grammar-finish-single") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Bezeichnung"), required = true)
            form.finish()
            var calls = 0
            form.submit(Button("x")) { calls++ }
            assertEquals(0, calls)
            assertEquals("", element().alertRegion().textContent?.trim(), "a one-field form must not repeat the field error as a sentence")
            assertEquals(1, element().querySelectorAll(".lapis-field-error--shown").length)
        }
    }

    // ── register() mit einem Upload (BackupScreen) ──

    private fun chooseFile(
        input: HTMLInputElement,
        name: String,
    ) {
        val transfer = js("new DataTransfer()")
        transfer.items.add(File(arrayOf<dynamic>("x"), name))
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    @Test
    fun register_withAnUpload_blocksWithoutAFile_andPassesOnceAFileIsChosen() {
        withMountedRoot("form-grammar-upload") { root, element ->
            val form = root.lapisForm()
            val upload = form.panel.upload(label = tr("Backup-Datei (.zip)"))
            val field =
                form.register(
                    upload,
                    label = tr("Backup-Datei (.zip)"),
                    required = true,
                    requiredMessage = "Bitte eine Datei auswählen.",
                )
            form.buttons(primary = Button("Wiederherstellen"))
            assertFalse(form.validateAll(force = true), "no file chosen: the form must not pass")
            val shownError = element().querySelector(".lapis-field-error--shown")
            assertEquals("Bitte eine Datei auswählen.", shownError?.textContent?.trim())

            val fileInput = assertNotNull(element().querySelector("input[type=file]") as? HTMLInputElement, "no file input")
            chooseFile(fileInput, "backup.zip")
            assertTrue(field.value.isNotBlank(), "a chosen file must make the field value non-blank, was: '${field.value}'")
            assertTrue(form.validateAll(force = true), "a file is chosen: the form must pass")
            assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length, "the error is gone")
        }
    }

    // ── ungebundene crossFieldRule: Zustimmungs-Checkbox (Registrierung) ──

    @Test
    fun anUnboundCrossFieldRule_blocksTheSubmit_showsItInTheAlertRegion_andFocusesTheCheckbox() {
        withMountedRoot("form-grammar-cross-unbound") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            val agree = form.panel.checkBox(label = tr("Ich akzeptiere."))
            agree.markAriaRequired()
            form.crossFieldRule(focusOn = agree.input) {
                if (agree.value) FieldCheck.Ok else FieldCheck.Invalid("Bitte bestätigen Sie die Bedingungen.")
            }
            form.buttons(primary = Button("Weiter"))
            val checkbox = assertNotNull(element().querySelector("input[type=checkbox]") as? HTMLInputElement, "no checkbox")
            assertEquals("true", checkbox.getAttribute("aria-required"), "aria-required must sit on the checkbox input")

            var calls = 0
            form.submit(Button("x")) { calls++ }
            assertEquals(0, calls, "an unchecked agreement must block the submit")
            assertEquals("Bitte bestätigen Sie die Bedingungen.", element().alertRegion().textContent?.trim())
            assertEquals(checkbox, document.activeElement, "focus goes to the checkbox")
            assertFalse(form.validateAll(force = false))

            checkbox.click()
            assertTrue(form.validateAll(force = false), "checked: the rule holds")
            form.submit(Button("x")) { calls++ }
            val remaining = element().alertRegion().textContent.orEmpty()
            assertEquals("", remaining.trim(), "a passing submit clears the message")
        }
    }
}
