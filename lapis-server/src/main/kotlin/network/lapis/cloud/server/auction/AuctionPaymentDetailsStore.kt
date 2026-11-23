package network.lapis.cloud.server.auction

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.320 -- the ONE place that touches [AuctionPaymentDetailsTable]. The seller's holder name, IBAN and link are sealed together as one JSON blob
 * ([SecretBox], AES-256-GCM, `aad` = the auction id, so a ciphertext copied to another auction's row does not open). A fresh [SecretBox]/cipher per
 * call. [seal] is pure cryptography (call it BEFORE `transaction {}`); [upsert]/[open]/[delete] must run inside the caller's transaction. Nothing here
 * ever logs or throws a value: a failure is a [PaymentDetailsUnavailableException] with a constant message.
 */
internal class AuctionPaymentDetailsStore(
    private val secretEncryptionKey: ByteArray?,
) {
    /** `false` without `LAPIS_SECRET_ENCRYPTION_KEY` -- then no Fiat auction can be created. */
    val available: Boolean get() = secretEncryptionKey != null

    fun seal(
        auctionId: Uuid,
        details: SellerPaymentDetails,
    ): String {
        val key = secretEncryptionKey ?: throw PaymentDetailsUnavailableException()
        return SecretBox(key).seal(plaintext = details.toSealedJson(), aad = auctionId.toString())
    }

    /** Insert or replace the row of [auctionId]. [sealed] comes from [seal]. */
    fun upsert(
        auctionId: Uuid,
        sealed: String,
        details: SellerPaymentDetails,
        now: LocalDateTime,
    ) {
        val exists = AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq auctionId }.any()
        if (exists) {
            AuctionPaymentDetailsTable.update({ AuctionPaymentDetailsTable.auctionId eq auctionId }) {
                it[detailsCiphertext] = sealed
                it[hasIban] = details.iban != null
                it[hasPaymentLink] = details.paymentLink != null
                it[updatedAt] = now
            }
        } else {
            AuctionPaymentDetailsTable.insert {
                it[AuctionPaymentDetailsTable.auctionId] = auctionId
                it[detailsCiphertext] = sealed
                it[hasIban] = details.iban != null
                it[hasPaymentLink] = details.paymentLink != null
                it[updatedAt] = now
            }
        }
    }

    /** `null` when there is no row (never stored or already deleted); a [PaymentDetailsUnavailableException] when it cannot be opened. */
    fun open(auctionId: Uuid): SellerPaymentDetails? {
        val row =
            AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq auctionId }.singleOrNull() ?: return null
        val key = secretEncryptionKey ?: throw PaymentDetailsUnavailableException()
        return try {
            SellerPaymentDetails.fromSealedJson(
                SecretBox(key).open(sealed = row[AuctionPaymentDetailsTable.detailsCiphertext], aad = auctionId.toString()),
            )
        } catch (e: SecretBoxException) {
            throw PaymentDetailsUnavailableException()
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PaymentDetailsUnavailableException()
        }
    }

    fun hasLink(auctionId: Uuid): Boolean =
        AuctionPaymentDetailsTable
            .selectAll()
            .where { AuctionPaymentDetailsTable.auctionId eq auctionId }
            .singleOrNull()
            ?.get(AuctionPaymentDetailsTable.hasPaymentLink) ?: false

    fun delete(auctionId: Uuid): Int = AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId eq auctionId }
}

/** The sealed payment details cannot be read (no key, wrong key after a restore, tampered row). The message is constant and carries no value. */
internal class PaymentDetailsUnavailableException : RuntimeException("Payment details are currently not available")
