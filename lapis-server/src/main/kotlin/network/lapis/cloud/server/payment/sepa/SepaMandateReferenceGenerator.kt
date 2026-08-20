package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.LocalDate
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * V1.2.2 "SEPA-Lastschriftmandate". Format: `LC-<8 hex from memberId>-<yyyyMMdd>-<4 hex random>`,
 * e.g. `LC-3F2A9C41-20260819-7B1E`. 25 characters (limit 35), exclusively `A-Z0-9-` (SEPA character
 * set).
 *
 * **No sequential number** -- an organization's member count must not be derivable from a mandate
 * reference (same enumeration-hardening reasoning as `MemberService.listMembers`). **No personal
 * plaintext** -- this is why the reference alone may appear as a mandate's identifying feature in an
 * audit snapshot ([network.lapis.cloud.shared.domain.SepaMandateSnapshot]).
 *
 * Randomness from [SecureRandom] (a shared, thread-safe instance -- same convention as
 * `SecretBox`/session tokens), never `kotlin.random.Random`.
 */
object SepaMandateReferenceGenerator {
    private val sharedSecureRandom = SecureRandom()
    private val WELL_FORMED = Regex("""^LC-[0-9A-F]{8}-\d{8}-[0-9A-F]{4}$""")

    fun generate(
        memberId: Uuid,
        signatureDate: LocalDate,
        random: SecureRandom = sharedSecureRandom,
    ): String {
        val memberHex =
            memberId
                .toString()
                .replace("-", "")
                .uppercase()
                .take(8)
        val datePart = signatureDate.toString().replace("-", "")
        val randomBytes = ByteArray(2)
        random.nextBytes(randomBytes)
        val randomHex = randomBytes.joinToString("") { "%02X".format(it) }
        return "LC-$memberHex-$datePart-$randomHex"
    }

    fun isWellFormed(reference: String): Boolean = WELL_FORMED.matches(reference)
}
