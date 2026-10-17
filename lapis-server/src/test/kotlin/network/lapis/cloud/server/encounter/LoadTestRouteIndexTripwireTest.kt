package network.lapis.cloud.server.encounter

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V1.9.149 -- the load-test tool (`scripts/loadtest`) addresses the encounter RPC by route index (`/rpc/routeEncounterSpaceServiceManager<N>`,
 * N = 0-based position of the method in [network.lapis.cloud.shared.rpc.IEncounterSpaceService]). `lib/routes.json` is that table; this
 * tripwire pins it to the interface source. Reorder or insert a method in the MIDDLE of the interface and this fails, instead of the
 * tool silently calling another method on a real instance. New methods are appended at the END.
 */
class LoadTestRouteIndexTripwireTest :
    FunSpec({
        val interfaceFile = "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IEncounterSpaceService.kt"
        val declared =
            LoadTestFiles
                .read(interfaceFile)
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
                .let { text -> Regex("""suspend\s+fun\s+(\w+)""").findAll(text).map { it.groupValues[1] }.toList() }

        test("routes.json lists exactly the methods of IEncounterSpaceService, in declaration order") {
            val table = Json.parseToJsonElement(LoadTestFiles.read("scripts/loadtest/lib/routes.json")).jsonObject
            table["manager"]!!.jsonPrimitive.content shouldBe "EncounterSpaceServiceManager"
            val methods = table["methods"]!!.jsonObject
            methods.entries.sortedBy { it.value.jsonPrimitive.int }.map { it.key } shouldBe declared
            methods.entries.map { it.value.jsonPrimitive.int }.sorted() shouldBe declared.indices.toList()
        }

        test(
            "every exception class named in exceptions.json exists and is a Kilua service exception; the six the encounter service throws are mapped",
        ) {
            val table = Json.parseToJsonElement(LoadTestFiles.read("scripts/loadtest/lib/exceptions.json")).jsonObject
            for (name in table.keys) {
                val type = Class.forName(name)
                AbstractServiceException::class.java.isAssignableFrom(type) shouldBe true
            }
            table.keys.map { it.substringAfterLast('.') }.toSet() shouldBe
                setOf(
                    "UnauthenticatedException",
                    "ForbiddenException",
                    "NotFoundException",
                    "ServiceBusyException",
                    "ConflictException",
                    "BadRequestException",
                )
        }
    })
