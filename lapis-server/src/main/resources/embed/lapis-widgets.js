/*
 * Lapis Cloud Website-Widgets (V1.4.1a). Eingebunden auf einer FREMDEN Origin. IIFE, keine
 * Abhängigkeiten, kein Build-Schritt (siehe EmbedAssets.kt KDoc). Die Origin-Prüfung unten ist
 * reine DX, NIE eine Sicherheitskontrolle -- Server validiert jede Origin erneut. Kein
 * Browser-Speicher und kein Cookie wird auf der fremden Origin verwendet oder gesetzt.
 */
(function () {
  "use strict";

  if (window.__lapisEmbedV1) return;
  window.__lapisEmbedV1 = true;

  // Synchron beim Skript-Start -- document.currentScript ist bei async nur waehrend der
  // Skriptausfuehrung gueltig, nicht mehr im spaeteren Klick-Handler.
  var currentScript = document.currentScript;
  var LAPIS_ORIGIN = currentScript ? new URL(currentScript.src).origin : "";

  var ALLOWED = window.__lapisEmbedAllowedOriginsV1 || [];
  var HERE = window.location.origin;
  if (ALLOWED.indexOf(HERE) === -1) {
    console.error(
      "[lapis-widgets] Diese Origin (" + HERE + ") ist nicht in window.__lapisEmbedAllowedOriginsV1 " +
        "freigeschaltet (aktuell freigeschaltet: " + ALLOWED.length + " Origin(s)). Widgets nicht gerendert."
    );
    return;
  }

  var TEXT = {
    idle: "Anmelden",
    pending: "Anmeldung läuft …",
    refocus: "Fenster in den Vordergrund holen",
    signedInPrefix: "Angemeldet als ",
    memberArea: "Zum Mitgliederbereich →",
    blocked: "Anmeldung nicht möglich (Popup blockiert?)",
    cancelled: "Anmeldung abgebrochen.",
    retry: "Erneut versuchen",
    join: "Mitglied werden",
  };

  var MAX_NAME_LEN = 64;
  var POLL_MS = 500;
  var TIMEOUT_MS = 5 * 60 * 1000;

  function truncate(name) {
    if (!name) return "";
    return name.length > MAX_NAME_LEN ? name.slice(0, MAX_NAME_LEN) + "…" : name;
  }

  function newNonce() {
    if (!window.crypto || !window.crypto.getRandomValues) return null;
    var bytes = new Uint8Array(16), hex = "";
    window.crypto.getRandomValues(bytes);
    for (var i = 0; i < bytes.length; i++) hex += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
    return hex;
  }

  var CSS =
    ":host{display:inline-block;font-family:var(--lapis-embed-font,inherit);min-height:44px}" +
    ".b{display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:0 1.1rem;" +
    "border-radius:var(--lapis-embed-radius,6px);border:1px solid transparent;font:inherit;" +
    "font-weight:600;cursor:pointer;text-decoration:none}" +
    ".f{background:var(--lapis-embed-accent,#1E56C8);color:var(--lapis-embed-accent-contrast,#FFFFFF)}" +
    ".o{background:transparent;color:inherit;border-color:currentColor}" +
    ".s{display:block;margin-top:.4rem;font-size:.85em}" +
    ".l{display:inline-block;margin-top:.4rem;font-size:.9em}";

  function mount(host) {
    var wasEmpty = host.childNodes.length === 0;
    var root = host.attachShadow({ mode: "closed" });
    var style = document.createElement("style");
    style.textContent = CSS;
    root.appendChild(style);
    if (wasEmpty) host.style.minHeight = "44px";
    return root;
  }

  function hydrateLogin(host) {
    var root = mount(host);
    var button = document.createElement("button");
    button.type = "button";
    button.className = "b f";
    button.textContent = TEXT.idle;
    root.appendChild(button);

    var status = document.createElement("span");
    status.className = "s";
    status.setAttribute("aria-live", "polite");
    root.appendChild(status);

    var memberLink = document.createElement("a");
    memberLink.className = "l";
    memberLink.target = "_blank";
    memberLink.rel = "noopener noreferrer";
    memberLink.hidden = true;
    root.appendChild(memberLink);

    var popupHandle = null, pollTimer = null, timeoutTimer = null, messageListener = null, currentNonce = null;

    function clearTimers() {
      if (pollTimer) { window.clearInterval(pollTimer); pollTimer = null; }
      if (timeoutTimer) { window.clearTimeout(timeoutTimer); timeoutTimer = null; }
      if (messageListener) { window.removeEventListener("message", messageListener); messageListener = null; }
    }

    function setState(state, payload) {
      if (state === "idle") {
        button.textContent = TEXT.idle; button.hidden = false; status.textContent = ""; memberLink.hidden = true;
      } else if (state === "pending") {
        button.textContent = TEXT.refocus; button.hidden = false; status.textContent = TEXT.pending;
      } else if (state === "signed-in") {
        button.hidden = true;
        status.textContent = TEXT.signedInPrefix + truncate(payload && payload.displayName);
        memberLink.textContent = TEXT.memberArea;
        // Welle V1.4.6: "/app" prefix -- the member SPA no longer lives at "/".
        memberLink.href = LAPIS_ORIGIN + "/app#/dashboard";
        memberLink.hidden = false;
        memberLink.focus();
      } else if (state === "blocked") {
        button.textContent = TEXT.retry; button.hidden = false; status.textContent = TEXT.blocked;
      } else if (state === "cancelled") {
        button.textContent = TEXT.retry; button.hidden = false; status.textContent = TEXT.cancelled;
      }
    }

    setState("idle");

    function onMessage(e) {
      if (
        e.origin === LAPIS_ORIGIN && e.source === popupHandle && e.data &&
        e.data.source === "lapis-embed" && e.data.state === currentNonce
      ) {
        clearTimers();
        setState(e.data.ok ? "signed-in" : "cancelled", { displayName: e.data.displayName });
      }
    }

    button.addEventListener("click", function () {
      if (popupHandle && !popupHandle.closed) { popupHandle.focus(); return; }
      var nonce = newNonce();
      if (!nonce) { setState("blocked"); return; }
      currentNonce = nonce;
      var w = 420, h = 640;
      var left = window.screenX + (window.outerWidth - w) / 2;
      var top = window.screenY + (window.outerHeight - h) / 2;
      var features =
        "width=" + w + ",height=" + h + ",left=" + left + ",top=" + top +
        ",menubar=no,toolbar=no,location=yes,status=no,resizable=yes,scrollbars=yes";
      var url = LAPIS_ORIGIN + "/embed/v1/login?state=" + nonce + "&origin=" + encodeURIComponent(HERE);
      // Synchron im Klick-Handler (kein await davor), noopener bewusst NICHT gesetzt (postMessage).
      popupHandle = window.open(url, "lapisEmbedLogin", features);
      if (!popupHandle) { setState("blocked"); return; }
      setState("pending");
      messageListener = onMessage;
      window.addEventListener("message", messageListener);
      pollTimer = window.setInterval(function () {
        if (popupHandle && popupHandle.closed) { clearTimers(); setState("cancelled"); }
      }, POLL_MS);
      timeoutTimer = window.setTimeout(function () { clearTimers(); setState("cancelled"); }, TIMEOUT_MS);
    });
  }

  function hydrateJoin(host) {
    var root = mount(host);
    var link = document.createElement("a");
    link.className = "b o";
    // Welle V1.4.6: "/app" prefix -- the member SPA no longer lives at "/".
    link.href = LAPIS_ORIGIN + "/app#/register";
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = TEXT.join;
    root.appendChild(link);
  }

  // ── Spenden-Widget (V1.4.1b) ─────────────────────────────────────────────────────────
  var DT = {
    other: "Anderer Betrag", submit: "Jetzt spenden", pending: "Bitte warten …",
    fb: "Andere Wege",
    eGeneric: "Bitte erneut versuchen.", retryIn: "Erneut in %s Sek."
  };
  var DCSS =
    ".amt{display:flex;flex-wrap:wrap;gap:.6rem;margin:0 0 .5rem}.amt label,.oth{min-height:44px}" +
    ".hp{position:absolute;left:-9999px;width:1px;height:1px;overflow:hidden}" +
    ".hint{display:block;margin:.3rem 0;font-size:.85em}.err{color:#B00020}";

  function el(tag, props) {
    var e = document.createElement(tag), k;
    for (k in props) { if (k.indexOf("-") > -1) e.setAttribute(k, props[k]); else e[k] = props[k]; }
    return e;
  }

  function hydrateDonate(host) {
    var root = mount(host);
    root.querySelector("style").textContent += DCSS;

    // Fix (Review MINOR, V1.4.1b): server-baked effective range from FIRST paint (see
    // EmbedAssets.widgetJs KDoc), not only after a guaranteed-to-fail submit.
    var RANGE = window.__lapisEmbedDonationRangeV1 || null;
    var minA = RANGE ? parseFloat(RANGE.min) : 5, maxA = RANGE ? parseFloat(RANGE.max) : 500;
    var rangeText = (RANGE ? RANGE.min + "–" + RANGE.max : "5–500") + " €";
    var hintText = rangeText + ", anonym via Stripe. Ihre Daten erreichen uns nie.";
    var eRangeText = rangeText + " wählen.";

    var amounts = (host.getAttribute("data-lapis-amounts") || "10,25,50,100").split(",")
      .map(function (s) { return parseFloat(s); }).filter(function (n) { return !isNaN(n) && n > 0 && n <= maxA; });
    // Fix (Round 3): defaults first, minA if still empty.
    if (amounts.length === 0) amounts = [10, 25, 50, 100].filter(function (n) { return n <= maxA; });
    if (amounts.length === 0) amounts = [minA];
    var fbUrl = host.getAttribute("data-lapis-fallback-url") || "";
    var name = "a" + Math.floor(Date.now() % 100000);

    var amt = el("div", { className: "amt" });
    amounts.concat(["other"]).forEach(function (a, i) {
      var v = String(a), label = el("label", {});
      label.appendChild(el("input", { type: "radio", name: name, value: v, checked: i === 0 }));
      label.appendChild(el("span", { textContent: v === "other" ? DT.other : v + " €" }));
      amt.appendChild(label);
    });
    root.appendChild(amt);

    var oI = el("input", { type: "text", inputMode: "decimal", className: "oth", hidden: true, "aria-label": DT.other });
    root.appendChild(oI);
    amt.addEventListener("change", function (e) {
      oI.hidden = e.target.value !== "other";
      if (!oI.hidden) oI.focus();
    });

    var hp = el("input", { type: "text", name: "kommentar", className: "hp", autocomplete: "off", tabIndex: -1, "aria-hidden": "true" });
    root.appendChild(hp);

    var button = el("button", { type: "button", className: "b f", textContent: DT.submit });
    root.appendChild(button);
    var status = el("span", { className: "s", "aria-live": "polite" });
    root.appendChild(status);
    root.appendChild(el("span", { className: "hint", textContent: hintText }));
    var fbA = el("a", { className: "l", textContent: DT.fb, hidden: true });
    if (fbUrl) fbA.href = fbUrl;
    root.appendChild(fbA);

    var DMSG = {
      503: "Derzeit nicht verfügbar.", 409: "Spende nicht möglich.", 502: "Zahlungsanbieter nicht erreichbar."
    };

    function showError(message, showFallback) {
      button.disabled = false;
      status.className = "s err";
      status.textContent = message;
      fbA.hidden = !(showFallback && fbUrl);
    }

    // 400: server may echo the real, possibly-lowered range (AMOUNT_OUT_OF_RANGE) -- use it.
    // showFallback=true (Fix Review TRIVIAL regression) -- same as every other rejection below.
    function showBadRequest(b) {
      showError(b && b.error === "AMOUNT_OUT_OF_RANGE" && b.minAmount && b.maxAmount ?
        b.minAmount + "–" + b.maxAmount + " € wählen." : eRangeText, true);
    }

    button.addEventListener("click", function () {
      var checked = amt.querySelector("input:checked");
      var amount = checked && (checked.value === "other" ? oI.value.trim().replace(",", ".") : checked.value);
      if (!amount) { showError(eRangeText, false); return; }
      button.disabled = true;
      status.className = "s";
      status.textContent = DT.pending;
      fbA.hidden = true;
      fetch(LAPIS_ORIGIN + "/api/embed/v1/donation/checkout", {
        method: "POST", credentials: "omit", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ amount: amount, kommentar: hp.value })
      }).then(function (res) {
        if (res.status === 200) return res.json().then(function (d) { window.location.assign(d.redirectUrl); });
        if (res.status === 429) {
          var ra = res.headers.get("Retry-After");
          showError(ra ? DT.retryIn.replace("%s", ra) : DT.eGeneric, true);
        } else if (res.status === 400) {
          res.json().then(showBadRequest).catch(function () { showBadRequest(null); });
        } else {
          showError(DMSG[res.status] || DT.eGeneric, res.status !== 409);
        }
      }).catch(function () { showError(DT.eGeneric, true); });
    });
  }

  // ── Anmeldungs-Widget (V1.4.3.3) ─────────────────────────────────────────────────────
  var EVT = {
    name: "Name", email: "E-Mail", submit: "Anmelden", pending: "Bitte warten …", fb: "Zur Anmeldungsseite",
    accepted: "Danke. Wir haben Ihnen eine E-Mail geschickt. Falls Sie bereits Mitglied sind, finden Sie darin den Hinweis, wie Sie sich im Mitgliederbereich anmelden.",
    eGeneric: "Bitte erneut versuchen.", retryIn: "Erneut in %s Sek.",
    eName: "Bitte einen Namen eingeben.", eEmail: "Bitte eine gültige E-Mail-Adresse eingeben.",
    eNotAvailable: "Diese Veranstaltung ist derzeit nicht für Anmeldungen geöffnet."
  };
  var ECSS = ".hp{position:absolute;left:-9999px;width:1px;height:1px;overflow:hidden}" +
    ".fld{display:block;margin:.35rem 0;min-height:44px}.err{color:#B00020}";

  // Slug zuerst, mount() danach -- mount() haengt eine CLOSED Shadow Root an und verdeckt damit
  // den No-JS-Fallback-Link; ohne Slug darf mount() nie laufen (Falle F-5).
  function hydrateEvent(host) {
    var slug = host.getAttribute("data-lapis-event-slug");
    if (!slug || slug === "ihre-veranstaltung") {
      console.error("[lapis-widgets] data-lapis-event-slug fehlt -- Anmeldungs-Widget nicht gerendert.");
      return;
    }
    var root = mount(host);
    root.querySelector("style").textContent += ECSS;
    // Abgeleitet aus dem Slug, kein eigenes Fallback-URL-Attribut auf diesem Host (Falle F-6).
    var fbUrl = LAPIS_ORIGIN + "/veranstaltung/" + encodeURIComponent(slug);

    var form = document.createElement("form");
    var nI = el("input", { type: "text", required: true, autocomplete: "name" });
    nI.maxLength = 300;
    var nL = el("label", { className: "fld", textContent: EVT.name });
    nL.appendChild(nI);
    form.appendChild(nL);
    var eI = el("input", { type: "email", required: true, autocomplete: "email" });
    eI.maxLength = 320;
    var eL = el("label", { className: "fld", textContent: EVT.email });
    eL.appendChild(eI);
    form.appendChild(eL);
    var hp = el("input", { type: "text", name: "kommentar", className: "hp", autocomplete: "off", tabIndex: -1, "aria-hidden": "true" });
    form.appendChild(hp);
    var button = el("button", { type: "submit", className: "b f", textContent: EVT.submit });
    form.appendChild(button);
    var status = el("span", { className: "s", "aria-live": "polite" });
    form.appendChild(status);
    var fbA = el("a", { className: "l", textContent: EVT.fb, href: fbUrl, hidden: true });
    form.appendChild(fbA);
    root.appendChild(form);

    var EMSG = {
      404: EVT.eNotAvailable, 409: "Anmeldung derzeit nicht möglich.", 503: "Derzeit nicht verfügbar.",
      502: "Zahlungsanbieter nicht erreichbar.", 400: "Bitte Eingaben prüfen."
    };

    function showError(message) {
      button.disabled = false; nI.disabled = false; eI.disabled = false;
      status.className = "s err"; status.textContent = message; fbA.hidden = false;
    }

    function done() { root.replaceChild(el("p", { textContent: EVT.accepted, "aria-live": "polite" }), form); }

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var name = nI.value.trim(), email = eI.value.trim();
      if (!name) { showError(EVT.eName); return; }
      if (email.indexOf("@") <= 0) { showError(EVT.eEmail); return; }
      button.disabled = true; nI.disabled = true; eI.disabled = true;
      status.className = "s"; status.textContent = EVT.pending; fbA.hidden = true;
      fetch(LAPIS_ORIGIN + "/api/embed/v1/event/" + encodeURIComponent(slug) + "/registration", {
        method: "POST", credentials: "omit", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ guestName: name, guestEmail: email, kommentar: hp.value })
      }).then(function (res) {
        if (res.status === 200 || res.status === 429) {
          return res.json().then(function (d) {
            if (d.outcome === "ACCEPTED") { done(); return; }
            if (d.outcome === "RATE_LIMITED") { showError(d.retryAfterSeconds ? EVT.retryIn.replace("%s", d.retryAfterSeconds) : EVT.eGeneric); return; }
            showError(EVT.eGeneric);
          }).catch(function () { showError(EVT.eGeneric); });
        }
        showError(EMSG[res.status] || EVT.eGeneric);
      }).catch(function () { showError(EVT.eGeneric); });
    });
  }

  // ── Veranstaltungsliste (V1.9.248) ───────────────────────────────────────────────────
  // Erst nach gueltiger Antwort mounten (F-5). Feed-Daten (auch fremde) nur als textContent / geprueftes href.
  var EVS = {
    empty: "Zurzeit sind keine Veranstaltungen geplant.", label: "Nächste Veranstaltungen",
    more: "Alle Veranstaltungen", online: "Online", series: "Reihe: ", partner: "Partnerserver: ",
    ext: " (externe Seite)", uhr: " Uhr"
  };
  var VCSS =
    ":host{display:block}ul{list-style:none;margin:0;padding:0}" +
    "li{min-height:44px;border-bottom:1px solid var(--lapis-embed-border,#D9DDE3)}" +
    "a{color:var(--lapis-embed-accent,#1E56C8)}li>a{display:block;padding:.5rem 0}" +
    "a:focus-visible{outline:2px solid var(--lapis-embed-accent,#1E56C8);outline-offset:2px;" +
    "border-radius:var(--lapis-embed-radius,6px)}" +
    "time,.m{display:block;color:var(--lapis-embed-muted,#5B6472);font-size:.875em}" +
    ".w{color:var(--lapis-embed-text,inherit)}" +
    ".vh{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}";

  function cut(s, n) {
    if (typeof s !== "string") return "";
    return s.length > n ? s.slice(0, n) + "…" : s;
  }

  function evInt(raw) {
    if (!raw || !/^[0-9]+$/.test(raw)) return 5;
    var n = parseInt(raw, 10);
    return n < 1 ? 5 : n > 20 ? 20 : n;
  }

  function evZone(z) {
    try { new Intl.DateTimeFormat("de-DE", { timeZone: z }); return z; } catch (e) { return "Europe/Berlin"; }
  }

  function evWhen(iso, zone, showZone) {
    var o = { weekday: "short", day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: zone };
    if (showZone) o.timeZoneName = "short";
    try {
      return new Intl.DateTimeFormat("de-DE", o).format(new Date(iso)) + (showZone ? "" : EVS.uhr);
    } catch (e) { return iso; }
  }

  // Fremder Link: https, keine Zugangsdaten, Port 443, Host == Ursprungs-Host.
  function evPartnerUrl(raw, originHost) {
    if (typeof raw !== "string" || typeof originHost !== "string") return null;
    try {
      var u = new URL(raw);
      if (u.protocol !== "https:" || u.username || u.password) return null;
      if (u.port !== "" && u.port !== "443") return null;
      return u.hostname.toLowerCase() === originHost.toLowerCase() ? u.href : null;
    } catch (e) { return null; }
  }

  function evRender(root, d, host, zone, all) {
    var items = [], prefix = LAPIS_ORIGIN + "/veranstaltung/";
    [d.events, Array.isArray(d.partnerEvents) ? d.partnerEvents : []].forEach(function (list, k) {
      list.forEach(function (e, i) {
        if (!e) return;
        var t = typeof e.startsAt === "string" ? Date.parse(e.startsAt) : NaN;
        var href = k ? evPartnerUrl(e.url, e.originHost) :
          (typeof e.registrationUrl === "string" && e.registrationUrl.indexOf(prefix) === 0 ? e.registrationUrl : null);
        if (isNaN(t) || !href || typeof e.title !== "string" || !e.title) return;
        items.push({ t: t, k: k, i: i, s: e.startsAt, z: k ? evZone(e.timezone) : zone, title: cut(e.title, 200), href: href,
          loc: cut(e.locationText, 120) || (e.online === true ? EVS.online : ""),
          series: cut(e.seriesText, 120), name: cut(e.originName, 80) });
      });
    });
    items.sort(function (a, b) { return a.t - b.t || a.k - b.k || a.i - b.i; });
    items = items.slice(0, evInt(host.getAttribute("data-limit")));

    var more = el("a", {
      href: LAPIS_ORIGIN + "/veranstaltungen" + (all ? "" : "?quelle=eigene"),
      textContent: host.getAttribute("data-more-text") || EVS.more + " →"
    });
    if (!items.length) {
      root.appendChild(el("p", { className: "w", textContent: host.getAttribute("data-empty-text") || EVS.empty }));
      root.appendChild(more);
      return;
    }
    var ul = el("ul", { "aria-label": host.getAttribute("data-label") || EVS.label });
    items.forEach(function (it) {
      var li = el("li", {});
      var shown = it.k === 1 && it.z !== zone;
      li.appendChild(el("time", { dateTime: it.s, textContent: evWhen(it.s, it.z, shown) }));
      var a = el("a", { href: it.href, textContent: it.title });
      if (it.k === 1) {
        a.rel = "noopener noreferrer external";
        a.referrerPolicy = "no-referrer";
        a.hreflang = "de";
        a.appendChild(el("span", { "aria-hidden": "true", textContent: " ↗" }));
        a.appendChild(el("span", { className: "vh", textContent: EVS.ext }));
      }
      li.appendChild(a);
      [it.loc, it.series && EVS.series + it.series, it.name && EVS.partner + it.name].forEach(function (m) {
        if (m) li.appendChild(el("span", { className: "m", textContent: m }));
      });
      ul.appendChild(li);
    });
    root.appendChild(ul);
    root.appendChild(more);
  }

  function hydrateEvents(host) {
    if (!LAPIS_ORIGIN) return;
    var src = host.getAttribute("data-source");
    var all = src === "all" || src === "alle";
    var zone = evZone(host.getAttribute("data-time-zone") || "Europe/Berlin");
    var ctl = window.AbortController ? new AbortController() : null;
    var done = false;
    var timer = window.setTimeout(function () { done = true; if (ctl) ctl.abort(); }, 5000);
    fetch(LAPIS_ORIGIN + "/api/embed/v1/events" + (all ? "?quelle=alle" : ""), {
      credentials: "omit", signal: ctl ? ctl.signal : undefined
    }).then(function (res) {
      if (done || res.status !== 200) return null;
      return res.json();
    }).then(function (d) {
      window.clearTimeout(timer);
      if (done || !d || !Array.isArray(d.events)) return;
      done = true;
      var root = mount(host);
      root.querySelector("style").textContent += VCSS;
      evRender(root, d, host, zone, all);
    }).catch(function () { window.clearTimeout(timer); });
  }

  function scan() {
    var hosts = document.querySelectorAll("[data-lapis-widget]");
    for (var i = 0; i < hosts.length; i++) {
      var host = hosts[i];
      var kind = host.getAttribute("data-lapis-widget");
      if (kind === "login") hydrateLogin(host);
      else if (kind === "join") hydrateJoin(host);
      else if (kind === "donate") hydrateDonate(host);
      else if (kind === "event") hydrateEvent(host);
      else if (kind === "events") hydrateEvents(host);
    }
  }

  // Das offizielle Snippet platziert <script async> VOR den data-lapis-widget-Elementen
  // (siehe embed-widgets.adoc). Bei async-Skripten ist der Ausfuehrungszeitpunkt relativ zum
  // HTML-Parser nicht deterministisch (Netz-Timing, HTTP-Cache) -- ohne diesen Guard liefert
  // querySelectorAll() bei einem bereits gecachten Skript oft 0 Treffer, weil der Parser noch
  // oberhalb der Widget-<div>s steht.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", scan, { once: true });
  } else {
    scan();
  }
})();
