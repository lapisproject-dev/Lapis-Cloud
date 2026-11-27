package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the account-security mails (password reset, friend e-mail verification, auditor code) in the eight supported
 * languages. German = the original wording; the other languages were written by the model and are NOT reviewed by native speakers.
 * Placeholders only through [fillMail].
 */
internal object PasswordResetTexts {
    val heading =
        MailText(
            de = "Passwort zurücksetzen",
            en = "Reset your password",
            es = "Restablecer la contraseña",
            fr = "Réinitialiser le mot de passe",
            it = "Reimpostare la password",
            nl = "Wachtwoord opnieuw instellen",
            pl = "Resetowanie hasła",
            ru = "Сброс пароля",
        )
    val requested =
        MailText(
            de = "Sie haben ein Zurücksetzen Ihres Passworts angefordert.",
            en = "You have requested to reset your password.",
            es = "Ha solicitado restablecer su contraseña.",
            fr = "Vous avez demandé la réinitialisation de votre mot de passe.",
            it = "Ha richiesto la reimpostazione della sua password.",
            nl = "U hebt gevraagd uw wachtwoord opnieuw in te stellen.",
            pl = "Zażądano zresetowania hasła do konta.",
            ru = "Вы запросили сброс пароля.",
        )
    val openLinkIntro =
        MailText(
            de = "Öffnen Sie diesen Link, um ein neues Passwort zu vergeben:",
            en = "Open this link to set a new password:",
            es = "Abra este enlace para establecer una nueva contraseña:",
            fr = "Ouvrez ce lien pour définir un nouveau mot de passe :",
            it = "Apra questo link per impostare una nuova password:",
            nl = "Open deze link om een nieuw wachtwoord in te stellen:",
            pl = "Otwórz ten link, aby ustawić nowe hasło:",
            ru = "Откройте эту ссылку, чтобы задать новый пароль:",
        )
    val linkLabel =
        MailText(
            de = "Link zum Zurücksetzen öffnen",
            en = "Open the reset link",
            es = "Abrir el enlace de restablecimiento",
            fr = "Ouvrir le lien de réinitialisation",
            it = "Apri il link di reimpostazione",
            nl = "Resetlink openen",
            pl = "Otwórz link do resetowania",
            ru = "Открыть ссылку для сброса",
        )
    val codeIntro =
        MailText(
            de = "Alternativ können Sie folgenden Code manuell im Anmeldeformular eingeben:",
            en = "Alternatively, you can enter the following code manually in the sign-in form:",
            es = "Como alternativa, puede introducir el siguiente código manualmente en el formulario de inicio de sesión:",
            fr = "Vous pouvez aussi saisir manuellement le code suivant dans le formulaire de connexion :",
            it = "In alternativa può inserire manualmente il seguente codice nel modulo di accesso:",
            nl = "U kunt ook de volgende code handmatig invoeren in het aanmeldformulier:",
            pl = "Można też wpisać poniższy kod ręcznie w formularzu logowania:",
            ru = "Либо введите следующий код вручную в форме входа:",
        )
    val validity =
        MailText(
            de = "Dieser Link/Code ist {ttl} gültig.",
            en = "This link/code is valid for {ttl}.",
            es = "Este enlace/código es válido durante {ttl}.",
            fr = "Ce lien/code est valable {ttl}.",
            it = "Questo link/codice è valido per {ttl}.",
            nl = "Deze link/code is {ttl} geldig.",
            pl = "Ten link/kod jest ważny przez {ttl}.",
            ru = "Эта ссылка/этот код действительны в течение {ttl}.",
        )
    val ignoreHint =
        MailText(
            de = "Wenn Sie diese Änderung nicht angefordert haben, können Sie diese E-Mail ignorieren – es wurde noch nichts verändert.",
            en = "If you did not request this change, you can ignore this e-mail – nothing has been changed yet.",
            es = "Si no solicitó este cambio, puede ignorar este correo: todavía no se ha cambiado nada.",
            fr = "Si vous n'avez pas demandé cette modification, vous pouvez ignorer cet e-mail : rien n'a encore été modifié.",
            it = "Se non ha richiesto questa modifica, può ignorare questa e-mail: finora non è stato modificato nulla.",
            nl = "Als u deze wijziging niet hebt aangevraagd, kunt u deze e-mail negeren – er is nog niets gewijzigd.",
            pl = "Jeśli ta zmiana nie była żądana, można zignorować tę wiadomość – nic jeszcze nie zostało zmienione.",
            ru = "Если вы не запрашивали это изменение, можете проигнорировать письмо – пока ничего не изменено.",
        )
}

internal object FriendVerificationTexts {
    val heading =
        MailText(
            de = "E-Mail-Adresse bestätigen",
            en = "Confirm your e-mail address",
            es = "Confirmar la dirección de correo",
            fr = "Confirmer l'adresse e-mail",
            it = "Confermare l'indirizzo e-mail",
            nl = "E-mailadres bevestigen",
            pl = "Potwierdzenie adresu e-mail",
            ru = "Подтверждение адреса электронной почты",
        )
    val intro =
        MailText(
            de = "Bitte bestätigen Sie Ihre E-Mail-Adresse für Ihr Konto bei {brand}.",
            en = "Please confirm your e-mail address for your account at {brand}.",
            es = "Confirme su dirección de correo para su cuenta en {brand}.",
            fr = "Veuillez confirmer votre adresse e-mail pour votre compte chez {brand}.",
            it = "Confermi il suo indirizzo e-mail per il suo account presso {brand}.",
            nl = "Bevestig uw e-mailadres voor uw account bij {brand}.",
            pl = "Prosimy o potwierdzenie adresu e-mail dla konta w {brand}.",
            ru = "Пожалуйста, подтвердите адрес электронной почты для вашей учётной записи в {brand}.",
        )
    val openLinkIntro =
        MailText(
            de = "Öffnen Sie diesen Link, um die Bestätigung abzuschließen:",
            en = "Open this link to complete the confirmation:",
            es = "Abra este enlace para completar la confirmación:",
            fr = "Ouvrez ce lien pour terminer la confirmation :",
            it = "Apra questo link per completare la conferma:",
            nl = "Open deze link om de bevestiging af te ronden:",
            pl = "Otwórz ten link, aby zakończyć potwierdzenie:",
            ru = "Откройте эту ссылку, чтобы завершить подтверждение:",
        )
    val linkLabel =
        MailText(
            de = "E-Mail-Adresse jetzt bestätigen",
            en = "Confirm e-mail address now",
            es = "Confirmar la dirección ahora",
            fr = "Confirmer l'adresse maintenant",
            it = "Conferma ora l'indirizzo",
            nl = "E-mailadres nu bevestigen",
            pl = "Potwierdź adres e-mail teraz",
            ru = "Подтвердить адрес сейчас",
        )
    val codeIntro =
        MailText(
            de = "Alternativ können Sie folgenden Code manuell eingeben:",
            en = "Alternatively, you can enter the following code manually:",
            es = "Como alternativa, puede introducir el siguiente código manualmente:",
            fr = "Vous pouvez aussi saisir manuellement le code suivant :",
            it = "In alternativa può inserire manualmente il seguente codice:",
            nl = "U kunt ook de volgende code handmatig invoeren:",
            pl = "Można też wpisać poniższy kod ręcznie:",
            ru = "Либо введите следующий код вручную:",
        )
    val ignoreHint =
        MailText(
            de = "Wenn Sie dieses Konto nicht angelegt haben, können Sie diese E-Mail ignorieren.",
            en = "If you did not create this account, you can ignore this e-mail.",
            es = "Si no creó esta cuenta, puede ignorar este correo.",
            fr = "Si vous n'avez pas créé ce compte, vous pouvez ignorer cet e-mail.",
            it = "Se non ha creato questo account, può ignorare questa e-mail.",
            nl = "Als u deze account niet hebt aangemaakt, kunt u deze e-mail negeren.",
            pl = "Jeśli to konto nie było zakładane, można zignorować tę wiadomość.",
            ru = "Если вы не создавали эту учётную запись, можете проигнорировать это письмо.",
        )
}

/** The auditor's code mail. FIXED wording: the code, its life span and a hint -- no link, no name (the tripwire test checks the source for `http`). */
internal object PartgReviewerCodeTexts {
    val subject =
        MailText(
            de = "Ihr Code für den Lesezugang zur Prüfung",
            en = "Your code for read access to the audit",
            es = "Su código para el acceso de lectura a la auditoría",
            fr = "Votre code d'accès en lecture pour l'audit",
            it = "Il suo codice per l'accesso in lettura alla verifica",
            nl = "Uw code voor leestoegang tot de controle",
            pl = "Kod dostępu do odczytu na potrzeby kontroli",
            ru = "Ваш код для доступа на чтение к проверке",
        )
    val intro =
        MailText(
            de = "Ihr Code für den Lesezugang zur Prüfung bei {brand}:",
            en = "Your code for read access to the audit at {brand}:",
            es = "Su código para el acceso de lectura a la auditoría en {brand}:",
            fr = "Votre code d'accès en lecture pour l'audit chez {brand} :",
            it = "Il suo codice per l'accesso in lettura alla verifica presso {brand}:",
            nl = "Uw code voor leestoegang tot de controle bij {brand}:",
            pl = "Kod dostępu do odczytu na potrzeby kontroli w {brand}:",
            ru = "Ваш код для доступа на чтение к проверке в {brand}:",
        )
    val validity =
        MailText(
            de = "Der Code ist {minutes} Minuten gültig und kann einmal verwendet werden.",
            en = "The code is valid for {minutes} minutes and can be used once.",
            es = "El código es válido durante {minutes} minutos y puede usarse una sola vez.",
            fr = "Le code est valable {minutes} minutes et ne peut être utilisé qu'une seule fois.",
            it = "Il codice è valido per {minutes} minuti e può essere usato una sola volta.",
            nl = "De code is {minutes} minuten geldig en kan één keer worden gebruikt.",
            pl = "Kod jest ważny przez {minutes} minut i można go użyć jednorazowo.",
            ru = "Код действителен {minutes} минут и может быть использован один раз.",
        )
    val ignoreHint =
        MailText(
            de = "Wenn Sie keinen Code angefordert haben, können Sie diese E-Mail ignorieren.",
            en = "If you did not request a code, you can ignore this e-mail.",
            es = "Si no solicitó un código, puede ignorar este correo.",
            fr = "Si vous n'avez pas demandé de code, vous pouvez ignorer cet e-mail.",
            it = "Se non ha richiesto un codice, può ignorare questa e-mail.",
            nl = "Als u geen code hebt aangevraagd, kunt u deze e-mail negeren.",
            pl = "Jeśli kod nie był żądany, można zignorować tę wiadomość.",
            ru = "Если вы не запрашивали код, можете проигнорировать это письмо.",
        )
}
