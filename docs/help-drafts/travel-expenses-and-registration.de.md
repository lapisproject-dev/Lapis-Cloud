<!-- Superseded by the website help page src/content/help/travel-expenses.md (cloud.lapisproject.dev); keep in sync there. -->
<!--
Arbeitsentwurf für die Hilfeseite der Webseite (Release-Ziel: Website-Release nach Lapis Cloud V1.9.122).
Bewusst Markdown, auf ausdrücklichen Auftrag: Das ist ein Text zum Weiterreichen an die Website-Redaktion
und gehört NICHT zur versionierten Projektdokumentation (die ist Asciidoctor, siehe docs/features/travel-expenses.adoc).
Die Kontaktdaten sind Platzhalter -- niemals echte Werte eintragen; die echten Angaben pflegt die Verwaltung der
jeweiligen Instanz unter /travel-expense-settings, sie erscheinen dort für alle Mitglieder.
Jede Aussage zur Anmeldung ist am Code belegt (siehe Quellenangaben am Ende); wo es eine Lücke gibt, steht sie ehrlich da.
-->

# Reisekosten abrechnen -- Schritt für Schritt

Diese Seite erklärt, wie Sie sich anmelden und eine Reise in Lapis Cloud abrechnen. Sie brauchen dafür keine Vorkenntnisse.

## 1. Anmelden

### Sind Sie schon Mitglied mit Zugang?

Dann melden Sie sich mit Ihrer E-Mail-Adresse und Ihrem Passwort an (Seite „Anmelden"). Haben Sie Ihr Passwort vergessen, wählen Sie
unter dem Anmeldefeld „Passwort vergessen?", geben Ihre E-Mail-Adresse ein und fordern das Zurücksetzen an. Die E-Mail kann einige Minuten brauchen.
Mit dem Link aus der E-Mail vergeben Sie ein neues Passwort und melden sich danach neu an.

### Noch kein Zugang? Mitglied werden

1. Öffnen Sie die Seite „Mitglied werden" (auf der Anmeldeseite: „Noch kein Konto? Jetzt Mitglied werden.").
2. Tragen Sie Name, E-Mail-Adresse und ein Passwort ein, wählen Sie auf Wunsch Ihren Landesverband und bestätigen Sie, dass Sie den
   Beitrittsvertrag gelesen haben und akzeptieren. Klicken Sie auf „Antrag einreichen".
3. Der Vorstand prüft Ihren Antrag. Bis dahin sind Sie **noch nicht angemeldet**. Der Vorstand entscheidet ausdrücklich -- es gibt keine automatische Freigabe.
4. Nach der Freigabe melden Sie sich mit der E-Mail-Adresse und dem Passwort an, die Sie bei der Antragstellung gewählt haben.
   Der Vorstand kann Ihnen die Entscheidung per kurzer E-Mail mitteilen; **verlassen Sie sich nicht darauf**, dass diese E-Mail kommt -- wenn Sie nach einigen
   Tagen nichts hören, probieren Sie die Anmeldung oder fragen Sie beim Vorstand nach.

> Hinweis: Es gibt außerdem ein „Freund-Konto" (nur für die Teilnahme an Videokonferenzen). Es entsteht ohne Prüfung durch den Vorstand, ist aber kein
> Mitgliedskonto: Die Reisekosten-Seite erscheint dort nicht im Menü.

## 2. Reisekosten abrechnen

Öffnen Sie im Menü **Reisekosten**. Oben sehen Sie Ihren Ansprechpartner für Rückfragen.

1. **Neuen Antrag anlegen.** Tragen Sie Zweck, Beginn und Ende der Reise ein (höchstens 31 Tage). Ihre Mitgliedsnummer wird automatisch angezeigt.
2. **Reisetage ausfüllen.** Für jeden Tag der Reise gibt es einen Block. Tragen Sie ein, wann Sie abgefahren sind bzw. zurückgekehrt sind (am ersten Tag nur die Abfahrt,
   am letzten Tag nur die Rückkehr, an Tagen dazwischen gar nichts -- sie zählen 24 Stunden). Kreuzen Sie an, welche Mahlzeiten Ihnen gestellt wurden; das mindert das
   Tagegeld. Wenn Ihr Hotel das Frühstück enthält, ist es am Folgetag bereits angekreuzt und gesperrt. Mit **Tage speichern** übernehmen Sie die Angaben.
   Die Summen, die Sie dabei sehen, sind eine **Vorschau** -- verbindlich ist, was nach dem Speichern dasteht.
3. **Weitere Kosten.** Fahrten mit dem eigenen Auto (ganze Kilometer, Mitfahrer erhöhen den Satz), öffentliche Verkehrsmittel, Übernachtung (mit Hotel, erster Nacht, Anzahl der
   Nächte, ggf. „mit Frühstück"), Bewirtung und Sonstiges. Für alle diese Kosten außer der eigenen Autofahrt laden Sie einen **Beleg** hoch (PDF, JPG oder PNG, bis 10 MB). Bei einer Autofahrt
   können Sie freiwillig einen Kartennachweis hochladen.
4. **Vorschuss und Auszahlung.** Tragen Sie einen bereits erhaltenen Vorschuss ein (sonst leer lassen). Wählen Sie dann bewusst: **Auszahlen** oder **Spendenquittung
   (Aufwandsverzicht)**. Bei der Spendenquittung verzichten Sie auf die Erstattung; die Zuwendungsbestätigung wird **außerhalb von Lapis** ausgestellt. Speichern Sie die Auswahl.
5. **Einreichen.** „Zur Freigabe einreichen" wird freigeschaltet, sobald alles vollständig ist -- der Grund, falls nicht, steht direkt über dem Knopf.
   Reichen Sie Ihre Reise bitte bis **zum 31. Dezember des Reisejahres** ein; nach diesem Datum erscheint nur ein Hinweis, Sie können trotzdem einreichen.
6. **Status.** Unter „Meine Anträge" sehen Sie, ob Ihr Antrag eingereicht, abgelehnt oder „zur Auszahlung gebucht" ist. „Zur Auszahlung gebucht" heißt: Die Kasse hat ihn verbucht -- die
   Überweisung veranlasst sie separat. Ein abgelehnter Antrag lässt sich als Entwurf kopieren (Belege müssen Sie erneut hochladen).

Ein Vorschuss, der höher ist als die Abrechnung, wird nicht unterstützt -- melden Sie sich in diesem Fall bei Ihrem Ansprechpartner.

## 3. Alternative

Sie können Ihre Reisekosten weiterhin mit der alten Excel-Vorlage abrechnen oder eine E-Mail an den Finanzvorstand schreiben. Lapis Cloud ist der bevorzugte Weg.

## 4. Fragen? Ihr Ansprechpartner

- Name: {{KONTAKT_NAME}}
- E-Mail: {{KONTAKT_EMAIL}}
- Telefon: {{KONTAKT_TELEFON}}

---

*Quellen im Code (für die Redaktion, nicht zur Veröffentlichung):* Beitrittsformular `RegistrationScreen.kt`/`IRegistrationService.registerApplication`,
Prüfung durch den Vorstand `approveApplication` (optionale Mitteilung per E-Mail seit V1.9.118), Anmeldung `LoginScreen.kt` („Passwort vergessen?", Zurücksetzen per E-Mail-Link),
Freund-Konto `FriendRegistrationScreen.kt`/`registerFriend`, Reisekosten `TravelExpenseScreen.kt`.
