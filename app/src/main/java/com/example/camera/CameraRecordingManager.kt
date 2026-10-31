package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraRecordingManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var currentTargetRotation: Int = Surface.ROTATION_0
    private var liveHudEffect: OverlayEffect? = null

    // Background thread and double-buffer bitmaps for zero-GC HUD overlay encoding
    private val hudGlThread = HandlerThread("CamRider-OpenGL").apply { start() }
    private val hudGlHandler = Handler(hudGlThread.looper)
    private val hudRenderThread = HandlerThread("CamRider-HUD-Raster").apply { start() }
    private val hudRenderHandler = Handler(hudRenderThread.looper)

    private val hudBitmapLock = Any()
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var activeFrontBitmap: Bitmap? = null
    private var currentAllocatedWidth = 0
    private var currentAllocatedHeight = 0
    private var cachedHudLastRenderMs = 0L
    private var hudRenderPending = false

    // WakeLock to keep CPU and encoders awake so audio/video recording never gets cut off
    private var wakeLock: PowerManager.WakeLock? = null

    /** Supplies the latest GPS values to the encoder-side HUD for every video frame. */
    var telemetryProvider: (() -> GpsTelemetry)? = null
    /** Current UI language used for the encoder-burned HUD. */
    var languageProvider: (() -> String)? = null
    var speedUnitLabelProvider: (() -> String)? = null
    var videoProfileLabelProvider: (() -> String)? = null
    var overlayConfigProvider: (() -> OverlayConfig)? = null
    private var activeRecording: Recording? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var isTorchOn = false
    private var isNightMode = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _currentLens = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val currentLens: StateFlow<Int> = _currentLens.asStateFlow()

    private val _isNightBoostActive = MutableStateFlow(false)
    val isNightBoostActive: StateFlow<Boolean> = _isNightBoostActive.asStateFlow()

    private val _isTorchActive = MutableStateFlow(false)
    val isTorchActive: StateFlow<Boolean> = _isTorchActive.asStateFlow()

    private var recordingStartTime = 0L

    var onRecordingSaved: ((videoUri: String, displayPath: String, durationSeconds: Long) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null

    private var isBindingInProgress = false

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: VideoResolution = VideoResolution.RES_1080P
    ) {
        if (isBindingInProgress) return
        isBindingInProgress = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                startCamera(lifecycleOwner, previewView, resolution)
            } catch (e: Exception) {
                Log.e("CameraRecordingManager", "Failed to get CameraProvider", e)
                onRecordingError?.invoke("Kamera tidak dapat dimulai: ${e.message}")
            } finally {
                isBindingInProgress = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: VideoResolution
    ) {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build()

            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            currentTargetRotation = targetRotation

            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            previewUseCase = preview

            // Quality selector matching user choice, checking actual supported profiles first
            val preferredQuality = when (resolution) {
                VideoResolution.RES_4K -> Quality.UHD
                VideoResolution.RES_1080P -> Quality.FHD
                VideoResolution.RES_720P -> Quality.HD
            }

            val supportedQualities = try {
                val cameraInfo = provider.getCameraInfo(cameraSelector)
                QualitySelector.getSupportedQualities(cameraInfo)
            } catch (e: Throwable) {
                emptyList()
            }

            val qualitySelector = if (supportedQualities.isNotEmpty()) {
                val candidate = if (supportedQualities.contains(preferredQuality)) {
                    preferredQuality
                } else {
                    supportedQualities.firstOrNull() ?: Quality.LOWEST
                }
                QualitySelector.from(
                    candidate,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                )
            } else {
                QualitySelector.from(
                    preferredQuality,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.LOWEST)
                )
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.Builder(recorder)
                .setTargetRotation(targetRotation)
                .build()

            // Real-time HUD effect overlaid directly into the encoded video
            liveHudEffect?.close()
            val hudEffect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                1, // 1-frame queue depth prevents buffer starvation and avoids EGL context query clashes
                hudGlHandler
            ) { throwable ->
                Log.w("CameraRecordingManager", "OpenGL HUD effect notice", throwable)
            }

            hudEffect.setOnDrawListener { frame ->
                try {
                    val outputCanvas = frame.overlayCanvas
                    outputCanvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    val frameWidth = outputCanvas.width
                    val frameHeight = outputCanvas.height
                    val now = SystemClock.elapsedRealtime()

                    if (frameWidth > 0 && frameHeight > 0) {
                        // Double-buffering without runtime GC allocations
                        synchronized(hudBitmapLock) {
                            val bitmapScale = when {
                                maxOf(frameWidth, frameHeight) >= 3000 -> 1.00f
                                maxOf(frameWidth, frameHeight) >= 1800 -> 1.00f
                                else -> 1.00f
                            }
                            val targetW = (frameWidth * bitmapScale).toInt().coerceAtLeast(1)
                            val targetH = (frameHeight * bitmapScale).toInt().coerceAtLeast(1)

                            if (bufferA == null || bufferB == null ||
                                currentAllocatedWidth != targetW || currentAllocatedHeight != targetH
                            ) {
                                bufferA?.recycle()
                                bufferB?.recycle()
                                bufferA = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                bufferB = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                activeFrontBitmap = bufferA
                                currentAllocatedWidth = targetW
                                currentAllocatedHeight = targetH
                            }

                            val stale = (now - cachedHudLastRenderMs) >= 70L // ~14 FPS HUD refresh is smooth & saves CPU
                            if (stale && !hudRenderPending) {
                                hudRenderPending = true
                                val backBuffer = if (activeFrontBitmap === bufferA) bufferB else bufferA
                                val crop = android.graphics.Rect(frame.cropRect)
                                val rotation = frame.rotationDegrees
                                val telemetry = telemetryProvider?.invoke() ?: GpsTelemetry()
                                val unit = speedUnitLabelProvider?.invoke() ?: "km/h"
                                val language = languageProvider?.invoke() ?: "id"

                                hudRenderHandler.post {
                                    try {
                                        synchronized(hudBitmapLock) {
                                            val target = backBuffer
                                            if (target != null && !target.isRecycled &&
                                                (target === bufferA || target === bufferB)
                                            ) {
                                                target.eraseColor(AndroidColor.TRANSPARENT)
                                                val rasterCanvas = Canvas(target)
                                                rasterCanvas.scale(bitmapScale, bitmapScale)
                                                drawLiveHud(
                                                    canvas = rasterCanvas,
                                                    telemetry = telemetry,
                                                    unit = unit,
                                                    language = language,
                                                    cropRect = crop,
                                                    rotationDegrees = rotation
                                                )
                                                activeFrontBitmap = target
                                                cachedHudLastRenderMs = SystemClock.elapsedRealtime()
                                            }
                                            hudRenderPending = false
                                        }
                                    } catch (e: Throwable) {
                                        Log.e("CameraRecordingManager", "HUD render failed", e)
                                        synchronized(hudBitmapLock) { hudRenderPending = false }
                                    }
                                }
                            }

                            activeFrontBitmap?.let { front ->
                                outputCanvas.drawBitmap(
                                    front,
                                    null,
                                    RectF(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat()),
                                    null
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("CameraRecordingManager", "Overlay onDraw transient error", t)
                }
                true
            }
            liveHudEffect = hudEffect

            val useCaseGroupBuilder = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture!!)
                .addEffect(hudEffect)

            previewView.viewPort?.let { viewPort ->
                useCaseGroupBuilder.setViewPort(viewPort)
            }
            val useCaseGroup = useCaseGroupBuilder.build()

            try {
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    useCaseGroup
                )
            } catch (effectErr: Throwable) {
                Log.w("CameraRecordingManager", "Binding with OverlayEffect fallback triggered", effectErr)
                val fallbackGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                previewView.viewPort?.let { fallbackGroup.setViewPort(it) }
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    fallbackGroup.build()
                )
            }

            applyNightModeSettings()

        } catch (e: Exception) {
            Log.e("CameraRecordingManager", "Error binding camera lifecycle", e)
            onRecordingError?.invoke("Gagal mengaktifkan kamera: ${e.localizedMessage}")
        }
    }

    /**
     * Draws futuristic high-legibility telemetry HUD directly onto the video stream.
     * Legends, speed numbers, and coordinates are sized generously for easy reading while riding.
     */
    private fun drawLiveHud(
        canvas: Canvas,
        telemetry: GpsTelemetry,
        unit: String,
        language: String,
        cropRect: android.graphics.Rect,
        rotationDegrees: Int
    ) {
        val cropWidth = cropRect.width().toFloat()
        val cropHeight = cropRect.height().toFloat()
        if (cropWidth <= 0f || cropHeight <= 0f) return
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = rotation == 90 || rotation == 270
        val w = if (rotated) cropHeight else cropWidth
        val h = if (rotated) cropWidth else cropHeight
        val save = canvas.save()
        canvas.clipRect(cropRect)
        val transform = Matrix()
        when (rotation) {
            90 -> transform.setValues(floatArrayOf(0f, 1f, cropRect.left.toFloat(), -1f, 0f, cropRect.bottom.toFloat(), 0f, 0f, 1f))
            180 -> transform.setValues(floatArrayOf(-1f, 0f, cropRect.right.toFloat(), 0f, -1f, cropRect.bottom.toFloat(), 0f, 0f, 1f))
            270 -> transform.setValues(floatArrayOf(0f, -1f, cropRect.right.toFloat(), 1f, 0f, cropRect.top.toFloat(), 0f, 0f, 1f))
            else -> transform.setValues(floatArrayOf(1f, 0f, cropRect.left.toFloat(), 0f, 1f, cropRect.top.toFloat(), 0f, 0f, 1f))
        }
        canvas.concat(transform)

        // Adjusted base scale so all text and legends are significantly enlarged
        val scale = (w / if (h > w) 720f else 1080f).coerceIn(1.0f, 3.2f)

        // Futuristic Cockpit HUD Color Tokens
        val cyan = AndroidColor.rgb(0, 240, 255)
        val green = AndroidColor.rgb(0, 255, 136)
        val red = AndroidColor.rgb(255, 42, 85)
        val yellow = AndroidColor.rgb(255, 214, 0)
        val amber = AndroidColor.rgb(255, 158, 0)
        val white = AndroidColor.rgb(248, 250, 252)
        val muted = AndroidColor.rgb(160, 185, 205)

        // HUD panels are outlines and telemetry only; leave the camera image fully visible beneath them.
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.TRANSPARENT
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * scale
            setShadowLayer(6f * scale, 0f, 0f, cyan)
        }

        fun drawText(
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int = white,
            bold: Boolean = false,
            align: Paint.Align = Paint.Align.LEFT
        ) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size * scale
                textAlign = align
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                if (bold) setShadowLayer(4f * scale, 0f, 0f, color)
            }
            value.split("\n").forEachIndexed { index, line ->
                canvas.drawText(line, x, y + index * size * 1.20f * scale, p)
            }
        }

        fun drawPanel(rect: RectF, radius: Float = 14f, color: Int = cyan) {
            canvas.drawRoundRect(rect, radius * scale, radius * scale, panelPaint)
            val p = Paint(borderPaint).apply {
                this.color = color
                setShadowLayer(7f * scale, 0f, 0f, color)
            }
            canvas.drawRoundRect(rect, radius * scale, radius * scale, p)
        }

        val margin = 20f * scale
        val top = 14f * scale
        val headerHeight = 54f * scale

        // LAYER 1: Futuristic Header Bar (Recording Status, Video Profile, GPS, Battery, Clock)
        // Left REC Pill
        val recPill = RectF(margin, top, margin + 210f * scale, top + headerHeight)
        drawPanel(recPill, 16f, red)
        canvas.drawCircle(recPill.left + 22f * scale, top + 27f * scale, 7f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; setShadowLayer(10f * scale, 0f, 0f, red) })
        drawText("● REC VIDEO", margin + 38f * scale, top + 34f * scale, 15f, red, true)

        // GPS + battery stack directly beneath the recording status pill.
        val rightWidth = 240f * scale
        val gpsPanelTop = top + headerHeight + 5f * scale
        val rightPanel = RectF(margin, gpsPanelTop, margin + rightWidth, gpsPanelTop + 38f * scale)
        val gpsColor = if (telemetry.isGpsFixed) green else yellow
        drawPanel(rightPanel, 12f, gpsColor)

        val gpsLabel = if (telemetry.isGpsFixed) "GPS LOCK (${telemetry.satellitesCount})" else "SEARCHING GPS..."
        canvas.drawCircle(rightPanel.left + 18f * scale, gpsPanelTop + 19f * scale, 6f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gpsColor; setShadowLayer(8f * scale, 0f, 0f, gpsColor) })
        drawText(gpsLabel, rightPanel.left + 30f * scale, gpsPanelTop + 24f * scale, 11.5f, gpsColor, true)

        // Battery stays on the same GPS row, aligned to the far right.
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val batteryScale = battery?.getIntExtra("scale", 100) ?: 100
        val batteryText = if (level >= 0 && batteryScale > 0) "${level * 100 / batteryScale}%" else "100%"
        val bx = rightPanel.right - 62f * scale
        val by = gpsPanelTop + 9f * scale
        val batteryOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 2.2f * scale }
        canvas.drawRoundRect(RectF(bx, by, bx + 20f * scale, by + 18f * scale), 4f * scale, 4f * scale, batteryOutline)
        canvas.drawRect(bx + 20f * scale, by + 5f * scale, bx + 24f * scale, by + 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        val fill = (level.coerceIn(0, 100) / 100f) * 14f
        canvas.drawRect(bx + 3f * scale, by + 3f * scale, bx + (3f + fill) * scale, by + 15f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        drawText(batteryText, bx + 29f * scale, gpsPanelTop + 24f * scale, 10.5f, green, true)

        // Video resolution / frame-rate indicator directly beneath GPS + battery.
        val techHeight = 30f * scale
        val techTop = gpsPanelTop + 43f * scale
        val techPill = RectF(margin, techTop, margin + rightWidth, techTop + techHeight)
        drawPanel(techPill, 8f, cyan)
        drawText(videoProfileLabelProvider?.invoke() ?: "1080p 60FPS H.265",
            techPill.centerX(), techTop + 20f * scale, 11.5f, white, true, Paint.Align.CENTER)

        // Compass HUD intentionally omitted from recorded video.

        // Telemetry dock remains on the left, below the GPS/video-profile stack.
        // LAYER 3: Left Side - Vertical Telemetry Dock (Narrowed compact layout)
        val telemTop = techTop + techHeight + 12f * scale
        val telemWidth = 175f * scale
        val telemHeight = 280f * scale
        val telemRect = RectF(margin, telemTop, margin + telemWidth, telemTop + telemHeight)
        drawPanel(telemRect, 14f, cyan)

        val unitMultiplier = if (unit.equals("mph", ignoreCase = true)) 0.621371f else 1f
        val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val coordFormatted = String.format(Locale.US, "%.3f, %.3f", telemetry.latitude, telemetry.longitude)
        val altFormatted = "${telemetry.altitudeMeters.toInt()} m"
        val distFormatted = String.format(Locale.US, "%.2f km", telemetry.distanceKm)
        val maxFormatted = "${(telemetry.maxSpeedKmh * unitMultiplier).toInt()} $unit"
        val avgFormatted = "${(telemetry.avgSpeedKmh * unitMultiplier).toInt()} $unit"

        val telemItems = listOf(
            Triple(if (language == "en") "TIME" else "WAKTU", timeFormatted, ""),
            Triple(if (language == "en") "COORDINATES" else "KOORDINAT", coordFormatted, ""),
            Triple(if (language == "en") "ALTITUDE" else "ELEVASI", altFormatted, if (telemetry.isGpsFixed) "FIX" else "--"),
            Triple(if (language == "en") "DISTANCE" else "JARAK", distFormatted, ""),
            Triple("TOP SPEED", maxFormatted, ""),
            Triple(if (language == "en") "AVERAGE" else "RATA-RATA", avgFormatted, "")
        )

        val slotHeight = (telemHeight - 16f * scale) / telemItems.size
        telemItems.forEachIndexed { idx, item ->
            val slotY = telemTop + 8f * scale + idx * slotHeight
            val valColor = when (idx) {
                1 -> cyan
                3 -> yellow
                4 -> amber
                5 -> green
                else -> white
            }
            drawText(item.first, margin + 12f * scale, slotY + 12f * scale, 11f, muted, true)
            drawText(item.second, margin + 12f * scale, slotY + 28f * scale, 13.5f, valColor, true)
            // Match the live preview: show the calendar date directly beneath the clock.
            // This is part of the TIME field and remains inside its existing telemetry slot.
            if (idx == 0 && overlayConfigProvider?.invoke()?.showDateTime != false) {
                drawText(dateFormatted, margin + 12f * scale, slotY + 39f * scale, 9f, muted, false)
            }
            if (item.third.isNotEmpty()) {
                drawText(item.third, margin + telemWidth - 12f * scale, slotY + 28f * scale, 10f, white, false, Paint.Align.RIGHT)
            }
            if (idx < telemItems.size - 1) {
                canvas.drawLine(
                    margin + 10f * scale,
                    slotY + slotHeight,
                    margin + telemWidth - 10f * scale,
                    slotY + slotHeight,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.argb(45, 0, 240, 255)
                        strokeWidth = 1f * scale
                    }
                )
            }
        }

        // LAYER 4: Speedometer Gauge (Position dynamically synchronized with dashboard placement)
        val config = overlayConfigProvider?.invoke()
        if (config?.showSpeedometer != false) {
            val speed = (telemetry.speedKmh * unitMultiplier).coerceIn(0f, 200f)
            val radius = minOf(w * 0.21f, h * 0.15f).coerceAtLeast(92f * scale)
            val ratioX = config?.speedometerXRatio ?: 0.65f
            val ratioY = config?.speedometerYRatio ?: 0.38f
            val cx = (w * ratioX).coerceIn(radius + 15f * scale, w - radius - 15f * scale)
            val cy = (h * ratioY).coerceIn(radius + 15f * scale, h - radius - 85f * scale)
            val dial = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            style = Paint.Style.STROKE
            strokeWidth = 6f * scale
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(12f * scale, 0f, 0f, cyan)
        }
        canvas.drawArc(dial, 135f, 270f, false, arc)
        canvas.drawArc(dial, 135f + 270f * 0.82f, 48.6f, false,
            Paint(arc).apply { color = red; strokeWidth = 7f * scale; setShadowLayer(14f * scale, 0f, 0f, red) })

        // Speedometer Ticks with 0-200 speed scale numbers (every 20 km/h)
        for (i in 0..40) {
            val angle = Math.toRadians(135.0 + 270.0 * i / 40.0)
            val isMajor = i % 4 == 0 // 0, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200
            val isMid = i % 2 == 0 // every 10 km/h
            val speedAtTick = i * 5
            val inner = radius - (if (isMajor) 18f else if (isMid) 11f else 6f) * scale
            val outer = radius - 3f * scale
            val isRedline = speedAtTick >= 160
            val tickColor = if (isRedline) red else if (isMajor) cyan else AndroidColor.argb(110, 0, 240, 255)
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tickColor
                strokeWidth = (if (isMajor) 3.2f else if (isMid) 1.8f else 1.0f) * scale
                strokeCap = Paint.Cap.ROUND
                if (isMajor) setShadowLayer(6f * scale, 0f, 0f, tickColor)
            }
            canvas.drawLine(
                cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer,
                tick
            )
            if (isMajor) {
                val numColor = when {
                    speedAtTick >= 160 -> red
                    speedAtTick >= 100 -> yellow
                    else -> cyan
                }
                drawText(
                    speedAtTick.toString(),
                    cx + kotlin.math.cos(angle).toFloat() * (radius - 36f * scale),
                    cy + kotlin.math.sin(angle).toFloat() * (radius - 36f * scale) + 5f * scale,
                    12.5f,
                    numColor,
                    true,
                    Paint.Align.CENTER
                )
            }
        }

        // Short perimeter needle follows the outside of the scale; no center spindle.
        val needleAngle = Math.toRadians(135.0 + 270.0 * speed / 200f)
        val baseRadius = radius * 0.94f
        val tipRadius = radius * 0.68f
        val baseX = cx + kotlin.math.cos(needleAngle).toFloat() * baseRadius
        val baseY = cy + kotlin.math.sin(needleAngle).toFloat() * baseRadius
        val tipX = cx + kotlin.math.cos(needleAngle).toFloat() * tipRadius
        val tipY = cy + kotlin.math.sin(needleAngle).toFloat() * tipRadius
        canvas.drawLine(baseX, baseY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(105, 255, 214, 0)
            strokeWidth = 13f * scale
            strokeCap = Paint.Cap.ROUND
        })
        canvas.drawLine(baseX, baseY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = yellow
            strokeWidth = 5.5f * scale
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(7f * scale, 0f, 0f, yellow)
        })
        canvas.drawLine(baseX, baseY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(255, 255, 230)
            strokeWidth = 1.8f * scale
            strokeCap = Paint.Cap.ROUND
        })

        // Digital speed is centered in the dial; no central pivot graphic.
        drawText(speed.toInt().toString(), cx, cy + 12f * scale, 39f, white, true, Paint.Align.CENTER)
        drawText(unit.uppercase(Locale.ROOT), cx, cy + 32f * scale, 11f, cyan, true, Paint.Align.CENTER)

        val digitalY = cy + radius + 10f * scale
        val gearStr = if (telemetry.gear.isNotEmpty() && telemetry.gear != "--") "GEAR ${telemetry.gear} • ${telemetry.rpm} RPM" else "GEAR / RPM --"
        if (config?.showGearRpm != false) {
            drawText(gearStr, cx, digitalY + 15f * scale, 11.5f, green, true, Paint.Align.CENTER)
        }
        }

        // Bottom area remains empty, keeping road view completely unobstructed
        canvas.restoreToCount(save)
    }

    /**
     * Keep CameraX output rotation in sync with the device/display orientation.
     */
    fun updateTargetRotation(rotation: Int) {
        if (rotation == currentTargetRotation) return
        currentTargetRotation = rotation
        try {
            previewUseCase?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
            Log.i("CameraRecordingManager", "Updated video target rotation: $rotation")
        } catch (e: Exception) {
            Log.e("CameraRecordingManager", "Failed to update target rotation", e)
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, resolution: VideoResolution) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _currentLens.value = currentLensFacing
        startCamera(lifecycleOwner, previewView, resolution)
    }

    fun toggleNightMode() {
        isNightMode = !isNightMode
        _isNightBoostActive.value = isNightMode
        applyNightModeSettings()
    }

    private fun applyNightModeSettings() {
        val camera = activeCamera ?: return
        val control = camera.cameraControl
        val info = camera.cameraInfo

        val exposureState: ExposureState = info.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val range = exposureState.exposureCompensationRange
            val targetIndex = if (isNightMode) {
                (range.upper * 0.75f).toInt()
            } else {
                0
            }
            try {
                control.setExposureCompensationIndex(targetIndex)
            } catch (e: Exception) {
                Log.w("CameraRecordingManager", "Could not set exposure compensation", e)
            }
        }
    }

    fun toggleTorch() {
        val camera = activeCamera ?: return
        if (camera.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            camera.cameraControl.enableTorch(isTorchOn)
            _isTorchActive.value = isTorchOn
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(audioEnabled: Boolean = true) {
        val vc = videoCapture ?: run {
            onRecordingError?.invoke("Kamera belum siap untuk merekam")
            return
        }

        if (activeRecording != null || _isRecording.value) return

        // Acquire WakeLock so recording is never suspended or interrupted
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamRiderPro:RecordingWakeLock")?.apply {
                acquire(4 * 3600 * 1000L) // 4 hours maximum timeout
            }
        } catch (e: Exception) {
            Log.w("CameraRecordingManager", "WakeLock acquisition notice", e)
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "CAMRIDERPRO_$timeStamp.mp4"
        val storageDisplayPath = "0://Movies/Camriderpro/$fileName"

        // Ensure Movies/Camriderpro directory exists
        try {
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Camriderpro"
            )
            if (!moviesDir.exists()) {
                moviesDir.mkdirs()
            }
        } catch (e: Exception) {
            Log.w("CameraRecordingManager", "Could not create Movies/Camriderpro directory", e)
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camriderpro")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recordingStartTime = System.currentTimeMillis()

        var pending = vc.output.prepareRecording(context, outputOptions)

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (audioEnabled && hasAudioPermission) {
            try {
                pending = pending.withAudioEnabled()
            } catch (e: Exception) {
                Log.w("CameraRecordingManager", "Audio recording enable notice", e)
            }
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                    _recordingDurationSeconds.value = 0L
                }
                is VideoRecordEvent.Status -> {
                    val durationNanos = event.recordingStats.recordedDurationNanos
                    _recordingDurationSeconds.value = durationNanos / 1_000_000_000L
                }
                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    val duration = _recordingDurationSeconds.value
                    if (!event.hasError()) {
                        val outputUri = event.outputResults.outputUri
                        onRecordingSaved?.invoke(outputUri.toString(), storageDisplayPath, duration)
                    } else {
                        Log.e("CameraRecordingManager", "Video recording finalized code: ${event.error}")
                        if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                            onRecordingError?.invoke("Perekaman berhenti: status ${event.error}")
                        }
                    }
                    activeRecording = null
                    _recordingDurationSeconds.value = 0L
                    releaseWakeLock()
                }
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    fun stopRecording() {
        try {
            activeRecording?.stop()
        } catch (_: Exception) {}
        activeRecording = null
        _isRecording.value = false
        releaseWakeLock()
    }

    fun release() {
        stopRecording()
        cameraProvider?.unbindAll()
        liveHudEffect?.close()
        liveHudEffect = null
        hudRenderHandler.removeCallbacksAndMessages(null)
        hudGlHandler.removeCallbacksAndMessages(null)
        synchronized(hudBitmapLock) {
            bufferA?.recycle()
            bufferB?.recycle()
            bufferA = null
            bufferB = null
            activeFrontBitmap = null
            currentAllocatedWidth = 0
            currentAllocatedHeight = 0
            hudRenderPending = false
        }
        hudRenderThread.quitSafely()
        hudGlThread.quitSafely()
    }
}
