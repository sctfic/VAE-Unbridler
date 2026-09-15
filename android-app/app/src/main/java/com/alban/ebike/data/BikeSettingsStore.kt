package com.alban.ebike.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alban.ebike.model.BleProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ebike_settings")

class BikeSettingsStore(private val context: Context) {
    private val circumferenceKey = intPreferencesKey("circumference_mm")

    val circumferenceMm: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[circumferenceKey] ?: BleProtocol.defaultCircumferenceMm
    }

    suspend fun setCircumferenceMm(value: Int) {
        require(value in 1000..4000) { "Wheel circumference must be between 1000 and 4000 mm" }
        context.dataStore.edit { it[circumferenceKey] = value }
    }
}
