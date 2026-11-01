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
 * 字表是 null 时 {@link #ladder} 是空的（FixedLayout.forEntry；这个布局这种模式的字表里没有那一段也一样，比如拉美的 Mac 模式）：
 * 选了实体键盘也照样显示豆花布局（KeyboardScreen 拿不到布局就不换），⋯ 菜单写"数据读不出来"（没有这种模式的数据时写"没有 Mac 模式的数据"），
 * 设置页那一节标红一行，日志里记一行。
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

    /**
     * 拼逐级缩的几级（FixedLayout.ladder：选的尺寸往下，全尺寸 → TKL → 75% → 笔记本……）：每级一块，右 Alt 写不写 AltGr 看 Windows 字表有没有第三层，
     * 小键盘小数点、ABNT2 小键盘多的那个键、Mac 模式的 Caps 键帽（拼音写 中/英）看清单（FixedLayout.forEntry）；
     * 字表读不出来、这个布局这种模式没有数据（拉美的 Mac 模式）、尺寸配不上（苹果紧凑 × Windows 模式 / ABNT2）返回空列表（见类注释）。
     */
    static List<FixedLayout> ladder(Context c, Boards.Spec spec) {
        FixedLegends.Layout l = FixedLegends.find(catalog(c), spec.lang);
        FixedLegends.Table t = table(c, spec.lang, spec.mode);
        List<FixedLayout> out = new ArrayList<>();
        for (String size : FixedLayout.ladder(spec.size)) {
            FixedLayout f = FixedLayout.forEntry(spec.shape, size, spec.mode, t, l);
            if (f == null) return new ArrayList<>();
            out.add(f);
        }
        return out;
    }

    /** 尺寸的短名字（⋯ 菜单里"全尺寸 → TKL"那种）："75%""65%""TKL"不翻译，全尺寸、笔记本、苹果紧凑翻。 */
    static String sizeName(Context c, String size) {
        switch (FixedLayout.validSize(size)) {
            case FixedLayout.FULL:
                return c.getString(R.string.kbd_size_short_full);
            case FixedLayout.LAPTOP:
                return c.getString(R.string.kbd_size_short_laptop);
            case FixedLayout.P75:
                return c.getString(R.string.kbd_size_short_75);
            case FixedLayout.P65:
                return c.getString(R.string.kbd_size_short_65);
            case FixedLayout.APPLE:
                return c.getString(R.string.kbd_size_short_apple);
            default:
                return c.getString(R.string.kbd_size_short_tkl);
        }
    }

    /**
     * 布局在界面语言里叫什么（规则在纯 Java 的 Boards.langName：同一种语言有几个地区才带地区，"法语（加拿大）""瑞典语 · 旧版"）；
     * 清单里没有这个布局就写 id。
     */
    static String langName(Context c, String lang) {
        List<FixedLegends.Layout> cat = catalog(c);
        FixedLegends.Layout l = FixedLegends.find(cat, lang);
        if (l == null) return lang;
        return Boards.langName(cat, l, uiLocale(c), w -> variantWord(c, w), c.getString(R.string.kbd_name_short));
    }

    /** 设置页的语言列表（Boards.langChoices：按界面语言的名字排，mode 下没有数据的、size 配不上形状的灰掉）。 */
    static List<Boards.LangChoice> langChoices(Context c, String mode, String size) {
        return Boards.langChoices(catalog(c), mode, size, uiLocale(c), w -> variantWord(c, w), c.getString(R.string.kbd_name_short));
    }

    static Locale uiLocale(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0);
    }

    /** 区分词：小写的翻译（strings.xml 的 kbd_var_*，加新词要在这里和九个 strings.xml 一起加）；认不出返回 null（照原样写）。 */
    static String variantWord(Context c, String word) {
        switch (word) {
            case "intl":
                return c.getString(R.string.kbd_var_intl);
            case "legacy":
                return c.getString(R.string.kbd_var_legacy);
            case "pinyin":
                return c.getString(R.string.kbd_var_pinyin);
            default:
                return null;
        }
    }

    /** 短名字（⋯ 菜单里写的）："德语（德国） · Mac"。 */
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
