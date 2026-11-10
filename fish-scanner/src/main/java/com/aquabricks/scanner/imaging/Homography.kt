package com.aquabricks.scanner.imaging

import kotlin.math.abs

internal object Homography {

    /**
     * Solves the 8 unknowns of the projective transform that maps the four
     * `from` points onto the four `to` points. Points are stored as
     * [x0, y0, x1, y1, x2, y2, x3, y3].
     *
     * Returns [a, b, c, d, e, f, g, h] such that
     *   X = (a*x + b*y + c) / (g*x + h*y + 1)
     *   Y = (d*x + e*y + f) / (g*x + h*y + 1)
     */
    fun solve(from: FloatArray, to: FloatArray): FloatArray? {
        require(from.size == 8 && to.size == 8)
        val matrix = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = from[i * 2].toDouble()
            val v = from[i * 2 + 1].toDouble()
            val x = to[i * 2].toDouble()
            val y = to[i * 2 + 1].toDouble()
            matrix[i * 2] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
            matrix[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
        }

        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-12) return null
            if (pivot != column) {
                val temp = matrix[column]
                matrix[column] = matrix[pivot]
                matrix[pivot] = temp
            }
            val divisor = matrix[column][column]
            for (c in column..8) matrix[column][c] /= divisor
            for (row in 0 until 8) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0.0) continue
                for (c in column..8) matrix[row][c] -= factor * matrix[column][c]
            }
        }

        return FloatArray(8) { matrix[it][8].toFloat() }
    }
}
