package network.lapis.cloud.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

class NumberTokenAssertionsTest :
    FunSpec({
        test("digits glued into a UUID or other identifier do not count") {
            "id=3f2a3000-1500-4b7e-9c11-0a1b2c3d4e5f".shouldNotContainNumber("3000")
            "id=3f2a3000-1500-4b7e-9c11-0a1b2c3d4e5f".shouldNotContainNumber("1500")
            "ref a4242b".shouldNotContainNumber("4242")
        }

        test("a standalone number is found, also as an amount or inside markup") {
            shouldThrow<AssertionError> { "total 4242.42 EUR".shouldNotContainNumber("4242") }
            shouldThrow<AssertionError> { "<td>3000</td>".shouldNotContainNumber("3000") }
            shouldThrow<AssertionError> { "{\"sum\":1500}".shouldNotContainNumber("1500") }
        }
    })
