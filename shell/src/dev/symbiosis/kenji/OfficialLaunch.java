package dev.symbiosis.kenji;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;

/**
 * Starts the official GameHost in this same APK.
 * Their handleIntent() reads {@code bootPath}, {@code titleName}, {@code titleId}.
 */
public final class OfficialLaunch {
    private OfficialLaunch() {}

    public static void game(Context context, GameItem game) {
        if (game == null) return;
        if (game.update) {
            toast(context, "это обновление, не игра. Откройте базовый NSP.");
            return;
        }
        game(context, game.path, game.title, game.titleId);
    }

    public static void game(Context context, String path, String title, String titleId) {
        DataSeed.ensure(context);
        String boot = resolvePath(context, path);
        if (boot.isEmpty() || (!boot.startsWith("/") && !boot.startsWith("content:"))) {
            toast(context, "нет файла игры");
            return;
        }
        if (boot.startsWith("/")) {
            File f = new File(boot);
            if (!f.isFile()) {
                toast(context, "файл не найден: " + boot);
                return;
            }
        }
        if (FolderStore.isUpdateId(titleId)) {
            toast(context, "это обновление (…800), нужен базовый NSP");
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), "org.kenjinx.android.MainActivity");
            intent.setAction("org.kenjinx.android.LAUNCH_GAME");
            intent.putExtra("bootPath", boot);
            intent.putExtra("titleName", title == null ? "" : title);
            intent.putExtra("titleId", titleId == null ? "" : titleId);
            intent.putExtra("forceNceAndPptc", false);
            if (boot.startsWith("content:")) {
                Uri uri = Uri.parse(boot);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            // Official MainActivity must be task root — same as tapping the icon.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            toast(context, "не открылось: " + e.getMessage());
        }
    }

    public static String resolvePath(Context context, String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("/")) {
            File f = new File(path);
            return f.isFile() ? f.getAbsolutePath() : "";
        }
        try {
            Uri uri = Uri.parse(path);
            String decoded = Uri.decode(uri.toString());
            int idx = decoded.lastIndexOf("primary:");
            if (idx >= 0) {
                String rel = decoded.substring(idx + "primary:".length());
                int cut = rel.indexOf('?');
                if (cut >= 0) rel = rel.substring(0, cut);
                File file = new File(android.os.Environment.getExternalStorageDirectory(), rel);
                if (file.isFile()) return file.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return path.startsWith("content:") ? path : "";
    }

    private static void toast(Context context, String message) {
        try {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }
}
