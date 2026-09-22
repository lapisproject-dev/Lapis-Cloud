// Pre-renders kUML diagrams embedded in docs/**/*.adoc. `dev.kuml:kuml-asciidoc` is a text
// preprocessor (regex-based, operates on the raw AsciiDoc string before Asciidoctor ever
// runs) — not a live Asciidoctor extension — so this module exists purely to invoke it as an
// explicit build step. See `renderDocs` below and CLAUDE.md "kUML-Repo-Konventionen" for the
// upstream kuml-dev/kUML precedent (`kuml asciidoc` CLI / `scripts/build-handbook.sh`) this
// mirrors as a plain JVM library dependency instead of shelling out to the CLI.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kuml.asciidoc)

    // `kuml-asciidoc` itself only pulls in the core UML/C4/SysML2/BPMN/ERM metamodels — a
    // `.kuml.kts` block that applies the ERM mapping profile (`applyProfile(ermMappingProfile)`,
    // used across docs/architecture/*.adoc for entity/table stereotypes) additionally needs the
    // profile/codegen modules below, or script evaluation fails with "Unresolved reference
    // 'ermMappingProfile'". Same module set lapis-server already pulls in test-scoped for
    // SchemaDriftTest — see gradle/libs.versions.toml's kuml-profile-erm comment.
    implementation(libs.kuml.profile.api)
    implementation(libs.kuml.profile.erm)
    implementation(libs.kuml.codegen.api)
    implementation(libs.kuml.codegen.m2m)
    implementation(libs.kuml.transform.uml.to.erm)
    implementation(libs.kuml.codegen.m2m.exposed)
    implementation(libs.kuml.gen.sql)
    implementation(libs.kotlin.scripting.jvm.host)
}

val docsSourceDir = rootProject.layout.projectDirectory.dir("docs")
val docsRenderedDir = layout.buildDirectory.dir("docs-rendered")

val renderDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Pre-renders [source,kuml] / kuml::path[] blocks in docs/**/*.adoc into " +
        "build/docs-rendered/ (linked SVG images, Antora-compatible image:: macros)."
    dependsOn(tasks.named("compileKotlin"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("network.lapis.cloud.docs.RenderDocsKt")
    inputs.dir(docsSourceDir).withPropertyName("docsSource")
    outputs.dir(docsRenderedDir).withPropertyName("docsRendered")
    args =
        listOf(
            docsSourceDir.asFile.absolutePath,
            docsRenderedDir.get().asFile.absolutePath,
        )
}
