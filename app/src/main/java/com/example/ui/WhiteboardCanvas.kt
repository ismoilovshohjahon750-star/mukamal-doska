package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGold
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

data class DrawnStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false
)

enum class BoardBackgroundType {
    SLATE_BLACKBOARD,
    CLEAN_WHITEBOARD,
    MATH_GRID,
    LINED_NOTEBOOK
}

@Composable
fun WhiteboardCanvas(
    onCloseWhiteboard: () -> Unit,
    onLockBoard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    var currentPoints = remember { mutableStateListOf<Offset>() }

    var selectedColor by remember { mutableStateOf(NeonCyan) }
    var selectedStrokeWidth by remember { mutableFloatStateOf(8f) }
    var isEraserMode by remember { mutableStateOf(false) }
    var backgroundType by remember { mutableStateOf(BoardBackgroundType.SLATE_BLACKBOARD) }

    // Floating toolbar position (can be dragged by teacher on 86" screen)
    var toolbarOffsetX by remember { mutableFloatStateOf(40f) }
    var toolbarOffsetY by remember { mutableFloatStateOf(40f) }

    val bgColor = when (backgroundType) {
        BoardBackgroundType.SLATE_BLACKBOARD -> Color(0xFF0F172A)
        BoardBackgroundType.CLEAN_WHITEBOARD -> Color(0xFFF8FAFC)
        BoardBackgroundType.MATH_GRID -> Color(0xFF0F172A)
        BoardBackgroundType.LINED_NOTEBOOK -> Color(0xFF1E293B)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Multi-touch Drawing Surface
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedColor, selectedStrokeWidth, isEraserMode) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes

                            // Track down/move/up for smooth writing
                            for (change in changes) {
                                if (change.pressed) {
                                    currentPoints.add(change.position)
                                    change.consume()
                                } else if (currentPoints.isNotEmpty()) {
                                    strokes.add(
                                        DrawnStroke(
                                            points = currentPoints.toList(),
                                            color = if (isEraserMode) bgColor else selectedColor,
                                            strokeWidth = if (isEraserMode) selectedStrokeWidth * 3 else selectedStrokeWidth,
                                            isEraser = isEraserMode
                                        )
                                    )
                                    currentPoints.clear()
                                    change.consume()
                                }
                            }
                        }
                    }
                }
        ) {
            // Draw grid if selected
            if (backgroundType == BoardBackgroundType.MATH_GRID) {
                val step = 48.dp.toPx()
                val gridColor = Color.White.copy(alpha = 0.08f)
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            } else if (backgroundType == BoardBackgroundType.LINED_NOTEBOOK) {
                val step = 64.dp.toPx()
                val lineCol = Color.White.copy(alpha = 0.12f)
                var y = step
                while (y < size.height) {
                    drawLine(lineCol, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                    y += step
                }
            }

            // Draw completed strokes
            for (stroke in strokes) {
                if (stroke.points.size > 1) {
                    val path = Path()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    drawPath(
                        path = path,
                        color = if (stroke.isEraser) bgColor else stroke.color,
                        style = Stroke(
                            width = stroke.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                } else if (stroke.points.isNotEmpty()) {
                    drawCircle(
                        color = if (stroke.isEraser) bgColor else stroke.color,
                        radius = stroke.strokeWidth / 2,
                        center = stroke.points[0]
                    )
                }
            }

            // Draw current active stroke in progress
            if (currentPoints.size > 1) {
                val activePath = Path()
                activePath.moveTo(currentPoints[0].x, currentPoints[0].y)
                for (i in 1 until currentPoints.size) {
                    activePath.lineTo(currentPoints[i].x, currentPoints[i].y)
                }
                drawPath(
                    path = activePath,
                    color = if (isEraserMode) bgColor else selectedColor,
                    style = Stroke(
                        width = if (isEraserMode) selectedStrokeWidth * 3 else selectedStrokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Draggable Smart Board Toolbar
        Surface(
            color = SurfaceDark.copy(alpha = 0.95f),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
            modifier = Modifier
                .offset { IntOffset(toolbarOffsetX.roundToInt(), toolbarOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarOffsetX = (toolbarOffsetX + dragAmount.x).coerceAtLeast(10f)
                        toolbarOffsetY = (toolbarOffsetY + dragAmount.y).coerceAtLeast(10f)
                    }
                }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle & title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.width(360.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "86\" Doska vositalari",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEF4444))
                                .clickable { onLockBoard() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Qulflash",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Qulflash",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onCloseWhiteboard,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Yopish",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Color palette row
                val palette = listOf(
                    NeonCyan,
                    Color.White,
                    NeonGold,
                    Color(0xFF10B981),
                    Color(0xFFF43F5E),
                    Color(0xFFA855F7)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    palette.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (!isEraserMode && selectedColor == color) 3.dp else 1.dp,
                                    color = if (!isEraserMode && selectedColor == color) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = color
                                    isEraserMode = false
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Pen width & tools
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thin Pen
                    ToolButton(
                        icon = Icons.Default.Edit,
                        isSelected = !isEraserMode && selectedStrokeWidth == 4f,
                        label = "4px",
                        onClick = {
                            selectedStrokeWidth = 4f
                            isEraserMode = false
                        }
                    )
                    // Medium Marker
                    ToolButton(
                        icon = Icons.Default.FormatPaint,
                        isSelected = !isEraserMode && selectedStrokeWidth == 10f,
                        label = "10px",
                        onClick = {
                            selectedStrokeWidth = 10f
                            isEraserMode = false
                        }
                    )
                    // Highlighter
                    ToolButton(
                        icon = Icons.Default.Highlight,
                        isSelected = !isEraserMode && selectedStrokeWidth == 22f,
                        label = "22px",
                        onClick = {
                            selectedStrokeWidth = 22f
                            isEraserMode = false
                        }
                    )
                    // Eraser
                    ToolButton(
                        icon = Icons.Default.AutoFixHigh,
                        isSelected = isEraserMode,
                        label = "O'chirgich",
                        onClick = {
                            isEraserMode = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action buttons: Undo, Clear, Grid
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Orqaga",
                            tint = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = { strokes.clear() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Tozalash",
                            tint = Color(0xFFF87171)
                        )
                    }

                    IconButton(
                        onClick = {
                            backgroundType = when (backgroundType) {
                                BoardBackgroundType.SLATE_BLACKBOARD -> BoardBackgroundType.MATH_GRID
                                BoardBackgroundType.MATH_GRID -> BoardBackgroundType.LINED_NOTEBOOK
                                BoardBackgroundType.LINED_NOTEBOOK -> BoardBackgroundType.CLEAN_WHITEBOARD
                                BoardBackgroundType.CLEAN_WHITEBOARD -> BoardBackgroundType.SLATE_BLACKBOARD
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BorderAll,
                            contentDescription = "To'r / Fon",
                            tint = NeonGold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) NeonCyan else Color(0xFF1E293B),
        contentColor = if (isSelected) Color(0xFF030712) else TextPrimary,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
