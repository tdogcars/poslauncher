package com.blurredlimes.pivotlauncher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher_settings")

data class LauncherConfig(
    val posPackage: String,
    val iconSizeDp: Int,
)

object LauncherPrefs {
    // Pre-seeded with the Pivot POS package so a standard install needs no typing;
    // fully changeable from the settings screen.
    const val DEFAULT_POS_PACKAGE = "com.blurredlimes.pivotpos"
    const val DEFAULT_ICON_SIZE_DP = 192
    val ICON_SIZE_RANGE = 96..320

    private val KEY_POS_PACKAGE = stringPreferencesKey("pos_package")
    private val KEY_ICON_SIZE_DP = intPreferencesKey("icon_size_dp")

    fun configFlow(context: Context): Flow<LauncherConfig> =
        context.dataStore.data.map { prefs ->
            LauncherConfig(
                posPackage = prefs[KEY_POS_PACKAGE] ?: DEFAULT_POS_PACKAGE,
                iconSizeDp = (prefs[KEY_ICON_SIZE_DP] ?: DEFAULT_ICON_SIZE_DP)
                    .coerceIn(ICON_SIZE_RANGE),
            )
        }

    suspend fun setPosPackage(context: Context, packageName: String) {
        context.dataStore.edit { it[KEY_POS_PACKAGE] = packageName }
    }

    suspend fun setIconSize(context: Context, sizeDp: Int) {
        context.dataStore.edit { it[KEY_ICON_SIZE_DP] = sizeDp.coerceIn(ICON_SIZE_RANGE) }
    }
}
