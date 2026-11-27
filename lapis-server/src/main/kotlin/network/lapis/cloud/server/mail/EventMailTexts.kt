package network.lapis.cloud.server.mail

/**
 * V1.9.335 -- the event mails (waitlist promotion, registration, member/duplicate notice, ticket, ticket reissue, cancellation, registration
 * message notice) in the eight supported languages. All of them are written in the INSTANCE mail language (see [MailLanguage.instance]).
 *
 * Why not the recipient's language: a member has no stored language, and a guest's language is NOT reliably known -- the public event page
 * knows the visitor language only through `?lang=` (absent for the default `de`, lost in the embed mode) and the registration stores none
 * (no column, no migration). So a missing parameter cannot be told apart from "the visitor chose German"; the instance language is used.
 *
 * The German wording is what the mails always had; the other seven languages were written by the model and are NOT reviewed by native
 * speakers. Placeholders are plain `{name}` tokens ([fillMail]); never string templates (a tripwire scans `*Texts.kt`). Links are NEVER put
 * into a text -- they stay layout arguments of [EventMailTemplates]. The cancellation reason is free text of the board and is not translated.
 */
internal object EventMailTexts {
    val greeting =
        MailText(
            de = "Hallo {name},",
            en = "Hello {name},",
            es = "Hola {name},",
            fr = "Bonjour {name},",
            it = "Buongiorno {name},",
            nl = "Hallo {name},",
            pl = "Dzień dobry {name},",
            ru = "Здравствуйте, {name}!",
        )
    val greetingAnonymous =
        MailText(
            de = "Hallo,",
            en = "Hello,",
            es = "Hola,",
            fr = "Bonjour,",
            it = "Buongiorno,",
            nl = "Hallo,",
            pl = "Dzień dobry,",
            ru = "Здравствуйте!",
        )
    val ticketLabel =
        MailText(
            de = "Ihr Ticket",
            en = "Your ticket",
            es = "Su entrada",
            fr = "Votre billet",
            it = "Il suo biglietto",
            nl = "Uw ticket",
            pl = "Twój bilet",
            ru = "Ваш билет",
        )
    val cancelRegistrationLabel =
        MailText(
            de = "Anmeldung stornieren",
            en = "Cancel registration",
            es = "Cancelar la inscripción",
            fr = "Annuler l'inscription",
            it = "Annulla l'iscrizione",
            nl = "Aanmelding annuleren",
            pl = "Anuluj zgłoszenie",
            ru = "Отменить регистрацию",
        )
    val payNowLabel =
        MailText(
            de = "Jetzt bezahlen",
            en = "Pay now",
            es = "Pagar ahora",
            fr = "Payer maintenant",
            it = "Paga ora",
            nl = "Nu betalen",
            pl = "Zapłać teraz",
            ru = "Оплатить сейчас",
        )
    val completePaymentLabel =
        MailText(
            de = "Zahlung abschließen",
            en = "Complete payment",
            es = "Completar el pago",
            fr = "Finaliser le paiement",
            it = "Completa il pagamento",
            nl = "Betaling afronden",
            pl = "Dokończ płatność",
            ru = "Завершить оплату",
        )

    // ── Waitlist promotion ────────────────────────────────────────────────────────────────────
    val waitlistSubject =
        MailText(
            de = "Platz frei geworden: {title}",
            en = "A seat has become free: {title}",
            es = "Hay una plaza libre: {title}",
            fr = "Une place s'est libérée : {title}",
            it = "Si è liberato un posto: {title}",
            nl = "Er is een plek vrijgekomen: {title}",
            pl = "Zwolniło się miejsce: {title}",
            ru = "Освободилось место: {title}",
        )
    val waitlistBodyPay =
        MailText(
            de =
                "Ein Platz für \"{title}\" ist frei geworden. Bitte schließen Sie die Zahlung innerhalb von {hours} ab, " +
                    "sonst rückt die nächste Person nach.",
            en =
                "A seat for \"{title}\" has become free. Please complete the payment within {hours}, " +
                    "otherwise the next person on the waitlist moves up.",
            es =
                "Se ha liberado una plaza para «{title}». Complete el pago en un plazo de {hours}; " +
                    "de lo contrario, pasará la siguiente persona.",
            fr =
                "Une place pour « {title} » s'est libérée. Veuillez finaliser le paiement dans un délai de {hours}, " +
                    "sinon la personne suivante passera avant vous.",
            it =
                "Si è liberato un posto per «{title}». Completi il pagamento entro {hours}, " +
                    "altrimenti subentrerà la persona successiva.",
            nl =
                "Er is een plek vrijgekomen voor „{title}”. Rond de betaling af binnen {hours}, " +
                    "anders schuift de volgende persoon op.",
            pl =
                "Zwolniło się miejsce na „{title}”. Prosimy dokończyć płatność w ciągu {hours}, " +
                    "w przeciwnym razie miejsce przejmie kolejna osoba.",
            ru =
                "Освободилось место на «{title}». Пожалуйста, завершите оплату в течение {hours}, " +
                    "иначе место получит следующий человек.",
        )
    val waitlistBodyConfirmed =
        MailText(
            de = "Ein Platz für \"{title}\" ist frei geworden -- Ihre Teilnahme ist bestätigt.",
            en = "A seat for \"{title}\" has become free -- your participation is confirmed.",
            es = "Se ha liberado una plaza para «{title}»: su participación está confirmada.",
            fr = "Une place pour « {title} » s'est libérée -- votre participation est confirmée.",
            it = "Si è liberato un posto per «{title}»: la sua partecipazione è confermata.",
            nl = "Er is een plek vrijgekomen voor „{title}” -- uw deelname is bevestigd.",
            pl = "Zwolniło się miejsce na „{title}” -- Twój udział jest potwierdzony.",
            ru = "Освободилось место на «{title}» -- ваше участие подтверждено.",
        )

    // ── Registration received ─────────────────────────────────────────────────────────────────
    val registrationSubjectConfirmed =
        MailText(
            de = "Anmeldebestätigung: {title}",
            en = "Registration confirmation: {title}",
            es = "Confirmación de inscripción: {title}",
            fr = "Confirmation d'inscription : {title}",
            it = "Conferma di iscrizione: {title}",
            nl = "Bevestiging van aanmelding: {title}",
            pl = "Potwierdzenie zgłoszenia: {title}",
            ru = "Подтверждение регистрации: {title}",
        )
    val registrationSubjectWaitlisted =
        MailText(
            de = "Warteliste: {title}",
            en = "Waitlist: {title}",
            es = "Lista de espera: {title}",
            fr = "Liste d'attente : {title}",
            it = "Lista d'attesa: {title}",
            nl = "Wachtlijst: {title}",
            pl = "Lista oczekujących: {title}",
            ru = "Лист ожидания: {title}",
        )
    val registrationSubjectPayment =
        MailText(
            de = "Bitte Zahlung abschließen: {title}",
            en = "Please complete your payment: {title}",
            es = "Complete su pago: {title}",
            fr = "Veuillez finaliser votre paiement : {title}",
            it = "Completi il pagamento: {title}",
            nl = "Rond uw betaling af: {title}",
            pl = "Prosimy dokończyć płatność: {title}",
            ru = "Пожалуйста, завершите оплату: {title}",
        )
    val registrationBodyConfirmed =
        MailText(
            de = "Ihre Anmeldung für \"{title}\" ist bestätigt.",
            en = "Your registration for \"{title}\" is confirmed.",
            es = "Su inscripción para «{title}» está confirmada.",
            fr = "Votre inscription à « {title} » est confirmée.",
            it = "La sua iscrizione a «{title}» è confermata.",
            nl = "Uw aanmelding voor „{title}” is bevestigd.",
            pl = "Twoje zgłoszenie na „{title}” zostało potwierdzone.",
            ru = "Ваша регистрация на «{title}» подтверждена.",
        )
    val registrationBodyWaitlisted =
        MailText(
            de = "Sie stehen auf der Warteliste für \"{title}\". Wir melden uns, sobald ein Platz frei wird.",
            en = "You are on the waitlist for \"{title}\". We will get in touch as soon as a seat becomes free.",
            es = "Está en la lista de espera para «{title}». Nos pondremos en contacto en cuanto quede una plaza libre.",
            fr = "Vous êtes sur la liste d'attente pour « {title} ». Nous vous contacterons dès qu'une place se libère.",
            it = "È nella lista d'attesa per «{title}». La contatteremo non appena si libera un posto.",
            nl = "U staat op de wachtlijst voor „{title}”. We nemen contact op zodra er een plek vrijkomt.",
            pl = "Jesteś na liście oczekujących na „{title}”. Odezwiemy się, gdy zwolni się miejsce.",
            ru = "Вы в листе ожидания на «{title}». Мы свяжемся с вами, как только освободится место.",
        )
    val registrationBodyPayment =
        MailText(
            de =
                "Ihr Platz für \"{title}\" ist reserviert. Bitte schließen Sie die Zahlung innerhalb von {minutes} ab, " +
                    "sonst wird der Platz wieder freigegeben.",
            en =
                "Your seat for \"{title}\" is reserved. Please complete the payment within {minutes}, " +
                    "otherwise the seat will be released again.",
            es =
                "Su plaza para «{title}» está reservada. Complete el pago en un plazo de {minutes}; " +
                    "de lo contrario, la plaza se liberará de nuevo.",
            fr =
                "Votre place pour « {title} » est réservée. Veuillez finaliser le paiement dans un délai de {minutes}, " +
                    "sinon la place sera de nouveau libérée.",
            it =
                "Il suo posto per «{title}» è riservato. Completi il pagamento entro {minutes}, " +
                    "altrimenti il posto verrà rilasciato di nuovo.",
            nl =
                "Uw plek voor „{title}” is gereserveerd. Rond de betaling af binnen {minutes}, " +
                    "anders komt de plek weer vrij.",
            pl =
                "Twoje miejsce na „{title}” jest zarezerwowane. Prosimy dokończyć płatność w ciągu {minutes}, " +
                    "w przeciwnym razie miejsce zostanie ponownie zwolnione.",
            ru =
                "Ваше место на «{title}» зарезервировано. Пожалуйста, завершите оплату в течение {minutes}, " +
                    "иначе место будет снова освобождено.",
        )

    // ── Notice to an account holder whose address was typed into the public guest form ───────
    val memberNoticeSubject =
        MailText(
            de = "Hinweis zur Veranstaltung \"{title}\"",
            en = "Notice about the event \"{title}\"",
            es = "Aviso sobre el evento «{title}»",
            fr = "Information sur l'événement « {title} »",
            it = "Avviso sull'evento «{title}»",
            nl = "Mededeling over het evenement „{title}”",
            pl = "Informacja o wydarzeniu „{title}”",
            ru = "Уведомление о мероприятии «{title}»",
        )
    val memberNoticeBody =
        MailText(
            de =
                "für die Veranstaltung \"{title}\" wurde auf unserer öffentlichen Seite diese E-Mail-Adresse eingetragen. " +
                    "Die Adresse gehört zu einem Konto bei uns. Bitte melden Sie sich an und melden Sie sich danach im " +
                    "Mitgliederbereich zur Veranstaltung an, damit die Anmeldung Ihrem Konto zugeordnet ist.",
            en =
                "For the event \"{title}\", this e-mail address was entered on our public page. " +
                    "The address belongs to an account with us. Please log in and then register for the event in the " +
                    "member area, so that the registration is assigned to your account.",
            es =
                "Para el evento «{title}» se introdujo esta dirección de correo en nuestra página pública. " +
                    "La dirección pertenece a una cuenta nuestra. Inicie sesión y, a continuación, inscríbase en el evento " +
                    "en el área de miembros, para que la inscripción quede asignada a su cuenta.",
            fr =
                "Pour l'événement « {title} », cette adresse e-mail a été saisie sur notre page publique. " +
                    "L'adresse appartient à un compte chez nous. Veuillez vous connecter, puis vous inscrire à l'événement " +
                    "dans l'espace membres afin que l'inscription soit rattachée à votre compte.",
            it =
                "Per l'evento «{title}» questo indirizzo e-mail è stato inserito sulla nostra pagina pubblica. " +
                    "L'indirizzo appartiene a un account presso di noi. Acceda e si iscriva poi all'evento " +
                    "nell'area soci, in modo che l'iscrizione sia assegnata al suo account.",
            nl =
                "Voor het evenement „{title}” is dit e-mailadres ingevoerd op onze openbare pagina. " +
                    "Het adres hoort bij een account bij ons. Log in en meld u daarna in het ledengedeelte aan voor het evenement, " +
                    "zodat de aanmelding aan uw account wordt gekoppeld.",
            pl =
                "Na naszej stronie publicznej wpisano ten adres e-mail dla wydarzenia „{title}”. " +
                    "Adres należy do konta u nas. Prosimy się zalogować, a następnie zapisać na wydarzenie " +
                    "w strefie członków, aby zgłoszenie zostało przypisane do Twojego konta.",
            ru =
                "На нашей публичной странице для мероприятия «{title}» был введён этот адрес электронной почты. " +
                    "Адрес принадлежит учётной записи у нас. Пожалуйста, войдите, а затем зарегистрируйтесь на мероприятие " +
                    "в разделе участников, чтобы регистрация была привязана к вашей учётной записи.",
        )
    val memberNoticeLoginLabel =
        MailText(
            de = "Anmelden",
            en = "Log in",
            es = "Iniciar sesión",
            fr = "Se connecter",
            it = "Accedi",
            nl = "Inloggen",
            pl = "Zaloguj się",
            ru = "Войти",
        )
    val memberNoticeEventLabel =
        MailText(
            de = "Zur Veranstaltung im Mitgliederbereich",
            en = "To the event in the member area",
            es = "Al evento en el área de miembros",
            fr = "Vers l'événement dans l'espace membres",
            it = "All'evento nell'area soci",
            nl = "Naar het evenement in het ledengedeelte",
            pl = "Do wydarzenia w strefie członków",
            ru = "К мероприятию в разделе участников",
        )
    val memberNoticeClosing =
        MailText(
            de = "Falls Sie das nicht waren, können Sie diese E-Mail ignorieren. Es wurde keine Anmeldung gespeichert.",
            en = "If this was not you, you can ignore this e-mail. No registration was saved.",
            es = "Si no fue usted, puede ignorar este correo. No se guardó ninguna inscripción.",
            fr = "Si ce n'était pas vous, vous pouvez ignorer cet e-mail. Aucune inscription n'a été enregistrée.",
            it = "Se non è stato lei, può ignorare questa e-mail. Non è stata salvata alcuna iscrizione.",
            nl = "Was u dit niet, dan kunt u deze e-mail negeren. Er is geen aanmelding opgeslagen.",
            pl = "Jeśli to nie Ty, możesz zignorować tę wiadomość. Zgłoszenie nie zostało zapisane.",
            ru = "Если это были не вы, просто проигнорируйте это письмо. Регистрация не была сохранена.",
        )

    // ── Same guest address typed in again ─────────────────────────────────────────────────────
    val duplicateSubject =
        MailText(
            de = "Ihre Anmeldung: {title}",
            en = "Your registration: {title}",
            es = "Su inscripción: {title}",
            fr = "Votre inscription : {title}",
            it = "La sua iscrizione: {title}",
            nl = "Uw aanmelding: {title}",
            pl = "Twoje zgłoszenie: {title}",
            ru = "Ваша регистрация: {title}",
        )
    val duplicateBody =
        MailText(
            de =
                "Diese E-Mail-Adresse ist für \"{title}\" bereits angemeldet. Es wurde nichts geändert. " +
                    "Ihren Stornierungslink finden Sie in der früheren E-Mail.",
            en =
                "This e-mail address is already registered for \"{title}\". Nothing was changed. " +
                    "You will find your cancellation link in the earlier e-mail.",
            es =
                "Esta dirección de correo ya está inscrita en «{title}». No se ha cambiado nada. " +
                    "Encontrará su enlace de cancelación en el correo anterior.",
            fr =
                "Cette adresse e-mail est déjà inscrite à « {title} ». Rien n'a été modifié. " +
                    "Vous trouverez votre lien d'annulation dans l'e-mail précédent.",
            it =
                "Questo indirizzo e-mail è già iscritto a «{title}». Non è stato modificato nulla. " +
                    "Il link di annullamento si trova nella e-mail precedente.",
            nl =
                "Dit e-mailadres is al aangemeld voor „{title}”. Er is niets gewijzigd. " +
                    "Uw annuleringslink vindt u in de eerdere e-mail.",
            pl =
                "Ten adres e-mail jest już zapisany na „{title}”. Nic nie zostało zmienione. " +
                    "Link do anulowania znajdziesz we wcześniejszej wiadomości.",
            ru =
                "Этот адрес электронной почты уже зарегистрирован на «{title}». Ничего не изменено. " +
                    "Ссылку для отмены вы найдёте в предыдущем письме.",
        )

    // ── Ticket after payment ──────────────────────────────────────────────────────────────────
    val ticketSubject =
        MailText(
            de = "Zahlung bestätigt: {title}",
            en = "Payment confirmed: {title}",
            es = "Pago confirmado: {title}",
            fr = "Paiement confirmé : {title}",
            it = "Pagamento confermato: {title}",
            nl = "Betaling bevestigd: {title}",
            pl = "Płatność potwierdzona: {title}",
            ru = "Оплата подтверждена: {title}",
        )
    val ticketBody =
        MailText(
            de = "Ihre Zahlung für \"{title}\" ist eingegangen -- Ihre Teilnahme ist bestätigt.",
            en = "Your payment for \"{title}\" has been received -- your participation is confirmed.",
            es = "Hemos recibido su pago para «{title}»: su participación está confirmada.",
            fr = "Votre paiement pour « {title} » a bien été reçu -- votre participation est confirmée.",
            it = "Abbiamo ricevuto il suo pagamento per «{title}»: la sua partecipazione è confermata.",
            nl = "Uw betaling voor „{title}” is ontvangen -- uw deelname is bevestigd.",
            pl = "Twoja płatność za „{title}” wpłynęła -- Twój udział jest potwierdzony.",
            ru = "Ваш платёж за «{title}» получен -- ваше участие подтверждено.",
        )

    // ── Ticket reissued ───────────────────────────────────────────────────────────────────────
    val reissueSubject =
        MailText(
            de = "Neues Ticket: {title}",
            en = "New ticket: {title}",
            es = "Nueva entrada: {title}",
            fr = "Nouveau billet : {title}",
            it = "Nuovo biglietto: {title}",
            nl = "Nieuw ticket: {title}",
            pl = "Nowy bilet: {title}",
            ru = "Новый билет: {title}",
        )
    val reissueBody =
        MailText(
            de =
                "Für Ihre Anmeldung zu \"{title}\" wurde ein neues Ticket ausgestellt. " +
                    "Ihr vorheriges Ticket ist ab sofort ungültig.",
            en =
                "A new ticket has been issued for your registration for \"{title}\". " +
                    "Your previous ticket is no longer valid.",
            es =
                "Se ha emitido una nueva entrada para su inscripción en «{title}». " +
                    "Su entrada anterior deja de ser válida de inmediato.",
            fr =
                "Un nouveau billet a été émis pour votre inscription à « {title} ». " +
                    "Votre ancien billet n'est plus valable.",
            it =
                "È stato emesso un nuovo biglietto per la sua iscrizione a «{title}». " +
                    "Il biglietto precedente non è più valido.",
            nl =
                "Er is een nieuw ticket uitgegeven voor uw aanmelding voor „{title}”. " +
                    "Uw vorige ticket is vanaf nu ongeldig.",
            pl =
                "Dla Twojego zgłoszenia na „{title}” wydano nowy bilet. " +
                    "Poprzedni bilet przestaje być ważny.",
            ru =
                "Для вашей регистрации на «{title}» выдан новый билет. " +
                    "Прежний билет с этого момента недействителен.",
        )

    // ── Event cancelled (the reason itself is free text of the board and stays untranslated) ──
    val cancelledSubject =
        MailText(
            de = "Abgesagt: {title}",
            en = "Cancelled: {title}",
            es = "Cancelado: {title}",
            fr = "Annulé : {title}",
            it = "Annullato: {title}",
            nl = "Afgelast: {title}",
            pl = "Odwołane: {title}",
            ru = "Отменено: {title}",
        )
    val cancelledBody =
        MailText(
            de = "Die Veranstaltung \"{title}\" wurde abgesagt.\n\nBegründung: {reason}",
            en = "The event \"{title}\" has been cancelled.\n\nReason: {reason}",
            es = "El evento «{title}» ha sido cancelado.\n\nMotivo: {reason}",
            fr = "L'événement « {title} » a été annulé.\n\nMotif : {reason}",
            it = "L'evento «{title}» è stato annullato.\n\nMotivo: {reason}",
            nl = "Het evenement „{title}” is afgelast.\n\nReden: {reason}",
            pl = "Wydarzenie „{title}” zostało odwołane.\n\nPowód: {reason}",
            ru = "Мероприятие «{title}» отменено.\n\nПричина: {reason}",
        )

    // ── Notice to the creator: a registration with a message came in ──────────────────────────
    val messageNoticeSubject =
        MailText(
            de = "Neue Anmeldung mit Nachricht: {title}",
            en = "New registration with a message: {title}",
            es = "Nueva inscripción con mensaje: {title}",
            fr = "Nouvelle inscription avec message : {title}",
            it = "Nuova iscrizione con messaggio: {title}",
            nl = "Nieuwe aanmelding met bericht: {title}",
            pl = "Nowe zgłoszenie z wiadomością: {title}",
            ru = "Новая регистрация с сообщением: {title}",
        )
    val messageNoticeBody =
        MailText(
            de = "Zu der Veranstaltung \"{title}\" ist eine Anmeldung mit einer Nachricht eingegangen.",
            en = "A registration with a message has been received for the event \"{title}\".",
            es = "Ha llegado una inscripción con un mensaje para el evento «{title}».",
            fr = "Une inscription accompagnée d'un message est arrivée pour l'événement « {title} ».",
            it = "È arrivata un'iscrizione con un messaggio per l'evento «{title}».",
            nl = "Voor het evenement „{title}” is een aanmelding met een bericht binnengekomen.",
            pl = "Na wydarzenie „{title}” wpłynęło zgłoszenie z wiadomością.",
            ru = "На мероприятие «{title}» поступила регистрация с сообщением.",
        )
    val messageNoticeLinkLabel =
        MailText(
            de = "Anmeldungsübersicht öffnen",
            en = "Open the registration overview",
            es = "Abrir el resumen de inscripciones",
            fr = "Ouvrir l'aperçu des inscriptions",
            it = "Apri il riepilogo delle iscrizioni",
            nl = "Overzicht van aanmeldingen openen",
            pl = "Otwórz przegląd zgłoszeń",
            ru = "Открыть обзор регистраций",
        )
    val messageNoticeClosing =
        MailText(
            de = "Aus Datenschutzgründen enthält diese E-Mail den Text der Nachricht nicht.",
            en = "For data protection reasons, this e-mail does not contain the text of the message.",
            es = "Por motivos de protección de datos, este correo no incluye el texto del mensaje.",
            fr = "Pour des raisons de protection des données, cet e-mail ne contient pas le texte du message.",
            it = "Per motivi di protezione dei dati, questa e-mail non contiene il testo del messaggio.",
            nl = "Om privacyredenen bevat deze e-mail de tekst van het bericht niet.",
            pl = "Ze względu na ochronę danych ta wiadomość nie zawiera treści komunikatu.",
            ru = "По соображениям защиты данных это письмо не содержит текст сообщения.",
        )

    /** Every text of this object -- the completeness test iterates it. */
    val all: List<MailText>
        get() =
            listOf(
                greeting,
                greetingAnonymous,
                ticketLabel,
                cancelRegistrationLabel,
                payNowLabel,
                completePaymentLabel,
                waitlistSubject,
                waitlistBodyPay,
                waitlistBodyConfirmed,
                registrationSubjectConfirmed,
                registrationSubjectWaitlisted,
                registrationSubjectPayment,
                registrationBodyConfirmed,
                registrationBodyWaitlisted,
                registrationBodyPayment,
                memberNoticeSubject,
                memberNoticeBody,
                memberNoticeLoginLabel,
                memberNoticeEventLabel,
                memberNoticeClosing,
                duplicateSubject,
                duplicateBody,
                ticketSubject,
                ticketBody,
                reissueSubject,
                reissueBody,
                cancelledSubject,
                cancelledBody,
                messageNoticeSubject,
                messageNoticeBody,
                messageNoticeLinkLabel,
                messageNoticeClosing,
            )
}
