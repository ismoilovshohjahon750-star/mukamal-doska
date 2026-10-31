package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGold
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.SmartBoardUiState
import kotlin.math.roundToInt

@Composable
fun ActiveLessonOverlay(
    state: SmartBoardUiState,
    onOpenWhiteboard: () -> Unit = {},
    onExtendMinutes: (Int) -> Unit = {},
    onTogglePause: () -> Unit = {},
    onFinishLesson: () -> Unit = {},
    onMinimizeToDesktop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(30f) }
    var offsetY by remember { mutableFloatStateOf(20f) }

    val remainingSeconds = state.activeSecondsRemaining
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format("%02d:%02d", minutes, seconds)

    val timerColor = when {
        remainingSeconds <= 180 -> Color(0xFFEF4444)
        remainingSeconds <= 600 -> NeonGold
        else -> NeonCyan
    }

    Box(modifier = modifier) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.94f),
            shape = RoundedCornerShape(30.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, timerColor.copy(alpha = 0.7f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceAtLeast(10f)
                        offsetY = (offsetY + dragAmount.y).coerceAtLeast(10f)
                    }
                }
                .testTag("compact_active_lesson_pill")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Kichik yashil/qizil indikator nuqta (pauza tugmasi)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.isTimerPaused) NeonGold else Color(0xFF10B981))
                        .clickable { onTogglePause() }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Ixcham Taymer
                Text(
                    text = formattedTime,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = timerColor,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onTogglePause() }
                )

                if (state.teacherName.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.teacherName.take(14),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }

                if (state.isTimerPaused) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(Pauza)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonGold
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // +10 min tugmasi
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { onExtendMinutes(10) }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("btn_extend_10m")
                ) {
                    Text(
                        text = "+10m",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Doska tugmasi (SVG / Material Icon)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonCyan.copy(alpha = 0.20f))
                        .clickable { onOpenWhiteboard() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("btn_open_whiteboard")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Doska",
                        tint = NeonCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Doska",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // QULFLASH TUGMASI (Ekranning o'zidan darsni darhol yakunlab qulflash)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFEF4444))
                        .clickable { onFinishLesson() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("btn_lock_board_instant")
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Qulflash",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Qulflash",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
