package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import network.lapis.cloud.shared.domain.StandardAssociationChart
import java.io.File

/**
 * V1.9.206: the "typical area" hints of the standard chart live in the shared module and reach the screen through `tr(hint)`, a call the
 * catalog scan ([AllClientMessagesCatalogTest]) cannot see (its argument is no literal). This pins that every hint is a msgid of the
 * template and of all seven catalogs with a translation, and keeps its `%N` placeholders (there are none).
 */
class StandardChartHintCatalogTest :
    FunSpec({
        test("every sphere hint of the standard chart is translated in all catalogs") {
            val hints = StandardAssociationChart.accounts.map { it.sphereHint }.distinct()
            val template = parseCatalog(File(CATALOG_DIR, "messages.pot"))
            val missing =
                hints.flatMap { hint ->
                    buildList {
                        if (!template.containsKey(hint)) add("pot: \"$hint\"")
                        CATALOG_LANGUAGES.forEach { lang ->
                            val translation = parseCatalog(File(CATALOG_DIR, "messages-$lang.po"))[hint]
                            if (translation.isNullOrBlank()) add("$lang: \"$hint\"")
                        }
                    }
                }
            missing.shouldBeEmpty()
        }
    })
