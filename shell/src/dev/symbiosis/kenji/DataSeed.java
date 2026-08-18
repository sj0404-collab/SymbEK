package dev.symbiosis.kenji;

import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keys + firmware where official GameHost looks:
 * {@code getExternalFilesDir()/system/prod.keys}
 * {@code .../bis/system/Contents/registered/{id}.nca/00}
 *
 * Firmware is never copied. Each {@code 00} is a symlink (ярлык) or
 * hardlink to Eden {@code nand/.../*.nca} or to another Kenji {@code bis}.
 * Two APKs share one dump. Keys are tiny and may be copied.
 */
public final class DataSeed {
    private DataSeed() {}

    private static final String PREF = "kenji_space";
    private static final String PREF_ROOT = "data_root";
    private static final String PREF_SRC = "fw_source";
    private static final String PREF_MODE = "fw_mode";
    private static final String PREF_NCA = "fw_nca";

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
        if (countReadable(destReg) >= 10) {
            rememberIfEmpty(context, destReg, destReg.getAbsolutePath(), modeOf(destReg));
            writeReport(context, dest);
            return;
        }
        for (File src : sources(context)) {
            File kenji = new File(src, "bis/system/Contents/registered");
            File stash = new File(src, "bis/system/Contents/registered.stash");
            File eden = new File(src, "nand/system/Contents/registered");
            File edenFlat = new File(src, "nand");
            if (countKenji(kenji) >= 10 && !samePath(kenji, destReg)) {
                if (bridgeFirmware(context, kenji, destReg, "kenji " + kenji.getAbsolutePath())) return;
            }
            if (countKenji(stash) >= 10) {
                if (bridgeFirmware(context, stash, destReg, "stash " + stash.getAbsolutePath())) return;
            }
            if (countAnyNca(eden) >= 10) {
                if (bridgeFirmware(context, eden, destReg, "eden " + eden.getAbsolutePath())) return;
            }
            if (countAnyNca(edenFlat) >= 10 && countAnyNca(eden) < 10) {
                if (bridgeFirmware(context, edenFlat, destReg, "nand " + edenFlat.getAbsolutePath())) return;
            }
        }
        writeReport(context, dest);
    }

    public static boolean keysOk(Context context) {
        File keys = new File(appPath(context), "system/prod.keys");
        return keys.isFile() && keys.length() > 100;
    }

    public static int firmwareNca(Context context) {
        return countReadable(new File(appPath(context), "bis/system/Contents/registered"));
    }

    public static String firmwareSource(Context context) {
        String s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_SRC, "");
        return s == null ? "" : s;
    }

    public static String firmwareMode(Context context) {
        String s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_MODE, "");
        return s == null ? "" : s;
    }

    public static String statusLine(Context context) {
        File keys = new File(appPath(context), "system/prod.keys");
        int nca = firmwareNca(context);
        String k = keys.isFile() && keys.length() > 100
                ? ("ключи " + (keys.length() / 1024) + " КБ")
                : "нет ключей";
        if (nca < 10) return k + " · нет прошивки в bis/";
        String src = firmwareSource(context);
        String mode = firmwareMode(context);
        String how = mode.isEmpty() ? "ярлыки" : mode;
        if (src.isEmpty()) return k + " · прошивка " + nca + " NCA (" + how + ")";
        return k + " · прошивка " + nca + " NCA · " + how + " ← " + src;
    }

    public static boolean looksLikeData(File f) {
        return looksData(f);
    }

    public static String statusJson(Context context) {
        ensure(context);
        File dest = appPath(context);
        File keys = new File(dest, "system/prod.keys");
        File destReg = new File(dest, "bis/system/Contents/registered");
        int nca = countReadable(destReg);
        try {
            JSONArray items = new JSONArray();
            items.put(item("Ключи", keys.isFile() && keys.length() > 100,
                    keys.isFile() ? ("prod.keys " + (keys.length() / 1024) + " КБ · " + keys.getAbsolutePath())
                            : "нет system/prod.keys"));
            String src = firmwareSource(context);
            String mode = firmwareMode(context);
            String fw = nca >= 10
                    ? (nca + " NCA · " + (mode.isEmpty() ? "ярлыки" : mode)
                    + (src.isEmpty() ? "" : (" ← " + src)))
                    : "Kenji не видит прошивку · нажмите «Мост»";
            items.put(item("Прошивка", nca >= 10, fw));
            items.put(item("Папка Kenji (bis)", true, destReg.getAbsolutePath()));
            items.put(item("Папка данных", true, dest.getAbsolutePath()));
            JSONArray found = new JSONArray();
            for (File s : sources(context)) {
                int kn = countKenji(new File(s, "bis/system/Contents/registered"));
                int en = countAnyNca(new File(s, "nand/system/Contents/registered"));
                if (kn < 10 && en < 10) continue;
                found.put(new JSONObject()
                        .put("path", s.getAbsolutePath())
                        .put("kenji", kn)
                        .put("eden", en));
            }
            return new JSONObject()
                    .put("items", items)
                    .put("sources", found)
                    .put("dataRoot", dest.getAbsolutePath())
                    .put("firmwarePath", destReg.getAbsolutePath())
                    .put("firmwareSource", src)
                    .put("firmwareMode", mode)
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
        int nca = countReadable(new File(dest, "bis/system/Contents/registered"));
        try {
            String src = firmwareSource(context);
            String mode = firmwareMode(context);
            return new JSONObject()
                    .put("ok", nca >= 10)
                    .put("message", nca >= 10
                            ? ("прошивка без копии · " + nca + " NCA · "
                            + (mode.isEmpty() ? "ярлыки" : mode)
                            + (src.isEmpty() ? "" : (" ← " + src)))
                            : "не нашёл прошивку. Проверьте Eden nand/ или Kenji bis/.../registered")
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

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
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(PREF_ROOT, path).commit();
    }

    public static File userRoot(Context context) {
        String p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(PREF_ROOT, "");
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
                "Switch",
                "Kenji",
                "Android/data/org.kenjinx.android/files",
                "Android/data/dev.symbiosis.kenji/files",
                "Android/data/dev.eden.eden_emulator/files",
                "Android/data/org.yuzu.yuzu_emu/files",
                "Android/data/org.citron.citron_emu/files"
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
        return f != null && f.isDirectory() && (
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

    /**
     * Lay Kenji {id}.nca/00 as shortcuts to src. Never copies NCA bytes.
     * @return true when dest has a readable firmware tree
     */
    private static boolean bridgeFirmware(Context context, File srcReg, File destReg, String label) {
        destReg.mkdirs();
        File[] entries = srcReg.listFiles();
        if (entries == null) return false;
        int linked = 0;
        String mode = "ярлыки";
        for (File entry : entries) {
            File payload;
            String name;
            if (entry.isFile() && entry.getName().toLowerCase(Locale.US).endsWith(".nca") && entry.length() > 1000) {
                payload = entry;
                name = entry.getName();
            } else if (entry.isDirectory() && entry.getName().toLowerCase(Locale.US).endsWith(".nca")) {
                File inner = new File(entry, "00");
                if (!inner.isFile() || inner.length() <= 1000) continue;
                payload = inner;
                name = entry.getName();
            } else {
                continue;
            }
            File destDir = new File(destReg, name);
            File dest = new File(destDir, "00");
            if (isReadableNca(dest) && isShortcut(dest)) {
                linked++;
                continue;
            }
            // Replace an old full copy with a shortcut so the second APK
            // stops holding a duplicate of the same firmware.
            if (dest.isFile() && !isShortcut(dest) && dest.length() == payload.length()) {
                if (!dest.delete()) continue;
            }
            destDir.mkdirs();
            String how = shortcut(payload, dest);
            if (how != null) {
                linked++;
                if ("hardlink".equals(how)) mode = "жёсткие ссылки";
            }
        }
        if (countReadable(destReg) >= 10) {
            remember(context, srcReg.getAbsolutePath(), mode, countReadable(destReg));
            writeReport(context, appPath(context));
            android.util.Log.i("KenjiSpace", "fw " + mode + " " + linked + " ← " + label);
            return true;
        }
        return false;
    }

    private static String shortcut(File src, File dest) {
        if (src == null || dest == null || !src.exists()) return null;
        if (dest.exists() && !dest.delete()) return null;
        try {
            Os.symlink(src.getAbsolutePath(), dest.getAbsolutePath());
            if (isReadableNca(dest) || isShortcut(dest)) return "symlink";
        } catch (Throwable t) {
            android.util.Log.w("KenjiSpace", "symlink " + dest.getName(), t);
        }
        try {
            Os.link(src.getAbsolutePath(), dest.getAbsolutePath());
            if (isReadableNca(dest)) return "hardlink";
        } catch (Throwable t) {
            android.util.Log.w("KenjiSpace", "link " + dest.getName(), t);
        }
        return null;
    }

    private static boolean isShortcut(File f) {
        try {
            StructStat st = Os.lstat(f.getAbsolutePath());
            return OsConstants.S_ISLNK(st.st_mode) || st.st_nlink > 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isReadableNca(File f) {
        try {
            return f != null && f.isFile() && f.length() > 1000;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int countKenji(File registered) {
        File[] dirs = registered == null ? null : registered.listFiles();
        if (dirs == null) return 0;
        int n = 0;
        for (File d : dirs) {
            File inner = new File(d, "00");
            if (d.isDirectory() && isReadableNca(inner)) n++;
        }
        return n;
    }

    private static int countReadable(File registered) {
        return countKenji(registered);
    }

    private static int countAnyNca(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".nca") && f.length() > 1000) n++;
            else if (f.isDirectory() && f.getName().toLowerCase(Locale.US).endsWith(".nca")
                    && isReadableNca(new File(f, "00"))) n++;
        }
        return n;
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static String modeOf(File destReg) {
        File[] dirs = destReg.listFiles();
        if (dirs == null) return "";
        int links = 0;
        int files = 0;
        for (File d : dirs) {
            File inner = new File(d, "00");
            if (!inner.exists()) continue;
            files++;
            if (isShortcut(inner)) links++;
        }
        if (files == 0) return "";
        if (links == files) return "ярлыки";
        if (links > 0) return "ярлыки + файлы";
        return "файлы (старая копия)";
    }

    private static void rememberIfEmpty(Context context, File destReg, String fallback, String mode) {
        String src = firmwareSource(context);
        if (src == null || src.isEmpty()) {
            remember(context, fallback, mode, countReadable(destReg));
        } else {
            remember(context, src, mode.isEmpty() ? firmwareMode(context) : mode, countReadable(destReg));
        }
    }

    private static void remember(Context context, String source, String mode, int nca) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(PREF_SRC, source == null ? "" : source)
                .putString(PREF_MODE, mode == null ? "" : mode)
                .putInt(PREF_NCA, nca)
                .commit();
    }

    private static void writeReport(Context context, File dest) {
        try {
            File report = new File(dest, "system/firmware_source.txt");
            File parent = report.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter w = new FileWriter(report, false);
            w.write("Kenji читает: " + new File(dest, "bis/system/Contents/registered").getAbsolutePath() + "\n");
            w.write("Источник: " + firmwareSource(context) + "\n");
            w.write("Как: " + firmwareMode(context) + " (без копии NCA)\n");
            w.write("NCA: " + firmwareNca(context) + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static String readFirstLine(File f) {
        try {
            Scanner s = new Scanner(f, "UTF-8");
            String line = s.hasNextLine() ? s.nextLine() : "";
            s.close();
            return line;
        } catch (Exception e) {
            return "";
        }
    }
}
