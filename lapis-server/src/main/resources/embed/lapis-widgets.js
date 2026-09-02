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
        memberLink.href = LAPIS_ORIGIN + "/#/dashboard";
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
    link.href = LAPIS_ORIGIN + "/#/register";
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = TEXT.join;
    root.appendChild(link);
  }

  function scan() {
    var hosts = document.querySelectorAll("[data-lapis-widget]");
    for (var i = 0; i < hosts.length; i++) {
      var host = hosts[i];
      var kind = host.getAttribute("data-lapis-widget");
      if (kind === "login") hydrateLogin(host);
      else if (kind === "join") hydrateJoin(host);
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
