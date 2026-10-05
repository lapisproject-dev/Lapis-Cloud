package network.lapis.cloud.server.payment.psp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.uuid.Uuid

class PspIdempotencyKeysTest :
    FunSpec({
        test("the same checkout session id always yields the same key; another id another key") {
            val a = Uuid.random().toString()
            val b = Uuid.random().toString()
            PspIdempotencyKeys.checkout(a) shouldBe PspIdempotencyKeys.checkout(a)
            PspIdempotencyKeys.checkout(a) shouldNotBe PspIdempotencyKeys.checkout(b)
        }

        test("the key is a versioned prefix plus the canonical UUID and short enough for both providers") {
            val id = Uuid.random()
            PspIdempotencyKeys.checkout(id.toString()) shouldBe "lapis-checkout-v1-$id"
            PspIdempotencyKeys.checkout(id.toString()).length shouldBeLessThan 255
        }

        test("a non-UUID value can never reach the header") {
            shouldThrow<IllegalArgumentException> { PspIdempotencyKeys.checkout("x\r\nInjected: header") }
        }
    })
