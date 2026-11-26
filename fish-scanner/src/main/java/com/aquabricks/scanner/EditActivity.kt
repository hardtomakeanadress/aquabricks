package com.aquabricks.scanner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.aquabricks.scanner.imaging.ArgbImage
import com.aquabricks.scanner.imaging.FishImageProcessor
import java.util.concurrent.Executors

class EditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"
    }

    private lateinit var cropView: CropQuadView
    private lateinit var previewImage: ImageView
    private lateinit var photoLoading: ProgressBar
    private lateinit var processSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var creatorInput: EditText
    private lateinit var sendButton: Button
    private lateinit var faceButton: Button
    private lateinit var toleranceSeek: SeekBar
    private lateinit var facingRow: LinearLayout
    private lateinit var gridRow: LinearLayout
    private lateinit var brickColourCheck: CheckBox

    private val processor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fish-processor").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { startProcessing() }

    private var workingArgb: ArgbImage? = null
    private var currentSprite: ArgbImage? = null
    private var gridCols = -1
    private var tolerance = 14
    private var facingRight = true
    private var snapBrickColours = true
    private var faceConfig: FaceConfig? = null
    private var userMovedCrop = false
    private var generation = 0
    private var processing = false
    private var facingChips: List<TextView> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        SystemBars.padTopAndBottom(findViewById(android.R.id.content))

        cropView = findViewById(R.id.cropView)
        previewImage = findViewById(R.id.previewImage)
        photoLoading = findViewById(R.id.photoLoading)
        processSpinner = findViewById(R.id.processSpinner)
        statusText = findViewById(R.id.statusText)
        nameInput = findViewById(R.id.nameInput)
        creatorInput = findViewById(R.id.creatorInput)
        sendButton = findViewById(R.id.sendButton)
        faceButton = findViewById(R.id.faceButton)
        toleranceSeek = findViewById(R.id.toleranceSeek)
        facingRow = findViewById(R.id.facingRow)
        gridRow = findViewById(R.id.gridRow)
        brickColourCheck = findViewById(R.id.brickColourCheck)

        previewImage.background = ImageUtils.checkerDrawable(this)
        sendButton.isEnabled = false
        faceButton.isEnabled = false

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.retakeButton).setOnClickListener { finish() }
        findViewById<View>(R.id.retakeButton2).setOnClickListener { finish() }
        sendButton.setOnClickListener { sendToTank() }
        faceButton.setOnClickListener { editFace() }

        setUpChips()
        brickColourCheck.setOnCheckedChangeListener { _, checked ->
            snapBrickColours = checked
            scheduleProcess(30)
        }
        toleranceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tolerance = progress
                scheduleProcess(220)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleProcess(40)
            }
        })
        cropView.onQuadChanged = {
            userMovedCrop = true
            scheduleProcess(240)
        }

        loadPhoto()
    }

    private fun setUpChips() {
        val facingLeft = PixelUi.chip(this, getString(R.string.head_left), false) {
            PixelUi.selectOnly(facingChips, 0)
            if (facingRight) {
                facingRight = false
                faceConfig = faceConfig?.mirrored()
                renderPreview()
            }
        }
        val facingRightChip = PixelUi.chip(this, getString(R.string.head_right), true) {
            PixelUi.selectOnly(facingChips, 1)
            if (!facingRight) {
                facingRight = true
                faceConfig = faceConfig?.mirrored()
                renderPreview()
            }
        }
        facingChips = listOf(facingLeft, facingRightChip)
        facingChips.forEach { facingRow.addView(it) }

        // Brick size comes from the detected stud pitch; arbitrary column counts
        // were removed so a 2x4 brick cannot be squashed into a different shape.
        val gridOptions = listOf(
            -1 to getString(R.string.grid_auto),
            0 to getString(R.string.grid_off)
        )
        val chips = ArrayList<TextView>(gridOptions.size)
        gridOptions.forEachIndexed { index, option ->
            val chip = PixelUi.chip(this, option.second, option.first == gridCols) {
                gridCols = option.first
                PixelUi.selectOnly(chips, index)
                scheduleProcess(30)
            }
            chips.add(chip)
            gridRow.addView(chip)
        }
    }

    private fun loadPhoto() {
        val path = intent.getStringExtra(EXTRA_PHOTO_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        photoLoading.visibility = View.VISIBLE
        statusText.text = getString(R.string.waiting_photo)
        processor.execute {
            val bitmap = ImageUtils.decodePhoto(path, 1600)
            if (bitmap == null) {
                runOnUiThread {
                    photoLoading.visibility = View.GONE
                    showFailure("COULD NOT LOAD THE PHOTO")
                }
                return@execute
            }
            val argb = ImageUtils.toArgb(bitmap)
            val initialQuad = try {
                FishImageProcessor.autoQuad(argb)
            } catch (_: Exception) {
                FishImageProcessor.insetQuad(argb, 0.06f)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                photoLoading.visibility = View.GONE
                workingArgb = argb
                cropView.setPhoto(bitmap)
                if (!userMovedCrop) cropView.setQuad(initialQuad)
                scheduleProcess(30)
            }
        }
    }

    private fun scheduleProcess(delayMs: Long) {
        // Invalidate the old sprite immediately, not when the delayed job runs:
        // otherwise Send stays enabled for up to 240 ms after the crop changed.
        generation++
        currentSprite = null
        previewImage.setImageDrawable(null)
        setProcessing(true)
        handler.removeCallbacks(processRunnable)
        handler.postDelayed(processRunnable, delayMs)
    }

    private fun startProcessing() {
        val source = workingArgb
        if (source == null) {
            setProcessing(false)
            return
        }
        val quad = cropView.quad()
        val options = FishImageProcessor.Options(
            tolerance = tolerance,
            gridCols = gridCols,
            snapBrickColours = snapBrickColours
        )
        val requestGeneration = generation
        setProcessing(true)
        processor.execute {
            val result = try {
                Result.success(FishImageProcessor.process(source, quad, options))
            } catch (error: Throwable) {
                Result.failure(error)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestGeneration != generation) return@runOnUiThread
                setProcessing(false)
                result.fold(
                    onSuccess = { processed ->
                        currentSprite = processed.image
                        renderPreview()
                        sendButton.isEnabled = true
                        faceButton.isEnabled = true
                        statusText.text = when {
                            processed.usedGrid && processed.autoGrid ->
                                "AUTO GRID ${processed.gridCols} x ${processed.gridRows} STUDS"
                            processed.usedGrid ->
                                "GRID ${processed.gridCols} x ${processed.gridRows} STUDS"
                            processed.brickized ->
                                "SHAPE BRICKS ${processed.gridCols} x ${processed.gridRows}"
                            else ->
                                "NATURAL SPRITE ${processed.image.width} x ${processed.image.height}"
                        }
                        statusText.setTextColor(
                            ContextCompat.getColor(this@EditActivity, R.color.cream)
                        )
                    },
                    onFailure = { error ->
                        val message = if (error is FishImageProcessor.NoFishFoundException) {
                            "NO FISH FOUND - LOWER THE CUTOUT OR CROP TIGHTER"
                        } else {
                            "COULD NOT PROCESS - ${error.message ?: "TRY AGAIN"}"
                        }
                        showFailure(message)
                    }
                )
            }
        }
    }

    private fun showFailure(message: String) {
        currentSprite = null
        previewImage.setImageDrawable(null)
        sendButton.isEnabled = false
        faceButton.isEnabled = false
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, R.color.coral))
    }

    private fun setProcessing(value: Boolean) {
        processing = value
        processSpinner.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            sendButton.isEnabled = false
            faceButton.isEnabled = false
        }
    }

    private fun renderPreview() {
        val base = currentSprite ?: return
        val composed = faceConfig?.let { FacePainter.render(base, it, facingRight) } ?: base
        previewImage.setImageBitmap(ImageUtils.toBitmap(composed))
    }

    private fun editFace() {
        val base = currentSprite ?: return
        FaceDialog(
            activity = this,
            base = base,
            initialConfig = faceConfig,
            facingRight = facingRight
        ) { updated ->
            faceConfig = if (updated.eye == EyeModel.NONE && updated.mouth == MouthModel.NONE) {
                null
            } else {
                updated
            }
            renderPreview()
        }.show()
    }

    private fun sendToTank() {
        val base = currentSprite ?: return
        if (processing) return
        val sprite = faceConfig?.let { FacePainter.render(base, it, facingRight) } ?: base
        val name = nameInput.text.toString().trim().ifBlank {
            getString(R.string.name_default)
        }.take(24)
        val creator = creatorInput.text.toString().trim().take(24)
        val bitmap = ImageUtils.toBitmap(sprite)
        val png = ImageUtils.pngBytes(bitmap)
        bitmap.recycle()
        SendDialog(
            activity = this,
            png = png,
            fishName = name,
            creator = creator,
            facing = if (facingRight) "right" else "left"
        ).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(processRunnable)
        processor.shutdownNow()
        super.onDestroy()
    }
}
