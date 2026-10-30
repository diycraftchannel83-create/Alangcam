package com.example.ui

import android.app.Application
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraRecordingManager
import com.example.data.local.RideDatabase
import com.example.data.local.RideEntity
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.RecordingSettings
import com.example.data.model.RideSession
import com.example.telemetry.GpsSpeedTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiderCamViewModel(application: Application) : AndroidViewModel(application) {

    val gpsTracker = GpsSpeedTracker(application)
    val cameraManager = CameraRecordingManager(application)
    val videoExporter = com.example.export.TelemetryVideoExporter(application)
    private val rideDao = RideDatabase.getInstance(application).rideDao()

    val telemetry: StateFlow<GpsTelemetry> = gpsTracker.telemetry
    val isRecording: StateFlow<Boolean> = cameraManager.isRecording
    val recordingDurationSeconds: StateFlow<Long> = cameraManager.recordingDurationSeconds
    val isNightBoostActive: StateFlow<Boolean> = cameraManager.isNightBoostActive
    val isTorchActive: StateFlow<Boolean> = cameraManager.isTorchActive

    private val _isScreenLocked = MutableStateFlow(false)
    val isScreenLocked: StateFlow<Boolean> = _isScreenLocked.asStateFlow()

    private val prefs = application.getSharedPreferences("camriderpro_preferences", android.content.Context.MODE_PRIVATE)
    private val _language = MutableStateFlow(prefs.getString("language", "id") ?: "id")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _settings = MutableStateFlow(RecordingSettings())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    private val _overlayConfig = MutableStateFlow(OverlayConfig())
    val overlayConfig: StateFlow<OverlayConfig> = _overlayConfig.asStateFlow()

    private val _rides = MutableStateFlow<List<RideSession>>(emptyList())
    val rides: StateFlow<List<RideSession>> = _rides.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    val exportState: StateFlow<com.example.export.ExportState> = videoExporter.exportState
    private var recordingTelemetrySnapshot: GpsTelemetry? = null
    private var voiceTts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var wasRecording = false
    private var speedAlertArmed = true
    private var lastSpeedAlertAt = 0L

    private fun speak(message: String) {
        if (!ttsReady || !_settings.value.voiceAssistantEnabled) return
        val utteranceId = "camrider-${System.currentTimeMillis()}"
        val result = voiceTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e("CamRiderVoice", "TTS rejected utterance: $utteranceId")
        }
    }

    init {
        voiceTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                // Navigation-guidance attributes are less disruptive to CameraX microphone capture
                // than the default media playback usage on devices with aggressive audio-focus policies.
                voiceTts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                voiceTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("CamRiderVoice", "TTS started: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d("CamRiderVoice", "TTS completed: $utteranceId")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("CamRiderVoice", "TTS playback failed: $utteranceId")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e("CamRiderVoice", "TTS playback failed ($errorCode): $utteranceId")
                    }
                })
                val result = voiceTts?.setLanguage(if (_language.value == "id") Locale("id", "ID") else Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    voiceTts?.setLanguage(Locale.getDefault())
                }
                voiceTts?.setSpeechRate(0.95f)
            }
        }

        telemetry.onEach { data ->
            val prefs = _settings.value
            val threshold = prefs.speedLimitKmh.toFloat()
            if (!prefs.speedLimitAlertEnabled || !prefs.voiceAssistantEnabled) {
                speedAlertArmed = true
                return@onEach
            }
            if (data.speedKmh < threshold - 3f) speedAlertArmed = true
            val now = System.currentTimeMillis()
            if (speedAlertArmed && data.speedKmh >= threshold &&
                now - lastSpeedAlertAt >= TimeUnit.SECONDS.toMillis(prefs.alertRepeatSeconds.toLong())) {
                speedAlertArmed = false
                lastSpeedAlertAt = now
                // The slider is explicitly calibrated and labelled in km/h.
                // Announce that exact configured threshold so the spoken value always
                // matches the number shown in Settings (not the fluctuating GPS speed).
                val configuredLimitKmh = prefs.speedLimitKmh
                speak(if (_language.value == "id") "Batas kecepatan diatur pada $configuredLimitKmh kilometer per jam. Mohon kurangi kecepatan." else "Speed limit is set to $configuredLimitKmh kilometers per hour. Please slow down.")
            }
        }.launchIn(viewModelScope)

        isRecording.onEach { recording ->
            if (recording && !wasRecording && _settings.value.welcomeVoiceEnabled) {
                // Short delay ensures AudioRecord stream is established without interference
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1200)
                    speak(if (_language.value == "id") "Selamat datang di CamRiderPro. Selamat berkendara dan nikmati perjalanan Anda!" else "Welcome to CamRiderPro. Happy travelling and enjoy your ride!")
                }
            }
            wasRecording = recording
        }.launchIn(viewModelScope)

        // Observe saved rides from database
        rideDao.getAllRides().onEach { entities ->
            _rides.value = entities.map { it.toRideSession() }
        }.launchIn(viewModelScope)

        // CameraX OverlayEffect reads this live snapshot while encoding each frame
        cameraManager.telemetryProvider = { telemetry.value }
        cameraManager.languageProvider = { _language.value }
        cameraManager.speedUnitLabelProvider = { _settings.value.speedUnit.label }
        cameraManager.overlayConfigProvider = { _overlayConfig.value }
        cameraManager.videoProfileLabelProvider = {
            "${_settings.value.resolution.label.substringBefore(" ")}  ${_settings.value.targetFps}FPS H.265"
        }

        // On recording saved handler
        cameraManager.onRecordingSaved = { videoUri, displayPath, duration ->
            saveRideToDatabase(videoUri, displayPath, duration)
        }

        cameraManager.onRecordingError = { msg ->
            _bannerMessage.value = msg
        }
    }

    fun updateBannerForMissingAudioPermission() {
        _bannerMessage.value = if (_language.value == "id") "Izin mikrofon tidak diberikan. Video akan direkam tanpa audio." else "Microphone permission denied. Video will be recorded without audio."
    }

    fun startGpsTracking() {
        gpsTracker.startTracking()
    }

    fun toggleRecord() {
        if (cameraManager.isRecording.value) {
            // Freeze trip metrics at the user's stop action; encoder finalization is asynchronous.
            recordingTelemetrySnapshot = telemetry.value
            cameraManager.stopRecording()
            _bannerMessage.value = if (_language.value == "id") "Perekaman selesai & tersimpan otomatis" else "Recording completed and saved automatically"
        } else {
            // Every manual recording starts with a clean trip accumulator.
            gpsTracker.resetTrip()
            recordingTelemetrySnapshot = null
            cameraManager.startRecording(_settings.value.audioEnabled)
            _bannerMessage.value = if (_language.value == "id") "Perekaman manual dimulai" else "Manual recording started"
        }
    }

    fun toggleAudio() {
        val updated = !_settings.value.audioEnabled
        _settings.value = _settings.value.copy(audioEnabled = updated)
        _bannerMessage.value = if (updated) if (_language.value == "id") "Audio mikrofon diaktifkan" else "Microphone audio enabled" else if (_language.value == "id") "Audio mikrofon dimatikan" else "Microphone audio disabled"
    }

    fun toggleNightBoost() {
        cameraManager.toggleNightMode()
        _settings.value = _settings.value.copy(nightModeEnabled = cameraManager.isNightBoostActive.value)
        _bannerMessage.value = if (cameraManager.isNightBoostActive.value) {
            if (_language.value == "id") "Mode Malam Aktif: Kompensasi eksposur ditingkatkan" else "Night Mode Active: Exposure compensation increased"
        } else {
            if (_language.value == "id") "Mode Normal" else "Normal Mode"
        }
    }

    fun toggleTorch() {
        cameraManager.toggleTorch()
    }

    fun lockScreen() {
        _isScreenLocked.value = true
    }

    fun unlockScreen() {
        _isScreenLocked.value = false
    }

    fun setLanguage(languageCode: String) {
        val normalized = if (languageCode == "en") "en" else "id"
        _language.value = normalized
        prefs.edit().putString("language", normalized).apply()
        voiceTts?.setLanguage(if (normalized == "id") Locale("id", "ID") else Locale.US)
    }

    fun updateSettings(newSettings: RecordingSettings) {
        _settings.value = newSettings
        if (!newSettings.speedLimitAlertEnabled) speedAlertArmed = true
    }

    fun updateOverlayConfig(newConfig: OverlayConfig) {
        _overlayConfig.value = newConfig
    }

    fun updateSpeedometerPosition(xRatio: Float, yRatio: Float) {
        _overlayConfig.value = _overlayConfig.value.copy(
            speedometerXRatio = xRatio.coerceIn(0.18f, 0.82f),
            speedometerYRatio = yRatio.coerceIn(0.18f, 0.78f)
        )
    }

    fun toggleSpeedometerLock() {
        val nextLock = !_overlayConfig.value.isSpeedometerLocked
        _overlayConfig.value = _overlayConfig.value.copy(isSpeedometerLocked = nextLock)
        _bannerMessage.value = if (nextLock) {
            if (_language.value == "id") "Posisi spedometer dikunci (Aman dari sentuhan)" else "Speedometer position locked (touch protected)"
        } else {
            if (_language.value == "id") "Mode Geser Aktif: Sentuh & geser spedometer ke posisi mana saja" else "Move Mode Active: Touch and drag the speedometer anywhere"
        }
    }

    fun setSpeedometerPreset(xRatio: Float, yRatio: Float, label: String) {
        _overlayConfig.value = _overlayConfig.value.copy(
            speedometerXRatio = xRatio,
            speedometerYRatio = yRatio
        )
        _bannerMessage.value = "Spedometer dipindah ke $label (Video rekaman otomatis sinkron)"
    }

    fun cycleSpeedometerPosition() {
        val current = _overlayConfig.value
        // Presets: Kanan (0.65, 0.38) -> Tengah (0.50, 0.38) -> Kanan Atas (0.68, 0.26)
        when {
            current.speedometerXRatio > 0.60f && current.speedometerYRatio > 0.32f -> {
                setSpeedometerPreset(0.50f, 0.38f, "Tengah")
            }
            current.speedometerXRatio <= 0.55f -> {
                setSpeedometerPreset(0.68f, 0.26f, "Kanan Atas")
            }
            else -> {
                setSpeedometerPreset(0.65f, 0.38f, "Kanan (Default)")
            }
        }
    }

    fun toggleSimulation() {
        if (gpsTracker.isSimulationActive()) {
            gpsTracker.stopSimulation()
            _isSimulating.value = false
            _bannerMessage.value = if (_language.value == "id") "Simulasi dimatikan: GPS asli aktif" else "Simulation disabled: Real GPS is active"
        } else {
            gpsTracker.resetTrip()
            gpsTracker.startRideSimulation()
            _isSimulating.value = true
            _bannerMessage.value = if (_language.value == "id") "Simulasi perjalanan motor aktif!" else "Motorcycle ride simulation active!"
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun deleteRide(ride: RideSession) {
        viewModelScope.launch {
            rideDao.deleteRide(RideEntity.fromRideSession(ride))
            if (ride.videoPath.startsWith("content://")) {
                try {
                    getApplication<Application>().contentResolver.delete(
                        android.net.Uri.parse(ride.videoPath), null, null
                    )
                } catch (_: Exception) {}
            } else {
                val file = File(ride.videoPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            _bannerMessage.value = if (_language.value == "id") "Rekaman perjalanan dihapus" else "Ride recording deleted"
        }
    }

    private fun saveRideToDatabase(videoUri: String, displayPath: String, durationSeconds: Long) {
        viewModelScope.launch {
            val telem = recordingTelemetrySnapshot ?: telemetry.value
            recordingTelemetrySnapshot = null
            val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())

            val session = RideSession(
                title = if (_language.value == "id") "Perjalanan Motor $dateStr" else "Motorcycle Ride $dateStr",
                videoPath = videoUri,
                timestamp = System.currentTimeMillis(),
                durationSeconds = durationSeconds.coerceAtLeast(1L),
                maxSpeedKmh = telem.maxSpeedKmh,
                avgSpeedKmh = telem.avgSpeedKmh,
                distanceKm = telem.distanceKm,
                startLat = telem.locationHistory.firstOrNull()?.lat ?: telem.latitude,
                startLon = telem.locationHistory.firstOrNull()?.lon ?: telem.longitude,
                endLat = telem.latitude,
                endLon = telem.longitude,
                fileSizeFormatted = displayPath
            )

            val rideId = rideDao.insertRide(RideEntity.fromRideSession(session))
            val savedSession = session.copy(id = rideId)
            // HUD is already burned into each encoded frame by CameraX OverlayEffect.
            _bannerMessage.value = if (_language.value == "id") "Rekaman tersimpan dengan HUD speedometer langsung di video" else "Recording saved with the speedometer HUD embedded in the video"
        }
    }

    fun exportVideoWithSpeedometer(session: RideSession) {
        viewModelScope.launch {
            _bannerMessage.value = if (_language.value == "id") "Mengekspor video dengan overlay spedometer..." else "Exporting video with speedometer overlay..."
            videoExporter.exportVideoWithSpeedometerOverlay(session, _settings.value.speedUnit)
        }
    }

    fun resetExportState() {
        videoExporter.resetState()
    }

    override fun onCleared() {
        voiceTts?.stop()
        voiceTts?.shutdown()
        voiceTts = null
        super.onCleared()
        gpsTracker.stopTracking()
        cameraManager.release()
    }
}
