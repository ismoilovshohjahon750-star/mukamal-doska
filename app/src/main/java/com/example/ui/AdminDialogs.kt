package com.example.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.AccessControlManager
import com.example.model.AccessLogEntry
import com.example.model.AuthorizedUser
import com.example.model.RoomEntry
import com.example.model.SmartBoardConfig
import com.example.ui.theme.DarkNavyBg
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGold
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceLightDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun AdminPinDialog(
    correctPin: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCyan.copy(alpha = 0.5f)),
            modifier = Modifier.width(380.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(36.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Admin Sozlamalari PIN",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Text(
                    text = "Tizim sozlamalariga kirish uchun PIN kiriting (Standart: 1234).\nDoska faqat Telegram bot orqali ochiladi.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // PIN dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val display = enteredPin.padEnd(4, '•').take(4)
                    Text(
                        text = display.map { "$it " }.joinToString(""),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = if (isError) Color(0xFFEF4444) else NeonCyan,
                        letterSpacing = 6.sp
                    )
                }

                if (isError) {
                    Text(
                        text = "Noto'g'ri PIN kod!",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val numRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "⌫")
                )

                numRows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        row.forEach { digit ->
                            Button(
                                onClick = {
                                    isError = false
                                    when (digit) {
                                        "C" -> enteredPin = ""
                                        "⌫" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                        else -> {
                                            if (enteredPin.length < 6) {
                                                enteredPin += digit
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (digit in listOf("C", "⌫")) Color(0xFF334155) else Color(0xFF1E293B),
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(72.dp, 48.dp)
                            ) {
                                if (digit == "⌫") {
                                    Icon(imageVector = Icons.Default.Backspace, contentDescription = null, modifier = Modifier.size(18.dp))
                                } else {
                                    Text(digit, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Bekor qilish", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (enteredPin == correctPin) {
                                onOpenSettings()
                            } else {
                                isError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("btn_pin_confirm")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kirish", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminSettingsDialog(
    currentConfig: SmartBoardConfig,
    teachers: List<AuthorizedUser>,
    itAdmins: List<AuthorizedUser>,
    accessLogs: List<AccessLogEntry>,
    rooms: List<RoomEntry> = emptyList(),
    androidId: String = "",
    macAddress: String = "",
    onAddRoom: (id: String, name: String) -> Unit = { _, _ -> },
    onRemoveRoom: (id: String) -> Unit = {},
    onClearRooms: () -> Unit = {},
    onAddTeacher: (id: Long, name: String) -> Unit,
    onRemoveTeacher: (id: Long) -> Unit,
    onAddItAdmin: (id: Long, name: String) -> Unit,
    onRemoveItAdmin: (id: Long) -> Unit,
    onClearLogs: () -> Unit,
    onSaveConfig: (SmartBoardConfig) -> Unit,
    onTestToken: suspend (String) -> Pair<Boolean, String>,
    onTestServer: (suspend (String) -> Pair<Boolean, String>)? = null,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Settings, 1: Rooms, 2: Teachers, 3: IT Admins, 4: Access Logs

    var connectionMode by remember { mutableStateOf(currentConfig.connectionMode) }
    var serverUrl by remember { mutableStateOf(currentConfig.serverUrl) }
    var serverTestStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTestingServer by remember { mutableStateOf(false) }

    var botToken by remember { mutableStateOf(currentConfig.botToken) }
    var botUsername by remember { mutableStateOf(currentConfig.botUsername) }
    var boardId by remember { mutableStateOf(currentConfig.boardId) }
    var roomName by remember { mutableStateOf(currentConfig.roomName) }
    var adminPin by remember { mutableStateOf(currentConfig.adminPin) }
    var wallpaperUrl by remember { mutableStateOf(currentConfig.wallpaperUrl) }

    // Add room inputs
    var newRoomIdText by remember { mutableStateOf("") }
    var newRoomNameText by remember { mutableStateOf("") }
    var roomActionFeedback by remember { mutableStateOf<String?>(null) }

    // Add teacher inputs
    var newTeacherIdText by remember { mutableStateOf("") }
    var newTeacherNameText by remember { mutableStateOf("") }
    var teacherActionFeedback by remember { mutableStateOf<String?>(null) }

    // Add IT admin inputs
    var newItAdminIdText by remember { mutableStateOf("") }
    var newItAdminNameText by remember { mutableStateOf("") }
    var itAdminActionFeedback by remember { mutableStateOf<String?>(null) }

    var testStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCyan.copy(alpha = 0.5f)),
            modifier = Modifier
                .width(640.dp)
                .height(600.dp)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                // Header with Super Admin badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Admin Panel (86\" SmartBoard)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    // Super Admin ID Badge
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0F2B48), RoundedCornerShape(8.dp))
                            .border(1.dp, NeonGold.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Super Admin: ${AccessControlManager.SUPER_ADMIN_ID}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Navigation Tabs
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val tabs = listOf(
                        "Sozlamalar",
                        "Xonalar (${rooms.size})",
                        "O'qituvchilar (${teachers.size})",
                        "IT Adminlar (${itAdmins.size})",
                        "Jurnal (${accessLogs.size})"
                    )

                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) NeonCyan.copy(alpha = 0.15f) else Color(0xFF1E293B),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) NeonCyan else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) NeonCyan else TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tab Content (Scrollable)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        0 -> {
                            // TAB 0: SYSTEM & BOT SETTINGS
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // 1. Ulanish usuli (Connection Mode) tanlash
                                Text("Ulanish arxitekturasi:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NeonCyan)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val isRenderMode = connectionMode == com.example.model.SmartBoardConfig.MODE_RENDER_SERVER
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (isRenderMode) NeonCyan.copy(alpha = 0.2f) else Color(0xFF1E293B),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                1.5.dp,
                                                if (isRenderMode) NeonCyan else Color(0xFF334155),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { connectionMode = com.example.model.SmartBoardConfig.MODE_RENDER_SERVER }
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = if (isRenderMode) NeonCyan else TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Render Server",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isRenderMode) NeonCyan else TextPrimary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Ko'p doskalar uchun (Tavsiya)",
                                                fontSize = 10.sp,
                                                color = if (isRenderMode) Color(0xFF38BDF8) else TextSecondary
                                            )
                                        }
                                    }

                                    val isDirectMode = connectionMode == com.example.model.SmartBoardConfig.MODE_DIRECT_BOT
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (isDirectMode) NeonGold.copy(alpha = 0.2f) else Color(0xFF1E293B),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                1.5.dp,
                                                if (isDirectMode) NeonGold else Color(0xFF334155),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { connectionMode = com.example.model.SmartBoardConfig.MODE_DIRECT_BOT }
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Key,
                                                    contentDescription = null,
                                                    tint = if (isDirectMode) NeonGold else TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "To'g'ridan-to'g'ri Bot",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isDirectMode) NeonGold else TextPrimary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Faqat 1 ta doska uchun",
                                                fontSize = 10.sp,
                                                color = if (isDirectMode) NeonGold else TextSecondary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (connectionMode == com.example.model.SmartBoardConfig.MODE_RENDER_SERVER) {
                                    // RENDER SERVER SOZLAMALARI
                                    Text("Render Server Manzili (URL):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                    OutlinedTextField(
                                        value = serverUrl,
                                        onValueChange = {
                                            serverUrl = it
                                            serverTestStatus = null
                                        },
                                        singleLine = true,
                                        placeholder = { Text("https://smartboard-bot-server.onrender.com", color = TextSecondary) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NeonCyan,
                                            unfocusedBorderColor = SurfaceLightDark,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedContainerColor = DarkNavyBg,
                                            unfocusedContainerColor = DarkNavyBg
                                        ),
                                        modifier = Modifier.fillMaxWidth().testTag("input_server_url")
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Button(
                                            onClick = {
                                                isTestingServer = true
                                                serverTestStatus = null
                                                scope.launch {
                                                    serverTestStatus = onTestServer?.invoke(serverUrl.trim()) ?: Pair(false, "Test funksiyasi mavjud emas")
                                                    isTestingServer = false
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = NeonCyan),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            if (isTestingServer) {
                                                CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text("Server aloqasini tekshirish", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        if (serverTestStatus != null) {
                                            val (success, msg) = serverTestStatus!!
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                                    contentDescription = null,
                                                    tint = if (success) Color(0xFF10B981) else Color(0xFFEF4444),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(text = msg, fontSize = 11.sp, color = if (success) Color(0xFF10B981) else Color(0xFFEF4444))
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Eslatma: Bot Render serverida 24/7 ishlaydi. 2 ta yoki undan ortiq doskaga o'rnatilganda Telegram bot to'qnashmaydi (409 xatosi chiqmaydi).",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                } else {
                                    // TO'G'RIDAN-TO'G'RI BOT SOZLAMALARI
                                    Text("Telegram Bot Token (Faqat 1 ta doska):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                    OutlinedTextField(
                                        value = botToken,
                                        onValueChange = {
                                            botToken = it
                                            testStatus = null
                                        },
                                        singleLine = true,
                                        placeholder = { Text("123456789:AAH...", color = TextSecondary) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NeonCyan,
                                            unfocusedBorderColor = SurfaceLightDark,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedContainerColor = DarkNavyBg,
                                            unfocusedContainerColor = DarkNavyBg
                                        ),
                                        modifier = Modifier.fillMaxWidth().testTag("input_bot_token")
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Button(
                                            onClick = {
                                                isTesting = true
                                                testStatus = null
                                                scope.launch {
                                                    testStatus = onTestToken(botToken.trim())
                                                    isTesting = false
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = NeonCyan),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            if (isTesting) {
                                                CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text("Bot aloqasini tekshirish", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        if (testStatus != null) {
                                            val (success, msg) = testStatus!!
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                                    contentDescription = null,
                                                    tint = if (success) Color(0xFF10B981) else Color(0xFFEF4444),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(text = msg, fontSize = 11.sp, color = if (success) Color(0xFF10B981) else Color(0xFFEF4444))
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Doska xonasi (Qurilma ID):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                        OutlinedTextField(
                                            value = boardId,
                                            onValueChange = { boardId = it },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = NeonCyan,
                                                unfocusedBorderColor = SurfaceLightDark,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedContainerColor = DarkNavyBg,
                                                unfocusedContainerColor = DarkNavyBg
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Xona nomi:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                        OutlinedTextField(
                                            value = roomName,
                                            onValueChange = { roomName = it },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = NeonCyan,
                                                unfocusedBorderColor = SurfaceLightDark,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedContainerColor = DarkNavyBg,
                                                unfocusedContainerColor = DarkNavyBg
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                if (rooms.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Tezkor xona tanlash:", fontSize = 11.sp, color = TextSecondary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        rooms.take(4).forEach { r ->
                                            val isSelected = boardId.equals(r.id, ignoreCase = true)
                                            Box(
                                                modifier = Modifier
                                                    .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                                    .border(1.dp, if (isSelected) NeonCyan else Color(0xFF334155), RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        boardId = r.id
                                                        roomName = r.name
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = r.id,
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NeonCyan else TextPrimary
                                                )
                                            }
                                        }
                                    }
                                }

                                if (androidId.isNotBlank() || macAddress.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0D1726), RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        if (macAddress.isNotBlank()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "Qurilma MAC: $macAddress (Android 9)",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeonCyan
                                                )
                                                Text(
                                                    text = "MAC'ni ID qilish",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonGold,
                                                    modifier = Modifier
                                                        .clickable {
                                                            boardId = macAddress
                                                        }
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (androidId.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Qurilma Android ID: $androidId",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Admin PIN (Standart 1234):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                        OutlinedTextField(
                                            value = adminPin,
                                            onValueChange = { adminPin = it },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = NeonCyan,
                                                unfocusedBorderColor = SurfaceLightDark,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedContainerColor = DarkNavyBg,
                                                unfocusedContainerColor = DarkNavyBg
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Jonli fon (Wallpaper) URL:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                        OutlinedTextField(
                                            value = wallpaperUrl,
                                            onValueChange = { wallpaperUrl = it },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = NeonCyan,
                                                unfocusedBorderColor = SurfaceLightDark,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedContainerColor = DarkNavyBg,
                                                unfocusedContainerColor = DarkNavyBg
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(imageVector = Icons.Default.SmartDisplay, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Home App", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            try {
                                                activity?.startLockTask()
                                            } catch (_: Exception) {}
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A), contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Kiosk qulflash", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        1 -> {
                            // TAB 1: ROOMS / BOARDS MANAGEMENT (Admin & IT Admin only)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Add Room Card
                                Surface(
                                    color = Color(0xFF131D33),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "Yangi xona qo'shish (Faqat Admin va IT Adminlar):",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )

                                        if (macAddress.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF0D1726), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "Doska MAC manzili: $macAddress",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeonCyan
                                                )
                                                Text(
                                                    text = "MAC'ni xona ID qilish",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonGold,
                                                    modifier = Modifier
                                                        .clickable {
                                                            newRoomIdText = macAddress
                                                            if (newRoomNameText.isBlank()) {
                                                                newRoomNameText = roomName.ifBlank { "SmartBoard ($macAddress)" }
                                                            }
                                                        }
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = newRoomIdText,
                                                onValueChange = { newRoomIdText = it.uppercase() },
                                                singleLine = true,
                                                placeholder = { Text("Xona ID (masalan: 1-XONA)", fontSize = 11.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )

                                            OutlinedTextField(
                                                value = newRoomNameText,
                                                onValueChange = { newRoomNameText = it },
                                                singleLine = true,
                                                placeholder = { Text("Nomi (masalan: 1-Informatika xonasi)", fontSize = 11.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1.3f)
                                            )

                                            Button(
                                                onClick = {
                                                    val cleanId = newRoomIdText.trim().uppercase()
                                                    val cleanName = newRoomNameText.trim()
                                                    if (cleanId.isNotBlank() && cleanName.isNotBlank()) {
                                                        onAddRoom(cleanId, cleanName)
                                                        newRoomIdText = ""
                                                        newRoomNameText = ""
                                                        roomActionFeedback = "Muvaffaqiyatli qo'shildi: $cleanName"
                                                    } else {
                                                        roomActionFeedback = "Xona ID va Nomini kiriting!"
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("Qo'shish", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }

                                        if (roomActionFeedback != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = roomActionFeedback!!,
                                                fontSize = 11.sp,
                                                color = if (roomActionFeedback!!.startsWith("Muvaffaqiyatli")) Color(0xFF10B981) else Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Registered Rooms List
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Ro'yxatdan o'tgan xonalar (${rooms.size}):",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )

                                    if (rooms.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = onClearRooms,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Barchasini tozalash", fontSize = 10.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (rooms.isEmpty()) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp)
                                    ) {
                                        Text(
                                            text = "Hozircha birorta ham xona qo'shilmagan.\nYuqoridagi forma orqali yoki Telegram botda /add_room buyrug'i bilan xona qo'shishingiz mumkin.",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    rooms.forEach { room ->
                                        val isCurrentBoard = boardId.equals(room.id, ignoreCase = true)
                                        Surface(
                                            color = if (isCurrentBoard) Color(0xFF1E293B) else Color(0xFF151F33),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isCurrentBoard) NeonCyan else Color(0xFF243352)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.padding(12.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = room.name,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = TextPrimary
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFF0F2B48), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = room.id,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = NeonCyan
                                                            )
                                                        }
                                                        if (isCurrentBoard) {
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(Color(0xFF064E3B), RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = "Hozirgi doska",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFF34D399)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = "Qo'shgan: ${room.addedBy}  •  Vaqt: ${room.addedAt}",
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }

                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (!isCurrentBoard) {
                                                        Button(
                                                            onClick = {
                                                                boardId = room.id
                                                                roomName = room.name
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = NeonCyan),
                                                            shape = RoundedCornerShape(6.dp),
                                                            modifier = Modifier.height(30.dp)
                                                        ) {
                                                            Text("Biriktirish", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }

                                                    IconButton(
                                                        onClick = { onRemoveRoom(room.id) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "O'chirish",
                                                            tint = Color(0xFFEF4444),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // TAB 2: TEACHER AUTHORIZATION MANAGEMENT
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Add Teacher Card
                                Surface(
                                    color = Color(0xFF131D33),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Yangi o'qituvchi qo'shish (Telegram ID orqali):",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextPrimary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = newTeacherIdText,
                                                onValueChange = { newTeacherIdText = it.filter { c -> c.isDigit() } },
                                                singleLine = true,
                                                placeholder = { Text("Telegram ID (masalan: 123456789)", fontSize = 12.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )

                                            OutlinedTextField(
                                                value = newTeacherNameText,
                                                onValueChange = { newTeacherNameText = it },
                                                singleLine = true,
                                                placeholder = { Text("Ism Familiya", fontSize = 12.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )

                                            Button(
                                                onClick = {
                                                    val id = newTeacherIdText.toLongOrNull()
                                                    if (id != null && id > 0) {
                                                        val name = newTeacherNameText.trim().ifBlank { "O'qituvchi ($id)" }
                                                        onAddTeacher(id, name)
                                                        newTeacherIdText = ""
                                                        newTeacherNameText = ""
                                                        teacherActionFeedback = "Muvaffaqiyatli qo'shildi: $name"
                                                    } else {
                                                        teacherActionFeedback = "To'g'ri Telegram ID raqamini kiriting!"
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("Qo'shish", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }

                                        if (teacherActionFeedback != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = teacherActionFeedback!!,
                                                fontSize = 11.sp,
                                                color = if (teacherActionFeedback!!.startsWith("Muvaffaqiyatli")) Color(0xFF10B981) else Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Registered Teachers List
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.People, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Ruxsat etilgan o'qituvchilar (${teachers.size}):",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (teachers.isEmpty()) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp)
                                    ) {
                                        Text(
                                            text = "Hozircha o'qituvchilar qo'shilmagan.\nYuqoridagi form orqali yoki Telegram botda /add_teacher buyrug'i bilan qo'shishingiz mumkin.",
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    teachers.forEach { teacher ->
                                        Surface(
                                            color = Color(0xFF0F172A),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightDark),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            ) {
                                                Column {
                                                    Text(
                                                        text = teacher.name,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = "Telegram ID: ${teacher.id}  •  Qo'shilgan: ${teacher.addedAt}",
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { onRemoveTeacher(teacher.id) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "O'chirish",
                                                        tint = Color(0xFFEF4444),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        3 -> {
                            // TAB 3: IT ADMINS MANAGEMENT
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Super Admin Card
                                Surface(
                                    color = Color(0xFF0D233A),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGold.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AdminPanelSettings,
                                            contentDescription = null,
                                            tint = NeonGold,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Asosiy Bosh Super Admin: ${AccessControlManager.SUPER_ADMIN_ID}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonGold
                                            )
                                            Text(
                                                text = "Barcha tizim huquqlariga ega (o'chirib bo'lmaydi). Yangi IT Adminlarni tayinlay oladi.",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Add IT Admin Card
                                Surface(
                                    color = Color(0xFF1E293B),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightDark),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "Yangi IT Admin tayinlash:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = NeonCyan
                                        )
                                        Text(
                                            text = "IT Adminlar o'qituvchilarni qo'shish/o'chirish va doska monitoring qilish huquqiga ega bo'ladi.",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = newItAdminIdText,
                                                onValueChange = { newItAdminIdText = it.filter { ch -> ch.isDigit() } },
                                                singleLine = true,
                                                placeholder = { Text("Telegram ID (masalan: 8223415949)", fontSize = 11.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1.3f).testTag("input_it_admin_id")
                                            )

                                            OutlinedTextField(
                                                value = newItAdminNameText,
                                                onValueChange = { newItAdminNameText = it },
                                                singleLine = true,
                                                placeholder = { Text("Ism (masalan: Husniddin)", fontSize = 11.sp, color = TextSecondary) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = NeonCyan,
                                                    unfocusedBorderColor = SurfaceLightDark,
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedContainerColor = DarkNavyBg,
                                                    unfocusedContainerColor = DarkNavyBg
                                                ),
                                                modifier = Modifier.weight(1f).testTag("input_it_admin_name")
                                            )

                                            Button(
                                                onClick = {
                                                    val id = newItAdminIdText.toLongOrNull()
                                                    if (id != null && id > 0) {
                                                        val name = newItAdminNameText.trim().ifBlank { "IT Admin ($id)" }
                                                        onAddItAdmin(id, name)
                                                        newItAdminIdText = ""
                                                        newItAdminNameText = ""
                                                        itAdminActionFeedback = "Muvaffaqiyatli tayinlandi: $name"
                                                    } else {
                                                        itAdminActionFeedback = "To'g'ri Telegram ID raqamini kiriting!"
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp).testTag("btn_add_it_admin")
                                            ) {
                                                Text("Tayinlash", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Quick Button for Husniddin
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Tezkor qo'shish:", fontSize = 11.sp, color = TextSecondary)
                                            Button(
                                                onClick = {
                                                    onAddItAdmin(8223415949L, "Husniddin")
                                                    itAdminActionFeedback = "Muvaffaqiyatli tayinlandi: Husniddin (8223415949)"
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2B48), contentColor = NeonGold),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text("+ Husniddin (8223415949)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        if (itAdminActionFeedback != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = itAdminActionFeedback!!,
                                                fontSize = 11.sp,
                                                color = if (itAdminActionFeedback!!.startsWith("Muvaffaqiyatli")) Color(0xFF10B981) else Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // IT Admins List
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.AdminPanelSettings, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Tayinlangan IT Adminlar (${itAdmins.size}):",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (itAdmins.isEmpty()) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp)
                                    ) {
                                        Text(
                                            text = "Hozircha qo'shimcha IT Adminlar tayinlanmagan.\nYuqoridagi forma orqali yoki Telegram botda /add_admin buyrug'i bilan tayinlang.",
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    itAdmins.forEach { admin ->
                                        Surface(
                                            color = Color(0xFF0F172A),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightDark),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            ) {
                                                Column {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = admin.name,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = TextPrimary
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFF064E3B), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text("IT Admin", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                                                        }
                                                    }
                                                    Text(
                                                        text = "Telegram ID: ${admin.id}  •  Tayinlangan: ${admin.addedAt}",
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { onRemoveItAdmin(admin.id) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "O'chirish",
                                                        tint = Color(0xFFEF4444),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        4 -> {
                            // TAB 4: ACCESS LOGS (Precise timestamp hh:mm:ss/dd/mm/yyyy)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(imageVector = Icons.Default.History, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Kirish jurnali (Access Logs):",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextPrimary
                                            )
                                        }
                                        Text(
                                            text = "Vaqt formati: hh:mm:ss/dd/mm/yyyy",
                                            fontSize = 10.sp,
                                            color = NeonCyan,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    if (accessLogs.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = onClearLogs,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Tozalash", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (accessLogs.isEmpty()) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp)
                                    ) {
                                        Text(
                                            text = "Hozircha kirish jurnali bo'sh.\nO'qituvchilar doskani ochganda, cho'zganda yoki qulflaganda bu yerda aniq vaqti (hh:mm:ss/dd/mm/yyyy) bilan qayd etiladi.",
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    accessLogs.forEachIndexed { index, log ->
                                        Surface(
                                            color = Color(0xFF0F172A),
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightDark),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                // Index badge
                                                Text(
                                                    text = "${index + 1}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextSecondary,
                                                    modifier = Modifier.width(26.dp)
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = log.userName,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 12.sp,
                                                            color = TextPrimary
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = "[${log.role}]",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (log.role.contains("Admin")) NeonGold else NeonCyan
                                                        )
                                                    }
                                                    Text(
                                                        text = "ID: ${log.userId}  •  Amal: ${log.action}",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFE2E8F0)
                                                    )
                                                }

                                                // Timestamp tag in required format: hh:mm:ss/dd/mm/yyyy
                                                Box(
                                                    modifier = Modifier
                                                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                                        .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = log.timestamp,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = NeonCyan
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Actions: Save / Close
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Yopish")
                    }

                    Button(
                        onClick = {
                            val updated = currentConfig.copy(
                                botToken = botToken.trim(),
                                botUsername = botUsername.trim().removePrefix("@"),
                                boardId = boardId.trim(),
                                roomName = roomName.trim(),
                                adminPin = adminPin.trim(),
                                wallpaperUrl = wallpaperUrl.trim(),
                                connectionMode = connectionMode,
                                serverUrl = serverUrl.trim()
                            )
                            onSaveConfig(updated)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Saqlash", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
