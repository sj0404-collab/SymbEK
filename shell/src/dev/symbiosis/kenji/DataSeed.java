package dev.symbiosis.kenji;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Copies Eden/Kenji keys and firmware into official AppPath
 * (getExternalFilesDir) so the stock GameHost sees them.
 */
public final class DataSeed {
    private DataSeed() {}

    public static File appPath(Context context) {
        File ext = context.getExternalFilesDir(null);
        return ext != null ? ext : context.getFilesDir();
    }

    public static void ensure(Context context) {
        File dest = appPath(context);
        File src = new File(Environment.getExternalStorageDirectory(), "Download/ed/Eden/files");
        if (!src.isDirectory()) {
            src = dest;
        }
        copyKey(new File(src, "system/prod.keys"), new File(dest, "system/prod.keys"));
        copyKey(new File(src, "keys/prod.keys"), new File(dest, "system/prod.keys"));
        File srcReg = new File(src, "bis/system/Contents/registered");
        if (!srcReg.isDirectory()) {
            srcReg = new File(src, "nand/system/Contents/registered");
        }
        File destReg = new File(dest, "bis/system/Contents/registered");
        if (srcReg.isDirectory() && countKenji(destReg) < 10) {
            bridgeFirmware(srcReg, destReg);
        }
    }

    public static String statusJson(Context context) {
        File dest = appPath(context);
        File keys = new File(dest, "system/prod.keys");
        int nca = countKenji(new File(dest, "bis/system/Contents/registered"));
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            items.put(item("Ключи", keys.isFile() && keys.length() > 100, keys.isFile() ? "system/prod.keys" : "нет prod.keys"));
            items.put(item("Прошивка", nca >= 10, nca + " NCA в bis/"));
            items.put(item("Папка данных", dest.isDirectory(), dest.getAbsolutePath()));
            return new org.json.JSONObject()
                    .put("items", items)
                    .put("dataRoot", dest.getAbsolutePath())
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
            return new org.json.JSONObject()
                    .put("ok", nca >= 10)
                    .put("message", nca >= 10
                            ? ("прошивка на месте · " + nca + " NCA")
                            : "не нашёл 10 NCA в Eden/bis")
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static org.json.JSONObject item(String label, boolean present, String detail) throws Exception {
        return new org.json.JSONObject()
                .put("label", label)
                .put("present", present)
                .put("detail", detail);
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
        File[] dirs = registered.listFiles();
        if (dirs == null) return 0;
        int n = 0;
        for (File d : dirs) {
            File inner = new File(d, "00");
            if (d.isDirectory() && inner.isFile() && inner.length() > 1000) n++;
        }
        return n;
    }
}
