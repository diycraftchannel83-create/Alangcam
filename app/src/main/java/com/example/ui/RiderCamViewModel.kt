package com.example.ui

import android.app.Application
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraRecordingManager
import com.example.ui.uiText
import com.example.billing.BillingManager
import com.example.billing.FirebaseCreditRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiderCamViewModel(application: Application) : AndroidViewModel(application) {

    val gpsTracker = GpsSpeedTracker(application)
    val cameraManager = CameraRecordingManager(application)
    private val creditRepository = FirebaseCreditRepository()
    val billingManager = BillingManager(application, creditRepository)
    val billingState: StateFlow<com.example.billing.BillingUiState> = billingManager.state
    private var creditSessionId: String? = null
    private var creditHeartbeatJob: Job? = null
    private var creditSessionInitialSeconds: Long? = null
    private var isStartingRecording = false
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

    private fun speechMessage(key: String, language: String, speedLimitKmh: Int = 0): String {
        val messages = mapOf(
            "welcome" to mapOf(
                "id" to "Selamat datang di CamRiderPro. Selamat berkendara dan nikmati perjalanan Anda!",
                "en" to "Welcome to CamRiderPro. Ride safely and enjoy your journey!",
                "ms" to "Selamat datang ke CamRiderPro. Pandu dengan selamat dan nikmati perjalanan anda!",
                "ja" to "CamRiderProへようこそ。安全運転で、旅をお楽しみください！",
                "ru" to "Добро пожаловать в CamRiderPro. Счастливого и безопасного пути!",
                "th" to "ยินดีต้อนรับสู่ CamRiderPro ขอให้ขับขี่อย่างปลอดภัยและสนุกกับการเดินทาง!",
                "vi" to "Chào mừng bạn đến với CamRiderPro. Hãy lái xe an toàn và tận hưởng hành trình!",
                "zh" to "欢迎使用 CamRiderPro。祝您一路平安，享受旅程！",
                "ko" to "CamRiderPro에 오신 것을 환영합니다. 안전하게 운전하고 즐거운 여행 되세요!",
                "ar" to "مرحبًا بك في CamRiderPro. نتمنى لك قيادة آمنة ورحلة ممتعة!"
            ),
            "speed" to mapOf(
                "id" to "Batas kecepatan diatur pada $speedLimitKmh kilometer per jam. Mohon kurangi kecepatan.",
                "en" to "Speed limit is set to $speedLimitKmh kilometers per hour. Please slow down.",
                "ms" to "Had laju ditetapkan pada $speedLimitKmh kilometer sejam. Sila perlahankan kenderaan.",
                "ja" to "制限速度は時速 $speedLimitKmh キロに設定されています。速度を落としてください。",
                "ru" to "Ограничение скорости установлено на $speedLimitKmh километров в час. Пожалуйста, снизьте скорость.",
                "th" to "ตั้งค่าจำกัดความเร็วไว้ที่ $speedLimitKmh กิโลเมตรต่อชั่วโมง กรุณาลดความเร็ว",
                "vi" to "Giới hạn tốc độ được đặt ở mức $speedLimitKmh ki-lô-mét một giờ. Vui lòng giảm tốc độ.",
                "zh" to "限速设置为每小时 $speedLimitKmh 公里。请减速慢行。",
                "ko" to "제한 속도는 시속 ${speedLimitKmh}킬로미터로 설정되어 있습니다. 속도를 줄여 주세요.",
                "ar" to "تم ضبط حد السرعة على $speedLimitKmh كيلومتر في الساعة. يرجى تخفيف السرعة."
            )
        )
        return messages[key]?.get(language) ?: messages[key]?.get("en").orEmpty()
    }

    private fun speak(message: String) {
        if (!ttsReady || !_settings.value.voiceAssistantEnabled) return
        val utteranceId = "camrider-${System.currentTimeMillis()}"
        val result = voiceTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e("CamRiderVoice", "TTS rejected utterance: $utteranceId")
        }
    }

    init {
        billingManager.connect()
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
                val selectedLocale = localeFor(_language.value)
                val result = voiceTts?.setLanguage(selectedLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Never silently switch to the device default language.
                    Log.w("CamRiderVoice", "TTS voice unavailable for selected language: ${_language.value} ($selectedLocale)")
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
                speak(speechMessage("speed", _language.value, configuredLimitKmh))
            }
        }.launchIn(viewModelScope)

        isRecording.onEach { recording ->
            if (!recording && wasRecording) finishServerCreditSession()
            if (recording && !wasRecording && _settings.value.welcomeVoiceEnabled) {
                // Short delay ensures AudioRecord stream is established without interference
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1200)
                    speak(speechMessage("welcome", _language.value))
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
        cameraManager.premiumProvider = { billingManager.state.value.isPremium }
        cameraManager.recordingCreditSecondsProvider = {
            creditSessionInitialSeconds ?: billingManager.state.value.creditSeconds
        }
        // Credit deductions are server-owned and applied by heartbeat/finish Cloud Functions.
        cameraManager.onRecordingCreditUsed = null
        cameraManager.onRecordingLimitReached = { usedCredits ->
            _bannerMessage.value = if (usedCredits) {
                uiText(_language.value, "Kredit rekam habis. Silakan top up untuk melanjutkan.", "Recording credit is exhausted. Top up to continue.")
            } else {
                uiText(_language.value, "Batas rekaman gratis 20 menit tercapai. Beli kredit untuk menambah durasi rekaman.", "The 20-minute free recording limit has been reached. Buy credits to add recording time.")
            }
        }
        cameraManager.videoProfileLabelProvider = {
            "${_settings.value.resolution.label.substringBefore(" ")}  ${_settings.value.targetFps}FPS H.265"
        }

        // On recording saved handler
        cameraManager.onRecordingSaved = { videoUri, displayPath, duration ->
            saveRideToDatabase(videoUri, displayPath, duration)
        }

        cameraManager.onRecordingError = { msg ->
            _bannerMessage.value = msg
            finishServerCreditSession()
        }
    }

    fun refreshPurchases() {\n        billingManager.refreshPurchases()\n    }\n\n    fun updateBannerForMissingAudioPermission() {
        _bannerMessage.value = uiText(_language.value, "Izin mikrofon tidak diberikan. Video akan direkam tanpa audio.", "Microphone permission denied. Video will be recorded without audio.")
    }

    fun startGpsTracking() {
        gpsTracker.startTracking()
    }

    fun toggleRecord() {
        if (cameraManager.isRecording.value) {
            // Freeze trip metrics at the user's stop action; encoder finalization is asynchronous.
            recordingTelemetrySnapshot = telemetry.value
            cameraManager.stopRecording()
            _bannerMessage.value = uiText(_language.value, "Perekaman selesai & tersimpan otomatis", "Recording completed and saved automatically")
            return
        }
        if (isStartingRecording) return
        isStartingRecording = true
        viewModelScope.launch {
            try {
                val availableSeconds = billingManager.state.value.creditSeconds.coerceAtLeast(0L)
                if (availableSeconds > 0L) {
                    creditRepository.signInAnonymouslyIfNeeded()
                    val sessionId = UUID.randomUUID().toString().replace("-", "")
                    val serverBalance = creditRepository.startCreditRecording(sessionId)
                    if (serverBalance <= 0L) {
                        throw IllegalStateException("Saldo kredit rekam kosong.")
                    }
                    creditSessionId = sessionId
                    creditSessionInitialSeconds = serverBalance
                } else {
                    creditSessionId = null
                    creditSessionInitialSeconds = null
                }

                // Every manual recording starts with a clean trip accumulator.
                gpsTracker.resetTrip()
                recordingTelemetrySnapshot = null
                cameraManager.startRecording(_settings.value.audioEnabled)
                if (creditSessionId != null) {
                    startCreditHeartbeat(creditSessionId!!)
                    _bannerMessage.value = uiText(
                        _language.value,
                        "Perekaman dimulai menggunakan kredit rekam",
                        "Recording started using recording credits"
                    )
                } else {
                    _bannerMessage.value = uiText(
                        _language.value,
                        "Perekaman gratis dimulai (maksimal 20 menit)",
                        "Free recording started (20-minute limit)"
                    )
                }
            } catch (e: Exception) {
                Log.e("CamRiderCredit", "Unable to start recording credit session", e)
                finishServerCreditSession()
                _bannerMessage.value = uiText(
                    _language.value,
                    "Tidak dapat memulai sesi kredit. Periksa koneksi internet dan saldo kredit.",
                    "Could not start a credit session. Check your internet connection and credit balance."
                )
            } finally {
                isStartingRecording = false
            }
        }
    }

    private fun startCreditHeartbeat(sessionId: String) {
        creditHeartbeatJob?.cancel()
        creditHeartbeatJob = viewModelScope.launch {
            while (isActive && creditSessionId == sessionId) {
                delay(15_000L)
                if (!cameraManager.isRecording.value || creditSessionId != sessionId) break
                try {
                    val result = creditRepository.heartbeatCreditRecording(sessionId)
                    val remaining = (result["remainingSeconds"] as? Number)?.toLong() ?: 0L
                    creditSessionInitialSeconds = remaining
                    val expired = result["expired"] == true
                    val exhausted = result["exhausted"] == true
                    if (expired || exhausted || remaining <= 0L) {
                        creditSessionId = null
                        creditSessionInitialSeconds = null
                        cameraManager.stopRecording()
                        _bannerMessage.value = uiText(
                            _language.value,
                            "Sesi kredit berakhir. Perekaman dihentikan.",
                            "Recording credit session ended. Recording stopped."
                        )
                        break
                    }
                } catch (e: Exception) {
                    Log.e("CamRiderCredit", "Credit heartbeat failed; stopping to prevent unbilled recording", e)
                    cameraManager.stopRecording()
                    _bannerMessage.value = uiText(
                        _language.value,
                        "Koneksi kredit terputus. Perekaman dihentikan demi keamanan saldo.",
                        "Credit connection lost. Recording stopped to protect your balance."
                    )
                    finishServerCreditSession()
                    break
                }
            }
        }
    }

    private fun finishServerCreditSession() {
        val sessionId = creditSessionId ?: return
        creditSessionId = null
        creditSessionInitialSeconds = null
        creditHeartbeatJob?.cancel()
        creditHeartbeatJob = null
        viewModelScope.launch {
            try {
                creditRepository.finishCreditRecording(sessionId)
            } catch (e: Exception) {
                // A later stale-session expiry is safer than charging from an untrusted client clock.
                Log.w("CamRiderCredit", "Could not finalize server credit session", e)
            }
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

    private fun localeFor(code: String): Locale = when (code) {
        "id" -> Locale("id", "ID")
        "ms" -> Locale("ms", "MY")
        "ja" -> Locale.JAPANESE
        "ru" -> Locale("ru", "RU")
        "th" -> Locale("th", "TH")
        "vi" -> Locale("vi", "VN")
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "ko" -> Locale.KOREAN
        "ar" -> Locale("ar")
        else -> Locale.US
    }

    fun setLanguage(languageCode: String) {
        val normalized = languageCode.takeIf { it in setOf("id", "en", "ms", "ja", "ru", "th", "vi", "zh", "ko", "ar") } ?: "id"
        _language.value = normalized
        prefs.edit().putString("language", normalized).apply()
        voiceTts?.setLanguage(localeFor(normalized))
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
            _bannerMessage.value = uiText(_language.value, "Mengekspor video dengan overlay spedometer...", "Exporting video with speedometer overlay...")
            videoExporter.exportVideoWithSpeedometerOverlay(session, _settings.value.speedUnit, _language.value)
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
        billingManager.close()
    }
}
