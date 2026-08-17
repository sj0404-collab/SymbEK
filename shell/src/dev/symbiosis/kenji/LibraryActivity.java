package dev.symbiosis.kenji;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * React library shell. The official Kenji-NX MainActivity still plays the game.
 */
public class LibraryActivity extends Activity {
    private WebView web;
    private KenjiBridge bridge;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DataSeed.ensure(this);
        askAllFiles();

        bridge = new KenjiBridge(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(bridge, "KenjiSpace");
        web.addJavascriptInterface(bridge, "Symbiosis");
        web.addJavascriptInterface(bridge, "Kenji");
        web.loadUrl("file:///android_asset/www/index.html");
        setContentView(web);

        handleView(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleView(intent);
    }

    private void handleView(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        OfficialLaunch.game(this, intent.getData().toString(), "игра");
    }

    @Override
    protected void onResume() {
        super.onResume();
        DataSeed.ensure(this);
        if (web != null) {
            web.evaluateJavascript(
                    "try{if(window.onFolderAdded)onFolderAdded()}catch(e){}",
                    null);
        }
    }

    void eval(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        KenjiBridge.onResult(this, requestCode, resultCode, data);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void askAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        }
    }
}
