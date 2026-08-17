package dev.symbiosis.kenji;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Puts keys + Kenji-layout firmware where official GameHost looks:
 * getExternalFilesDir()/system/prod.keys and .../bis/.../registered/{id}.nca/00
 *
 * Also undoes registered.stash / registered.junk left by older Kenji Space
 * builds — that empty registered/ is why Loading never finishes.
 */
public final class DataSeed {
    private DataSeed() {}

    public static File appPath(Context context) {
        File ext = context.getExternalFilesDir(null);
        return ext != null ? ext : context.getFilesDir();
    }

    public static void ensure(Context context) {
        try {
            ensureInner(context);
        } catch (Throwable t) {
            android.util.Log.e("KenjiSpace", "ensure", t);
        }
    }

    private static void ensureInner(Context context) {
        File dest = appPath(context);
        restoreOrphanStash(dest);
        for (File src : sources(context)) {
            restoreOrphanStash(src);
            copyKey(new File(src, "system/prod.keys"), new File(dest, "system/prod.keys"));
            copyKey(new File(src, "keys/prod.keys"), new File(dest, "system/prod.keys"));
        }
        File destReg = new File(dest, "bis/system/Contents/registered");
        if (countKenji(destReg) >= 10) return;
        for (File src : sources(context)) {
            File kenji = new File(src, "bis/system/Contents/registered");
            File stash = new File(src, "bis/system/Contents/registered.stash");
            File eden = new File(src, "nand/system/Contents/registered");
            if (countKenji(kenji) >= 10) {
                bridgeFirmware(kenji, destReg);
            } else if (countKenji(stash) >= 10) {
                bridgeFirmware(stash, destReg);
            } else if (eden.isDirectory()) {
                bridgeFirmware(eden, destReg);
            }
            if (countKenji(destReg) >= 10) return;
        }
    }

    public static boolean keysOk(Context context) {
        File keys = new File(appPath(context), "system/prod.keys");
        return keys.isFile() && keys.length() > 100;
    }

    public static int firmwareNca(Context context) {
        return countKenji(new File(appPath(context), "bis/system/Contents/registered"));
    }

    public static String statusLine(Context context) {
        File keys = new File(appPath(context), "system/prod.keys");
        int nca = firmwareNca(context);
        String k = keys.isFile() && keys.length() > 100
                ? ("ключи " + (keys.length() / 1024) + " КБ")
                : "нет ключей";
        String f = nca >= 10 ? ("прошивка " + nca + " NCA") : "нет прошивки в bis/";
        return k + " · " + f;
    }

    public static boolean looksLikeData(File f) {
        return looksData(f);
    }

    public static String statusJson(Context context) {
        ensure(context);
        File dest = appPath(context);
        File keys = new File(dest, "system/prod.keys");
        int nca = countKenji(new File(dest, "bis/system/Contents/registered"));
        try {
            JSONArray items = new JSONArray();
            items.put(item("Ключи", keys.isFile() && keys.length() > 100,
                    keys.isFile() ? ("prod.keys " + (keys.length() / 1024) + " КБ") : "нет system/prod.keys"));
            items.put(item("Прошивка", nca >= 10,
                    nca >= 10 ? (nca + " NCA в bis/ — Kenji видит") : "Kenji не видит прошивку · нажмите «Мост»"));
            items.put(item("Папка данных", true, dest.getAbsolutePath()));
            return new JSONObject()
                    .put("items", items)
                    .put("dataRoot", dest.getAbsolutePath())
                    .put("firmwareOk", nca >= 10)
                    .put("keysOk", keys.isFile() && keys.length() > 100)
                    .toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    public static String bridgeFirmware(Context context) {
        ensure(context);
        File dest = appPath(context);
        int nca = countKenji(new File(dest, "bis/system/Contents/registered"));
        try {
            return new JSONObject()
                    .put("ok", nca >= 10)
                    .put("message", nca >= 10
                            ? ("прошивка на месте · " + nca + " NCA в " + dest.getAbsolutePath())
                            : "не нашёл прошивку. Проверьте Download/ed/Eden/files/bis/.../registered или registered.stash")
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** Older builds hid firmware here. Put it back so Kenji and Eden both see it. */
    public static void restoreOrphanStash(File root) {
        try {
            restoreOrphanStashInner(root);
        } catch (Throwable t) {
            android.util.Log.e("KenjiSpace", "stash", t);
        }
    }

    private static void restoreOrphanStashInner(File root) {
        if (root == null || !root.isDirectory()) return;
        File registered = new File(root, "bis/system/Contents/registered");
        File stash = new File(root, "bis/system/Contents/registered.stash");
        File junk = new File(root, "bis/system/Contents/registered.junk");
        if (countKenji(registered) < 10 && countKenji(stash) >= 10) {
            if (registered.exists()) mergeInto(stash, registered);
            else stash.renameTo(registered);
        }
        if (junk.isDirectory()) mergeInto(junk, registered);
    }

    public static void setUserRoot(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
                .edit().putString("data_root", path).commit();
    }

    public static File userRoot(Context context) {
        String p = context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
                .getString("data_root", "");
        if (p == null || p.isEmpty()) return null;
        File f = new File(p);
        return f.isDirectory() ? f : null;
    }

    public static File bestEden(Context context) {
        for (File src : sources(context)) {
            if (new File(src, "nand/system/Contents/registered").isDirectory()
                    || new File(src, "load").isDirectory()
                    || new File(src, "keys/prod.keys").isFile()) {
                return src;
            }
        }
        return userRoot(context);
    }

    public static String treeToPath(android.net.Uri uri) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
            String[] parts = id.split(":", 2);
            String rel = parts.length > 1 ? parts[1] : "";
            if ("primary".equalsIgnoreCase(parts[0])) {
                File f = new File(Environment.getExternalStorageDirectory(), rel);
                if (f.isDirectory()) return f.getAbsolutePath();
            }
            File alt = new File("/storage/" + parts[0] + "/" + rel);
            if (alt.isDirectory()) return alt.getAbsolutePath();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<File> sources(Context context) {
        File sd = Environment.getExternalStorageDirectory();
        List<File> out = new ArrayList<>();
        File user = userRoot(context);
        if (user != null) {
            out.add(user);
            File files = new File(user, "files");
            if (files.isDirectory()) out.add(files);
        }
        String[] rel = {
                "Download/ed/Eden/files",
                "Download/ed/Eden",
                "Eden/files",
                "Eden",
                "Android/data/org.kenjinx.android/files",
                "Android/data/dev.eden.eden_emulator/files",
                "Android/data/org.yuzu.yuzu_emu/files",
                "Switch",
                "Kenji"
        };
        for (String r : rel) {
            File f = new File(sd, r);
            if (f.isDirectory()) out.add(f);
        }
        scanKids(new File(sd, "Download"), out);
        scanKids(sd, out);
        return out;
    }

    private static void scanKids(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            if (looksData(f) && !out.contains(f)) out.add(f);
            File files = new File(f, "files");
            if (looksData(files) && !out.contains(files)) out.add(files);
        }
    }

    private static boolean looksData(File f) {
        return f.isDirectory() && (
                new File(f, "system/prod.keys").isFile()
                        || new File(f, "keys/prod.keys").isFile()
                        || new File(f, "bis").isDirectory()
                        || new File(f, "nand").isDirectory()
                        || new File(f, "load").isDirectory());
    }

    private static JSONObject item(String label, boolean present, String detail) throws Exception {
        return new JSONObject().put("label", label).put("present", present).put("detail", detail);
    }

    private static void mergeInto(File from, File to) {
        if (!from.isDirectory()) return;
        to.mkdirs();
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            File dest = new File(to, kid.getName());
            if (!dest.exists()) kid.renameTo(dest);
        }
    }

    private static void copyKey(File from, File to) {
        if (!from.isFile() || from.length() < 100) return;
        if (to.isFile() && to.length() == from.length()) return;
        File parent = to.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    private static void bridgeFirmware(File srcReg, File destReg) {
        destReg.mkdirs();
        File[] entries = srcReg.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            File payload;
            String name;
            if (entry.isFile() && entry.getName().toLowerCase().endsWith(".nca") && entry.length() > 1000) {
                payload = entry;
                name = entry.getName();
            } else if (entry.isDirectory() && entry.getName().toLowerCase().endsWith(".nca")) {
                File inner = new File(entry, "00");
                if (!inner.isFile() || inner.length() <= 1000) continue;
                payload = inner;
                name = entry.getName();
            } else {
                continue;
            }
            File destDir = new File(destReg, name);
            File dest = new File(destDir, "00");
            if (dest.isFile() && dest.length() == payload.length()) continue;
            destDir.mkdirs();
            copyKey(payload, dest);
        }
    }

    private static int countKenji(File registered) {
        File[] dirs = registered == null ? null : registered.listFiles();
        if (dirs == null) return 0;
        int n = 0;
        for (File d : dirs) {
            File inner = new File(d, "00");
            if (d.isDirectory() && inner.isFile() && inner.length() > 1000) n++;
        }
        return n;
    }
}
