package network.lapis.cloud.shared

/**
 * Minimal shared domain object, used to prove the jvm+js multiplatform
 * wiring works end to end: both [lapis-server] and [lapis-client] depend on
 * this module and consume [Greeting.message].
 */
object Greeting {
    fun message(): String = "Hello from Lapis Cloud"
}
