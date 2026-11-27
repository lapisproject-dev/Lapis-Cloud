package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the notices that follow an administrative action (password reset by an administrator, Keycloak link change,
 * Keycloak provisioning) in the eight supported languages. German = the original wording; the other languages were written by the model and
 * are NOT reviewed by native speakers. Placeholders only through [fillMail].
 */
internal object AdminPasswordResetTexts {
    val subjectLead =
        MailText(
            de = "Ihr Passwort wurde zurückgesetzt",
            en = "Your password was reset",
            es = "Su contraseña ha sido restablecida",
            fr = "Votre mot de passe a été réinitialisé",
            it = "La sua password è stata reimpostata",
            nl = "Uw wachtwoord is opnieuw ingesteld",
            pl = "Hasło zostało zresetowane",
            ru = "Ваш пароль был сброшен",
        )
    val heading =
        MailText(
            de = "Passwort zurückgesetzt",
            en = "Password reset",
            es = "Contraseña restablecida",
            fr = "Mot de passe réinitialisé",
            it = "Password reimpostata",
            nl = "Wachtwoord opnieuw ingesteld",
            pl = "Hasło zresetowane",
            ru = "Пароль сброшен",
        )
    val sentence =
        MailText(
            de = "Ihr Passwort bei {brand} wurde am {when} von einer administrativen Person zurückgesetzt.",
            en = "Your password at {brand} was reset by an administrative person on {when}.",
            es = "Su contraseña en {brand} fue restablecida el {when} por una persona administradora.",
            fr = "Votre mot de passe chez {brand} a été réinitialisé le {when} par une personne administratrice.",
            it = "La sua password presso {brand} è stata reimpostata il {when} da una persona amministratrice.",
            nl = "Uw wachtwoord bij {brand} is op {when} door een beheerder opnieuw ingesteld.",
            pl = "Hasło w {brand} zostało zresetowane dnia {when} przez osobę administracyjną.",
            ru = "Ваш пароль в {brand} был сброшен {when} администратором.",
        )
    val advice =
        MailText(
            de =
                "Wenn Sie das erwartet haben (z. B. weil Sie telefonisch um Hilfe gebeten haben), müssen Sie nichts weiter tun. " +
                    "Wenn Sie das NICHT erwartet haben, melden Sie sich bitte umgehend bei uns.",
            en =
                "If you expected this (e.g. because you asked for help by phone), you do not need to do anything. " +
                    "If you did NOT expect this, please contact us immediately.",
            es =
                "Si lo esperaba (p. ej., porque pidió ayuda por teléfono), no tiene que hacer nada más. " +
                    "Si NO lo esperaba, póngase en contacto con nosotros de inmediato.",
            fr =
                "Si vous vous y attendiez (par exemple parce que vous avez demandé " +
                    "de l'aide par téléphone), vous n'avez rien d'autre à faire. " +
                    "Si vous ne vous y attendiez PAS, veuillez nous contacter immédiatement.",
            it =
                "Se se lo aspettava (ad es. perché ha chiesto aiuto per telefono), non deve fare nient'altro. " +
                    "Se NON se lo aspettava, ci contatti subito.",
            nl =
                "Als u dit verwachtte (bijvoorbeeld omdat u telefonisch om hulp hebt gevraagd), hoeft u niets te doen. " +
                    "Als u dit NIET verwachtte, neem dan onmiddellijk contact met ons op.",
            pl =
                "Jeśli jest to oczekiwane (np. po prośbie o pomoc telefonicznie), nie trzeba nic robić. " +
                    "Jeśli NIE jest to oczekiwane, prosimy o niezwłoczny kontakt z nami.",
            ru =
                "Если вы этого ожидали (например, обратились за помощью по телефону), ничего делать не нужно. " +
                    "Если вы этого НЕ ожидали, пожалуйста, немедленно свяжитесь с нами.",
        )
}

internal object KeycloakLinkTexts {
    val linkedHeading =
        MailText(
            de = "Anmeldekonto verknüpft",
            en = "Sign-in account linked",
            es = "Cuenta de acceso vinculada",
            fr = "Compte de connexion lié",
            it = "Account di accesso collegato",
            nl = "Aanmeldaccount gekoppeld",
            pl = "Konto logowania powiązane",
            ru = "Учётная запись для входа связана",
        )
    val linkedSentence =
        MailText(
            de =
                "Ihr Mitgliedskonto bei {brand} wurde am {when} von einer administrativen " +
                    "Person mit einem Keycloak-Anmeldekonto verknüpft. " +
                    "Wer sich mit diesem Keycloak-Konto anmeldet, handelt ab sofort in Ihrem Namen.",
            en =
                "Your member account at {brand} was linked to a Keycloak sign-in account by an administrative person on {when}. " +
                    "Whoever signs in with this Keycloak account acts in your name from now on.",
            es =
                "Su cuenta de miembro en {brand} fue vinculada el {when} por una " +
                    "persona administradora a una cuenta de acceso de Keycloak. " +
                    "Quien inicie sesión con esta cuenta de Keycloak actúa a partir de ahora en su nombre.",
            fr =
                "Votre compte de membre chez {brand} a été lié le {when} par une " +
                    "personne administratrice à un compte de connexion Keycloak. " +
                    "Quiconque se connecte avec ce compte Keycloak agit désormais en votre nom.",
            it =
                "Il suo account di membro presso {brand} è stato collegato il {when} " +
                    "da una persona amministratrice a un account di accesso Keycloak. " +
                    "Chi accede con questo account Keycloak agisce d'ora in poi a suo nome.",
            nl =
                "Uw ledenaccount bij {brand} is op {when} door een beheerder gekoppeld aan een Keycloak-aanmeldaccount. " +
                    "Wie zich met dit Keycloak-account aanmeldt, handelt vanaf nu namens u.",
            pl =
                "Konto członkowskie w {brand} zostało dnia {when} powiązane przez osobę administracyjną z kontem logowania Keycloak. " +
                    "Każdy, kto loguje się tym kontem Keycloak, działa odtąd w imieniu tego członka.",
            ru =
                "Ваша учётная запись участника в {brand} была {when} связана администратором с учётной записью Keycloak для входа. " +
                    "Тот, кто входит с этой учётной записью Keycloak, с этого момента действует от вашего имени.",
        )
    val unlinkedHeading =
        MailText(
            de = "Anmeldekonto-Verknüpfung entfernt",
            en = "Sign-in account link removed",
            es = "Vínculo de la cuenta de acceso eliminado",
            fr = "Lien du compte de connexion supprimé",
            it = "Collegamento dell'account di accesso rimosso",
            nl = "Koppeling van het aanmeldaccount verwijderd",
            pl = "Powiązanie konta logowania usunięte",
            ru = "Связь с учётной записью для входа удалена",
        )
    val unlinkedSentence =
        MailText(
            de =
                "Die Verknüpfung Ihres Mitgliedskontos bei {brand} mit einem Keycloak-Anmeldekonto wurde am {when} von einer " +
                    "administrativen Person entfernt; bestehende Sitzungen wurden beendet.",
            en =
                "The link between your member account at {brand} and a Keycloak sign-in account was removed by an administrative person " +
                    "on {when}; existing sessions were ended.",
            es =
                "El vínculo de su cuenta de miembro en {brand} con una cuenta de " +
                    "acceso de Keycloak fue eliminado el {when} por una persona " +
                    "administradora; las sesiones existentes se han cerrado.",
            fr =
                "Le lien entre votre compte de membre chez {brand} et un compte de " +
                    "connexion Keycloak a été supprimé le {when} par une personne " +
                    "administratrice ; les sessions existantes ont été terminées.",
            it =
                "Il collegamento tra il suo account di membro presso {brand} e un " +
                    "account di accesso Keycloak è stato rimosso il {when} da una " +
                    "persona amministratrice; le sessioni esistenti sono state terminate.",
            nl =
                "De koppeling tussen uw ledenaccount bij {brand} en een Keycloak-aanmeldaccount " +
                    "is op {when} door een beheerder verwijderd; " +
                    "bestaande sessies zijn beëindigd.",
            pl =
                "Powiązanie konta członkowskiego w {brand} z kontem logowania Keycloak " +
                    "zostało dnia {when} usunięte przez osobę administracyjną; " +
                    "istniejące sesje zostały zakończone.",
            ru =
                "Связь вашей учётной записи участника в {brand} с учётной записью Keycloak была удалена {when} администратором; " +
                    "существующие сеансы завершены.",
        )
    val advice =
        MailText(
            de =
                "Wenn Sie das erwartet haben, müssen Sie nichts weiter tun. " +
                    "Wenn Sie das NICHT erwartet haben, melden Sie sich bitte umgehend bei uns.",
            en =
                "If you expected this, you do not need to do anything. " +
                    "If you did NOT expect this, please contact us immediately.",
            es =
                "Si lo esperaba, no tiene que hacer nada más. " +
                    "Si NO lo esperaba, póngase en contacto con nosotros de inmediato.",
            fr =
                "Si vous vous y attendiez, vous n'avez rien d'autre à faire. " +
                    "Si vous ne vous y attendiez PAS, veuillez nous contacter immédiatement.",
            it =
                "Se se lo aspettava, non deve fare nient'altro. " +
                    "Se NON se lo aspettava, ci contatti subito.",
            nl =
                "Als u dit verwachtte, hoeft u niets te doen. " +
                    "Als u dit NIET verwachtte, neem dan onmiddellijk contact met ons op.",
            pl =
                "Jeśli jest to oczekiwane, nie trzeba nic robić. " +
                    "Jeśli NIE jest to oczekiwane, prosimy o niezwłoczny kontakt z nami.",
            ru =
                "Если вы этого ожидали, ничего делать не нужно. " +
                    "Если вы этого НЕ ожидали, пожалуйста, немедленно свяжитесь с нами.",
        )
}

internal object KeycloakProvisioningTexts {
    val provisionedHeading =
        MailText(
            de = "Mitglied automatisch angelegt",
            en = "Member created automatically",
            es = "Miembro creado automáticamente",
            fr = "Membre créé automatiquement",
            it = "Membro creato automaticamente",
            nl = "Lid automatisch aangemaakt",
            pl = "Członek utworzony automatycznie",
            ru = "Участник создан автоматически",
        )
    val provisionedSentence =
        MailText(
            de =
                "{name} wurde am {when} beim ersten Login über den externen Anmeldedienst (Keycloak) " +
                    "automatisch als aktives Mitglied bei {brand} angelegt.",
            en =
                "{name} was created as an active member at {brand} on {when}, " +
                    "on their first login through the external sign-in service (Keycloak).",
            es =
                "{name} fue creado automáticamente como miembro activo en {brand} el {when}, " +
                    "en su primer inicio de sesión a través del servicio de acceso externo (Keycloak).",
            fr =
                "{name} a été créé automatiquement comme membre actif chez {brand} le {when}, " +
                    "lors de sa première connexion via le service de connexion externe (Keycloak).",
            it =
                "{name} è stato creato automaticamente come membro attivo presso {brand} il {when}, " +
                    "al primo accesso tramite il servizio di accesso esterno (Keycloak).",
            nl =
                "{name} is op {when} automatisch aangemaakt als actief lid bij {brand}, " +
                    "bij de eerste aanmelding via de externe aanmelddienst (Keycloak).",
            pl =
                "{name} został(a) utworzony(-a) automatycznie jako aktywny członek w {brand} dnia {when}, " +
                    "przy pierwszym logowaniu przez zewnętrzną usługę logowania (Keycloak).",
            ru =
                "{name} был(а) автоматически создан(а) как активный участник в {brand} {when} " +
                    "при первом входе через внешний сервис входа (Keycloak).",
        )
    val syncedSubjectLead =
        MailText(
            de = "E-Mail-Adresse übernommen",
            en = "E-mail address taken over",
            es = "Dirección de correo adoptada",
            fr = "Adresse e-mail reprise",
            it = "Indirizzo e-mail acquisito",
            nl = "E-mailadres overgenomen",
            pl = "Adres e-mail przejęty",
            ru = "Адрес электронной почты перенят",
        )
    val syncedHeading =
        MailText(
            de = "E-Mail-Adresse aus dem Anmeldedienst übernommen",
            en = "E-mail address taken over from the sign-in service",
            es = "Dirección de correo adoptada del servicio de acceso",
            fr = "Adresse e-mail reprise du service de connexion",
            it = "Indirizzo e-mail acquisito dal servizio di accesso",
            nl = "E-mailadres overgenomen van de aanmelddienst",
            pl = "Adres e-mail przejęty z usługi logowania",
            ru = "Адрес электронной почты перенят из сервиса входа",
        )
    val syncedSentence =
        MailText(
            de =
                "Die E-Mail-Adresse Ihres Kontos bei {brand} wurde am {when} aus dem externen Anmeldedienst (Keycloak) " +
                    "übernommen und lautet jetzt {masked}. Alle anderen Sitzungen wurden beendet.",
            en =
                "The e-mail address of your account at {brand} was taken over from the external sign-in service (Keycloak) " +
                    "on {when} and is now {masked}. All other sessions were ended.",
            es =
                "La dirección de correo de su cuenta en {brand} fue adoptada del servicio de acceso externo (Keycloak) " +
                    "el {when} y ahora es {masked}. Todas las demás sesiones se han cerrado.",
            fr =
                "L'adresse e-mail de votre compte chez {brand} a été reprise du service de connexion externe (Keycloak) " +
                    "le {when} et est désormais {masked}. Toutes les autres sessions ont été terminées.",
            it =
                "L'indirizzo e-mail del suo account presso {brand} è stato acquisito dal servizio di accesso esterno (Keycloak) " +
                    "il {when} e ora è {masked}. Tutte le altre sessioni sono state terminate.",
            nl =
                "Het e-mailadres van uw account bij {brand} is op {when} overgenomen van de externe aanmelddienst (Keycloak) " +
                    "en luidt nu {masked}. Alle andere sessies zijn beëindigd.",
            pl =
                "Adres e-mail konta w {brand} został dnia {when} przejęty z zewnętrznej usługi logowania (Keycloak) " +
                    "i brzmi teraz {masked}. Wszystkie pozostałe sesje zostały zakończone.",
            ru =
                "Адрес электронной почты вашей учётной записи в {brand} был {when} перенят из внешнего сервиса входа (Keycloak) " +
                    "и теперь выглядит так: {masked}. Все остальные сеансы завершены.",
        )
}
