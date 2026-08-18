package dev.symbiosis.kenji;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Eden-like game properties: mods, presets, default, saves, cheats, DLC.
 * Native — no HTML.
 */
public class GamePropsActivity extends Activity {
    private String path;
    private String title;
    private String titleId;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        path = getIntent().getStringExtra("path");
        title = getIntent().getStringExtra("title");
        titleId = getIntent().getStringExtra("titleId");
        if (titleId == null || titleId.isEmpty()) {
            titleId = GameShelf.titleId(path, title);
        }
        SettingsBank.ensureBuiltins(this);
        setContentView(build());
        fill();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(LibraryActivity.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        body.setPadding(p, dp(20), p, p);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void fill() {
        body.removeAllViews();
        heading(title == null || title.isEmpty() ? "Свойства игры" : title);
        muted((titleId == null || titleId.isEmpty() ? "" : titleId + "\n") + (path == null ? "" : path));

        body.addView(btn("Запустить", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                OfficialLaunch.game(GamePropsActivity.this, path, title, titleId);
            }
        }));

        JSONObject props;
        try {
            props = new JSONObject(GameShelf.properties(this, path, title));
        } catch (Exception e) {
            props = new JSONObject();
        }

        section("Пресеты");
        muted("для всех игр и отдельно для этой. Пишутся в настройки официального Kenji.");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText name = new EditText(this);
        name.setHint("имя пресета");
        name.setHintTextColor(0xFF6A6A78);
        name.setTextColor(LibraryActivity.TEXT);
        name.setBackground(round(LibraryActivity.CARD, dp(8)));
        name.setPadding(dp(10), dp(8), dp(10), dp(8));
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(name);
        row.addView(smallBtn("всем", new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast(SettingsBank.save(GamePropsActivity.this, text(name), "", true));
                fill();
            }
        }));
        row.addView(smallBtn("этой", new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast(SettingsBank.save(GamePropsActivity.this, text(name), titleId, false));
                fill();
            }
        }));
        body.addView(row, lp(-1, -2, 0, 6, 0, 6));

        body.addView(btn("По умолчанию (Mali-G57)", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast(SettingsBank.applyDefault(GamePropsActivity.this));
            }
        }));

        JSONArray presets = props.optJSONArray("presets");
        if (presets == null || presets.length() == 0) {
            muted("сохранённых пресетов пока нет");
        } else {
            for (int i = 0; i < presets.length(); i++) {
                final JSONObject o = presets.optJSONObject(i);
                if (o == null) continue;
                final String n = o.optString("name");
                String scope = "game".equals(o.optString("scope")) ? "эта игра" : "все игры";
                LinearLayout pr = new LinearLayout(this);
                pr.setOrientation(LinearLayout.HORIZONTAL);
                pr.setPadding(0, dp(4), 0, dp(4));
                TextView lab = new TextView(this);
                lab.setText(n + " · " + scope);
                lab.setTextColor(LibraryActivity.TEXT);
                lab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                pr.addView(lab);
                pr.addView(smallBtn("вкл", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toast(SettingsBank.apply(GamePropsActivity.this, n));
                    }
                }));
                pr.addView(smallBtn("×", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toast(SettingsBank.remove(GamePropsActivity.this, n));
                        fill();
                    }
                }));
                body.addView(pr);
            }
        }

        listBlock("Моды", props.optJSONArray("mods"), "модов нет");
        body.addView(btn("Мост модов Eden → Kenji", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast(GameShelf.bridgeMods(GamePropsActivity.this));
                fill();
            }
        }));
        listBlock("Читы", props.optJSONArray("cheats"), "читов нет");
        listBlock("DLC", props.optJSONArray("dlc"), "DLC нет");
        listBlock("Сейвы", props.optJSONArray("saves"), "сейвов Kenji пока нет");
        muted(props.optString("hint"));
    }

    private void listBlock(String title, JSONArray arr, String empty) {
        section(title);
        if (arr == null || arr.length() == 0) {
            muted(empty);
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TextView t = new TextView(this);
            String line = o.optString("name");
            if (o.has("kind")) line += " · " + o.optString("kind");
            if (o.has("source")) line += " · " + o.optString("source");
            t.setText(line);
            t.setTextColor(LibraryActivity.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setPadding(0, dp(4), 0, 0);
            body.addView(t);
            String path = o.optString("path");
            if (!path.isEmpty()) {
                TextView p = new TextView(this);
                p.setText(path);
                p.setTextColor(LibraryActivity.MUTED);
                p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                body.addView(p);
            }
        }
    }

    private void heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(LibraryActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(t);
    }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(LibraryActivity.CYAN);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        body.addView(t);
    }

    private void muted(String s) {
        if (s == null || s.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(LibraryActivity.MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, 0, 0, dp(6));
        body.addView(t);
    }

    private Button btn(String label, boolean accent, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(accent ? LibraryActivity.BG : LibraryActivity.TEXT);
        b.setBackground(round(accent ? LibraryActivity.CYAN : LibraryActivity.CARD, dp(10)));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = lp(-1, -2, 0, 6, 0, 6);
        body.addView(b, lp);
        return b;
    }

    private Button smallBtn(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(LibraryActivity.TEXT);
        b.setBackground(round(LibraryActivity.CARD, dp(8)));
        b.setOnClickListener(click);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String text(EditText e) {
        String s = e.getText() == null ? "" : e.getText().toString().trim();
        return s.isEmpty() ? "пресет" : s;
    }

    private void toast(String jsonOrText) {
        String msg = jsonOrText;
        if (jsonOrText != null && jsonOrText.startsWith("{")) {
            try {
                msg = new JSONObject(jsonOrText).optString("message", jsonOrText);
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
