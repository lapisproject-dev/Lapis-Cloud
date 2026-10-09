package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MailDeliveryStatusDto

/**
 * Welle V1.9.81 -- ADMIN-only health card of the mail pipeline (hourly budget, durable queue, failures by purpose). The server enforces
 * the role as the FIRST statement. The result carries counts and class-A timestamps only -- never an address, a subject, or a row.
 */
@RpcService
interface IMailDeliveryStatusService {
    suspend fun getMailDeliveryStatus(): MailDeliveryStatusDto
}
