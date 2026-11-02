package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

class AudioSnifferTest :
    FunSpec({
        test("every accepted container is recognised by its magic bytes") {
            AudioSniffer.sniff(ascii("ID3") + ByteArray(20)) shouldBe SniffedAudio.MP3
            AudioSniffer.sniff(bytes(0xFF, 0xFB, 0x90, 0x00)) shouldBe SniffedAudio.MP3 // MPEG-1 layer III, 128 kbit/s, 44.1 kHz
            AudioSniffer.sniff(ascii("OggS") + ByteArray(8)) shouldBe SniffedAudio.OGG
            AudioSniffer.sniff(ascii("fLaC") + ByteArray(8)) shouldBe SniffedAudio.FLAC
            AudioSniffer.sniff(ascii("RIFF") + ByteArray(4) + ascii("WAVE")) shouldBe SniffedAudio.WAV
            listOf("M4A ", "M4B ", "mp42", "isom", "mp41", "dash").forEach { brand ->
                AudioSniffer.sniff(bytes(0, 0, 0, 0x20) + ascii("ftyp") + ascii(brand)) shouldBe SniffedAudio.M4A
            }
        }

        test("raw ADTS (FFF1 / FFF9) is NOT accepted as MP3") {
            AudioSniffer.sniff(bytes(0xFF, 0xF1, 0x50, 0x80)) shouldBe null
            AudioSniffer.sniff(bytes(0xFF, 0xF9, 0x50, 0x80)) shouldBe null
        }

        test("an M4A with a foreign brand, a RIFF that is not WAVE, text, HTML and a video container are refused") {
            AudioSniffer.sniff(bytes(0, 0, 0, 0x20) + ascii("ftyp") + ascii("qt  ")) shouldBe null
            AudioSniffer.sniff(ascii("RIFF") + ByteArray(4) + ascii("AVI ")) shouldBe null
            AudioSniffer.sniff(ascii("<html><body>x</body></html>")) shouldBe null
            AudioSniffer.sniff(ascii("#EXTM3U\n#EXT-X-VERSION:3")) shouldBe null
            AudioSniffer.sniff(ascii("ffconcat version 1.0")) shouldBe null
            AudioSniffer.sniff(ByteArray(0)) shouldBe null
            AudioSniffer.sniff(bytes(0xFF, 0xFB)) shouldBe null
        }

        test("a bare MPEG frame with reserved bits is refused") {
            AudioSniffer.sniff(bytes(0xFF, 0xF9, 0x90, 0x00)) shouldBe null // layer 00 reserved
            AudioSniffer.sniff(bytes(0xFF, 0xEB, 0x90, 0x00)) shouldBe null // version 01 reserved
            AudioSniffer.sniff(bytes(0xFF, 0xFB, 0xF0, 0x00)) shouldBe null // bitrate index 1111
            AudioSniffer.sniff(bytes(0xFF, 0xFB, 0x9C, 0x00)) shouldBe null // sample rate index 11
        }
    })
