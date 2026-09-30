package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.RpcSerialization
import dev.kilua.rpc.registerRpcServiceExceptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.ProbeCodedConflictException
import java.io.File

/**
 * V1.9.17: does a NON-message field of an exception cross the Kilua RPC wire? Server side of the proof (the decoding side runs in
 * the browser: `RpcExceptionCodeWireTest`). The exception is encoded exactly like Kilua RPC's JSON-RPC handler does for an
 * [AbstractServiceException] (`RpcSerialization.getJson()`, open polymorphism, flat `type` discriminator), then decoded again.
 *
 * The result is pinned as assertions on the ACTUAL behaviour, so a Kilua RPC upgrade that changes it fails here and the ADR
 * (`docs/architecture/typed-conflict-errors.adoc`) gets revisited.
 */
class CodedExceptionWireTest :
    FunSpec({
        beforeSpec { registerRpcServiceExceptions() }

        test("the subclass discriminator crosses the wire: the probe comes back as its own type") {
            val json = RpcSerialization.getJson()
            val encoded = json.encodeToString<AbstractServiceException>(ProbeCodedConflictException(message = "m", code = "IBAN_TAKEN"))
            val decoded = json.decodeFromString<AbstractServiceException>(encoded)
            (decoded is ProbeCodedConflictException) shouldBe true
        }

        test("a non-message constructor field (the code) is part of the encoded exception and comes back") {
            val json = RpcSerialization.getJson()
            val encoded = json.encodeToString<AbstractServiceException>(ProbeCodedConflictException(message = "m", code = "IBAN_TAKEN"))
            encoded shouldBe
                """{"type":"network.lapis.cloud.shared.rpc.ProbeCodedConflictException","message":"m","code":"IBAN_TAKEN"}"""
            (json.decodeFromString<AbstractServiceException>(encoded) as ProbeCodedConflictException).code shouldBe "IBAN_TAKEN"
        }

        test(
            "a field equal to its constructor default is NOT encoded (encodeDefaults = false): why default-message exceptions arrive without a message",
        ) {
            val json = RpcSerialization.getJson()
            val encoded = json.encodeToString<AbstractServiceException>(ProbeCodedConflictException())
            encoded shouldBe """{"type":"network.lapis.cloud.shared.rpc.ProbeCodedConflictException"}"""
            // ... and it comes back with the same defaults, i.e. the receiving side only ever sees what differs from the default.
            (json.decodeFromString<AbstractServiceException>(encoded) as ProbeCodedConflictException).code shouldBe ""
        }

        test("production code never throws the probe") {
            val main = File("../lapis-server/src/main").let { if (it.exists()) it else File("lapis-server/src/main") }
            val offenders =
                main
                    .walkTopDown()
                    .filter {
                        it.isFile && it.extension == "kt" && it.readText().contains("ProbeCodedConflictException")
                    }.map { it.name }
                    .toList()
            offenders shouldBe emptyList()
        }
    })
