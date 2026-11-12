package com.arin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arin.llm.Provider

enum class ThemeMode(val label: String) { System("Match phone"), Light("Light"), Dark("Dark") }

/** App settings. API keys are encrypted with a key held in the Android Keystore and never leave the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val keys: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "api_keys",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore unavailable or its data damaged: start a fresh key store rather than crash.
        context.deleteSharedPreferences("api_keys")
        context.getSharedPreferences("api_keys_fallback", Context.MODE_PRIVATE)
    }

    var provider: Provider
        get() = prefs.getString("provider", null)?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: Provider.Free
        set(value) = prefs.edit().putString("provider", value.name).apply()

    fun model(provider: Provider): String? = prefs.getString("model_${provider.name}", null)

    fun setModel(provider: Provider, id: String) = prefs.edit().putString("model_${provider.name}", id).apply()

    var theme: ThemeMode
        get() = prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var showThinking: Boolean
        get() = prefs.getBoolean("show_thinking", true)
        set(value) = prefs.edit().putBoolean("show_thinking", value).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", true)
        set(value) = prefs.edit().putBoolean("web_search", value).apply()

    var memoryOn: Boolean
        get() = prefs.getBoolean("memory_on", true)
        set(value) = prefs.edit().putBoolean("memory_on", value).apply()

    var notifyOnReply: Boolean
        get() = prefs.getBoolean("notify_on_reply", true)
        set(value) = prefs.edit().putBoolean("notify_on_reply", value).apply()

    /** Whether Arin has already asked Android for permission to show notifications. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(value) = prefs.edit().putBoolean("asked_notifications", value).apply()

    var voiceRate: Float
        get() = prefs.getFloat("voice_rate", 1.0f)
        set(value) = prefs.edit().putFloat("voice_rate", value).apply()

    fun key(provider: Provider): String = keys.getString(provider.name, "").orEmpty()

    fun setKey(provider: Provider, value: String) = keys.edit().putString(provider.name, value.trim()).apply()
}
