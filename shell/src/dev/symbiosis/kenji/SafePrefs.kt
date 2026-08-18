package dev.symbiosis.kenji

import android.content.SharedPreferences

/** Official Kenji stores mixed types. Wrong getFloat/getBoolean kills the process. */
object SafePrefs {
    fun bool(p: SharedPreferences, key: String, def: Boolean): Boolean = try {
        when (val v = p.all[key]) {
            null -> def
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase()) {
                "1", "true", "on" -> true
                "0", "false", "off" -> false
                else -> def
            }
            else -> def
        }
    } catch (_: Throwable) {
        def
    }

    fun integer(p: SharedPreferences, key: String, def: Int): Int = try {
        when (val v = p.all[key]) {
            null -> def
            is Number -> v.toInt()
            is Boolean -> if (v) 1 else 0
            is String -> v.trim().toIntOrNull() ?: def
            else -> def
        }
    } catch (_: Throwable) {
        def
    }

    fun dec(p: SharedPreferences, key: String, def: Float): Float = try {
        when (val v = p.all[key]) {
            null -> def
            is Number -> v.toFloat()
            is String -> v.trim().replace(',', '.').toFloatOrNull() ?: def
            else -> def
        }
    } catch (_: Throwable) {
        def
    }

    fun putBool(p: SharedPreferences, key: String, on: Boolean) {
        try {
            p.edit().remove(key).putBoolean(key, on).commit()
        } catch (_: Throwable) {
            runCatching { p.edit().putBoolean(key, on).commit() }
        }
    }

    fun putInt(p: SharedPreferences, key: String, value: Int) {
        try {
            p.edit().remove(key).putInt(key, value).commit()
        } catch (_: Throwable) {
            runCatching { p.edit().putInt(key, value).commit() }
        }
    }

    fun putFloat(p: SharedPreferences, key: String, value: Float) {
        try {
            p.edit().remove(key).putFloat(key, value).commit()
        } catch (_: Throwable) {
            runCatching { p.edit().putFloat(key, value).commit() }
        }
    }
}
