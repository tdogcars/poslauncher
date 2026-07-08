package com.blurredlimes.pivotlauncher

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** How long a finger must stay down on empty background to open configuration. */
private const val CONFIGURE_HOLD_MILLIS = 2000L

private sealed interface PosLookup {
    data object Loading : PosLookup
    data object Missing : PosLookup
    data class Found(val app: InstalledApp) : PosLookup
}

/**
 * Opens [onHold] after an uninterrupted [CONFIGURE_HOLD_MILLIS] press. A short
 * tap does nothing: releasing (or the gesture being consumed/cancelled) before
 * the timeout simply ends the gesture.
 */
private fun Modifier.holdToConfigure(onHold: () -> Unit): Modifier =
    pointerInput(onHold) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            try {
                withTimeout(CONFIGURE_HOLD_MILLIS) { waitForUpOrCancellation() }
            } catch (e: PointerEventTimeoutCancellationException) {
                onHold()
            }
        }
    }

@Composable
fun HomeScreen(config: LauncherConfig, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val iconSizePx = with(LocalDensity.current) { config.iconSizeDp.dp.roundToPx() }

    // Bumped on every resume so reinstalling the POS app recovers immediately,
    // and after a failed launch so the UI falls back to the diagnostic screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val lookup by produceState<PosLookup>(
        initialValue = PosLookup.Loading,
        config.posPackage, iconSizePx, refresh,
    ) {
        value = resolvePosApp(context, config.posPackage, iconSizePx)
            ?.let { PosLookup.Found(it) }
            ?: PosLookup.Missing
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background layer: any press on empty space lands here. Content is
        // stacked on top, so presses on the icon never reach this gesture.
        Box(
            modifier = Modifier
                .matchParentSize()
                .holdToConfigure {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSettings()
                }
        )

        when (val state = lookup) {
            PosLookup.Loading -> Unit // stays pure black while resolving

            is PosLookup.Found -> PosAppIcon(
                app = state.app,
                iconSizeDp = config.iconSizeDp,
                onLaunchFailed = { refresh++ },
                modifier = Modifier.align(Alignment.Center),
            )

            PosLookup.Missing -> MissingPosApp(
                packageName = config.posPackage,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PosAppIcon(
    app: InstalledApp,
    iconSizeDp: Int,
    onLaunchFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.launch_failed, app.packageName)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable {
                if (!launchApp(context, app.packageName)) {
                    Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
                    onLaunchFailed()
                }
            }
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(iconSizeDp.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MissingPosApp(
    packageName: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.missing_title),
            color = Color.White,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.missing_package, packageName),
            color = Color(0xFFBBBBBB),
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.missing_hint),
            color = Color(0xFF888888),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.missing_open_settings))
        }
    }
}
