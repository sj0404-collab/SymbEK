package dev.symbiosis.kenji;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;

/**
 * Hands the ROM to the official Kenji-NX MainActivity shipped in this APK.
 *
 * Their handleIntent() reads extras named {@code bootPath}, {@code titleName},
 * {@code titleId}, {@code forceNceAndPptc} — NOT the EXTRA_* constants. The
 * HTML launcher sent the wrong names, so the game never started.
 */
public final class OfficialLaunch {
    private OfficialLaunch() {}

    public static void game(Context context, GameItem game) {
        if (game == null) return;
        game(context, game.path, game.title, game.titleId);
    }

    public static void game(Context context, String path, String title, String titleId) {
        DataSeed.ensure(context);
        String real = resolvePath(context, path);
        String boot = (real != null && !real.isEmpty()) ? real : (path == null ? "" : path);
        if (boot.isEmpty()) {
            toast(context, "нет пути к файлу игры");
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "org.kenjinx.android.MainActivity");
            intent.setAction("org.kenjinx.android.LAUNCH_GAME");
            intent.putExtra("bootPath", boot);
            intent.putExtra("titleName", title == null ? "" : title);
            intent.putExtra("titleId", titleId == null ? "" : titleId);
            intent.putExtra("forceNceAndPptc", false);
            if (boot.startsWith("content:")) {
                Uri uri = Uri.parse(boot);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    context.grantUriPermission(
                            context.getPackageName(),
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            toast(context, "не открылось: " + e.getMessage());
        }
    }

    public static void home(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "org.kenjinx.android.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            toast(context, "их интерфейс не открылся: " + e.getMessage());
        }
    }

    public static String resolvePath(Context context, String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("/")) {
            File f = new File(path);
            return f.isFile() ? f.getAbsolutePath() : path;
        }
        try {
            Uri uri = Uri.parse(path);
            String decoded = Uri.decode(uri.toString());
            int idx = decoded.indexOf("primary:");
            if (idx >= 0) {
                String rel = decoded.substring(idx + "primary:".length());
                int cut = rel.indexOf('?');
                if (cut >= 0) rel = rel.substring(0, cut);
                // document URIs can contain another "primary:" — take the last segment path
                int last = rel.lastIndexOf("primary:");
                if (last > 0) rel = rel.substring(last + "primary:".length());
                File file = new File(android.os.Environment.getExternalStorageDirectory(), rel);
                if (file.isFile()) return file.getAbsolutePath();
            }
            String mapped = DataSeed.treeToPath(uri);
            if (mapped != null) {
                File asFile = new File(mapped);
                if (asFile.isFile()) return asFile.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return path;
    }

    private static void toast(Context context, String message) {
        try {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }
}
