package dev.douhua;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实体键盘的数据从 res/raw 读进来（布局清单、Windows / Mac 两份键帽字），进程里读一次缓存着；文件几 KB，主线程上读。
 * 解析规则在纯 Java 的 FixedLegends（离线测）。读不出来（照理不会，文件是生成好打进 APK 的）时清单是空的、字表是 null，
 * 字表是 null 时 {@link #layout} 也是 null（FixedLayout.forLegends；这个布局这种模式的字表里没有那一段也一样）：
 * 选了实体键盘也照样显示豆花布局（KeyboardScreen 拿不到布局就不换），⋯ 菜单写"数据读不出来"，设置页那一节标红一行，日志里记一行。
 */
final class FixedData {
    private FixedData() {
    }

    private static final String TAG = "douhua";
    private static List<FixedLegends.Layout> catalog;
    private static FixedLegends.TableFile win, mac;

    /** 布局清单（读不出来是空列表）。 */
    static synchronized List<FixedLegends.Layout> catalog(Context c) {
        if (catalog == null) {
            try {
                catalog = FixedLegends.parseCatalog(raw(c, R.raw.kbd_catalog));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "实体键盘布局清单读不出来：" + e);
                catalog = new ArrayList<>();
            }
        }
        return catalog;
    }

    /** 这个布局在这种模式下（FixedLayout.WIN / MAC）的键帽字；没有返回 null。Mac 模式查不对调那段（见 FixedLegends 类注释）。 */
    static synchronized FixedLegends.Table table(Context c, String lang, String mode) {
        boolean m = FixedLayout.MAC.equals(mode);
        try {
            if (m && mac == null) mac = FixedLegends.parseTables(raw(c, R.raw.kbd_mac));
            if (!m && win == null) win = FixedLegends.parseTables(raw(c, R.raw.kbd_win));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "实体键盘键帽字读不出来：" + e);
            return null;
        }
        FixedLegends.TableFile f = m ? mac : win;
        return f == null ? null : f.sections.get(lang);
    }

    /** 拼这一块：右 Alt 写不写 AltGr 看 Windows 字表有没有第三层，小键盘小数点看清单；字表读不出来返回 null（见类注释）。 */
    static FixedLayout layout(Context c, Boards.Spec spec) {
        FixedLegends.Layout l = FixedLegends.find(catalog(c), spec.lang);
        return FixedLayout.forLegends(spec.shape, spec.size, spec.mode, table(c, spec.lang, spec.mode), l == null ? "." : l.kpdl);
    }

    /** 布局在界面语言里叫什么（美式、德语；以后的布局没写进 strings 的用 Locale 自己翻）。 */
    static String langName(Context c, String lang) {
        if ("us".equals(lang)) return c.getString(R.string.kbd_lang_us);
        if ("de".equals(lang)) return c.getString(R.string.kbd_lang_de);
        String tag = FixedLegends.LOCALE_TAGS.get(lang);
        return tag == null ? lang : Locale.forLanguageTag(tag).getDisplayName(c.getResources().getConfiguration().getLocales().get(0));
    }

    /** 短名字（⋯ 菜单里写的）："德语 · Mac"。 */
    static String shortName(Context c, Boards.Spec spec) {
        return c.getString(R.string.kbd_name_short, langName(c, spec.lang),
                c.getString(FixedLayout.MAC.equals(spec.mode) ? R.string.kbd_mode_mac : R.string.kbd_mode_win));
    }

    private static String raw(Context c, int id) throws IOException {
        try (InputStream in = c.getResources().openRawResource(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** res/raw 里的一份文本（开源许可页用）；读不出来返回 null。 */
    static String text(Context c, int id) {
        try {
            return raw(c, id);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "读不出 res/raw 的文本：" + e);
            return null;
        }
    }
}
