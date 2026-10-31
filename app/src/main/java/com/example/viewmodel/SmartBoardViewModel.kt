package com.example.viewmodel

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.provider.Settings
import com.example.model.AccessControlManager
import com.example.model.AccessLogEntry
import com.example.model.AuthorizedUser
import com.example.model.LessonSessionManager
import com.example.model.RoomEntry
import com.example.model.SmartBoardConfig
import com.example.model.SmartBoardPreferences
import com.example.service.FloatingSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class BoardScreenMode {
    STANDBY_LOCK,       // Qulflangan kutish ekrani (Jonli fon, soat, QR kod)
    UNLOCKED_DESKTOP,   // Ochiq dars ishchi stoli va suzuvchi taymer
    WHITEBOARD_CANVAS   // To'liq ekranli interaktiv doska
}

data class SmartBoardUiState(
    val isLocked: Boolean = true,
    val currentMode: BoardScreenMode = BoardScreenMode.STANDBY_LOCK,
    val activeSecondsRemaining: Int = 45 * 60,
    val sessionTotalSeconds: Int = 45 * 60,
    val teacherName: String = "",
    val isTimerPaused: Boolean = false,
    val botStatusText: String = "Kutilmoqda",
    val config: SmartBoardConfig = SmartBoardConfig(),
    val showAdminPinDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val lockMessageNotice: String? = null,
    val teachers: List<AuthorizedUser> = emptyList(),
    val itAdmins: List<AuthorizedUser> = emptyList(),
    val accessLogs: List<AccessLogEntry> = emptyList(),
    val rooms: List<RoomEntry> = emptyList(),
    val androidId: String = "",
    val macAddress: String = "",
    val isBoardRegistered: Boolean = false
)

class SmartBoardViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SmartBoardPreferences(application)
    val accessControl = AccessControlManager(application)
    private val hardwareId = try {
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
    } catch (_: Exception) {
        "UNKNOWN"
    }
    val deviceMacAddress = com.example.util.DeviceHardwareUtil.getDeviceMacAddress(application)

    private val _uiState = MutableStateFlow(
        SmartBoardUiState(
            config = prefs.loadConfig(),
            teachers = accessControl.getTeachers(),
            itAdmins = accessControl.getItAdmins(),
            accessLogs = accessControl.getAccessLogs(),
            rooms = accessControl.getRooms(),
            botStatusText = LessonSessionManager.botStatusText.value,
            androidId = hardwareId,
            macAddress = deviceMacAddress,
            isBoardRegistered = prefs.isBoardRegistered()
        )
    )
    val uiState: StateFlow<SmartBoardUiState> = _uiState.asStateFlow()

    private val _shouldMinimizeToDesktop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shouldMinimizeToDesktop = _shouldMinimizeToDesktop.asSharedFlow()

    private val _onLockEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
    val onLockEvent = _onLockEvent.asSharedFlow()

    private var timerJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun matchesDeviceTarget(targetRoomId: String): Boolean {
        val cleanTarget = targetRoomId.trim().uppercase()
        if (cleanTarget.isBlank() || cleanTarget == "ALL") return true
        val myBoardId = _uiState.value.config.boardId.trim().uppercase()
        val myAndroidId = _uiState.value.androidId.trim().uppercase()
        val myMac = _uiState.value.macAddress.trim().uppercase()
        val myRoom = _uiState.value.config.roomName.trim().uppercase()
        val cleanTargetNoColon = cleanTarget.replace(":", "")
        val myMacNoColon = myMac.replace(":", "")

        return cleanTarget == myBoardId ||
                cleanTarget == myAndroidId ||
                cleanTarget == myMac ||
                (myRoom.isNotBlank() && cleanTarget == myRoom) ||
                (myMacNoColon.isNotBlank() && cleanTargetNoColon == myMacNoColon)
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: Exception) {}

        // LessonSessionManager dan hodisalarni ulash
        LessonSessionManager.onUnlockFromBot = { teacher, minutes, targetRoomId ->
            if (matchesDeviceTarget(targetRoomId)) {
                startLessonSession(teacher, minutes, autoMinimize = false)
                refreshAccessData()
            }
        }
        LessonSessionManager.onLockFromBot = { targetRoomId ->
            if (matchesDeviceTarget(targetRoomId)) {
                lockBoard()
                refreshAccessData()
            }
        }
        LessonSessionManager.onExtendMinutes = { addMin -> extendSession(addMin) }
        LessonSessionManager.onTogglePause = { toggleTimerPause() }
        LessonSessionManager.onFinishLesson = { lockBoard() }
        LessonSessionManager.onOpenWhiteboard = { setScreenMode(BoardScreenMode.WHITEBOARD_CANVAS) }

        // Botdan yoki Telegram orqali xona ro'yxatdan o'tkazilganda
        LessonSessionManager.onBoardRegisteredFromBot = { boardId, roomName ->
            if (matchesDeviceTarget(boardId)) {
                accessControl.addRoom(boardId, roomName, "Telegram Bot")
                val current = _uiState.value.config
                val updated = current.copy(boardId = boardId, roomName = roomName, isRegistered = true)
                prefs.saveConfig(updated)
                prefs.setBoardRegistered(true)
                _uiState.value = _uiState.value.copy(
                    config = updated,
                    isBoardRegistered = true,
                    lockMessageNotice = "Doska ro'yxatdan o'tdi: $roomName"
                )
                refreshAccessData()
                playBeepTone(ToneGenerator.TONE_PROP_BEEP)
            }
        }

        // Bot holatini kuzatib UI ga uzatish
        viewModelScope.launch {
            LessonSessionManager.botStatusText.collect { status ->
                _uiState.value = _uiState.value.copy(botStatusText = status)
            }
        }

        refreshAccessData()

        // 24/7 Fon xizmatini kafolatli boshlash
        FloatingSessionService.start(application)
    }

    fun requestBoardRegistrationViaBot() {
        LessonSessionManager.onRequestRegisterNewBoard?.invoke(deviceMacAddress, hardwareId)
    }

    fun registerThisBoardAsRoom(roomName: String): Boolean {
        val cleanName = roomName.trim().ifBlank { "Doska ($deviceMacAddress)" }
        val current = _uiState.value.config
        val updated = current.copy(boardId = deviceMacAddress, roomName = cleanName, isRegistered = true)
        prefs.saveConfig(updated)
        prefs.setBoardRegistered(true)
        val success = accessControl.addRoom(deviceMacAddress, cleanName, "Doska ekrani orqali")
        accessControl.logAccess(0L, "Doska ekrani", "Doska ro'yxatdan o'tkazildi: $cleanName ($deviceMacAddress)")
        _uiState.value = _uiState.value.copy(
            config = updated,
            isBoardRegistered = true,
            lockMessageNotice = "Doska ro'yxatdan o'tdi: $cleanName"
        )
        refreshAccessData()
        playBeepTone(ToneGenerator.TONE_PROP_BEEP)
        return success
    }

    fun refreshAccessData() {
        _uiState.value = _uiState.value.copy(
            teachers = accessControl.getTeachers(),
            itAdmins = accessControl.getItAdmins(),
            accessLogs = accessControl.getAccessLogs(),
            rooms = accessControl.getRooms(),
            isBoardRegistered = prefs.isBoardRegistered()
        )
    }

    fun addRoom(id: String, name: String): Boolean {
        val success = accessControl.addRoom(id, name, "Admin Paneli")
        accessControl.logAccess(0L, "Admin Paneli", "Xona qo'shildi: $name ($id)")
        refreshAccessData()
        return success
    }

    fun removeRoom(id: String): Boolean {
        val success = accessControl.removeRoom(id)
        accessControl.logAccess(0L, "Admin Paneli", "Xona o'chirildi: $id")
        refreshAccessData()
        return success
    }

    fun clearRooms(): Boolean {
        val success = accessControl.clearRooms()
        accessControl.logAccess(0L, "Admin Paneli", "Barcha xonalar tozalandi")
        refreshAccessData()
        return success
    }

    fun addTeacher(id: Long, name: String): Boolean {
        val success = accessControl.addTeacher(id, name)
        accessControl.logAccess(id, name, "O'qituvchi qo'shildi")
        refreshAccessData()
        return success
    }

    fun removeTeacher(id: Long): Boolean {
        val success = accessControl.removeTeacher(id)
        accessControl.logAccess(id, "O'qituvchi", "O'qituvchi o'chirildi")
        refreshAccessData()
        return success
    }

    fun addItAdmin(id: Long, name: String): Boolean {
        val success = accessControl.addItAdmin(id, name)
        accessControl.logAccess(id, name, "IT Admin tayinlandi")
        refreshAccessData()
        return success
    }

    fun removeItAdmin(id: Long): Boolean {
        val success = accessControl.removeItAdmin(id)
        accessControl.logAccess(id, "IT Admin", "IT Admin o'chirildi")
        refreshAccessData()
        return success
    }

    fun clearLogs() {
        accessControl.clearLogs()
        refreshAccessData()
    }

    fun startLessonSession(
        teacherName: String = "O'qituvchi",
        durationMinutes: Int = 45,
        autoMinimize: Boolean = false
    ) {
        val totalSecs = durationMinutes * 60
        val resolvedTeacher = teacherName.ifBlank { "O'qituvchi" }

        // Sync with LessonSessionManager for Floating Service
        LessonSessionManager.isLocked.value = false
        LessonSessionManager.activeSeconds.value = totalSecs
        LessonSessionManager.sessionTotalSeconds.value = totalSecs
        LessonSessionManager.teacherName.value = resolvedTeacher
        LessonSessionManager.isPaused.value = false

        _uiState.value = _uiState.value.copy(
            isLocked = false,
            currentMode = BoardScreenMode.UNLOCKED_DESKTOP,
            activeSecondsRemaining = totalSecs,
            sessionTotalSeconds = totalSecs,
            teacherName = resolvedTeacher,
            isTimerPaused = false,
            lockMessageNotice = null
        )

        playBeepTone(ToneGenerator.TONE_PROP_BEEP2)
        startCountdownTimer()

        if (autoMinimize) {
            viewModelScope.launch {
                delay(350L)
                _shouldMinimizeToDesktop.emit(Unit)
            }
        }
    }

    private fun startCountdownTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive && !_uiState.value.isLocked) {
                delay(1000L)
                if (!_uiState.value.isTimerPaused) {
                    val remaining = _uiState.value.activeSecondsRemaining - 1
                    LessonSessionManager.activeSeconds.value = remaining.coerceAtLeast(0)

                    if (remaining <= 0) {
                        _uiState.value = _uiState.value.copy(activeSecondsRemaining = 0)
                        playBeepTone(ToneGenerator.TONE_CDMA_HIGH_L)
                        lockBoard()
                        accessControl.logAccess(0L, "Tizim taymeri", "45 daqiqalik dars tugadi (Avtomatik bloklandi)")
                        refreshAccessData()
                        break
                    } else {
                        // 5 daqiqa qolganda ogohlantiruvchi signal
                        if (remaining == 5 * 60) {
                            playBeepTone(ToneGenerator.TONE_PROP_BEEP)
                        }
                        _uiState.value = _uiState.value.copy(activeSecondsRemaining = remaining)
                    }
                }
            }
        }
    }

    fun lockBoard() {
        timerJob?.cancel()
        timerJob = null
        LessonSessionManager.isLocked.value = true

        _uiState.value = _uiState.value.copy(
            isLocked = true,
            currentMode = BoardScreenMode.STANDBY_LOCK,
            isTimerPaused = false,
            showAdminPinDialog = false,
            showSettingsDialog = false,
            lockMessageNotice = null
        )
        playBeepTone(ToneGenerator.TONE_PROP_PROMPT)
        _onLockEvent.tryEmit(Unit)
    }

    fun extendSession(additionalMinutes: Int = 10) {
        val additionalSecs = additionalMinutes * 60
        val currentRemaining = _uiState.value.activeSecondsRemaining
        val newRemaining = currentRemaining + additionalSecs
        val newTotal = _uiState.value.sessionTotalSeconds + additionalSecs

        LessonSessionManager.activeSeconds.value = newRemaining
        LessonSessionManager.sessionTotalSeconds.value = newTotal

        _uiState.value = _uiState.value.copy(
            activeSecondsRemaining = newRemaining,
            sessionTotalSeconds = newTotal
        )
        playBeepTone(ToneGenerator.TONE_PROP_ACK)
    }

    fun toggleTimerPause() {
        val nextPaused = !_uiState.value.isTimerPaused
        LessonSessionManager.isPaused.value = nextPaused
        _uiState.value = _uiState.value.copy(
            isTimerPaused = nextPaused
        )
    }

    fun setScreenMode(mode: BoardScreenMode) {
        _uiState.value = _uiState.value.copy(currentMode = mode)
    }

    fun showNotice(msg: String) {
        _uiState.value = _uiState.value.copy(lockMessageNotice = msg)
    }

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(lockMessageNotice = null)
    }

    fun openAdminPinDialog() {
        _uiState.value = _uiState.value.copy(showAdminPinDialog = true)
    }

    fun closeAdminPinDialog() {
        _uiState.value = _uiState.value.copy(showAdminPinDialog = false)
    }

    fun openSettingsDialog() {
        refreshAccessData()
        _uiState.value = _uiState.value.copy(showSettingsDialog = true)
    }

    fun closeSettingsDialog() {
        _uiState.value = _uiState.value.copy(showSettingsDialog = false)
    }

    fun verifyAdminPin(enteredPin: String): Boolean {
        return enteredPin == _uiState.value.config.adminPin
    }

    fun updateConfig(newConfig: SmartBoardConfig) {
        prefs.saveConfig(newConfig)
        _uiState.value = _uiState.value.copy(config = newConfig)
        // 24/7 Xizmatga yangi tokenni yuklash
        FloatingSessionService.reloadToken(getApplication())
        LessonSessionManager.onReloadBotToken?.invoke()
    }

    suspend fun testTelegramToken(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = token.trim()
            .replace("\n", "")
            .replace("\r", "")
            .removeSurrounding("\"")
            .removeSurrounding("'")

        if (clean.isBlank()) return@withContext Pair(false, "Token bo'sh bo'lmasligi kerak")
        if (!clean.contains(":")) return@withContext Pair(false, "Token formati noto'g'ri (123456:ABC... bo'lishi kerak)")
        try {
            val url = "https://api.telegram.org/bot$clean/getMe"
            val request = Request.Builder().url(url).build()
            testClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val result = json.optJSONObject("result")
                        val botUsername = result?.optString("username", "bot") ?: "bot"
                        val botFirstName = result?.optString("first_name", "") ?: ""
                        return@withContext Pair(true, "@$botUsername ($botFirstName)")
                    }
                }
                return@withContext Pair(false, "Token noto'g'ri yoki bot topilmadi (HTTP ${resp.code})")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Tarmoq xatosi: ${e.localizedMessage}")
        }
    }

    suspend fun testServerConnection(serverUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        if (cleanUrl.isBlank()) return@withContext Pair(false, "Server manzili bo'sh bo'lmasligi kerak")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext Pair(false, "Server manzili https:// bilan boshlanishi kerak")
        }
        try {
            val request = Request.Builder().url("$cleanUrl/health").build()
            testClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext Pair(true, "Render Server faol va javob bermoqda (OK)")
                }
                return@withContext Pair(false, "Server xatolik qaytardi (HTTP ${resp.code})")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Ulanib bo'lmadi: ${e.localizedMessage}")
        }
    }

    private fun playBeepTone(toneType: Int) {
        try {
            toneGenerator?.startTone(toneType, 400)
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
    }
}
