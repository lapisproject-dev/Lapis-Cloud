package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * i18n-Restschuld, round 5 (2026-09-27): the three `SepaBatchesScreen.kt` sites that switched from `gettext("%1
 * Positionen ...")` to `ngettext("%1 Position ...", "%1 Positionen ...", count, count, ...)` (this codebase's own
 * top-level wrapper in `I18nCatalogManager.kt` -- KVision itself publishes no top-level free function for the
 * plural case, only the `I18n` singleton's own method and its `tr(...)`-style, single-value-only `ntr()`/`trans()`
 * pair, unsuitable here since these templates need a second substitution argument too) -- the actual "1
 * Positionen" grammar bug fix this wave exists for (see `CHANGELOG.md`'s "i18n-Restschuld" entry,
 * `ui-ux-guideline.adoc`'s "i18n-Restschuld" section).
 *
 * Why this needs its OWN test, not just [AllClientMessagesCatalogTest]: that test's extractor (`extractMessages`,
 * `\b(?:tr|gettext)\(`) never sees an `ngettext(...)` call site -- a word boundary never appears between "n" and
 * "gettext" -- and [parseCatalog] never sees a `msgid`/`msgid_plural` block -- a `msgstr[N] "..."` line matches none
 * of its four line patterns, so the block's `flush()` finds a null `str` and silently drops the whole entry (see
 * that function's own KDoc for the analogous claim about [parseCatalog]). Together, the two existing tests are
 * blind to this wave's entries in both directions: neither the Kotlin call sites nor the catalog blocks they
 * depend on are checked anywhere else. This is the substitute safety net, using [parsePluralCatalog] instead.
 *
 * A small, explicit ledger ([EXPECTED_PLURAL_MESSAGES]) rather than extending the generic scanner to also match
 * `ngettext(` -- three call sites do not justify widening a regex whose current narrow scope
 * (`scripts/i18n/regenerate-source-refs.mjs`'s own comment, corrected this same round) is a deliberate, documented
 * choice; extending it later remains possible without touching this file.
 */
private val EXPECTED_PLURAL_MESSAGES: List<Pair<String, String>> =
    listOf(
        "Lauf anlegen (%1 Position, %2)" to "Lauf anlegen (%1 Positionen, %2)",
        "%1 Position konnte nicht gebucht werden -- erneut versuchen mit \"Abrechnen\"." to
            "%1 Positionen konnten nicht gebucht werden -- erneut versuchen mit \"Abrechnen\".",
        "%1 Position konnte nicht gebucht werden." to "%1 Positionen konnten nicht gebucht werden.",
        // Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" --
        // RegionalChaptersScreen.kt's "%1 Mitglieder ohne Landesverband" headline (plan §1 P6).
        "%1 Mitglied ohne Landesverband" to "%1 Mitglieder ohne Landesverband",
    )

/** `nplurals` per language, matching `PluralRules.kt`'s `pluralFormCount` (not importable here: `lapis-client` is a separate, jsMain-only module). */
private fun expectedFormCount(lang: String) = if (lang in listOf("pl", "ru")) 3 else 2

class PluralMessagesCatalogTest :
    FunSpec({
        val template by lazy { parsePluralCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parsePluralCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the template has all three plural entries, each with the matching msgid_plural and two empty forms") {
            EXPECTED_PLURAL_MESSAGES.forEach { (singular, plural) ->
                val entry = checkNotNull(template[singular]) { "template: missing plural entry for \"$singular\"" }
                entry.pluralMsgid shouldBe plural
                entry.forms.size shouldBe 2
                entry.forms.all { it.isEmpty() } shouldBe true
            }
        }

        test("every catalog has all three plural entries with the matching msgid_plural") {
            val missing =
                CATALOG_LANGUAGES.flatMap { lang ->
                    EXPECTED_PLURAL_MESSAGES.mapNotNull { (singular, _) ->
                        if (catalogs.getValue(lang)[singular] == null) "$lang: \"$singular\"" else null
                    }
                }
            missing.shouldBeEmpty()
            val wrongPlural =
                CATALOG_LANGUAGES.flatMap { lang ->
                    EXPECTED_PLURAL_MESSAGES.mapNotNull { (singular, plural) ->
                        val actualPlural = catalogs.getValue(lang)[singular]?.pluralMsgid ?: return@mapNotNull null
                        val mismatch = "$lang: \"$singular\" has msgid_plural \"$actualPlural\", expected \"$plural\""
                        if (actualPlural != plural) mismatch else null
                    }
                }
            wrongPlural.shouldBeEmpty()
        }

        test("every catalog has exactly the right number of non-blank forms for its language (2, or 3 for pl/ru)") {
            val problems =
                CATALOG_LANGUAGES.flatMap { lang ->
                    val expected = expectedFormCount(lang)
                    EXPECTED_PLURAL_MESSAGES.mapNotNull { (singular, _) ->
                        val forms = catalogs.getValue(lang)[singular]?.forms.orEmpty()
                        when {
                            forms.size != expected -> "$lang: \"$singular\" has ${forms.size} form(s), expected $expected"
                            forms.any { it.isBlank() } -> "$lang: \"$singular\" has a blank form: $forms"
                            else -> null
                        }
                    }
                }
            problems.shouldBeEmpty()
        }

        test("every form of every catalog keeps the %N placeholders of its msgid") {
            val placeholder = Regex("""%\d""")
            val broken =
                CATALOG_LANGUAGES.flatMap { lang ->
                    EXPECTED_PLURAL_MESSAGES.mapNotNull { (singular, _) ->
                        val forms = catalogs.getValue(lang)[singular]?.forms.orEmpty()
                        val expectedPlaceholders = placeholder.findAll(singular).map { it.value }.toSet()
                        val brokenForms = forms.filter { placeholder.findAll(it).map { m -> m.value }.toSet() != expectedPlaceholders }
                        if (brokenForms.isNotEmpty()) "$lang: \"$singular\" forms with wrong placeholders: $brokenForms" else null
                    }
                }
            broken.shouldBeEmpty()
        }
    })
