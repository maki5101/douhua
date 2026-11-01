package dev.douhua;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;

/**
 * 按 {@link GamepadPlan} 画手柄控件（手柄页 GamepadScreen、设置页"手柄"的预览和预设网格共用一份画法）。
 * 按下的样子全看手柄报文（键看位、十字键看方向、摇杆看轴、扳机看轴、N64 的 C 键看右摇杆）。
 * 颜色从传进来的主题取：面键、十字键当普通键帽，肩键、Select / Start / Home、LS / RS 当功能键；△○×□、N64 的 C 键画形状不用字符。
 * k = 一个 dp（GamepadPlan 的单位）画成多少像素：手柄页是屏幕密度，预览缩小；线宽、字号都跟着它，minText = 字最小多少像素（0 = 不写字，网格里的小图）。
 */
final class GamepadPainter {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private float k = 1, minText = 8;
    private Theme t = Theme.DEFAULT;
    private boolean borders = true;
    private GamepadSpec spec;
    private Hid.Gamepad shown;

    GamepadPainter() {
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void draw(Canvas canvas, GamepadSpec spec, GamepadPlan.Plan plan, Hid.Gamepad shown, Theme theme, boolean keyBorders, float k, float minText) {
        if (plan == null) return;
        this.spec = spec;
        this.shown = shown;
        this.t = theme;
        this.borders = keyBorders;
        this.k = k;
        this.minText = minText;
        stroke.setStrokeWidth(Math.max(1, t.borderDp * k));
        for (GamepadPlan.Control c : plan.controls) {
            switch (c.role) {
                case GamepadPlan.ROLE_FACE:
                    drawFace(canvas, c);
                    break;
                case GamepadPlan.ROLE_DPAD:
                    if (spec.arcadeStick) drawArcade(canvas, c);
                    else drawDpad(canvas, c);
                    break;
                case GamepadPlan.ROLE_STICK:
                    drawStick(canvas, c);
                    break;
                case GamepadPlan.ROLE_BUMPER:
                case GamepadPlan.ROLE_TRIGGER:
                case GamepadPlan.ROLE_MIDDLE:
                case GamepadPlan.ROLE_THUMB:
                    drawKey(canvas, c);
                    break;
                default:
                    break;   // 簇的范围不画
            }
        }
    }

    private boolean labels() {
        return minText > 0;
    }

    /** 面键：圆，普通键帽的颜色；边框跟"按键边框"开关（按下时照样画）。 */
    private void drawFace(Canvas canvas, GamepadPlan.Control c) {
        boolean down = shown.pressed(c.output);
        float cx = mid(c.dl, c.dr), cy = mid(c.dt, c.db), r = (c.dr - c.dl) * k / 2 - stroke.getStrokeWidth() / 2;
        fill.setColor(down ? t.pressed : t.key);
        canvas.drawCircle(cx, cy, r, fill);
        if (borders) {
            stroke.setColor(t.border);
            canvas.drawCircle(cx, cy, r, stroke);
        }
        if (!labels()) return;
        int color = t.text;
        if (shape(canvas, c.label, cx, cy, r * 0.42f, color)) return;
        text.setColor(color);
        text.setTextSize(Math.min(r * 0.9f, 20 * k * (c.dr - c.dl) / GamepadPlan.FACE_D));
        text.setFakeBoldText(true);
        centerText(canvas, c.label, cx, cy, r * 1.6f);
        text.setFakeBoldText(false);
    }

    /** △○×□（描边，线宽约 2dp）、N64 的 C 键（实心三角，指着那个方向）。不是这几个返回假。 */
    private boolean shape(Canvas canvas, String label, float cx, float cy, float s, int color) {
        if (label == null) return false;
        Paint p = stroke;
        float was = p.getStrokeWidth();
        p.setStrokeWidth(Math.max(1, 2.2f * k));
        p.setColor(color);
        boolean done = true;
        switch (label) {
            case GamepadSpec.TRIANGLE:
                path.reset();
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s * 0.95f, cy + s * 0.65f);
                path.lineTo(cx - s * 0.95f, cy + s * 0.65f);
                path.close();
                canvas.drawPath(path, p);
                break;
            case GamepadSpec.CIRCLE:
                canvas.drawCircle(cx, cy, s * 0.85f, p);
                break;
            case GamepadSpec.CROSS:
                canvas.drawLine(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy + s * 0.75f, p);
                canvas.drawLine(cx + s * 0.75f, cy - s * 0.75f, cx - s * 0.75f, cy + s * 0.75f, p);
                break;
            case GamepadSpec.SQUARE:
                canvas.drawRect(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy + s * 0.75f, p);
                break;
            case GamepadSpec.C_UP:
                arrow(canvas, cx, cy, s, 0, -1, color);
                break;
            case GamepadSpec.C_DOWN:
                arrow(canvas, cx, cy, s, 0, 1, color);
                break;
            case GamepadSpec.C_LEFT:
                arrow(canvas, cx, cy, s, -1, 0, color);
                break;
            case GamepadSpec.C_RIGHT:
                arrow(canvas, cx, cy, s, 1, 0, color);
                break;
            default:
                done = false;
                break;
        }
        p.setStrokeWidth(was);
        return done;
    }

    /** 实心三角，尖朝 (dx, dy)。 */
    private void arrow(Canvas canvas, float cx, float cy, float s, int dx, int dy, int color) {
        float a = s * 0.85f;
        path.reset();
        path.moveTo(cx + dx * a, cy + dy * a);
        path.lineTo(cx - dx * a * 0.6f - dy * a * 0.8f, cy - dy * a * 0.6f + dx * a * 0.8f);
        path.lineTo(cx - dx * a * 0.6f + dy * a * 0.8f, cy - dy * a * 0.6f - dx * a * 0.8f);
        path.close();
        fill.setColor(color);
        canvas.drawPath(path, fill);
    }

    /** 十字键：一横一竖两条臂，按着的方向那一两条臂画按下的颜色。 */
    private void drawDpad(Canvas canvas, GamepadPlan.Control c) {
        float l = c.dl * k, tp = c.dt * k, r = c.dr * k, b = c.db * k;
        float w = r - l, arm = w / 3, rad = arm * 0.22f;
        float cx = (l + r) / 2, cy = (tp + b) / 2;
        int hx = Hid.Gamepad.hatX(shown.hat), hy = Hid.Gamepad.hatY(shown.hat);
        fill.setColor(t.key);
        box.set(l, cy - arm / 2, r, cy + arm / 2);
        canvas.drawRoundRect(box, rad, rad, fill);
        box.set(cx - arm / 2, tp, cx + arm / 2, b);
        canvas.drawRoundRect(box, rad, rad, fill);
        fill.setColor(t.pressed);
        if (hx != 0) {
            box.set(hx < 0 ? l : cx + arm / 2, cy - arm / 2, hx < 0 ? cx - arm / 2 : r, cy + arm / 2);
            canvas.drawRoundRect(box, rad, rad, fill);
        }
        if (hy != 0) {
            box.set(cx - arm / 2, hy < 0 ? tp : cy + arm / 2, cx + arm / 2, hy < 0 ? cy - arm / 2 : b);
            canvas.drawRoundRect(box, rad, rad, fill);
        }
        if (borders) {
            stroke.setColor(t.border);
            path.reset();
            float a = arm / 2;
            path.moveTo(cx - a, tp);
            path.lineTo(cx + a, tp);
            path.lineTo(cx + a, cy - a);
            path.lineTo(r, cy - a);
            path.lineTo(r, cy + a);
            path.lineTo(cx + a, cy + a);
            path.lineTo(cx + a, b);
            path.lineTo(cx - a, b);
            path.lineTo(cx - a, cy + a);
            path.lineTo(l, cy + a);
            path.lineTo(l, cy - a);
            path.lineTo(cx - a, cy - a);
            path.close();
            canvas.drawPath(path, stroke);
        }
        // 正中一个小凹点，告诉拇指中心在哪
        fill.setColor(t.border);
        canvas.drawCircle(cx, cy, arm * 0.14f, fill);
    }

    /** 街机：十字键画成摇杆底座 + 球，球朝按着的方向偏。 */
    private void drawArcade(Canvas canvas, GamepadPlan.Control c) {
        float cx = mid(c.dl, c.dr), cy = mid(c.dt, c.db), r = (c.dr - c.dl) * k / 2 - stroke.getStrokeWidth();
        fill.setColor(t.key);
        canvas.drawCircle(cx, cy, r, fill);
        stroke.setColor(t.ring);
        canvas.drawCircle(cx, cy, r, stroke);
        // 八个方向的刻痕
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float x0 = cx + (float) Math.cos(a) * r * 0.78f, y0 = cy + (float) Math.sin(a) * r * 0.78f;
            float x1 = cx + (float) Math.cos(a) * r * 0.9f, y1 = cy + (float) Math.sin(a) * r * 0.9f;
            canvas.drawLine(x0, y0, x1, y1, stroke);
        }
        int hx = Hid.Gamepad.hatX(shown.hat), hy = Hid.Gamepad.hatY(shown.hat);
        float len = (hx != 0 && hy != 0) ? 0.7071f : 1f;
        float ball = r * 0.36f, off = r * 0.38f;
        fill.setColor(shown.hat != 0 ? t.fnPressed : t.fnKey);
        canvas.drawCircle(cx + hx * len * off, cy + hy * len * off, ball, fill);
        stroke.setColor(t.border);
        canvas.drawCircle(cx + hx * len * off, cy + hy * len * off, ball, stroke);
    }

    /** 摇杆：一圈底座 + 钮，钮按轴偏过去（服务那一路不知道浮动的按下点，底座画在区域中心）。 */
    private void drawStick(Canvas canvas, GamepadPlan.Control c) {
        float cx = mid(c.dl, c.dr), cy = mid(c.dt, c.db), r = (c.dr - c.dl) * k / 2 - stroke.getStrokeWidth();
        boolean right = c.output == 1;
        int ax = right ? shown.rx : shown.lx, ay = right ? shown.ry : shown.ly;
        boolean active = ax != 0 || ay != 0;
        fill.setColor(t.key);
        canvas.drawCircle(cx, cy, r, fill);
        stroke.setColor(active ? t.focus : t.ring);
        canvas.drawCircle(cx, cy, r, stroke);
        float knob = r * 0.42f, travel = r - knob;
        float kx = cx + travel * ax / (float) Hid.Gamepad.AXIS_MAX, ky = cy + travel * ay / (float) Hid.Gamepad.AXIS_MAX;
        fill.setColor(active ? t.fnPressed : t.fnKey);
        canvas.drawCircle(kx, ky, knob, fill);
        stroke.setColor(t.border);
        canvas.drawCircle(kx, ky, knob, stroke);
        fill.setColor(t.fnText);
        RoundPad.dots(canvas, kx, ky, knob * 0.7f, fill, k);
    }

    /** 肩键、扳机、Select / Home / Start、LS / RS：功能键的颜色；中间那排画成小长条（Game Boy 的斜着）。 */
    private void drawKey(Canvas canvas, GamepadPlan.Control c) {
        boolean down = shown.pressed(c.output);
        box.set(c.dl * k, c.dt * k, c.dr * k, c.db * k);
        float inset = stroke.getStrokeWidth() / 2;
        box.inset(inset, inset);
        boolean pill = c.role == GamepadPlan.ROLE_MIDDLE;
        float rad = pill ? box.height() / 2 : c.role == GamepadPlan.ROLE_TRIGGER ? box.height() * 0.45f : 10 * k;
        boolean slant = pill && spec.face == GamepadSpec.FACE_DIAG2;
        if (slant) {
            canvas.save();
            canvas.rotate(-GamepadPlan.DIAG_DEG, box.centerX(), box.centerY());
        }
        fill.setColor(down ? t.fnPressed : t.fnKey);
        canvas.drawRoundRect(box, rad, rad, fill);
        if (borders || c.role != GamepadPlan.ROLE_MIDDLE) {
            stroke.setColor(t.border);
            canvas.drawRoundRect(box, rad, rad, stroke);
        }
        if (labels()) {
            text.setColor(t.fnText);
            float size = c.role == GamepadPlan.ROLE_MIDDLE ? 12 : c.role == GamepadPlan.ROLE_THUMB ? 13 : 15;
            text.setTextSize(Math.min(size * k, box.height() * 0.6f));
            centerText(canvas, c.label, box.centerX(), box.centerY(), box.width() - 6 * k);
        }
        if (slant) canvas.restore();
    }

    /** 一行字居中，宽过 room 就缩字号（最小 minText 像素）。 */
    void centerText(Canvas canvas, String s, float cx, float cy, float room) {
        if (s == null || s.isEmpty()) return;
        float w = text.measureText(s);
        if (w > room && w > 0) text.setTextSize(Math.max(minText, text.getTextSize() * room / w));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text);
    }

    /** (a + b) / 2 dp → 像素。 */
    private float mid(float a, float b) {
        return (a + b) / 2 * k;
    }
}
