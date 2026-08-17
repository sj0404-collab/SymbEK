package dev.symbiosis.kenji;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;

/**
 * Hands the ROM to the official Kenji-NX MainActivity shipped in this APK.
 */
public final class OfficialLaunch {
    private OfficialLaunch() {}

    public static String game(Context context, String path, String title) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "org.kenjinx.android.MainActivity");
            intent.setAction("org.kenjinx.android.LAUNCH_GAME");
            intent.putExtra("EXTRA_BOOT_PATH", resolvePath(context, path));
            intent.putExtra("EXTRA_TITLE_NAME", title == null ? "" : title);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return new JSONObject()
                    .put("ok", true)
                    .put("message", "открыл официальный Kenji")
                    .toString();
        } catch (Exception e) {
            try {
                Intent home = new Intent();
                home.setClassName(context, "org.kenjinx.android.MainActivity");
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(home);
                return new JSONObject()
                        .put("ok", true)
                        .put("message", "открыл их интерфейс — выберите игру там")
                        .toString();
            } catch (Exception e2) {
                return jsonErr(e2.getMessage());
            }
        }
    }

    public static String home(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "org.kenjinx.android.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return new JSONObject().put("ok", true).put("message", "официальный Kenji").toString();
        } catch (Exception e) {
            return jsonErr(e.getMessage());
        }
    }

    private static String resolvePath(Context context, String path) {
        if (path == null) return "";
        if (path.startsWith("/")) return path;
        try {
            Uri uri = Uri.parse(path);
            String decoded = Uri.decode(uri.toString());
            int idx = decoded.indexOf("primary:");
            if (idx >= 0) {
                String rel = decoded.substring(idx + "primary:".length());
                int cut = rel.indexOf('?');
                if (cut >= 0) rel = rel.substring(0, cut);
                java.io.File file = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), rel);
                if (file.isFile()) return file.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return path;
    }

    private static String jsonErr(String message) {
        try {
            return new JSONObject().put("ok", false).put("message", message == null ? "не открылся" : message).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
