package com.aquabricks.scanner.imaging

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** ARGB -> PNG writer for visual test artefacts (no java.desktop required). */
object TestPng {

    fun write(image: ArgbImage, file: File) = writePixels(image.width, image.height, image.pixels, file)

    fun writePixels(width: Int, height: Int, pixels: IntArray, file: File) {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            val header = ByteArray(13)
            writeInt(header, 0, width)
            writeInt(header, 4, height)
            header[8] = 8
            header[9] = 6
            chunk(out, "IHDR", header)

            val raw = ByteArray((width * 4 + 1) * height)
            var cursor = 0
            for (y in 0 until height) {
                raw[cursor++] = 0
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    raw[cursor++] = ((pixel shr 16) and 0xFF).toByte()
                    raw[cursor++] = ((pixel shr 8) and 0xFF).toByte()
                    raw[cursor++] = ArgbImage.blue(pixel).toByte()
                    raw[cursor++] = ((pixel ushr 24) and 0xFF).toByte()
                }
            }
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(raw)
            deflater.finish()
            val compressed = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                compressed.write(buffer, 0, count)
            }
            deflater.end()
            chunk(out, "IDAT", compressed.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun chunk(out: FileOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: FileOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
