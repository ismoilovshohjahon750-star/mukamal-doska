package com.example.model

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TelegramBotManager(
    private val accessControl: AccessControlManager,
    private val onUnlockRequested: (teacherName: String, durationMinutes: Int, targetRoomId: String) -> Unit,
    private val onLockRequested: (targetRoomId: String) -> Unit,
    private val onExtendRequested: (additionalMinutes: Int) -> Unit,
    private val getStatusInfo: () -> String
) {
    private val tag = "TelegramBotManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var pollingJob: Job? = null
    private var lastUpdateId = 0L
    @Volatile
    private var currentCall: okhttp3.Call? = null

    var isPollingActive = false
        private set

    var lastStatusMessage = "Kutilmoqda"
        private set(value) {
            field = value
            LessonSessionManager.botStatusText.value = value
        }

    private var activeToken: String = ""
    private var lastActiveChatId: Long = 0L
    private var activeLessonRoomId: String = ""
    private val awaitingRoomSetupForChat = ConcurrentHashMap<Long, String>()

    fun startPolling(token: String, scope: CoroutineScope) {
        stopPolling()
        val cleanToken = token.trim()
            .replace("\n", "")
            .replace("\r", "")
            .removeSurrounding("\"")
            .removeSurrounding("'")

        if (cleanToken.isBlank() || !cleanToken.contains(":")) {
            lastStatusMessage = "Token sozlanmagan"
            return
        }

        activeToken = cleanToken

        pollingJob = scope.launch(Dispatchers.IO) {
            isPollingActive = true
            lastStatusMessage = "Telegram botga ulanmoqda..."
            Log.d(tag, "Starting Telegram polling...")

            var backoffDelay = 2000L

            while (isActive) {
                try {
                    val url = "https://api.telegram.org/bot$cleanToken/getUpdates?offset=${lastUpdateId + 1}&timeout=15"
                    val request = Request.Builder().url(url).build()
                    val call = client.newCall(request)
                    currentCall = call

                    call.execute().use { response ->
                        currentCall = null
                        val bodyString = response.body?.string()

                        if (response.isSuccessful && bodyString != null) {
                            backoffDelay = 2000L
                            lastStatusMessage = "Bot faol va 24/7 buyruqlarni qabul qilmoqda"
                            val json = JSONObject(bodyString)
                            if (json.optBoolean("ok")) {
                                val result = json.optJSONArray("result") ?: JSONArray()
                                for (i in 0 until result.length()) {
                                    val update = result.getJSONObject(i)
                                    val updateId = update.getLong("update_id")
                                    if (updateId > lastUpdateId) {
                                        lastUpdateId = updateId
                                    }
                                    processUpdate(cleanToken, update)
                                }
                            }
                        } else {
                            val code = response.code
                            if (code == 409) {
                                lastStatusMessage = "Boshqa bot nusxasi faol (409). Kutilmoqda..."
                                delay(3000L)
                                return@use
                            }
                            lastStatusMessage = if (code == 401 || code == 404) {
                                "Bot tokeni noto'g'ri (Xato $code)"
                            } else {
                                "Tarmoq holati: $code"
                            }
                            Log.w(tag, "Telegram API non-success code: $code")
                            delay(backoffDelay)
                            backoffDelay = (backoffDelay * 2).coerceAtMost(30000L)
                        }
                    }
                } catch (e: Exception) {
                    currentCall = null
                    if (!isActive) break

                    if (e is java.net.SocketTimeoutException) {
                        Log.d(tag, "Polling timeout (normal wait), next request...")
                        continue
                    }
                    if (e is IOException) {
                        lastStatusMessage = "Aloqa tiklanmoqda..."
                        Log.w(tag, "Telegram reconnecting: ${e.message}")
                    } else {
                        lastStatusMessage = "Kutilmoqda..."
                        Log.w(tag, "Telegram polling issue: ${e.message}")
                    }
                    delay(backoffDelay)
                    backoffDelay = (backoffDelay * 2).coerceAtMost(30000L)
                } finally {
                    currentCall = null
                }
            }
            isPollingActive = false
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        try {
            currentCall?.cancel()
            client.dispatcher.cancelAll()
        } catch (_: Exception) {}
        currentCall = null
        isPollingActive = false
    }

    private suspend fun processUpdate(token: String, update: JSONObject) {
        val message = update.optJSONObject("message") ?: update.optJSONObject("callback_query")?.optJSONObject("message")
        val callbackQuery = update.optJSONObject("callback_query")
        val callbackId = callbackQuery?.optString("id", "") ?: ""
        if (callbackId.isNotBlank()) {
            answerCallbackQuery(token, callbackId)
        }

        val fromObj = update.optJSONObject("message")?.optJSONObject("from")
            ?: callbackQuery?.optJSONObject("from")

        val userId = fromObj?.optLong("id") ?: 0L
        val chatId = message?.optJSONObject("chat")?.optLong("id")
            ?: callbackQuery?.optJSONObject("from")?.optLong("id")
            ?: return

        if (userId == 0L) return

        val teacherRawName = buildString {
            val first = fromObj?.optString("first_name", "") ?: ""
            val last = fromObj?.optString("last_name", "") ?: ""
            val user = fromObj?.optString("username", "") ?: ""
            if (first.isNotBlank() || last.isNotBlank()) {
                append("$first $last".trim())
            } else if (user.isNotBlank()) {
                append("@$user")
            } else {
                append("O'qituvchi")
            }
        }

        var text = update.optJSONObject("message")?.optString("text", "") ?: ""
        val callbackData = callbackQuery?.optString("data", "") ?: ""
        if (callbackData.isNotBlank()) {
            text = callbackData
        }
        text = text.trim()

        // 1. ACCESS RESTRICTION: Only authorized users can access the bot
        val isAuthorized = accessControl.isAuthorized(userId)
        if (!isAuthorized) {
            sendMessage(
                token = token,
                chatId = chatId,
                text = AccessControlManager.REJECTION_MESSAGE
            )
            return
        }

        val isSuperAdmin = accessControl.isSuperAdmin(userId)
        val isItAdmin = accessControl.isItAdmin(userId)
        val userDisplayName = accessControl.getUserDisplayName(userId, teacherRawName)
        val userRole = accessControl.getUserRole(userId)

        Log.d(tag, "Authorized command: '$text' from $userDisplayName (ID: $userId, Role: $userRole)")

        val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()

        // 2. Yangi doska uchun xona ma'lumotlarini qabul qilish (Suhbat holati)
        if (awaitingRoomSetupForChat.containsKey(chatId) && !cleanText.startsWith("/") && !cleanText.startsWith("cmd_")) {
            val targetMac = awaitingRoomSetupForChat.remove(chatId) ?: ""
            val roomName = cleanText.trim()
            if (targetMac.isNotBlank() && roomName.isNotBlank()) {
                accessControl.addRoom(targetMac, roomName, userDisplayName)
                accessControl.logAccess(userId, userDisplayName, "Doska ro'yxatdan o'tkazildi: $roomName ($targetMac)")
                withContext(Dispatchers.Main) {
                    LessonSessionManager.onBoardRegisteredFromBot?.invoke(targetMac, roomName)
                }
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>Doska muvaffaqiyatli tizimga qo'shildi!</b>
                        
                        Xona nomi: <b>$roomName</b>
                        Apparat MAC: <code>$targetMac</code>
                        Ro'yxatdan o'tkazgan: <b>$userDisplayName</b>
                        Vaqt: <code>${accessControl.getFormattedNow()}</code>
                        
                        Doska avtomatik faollashtirildi va tizimga ulandi!
                    """.trimIndent()
                )
                return
            }
        }

        when {
            // Welcome menu
            cleanText.startsWith("/start") || cleanText.startsWith("/help") || cleanText.equals("yordam", ignoreCase = true) -> {
                sendWelcomeMenu(token, chatId, userDisplayName, isItAdmin, isSuperAdmin)
            }

            // --- NEW BOARD SETUP CALLBACK ---
            cleanText.startsWith("cmd_setup_board:") -> {
                val targetMac = cleanText.removePrefix("cmd_setup_board:").trim()
                awaitingRoomSetupForChat[chatId] = targetMac
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>Xona nomini kiritish:</b>
                        
                        Apparat MAC manzili: <code>$targetMac</code>
                        
                        Iltimos, ushbu doska o'rnatilgan xona nomini xabar ko'rinishida yuboring:
                        <i>(Masalan: <code>101-Fizika xonasi</code> yoki <code>204-Tarix xonasi</code>)</i>
                    """.trimIndent()
                )
            }

            // --- SPECIFIC ROOM UNLOCK CALLBACK ---
            cleanText.startsWith("cmd_room_unlock:") -> {
                val targetRoomId = cleanText.removePrefix("cmd_room_unlock:").trim().uppercase()
                val roomObj = accessControl.getRooms().find { it.id.equals(targetRoomId, ignoreCase = true) }
                val roomDisplay = roomObj?.name ?: targetRoomId

                lastActiveChatId = chatId
                activeLessonRoomId = targetRoomId
                accessControl.logAccess(userId, userDisplayName, "Dars boshlandi: $roomDisplay (45 daqiqa)")
                withContext(Dispatchers.Main) {
                    onUnlockRequested(userDisplayName, 45, targetRoomId)
                }
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>86" Interaktiv Doska ochildi!</b>
                        
                        Xona: <b>$roomDisplay</b> (<code>$targetRoomId</code>)
                        O'qituvchi: <b>$userDisplayName</b>
                        Telegram ID: <code>$userId</code>
                        Dars davomiyligi: <b>45 daqiqa</b>
                        Vaqt: <code>${accessControl.getFormattedNow()}</code>
                        
                        <i>Doskadan erkin foydalanishingiz mumkin. Chizish, veb-brauzer va barcha ilovalar faol.</i>
                        
                        <b>Darsni yakunlash va doskani yopish uchun tugmani bosing:</b>
                    """.trimIndent(),
                    replyMarkup = buildActiveLessonInlineKeyboard(targetRoomId)
                )
            }

            // --- SPECIFIC ROOM SELECTION FOR LOCK ---
            cleanText.startsWith("cmd_room_lock:") || cleanText.startsWith("cmd_select_lock_room:") -> {
                val targetRoomId = if (cleanText.startsWith("cmd_room_lock:")) {
                    cleanText.removePrefix("cmd_room_lock:").trim().uppercase()
                } else {
                    cleanText.removePrefix("cmd_select_lock_room:").trim().uppercase()
                }
                val isAll = targetRoomId == "ALL"
                val roomObj = accessControl.getRooms().find { it.id.equals(targetRoomId, ignoreCase = true) }
                val roomDisplay = if (isAll) "Barcha xonalar" else (roomObj?.name ?: targetRoomId)

                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>Doskani qulflashni tasdiqlash</b>
                        
                        Tanlangan xona: <b>$roomDisplay</b>
                        Doska ID: <code>$targetRoomId</code>
                        Mas'ul: <b>$userDisplayName</b>
                        
                        Ushbu doskani qulflab, jonli fon (Wallpaper) rejimiga o'tkazish uchun quyidagi tugmani bosing:
                    """.trimIndent(),
                    replyMarkup = buildConfirmLockInlineKeyboard(targetRoomId)
                )
            }

            // --- EXECUTE LOCK AFTER CONFIRMATION BUTTON CLICKED ---
            cleanText.startsWith("cmd_do_lock:") -> {
                val targetRoomId = cleanText.removePrefix("cmd_do_lock:").trim().uppercase()
                val isAll = targetRoomId == "ALL"
                val roomObj = accessControl.getRooms().find { it.id.equals(targetRoomId, ignoreCase = true) }
                val roomDisplay = if (isAll) "Barcha xonalar" else (roomObj?.name ?: targetRoomId)

                activeLessonRoomId = ""
                accessControl.logAccess(userId, userDisplayName, "Doska qulflanadi: $roomDisplay")
                withContext(Dispatchers.Main) {
                    onLockRequested(targetRoomId)
                }
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>Doska qulflanadi!</b>
                        
                        Xona: <b>$roomDisplay</b>
                        Mas'ul: <b>$userDisplayName</b>
                        Vaqt: <code>${accessControl.getFormattedNow()}</code>
                        
                        Jonli fon (Wallpaper) va soat ekrani avtomatik ochildi.
                    """.trimIndent(),
                    replyMarkup = buildStandbyInlineKeyboard()
                )
            }

            // --- CANCEL LOCK ---
            cleanText == "cmd_cancel_lock" -> {
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = "Doskani qulflash bekor qilindi.",
                    replyMarkup = buildStandbyInlineKeyboard()
                )
            }

            // --- GENERAL UNLOCK REQUEST ---
            cleanText.contains("Darsni boshlash", ignoreCase = true)
                    || cleanText.contains("Dars boshlash", ignoreCase = true)
                    || cleanText.contains("45 daqiqalik darsni boshlash", ignoreCase = true)
                    || cleanText.equals("Ochish", ignoreCase = true)
                    || cleanText.equals("Unlock", ignoreCase = true)
                    || cleanText.startsWith("/unlock", ignoreCase = true)
                    || cleanText.startsWith("/start_lesson", ignoreCase = true)
                    || cleanText.startsWith("/ochish", ignoreCase = true)
                    || cleanText.startsWith("/open", ignoreCase = true)
                    || cleanText.equals("unlock", ignoreCase = true)
                    || cleanText.equals("ochish", ignoreCase = true)
                    || cleanText.equals("och", ignoreCase = true)
                    || cleanText.equals("open", ignoreCase = true)
                    || text == "cmd_unlock" -> {

                // Agar foydalanuvchi to'g'ridan-to'g'ri xona ID sini yozgan bo'lsa (masalan: /unlock 101-XONA)
                val explicitArg = if (cleanText.startsWith("/unlock")) cleanText.removePrefix("/unlock").trim() else ""
                val rooms = accessControl.getRooms()

                val matchedRoom = if (explicitArg.isNotBlank()) {
                    rooms.find { it.id.equals(explicitArg, ignoreCase = true) || it.name.contains(explicitArg, ignoreCase = true) }
                } else null

                if (matchedRoom != null) {
                    val targetRoomId = matchedRoom.id
                    lastActiveChatId = chatId
                    activeLessonRoomId = targetRoomId
                    accessControl.logAccess(userId, userDisplayName, "Dars boshlandi: ${matchedRoom.name} (45 daqiqa)")
                    withContext(Dispatchers.Main) {
                        onUnlockRequested(userDisplayName, 45, targetRoomId)
                    }
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>86" Interaktiv Doska ochildi!</b>
                            
                            Xona: <b>${matchedRoom.name}</b> (<code>$targetRoomId</code>)
                            O'qituvchi: <b>$userDisplayName</b>
                            Telegram ID: <code>$userId</code>
                            Dars davomiyligi: <b>45 daqiqa</b>
                            Vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            <i>Doskadan erkin foydalanishingiz mumkin.</i>
                        """.trimIndent(),
                        replyMarkup = buildActiveLessonInlineKeyboard(targetRoomId)
                    )
                } else if (rooms.isEmpty()) {
                    // Agar xonalar hali kiritilmagan bo'lsa, umumiy rejimda doska ochiladi
                    lastActiveChatId = chatId
                    activeLessonRoomId = "ALL"
                    accessControl.logAccess(userId, userDisplayName, "Dars boshlandi (45 daqiqa)")
                    withContext(Dispatchers.Main) {
                        onUnlockRequested(userDisplayName, 45, "ALL")
                    }
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>86" Interaktiv Doska ochildi!</b>
                            
                            O'qituvchi: <b>$userDisplayName</b>
                            Telegram ID: <code>$userId</code>
                            Dars davomiyligi: <b>45 daqiqa</b>
                            Vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            <i>Doskadan erkin foydalanishingiz mumkin.</i>
                            <i>Eslatma: Hozircha alohida xonalar kiritilmagan. Admin yoki IT Adminlar /add_room orqali xona qo'shishi mumkin.</i>
                        """.trimIndent(),
                        replyMarkup = buildActiveLessonInlineKeyboard("ALL")
                    )
                } else if (rooms.size == 1) {
                    // Yagona xona bo'lsa darhol ochiladi
                    val singleRoom = rooms.first()
                    lastActiveChatId = chatId
                    activeLessonRoomId = singleRoom.id
                    accessControl.logAccess(userId, userDisplayName, "Dars boshlandi: ${singleRoom.name} (45 daqiqa)")
                    withContext(Dispatchers.Main) {
                        onUnlockRequested(userDisplayName, 45, singleRoom.id)
                    }
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>86" Interaktiv Doska ochildi!</b>
                            
                            Xona: <b>${singleRoom.name}</b> (<code>${singleRoom.id}</code>)
                            O'qituvchi: <b>$userDisplayName</b>
                            Telegram ID: <code>$userId</code>
                            Dars davomiyligi: <b>45 daqiqa</b>
                            Vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            <i>Doskadan erkin foydalanishingiz mumkin.</i>
                        """.trimIndent(),
                        replyMarkup = buildActiveLessonInlineKeyboard(singleRoom.id)
                    )
                } else {
                    // Ko'p xonalar mavjud bo'lganda: o'qituvchiga xona tanlash tugmalari chiqadi
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Qaysi xonadagi doskani ochmoqchisiz?</b>
                            
                            Iltimos, dars o'tadigan xonangizni tanlang:
                        """.trimIndent(),
                        replyMarkup = buildRoomSelectionInlineKeyboard(rooms, "cmd_room_unlock", includeAll = false)
                    )
                }
            }

            // --- GENERAL LOCK REQUEST ---
            cleanText.contains("Doskani qulflash", ignoreCase = true)
                    || cleanText.contains("Qulflash", ignoreCase = true)
                    || cleanText.contains("Bloklash", ignoreCase = true)
                    || cleanText.contains("Quluflash", ignoreCase = true)
                    || cleanText.contains("Yopish", ignoreCase = true)
                    || cleanText.equals("Lock", ignoreCase = true)
                    || cleanText.equals("Block", ignoreCase = true)
                    || cleanText.startsWith("/lock", ignoreCase = true)
                    || cleanText.startsWith("/block", ignoreCase = true)
                    || cleanText.startsWith("/blok", ignoreCase = true)
                    || cleanText.startsWith("/qulf", ignoreCase = true)
                    || cleanText.startsWith("/quluf", ignoreCase = true)
                    || cleanText.startsWith("/stop", ignoreCase = true)
                    || cleanText.startsWith("/close", ignoreCase = true)
                    || cleanText.startsWith("/yop", ignoreCase = true)
                    || cleanText.startsWith("/standby", ignoreCase = true)
                    || cleanText.startsWith("/tamom", ignoreCase = true)
                    || cleanText.equals("lock", ignoreCase = true)
                    || cleanText.equals("block", ignoreCase = true)
                    || cleanText.equals("blok", ignoreCase = true)
                    || cleanText.equals("qulf", ignoreCase = true)
                    || cleanText.equals("quluf", ignoreCase = true)
                    || cleanText.equals("qulfla", ignoreCase = true)
                    || cleanText.equals("qulufla", ignoreCase = true)
                    || cleanText.equals("qulflay", ignoreCase = true)
                    || cleanText.equals("quluflay", ignoreCase = true)
                    || cleanText.equals("yopish", ignoreCase = true)
                    || cleanText.equals("yop", ignoreCase = true)
                    || cleanText.equals("yopilsin", ignoreCase = true)
                    || cleanText.equals("stop", ignoreCase = true)
                    || cleanText.equals("close", ignoreCase = true)
                    || cleanText.equals("tamom", ignoreCase = true)
                    || cleanText.equals("dars tugadi", ignoreCase = true)
                    || cleanText.contains("doskani qulf", ignoreCase = true)
                    || cleanText.contains("doskani quluf", ignoreCase = true)
                    || cleanText.contains("doskani blok", ignoreCase = true)
                    || cleanText.contains("doskani block", ignoreCase = true)
                    || cleanText.contains("doskani yop", ignoreCase = true)
                    || cleanText.contains("darsni yop", ignoreCase = true)
                    || cleanText.contains("dars tugadi", ignoreCase = true)
                    || text == "cmd_lock"
                    || text == "cmd_block" -> {

                val explicitArg = if (cleanText.startsWith("/lock")) cleanText.removePrefix("/lock").trim() else ""
                val rooms = accessControl.getRooms()

                val matchedRoom = if (explicitArg.isNotBlank()) {
                    rooms.find { it.id.equals(explicitArg, ignoreCase = true) || it.name.contains(explicitArg, ignoreCase = true) }
                } else null

                if (matchedRoom != null) {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Doskani qulflashni tasdiqlash</b>
                            
                            Xona: <b>${matchedRoom.name}</b> (<code>${matchedRoom.id}</code>)
                            Mas'ul: <b>$userDisplayName</b>
                            
                            Doskani qulflash uchun quyidagi tugmani bosing:
                        """.trimIndent(),
                        replyMarkup = buildConfirmLockInlineKeyboard(matchedRoom.id)
                    )
                } else if (rooms.isEmpty()) {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Doskani qulflashni tasdiqlash</b>
                            
                            Hozircha tizimda alohida xonalar qo'shilmagan.
                            Doskani qulflash uchun quyidagi tugmani bosing:
                        """.trimIndent(),
                        replyMarkup = buildConfirmLockInlineKeyboard("ALL")
                    )
                } else {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Qaysi xonadagi doskani yopmoqchisiz?</b>
                            
                            Iltimos, darsni yakunlash va doskani qulflash uchun kerakli xonani tanlang:
                        """.trimIndent(),
                        replyMarkup = buildRoomSelectionInlineKeyboard(rooms, "cmd_room_lock", includeAll = true)
                    )
                }
            }

            // Extend 10 minutes
            cleanText.contains("10 daqiqa qo'shish", ignoreCase = true)
                    || cleanText.contains("10 daqiqa", ignoreCase = true)
                    || cleanText.startsWith("/extend", ignoreCase = true)
                    || cleanText.equals("extend", ignoreCase = true)
                    || text == "cmd_extend" -> {
                accessControl.logAccess(userId, userDisplayName, "10 daqiqa qo'shildi")
                withContext(Dispatchers.Main) {
                    onExtendRequested(10)
                }
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = """
                        <b>Dars vaqti 10 daqiqaga uzaytirildi!</b>
                        Vaqt: <code>${accessControl.getFormattedNow()}</code>
                    """.trimIndent()
                )
            }

            // Status info
            cleanText.contains("Qolgan vaqt", ignoreCase = true)
                    || cleanText.contains("Holatni tekshirish", ignoreCase = true)
                    || cleanText.contains("Doska holati", ignoreCase = true)
                    || cleanText.startsWith("/status")
                    || text == "cmd_status" -> {
                accessControl.logAccess(userId, userDisplayName, "Holat ko'rildi")
                val statusText = withContext(Dispatchers.Main) { getStatusInfo() }
                sendMessage(
                    token = token,
                    chatId = chatId,
                    text = "<b>Doska holati:</b>\n$statusText\nVaqt: <code>${accessControl.getFormattedNow()}</code>"
                )
            }

            // --- ROOM MANAGEMENT: Add Room (Super Admin & IT Admin ONLY) ---
            cleanText.startsWith("/add_room") || cleanText.startsWith("/register_board") || cleanText.equals("Xona qo'shish", ignoreCase = true) -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val subText = when {
                    cleanText.startsWith("/add_room") -> cleanText.removePrefix("/add_room").trim()
                    cleanText.startsWith("/register_board") -> cleanText.removePrefix("/register_board").trim()
                    else -> ""
                }
                val parts = if (subText.isNotBlank()) subText.split("\\s+".toRegex(), limit = 2) else emptyList()
                val roomId = parts.getOrNull(0)?.trim()?.uppercase() ?: ""
                val roomName = parts.getOrNull(1)?.trim() ?: ""

                if (roomId.isNotBlank() && roomName.isNotBlank()) {
                    accessControl.addRoom(roomId, roomName, userDisplayName)
                    accessControl.logAccess(userId, userDisplayName, "Xona qo'shildi: $roomName ($roomId)")
                    withContext(Dispatchers.Main) {
                        LessonSessionManager.onBoardRegisteredFromBot?.invoke(roomId, roomName)
                    }
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Yangi xona muvaffaqiyatli qo'shildi!</b>
                            
                            Xona nomi: <b>$roomName</b>
                            Xona ID / MAC: <code>$roomId</code>
                            Mas'ul: <b>$userDisplayName</b>
                            Vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            Doska avtomatik faollashtirildi va tizimga ulandi.
                        """.trimIndent()
                    )
                } else {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Yangi xona qo'shish yo'riqnomasi:</b>
                            
                            Quyidagi formatda buyruq yuboring:
                            <code>/add_room &lt;XONA_ID&gt; &lt;XONA_NOMI&gt;</code>
                            
                            <i>Misollar:</i>
                            <code>/add_room 103-XONA 103-Fizika xonasi</code>
                            <code>/add_room 204-XONA 204-Tarix xonasi</code>
                            
                            <i>Eslatma: Xona ID raqami doska ilovasidagi "Qurilma ID" bilan bir xil bo'lishi lozim.</i>
                        """.trimIndent()
                    )
                }
            }

            // --- ROOM MANAGEMENT: List Rooms (Super Admin & IT Admin ONLY) ---
            cleanText.startsWith("/rooms") || cleanText.equals("Xonalar", ignoreCase = true) || cleanText.equals("Xonalar ro'yxati", ignoreCase = true) -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val rooms = accessControl.getRooms()
                if (rooms.isEmpty()) {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Hozircha birorta ham xona qo'shilmagan.</b>
                            
                            Yangi xona qo'shish uchun:
                            <code>/add_room XONA-1 1-Informatika xonasi</code>
                        """.trimIndent()
                    )
                } else {
                    val msg = buildString {
                        append("<b>Ro'yxatdan o'tgan xonalar (${rooms.size} ta):</b>\n\n")
                        rooms.forEachIndexed { index, r ->
                            append("${index + 1}. <b>${r.name}</b>\n")
                            append("   ID: <code>${r.id}</code>\n")
                            append("   Qo'shgan: ${r.addedBy}\n")
                            append("   Vaqt: <code>${r.addedAt}</code>\n")
                            append("   O'chirish: <code>/del_room ${r.id}</code>\n\n")
                        }
                        append("Yangi qo'shish: <code>/add_room &lt;ID&gt; &lt;Nomi&gt;</code>")
                    }
                    sendMessage(token, chatId, msg)
                }
            }

            // --- ROOM MANAGEMENT: Delete Room (Super Admin & IT Admin ONLY) ---
            cleanText.startsWith("/del_room") -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val targetId = cleanText.removePrefix("/del_room").trim().uppercase()
                if (targetId.isNotBlank()) {
                    val removed = accessControl.removeRoom(targetId)
                    if (removed) {
                        accessControl.logAccess(userId, userDisplayName, "Xona o'chirildi: $targetId")
                        sendMessage(token, chatId, "Xona (<code>$targetId</code>) muvaffaqiyatli o'chirildi.")
                    } else {
                        sendMessage(token, chatId, "Xona (<code>$targetId</code>) ro'yxatda topilmadi.")
                    }
                } else {
                    sendMessage(token, chatId, "Format: <code>/del_room &lt;XONA_ID&gt;</code>")
                }
            }

            // --- ADMIN CAPABILITY: Add new teacher ---
            cleanText.startsWith("/add_teacher") || cleanText.contains("O'qituvchi qo'shish", ignoreCase = true) -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val parts = cleanText.removePrefix("/add_teacher").trim().split("\\s+".toRegex(), limit = 2)
                val targetId = parts.getOrNull(0)?.toLongOrNull()
                val targetName = parts.getOrNull(1)?.trim() ?: ""

                if (targetId != null) {
                    val finalName = targetName.ifBlank { "O'qituvchi ($targetId)" }
                    accessControl.addTeacher(targetId, finalName)
                    accessControl.logAccess(userId, userDisplayName, "O'qituvchi qo'shildi: $finalName (ID: $targetId)")

                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Yangi o'qituvchi muvaffaqiyatli qo'shildi!</b>
                            
                            Ism: <b>$finalName</b>
                            Telegram ID: <code>$targetId</code>
                            Qo'shilgan vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            Endi bu o'qituvchi doskani ochish uchun botdan foydalana oladi.
                        """.trimIndent()
                    )
                } else {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Yangi o'qituvchi qo'shish yo'riqnomasi:</b>
                            
                            Quyidagi formatda buyruq yuboring:
                            <code>/add_teacher &lt;Telegram_ID&gt; &lt;Ism Familiya&gt;</code>
                            
                            <i>Misol:</i>
                            <code>/add_teacher 123456789 Jasur Rahim</code>
                            
                            <i>O'qituvchi o'z Telegram ID raqamini @userinfobot orqali bilib olishi mumkin.</i>
                        """.trimIndent()
                    )
                }
            }

            // --- ADMIN CAPABILITY: List teachers ---
            cleanText.startsWith("/teachers") || cleanText.equals("O'qituvchilar", ignoreCase = true) || cleanText.equals("O'qituvchilar ro'yxati", ignoreCase = true) -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val teachers = accessControl.getTeachers()
                if (teachers.isEmpty()) {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Hozircha ro'yxatdan o'tgan o'qituvchilar yo'q.</b>
                            
                            Yangi o'qituvchi qo'shish uchun:
                            <code>/add_teacher &lt;Telegram_ID&gt; &lt;Ism&gt;</code>
                        """.trimIndent()
                    )
                } else {
                    val msg = buildString {
                        append("<b>Ruxsat berilgan o'qituvchilar (${teachers.size} ta):</b>\n\n")
                        teachers.forEachIndexed { index, t ->
                            append("${index + 1}. <b>${t.name}</b>\n")
                            append("   ID: <code>${t.id}</code>\n")
                            append("   Qo'shildi: <code>${t.addedAt}</code>\n")
                            append("   O'chirish: <code>/del_teacher ${t.id}</code>\n\n")
                        }
                    }
                    sendMessage(token, chatId, msg)
                }
            }

            // --- ADMIN CAPABILITY: Remove teacher ---
            cleanText.startsWith("/del_teacher") -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val targetId = cleanText.removePrefix("/del_teacher").trim().toLongOrNull()
                if (targetId != null) {
                    val removed = accessControl.removeTeacher(targetId)
                    if (removed) {
                        accessControl.logAccess(userId, userDisplayName, "O'qituvchi o'chirildi (ID: $targetId)")
                        sendMessage(token, chatId, "O'qituvchi (ID: <code>$targetId</code>) muvaffaqiyatli o'chirildi.")
                    } else {
                        sendMessage(token, chatId, "O'qituvchi (ID: <code>$targetId</code>) ro'yxatda topilmadi.")
                    }
                } else {
                    sendMessage(token, chatId, "O'chirish uchun: <code>/del_teacher &lt;Telegram_ID&gt;</code>")
                }
            }

            // --- ADMIN CAPABILITY: Access Logging (precise timestamp hh:mm:ss/dd/mm/yyyy) ---
            cleanText.startsWith("/logs") || cleanText.contains("Kirish jurnali", ignoreCase = true) -> {
                if (!isItAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Super Admin va IT Adminlar uchun ruxsat etilgan.")
                    return
                }

                val logs = accessControl.getAccessLogs().take(20)
                if (logs.isEmpty()) {
                    sendMessage(token, chatId, "<b>Hozircha tizimga kirish jurnali bo'sh.</b>")
                } else {
                    val msg = buildString {
                        append("<b>Tizimga kirish jurnali (Access Logs):</b>\n")
                        append("<i>Format: hh:mm:ss/dd/mm/yyyy</i>\n\n")
                        logs.forEachIndexed { index, l ->
                            append("${index + 1}. Vaqt: <code>${l.timestamp}</code>\n")
                            append("   Foydalanuvchi: <b>${l.userName}</b> [${l.role}]\n")
                            append("   ID: <code>${l.userId}</code>\n")
                            append("   Amal: <i>${l.action}</i>\n\n")
                        }
                    }
                    sendMessage(token, chatId, msg)
                }
            }

            // --- SUPER ADMIN CAPABILITY: Add / Remove IT Admins ---
            cleanText.startsWith("/add_admin") || cleanText.contains("IT Admin qo'shish", ignoreCase = true) -> {
                if (!isSuperAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Bosh Super Admin (ID: ${AccessControlManager.SUPER_ADMIN_ID}) uchun.")
                    return
                }

                val subText = if (cleanText.startsWith("/add_admin")) cleanText.removePrefix("/add_admin").trim() else ""
                val parts = if (subText.isNotBlank()) subText.split("\\s+".toRegex(), limit = 2) else emptyList()
                val targetId = parts.getOrNull(0)?.toLongOrNull()
                val targetName = parts.getOrNull(1)?.trim() ?: ""

                if (targetId != null) {
                    val finalName = targetName.ifBlank { "IT Admin ($targetId)" }
                    accessControl.addItAdmin(targetId, finalName)
                    accessControl.logAccess(userId, userDisplayName, "IT Admin tayinlandi: $finalName (ID: $targetId)")
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>IT Admin muvaffaqiyatli tayinlandi!</b>
                            
                            Ism: <b>$finalName</b>
                            Telegram ID: <code>$targetId</code>
                            Tayinlangan vaqt: <code>${accessControl.getFormattedNow()}</code>
                            
                            <i>Ushbu foydalanuvchi endi xonalar va o'qituvchilarni boshqarish, jurnallarni ko'rish huquqiga ega.</i>
                        """.trimIndent()
                    )
                } else {
                    sendMessage(
                        token = token,
                        chatId = chatId,
                        text = """
                            <b>Yangi IT Admin tayinlash yo'riqnomasi:</b>
                            
                            Quyidagi buyruqni nusxalab yuboring:
                            <code>/add_admin &lt;Telegram_ID&gt; &lt;Ism Familiya&gt;</code>
                            
                            <i>Misol:</i>
                            <code>/add_admin 8223415949 Husniddin</code>
                        """.trimIndent()
                    )
                }
            }

            cleanText.startsWith("/del_admin") -> {
                if (!isSuperAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Bosh Super Admin uchun.")
                    return
                }
                val targetId = cleanText.removePrefix("/del_admin").trim().toLongOrNull()
                if (targetId != null) {
                    val removed = accessControl.removeItAdmin(targetId)
                    if (removed) {
                        accessControl.logAccess(userId, userDisplayName, "IT Admin o'chirildi (ID: $targetId)")
                        sendMessage(token, chatId, "IT Admin (ID: <code>$targetId</code>) muvaffaqiyatli o'chirildi.")
                    } else {
                        sendMessage(token, chatId, "IT Admin (ID: <code>$targetId</code>) ro'yxatda topilmadi.")
                    }
                } else {
                    sendMessage(token, chatId, "Format: <code>/del_admin &lt;Telegram_ID&gt;</code>")
                }
            }

            cleanText.startsWith("/admins") || cleanText.equals("IT Adminlar", ignoreCase = true) -> {
                if (!isSuperAdmin) {
                    sendMessage(token, chatId, "Bu amal faqat Bosh Super Admin uchun.")
                    return
                }
                val admins = accessControl.getItAdmins()
                val msg = buildString {
                    append("<b>Tizim Administratorlari bo'limi:</b>\n\n")
                    append("<b>Asosiy Super Admin:</b> <code>${AccessControlManager.SUPER_ADMIN_ID}</code>\n\n")
                    if (admins.isEmpty()) {
                        append("<i>Hozircha qo'shimcha IT Adminlar tayinlanmagan.</i>\n\n")
                        append("Qo'shish uchun: <code>/add_admin 8223415949 Husniddin</code>")
                    } else {
                        append("<b>Tayinlangan IT Adminlar (${admins.size} ta):</b>\n")
                        admins.forEachIndexed { index, a ->
                            append("${index + 1}. <b>${a.name}</b>\n")
                            append("   Telegram ID: <code>${a.id}</code>\n")
                            append("   Tayinlangan: <code>${a.addedAt}</code>\n")
                            append("   O'chirish: <code>/del_admin ${a.id}</code>\n\n")
                        }
                        append("Yangi qo'shish: <code>/add_admin &lt;ID&gt; &lt;Ism&gt;</code>")
                    }
                }
                sendMessage(token, chatId, msg)
            }

            else -> {
                sendWelcomeMenu(token, chatId, userDisplayName, isItAdmin, isSuperAdmin)
            }
        }
    }

    private fun sendWelcomeMenu(
        token: String,
        chatId: Long,
        displayName: String,
        isItAdmin: Boolean,
        isSuperAdmin: Boolean
    ) {
        val roleBadge = when {
            isSuperAdmin -> "Super Admin"
            isItAdmin -> "IT Admin"
            else -> "O'qituvchi"
        }

        val welcomeText = """
            <b>Assalomu alaykum, $displayName!</b>
            
            Sizning maqomingiz: <b>$roleBadge</b>
            Bu <b>86" 4K UHD Interaktiv Doska</b> boshqaruv boti.
            
            Kerakli amalni tanlang:
        """.trimIndent()

        val keyboardArray = JSONArray().apply {
            put(JSONArray().apply {
                put("Darsni boshlash (45 daqiqa)")
                put("Doskani yopish")
            })
            put(JSONArray().apply {
                put("Holatni tekshirish")
                put("10 daqiqa qo'shish")
            })

            // Admin buttons: Xonalar va o'qituvchilar boshqaruvi
            if (isItAdmin) {
                put(JSONArray().apply {
                    put("Xonalar")
                    put("Xona qo'shish")
                })
                put(JSONArray().apply {
                    put("O'qituvchilar")
                    put("O'qituvchi qo'shish")
                })
                put(JSONArray().apply {
                    put("Kirish jurnali")
                    if (isSuperAdmin) {
                        put("IT Adminlar")
                    }
                })
                if (isSuperAdmin) {
                    put(JSONArray().apply {
                        put("IT Admin qo'shish")
                    })
                }
            }
        }

        val keyboard = JSONObject().apply {
            put("keyboard", keyboardArray)
            put("resize_keyboard", true)
            put("one_time_keyboard", false)
        }

        sendMessage(token, chatId, welcomeText, keyboard)
    }

    fun sendMessage(token: String, chatId: Long, text: String, replyMarkup: JSONObject? = null) {
        if (token.isBlank() || chatId == 0L) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "HTML")
                    if (replyMarkup != null) {
                        put("reply_markup", replyMarkup)
                    }
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(tag, "Failed to send message: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Warning sending telegram message: ${e.message}")
            }
        }
    }

    fun notifyAdminsNewBoardDetected(macAddress: String, androidId: String) {
        val token = activeToken
        if (token.isBlank()) return
        val adminIds = accessControl.getAllAdminIds()
        val keyboard = JSONObject().apply {
            val rows = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "Xona ma'lumotlarini kiritish")
                        put("callback_data", "cmd_setup_board:$macAddress")
                    })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "Holatni tekshirish")
                        put("callback_data", "cmd_status")
                    })
                })
            }
            put("inline_keyboard", rows)
        }

        val noticeText = """
            <b>Yangi interaktiv doska aniqlandi!</b>
            
            Apparat MAC manzili: <code>$macAddress</code>
            Qurilma ID: <code>$androidId</code>
            
            Ushbu yangi doska o'rnatilgan xona ma'lumotlarini kiriting.
            Quyidagi tugmani bosing yoki to'g'ridan-to'g'ri xona nomini yuboring:
        """.trimIndent()

        for (adminId in adminIds) {
            sendMessage(token, adminId, noticeText, keyboard)
        }
    }

    private fun buildRoomSelectionInlineKeyboard(
        rooms: List<RoomEntry>,
        actionPrefix: String,
        includeAll: Boolean = false
    ): JSONObject {
        val rows = JSONArray()
        rooms.forEach { room ->
            rows.put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", room.name)
                    put("callback_data", "$actionPrefix:${room.id}")
                })
            })
        }
        if (includeAll) {
            rows.put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Barcha doskalarni qulflash")
                    put("callback_data", "$actionPrefix:ALL")
                })
            })
        }
        return JSONObject().apply {
            put("inline_keyboard", rows)
        }
    }

    private fun buildConfirmLockInlineKeyboard(targetRoomId: String): JSONObject {
        val rows = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Doskani qulflash")
                    put("callback_data", "cmd_do_lock:$targetRoomId")
                })
            })
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Bekor qilish")
                    put("callback_data", "cmd_cancel_lock")
                })
            })
        }
        return JSONObject().apply {
            put("inline_keyboard", rows)
        }
    }

    private fun buildActiveLessonInlineKeyboard(roomId: String = ""): JSONObject {
        val rows = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Doskani yopish")
                    put("callback_data", "cmd_lock")
                })
            })
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "10 daqiqa qo'shish")
                    put("callback_data", "cmd_extend")
                })
                put(JSONObject().apply {
                    put("text", "Qolgan vaqt")
                    put("callback_data", "cmd_status")
                })
            })
        }
        return JSONObject().apply {
            put("inline_keyboard", rows)
        }
    }

    private fun buildStandbyInlineKeyboard(): JSONObject {
        val rows = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "45 daqiqalik darsni boshlash")
                    put("callback_data", "cmd_unlock")
                })
            })
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Doska holati")
                    put("callback_data", "cmd_status")
                })
            })
        }
        return JSONObject().apply {
            put("inline_keyboard", rows)
        }
    }

    private fun answerCallbackQuery(token: String, callbackId: String, alertText: String? = null) {
        if (token.isBlank() || callbackId.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("callback_query_id", callbackId)
                    if (!alertText.isNullOrBlank()) {
                        put("text", alertText)
                    }
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/answerCallbackQuery")
                    .post(requestBody)
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {}
        }
    }

    fun notifyLessonExpired() {
        val cid = lastActiveChatId
        val tok = activeToken
        if (cid != 0L && tok.isNotBlank()) {
            sendMessage(
                token = tok,
                chatId = cid,
                text = """
                    <b>45 daqiqalik dars vaqti tugadi!</b>
                    
                    Doska avtomatik ravishda qulflanadi va kutish rejimiga o'tkazildi.
                    Vaqt: <code>${accessControl.getFormattedNow()}</code>
                """.trimIndent()
            )
        }
    }

    suspend fun testToken(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
            client.newCall(request).execute().use { resp ->
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
}
