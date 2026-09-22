package network.lapis.cloud.docs

import dev.kuml.asciidoc.AsciidocOutputMode
import dev.kuml.asciidoc.AsciidocProcessor
import java.io.File

/**
 * One [sourceFile]'s outcome: either it rendered cleanly (with [renderedDiagramCount] kUML
 * blocks turned into images, possibly zero for a file with no kUML blocks at all), or it
 * failed with [failure] — collected rather than thrown immediately so a single broken
 * diagram doesn't hide every other file's result in the same run.
 */
internal data class RenderOutcome(
    val sourceFile: File,
    val renderedDiagramCount: Int,
    val failure: Throwable?,
)

/**
 * Walks every `.adoc` file under [inputDir], runs each through [AsciidocProcessor] and
 * writes the result (kUML blocks replaced by `image::` links to rendered SVGs) to the
 * mirrored path under [outputDir]. Files without any kUML block are copied through
 * unchanged, matching `kuml asciidoc`'s own directory-mode behaviour (see
 * kuml-dev/kUML's `docs/handbook/modules/tooling/pages/asciidoc.adoc`).
 */
internal fun renderDocsTree(
    inputDir: File,
    outputDir: File,
): List<RenderOutcome> {
    val adocFiles = inputDir.walkTopDown().filter { it.isFile && it.extension == "adoc" }.toList()

    return adocFiles.map { sourceFile ->
        val relativePath = sourceFile.relativeTo(inputDir)
        val targetFile = File(outputDir, relativePath.path)
        targetFile.parentFile.mkdirs()

        try {
            val processor = AsciidocProcessor(baseDir = sourceFile.parentFile)
            val imagesDir = File(targetFile.parentFile, "images")
            val result =
                processor.process(
                    input = sourceFile.readText(),
                    mode = AsciidocOutputMode.LinkedSvg(imagesDir),
                    baseName = sourceFile.nameWithoutExtension,
                )
            targetFile.writeText(result.output)
            RenderOutcome(sourceFile = sourceFile, renderedDiagramCount = result.assets.size, failure = null)
        } catch (e: Exception) {
            RenderOutcome(sourceFile = sourceFile, renderedDiagramCount = 0, failure = e)
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: renderDocs <inputDir> <outputDir>" }
    val inputDir = File(args[0])
    val outputDir = File(args[1])
    require(inputDir.isDirectory) { "input directory does not exist: ${inputDir.absolutePath}" }

    outputDir.deleteRecursively()
    outputDir.mkdirs()
    val outcomes = renderDocsTree(inputDir = inputDir, outputDir = outputDir)

    val failures = outcomes.filter { it.failure != null }
    val renderedDiagramCount = outcomes.sumOf { it.renderedDiagramCount }
    println(
        "kUML AsciiDoc: rendered $renderedDiagramCount diagram(s) across ${outcomes.size} file(s) " +
            "-> ${outputDir.absolutePath} (${failures.size} file(s) failed)",
    )

    if (failures.isNotEmpty()) {
        failures.forEach { outcome ->
            println("FAILED ${outcome.sourceFile.relativeTo(inputDir)}: ${outcome.failure!!.message}")
        }
        error("${failures.size} file(s) failed kUML diagram rendering (see above).")
    }
}
