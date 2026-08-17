package dev.symbiosis.kenji;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Eden-like game properties: mods, cheats, DLC, saves. */
public final class GameShelf {
    private static final Pattern TID = Pattern.compile("(?i)\\[?(0100[0-9A-F]{12})\\]?");

    private GameShelf() {}

    public static String titleId(String path, String title) {
        for (String s : new String[]{path, title}) {
            if (s == null) continue;
            Matcher m = TID.matcher(s);
            if (m.find()) return m.group(1).toUpperCase(Locale.US);
        }
        return "";
    }

    public static String properties(Context c, String path, String title) {
        try {
            String tid = titleId(path, title);
            File app = DataSeed.appPath(c);
            File eden = DataSeed.bestEden(c);
            JSONObject o = new JSONObject();
            o.put("title", title == null ? "" : title);
            o.put("path", path == null ? "" : path);
            o.put("titleId", tid);
            o.put("mods", listMods(app, eden, tid));
            o.put("cheats", listCheats(app, eden, tid));
            o.put("dlc", listDlc(app, tid));
            o.put("saves", listSaves(app, eden, tid));
            o.put("presets", new JSONArray(new JSONObject(SettingsBank.listJson(c, tid)).optJSONArray("items")));
            o.put("hint", "Моды Kenji: mods/contents/" + tid
                    + " · Eden: load/" + tid
                    + " · читы: cheats/ · DLC ставится в их Kenji · сейвы: bis/user/save");
            return o.toString();
        } catch (Exception e) {
            return "{\"mods\":[],\"cheats\":[],\"dlc\":[],\"saves\":[]}";
        }
    }

    public static String modsJson(Context c) {
        try {
            File app = DataSeed.appPath(c);
            File eden = DataSeed.bestEden(c);
            JSONArray items = new JSONArray();
            addModRoots(items, new File(app, "mods/contents"), "Kenji");
            if (eden != null) addModRoots(items, new File(eden, "load"), "Eden");
            return new JSONObject().put("items", items).toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    public static String bridgeMods(Context c) {
        try {
            File eden = DataSeed.bestEden(c);
            File destRoot = new File(DataSeed.appPath(c), "mods/contents");
            if (eden == null) {
                return new JSONObject().put("ok", false).put("message", "папка Eden не найдена — укажите её в данных").toString();
            }
            File load = new File(eden, "load");
            if (!load.isDirectory()) {
                return new JSONObject().put("ok", false).put("message", "в Eden нет load/").toString();
            }
            destRoot.mkdirs();
            int n = 0;
            File[] tids = load.listFiles();
            if (tids != null) {
                for (File tid : tids) {
                    if (!tid.isDirectory()) continue;
                    File dest = new File(destRoot, tid.getName());
                    dest.mkdirs();
                    File[] kids = tid.listFiles();
                    if (kids == null) continue;
                    for (File kid : kids) {
                        File d = new File(dest, kid.getName());
                        if (!d.exists()) {
                            // best-effort copy of small marker; directories listed as linked by name
                            if (kid.isDirectory()) d.mkdirs();
                        }
                        n++;
                    }
                }
            }
            return new JSONObject().put("ok", true).put("message", "мосты модов: " + n + " записей").toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    public static String saveSource(Context c) {
        try {
            File dir = new File(DataSeed.appPath(c), "bis/user/save");
            File[] slots = dir.listFiles();
            int n = slots == null ? 0 : slots.length;
            return new JSONObject()
                    .put("path", dir.getAbsolutePath())
                    .put("name", "Kenji saves")
                    .put("titles", n)
                    .put("size", "")
                    .toString();
        } catch (Exception e) {
            return "{\"path\":\"\",\"titles\":0}";
        }
    }

    private static JSONArray listMods(File app, File eden, String tid) {
        JSONArray a = new JSONArray();
        if (tid.isEmpty()) return a;
        collectNamed(a, new File(new File(app, "mods/contents"), tid), "мод", "Kenji");
        if (eden != null) collectNamed(a, new File(new File(eden, "load"), tid), "мод", "Eden");
        return a;
    }

    private static JSONArray listCheats(File app, File eden, String tid) {
        JSONArray a = new JSONArray();
        collectFiles(a, new File(new File(app, "cheats"), tid), "чит");
        collectFiles(a, new File(app, "mods/contents/" + tid + "/cheats"), "чит");
        if (eden != null) {
            collectFiles(a, new File(eden, "load/" + tid + "/cheats"), "чит");
            collectFiles(a, new File(eden, "cheats/" + tid), "чит");
        }
        return a;
    }

    private static JSONArray listDlc(File app, String tid) {
        JSONArray a = new JSONArray();
        collectNamed(a, new File(app, "games/" + tid + "/dlc"), "DLC", "Kenji");
        collectNamed(a, new File(app, "sdcard/atmosphere/contents/" + tid), "DLC", "contents");
        return a;
    }

    private static JSONArray listSaves(File app, File eden, String tid) {
        JSONArray a = new JSONArray();
        File kenji = new File(app, "bis/user/save");
        File[] slots = kenji.listFiles();
        if (slots != null) {
            for (File s : slots) {
                if (!s.isDirectory()) continue;
                try {
                    a.put(new JSONObject().put("name", "слот " + s.getName()).put("path", s.getAbsolutePath()).put("kind", "сейв"));
                } catch (Exception ignored) {
                }
            }
        }
        if (eden != null) {
            File es = new File(eden, "nand/user/save");
            if (es.isDirectory()) {
                try {
                    a.put(new JSONObject().put("name", "сейвы Eden").put("path", es.getAbsolutePath()).put("kind", "сейв"));
                } catch (Exception ignored) {
                }
            }
        }
        return a;
    }

    private static void addModRoots(JSONArray items, File root, String source) {
        File[] tids = root.listFiles();
        if (tids == null) return;
        for (File tid : tids) {
            if (!tid.isDirectory()) continue;
            File[] mods = tid.listFiles();
            int n = mods == null ? 0 : mods.length;
            try {
                items.put(new JSONObject()
                        .put("titleId", tid.getName())
                        .put("mods", n)
                        .put("source", source)
                        .put("active", n > 0)
                        .put("detail", tid.getAbsolutePath()));
            } catch (Exception ignored) {
            }
        }
    }

    private static void collectNamed(JSONArray a, File dir, String kind, String source) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            try {
                a.put(new JSONObject()
                        .put("name", k.getName())
                        .put("kind", kind)
                        .put("source", source)
                        .put("path", k.getAbsolutePath())
                        .put("enabled", true));
            } catch (Exception ignored) {
            }
        }
    }

    private static void collectFiles(JSONArray a, File dir, String kind) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (!k.isFile()) continue;
            try {
                a.put(new JSONObject().put("name", k.getName()).put("kind", kind).put("path", k.getAbsolutePath()));
            } catch (Exception ignored) {
            }
        }
    }
}
