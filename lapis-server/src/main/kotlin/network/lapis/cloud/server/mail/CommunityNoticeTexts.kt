package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the community notices (encounter-space entry, carpool news, article review) in the eight supported languages.
 * German = the original wording; the other languages were written by the model and are NOT reviewed by native speakers. Placeholders only
 * through [fillMail].
 *
 * Plurals: the person counts of the encounter-space notice are phrased number-neutrally ("People present now: {present}") in every language
 * except German and English, so that pl/ru (one/few/many) never produce a grammatically wrong form.
 */
internal object EncounterEntryTexts {
    val subjectSingle =
        MailText(
            de = "Begegnungsraum „{title}“: jemand ist eingetroffen",
            en = "Gathering room \"{title}\": someone has arrived",
            es = "Sala de encuentro «{title}»: ha llegado alguien",
            fr = "Salle de rencontre « {title} » : quelqu'un est arrivé",
            it = "Sala d'incontro «{title}»: è arrivato qualcuno",
            nl = "Ontmoetingsruimte \"{title}\": er is iemand aangekomen",
            pl = "Pokój spotkań „{title}”: ktoś przybył",
            ru = "Комната встреч «{title}»: кто-то пришёл",
        )
    val subjectWindow =
        MailText(
            de = "Begegnungsraum „{title}“: {entries} Personen sind eingetroffen",
            en = "Gathering room \"{title}\": {entries} people have arrived",
            es = "Sala de encuentro «{title}»: nuevas llegadas ({entries})",
            fr = "Salle de rencontre « {title} » : nouvelles arrivées ({entries})",
            it = "Sala d'incontro «{title}»: nuovi arrivi ({entries})",
            nl = "Ontmoetingsruimte \"{title}\": nieuwe aankomsten ({entries})",
            pl = "Pokój spotkań „{title}”: nowe przybycia ({entries})",
            ru = "Комната встреч «{title}»: новые приходы ({entries})",
        )
    val headingSingle =
        MailText(
            de = "Im Begegnungsraum „{title}“ ist jemand eingetroffen.",
            en = "Someone has arrived in the gathering room “{title}”.",
            es = "Ha llegado alguien a la sala de encuentro «{title}».",
            fr = "Quelqu'un est arrivé dans la salle de rencontre « {title} ».",
            it = "È arrivato qualcuno nella sala d'incontro «{title}».",
            nl = "Er is iemand aangekomen in de ontmoetingsruimte \"{title}\".",
            pl = "Ktoś przybył do pokoju spotkań „{title}”.",
            ru = "В комнату встреч «{title}» кто-то пришёл.",
        )
    val headingWindow =
        MailText(
            de = "Im Begegnungsraum „{title}“ sind Personen eingetroffen.",
            en = "People have arrived in the gathering room “{title}”.",
            es = "Han llegado personas a la sala de encuentro «{title}».",
            fr = "Des personnes sont arrivées dans la salle de rencontre « {title} ».",
            it = "Sono arrivate persone nella sala d'incontro «{title}».",
            nl = "Er zijn mensen aangekomen in de ontmoetingsruimte \"{title}\".",
            pl = "Do pokoju spotkań „{title}” przybyły osoby.",
            ru = "В комнату встреч «{title}» пришли люди.",
        )
    val whenAt =
        MailText(
            de = "um {at} Uhr",
            en = "at {at}",
            es = "a las {at}",
            fr = "à {at}",
            it = "alle {at}",
            nl = "om {at}",
            pl = "o {at}",
            ru = "в {at}",
        )
    val whenBetween =
        MailText(
            de = "zwischen {at} und {end} Uhr",
            en = "between {at} and {end}",
            es = "entre las {at} y las {end}",
            fr = "entre {at} et {end}",
            it = "tra le {at} e le {end}",
            nl = "tussen {at} en {end}",
            pl = "między {at} a {end}",
            ru = "между {at} и {end}",
        )
    val detailSingle =
        MailText(
            de = "Eine Person ohne Amt ist {when} eingetroffen. Aktuell sind {present} Personen im Raum.",
            en = "One person without an office arrived {when}. {present} people are in the room now.",
            es = "Una persona sin cargo llegó {when}. Personas presentes ahora: {present}.",
            fr = "Une personne sans fonction est arrivée {when}. Personnes présentes actuellement : {present}.",
            it = "Una persona senza incarico è arrivata {when}. Persone presenti ora: {present}.",
            nl = "Eén persoon zonder ambt is {when} aangekomen. Aanwezige personen nu: {present}.",
            pl = "Jedna osoba bez funkcji przybyła {when}. Obecnie w pokoju przebywa osób: {present}.",
            ru = "Один человек без должности пришёл {when}. Сейчас в комнате человек: {present}.",
        )
    val detailWindow =
        MailText(
            de = "{entries} Personen ohne Amt sind {when} eingetroffen. Aktuell sind {present} Personen im Raum.",
            en = "{entries} people without an office arrived {when}. {present} people are in the room now.",
            es = "Personas sin cargo llegadas {when}: {entries}. Personas presentes ahora: {present}.",
            fr = "Personnes sans fonction arrivées {when} : {entries}. Personnes présentes actuellement : {present}.",
            it = "Persone senza incarico arrivate {when}: {entries}. Persone presenti ora: {present}.",
            nl = "Aantal personen zonder ambt dat {when} is aangekomen: {entries}. Aanwezige personen nu: {present}.",
            pl = "Liczba osób bez funkcji, które przybyły {when}: {entries}. Obecnie w pokoju przebywa osób: {present}.",
            ru = "Число людей без должности, пришедших {when}: {entries}. Сейчас в комнате человек: {present}.",
        )
    val confidentiality =
        MailText(
            de = "Diese Nachricht nennt bewusst keine Namen, um die Vertraulichkeit der Teilnahme zu wahren.",
            en = "This message deliberately names nobody, to keep attendance confidential.",
            es = "Este mensaje no nombra a nadie a propósito, para preservar la confidencialidad de la asistencia.",
            fr = "Ce message ne nomme volontairement personne, afin de préserver la confidentialité de la participation.",
            it = "Questo messaggio non nomina volutamente nessuno, per tutelare la riservatezza della partecipazione.",
            nl = "Dit bericht noemt bewust geen namen, om de vertrouwelijkheid van de deelname te waarborgen.",
            pl = "Ta wiadomość celowo nie podaje nazwisk, aby zachować poufność uczestnictwa.",
            ru = "В этом сообщении намеренно нет имён, чтобы сохранить конфиденциальность участия.",
        )
}

internal object CarpoolNoticeTexts {
    val subjectPrefix =
        MailText(
            de = "Mitfahrzentrale",
            en = "Carpool centre",
            es = "Centro de viajes compartidos",
            fr = "Centrale de covoiturage",
            it = "Centrale del carpooling",
            nl = "Carpoolcentrale",
            pl = "Centrum wspólnych przejazdów",
            ru = "Центр совместных поездок",
        )
    val requested =
        MailText(
            de = "Neue Mitfahranfrage zu Ihrer Fahrt",
            en = "New request to ride along on your trip",
            es = "Nueva solicitud para acompañarle en su viaje",
            fr = "Nouvelle demande pour voyager avec vous",
            it = "Nuova richiesta di passaggio per il suo viaggio",
            nl = "Nieuw verzoek om met uw rit mee te rijden",
            pl = "Nowa prośba o podwiezienie w Twojej podróży",
            ru = "Новый запрос присоединиться к вашей поездке",
        )
    val invited =
        MailText(
            de = "Einladung zu einer Mitfahrt",
            en = "Invitation to ride along",
            es = "Invitación a un viaje compartido",
            fr = "Invitation à un covoiturage",
            it = "Invito a un passaggio",
            nl = "Uitnodiging om mee te rijden",
            pl = "Zaproszenie do wspólnego przejazdu",
            ru = "Приглашение в совместную поездку",
        )
    val confirmed =
        MailText(
            de = "Eine Mitfahrt wurde bestätigt",
            en = "A ride was confirmed",
            es = "Se ha confirmado un viaje compartido",
            fr = "Un covoiturage a été confirmé",
            it = "Un passaggio è stato confermato",
            nl = "Een rit is bevestigd",
            pl = "Przejazd został potwierdzony",
            ru = "Поездка подтверждена",
        )
    val declined =
        MailText(
            de = "Eine Mitfahrt wurde abgelehnt",
            en = "A ride was declined",
            es = "Se ha rechazado un viaje compartido",
            fr = "Un covoiturage a été refusé",
            it = "Un passaggio è stato rifiutato",
            nl = "Een rit is geweigerd",
            pl = "Przejazd został odrzucony",
            ru = "Поездка отклонена",
        )
    val withdrawn =
        MailText(
            de = "Eine Mitfahrt wurde zurückgezogen",
            en = "A ride was withdrawn",
            es = "Se ha retirado un viaje compartido",
            fr = "Un covoiturage a été retiré",
            it = "Un passaggio è stato ritirato",
            nl = "Een rit is ingetrokken",
            pl = "Przejazd został wycofany",
            ru = "Поездка отозвана",
        )
    val postingChanged =
        MailText(
            de = "Eine Fahrt wurde geändert",
            en = "A trip was changed",
            es = "Se ha modificado un viaje",
            fr = "Un trajet a été modifié",
            it = "Un viaggio è stato modificato",
            nl = "Een rit is gewijzigd",
            pl = "Podróż została zmieniona",
            ru = "Поездка изменена",
        )
    val postingDeleted =
        MailText(
            de = "Eine Fahrt wurde gelöscht",
            en = "A trip was deleted",
            es = "Se ha eliminado un viaje",
            fr = "Un trajet a été supprimé",
            it = "Un viaggio è stato eliminato",
            nl = "Een rit is verwijderd",
            pl = "Podróż została usunięta",
            ru = "Поездка удалена",
        )
    val message =
        MailText(
            de = "Neue Nachricht zu Ihrer Mitfahrt",
            en = "New message about your ride",
            es = "Nuevo mensaje sobre su viaje compartido",
            fr = "Nouveau message concernant votre covoiturage",
            it = "Nuovo messaggio sul suo passaggio",
            nl = "Nieuw bericht over uw rit",
            pl = "Nowa wiadomość dotycząca przejazdu",
            ru = "Новое сообщение о вашей поездке",
        )
    val newsLine =
        MailText(
            de = "Es gibt eine Neuigkeit zu der Fahrt am {date} von {from} nach {to}.",
            en = "There is news about the trip on {date} from {from} to {to}.",
            es = "Hay novedades sobre el viaje del {date} de {from} a {to}.",
            fr = "Il y a du nouveau concernant le trajet du {date} de {from} à {to}.",
            it = "Ci sono novità sul viaggio del {date} da {from} a {to}.",
            nl = "Er is nieuws over de rit op {date} van {from} naar {to}.",
            pl = "Jest nowa informacja o podróży dnia {date} z {from} do {to}.",
            ru = "Есть новости о поездке {date} из {from} в {to}.",
        )
    val newsPrivacy =
        MailText(
            de = "Aus Datenschutzgründen nennt diese Nachricht keine Namen. Die Einzelheiten sehen Sie nach der Anmeldung.",
            en = "For privacy reasons this message names nobody. Sign in to see the details.",
            es = "Por motivos de protección de datos, este mensaje no nombra a nadie. Verá los detalles tras iniciar sesión.",
            fr =
                "Pour des raisons de protection des données, ce message ne nomme " +
                    "personne. Vous verrez les détails après vous être connecté.",
            it = "Per motivi di protezione dei dati questo messaggio non nomina nessuno. I dettagli sono visibili dopo l'accesso.",
            nl = "Om privacyredenen noemt dit bericht geen namen. De details ziet u na het aanmelden.",
            pl = "Ze względu na ochronę danych ta wiadomość nie podaje nazwisk. Szczegóły są widoczne po zalogowaniu.",
            ru = "Из соображений защиты данных в этом сообщении нет имён. Подробности вы увидите после входа.",
        )
    val messageLine =
        MailText(
            de = "Sie haben eine neue Nachricht zu einer Ihrer Mitfahrten. Öffnen Sie die Mitfahrzentrale, um sie zu lesen.",
            en = "You have a new message about one of your rides. Open the carpool centre to read it.",
            es = "Tiene un nuevo mensaje sobre uno de sus viajes compartidos. Abra el centro de viajes compartidos para leerlo.",
            fr = "Vous avez un nouveau message concernant l'un de vos covoiturages. Ouvrez la centrale de covoiturage pour le lire.",
            it = "Ha un nuovo messaggio su uno dei suoi passaggi. Apra la centrale del carpooling per leggerlo.",
            nl = "U hebt een nieuw bericht over een van uw ritten. Open de carpoolcentrale om het te lezen.",
            pl = "Masz nową wiadomość dotyczącą jednego z przejazdów. Otwórz centrum wspólnych przejazdów, aby ją przeczytać.",
            ru = "У вас новое сообщение об одной из ваших поездок. Откройте центр совместных поездок, чтобы прочитать его.",
        )
    val messagePrivacy =
        MailText(
            de = "Aus Datenschutzgründen nennt diese Nachricht keine Namen und keinen Inhalt.",
            en = "For privacy reasons this message names nobody and quotes nothing.",
            es = "Por motivos de protección de datos, este mensaje no nombra a nadie ni cita ningún contenido.",
            fr = "Pour des raisons de protection des données, ce message ne nomme personne et ne cite aucun contenu.",
            it = "Per motivi di protezione dei dati questo messaggio non nomina nessuno e non riporta alcun contenuto.",
            nl = "Om privacyredenen noemt dit bericht geen namen en geeft het geen inhoud weer.",
            pl = "Ze względu na ochronę danych ta wiadomość nie podaje nazwisk ani treści.",
            ru = "Из соображений защиты данных в этом сообщении нет ни имён, ни содержания.",
        )
    val linkLabel =
        MailText(
            de = "Mitfahrzentrale öffnen",
            en = "Open the carpool centre",
            es = "Abrir el centro de viajes compartidos",
            fr = "Ouvrir la centrale de covoiturage",
            it = "Apri la centrale del carpooling",
            nl = "Carpoolcentrale openen",
            pl = "Otwórz centrum wspólnych przejazdów",
            ru = "Открыть центр совместных поездок",
        )
}

internal object ArticleReviewTexts {
    val approvedSubject =
        MailText(
            de = "Artikel „{title}“ veröffentlicht",
            en = "Article \"{title}\" published",
            es = "Artículo «{title}» publicado",
            fr = "Article « {title} » publié",
            it = "Articolo «{title}» pubblicato",
            nl = "Artikel \"{title}\" gepubliceerd",
            pl = "Artykuł „{title}” opublikowany",
            ru = "Статья «{title}» опубликована",
        )
    val rejectedSubject =
        MailText(
            de = "Artikel „{title}“ abgelehnt",
            en = "Article \"{title}\" rejected",
            es = "Artículo «{title}» rechazado",
            fr = "Article « {title} » refusé",
            it = "Articolo «{title}» rifiutato",
            nl = "Artikel \"{title}\" afgewezen",
            pl = "Artykuł „{title}” odrzucony",
            ru = "Статья «{title}» отклонена",
        )
    val unpublishedSubject =
        MailText(
            de = "Artikel „{title}“ depubliziert",
            en = "Article \"{title}\" unpublished",
            es = "Artículo «{title}» despublicado",
            fr = "Article « {title} » dépublié",
            it = "Articolo «{title}» depubblicato",
            nl = "Artikel \"{title}\" gedepubliceerd",
            pl = "Artykuł „{title}” wycofany z publikacji",
            ru = "Статья «{title}» снята с публикации",
        )
    val approvedHeading =
        MailText(
            de = "Ihr Artikel wurde veröffentlicht",
            en = "Your article was published",
            es = "Su artículo ha sido publicado",
            fr = "Votre article a été publié",
            it = "Il suo articolo è stato pubblicato",
            nl = "Uw artikel is gepubliceerd",
            pl = "Artykuł został opublikowany",
            ru = "Ваша статья опубликована",
        )
    val rejectedHeading =
        MailText(
            de = "Ihr Artikel wurde abgelehnt",
            en = "Your article was rejected",
            es = "Su artículo ha sido rechazado",
            fr = "Votre article a été refusé",
            it = "Il suo articolo è stato rifiutato",
            nl = "Uw artikel is afgewezen",
            pl = "Artykuł został odrzucony",
            ru = "Ваша статья отклонена",
        )
    val unpublishedHeading =
        MailText(
            de = "Ihr Artikel wurde depubliziert",
            en = "Your article was unpublished",
            es = "Su artículo ha sido despublicado",
            fr = "Votre article a été dépublié",
            it = "Il suo articolo è stato depubblicato",
            nl = "Uw artikel is gedepubliceerd",
            pl = "Artykuł został wycofany z publikacji",
            ru = "Ваша статья снята с публикации",
        )
    val approvedIntro =
        MailText(
            de = "Ihr Artikel „{title}“ wurde vom Vorstand geprüft und veröffentlicht.",
            en = "Your article \"{title}\" was reviewed by the board and published.",
            es = "Su artículo «{title}» fue revisado por la junta directiva y publicado.",
            fr = "Votre article « {title} » a été examiné par le comité directeur et publié.",
            it = "Il suo articolo «{title}» è stato esaminato dal consiglio direttivo e pubblicato.",
            nl = "Uw artikel \"{title}\" is door het bestuur beoordeeld en gepubliceerd.",
            pl = "Artykuł „{title}” został sprawdzony przez zarząd i opublikowany.",
            ru = "Ваша статья «{title}» проверена правлением и опубликована.",
        )
    val rejectedIntro =
        MailText(
            de = "Ihr Artikel „{title}“ wurde vom Vorstand geprüft und nicht veröffentlicht.",
            en = "Your article \"{title}\" was reviewed by the board and not published.",
            es = "Su artículo «{title}» fue revisado por la junta directiva y no se ha publicado.",
            fr = "Votre article « {title} » a été examiné par le comité directeur et n'a pas été publié.",
            it = "Il suo articolo «{title}» è stato esaminato dal consiglio direttivo e non è stato pubblicato.",
            nl = "Uw artikel \"{title}\" is door het bestuur beoordeeld en niet gepubliceerd.",
            pl = "Artykuł „{title}” został sprawdzony przez zarząd i nie został opublikowany.",
            ru = "Ваша статья «{title}» проверена правлением и не опубликована.",
        )
    val unpublishedIntro =
        MailText(
            de = "Ihr Artikel „{title}“ wurde vom Vorstand von der Webseite genommen.",
            en = "Your article \"{title}\" was taken off the website by the board.",
            es = "Su artículo «{title}» fue retirado del sitio web por la junta directiva.",
            fr = "Votre article « {title} » a été retiré du site web par le comité directeur.",
            it = "Il suo articolo «{title}» è stato rimosso dal sito web dal consiglio direttivo.",
            nl = "Uw artikel \"{title}\" is door het bestuur van de website gehaald.",
            pl = "Artykuł „{title}” został usunięty ze strony internetowej przez zarząd.",
            ru = "Ваша статья «{title}» снята правлением с сайта.",
        )
    val reasonLine =
        MailText(
            de = "Begründung: {reason}",
            en = "Reason: {reason}",
            es = "Motivo: {reason}",
            fr = "Motif : {reason}",
            it = "Motivazione: {reason}",
            nl = "Reden: {reason}",
            pl = "Uzasadnienie: {reason}",
            ru = "Причина: {reason}",
        )
    val noReason =
        MailText(
            de = "Der Vorstand hat keine Begründung angegeben.",
            en = "The board did not give a reason.",
            es = "La junta directiva no indicó ningún motivo.",
            fr = "Le comité directeur n'a pas indiqué de motif.",
            it = "Il consiglio direttivo non ha indicato alcuna motivazione.",
            nl = "Het bestuur heeft geen reden opgegeven.",
            pl = "Zarząd nie podał uzasadnienia.",
            ru = "Правление не указало причину.",
        )
    val findHere =
        MailText(
            de = "Sie finden Ihren Artikel hier:",
            en = "You can find your article here:",
            es = "Puede encontrar su artículo aquí:",
            fr = "Vous trouverez votre article ici :",
            it = "Può trovare il suo articolo qui:",
            nl = "U vindt uw artikel hier:",
            pl = "Artykuł znajdziesz tutaj:",
            ru = "Вашу статью можно найти здесь:",
        )
    val linkLabel =
        MailText(
            de = "Artikel öffnen",
            en = "Open the article",
            es = "Abrir el artículo",
            fr = "Ouvrir l'article",
            it = "Apri l'articolo",
            nl = "Artikel openen",
            pl = "Otwórz artykuł",
            ru = "Открыть статью",
        )
}
