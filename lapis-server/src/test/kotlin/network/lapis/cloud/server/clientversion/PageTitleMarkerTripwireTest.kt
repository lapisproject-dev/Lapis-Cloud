package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.257 -- source tripwires for the browser tab title (the jsTest tests prove the behaviour; Karma has no file system).
 *
 * `document.title` is plain text and not part of the KVision tree. Two rules keep the `###KvI18nS###` marker of `tr()` out of the tab:
 *  1. a constant `tr()` subtitle is never resolved into the tab title (`PageTitle` keeps no `subtitleKey`);
 *  2. the finished string is cleaned as a last guard (`.replace(KV_I18N_MARKER, "")`) after `ConferenceDock.decorateTitle`.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val PAGE_HEADER =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/PageHeader.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/PageHeader.kt") }

class PageTitleMarkerTripwireTest :
    FunSpec({
        test("the tab title never resolves a constant tr() subtitle") {
            val src = PAGE_HEADER.readText()
            src shouldNotContain "subtitleKey"
            src shouldNotContain "subtitleKey?.let { resolvedAttributeText"
        }

        test("the finished tab title is stripped of the marker after the dock decoration") {
            val src = PAGE_HEADER.readText()
            val assignment = src.substringAfter("document.title =").substringBefore("\n    }\n")
            assignment shouldContain "ConferenceDock.decorateTitle("
            assignment shouldContain ".replace(KV_I18N_MARKER, \"\")"
        }
    })
