package dev.symbiosis.kenji;

import android.content.Context;
import java.io.File;

/** Drop leftover WebView / HTML caches so an old www UI cannot come back. */
public final class WebWipe {
    private WebWipe() {}

    public static void run(Context context) {
        if (context == null) return;
        try {
            File data = context.getApplicationInfo().dataDir == null
                    ? context.getFilesDir().getParentFile()
                    : new File(context.getApplicationInfo().dataDir);
            String[] names = {
                    "app_webview", "app_webview_data", "app_textures",
                    "app_webview_metrics", "app_hws_webview"
            };
            if (data != null) {
                for (String n : names) wipe(new File(data, n));
            }
            wipe(context.getDir("webview", Context.MODE_PRIVATE));
            wipe(context.getDir("webview_data", Context.MODE_PRIVATE));
            File cache = context.getCacheDir();
            if (cache != null) {
                wipe(new File(cache, "WebView"));
                wipe(new File(cache, "webview"));
                wipe(new File(cache, "org.chromium.android_webview"));
                wipe(new File(cache, "www"));
                wipe(new File(cache, "web"));
            }
            wipe(new File(context.getFilesDir(), "www"));
            wipe(new File(context.getFilesDir(), "web"));
            wipe(new File(context.getFilesDir(), "assets/www"));
            File ext = context.getExternalCacheDir();
            if (ext != null) {
                wipe(new File(ext, "www"));
                wipe(new File(ext, "WebView"));
            }
        } catch (Throwable t) {
            android.util.Log.w("KenjiSpace", "webwipe", t);
        }
    }

    private static void wipe(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) wipe(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
