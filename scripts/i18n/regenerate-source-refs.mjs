#!/usr/bin/env node
/**
 * Regenerates the `#:` source-reference comments in messages.pot and all seven translated
 * `.po` catalogs (lapis-client/src/jsMain/resources/modules/i18n/) from the CURRENT client
 * source tree, WITHOUT touching msgid or msgstr content anywhere.
 *
 * Why not KVision's own `generatePotFile` task: that tool (see ui-ux-guideline.adoc /
 * commit d906e0b's message) silently drops 61 live, indirectly-reached msgids when it
 * regenerates `#:` references -- it can't see through string-template interpolation,
 * const-concatenation chains, or indirect dispatch through helper functions, and treats
 * "no directly-found call site" as grounds to consider a msgid orphaned. That is a
 * genuinely destructive failure mode (loses real translations).
 *
 * This script never touches presence/absence of a msgid. It only ever ADDS an accurate
 * `#:` reference where a direct call-site literal is positively found. Where zero direct
 * literal call sites are found for a msgid (the same "indirectly reached" cases the W6-era
 * investigation flagged), the msgid keeps NO `#:` line at all -- which is valid, standard
 * gettext (the comment is optional) and strictly more honest than either a stale or a
 * fabricated line. Nothing is ever dropped or guessed.
 *
 * Scope: only `tr(` and `gettext(` call sites are extracted. `ntr(`/`ngettext(` are
 * deliberately NOT covered by the matcher below -- its negative lookbehind
 * `(?<![A-Za-z0-9_])` requires a non-word character (or start of input) immediately before
 * "tr(gettext(", so the "n" of "ngettext(" and the "n" of "ntr(" both block the match. This
 * is no longer a purely theoretical gap: SepaBatchesScreen.kt has three real `ngettext(...)`
 * call sites since the i18n-Restschuld plural-support wave (see PluralRules.kt), and none of
 * them gets a `#:` reference from this script -- exactly like the 61 indirectly-reached
 * msgids above, no `#:` line is honest here, a wrong one would not be. Extending the matcher
 * to also cover `ntr(`/`ngettext(` is a possible follow-up (tracked in ui-ux-guideline.adoc's
 * "Known gaps"), not done here to keep this fix to the comment alone.
 *
 * Usage: node scripts/i18n/regenerate-source-refs.mjs [--check]
 *   --check  exit 1 if applying the regenerated refs would change any catalog file,
 *            without writing anything (for CI / pre-commit use).
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const REPO_ROOT = join(import.meta.dirname, "..", "..");
const CLIENT_SRC = join(REPO_ROOT, "lapis-client", "src", "jsMain", "kotlin");
const I18N_DIR = join(REPO_ROOT, "lapis-client", "src", "jsMain", "resources", "modules", "i18n");
const CATALOG_FILES = [
  "messages.pot",
  "messages-en.po",
  "messages-es.po",
  "messages-fr.po",
  "messages-it.po",
  "messages-nl.po",
  "messages-pl.po",
  "messages-ru.po",
];
// The path form already used by every existing `#:` line in these catalogs, e.g.
// "src/jsMain/kotlin/network/lapis/cloud/client/BankAccountsScreen.kt:107".
const SOURCE_PREFIX = join("src", "jsMain", "kotlin");

function listKotlinFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      out.push(...listKotlinFiles(full));
    } else if (entry.endsWith(".kt")) {
      out.push(full);
    }
  }
  return out;
}

/** Kotlin string-escape decoding for the literal forms actually used in this codebase's tr()/gettext() call sites. */
export function unescapeKotlinString(raw) {
  let out = "";
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === "\\" && i + 1 < raw.length) {
      const next = raw[i + 1];
      switch (next) {
        case "n":
          out += "\n";
          i++;
          break;
        case "t":
          out += "\t";
          i++;
          break;
        case "r":
          out += "\r";
          i++;
          break;
        case '"':
          out += '"';
          i++;
          break;
        case "\\":
          out += "\\";
          i++;
          break;
        case "$":
          out += "$";
          i++;
          break;
        case "u": {
          const hex = raw.slice(i + 2, i + 6);
          if (/^[0-9a-fA-F]{4}$/.test(hex)) {
            out += String.fromCharCode(parseInt(hex, 16));
            i += 5;
          } else {
            out += c;
          }
          break;
        }
        default:
          out += next;
          i++;
      }
    } else {
      out += c;
    }
  }
  return out;
}

/**
 * Scans one file's full text for `tr(` / `gettext(` call sites whose first argument is a
 * literal Kotlin string, or a `+`-concatenation of only literal Kotlin strings (both are
 * used throughout this codebase -- see e.g. ConferenceWhiteboardController.kt / PaymentReturnScreen.kt
 * for the multi-line concatenation form). Returns a list of { value, line } where `line`
 * is the 1-based line of the `tr(`/`gettext(` token itself (matching the convention already
 * used by every pre-existing `#:` line in these catalogs).
 */
export function extractCallSites(text) {
  const results = [];
  const callRe = /(?<![A-Za-z0-9_])(?:tr|gettext)\(/g;
  let match;
  while ((match = callRe.exec(text)) !== null) {
    const callStart = match.index;
    let pos = match.index + match[0].length;
    // skip whitespace/newlines
    while (pos < text.length && /\s/.test(text[pos])) pos++;
    if (text[pos] !== '"') continue; // not a literal-first call (a variable, a template, etc.) -- can't safely attribute
    let value = "";
    let ok = true;
    // consume one or more `"..."` segments joined by `+`
    for (;;) {
      if (text[pos] !== '"') {
        ok = false;
        break;
      }
      pos++; // opening quote
      const segStart = pos;
      while (pos < text.length && text[pos] !== '"') {
        if (text[pos] === "\\") pos++; // skip escaped char
        pos++;
      }
      if (pos >= text.length) {
        ok = false;
        break;
      }
      value += unescapeKotlinString(text.slice(segStart, pos));
      pos++; // closing quote
      // look ahead, skipping whitespace/newlines, for a `+` continuation
      let lookahead = pos;
      while (lookahead < text.length && /\s/.test(text[lookahead])) lookahead++;
      if (text[lookahead] === "+") {
        lookahead++;
        while (lookahead < text.length && /\s/.test(text[lookahead])) lookahead++;
        if (text[lookahead] === '"') {
          pos = lookahead;
          continue; // another literal segment follows
        }
        // `+` followed by something that isn't a literal (a variable/expression) -- can't
        // safely attribute the whole concatenation as this msgid's exact source text.
        ok = false;
        break;
      }
      break; // no continuation: this literal (or literal chain) is the whole first argument
    }
    if (!ok) continue;
    const line = text.slice(0, callStart).split("\n").length;
    results.push({ value, line });
  }
  return results;
}

function buildReferenceMap() {
  const map = new Map(); // msgid value -> Set of "path:line"
  for (const file of listKotlinFiles(CLIENT_SRC)) {
    const text = readFileSync(file, "utf8");
    const relPath = relative(join(REPO_ROOT, "lapis-client"), file).split(sep).join("/");
    const posixPrefix = SOURCE_PREFIX.split(sep).join("/");
    const fullRelPath = `${posixPrefix}/${relPath.slice("src/jsMain/kotlin/".length)}`;
    for (const { value, line } of extractCallSites(text)) {
      if (!map.has(value)) map.set(value, new Set());
      map.get(value).add(`${fullRelPath}:${line}`);
    }
  }
  return map;
}

/** Parses a catalog file into an ordered list of entry blocks, each with its `#:` lines and the rest verbatim. */
export function parseCatalog(text) {
  const lines = text.split("\n");
  const entries = [];
  let i = 0;
  // header block: from the top up to (but not including) the first blank line after "msgstr """
  while (i < lines.length && !(lines[i] === "" && i > 0)) i++;
  const header = lines.slice(0, i);
  i++; // skip the blank separator line
  while (i < lines.length) {
    const blockStart = i;
    const refLines = [];
    while (i < lines.length && lines[i] !== "" && !lines[i].startsWith("msgid ")) {
      if (lines[i].startsWith("#:")) refLines.push(lines[i]);
      i++;
    }
    if (i >= lines.length || !lines[i].startsWith("msgid ")) {
      // trailing content that isn't a full entry (e.g. EOF blank lines) -- keep verbatim
      entries.push({ verbatim: lines.slice(blockStart) });
      break;
    }
    const preMsgid = lines.slice(blockStart, i).filter((l) => !l.startsWith("#:"));
    const msgidLineIdx = i;
    let msgidRaw = lines[i].slice('msgid "'.length, lines[i].length - 1);
    i++;
    // multi-line msgid continuation (plain quoted lines before msgstr)
    const msgidContinuation = [];
    while (i < lines.length && lines[i].startsWith('"') && !lines[i].startsWith('msgstr')) {
      msgidContinuation.push(lines[i]);
      i++;
    }
    const bodyStart = i;
    while (i < lines.length && lines[i] !== "") i++;
    const body = lines.slice(bodyStart, i); // msgstr line(s)
    if (i < lines.length && lines[i] === "") i++; // consume blank separator
    entries.push({
      preMsgid,
      refLines,
      msgidLine: lines[msgidLineIdx],
      msgidRaw,
      msgidContinuation,
      body,
    });
  }
  return { header, entries };
}

export function serializeCatalog({ header, entries }) {
  const out = [...header, ""];
  for (const entry of entries) {
    if (entry.verbatim) {
      out.push(...entry.verbatim);
      continue;
    }
    out.push(...entry.preMsgid);
    out.push(...entry.refLines);
    out.push(entry.msgidLine);
    out.push(...entry.msgidContinuation);
    out.push(...entry.body);
    out.push("");
  }
  // drop a possible trailing extra blank line to keep output stable
  while (out.length > 1 && out[out.length - 1] === "" && out[out.length - 2] === "") out.pop();
  return out.join("\n");
}

function main() {
  const checkOnly = process.argv.includes("--check");
  const listUnmatched = process.argv.includes("--list-unmatched");
  const refMap = buildReferenceMap();
  let changedFiles = 0;
  let totalRefsWritten = 0;
  let totalMsgidsWithoutRef = 0;
  const unmatchedInPot = [];

  for (const fileName of CATALOG_FILES) {
    const path = join(I18N_DIR, fileName);
    const original = readFileSync(path, "utf8");
    const parsed = parseCatalog(original);
    for (const entry of parsed.entries) {
      if (entry.verbatim) continue;
      const msgid = unescapeKotlinStyleFromPo(entry.msgidRaw, entry.msgidContinuation);
      const refs = refMap.get(msgid);
      if (refs && refs.size > 0) {
        entry.refLines = [...refs].sort().map((r) => `#: ${r}`);
        totalRefsWritten += entry.refLines.length;
      } else {
        entry.refLines = [];
        if (msgid !== "") {
          totalMsgidsWithoutRef++;
          if (fileName === "messages.pot") unmatchedInPot.push(msgid);
        }
      }
    }
    const updated = serializeCatalog(parsed);
    if (updated !== original) {
      changedFiles++;
      if (!checkOnly) writeFileSync(path, updated, "utf8");
    }
  }

  console.log(`catalogs scanned: ${CATALOG_FILES.length}, changed: ${changedFiles}`);
  console.log(`#: lines written (last catalog pass): ${totalRefsWritten}`);
  console.log(`msgids with no direct call-site match (left without #:, last catalog pass): ${totalMsgidsWithoutRef}`);

  if (listUnmatched) {
    console.log("--- msgids without a direct call-site match (messages.pot) ---");
    for (const m of unmatchedInPot) console.log(JSON.stringify(m));
  }

  if (checkOnly && changedFiles > 0) {
    console.error("--check: catalogs are stale, run without --check to regenerate.");
    process.exit(1);
  }
}

/** Reassembles a (possibly multi-line, `"..."`-continued) po msgid back into its Kotlin-literal value for lookup in refMap. */
export function unescapeKotlinStyleFromPo(msgidRaw, continuationLines) {
  let poEscaped = msgidRaw;
  for (const line of continuationLines) {
    poEscaped += line.slice(1, line.length - 1);
  }
  return unescapePoString(poEscaped);
}

export function unescapePoString(raw) {
  let out = "";
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === "\\" && i + 1 < raw.length) {
      const next = raw[i + 1];
      if (next === "n") {
        out += "\n";
        i++;
      } else if (next === "t") {
        out += "\t";
        i++;
      } else if (next === "r") {
        out += "\r";
        i++;
      } else if (next === '"') {
        out += '"';
        i++;
      } else if (next === "\\") {
        out += "\\";
        i++;
      } else {
        out += next;
        i++;
      }
    } else {
      out += c;
    }
  }
  return out;
}

// Only run when invoked directly (`node regenerate-source-refs.mjs ...`), not when imported
// by regenerate-source-refs.test.mjs.
if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
