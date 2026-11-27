package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the e-mail-address-change mails (confirm to the new address, warning / info to the old one) in the eight
 * supported languages. German = the original wording; the other languages were written by the model and are NOT reviewed by native
 * speakers. Placeholders only through [fillMail].
 */
internal object EmailChangeTexts {
    val subjectLead =
        MailText(
            de = "E-Mail-Adresse ändern",
            en = "Change e-mail address",
            es = "Cambiar la dirección de correo",
            fr = "Modifier l'adresse e-mail",
            it = "Modifica dell'indirizzo e-mail",
            nl = "E-mailadres wijzigen",
            pl = "Zmiana adresu e-mail",
            ru = "Изменение адреса электронной почты",
        )
    val proposalHeading =
        MailText(
            de = "Neue Anmeldeadresse annehmen",
            en = "Accept the new sign-in address",
            es = "Aceptar la nueva dirección de acceso",
            fr = "Accepter la nouvelle adresse de connexion",
            it = "Accettare il nuovo indirizzo di accesso",
            nl = "Het nieuwe aanmeldadres accepteren",
            pl = "Zaakceptowanie nowego adresu logowania",
            ru = "Принять новый адрес для входа",
        )
    val proposalIntro =
        MailText(
            de = "Für ein Konto bei {brand} wurde vorgeschlagen, diese E-Mail-Adresse als Anmeldeadresse zu verwenden.",
            en = "Using this e-mail address as the sign-in address of an account at {brand} was proposed.",
            es = "Se ha propuesto usar esta dirección de correo como dirección de acceso de una cuenta en {brand}.",
            fr = "Il a été proposé d'utiliser cette adresse e-mail comme adresse de connexion d'un compte chez {brand}.",
            it = "È stato proposto di usare questo indirizzo e-mail come indirizzo di accesso di un account presso {brand}.",
            nl = "Er is voorgesteld dit e-mailadres te gebruiken als aanmeldadres van een account bij {brand}.",
            pl = "Zaproponowano użycie tego adresu e-mail jako adresu logowania do konta w {brand}.",
            ru = "Предложено использовать этот адрес электронной почты как адрес для входа в учётную запись в {brand}.",
        )
    val proposalAction =
        MailText(
            de = "Wenn das Ihr Konto ist, öffnen Sie den Link und bestätigen Sie mit Ihrem Passwort. Der Link ist 7 Tage gültig.",
            en = "If this is your account, open the link and confirm with your password. The link is valid for 7 days.",
            es = "Si es su cuenta, abra el enlace y confirme con su contraseña. El enlace es válido durante 7 días.",
            fr = "S'il s'agit de votre compte, ouvrez le lien et confirmez avec votre mot de passe. Le lien est valable 7 jours.",
            it = "Se è il suo account, apra il link e confermi con la sua password. Il link è valido per 7 giorni.",
            nl = "Als dit uw account is, open dan de link en bevestig met uw wachtwoord. De link is 7 dagen geldig.",
            pl = "Jeśli to Twoje konto, otwórz link i potwierdź hasłem. Link jest ważny przez 7 dni.",
            ru = "Если это ваша учётная запись, откройте ссылку и подтвердите паролем. Ссылка действительна 7 дней.",
        )
    val proposalLinkLabel =
        MailText(
            de = "Neue Adresse annehmen",
            en = "Accept the new address",
            es = "Aceptar la nueva dirección",
            fr = "Accepter la nouvelle adresse",
            it = "Accetta il nuovo indirizzo",
            nl = "Nieuw adres accepteren",
            pl = "Zaakceptuj nowy adres",
            ru = "Принять новый адрес",
        )
    val verifyHeading =
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
    val verifyIntro =
        MailText(
            de = "Für ein Konto bei {brand} wurde beantragt, diese E-Mail-Adresse als Anmeldeadresse zu verwenden.",
            en = "Using this e-mail address as the sign-in address of an account at {brand} was requested.",
            es = "Se ha solicitado usar esta dirección de correo como dirección de acceso de una cuenta en {brand}.",
            fr = "Il a été demandé d'utiliser cette adresse e-mail comme adresse de connexion d'un compte chez {brand}.",
            it = "È stato richiesto di usare questo indirizzo e-mail come indirizzo di accesso di un account presso {brand}.",
            nl = "Er is gevraagd dit e-mailadres te gebruiken als aanmeldadres van een account bij {brand}.",
            pl = "Zażądano użycia tego adresu e-mail jako adresu logowania do konta w {brand}.",
            ru = "Запрошено использовать этот адрес электронной почты как адрес для входа в учётную запись в {brand}.",
        )
    val verifyAction =
        MailText(
            de =
                "Bitte bestätigen Sie, dass diese Adresse Ihnen gehört. Die Änderung wird frühestens {when} wirksam; " +
                    "bis dahin kann sie über die bisherige Adresse abgelehnt werden. Der Link ist 7 Tage gültig.",
            en =
                "Please confirm that this address is yours. The change takes effect no earlier than {when}; " +
                    "until then it can be rejected through the previous address. The link is valid for 7 days.",
            es =
                "Confirme que esta dirección le pertenece. El cambio surte efecto como pronto el {when}; " +
                    "hasta entonces puede rechazarse a través de la dirección anterior. El enlace es válido durante 7 días.",
            fr =
                "Veuillez confirmer que cette adresse vous appartient. La modification prend effet au plus tôt le {when} ; " +
                    "d'ici là, elle peut être refusée via l'ancienne adresse. Le lien est valable 7 jours.",
            it =
                "Confermi che questo indirizzo è suo. La modifica diventa efficace non prima del {when}; " +
                    "fino ad allora può essere rifiutata tramite l'indirizzo precedente. Il link è valido per 7 giorni.",
            nl =
                "Bevestig dat dit adres van u is. De wijziging gaat op zijn vroegst in op {when}; " +
                    "tot die tijd kan ze via het vorige adres worden geweigerd. De link is 7 dagen geldig.",
            pl =
                "Prosimy potwierdzić, że ten adres należy do Ciebie. Zmiana wejdzie w życie najwcześniej {when}; " +
                    "do tego czasu można ją odrzucić przez dotychczasowy adres. Link jest ważny przez 7 dni.",
            ru =
                "Подтвердите, что этот адрес принадлежит вам. Изменение вступит в силу не раньше {when}; " +
                    "до этого его можно отклонить через прежний адрес. Ссылка действительна 7 дней.",
        )
    val verifyLinkLabel =
        MailText(
            de = "Adresse bestätigen",
            en = "Confirm the address",
            es = "Confirmar la dirección",
            fr = "Confirmer l'adresse",
            it = "Conferma l'indirizzo",
            nl = "Adres bevestigen",
            pl = "Potwierdź adres",
            ru = "Подтвердить адрес",
        )

    val warningSubjectLead =
        MailText(
            de = "Änderung Ihrer Anmeldeadresse",
            en = "Change of your sign-in address",
            es = "Cambio de su dirección de acceso",
            fr = "Modification de votre adresse de connexion",
            it = "Modifica del suo indirizzo di accesso",
            nl = "Wijziging van uw aanmeldadres",
            pl = "Zmiana adresu logowania",
            ru = "Изменение вашего адреса для входа",
        )
    val warningHeading =
        MailText(
            de = "Änderung Ihrer Anmeldeadresse beantragt",
            en = "Change of your sign-in address requested",
            es = "Cambio de su dirección de acceso solicitado",
            fr = "Modification de votre adresse de connexion demandée",
            it = "Richiesta la modifica del suo indirizzo di accesso",
            nl = "Wijziging van uw aanmeldadres aangevraagd",
            pl = "Zażądano zmiany adresu logowania",
            ru = "Запрошено изменение вашего адреса для входа",
        )
    val warningIntro =
        MailText(
            de = "Für Ihr Konto bei {brand} hat eine administrative Person beantragt, die Anmeldeadresse auf {masked} zu ändern.",
            en = "For your account at {brand}, an administrative person requested to change the sign-in address to {masked}.",
            es = "Para su cuenta en {brand}, una persona administradora solicitó cambiar la dirección de acceso a {masked}.",
            fr = "Pour votre compte chez {brand}, une personne administratrice a demandé de changer l'adresse de connexion en {masked}.",
            it =
                "Per il suo account presso {brand}, una persona amministratrice " +
                    "ha richiesto di cambiare l'indirizzo di accesso in {masked}.",
            nl = "Voor uw account bij {brand} heeft een beheerder gevraagd het aanmeldadres te wijzigen in {masked}.",
            pl = "Dla konta w {brand} osoba administracyjna zażądała zmiany adresu logowania na {masked}.",
            ru = "Для вашей учётной записи в {brand} администратор запросил изменение адреса для входа на {masked}.",
        )
    val effectPassword =
        MailText(
            de = "Die Änderung wird nur wirksam, wenn Sie sie mit Ihrem Passwort annehmen.",
            en = "The change only takes effect if you accept it with your password.",
            es = "El cambio solo surte efecto si usted lo acepta con su contraseña.",
            fr = "La modification ne prend effet que si vous l'acceptez avec votre mot de passe.",
            it = "La modifica diventa efficace solo se la accetta con la sua password.",
            nl = "De wijziging gaat alleen in als u haar met uw wachtwoord accepteert.",
            pl = "Zmiana wejdzie w życie tylko wtedy, gdy zostanie zaakceptowana hasłem.",
            ru = "Изменение вступит в силу, только если вы примете его с помощью пароля.",
        )
    val effectTimed =
        MailText(
            de = "Die Änderung wird frühestens {when} wirksam, sofern die neue Adresse bestätigt wurde und Sie nicht ablehnen.",
            en = "The change takes effect no earlier than {when}, provided the new address has been confirmed and you do not reject it.",
            es = "El cambio surte efecto como pronto el {when}, siempre que la nueva dirección haya sido confirmada y usted no la rechace.",
            fr =
                "La modification prend effet au plus tôt le {when}, à condition que la " +
                    "nouvelle adresse ait été confirmée et que vous ne la refusiez pas.",
            it =
                "La modifica diventa efficace non prima del {when}, a condizione che " +
                    "il nuovo indirizzo sia stato confermato e che lei non la rifiuti.",
            nl = "De wijziging gaat op zijn vroegst in op {when}, mits het nieuwe adres is bevestigd en u niet weigert.",
            pl = "Zmiana wejdzie w życie najwcześniej {when}, o ile nowy adres zostanie potwierdzony i zmiana nie zostanie odrzucona.",
            ru = "Изменение вступит в силу не раньше {when}, если новый адрес подтверждён и вы не отклоните его.",
        )
    val warningAdvice =
        MailText(
            de = "Wenn Sie das NICHT erwartet haben, lehnen Sie die Änderung jetzt ab und melden Sie sich bitte umgehend bei uns.",
            en = "If you did NOT expect this, reject the change now and please contact us immediately.",
            es = "Si NO lo esperaba, rechace el cambio ahora y póngase en contacto con nosotros de inmediato.",
            fr = "Si vous ne vous y attendiez PAS, refusez la modification maintenant et veuillez nous contacter immédiatement.",
            it = "Se NON se lo aspettava, rifiuti subito la modifica e ci contatti immediatamente.",
            nl = "Als u dit NIET verwachtte, weiger de wijziging dan nu en neem onmiddellijk contact met ons op.",
            pl = "Jeśli NIE jest to oczekiwane, prosimy teraz odrzucić zmianę i niezwłocznie skontaktować się z nami.",
            ru = "Если вы этого НЕ ожидали, отклоните изменение сейчас и немедленно свяжитесь с нами.",
        )
    val warningLinkLabel =
        MailText(
            de = "Änderung ablehnen",
            en = "Reject the change",
            es = "Rechazar el cambio",
            fr = "Refuser la modification",
            it = "Rifiuta la modifica",
            nl = "Wijziging weigeren",
            pl = "Odrzuć zmianę",
            ru = "Отклонить изменение",
        )

    val changedSubjectLead =
        MailText(
            de = "Ihre Anmeldeadresse wurde geändert",
            en = "Your sign-in address was changed",
            es = "Su dirección de acceso ha sido cambiada",
            fr = "Votre adresse de connexion a été modifiée",
            it = "Il suo indirizzo di accesso è stato modificato",
            nl = "Uw aanmeldadres is gewijzigd",
            pl = "Adres logowania został zmieniony",
            ru = "Ваш адрес для входа был изменён",
        )
    val changedHeading =
        MailText(
            de = "Anmeldeadresse geändert",
            en = "Sign-in address changed",
            es = "Dirección de acceso cambiada",
            fr = "Adresse de connexion modifiée",
            it = "Indirizzo di accesso modificato",
            nl = "Aanmeldadres gewijzigd",
            pl = "Adres logowania zmieniony",
            ru = "Адрес для входа изменён",
        )
    val selfChanged =
        MailText(
            de =
                "Die Anmeldeadresse Ihres Kontos bei {brand} wurde soeben auf {masked} geändert. " +
                    "Alle anderen Sitzungen wurden beendet.",
            en =
                "The sign-in address of your account at {brand} was just changed to {masked}. " +
                    "All other sessions were ended.",
            es =
                "La dirección de acceso de su cuenta en {brand} acaba de cambiarse a {masked}. " +
                    "Todas las demás sesiones se han cerrado.",
            fr =
                "L'adresse de connexion de votre compte chez {brand} vient d'être modifiée en {masked}. " +
                    "Toutes les autres sessions ont été terminées.",
            it =
                "L'indirizzo di accesso del suo account presso {brand} è appena stato modificato in {masked}. " +
                    "Tutte le altre sessioni sono state terminate.",
            nl =
                "Het aanmeldadres van uw account bij {brand} is zojuist gewijzigd in {masked}. " +
                    "Alle andere sessies zijn beëindigd.",
            pl =
                "Adres logowania konta w {brand} został właśnie zmieniony na {masked}. " +
                    "Wszystkie pozostałe sesje zostały zakończone.",
            ru =
                "Адрес для входа в вашу учётную запись в {brand} только что изменён на {masked}. " +
                    "Все остальные сеансы завершены.",
        )
    val selfAdvice =
        MailText(
            de = "Wenn Sie das NICHT selbst getan haben, melden Sie sich bitte umgehend bei uns.",
            en = "If you did NOT do this yourself, please contact us immediately.",
            es = "Si NO lo hizo usted mismo, póngase en contacto con nosotros de inmediato.",
            fr = "Si vous ne l'avez PAS fait vous-même, veuillez nous contacter immédiatement.",
            it = "Se NON l'ha fatto lei stesso, ci contatti subito.",
            nl = "Als u dit NIET zelf hebt gedaan, neem dan onmiddellijk contact met ons op.",
            pl = "Jeśli zmiana NIE została dokonana przez Ciebie, prosimy o niezwłoczny kontakt z nami.",
            ru = "Если это сделали НЕ вы, пожалуйста, немедленно свяжитесь с нами.",
        )
    val appliedChanged =
        MailText(
            de =
                "Die Anmeldeadresse Ihres Kontos bei {brand} wurde nach Ablauf der Warnfrist auf {masked} geändert. " +
                    "Alle Sitzungen wurden beendet.",
            en =
                "The sign-in address of your account at {brand} was changed to {masked} after the warning period elapsed. " +
                    "All sessions were ended.",
            es =
                "La dirección de acceso de su cuenta en {brand} se cambió a {masked} tras concluir el plazo de aviso. " +
                    "Todas las sesiones se han cerrado.",
            fr =
                "L'adresse de connexion de votre compte chez {brand} a été modifiée en {masked} à l'expiration du délai d'alerte. " +
                    "Toutes les sessions ont été terminées.",
            it =
                "L'indirizzo di accesso del suo account presso {brand} è stato modificato " +
                    "in {masked} dopo la scadenza del periodo di avviso. " +
                    "Tutte le sessioni sono state terminate.",
            nl =
                "Het aanmeldadres van uw account bij {brand} is na afloop van de waarschuwingstermijn gewijzigd in {masked}. " +
                    "Alle sessies zijn beëindigd.",
            pl =
                "Adres logowania konta w {brand} został po upływie okresu ostrzegawczego zmieniony na {masked}. " +
                    "Wszystkie sesje zostały zakończone.",
            ru =
                "Адрес для входа в вашу учётную запись в {brand} был изменён на {masked} по истечении срока предупреждения. " +
                    "Все сеансы завершены.",
        )
}
