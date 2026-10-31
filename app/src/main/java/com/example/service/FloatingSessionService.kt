package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.AccessControlManager
import com.example.model.LessonSessionManager
import com.example.model.BoardServerClient
import com.example.model.SmartBoardPreferences
import com.example.model.TelegramBotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingSessionService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isViewAttached = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    private var tvTimer: TextView? = null
    private var tvTeacher: TextView? = null

    private lateinit var accessControl: AccessControlManager
    private lateinit var prefs: SmartBoardPreferences
    private var botManager: TelegramBotManager? = null
    private var serverClient: BoardServerClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        accessControl = AccessControlManager(this)
        prefs = SmartBoardPreferences(this)

        // 1. Orqa fonda 24/7 aloqa uzilmasligi uchun xavfsiz WakeLock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmartBoard::KioskGuardWakeLock"
            )?.apply {
                acquire(24 * 60 * 60 * 1000L) // 24 soat davomida barqaror ushlab turish
            }
        } catch (_: Exception) {}

        // 2. Doimiy Foreground Service bildirishnomasi
        startForegroundServiceNotification()

        // 3. 24/7 Telegram Bot boshqaruvini ishga tushirish
        initTelegramBot()

        // 4. Floating view tayyorlash
        buildFloatingView()

        if (Settings.canDrawOverlays(this) && !LessonSessionManager.isLocked.value) {
            showFloatingView()
        }

        observeSession()

        // Token o'zgarganda botni qayta ishga tushirish tinglovchisi
        LessonSessionManager.onReloadBotToken = {
            restartBotPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        if (intent?.action == ACTION_RELOAD_BOT_TOKEN) {
            restartBotPolling()
        }

        return START_STICKY
    }

    private fun matchesTarget(targetRoomId: String, cfg: com.example.model.SmartBoardConfig): Boolean {
        val cleanTarget = targetRoomId.trim().uppercase()
        if (cleanTarget.isBlank() || cleanTarget == "ALL") return true
        val myBoardId = cfg.boardId.trim().uppercase()
        val myMac = com.example.util.DeviceHardwareUtil.getDeviceMacAddress(this).trim().uppercase()
        val myRoom = cfg.roomName.trim().uppercase()
        val hwId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (_: Exception) { "" }.trim().uppercase()
        val cleanTargetNoColon = cleanTarget.replace(":", "")
        val myMacNoColon = myMac.replace(":", "")

        return cleanTarget == myBoardId ||
                cleanTarget == myMac ||
                cleanTarget == hwId ||
                (myRoom.isNotBlank() && cleanTarget == myRoom) ||
                (myMacNoColon.isNotBlank() && cleanTargetNoColon == myMacNoColon)
    }

    private fun executeUnlock(teacherName: String, durationMinutes: Int, targetRoomId: String, isFromServer: Boolean = false) {
        val cfg = prefs.loadConfig()
        val matches = matchesTarget(targetRoomId, cfg)

        if (matches) {
            LessonSessionManager.isLocked.value = false
            LessonSessionManager.teacherName.value = teacherName
            LessonSessionManager.currentRoomId.value = cfg.boardId
            val secs = durationMinutes * 60
            LessonSessionManager.activeSeconds.value = secs
            LessonSessionManager.sessionTotalSeconds.value = secs
            LessonSessionManager.isPaused.value = false

            LessonSessionManager.onUnlockFromBot?.invoke(teacherName, durationMinutes, targetRoomId)

            if (Settings.canDrawOverlays(this)) {
                showFloatingView()
            }
            updateNotification(
                "SmartBoard 86 - Dars faol",
                "Dars sessiyasi: $teacherName (${cfg.roomName})"
            )
        }
    }

    private fun executeLock(targetRoomId: String, isFromServer: Boolean = false) {
        val cfg = prefs.loadConfig()
        val matches = matchesTarget(targetRoomId, cfg)

        if (matches) {
            LessonSessionManager.isLocked.value = true
            LessonSessionManager.isPaused.value = false
            LessonSessionManager.onLockFromBot?.invoke(targetRoomId)

            removeFloatingView()
            updateNotification(
                "SmartBoard 86 - Qulflangan",
                "Kutish rejimi • Jonli fon va soat faol (${cfg.roomName})"
            )
            openMainActivity(false)
        }
    }

    private fun initTelegramBot() {
        val mac = com.example.util.DeviceHardwareUtil.getDeviceMacAddress(this)
        val hwId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" }

        // 1. Render Server mijozi (Ko'p doskalar uchun)
        serverClient = BoardServerClient(
            context = this,
            prefs = prefs,
            deviceMacAddress = mac,
            hardwareId = hwId,
            onUnlockRequested = { teacher, minutes, target ->
                executeUnlock(teacher, minutes, target, isFromServer = true)
            },
            onLockRequested = { target ->
                executeLock(target, isFromServer = true)
            },
            onExtendRequested = { addMin ->
                LessonSessionManager.onExtendMinutes?.invoke(addMin)
            }
        )

        // 2. To'g'ridan-to'g'ri Telegram Bot mijozi (1 ta doska uchun)
        botManager = TelegramBotManager(
            accessControl = accessControl,
            onUnlockRequested = { teacherName, durationMinutes, targetRoomId ->
                executeUnlock(teacherName, durationMinutes, targetRoomId)
            },
            onLockRequested = { targetRoomId ->
                executeLock(targetRoomId)
            },
            onExtendRequested = { additionalMinutes ->
                LessonSessionManager.onExtendMinutes?.invoke(additionalMinutes)
            },
            getStatusInfo = {
                val isLocked = LessonSessionManager.isLocked.value
                val cfg = prefs.loadConfig()
                if (isLocked) {
                    "Doska qulflangan (Kutish rejimi)\nXona: ${cfg.roomName} (${cfg.boardId})"
                } else {
                    val secs = LessonSessionManager.activeSeconds.value
                    val mm = secs / 60
                    val ss = secs % 60
                    val timeStr = String.format("%02d:%02d", mm, ss)
                    val teacher = LessonSessionManager.teacherName.value
                    "Doska ochiq!\nXona: ${cfg.roomName}\nO'qituvchi: $teacher\nQolgan vaqt: $timeStr"
                }
            }
        )

        // Yangi doska aniqlanganda adminlarga xabar berish ulanishi
        LessonSessionManager.onRequestRegisterNewBoard = { macAddr, devId ->
            botManager?.notifyAdminsNewBoardDetected(macAddr, devId)
        }

        restartBotPolling()
    }

    private fun restartBotPolling() {
        val cfg = prefs.loadConfig()

        if (cfg.connectionMode == com.example.model.SmartBoardConfig.MODE_RENDER_SERVER) {
            // RENDER SERVER REJIMI:
            // Doska to'g'ridan-to'g'ri Telegramga getUpdates qilmaydi! 2 ta doska bo'lsa ham hech qanday 409 xatosi chiqmaydi!
            botManager?.stopPolling()
            serverClient?.startPolling(cfg.serverUrl, serviceScope)
        } else {
            // TO'G'RIDAN-TO'G'RI TELEGRAM BOT REJIMI (Faqat 1 ta doska bo'lganda)
            serverClient?.stopPolling()
            val token = cfg.botToken
            if (token.isNotBlank()) {
                botManager?.startPolling(token, serviceScope)

                if (!prefs.isBoardRegistered()) {
                    serviceScope.launch {
                        kotlinx.coroutines.delay(3000L)
                        val mac = com.example.util.DeviceHardwareUtil.getDeviceMacAddress(this@FloatingSessionService)
                        val hwId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" }
                        botManager?.notifyAdminsNewBoardDetected(mac, hwId)
                    }
                }
            } else {
                botManager?.stopPolling()
                LessonSessionManager.botStatusText.value = "Bot tokeni sozlanmagan"
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "smartboard_lesson_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SmartBoard 86 Doimiy Xizmat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Doska masofaviy boshqaruvi va fon xizmati"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_ENSURE_WALLPAPER", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isLocked = LessonSessionManager.isLocked.value
        val title = if (isLocked) "SmartBoard 86 - Qulflangan" else "SmartBoard 86 - Dars faol"
        val desc = if (isLocked) "Kutish rejimi • Jonli fon va soat faol" else "45 daqiqalik dars sessiyasi davom etmoqda"

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val channelId = "smartboard_lesson_channel"
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("EXTRA_ENSURE_WALLPAPER", true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(1001, notification)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 120
        }

        // Bosh konteyner: Ultra-ixcham mini tabletka
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(Color.parseColor("#F00B132B"))
                setStroke(2, Color.parseColor("#00F0FF"))
            }
            background = bg
            setPadding(18, 10, 18, 10)
            elevation = 16f
        }

        // Kichik yashil indikator nuqta
        val liveDot = View(this).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(14, 14).apply {
                rightMargin = 10
            }
        }

        tvTimer = TextView(this).apply {
            text = "45:00"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00F0FF"))
            letterSpacing = 0.05f
        }

        tvTeacher = TextView(this).apply {
            text = " • O'qituvchi"
            textSize = 11f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(Color.parseColor("#CBD5E1"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 8
            }
        }

        rootLayout.addView(liveDot)
        rootLayout.addView(tvTimer)
        rootLayout.addView(tvTeacher)

        // Surish (Drag and Drop) va bosish (Click)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        rootLayout.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    p.x = initialX - dx
                    p.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(rootLayout, p)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        openMainActivity(false)
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = rootLayout
    }

    private fun showFloatingView() {
        if (!Settings.canDrawOverlays(this) || LessonSessionManager.isLocked.value) return
        if (isViewAttached) return

        if (floatingView == null) {
            buildFloatingView()
        }

        try {
            windowManager?.addView(floatingView, params)
            isViewAttached = true
        } catch (_: Exception) {}
    }

    private fun removeFloatingView() {
        if (!isViewAttached || floatingView == null) return
        try {
            windowManager?.removeView(floatingView)
        } catch (_: Exception) {}
        isViewAttached = false
    }

    private fun observeSession() {
        updateJob = serviceScope.launch {
            launch {
                LessonSessionManager.activeSeconds.collect { secs ->
                    val mm = secs / 60
                    val ss = secs % 60
                    tvTimer?.text = String.format("%02d:%02d", mm, ss)

                    if (!LessonSessionManager.isLocked.value) {
                        updateNotification(
                            "SmartBoard 86 - Dars faol",
                            "Qolgan vaqt: ${String.format("%02d:%02d", mm, ss)}"
                        )
                    }

                    // 45 daqiqalik dars vaqti tugaganda avtomatik doska qulflanadi va kutish ekrani ochiladi
                    if (secs <= 0 && !LessonSessionManager.isLocked.value) {
                        botManager?.notifyLessonExpired()
                        LessonSessionManager.onFinishLesson?.invoke()
                        openMainActivity(false)
                    }
                }
            }

            launch {
                LessonSessionManager.teacherName.collect { name ->
                    tvTeacher?.text = " • $name"
                }
            }

            launch {
                LessonSessionManager.isLocked.collect { locked ->
                    if (locked) {
                        removeFloatingView()
                        updateNotification(
                            "SmartBoard 86 - Qulflangan",
                            "Kutish rejimi • Jonli fon va soat faol"
                        )
                        // Bot orqali yoki taymer tugaganda darhol ilovani oldinga chiqarib wallpaper ochiladi
                        openMainActivity(false)
                    } else {
                        updateNotification(
                            "SmartBoard 86 - Dars faol",
                            "Dars sessiyasi davom etmoqda"
                        )
                        if (Settings.canDrawOverlays(this@FloatingSessionService)) {
                            showFloatingView()
                        }
                    }
                }
            }
        }
    }

    private fun openMainActivity(openWhiteboard: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_OPEN_WHITEBOARD", openWhiteboard)
            putExtra("EXTRA_ENSURE_WALLPAPER", !openWhiteboard)
        }
        try {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        } catch (_: Exception) {}
        try {
            startActivity(intent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        botManager?.stopPolling()
        removeFloatingView()
        floatingView = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        const val ACTION_RELOAD_BOT_TOKEN = "com.example.service.ACTION_RELOAD_BOT_TOKEN"

        fun start(context: Context) {
            val intent = Intent(context, FloatingSessionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun reloadToken(context: Context) {
            val intent = Intent(context, FloatingSessionService::class.java).apply {
                action = ACTION_RELOAD_BOT_TOKEN
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingSessionService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
