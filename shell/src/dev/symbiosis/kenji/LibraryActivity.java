package dev.symbiosis.kenji;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native library. Official {@code MainActivity} still plays the game.
 * No WebView, no HTML.
 */
public class LibraryActivity extends Activity {
    static final int REQ_FOLDER = 71;
    static final int REQ_DATA = 72;
    static final int BG = 0xFF0E0E14;
    static final int CARD = 0xFF1A1A24;
    static final int PINK = 0xFFFF4D8D;
    static final int CYAN = 0xFF5EF0E6;
    static final int TEXT = 0xFFECECF4;
    static final int MUTED = 0xFF9A9AAB;

    private GridView grid;
    private EditText search;
    private TextView status;
    private TextView empty;
    private Adapter adapter;
    private final List<GameItem> all = new ArrayList<GameItem>();
    private final List<GameItem> shown = new ArrayList<GameItem>();
    private final ConcurrentHashMap<String, Bitmap> covers = new ConcurrentHashMap<String, Bitmap>();
    private final ExecutorService coverPool = Executors.newFixedThreadPool(2);
    private GameItem selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            android.util.Log.e("KenjiSpace", "ui", t);
            TextView fallback = new TextView(this);
            fallback.setText("Kenji Space");
            fallback.setTextColor(TEXT);
            fallback.setPadding(dp(16), dp(32), dp(16), dp(16));
            setContentView(fallback);
        }
        askAllFiles();
        new Thread(() -> {
            try {
                DataSeed.ensure(this);
                if (!getSharedPreferences("kenji_space", MODE_PRIVATE).getBoolean("mali_applied", false)) {
                    SettingsBank.applyDefault(this);
                    getSharedPreferences("kenji_space", MODE_PRIVATE)
                            .edit().putBoolean("mali_applied", true).commit();
                }
            } catch (Throwable t) {
                android.util.Log.e("KenjiSpace", "seed", t);
            }
            runOnUiThread(() -> reload(false));
        }, "kenji-seed").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleView(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        coverPool.shutdownNow();
    }

    private void handleView(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String path = OfficialLaunch.resolvePath(this, intent.getData().toString());
        OfficialLaunch.game(this, path, intent.getData().getLastPathSegment(), FolderStore.titleIdOf(path));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(12);
        root.setPadding(pad, dp(18), pad, dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Kenji Space");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        head.addView(title, tp);
        head.addView(pill("Настройки", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(LibraryActivity.this, SettingsActivity.class));
            }
        }));
        root.addView(head);

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        search = new EditText(this);
        search.setHint("найти игру…");
        search.setHintTextColor(0xFF6A6A78);
        search.setTextColor(TEXT);
        search.setBackground(round(CARD, dp(10)));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, lp(-1, -2, 0, 0, 0, 8));

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            GameItem g = adapter.getItem(pos);
            selected = g;
            launch(g);
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            GameItem g = adapter.getItem(pos);
            selected = g;
            openProps(g);
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(this);
        empty.setTextColor(MUTED);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(24), dp(16), dp(24));
        empty.setText("нет игр — нажмите «Папка» и укажите каталог с NSP/XCI");
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(0, dp(8), 0, 0);
        dock.addView(dockBtn("Папка", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_FOLDER); }
        }), dockLp());
        dock.addView(dockBtn("Мост", new View.OnClickListener() {
            @Override public void onClick(View v) {
                DataSeed.ensure(LibraryActivity.this);
                String msg = DataSeed.bridgeFirmware(LibraryActivity.this);
                refreshStatus();
                toast(msg.contains("прошивка") ? extractMessage(msg) : msg);
            }
        }), dockLp());
        dock.addView(dockBtn("Свойства", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selected == null && !shown.isEmpty()) selected = shown.get(0);
                if (selected == null) {
                    toast("сначала выберите игру");
                    return;
                }
                openProps(selected);
            }
        }), dockLp());
        dock.addView(dockBtn("Данные", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_DATA); }
        }), dockLp());
        root.addView(dock);
        return root;
    }

    private void launch(GameItem g) {
        if (g == null) return;
        String low = g.fileName.toLowerCase(Locale.US);
        if (low.endsWith(".nsz") || low.endsWith(".xcz")) {
            toast("NSZ/XCZ не распакованы — нужен NSP или XCI");
            return;
        }
        if (!DataSeed.keysOk(this) || DataSeed.firmwareNca(this) < 10) {
            toast("нет ключей или прошивки в bis/ — нажмите «Мост» или «Данные»");
        }
        OfficialLaunch.game(this, g);
    }

    private void openProps(GameItem g) {
        if (g == null) return;
        Intent i = new Intent(this, GamePropsActivity.class);
        i.putExtra("path", g.path);
        i.putExtra("title", g.title);
        i.putExtra("titleId", g.titleId);
        startActivity(i);
    }

    private void reload(boolean fromUser) {
        refreshStatus();
        new Thread(() -> {
            final List<GameItem> games = FolderStore.listGames(this);
            runOnUiThread(() -> {
                all.clear();
                all.addAll(games);
                applyFilter();
                if (fromUser) toast("игр: " + games.size());
            });
            for (GameItem g : games) {
                final GameItem item = g;
                coverPool.execute(() -> {
                    try {
                        Bitmap b = CoverArt.load(LibraryActivity.this, item);
                        if (b != null) {
                            covers.put(item.key(), b);
                            runOnUiThread(() -> adapter.notifyDataSetChanged());
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        }, "kenji-list").start();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (GameItem g : all) {
            if (q.isEmpty()
                    || g.title.toLowerCase(Locale.US).contains(q)
                    || g.titleId.toLowerCase(Locale.US).contains(q)
                    || g.fileName.toLowerCase(Locale.US).contains(q)) {
                shown.add(g);
            }
        }
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void refreshStatus() {
        status.setText(DataSeed.statusLine(this)
                + " · папок " + FolderStore.folderCount(this));
    }

    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_FOLDER) {
            FolderStore.add(this, uri);
            reload(true);
        } else if (requestCode == REQ_DATA) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String path = DataSeed.treeToPath(uri);
            if (path != null) DataSeed.setUserRoot(this, path);
            DataSeed.ensure(this);
            refreshStatus();
            toast(path == null ? "папка данных принята" : ("данные: " + path));
        }
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String extractMessage(String json) {
        int i = json.indexOf("\"message\":\"");
        if (i < 0) return json;
        int s = i + 11;
        int e = json.indexOf('"', s);
        return e > s ? json.substring(s, e).replace("\\n", "\n") : json;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams dockLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private View dockBtn(String label, View.OnClickListener click) {
        return pill(label, true, click);
    }

    private Button pill(String label, boolean fill, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fill ? TEXT : BG);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(round(fill ? CARD : CYAN, dp(10)));
        b.setOnClickListener(click);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public GameItem getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile;
            if (convertView instanceof Tile) {
                tile = (Tile) convertView;
            } else {
                tile = new Tile(LibraryActivity.this);
            }
            tile.bind(getItem(position));
            return tile;
        }
    }

    private class Tile extends LinearLayout {
        final ImageView art;
        final TextView letter;
        final TextView name;
        final TextView meta;
        final FrameLayout cover;

        Tile(Activity a) {
            super(a);
            setOrientation(VERTICAL);
            setBackground(round(CARD, dp(10)));
            setPadding(dp(6), dp(6), dp(6), dp(8));
            int w = getResources().getDisplayMetrics().widthPixels;
            int cell = (w - dp(36)) / 2;
            cover = new FrameLayout(a);
            art = new ImageView(a);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            letter = new TextView(a);
            letter.setTextColor(PINK);
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            letter.setTypeface(Typeface.DEFAULT_BOLD);
            letter.setGravity(Gravity.CENTER);
            cover.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            cover.addView(art, new FrameLayout.LayoutParams(-1, -1));
            cover.setBackground(round(0xFF12121A, dp(8)));
            addView(cover, new LayoutParams(-1, cell));
            name = new TextView(a);
            name.setTextColor(TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setMaxLines(2);
            name.setPadding(0, dp(6), 0, 0);
            addView(name);
            meta = new TextView(a);
            meta.setTextColor(MUTED);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            addView(meta);
            setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        }

        void bind(GameItem g) {
            name.setText(g.title);
            String m = g.size;
            if (g.titleId != null && !g.titleId.isEmpty()) {
                m = (m.isEmpty() ? "" : m + " · ") + g.titleId.substring(0, Math.min(16, g.titleId.length()));
            }
            meta.setText(m);
            Bitmap b = covers.get(g.key());
            if (b != null) {
                art.setImageBitmap(b);
                art.setVisibility(VISIBLE);
                letter.setVisibility(GONE);
            } else {
                art.setImageDrawable(null);
                art.setVisibility(GONE);
                letter.setVisibility(VISIBLE);
                letter.setText(g.title.isEmpty() ? "?" : g.title.substring(0, 1).toUpperCase(Locale.US));
            }
        }
    }
}
