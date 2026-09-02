plugins {
    // `base` gives the root project its own lifecycle `check` task (it has no
    // Kotlin plugin of its own) so `verifyDetektCoverage` below can hang off
    // it. Gradle's by-name task matching still runs every subproject's own
    // `check` exactly as before — this only adds one more (root-level) check.
    // Ported from kuml-dev/kUML's root build.gradle.kts.
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kvision) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kilua.rpc) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "network.lapis.cloud"
    version = "0.17.0"
}

// Kotlin modules that provably cannot be covered by the RequireNamedArguments
// gate. Every entry needs a written justification in the module's own build
// script. Adding to this set is a review-gated decision.
//  - lapis-client: Kotlin/JS-only multiplatform module (no jvm() target — see
//    its own build.gradle.kts, `kotlin { js { browser { ... } } }` with no
//    accompanying `jvm()` block). Mirrors kuml-dev/kUML's kuml-wasm-playground
//    exemption: the detekt Gradle plugin only registers a type-resolution
//    compilation task (one with a classpath, able to run a RequiresAnalysisApi
//    rule) for jvm/androidJvm compilations — verified empirically against this
//    repo 2026-08-15 (`./gradlew :lapis-client:tasks --group verification`
//    shows only detektCommonMainSourceSet/detektJsMainSourceSet/
//    detektWebMainSourceSet/... — no detektMain*/detektTest* task at all,
//    unlike :lapis-shared, whose jvm() target produces detektMainJvm and
//    detektTestJvm). Without this exemption the module would silently pass
//    `check` with zero findings inspected, exactly the false-green failure
//    mode `verifyDetektCoverage` exists to catch. If lapis-client ever grows
//    a jvm() target, remove this exemption.
val lapisDetektExemptModules = setOf(":lapis-client")

// Apply ktlint (+ the custom Detekt RequireNamedArguments gate) to every
// subproject that carries a Kotlin JVM or Kotlin Multiplatform plugin
// (mirrors the kuml-dev/kUML root build convention).
subprojects {
    // The ruleset module itself must not be analysed by the ruleset it builds
    // (bootstrap cycle: detekt would need :lapis-detekt-rules:jar to lint
    // :lapis-detekt-rules). ktlint still applies to it.
    val isDetektRulesModule = path == ":lapis-detekt-rules"

    fun applyLapisLinters() {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        // Never lint generated sources (e.g. Kilua RPC's KSP-generated
        // jvm/js/commonMain bindings under build/generated/ksp/**) — they are
        // not hand-written, are regenerated on every build, and ktlint's
        // implicit-dependency validation otherwise races the KSP task that
        // produces them.
        plugins.withId("org.jlleitschuh.gradle.ktlint") {
            extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
                filter {
                    exclude {
                        entry ->
                        entry.file.path.contains("${java.io.File.separator}generated${java.io.File.separator}")
                    }
                }
            }
        }

        if (isDetektRulesModule) return
        if (path in lapisDetektExemptModules) return

        apply(plugin = "dev.detekt")

        extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig = false // built-in rulesets stay off — see detekt.yml header
            ignoreFailures = false
            autoCorrect =
                providers.gradleProperty("lapis.detekt.autoCorrect")
                    .getOrElse("false")
                    .toBoolean()
        }

        dependencies.add("detektPlugins", project(":lapis-detekt-rules"))

        // Never analyse generated sources (e.g. Kilua RPC's KSP-generated
        // jvm/js/commonMain bindings under build/generated/ksp/**, registered
        // as extra source dirs on the very sourceSets detekt scans) — they are
        // not hand-written, are regenerated on every build (so any autocorrect
        // edit would be silently discarded on the next KSP run), and are not
        // "our own code" in the sense CLAUDE.md's Named-Parameters rule targets
        // even though their fully-qualified names sit under network.lapis.cloud.
        // (KSP's generator emits the code inside the consuming module's own
        // package, e.g. GeneratedRpcServiceManager's `bind(...)` calls resolve
        // to network.lapis.cloud.shared.rpc.AccountingServiceManager.bind).
        // Discovered 2026-08-15: a first full autocorrect pass over lapis-shared
        // left 819 residual findings that never converged across repeated runs
        // — all traced to dev.kilua.rpc.GeneratedRpcServiceManager(Jvm).kt under
        // build/generated/ksp/.
        //
        // Two failed approaches before this one, both worth recording so nobody
        // re-tries them:
        //  1. `exclude("**/generated/**")` (the glob-string PatternFilterable
        //     overload, mirroring ktlint's exclusion) — globs match paths
        //     *relative to each registered source root*, and the KSP-generated
        //     source root IS ".../build/generated/ksp/metadata/commonMain/
        //     kotlin" itself, so the relative path below it (e.g. "network/
        //     lapis/cloud/shared/rpc/AccountingService.kt") never contains a
        //     "generated" segment at all — silently matched nothing, all 819
        //     findings remained.
        //  2. `setSource(source.filter { ... })` — the detekt Kotlin-
        //     Multiplatform integration wires each compilation's source
        //     *after* this configureEach block runs (it reacts to the `kotlin
        //     { jvm() }` target block later in this module's own
        //     build.gradle.kts, which executes after the root script's
        //     `subprojects {}` closure that applies this plugin), then calls
        //     `detektTask.source(source)` — SourceTask's *appending* overload —
        //     re-adding the full unfiltered compilation source set on top of
        //     whatever this block had just set. Net effect: no change.
        // The closure-based `exclude(Spec<FileTreeElement>)` overload below
        // works because PatternFilterable exclude *patterns* are stored
        // separately from the source FileCollection itself and are applied
        // whenever `.getFiles()` is resolved, regardless of how many times
        // `source`/`source(...)` gets reassigned afterwards — and because the
        // Spec receives the FileTreeElement's absolute `.file`, not a root-
        // relative path, sidestepping failure mode 1 as well. Verified
        // 2026-08-15: reduced lapis-shared's mainJvm findings from 819 to 0.
        tasks.withType(dev.detekt.gradle.Detekt::class.java).configureEach {
            exclude { element ->
                element.file.path.contains("${java.io.File.separator}generated${java.io.File.separator}")
            }
        }

        // ── neutralise the no-type-resolution tasks ───────────────────────────
        // `detekt`, `detektMainSourceSet`, `detektCommonMainSourceSet`,
        // `detektJsMain`, … all run WITHOUT a classpath. A RequiresAnalysisApi
        // rule is skipped there, so they always pass and would make `check`
        // look green for modules the gate never actually inspected.
        tasks.withType(dev.detekt.gradle.Detekt::class.java).configureEach {
            val hasTypeResolution = name.startsWith("detektMain") || name.startsWith("detektTest")
            if (!hasTypeResolution) {
                enabled = false
            }
        }

        // ── wire the surviving type-resolution tasks into `check` ────────────
        tasks.named("check").configure {
            dependsOn(
                tasks.withType(dev.detekt.gradle.Detekt::class.java).matching { it.enabled },
            )
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { applyLapisLinters() }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { applyLapisLinters() }
}

// Structural guard against a Kotlin module that quietly has no working lint
// gate. Fails the build at *configuration-check* time — not by producing zero
// findings — if any Kotlin subproject ends up without a type-resolution
// detekt task in its `check` graph and is not on the documented exemption
// list. Ported from kuml-dev/kUML's root build.gradle.kts.
tasks.register("verifyDetektCoverage") {
    group = "verification"
    description = "Fails if any Kotlin subproject lacks a type-resolution detekt task wired into check."

    val offenders =
        subprojects.filter { sp ->
            val isKotlin =
                sp.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
                    sp.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
            if (!isKotlin) return@filter false
            if (sp.path == ":lapis-detekt-rules") return@filter false
            if (sp.path in lapisDetektExemptModules) return@filter false
            sp.tasks.withType(dev.detekt.gradle.Detekt::class.java)
                .none { it.enabled && (it.name.startsWith("detektMain") || it.name.startsWith("detektTest")) }
        }.map { it.path }

    doLast {
        check(offenders.isEmpty()) {
            "No type-resolution detekt task for: ${offenders.joinToString()}. " +
                "Either give the module a jvm() target, or add it to lapisDetektExemptModules " +
                "in the root build.gradle.kts WITH a written justification in the module's own " +
                "build script (see lapis-client)."
        }
    }
}

tasks.named("check") { dependsOn("verifyDetektCoverage") }

// ── i18n-Katalog-Wächter (Welle V1.4.1a "Öffentliche Website-Integration") ──────────────────────
// Alle acht gettext-Kataloge unter lapis-client/.../modules/i18n müssen dieselbe msgid-MENGE
// tragen (Ist-Zustand verifiziert: 1906 msgid je Katalog, keine Abweichung). Bis zu dieser Welle
// war das eine ungeprüfte Invariante -- der Fehler "neuer tr()-String nur in messages.pot und
// messages-en.po nachgetragen" ist wiederholt aufgetreten. Analog zu verifyDetektCoverage oben:
// hängt am Root-`check`, macht die Fehlerklasse strukturell unmöglich statt disziplinabhängig.
val i18nCatalogDirFile = file("lapis-client/src/jsMain/resources/modules/i18n")
val i18nCatalogNames =
    listOf(
        "messages.pot",
        "messages-en.po", "messages-es.po", "messages-fr.po", "messages-it.po",
        "messages-nl.po", "messages-pl.po", "messages-ru.po",
    )

/**
 * Config-Cache-safe worker for [tasks.register] `verifyI18nCatalogParity`'s `doLast` -- a real
 * `Action<Task>` object (not a script-level `fun`/lambda) so nothing here ever needs to serialize
 * a reference to the enclosing build script instance (Stolperfalle: a top-level script `fun`
 * called from inside a `doLast { }` lambda captures `this@Build_gradle` implicitly, which the
 * Configuration Cache cannot serialize -- see CLAUDE.md "kUML-Repo-Konventionen" §
 * "Configuration Cache"). Only plain `File`/`List<String>` constructor arguments are held as
 * fields; the msgid parser itself is a local function nested inside [execute], never a
 * script-level declaration.
 */
private class VerifyI18nCatalogParity(
    private val dirFile: File,
    private val names: List<String>,
) : Action<Task> {
    override fun execute(task: Task) {
        val catalogFiles = names.map { File(dirFile, it) }
        val missingFiles = catalogFiles.filter { !it.exists() }
        check(missingFiles.isEmpty()) {
            "verifyI18nCatalogParity: missing catalog file(s): ${missingFiles.joinToString { it.name }} " +
                "under ${dirFile.path} -- expected exactly: ${names.joinToString()}."
        }

        val actualPoFiles =
            dirFile
                .listFiles { f -> f.isFile && (f.name.endsWith(".po") || f.name.endsWith(".pot")) }
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        val unexpected = actualPoFiles - names.toSet()
        check(unexpected.isEmpty()) {
            "verifyI18nCatalogParity: new catalog(s) found but not registered: ${unexpected.joinToString()} -- " +
                "add them to i18nCatalogNames in the root build.gradle.kts (verifyI18nCatalogParity)."
        }

        /**
         * Parses the msgid entries of a single gettext catalog file. Zeilenweise:
         * - a line starting with `msgid ` opens a new entry; the REST of that line is its first
         *   quoted segment, and every immediately following line that itself starts with `"` is a
         *   continuation segment appended to it (191 entries in this repo's catalogs use
         *   multi-line msgids -- a naive `grep '^msgid '` undercounts for exactly this reason).
         * - segments are stripped of their surrounding `"` and concatenated WITHOUT unescaping --
         *   the escape conventions are identical across every catalog, so comparing the raw
         *   quoted form is both correct and simpler than decoding `\n`/`\"`/etc.
         * - `#~ msgid` (127 obsolete entries in messages-en.po) does not start a line with
         *   `msgid ` and is therefore correctly ignored.
         * - the header entry `msgid ""` is counted like any other (present, identically, in all
         *   eight).
         * - `msgstr` lines never get collected as msgid continuations: the loop below closes the
         *   current entry the moment it sees any line that is neither `msgid ` nor a
         *   `"`-continuation.
         * - `msgid_plural`/`msgctxt` do not occur in this repo (verified: zero occurrences) and
         *   are deliberately NOT handled -- an occurrence fails this parser loudly (wrong entry
         *   count) rather than silently mis-parsing.
         */
        fun parseMsgIds(file: File): List<String> {
            val ids = mutableListOf<String>()
            var current: MutableList<String>? = null
            fun flush() {
                current?.let { segments -> ids += segments.joinToString(separator = "") { it.removeSurrounding("\"") } }
                current = null
            }
            file.forEachLine { rawLine ->
                val line = rawLine.trimEnd('\r')
                when {
                    line.startsWith("msgid ") -> {
                        flush()
                        current = mutableListOf(line.removePrefix("msgid ").trim())
                    }
                    current != null && line.trim().startsWith("\"") -> {
                        current!!.add(line.trim())
                    }
                    else -> flush()
                }
            }
            flush()
            return ids
        }

        val referenceFile = File(dirFile, "messages.pot")
        val referenceIds = parseMsgIds(referenceFile)
        val referenceDuplicates = referenceIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(referenceDuplicates.isEmpty()) {
            "verifyI18nCatalogParity: messages.pot itself contains duplicate msgid(s): " +
                referenceDuplicates.take(10).joinToString { "\"" + it.take(120) + "\"" }
        }
        val referenceSet = referenceIds.toSet()

        val failures = mutableListOf<String>()
        for (name in names) {
            if (name == "messages.pot") continue
            val file = File(dirFile, name)
            val ids = parseMsgIds(file)
            val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                failures +=
                    "$name contains duplicate msgid(s): " +
                        duplicates.take(10).joinToString { "\"" + it.take(120) + "\"" }
            }
            val idSet = ids.toSet()
            val missing = (referenceSet - idSet).take(10)
            val extra = (idSet - referenceSet).take(10)
            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                failures +=
                    "$name has ${ids.size} msgid(s), messages.pot (reference) has ${referenceIds.size}. " +
                        (if (missing.isNotEmpty()) "Missing (up to 10): ${missing.joinToString { "\"" + it.take(120) + "\"" }}. " else "") +
                        (if (extra.isNotEmpty()) "Extra (up to 10): ${extra.joinToString { "\"" + it.take(120) + "\"" }}." else "")
            }
        }
        check(failures.isEmpty()) {
            "verifyI18nCatalogParity: msgid set mismatch across catalogs (see CLAUDE.md \"Welle V1.4.1a\"):\n" +
                failures.joinToString("\n")
        }
    }
}

tasks.register("verifyI18nCatalogParity") {
    group = "verification"
    description = "Fails if the eight gettext catalogs do not carry an identical msgid set."
    val catalogFiles: List<File> = i18nCatalogNames.map { File(i18nCatalogDirFile, it) }
    inputs.files(catalogFiles).withPropertyName("i18nCatalogs")
    doLast(VerifyI18nCatalogParity(dirFile = i18nCatalogDirFile, names = i18nCatalogNames))
}

tasks.named("check") { dependsOn("verifyI18nCatalogParity") }
