package dev.symbiosis.kenji;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Named presets live in kenji_presets. Applying a preset writes the official
 * Kenji QuickSettings keys (same default SharedPreferences their UI uses).
 */
public final class SettingsBank {
    private SettingsBank() {}

    private static SharedPreferences named(Context c) {
        return c.getSharedPreferences("kenji_presets", Context.MODE_PRIVATE);
    }

    private static SharedPreferences official(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    public static String listJson(Context c, String titleId) {
        try {
            JSONArray items = new JSONArray();
            addNamed(items, named(c).getString("global", "[]"));
            if (titleId != null && !titleId.isEmpty()) {
                addNamed(items, named(c).getString("game_" + titleId, "[]"));
            }
            return new JSONObject().put("items", items).put("titleId", titleId == null ? "" : titleId).toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    public static String save(Context c, String name, String titleId, boolean global) {
        try {
            if (name == null || name.trim().isEmpty()) name = "пресет";
            String key = global || titleId == null || titleId.isEmpty() ? "global" : "game_" + titleId;
            JSONArray arr = new JSONArray(named(c).getString(key, "[]"));
            JSONObject snap = snapshot(c);
            snap.put("name", name.trim());
            snap.put("scope", key.startsWith("game_") ? "game" : "all");
            snap.put("titleId", titleId == null ? "" : titleId);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && name.equals(o.optString("name"))) continue;
                if (o != null) next.put(o);
            }
            next.put(snap);
            named(c).edit().putString(key, next.toString()).commit();
            return new JSONObject().put("ok", true).put("message", "пресет «" + name + "» сохранён").toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    public static String apply(Context c, String name) {
        try {
            JSONObject found = find(c, name);
            if (found == null) {
                return new JSONObject().put("ok", false).put("message", "пресета нет").toString();
            }
            writeOfficial(c, found);
            return new JSONObject().put("ok", true).put("message", "включён пресет «" + name + "»").toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    public static String applyDefault(Context c) {
        try {
            ensureBuiltins(c);
            JSONObject d = maliDefault();
            writeOfficial(c, d);
            save(c, "по умолчанию", "", true);
            return new JSONObject()
                    .put("ok", true)
                    .put("message", "по умолчанию: NCE выкл, PPTC вкл, DRAM 4 ГиБ, Host Unchecked, 1×")
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** Built-in graphics presets — written once, then listed like user ones. */
    public static void ensureBuiltins(Context c) {
        SharedPreferences p = named(c);
        if (p.getBoolean("builtins_v3", false)) return;
        try {
            String[][] pack = new String[][]{
                    {"по умолчанию", "1.0", "0", "false"},
                    {"скорость 0.5×", "0.5", "0", "false"},
                    {"баланс 0.75×", "0.75", "0", "false"},
                    {"оригинал 1×", "1.0", "0", "false"},
                    {"чёткость 1.5×", "1.5", "0", "false"},
                    {"качество 2×", "2.0", "0", "false"},
                    {"максимум 3×", "3.0", "0", "false"},
                    {"Docked 1×", "1.0", "0", "true"},
                    {"Docked 1.5×", "1.5", "0", "true"},
                    {"Docked 2×", "2.0", "0", "true"},
                    {"экономия", "0.5", "0", "false"},
            };
            JSONArray arr = new JSONArray(p.getString("global", "[]"));
            for (String[] row : pack) {
                JSONObject o = maliDefault();
                o.put("name", row[0]);
                o.put("scope", "all");
                o.put("titleId", "");
                o.put("resScale", Double.parseDouble(row[1]));
                o.put("memoryConfiguration", Integer.parseInt(row[2]));
                o.put("enableDocked", Boolean.parseBoolean(row[3]));
                if ("экономия".equals(row[0])) {
                    o.put("enableLowPowerPptc", true);
                    o.put("enableShaderCache", true);
                }
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cur = arr.optJSONObject(i);
                    if (cur != null && row[0].equals(cur.optString("name"))) continue;
                    if (cur != null) next.put(cur);
                }
                next.put(o);
                arr = next;
            }
            p.edit().putString("global", arr.toString()).putBoolean("builtins_v3", true).commit();
        } catch (Exception ignored) {
        }
    }

    public static String applyScale(Context c, double scale, boolean docked) {
        official(c).edit()
                .putFloat("resScale", (float) scale)
                .putBoolean("enableDocked", docked)
                .commit();
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("message", (docked ? "Docked " : "Handheld ") + scale + "×")
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    public static String remove(Context c, String name) {
        try {
            SharedPreferences p = named(c);
            for (String key : p.getAll().keySet()) {
                JSONArray src = new JSONArray(p.getString(key, "[]"));
                JSONArray next = new JSONArray();
                for (int i = 0; i < src.length(); i++) {
                    JSONObject o = src.optJSONObject(i);
                    if (o != null && name.equals(o.optString("name"))) continue;
                    if (o != null) next.put(o);
                }
                p.edit().putString(key, next.toString()).commit();
            }
            return new JSONObject().put("ok", true).put("message", "пресет убран").toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    public static String settingsJson(Context c) {
        try {
            SharedPreferences p = official(c);
            JSONArray toggles = new JSONArray();
            toggles.put(tog("enablePptc", "PPTC", "кэш профилей", p.getBoolean("enablePptc", true)));
            toggles.put(tog("useNce", "NCE", "на этом Mali лучше выкл", p.getBoolean("useNce", false)));
            toggles.put(tog("enableDocked", "Docked", "телевизионный режим", p.getBoolean("enableDocked", false)));
            return new JSONObject()
                    .put("toggles", toggles)
                    .put("resolution", p.getFloat("resScale", 1f))
                    .put("cpuLabel", p.getBoolean("useNce", false) ? "NCE" : "JIT")
                    .toString();
        } catch (Exception e) {
            return "{\"toggles\":[]}";
        }
    }

    public static String setBool(Context c, String key, boolean on) {
        official(c).edit().putBoolean(key, on).commit();
        try {
            return new JSONObject().put("ok", true).put("message", key + (on ? " вкл" : " выкл")).toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    private static JSONObject maliDefault() throws Exception {
        return new JSONObject()
                .put("enablePptc", true)
                .put("useNce", false)
                .put("enableDocked", false)
                .put("enableLowPowerPptc", false)
                .put("enableJitCacheEviction", false)
                .put("enableFsIntegrityChecks", false)
                .put("ignoreMissingServices", false)
                .put("memoryConfiguration", 0) // 4GiB
                .put("memoryManagerMode", 2)   // HostMappedUnsafe
                .put("enableShaderCache", true)
                .put("resScale", 1.0);
    }

    private static JSONObject snapshot(Context c) throws Exception {
        SharedPreferences p = official(c);
        return new JSONObject()
                .put("enablePptc", p.getBoolean("enablePptc", true))
                .put("useNce", p.getBoolean("useNce", false))
                .put("enableDocked", p.getBoolean("enableDocked", false))
                .put("enableLowPowerPptc", p.getBoolean("enableLowPowerPptc", false))
                .put("enableJitCacheEviction", p.getBoolean("enableJitCacheEviction", false))
                .put("enableFsIntegrityChecks", p.getBoolean("enableFsIntegrityChecks", false))
                .put("ignoreMissingServices", p.getBoolean("ignoreMissingServices", false))
                .put("memoryConfiguration", p.getInt("memoryConfiguration", 0))
                .put("memoryManagerMode", p.getInt("memoryManagerMode", 2))
                .put("enableShaderCache", p.getBoolean("enableShaderCache", true))
                .put("resScale", (double) p.getFloat("resScale", 1f));
    }

    private static void writeOfficial(Context c, JSONObject s) {
        official(c).edit()
                .putBoolean("enablePptc", s.optBoolean("enablePptc", true))
                .putBoolean("useNce", s.optBoolean("useNce", false))
                .putBoolean("enableDocked", s.optBoolean("enableDocked", false))
                .putBoolean("enableLowPowerPptc", s.optBoolean("enableLowPowerPptc", false))
                .putBoolean("enableJitCacheEviction", s.optBoolean("enableJitCacheEviction", false))
                .putBoolean("enableFsIntegrityChecks", s.optBoolean("enableFsIntegrityChecks", false))
                .putBoolean("ignoreMissingServices", s.optBoolean("ignoreMissingServices", false))
                .putInt("memoryConfiguration", s.optInt("memoryConfiguration", 0))
                .putInt("memoryManagerMode", s.optInt("memoryManagerMode", 2))
                .putFloat("resScale", (float) s.optDouble("resScale", 1.0))
                .commit();
    }

    private static void addNamed(JSONArray items, String raw) {
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) items.put(o);
            }
        } catch (Exception ignored) {
        }
    }

    private static JSONObject find(Context c, String name) {
        try {
            JSONArray all = new JSONArray();
            addNamed(all, named(c).getString("global", "[]"));
            for (String key : named(c).getAll().keySet()) {
                if (key.startsWith("game_")) addNamed(all, named(c).getString(key, "[]"));
            }
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null && name.equals(o.optString("name"))) return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject tog(String key, String label, String hint, boolean on) throws Exception {
        return new JSONObject().put("key", key).put("label", label).put("hint", hint).put("on", on);
    }
}
