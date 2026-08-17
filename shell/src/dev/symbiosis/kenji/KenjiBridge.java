package dev.symbiosis.kenji;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

public class KenjiBridge {
    static final int REQ_FOLDER = 71;
    static final int REQ_DATA = 72;

    private final LibraryActivity activity;

    public KenjiBridge(LibraryActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String games() {
        return FolderStore.gamesJson(activity);
    }

    @JavascriptInterface
    public String folders() {
        return FolderStore.json(activity);
    }

    @JavascriptInterface
    public String status() {
        return DataSeed.statusJson(activity);
    }

    @JavascriptInterface
    public String launch(String path, String title) {
        DataSeed.ensure(activity);
        return OfficialLaunch.game(activity, path, title);
    }

    @JavascriptInterface
    public void pickFolder() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivityForResult(i, REQ_FOLDER);
        });
    }

    @JavascriptInterface
    public void pickDataRoot() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivityForResult(i, REQ_DATA);
        });
    }

    @JavascriptInterface
    public String bridgeFirmware() {
        return DataSeed.bridgeFirmware(activity);
    }

    @JavascriptInterface
    public String openOfficialHome() {
        return OfficialLaunch.home(activity);
    }

    @JavascriptInterface
    public String settingsHelp() {
        try {
            return new JSONObject()
                    .put("title", "Настройки Kenji · прошивка")
                    .put("text",
                            "Kenji читает только:\n"
                                    + "• ключи: system/prod.keys (не keys/prod.keys)\n"
                                    + "• прошивка: bis/system/Contents/registered/{id}.nca/00\n"
                                    + "Eden держит nand/.../registered/*.nca — это другие пути, те же байты.\n"
                                    + "Вечный Loading при живой иконке = нет bis/. "
                                    + "Если есть registered.stash — это спрятанная прошивка, мост вернёт её сам.\n"
                                    + "Автоисправление без краша: при старте копируем Eden/их Kenji → "
                                    + "Android/data/dev.symbiosis.kenji/files")
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String removeFolder(String uri) {
        return FolderStore.remove(activity, uri);
    }

    static void onResult(LibraryActivity activity, int code, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (code == REQ_FOLDER) {
            FolderStore.add(activity, uri);
            activity.eval("try{if(window.onFolderAdded)onFolderAdded()}catch(e){}");
        } else if (code == REQ_DATA) {
            try {
                activity.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            DataSeed.ensure(activity);
            activity.eval("try{if(window.onFolderAdded)onFolderAdded()}catch(e){}");
        }
    }
}
