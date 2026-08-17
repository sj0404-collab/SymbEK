package dev.symbiosis.kenji;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

public final class FolderStore {
    private static final String PREF = "kenji_folders";

    private FolderStore() {}

    public static String json(Context context) {
        JSONArray arr = new JSONArray();
        for (String uri : uris(context)) {
            try {
                String name = uri.substring(uri.lastIndexOf('/') + 1);
                int games = 0;
                Uri tree = Uri.parse(uri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Cursor c = context.getContentResolver().query(
                        children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                        null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String n = c.getString(0);
                        if (isRom(n)) games++;
                    }
                    c.close();
                }
                arr.put(new JSONObject().put("uri", uri).put("name", name).put("games", games));
            } catch (Exception ignored) {
            }
        }
        try {
            return new JSONObject().put("folders", arr).toString();
        } catch (Exception e) {
            return "{\"folders\":[]}";
        }
    }

    public static String gamesJson(Context context) {
        JSONArray arr = new JSONArray();
        for (String uri : uris(context)) {
            try {
                Uri tree = Uri.parse(uri);
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                ContentResolver cr = context.getContentResolver();
                Cursor c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE
                }, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    long size = c.getLong(2);
                    if (!isRom(name)) continue;
                    Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    arr.put(new JSONObject()
                            .put("title", name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name)
                            .put("path", file.toString())
                            .put("fileSize", human(size)));
                }
                c.close();
            } catch (Exception ignored) {
            }
        }
        try {
            return new JSONObject().put("games", arr).toString();
        } catch (Exception e) {
            return "{\"games\":[]}";
        }
    }

    public static void add(Context context, Uri uri) {
        try {
            context.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(uris(context));
        next.add(uri.toString());
        p.edit().putStringSet("uris", next).commit();
    }

    public static String remove(Context context, String uri) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(uris(context));
        next.remove(uri);
        p.edit().putStringSet("uris", next).commit();
        try {
            return new JSONObject().put("ok", true).put("message", "папка убрана").toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    private static Set<String> uris(Context context) {
        Set<String> set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getStringSet("uris", null);
        return set == null ? new HashSet<String>() : new HashSet<String>(set);
    }

    private static boolean isRom(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nro");
    }

    private static String human(long n) {
        if (n <= 0) return "";
        if (n < 1024L * 1024) return (n / 1024) + " КБ";
        if (n < 1024L * 1024 * 1024) return (n / (1024 * 1024)) + " МБ";
        return String.format("%.1f ГБ", n / (1024.0 * 1024 * 1024));
    }
}
