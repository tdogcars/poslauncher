package com.blurredlimes.poslauncher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher_settings")

data class LauncherConfig(
    val posPackages: List<String>,
    val iconSizeDp: Int,
)

object LauncherPrefs {
    const val DEFAULT_ICON_SIZE_DP = 125
    val ICON_SIZE_RANGE = 96..320

    // Comma-joined to preserve the order apps were selected in (a string set
    // preference would lose it). Package names cannot contain commas.
    private val KEY_POS_PACKAGES = stringPreferencesKey("pos_packages")

    // Pre-multi-select installs stored a single package under this key.
    private val KEY_POS_PACKAGE_LEGACY = stringPreferencesKey("pos_package")

    private val KEY_ICON_SIZE_DP = intPreferencesKey("icon_size_dp")

    fun configFlow(context: Context): Flow<LauncherConfig> =
        context.dataStore.data.map { prefs ->
            // No pre-seeded default: a fresh install starts with nothing
            // selected and the technician picks apps from the settings list.
            val packages = prefs[KEY_POS_PACKAGES]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?: prefs[KEY_POS_PACKAGE_LEGACY]?.let { listOf(it) }
                ?: emptyList()
            LauncherConfig(
                posPackages = packages,
                iconSizeDp = (prefs[KEY_ICON_SIZE_DP] ?: DEFAULT_ICON_SIZE_DP)
                    .coerceIn(ICON_SIZE_RANGE),
            )
        }

    suspend fun setPosPackages(context: Context, packages: List<String>) {
        context.dataStore.edit { it[KEY_POS_PACKAGES] = packages.joinToString(",") }
    }

    suspend fun setIconSize(context: Context, sizeDp: Int) {
        context.dataStore.edit { it[KEY_ICON_SIZE_DP] = sizeDp.coerceIn(ICON_SIZE_RANGE) }
    }
}
