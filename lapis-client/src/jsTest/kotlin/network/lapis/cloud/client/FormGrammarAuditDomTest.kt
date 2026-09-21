package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 audit: the defects the independent audit found in the form grammar itself (MA-1, MA-2, MA-3, MA-7 and the
 * grammar-level minor findings), each pinned in a real mounted root.
 */
class FormGrammarAuditDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.inputs(): List<HTMLInputElement> =
        (0 until querySelectorAll("input").length).map { querySelectorAll("input").item(it) as HTMLInputElement }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun HTMLElement.alertText(): String = first("[role=alert]").textContent.orEmpty().trim()

    private fun typeInto(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    private fun mouse(
        type: String,
        x: Double,
        y: Double,
    ) = MouseEvent(type, MouseEventInit(bubbles = true, cancelable = true, clientX = x.toInt(), clientY = y.toInt()))

    // ── MA-1: the first click on a button after leaving an invalid field ────────────────────────────────────

    /**
     * The REAL event chain of a mouse click: `mousedown` on the button, THEN the field's `blur` (the browser moves focus
     * after the mousedown handlers ran), `mouseup`, `click`. Without the pointer gate the blur validation makes the error
     * appear at once, the button slides down between mousedown and mouseup, and `mouseup` lands on another element -- the
     * browser then fires no `click` at all. A synthetic `.click()` cannot show that, so the test asks the layout: where is
     * the button, and what is under the mousedown point at mouseup time.
     */
    @Test
    fun theFirstClickOnAButton_afterLeavingAnInvalidField_isNotLost(): Promise<Unit> =
        test {
            withMountedRoot("audit-first-click") { root, element ->
                var cancelClicks = 0
                var submitClicks = 0
                val form = root.lapisForm()
                form.textField(label = tr("E-Mail"), required = true, rule = FormRules::email)
                form.textField(label = tr("Name"), required = true)
                val cancel = Button("Abbrechen").apply { onClick { cancelClicks++ } }
                val submit = Button("Absenden", style = ButtonStyle.PRIMARY)
                form.buttons(primary = submit, cancel = cancel)
                submit.onClick { form.submit(submit) { submitClicks++ } }

                val email = element().inputs()[0]
                typeInto(email, "kaputt")
                email.focus()
                val target = element().first("button.btn-outline-secondary")
                target.asDynamic().scrollIntoView(js("({ block: 'center' })"))
                val rect = target.getBoundingClientRect()
                // Ein Punkt nahe der OBERKANTE: schon ein kleiner Versatz nach unten schiebt ihn aus dem Knopf.
                val x = rect.left + rect.width / 2
                val y = rect.top + 3

                target.dispatchEvent(mouse("mousedown", x, y))
                email.dispatchEvent(Event("blur")) // the browser blurs the field AFTER the mousedown handlers
                val during = target.getBoundingClientRect()
                assertEquals(rect.top, during.top, "the button must not move between mousedown and mouseup")
                val hit = document.elementFromPoint(x, y)
                assertTrue(hit != null && target.contains(hit), "mouseup must still land on the button, hit: ${hit?.outerHTML?.take(80)}")
                assertFalse(email.classList.contains("is-invalid"), "the error must not appear while the button is pressed")
                target.dispatchEvent(mouse("mouseup", x, y))
                target.dispatchEvent(mouse("click", x, y))
                assertEquals(1, cancelClicks, "the FIRST click reaches the button")

                delay(60)
                assertTrue(email.classList.contains("is-invalid"), "the deferred validation catches up after the click")
                assertEquals(0, submitClicks)
            }
        }

    @Test
    fun theFirstClickOnSubmit_afterLeavingAnInvalidField_showsTheCollectiveMessageImmediately(): Promise<Unit> =
        test {
            withMountedRoot("audit-first-click-submit") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("E-Mail"), required = true, rule = FormRules::email)
                form.textField(label = tr("Name"), required = true)
                val submit = Button("Absenden", style = ButtonStyle.PRIMARY)
                form.buttons(primary = submit)
                submit.onClick { form.submit(submit) { } }
                val email = element().inputs()[0]
                typeInto(email, "kaputt")
                val button = element().first("button.btn-primary")
                button.dispatchEvent(mouse("mousedown", 5.0, 5.0))
                email.dispatchEvent(Event("blur"))
                button.dispatchEvent(mouse("mouseup", 5.0, 5.0))
                button.dispatchEvent(mouse("click", 5.0, 5.0))
                assertEquals("Bitte korrigieren Sie diese Felder: E-Mail, Name.", element().alertText(), "the click was not swallowed")
            }
        }

    @Test
    fun aBlurWithoutAPressedButton_stillValidatesAtOnce() {
        withMountedRoot("audit-blur-plain") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("E-Mail"), rule = FormRules::email)
            form.buttons(primary = Button("Absenden"))
            val email = element().inputs()[0]
            typeInto(email, "kaputt")
            email.dispatchEvent(Event("blur"))
            assertTrue(email.classList.contains("is-invalid"), "keyboard/Tab and plain clicks are not delayed")
        }
    }

    // ── MA-2: hint and error slot of a register()ed <select> ────────────────────────────────────────────────

    @Test
    fun aRegisteredSelect_keepsHintAndErrorSlotOutsideTheSelectElement() {
        withMountedRoot("audit-select") { root, element ->
            val form = root.lapisForm()
            val select = form.panel.select(options = listOf("a" to "A", "b" to "B"), value = "a", label = tr("Plattform"))
            val field =
                form.register(
                    select,
                    label = tr("Plattform"),
                    required = true,
                    hint = "Ein Hinweis zur Plattform.",
                    rule = { FieldCheck.Invalid("Nein.") },
                )
            form.buttons(primary = Button("Weiter"))
            val selectElement = element().first("select")
            assertNull(selectElement.querySelector(".lapis-field-error"), "the error slot must not live inside the <select>")
            assertNull(selectElement.querySelector(".form-text"), "the hint must not live inside the <select>")
            val describedBy = assertNotNull(selectElement.getAttribute("aria-describedby")).split(" ")
            assertEquals(2, describedBy.size)
            describedBy.forEach { id ->
                val target = assertNotNull(document.getElementById(id), "aria-describedby points at a missing element: $id")
                assertFalse(selectElement.contains(target), "aria-describedby must not point at a descendant of the select")
            }
            field.validate(force = true)
            val error = element().first(".lapis-field-error")
            assertEquals("Nein.", error.textContent?.trim())
            assertTrue(error.getBoundingClientRect().height > 0, "the error text must be rendered (visible), not swallowed by the select")
            assertTrue(element().first(".form-text").getBoundingClientRect().height > 0, "the hint must be visible")
        }
    }

    // ── MA-7: validateAndReport ────────────────────────────────────────────────────────────────────────────

    @Test
    fun validateAndReport_isTheOneCopyOfTheCollectiveSentence_andFocusesTheFirstInvalidField() {
        withMountedRoot("audit-validate-and-report") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("E-Mail"), required = true)
            form.textField(label = tr("Name"), required = true)
            form.buttons(primary = Button("Absenden"))
            assertFalse(form.validateAndReport())
            assertEquals("Bitte korrigieren Sie diese Felder: E-Mail, Name.", element().alertText())
            assertEquals(element().inputs()[0], document.activeElement)
            val inputs = element().inputs()
            typeInto(inputs[0], "a@b.de")
            typeInto(inputs[1], "Amara")
            assertTrue(form.validateAndReport())
            assertEquals("", element().alertText(), "a passing report clears the old message")
        }
    }

    @Test
    fun aSingleFieldForm_reportsOnlyAtTheField_notTwice() {
        withMountedRoot("audit-single-field") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("Bezeichnung"), required = true, requiredMessage = "Bitte eine Bezeichnung angeben.")
            form.buttons(primary = Button("Ausstellen"))
            assertFalse(form.validateAndReport())
            assertEquals("Bitte eine Bezeichnung angeben.", element().first(".lapis-field-error--shown").textContent?.trim())
            assertEquals("", element().alertText(), "the collective sentence would repeat the field error")
        }
    }

    @Test
    fun theCollectiveMessage_clearsItself_asSoonAsEveryFieldIsValidAgain() {
        withMountedRoot("audit-collective-clears") { root, element ->
            val form = root.lapisForm()
            form.textField(label = tr("E-Mail"), required = true)
            form.textField(label = tr("Name"), required = true)
            form.buttons(primary = Button("Absenden"))
            assertFalse(form.validateAndReport())
            val inputs = element().inputs()
            typeInto(inputs[0], "a@b.de")
            assertTrue(element().alertText().isNotEmpty(), "one field is still invalid: the sentence stays")
            typeInto(inputs[1], "Amara")
            assertEquals("", element().alertText(), "all fields valid: the stale sentence is gone without another submit")
        }
    }

    @Test
    fun aServerMessage_isNotClearedByTyping(): Promise<Unit> =
        test {
            withMountedRoot("audit-server-message-stays") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("E-Mail"))
                form.textField(label = tr("Name"))
                form.buttons(primary = Button("Absenden"))
                form.showFormError("Der Server sagt nein.")
                typeInto(element().inputs()[0], "x")
                delay(30)
                assertEquals("Der Server sagt nein.", element().alertText())
            }
        }

    @Test
    fun ticking_theAgreementCheckbox_clearsItsCollectiveMessage(): Promise<Unit> =
        test {
            withMountedRoot("audit-checkbox-clears") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("Name"))
                form.textField(label = tr("Notiz"))
                val agree = form.panel.checkBox(label = tr("Ich akzeptiere."))
                form.crossFieldRule(focusOn = agree.input) { if (agree.value) FieldCheck.Ok else FieldCheck.Invalid("Bitte bestätigen.") }
                form.buttons(primary = Button("Weiter"))
                assertFalse(form.validateAndReport())
                assertEquals("Bitte bestätigen.", element().alertText())
                (element().first("input[type=checkbox]") as HTMLInputElement).click()
                delay(60)
                assertEquals("", element().alertText())
            }
        }

    @Test
    fun aCrossRule_runsExactlyOncePerSubmit() {
        withMountedRoot("audit-cross-once") { root, _ ->
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            form.textField(label = tr("Notiz"))
            var runs = 0
            form.crossFieldRule {
                runs++
                FieldCheck.Invalid("Nein.")
            }
            form.buttons(primary = Button("Weiter"))
            assertFalse(form.validateAndReport())
            assertEquals(1, runs, "one evaluation per submit (the message is taken from the same result)")
        }
    }

    // ── loud failures instead of silent ones ────────────────────────────────────────────────────────────────

    @Test
    fun showFormError_beforeButtonsOrFinish_failsLoudly() {
        withMountedRoot("audit-alert-not-mounted") { root, _ ->
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            assertFailsWith<IllegalStateException> { form.showFormError("zu früh") }
        }
    }

    @Test
    fun setValue_onANonTextControl_failsLoudly_resetOnAnUploadClearsIt() {
        withMountedRoot("audit-set-value") { root, element ->
            val form = root.lapisForm()
            val select = form.panel.select(options = listOf("a" to "A"), value = "a", label = tr("Plattform"))
            val selectField = form.register(select, label = tr("Plattform"))
            val upload = form.panel.upload(label = tr("Datei"))
            val uploadField = form.register(upload, label = tr("Datei"), required = true)
            form.buttons(primary = Button("Weiter"))
            assertFailsWith<IllegalStateException> { selectField.setValue("b") }
            assertFailsWith<IllegalStateException> { selectField.reset() }
            val fileInput = element().first("input[type=file]") as HTMLInputElement
            val transfer = js("new DataTransfer()")
            transfer.items.add(File(arrayOf<dynamic>("x"), "backup.zip"))
            fileInput.asDynamic().files = transfer.files
            fileInput.dispatchEvent(Event("change"))
            assertTrue(uploadField.value.isNotBlank())
            assertFalse(form.validateAndReport().not(), "a file is chosen")
            uploadField.reset()
            assertEquals(0, (fileInput.asDynamic().files.length as Int), "reset() empties the file input")
            fileInput.dispatchEvent(Event("blur"))
            assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length, "a reset field is untouched again")
        }
    }

    @Test
    fun aPasswordField_cannotCombineAutocompleteWithSuppressManagers() {
        withMountedRoot("audit-autocomplete-twice") { root, _ ->
            val form = root.lapisForm()
            assertFailsWith<IllegalArgumentException> {
                form.passwordField(
                    label = tr("Passwort"),
                    autocomplete = io.kvision.html.Autocomplete.NEW_PASSWORD,
                    suppressManagers = true,
                )
            }
        }
    }

    // ── MA-3: a failing AppScope child must not kill the scope ──────────────────────────────────────────────

    @Test
    fun anExceptionInAnAppScopeLaunch_doesNotCancelLaterLaunches(): Promise<Unit> =
        test {
            // With a handler on the child: without one Kotlin/JS rethrows the exception globally and Karma reports it as an error.
            var handled = false
            val failing =
                AppScope.launch(CoroutineExceptionHandler { _, _ -> handled = true }) {
                    throw IllegalStateException("boom (expected in this test)")
                }
            failing.join()
            assertTrue(handled, "the exception still reaches a handler, it is not swallowed")
            assertTrue(AppScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive, "the scope must stay active")
            var ran = false
            AppScope.launch { ran = true }.join()
            assertTrue(ran, "a later launch still runs")
        }
}
