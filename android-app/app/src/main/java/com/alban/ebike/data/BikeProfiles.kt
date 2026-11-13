package com.alban.ebike.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local profiles keyed by stable ESP32 BLE address, never by the shared advertising name. */
object BikeProfiles {
    const val OFFLINE = "offline"
    private var storage: ((String) -> SharedPreferences)? = null
    private val _activeId = MutableStateFlow(OFFLINE)
    val activeId = _activeId.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val app = context.applicationContext
        initialize { name -> app.getSharedPreferences(name, Context.MODE_PRIVATE) }
    }
    @Synchronized internal fun initialize(storage: (String) -> SharedPreferences) {
        this.storage = storage
        _activeId.value = index().getString("active", OFFLINE) ?: OFFLINE
        changed()
    }
    private fun index() = storage!!.invoke("bike-profiles")
    fun preferences(section: String, id: String = activeId.value): SharedPreferences =
        storage!!.invoke(if (id == OFFLINE) section else "bike-${id.replace(":", "")}-${section}")
    @Synchronized fun activate(address: String) {
        val id = address.uppercase(java.util.Locale.ROOT)
        require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(id))
        val known = index().getStringSet("known", emptySet())!!.toMutableSet()
        if (known.add(id)) {
            // Migrate former global UI preferences to the first bike only. Never migrate a wheel size to an unknown ESP.
            if (known.size == 1) for (section in listOf("ride-options", "scene-layers", "display")) {
                val edit = preferences(section, id).edit()
                preferences(section, OFFLINE).all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                        is String -> edit.putString(key, value)
                    }
                }
                edit.apply()
            }
            index().edit().putStringSet("known", known).apply()
        }
        index().edit().putString("active", id).apply()
        _activeId.value = id
        changed()
    }
    fun name(id: String = activeId.value): String = if (id == OFFLINE) "Sans VAE" else
        preferences("settings", id).getString("name", "VAE · ${id.takeLast(5)}")!!
    fun rename(name: String, id: String = activeId.value) {
        if (id == OFFLINE) return
        preferences("settings", id).edit().putString("name", name.trim().take(40).ifEmpty { "VAE · ${id.takeLast(5)}" }).apply(); changed()
    }
    fun knownNames(): List<String> = index().getStringSet("known", emptySet())!!.sorted().map { name(it) }
    fun changed() { synchronized(this) { _revision.value++ } }
}
