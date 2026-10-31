package com.example.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BoardServerClient(
    private val context: Context,
    private val prefs: SmartBoardPreferences,
    private val deviceMacAddress: String,
    private val hardwareId: String,
    private val onUnlockRequested: (teacherName: String, durationMinutes: Int, targetRoomId: String) -> Unit,
    private val onLockRequested: (targetRoomId: String) -> Unit,
    private val onExtendRequested: (additionalMinutes: Int) -> Unit
) {
    private val tag = "BoardServerClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var pollingJob: Job? = null
    @Volatile
    private var currentCall: okhttp3.Call? = null

    var isPollingActive = false
        private set

    var lastStatusMessage = "Serverga ulanmoqda..."
        private set(value) {
            field = value
            LessonSessionManager.botStatusText.value = value
        }

    fun startPolling(serverUrl: String, scope: CoroutineScope) {
        stopPolling()

        val cleanUrl = serverUrl.trim()
            .removeSuffix("/")
            .ifBlank { SmartBoardConfig.DEFAULT_SERVER_URL }

        pollingJob = scope.launch(Dispatchers.IO) {
            isPollingActive = true
            lastStatusMessage = "Render serverga ulanmoqda..."
            Log.d(tag, "Starting Render server polling: $cleanUrl")

            var backoffDelay = 1500L

            while (isActive) {
                try {
                    val config = prefs.loadConfig()
                    val pollPayload = JSONObject().apply {
                        put("mac", deviceMacAddress)
                        put("boardId", config.boardId)
                        put("roomName", config.roomName)
                        put("isLocked", LessonSessionManager.isLocked.value)
                        put("secondsRemaining", LessonSessionManager.activeSeconds.value)
                        put("teacherName", LessonSessionManager.teacherName.value)
                        put("hardwareId", hardwareId)
                        put("appVersion", "1.0")
                    }

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = pollPayload.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url("$cleanUrl/api/board/poll")
                        .post(requestBody)
                        .build()

                    val call = client.newCall(request)
                    currentCall = call

                    call.execute().use { response ->
                        currentCall = null
                        val bodyString = response.body?.string()

                        if (response.isSuccessful && bodyString != null) {
                            backoffDelay = 1500L
                            lastStatusMessage = "Render Server: Ulangan (24/7 Faol)"

                            val json = JSONObject(bodyString)
                            if (json.optBoolean("status", false) || json.optString("status") == "ok") {
                                val regRoomName = json.optString("registeredRoomName", "")
                                val isRegistered = json.optBoolean("isRegistered", false)
                                if (isRegistered && regRoomName.isNotBlank() && regRoomName != config.roomName) {
                                    Log.i(tag, "Doska xona nomi serverdan yangilandi: $regRoomName")
                                    prefs.saveConfig(config.copy(roomName = regRoomName, isRegistered = true))
                                }

                                val commandObj = json.optJSONObject("command")
                                if (commandObj != null) {
                                    handleServerCommand(cleanUrl, commandObj)
                                }
                            }
                        } else {
                            val code = response.code
                            lastStatusMessage = "Server javobi: $code (Kutilmoqda...)"
                            Log.w(tag, "Render server response error: $code")
                            delay(backoffDelay)
                            backoffDelay = (backoffDelay * 2).coerceAtMost(10000L)
                        }
                    }
                } catch (e: Exception) {
                    currentCall = null
                    if (!isActive) break

                    if (e is java.net.SocketTimeoutException) {
                        Log.d(tag, "Server poll timeout (normal interval)")
                    } else if (e is IOException) {
                        lastStatusMessage = "Serverga qayta ulanmoqda..."
                        Log.w(tag, "Server reconnecting: ${e.message}")
                    } else {
                        lastStatusMessage = "Server kutilmoqda..."
                        Log.w(tag, "Server polling error: ${e.message}")
                    }
                    delay(backoffDelay)
                    backoffDelay = (backoffDelay * 2).coerceAtMost(10000L)
                } finally {
                    currentCall = null
                }

                delay(1500L) // Har 1.5 soniyada server bilan yangilanadi
            }

            isPollingActive = false
        }
    }

    private val executedCommandIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private suspend fun handleServerCommand(serverUrl: String, cmd: JSONObject) {
        val cmdId = cmd.optString("id", "")
        val type = cmd.optString("type", "").uppercase()
        val targetRoomId = cmd.optString("targetRoomId", "")
        val teacherName = cmd.optString("teacherName", "O'qituvchi")
        val durationMinutes = cmd.optInt("durationMinutes", 45)
        val minutes = cmd.optInt("minutes", 10)

        Log.d(tag, "Server command received: $type (ID: $cmdId) for target: $targetRoomId")

        // Buyruqni tasdiqlash (ACK) va takroriy buyruqlarni filtrdan o'tkazish
        if (cmdId.isNotBlank()) {
            if (executedCommandIds.contains(cmdId)) {
                acknowledgeCommand(serverUrl, cmdId)
                return
            }
            executedCommandIds.add(cmdId)
            if (executedCommandIds.size > 100) {
                val it = executedCommandIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            acknowledgeCommand(serverUrl, cmdId)
        }

        when (type) {
            "UNLOCK" -> {
                onUnlockRequested(teacherName, durationMinutes, targetRoomId)
            }
            "LOCK" -> {
                onLockRequested(targetRoomId)
            }
            "EXTEND" -> {
                onExtendRequested(minutes)
            }
        }
    }

    private fun acknowledgeCommand(serverUrl: String, commandId: String) {
        try {
            val payload = JSONObject().apply {
                put("commandId", commandId)
                put("mac", deviceMacAddress)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = payload.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$serverUrl/api/board/ack")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(tag, "ACK failed for $commandId: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (_: Exception) {}
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
}
