package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WallpaperConfig
import com.example.ui.theme.DarkNavyBg
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGold
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceLightDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    currentConfig: WallpaperConfig,
    onConfigChanged: (WallpaperConfig) -> Unit,
    onSetLiveWallpaper: () -> Unit,
    onClearCacheAndReload: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var urlText by remember { mutableStateOf(currentConfig.url) }
    var keepScreenOn by remember { mutableStateOf(currentConfig.keepScreenOn) }
    var touchDragMode by remember { mutableStateOf(currentConfig.touchDragMode) }
    var isDesktopMode by remember { mutableStateOf(currentConfig.isDesktopMode) }
    var enableOverlayClock by remember { mutableStateOf(currentConfig.enableOverlayClock) }
    var zoomScale by remember { mutableFloatStateOf(currentConfig.zoomScale) }
    var brightness by remember { mutableFloatStateOf(currentConfig.brightness) }
    var screenOrientation by remember { mutableIntStateOf(currentConfig.screenOrientation) }

    fun updateAndNotify(block: (WallpaperConfig) -> WallpaperConfig) {
        val updated = block(
            currentConfig.copy(
                url = urlText,
                keepScreenOn = keepScreenOn,
                touchDragMode = touchDragMode,
                isDesktopMode = isDesktopMode,
                enableOverlayClock = enableOverlayClock,
                zoomScale = zoomScale,
                brightness = brightness,
                screenOrientation = screenOrientation
            )
        )
        onConfigChanged(updated)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DarkNavyBg,
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Fon va Vidjet Sozlamalari",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Sam Corp Live Wallpaper parametrlari",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Button: Set as Live Wallpaper
            Button(
                onClick = {
                    onSetLiveWallpaper()
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkNavyBg
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_set_live_wallpaper")
            ) {
                Icon(
                    imageVector = Icons.Default.Wallpaper,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Jonli Fon (Live Wallpaper) oʻrnatish",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Keep Screen Awake Toggle
            SettingsToggleCard(
                title = "Ekran oʻchib qolmasligi",
                description = "Ilova ochiq turganda telefon ekrani aslo oʻchmaydi va uxlamaydi (stol soati sifatida juda qulay)",
                icon = Icons.Default.WbSunny,
                checked = keepScreenOn,
                onCheckedChange = { checked ->
                    keepScreenOn = checked
                    updateAndNotify { it.copy(keepScreenOn = checked) }
                },
                accentColor = NeonGold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Drag Clock / Touch polyfill mode
            SettingsToggleCard(
                title = "Soatni surish rejimi (Drag Mode)",
                description = "Saytdagi soat va vidjetlarni xuddi kompyuterdagidek barmogʻingiz bilan istalgan joyga surish imkoniyati",
                icon = Icons.Default.OpenWith,
                checked = touchDragMode,
                onCheckedChange = { checked ->
                    touchDragMode = checked
                    updateAndNotify { it.copy(touchDragMode = checked) }
                },
                accentColor = NeonCyan
            )

            Spacer(modifier = Modifier.height(12.dp))

            // PC / Desktop View Toggle
            SettingsToggleCard(
                title = "Kompyuter (PC / Desktop) rejimi",
                description = "Saytni kompyuter brauzeri sifatida ochish (agar soat dastlab PC uchun moʻljallangan boʻlsa)",
                icon = Icons.Default.Computer,
                checked = isDesktopMode,
                onCheckedChange = { checked ->
                    isDesktopMode = checked
                    updateAndNotify { it.copy(isDesktopMode = checked) }
                },
                accentColor = Color(0xFF38BDF8)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Native Draggable Clock Overlay Toggle
            SettingsToggleCard(
                title = "Mustaqil suzuvchi soat vidjeti",
                description = "Ekran ustida erkin suriladigan, neon uslubidagi jonli raqamli soat va sana vidjetini koʻrsatish",
                icon = Icons.Default.DragHandle,
                checked = enableOverlayClock,
                onCheckedChange = { checked ->
                    enableOverlayClock = checked
                    updateAndNotify { it.copy(enableOverlayClock = checked) }
                },
                accentColor = NeonGold
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Scale / Zoom Slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Masshtab (Zoom / Scale)",
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        text = "${(zoomScale * 100).roundToInt()}%",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "Fon va soat oʻlchamini telefon ekraniga moslashtiring",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                Slider(
                    value = zoomScale,
                    onValueChange = { scale ->
                        zoomScale = scale
                        updateAndNotify { it.copy(zoomScale = scale) }
                    },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyan,
                        activeTrackColor = NeonCyan,
                        inactiveTrackColor = SurfaceLightDark
                    ),
                    modifier = Modifier.testTag("slider_zoom_scale")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Brightness Dimmer Slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BrightnessMedium,
                            contentDescription = null,
                            tint = NeonGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tungi yorqinlik (Dimmer)",
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        text = "${(brightness * 100).roundToInt()}%",
                        color = NeonGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "Stol soati sifatida turganda batareyani tejash va koʻzni asrash",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                Slider(
                    value = brightness,
                    onValueChange = { b ->
                        brightness = b
                        updateAndNotify { it.copy(brightness = b) }
                    },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonGold,
                        activeTrackColor = NeonGold,
                        inactiveTrackColor = SurfaceLightDark
                    ),
                    modifier = Modifier.testTag("slider_brightness")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Orientation selector
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ekran yoʻnalishi",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 15.sp
                    )
                }

                Text(
                    text = "Telefon stendida yotiq (albom) yoki tik holatda ishlatish",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val orientations = listOf(
                        0 to "Avto",
                        1 to "Tik (Portret)",
                        2 to "Yotiq (Albom)"
                    )
                    orientations.forEach { (mode, label) ->
                        FilterChip(
                            selected = screenOrientation == mode,
                            onClick = {
                                screenOrientation = mode
                                updateAndNotify { it.copy(screenOrientation = mode) }
                            },
                            label = { Text(label, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = DarkNavyBg,
                                containerColor = SurfaceLightDark,
                                labelColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // URL editor
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Veb-manzil (URL)",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = SurfaceLightDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkNavyBg,
                        unfocusedContainerColor = DarkNavyBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_wallpaper_url")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            urlText = "https://sam-corp-live-wallpaper.vercel.app/"
                            updateAndNotify { it.copy(url = urlText) }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Asliga qaytarish", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            updateAndNotify { it.copy(url = urlText) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkNavyBg),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Saqlash", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Clear cache & Reload action
            OutlinedButton(
                onClick = {
                    onClearCacheAndReload()
                    onDismissRequest()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Keshni tozalash va qayta yuklash", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun SettingsToggleCard(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkNavyBg,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceLightDark
            )
        )
    }
}
