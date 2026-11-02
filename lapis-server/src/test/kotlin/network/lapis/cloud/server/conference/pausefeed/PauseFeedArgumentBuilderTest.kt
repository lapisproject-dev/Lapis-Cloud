package network.lapis.cloud.server.conference.pausefeed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

private val IMAGE = File("/data/pause.png")
private val MUSIC = File("/data/pause.m4a")
private val TLS = listOf("-tls_verify", "1", "-ca_file", "/etc/ssl/certs/ca-certificates.crt")

class PauseFeedArgumentBuilderTest :
    FunSpec({
        test("one target: a plain flv output, music looped, file inputs restricted to the file protocol") {
            val cmd =
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = MUSIC,
                    targetUrls = listOf("rtmp://a.example.org/live/SECRETKEY1"),
                    tlsVerifyArgs = TLS,
                )
            val a = cmd.args
            a.takeLast(3) shouldBe listOf("-f", "flv", "rtmp://a.example.org/live/SECRETKEY1")
            a shouldContain "-stream_loop"
            a shouldNotContain "anullsrc=r=44100:cl=stereo"
            a.count { it == "-protocol_whitelist" } shouldBe 2
            a.indices.filter { a[it] == "-protocol_whitelist" }.forEach { a[it + 1] shouldBe "file" }
            a[a.indexOf("-t") + 1] shouldBe PauseFeedArgumentBuilder.HARD_CAP_SECONDS.toString()
            a shouldContain "-progress"
            // a plain rtmp target gets no TLS option
            a shouldNotContain "-tls_verify"
        }

        test("without music the silence source is used") {
            val cmd =
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = null,
                    targetUrls = listOf("rtmp://a.example.org/live/SECRETKEY1"),
                    tlsVerifyArgs = TLS,
                )
            cmd.args shouldContain "anullsrc=r=44100:cl=stereo"
            cmd.args shouldNotContain "-stream_loop"
        }

        test("an rtmps target gets verification and the CA file; the output option precedes the format") {
            val cmd =
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = null,
                    targetUrls = listOf("rtmps://a.example.org/live/SECRETKEY1"),
                    tlsVerifyArgs = TLS,
                )
            val a = cmd.args
            val at = a.indexOf("-tls_verify")
            (at > 0) shouldBe true
            a.subList(at, at + 4) shouldBe TLS
            (at < a.lastIndexOf("-f")) shouldBe true
        }

        test("several targets go through tee with onfail=ignore, TLS options only on rtmps slaves") {
            val cmd =
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = MUSIC,
                    targetUrls = listOf("rtmp://a.example.org/live/KEYAAAA1", "rtmps://b.example.org/live/KEYBBBB2"),
                    tlsVerifyArgs = TLS,
                )
            cmd.args.takeLast(2).first() shouldBe "tee"
            val slaves = cmd.args.last()
            slaves shouldBe
                "[f=flv:onfail=ignore]rtmp://a.example.org/live/KEYAAAA1|" +
                "[f=flv:onfail=ignore:tls_verify=1:ca_file=/etc/ssl/certs/ca-certificates.crt]rtmps://b.example.org/live/KEYBBBB2"
        }

        test("no target is a programming error, and the TLS arguments are validated") {
            shouldThrow<IllegalArgumentException> {
                PauseFeedArgumentBuilder.build(imagePng = IMAGE, musicM4a = null, targetUrls = emptyList(), tlsVerifyArgs = TLS)
            }
            shouldThrow<IllegalArgumentException> {
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = null,
                    targetUrls = listOf("rtmps://a.example.org/l/k"),
                    tlsVerifyArgs = listOf("-tls_verify", "0", "-ca_file", "/x"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = null,
                    targetUrls = listOf("rtmps://a.example.org/l/k"),
                    tlsVerifyArgs = listOf("-tls_verify", "1", "-ca_file", "/x:y|z"),
                )
            }
        }

        test("toString and sanitize never show a key or a plaintext URL, also for keys inside other text and for unknown URLs") {
            val url1 = "rtmp://a.example.org/live/SECRETKEY1"
            val url2 = "rtmps://b.example.org/live/OTHERKEY22"
            val cmd =
                PauseFeedArgumentBuilder.build(
                    imagePng = IMAGE,
                    musicM4a = null,
                    targetUrls = listOf(url1, url2),
                    tlsVerifyArgs = TLS,
                )
            cmd.toString() shouldNotContain "SECRETKEY1"
            cmd.toString() shouldNotContain "a.example.org"
            val lines =
                listOf(
                    "Error opening output $url1: Input/output error",
                    "connect to rtmps://b.example.org/live/OTHERKEY22 failed",
                    "tee: slave 'SECRETKEY1' broke",
                    "unknown rtmp://zzz.example.net:1935/app/UNKNOWNKEY99 refused",
                    "RTMPS://UPPER.example.net/app/k",
                )
            lines.forEach { line ->
                val clean = cmd.sanitize(line)
                clean shouldNotContain "SECRETKEY1"
                clean shouldNotContain "OTHERKEY22"
                clean shouldNotContain "UNKNOWNKEY99"
                clean shouldNotContain "example"
            }
            cmd.sanitize("no secret here") shouldBe "no secret here"
        }
    })
