// Unit tests for regenerate-source-refs.mjs's own parsing/extraction logic, using Node's
// built-in test runner (no extra dependency). Run with:
//   node --test scripts/i18n/regenerate-source-refs.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  extractCallSites,
  unescapeKotlinString,
  unescapePoString,
  unescapeKotlinStyleFromPo,
  parseCatalog,
  serializeCatalog,
} from "./regenerate-source-refs.mjs";

test("extractCallSites: single-line tr() literal", () => {
  const [hit] = extractCallSites('        tr("Aktivieren …")\n');
  assert.equal(hit.value, "Aktivieren …");
  assert.equal(hit.line, 1);
});

test("extractCallSites: gettext() literal on a later line", () => {
  const text = "line1\nline2\n    gettext(\"Speichern\")\n";
  const [hit] = extractCallSites(text);
  assert.equal(hit.value, "Speichern");
  assert.equal(hit.line, 3);
});

test("extractCallSites: multi-line '+'-concatenated literal is joined into one value, at the tr( line", () => {
  const text = 'tr(\n    "Teil eins " +\n    "Teil zwei"\n)\n';
  const [hit] = extractCallSites(text);
  assert.equal(hit.value, "Teil eins Teil zwei");
  assert.equal(hit.line, 1);
});

test("extractCallSites: a variable first argument is skipped, never misattributed", () => {
  const hits = extractCallSites("tr(someVariable)\n");
  assert.equal(hits.length, 0);
});

test("extractCallSites: literal concatenated with a variable is skipped entirely", () => {
  const hits = extractCallSites('tr("prefix " + someVariable)\n');
  assert.equal(hits.length, 0);
});

test("extractCallSites: does not match an unrelated identifier ending in tr/gettext", () => {
  const hits = extractCallSites('regettext("nope")\nrestructure("nope")\n');
  assert.equal(hits.length, 0);
});

test("extractCallSites: two calls on the same line are both found at that line", () => {
  const hits = extractCallSites('tr("A") + " " + tr("B")\n');
  assert.equal(hits.length, 2);
  assert.equal(hits[0].value, "A");
  assert.equal(hits[1].value, "B");
  assert.equal(hits[0].line, 1);
  assert.equal(hits[1].line, 1);
});

test("unescapeKotlinString: standard escapes", () => {
  assert.equal(unescapeKotlinString('a\\nb'), "a\nb");
  assert.equal(unescapeKotlinString('a\\"b'), 'a"b');
  assert.equal(unescapeKotlinString('a\\\\b'), "a\\b");
});

test("unescapePoString / unescapeKotlinString agree on a plain sentence (round-trip via po escaping)", () => {
  const original = 'Sagt "Hallo" und geht.';
  const poEscaped = original.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  assert.equal(unescapePoString(poEscaped), original);
  assert.equal(unescapeKotlinString(poEscaped), original);
});

test("unescapeKotlinStyleFromPo: joins a multi-line po msgid continuation back into one value", () => {
  const value = unescapeKotlinStyleFromPo("Teil eins ", ['"Teil zwei"']);
  assert.equal(value, "Teil eins Teil zwei");
});

test("parseCatalog -> serializeCatalog round-trips an already-normalized catalog (structurally stable)", () => {
  const fixture =
    'msgid ""\n' +
    'msgstr ""\n' +
    '"Language: en\\n"\n' +
    '"Content-Type: text/plain; charset=UTF-8\\n"\n' +
    "\n" +
    "#: src/jsMain/kotlin/network/lapis/cloud/client/Foo.kt:12\n" +
    'msgid "Hallo"\n' +
    'msgstr "Hello"\n' +
    "\n" +
    'msgid "Ohne Referenz"\n' +
    'msgstr "Without reference"\n';
  const parsed = parseCatalog(fixture);
  const serialized = serializeCatalog(parsed);
  // Re-parsing the serialized output must yield the exact same entries (order, refs, body) --
  // a stronger, newline-convention-independent check than a raw string comparison.
  assert.deepEqual(parseCatalog(serialized).entries, parsed.entries);
});

test("parseCatalog: preserves msgstr content and entry order exactly, only #: lines are structural", () => {
  const fixture =
    'msgid ""\n' +
    'msgstr ""\n' +
    '"Language: en\\n"\n' +
    "\n" +
    "#: old/stale/Path.kt:1\n" +
    'msgid "A"\n' +
    'msgstr "translated A"\n' +
    "\n" +
    'msgid "B"\n' +
    'msgstr "translated B"\n';
  const parsed = parseCatalog(fixture);
  const msgids = parsed.entries.filter((e) => !e.verbatim).map((e) => e.msgidRaw);
  assert.deepEqual(msgids, ["A", "B"]);
  const bBody = parsed.entries.find((e) => e.msgidRaw === "B").body;
  assert.deepEqual(bBody, ['msgstr "translated B"']);
});
