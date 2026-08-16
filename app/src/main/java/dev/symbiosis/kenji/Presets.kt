package dev.symbiosis.kenji

import org.json.JSONArray
import org.json.JSONObject

/** Named snapshots of Kenji SettingsStore — same idea as Symbiosis presets. */
object Presets {

    private const val KEY = "kenji_presets"

    private fun prefs() = KenjiApp.instance.getSharedPreferences("kenji_space", 0)

    fun listJson(): String {
        val arr = JSONArray()
        load().forEach { p ->
            arr.put(JSONObject().put("name", p.optString("name")).put("keys", p.optJSONObject("values")?.length() ?: 0))
        }
        return JSONObject().put("items", arr).toString()
    }

    fun snapshot(name: String, store: SettingsStore): String {
        val n = name.trim().ifBlank { return fail("пустое имя") }
        val values = JSONObject()
        store.toggles.forEach { t -> values.put(t.key, store.bool(t.key, t.default)) }
        values.put("memoryConfiguration", store.int("memoryConfiguration", 0))
        values.put("memoryManagerMode", store.int("memoryManagerMode", 2))
        values.put("backendThreading", store.int("backendThreading", 1))
        val all = load().filter { it.optString("name") != n }.toMutableList()
        all.add(0, JSONObject().put("name", n).put("values", values).put("when", System.currentTimeMillis()))
        save(all)
        return JSONObject().put("ok", true).put("name", n).put("keys", values.length())
            .put("message", "Поздравляю: пресет «$n» · ${values.length()} ключей Kenji").toString()
    }

    fun apply(name: String, store: SettingsStore): String {
        val p = load().firstOrNull { it.optString("name") == name } ?: return fail("нет пресета «$name»")
        val values = p.optJSONObject("values") ?: return fail("пресет пустой")
        var n = 0
        store.toggles.forEach { t ->
            if (values.has(t.key)) {
                store.setBool(t.key, values.optBoolean(t.key, t.default))
                n++
            }
        }
        return JSONObject().put("ok", n > 0).put("applied", n)
            .put("message", "Поставлен «$name» · $n настроек Kenji").toString()
    }

    fun remove(name: String): String {
        save(load().filter { it.optString("name") != name })
        return JSONObject().put("ok", true).put("message", "пресет «$name» снесён").toString()
    }

    private fun load(): List<JSONObject> {
        val raw = prefs().getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
    }

    private fun save(list: List<JSONObject>) {
        val arr = JSONArray()
        list.take(20).forEach { arr.put(it) }
        prefs().edit().putString(KEY, arr.toString()).commit()
    }

    private fun fail(m: String) = JSONObject().put("ok", false).put("message", m).toString()
}
