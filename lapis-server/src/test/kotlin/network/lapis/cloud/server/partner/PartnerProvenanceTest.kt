package network.lapis.cloud.server.partner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PartnerProvenanceTest :
    FunSpec({
        test("an empty chain is null, a chain round-trips") {
            PartnerProvenance.encode(emptyList()).shouldBeNull()
            PartnerProvenance.decode(null).shouldBeEmpty()
            val via = listOf("a.beispiel.de", "b.beispiel.de", "c.beispiel.de")
            PartnerProvenance.decode(PartnerProvenance.encode(via)) shouldBe via
        }

        test("more than three hosts are refused on write") {
            shouldThrow<IllegalArgumentException> { PartnerProvenance.encode(listOf("a.de", "b.de", "c.de", "d.de")) }
        }

        test("three maximal hosts fit into 800 characters") {
            val long = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(55) + ".de"
            (long.length <= 253) shouldBe true
            PartnerProvenance.encode(listOf(long, long, long))!!.length shouldBe long.length * 3 + 2
        }

        test("invalid hosts in a stored chain are dropped on read") {
            PartnerProvenance.decode("a.beispiel.de 127.0.0.1 localhost <b>.de b.beispiel.de") shouldBe
                listOf("a.beispiel.de", "b.beispiel.de")
            PartnerProvenance.decode("x".repeat(801)).shouldBeEmpty()
        }
    })
