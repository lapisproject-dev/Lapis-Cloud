package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- the wording of the operational notices to the board (webhook endpoint deactivated, FinTS re-authentication) in the eight
 * supported languages. German = the original wording; the other languages were written by the model and are NOT reviewed by native speakers.
 * Placeholders only through [fillMail].
 */
internal object WebhookDeactivatedTexts {
    val heading =
        MailText(
            de = "Webhook deaktiviert",
            en = "Webhook deactivated",
            es = "Webhook desactivado",
            fr = "Webhook désactivé",
            it = "Webhook disattivato",
            nl = "Webhook gedeactiveerd",
            pl = "Webhook dezaktywowany",
            ru = "Webhook отключён",
        )
    val body =
        MailText(
            de =
                "Der Webhook für den API-Schlüssel „{key}“ (Ziel-Host: {host}) wurde nach " +
                    "wiederholten Zustellfehlern automatisch deaktiviert.",
            en =
                "The webhook for the API key \"{key}\" (target host: {host}) was automatically deactivated after " +
                    "repeated delivery failures.",
            es =
                "El webhook de la clave de API «{key}» (host de destino: {host}) se desactivó automáticamente tras " +
                    "repetidos fallos de entrega.",
            fr =
                "Le webhook de la clé d'API « {key} » (hôte cible : {host}) a été désactivé automatiquement après " +
                    "des échecs de remise répétés.",
            it =
                "Il webhook della chiave API «{key}» (host di destinazione: {host}) è stato disattivato automaticamente dopo " +
                    "ripetuti errori di consegna.",
            nl =
                "De webhook voor de API-sleutel \"{key}\" (doelhost: {host}) is automatisch gedeactiveerd na " +
                    "herhaalde afleverfouten.",
            pl =
                "Webhook dla klucza API „{key}” (host docelowy: {host}) został automatycznie dezaktywowany po " +
                    "wielokrotnych błędach dostarczenia.",
            ru =
                "Webhook для API-ключа «{key}» (целевой хост: {host}) был автоматически отключён после " +
                    "повторных ошибок доставки.",
        )
    val lastEvent =
        MailText(
            de = "Letztes Ereignis: {event}, Versuch {attempt}, {status}.",
            en = "Last event: {event}, attempt {attempt}, {status}.",
            es = "Último evento: {event}, intento {attempt}, {status}.",
            fr = "Dernier événement : {event}, tentative {attempt}, {status}.",
            it = "Ultimo evento: {event}, tentativo {attempt}, {status}.",
            nl = "Laatste gebeurtenis: {event}, poging {attempt}, {status}.",
            pl = "Ostatnie zdarzenie: {event}, próba {attempt}, {status}.",
            ru = "Последнее событие: {event}, попытка {attempt}, {status}.",
        )
    val statusLine =
        MailText(
            de = "letzter HTTP-Status: {code}",
            en = "last HTTP status: {code}",
            es = "último estado HTTP: {code}",
            fr = "dernier statut HTTP : {code}",
            it = "ultimo stato HTTP: {code}",
            nl = "laatste HTTP-status: {code}",
            pl = "ostatni status HTTP: {code}",
            ru = "последний статус HTTP: {code}",
        )
    val noStatus =
        MailText(
            de = "kein HTTP-Status erhalten",
            en = "no HTTP status received",
            es = "no se recibió ningún estado HTTP",
            fr = "aucun statut HTTP reçu",
            it = "nessuno stato HTTP ricevuto",
            nl = "geen HTTP-status ontvangen",
            pl = "nie otrzymano statusu HTTP",
            ru = "статус HTTP не получен",
        )
    val unknownHost =
        MailText(
            de = "(unbekannt)",
            en = "(unknown)",
            es = "(desconocido)",
            fr = "(inconnu)",
            it = "(sconosciuto)",
            nl = "(onbekend)",
            pl = "(nieznany)",
            ru = "(неизвестно)",
        )
    val linkIntro =
        MailText(
            de = "Öffnen Sie die API-Schlüssel-Verwaltung, um den Webhook zu prüfen und ggf. wieder zu aktivieren:",
            en = "Open the API key administration to check the webhook and, if necessary, reactivate it:",
            es = "Abra la administración de claves de API para revisar el webhook y, si procede, reactivarlo:",
            fr = "Ouvrez la gestion des clés d'API pour vérifier le webhook et, le cas échéant, le réactiver :",
            it = "Apra l'amministrazione delle chiavi API per controllare il webhook ed eventualmente riattivarlo:",
            nl = "Open het beheer van API-sleutels om de webhook te controleren en zo nodig weer te activeren:",
            pl = "Otwórz zarządzanie kluczami API, aby sprawdzić webhook i w razie potrzeby ponownie go aktywować:",
            ru = "Откройте управление API-ключами, чтобы проверить webhook и при необходимости снова включить его:",
        )
    val linkLabel =
        MailText(
            de = "API-Schlüssel-Verwaltung öffnen",
            en = "Open the API key administration",
            es = "Abrir la administración de claves de API",
            fr = "Ouvrir la gestion des clés d'API",
            it = "Apri l'amministrazione delle chiavi API",
            nl = "Beheer van API-sleutels openen",
            pl = "Otwórz zarządzanie kluczami API",
            ru = "Открыть управление API-ключами",
        )
}

internal object FinTsReauthTexts {
    val subjectLead =
        MailText(
            de = "FinTS-Live-Abruf muss neu angemeldet werden",
            en = "FinTS live retrieval needs a new sign-in",
            es = "La consulta en vivo FinTS requiere un nuevo inicio de sesión",
            fr = "La récupération en direct FinTS nécessite une nouvelle connexion",
            it = "Il recupero in tempo reale FinTS richiede un nuovo accesso",
            nl = "FinTS-liveopvraging moet opnieuw worden aangemeld",
            pl = "Pobieranie na żywo FinTS wymaga ponownego logowania",
            ru = "Для онлайн-получения FinTS нужен повторный вход",
        )
    val heading =
        MailText(
            de = "FinTS-Live-Abruf: erneute Anmeldung erforderlich",
            en = "FinTS live retrieval: new sign-in required",
            es = "Consulta en vivo FinTS: se requiere un nuevo inicio de sesión",
            fr = "Récupération en direct FinTS : nouvelle connexion requise",
            it = "Recupero in tempo reale FinTS: nuovo accesso richiesto",
            nl = "FinTS-liveopvraging: opnieuw aanmelden vereist",
            pl = "Pobieranie na żywo FinTS: wymagane ponowne logowanie",
            ru = "Онлайн-получение FinTS: требуется повторный вход",
        )
    val body =
        MailText(
            de =
                "Der Live-Abruf für das Bankkonto „{account}“ ({iban}) benötigt eine erneute Anmeldung (Datum/Uhrzeit: {when}). " +
                    "Bis dahin werden keine neuen Kontoumsätze mehr automatisch abgerufen.",
            en =
                "The live retrieval for the bank account \"{account}\" ({iban}) needs a new sign-in (date/time: {when}). " +
                    "Until then, no new account transactions are retrieved automatically.",
            es =
                "La consulta en vivo de la cuenta bancaria «{account}» ({iban}) requiere un nuevo inicio de sesión (fecha/hora: {when}). " +
                    "Hasta entonces no se consultarán automáticamente nuevos movimientos.",
            fr =
                "La récupération en direct du compte bancaire « {account} » ({iban}) " +
                    "nécessite une nouvelle connexion (date/heure : {when}). " +
                    "D'ici là, plus aucun nouveau mouvement n'est récupéré automatiquement.",
            it =
                "Il recupero in tempo reale del conto bancario «{account}» ({iban}) richiede un nuovo accesso (data/ora: {when}). " +
                    "Fino ad allora non vengono più recuperati automaticamente nuovi movimenti.",
            nl =
                "De liveopvraging voor de bankrekening \"{account}\" ({iban}) moet opnieuw worden aangemeld (datum/tijd: {when}). " +
                    "Tot die tijd worden geen nieuwe rekeningmutaties meer automatisch opgehaald.",
            pl =
                "Pobieranie na żywo dla konta bankowego „{account}” ({iban}) wymaga ponownego logowania (data/godzina: {when}). " +
                    "Do tego czasu nowe operacje nie są już pobierane automatycznie.",
            ru =
                "Для онлайн-получения по банковскому счёту «{account}» ({iban}) требуется повторный вход (дата/время: {when}). " +
                    "До этого новые операции по счёту автоматически не загружаются.",
        )
    val linkIntro =
        MailText(
            de = "Öffnen Sie die Bankkonten-Verwaltung, um sich erneut anzumelden:",
            en = "Open the bank account administration to sign in again:",
            es = "Abra la administración de cuentas bancarias para iniciar sesión de nuevo:",
            fr = "Ouvrez la gestion des comptes bancaires pour vous reconnecter :",
            it = "Apra l'amministrazione dei conti bancari per accedere di nuovo:",
            nl = "Open het beheer van bankrekeningen om opnieuw aan te melden:",
            pl = "Otwórz zarządzanie kontami bankowymi, aby zalogować się ponownie:",
            ru = "Откройте управление банковскими счетами, чтобы войти снова:",
        )
    val linkLabel =
        MailText(
            de = "Bankkonten-Verwaltung öffnen",
            en = "Open the bank account administration",
            es = "Abrir la administración de cuentas bancarias",
            fr = "Ouvrir la gestion des comptes bancaires",
            it = "Apri l'amministrazione dei conti bancari",
            nl = "Beheer van bankrekeningen openen",
            pl = "Otwórz zarządzanie kontami bankowymi",
            ru = "Открыть управление банковскими счетами",
        )
}
