package dev.douhua;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** 设置页。改动即时保存，回到触控板时生效。 */
public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        density = getResources().getDisplayMetrics().density;
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(list);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        setContentView(scroll);

        GestureEngine.Config def = new GestureEngine.Config();

        list.addView(text(getString(R.string.settings_title), 22, 0xFFE8E8E8));
        header(R.string.sec_pointer);
        slider(R.string.pointer_speed, "speed", def.pointerSpeed, 0.5f, 6f, 0.1f, "%.1f");
        slider(R.string.acceleration, "accel", def.acceleration, 0f, 3f, 0.1f, "%.1f");
        list.addView(text(getString(R.string.precision), 16, 0xFFE8E8E8));
        levels(Prefs.HAPTIC_LABELS, prefs.getInt("precision", 2), level -> prefs.putInt("precision", level));

        header(R.string.sec_tap);
        toggle(R.string.tap_drag, "tap_drag", def.tapToDrag);
        sliderInt(R.string.drag_window, "drag_ms", def.dragWindowMs, 120, 400, 10);
        toggle(R.string.long_press, "long_press", def.longPressDrag);
        sliderInt(R.string.long_press_ms, "long_press_ms", def.longPressMs, 250, 1000, 50);

        header(R.string.sec_scroll);
        scrollSpeed(def.scrollStepDp);
        toggle(R.string.natural_scroll, "natural", def.naturalScroll);
        toggle(R.string.pinch_zoom, "pinch", def.pinchZoom);
        toggle(R.string.edge_v, "edge", def.edgeScroll);
        toggle(R.string.edge_h, "edge_h", def.edgeScrollHorizontal);
        sliderInt(R.string.edge_width, "edge_width_i", Math.round(def.edgeWidthDp), 12, 64, 2);
        choice(R.string.ticks, "ticks", def.scrollTicks,
                new int[]{R.string.ticks_off, R.string.ticks_edge, R.string.ticks_all});
        list.addView(text(getString(R.string.tick_strength), 16, 0xFFE8E8E8));
        levels(Prefs.TICK_STRENGTH_LABELS, prefs.tickStrength(), strength -> {
            prefs.setTickStrength(strength);
            Haptics h = new Haptics(this);   // 选了就连震几下，像滚了几格
            h.setLevel(prefs.haptic());
            h.setTickStrength(strength);
            for (int i = 0; i < 4; i++) list.postDelayed(() -> h.play(GestureEngine.HAPTIC_TICK), i * 70L);
        });

        header(R.string.sec_haptics);
        hapticChoice();

        header(R.string.sec_gestures);
        for (int i = 0; i < Prefs.GESTURE_KEYS.length; i++) {
            final int index = i;
            actionRow(getString(Prefs.GESTURE_LABELS[i]), () -> prefs.gesture(index), id -> prefs.setGesture(index, id));
        }

        header(R.string.sec_strip);
        toggle(R.string.strip_hidden, "strip_hidden", false);
        for (int i = 0; i < Prefs.SLOT_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.slot(index), id -> prefs.setSlot(index, id));
        }

        header(R.string.sec_corners);
        for (int i = 0; i < Prefs.CORNER_COUNT; i++) {
            final int index = i;
            actionRow(getString(Prefs.CORNER_LABELS[i]), () -> prefs.corner(index), id -> prefs.setCorner(index, id));
        }

        header(R.string.sec_kb_row);
        for (int i = 0; i < Prefs.KEYBOARD_ROW_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.keyboardRow(index), id -> prefs.setKeyboardRow(index, id));
        }

        header(0);
        TextView reset = text(getString(R.string.reset_all), 16, 0xFFE07A5F);
        reset.setPadding(0, dp(12), 0, dp(12));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.reset, (d, w) -> {
                    prefs.resetAll();
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        list.addView(reset);
    }

    // ═══════════ 各种行 ═══════════

    /** titleRes = 0：只留一段空白。 */
    private void header(int titleRes) {
        TextView t = text(titleRes == 0 ? "" : getString(titleRes), 13, 0xFF9AA0A6);
        t.setPadding(0, dp(24), 0, dp(6));
        list.addView(t);
    }

    private void slider(int labelRes, String key, float def, float min, float max, float step, String fmt) {
        String label = getString(labelRes);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        int steps = Math.round((max - min) / step);
        bar.setMax(steps);
        float value = prefs.getFloat(key, def);
        bar.setProgress(Math.round((value - min) / step));
        title.setText(getString(R.string.value_format, label, String.format(Locale.ROOT, fmt, value)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            float v = min + p * step;
            prefs.putFloat(key, v);
            title.setText(getString(R.string.value_format, label, String.format(Locale.ROOT, fmt, v)));
        }));
        list.addView(title);
        list.addView(bar);
    }

    private void sliderInt(int labelRes, String key, int def, int min, int max, int step) {
        String label = getString(labelRes);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int value = prefs.getInt(key, def);
        bar.setProgress((value - min) / step);
        title.setText(getString(R.string.value_format, label, String.valueOf(value)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int v = min + p * step;
            prefs.putInt(key, v);
            title.setText(getString(R.string.value_format, label, String.valueOf(v)));
        }));
        list.addView(title);
        list.addView(bar);
    }

    /** 滚动速度 1–10 档，换算成"滚一格要划多少 dp"（档位越高，划得越少就滚一格）。 */
    private void scrollSpeed(float defStep) {
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax(9);
        int level = Math.round(100f / prefs.getFloat("scroll_step", defStep));
        level = Math.max(1, Math.min(10, level));
        bar.setProgress(level - 1);
        title.setText(getString(R.string.value_format, getString(R.string.scroll_speed), String.valueOf(level)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int l = p + 1;
            prefs.putFloat("scroll_step", 100f / l);
            title.setText(getString(R.string.value_format, getString(R.string.scroll_speed), String.valueOf(l)));
        }));
        list.addView(title);
        list.addView(bar);
    }

    private void toggle(int labelRes, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(labelRes);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.getBool(key, def));
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, checked) -> prefs.putBool(key, checked));
        list.addView(s);
    }

    /** 几选一，存成下标。 */
    private void choice(int labelRes, String key, int def, int[] labels) {
        list.addView(text(getString(labelRes), 16, 0xFFE8E8E8));
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int current = prefs.getInt(key, def);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == current) b.setChecked(true);
            final int value = i;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) prefs.putInt(key, value);
            });
        }
        list.addView(g);
    }

    private void hapticChoice() {
        levels(Prefs.HAPTIC_LABELS, prefs.haptic(), level -> {
            prefs.setHaptic(level);
            Haptics h = new Haptics(this);   // 选了就震一下，直接感受强度
            h.setLevel(level);
            h.play(GestureEngine.HAPTIC_CLICK);
        });
    }

    /** 横排的几档（关 / 小 / 中 / 大 这类），选中时回调下标。 */
    private void levels(int[] labels, int current, IntConsumer onPick) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setId(View.generateViewId());
            g.addView(b, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i == current) b.setChecked(true);
            final int value = i;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) onPick.accept(value);
            });
        }
        list.addView(g);
    }

    interface Getter {
        String get();
    }

    interface Setter {
        void set(String actionId);
    }

    /**
     * 选一个动作。所有能自定义的位置（按钮条、角上按钮、三指四指手势、键盘最下面一排）都用这一份清单，
     * 最后一项是"自定义组合键"。
     */
    private void actionRow(String label, Getter getter, Setter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView name = text(label, 16, 0xFFE8E8E8);
        TextView value = text(Actions.get(getter.get()).label(this), 15, 0xFF8AB4F8);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        Setter save = id -> {
            setter.set(id);
            value.setText(Actions.get(id).label(this));
        };
        row.setOnClickListener(v -> {
            List<Actions.Action> all = Actions.all();
            String[] labels = new String[all.size() + 1];
            int checked = -1;
            String current = getter.get();
            for (int i = 0; i < all.size(); i++) {
                labels[i] = all.get(i).label(this);
                if (all.get(i).id.equals(current)) checked = i;
            }
            labels[all.size()] = getString(R.string.custom_combo);
            if (checked < 0) checked = all.size();   // 当前是自定义组合键
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        d.dismiss();
                        if (which == all.size()) customCombo(label, save);
                        else save.set(all.get(which).id);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        list.addView(row);
    }

    /** 自己勾修饰键、挑一个键；按钮上显示的字不填就用 "Ctrl+Shift+T" 这样的名字。 */
    private void customCombo(String title, Setter save) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        LinearLayout mods = new LinearLayout(this);
        String[] modNames = {"Ctrl", "Shift", "Alt", "Win"};
        CheckBox[] checks = new CheckBox[4];
        for (int i = 0; i < 4; i++) {
            checks[i] = new CheckBox(this);
            checks[i].setText(modNames[i]);
            mods.addView(checks[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(mods);
        Spinner keys = new Spinner(this);
        keys.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Combos.KEY_LABELS));
        box.addView(keys);
        EditText name = new EditText(this);
        name.setHint(R.string.custom_combo_hint);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.custom_combo_title, title))
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int key = Combos.combine(Combos.KEY_USAGES[keys.getSelectedItemPosition()],
                            checks[0].isChecked(), checks[1].isChecked(), checks[2].isChecked(), checks[3].isChecked());
                    String text = name.getText().toString().trim();
                    save.set(Actions.combo(key, text.isEmpty() ? Combos.describe(key) : text));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    interface IntCallback {
        void on(int progress);
    }

    /** 只关心拖动中的数值变化。 */
    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final IntCallback cb;

        SimpleSeek(IntCallback cb) {
            this.cb = cb;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) cb.on(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
