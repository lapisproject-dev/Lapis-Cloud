package network.lapis.cloud.client

import kotlinx.coroutines.delay
import network.lapis.cloud.shared.rpc.IAuthService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DashboardScreen change-password field's full label, matching [renderChangePassword]'s [Validation.PASSWORD_MIN_LENGTH]. */
private val NEW_PASSWORD_LABEL = "Neues Passwort (mind. ${Validation.PASSWORD_MIN_LENGTH} Zeichen)"

/**
 * W6c ("W4d form migration: the last eight screens") Part 4: so far only `DashboardScreen`'s
 * change-password form (the remaining seven screens of the plan are not covered by this file yet).
 * [renderChangePassword] is now a `lapisForm`, so failure cases assert `shownErrors()`
 * (`.lapis-field-error--shown`, the class the form grammar stamps) in addition to "no RPC at all".
 *
 * **Standing-rule deviation, named honestly**: the W5 rule ("request-body tests against the OLD
 * code before any of the eight is touched", `ui-ux-guideline.adoc`) was not followed here -- the
 * migration and these tests landed together in one commit (`77875d6`). Two of the three tests below
 * (`changePassword_withMismatchedConfirmation_sendsNothingAtAll`,
 * `changePassword_withEmptyFields_sendsNothingAtAll`) assert `shownErrors()`, which the OLD code
 * (a plain `text-danger` div) never set -- they could not have run against the pre-migration code
 * either way. Only `changePassword_sendsCurrentAndNewPasswordEachInItsSlot` is grammar-neutral, and
 * even that one was never actually executed against the old implementation, so its claim that the
 * two RPC parameters keep their slot across the migration is asserted, not independently proven.
 */
class FormSubmitBodyPart4DomTest {
    @Test
    fun changePassword_sendsCurrentAndNewPasswordEachInItsSlot(): Promise<Unit> =
        formTest {
            val changePassword = routeOf { rpcService<IAuthService>().changePassword("a", "b") }
            withFetchStub { calls ->
                mountedForm("p4-change-password") { root, element ->
                    renderChangePassword(root)
                    element().typeInto("Aktuelles Passwort", "  altesPasswort1  ")
                    element().typeInto(NEW_PASSWORD_LABEL, "  neuesPasswort2  ")
                    element().typeInto("Neues Passwort bestätigen", "  neuesPasswort2  ")
                    element().buttonNamed("Passwort ändern").click()
                    awaitUntil("changePassword", timeoutMs = 800) { calls.toRoute(changePassword).size == 1 }
                    val call = calls.singleCall(changePassword)
                    assertEquals("  altesPasswort1  ", call.rpcParam(0) as String, "current password sent as typed, in its own slot")
                    assertEquals("  neuesPasswort2  ", call.rpcParam(1) as String, "new password sent as typed, in its own slot")
                }
            }
        }

    @Test
    fun changePassword_withMismatchedConfirmation_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p4-change-password-mismatch") { root, element ->
                    renderChangePassword(root)
                    element().typeInto("Aktuelles Passwort", "altesPasswort1")
                    element().typeInto(NEW_PASSWORD_LABEL, "neuesPasswort2")
                    element().typeInto("Neues Passwort bestätigen", "andereEingabe3")
                    element().buttonNamed("Passwort ändern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertEquals(1, element().shownErrors().size, "the mismatch is reported on the confirmation field")
                }
            }
        }

    @Test
    fun changePassword_withEmptyFields_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p4-change-password-empty") { root, element ->
                    renderChangePassword(root)
                    element().buttonNamed("Passwort ändern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertTrue(element().shownErrors().isNotEmpty(), "the three required fields are reported")
                }
            }
        }

    /** MAJOR finding (review): the new client-side length rule ([FormRules.newPassword]) was previously untested. */
    @Test
    fun changePassword_withTooShortNewPassword_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p4-change-password-too-short") { root, element ->
                    renderChangePassword(root)
                    element().typeInto("Aktuelles Passwort", "altesPasswort1")
                    // 11 chars: one below Validation.PASSWORD_MIN_LENGTH (12).
                    element().typeInto(NEW_PASSWORD_LABEL, "elfZeichen1")
                    element().typeInto("Neues Passwort bestätigen", "elfZeichen1")
                    element().buttonNamed("Passwort ändern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "an 11-character password never reaches the server")
                    assertEquals(1, element().shownErrors().size, "the length rule is reported on the new-password field")
                }
            }
        }

    /** MAJOR finding (review): the new `Abbrechen` button ([renderChangePassword]'s `cancel.onClick`) was previously untested. */
    @Test
    fun changePassword_cancelClearsAllThreeFieldsAndTheirErrors(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p4-change-password-cancel") { root, element ->
                    renderChangePassword(root)
                    element().typeInto("Aktuelles Passwort", "altesPasswort1")
                    element().typeInto(NEW_PASSWORD_LABEL, "neuesPasswort2")
                    element().typeInto("Neues Passwort bestätigen", "andereEingabe3")
                    // Trigger the mismatch error first, so the reset is proven to clear errors too, not only values.
                    element().buttonNamed("Passwort ändern").click()
                    delay(80)
                    assertEquals(1, element().shownErrors().size, "the mismatch is shown before Abbrechen is clicked")

                    element().buttonNamed("Abbrechen").click()

                    assertEquals("", (element().controlOf("Aktuelles Passwort") as HTMLInputElement).value, "current password cleared")
                    assertEquals("", (element().controlOf(NEW_PASSWORD_LABEL) as HTMLInputElement).value, "new password cleared")
                    assertEquals(
                        "",
                        (element().controlOf("Neues Passwort bestätigen") as HTMLInputElement).value,
                        "confirmation cleared",
                    )
                    assertTrue(element().shownErrors().isEmpty(), "Abbrechen also clears the errors it left standing")
                    assertEquals(0, calls.rpcCount, "Abbrechen never talks to the server")
                }
            }
        }
}
