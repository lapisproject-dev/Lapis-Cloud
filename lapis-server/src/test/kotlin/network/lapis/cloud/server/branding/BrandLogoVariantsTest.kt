package network.lapis.cloud.server.branding

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun file(
    variant: LogoVariant,
    ext: String,
) = ResolvedLogoFile(path = "/app/branding/${variant.slug}.$ext", extension = ext)

private fun branding(
    variants: Map<LogoVariant, ResolvedLogoFile> = emptyMap(),
    legacy: String? = null,
) = ResolvedBranding(
    title = "T",
    logoAvailable = legacy != null,
    logoPath = legacy,
    variants = variants,
)

/**
 * An INDEPENDENT formulation of the fallback chain (a table of preference lists, written out by hand), so the production
 * implementation is not checked against itself.
 */
private val REFERENCE_ORDER: Map<Pair<LogoMode, LogoKind>, List<LogoVariant>> =
    mapOf(
        (LogoMode.LIGHT to LogoKind.SIGNET) to
            listOf(LogoVariant.SIGNET_LIGHT, LogoVariant.SIGNET_DARK, LogoVariant.WORDMARK_LIGHT, LogoVariant.WORDMARK_DARK),
        (LogoMode.DARK to LogoKind.SIGNET) to
            listOf(LogoVariant.SIGNET_DARK, LogoVariant.SIGNET_LIGHT, LogoVariant.WORDMARK_DARK, LogoVariant.WORDMARK_LIGHT),
        (LogoMode.LIGHT to LogoKind.WORDMARK) to
            listOf(LogoVariant.WORDMARK_LIGHT, LogoVariant.WORDMARK_DARK, LogoVariant.SIGNET_LIGHT, LogoVariant.SIGNET_DARK),
        (LogoMode.DARK to LogoKind.WORDMARK) to
            listOf(LogoVariant.WORDMARK_DARK, LogoVariant.WORDMARK_LIGHT, LogoVariant.SIGNET_DARK, LogoVariant.SIGNET_LIGHT),
    )

class BrandLogoVariantsTest :
    FunSpec({
        test("all 32 combinations x 2 modes x 2 kinds resolve exactly like the reference chain") {
            val all = LogoVariant.entries
            var checked = 0
            for (mask in 0 until 16) {
                val present = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                for (legacy in listOf(null, "/app/branding/logo.svg")) {
                    val b = branding(variants = present.associateWith { file(variant = it, ext = "svg") }, legacy = legacy)
                    for (mode in LogoMode.entries) {
                        for (kind in LogoKind.entries) {
                            val expectedVariant = REFERENCE_ORDER.getValue(mode to kind).firstOrNull { it in present }
                            val expected =
                                when {
                                    expectedVariant != null -> LogoSource.Variant(expectedVariant)
                                    legacy != null -> LogoSource.Legacy
                                    else -> LogoSource.DefaultMark
                                }
                            b.resolveLogo(mode = mode, kind = kind) shouldBe expected
                            checked++
                        }
                    }
                }
            }
            checked shouldBe 128
        }

        test("HEADER_LOGO_KIND is the wordmark and decides the navbar flag in every configuration") {
            HEADER_LOGO_KIND shouldBe LogoKind.WORDMARK
            val svg = { v: LogoVariant -> v to file(variant = v, ext = "svg") }
            val configurations =
                mapOf(
                    "wordmark only" to (mapOf(svg(LogoVariant.WORDMARK_LIGHT)) to true),
                    "signet only" to (mapOf(svg(LogoVariant.SIGNET_LIGHT)) to false),
                    "both kinds" to (LogoVariant.entries.associate(svg) to true),
                    "wordmark light + signet dark" to
                        (mapOf(svg(LogoVariant.WORDMARK_LIGHT), svg(LogoVariant.SIGNET_DARK)) to true),
                    "signet light + wordmark dark" to
                        (mapOf(svg(LogoVariant.SIGNET_LIGHT), svg(LogoVariant.WORDMARK_DARK)) to true),
                    "none" to (emptyMap<LogoVariant, ResolvedLogoFile>() to false),
                )
            for ((name, cfg) in configurations) {
                val b = branding(variants = cfg.first)
                withClue(name) { b.wordmarkCarriesName(HEADER_LOGO_KIND) shouldBe cfg.second }
                // the header image of both modes is the wordmark chain, never a separate signet lookup
                for (mode in LogoMode.entries) {
                    b.resolveLogo(mode = mode, kind = HEADER_LOGO_KIND) shouldBe b.resolveLogo(mode = mode, kind = LogoKind.WORDMARK)
                }
            }
        }

        test("nothing configured -> default mark everywhere, no URL") {
            val b = branding()
            for (mode in LogoMode.entries) {
                for (kind in LogoKind.entries) {
                    b.resolveLogo(mode = mode, kind = kind) shouldBe LogoSource.DefaultMark
                    b.urlFor(b.resolveLogo(mode = mode, kind = kind)) shouldBe null
                }
            }
        }

        test("only the legacy logo -> Legacy everywhere at the legacy route") {
            val b = branding(legacy = "/app/branding/logo.png")
            for (mode in LogoMode.entries) {
                for (kind in LogoKind.entries) {
                    b.urlFor(b.resolveLogo(mode = mode, kind = kind)) shouldBe "/api/branding/logo"
                }
            }
        }

        test("only SIGNET_DARK -> every slot resolves to SIGNET_DARK") {
            val b = branding(variants = mapOf(LogoVariant.SIGNET_DARK to file(variant = LogoVariant.SIGNET_DARK, ext = "png")))
            for (mode in LogoMode.entries) {
                for (kind in LogoKind.entries) {
                    b.resolveLogo(mode = mode, kind = kind) shouldBe LogoSource.Variant(LogoVariant.SIGNET_DARK)
                }
            }
            b.urlFor(b.resolveLogo(mode = LogoMode.LIGHT, kind = LogoKind.WORDMARK)) shouldBe "/api/branding/logo/signet-dark"
        }

        test("only WORDMARK_LIGHT -> every slot resolves to it, and it carries the name for both kinds") {
            val b = branding(variants = mapOf(LogoVariant.WORDMARK_LIGHT to file(variant = LogoVariant.WORDMARK_LIGHT, ext = "svg")))
            for (mode in LogoMode.entries) {
                for (kind in LogoKind.entries) {
                    b.resolveLogo(mode = mode, kind = kind) shouldBe LogoSource.Variant(LogoVariant.WORDMARK_LIGHT)
                }
            }
            b.wordmarkCarriesName(LogoKind.SIGNET) shouldBe true
            b.wordmarkCarriesName(LogoKind.WORDMARK) shouldBe true
        }

        test("wordmarkCarriesName is false when any mode of the kind falls to a signet, legacy or the default mark") {
            branding().wordmarkCarriesName(LogoKind.SIGNET) shouldBe false
            branding(legacy = "/x/l.svg").wordmarkCarriesName(LogoKind.WORDMARK) shouldBe false
            val signetOnly = branding(variants = mapOf(LogoVariant.SIGNET_LIGHT to file(variant = LogoVariant.SIGNET_LIGHT, ext = "svg")))
            signetOnly.wordmarkCarriesName(LogoKind.SIGNET) shouldBe false
            signetOnly.wordmarkCarriesName(LogoKind.WORDMARK) shouldBe false
            // wordmark light + signet dark: SIGNET kind -> light: signet slot empty, falls to... own kind other mode (signet dark) = signet
            val mixed =
                branding(
                    variants =
                        mapOf(
                            LogoVariant.WORDMARK_LIGHT to file(variant = LogoVariant.WORDMARK_LIGHT, ext = "svg"),
                            LogoVariant.SIGNET_DARK to file(variant = LogoVariant.SIGNET_DARK, ext = "svg"),
                        ),
                )
            mixed.wordmarkCarriesName(LogoKind.SIGNET) shouldBe false
            mixed.wordmarkCarriesName(LogoKind.WORDMARK) shouldBe true
        }

        test("legacyRouteFile: the legacy logo wins; without it the light-signet chain; without anything null") {
            val v = mapOf(LogoVariant.WORDMARK_DARK to file(variant = LogoVariant.WORDMARK_DARK, ext = "webp"))
            branding(variants = v, legacy = "/app/branding/Logo.PNG").legacyRouteFile() shouldBe
                ResolvedLogoFile(path = "/app/branding/Logo.PNG", extension = "png")
            branding(variants = v).legacyRouteFile() shouldBe v.getValue(LogoVariant.WORDMARK_DARK)
            branding().legacyRouteFile() shouldBe null
            branding(legacy = "/app/branding/logo.svg").legacyRouteFile()?.extension shouldBe "svg"
        }

        test("rasterShareLogoPath: SVG-only is null; raster order wordmark-light, signet-light, wordmark-dark, signet-dark, legacy") {
            branding(
                variants = mapOf(LogoVariant.WORDMARK_LIGHT to file(variant = LogoVariant.WORDMARK_LIGHT, ext = "svg")),
            ).rasterShareLogoPath() shouldBe
                null
            branding(legacy = "/x/logo.svg").rasterShareLogoPath() shouldBe null
            branding().rasterShareLogoPath() shouldBe null

            val all = LogoVariant.entries.associateWith { file(variant = it, ext = "png") }
            branding(variants = all, legacy = "/x/logo.png").rasterShareLogoPath() shouldBe "/api/branding/logo/wordmark-light"
            branding(variants = all - LogoVariant.WORDMARK_LIGHT).rasterShareLogoPath() shouldBe "/api/branding/logo/signet-light"
            branding(variants = all - LogoVariant.WORDMARK_LIGHT - LogoVariant.SIGNET_LIGHT).rasterShareLogoPath() shouldBe
                "/api/branding/logo/wordmark-dark"
            branding(
                variants = mapOf(LogoVariant.SIGNET_DARK to file(variant = LogoVariant.SIGNET_DARK, ext = "webp")),
            ).rasterShareLogoPath() shouldBe
                "/api/branding/logo/signet-dark"
            // An SVG variant is skipped in favour of a later raster one.
            branding(
                variants =
                    mapOf(
                        LogoVariant.WORDMARK_LIGHT to file(variant = LogoVariant.WORDMARK_LIGHT, ext = "svg"),
                        LogoVariant.SIGNET_DARK to file(variant = LogoVariant.SIGNET_DARK, ext = "png"),
                    ),
            ).rasterShareLogoPath() shouldBe "/api/branding/logo/signet-dark"
            // Legacy raster is the last resort, and unchanged for a legacy-only instance.
            branding(legacy = "/x/logo.webp").rasterShareLogoPath() shouldBe "/api/branding/logo"
        }

        test("fromSlug matches exactly: no case folding, no trimming, null and empty are null") {
            LogoVariant.entries.forEach { LogoVariant.fromSlug(it.slug) shouldBe it }
            LogoVariant.fromSlug("Signet-Light") shouldBe null
            LogoVariant.fromSlug("SIGNET-LIGHT") shouldBe null
            LogoVariant.fromSlug(" signet-light") shouldBe null
            LogoVariant.fromSlug("signet-light ") shouldBe null
            LogoVariant.fromSlug("") shouldBe null
            LogoVariant.fromSlug(null) shouldBe null
            LogoVariant.fromSlug("../signet-light") shouldBe null
        }

        test("servesAnyLogo is true for the legacy logo or any variant only") {
            branding().servesAnyLogo shouldBe false
            branding(legacy = "/x/l.svg").servesAnyLogo shouldBe true
            branding(
                variants = mapOf(LogoVariant.SIGNET_LIGHT to file(variant = LogoVariant.SIGNET_LIGHT, ext = "svg")),
            ).servesAnyLogo shouldBe
                true
        }

        test("brandingLogoContentType allowlist") {
            brandingLogoContentType("svg")?.toString() shouldBe "image/svg+xml"
            brandingLogoContentType("PNG")?.toString() shouldBe "image/png"
            brandingLogoContentType("webp")?.toString() shouldBe "image/webp"
            brandingLogoContentType("jpg") shouldBe null
            brandingLogoContentType("") shouldBe null
        }
    })
