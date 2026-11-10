package com.aquabricks.scanner.imaging

/** A deliberately tiny ARGB bitmap so all the image maths is pure Kotlin and unit-testable. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "Empty image" }
        require(pixels.size == width * height) { "Pixel buffer does not match size" }
    }

    fun copy(): ArgbImage = ArgbImage(width, height, pixels.copyOf())

    companion object {
        fun alpha(color: Int): Int = (color ushr 24) and 0xFF
        fun red(color: Int): Int = (color ushr 16) and 0xFF
        fun green(color: Int): Int = (color ushr 8) and 0xFF
        fun blue(color: Int): Int = color and 0xFF
        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }
}
