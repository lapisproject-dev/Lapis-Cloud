package network.lapis.cloud.server.share

/** Path under which [SHARE_SCRIPT] is served (V1.9.217); registered in `registerSocialPublicRoutes`. */
internal const val SHARE_SCRIPT_PATH = "/s/assets/share.js"

/**
 * The share bar's progressive enhancement: reveals the system share dialog where the browser supports it and copies the link
 * to the clipboard. ES5, no network, no storage, no HTML injection (only `textContent`). Without JavaScript every link in the
 * bar still works as a plain link.
 */
internal const val SHARE_SCRIPT: String = """(function () {
  var s = document.querySelector("section.share[data-share-url]");
  if (!s) return;
  var url = s.getAttribute("data-share-url") || "";
  if (url.indexOf(location.origin + "/") !== 0) return;
  var data = { title: s.getAttribute("data-share-title") || "", text: s.getAttribute("data-share-text") || "", url: url };
  var status = s.querySelector(".share-status"), timer = 0;
  function say(msg) {
    if (!status) return;
    status.textContent = msg;
    clearTimeout(timer);
    timer = setTimeout(function () { status.textContent = ""; }, 2500);
  }
  var item = s.querySelector(".share-item--native");
  if (item && typeof navigator.share === "function" &&
      (typeof navigator.canShare !== "function" || navigator.canShare({ url: url }))) {
    item.hidden = false;
    item.querySelector("button").addEventListener("click", function () {
      navigator.share(data).catch(function () {});
    });
  }
  var copy = s.querySelector("[data-share-copy]");
  if (copy && navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
    copy.addEventListener("click", function (e) {
      e.preventDefault();
      navigator.clipboard.writeText(url).then(
        function () { say(s.getAttribute("data-share-copied") || ""); },
        function () { say(s.getAttribute("data-share-copy-failed") || ""); });
    });
  }
})();
"""
