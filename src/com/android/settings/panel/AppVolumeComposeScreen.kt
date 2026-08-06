/*
 * SPDX-FileCopyrightText: 2026 ASCP OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.panel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AppVolume
import android.media.AudioManager
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.settings.R
import kotlinx.coroutines.delay

data class AppVolumeItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val volume: Float,
    val isMuted: Boolean
)

@Composable
fun AppVolumeComposeContent(
    onClose: () -> Unit,
    onSeeMore: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    var appVolumes by remember { mutableStateOf<List<AppVolumeItem>>(emptyList()) }

    fun refreshAppVolumes() {
        if (audioManager == null) return
        val rawVolumes = audioManager.listAppVolumes() ?: emptyList()
        val pm = context.packageManager
        val list = mutableListOf<AppVolumeItem>()

        for (vol in rawVolumes) {
            if (vol.isActive) {
                val pkg = vol.packageName
                val appName = try {
                    val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_ANY_USER)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }
                list.add(
                    AppVolumeItem(
                        packageName = pkg,
                        appName = appName,
                        icon = icon,
                        volume = vol.volume,
                        isMuted = vol.isMuted
                    )
                )
            }
        }
        appVolumes = list
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshAppVolumes()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
            .padding(vertical = 20.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = stringResource(R.string.app_volume),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (appVolumes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No apps currently playing audio",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                appVolumes.forEach { item ->
                    AppVolumeRow(
                        item = item,
                        onVolumeChange = { newVol ->
                            audioManager?.setAppVolume(item.packageName, newVol)
                            refreshAppVolumes()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Footer buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSeeMore,
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.settings_button))
            }

            Button(
                onClick = onClose,
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun AppVolumeRow(
    item: AppVolumeItem,
    onVolumeChange: (Float) -> Unit
) {
    var currentVolume by remember(item.volume) { mutableFloatStateOf(item.volume) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        ) {
            if (item.icon != null) {
                val bitmap = remember(item.icon) { item.icon.toBitmap(width = 24, height = 24).asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = item.appName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Material 3 Expressive Thick Pill Slider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .onSizeChanged { componentSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (componentSize.width > 0) {
                            val newVol = (offset.x / componentSize.width.toFloat()).coerceIn(0f, 1f)
                            currentVolume = newVol
                            onVolumeChange(newVol)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (componentSize.width > 0) {
                            val newVol = (change.position.x / componentSize.width.toFloat()).coerceIn(0f, 1f)
                            currentVolume = newVol
                            onVolumeChange(newVol)
                        }
                    }
                }
        ) {
            // Active filled track
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = currentVolume.coerceIn(0.01f, 1f))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )

            // Right thumb divider indicator inside thick capsule
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = currentVolume.coerceIn(0.01f, 1f)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .width(4.dp)
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                )
            }
        }
    }
}

object AppVolumeComposeHelper {
    @JvmStatic
    fun createView(context: Context, onSeeMore: Runnable, onClose: Runnable): android.view.View {
        return androidx.compose.ui.platform.ComposeView(context).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                com.android.settingslib.spa.framework.theme.SettingsTheme {
                    AppVolumeComposeContent(
                        onClose = { onClose.run() },
                        onSeeMore = { onSeeMore.run() }
                    )
                }
            }
        }
    }
}

