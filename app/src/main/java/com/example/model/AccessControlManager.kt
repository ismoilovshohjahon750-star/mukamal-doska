package com.example.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AuthorizedUser(
    val id: Long,
    val name: String,
    val role: String, // "SUPER_ADMIN", "IT_ADMIN", "TEACHER"
    val addedAt: String
)

data class AccessLogEntry(
    val timestamp: String, // "hh:mm:ss/dd/mm/yyyy" e.g. "14:22:05/18/10/2026"
    val userId: Long,
    val userName: String,
    val role: String,
    val action: String
)

data class RoomEntry(
    val id: String, // e.g. "101-XONA"
    val name: String, // e.g. "101-Informatika xonasi"
    val addedBy: String,
    val addedAt: String
)

class AccessControlManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smartboard_access_control", Context.MODE_PRIVATE)

    companion object {
        const val SUPER_ADMIN_ID = 8453381252L
        const val SUPER_ADMIN_NAME = "Primary Super Admin"

        const val REJECTION_MESSAGE =
            "Unfortunately, you do not have permission to access this bot. If you are a new teacher, please visit the Computer Science room or request authorization from the Vice Principals."

        private const val KEY_IT_ADMINS = "key_it_admins_json"
        private const val KEY_TEACHERS = "key_teachers_json"
        private const val KEY_ACCESS_LOGS = "key_access_logs_json"
        private const val KEY_ROOMS = "key_rooms_json"
    }

    // Precise timestamp format required: hh:mm:ss/dd/mm/yyyy
    private val timestampFormat = SimpleDateFormat("HH:mm:ss/dd/MM/yyyy", Locale.getDefault())

    fun getFormattedNow(): String {
        return timestampFormat.format(Date())
    }

    fun isSuperAdmin(userId: Long): Boolean = userId == SUPER_ADMIN_ID

    fun isItAdmin(userId: Long): Boolean {
        if (isSuperAdmin(userId)) return true
        return getItAdmins().any { it.id == userId }
    }

    fun isTeacher(userId: Long): Boolean {
        return getTeachers().any { it.id == userId }
    }

    fun isAuthorized(userId: Long): Boolean {
        return isSuperAdmin(userId) || isItAdmin(userId) || isTeacher(userId)
    }

    fun getAllAdminIds(): Set<Long> {
        val set = mutableSetOf(SUPER_ADMIN_ID)
        getItAdmins().forEach { set.add(it.id) }
        return set
    }

    fun getUserRole(userId: Long): String {
        return when {
            isSuperAdmin(userId) -> "Super Admin"
            isItAdmin(userId) -> "IT Admin"
            isTeacher(userId) -> "O'qituvchi"
            else -> "Noma'lum"
        }
    }

    fun getUserDisplayName(userId: Long, fallback: String = ""): String {
        if (isSuperAdmin(userId)) return "$SUPER_ADMIN_NAME"
        val itAdmin = getItAdmins().find { it.id == userId }
        if (itAdmin != null) return itAdmin.name
        val teacher = getTeachers().find { it.id == userId }
        if (teacher != null) return teacher.name
        return fallback.ifBlank { "Foydalanuvchi ($userId)" }
    }

    // --- Teacher Management ---
    @Synchronized
    fun getTeachers(): List<AuthorizedUser> {
        val jsonStr = prefs.getString(KEY_TEACHERS, "[]") ?: "[]"
        val list = mutableListOf<AuthorizedUser>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AuthorizedUser(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        role = "TEACHER",
                        addedAt = obj.optString("addedAt", getFormattedNow())
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun addTeacher(id: Long, name: String): Boolean {
        val teachers = getTeachers().toMutableList()
        teachers.removeAll { it.id == id }
        val resolvedName = name.trim().ifBlank { "O'qituvchi ($id)" }
        teachers.add(0, AuthorizedUser(id, resolvedName, "TEACHER", getFormattedNow()))
        return saveTeachers(teachers)
    }

    @Synchronized
    fun removeTeacher(id: Long): Boolean {
        val teachers = getTeachers().toMutableList()
        val removed = teachers.removeAll { it.id == id }
        if (removed) {
            saveTeachers(teachers)
        }
        return removed
    }

    private fun saveTeachers(list: List<AuthorizedUser>): Boolean {
        val arr = JSONArray()
        for (u in list) {
            val obj = JSONObject().apply {
                put("id", u.id)
                put("name", u.name)
                put("addedAt", u.addedAt)
            }
            arr.put(obj)
        }
        return prefs.edit().putString(KEY_TEACHERS, arr.toString()).commit()
    }

    // --- IT Admin Management (Super Admin only) ---
    @Synchronized
    fun getItAdmins(): List<AuthorizedUser> {
        val jsonStr = prefs.getString(KEY_IT_ADMINS, null)
        val list = mutableListOf<AuthorizedUser>()
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AuthorizedUser(
                            id = obj.getLong("id"),
                            name = obj.getString("name"),
                            role = "IT_ADMIN",
                            addedAt = obj.optString("addedAt", getFormattedNow())
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return list
    }

    @Synchronized
    fun addItAdmin(id: Long, name: String): Boolean {
        val admins = getItAdmins().toMutableList()
        admins.removeAll { it.id == id }
        val resolvedName = name.trim().ifBlank { "IT Admin ($id)" }
        admins.add(0, AuthorizedUser(id, resolvedName, "IT_ADMIN", getFormattedNow()))
        return saveItAdmins(admins)
    }

    @Synchronized
    fun removeItAdmin(id: Long): Boolean {
        val admins = getItAdmins().toMutableList()
        val removed = admins.removeAll { it.id == id }
        if (removed) {
            saveItAdmins(admins)
        }
        return removed
    }

    private fun saveItAdmins(list: List<AuthorizedUser>): Boolean {
        val arr = JSONArray()
        for (u in list) {
            val obj = JSONObject().apply {
                put("id", u.id)
                put("name", u.name)
                put("addedAt", u.addedAt)
            }
            arr.put(obj)
        }
        return prefs.edit().putString(KEY_IT_ADMINS, arr.toString()).commit()
    }

    // --- Access Logging with format hh:mm:ss/dd/mm/yyyy ---
    @Synchronized
    fun logAccess(userId: Long, userName: String, action: String) {
        val role = getUserRole(userId)
        val timestamp = getFormattedNow()
        val entry = AccessLogEntry(
            timestamp = timestamp,
            userId = userId,
            userName = userName,
            role = role,
            action = action
        )

        val logs = getAccessLogs().toMutableList()
        logs.add(0, entry)
        // Keep the latest 200 logs
        val trimmed = logs.take(200)

        val arr = JSONArray()
        for (l in trimmed) {
            val obj = JSONObject().apply {
                put("timestamp", l.timestamp)
                put("userId", l.userId)
                put("userName", l.userName)
                put("role", l.role)
                put("action", l.action)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ACCESS_LOGS, arr.toString()).apply()
    }

    @Synchronized
    fun getAccessLogs(): List<AccessLogEntry> {
        val jsonStr = prefs.getString(KEY_ACCESS_LOGS, "[]") ?: "[]"
        val list = mutableListOf<AccessLogEntry>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AccessLogEntry(
                        timestamp = obj.getString("timestamp"),
                        userId = obj.getLong("userId"),
                        userName = obj.getString("userName"),
                        role = obj.getString("role"),
                        action = obj.getString("action")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun clearLogs() {
        prefs.edit().remove(KEY_ACCESS_LOGS).apply()
    }

    // --- Room / Board Management (Super Admin & IT Admin only) ---
    @Synchronized
    fun getRooms(): List<RoomEntry> {
        val jsonStr = prefs.getString(KEY_ROOMS, null)
        val list = mutableListOf<RoomEntry>()
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        RoomEntry(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            addedBy = obj.optString("addedBy", "Tizim"),
                            addedAt = obj.optString("addedAt", getFormattedNow())
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        // Shablon sifatida qo'shilgan eski xonalar bo'lsa ularni tozalash
        val cleanedList = list.filterNot { it.addedBy == "Tizim" && (it.id == "101-XONA" || it.id == "102-XONA") }
        if (cleanedList.size != list.size) {
            saveRooms(cleanedList)
            return cleanedList
        }
        return list
    }

    @Synchronized
    fun clearRooms(): Boolean {
        return prefs.edit().putString(KEY_ROOMS, "[]").commit()
    }

    @Synchronized
    fun addRoom(id: String, name: String, addedBy: String): Boolean {
        val cleanId = id.trim().uppercase()
        if (cleanId.isBlank()) return false
        val rooms = getRooms().toMutableList()
        rooms.removeAll { it.id.equals(cleanId, ignoreCase = true) }
        val resolvedName = name.trim().ifBlank { cleanId }
        rooms.add(RoomEntry(cleanId, resolvedName, addedBy, getFormattedNow()))
        return saveRooms(rooms)
    }

    @Synchronized
    fun removeRoom(id: String): Boolean {
        val cleanId = id.trim().uppercase()
        val rooms = getRooms().toMutableList()
        val removed = rooms.removeAll { it.id.equals(cleanId, ignoreCase = true) }
        if (removed) {
            saveRooms(rooms)
        }
        return removed
    }

    private fun saveRooms(list: List<RoomEntry>): Boolean {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("addedBy", r.addedBy)
                put("addedAt", r.addedAt)
            }
            arr.put(obj)
        }
        return prefs.edit().putString(KEY_ROOMS, arr.toString()).commit()
    }
}
