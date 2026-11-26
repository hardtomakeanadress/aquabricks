package com.aquabricks.scanner.imaging

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a photo of a flat brick fish into a transparent sprite.
 *
 * Pipeline: perspective-correct the four-corner crop, flood the plain background
 * away from the border, trim, estimate the real stud pitch, sample one flat colour
 * per stud (ignoring the central stud highlight and brick gaps), and optionally snap
 * that colour to the brick palette. All maths is pure Kotlin on an IntArray, which
 * keeps it fast enough for a live preview and unit-testable.
 */
object FishImageProcessor {

    data class Options(
        val tolerance: Int = 14,
        /** -1 = auto-detect the stud grid, 0 = natural resolution, >=4 = manual columns. */
        val gridCols: Int = -1,
        val snapBrickColours: Boolean = true,
        val maxOut: Int = 900
    )

    data class Processed(
        val image: ArgbImage,
        val gridCols: Int,
        val gridRows: Int,
        val usedGrid: Boolean,
        val autoGrid: Boolean = false,
        val brickized: Boolean = false
    )

    data class GridEstimate(
        val columns: Int,
        val rows: Int,
        val pitchX: Float,
        val pitchY: Float,
        val originX: Float = 0f,
        val originY: Float = 0f
    )

    class NoFishFoundException : Exception("No fish found")

    fun process(source: ArgbImage, quad: FloatArray, options: Options): Processed {
        val warped = warp(source, quad, options.maxOut)
        val cut = removeBackground(warped, options.tolerance)
        val tight = trimTransparent(cut, 0) ?: throw NoFishFoundException()
        if (options.gridCols != 0) {
            if (options.gridCols < 0) {
                val estimate = detectGrid(tight)
                if (estimate == null) {
                    // No visible stud pitch: keep the exact silhouette (including
                    // small tails) but replace the photo with a brick-stud mosaic.
                    return brickFallback(tight, options.snapBrickColours)
                }
                val grid = resampleGrid(tight, estimate, options.snapBrickColours)
                val gridTrimmed = trimTransparent(grid, 0) ?: throw NoFishFoundException()
                val bricks = BrickRenderer.render(gridTrimmed)
                return Processed(bricks, gridTrimmed.width, gridTrimmed.height, true, true, true)
            }
            val cols = options.gridCols.coerceIn(4, 40)
            val rows = max(1, (cols * tight.height / tight.width.toFloat()).roundToInt()).coerceAtMost(48)
            val grid = resampleGrid(tight, cols, rows, options.snapBrickColours)
            val gridTrimmed = trimTransparent(grid, 0) ?: throw NoFishFoundException()
            val bricks = BrickRenderer.render(gridTrimmed)
            return Processed(bricks, gridTrimmed.width, gridTrimmed.height, true, false, true)
        }
        return naturalResult(cut)
    }

    private fun naturalResult(cut: ArgbImage): Processed {
        val padded = trimTransparent(cut, 2) ?: throw NoFishFoundException()
        val natural = if (max(padded.width, padded.height) > 720) scaleToMax(padded, 720) else padded
        return Processed(natural, 0, 0, false, false)
    }

    private fun brickFallback(tight: ArgbImage, snapBrickColours: Boolean): Processed {
        val aspect = tight.width.toFloat() / tight.height.coerceAtLeast(1)
        val shortSide = 2
        val cols: Int
        val rows: Int
        if (aspect >= 1f) {
            rows = shortSide
            cols = (shortSide * aspect).roundToInt().coerceIn(2, 40)
        } else {
            cols = shortSide
            rows = (shortSide / aspect).roundToInt().coerceIn(2, 40)
        }
        // Both paths must obey "Match brick colours"; the smooth-tile fallback
        // used to ignore the checkbox entirely.
        val bricks = BrickRenderer.renderSilhouette(tight, cols, rows) { colour ->
            if (snapBrickColours) snapToBrickPalette(colour) else colour
        }
        if (bricks.pixels.none { ArgbImage.alpha(it) > 0 }) {
            throw NoFishFoundException()
        }
        return Processed(bricks, cols, rows, false, false, true)
    }

    /**
     * Estimates the brick stud pitch from the repeating edges of studs/brick gaps.
     * The smallest strong autocorrelation peak is the fundamental stud period; using
     * the harmonic instead would double the stud count and distort brick size.
     */
    fun detectGrid(source: ArgbImage): GridEstimate? {
        if (source.width < 24 || source.height < 24) return null
        val rawX = detectPitch(source, horizontal = true)
        // Once a horizontal pitch is known, search the vertical axis near it. This
        // prevents the large silhouette period (or a sparse autocorrelation peak)
        // from selecting a one-row grid.
        val rawY = detectPitch(source, horizontal = false, near = rawX)
        val pitches = reconcilePitches(source.width, source.height, rawX, rawY)
            ?: return null
        val px = pitches.first
        val py = pitches.second
        val originX = latticePhase(source, horizontal = true, pitch = px)
        val originY = latticePhase(source, horizontal = false, pitch = py)
        val columns = ((source.width - originX) / px).roundToInt().coerceIn(2, 40)
        val rows = ((source.height - originY) / py).roundToInt().coerceIn(1, 48)
        if (columns * rows < 6) return null
        return GridEstimate(columns, rows, px, py, originX, originY)
    }

    /**
     * Reconciles the two axis estimates. A brick stud grid is square, so if one
     * axis locked onto a silhouette and the other did not, both grid dimensions
     * must use the supported pitch.
     */
    internal fun reconcilePitches(
        width: Int,
        height: Int,
        rawX: Float?,
        rawY: Float?
    ): Pair<Float, Float>? {
        var px = rawX ?: rawY ?: return null
        var py = rawY ?: px
        if (rawX != null && rawY != null) {
            val ratio = py / px
            if (ratio !in 0.55f..1.8f) {
                val xSupported = plausibleGrid(width, height, px)
                val ySupported = plausibleGrid(width, height, py)
                when {
                    ySupported && !xSupported -> px = py
                    xSupported -> py = px
                    ySupported -> px = py
                    else -> {
                        val fallback = min(px, py)
                        px = fallback
                        py = fallback
                    }
                }
            }
        }
        return max(4f, px) to max(4f, py)
    }

    /** Finds the comb offset where stud/brick edges best line up with the pitch. */
    private fun latticePhase(source: ArgbImage, horizontal: Boolean, pitch: Float): Float {
        val period = pitch.roundToInt().coerceAtLeast(2)
        val profile = gradientProfile(source, horizontal)
        var bestOffset = 0
        var bestScore = -1f
        for (offset in 0 until period) {
            var score = 0f
            var samples = 0
            var index = offset
            while (index < profile.size) {
                score += profile[index]
                samples++
                index += period
            }
            if (samples >= 2) {
                score /= samples
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = offset
                }
            }
        }
        return bestOffset.toFloat()
    }

    private fun plausibleGrid(width: Int, height: Int, pitch: Float): Boolean {
        if (pitch < 4f) return false
        val columns = width / pitch
        val rows = height / pitch
        return columns >= 4f && rows >= 3f
    }

    private fun detectPitch(
        source: ArgbImage,
        horizontal: Boolean,
        near: Float? = null
    ): Float? {
        val profile = gradientProfile(source, horizontal)
        if (profile.size < 16) return null
        var mean = 0.0
        for (value in profile) mean += value
        mean /= profile.size
        for (index in profile.indices) profile[index] = (profile[index] - mean).toFloat()

        val minLag = if (near != null) {
            max(10, (near * 0.6f).roundToInt())
        } else {
            // A real stud pitch in a phone-sized (<=1600 px) fish crop is not
            // 8-11 px; those lags are JPEG texture on smooth plates and produce
            // absurd 40x40 "grids".
            12
        }
        val maxLag = min(
            profile.size - 2,
            if (near != null) min(200, (near * 1.6f).roundToInt()) else 200
        )
        if (maxLag <= minLag) return null
        val correlation = FloatArray(maxLag + 1)
        for (lag in 1..maxLag) {
            correlation[lag] = normalizedAutocorrelation(profile, lag)
        }
        // A credible stud period must repeat several times. A single silhouette
        // edge with a handful of overlapping pixels is not a lattice.
        val supportMax = min(maxLag, (profile.size / 2.5f).roundToInt())
        if (supportMax < minLag) return null
        var best = 0f
        for (lag in minLag..supportMax) if (correlation[lag] > best) best = correlation[lag]
        if (best < 0.25f) return null
        val threshold = best * 0.78f
        for (lag in minLag..supportMax) {
            val left = correlation[lag - 1]
            val right = if (lag < correlation.size - 1) correlation[lag + 1] else 0f
            if (correlation[lag] >= threshold &&
                correlation[lag] >= left &&
                correlation[lag] >= right
            ) {
                return lag.toFloat()
            }
        }
        var bestLag = minLag
        for (lag in minLag..supportMax) if (correlation[lag] > correlation[bestLag]) bestLag = lag
        return bestLag.toFloat()
    }

    private fun gradientProfile(source: ArgbImage, horizontal: Boolean): FloatArray {
        val length = if (horizontal) source.width else source.height
        val other = if (horizontal) source.height else source.width
        val raw = FloatArray(length)
        for (line in 0 until other) {
            var previous = -1
            for (index in 0 until length) {
                val color = if (horizontal) {
                    source.pixels[line * source.width + index]
                } else {
                    source.pixels[index * source.width + line]
                }
                if (ArgbImage.alpha(color) <= 60) {
                    previous = -1
                    continue
                }
                if (previous != -1 && index > 0) {
                    val difference =
                        abs(ArgbImage.red(color) - ArgbImage.red(previous)) +
                            abs(ArgbImage.green(color) - ArgbImage.green(previous)) +
                            abs(ArgbImage.blue(color) - ArgbImage.blue(previous))
                    if (difference > 3) raw[index - 1] += difference.toFloat()
                }
                previous = color
            }
        }
        val smoothed = FloatArray(length)
        for (index in 0 until length) {
            val left = raw[if (index > 0) index - 1 else 0]
            val right = raw[if (index + 1 < length) index + 1 else length - 1]
            smoothed[index] = (left + raw[index] * 2f + right) * 0.25f
        }
        return smoothed
    }

    private fun normalizedAutocorrelation(values: FloatArray, lag: Int): Float {
        var numerator = 0.0
        var energyA = 0.0
        var energyB = 0.0
        val limit = values.size - lag
        for (index in 0 until limit) {
            val a = values[index]
            val b = values[index + lag]
            numerator += a * b
            energyA += a * a
            energyB += b * b
        }
        if (energyA < 1e-6 || energyB < 1e-6) return 0f
        return (numerator / sqrt(energyA * energyB)).toFloat()
    }

    /** Finds a good starting crop: the bounding box of everything unlike the border. */
    fun autoQuad(source: ArgbImage): FloatArray {
        val thumb = scaleToMax(source, 320)
        val anchors = backgroundAnchors(thumb)
        val background = anchors.firstOrNull() ?: return insetQuad(source, 0.06f)

        var minX = thumb.width
        var minY = thumb.height
        var maxX = -1
        var maxY = -1
        var found = 0
        for (y in 0 until thumb.height) {
            for (x in 0 until thumb.width) {
                val color = thumb.pixels[y * thumb.width + x]
                if (ArgbImage.alpha(color) < 10) continue
                if (colorDistance(color, background) > 38f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    found++
                }
            }
        }
        val thumbArea = thumb.width * thumb.height
        if (found < thumbArea * 0.004f || maxX < minX || maxY < minY) {
            return insetQuad(source, 0.06f)
        }
        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        if (boxWidth.toFloat() * boxHeight / thumbArea > 0.985f) {
            return insetQuad(source, 0.06f)
        }

        val marginX = max(3f, boxWidth * 0.06f)
        val marginY = max(3f, boxHeight * 0.06f)
        val sx = source.width / thumb.width.toFloat()
        val sy = source.height / thumb.height.toFloat()
        val left = ((minX - marginX) * sx).coerceIn(0f, source.width.toFloat())
        val top = ((minY - marginY) * sy).coerceIn(0f, source.height.toFloat())
        val right = ((maxX + 1 + marginX) * sx).coerceIn(0f, source.width.toFloat())
        val bottom = ((maxY + 1 + marginY) * sy).coerceIn(0f, source.height.toFloat())
        return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
    }

    fun insetQuad(source: ArgbImage, fraction: Float): FloatArray {
        val dx = source.width * fraction
        val dy = source.height * fraction
        return floatArrayOf(dx, dy, source.width - dx, dy, source.width - dx, source.height - dy, dx, source.height - dy)
    }

    /**
     * A perspective rectification is only meaningful for a convex, non-degenerate
     * quadrilateral. The old corner constraints allowed a concave shape by dragging
     * the top-left corner past the other points; the homography then sampled clamped
     * border pixels and produced a stretched smear.
     */
    fun isValidQuad(quad: FloatArray): Boolean {
        if (quad.size != 8 || quad.any { !it.isFinite() }) return false
        var sign = 0f
        var twiceArea = 0f
        for (i in 0 until 4) {
            val x0 = quad[i * 2]
            val y0 = quad[i * 2 + 1]
            val next = (i + 1) % 4
            val after = (i + 2) % 4
            val x1 = quad[next * 2]
            val y1 = quad[next * 2 + 1]
            val x2 = quad[after * 2]
            val y2 = quad[after * 2 + 1]
            twiceArea += x0 * y1 - x1 * y0
            val cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
            if (!cross.isFinite() || abs(cross) < 0.01f) return false
            if (sign == 0f) {
                sign = cross
            } else if (sign * cross <= 0f) {
                return false
            }
        }
        return abs(twiceArea) >= 32f
    }

    /** Perspective warp of the quad onto a rectangular output. */
    fun warp(source: ArgbImage, quad: FloatArray, maxLongSide: Int = 900): ArgbImage {
        require(quad.size == 8) { "Quad needs 8 values" }
        require(isValidQuad(quad)) { "Crop must be a convex quadrilateral" }
        val top = hypot((quad[2] - quad[0]).toDouble(), (quad[3] - quad[1]).toDouble()).toFloat()
        val bottom = hypot((quad[4] - quad[6]).toDouble(), (quad[5] - quad[7]).toDouble()).toFloat()
        val left = hypot((quad[6] - quad[0]).toDouble(), (quad[7] - quad[1]).toDouble()).toFloat()
        val right = hypot((quad[4] - quad[2]).toDouble(), (quad[5] - quad[3]).toDouble()).toFloat()
        var outWidth = max(8f, (top + bottom) * 0.5f)
        var outHeight = max(8f, (left + right) * 0.5f)
        val longest = max(outWidth, outHeight)
        if (longest > maxLongSide) {
            val scale = maxLongSide / longest
            outWidth *= scale
            outHeight *= scale
        }
        val outW = max(8, outWidth.roundToInt())
        val outH = max(8, outHeight.roundToInt())

        val transform = Homography.solve(
            floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()),
            quad
        ) ?: throw IllegalArgumentException("Could not map that crop")

        val denominators = floatArrayOf(
            transform[6] * outW + transform[7] * 0f + 1f,
            transform[6] * outW + transform[7] * outH + 1f,
            transform[6] * 0f + transform[7] * outH + 1f
        )
        val positive = denominators[0] > 0f
        require(denominators.all { it.isFinite() && abs(it) > 1e-4f && (it > 0f) == positive }) {
            "Crop produces an invalid perspective"
        }

        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val u = x + 0.5f
                val v = y + 0.5f
                val denominator = transform[6] * u + transform[7] * v + 1f
                if (abs(denominator) < 1e-6f) continue
                val sx = (transform[0] * u + transform[1] * v + transform[2]) / denominator
                val sy = (transform[3] * u + transform[4] * v + transform[5]) / denominator
                out[y * outW + x] = sampleBilinear(source, sx, sy)
            }
        }
        return ArgbImage(outW, outH, out)
    }

    /** Region-growing cutout seeded from all four borders. */
    fun removeBackground(source: ArgbImage, tolerance: Int): ArgbImage {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = source.pixels.copyOf()
        val anchors = backgroundAnchors(source)

        val clamped = tolerance.coerceIn(0, 100)
        // Deliberately conservative: light bricks (cream eyes, white plates) on a
        // pale baseplate can be close to the background colour. The region-growing
        // pass handles shadows/gradients; the slider lets the user raise this.
        val globalTolerance = 6f + clamped * 0.95f
        val localTolerance = 13f + clamped * 0.05f
        // Keep the smooth-gradient fallback bounded: it is there for lighting and
        // paper shadows, not for jumping into a dark grey brick. Without the cap, a
        // high cutout slider treated the user's grey 2x4 brick as background.
        val looseTolerance = min(globalTolerance * 1.6f, 36f)

        val anchorDistance = FloatArray(count)
        for (i in 0 until count) anchorDistance[i] = nearestAnchorDistance(pixels[i], anchors)

        val background = BooleanArray(count)
        val queue = IntArray(count)
        var head = 0
        var tail = 0

        fun push(index: Int, neighbourColor: Int) {
            if (background[index]) return
            val isGlobal = anchorDistance[index] <= globalTolerance
            val isLocal = neighbourColor != -1 &&
                colorDistance(pixels[index], neighbourColor) <= localTolerance &&
                anchorDistance[index] <= looseTolerance
            if (isGlobal || isLocal) {
                background[index] = true
                queue[tail++] = index
            }
        }

        for (x in 0 until width) {
            push(x, -1)
            push((height - 1) * width + x, -1)
        }
        for (y in 1 until height - 1) {
            push(y * width, -1)
            push(y * width + width - 1, -1)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            val color = pixels[index]
            if (x > 0) push(index - 1, color)
            if (x < width - 1) push(index + 1, color)
            if (y > 0) push(index - width, color)
            if (y < height - 1) push(index + width, color)
        }

        // Paper shadows are neutral, but so is a grey brick. Follow only smooth
        // neutral pixels from the known background; the sharp brick perimeter
        // stops the flood before it can eat the brick.
        removeSoftShadows(pixels, background, width, height)
        // Remove detached ghost studs and paper flecks; the real fish build is one
        // connected piece, while those leftovers are small isolated islands.
        removeSmallOpaqueIslands(pixels, background, width, height, max(14, count / 200))

        val result = pixels.copyOf()
        for (i in 0 until count) {
            if (background[i]) {
                result[i] = 0
            }
        }
        // A one-pixel soft edge reads much better on a TV than a hard staircase.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (background[index]) continue
                val touchesBackground =
                    (x > 0 && background[index - 1]) ||
                        (x < width - 1 && background[index + 1]) ||
                        (y > 0 && background[index - width]) ||
                        (y < height - 1 && background[index + width])
                if (touchesBackground) {
                    val alpha = ArgbImage.alpha(result[index])
                    if (alpha > 190) result[index] = (result[index] and 0x00FFFFFF) or (190 shl 24)
                }
            }
        }
        return ArgbImage(width, height, result)
    }

    fun trimTransparent(source: ArgbImage, padding: Int): ArgbImage? {
        var minX = source.width
        var minY = source.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                if (ArgbImage.alpha(source.pixels[y * source.width + x]) > 12) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val left = (minX - padding).coerceAtLeast(0)
        val top = (minY - padding).coerceAtLeast(0)
        val right = (maxX + padding).coerceAtMost(source.width - 1)
        val bottom = (maxY + padding).coerceAtMost(source.height - 1)
        val outWidth = right - left + 1
        val outHeight = bottom - top + 1
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                out[y * outWidth + x] = source.pixels[(y + top) * source.width + (x + left)]
            }
        }
        return ArgbImage(outWidth, outHeight, out)
    }

    /**
     * Resamples the sprite onto a grid of studs. Each cell gets one flat colour
     * sampled from the ring around its stud (not the highlight or the gap), then
     * optionally snapped to the brick palette. That keeps two cells belonging to one
     * 2x4 brick identical and preserves its apparent size.
     */
    fun resampleGrid(
        source: ArgbImage,
        columns: Int,
        rowsOverride: Int = 0,
        snapBrickColours: Boolean = true
    ): ArgbImage {
        val cols = columns.coerceIn(2, 40)
        val rows = if (rowsOverride in 1..48) {
            rowsOverride
        } else {
            max(1, (cols * source.height / source.width.toFloat()).roundToInt()).coerceAtMost(48)
        }
        val out = IntArray(cols * rows)
        for (cy in 0 until rows) {
            val y0 = cy * source.height / rows
            val y1 = max(y0 + 1, (cy + 1) * source.height / rows)
            for (cx in 0 until cols) {
                val x0 = cx * source.width / cols
                val x1 = max(x0 + 1, (cx + 1) * source.width / cols)
                out[cy * cols + cx] = sampleCellColour(
                    source, x0, y0, min(x1, source.width), min(y1, source.height), snapBrickColours
                ) ?: 0
            }
        }
        return ArgbImage(cols, rows, out)
    }

    /**
     * Samples on the detected lattice rather than stretching the rounded counts
     * across the silhouette. The phase matters: a half-stud offset moves every
     * sample away from the brick face and into a stud/gap.
     */
    fun resampleGrid(
        source: ArgbImage,
        estimate: GridEstimate,
        snapBrickColours: Boolean = true
    ): ArgbImage {
        val cols = estimate.columns.coerceIn(2, 40)
        val rows = estimate.rows.coerceIn(1, 48)
        val pitchX = estimate.pitchX.coerceAtLeast(2f)
        val pitchY = estimate.pitchY.coerceAtLeast(2f)
        val out = IntArray(cols * rows)
        for (cy in 0 until rows) {
            val y0 = (estimate.originY + cy * pitchY).roundToInt()
            val y1 = (estimate.originY + (cy + 1) * pitchY).roundToInt()
            if (y1 <= 0 || y0 >= source.height) continue
            for (cx in 0 until cols) {
                val x0 = (estimate.originX + cx * pitchX).roundToInt()
                val x1 = (estimate.originX + (cx + 1) * pitchX).roundToInt()
                if (x1 <= 0 || x0 >= source.width) continue
                out[cy * cols + cx] = sampleCellColour(
                    source,
                    x0.coerceIn(0, source.width - 1),
                    y0.coerceIn(0, source.height - 1),
                    x1.coerceIn(1, source.width),
                    y1.coerceIn(1, source.height),
                    snapBrickColours
                ) ?: 0
            }
        }
        return ArgbImage(cols, rows, out)
    }

    private fun sampleCellColour(
        source: ArgbImage,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        snapBrickColours: Boolean
    ): Int? {
        val cellWidth = x1 - x0
        val cellHeight = y1 - y0
        if (cellWidth <= 0 || cellHeight <= 0) return null
        val area = cellWidth * cellHeight
        val centreX = x0 + cellWidth * 0.5f
        val centreY = y0 + cellHeight * 0.5f
        val minDim = min(cellWidth, cellHeight).toFloat()
        val innerRadius = minDim * 0.27f
        val innerRadiusSquared = innerRadius * innerRadius
        val marginX = max(1f, cellWidth * 0.13f)
        val marginY = max(1f, cellHeight * 0.13f)

        var opaque = 0
        var samples = 0
        val redHistogram = IntArray(256)
        val greenHistogram = IntArray(256)
        val blueHistogram = IntArray(256)

        fun collect(x: Int, y: Int, color: Int) {
            redHistogram[ArgbImage.red(color)]++
            greenHistogram[ArgbImage.green(color)]++
            blueHistogram[ArgbImage.blue(color)]++
            samples++
        }

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val color = source.pixels[y * source.width + x]
                if (ArgbImage.alpha(color) <= 60) continue
                opaque++
                val dx = x + 0.5f - centreX
                val dy = y + 0.5f - centreY
                val insideBrickFace = x + 0.5f >= x0 + marginX && x + 0.5f <= x1 - marginX &&
                    y + 0.5f >= y0 + marginY && y + 0.5f <= y1 - marginY
                if (insideBrickFace && dx * dx + dy * dy >= innerRadiusSquared) {
                    collect(x, y, color)
                }
            }
        }

        if (opaque * 100 < area * 30) return null
        if (samples < 8) {
            // Very small/irregular cell: fall back to a lightly inset full-cell median.
            redHistogram.fill(0)
            greenHistogram.fill(0)
            blueHistogram.fill(0)
            samples = 0
            val insetX = max(1, (cellWidth * 0.08f).roundToInt())
            val insetY = max(1, (cellHeight * 0.08f).roundToInt())
            for (y in y0 + insetY until y1 - insetY) {
                for (x in x0 + insetX until x1 - insetX) {
                    val color = source.pixels[y * source.width + x]
                    if (ArgbImage.alpha(color) > 60) collect(x, y, color)
                }
            }
        }
        if (samples == 0) return null
        val half = max(1, samples / 2)
        val sampled = ArgbImage.argb(
            255,
            histogramMedian(redHistogram, half),
            histogramMedian(greenHistogram, half),
            histogramMedian(blueHistogram, half)
        )
        return if (snapBrickColours) snapToBrickPalette(sampled) else sampled
    }

    private fun snapToBrickPalette(color: Int): Int {
        var best = color
        var bestDistance = Float.MAX_VALUE
        for ((index, paletteColour) in BRICK_PALETTE.withIndex()) {
            val distance = labDistance(color, BRICK_PALETTE_LAB[index])
            if (distance < bestDistance) {
                bestDistance = distance
                best = paletteColour
            }
        }
        // Lab distance is perceptual; plain RGB was mapping orange-red to gold.
        // Leave genuinely custom colours alone rather than forcing a wrong match.
        return if (bestDistance <= 30f) best else color
    }

    private fun labDistance(first: Int, second: FloatArray): Float {
        val lab = rgbToLab(first)
        val dl = lab[0] - second[0]
        val da = lab[1] - second[1]
        val db = lab[2] - second[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun rgbToLab(color: Int): FloatArray {
        fun linear(channel: Int): Float {
            val value = channel / 255f
            return if (value <= 0.04045f) {
                value / 12.92f
            } else {
                ((value + 0.055f) / 1.055f).pow(2.4f)
            }
        }
        val red = linear(ArgbImage.red(color))
        val green = linear(ArgbImage.green(color))
        val blue = linear(ArgbImage.blue(color))
        val x = (red * 0.4124f + green * 0.3576f + blue * 0.1805f) / 0.95047f
        val y = red * 0.2126f + green * 0.7152f + blue * 0.0722f
        val z = (red * 0.0193f + green * 0.1192f + blue * 0.9505f) / 1.08883f
        fun pivot(value: Float): Float =
            if (value > 0.008856f) {
                java.lang.Math.cbrt(value.toDouble()).toFloat()
            } else {
                7.787f * value + 16f / 116f
            }
        val fx = pivot(x)
        val fy = pivot(y)
        val fz = pivot(z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private val BRICK_PALETTE = intArrayOf(
        0xFFF4F4F4.toInt(), // White
        0xFFA0A5A9.toInt(), // Light Bluish Gray
        0xFF6C6E68.toInt(), // Dark Bluish Gray
        0xFF1B2A34.toInt(), // Black
        0xFFC91A09.toInt(), // Red
        0xFF720E0F.toInt(), // Dark Red
        0xFFFE8A18.toInt(), // Orange
        0xFFF8BB3D.toInt(), // Bright Light Orange
        0xFFA95500.toInt(), // Dark Orange
        0xFFF2CD37.toInt(), // Yellow
        0xFFA5CA18.toInt(), // Lime
        0xFF4B9F4A.toInt(), // Bright Green
        0xFF237841.toInt(), // Green
        0xFF184632.toInt(), // Dark Green
        0xFF9B9A5A.toInt(), // Olive Green
        0xFFA0BCAC.toInt(), // Sand Green
        0xFF36AEBF.toInt(), // Medium Azure
        0xFF0055BF.toInt(), // Blue
        0xFF5A93DB.toInt(), // Medium Blue
        0xFF0A3463.toInt(), // Dark Blue
        0xFF81007B.toInt(), // Purple
        0xFF923978.toInt(), // Magenta
        0xFFE4ADC8.toInt(), // Bright Pink
        0xFFC870A0.toInt(), // Dark Pink
        0xFFE4CD9E.toInt(), // Tan
        0xFF958A73.toInt(), // Dark Tan
        0xFFD09168.toInt(), // Nougat
        0xFFF6D7B3.toInt(), // Light Nougat
        0xFF582A12.toInt(), // Reddish Brown
        0xFF352100.toInt(), // Dark Brown
        0xFF898788.toInt(), // Flat Silver
        0xFFAA7F2E.toInt()  // Pearl Gold
    )

    private val BRICK_PALETTE_LAB: Array<FloatArray> by lazy {
        BRICK_PALETTE.map { rgbToLab(it) }.toTypedArray()
    }

    fun upscaleNearest(source: ArgbImage, targetLongSide: Int): ArgbImage {
        val factor = (targetLongSide / max(source.width, source.height)).coerceIn(1, 64)
        if (factor == 1) return source
        val outWidth = source.width * factor
        val outHeight = source.height * factor
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sy = y / factor
            for (x in 0 until outWidth) {
                out[y * outWidth + x] = source.pixels[sy * source.width + x / factor]
            }
        }
        return ArgbImage(outWidth, outHeight, out)
    }

    fun scaleToMax(source: ArgbImage, maxSide: Int): ArgbImage {
        val longest = max(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide / longest.toFloat()
        val outWidth = max(1, (source.width * scale).roundToInt())
        val outHeight = max(1, (source.height * scale).roundToInt())
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val sx = (x + 0.5f) / scale - 0.5f
                val sy = (y + 0.5f) / scale - 0.5f
                out[y * outWidth + x] = sampleBilinear(source, sx, sy)
            }
        }
        return ArgbImage(outWidth, outHeight, out)
    }

    // -------------------------------------------------------------- internals

    private fun sampleBilinear(source: ArgbImage, sx: Float, sy: Float): Int {
        val x = sx.coerceIn(0f, (source.width - 1).toFloat())
        val y = sy.coerceIn(0f, (source.height - 1).toFloat())
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, source.width - 1)
        val y1 = min(y0 + 1, source.height - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = source.pixels[y0 * source.width + x0]
        val c10 = source.pixels[y0 * source.width + x1]
        val c01 = source.pixels[y1 * source.width + x0]
        val c11 = source.pixels[y1 * source.width + x1]

        fun blend(shift: Int): Int {
            val p00 = (c00 shr shift) and 0xFF
            val p10 = (c10 shr shift) and 0xFF
            val p01 = (c01 shr shift) and 0xFF
            val p11 = (c11 shr shift) and 0xFF
            val top = p00 + (p10 - p00) * fx
            val bottom = p01 + (p11 - p01) * fx
            return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
        }
        return ArgbImage.argb(blend(24), blend(16), blend(8), blend(0))
    }

    /**
     * Background estimation has one hard job: distinguish the plain baseplate from
     * the fish. A tight crop often has the fish touching the left and right edges,
     * so blindly taking all four edge medians would call a red fish "background".
     *
     * We compare the top/bottom median with the left/right median. The less saturated
     * cluster wins (baseplates are usually neutral; fish are usually colourful), and
     * the other cluster is only used if it is close to the winner. Local region
     * growing still handles soft lighting gradients within the chosen background.
     */
    private fun backgroundAnchors(source: ArgbImage): IntArray {
        val width = source.width
        val height = source.height
        val stepX = max(1, width / 70)
        val stepY = max(1, height / 70)

        val top = ArrayList<Int>(80)
        val bottom = ArrayList<Int>(80)
        val left = ArrayList<Int>(80)
        val right = ArrayList<Int>(80)
        for (x in 0 until width step stepX) {
            top.add(source.pixels[x])
            bottom.add(source.pixels[(height - 1) * width + x])
        }
        for (y in 0 until height step stepY) {
            left.add(source.pixels[y * width])
            right.add(source.pixels[y * width + width - 1])
        }
        if (top.isEmpty() && left.isEmpty()) return intArrayOf(0xFFFFFFFF.toInt())

        val topBottom = medianColor(top + bottom)
        val leftRight = medianColor(left + right)
        val primary = when {
            colorDistance(topBottom, leftRight) < 35f -> medianColor(top + bottom + left + right)
            saturation(topBottom) <= saturation(leftRight) -> topBottom
            else -> leftRight
        }

        val anchors = ArrayList<Int>()
        anchors.add(primary)
        for (candidate in anchorCandidates(source, stepX, stepY, topBottom, leftRight)) {
            if (colorDistance(primary, candidate) >= 45f) continue
            var duplicate = false
            for (anchor in anchors) {
                if (colorDistance(anchor, candidate) < 10f) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) anchors.add(candidate)
        }
        return anchors.toIntArray()
    }

    private fun anchorCandidates(
        source: ArgbImage,
        stepX: Int,
        stepY: Int,
        topBottom: Int,
        leftRight: Int
    ): List<Int> {
        val candidates = ArrayList<Int>()
        candidates.add(topBottom)
        candidates.add(leftRight)
        val width = source.width
        val height = source.height
        val patchW = max(2, width / 12)
        val patchH = max(2, height / 12)
        val corners = listOf(
            0 to 0,
            width - patchW to 0,
            0 to height - patchH,
            width - patchW to height - patchH
        )
        for ((startX, startY) in corners) {
            val samples = ArrayList<Int>(patchW * patchH / 9)
            var y = startY
            while (y < min(startY + patchH, height)) {
                var x = startX
                while (x < min(startX + patchW, width)) {
                    samples.add(source.pixels[y * width + x])
                    x += max(1, stepX / 2)
                }
                y += max(1, stepY / 2)
            }
            if (samples.isNotEmpty()) candidates.add(medianColor(samples))
        }
        return candidates
    }

    private fun saturation(color: Int): Int {
        val r = ArgbImage.red(color)
        val g = ArgbImage.green(color)
        val b = ArgbImage.blue(color)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFFFFFFFF.toInt()
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        for (color in colors) {
            red[ArgbImage.red(color)]++
            green[ArgbImage.green(color)]++
            blue[ArgbImage.blue(color)]++
        }
        val half = colors.size / 2
        return ArgbImage.argb(
            255,
            histogramMedian(red, half),
            histogramMedian(green, half),
            histogramMedian(blue, half)
        )
    }

    private fun histogramMedian(histogram: IntArray, target: Int): Int {
        var count = 0
        for (value in 0 until 256) {
            count += histogram[value]
            if (count > target) return value
        }
        return 255
    }

    private fun nearestAnchorDistance(color: Int, anchors: IntArray): Float {
        var best = Float.MAX_VALUE
        for (anchor in anchors) {
            val distance = colorDistance(color, anchor)
            if (distance < best) best = distance
        }
        return best
    }

    private fun colorDistance(first: Int, second: Int): Float {
        val dr = (ArgbImage.red(first) - ArgbImage.red(second)).toFloat()
        val dg = (ArgbImage.green(first) - ArgbImage.green(second)).toFloat()
        val db = (ArgbImage.blue(first) - ArgbImage.blue(second)).toFloat()
        return sqrt(0.3f * dr * dr + 0.59f * dg * dg + 0.11f * db * db)
    }

    private fun removeSoftShadows(
        pixels: IntArray,
        background: BooleanArray,
        width: Int,
        height: Int
    ) {
        val count = width * height
        val queue = IntArray(count)
        var head = 0
        var tail = 0

        fun canEnter(index: Int): Boolean {
            if (background[index]) return false
            val color = pixels[index]
            if (ArgbImage.alpha(color) <= 60) return false
            val r = ArgbImage.red(color)
            val g = ArgbImage.green(color)
            val b = ArgbImage.blue(color)
            if (max(r, max(g, b)) - min(r, min(g, b)) > 24) return false
            // The paper shadow is lighter than a dark grey brick face. Keep the
            // darker neutral pixels as potential brick, even when their edge is soft.
            if ((r + g + b) / 3 < 145) return false
            val x = index % width
            val y = index / width
            var largestStep = 0
            fun compare(neighbour: Int) {
                if (ArgbImage.alpha(pixels[neighbour]) <= 60) return
                val nr = ArgbImage.red(pixels[neighbour])
                val ng = ArgbImage.green(pixels[neighbour])
                val nb = ArgbImage.blue(pixels[neighbour])
                largestStep = max(
                    largestStep,
                    max(abs(r - nr), max(abs(g - ng), abs(b - nb)))
                )
            }
            if (x > 0) compare(index - 1)
            if (x < width - 1) compare(index + 1)
            if (y > 0) compare(index - width)
            if (y < height - 1) compare(index + width)
            return largestStep <= 12
        }

        fun seed(index: Int) {
            if (canEnter(index)) {
                background[index] = true
                queue[tail++] = index
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!background[index]) continue
                if (x > 0) seed(index - 1)
                if (x < width - 1) seed(index + 1)
                if (y > 0) seed(index - width)
                if (y < height - 1) seed(index + width)
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) seed(index - 1)
            if (x < width - 1) seed(index + 1)
            if (y > 0) seed(index - width)
            if (y < height - 1) seed(index + width)
        }
    }

    private fun removeSmallOpaqueIslands(
        pixels: IntArray,
        background: BooleanArray,
        width: Int,
        height: Int,
        minimumSize: Int
    ) {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        for (start in 0 until width * height) {
            if (background[start] || visited[start]) continue
            var head = 0
            var tail = 0
            stack[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = stack[head++]
                val x = index % width
                val y = index / width
                if (x > 0) {
                    val n = index - 1
                    if (!background[n] && !visited[n]) { visited[n] = true; stack[tail++] = n }
                }
                if (x < width - 1) {
                    val n = index + 1
                    if (!background[n] && !visited[n]) { visited[n] = true; stack[tail++] = n }
                }
                if (y > 0) {
                    val n = index - width
                    if (!background[n] && !visited[n]) { visited[n] = true; stack[tail++] = n }
                }
                if (y < height - 1) {
                    val n = index + width
                    if (!background[n] && !visited[n]) { visited[n] = true; stack[tail++] = n }
                }
            }
            if (tail < minimumSize) {
                for (i in 0 until tail) background[stack[i]] = true
            }
        }
    }
}
