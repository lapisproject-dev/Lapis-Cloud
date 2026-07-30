package network.lapis.cloud.server.federation

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/**
 * A synthetic hostname [FederationIpPinningTest]'s DNS-rebinding-simulation test resolves --
 * looked up ONLY by [RebindingSimulationInetAddressResolverProvider]'s custom resolver (see that
 * class KDoc); every other hostname is passed straight through to the JDK's own builtin resolver,
 * so this test double cannot affect any other test in this module.
 */
internal const val REBINDING_SIMULATION_HOSTNAME = "rebinding-test.lapisproject.invalid"

/**
 * How many times [REBINDING_SIMULATION_HOSTNAME] has been looked up via the installed resolver --
 * used to hand back a DIFFERENT (but still public-looking, non-private) address on every
 * successive lookup, simulating a malicious DNS server answering differently on each query. Reset
 * is deliberately NOT provided -- the counter only ever increases, so "did the address change
 * between call N and call N+1" is well-defined regardless of what other tests in this JVM process
 * have already triggered.
 */
private val rebindingLookupCount = AtomicInteger(0)

/**
 * `java.net.spi.InetAddressResolverProvider` test double (JDK 18+ SPI, see JEP 418) -- registered
 * for the `lapis-server` test source set ONLY via
 * `src/test/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`, never for
 * `main`. Exists so [FederationIpPinningTest] can prove its DNS-rebinding-attack PRECONDITION is
 * real (a hostname resolving to a different address on successive lookups) before proving
 * [requireSafeFederationUrl]/[federationHttpClient]'s pinning mechanism defeats it -- see that
 * test's "T1" group.
 *
 * **Deliberately narrow blast radius**: this resolver only intercepts the single synthetic
 * hostname [REBINDING_SIMULATION_HOSTNAME] (under the reserved `.invalid` TLD, RFC 2606 -- never a
 * real registrable domain). EVERY other hostname -- including every hostname every other test in
 * this module resolves (`example.org`, `this-host-does-not-exist.invalid`,
 * `127.0.0.1.nip.io`, real federation-target hosts, etc.) -- is passed through unchanged to
 * [InetAddressResolverProvider.Configuration.builtinResolver], the JDK's own platform resolver.
 * Installing this test double is therefore observationally transparent for every test that never
 * resolves [REBINDING_SIMULATION_HOSTNAME] -- verified by [FederationHttpClientSsrfTest] (unchanged
 * file, same assertions) staying green with this provider installed.
 */
class RebindingSimulationInetAddressResolverProvider : InetAddressResolverProvider() {
    override fun name(): String = "lapis-cloud-rebinding-simulation-test-double"

    override fun get(configuration: Configuration): InetAddressResolver =
        object : InetAddressResolver {
            override fun lookupByName(
                host: String,
                lookupPolicy: LookupPolicy,
            ): Stream<InetAddress> {
                if (!host.equals(REBINDING_SIMULATION_HOSTNAME, ignoreCase = true)) {
                    return configuration.builtinResolver().lookupByName(host, lookupPolicy)
                }
                // TEST-NET-3 (RFC 5737, 203.0.113.0/24) -- reserved for documentation/examples,
                // but NOT recognized as private/loopback/link-local/site-local/multicast/any-local
                // by java.net.InetAddress's own isXxx() checks, i.e. exactly the class of address
                // that would pass requireSafeFederationUrl's safety check while still being a
                // synthetic, never-actually-routed address for this test.
                val callNumber = rebindingLookupCount.getAndIncrement()
                val lastOctet = 10 + (callNumber % 240)
                val syntheticAddress =
                    InetAddress.getByAddress(
                        host,
                        byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), lastOctet.toByte()),
                    )
                return Stream.of(syntheticAddress)
            }

            override fun lookupByAddress(addr: ByteArray): String =
                configuration.builtinResolver().lookupByAddress(addr)
                    ?: throw UnknownHostException("no reverse-lookup name for the given address")
        }
}
