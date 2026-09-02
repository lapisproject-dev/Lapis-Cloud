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
