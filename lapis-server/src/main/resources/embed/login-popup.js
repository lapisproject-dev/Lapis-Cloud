/*
 * Lapis Cloud -- Popup-Login-Skript (Welle V1.4.1a "Öffentliche Website-Integration").
 * Ausgeliefert unter /embed/v1/login.js, geladen NUR von /embed/v1/login (same-origin). Eine
 * KONSTANTE Datei -- kein Wert wird je hineininterpoliert; state/targetOrigin reisen ausschließlich
 * über `data-*`-Attribute (siehe EmbedHtml.kt KDoc "Sicherheits-Vertrag"). Dadurch genügt eine
 * `script-src 'self'`-CSP ohne Nonce. Der Sign-in-Status ist beim Rendern dieser Seite strukturell
 * nie bekannt (SameSite=Strict, siehe EmbedHtml.kt KDoc) -- dieses Skript bestimmt ihn selbst über
 * die same-origin `/api/embed/v1/session`-Probe weiter unten, statt ihn vom Server entgegenzunehmen.
 */
(function () {
  "use strict";

  var dataEl = document.getElementById("lapis-embed-data");
  var state = dataEl.getAttribute("data-state");
  var targetOrigin = dataEl.getAttribute("data-target-origin");

  var statusEl = document.getElementById("lapis-status");
  var errorEl = document.getElementById("lapis-error");
  var form = document.getElementById("lapis-login-form");

  function closeSoon() {
    window.setTimeout(function () {
      try {
        window.close();
      } catch (e) {
        // ignoriert -- der Fallback-Hinweistext im DOM bleibt stehen.
      }
      if (!window.closed) {
        statusEl.textContent += " Sie können dieses Fenster schließen.";
      }
    }, 800);
  }

  function sendResult(ok, name) {
    // targetOrigin ist NIE "*" -- kommt aus dem kanonischen Allowlist-Eintrag (server-berechnet).
    if (window.opener && targetOrigin) {
      window.opener.postMessage(
        { source: "lapis-embed", v: 1, type: "login", state: state, ok: ok, displayName: name || "" },
        targetOrigin
      );
    }
  }

  function showSignedIn(name) {
    sendResult(true, name);
    statusEl.textContent = "Angemeldet. Fenster wird geschlossen.";
    if (form) form.hidden = true;
    closeSoon();
  }

  function wireForm() {
    if (!form) return;
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      errorEl.textContent = "";
      var email = document.getElementById("lapis-email").value;
      var password = document.getElementById("lapis-password").value;
      fetch("/api/auth/login", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email, password: password }),
      })
        .then(function (response) {
          if (response.status === 200) {
            return response.json().then(function (body) {
              showSignedIn(body.displayName);
            });
          }
          // Generische Meldung -- kein Unterschied zwischen 401/429/etc., keine
          // Existenz-Auskunft über E-Mail-Adressen. KEIN postMessage.
          errorEl.textContent = "Anmeldung fehlgeschlagen. Bitte E-Mail und Passwort prüfen.";
        })
        .catch(function () {
          errorEl.textContent = "Anmeldung fehlgeschlagen. Bitte E-Mail und Passwort prüfen.";
        });
    });
  }

  // GET /embed/v1/login (die Seite, die dieses Skript lädt) ist eine cross-site Top-Level-
  // Navigation von der Partner-Origin aus -- das lapis_session-Cookie ist SameSite=Strict und wird
  // dabei serverseitig NIE mitgeschickt (siehe EmbedRoutes.kt KDoc auf dieser Route), weshalb der
  // Server hier keinen Sign-in-Status übergeben kann. EIN fetch, den DIESES bereits geladene
  // Dokument an SEINE EIGENE Origin richtet, ist dagegen ein reiner Same-Site-Request -- SameSite=Strict
  // schränkt nur cross-site Requests ein, nicht diesen. `/api/embed/v1/session` (same-origin,
  // credentials:'same-origin', kein Origin-Header bei einem echten Same-Origin-fetch) beantwortet
  // deshalb ehrlich, ob bereits eine Sitzung besteht -- ohne dass die Partner-Origin je etwas davon
  // mitbekommt (kein neuer Cross-Site-Tracking-Kanal, siehe docs/api/embed-widgets.adoc "Known,
  // deliberate limitation"). Das Formular bleibt bis zur Antwort verborgen, damit ein bereits
  // angemeldetes Mitglied nicht kurz ein Passwortfeld sieht, das gleich wieder verschwindet -- ein
  // Statustext während der Wartezeit UND ein Timeout stellen sicher, dass ein hängender/sehr
  // langsamer fetch (Funkloch, hängender Proxy) das Popup nicht dauerhaft leer und ohne jede
  // Rückmeldung stehen lässt (Review-Fund V1.4.1a: ohne Timeout hatte ein Mitglied in diesem
  // Zustand weder Formular noch Statustext noch Fehlermeldung -- nur eine stillstehende Seite).
  if (form) form.hidden = true;
  statusEl.textContent = "Anmeldung wird geprüft …";

  var probeSettled = false;
  function fallBackToForm() {
    if (probeSettled) return;
    probeSettled = true;
    window.clearTimeout(probeTimeoutHandle);
    statusEl.textContent = "";
    if (form) form.hidden = false;
    wireForm();
  }
  // SESSION_PROBE_TIMEOUT_MS: großzügig genug für einen normalen Same-Origin-Roundtrip, kurz genug
  // dass ein Mitglied nicht minutenlang auf einen hängenden fetch starrt.
  var SESSION_PROBE_TIMEOUT_MS = 3000;
  var probeTimeoutHandle = window.setTimeout(fallBackToForm, SESSION_PROBE_TIMEOUT_MS);

  fetch("/api/embed/v1/session", { method: "GET", credentials: "same-origin" })
    .then(function (response) {
      if (!response.ok) throw new Error("session probe failed");
      return response.json();
    })
    .then(function (body) {
      if (probeSettled) return; // Timeout ist bereits gefallen -- Formular wird schon angezeigt.
      probeSettled = true;
      window.clearTimeout(probeTimeoutHandle);
      if (body && body.signedIn) {
        showSignedIn(body.displayName);
      } else {
        statusEl.textContent = "";
        if (form) form.hidden = false;
        wireForm();
      }
    })
    .catch(fallBackToForm);
})();
