package dev.symbiosis.kenji;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Global and per-game presets. Stored here; launch still uses official Kenji. */
public final class SettingsBank {
    private SettingsBank() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("kenji_presets", Context.MODE_PRIVATE);
    }

    public static String listJson(Context c, String titleId) {
        try {
            JSONArray items = new JSONArray();
            addNamed(items, prefs(c).getString("global", "[]"));
            if (titleId != null && !titleId.isEmpty()) {
                addNamed(items, prefs(c).getString("game_" + titleId, "[]"));
            }
            return new JSONObject().put("items", items).put("titleId", titleId == null ? "" : titleId).toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    public static String save(Context c, String name, String titleId, boolean global) {
        try {
            String key = global || titleId == null || titleId.isEmpty() ? "global" : "game_" + titleId;
            JSONArray arr = new JSONArray(prefs(c).getString(key, "[]"));
            JSONObject snap = current(c);
            snap.put("name", name);
            snap.put("scope", key.startsWith("game_") ? "game" : "all");
            snap.put("titleId", titleId == null ? "" : titleId);
            arr.put(snap);
            prefs(c).edit().putString(key, arr.toString()).commit();
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
            SharedPreferences.Editor e = c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).edit();
            e.putBoolean("enablePptc", found.optBoolean("enablePptc", true));
            e.putBoolean("useNce", found.optBoolean("useNce", false));
            e.putBoolean("enableDocked", found.optBoolean("enableDocked", false));
            e.putInt("resolution", found.optInt("resolution", 2));
            e.putInt("memoryConfiguration", found.optInt("memoryConfiguration", 0));
            e.commit();
            return new JSONObject().put("ok", true).put("message", "включён пресет «" + name + "»").toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    public static String applyDefault(Context c) {
        return apply(c, saveDefault(c));
    }

    public static String remove(Context c, String name) {
        try {
            for (String key : new String[]{"global"}) {
                JSONArray src = new JSONArray(prefs(c).getString(key, "[]"));
                JSONArray next = new JSONArray();
                for (int i = 0; i < src.length(); i++) {
                    JSONObject o = src.optJSONObject(i);
                    if (o != null && name.equals(o.optString("name"))) continue;
                    if (o != null) next.put(o);
                }
                prefs(c).edit().putString(key, next.toString()).commit();
            }
            return new JSONObject().put("ok", true).put("message", "пресет убран").toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    public static String settingsJson(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE);
            JSONArray toggles = new JSONArray();
            toggles.put(tog("enablePptc", "PPTC", "кэш профилей", p.getBoolean("enablePptc", true)));
            toggles.put(tog("useNce", "NCE", "на этом Mali лучше выкл", p.getBoolean("useNce", false)));
            toggles.put(tog("enableDocked", "Docked", "телевизионный режим", p.getBoolean("enableDocked", false)));
            return new JSONObject()
                    .put("toggles", toggles)
                    .put("resolution", p.getInt("resolution", 2))
                    .put("resolutionLabel", "1x")
                    .put("cpuLabel", p.getBoolean("useNce", false) ? "NCE" : "JIT")
                    .toString();
        } catch (Exception e) {
            return "{\"toggles\":[]}";
        }
    }

    public static String setBool(Context c, String key, boolean on) {
        c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).edit().putBoolean(key, on).commit();
        try {
            return new JSONObject().put("ok", true).put("message", key + (on ? " вкл" : " выкл")).toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    private static String saveDefault(Context c) {
        String name = "по умолчанию";
        save(c, name, "", true);
        return name;
    }

    private static JSONObject current(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE);
        return new JSONObject()
                .put("enablePptc", p.getBoolean("enablePptc", true))
                .put("useNce", p.getBoolean("useNce", false))
                .put("enableDocked", p.getBoolean("enableDocked", false))
                .put("resolution", p.getInt("resolution", 2))
                .put("memoryConfiguration", p.getInt("memoryConfiguration", 0));
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
            addNamed(all, prefs(c).getString("global", "[]"));
            for (String key : prefs(c).getAll().keySet()) {
                if (key.startsWith("game_")) addNamed(all, prefs(c).getString(key, "[]"));
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
