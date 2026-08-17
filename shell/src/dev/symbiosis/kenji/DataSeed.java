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
        File dest = appPath(context);
        restoreOrphanStash(dest);
        for (File src : sources()) {
            restoreOrphanStash(src);
            copyKey(new File(src, "system/prod.keys"), new File(dest, "system/prod.keys"));
            copyKey(new File(src, "keys/prod.keys"), new File(dest, "system/prod.keys"));
        }
        File destReg = new File(dest, "bis/system/Contents/registered");
        if (countKenji(destReg) >= 10) return;
        for (File src : sources()) {
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

    private static List<File> sources() {
        File sd = Environment.getExternalStorageDirectory();
        List<File> out = new ArrayList<>();
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
        return out;
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
