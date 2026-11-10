package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole aquarium, rendered at 640x360 and upscaled with nearest-neighbour
 * filtering for a chunky, cosy pixel look. Static scenery is pre-rendered once;
 * every frame only animates water, plants, bubbles, food and the fish.
 */
class AquariumScene(
    private val width: Int,
    private val height: Int
) {

    val fish = ArrayList<Fish>()
    private val removedFishIds = HashSet<String>()
    private val food = ArrayList<Food>()
    private val bubbles = ArrayList<Bubble>()
    private val sparkles = ArrayList<Sparkle>()
    private val plankton = ArrayList<Plankton>()
    private val bannerQueue = ArrayDeque<Banner>()
    private var banner: Banner? = null
    private var bannerAge = 0f
    private val rand = Random(0x5EED_F15)
    // Scenery stays reproducible, but fish get a fresh random character each
    // launch: tail phase, tempo and activity rhythm must not be choreographed.
    private val fishRand = Random(System.nanoTime())

    private var theme: AquariumTheme = AquariumTheme.REEF
    private var palette: ScenePalette = paletteFor(AquariumTheme.REEF)

    val currentTheme: AquariumTheme get() = theme

    private val sandY = height * 0.84f
    private var time = 0f
    private var sceneAge = 0f

    @Volatile private var loaded = false
    private var serverRunning = false
    private var serverIp = ""
    private var serverPort = 0

    private var bubbleTimer = 0f
    private var feedFlash = 0f

    private val backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val backdropCanvas = Canvas(backdrop)
    private val plantBehind = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val plantBehindCanvas = Canvas(plantBehind)
    private val plantFront = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val plantFrontCanvas = Canvas(plantFront)
    private var plantTimer = 0f
    private val plantInterval = 1f / 15f

    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val noAaPaint = Paint().apply { isAntiAlias = false }
    private val plantFill = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val plantStroke = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rayPaint = Paint().apply {
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 7f
        color = 0xCCFFF4D6.toInt()
        setShadowLayer(2f, 0f, 1f, 0xCC081A22.toInt())
    }
    private val fishMesh = FloatArray((FISH_MESH_COLS + 1) * (FISH_MESH_ROWS + 1) * 2)
    private val kelpPath = Path()
    private val kelpVeinPath = Path()
    private val kelpCentresX = FloatArray(17)
    private val kelpCentresY = FloatArray(17)
    private val kelpHalfWidths = FloatArray(17)
    private val grassPath = Path()
    private val tentaclePath = Path()
    private val rayPath = Path()
    private val surfacePath = Path()

    private val vignettePaint = Paint().apply {
        shader = RadialGradient(
            width / 2f, height / 2f, width * 0.72f,
            intArrayOf(0x00000000, 0x00000000, 0x43000000),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    init {
        buildBackdrop()
        redrawPlants()
        repeat(26) {
            plankton.add(
                Plankton(
                    x = rand.nextFloat() * width,
                    y = 30f + rand.nextFloat() * (sandY - 60f),
                    phase = rand.nextFloat() * 6.283f,
                    speed = 1.5f + rand.nextFloat() * 3f
                )
            )
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** Rebuilds the hardscape and plant layers, leaving every fish in place. */
    fun setTheme(value: AquariumTheme, announce: Boolean = true) {
        if (theme == value) return
        theme = value
        palette = paletteFor(value)
        buildBackdrop()
        redrawPlants()
        if (announce) {
            // A theme change must name the theme that is on screen now, even when
            // several arrows are pressed quickly; do not queue old theme names.
            bannerQueue.clear()
            banner = null
            bannerQueue.addLast(Banner(value.displayName, "LEFT / RIGHT OR T TO CHANGE THEME"))
            nextBanner()
        }
    }

    fun cycleTheme(delta: Int): AquariumTheme {
        val themes = AquariumTheme.values()
        val index = Math.floorMod(theme.ordinal + delta, themes.size)
        val next = themes[index]
        setTheme(next, announce = true)
        return next
    }

    fun addFish(meta: FishMeta, bitmap: Bitmap, announce: Boolean): Fish? {
        if (meta.id in removedFishIds || fish.any { it.meta.id == meta.id }) {
            return null
        }
        val newFish = try {
            Fish(meta, bitmap, width.toFloat(), height.toFloat(), sandY, fishRand)
        } catch (error: Exception) {
            android.util.Log.w("AquariumScene", "Skipping unreadable fish ${meta.name}", error)
            return null
        }
        fish.add(newFish)
        if (announce) {
            spawnSparkles(newFish.x, newFish.y, 16)
            spawnBubbles(newFish.x, newFish.y + 6f, 9)
            val subtitle = if (meta.creator.isNotBlank()) {
                "MADE BY ${meta.creator.uppercase()}"
            } else {
                "A NEW FISH IN THE TANK"
            }
            bannerQueue.addLast(Banner("${meta.name.uppercase()} JOINED!", subtitle))
            if (banner == null) nextBanner()
        }
        return newFish
    }

    fun removeFish(id: String) {
        removedFishIds.add(id)
        var index = fish.indexOfFirst { it.meta.id == id }
        while (index >= 0) {
            val gone = fish.removeAt(index)
            spawnBubbles(gone.x, gone.y, 8)
            index = fish.indexOfFirst { it.meta.id == id }
        }
    }

    fun feed() {
        if (food.size >= 18) return
        val count = 4 + rand.nextInt(3)
        val center = width * (0.28f + rand.nextFloat() * 0.44f)
        repeat(count) {
            food.add(Food(center + (rand.nextFloat() - 0.5f) * 120f, 10f + rand.nextFloat() * 8f, rand))
        }
        repeat(5) {
            bubbles.add(Bubble(center + (rand.nextFloat() - 0.5f) * 40f, 8f, rand))
        }
        feedFlash = 1.4f
    }

    /** Test hook: current pellet heights, top to bottom. */
    internal fun foodPositions(): List<Float> = food.map { it.y }

    fun setLoaded(value: Boolean) {
        loaded = value
    }

    fun setServerInfo(ip: String, port: Int, running: Boolean) {
        serverIp = ip
        serverPort = port
        serverRunning = running
    }

    fun update(dt: Float) {
        time += dt
        sceneAge += dt
        feedFlash = max(0f, feedFlash - dt)

        // Pellets must sink before the fish target them. Without this update they
        // stayed at the surface forever, could not be reached, and eventually
        // filled the 18-pellet cap so feeding stopped permanently.
        for (item in food) item.update(dt)

        for (f in fish) {
            val target = nearestFood(f)
            f.update(dt, target, fishRand)
            if (target != null && target.eaten) spawnBubbles(target.x, target.y, 4)
            if (f.activity > 1.35f && rand.nextFloat() < dt * 0.9f) {
                val direction = if (f.facingRight) -1f else 1f
                bubbles.add(
                    Bubble(
                        f.x + direction * f.drawWidth * 0.42f,
                        f.y + (rand.nextFloat() - 0.5f) * f.drawHeight * 0.2f,
                        rand
                    )
                )
            }
        }
        food.removeAll { it.eaten || it.age > 40f || it.y > sandY + 4f }

        val iterator = bubbles.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            b.update(dt)
            if (b.y < -4f) iterator.remove()
        }
        val sparks = sparkles.iterator()
        while (sparks.hasNext()) {
            val s = sparks.next()
            s.update(dt)
            if (s.life <= 0f) sparks.remove()
        }
        for (p in plankton) p.update(dt)

        plantTimer += dt
        if (plantTimer >= plantInterval) {
            plantTimer %= plantInterval
            redrawPlants()
        }

        bubbleTimer -= dt
        if (bubbleTimer <= 0f) {
            bubbleTimer = 0.55f + rand.nextFloat() * 1.1f
            val vent = if (rand.nextBoolean()) width * 0.18f else width * 0.74f
            bubbles.add(Bubble(vent + (rand.nextFloat() - 0.5f) * 14f, sandY - 2f, rand))
        }

        if (banner == null && bannerQueue.isNotEmpty()) nextBanner()
        banner?.let {
            bannerAge += dt
            if (bannerAge > 4.8f) {
                banner = null
                nextBanner()
            }
        }

    }

    fun draw(canvas: Canvas) {
        canvas.drawBitmap(backdrop, 0f, 0f, null)
        canvas.drawBitmap(plantBehind, 0f, 0f, null)
        drawWaterLight(canvas)
        drawPlankton(canvas)
        // Far fish first, near fish last, so depth reads as a real water column.
        for (f in fish.sortedBy { it.depth }) drawFish(canvas, f)
        canvas.drawBitmap(plantFront, 0f, 0f, null)
        drawFishNames(canvas)
        drawFood(canvas)
        drawBubbles(canvas)
        drawSparkles(canvas)
        drawSurface(canvas)
        drawHints(canvas)
        drawBanner(canvas)
    }

    // --------------------------------------------------------------- fish art

    private fun drawFish(canvas: Canvas, f: Fish) {
        val bmp = f.sprite
        if (bmp.height <= 0) return
        val scale = f.drawHeight / bmp.height
        val dw = bmp.width * scale
        val dh = f.drawHeight
        val bob = sin(time * 1.35f + f.phase) * 0.8f
        // Fish farther from the glass sit slightly higher and smaller, like the
        // receding water column.
        val depthLift = (1f - f.depth) * height * 0.06f
        val drawY = f.y - depthLift + bob

        val shadowAlpha = (24f + f.depth * 16f).toInt().coerceIn(24, 40)
        shadowPaint.color = shadowAlpha shl 24
        val shadowTurn = max(0.25f, abs(f.turnScaleX))
        canvas.drawOval(
            f.x - dw * 0.34f * shadowTurn,
            drawY + dh * 0.34f,
            f.x + dw * 0.34f * shadowTurn,
            drawY + dh * 0.5f,
            shadowPaint
        )

        canvas.save()
        canvas.translate(f.x, drawY)
        // Canvas pre-concatenates transforms, so calling scale before rotate
        // rotates local points first and mirrors them afterwards. The pitch is
        // therefore expressed in world space and tips the head along the vertical
        // direction of travel for both facings. Rotating after the mirror inverted
        // a left-facing fish's pitch. The eased scale still turns it edge-on.
        val turnScale = if (abs(f.turnScaleX) < 0.05f) {
            if (f.turnScaleX < 0f) -0.05f else 0.05f
        } else {
            f.turnScaleX
        }
        canvas.scale(turnScale, 1f)
        canvas.rotate(f.tilt * 57.2958f)

        // Carangiform-style traveling wave: the head and front third stay rigid,
        // while a smooth wave grows toward the tail. Displacing in Y (not X)
        // makes the tail sweep through the water instead of stretching the body.
        val amplitude = dh * 0.16f
        // Wavelength equals the 0.8-body stride in Fish, so the visible body wave
        // travels at the same speed as the fish. A longer wave made the tail look
        // fast while the fish was barely advancing.
        val waveLength = 0.8f
        val phaseShift = 2f * PI.toFloat() * waveLength
        var vertex = 0
        for (row in 0..FISH_MESH_ROWS) {
            val v = row / FISH_MESH_ROWS.toFloat()
            for (col in 0..FISH_MESH_COLS) {
                val u = col / FISH_MESH_COLS.toFloat()
                // u=0 is tail, u=1 is head; the front 35% is effectively rigid.
                var tail = ((0.65f - u) / 0.65f).coerceIn(0f, 1f)
                tail = tail * tail * (3f - 2f * tail)
                val wave = sin(f.tailPhase + u * phaseShift + f.phase)
                val dy = wave * amplitude * tail
                fishMesh[vertex++] = -dw / 2f + u * dw
                fishMesh[vertex++] = -dh / 2f + v * dh + dy
            }
        }
        bitmapPaint.alpha = (200 + (f.depth * 55f)).toInt().coerceIn(200, 255)
        canvas.drawBitmapMesh(
            bmp,
            FISH_MESH_COLS,
            FISH_MESH_ROWS,
            fishMesh,
            0,
            null,
            0,
            bitmapPaint
        )
        bitmapPaint.alpha = 255
        canvas.restore()
    }

    private fun drawFishNames(canvas: Canvas) {
        for (f in fish.sortedBy { it.depth }) {
            val name = f.meta.name.trim().uppercase()
            if (name.isBlank()) continue
            val label = if (name.length > 18) name.take(18) else name
            val x = f.x.coerceIn(20f, width - 20f)
            val depthLift = (1f - f.depth) * height * 0.06f
            val baseline = (f.y - depthLift + f.drawHeight * 0.5f + 9f)
                .coerceIn(12f, height - 4f)
            val textWidth = namePaint.measureText(label)
            panelPaint.color = 0x99081A22.toInt()
            canvas.drawRoundRect(
                x - textWidth * 0.5f - 4f,
                baseline - 7f,
                x + textWidth * 0.5f + 4f,
                baseline + 2f,
                3f,
                3f,
                panelPaint
            )
            canvas.drawText(label, x, baseline, namePaint)
        }
    }

    private fun nearestFood(f: Fish): Food? {
        var best: Food? = null
        var bestDistance = 250f
        for (item in food) {
            if (item.eaten) continue
            val distance = hypot(item.x - f.x, item.y - f.y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = item
            }
        }
        return best
    }

    // --------------------------------------------------------------- scenery

    private fun buildBackdrop() {
        val canvas = backdropCanvas
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(palette.waterTop, palette.waterMid, palette.waterLow, palette.waterDeep),
            floatArrayOf(0f, 0.34f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        fillPaint.shader = null

        // Deliberate low-res bands give the gradient a subtle pixel texture.
        noAaPaint.color = 0x0BFFFFFF
        var bandY = 12f
        var band = 0
        while (bandY < sandY) {
            canvas.drawRect(0f, bandY, width.toFloat(), bandY + 5f, noAaPaint)
            bandY += 19f
            band++
            if (band % 3 == 0) noAaPaint.color = 0x08000000 else noAaPaint.color = 0x0BFFFFFF
        }

        // Distant hills and kelp silhouettes.
        val hills = Path().apply {
            moveTo(0f, sandY - 6f)
            cubicTo(width * 0.16f, sandY - 70f, width * 0.3f, sandY - 12f, width * 0.46f, sandY - 44f)
            cubicTo(width * 0.62f, sandY - 78f, width * 0.8f, sandY - 10f, width.toFloat(), sandY - 52f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        noAaPaint.color = palette.hill
        canvas.drawPath(hills, noAaPaint)

        // Broad, layered kelp silhouettes instead of thin lines.
        drawKelpCluster(
            canvas, width * 0.04f, sandY + 2f, 132f, 0.5f, 1.05f, 165,
            palette.kelpBackDark, palette.kelpBackMid, palette.kelpBackLight
        )
        drawKelpCluster(
            canvas, width * 0.455f, sandY + 2f, 92f, 2.1f, 0.82f, 135,
            palette.kelpBackDark, palette.kelpBackMid, palette.kelpBackLight
        )
        drawKelpCluster(
            canvas, width * 0.64f, sandY + 2f, 74f, 3.2f, 0.7f, 125,
            palette.kelpBackDark, palette.kelpBackMid, palette.kelpBackLight
        )
        drawKelpCluster(
            canvas, width * 0.955f, sandY + 2f, 118f, 4.4f, 0.95f, 150,
            palette.kelpBackDark, palette.kelpBackMid, palette.kelpBackLight
        )

        // Sand with dunes and grain.
        val sand = Path().apply {
            moveTo(0f, sandY - 4f)
            quadTo(width * 0.18f, sandY - 15f, width * 0.38f, sandY - 5f)
            quadTo(width * 0.62f, sandY + 5f, width * 0.78f, sandY - 9f)
            quadTo(width * 0.9f, sandY - 16f, width.toFloat(), sandY - 6f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        noAaPaint.color = palette.sandMain
        canvas.drawPath(sand, noAaPaint)
        noAaPaint.color = palette.sandLight
        canvas.drawRect(0f, sandY + 9f, width.toFloat(), sandY + 20f, noAaPaint)
        noAaPaint.color = palette.sandRidge
        canvas.drawRect(0f, sandY + 10f, width.toFloat(), sandY + 12f, noAaPaint)
        noAaPaint.color = palette.sandEdge
        canvas.drawRect(0f, height - 12f, width.toFloat(), height.toFloat(), noAaPaint)

        val grain = Random(1234)
        for (i in 0 until 420) {
            val gx = grain.nextFloat() * width
            val gy = sandY + 4f + grain.nextFloat() * (height - sandY - 4f)
            val size = if (grain.nextInt(5) == 0) 2f else 1f
            noAaPaint.color = when (grain.nextInt(4)) {
                0 -> 0x33A97C3E
                1 -> 0x33FFF0C0
                2 -> 0x33B98A4A
                else -> 0x22FFFFFF
            }
            canvas.drawRect(gx, gy, gx + size, gy + size, noAaPaint)
        }

        drawRock(canvas, width * 0.4f, sandY + 12f, 54f, 34f)
        drawRock(canvas, width * 0.71f, sandY + 16f, 40f, 24f)
        drawRock(canvas, width * 0.12f, sandY + 22f, 30f, 17f)

        // The soft plants live in animated overlays; this is the hard-scape for
        // the selected layout.
        when (theme) {
            AquariumTheme.REEF -> {
                drawDriftwood(canvas, width * 0.255f, width * 0.415f, sandY + 24f, 1.05f)
                drawBrainCoral(canvas, width * 0.61f, sandY + 26f, 36f, 16f)
                drawSeaFan(canvas, width * 0.93f, sandY + 22f, 56f, 225)
                drawTubeSponges(canvas, width * 0.735f, sandY + 20f, 1f)
                drawUrchin(canvas, width * 0.30f, sandY + 43f, 6.5f)
                drawCoral(canvas, width * 0.87f, sandY + 4f, 0xFFE8685D.toInt(), 34f)
                drawCoral(canvas, width * 0.81f, sandY + 8f, 0xFFF2A65A.toInt(), 23f)
                drawCastle(canvas, width * 0.17f, sandY + 10f)
                drawChest(canvas, width * 0.67f, sandY + 24f)
                drawStarfish(canvas, width * 0.55f, sandY + 34f)
                drawShell(canvas, width * 0.455f, sandY + 40f, 0xFFF6E3C2.toInt())
                drawShell(canvas, width * 0.585f, sandY + 28f, 0xFFF0B7A4.toInt())
            }
            AquariumTheme.KELP_FOREST -> {
                drawDriftwood(canvas, width * 0.18f, width * 0.47f, sandY + 30f, 1.35f)
                drawRock(canvas, width * 0.56f, sandY + 14f, 46f, 30f)
                drawSeaFan(canvas, width * 0.86f, sandY + 24f, 64f, 220)
                drawTubeSponges(canvas, width * 0.72f, sandY + 22f, 1.1f)
                drawUrchin(canvas, width * 0.34f, sandY + 44f, 7f)
                drawStarfish(canvas, width * 0.60f, sandY + 36f)
            }
            AquariumTheme.SUNKEN_WRECK -> {
                drawShipwreck(canvas, width * 0.66f, sandY + 28f, 1.05f)
                drawChest(canvas, width * 0.84f, sandY + 30f)
                drawSeaFan(canvas, width * 0.22f, sandY + 24f, 46f, 200)
                drawTubeSponges(canvas, width * 0.36f, sandY + 22f, 0.9f)
                drawUrchin(canvas, width * 0.51f, sandY + 43f, 6.5f)
                drawStarfish(canvas, width * 0.14f, sandY + 36f)
                drawShell(canvas, width * 0.48f, sandY + 40f, 0xFFF6E3C2.toInt())
            }
            AquariumTheme.RIVERBED -> {
                drawDriftwood(canvas, width * 0.30f, width * 0.76f, sandY + 30f, 1.5f)
                drawRock(canvas, width * 0.48f, sandY + 12f, 38f, 23f)
                drawRock(canvas, width * 0.13f, sandY + 20f, 42f, 24f)
                drawShell(canvas, width * 0.42f, sandY + 38f, 0xFFEAD9B0.toInt())
                drawShell(canvas, width * 0.83f, sandY + 34f, 0xFFD8BFA0.toInt())
                drawStarfish(canvas, width * 0.58f, sandY + 38f)
            }
        }

        // Bubble vents.
        noAaPaint.color = 0xFF8C6D45.toInt()
        canvas.drawOval(width * 0.18f - 5f, sandY + 2f, width * 0.18f + 5f, sandY + 9f, noAaPaint)
        canvas.drawOval(width * 0.74f - 6f, sandY + 5f, width * 0.74f + 6f, sandY + 12f, noAaPaint)

        // The vignette is static, so bake it into the backdrop. The soft plant
        // overlays are composited over this base each frame.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
    }

    private fun drawRock(canvas: Canvas, cx: Float, baseY: Float, halfW: Float, rockH: Float) {
        val path = Path().apply {
            moveTo(cx - halfW, baseY)
            lineTo(cx - halfW * 0.8f, baseY - rockH * 0.55f)
            lineTo(cx - halfW * 0.36f, baseY - rockH * 0.92f)
            lineTo(cx + halfW * 0.08f, baseY - rockH)
            lineTo(cx + halfW * 0.55f, baseY - rockH * 0.7f)
            lineTo(cx + halfW * 0.9f, baseY - rockH * 0.24f)
            lineTo(cx + halfW, baseY)
            close()
        }
        noAaPaint.color = palette.rockMid
        canvas.drawPath(path, noAaPaint)
        noAaPaint.color = palette.rockLight
        canvas.drawRect(cx - halfW * 0.42f, baseY - rockH * 0.9f, cx + halfW * 0.08f, baseY - rockH * 0.82f, noAaPaint)
        noAaPaint.color = palette.rockDark
        canvas.drawRect(cx + halfW * 0.2f, baseY - rockH * 0.5f, cx + halfW * 0.66f, baseY - rockH * 0.42f, noAaPaint)
    }

    private fun drawCoral(canvas: Canvas, baseX: Float, baseY: Float, color: Int, size: Float) {
        val paint = Paint(strokePaint).apply {
            this.color = color
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        fun branch(x: Float, y: Float, angle: Float, length: Float, depth: Int) {
            if (depth <= 0) return
            val nx = x + cos(angle) * length
            val ny = y - sin(angle) * length
            canvas.drawLine(x, y, nx, ny, paint)
            branch(nx, ny, angle - 0.52f, length * 0.68f, depth - 1)
            branch(nx, ny, angle + 0.4f, length * 0.66f, depth - 1)
        }
        branch(baseX, baseY, PI.toFloat() / 2f, size, 4)
        fillPaint.color = 0x99FFE1B8.toInt()
        canvas.drawCircle(baseX + cos(1.2f) * size * 0.7f, baseY - sin(1.2f) * size * 0.7f, 2.2f, fillPaint)
        canvas.drawCircle(baseX - cos(0.4f) * size * 0.9f, baseY - sin(0.4f) * size * 0.9f, 2f, fillPaint)
    }

    private fun drawCastle(canvas: Canvas, cx: Float, baseY: Float) {
        val stone = 0xFFD9B978.toInt()
        val shadow = 0xFFB8935A.toInt()
        val dark = 0xFF8A6D44.toInt()
        val door = 0xFF1F3F49.toInt()
        noAaPaint.color = stone
        canvas.drawRect(cx - 16f, baseY - 46f, cx + 16f, baseY, noAaPaint)
        canvas.drawRect(cx - 30f, baseY - 30f, cx - 12f, baseY, noAaPaint)
        canvas.drawRect(cx + 12f, baseY - 30f, cx + 30f, baseY, noAaPaint)
        noAaPaint.color = shadow
        canvas.drawRect(cx + 4f, baseY - 46f, cx + 16f, baseY, noAaPaint)
        canvas.drawRect(cx + 18f, baseY - 30f, cx + 30f, baseY, noAaPaint)
        noAaPaint.color = stone
        for (i in 0 until 4) {
            canvas.drawRect(cx - 16f + i * 9f, baseY - 53f, cx - 10f + i * 9f, baseY - 46f, noAaPaint)
        }
        canvas.drawRect(cx - 30f, baseY - 36f, cx - 24f, baseY - 30f, noAaPaint)
        canvas.drawRect(cx + 24f, baseY - 36f, cx + 30f, baseY - 30f, noAaPaint)
        noAaPaint.color = door
        canvas.drawRect(cx - 7f, baseY - 20f, cx + 7f, baseY, noAaPaint)
        canvas.drawCircle(cx, baseY - 20f, 7f, noAaPaint)
        noAaPaint.color = 0xFFFFD166.toInt()
        canvas.drawRect(cx - 2f, baseY - 40f, cx + 3f, baseY - 35f, noAaPaint)
        canvas.drawRect(cx - 26f, baseY - 24f, cx - 21f, baseY - 20f, noAaPaint)
        canvas.drawRect(cx + 21f, baseY - 24f, cx + 26f, baseY - 20f, noAaPaint)
        noAaPaint.color = dark
        canvas.drawRect(cx - 1f, baseY - 67f, cx + 1f, baseY - 52f, noAaPaint)
        val flag = Path().apply {
            moveTo(cx + 1f, baseY - 67f)
            lineTo(cx + 15f, baseY - 62f)
            lineTo(cx + 1f, baseY - 57f)
            close()
        }
        noAaPaint.color = 0xFFE8685D.toInt()
        canvas.drawPath(flag, noAaPaint)
    }

    private fun drawShipwreck(canvas: Canvas, cx: Float, baseY: Float, scale: Float) {
        val hull = Path().apply {
            moveTo(cx - 82f * scale, baseY - 20f * scale)
            lineTo(cx + 72f * scale, baseY - 12f * scale)
            lineTo(cx + 52f * scale, baseY + 8f * scale)
            lineTo(cx - 54f * scale, baseY + 8f * scale)
            close()
        }
        noAaPaint.color = 0xFF4A382A.toInt()
        canvas.drawPath(hull, noAaPaint)
        noAaPaint.color = 0xFF6A5138.toInt()
        canvas.drawRect(
            cx - 66f * scale, baseY - 18f * scale,
            cx + 58f * scale, baseY - 14f * scale,
            noAaPaint
        )
        noAaPaint.color = 0xFF382A20.toInt()
        var plank = 0
        while (plank < 3) {
            canvas.drawRect(
                cx - 60f * scale,
                baseY - (7f + plank * 5f) * scale,
                cx + 52f * scale,
                baseY - (6f + plank * 5f) * scale,
                noAaPaint
            )
            plank++
        }
        // Broken mast and a surviving cross-yard.
        plantStroke.color = 0xFF5E4631.toInt()
        plantStroke.strokeWidth = 4f * scale
        canvas.drawLine(cx - 6f * scale, baseY - 16f * scale, cx + 18f * scale, baseY - 82f * scale, plantStroke)
        plantStroke.strokeWidth = 2.5f * scale
        canvas.drawLine(cx + 7f * scale, baseY - 58f * scale, cx + 48f * scale, baseY - 70f * scale, plantStroke)
        drawTatteredSail(canvas, cx + 20f * scale, baseY - 78f * scale, scale)
        // Portholes.
        noAaPaint.color = 0xFF233B3D.toInt()
        canvas.drawCircle(cx - 36f * scale, baseY - 3f * scale, 5f * scale, noAaPaint)
        canvas.drawCircle(cx + 8f * scale, baseY - 1f * scale, 5f * scale, noAaPaint)
        plantStroke.color = 0xBBFFD166.toInt()
        plantStroke.strokeWidth = 1.2f
        canvas.drawCircle(cx - 36f * scale, baseY - 3f * scale, 3f * scale, plantStroke)
        canvas.drawCircle(cx + 8f * scale, baseY - 1f * scale, 3f * scale, plantStroke)
    }

    private fun drawTatteredSail(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val sail = Path().apply {
            moveTo(x, y)
            lineTo(x + 30f * scale, y + 6f * scale)
            lineTo(x + 25f * scale, y + 25f * scale)
            lineTo(x + 15f * scale, y + 20f * scale)
            lineTo(x + 8f * scale, y + 31f * scale)
            close()
        }
        noAaPaint.color = 0x665F766D
        canvas.drawPath(sail, noAaPaint)
        plantStroke.color = 0xFF7F958B.toInt()
        plantStroke.strokeWidth = 1f
        canvas.drawLine(x, y, x + 25f * scale, y + 25f * scale, plantStroke)
    }

    private fun drawChest(canvas: Canvas, cx: Float, baseY: Float) {
        noAaPaint.color = 0xFF8B5A3C.toInt()
        canvas.drawRect(cx - 19f, baseY - 17f, cx + 19f, baseY, noAaPaint)
        noAaPaint.color = 0xFFA96F49.toInt()
        canvas.drawRect(cx - 19f, baseY - 24f, cx + 19f, baseY - 17f, noAaPaint)
        noAaPaint.color = 0xFFFFD166.toInt()
        canvas.drawRect(cx - 19f, baseY - 21f, cx + 19f, baseY - 19f, noAaPaint)
        canvas.drawRect(cx - 3f, baseY - 24f, cx + 3f, baseY, noAaPaint)
        canvas.drawRect(cx - 2f, baseY - 15f, cx + 2f, baseY - 9f, noAaPaint)
        noAaPaint.color = 0x33FFF2B0.toInt()
        canvas.drawRect(cx - 14f, baseY - 15f, cx - 10f, baseY - 12f, noAaPaint)
    }

    private fun drawStarfish(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path()
        for (i in 0 until 10) {
            val angle = -PI / 2 + i * PI / 5
            val radius = if (i % 2 == 0) 8f else 3.2f
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        noAaPaint.color = 0xFFE8685D.toInt()
        canvas.drawPath(path, noAaPaint)
        noAaPaint.color = 0xFFFFC48A.toInt()
        canvas.drawRect(cx - 1f, cy - 3f, cx + 1f, cy + 1f, noAaPaint)
    }

    private fun drawShell(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        noAaPaint.color = color
        canvas.drawOval(cx - 6f, cy - 3f, cx + 6f, cy + 4f, noAaPaint)
        noAaPaint.color = 0x44000000
        canvas.drawRect(cx - 4f, cy - 1f, cx + 4f, cy + 1f, noAaPaint)
    }

    // ------------------------------------------------------------ plants

    private fun redrawPlants() {
        plantBehindCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawPlantsBehind(plantBehindCanvas)
        plantFrontCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawPlantsFront(plantFrontCanvas)
    }

    private fun drawPlantsBehind(canvas: Canvas) {
        when (theme) {
            AquariumTheme.REEF -> {
                kelpBehind(canvas, 0.045f, 150f, 0.4f, 1.05f, 225)
                kelpBehind(canvas, 0.085f, 112f, 1.7f, 0.85f, 205)
                kelpBehind(canvas, 0.78f, 132f, 2.4f, 0.95f, 215)
                kelpBehind(canvas, 0.825f, 92f, 3.6f, 0.75f, 190)
                kelpBehind(canvas, 0.335f, 66f, 4.3f, 0.6f, 170)
                drawFern(canvas, width * 0.06f, sandY + 14f, 92f, 0.7f, 235)
                drawFern(canvas, width * 0.945f, sandY + 12f, 84f, 2.2f, 225)
                drawAnubias(canvas, width * 0.30f, sandY + 18f, 1.05f, 0.5f, 245)
                drawAnubias(canvas, width * 0.49f, sandY + 16f, 0.90f, 1.9f, 235)
                drawAnubias(canvas, width * 0.755f, sandY + 16f, 0.85f, 1.7f, 235)
                grassRow(canvas, 0.025f, 34f, 0.3f, 225)
                grassRow(canvas, 0.145f, 28f, 1.1f, 215)
                grassRow(canvas, 0.245f, 38f, 2.0f, 225)
                grassRow(canvas, 0.555f, 30f, 3.1f, 215)
                grassRow(canvas, 0.69f, 26f, 4.0f, 205)
                grassRow(canvas, 0.86f, 32f, 5.0f, 220)
            }
            AquariumTheme.KELP_FOREST -> {
                kelpBehind(canvas, 0.015f, 190f, 0.2f, 1.2f, 240)
                kelpBehind(canvas, 0.07f, 165f, 1.3f, 0.95f, 225)
                kelpBehind(canvas, 0.30f, 108f, 3.4f, 0.72f, 205)
                kelpBehind(canvas, 0.62f, 128f, 2.2f, 1.0f, 220)
                kelpBehind(canvas, 0.79f, 180f, 4.1f, 1.15f, 235)
                kelpBehind(canvas, 0.96f, 168f, 5.2f, 0.95f, 225)
                drawFern(canvas, width * 0.17f, sandY + 16f, 104f, 1.1f, 240)
                drawFern(canvas, width * 0.90f, sandY + 14f, 96f, 2.8f, 230)
                drawAnubias(canvas, width * 0.40f, sandY + 18f, 0.95f, 1.4f, 235)
                drawAnubias(canvas, width * 0.70f, sandY + 16f, 0.80f, 3.0f, 225)
                grassRow(canvas, 0.12f, 30f, 0.5f, 220)
                grassRow(canvas, 0.23f, 38f, 2.2f, 230)
                grassRow(canvas, 0.55f, 28f, 1.7f, 215)
                grassRow(canvas, 0.68f, 34f, 4.4f, 225)
                grassRow(canvas, 0.86f, 26f, 5.1f, 210)
            }
            AquariumTheme.SUNKEN_WRECK -> {
                kelpBehind(canvas, 0.04f, 82f, 0.8f, 0.75f, 185)
                kelpBehind(canvas, 0.95f, 112f, 1.2f, 0.85f, 195)
                drawFern(canvas, width * 0.18f, sandY + 14f, 72f, 1.5f, 205)
                drawFern(canvas, width * 0.82f, sandY + 12f, 78f, 3.1f, 210)
                drawAnubias(canvas, width * 0.42f, sandY + 18f, 0.72f, 2.5f, 210)
                grassRow(canvas, 0.12f, 24f, 0.4f, 195)
                grassRow(canvas, 0.28f, 30f, 1.9f, 205)
                grassRow(canvas, 0.58f, 25f, 2.7f, 200)
                grassRow(canvas, 0.74f, 28f, 4.0f, 195)
                grassRow(canvas, 0.90f, 22f, 5.4f, 190)
            }
            AquariumTheme.RIVERBED -> {
                kelpBehind(canvas, 0.06f, 122f, 0.4f, 0.9f, 215)
                kelpBehind(canvas, 0.93f, 132f, 1.8f, 0.95f, 220)
                kelpBehind(canvas, 0.50f, 74f, 3.0f, 0.65f, 180)
                drawFern(canvas, width * 0.20f, sandY + 16f, 94f, 0.9f, 240)
                drawFern(canvas, width * 0.80f, sandY + 14f, 88f, 2.4f, 235)
                drawAnubias(canvas, width * 0.31f, sandY + 18f, 1.0f, 1.1f, 245)
                drawAnubias(canvas, width * 0.62f, sandY + 16f, 0.92f, 2.8f, 235)
                drawAnubias(canvas, width * 0.76f, sandY + 18f, 0.85f, 4.1f, 230)
                grassRow(canvas, 0.05f, 30f, 0.2f, 225)
                grassRow(canvas, 0.14f, 36f, 1.2f, 230)
                grassRow(canvas, 0.36f, 26f, 2.1f, 215)
                grassRow(canvas, 0.50f, 32f, 3.2f, 225)
                grassRow(canvas, 0.68f, 28f, 4.0f, 215)
                grassRow(canvas, 0.88f, 30f, 5.2f, 220)
            }
        }
    }

    private fun drawPlantsFront(canvas: Canvas) {
        when (theme) {
            AquariumTheme.REEF -> {
                kelpFront(canvas, 0.005f, 154f, 2.8f, 1.15f, 248)
                kelpFront(canvas, 0.995f, 132f, 5.0f, 1.0f, 248)
                drawAnemone(canvas, width * 0.90f, sandY + 22f, 1.0f, 0.3f, 235)
                drawSeaGrassClump(canvas, width * 0.40f, height - 6f, 40f, 0.8f, 240)
                drawSeaGrassClump(canvas, width * 0.62f, height - 4f, 34f, 1.9f, 235)
            }
            AquariumTheme.KELP_FOREST -> {
                kelpFront(canvas, 0.0f, 184f, 2.1f, 1.25f, 250)
                kelpFront(canvas, 0.995f, 158f, 4.7f, 1.1f, 250)
                drawSeaGrassClump(canvas, width * 0.36f, height - 6f, 44f, 0.8f, 240)
                drawSeaGrassClump(canvas, width * 0.58f, height - 4f, 38f, 1.9f, 238)
                drawSeaGrassClump(canvas, width * 0.74f, height - 5f, 32f, 3.2f, 232)
            }
            AquariumTheme.SUNKEN_WRECK -> {
                kelpFront(canvas, 0.005f, 118f, 3.0f, 0.95f, 235)
                kelpFront(canvas, 0.995f, 102f, 5.1f, 0.85f, 228)
                drawSeaGrassClump(canvas, width * 0.40f, height - 6f, 30f, 1.0f, 225)
                drawSeaGrassClump(canvas, width * 0.64f, height - 5f, 26f, 2.4f, 220)
            }
            AquariumTheme.RIVERBED -> {
                kelpFront(canvas, 0.005f, 126f, 2.5f, 0.95f, 235)
                kelpFront(canvas, 0.995f, 118f, 4.5f, 0.9f, 230)
                drawSeaGrassClump(canvas, width * 0.28f, height - 6f, 36f, 0.7f, 238)
                drawSeaGrassClump(canvas, width * 0.47f, height - 4f, 40f, 1.8f, 240)
                drawSeaGrassClump(canvas, width * 0.66f, height - 5f, 34f, 2.9f, 235)
            }
        }
    }

    private fun kelpBehind(
        canvas: Canvas,
        fraction: Float,
        heightPx: Float,
        phase: Float,
        scale: Float,
        alpha: Int
    ) {
        drawKelpCluster(
            canvas, width * fraction, sandY + 12f, heightPx, phase, scale, alpha,
            palette.kelpBackDark, palette.kelpBackMid, palette.kelpBackLight
        )
    }

    private fun kelpFront(
        canvas: Canvas,
        fraction: Float,
        heightPx: Float,
        phase: Float,
        scale: Float,
        alpha: Int
    ) {
        drawKelpCluster(
            canvas, width * fraction, height + 4f, heightPx, phase, scale, alpha,
            palette.kelpFrontDark, palette.kelpFrontMid, palette.kelpFrontLight
        )
    }

    private fun grassRow(canvas: Canvas, fraction: Float, heightPx: Float, phase: Float, alpha: Int) {
        drawSeaGrassClump(canvas, width * fraction, sandY + 22f, heightPx, phase, alpha)
    }

    private fun drawKelpCluster(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        heightPx: Float,
        phase: Float,
        scale: Float,
        alpha: Int,
        dark: Int,
        mid: Int,
        light: Int
    ) {
        noAaPaint.color = dark
        noAaPaint.alpha = alpha
        canvas.drawOval(baseX - 7f * scale, baseY - 2f, baseX + 7f * scale, baseY + 4f, noAaPaint)
        noAaPaint.alpha = 255

        val specs = arrayOf(
            floatArrayOf(-0.18f, 0.78f, 0.66f),
            floatArrayOf(-0.06f, 0.94f, 0.84f),
            floatArrayOf(0.04f, 1.00f, 1.00f),
            floatArrayOf(0.15f, 0.86f, 0.76f),
            floatArrayOf(0.26f, 0.70f, 0.58f)
        )
        val widthPx = heightPx * 0.085f * scale
        for (index in specs.indices) {
            val spec = specs[index]
            val color = when (index % 3) {
                0 -> mid
                1 -> light
                else -> mid
            }
            drawKelpBlade(
                canvas = canvas,
                baseX = baseX + (index - 2) * 2.0f * scale,
                baseY = baseY,
                heightPx = heightPx * spec[1] * scale,
                widthPx = widthPx * spec[2],
                lean = heightPx * spec[0] * 0.5f * scale,
                phase = phase + index * 0.85f,
                sway = heightPx * 0.055f * scale,
                color = color,
                veinColor = light,
                alpha = alpha
            )
        }
    }

    private fun drawKelpBlade(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        heightPx: Float,
        widthPx: Float,
        lean: Float,
        phase: Float,
        sway: Float,
        color: Int,
        veinColor: Int,
        alpha: Int
    ) {
        val steps = 16
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            kelpCentresX[i] = baseX + lean * t + sin(time * 0.75f + phase + t * 2.3f) * sway * t
            kelpCentresY[i] = baseY - heightPx * t
            val waviness = 0.92f + 0.08f * sin(t * 11f + phase)
            kelpHalfWidths[i] = widthPx * 0.5f * (1f - 0.78f * t) * waviness
        }

        kelpPath.reset()
        kelpPath.moveTo(kelpCentresX[0] - kelpHalfWidths[0], kelpCentresY[0])
        for (i in 1..steps) kelpPath.lineTo(kelpCentresX[i] - kelpHalfWidths[i], kelpCentresY[i])
        kelpPath.lineTo(kelpCentresX[steps], kelpCentresY[steps] - 2f)
        kelpPath.lineTo(kelpCentresX[steps] + kelpHalfWidths[steps], kelpCentresY[steps])
        for (i in steps downTo 0) kelpPath.lineTo(kelpCentresX[i] + kelpHalfWidths[i], kelpCentresY[i])
        kelpPath.close()

        plantFill.color = color
        plantFill.alpha = alpha
        canvas.drawPath(kelpPath, plantFill)

        plantStroke.color = veinColor
        plantStroke.alpha = (alpha * 0.72f).toInt()
        plantStroke.strokeWidth = 1.15f
        kelpVeinPath.reset()
        kelpVeinPath.moveTo(kelpCentresX[0], kelpCentresY[0])
        for (i in 1 until steps) kelpVeinPath.lineTo(kelpCentresX[i], kelpCentresY[i])
        canvas.drawPath(kelpVeinPath, plantStroke)
        plantStroke.alpha = 255
    }

    private fun drawBush(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        size: Float,
        phase: Float,
        seed: Int,
        alpha: Int,
        dark: Int,
        mid: Int,
        tip: Int
    ) {
        val rng = Random(seed)
        noAaPaint.color = dark
        noAaPaint.alpha = alpha
        canvas.drawOval(baseX - size * 0.22f, baseY - size * 0.06f, baseX + size * 0.22f, baseY + size * 0.08f, noAaPaint)
        noAaPaint.alpha = 255

        val stems = 6
        for (stem in 0 until stems) {
            val spread = (stem - (stems - 1) / 2f) * 0.27f + sin(phase + stem * 1.3f) * 0.025f
            val stemHeight = size * (0.62f + rng.nextFloat() * 0.38f)
            val endX = baseX + sin(spread) * stemHeight * 0.55f
            val endY = baseY - stemHeight
            val controlX = baseX + sin(spread) * stemHeight * 0.22f
            val controlY = baseY - stemHeight * 0.6f

            plantStroke.color = dark
            plantStroke.alpha = alpha
            plantStroke.strokeWidth = 1.6f
            val stemPath = Path().apply {
                moveTo(baseX, baseY)
                quadTo(controlX, controlY, endX, endY)
            }
            canvas.drawPath(stemPath, plantStroke)

            for (leafIndex in 1..9) {
                val t = leafIndex / 10f
                val inverse = 1f - t
                val leafX = inverse * inverse * baseX + 2f * inverse * t * controlX + t * t * endX
                val leafY = inverse * inverse * baseY + 2f * inverse * t * controlY + t * t * endY
                val side = if (leafIndex % 2 == 0) -1f else 1f
                val leafColor = when {
                    t > 0.7f -> tip
                    t > 0.38f -> mid
                    else -> dark
                }
                val length = size * 0.18f * (1f - t * 0.32f)
                val width = size * 0.055f
                drawLeaf(canvas, leafX, leafY, spread + side * 1.02f, length, width, leafColor, alpha)
            }
        }
        plantStroke.alpha = 255
    }

    private fun drawLeaf(
        canvas: Canvas,
        x: Float,
        y: Float,
        angle: Float,
        length: Float,
        width: Float,
        color: Int,
        alpha: Int
    ) {
        plantFill.color = color
        plantFill.alpha = alpha
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle * 57.2958f)
        canvas.drawOval(-length * 0.5f, -width * 0.5f, length * 0.5f, width * 0.5f, plantFill)
        canvas.restore()
    }

    private fun drawFern(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        heightPx: Float,
        phase: Float,
        alpha: Int
    ) {
        val fronds = 5
        val up = -PI.toFloat() / 2f
        for (frond in 0 until fronds) {
            val spread = (frond - (fronds - 1) / 2f) * 0.26f + sin(time * 0.6f + phase + frond) * 0.05f
            val endX = baseX + sin(spread) * heightPx * 0.55f
            val endY = baseY - heightPx
            val controlX = baseX + sin(spread) * heightPx * 0.22f
            val controlY = baseY - heightPx * 0.58f

            plantStroke.color = 0xFF2B6246.toInt()
            plantStroke.alpha = alpha
            plantStroke.strokeWidth = 1.5f
            val stem = Path().apply {
                moveTo(baseX, baseY)
                quadTo(controlX, controlY, endX, endY)
            }
            canvas.drawPath(stem, plantStroke)

            for (node in 1..8) {
                val t = node / 9f
                val inverse = 1f - t
                val leafX = inverse * inverse * baseX + 2f * inverse * t * controlX + t * t * endX
                val leafY = inverse * inverse * baseY + 2f * inverse * t * controlY + t * t * endY
                val side = if (node % 2 == 0) -1f else 1f
                val length = heightPx * 0.18f * (1f - t * 0.25f)
                val width = heightPx * 0.04f
                val leafColor = if (t > 0.5f) 0xFF6FAE63.toInt() else 0xFF3F8754.toInt()
                drawLeaf(canvas, leafX, leafY, up + spread + side * 1.12f, length, width, leafColor, alpha)
            }
            drawLeaf(canvas, endX, endY, up + spread, heightPx * 0.09f, heightPx * 0.03f, 0xFF83B96F.toInt(), alpha)
        }
        plantStroke.alpha = 255
    }

    private fun drawAnubias(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        scale: Float,
        phase: Float,
        alpha: Int
    ) {
        noAaPaint.color = 0xFF5A3F2B.toInt()
        noAaPaint.alpha = alpha
        canvas.drawOval(baseX - 9f * scale, baseY - 3f * scale, baseX + 9f * scale, baseY + 3f * scale, noAaPaint)
        noAaPaint.color = 0xFF4E8C5A.toInt()
        canvas.drawOval(baseX - 6f * scale, baseY - 2f * scale, baseX + 2f * scale, baseY + 2f * scale, noAaPaint)
        noAaPaint.alpha = 255

        val leaves = 5
        for (index in 0 until leaves) {
            val angle = -PI.toFloat() / 2f + (index - (leaves - 1) / 2f) * 0.30f +
                sin(time * 0.7f + phase + index * 0.9f) * 0.06f
            val length = (24f + (index % 3) * 3f) * scale
            val width = (12f + (index % 2) * 2f) * scale

            canvas.save()
            canvas.translate(baseX, baseY)
            canvas.rotate((angle + PI.toFloat() / 2f) * 57.2958f)

            plantStroke.color = 0xFF2E6145.toInt()
            plantStroke.alpha = alpha
            plantStroke.strokeWidth = 1.6f * scale
            canvas.drawLine(0f, 0f, 0f, -length * 0.22f, plantStroke)

            val leaf = Path().apply {
                moveTo(0f, -length * 0.2f)
                cubicTo(-width * 0.65f, -length * 0.38f, -width * 0.55f, -length * 0.82f, 0f, -length)
                cubicTo(width * 0.55f, -length * 0.82f, width * 0.65f, -length * 0.38f, 0f, -length * 0.2f)
                close()
            }
            plantFill.color = if (index % 2 == 0) 0xFF3E8A52.toInt() else 0xFF4E9A5A.toInt()
            plantFill.alpha = alpha
            canvas.drawPath(leaf, plantFill)

            plantStroke.color = 0xFFA8C66C.toInt()
            plantStroke.alpha = (alpha * 0.8f).toInt()
            plantStroke.strokeWidth = 1f
            canvas.drawLine(0f, -length * 0.3f, 0f, -length * 0.9f, plantStroke)
            canvas.drawLine(0f, -length * 0.52f, -width * 0.24f, -length * 0.62f, plantStroke)
            canvas.drawLine(0f, -length * 0.52f, width * 0.24f, -length * 0.62f, plantStroke)
            canvas.restore()
        }

        // A bright young leaf in the middle.
        canvas.save()
        canvas.translate(baseX, baseY)
        canvas.rotate(-4f)
        val young = Path().apply {
            moveTo(0f, -4f * scale)
            cubicTo(-7f * scale, -10f * scale, -6f * scale, -18f * scale, 0f, -23f * scale)
            cubicTo(6f * scale, -18f * scale, 7f * scale, -10f * scale, 0f, -4f * scale)
            close()
        }
        plantFill.color = 0xFF6FB06A.toInt()
        plantFill.alpha = alpha
        canvas.drawPath(young, plantFill)
        canvas.restore()
        plantStroke.alpha = 255
    }

    private fun drawSeaGrassClump(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        heightPx: Float,
        phase: Float,
        alpha: Int
    ) {
        noAaPaint.color = 0xFF2E6145.toInt()
        noAaPaint.alpha = (alpha * 0.8f).toInt()
        canvas.drawOval(baseX - 10f, baseY - 2f, baseX + 10f, baseY + 3f, noAaPaint)
        noAaPaint.alpha = 255

        val blades = 10
        for (index in 0 until blades) {
            val offset = (index - (blades - 1) / 2f) * 2.4f
            val bend = sin(time * 1.0f + phase + index * 0.7f) * (5f + heightPx * 0.18f)
            val bladeHeight = heightPx * (0.65f + 0.35f * abs(sin(index * 1.7f + phase)))
            val tipX = baseX + offset + bend
            val tipY = baseY - bladeHeight
            grassPath.reset()
            grassPath.moveTo(baseX + offset - 1.4f, baseY)
            grassPath.quadTo(baseX + offset + bend * 0.3f - 0.5f, baseY - bladeHeight * 0.55f, tipX, tipY)
            grassPath.quadTo(baseX + offset + bend * 0.3f + 0.5f, baseY - bladeHeight * 0.55f, baseX + offset + 1.4f, baseY)
            grassPath.close()
            plantFill.color = when (index % 3) {
                0 -> 0xFF79B36A.toInt()
                1 -> 0xFF5FA05A.toInt()
                else -> 0xFF8CC474.toInt()
            }
            plantFill.alpha = alpha
            canvas.drawPath(grassPath, plantFill)
        }
    }

    private fun drawMossBall(canvas: Canvas, cx: Float, cy: Float, radius: Float, seed: Int) {
        val rng = Random(seed)
        noAaPaint.color = 0x33000000
        canvas.drawOval(cx - radius, cy + radius * 0.55f, cx + radius, cy + radius * 0.95f, noAaPaint)
        noAaPaint.color = 0xFF2E6145.toInt()
        canvas.drawCircle(cx, cy, radius, noAaPaint)
        noAaPaint.color = 0xFF4E8C5A.toInt()
        canvas.drawCircle(cx - radius * 0.16f, cy - radius * 0.2f, radius * 0.78f, noAaPaint)
        for (index in 0 until 10) {
            val angle = rng.nextFloat() * 6.283f
            val distance = radius * (0.7f + rng.nextFloat() * 0.25f)
            val x = cx + cos(angle) * distance
            val y = cy + sin(angle) * distance
            noAaPaint.color = if (index % 3 == 0) 0xFF79B36A.toInt() else 0xFF397A50.toInt()
            canvas.drawCircle(x, y, 1.1f + rng.nextFloat() * 0.7f, noAaPaint)
        }
    }

    private fun drawDriftwood(canvas: Canvas, startX: Float, endX: Float, baseY: Float, scale: Float) {
        plantStroke.strokeWidth = 9f * scale
        plantStroke.color = 0xFF6B4A33.toInt()
        plantStroke.alpha = 255
        val trunk = Path().apply {
            moveTo(startX, baseY)
            cubicTo(
                startX + (endX - startX) * 0.22f, baseY - 30f * scale,
                endX - (endX - startX) * 0.22f, baseY - 10f * scale,
                endX, baseY - 2f * scale
            )
        }
        canvas.drawPath(trunk, plantStroke)

        plantStroke.strokeWidth = 3.2f * scale
        plantStroke.color = 0xFF8A6244.toInt()
        val highlight = Path().apply {
            moveTo(startX + 2f, baseY - 3.5f * scale)
            cubicTo(
                startX + (endX - startX) * 0.24f, baseY - 32f * scale,
                endX - (endX - startX) * 0.24f, baseY - 12.5f * scale,
                endX - 2f, baseY - 4.5f * scale
            )
        }
        canvas.drawPath(highlight, plantStroke)

        plantStroke.strokeWidth = 2.8f * scale
        plantStroke.color = 0xFF6B4A33.toInt()
        canvas.drawLine(startX + (endX - startX) * 0.28f, baseY - 19f * scale,
            startX + (endX - startX) * 0.22f, baseY - 34f * scale, plantStroke)
        canvas.drawLine(startX + (endX - startX) * 0.68f, baseY - 14f * scale,
            startX + (endX - startX) * 0.73f, baseY - 27f * scale, plantStroke)

        plantFill.color = 0xFF4E8C5A.toInt()
        plantFill.alpha = 240
        canvas.drawOval(startX + (endX - startX) * 0.24f, baseY - 27f * scale,
            startX + (endX - startX) * 0.36f, baseY - 19f * scale, plantFill)
        canvas.drawOval(startX + (endX - startX) * 0.58f, baseY - 16f * scale,
            startX + (endX - startX) * 0.72f, baseY - 8f * scale, plantFill)
        plantFill.color = 0xFF79B36A.toInt()
        canvas.drawOval(startX + (endX - startX) * 0.44f, baseY - 24f * scale,
            startX + (endX - startX) * 0.52f, baseY - 19f * scale, plantFill)
        plantFill.alpha = 255

        noAaPaint.color = 0xFF3E2B1E.toInt()
        canvas.drawCircle(startX + (endX - startX) * 0.5f, baseY - 12f * scale, 2.2f * scale, noAaPaint)
    }

    private fun drawSeaFan(canvas: Canvas, baseX: Float, baseY: Float, size: Float, alpha: Int) {
        val branches = 9
        val originY = baseY - size * 0.22f
        val angles = FloatArray(branches)
        plantStroke.strokeWidth = 1.9f
        plantStroke.alpha = alpha
        for (index in 0 until branches) {
            val spread = (index - (branches - 1) / 2f) * 0.16f + sin(index * 1.3f) * 0.02f
            val angle = -PI.toFloat() / 2f + spread
            angles[index] = angle
            val controlX = baseX + cos(angle) * size * 0.42f
            val controlY = originY + sin(angle) * size * 0.42f
            val endX = baseX + cos(angle) * size * 0.86f
            val endY = originY + sin(angle) * size * 0.86f
            plantStroke.color = if (index % 2 == 0) 0xFFB56892.toInt() else 0xFFC97AA6.toInt()
            val branch = Path().apply {
                moveTo(baseX, originY)
                quadTo(controlX, controlY, endX, endY)
            }
            canvas.drawPath(branch, plantStroke)
            plantFill.color = 0xFFF2C4D8.toInt()
            plantFill.alpha = alpha
            canvas.drawCircle(endX, endY, 1.5f, plantFill)
        }

        // A light lattice between neighbouring branches.
        plantStroke.color = 0xFFA45D86.toInt()
        plantStroke.strokeWidth = 1f
        plantStroke.alpha = (alpha * 0.65f).toInt()
        for (ring in listOf(0.52f, 0.78f)) {
            for (index in 0 until branches - 1) {
                val angleA = angles[index]
                val angleB = angles[index + 1]
                val x1 = baseX + cos(angleA) * size * 0.86f * ring
                val y1 = originY + sin(angleA) * size * 0.86f * ring
                val x2 = baseX + cos(angleB) * size * 0.86f * ring
                val y2 = originY + sin(angleB) * size * 0.86f * ring
                canvas.drawLine(x1, y1, x2, y2, plantStroke)
            }
        }
        plantStroke.alpha = 255
        plantFill.alpha = 255
        plantStroke.color = 0xFF8B4F73.toInt()
        plantStroke.strokeWidth = 3f
        canvas.drawLine(baseX, baseY, baseX, originY, plantStroke)
    }

    private fun drawTubeSponges(canvas: Canvas, baseX: Float, baseY: Float, scale: Float) {
        val tubes = arrayOf(
            floatArrayOf(-11f, 23f, 7f),
            floatArrayOf(-1f, 34f, 8f),
            floatArrayOf(9f, 20f, 6f)
        )
        for ((index, tube) in tubes.withIndex()) {
            val x = baseX + tube[0] * scale
            val heightPx = tube[1] * scale
            val widthPx = tube[2] * scale
            val top = baseY - heightPx
            noAaPaint.color = if (index % 2 == 0) 0xFFC76B4D.toInt() else 0xFFD97A55.toInt()
            canvas.drawRoundRect(x - widthPx * 0.5f, top, x + widthPx * 0.5f, baseY, 3f, 3f, noAaPaint)
            noAaPaint.color = 0xFFF0A274.toInt()
            canvas.drawRect(x - widthPx * 0.5f + 1f, top + 2f, x - widthPx * 0.5f + 2.2f, baseY - 1f, noAaPaint)
            noAaPaint.color = 0xFF5A3B34.toInt()
            canvas.drawOval(x - widthPx * 0.40f, top - 1.5f, x + widthPx * 0.40f, top + 3.5f, noAaPaint)
            noAaPaint.color = 0xFFE68A5E.toInt()
            canvas.drawOval(x - widthPx * 0.28f, top + 0.5f, x + widthPx * 0.28f, top + 2.5f, noAaPaint)
        }
    }

    private fun drawBrainCoral(canvas: Canvas, cx: Float, baseY: Float, widthPx: Float, heightPx: Float) {
        val dome = Path().apply {
            moveTo(cx - widthPx * 0.5f, baseY)
            cubicTo(cx - widthPx * 0.5f, baseY - heightPx * 1.25f,
                cx - widthPx * 0.15f, baseY - heightPx * 1.35f, cx, baseY - heightPx * 1.35f)
            cubicTo(cx + widthPx * 0.15f, baseY - heightPx * 1.35f,
                cx + widthPx * 0.5f, baseY - heightPx * 1.25f, cx + widthPx * 0.5f, baseY)
            close()
        }
        noAaPaint.color = 0xFFD97D5E.toInt()
        canvas.drawPath(dome, noAaPaint)
        noAaPaint.color = 0xFFF0A274.toInt()
        canvas.drawOval(cx - widthPx * 0.34f, baseY - heightPx * 1.2f,
            cx + widthPx * 0.18f, baseY - heightPx * 0.8f, noAaPaint)
        plantStroke.color = 0xFFA95343.toInt()
        plantStroke.alpha = 255
        plantStroke.strokeWidth = 1f
        for (index in 0 until 4) {
            val offset = (index - 1.5f) * widthPx * 0.16f
            val groove = Path().apply {
                moveTo(cx + offset, baseY - 2f)
                quadTo(cx + offset - 4f, baseY - heightPx * 0.8f,
                    cx + offset + 2f, baseY - heightPx * 1.3f)
            }
            canvas.drawPath(groove, plantStroke)
        }
    }

    private fun drawUrchin(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        plantStroke.color = 0xFF4B3555.toInt()
        plantStroke.alpha = 255
        plantStroke.strokeWidth = 1.1f
        for (index in 0 until 14) {
            val angle = index * (2f * PI.toFloat() / 14f)
            canvas.drawLine(
                cx + cos(angle) * radius * 0.7f,
                cy + sin(angle) * radius * 0.7f,
                cx + cos(angle) * radius * 1.45f,
                cy + sin(angle) * radius * 1.45f,
                plantStroke
            )
        }
        noAaPaint.color = 0xFF5E4568.toInt()
        canvas.drawCircle(cx, cy, radius, noAaPaint)
        noAaPaint.color = 0xFF7A5C8A.toInt()
        canvas.drawCircle(cx - radius * 0.2f, cy - radius * 0.2f, radius * 0.6f, noAaPaint)
        noAaPaint.color = 0xFFA88BB6.toInt()
        canvas.drawCircle(cx - radius * 0.25f, cy - radius * 0.3f, radius * 0.2f, noAaPaint)
    }

    private fun drawAnemone(
        canvas: Canvas,
        baseX: Float,
        baseY: Float,
        scale: Float,
        phase: Float,
        alpha: Int
    ) {
        noAaPaint.color = 0xFF6E3D6C.toInt()
        noAaPaint.alpha = alpha
        canvas.drawOval(baseX - 12f * scale, baseY - 5f * scale, baseX + 12f * scale, baseY + 4f * scale, noAaPaint)
        noAaPaint.alpha = 255

        val tentacles = 13
        for (index in 0 until tentacles) {
            val spread = (index - (tentacles - 1) / 2f) * 0.14f
            val wiggle = sin(time * 1.2f + phase + index * 0.72f) * 0.10f
            val angle = -PI.toFloat() / 2f + spread + wiggle
            val length = (20f + (index % 4) * 4f) * scale
            val controlX = baseX + (index - (tentacles - 1) / 2f) * 1.6f * scale + cos(angle) * length * 0.45f
            val controlY = baseY - 6f * scale + sin(angle) * length * 0.45f
            val endX = baseX + (index - (tentacles - 1) / 2f) * 2.6f * scale + cos(angle) * length
            val endY = baseY - 6f * scale + sin(angle) * length
            plantStroke.color = if (index % 2 == 0) 0xFFB568AD.toInt() else 0xFFD08ACC.toInt()
            plantStroke.alpha = alpha
            plantStroke.strokeWidth = 2.2f * scale
            tentaclePath.reset()
            tentaclePath.moveTo(baseX + (index - (tentacles - 1) / 2f) * 2.2f * scale, baseY - 4f * scale)
            tentaclePath.quadTo(controlX, controlY, endX, endY)
            canvas.drawPath(tentaclePath, plantStroke)
            plantFill.color = 0xFFFFE1F0.toInt()
            plantFill.alpha = alpha
            canvas.drawCircle(endX, endY, 1.6f * scale, plantFill)
        }
        plantStroke.alpha = 255
        plantFill.alpha = 255
    }

    /**
     * Soft animated caustics instead of hard spotlight cones: a shallow sun pool
     * at the surface, slow light ribbons through the water and dappled pools on
     * the sand. Drawn before the fish, so the scanned brick colours stay clean.
     */
    private fun drawWaterLight(canvas: Canvas) {
        rayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

        // A soft pool of daylight at the surface.
        rayPaint.style = Paint.Style.FILL
        rayPaint.color = 0x10FFF4D6
        canvas.drawOval(
            width * 0.16f,
            -height * 0.08f,
            width * 0.84f,
            height * 0.20f,
            rayPaint
        )

        // Slow caustic ribbons in the water column.
        rayPaint.style = Paint.Style.STROKE
        rayPaint.strokeWidth = 1.4f
        rayPaint.color = 0x12DFF6E8
        for (layer in 0..2) {
            rayPath.reset()
            var x = -12f
            val baseY = height * (0.20f + layer * 0.16f)
            rayPath.moveTo(x, baseY)
            while (x <= width + 12f) {
                val y = baseY +
                    sin(x * 0.031f + time * 0.55f + layer * 1.7f) * 7f +
                    sin(x * 0.013f - time * 0.31f + layer * 0.8f) * 4f
                rayPath.lineTo(x, y)
                x += 7f
            }
            canvas.drawPath(rayPath, rayPaint)
        }

        // Dappled caustic pools drifting over the sand.
        rayPaint.style = Paint.Style.FILL
        rayPaint.color = 0x18FFF4D6
        for (i in 0 until 5) {
            val x = width * (0.10f + i * 0.19f) + sin(time * 0.34f + i * 1.6f) * 13f
            val y = sandY + 14f + (i % 3) * 9f
            val rx = 12f + sin(time * 0.47f + i) * 3f
            val ry = 3.4f + sin(time * 0.41f + i * 1.2f) * 0.9f
            canvas.drawOval(x - rx, y - ry, x + rx, y + ry, rayPaint)
        }

        // Thin, brighter caustic lines on the sand.
        rayPaint.style = Paint.Style.STROKE
        rayPaint.strokeWidth = 1.1f
        rayPaint.color = 0x20FFF4D6
        for (layer in 0..1) {
            rayPath.reset()
            var x = -10f
            val baseY = sandY + 12f + layer * 13f
            rayPath.moveTo(x, baseY)
            while (x <= width + 10f) {
                val y = baseY +
                    sin(x * 0.045f + time * 0.8f + layer * 2.2f) * 2.2f +
                    sin(x * 0.019f - time * 0.5f + layer) * 1.7f
                rayPath.lineTo(x, y)
                x += 6f
            }
            canvas.drawPath(rayPath, rayPaint)
        }
    }

    private fun drawPlankton(canvas: Canvas) {
        noAaPaint.color = 0x33FFF4D6
        for (p in plankton) canvas.drawRect(p.x, p.y, p.x + 1.3f, p.y + 1.3f, noAaPaint)
    }

    private fun drawBubbles(canvas: Canvas) {
        bubblePaint.color = 0x99DFF6E8.toInt()
        for (b in bubbles) {
            bubblePaint.strokeWidth = if (b.r > 2f) 1.3f else 1f
            canvas.drawCircle(b.x, b.y, b.r, bubblePaint)
            if (b.r > 2.2f) {
                noAaPaint.color = 0x99FFFFFF.toInt()
                canvas.drawRect(b.x - 0.8f, b.y - b.r * 0.5f, b.x, b.y - b.r * 0.1f, noAaPaint)
            }
        }
    }

    private fun drawFood(canvas: Canvas) {
        for (f in food) {
            noAaPaint.color = 0xFFB8742A.toInt()
            canvas.drawRect(f.x - 1.6f, f.y - 1.2f, f.x + 1.6f, f.y + 1.8f, noAaPaint)
            noAaPaint.color = 0xFFFFD166.toInt()
            canvas.drawRect(f.x - 1.2f, f.y - 0.8f, f.x + 1.2f, f.y + 0.4f, noAaPaint)
        }
    }

    private fun drawSparkles(canvas: Canvas) {
        for (s in sparkles) {
            val alpha = (s.life / s.total * 255).toInt().coerceIn(0, 255)
            noAaPaint.color = Color.argb(alpha, 255, 230, 150)
            val size = 1f + (1f - s.life / s.total) * 1.8f
            canvas.drawRect(s.x - size, s.y, s.x + size, s.y + 0.8f, noAaPaint)
            canvas.drawRect(s.x, s.y - size, s.x + 0.8f, s.y + size, noAaPaint)
        }
    }

    private fun drawSurface(canvas: Canvas) {
        strokePaint.color = 0x55DFF6E8
        strokePaint.strokeWidth = 1.2f
        for (layer in 0..2) {
            surfacePath.reset()
            surfacePath.moveTo(0f, 4f + layer * 2.5f)
            var x = 0f
            while (x <= width) {
                val y = 4f + layer * 2.5f + sin(x * 0.035f + time * 1.3f + layer * 0.9f) * 2.2f
                surfacePath.lineTo(x, y)
                x += 8f
            }
            canvas.drawPath(surfacePath, strokePaint)
        }
    }

    private data class ScenePalette(
        val waterTop: Int,
        val waterMid: Int,
        val waterLow: Int,
        val waterDeep: Int,
        val hill: Int,
        val sandMain: Int,
        val sandLight: Int,
        val sandRidge: Int,
        val sandEdge: Int,
        val rockLight: Int,
        val rockMid: Int,
        val rockDark: Int,
        val kelpBackDark: Int,
        val kelpBackMid: Int,
        val kelpBackLight: Int,
        val kelpFrontDark: Int,
        val kelpFrontMid: Int,
        val kelpFrontLight: Int
    )

    private fun paletteFor(value: AquariumTheme): ScenePalette = when (value) {
        AquariumTheme.REEF -> ScenePalette(
            0xFF4AA6A0.toInt(), 0xFF2F7E85.toInt(), 0xFF1B5866.toInt(), 0xFF123F4D.toInt(),
            0xFF194B59.toInt(),
            0xFFE3C07A.toInt(), 0xFFF2D79A.toInt(), 0xFFC79F5F.toInt(), 0xFFD9B36C.toInt(),
            0xFF74867F.toInt(), 0xFF536968.toInt(), 0xFF3B5254.toInt(),
            0xFF255F45.toInt(), 0xFF38794E.toInt(), 0xFF69A85F.toInt(),
            0xFF204D39.toInt(), 0xFF2E6544.toInt(), 0xFF4C8A55.toInt()
        )
        AquariumTheme.KELP_FOREST -> ScenePalette(
            0xFF3D9B87.toInt(), 0xFF2A746D.toInt(), 0xFF17545A.toInt(), 0xFF0D3943.toInt(),
            0xFF123F3C.toInt(),
            0xFFCDB77C.toInt(), 0xFFE3D093.toInt(), 0xFFB09255.toInt(), 0xFFC3AA6A.toInt(),
            0xFF6F8D76.toInt(), 0xFF4D6C5A.toInt(), 0xFF354E42.toInt(),
            0xFF1F593C.toInt(), 0xFF2F7A4B.toInt(), 0xFF5AA45A.toInt(),
            0xFF163F2F.toInt(), 0xFF245B3D.toInt(), 0xFF3F7E4B.toInt()
        )
        AquariumTheme.SUNKEN_WRECK -> ScenePalette(
            0xFF3D7D8D.toInt(), 0xFF265E74.toInt(), 0xFF173F58.toInt(), 0xFF0B2B3E.toInt(),
            0xFF102F3A.toInt(),
            0xFFA7B49D.toInt(), 0xFFC2CDB4.toInt(), 0xFF84957F.toInt(), 0xFF98A78C.toInt(),
            0xFF7A7D74.toInt(), 0xFF565A54.toInt(), 0xFF373B38.toInt(),
            0xFF1B4B3F.toInt(), 0xFF2B6250.toInt(), 0xFF4F8A66.toInt(),
            0xFF123A32.toInt(), 0xFF1F4D3D.toInt(), 0xFF376E51.toInt()
        )
        AquariumTheme.RIVERBED -> ScenePalette(
            0xFF6AA679.toInt(), 0xFF4B8763.toInt(), 0xFF2D624B.toInt(), 0xFF194535.toInt(),
            0xFF1D4635.toInt(),
            0xFFC6A66C.toInt(), 0xFFDEC58C.toInt(), 0xFFA5824E.toInt(), 0xFFB6935B.toInt(),
            0xFF8B7A5E.toInt(), 0xFF665540.toInt(), 0xFF45392B.toInt(),
            0xFF2E6641.toInt(), 0xFF468552.toInt(), 0xFF74B269.toInt(),
            0xFF204B32.toInt(), 0xFF346A41.toInt(), 0xFF578F52.toInt()
        )
    }

    private companion object {
        const val FISH_MESH_COLS = 16
        const val FISH_MESH_ROWS = 2
    }

    // ------------------------------------------------------------------ text

    private fun drawHints(canvas: Canvas) {
        if (!loaded) {
            drawPlaque(canvas, "FILLING THE TANK...", "", "", 0xFFFFD166.toInt())
            return
        }
        if (fish.isEmpty()) {
            val address = if (serverRunning && serverIp != "0.0.0.0") "$serverIp:$serverPort" else "CHECK THE TV'S WI-FI"
            drawPlaque(
                canvas,
                "THE TANK IS EMPTY",
                "SCAN A LEGO FISH WITH THE PHONE APP",
                "AND SEND IT OVER - $address",
                0xFFFFD166.toInt()
            )
        }

        labelPaint.textSize = 7.5f
        labelPaint.color = 0x88FFF4D6.toInt()
        labelPaint.textAlign = Paint.Align.LEFT
        val countText = if (fish.size == 1) "1 FISH" else "${fish.size} FISH"
        canvas.drawText(countText, 11f, height - 7f, labelPaint)

        if (serverRunning && serverIp != "0.0.0.0") {
            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.color = 0x66FFF4D6.toInt()
            canvas.drawText("$serverIp:$serverPort", width - 11f, height - 7f, labelPaint)
        }

        if (sceneAge < 16f && fish.isNotEmpty()) {
            val alpha = (((16f - sceneAge) / 4f).coerceAtMost(1f) * 0.55f * 255).toInt()
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.color = Color.argb(alpha, 255, 244, 214)
            canvas.drawText("PRESS OK TO FEED", width / 2f, height - 7f, labelPaint)
        }
        if (feedFlash > 0f) {
            val alpha = (feedFlash / 1.4f * 180).toInt()
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.color = Color.argb(alpha, 255, 209, 102)
            labelPaint.textSize = 10f
            canvas.drawText("YUM!", width / 2f, height * 0.2f, labelPaint)
        }
        labelPaint.textSize = 7f
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = 0xBBFFF4D6.toInt()
        val themeLabel = "THEME: ${currentTheme.displayName}   LEFT/RIGHT"
        val themeWidth = labelPaint.measureText(themeLabel)
        panelPaint.color = 0x66081A22
        canvas.drawRoundRect(8f, 4f, themeWidth + 16f, 19f, 4f, 4f, panelPaint)
        canvas.drawText(themeLabel, 12f, 15f, labelPaint)
    }

    private fun drawPlaque(canvas: Canvas, title: String, line1: String, line2: String, accent: Int) {
        val boxWidth = 468f
        val boxHeight = if (line2.isBlank()) 84f else 94f
        val left = (width - boxWidth) / 2f
        val top = height * 0.34f
        panelPaint.color = 0xD90E2A36.toInt()
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 12f, 12f, panelPaint)
        panelBorder.color = 0xAAFFD166.toInt()
        panelBorder.strokeWidth = 1.5f
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 12f, 12f, panelBorder)

        titlePaint.color = accent
        titlePaint.textSize = 17f
        canvas.drawText(title, width / 2f, top + 27f, titlePaint)
        bodyPaint.color = 0xFFFFF4D6.toInt()
        bodyPaint.textSize = 8f
        if (line1.isNotBlank()) canvas.drawText(line1, width / 2f, top + 48f, bodyPaint)
        if (line2.isNotBlank()) canvas.drawText(line2, width / 2f, top + 64f, bodyPaint)
        bodyPaint.textSize = 7f
        bodyPaint.color = 0x88FFF4D6.toInt()
        canvas.drawText("AQUABRICKS", width / 2f, top + boxHeight - 10f, bodyPaint)
    }

    private fun nextBanner() {
        banner = bannerQueue.pollFirst()
        bannerAge = 0f
    }

    private fun drawBanner(canvas: Canvas) {
        val current = banner ?: return
        val easeIn = (bannerAge / 0.35f).coerceIn(0f, 1f)
        val easeOut = ((4.8f - bannerAge) / 0.5f).coerceIn(0f, 1f)
        val alpha = (min(easeIn, easeOut) * 235).toInt()
        if (alpha <= 0) return
        val boxWidth = 380f
        val boxHeight = 54f
        val left = (width - boxWidth) / 2f
        val top = 22f - (1f - easeIn) * 10f
        panelPaint.color = Color.argb((alpha * 0.9f).toInt(), 14, 42, 54)
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 11f, 11f, panelPaint)
        panelBorder.color = Color.argb(alpha, 255, 209, 102)
        panelBorder.strokeWidth = 1.5f
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 11f, 11f, panelBorder)
        titlePaint.color = Color.argb(alpha, 255, 209, 102)
        titlePaint.textSize = 13f
        canvas.drawText(current.title, width / 2f, top + 24f, titlePaint)
        bodyPaint.color = Color.argb((alpha * 0.85f).toInt(), 255, 244, 214)
        bodyPaint.textSize = 7.5f
        canvas.drawText(current.subtitle, width / 2f, top + 40f, bodyPaint)
    }

    // --------------------------------------------------------------- effects

    private fun spawnBubbles(x: Float, y: Float, count: Int) {
        repeat(count) { bubbles.add(Bubble(x + (rand.nextFloat() - 0.5f) * 12f, y + (rand.nextFloat() - 0.5f) * 8f, rand)) }
    }

    private fun spawnSparkles(x: Float, y: Float, count: Int) {
        repeat(count) {
            val angle = rand.nextFloat() * 6.283f
            val speed = 14f + rand.nextFloat() * 26f
            sparkles.add(
                Sparkle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    total = 0.55f + rand.nextFloat() * 0.65f
                )
            )
        }
    }

    private class Bubble(x: Float, y: Float, rng: Random) {
        var x = x
        var y = y
        val r = 1.2f + rng.nextFloat() * 2.6f
        val speed = 9f + rng.nextFloat() * 15f
        val phase = rng.nextFloat() * 6.283f
        var age = 0f

        fun update(dt: Float) {
            age += dt
            y -= speed * dt
            x += sin(age * 2.1f + phase) * 5f * dt
        }
    }

    private class Sparkle(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val total: Float
    ) {
        var life = total

        fun update(dt: Float) {
            x += vx * dt
            y += vy * dt
            life -= dt
        }
    }

    private class Plankton(
        var x: Float,
        var y: Float,
        val phase: Float,
        val speed: Float
    ) {
        fun update(dt: Float) {
            x += sin(phase + y * 0.01f) * speed * dt
            y -= speed * 0.18f * dt
            if (y < 25f) y = 300f
            if (x < 0f) x = 640f
            if (x > 640f) x = 0f
        }
    }

    private data class Banner(val title: String, val subtitle: String)
}
