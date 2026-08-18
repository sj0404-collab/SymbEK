package dev.symbiosis.kenji;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game folders: ours, plus the official {@code gameFolder} so a folder
 * already added in Kenji-NX is not lost when the launcher changes.
 */
public final class FolderStore {
    private static final String PREF = "kenji_folders";
    private static final Pattern TID = Pattern.compile("(?i)\\[?(0100[0-9A-F]{12})\\]?");

    private FolderStore() {}

    public static List<GameItem> listGames(Context context) {
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<GameItem> raw = new ArrayList<GameItem>();
        for (String uri : allFolderUris(context)) {
            if (uri.startsWith("/")) {
                collectFileDir(new File(uri), seen, raw);
            } else {
                collectTree(context, uri, seen, raw);
            }
        }
        List<GameItem> out = mergeUpdates(raw);
        Collections.sort(out, new Comparator<GameItem>() {
            @Override public int compare(GameItem a, GameItem b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return out;
    }

    /** Hide standalone updates (titleId …800). Official Kenji loads them from the same folder. */
    private static List<GameItem> mergeUpdates(List<GameItem> raw) {
        LinkedHashSet<String> bases = new LinkedHashSet<String>();
        for (GameItem g : raw) {
            if (!g.update && g.titleId.length() == 16) bases.add(g.titleId);
        }
        List<GameItem> out = new ArrayList<GameItem>();
        for (GameItem g : raw) {
            if (g.update) {
                String base = baseId(g.titleId);
                if (bases.contains(base)) continue;
                // no base in the folder — still don't launch it as a game
                continue;
            }
            out.add(g);
        }
        return out;
    }

    public static boolean isUpdateId(String titleId) {
        if (titleId == null || titleId.length() != 16) return false;
        return titleId.toUpperCase(Locale.US).endsWith("800");
    }

    public static String baseId(String titleId) {
        if (!isUpdateId(titleId)) return titleId == null ? "" : titleId;
        try {
            long v = Long.parseUnsignedLong(titleId, 16) - 0x800L;
            return String.format(Locale.US, "%016X", v);
        } catch (Exception e) {
            return titleId;
        }
    }

    public static void add(Context context, Uri uri) {
        if (uri == null) return;
        try {
            context.getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> next = new LinkedHashSet<String>(uris(context));
        next.add(uri.toString());
        String real = DataSeed.treeToPath(uri);
        if (real != null) next.add(real);
        p.edit().putStringSet("uris", next).commit();
        // Keep official Kenji's own library pointed at the same folder.
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString("gameFolder", uri.toString())
                    .commit();
        } catch (Exception ignored) {
        }
        if (real != null) {
            File maybeData = new File(real);
            if (DataSeed.looksLikeData(maybeData) || new File(maybeData, "files").isDirectory()) {
                DataSeed.setUserRoot(context, real);
            }
        }
    }

    public static void addPath(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        if (!f.isDirectory()) return;
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> next = new LinkedHashSet<String>(uris(context));
        next.add(f.getAbsolutePath());
        p.edit().putStringSet("uris", next).commit();
    }

    public static int folderCount(Context context) {
        return allFolderUris(context).size();
    }

    private static Set<String> allFolderUris(Context context) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.addAll(uris(context));
        try {
            String official = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("gameFolder", "");
            if (official != null && !official.isEmpty()) out.add(official);
        } catch (Exception ignored) {
        }
        File user = DataSeed.userRoot(context);
        if (user != null) {
            // User-picked data root may sit next to the dumps.
            File parent = user.getParentFile();
            if (parent != null) out.add(parent.getAbsolutePath());
            out.add(user.getAbsolutePath());
        }
        File sd = android.os.Environment.getExternalStorageDirectory();
        String[] rel = {
                "Download", "Download/Switch", "Download/NSP", "Download/Games",
                "Switch", "Games", "NSP", "XCI", "roms/switch", "Kenji/games",
                "Android/data/org.kenjinx.android/files/games"
        };
        for (String r : rel) {
            File f = new File(sd, r);
            if (f.isDirectory()) out.add(f.getAbsolutePath());
        }
        return out;
    }

    private static void collectFileDir(File dir, Set<String> seen, List<GameItem> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                // one level only — do not walk the whole card
                File[] inner = f.listFiles();
                if (inner == null) continue;
                for (File g : inner) {
                    if (g.isFile() && isRom(g.getName())) addFile(g, seen, out);
                }
            } else if (isRom(f.getName())) {
                addFile(f, seen, out);
            }
        }
    }

    private static void collectTree(Context context, String uriString, Set<String> seen, List<GameItem> out) {
        try {
            Uri tree = Uri.parse(uriString);
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            ContentResolver cr = context.getContentResolver();
            Cursor c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                if (!isRom(name)) continue;
                Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String real = OfficialLaunch.resolvePath(context, file.toString());
                String path = (real != null && real.startsWith("/") && new File(real).isFile())
                        ? real : file.toString();
                if (!seen.add(path)) continue;
                String tid = titleIdOf(name);
                out.add(new GameItem(prettyTitle(name), path, tid, human(size), name, isUpdateId(tid), size));
            }
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static void addFile(File f, Set<String> seen, List<GameItem> out) {
        String path = f.getAbsolutePath();
        if (!seen.add(path)) return;
        out.add(new GameItem(prettyTitle(f.getName()), path, titleIdOf(f.getName()), human(f.length()), f.getName()));
    }

    private static Set<String> uris(Context context) {
        Set<String> set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getStringSet("uris", null);
        return set == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(set);
    }

    static boolean isRom(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nro")
                || n.endsWith(".nsz") || n.endsWith(".xcz");
    }

    static String prettyTitle(String name) {
        if (name == null) return "игра";
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        stem = stem.replaceAll("(?i)\\[0100[0-9A-F]{12}]", "");
        stem = stem.replaceAll("(?i)\\[v\\d+]", "");
        stem = stem.replaceAll("\\([^)]*\\s*[Gg][Bb]\\)", "");
        stem = stem.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return stem.isEmpty() ? name : stem;
    }

    static String titleIdOf(String name) {
        if (name == null) return "";
        Matcher m = TID.matcher(name);
        return m.find() ? m.group(1).toUpperCase(Locale.US) : "";
    }

    static String human(long n) {
        if (n <= 0) return "";
        if (n < 1024L * 1024) return (n / 1024) + " КБ";
        if (n < 1024L * 1024 * 1024) return (n / (1024 * 1024)) + " МБ";
        return String.format(Locale.US, "%.1f ГБ", n / (1024.0 * 1024 * 1024));
    }
}
