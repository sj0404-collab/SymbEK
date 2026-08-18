package dev.symbiosis.kenji;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Writes the same QuickSettings keys official Kenji reads.
 */
public class SettingsActivity extends Activity {
    private SharedPreferences p;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsBank.ensureBuiltins(this);
        p = PreferenceManager.getDefaultSharedPreferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(LibraryActivity.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, dp(22), pad, pad);
        scroll.addView(body);
        setContentView(scroll);
        draw();
    }

    private void draw() {
        body.removeAllViews();
        heading("Настройки");
        muted("те же ключи, что читает Kenji при запуске игры");

        section("Прошивка");
        muted(DataSeed.statusLine(this)
                + "\nKenji читает: " + new java.io.File(DataSeed.appPath(this),
                "bis/system/Contents/registered").getAbsolutePath()
                + "\nИсточник: " + (DataSeed.firmwareSource(this).isEmpty()
                ? "ещё не найден" : DataSeed.firmwareSource(this))
                + "\nКак: " + (DataSeed.firmwareMode(this).isEmpty()
                ? "ярлыки, без копии NCA" : DataSeed.firmwareMode(this)));
        rowBtn("Обновить ярлыки (без копии)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                DataSeed.ensure(SettingsActivity.this);
                toast(extract(DataSeed.bridgeFirmware(SettingsActivity.this)));
                draw();
            }
        });

        section("Графика");
        muted("масштаб рендера → resScale, который читает официальный Kenji");
        scaleRow(new double[]{0.5, 0.75, 1.0, 1.5, 2.0, 3.0});
        try {
            org.json.JSONArray presets = new org.json.JSONObject(
                    SettingsBank.listJson(this, "")).optJSONArray("items");
            if (presets != null) {
                for (int i = 0; i < presets.length(); i++) {
                    org.json.JSONObject o = presets.optJSONObject(i);
                    if (o == null) continue;
                    final String n = o.optString("name");
                    double sc = o.optDouble("resScale", 1.0);
                    boolean dock = o.optBoolean("enableDocked", false);
                    rowBtn(n + " · " + sc + "×" + (dock ? " docked" : ""),
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    toast(extract(SettingsBank.apply(SettingsActivity.this, n)));
                                    draw();
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }

        section("Ядро");
        toggle("useNce", "NCE", "на Mali-G57 лучше выкл", false);
        toggle("enablePptc", "PPTC", "кэш профилей", true);
        toggle("enableLowPowerPptc", "Low-Power PPTC", "выкл", false);
        toggle("enableJitCacheEviction", "JIT Cache Eviction", "выкл", false);
        toggle("enableFsIntegrityChecks", "FS Integrity", "выкл", false);
        toggle("ignoreMissingServices", "Ignore Missing Services", "выкл", false);
        toggle("enableDocked", "Docked", "телевизионный режим", false);
        toggle("enableShaderCache", "Shader cache", "вкл", true);

        section("Память");
        rowBtn("DRAM 4 ГиБ", new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.edit().putInt("memoryConfiguration", 0).commit();
                toast("DRAM 4 ГиБ");
            }
        });
        rowBtn("Host Unchecked", new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.edit().putInt("memoryManagerMode", 2).commit();
                toast("Memory Manager: Host Unchecked");
            }
        });

        body.addView(btn("По умолчанию для этого телефона", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String r = SettingsBank.applyDefault(SettingsActivity.this);
                try {
                    toast(new org.json.JSONObject(r).optString("message", r));
                } catch (Exception e) {
                    toast(r);
                }
                draw();
            }
        }));

        muted(DataSeed.statusLine(this) + "\n" + DataSeed.appPath(this).getAbsolutePath());
    }

    private void toggle(final String key, String label, String hint, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(LibraryActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(p.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                p.edit().putBoolean(key, on).commit();
            }
        });
        top.addView(t);
        top.addView(sw);
        row.addView(top);
        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(LibraryActivity.MUTED);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(h);
        body.addView(row);
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
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(6));
        body.addView(t);
    }

    private void muted(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(LibraryActivity.MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(6), 0, dp(10));
        body.addView(t);
    }

    private void rowBtn(String label, View.OnClickListener click) {
        body.addView(btn(label, false, click));
    }

    private Button btn(String label, boolean accent, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(accent ? LibraryActivity.BG : LibraryActivity.TEXT);
        GradientDrawable d = new GradientDrawable();
        d.setColor(accent ? LibraryActivity.CYAN : LibraryActivity.CARD);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        body.addView(b, lp);
        return b;
    }

    private void scaleRow(double[] scales) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        float current = p.getFloat("resScale", 1f);
        for (final double s : scales) {
            Button b = new Button(this);
            b.setText(s + "×");
            b.setAllCaps(false);
            b.setTextColor(Math.abs(current - s) < 0.01 ? LibraryActivity.BG : LibraryActivity.TEXT);
            GradientDrawable d = new GradientDrawable();
            d.setColor(Math.abs(current - s) < 0.01 ? LibraryActivity.CYAN : LibraryActivity.CARD);
            d.setCornerRadius(dp(8));
            b.setBackground(d);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toast(extract(SettingsBank.applyScale(SettingsActivity.this, s, false)));
                    draw();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(b, lp);
        }
        body.addView(row);
    }

    private String extract(String jsonOrText) {
        if (jsonOrText != null && jsonOrText.startsWith("{")) {
            try {
                return new org.json.JSONObject(jsonOrText).optString("message", jsonOrText);
            } catch (Exception ignored) {
            }
        }
        return jsonOrText == null ? "" : jsonOrText;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
