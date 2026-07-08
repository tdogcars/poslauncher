package com.blurredlimes.pivotlauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    fun savePackages(packages: List<String>) {
        scope.launch { LauncherPrefs.setPosPackages(context, packages) }
    }

    fun move(packageName: String, delta: Int) {
        val list = config.posPackages.toMutableList()
        val from = list.indexOf(packageName)
        val to = from + delta
        if (from == -1 || to !in list.indices) return
        list[from] = list[to].also { list[to] = list[from] }
        savePackages(list)
    }

    // Selected apps in their home-screen order; unselected alphabetical below.
    val installedByPackage = apps.orEmpty().associateBy { it.packageName }
    val unselected = apps.orEmpty().filter { it.packageName !in config.posPackages }

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
                        R.string.settings_selected_count,
                        config.posPackages.size,
                    ),
                    color = Color(0xFF999999),
                    fontSize = 13.sp,
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
                if (config.posPackages.isNotEmpty()) {
                    item(key = "header_selected") {
                        SectionHeader(stringResource(R.string.settings_section_selected))
                    }
                    itemsIndexed(
                        config.posPackages,
                        key = { _, pkg -> pkg },
                    ) { index, pkg ->
                        SelectedAppRow(
                            app = installedByPackage[pkg],
                            packageName = pkg,
                            canMoveUp = index > 0,
                            canMoveDown = index < config.posPackages.lastIndex,
                            onMoveUp = { move(pkg, -1) },
                            onMoveDown = { move(pkg, +1) },
                            onRemove = { savePackages(config.posPackages - pkg) },
                        )
                    }
                    item(key = "header_available") {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader(stringResource(R.string.settings_section_available))
                    }
                }
                items(unselected, key = { it.packageName }) { app ->
                    AvailableAppRow(
                        app = app,
                        onAdd = { savePackages(config.posPackages + app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SelectedAppRow(
    app: InstalledApp?,
    packageName: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (app != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A)),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app?.label ?: stringResource(R.string.settings_not_installed),
                color = if (app != null) Color.White else Color(0xFF777777),
                fontSize = 16.sp,
            )
            Text(
                text = packageName,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.settings_move_up),
                tint = if (canMoveUp) Color.White else Color(0xFF444444),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.settings_move_down),
                tint = if (canMoveDown) Color.White else Color(0xFF444444),
            )
        }
        Checkbox(checked = true, onCheckedChange = { onRemove() })
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun AvailableAppRow(app: InstalledApp, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onAdd)
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
        Checkbox(checked = false, onCheckedChange = { onAdd() })
    }
}
