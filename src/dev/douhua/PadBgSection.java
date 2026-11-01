package dev.douhua;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * 设置 → 外观里"触控板背景图"一节（SettingsActivity 在"按键边框"和主题说明之后建它，标题由 SettingsActivity 加）：
 * 三块预览（宽扁 16:9 / 窄高 3:5 / 很扁 7:2，各画当前主题的触控板 + 图 + 一句假状态字，"形状一直变"时图怎么跟着变一眼看得到）、
 * 从相册 / 从文件选、移除、状态行；设了图以后：显示方式、焦点（缩略图上点 / 拖十字）、图片大小（只在平铺 / 原大小时）、不透明度、
 * "宽的和高的触控板分开设"（开了是宽、高各一组）、一段说明。
 * 改动马上存进 Prefs、马上在预览里变，主界面回去时才换（MainActivity.applyPrefs → PadImage.apply）。预览和主界面用同一个画法（PadImage.drawImage）。
 * 选图走系统的照片选择器（ACTION_PICK_IMAGES）/ 文件选择器（ACTION_OPEN_DOCUMENT），都不用申请权限；拷贝、验证在后台（PadImage.startImport）。
 */
final class PadBgSection {
    static final int REQ_PHOTO = 41, REQ_FILE = 42;
    private static final String TAG = "douhua";
    private static final int TEXT = 0xFFE8E8E8, HINT = 0xFF9AA0A6, LINK = 0xFF8AB4F8, WARN = 0xFFE8B04A;

    private final Activity a;
    private final Prefs prefs;
    private final float density;
    /** 这一节的容器：导入完、移除、开关分形状时整节重建（预览的小图留着）。 */
    private LinearLayout box;
    private Previews previews;
    private TextView status;
    private TextView pickPhoto, pickFile, remove;
    /** 正在编辑的设置（拖焦点时先改这里、抬手才存）；预览照它画。 */
    private PadFit.Settings cur;
    /** 设置页自己解的小图（长边 ≤ PadImage.PREVIEW_SIDE）和它是哪个文件、原图多大。 */
    private Bitmap thumb;
    private String thumbFile;
    private int srcW, srcH;
    private boolean loadingThumb;
    /** 上次导入的结果（PadImage.OK / ERR_*）：错误留着显示，直到再选一张或者移除。 */
    private int error = PadImage.OK;
    private final PadImage.ImportListener importListener = this::onImported;

    PadBgSection(Activity a, Prefs prefs) {
        this.a = a;
        this.prefs = prefs;
        this.density = a.getResources().getDisplayMetrics().density;
    }

    /** 建到 list 里（SettingsActivity.build 每次整页重建都调）。 */
    void build(LinearLayout list) {
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        list.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();
    }

    void start() {
        PadImage.setImportListener(importListener);
        // 不在前台时导入可能已经做完了（结果写进了 Prefs，没人收到的结果码留在 PadImage）：失败的提示照样显示
        int missed = PadImage.takeMissedResult();
        if (missed != PadImage.NO_RESULT) error = missed;
        if (box == null) return;
        // 文件变了整节重建，没变也刷新一下状态行
        if (!prefs.padBgFile().equals(cur.file)) rebuild();
        else updateStatus();
    }

    void stop() {
        PadImage.removeImportListener(importListener);
    }

    /** 主题换了：预览重画（触控板底色、边框、字跟主题）。 */
    void themeChanged() {
        if (previews != null) previews.invalidate();
    }

    /** "恢复默认设置"之后（Prefs 已经清了）：删图片文件，忘掉小图和错误。 */
    void reset() {
        PadImage.deleteAll(a);
        thumb = null;
        thumbFile = null;
        error = PadImage.OK;
    }

    /** SettingsActivity.onActivityResult 转过来：是这一节的请求就处理、返回 true。 */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PHOTO && requestCode != REQ_FILE) return false;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true;   // 取消了
        error = PadImage.OK;
        PadImage.startImport(a, uri);
        if (box != null) updateStatus();
        return true;
    }

    // ═══════════ 建 ═══════════

    private void rebuild() {
        box.removeAllViews();
        cur = prefs.padBg();
        previews = new Previews(a);
        box.addView(previews, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        pickPhoto = link(R.string.pad_bg_pick_photo, v -> pick(true));
        pickFile = link(R.string.pad_bg_pick_file, v -> pick(false));
        row.addView(pickPhoto, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(pickFile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        remove = link(R.string.pad_bg_remove, v -> removeImage());
        box.addView(remove);
        status = text("", 14, HINT);
        status.setPadding(0, 0, 0, dp(6));
        box.addView(status);

        if (cur.hasImage()) {
            if (!cur.split) group(box, false);
            slider(box, R.string.pad_bg_opacity, cur.alphaPct, PadFit.ALPHA_MIN, PadFit.ALPHA_MAX, PadFit.ALPHA_STEP, v -> {
                prefs.setPadBgAlpha(v);
                cur = cur.withAlpha(v);
                previews.invalidate();
            });
            Switch split = new Switch(a);
            split.setText(R.string.pad_bg_per_shape);
            split.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            split.setChecked(cur.split);
            split.setPadding(0, dp(8), 0, dp(8));
            split.setOnCheckedChangeListener((b, on) -> {
                prefs.setPadBgSplit(on);
                box.post(this::rebuild);   // 等这次点击处理完再整节重建
            });
            box.addView(split);
            if (cur.split) {
                box.addView(subTitle(R.string.pad_bg_wide));
                group(box, false);
                box.addView(subTitle(R.string.pad_bg_tall));
                group(box, true);
            }
        }
        TextView note = text(a.getString(R.string.pad_bg_note), 13, 0xFF9A9A9A);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);
        updateStatus();
        ensureThumb();
    }

    /** 一组"怎么摆"：显示方式、焦点（拉伸时藏起来）、图片大小（只在平铺 / 原大小时）。tall = 高的触控板那套。 */
    private void group(LinearLayout to, boolean tall) {
        to.addView(text(a.getString(R.string.pad_bg_fit), 16, TEXT));
        RadioGroup g = new RadioGroup(a);
        g.setOrientation(RadioGroup.VERTICAL);
        int[] labels = {R.string.pad_bg_fit_cover, R.string.pad_bg_fit_contain, R.string.pad_bg_fit_stretch,
                R.string.pad_bg_fit_tile, R.string.pad_bg_fit_original};   // 下标就是 PadFit.FIT_*
        to.addView(g);
        LinearLayout focusBox = new LinearLayout(a);
        focusBox.setOrientation(LinearLayout.VERTICAL);
        focusBox.addView(text(a.getString(R.string.pad_bg_focus), 16, TEXT));
        FocusPicker picker = new FocusPicker(a, tall);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        pp.topMargin = dp(6);
        pp.bottomMargin = dp(6);
        focusBox.addView(picker, pp);
        to.addView(focusBox);
        LinearLayout sizeBox = new LinearLayout(a);
        sizeBox.setOrientation(LinearLayout.VERTICAL);
        slider(sizeBox, R.string.pad_bg_size, look(tall).scalePct, PadFit.SCALE_MIN, PadFit.SCALE_MAX, PadFit.SCALE_STEP, v -> {
            setLook(tall, look(tall).withScale(v));
            prefs.setPadBgLook(tall, look(tall));
        });
        to.addView(sizeBox);
        Runnable shown = () -> {
            focusBox.setVisibility(look(tall).usesFocus() ? View.VISIBLE : View.GONE);
            sizeBox.setVisibility(look(tall).usesScale() ? View.VISIBLE : View.GONE);
        };
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == look(tall).fit) b.setChecked(true);
            final int fit = i;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked) return;
                setLook(tall, look(tall).withFit(fit));
                prefs.setPadBgLook(tall, look(tall));
                shown.run();
            });
        }
        shown.run();
    }

    private PadFit.Look look(boolean tall) {
        return tall ? cur.tall : cur.wide;
    }

    /** 改正在编辑的那套（预览马上重画）；存不存由调用方定（拖焦点时抬手才存）。 */
    private void setLook(boolean tall, PadFit.Look l) {
        cur = cur.withLook(tall, l);
        if (previews != null) previews.invalidate();
    }

    /** 一个百分比滑块：标题"名字：数值%"，拖的时候回调（同 SettingsActivity.sliderInt，多了回调）。 */
    private void slider(LinearLayout to, int labelRes, int value, int min, int max, int step, IntConsumer onChange) {
        String label = a.getString(labelRes);
        TextView title = text(a.getString(R.string.value_format, label, value + "%"), 16, TEXT);
        SeekBar bar = new SeekBar(a);
        bar.setMax((max - min) / step);
        bar.setProgress((value - min) / step);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                int v = min + p * step;
                title.setText(a.getString(R.string.value_format, label, v + "%"));
                onChange.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        to.addView(title);
        to.addView(bar);
    }

    private TextView subTitle(int res) {
        TextView t = text(a.getString(res), 14, HINT);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView link(int res, View.OnClickListener l) {
        TextView t = text(a.getString(res), 16, LINK);
        t.setPadding(0, dp(12), dp(12), dp(12));
        t.setOnClickListener(l);
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ═══════════ 选图、移除、状态 ═══════════

    /** photo = 系统照片选择器（只列媒体库）；否则文件选择器（Download、文件管理器里的 PNG、贴纸，SVG 也会列出来，选了由解码报错）。 */
    private void pick(boolean photo) {
        if (PadImage.importing()) return;
        Intent i = photo
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            a.startActivityForResult(i, photo ? REQ_PHOTO : REQ_FILE);
        } catch (ActivityNotFoundException e) {
            if (photo) pick(false);   // 没有照片选择器：退到文件选择器
            else Log.w(TAG, "背景图：没有能选文件的界面");
        }
    }

    private void removeImage() {
        if (PadImage.importing()) return;
        prefs.clearPadBgFile();
        PadImage.deleteAll(a);
        thumb = null;
        thumbFile = null;
        error = PadImage.OK;
        rebuild();
    }

    private void onImported(int result, String file, Bitmap preview, int w, int h) {
        error = result;
        if (result == PadImage.OK && preview != null) {
            thumb = preview;
            thumbFile = file;
            srcW = w;
            srcH = h;
        }
        if (box != null) rebuild();
    }

    /** 状态行和按钮：读取中 / 读不了 / 太大 / 没有设图片 / 动图只显示第一帧；读取中两个"选"和"移除"都不让点。 */
    private void updateStatus() {
        boolean busy = PadImage.importing();
        boolean has = prefs.padBg().hasImage();
        String s;
        int color = HINT;
        if (busy) {
            s = a.getString(R.string.pad_bg_loading);
        } else if (error == PadImage.ERR_TOO_BIG) {
            s = a.getString(R.string.pad_bg_too_big, PadFit.MAX_FILE_MB);
            color = WARN;
        } else if (error == PadImage.ERR_DECODE) {
            s = a.getString(R.string.pad_bg_error);
            color = WARN;
        } else if (!has) {
            s = a.getString(R.string.pad_bg_none);
        } else if (prefs.padBg().animated) {
            s = a.getString(R.string.pad_bg_animated);
        } else {
            s = "";
        }
        status.setText(s);
        status.setTextColor(color);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        pickPhoto.setEnabled(!busy);
        pickFile.setEnabled(!busy);
        pickPhoto.setAlpha(busy ? 0.4f : 1f);
        pickFile.setAlpha(busy ? 0.4f : 1f);
        remove.setVisibility(has && !busy ? View.VISIBLE : View.GONE);
    }

    /** 设了图、手上没有它的小图：后台解一张（打开设置页、转屏重建、换机搬过来的）。 */
    private void ensureThumb() {
        String file = prefs.padBgFile();
        if (file.isEmpty() || file.equals(thumbFile) || loadingThumb) return;
        loadingThumb = true;
        PadImage.loadPreview(a, file, (f, bmp, w, h) -> {
            loadingThumb = false;
            if (!f.equals(prefs.padBgFile())) {
                ensureThumb();   // 解的期间又换了图
                return;
            }
            thumb = bmp;
            thumbFile = f;
            srcW = w;
            srcH = h;
            if (box != null) rebuild();   // 焦点那张缩略图、预览都要重画
        });
    }

    // ═══════════ 预览：三种形状的触控板 ═══════════

    /**
     * 一张页面底色的卡片上三块触控板：第一行宽扁 16:9 + 窄高 3:5（一样高），第二行很扁 7:2（占满宽）。
     * 原大小 / 平铺按比例缩小：宽扁那块当作和手机短边一样宽的触控板（三块用同一个比例，相对大小是对的）。
     * 边框粗细照真的（缩了就看不见了），圆角、状态字按比例缩、有下限。
     */
    private final class Previews extends View {
        private final PadImage.Brush[] brushes = {new PadImage.Brush(), new PadImage.Brush(), new PadImage.Brush()};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), clip = new RectF(), inner = new RectF(), chip = new RectF();

        Previews(Context c) {
            super(c);
            label.setTextAlign(Paint.Align.CENTER);
        }

        private float pad() {
            return dp(10);
        }

        private float gap() {
            return dp(8);
        }

        /** 第一行的高：两块一样高、连缝铺满宽。 */
        private float rowHeight(int w) {
            return (w - 2 * pad() - gap()) / (16f / 9 + 3f / 5);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            float h1 = rowHeight(w), h2 = (w - 2 * pad()) / 3.5f;
            setMeasuredDimension(w, Math.round(2 * pad() + h1 + gap() + h2));
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme t = Theme.byId(prefs.theme());
            int w = getWidth();
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(t.bg);
            r.set(0, 0, w, getHeight());
            c.drawRoundRect(r, dp(12), dp(12), paint);
            float p = pad(), g = gap(), h1 = rowHeight(w);
            float wideW = h1 * 16 / 9;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            double unit = wideW / Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            tile(c, t, p, p, p + wideW, p + h1, unit, brushes[0]);
            tile(c, t, w - p - h1 * 3 / 5, p, w - p, p + h1, unit, brushes[1]);
            tile(c, t, p, p + h1 + g, w - p, getHeight() - p, unit, brushes[2]);
        }

        /** 一块小触控板：底色 → 图 → 假状态字（有图时垫底）→ 边框，同 TrackpadView。 */
        private void tile(Canvas c, Theme t, float l, float top, float rt, float b, double unit, PadImage.Brush brush) {
            float k = (float) Math.max(0.35, Math.min(1, unit));
            float stroke = t.borderDp * density;
            float radius = Math.max(dp(6), 18 * density * k);
            clip.set(l + stroke / 2, top + stroke / 2, rt - stroke / 2, b - stroke / 2);
            inner.set(l + stroke, top + stroke, rt - stroke, b - stroke);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(t.padFill);
            c.drawRoundRect(clip, radius, radius, paint);
            boolean onImage = thumb != null && cur.hasImage() && PadImage.drawImage(c, clip, radius, inner, thumb, srcW, srcH,
                    cur.pick(inner.width(), inner.height()), cur.alphaPct, unit, brush);
            float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
            label.setTextSize(Math.max(9 * sp, 12 * sp * k));
            label.setColor(t.status);
            String s = a.getString(R.string.theme_preview_pad);
            float room = Math.max(0, (rt - l) - 2 * radius);
            CharSequence shown = label.measureText(s) > room ? TextUtils.ellipsize(s, label, room, TextUtils.TruncateAt.END) : s;
            float base = b - Math.max(dp(8), 14 * density * k);
            if (shown.length() > 0) {
                if (onImage) {
                    Paint.FontMetrics fm = label.getFontMetrics();
                    float half = label.measureText(shown, 0, shown.length()) / 2 + 8 * density * k;
                    float cx = (l + rt) / 2;
                    chip.set(cx - half, base + fm.ascent - 3 * density * k, cx + half, base + fm.descent + 3 * density * k);
                    paint.setColor(t.padFill);
                    c.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, paint);
                }
                c.drawText(shown, 0, shown.length(), (l + rt) / 2, base, label);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(t.padBorder);
            c.drawRoundRect(clip, radius, radius, paint);
        }
    }

    // ═══════════ 焦点：整张缩略图 + 十字 ═══════════

    /**
     * 整张图（完整显示，居中）上叠一个十字，点或拖十字改焦点；离 0 / 0.5 / 1 近的吸过去（PadFit.snap，等于 3×3 对齐）。
     * 拖的时候只改正在编辑的那套（预览跟着变），抬手才存。抢住父级的滑动，免得往下拖时整页跟着滚。
     */
    private final class FocusPicker extends View {
        private final boolean tall;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF img = new RectF();

        FocusPicker(Context c, boolean tall) {
            super(c);
            this.tall = tall;
        }

        /** 缩略图画在哪（完整显示、居中，四周留 4dp）；还没小图时是整块。 */
        private void layoutImage() {
            float m = dp(4), w = getWidth() - 2 * m, h = getHeight() - 2 * m;
            if (thumb == null || w <= 0 || h <= 0) {
                img.set(m, m, m + Math.max(0, w), m + Math.max(0, h));
                return;
            }
            float s = Math.min(w / thumb.getWidth(), h / thumb.getHeight());
            float iw = thumb.getWidth() * s, ih = thumb.getHeight() * s;
            float l = m + (w - iw) / 2, t = m + (h - ih) / 2;
            img.set(l, t, l + iw, t + ih);
        }

        @Override
        protected void onDraw(Canvas c) {
            layoutImage();
            if (thumb != null) c.drawBitmap(thumb, null, img, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, dp(1)));
            paint.setColor(0xFF5F6368);
            c.drawRect(img, paint);
            if (thumb == null) {
                paint.setStyle(Paint.Style.FILL);
                return;
            }
            PadFit.Look l = look(tall);
            float x = img.left + l.fx * img.width(), y = img.top + l.fy * img.height();
            // 十字：黑边白芯，什么颜色的图上都看得见
            for (int pass = 0; pass < 2; pass++) {
                paint.setColor(pass == 0 ? 0xC0000000 : 0xFFFFFFFF);
                paint.setStrokeWidth(dp(pass == 0 ? 4 : 2));
                float arm = dp(16), ring = dp(9);
                c.drawCircle(x, y, ring, paint);
                c.drawLine(x - arm, y, x - ring, y, paint);
                c.drawLine(x + ring, y, x + arm, y, paint);
                c.drawLine(x, y - arm, x, y - ring, paint);
                c.drawLine(x, y + ring, x, y + arm, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (thumb == null || img.width() <= 0 || img.height() <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    float fx = PadFit.snap((e.getX() - img.left) / img.width());
                    float fy = PadFit.snap((e.getY() - img.top) / img.height());
                    setLook(tall, look(tall).withFocus(fx, fy));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs.setPadBgLook(tall, look(tall));
                    return true;
                default:
                    return true;
            }
        }
    }
}
