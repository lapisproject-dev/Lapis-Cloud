package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private val CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

/**
 * V1.9.164 -- the reaction popover of the video conference carries eight entries, so on a low landscape phone it has to be height-limited
 * and scroll (with a `100vh` fallback before the `100dvh` value). The DOM tests cannot measure geometry; this keeps the rule from vanishing.
 */
class ClientConferenceReactionMenuCssTest :
    FunSpec({
        test("the reaction popover is height-limited, scrolls and has a vh fallback before the dvh value") {
            val rule = Regex("""\.lapis-conference-reaction-popover\s*\{([^}]*)}""").find(CSS.readText())!!.groupValues[1]
            rule shouldContain "overflow-y: auto"
            val heights = Regex("""max-height:\s*calc\(100(d?vh)""").findAll(rule).map { it.groupValues[1] }.toList()
            heights shouldBe listOf("vh", "dvh")
        }
    })
