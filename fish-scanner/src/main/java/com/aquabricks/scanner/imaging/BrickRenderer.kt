package com.aquabricks.scanner.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns detected stud cells into a clean LEGO-style sprite instead of copying the
 * photo texture. The silhouette comes from cell occupancy; every occupied cell is
 * painted as a brick face with a bevel and a round stud.
 */
object BrickRenderer {

    /**
     * Keeps the exact scanned silhouette (including small tails/fins) but replaces
     * the photo texture with quantised brick faces and raised studs. Used when the
     * object is a smooth LEGO plate/tile with no visible stud pitch.
     */
    fun renderSilhouette(
        source: ArgbImage,
        columns: Int,
        rows: Int,
        colourMapper: (Int) -> Int = { it }
    ): ArgbImage {
        val cols = columns.coerceIn(2, 40)
        val rowCount = rows.coerceIn(2, 40)
        val width = source.width
        val height = source.height
        val cellWidth = width.toFloat() / cols
        val cellHeight = height.toFloat() / rowCount
        // A 3x3 majority pass twice removes the one/two-pixel paper fringe that
        // makes a cutout look "cropped by a monkey"; the body and a real tail tab
        // are several pixels thick and survive.
        var mask = BooleanArray(width * height) {
            ArgbImage.alpha(source.pixels[it]) > 60
        }
        repeat(2) { mask = smoothMask(mask, width, height) }
        val red = DoubleArray(cols * rowCount)
        val green = DoubleArray(cols * rowCount)
        val blue = DoubleArray(cols * rowCount)
        val counts = IntArray(cols * rowCount)
        val cellArea = max(1f, cellWidth * cellHeight)
        for (y in 0 until height) {
            val cy = min(rowCount - 1, (y / cellHeight).toInt())
            for (x in 0 until width) {
                val sourceIndex = y * width + x
                if (!mask[sourceIndex]) continue
                val cx = min(cols - 1, (x / cellWidth).toInt())
                val index = cy * cols + cx
                red[index] += ArgbImage.red(source.pixels[sourceIndex])
                green[index] += ArgbImage.green(source.pixels[sourceIndex])
                blue[index] += ArgbImage.blue(source.pixels[sourceIndex])
                counts[index]++
            }
        }
        val colours = IntArray(cols * rowCount) { index ->
            val count = counts[index]
            if (count == 0) {
                0
            } else {
                colourMapper(
                    ArgbImage.argb(
                        255,
                        (red[index] / count).roundToInt().coerceIn(0, 255),
                        (green[index] / count).roundToInt().coerceIn(0, 255),
                        (blue[index] / count).roundToInt().coerceIn(0, 255)
                    )
                )
            }
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val cy = min(rowCount - 1, (y / cellHeight).toInt())
            for (x in 0 until width) {
                val index = y * width + x
                if (!mask[index]) continue
                val cx = min(cols - 1, (x / cellWidth).toInt())
                val colour = colours[cy * cols + cx]
                if (colour != 0) pixels[index] = colour
            }
        }

        // A dark silhouette outline keeps the tile readable on the dark tank.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (pixels[index] == 0) continue
                val edge = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                    pixels[index - 1] == 0 || pixels[index + 1] == 0 ||
                    pixels[index - width] == 0 || pixels[index + width] == 0
                if (edge) pixels[index] = outline(pixels[index])
            }
        }

        // Studs use the detected/estimated cell centres; they are clipped back to
        // the exact silhouette afterwards.
        for (cy in 0 until rowCount) {
            for (cx in 0 until cols) {
                val index = cy * cols + cx
                if (counts[index] * 4 < cellArea) continue
                val colour = colours[index]
                if (colour == 0) continue
                drawStud(
                    pixels, width, height,
                    (cx + 0.5f) * cellWidth,
                    (cy + 0.5f) * cellHeight,
                    min(cellWidth, cellHeight) * 0.29f,
                    colour
                )
            }
        }
        drawBrickJoints(
            pixels, width, height, cols, rowCount,
            cellWidth, cellHeight
        ) { cx, cy ->
            val index = cy * cols + cx
            counts[index] * 4 >= cellArea && colours[index] != 0
        }
        for (index in pixels.indices) {
            if (!mask[index]) pixels[index] = 0
        }
        return ArgbImage(width, height, pixels)
    }

    private fun smoothMask(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var neighbours = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        if (mask[ny * width + nx]) neighbours++
                    }
                }
                val index = y * width + x
                out[index] = if (mask[index]) neighbours >= 3 else neighbours >= 6
            }
        }
        return out
    }

    fun render(cells: ArgbImage): ArgbImage {
        val cols = cells.width.coerceAtLeast(1)
        val rows = cells.height.coerceAtLeast(1)
        val cellSize = min(40, 900 / max(cols, rows)).coerceAtLeast(10)
        val width = cols * cellSize
        val height = rows * cellSize
        val pixels = IntArray(width * height)

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val source = cells.pixels[cy * cols + cx]
                if (ArgbImage.alpha(source) <= 0) continue
                val color = ArgbImage.argb(255, ArgbImage.red(source), ArgbImage.green(source), ArgbImage.blue(source))
                val left = cx * cellSize
                val top = cy * cellSize
                for (y in 0 until cellSize) {
                    for (x in 0 until cellSize) {
                        pixels[(top + y) * width + left + x] = color
                    }
                }
            }
        }

        // Outer bevel and a crisp dark silhouette edge.
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val source = cells.pixels[cy * cols + cx]
                if (ArgbImage.alpha(source) <= 0) continue
                val color = ArgbImage.argb(255, ArgbImage.red(source), ArgbImage.green(source), ArgbImage.blue(source))
                val left = cx * cellSize
                val top = cy * cellSize
                val right = left + cellSize - 1
                val bottom = top + cellSize - 1
                val openTop = cy == 0 || ArgbImage.alpha(cells.pixels[(cy - 1) * cols + cx]) <= 0
                val openBottom = cy == rows - 1 || ArgbImage.alpha(cells.pixels[(cy + 1) * cols + cx]) <= 0
                val openLeft = cx == 0 || ArgbImage.alpha(cells.pixels[cy * cols + cx - 1]) <= 0
                val openRight = cx == cols - 1 || ArgbImage.alpha(cells.pixels[cy * cols + cx + 1]) <= 0
                if (openTop) {
                    fillRow(pixels, width, top, left, left + cellSize, shade(color, 1.25f))
                    fillRow(pixels, width, top + 1, left, left + cellSize, shade(color, 1.12f))
                    fillRow(pixels, width, top, left, left + 2, outline(color))
                }
                if (openLeft) {
                    fillColumn(pixels, width, left, top, top + cellSize, shade(color, 1.15f))
                    fillColumn(pixels, width, left + 1, top, top + cellSize, shade(color, 1.08f))
                    fillColumn(pixels, width, left, top, top + 2, outline(color))
                }
                if (openBottom) {
                    fillRow(pixels, width, bottom, left, left + cellSize, shade(color, 0.68f))
                    fillRow(pixels, width, bottom - 1, left, left + cellSize, shade(color, 0.82f))
                }
                if (openRight) {
                    fillColumn(pixels, width, right, top, top + cellSize, shade(color, 0.72f))
                    fillColumn(pixels, width, right - 1, top, top + cellSize, shade(color, 0.86f))
                }
                if (openTop || openLeft) {
                    pixels[top * width + left] = outline(color)
                }
                if (openTop || openRight) {
                    pixels[top * width + right] = outline(color)
                }
                if (openBottom || openLeft) {
                    pixels[bottom * width + left] = outline(color)
                }
                if (openBottom || openRight) {
                    pixels[bottom * width + right] = outline(color)
                }
            }
        }

        // One raised round stud per cell.
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val source = cells.pixels[cy * cols + cx]
                if (ArgbImage.alpha(source) <= 0) continue
                val color = ArgbImage.argb(255, ArgbImage.red(source), ArgbImage.green(source), ArgbImage.blue(source))
                drawStud(
                    pixels, width, height,
                    cx * cellSize + cellSize * 0.5f,
                    cy * cellSize + cellSize * 0.5f,
                    cellSize * 0.29f,
                    color
                )
            }
        }
        drawBrickJoints(
            pixels, width, height, cols, rows,
            cellSize.toFloat(), cellSize.toFloat()
        ) { cx, cy -> ArgbImage.alpha(cells.pixels[cy * cols + cx]) > 0 }
        return ArgbImage(width, height, pixels)
    }

    /**
     * A one-pixel dark joint along every shared edge makes the sprite read as a
     * LEGO build even after the TV scales it down. Outer edges keep their bevel.
     */
    private fun drawBrickJoints(
        pixels: IntArray,
        width: Int,
        height: Int,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
        occupied: (Int, Int) -> Boolean
    ) {
        for (cy in 0 until rows) {
            for (cx in 0 until columns) {
                if (!occupied(cx, cy)) continue
                if (cx + 1 < columns && occupied(cx + 1, cy)) {
                    val x = (((cx + 1) * cellWidth).toInt() - 1).coerceIn(0, width - 1)
                    val top = (cy * cellHeight).toInt().coerceIn(0, height - 1)
                    val bottom = ((cy + 1) * cellHeight).toInt().coerceIn(0, height)
                    for (y in top until bottom) pixels[y * width + x] = BRICK_JOINT
                }
                if (cy + 1 < rows && occupied(cx, cy + 1)) {
                    val y = (((cy + 1) * cellHeight).toInt() - 1).coerceIn(0, height - 1)
                    val left = (cx * cellWidth).toInt().coerceIn(0, width - 1)
                    val right = ((cx + 1) * cellWidth).toInt().coerceIn(0, width)
                    for (x in left until right) pixels[y * width + x] = BRICK_JOINT
                }
            }
        }
    }

    private fun drawStud(
        pixels: IntArray,
        width: Int,
        height: Int,
        centreX: Float,
        centreY: Float,
        radius: Float,
        color: Int
    ) {
        val left = (centreX - radius - 1).toInt().coerceAtLeast(0)
        val top = (centreY - radius - 1).toInt().coerceAtLeast(0)
        val right = (centreX + radius + 1).toInt().coerceAtMost(width - 1)
        val bottom = (centreY + radius + 1).toInt().coerceAtMost(height - 1)
        val radiusSquared = radius * radius
        val ringSquared = (radius - 1.2f).let { it * it }
        val highlightX = centreX - radius * 0.32f
        val highlightY = centreY - radius * 0.32f
        val highlightRadius = radius * 0.38f
        val highlightSquared = highlightRadius * highlightRadius
        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x + 0.5f - centreX
                val dy = y + 0.5f - centreY
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared > radiusSquared) continue
                val stud = if (distanceSquared >= ringSquared) {
                    shade(color, 0.62f)
                } else {
                    shade(color, 1.16f)
                }
                pixels[y * width + x] = stud
                val hx = x + 0.5f - highlightX
                val hy = y + 0.5f - highlightY
                if (hx * hx + hy * hy <= highlightSquared) {
                    pixels[y * width + x] = shade(color, 1.42f)
                }
            }
        }
    }

    private fun fillRow(pixels: IntArray, width: Int, y: Int, left: Int, right: Int, color: Int) {
        if (y < 0 || y >= pixels.size / width) return
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
            pixels[y * width + x] = color
        }
    }

    private fun fillColumn(pixels: IntArray, width: Int, x: Int, top: Int, bottom: Int, color: Int) {
        if (x < 0 || x >= width) return
        val height = pixels.size / width
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            pixels[y * width + x] = color
        }
    }

    private fun outline(color: Int): Int {
        return shade(color, 0.48f)
    }

    private fun shade(color: Int, factor: Float): Int {
        return ArgbImage.argb(
            255,
            (ArgbImage.red(color) * factor).roundToInt().coerceIn(0, 255),
            (ArgbImage.green(color) * factor).roundToInt().coerceIn(0, 255),
            (ArgbImage.blue(color) * factor).roundToInt().coerceIn(0, 255)
        )
    }

    private const val BRICK_JOINT = 0xFF101417.toInt()
}
