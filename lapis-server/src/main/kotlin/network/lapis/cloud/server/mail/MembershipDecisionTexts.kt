package network.lapis.cloud.server.mail

/**
 * V1.9.118 -- the wording of the membership-decision mails in the eight supported languages.
 *
 * Placeholders are plain `{name}` / `{org}` / `{address}` tokens replaced by [fill] -- never string templates, so a stray dollar sign in
 * a translation cannot break compilation. The translations were written by the model and are NOT reviewed by native speakers.
 */
internal data class MembershipDecisionTexts(
    val subjectApproved: String,
    val greeting: String,
    val approvedBodyWithOrg: String,
    val approvedBody: String,
    val approvedLogin: String,
    val loginLabelApproved: String,
    val subjectRejected: String,
    val rejectedBodyWithOrg: String,
    val rejectedBody: String,
    val friendKept: String,
    val loginLabelFriend: String,
    val contactReplyTo: String,
    val contactBoard: String,
) {
    companion object {
        const val DEFAULT_LANGUAGE = "de"

        /** Language codes (BCP-47 primary subtag) a decision mail can be written in. */
        val SUPPORTED: Set<String> get() = ALL.keys

        /** Unknown, blank or oversize codes fall back to [DEFAULT_LANGUAGE]. */
        fun normalize(language: String?): String {
            val code = language?.trim()?.lowercase().orEmpty()
            return if (code in ALL) code else DEFAULT_LANGUAGE
        }

        fun forLanguage(language: String?): MembershipDecisionTexts = ALL.getValue(normalize(language))

        fun fill(
            template: String,
            name: String? = null,
            org: String? = null,
            address: String? = null,
        ): String {
            var out = template
            if (name != null) out = out.replace("{name}", name)
            if (org != null) out = out.replace("{org}", org)
            if (address != null) out = out.replace("{address}", address)
            return out
        }

        private val DE =
            MembershipDecisionTexts(
                subjectApproved = "Ihr Mitgliedsantrag wurde angenommen",
                greeting = "Guten Tag {name},",
                approvedBodyWithOrg = "Ihr Antrag auf Mitgliedschaft bei {org} wurde angenommen. Herzlich willkommen!",
                approvedBody = "Ihr Antrag auf Mitgliedschaft wurde angenommen. Herzlich willkommen!",
                approvedLogin = "Sie können sich ab sofort anmelden.",
                loginLabelApproved = "Jetzt anmelden",
                subjectRejected = "Ihr Mitgliedsantrag",
                rejectedBodyWithOrg = "Ihr Antrag auf Mitgliedschaft bei {org} wurde leider nicht angenommen.",
                rejectedBody = "Ihr Antrag auf Mitgliedschaft wurde leider nicht angenommen.",
                friendKept = "Ihr Zugang als Freund bleibt bestehen.",
                loginLabelFriend = "Anmelden",
                contactReplyTo = "Bei Fragen antworten Sie einfach auf diese E-Mail ({address}).",
                contactBoard = "Bei Fragen wenden Sie sich bitte an den Vorstand.",
            )

        private val EN =
            MembershipDecisionTexts(
                subjectApproved = "Your membership application has been approved",
                greeting = "Dear {name},",
                approvedBodyWithOrg = "Your application for membership of {org} has been approved. Welcome!",
                approvedBody = "Your membership application has been approved. Welcome!",
                approvedLogin = "You can sign in right away.",
                loginLabelApproved = "Sign in now",
                subjectRejected = "Your membership application",
                rejectedBodyWithOrg = "Unfortunately, your application for membership of {org} was not approved.",
                rejectedBody = "Unfortunately, your membership application was not approved.",
                friendKept = "Your access as a friend remains in place.",
                loginLabelFriend = "Sign in",
                contactReplyTo = "If you have questions, simply reply to this e-mail ({address}).",
                contactBoard = "If you have questions, please contact the board.",
            )

        private val ES =
            MembershipDecisionTexts(
                subjectApproved = "Su solicitud de afiliación ha sido aprobada",
                greeting = "Estimado/a {name}:",
                approvedBodyWithOrg = "Su solicitud de afiliación a {org} ha sido aprobada. ¡Bienvenido/a!",
                approvedBody = "Su solicitud de afiliación ha sido aprobada. ¡Bienvenido/a!",
                approvedLogin = "Ya puede iniciar sesión.",
                loginLabelApproved = "Iniciar sesión ahora",
                subjectRejected = "Su solicitud de afiliación",
                rejectedBodyWithOrg = "Lamentablemente, su solicitud de afiliación a {org} no ha sido aprobada.",
                rejectedBody = "Lamentablemente, su solicitud de afiliación no ha sido aprobada.",
                friendKept = "Su acceso como amigo se mantiene.",
                loginLabelFriend = "Iniciar sesión",
                contactReplyTo = "Si tiene preguntas, responda simplemente a este correo ({address}).",
                contactBoard = "Si tiene preguntas, póngase en contacto con la junta directiva.",
            )

        private val FR =
            MembershipDecisionTexts(
                subjectApproved = "Votre demande d'adhésion a été acceptée",
                greeting = "Bonjour {name},",
                approvedBodyWithOrg = "Votre demande d'adhésion à {org} a été acceptée. Bienvenue !",
                approvedBody = "Votre demande d'adhésion a été acceptée. Bienvenue !",
                approvedLogin = "Vous pouvez vous connecter dès maintenant.",
                loginLabelApproved = "Se connecter maintenant",
                subjectRejected = "Votre demande d'adhésion",
                rejectedBodyWithOrg = "Votre demande d'adhésion à {org} n'a malheureusement pas été acceptée.",
                rejectedBody = "Votre demande d'adhésion n'a malheureusement pas été acceptée.",
                friendKept = "Votre accès en tant qu'ami est maintenu.",
                loginLabelFriend = "Se connecter",
                contactReplyTo = "Pour toute question, répondez simplement à ce courriel ({address}).",
                contactBoard = "Pour toute question, veuillez vous adresser au bureau.",
            )

        private val IT =
            MembershipDecisionTexts(
                subjectApproved = "La sua richiesta di adesione è stata accolta",
                greeting = "Gentile {name},",
                approvedBodyWithOrg = "La sua richiesta di adesione a {org} è stata accolta. Benvenuto/a!",
                approvedBody = "La sua richiesta di adesione è stata accolta. Benvenuto/a!",
                approvedLogin = "Può accedere subito.",
                loginLabelApproved = "Accedi ora",
                subjectRejected = "La sua richiesta di adesione",
                rejectedBodyWithOrg = "Purtroppo la sua richiesta di adesione a {org} non è stata accolta.",
                rejectedBody = "Purtroppo la sua richiesta di adesione non è stata accolta.",
                friendKept = "Il suo accesso come amico rimane attivo.",
                loginLabelFriend = "Accedi",
                contactReplyTo = "In caso di domande, risponda semplicemente a questa e-mail ({address}).",
                contactBoard = "In caso di domande, si rivolga al direttivo.",
            )

        private val NL =
            MembershipDecisionTexts(
                subjectApproved = "Uw lidmaatschapsaanvraag is goedgekeurd",
                greeting = "Beste {name},",
                approvedBodyWithOrg = "Uw aanvraag voor het lidmaatschap van {org} is goedgekeurd. Welkom!",
                approvedBody = "Uw lidmaatschapsaanvraag is goedgekeurd. Welkom!",
                approvedLogin = "U kunt nu direct inloggen.",
                loginLabelApproved = "Nu inloggen",
                subjectRejected = "Uw lidmaatschapsaanvraag",
                rejectedBodyWithOrg = "Uw aanvraag voor het lidmaatschap van {org} is helaas niet goedgekeurd.",
                rejectedBody = "Uw lidmaatschapsaanvraag is helaas niet goedgekeurd.",
                friendKept = "Uw toegang als vriend blijft behouden.",
                loginLabelFriend = "Inloggen",
                contactReplyTo = "Heeft u vragen? Antwoord dan gewoon op deze e-mail ({address}).",
                contactBoard = "Heeft u vragen? Neem dan contact op met het bestuur.",
            )

        private val PL =
            MembershipDecisionTexts(
                subjectApproved = "Państwa wniosek o członkostwo został przyjęty",
                greeting = "Dzień dobry, {name},",
                approvedBodyWithOrg = "Państwa wniosek o członkostwo w {org} został przyjęty. Serdecznie witamy!",
                approvedBody = "Państwa wniosek o członkostwo został przyjęty. Serdecznie witamy!",
                approvedLogin = "Mogą się Państwo zalogować od razu.",
                loginLabelApproved = "Zaloguj się teraz",
                subjectRejected = "Państwa wniosek o członkostwo",
                rejectedBodyWithOrg = "Niestety Państwa wniosek o członkostwo w {org} nie został przyjęty.",
                rejectedBody = "Niestety Państwa wniosek o członkostwo nie został przyjęty.",
                friendKept = "Państwa dostęp jako przyjaciela pozostaje w mocy.",
                loginLabelFriend = "Zaloguj się",
                contactReplyTo = "W razie pytań wystarczy odpowiedzieć na tę wiadomość ({address}).",
                contactBoard = "W razie pytań prosimy o kontakt z zarządem.",
            )

        private val RU =
            MembershipDecisionTexts(
                subjectApproved = "Ваша заявка на членство одобрена",
                greeting = "Здравствуйте, {name}!",
                approvedBodyWithOrg = "Ваша заявка на вступление в {org} одобрена. Добро пожаловать!",
                approvedBody = "Ваша заявка на членство одобрена. Добро пожаловать!",
                approvedLogin = "Вы можете войти прямо сейчас.",
                loginLabelApproved = "Войти сейчас",
                subjectRejected = "Ваша заявка на членство",
                rejectedBodyWithOrg = "К сожалению, ваша заявка на вступление в {org} не была одобрена.",
                rejectedBody = "К сожалению, ваша заявка на членство не была одобрена.",
                friendKept = "Ваш доступ в качестве друга сохраняется.",
                loginLabelFriend = "Войти",
                contactReplyTo = "Если у вас есть вопросы, просто ответьте на это письмо ({address}).",
                contactBoard = "Если у вас есть вопросы, обратитесь, пожалуйста, в правление.",
            )

        private val ALL: Map<String, MembershipDecisionTexts> =
            mapOf("de" to DE, "en" to EN, "es" to ES, "fr" to FR, "it" to IT, "nl" to NL, "pl" to PL, "ru" to RU)
    }
}
