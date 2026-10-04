package com.alban.ebike.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BikeProfilesTest {
    private val files = mutableMapOf<String, SharedPreferences>()
    private val storage: (String) -> SharedPreferences = { files.getOrPut(it) { MemoryPreferences() } }
    private val a = "AA:00:00:00:00:01"
    private val b = "AA:00:00:00:00:02"
    @Before fun setup() { files.clear(); BikeProfiles.initialize(storage) }

    @Test fun restoresEachBikeAndActiveProfileAfterRestart() {
        val settings = BikeSettingsStore()
        BikeProfiles.activate(a)
        settings.initializeWheel(2100, a)
        BikeProfiles.rename("Ville")
        BikeProfiles.preferences("ride-options").edit().putFloat("moving-threshold", 6f).apply()
        BikeProfiles.preferences("scene-layers").edit().putBoolean("roads", false).apply()
        BikeProfiles.activate(b)
        assertEquals(2400, settings.initializeWheel(2400, b))
        assertEquals(4f, BikeProfiles.preferences("ride-options").getFloat("moving-threshold", 4f), 0f)
        assertTrue(BikeProfiles.preferences("scene-layers").getBoolean("roads", true))
        settings.setCircumferenceMm(2350)
        BikeProfiles.initialize(storage)
        assertEquals(b, BikeProfiles.activeId.value)
        assertEquals(2350, settings.currentCircumference())
        BikeProfiles.activate(a)
        assertEquals("Ville", BikeProfiles.name())
        assertEquals(2100, settings.initializeWheel(2200, a))
        assertEquals(6f, BikeProfiles.preferences("ride-options").getFloat("moving-threshold", 4f), 0f)
        assertFalse(BikeProfiles.preferences("scene-layers").getBoolean("roads", true))
    }
    @Test fun migratesLegacyUiPreferencesOnlyToFirstBikeAndAdoptsUnknownWheel() {
        BikeProfiles.preferences("ride-options").edit().putInt("grade-points", 25).apply()
        val settings = BikeSettingsStore()
        settings.setCircumferenceMm(3999)
        BikeProfiles.activate(a)
        assertEquals(25, BikeProfiles.preferences("ride-options").getInt("grade-points", 10))
        assertEquals(2050, settings.initializeWheel(2050, a))
        BikeProfiles.activate(b)
        assertEquals(10, BikeProfiles.preferences("ride-options").getInt("grade-points", 10))
        assertEquals(2300, settings.initializeWheel(2300, b))
    }
    @Test fun editCapturedBeforeBikeChangeDoesNotModifyNextBike() {
        val settings = BikeSettingsStore()
        BikeProfiles.activate(a); settings.initializeWheel(2100, a)
        val captured = BikeProfiles.activeId.value
        BikeProfiles.activate(b); settings.initializeWheel(2400, b)
        settings.setCircumferenceMm(2110, captured)
        assertEquals(2400, settings.currentCircumference())
        assertEquals(2110, settings.currentCircumference(a))
    }
    @Test fun normalisesBluetoothIdentityAndRejectsInvalidProfile() {
        BikeProfiles.activate(a.lowercase())
        assertEquals(a, BikeProfiles.activeId.value)
        assertThrows(IllegalArgumentException::class.java) { BikeProfiles.activate("E-Bike RT") }
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
