package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the administrator peer-protection mails (request, approval needed, executed, reset mail triggered, protected data
 * changed, new administrator) in the eight supported languages. German = the original wording; the other languages were written by the model
 * and are NOT reviewed by native speakers. Placeholders only through [fillMail].
 */
internal object PeerNotificationTexts {
    val requestSubjectLead =
        MailText(
            de = "Passwortvergabe für Ihr Konto beantragt",
            en = "Temporary password requested for your account",
            es = "Contraseña temporal solicitada para su cuenta",
            fr = "Mot de passe temporaire demandé pour votre compte",
            it = "Richiesta una password temporanea per il suo account",
            nl = "Tijdelijk wachtwoord aangevraagd voor uw account",
            pl = "Zażądano hasła tymczasowego dla konta",
            ru = "Запрошен временный пароль для вашей учётной записи",
        )
    val requestHeading =
        MailText(
            de = "Passwortvergabe für Ihr Administratorkonto beantragt",
            en = "Temporary password requested for your administrator account",
            es = "Contraseña temporal solicitada para su cuenta de administrador",
            fr = "Mot de passe temporaire demandé pour votre compte administrateur",
            it = "Richiesta una password temporanea per il suo account di amministratore",
            nl = "Tijdelijk wachtwoord aangevraagd voor uw beheerdersaccount",
            pl = "Zażądano hasła tymczasowego dla konta administratora",
            ru = "Запрошен временный пароль для вашей учётной записи администратора",
        )
    val requestBody =
        MailText(
            de =
                "{actor} hat beantragt, für Ihr Konto bei {brand} ein temporäres Passwort zu vergeben. " +
                    "Dafür muss ein weiterer Administrator zustimmen; frühestens 24 Stunden danach " +
                    "(voraussichtlich ab {when} bei sofortiger Zustimmung) kann das Passwort erzeugt werden.",
            en =
                "{actor} requested a temporary password for your account at {brand}. " +
                    "A second administrator must approve; the password can be generated no earlier than 24 hours after " +
                    "approval (earliest {when} if approved at once).",
            es =
                "{actor} solicitó una contraseña temporal para su cuenta en {brand}. " +
                    "Otro administrador debe aprobarlo; la contraseña puede generarse como pronto 24 horas después de la " +
                    "aprobación (a partir del {when} si se aprueba de inmediato).",
            fr =
                "{actor} a demandé un mot de passe temporaire pour votre compte chez {brand}. " +
                    "Un second administrateur doit l'approuver ; le mot de passe ne peut être généré que 24 heures au plus tôt après " +
                    "l'approbation (au plus tôt le {when} en cas d'approbation immédiate).",
            it =
                "{actor} ha richiesto una password temporanea per il suo account presso {brand}. " +
                    "Un secondo amministratore deve approvare; la password può essere generata non prima di 24 ore dopo " +
                    "l'approvazione (al più presto il {when} in caso di approvazione immediata).",
            nl =
                "{actor} heeft een tijdelijk wachtwoord voor uw account bij {brand} aangevraagd. " +
                    "Een tweede beheerder moet goedkeuren; het wachtwoord kan op zijn vroegst 24 uur na de goedkeuring " +
                    "worden aangemaakt (vroegst {when} bij onmiddellijke goedkeuring).",
            pl =
                "{actor} zażądał(a) hasła tymczasowego dla konta w {brand}. " +
                    "Musi je zatwierdzić drugi administrator; hasło można wygenerować najwcześniej 24 godziny po " +
                    "zatwierdzeniu (najwcześniej {when} przy natychmiastowym zatwierdzeniu).",
            ru =
                "{actor} запросил(а) временный пароль для вашей учётной записи в {brand}. " +
                    "Требуется одобрение второго администратора; пароль можно создать не раньше чем через 24 часа после " +
                    "одобрения (не раньше {when} при немедленном одобрении).",
        )
    val requestAdvice =
        MailText(
            de = "Wenn Sie das nicht erwartet haben, widersprechen Sie bitte über den Link. Der Antrag wird dann beendet.",
            en = "If you did not expect this, please object using the link. The request is then ended.",
            es = "Si no esperaba esto, presente su oposición mediante el enlace. La solicitud se cancelará.",
            fr = "Si vous ne vous y attendiez pas, veuillez faire opposition via le lien. La demande sera alors close.",
            it = "Se non se lo aspettava, si opponga tramite il link. La richiesta verrà quindi chiusa.",
            nl = "Als u dit niet verwachtte, maak dan bezwaar via de link. De aanvraag wordt dan beëindigd.",
            pl = "Jeśli nie jest to oczekiwane, prosimy o sprzeciw za pomocą linku. Wniosek zostanie wtedy zakończony.",
            ru = "Если вы этого не ожидали, возразите по ссылке. Тогда запрос будет закрыт.",
        )
    val requestLinkLabel =
        MailText(
            de = "Widerspruch einlegen",
            en = "Object to the request",
            es = "Presentar oposición",
            fr = "Faire opposition",
            it = "Presenta opposizione",
            nl = "Bezwaar maken",
            pl = "Zgłoś sprzeciw",
            ru = "Подать возражение",
        )

    val approvalSubjectLead =
        MailText(
            de = "Freigabe erforderlich",
            en = "Approval needed",
            es = "Se necesita su aprobación",
            fr = "Approbation requise",
            it = "Approvazione necessaria",
            nl = "Goedkeuring vereist",
            pl = "Wymagane zatwierdzenie",
            ru = "Требуется одобрение",
        )
    val approvalHeading =
        MailText(
            de = "Ihre Freigabe ist erforderlich",
            en = "Your approval is needed",
            es = "Se necesita su aprobación",
            fr = "Votre approbation est requise",
            it = "È necessaria la sua approvazione",
            nl = "Uw goedkeuring is vereist",
            pl = "Wymagane jest Twoje zatwierdzenie",
            ru = "Требуется ваше одобрение",
        )
    val approvalTempPassword =
        MailText(
            de =
                "{actor} beantragt für das Administratorkonto von {target}, ein temporäres Passwort zu vergeben. " +
                    "Ohne Ihre Freigabe geschieht nichts. Der Antrag verfällt am {when}.",
            en =
                "{actor} requests to set a temporary password for the administrator account of {target}. " +
                    "Nothing happens without your approval. The request expires on {when}.",
            es =
                "{actor} solicita asignar una contraseña temporal a la cuenta de administrador de {target}. " +
                    "Sin su aprobación no ocurre nada. La solicitud caduca el {when}.",
            fr =
                "{actor} demande de définir un mot de passe temporaire pour le compte administrateur de {target}. " +
                    "Rien ne se passe sans votre approbation. La demande expire le {when}.",
            it =
                "{actor} richiede di impostare una password temporanea per l'account di amministratore di {target}. " +
                    "Senza la sua approvazione non accade nulla. La richiesta scade il {when}.",
            nl =
                "{actor} vraagt een tijdelijk wachtwoord in te stellen voor het beheerdersaccount van {target}. " +
                    "Zonder uw goedkeuring gebeurt er niets. De aanvraag verloopt op {when}.",
            pl =
                "{actor} wnioskuje o nadanie hasła tymczasowego dla konta administratora {target}. " +
                    "Bez Twojego zatwierdzenia nic się nie stanie. Wniosek wygasa {when}.",
            ru =
                "{actor} запрашивает назначение временного пароля для учётной записи администратора {target}. " +
                    "Без вашего одобрения ничего не произойдёт. Запрос истекает {when}.",
        )
    val approvalDemote =
        MailText(
            de =
                "{actor} beantragt für das Administratorkonto von {target}, die Administratorrolle zu entziehen. " +
                    "Ohne Ihre Freigabe geschieht nichts. Der Antrag verfällt am {when}.",
            en =
                "{actor} requests to remove the administrator role for the administrator account of {target}. " +
                    "Nothing happens without your approval. The request expires on {when}.",
            es =
                "{actor} solicita retirar el rol de administrador a la cuenta de administrador de {target}. " +
                    "Sin su aprobación no ocurre nada. La solicitud caduca el {when}.",
            fr =
                "{actor} demande de retirer le rôle d'administrateur du compte administrateur de {target}. " +
                    "Rien ne se passe sans votre approbation. La demande expire le {when}.",
            it =
                "{actor} richiede di revocare il ruolo di amministratore all'account di amministratore di {target}. " +
                    "Senza la sua approvazione non accade nulla. La richiesta scade il {when}.",
            nl =
                "{actor} vraagt de beheerdersrol van het beheerdersaccount van {target} in te trekken. " +
                    "Zonder uw goedkeuring gebeurt er niets. De aanvraag verloopt op {when}.",
            pl =
                "{actor} wnioskuje o odebranie roli administratora dla konta administratora {target}. " +
                    "Bez Twojego zatwierdzenia nic się nie stanie. Wniosek wygasa {when}.",
            ru =
                "{actor} запрашивает лишение роли администратора для учётной записи администратора {target}. " +
                    "Без вашего одобрения ничего не произойдёт. Запрос истекает {when}.",
        )
    val approvalSuspend =
        MailText(
            de =
                "{actor} beantragt für das Administratorkonto von {target}, den Zugang zu sperren. " +
                    "Ohne Ihre Freigabe geschieht nichts. Der Antrag verfällt am {when}.",
            en =
                "{actor} requests to block the access for the administrator account of {target}. " +
                    "Nothing happens without your approval. The request expires on {when}.",
            es =
                "{actor} solicita bloquear el acceso de la cuenta de administrador de {target}. " +
                    "Sin su aprobación no ocurre nada. La solicitud caduca el {when}.",
            fr =
                "{actor} demande de bloquer l'accès du compte administrateur de {target}. " +
                    "Rien ne se passe sans votre approbation. La demande expire le {when}.",
            it =
                "{actor} richiede di bloccare l'accesso dell'account di amministratore di {target}. " +
                    "Senza la sua approvazione non accade nulla. La richiesta scade il {when}.",
            nl =
                "{actor} vraagt de toegang van het beheerdersaccount van {target} te blokkeren. " +
                    "Zonder uw goedkeuring gebeurt er niets. De aanvraag verloopt op {when}.",
            pl =
                "{actor} wnioskuje o zablokowanie dostępu dla konta administratora {target}. " +
                    "Bez Twojego zatwierdzenia nic się nie stanie. Wniosek wygasa {when}.",
            ru =
                "{actor} запрашивает блокировку доступа для учётной записи администратора {target}. " +
                    "Без вашего одобрения ничего не произойдёт. Запрос истекает {when}.",
        )
    val approvalWhere =
        MailText(
            de = "Sie finden ihn in der Mitgliederverwaltung unter „Ausstehende Freigaben“.",
            en = "You find it in the member administration under \"Pending approvals\".",
            es = "Lo encontrará en la administración de miembros, en «Aprobaciones pendientes».",
            fr = "Vous le trouvez dans la gestion des membres sous « Approbations en attente ».",
            it = "Lo trova nell'amministrazione dei membri sotto «Approvazioni in sospeso».",
            nl = "U vindt het in het ledenbeheer onder \"Openstaande goedkeuringen\".",
            pl = "Znajdziesz go w administracji członków w sekcji „Oczekujące zatwierdzenia”.",
            ru = "Вы найдёте его в управлении участниками в разделе «Ожидающие одобрения».",
        )
    val approvalLinkLabel =
        MailText(
            de = "Zur Anwendung",
            en = "Open the application",
            es = "Abrir la aplicación",
            fr = "Ouvrir l'application",
            it = "Apri l'applicazione",
            nl = "Naar de applicatie",
            pl = "Otwórz aplikację",
            ru = "Открыть приложение",
        )

    val executedSubjectLead =
        MailText(
            de = "Änderung an Ihrem Konto",
            en = "Change to your account",
            es = "Cambio en su cuenta",
            fr = "Modification de votre compte",
            it = "Modifica al suo account",
            nl = "Wijziging van uw account",
            pl = "Zmiana w koncie",
            ru = "Изменение в вашей учётной записи",
        )
    val executedTempPassword =
        MailText(
            de = "Für Ihr Konto wurde ein temporäres Passwort vergeben. Alle Sitzungen wurden beendet.",
            en = "A temporary password was set for your account. All sessions were ended.",
            es = "Se ha asignado una contraseña temporal a su cuenta. Todas las sesiones se han cerrado.",
            fr = "Un mot de passe temporaire a été défini pour votre compte. Toutes les sessions ont été terminées.",
            it = "È stata impostata una password temporanea per il suo account. Tutte le sessioni sono state terminate.",
            nl = "Voor uw account is een tijdelijk wachtwoord ingesteld. Alle sessies zijn beëindigd.",
            pl = "Dla konta ustawiono hasło tymczasowe. Wszystkie sesje zostały zakończone.",
            ru = "Для вашей учётной записи назначен временный пароль. Все сеансы завершены.",
        )
    val executedRoleChanged =
        MailText(
            de = "Die Rolle Ihres Kontos wurde geändert.",
            en = "The role of your account was changed.",
            es = "El rol de su cuenta ha sido modificado.",
            fr = "Le rôle de votre compte a été modifié.",
            it = "Il ruolo del suo account è stato modificato.",
            nl = "De rol van uw account is gewijzigd.",
            pl = "Rola konta została zmieniona.",
            ru = "Роль вашей учётной записи изменена.",
        )
    val executedSuspended =
        MailText(
            de = "Der Zugang Ihres Kontos wurde gesperrt. Alle Sitzungen wurden beendet.",
            en = "The access of your account was blocked. All sessions were ended.",
            es = "El acceso de su cuenta ha sido bloqueado. Todas las sesiones se han cerrado.",
            fr = "L'accès de votre compte a été bloqué. Toutes les sessions ont été terminées.",
            it = "L'accesso al suo account è stato bloccato. Tutte le sessioni sono state terminate.",
            nl = "De toegang van uw account is geblokkeerd. Alle sessies zijn beëindigd.",
            pl = "Dostęp do konta został zablokowany. Wszystkie sesje zostały zakończone.",
            ru = "Доступ к вашей учётной записи заблокирован. Все сеансы завершены.",
        )
    val executedStatusChanged =
        MailText(
            de = "Der Status Ihrer Mitgliedschaft wurde geändert.",
            en = "The status of your membership was changed.",
            es = "El estado de su membresía ha sido modificado.",
            fr = "Le statut de votre adhésion a été modifié.",
            it = "Lo stato della sua adesione è stato modificato.",
            nl = "De status van uw lidmaatschap is gewijzigd.",
            pl = "Status członkostwa został zmieniony.",
            ru = "Статус вашего членства изменён.",
        )
    val executedTrigger =
        MailText(
            de = "Ausgelöst durch {actor} am {when}.",
            en = "Triggered by {actor} on {when}.",
            es = "Realizado por {actor} el {when}.",
            fr = "Déclenché par {actor} le {when}.",
            it = "Avviato da {actor} il {when}.",
            nl = "Uitgevoerd door {actor} op {when}.",
            pl = "Wykonane przez {actor} dnia {when}.",
            ru = "Выполнено: {actor}, {when}.",
        )
    val operatorConsoleActor =
        MailText(
            de = "Betreiberkonsole",
            en = "operator console",
            es = "consola del operador",
            fr = "console de l'exploitant",
            it = "console dell'operatore",
            nl = "beheerconsole",
            pl = "konsola operatora",
            ru = "консоль оператора",
        )

    val resetSubjectLead =
        MailText(
            de = "Passwort-Reset für Ihr Konto ausgelöst",
            en = "Password reset triggered for your account",
            es = "Restablecimiento de contraseña activado para su cuenta",
            fr = "Réinitialisation du mot de passe déclenchée pour votre compte",
            it = "Reimpostazione della password avviata per il suo account",
            nl = "Wachtwoordreset gestart voor uw account",
            pl = "Uruchomiono reset hasła dla konta",
            ru = "Для вашей учётной записи запущен сброс пароля",
        )
    val resetBody =
        MailText(
            de =
                "{actor} hat am {when} eine Passwort-Reset-Mail für Ihr Konto bei {brand} ausgelöst. " +
                    "Ihr Passwort ist unverändert, solange niemand den Link in dieser Mail verwendet.",
            en =
                "{actor} triggered a password-reset mail for your account at {brand} on {when}. " +
                    "Your password is unchanged as long as nobody uses the link in that mail.",
            es =
                "{actor} activó el {when} un correo de restablecimiento de contraseña para su cuenta en {brand}. " +
                    "Su contraseña no cambia mientras nadie use el enlace de ese correo.",
            fr =
                "{actor} a déclenché le {when} un e-mail de réinitialisation du mot de passe pour votre compte chez {brand}. " +
                    "Votre mot de passe reste inchangé tant que personne n'utilise le lien de cet e-mail.",
            it =
                "{actor} ha avviato il {when} una e-mail di reimpostazione della password per il suo account presso {brand}. " +
                    "La sua password resta invariata finché nessuno usa il link di quella e-mail.",
            nl =
                "{actor} heeft op {when} een e-mail voor het opnieuw instellen van het wachtwoord voor uw account bij {brand} gestart. " +
                    "Uw wachtwoord blijft ongewijzigd zolang niemand de link in die e-mail gebruikt.",
            pl =
                "{actor} uruchomił(a) dnia {when} wiadomość resetującą hasło dla konta w {brand}. " +
                    "Hasło pozostaje bez zmian, dopóki nikt nie użyje linku z tej wiadomości.",
            ru =
                "{actor} {when} запустил(а) письмо для сброса пароля вашей учётной записи в {brand}. " +
                    "Пароль не изменится, пока никто не воспользуется ссылкой из этого письма.",
        )
    val resetAdvice =
        MailText(
            de = "Wenn Sie das nicht erwartet haben, ändern Sie Ihr Passwort und melden Sie sich bitte umgehend bei uns.",
            en = "If you did not expect this, change your password and please contact us immediately.",
            es = "Si no esperaba esto, cambie su contraseña y póngase en contacto con nosotros de inmediato.",
            fr = "Si vous ne vous y attendiez pas, changez votre mot de passe et veuillez nous contacter immédiatement.",
            it = "Se non se lo aspettava, cambi la sua password e ci contatti subito.",
            nl = "Als u dit niet verwachtte, wijzig dan uw wachtwoord en neem onmiddellijk contact met ons op.",
            pl = "Jeśli nie jest to oczekiwane, prosimy zmienić hasło i niezwłocznie skontaktować się z nami.",
            ru = "Если вы этого не ожидали, смените пароль и немедленно свяжитесь с нами.",
        )

    val protectedSubjectLead =
        MailText(
            de = "Ihre Anschrift/Angaben wurden geändert",
            en = "Your address data was changed",
            es = "Sus datos de dirección han sido modificados",
            fr = "Vos données d'adresse ont été modifiées",
            it = "I suoi dati di indirizzo sono stati modificati",
            nl = "Uw adresgegevens zijn gewijzigd",
            pl = "Dane adresowe zostały zmienione",
            ru = "Ваши адресные данные изменены",
        )
    val protectedHeading =
        MailText(
            de = "Ihre Angaben wurden geändert",
            en = "Your data was changed",
            es = "Sus datos han sido modificados",
            fr = "Vos données ont été modifiées",
            it = "I suoi dati sono stati modificati",
            nl = "Uw gegevens zijn gewijzigd",
            pl = "Dane zostały zmienione",
            ru = "Ваши данные изменены",
        )
    val protectedBody =
        MailText(
            de =
                "{actor} hat am {when} Ihre Anschrift oder Ihre Angaben zur wirtschaftlichen Berechtigung " +
                    "bei {brand} geändert.",
            en = "{actor} changed your address or your beneficial-owner data at {brand} on {when}.",
            es = "{actor} modificó el {when} su dirección o sus datos sobre el titular real en {brand}.",
            fr = "{actor} a modifié le {when} votre adresse ou vos données de bénéficiaire effectif chez {brand}.",
            it = "{actor} ha modificato il {when} il suo indirizzo o i suoi dati sul titolare effettivo presso {brand}.",
            nl = "{actor} heeft op {when} uw adres of uw gegevens over de uiteindelijk belanghebbende bij {brand} gewijzigd.",
            pl = "{actor} zmienił(a) dnia {when} adres lub dane o beneficjencie rzeczywistym w {brand}.",
            ru = "{actor} {when} изменил(а) ваш адрес или данные о бенефициарном владельце в {brand}.",
        )

    val newAdminSubjectLead =
        MailText(
            de = "Neuer Administrator",
            en = "New administrator",
            es = "Nuevo administrador",
            fr = "Nouvel administrateur",
            it = "Nuovo amministratore",
            nl = "Nieuwe beheerder",
            pl = "Nowy administrator",
            ru = "Новый администратор",
        )
    val newAdminBody =
        MailText(
            de = "{actor} hat am {when} {newAdmin} zum Administrator von {brand} gemacht.",
            en = "{actor} made {newAdmin} an administrator of {brand} on {when}.",
            es = "{actor} nombró el {when} a {newAdmin} administrador de {brand}.",
            fr = "{actor} a nommé {newAdmin} administrateur de {brand} le {when}.",
            it = "{actor} ha nominato il {when} {newAdmin} amministratore di {brand}.",
            nl = "{actor} heeft op {when} {newAdmin} tot beheerder van {brand} gemaakt.",
            pl = "{actor} nadał(a) dnia {when} użytkownikowi {newAdmin} rolę administratora w {brand}.",
            ru = "{actor} {when} назначил(а) {newAdmin} администратором {brand}.",
        )
}
