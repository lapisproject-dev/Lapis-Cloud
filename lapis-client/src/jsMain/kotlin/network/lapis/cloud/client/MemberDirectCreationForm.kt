package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException

/**
 * V1.9.48 (R36B): the direct member creation form, built into the host of a collapsible create form ([collapsibleCreateForm]) behind
 * the page header button "Mitglied direkt anlegen" (BOARD and ADMIN only). Behaviour is unchanged from before the move; the form
 * has no "Stufe" field. The password field is part of the form's [snapshot] (compared, never rendered or logged).
 */
internal fun renderDirectMemberCreation(
    root: SimplePanel,
    chapters: List<RegionalChapterRefDto> = emptyList(),
    collapse: ((Boolean) -> Unit)? = null,
    onCreated: () -> Unit = {},
): FormSnapshot {
    root.p(
        tr(
            "Legt ein Mitglied ohne Antrags-/Freigabeschritt an (z. B. für Beitritte auf Papier oder " +
                "Datenmigration) -- Status sofort Aktiv.",
        ),
    )

    val callerRole = AppState.session?.role ?: AccountRole.MEMBER
    val roleOptions = selectableRolesFor(callerRole).map { it.name to it.name }

    // Formular-Grammatik (V1.4.29): vier Pflichtfelder => Fall (b), keine Sterne, Legende "Alle Felder sind Pflichtfelder."
    val form = root.lapisForm()
    val nameField = form.textField(label = tr("Name"), required = true)
    val emailField =
        form.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            rule = { FormRules.email(value = it) },
        )
    val passwordField =
        form.passwordField(
            label = gettext("Vorläufiges Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH),
            required = true,
            suppressManagers = true,
            reveal = true,
            rule = { FormRules.newPassword(value = it, email = emailField.value.trim()) },
        )
    val roleField =
        form.selectField(
            label = tr("Rolle"),
            options = roleOptions,
            value = roleOptions.firstOrNull()?.first,
            required = true,
        )
    if (roleOptions.size == 1) {
        form.panel.p(
            tr("Als Vorstand können Sie hier nur reguläre Mitglieder anlegen -- Vorstand/Schatzmeister/Admin ist Admin vorbehalten."),
        )
    }
    // Welle V1.9.14 -- MUST be built before `form.buttons(...)` (plan §1 P9): the pflicht-legend
    // decision happens there and needs the final field count/required-set.
    val chapterField =
        if (chapters.isNotEmpty()) {
            form.selectField(
                label = tr("Landesverband"),
                options = listOf("" to tr("— noch nicht festgelegt —")) + untrustedOptions(chapters.map { it.id to it.name }),
                required = false,
            )
        } else {
            null
        }

    val createButton = newActionButton(ActionIcon.ADD, tr("Mitglied anlegen"), ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })
    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val email = emailField.value.trim()
            // Ein Passwort wird NIE getrimmt.
            val temporaryPassword = passwordField.value
            val chapterId = chapterField?.value?.ifBlank { null }
            // Review fix (NIT "misleading KDoc/behavior"): a chapter-shaped `BadRequestException`
            // (the chapter picked here was deleted between loading the options and submitting --
            // `createMemberDirect` then throws `BadRequestException("Unknown regionalChapterId")`)
            // now gets the SAME field error `RegistrationScreen.kt`'s own catch chain already shows
            // for the identical case, instead of `regionalChapterGuarded`'s generic "Ungültige
            // Anfrage." toast -- see [regionalChapterGuarded] KDoc. RegionalChapterRequiredException
            // is caught here too so the fallback to `regionalChapterGuarded { throw e }` (everything
            // else) still gets its usual dispatch, unchanged from before this fix.
            val result =
                try {
                    rpcService<IRegistrationService>().createMemberDirect(
                        AdminCreateMemberInput(
                            displayName = name,
                            email = email,
                            role = AccountRole.valueOf(roleField.value),
                            temporaryPassword = temporaryPassword,
                            regionalChapterId = chapterId,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RegionalChapterRequiredException) {
                    chapterField?.showError(tr("Bitte wählen Sie einen Landesverband."))
                        ?: notifyError(tr("Bitte zuerst einen Landesverband zuordnen."))
                    null
                } catch (e: BadRequestException) {
                    if (chapterField != null && !chapterId.isNullOrBlank()) {
                        chapterField.showError(tr("Dieser Landesverband ist nicht mehr verfügbar -- bitte Seite neu laden."))
                        null
                    } else {
                        regionalChapterGuarded { throw e }
                    }
                } catch (e: Throwable) {
                    regionalChapterGuarded { throw e }
                }
            if (result != null) {
                notifySuccess(gettext("%1 wurde angelegt.", name))
                collapse?.invoke(true)
                onCreated()
            }
        }
    }
    return form.snapshot()
}
