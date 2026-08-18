package dev.symbiosis.kenji;

import android.content.SharedPreferences;

/** Official Kenji writes mixed types. A wrong getFloat/getBoolean kills the activity. */
public final class SafePrefs {
    private SafePrefs() {}

    public static boolean bool(SharedPreferences p, String key, boolean def) {
        try {
            if (!p.contains(key)) return def;
            Object v = p.getAll().get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            if (v instanceof String) {
                String s = ((String) v).trim();
                if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) return true;
                if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) return false;
            }
            return def;
        } catch (Throwable t) {
            return def;
        }
    }

    public static int integer(SharedPreferences p, String key, int def) {
        try {
            if (!p.contains(key)) return def;
            Object v = p.getAll().get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try { return Integer.parseInt(((String) v).trim()); } catch (Exception ignored) {}
            }
            if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
            return def;
        } catch (Throwable t) {
            return def;
        }
    }

    public static float dec(SharedPreferences p, String key, float def) {
        try {
            if (!p.contains(key)) return def;
            Object v = p.getAll().get(key);
            if (v instanceof Number) return ((Number) v).floatValue();
            if (v instanceof String) {
                try { return Float.parseFloat(((String) v).trim().replace(',', '.')); } catch (Exception ignored) {}
            }
            return def;
        } catch (Throwable t) {
            return def;
        }
    }

    public static String text(SharedPreferences p, String key, String def) {
        try {
            if (!p.contains(key)) return def;
            Object v = p.getAll().get(key);
            return v == null ? def : String.valueOf(v);
        } catch (Throwable t) {
            return def;
        }
    }

    public static void putBool(SharedPreferences p, String key, boolean on) {
        try {
            p.edit().remove(key).putBoolean(key, on).commit();
        } catch (Throwable t) {
            try { p.edit().putBoolean(key, on).commit(); } catch (Throwable ignored) {}
        }
    }

    public static void putInt(SharedPreferences p, String key, int v) {
        try {
            p.edit().remove(key).putInt(key, v).commit();
        } catch (Throwable t) {
            try { p.edit().putInt(key, v).commit(); } catch (Throwable ignored) {}
        }
    }

    public static void putFloat(SharedPreferences p, String key, float v) {
        try {
            p.edit().remove(key).putFloat(key, v).commit();
        } catch (Throwable t) {
            try { p.edit().putFloat(key, v).commit(); } catch (Throwable ignored) {}
        }
    }
}
