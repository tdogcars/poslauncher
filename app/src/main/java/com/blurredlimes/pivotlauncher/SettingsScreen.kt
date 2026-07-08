package com.blurredlimes.pivotlauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(config: LauncherConfig, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listIconPx = with(LocalDensity.current) { 44.dp.roundToPx() }

    BackHandler(onBack = onDone)

    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = queryLaunchableApps(context, listIconPx)
    }

    var sliderValue by remember(config.iconSizeDp) {
        mutableFloatStateOf(config.iconSizeDp.toFloat())
    }

    val currentInstalled = apps?.any { it.packageName == config.posPackage }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = Color.White,
                    fontSize = 22.sp,
                )
                Text(
                    text = stringResource(
                        if (currentInstalled == false) R.string.settings_current_not_installed
                        else R.string.settings_current_package,
                        config.posPackage,
                    ),
                    color = Color(0xFF999999),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(onClick = onDone) {
                Text(text = stringResource(R.string.settings_done), fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.settings_icon_size),
            color = Color.White,
            fontSize = 16.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    scope.launch {
                        LauncherPrefs.setIconSize(context, sliderValue.roundToInt())
                    }
                },
                valueRange = LauncherPrefs.ICON_SIZE_RANGE.first.toFloat()..
                    LauncherPrefs.ICON_SIZE_RANGE.last.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(
                    R.string.settings_icon_size_value,
                    sliderValue.roundToInt(),
                ),
                color = Color(0xFF999999),
                fontSize = 14.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF222222))
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.settings_choose_app),
            color = Color.White,
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            apps == null -> Text(
                text = stringResource(R.string.settings_loading_apps),
                color = Color(0xFF999999),
                fontSize = 14.sp,
            )

            apps.orEmpty().isEmpty() -> Text(
                text = stringResource(R.string.settings_no_apps),
                color = Color(0xFF999999),
                fontSize = 14.sp,
            )

            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(apps.orEmpty(), key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName == config.posPackage,
                        onSelect = {
                            scope.launch {
                                LauncherPrefs.setPosPackage(context, app.packageName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .background(if (selected) Color(0x14FFFFFF) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, color = Color.White, fontSize = 16.sp)
            Text(
                text = app.packageName,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}
