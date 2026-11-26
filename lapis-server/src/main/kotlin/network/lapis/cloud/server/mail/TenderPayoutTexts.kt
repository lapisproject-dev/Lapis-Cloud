package network.lapis.cloud.server.mail

/**
 * V1.9.332 -- the wording of the tender payout mails (receipt to the winner, notice to the board) in the eight supported languages.
 *
 * Placeholders are plain `{brand}` / `{amount}` / `{title}` / `{when}` tokens replaced in ONE pass by [fillTenderPayout] -- never string
 * templates, and a value that itself contains a token is not expanded again. The German texts are the V1.9.330/331 wording; the other
 * translations were written by the model and are NOT reviewed by native speakers.
 */
internal data class TenderPayoutPaidTexts(
    val subject: String,
    val heading: String,
    val sentence: String,
    val linkIntro: String,
    val linkLabel: String,
) {
    companion object {
        fun forLanguage(language: String?): TenderPayoutPaidTexts = ALL.getValue(MembershipDecisionTexts.normalize(language))

        private val ALL: Map<String, TenderPayoutPaidTexts> =
            mapOf(
                "de" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR gutgeschrieben",
                        heading = "LTR gutgeschrieben",
                        sentence = "Ihnen wurden {amount} LTR für die Ausschreibung „{title}“ gutgeschrieben ({when}).",
                        linkIntro = "Zur Ausschreibung:",
                        linkLabel = "Ausschreibung öffnen",
                    ),
                "en" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR credited",
                        heading = "LTR credited",
                        sentence = "{amount} LTR have been credited to you for the tender \"{title}\" ({when}).",
                        linkIntro = "Go to the tender:",
                        linkLabel = "Open the tender",
                    ),
                "es" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR abonados",
                        heading = "LTR abonados",
                        sentence = "Se le han abonado {amount} LTR por la licitación «{title}» ({when}).",
                        linkIntro = "Ir a la licitación:",
                        linkLabel = "Abrir la licitación",
                    ),
                "fr" to
                    TenderPayoutPaidTexts(
                        subject = "{brand} : LTR crédités",
                        heading = "LTR crédités",
                        sentence = "{amount} LTR vous ont été crédités pour l'appel d'offres « {title} » ({when}).",
                        linkIntro = "Accéder à l'appel d'offres :",
                        linkLabel = "Ouvrir l'appel d'offres",
                    ),
                "it" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR accreditati",
                        heading = "LTR accreditati",
                        sentence = "Le sono stati accreditati {amount} LTR per il bando «{title}» ({when}).",
                        linkIntro = "Vai al bando:",
                        linkLabel = "Apri il bando",
                    ),
                "nl" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR bijgeschreven",
                        heading = "LTR bijgeschreven",
                        sentence = "Er zijn {amount} LTR aan u bijgeschreven voor de aanbesteding \"{title}\" ({when}).",
                        linkIntro = "Naar de aanbesteding:",
                        linkLabel = "Aanbesteding openen",
                    ),
                "pl" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR zaksięgowane",
                        heading = "LTR zaksięgowane",
                        sentence = "Zaksięgowano {amount} LTR za przetarg „{title}” ({when}).",
                        linkIntro = "Przejdź do przetargu:",
                        linkLabel = "Otwórz przetarg",
                    ),
                "ru" to
                    TenderPayoutPaidTexts(
                        subject = "{brand}: LTR зачислены",
                        heading = "LTR зачислены",
                        sentence = "Вам зачислено {amount} LTR за тендер «{title}» ({when}).",
                        linkIntro = "Перейти к тендеру:",
                        linkLabel = "Открыть тендер",
                    ),
            )
    }
}

internal data class TenderPayoutFailedTexts(
    val subject: String,
    val heading: String,
    val p1: String,
    val p2: String,
    val linkLabel: String,
) {
    companion object {
        fun forLanguage(language: String?): TenderPayoutFailedTexts = ALL.getValue(MembershipDecisionTexts.normalize(language))

        private val ALL: Map<String, TenderPayoutFailedTexts> =
            mapOf(
                "de" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: LTR-Auszahlung fehlgeschlagen",
                        heading = "LTR-Auszahlung fehlgeschlagen",
                        p1 =
                            "Die LTR-Auszahlung der Ausschreibung „{title}“ konnte nicht gebucht werden, " +
                                "weil der Gewinner derzeit kein LTR empfangen kann. Es wurde nichts gebucht.",
                        p2 = "Sobald die Ursache geklärt ist, kann die Auszahlung auf der Ausschreibung erneut ausgelöst werden.",
                        linkLabel = "Ausschreibung öffnen",
                    ),
                "en" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: LTR payout failed",
                        heading = "LTR payout failed",
                        p1 =
                            "The LTR payout of the tender \"{title}\" could not be booked " +
                                "because the winner cannot currently receive LTR. Nothing was booked.",
                        p2 = "Once the cause is resolved, the payout can be triggered again on the tender.",
                        linkLabel = "Open the tender",
                    ),
                "es" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: pago de LTR fallido",
                        heading = "Pago de LTR fallido",
                        p1 =
                            "No se pudo contabilizar el pago de LTR de la licitación «{title}», " +
                                "porque el ganador no puede recibir LTR en este momento. No se ha contabilizado nada.",
                        p2 = "Una vez aclarada la causa, el pago puede volver a activarse en la licitación.",
                        linkLabel = "Abrir la licitación",
                    ),
                "fr" to
                    TenderPayoutFailedTexts(
                        subject = "{brand} : échec du versement de LTR",
                        heading = "Échec du versement de LTR",
                        p1 =
                            "Le versement de LTR de l'appel d'offres « {title} » n'a pas pu être comptabilisé, " +
                                "car le gagnant ne peut actuellement pas recevoir de LTR. Rien n'a été comptabilisé.",
                        p2 = "Une fois la cause élucidée, le versement peut être relancé depuis l'appel d'offres.",
                        linkLabel = "Ouvrir l'appel d'offres",
                    ),
                "it" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: pagamento LTR non riuscito",
                        heading = "Pagamento LTR non riuscito",
                        p1 =
                            "Il pagamento LTR del bando «{title}» non ha potuto essere registrato, " +
                                "perché al momento il vincitore non può ricevere LTR. Non è stato registrato nulla.",
                        p2 = "Una volta chiarita la causa, il pagamento può essere riavviato dal bando.",
                        linkLabel = "Apri il bando",
                    ),
                "nl" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: LTR-uitbetaling mislukt",
                        heading = "LTR-uitbetaling mislukt",
                        p1 =
                            "De LTR-uitbetaling van de aanbesteding \"{title}\" kon niet worden geboekt, " +
                                "omdat de winnaar momenteel geen LTR kan ontvangen. Er is niets geboekt.",
                        p2 = "Zodra de oorzaak is opgehelderd, kan de uitbetaling opnieuw worden gestart bij de aanbesteding.",
                        linkLabel = "Aanbesteding openen",
                    ),
                "pl" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: wypłata LTR nie powiodła się",
                        heading = "Wypłata LTR nie powiodła się",
                        p1 =
                            "Nie udało się zaksięgować wypłaty LTR za przetarg „{title}”, " +
                                "ponieważ zwycięzca nie może obecnie otrzymać LTR. Nic nie zostało zaksięgowane.",
                        p2 = "Po wyjaśnieniu przyczyny wypłatę można ponownie uruchomić w przetargu.",
                        linkLabel = "Otwórz przetarg",
                    ),
                "ru" to
                    TenderPayoutFailedTexts(
                        subject = "{brand}: выплата LTR не удалась",
                        heading = "Выплата LTR не удалась",
                        p1 =
                            "Выплату LTR по тендеру «{title}» не удалось провести, " +
                                "так как победитель в настоящее время не может получать LTR. Ничего не проведено.",
                        p2 = "После выяснения причины выплату можно запустить повторно в тендере.",
                        linkLabel = "Открыть тендер",
                    ),
            )
    }
}

private val TOKEN = Regex("\\{(brand|amount|title|when)}")

/** Replaces `{brand}`/`{amount}`/`{title}`/`{when}` in ONE pass: a value containing a token (a tender title is free text) is not expanded again. */
internal fun fillTenderPayout(
    template: String,
    values: Map<String, String>,
): String = TOKEN.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
