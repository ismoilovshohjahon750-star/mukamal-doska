package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.viewmodel.SmartBoardUiState

/**
 * Tanaffus / Standby rejimi:
 * 
 * 1. SAM CORP jonli foni, soat va fon sozlamalari to'liq ishlaydi.
 * 2. Foydalanuvchi soatni xohlagan joyiga surishi (drag) va o'ng yuqoridagi
 *    tishli g'ildirakcha orqali soat/fon sozlamalarini ocha oladi.
 * 3. Yangi o'rnatilgan doskada: Bot orqali yoki to'g'ridan-to'g'ri xona biriktirish paneli ko'rinadi.
 * 4. Doskadagi dars sessiyasi esa faqat Telegram bot orqali ochiladi.
 */
@Composable
fun StandbyLockScreen(
    state: SmartBoardUiState,
    onOpenAdminPin: () -> Unit = {},
    onRequestRegisterViaBot: () -> Unit = {},
    onRegisterRoomDirectly: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDirectSetupDialog by remember { mutableStateOf(false) }
    var roomNameInput by remember { mutableStateOf("") }
    var sentNotice by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("standby_clean_lock_surface")
    ) {
        // Yashirin Admin kirish (Faqat tizim sozlamalari uchun: chap yuqori burchakda 3 soniya bosib turish)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(70.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            onOpenAdminPin()
                        }
                    )
                }
                .testTag("secret_admin_hotspot_top_left")
        )

        // Yangi doska aniqlanganda va hali xona biriktirilmaganda: Tartibli axborot kartasi
        if (!state.isBoardRegistered) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xF00A1128)),
                    border = BorderStroke(1.dp, Color(0x5538BDF8)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_unregistered_board_setup")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Tizim ma'lumoti",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Yangi doska aniqlandi",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Apparat MAC: ${state.macAddress}",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        Text(
                            text = "Telegram bot orqali IT Admin yoki Super Adminga xona ma'lumotlarini kiritish so'rovi yuborildi. Telegram orqali xona nomini yuborishingiz bilan doska avtomatik tizimga ulanadi.",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 18.sp
                        )

                        if (sentNotice) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Yuborildi",
                                    tint = Color(0xFF4ADE80),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Telegram botga qayta so'rov yuborildi",
                                    fontSize = 12.sp,
                                    color = Color(0xFF4ADE80)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onRequestRegisterViaBot()
                                    sentNotice = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("btn_request_bot_setup")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Botga so'rov yuborish",
                                    fontSize = 13.sp,
                                    color = Color(0xFF38BDF8)
                                )
                            }

                            Button(
                                onClick = {
                                    roomNameInput = ""
                                    showDirectSetupDialog = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("btn_direct_setup_room")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Shu yerda kiritish",
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // To'g'ridan-to'g'ri ekrandan xona nomini kiritish muloqot oynasi
        if (showDirectSetupDialog) {
            Dialog(onDismissRequest = { showDirectSetupDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.widthIn(max = 440.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Doskani xonaga biriktirish",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = "Apparat MAC: ${state.macAddress}\nUshbu doska o'rnatilgan xona nomini kiriting:",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )

                        OutlinedTextField(
                            value = roomNameInput,
                            onValueChange = { roomNameInput = it },
                            placeholder = { Text("Masalan: 101-Fizika xonasi", color = Color(0xFF64748B)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF475569)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_direct_room_name")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { showDirectSetupDialog = false },
                                modifier = Modifier.height(44.dp)
                            ) {
                                Text("Bekor qilish", color = Color(0xFF94A3B8))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (roomNameInput.isNotBlank()) {
                                        onRegisterRoomDirectly(roomNameInput.trim())
                                        showDirectSetupDialog = false
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                modifier = Modifier
                                    .height(44.dp)
                                    .testTag("btn_save_direct_room")
                            ) {
                                Text("Saqlash va Ulanish", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
