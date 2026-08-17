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

    @JavascriptInterface public boolean keysOk() { return DataSeed.statusJson(activity).contains("\"keysOk\":true"); }
    @JavascriptInterface public void rescan() { DataSeed.ensure(activity); }
    @JavascriptInterface public String icon(String path) { return ""; }
    @JavascriptInterface public String cover(String path) { return ""; }
    @JavascriptInterface public String shot(String path) { return ""; }
    @JavascriptInterface public String shots(String path, String title) { return "{\"items\":[]}"; }
    @JavascriptInterface public String settings() { return SettingsBank.settingsJson(activity); }
    @JavascriptInterface public String setBool(String key, boolean on) { return SettingsBank.setBool(activity, key, on); }
    @JavascriptInterface public String setResolution(int index) { return SettingsBank.setBool(activity, "resolution_touch", true); }
    @JavascriptInterface public String cycleDram() { return "{\"message\":\"DRAM: как в официальном Kenji\"}"; }
    @JavascriptInterface public String cycleMemMode() { return "{\"message\":\"память: как в официальном Kenji\"}"; }
    @JavascriptInterface public String cycleCpu() { return SettingsBank.setBool(activity, "useNce", false); }
    @JavascriptInterface public String applyAaaMode() { SettingsBank.setBool(activity, "useNce", false); return "{\"ok\":true,\"message\":\"AAA: NCE выкл\"}"; }
    @JavascriptInterface public String crashReport() { return "{\"title\":\"Отчёт\",\"excerpt\":\"крашей оболочки нет\",\"path\":\"\"}"; }
    @JavascriptInterface public String presets() { return SettingsBank.listJson(activity, ""); }
    @JavascriptInterface public String savePreset(String name) { return SettingsBank.save(activity, name, "", true); }
    @JavascriptInterface public String applyPreset(String name) { return SettingsBank.apply(activity, name); }
    @JavascriptInterface public String removePreset(String name) { return SettingsBank.remove(activity, name); }
    @JavascriptInterface public String firmwareBridge() { return DataSeed.bridgeFirmware(activity); }
    @JavascriptInterface public String mods() { return GameShelf.modsJson(activity); }
    @JavascriptInterface public String bridgeMods() { return GameShelf.bridgeMods(activity); }
    @JavascriptInterface public String saveSource() { return GameShelf.saveSource(activity); }
    @JavascriptInterface public String saves() { return "{\"items\":[]}"; }
    @JavascriptInterface public String gameProps(String path, String title) { return GameShelf.properties(activity, path, title); }
    @JavascriptInterface public String saveGamePreset(String name, String titleId) { return SettingsBank.save(activity, name, titleId, false); }
    @JavascriptInterface public String applyDefault() { return SettingsBank.applyDefault(activity); }
    @JavascriptInterface public String suggestRoots() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            return new JSONObject().put("roots", arr).put("current", DataSeed.appPath(activity).getAbsolutePath()).toString();
        } catch (Exception e) { return "{\"roots\":[]}"; }
    }
    @JavascriptInterface public String memory() { return "{\"leftMb\":0,\"usedMb\":0,\"budgetMb\":0,\"warn\":false}"; }
    @JavascriptInterface public String prepareShaders() { return "{\"ok\":true,\"note\":\"кэш шейдеров у официального Kenji\"}"; }
    @JavascriptInterface public String engines() { return "{\"current\":\"kenji\",\"currentLabel\":\"Kenji-NX\",\"launch\":\"kenji\",\"items\":[]}"; }
    @JavascriptInterface public String selectEngine(String id) { return "{\"ok\":true,\"id\":\"kenji\"}"; }
    @JavascriptInterface public String spaces() { return "{\"current\":\"symbiosis\",\"items\":[]}"; }
    @JavascriptInterface public String selectSpace(String id) { return OfficialLaunch.home(activity); }
    @JavascriptInterface public String converterItems() { return "{\"items\":[]}"; }
    @JavascriptInterface public String plugins() { return "{\"items\":[],\"logs\":[]}"; }
    @JavascriptInterface public String pluginPayload() { return "{}"; }
    @JavascriptInterface public void pickConvert() {}
    @JavascriptInterface public void pickPlugin() {}
    @JavascriptInterface public void pickKeys() {}
    @JavascriptInterface public void pickFirmware() {}
    @JavascriptInterface public void pickSavesFolder() { pickDataRoot(); }
    @JavascriptInterface public void clearSavesFolder() {}
    @JavascriptInterface public void openEngines() { OfficialLaunch.home(activity); }
    @JavascriptInterface public void openUtilities() {}
    @JavascriptInterface public void openSettings() { OfficialLaunch.home(activity); }
    @JavascriptInterface public void openGameMenu(String path) {}
    @JavascriptInterface public void downloadEngine(String id) {}
    @JavascriptInterface public void probeEngine(String id) {}
    @JavascriptInterface public String openOfficialKenji(String path) { return OfficialLaunch.game(activity, path, ""); }
    @JavascriptInterface public String installKenjiShell() { return "{\"ok\":true,\"message\":\"оболочка уже эта\"}"; }
    @JavascriptInterface public String readText(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isFile() || f.length() > 200000) return "{\"ok\":false,\"reason\":\"не текст\"}";
            java.util.Scanner s = new java.util.Scanner(f, "UTF-8").useDelimiter("\\A");
            String t = s.hasNext() ? s.next() : "";
            s.close();
            return new JSONObject().put("ok", true).put("text", t).toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
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
