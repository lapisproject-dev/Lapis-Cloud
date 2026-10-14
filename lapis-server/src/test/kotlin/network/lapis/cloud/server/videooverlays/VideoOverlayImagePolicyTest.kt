package network.lapis.cloud.server.videooverlays

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Byte-level PNG builder: signature plus the given chunks, every CRC computed (the parser never looks at it, but the files stay valid). */
private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    for ((type, data) in chunks) {
        out.write(intBytes(data.size))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(data)
            }
        out.write(intBytes(crc.value.toInt()))
    }
    return out.toByteArray()
}

private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

private fun ihdr(colorType: Int): Pair<String, ByteArray> {
    val data = ByteArrayOutputStream()
    data.write(intBytes(4))
    data.write(intBytes(4))
    data.write(byteArrayOf(8, colorType.toByte(), 0, 0, 0))
    return "IHDR" to data.toByteArray()
}

class VideoOverlayImagePolicyTest :
    FunSpec({
        val idat = "IDAT" to ByteArray(4)
        val iend = "IEND" to ByteArray(0)

        test("colour types 4 and 6 carry alpha, 0, 2 and 3 without tRNS do not") {
            VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(6), idat, iend)) shouldBe true
            VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(4), idat, iend)) shouldBe true
            for (type in listOf(0, 2, 3)) VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(type), idat, iend)) shouldBe false
        }

        test(
            "a tRNS chunk before the pixel data makes colour types 0, 2 and 3 transparent; after IDAT it is ignored; empty tRNS is nothing",
        ) {
            val trns = "tRNS" to byteArrayOf(0, 1)
            for (type in listOf(0, 2, 3)) VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(type), trns, idat, iend)) shouldBe true
            VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(3), idat, trns, iend)) shouldBe false
            VideoOverlayImagePolicy.pngHasAlpha(png(ihdr(3), "tRNS" to ByteArray(0), idat, iend)) shouldBe false
        }

        test("truncated chunks, hostile lengths, a wrong signature, a JPEG and tiny input are false and never throw") {
            val good = png(ihdr(6), idat, iend)
            VideoOverlayImagePolicy.pngHasAlpha(good.copyOf(30)) shouldBe false
            VideoOverlayImagePolicy.pngHasAlpha(ByteArray(0)) shouldBe false
            VideoOverlayImagePolicy.pngHasAlpha(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100)) shouldBe false
            val wrongSignature = good.copyOf().also { it[1] = 0x51 }
            VideoOverlayImagePolicy.pngHasAlpha(wrongSignature) shouldBe false
            // Length field of the first chunk claims 2 GB: the walk ends instead of overflowing.
            for (length in listOf(Int.MAX_VALUE, -1, 0x7FFFFFF0, 1000)) {
                val hostile = good.copyOf()
                intBytes(length).copyInto(hostile, destinationOffset = 8)
                VideoOverlayImagePolicy.pngHasAlpha(hostile) shouldBe false
            }
        }

        test("a PNG decoded from real pixels: only a pixel that is not fully opaque counts as transparency") {
            VideoOverlayImagePolicy.hasTransparentPixel(VideoOverlayTestImages.framePng(width = 64, height = 64)) shouldBe true
            VideoOverlayImagePolicy.hasTransparentPixel(VideoOverlayTestImages.opaqueArgbPng(width = 64, height = 64)) shouldBe false
            VideoOverlayImagePolicy.hasTransparentPixel(VideoOverlayTestImages.rgbPng(width = 64, height = 64)) shouldBe false
            VideoOverlayImagePolicy.hasTransparentPixel("not an image".toByteArray()) shouldBe false
        }
    })
