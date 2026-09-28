package network.lapis.cloud.server.membermap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/** Mirrors `network.lapis.cloud.server.branding.BrandConfigTest`'s structure -- pure `env` injection, no filesystem access. */
class MemberMapConfigTest :
    FunSpec({
        test("unset -> null path, no invalid entries") {
            val config = MemberMapConfig.load(envOf())
            config.pmtilesPath shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("blank -> null path, no invalid entries") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "   "))
            config.pmtilesPath shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("valid absolute .pmtiles path -> accepted") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "/app/geodata/germany.pmtiles"))
            config.pmtilesPath shouldBe "/app/geodata/germany.pmtiles"
            config.invalid.shouldBeEmpty()
        }

        test("uppercase extension .PMTILES -> accepted (case-insensitive)") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "/data/germany.PMTILES"))
            config.pmtilesPath shouldBe "/data/germany.PMTILES"
            config.invalid.shouldBeEmpty()
        }

        test("relative path -> rejected, invalid names LAPIS_MAP_PMTILES_PATH") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "geodata/germany.pmtiles"))
            config.pmtilesPath shouldBe null
            config.invalid shouldContain MemberMapConfig.ENV_PMTILES_PATH
        }

        test("wrong extension .png -> rejected") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "/app/geodata/germany.png"))
            config.pmtilesPath shouldBe null
            config.invalid shouldContain MemberMapConfig.ENV_PMTILES_PATH
        }

        test("embedded NUL byte -> rejected") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "/app/geo\u0000data/germany.pmtiles"))
            config.pmtilesPath shouldBe null
            config.invalid shouldContain MemberMapConfig.ENV_PMTILES_PATH
        }

        test("embedded control character (not at the edge, so trim() cannot strip it) -> rejected") {
            val config = MemberMapConfig.load(envOf(MemberMapConfig.ENV_PMTILES_PATH to "/app/germ\tany.pmtiles"))
            config.pmtilesPath shouldBe null
            config.invalid shouldContain MemberMapConfig.ENV_PMTILES_PATH
        }

        test("notConfigured() -> null path, no invalid entries") {
            val config = MemberMapConfig.notConfigured()
            config.pmtilesPath shouldBe null
            config.invalid.shouldBeEmpty()
        }
    })
