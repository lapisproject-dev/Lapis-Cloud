package network.lapis.cloud.server.mail

/**
 * V1.9.334 -- one wording of a system mail in the eight supported languages (see [MembershipDecisionTexts.SUPPORTED]).
 *
 * The German texts are the wording the mails always had; the other seven languages were written by the model and are NOT reviewed by native
 * speakers. Placeholders are plain `{name}` tokens replaced in ONE pass by [fillMail] -- never string templates (`${'$'}{...}` is forbidden in the
 * `*Texts.kt` files, a tripwire test scans for it). Every language of one [MailText] must use the same token set (completeness test).
 */
internal class MailText(
    val de: String,
    val en: String,
    val es: String,
    val fr: String,
    val it: String,
    val nl: String,
    val pl: String,
    val ru: String,
) {
    /** Unknown, blank or oversize codes fall back to German. */
    fun forLanguage(language: String?): String =
        when (MembershipDecisionTexts.normalize(language)) {
            "en" -> en
            "es" -> es
            "fr" -> fr
            "it" -> it
            "nl" -> nl
            "pl" -> pl
            "ru" -> ru
            else -> de
        }

    fun byCode(): Map<String, String> =
        linkedMapOf("de" to de, "en" to en, "es" to es, "fr" to fr, "it" to it, "nl" to nl, "pl" to pl, "ru" to ru)
}

private val MAIL_TOKEN = Regex("\\{([a-zA-Z]+)}")

/** Replaces `{name}` tokens in ONE pass: a value that itself contains `{x}` is never expanded again; an unknown token stays literally. */
internal fun fillMail(
    template: String,
    values: Map<String, String>,
): String = MAIL_TOKEN.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

/** The `{name}` tokens of a template (used by the completeness test). */
internal fun mailTokens(template: String): Set<String> = MAIL_TOKEN.findAll(template).map { it.groupValues[1] }.toSet()

/** The parts of every system mail that are not specific to one mail: footer, "unknown" and the hour plurals. */
internal object MailCommonTexts {
    val footerReplyTo =
        MailText(
            de = "Fragen? Antworten Sie einfach auf diese E-Mail ({address}).",
            en = "Questions? Simply reply to this e-mail ({address}).",
            es = "¿Preguntas? Responda simplemente a este correo ({address}).",
            fr = "Des questions ? Répondez simplement à cet e-mail ({address}).",
            it = "Domande? Risponda semplicemente a questa e-mail ({address}).",
            nl = "Vragen? Beantwoord gewoon deze e-mail ({address}).",
            pl = "Pytania? Wystarczy odpowiedzieć na tę wiadomość ({address}).",
            ru = "Есть вопросы? Просто ответьте на это письмо ({address}).",
        )
    val footerNoReply =
        MailText(
            de = "Diese Adresse wird nicht gelesen. Fragen: {url}",
            en = "This address is not monitored. Questions: {url}",
            es = "Esta dirección no se lee. Preguntas: {url}",
            fr = "Cette adresse n'est pas consultée. Questions : {url}",
            it = "Questo indirizzo non viene letto. Domande: {url}",
            nl = "Dit adres wordt niet gelezen. Vragen: {url}",
            pl = "Ta skrzynka nie jest odczytywana. Pytania: {url}",
            ru = "Этот адрес не читается. Вопросы: {url}",
        )
    val unknownTime =
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
    val hourOne =
        MailText(
            de = "{n} Stunde",
            en = "{n} hour",
            es = "{n} hora",
            fr = "{n} heure",
            it = "{n} ora",
            nl = "{n} uur",
            pl = "{n} godzina",
            ru = "{n} час",
        )
    val hourFew =
        MailText(
            de = "{n} Stunden",
            en = "{n} hours",
            es = "{n} horas",
            fr = "{n} heures",
            it = "{n} ore",
            nl = "{n} uur",
            pl = "{n} godziny",
            ru = "{n} часа",
        )
    val hourMany =
        MailText(
            de = "{n} Stunden",
            en = "{n} hours",
            es = "{n} horas",
            fr = "{n} heures",
            it = "{n} ore",
            nl = "{n} uur",
            pl = "{n} godzin",
            ru = "{n} часов",
        )

    /** "If you did not expect this, contact us" -- the one closing sentence of most notices. */
    val notExpectedContact =
        MailText(
            de = "Wenn Sie das nicht erwartet haben, melden Sie sich bitte umgehend bei uns.",
            en = "If you did not expect this, please contact us immediately.",
            es = "Si no esperaba esto, póngase en contacto con nosotros de inmediato.",
            fr = "Si vous ne vous y attendiez pas, veuillez nous contacter immédiatement.",
            it = "Se non se lo aspettava, ci contatti subito.",
            nl = "Als u dit niet verwachtte, neem dan onmiddellijk contact met ons op.",
            pl = "Jeśli nie jest to oczekiwane, prosimy o niezwłoczny kontakt z nami.",
            ru = "Если вы этого не ожидали, пожалуйста, немедленно свяжитесь с нами.",
        )

    /** "If you did not expect this, check the member administration and the audit log." */
    val notExpectedReview =
        MailText(
            de = "Wenn Sie das nicht erwartet haben, prüfen Sie bitte die Mitgliederverwaltung und das Änderungsprotokoll.",
            en = "If you did not expect this, please review the member administration and the change log.",
            es = "Si no esperaba esto, revise la administración de miembros y el registro de cambios.",
            fr = "Si vous ne vous y attendiez pas, veuillez vérifier la gestion des membres et le journal des modifications.",
            it = "Se non se lo aspettava, controlli l'amministrazione dei membri e il registro delle modifiche.",
            nl = "Als u dit niet verwachtte, controleer dan het ledenbeheer en het wijzigingslogboek.",
            pl = "Jeśli nie jest to oczekiwane, prosimy sprawdzić administrację członków i dziennik zmian.",
            ru = "Если вы этого не ожидали, проверьте управление участниками и журнал изменений.",
        )

    /** "If you did not request this, ignore this e-mail -- nothing has been changed." */
    val ignoreNothingChanged =
        MailText(
            de = "Wenn Sie das nicht erwartet haben, können Sie diese E-Mail ignorieren – es wurde nichts verändert.",
            en = "If you did not expect this, you can ignore this e-mail – nothing has been changed.",
            es = "Si no esperaba esto, puede ignorar este correo: no se ha cambiado nada.",
            fr = "Si vous ne vous y attendiez pas, vous pouvez ignorer cet e-mail : rien n'a été modifié.",
            it = "Se non se lo aspettava, può ignorare questa e-mail: non è stato modificato nulla.",
            nl = "Als u dit niet verwachtte, kunt u deze e-mail negeren – er is niets gewijzigd.",
            pl = "Jeśli nie jest to oczekiwane, można zignorować tę wiadomość – nic nie zostało zmienione.",
            ru = "Если вы этого не ожидали, можете проигнорировать это письмо – ничего не изменено.",
        )
}

/** `1 hour` / `24 hours` in the grammatically right form (pl/ru: one/few/many; the other languages: one/other). */
internal fun hoursText(
    n: Long,
    language: String,
): String {
    val lang = MembershipDecisionTexts.normalize(language)
    val mod10 = n % 10
    val mod100 = n % 100
    val text =
        when (lang) {
            "pl" ->
                when {
                    n == 1L -> MailCommonTexts.hourOne
                    mod10 in 2..4 && mod100 !in 12..14 -> MailCommonTexts.hourFew
                    else -> MailCommonTexts.hourMany
                }
            "ru" ->
                when {
                    mod10 == 1L && mod100 != 11L -> MailCommonTexts.hourOne
                    mod10 in 2..4 && mod100 !in 12..14 -> MailCommonTexts.hourFew
                    else -> MailCommonTexts.hourMany
                }
            else -> if (n == 1L) MailCommonTexts.hourOne else MailCommonTexts.hourMany
        }
    return fillMail(template = text.forLanguage(lang), values = mapOf("n" to n.toString()))
}
