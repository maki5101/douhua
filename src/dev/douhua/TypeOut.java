package dev.douhua;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 贴字面板的"打出来"（2026-11-02，方案 design-typeout）：在手机上编辑好一段字，按设置 → 键盘里实体键盘的语言 + 模式的字表找每个字在哪个键，
 * 一键一键发出去（发的节拍、停在 {@link TypeOutSender}）。这里是反查：字 → 一串键。纯 Java（离线测，TypeOutTest）。
 *
 * 反查表（{@link #build}）：
 * - 字表（FixedLegends.Table：Windows 模式 kbd_win、Mac 模式 kbd_mac 不对调那段——和实体键盘画键帽同一份）里每个键四层：普通 / Shift / 第三层 / 第三层 + Shift。
 *   第三层 Mac 模式按左 Option（左 Alt，0x04），Windows 模式按右 Alt（AltGr，0x40）——只有字表有第三层的布局才用（同 FixedLayout.altGr：美式没有，右 Alt 只是 Alt）。
 * - 一个字几个键都出得来时：普通层 > Shift > 第三层 > 第三层 + Shift；同一层里挑用法码小的——ISO / ABNT2 才有的 0x64、0x87 码最大，
 *   自然排在每种形状都有的键后面（美式 Windows 的 \ 发 0x31 不发 0x64）。字表本来就只有主键区（没有小键盘：小键盘受 NumLock 影响，不用）。
 * - 死键：字表里标了死键的格子按下后和下一个字拼。直接没有这个字的键时，查这个布局的死键组合（FixedLegends.DeadFile：CLDR transforms，主机自己的组合表）：
 *   "死键的字 + 后面那个字 → 这个字"，后面那个字要能直接打（不是死键）或者是空格；先按死键、再按那个键；几种拼法都行时先比死键、再比后面那个键（同上面的先后）。
 *   不拿 Unicode 分解去猜（方案原来这么写：德语 Windows 的 ´ + c 拼不出 ć，主机打出"´c"；波兰程序员的 ~ + a 是 ą 不是 ã，见 docs/decisions.md）。
 *   死键 + 空格同理：拼出来的多半是死键自己那个字（单独一个 ´），个别布局是别的（罗马尼亚 Windows ˙ + 空格 = ·），照组合表。主机真怎么拼是主机的事（照真键盘发），要实测。
 * - 空格 = 0x2C（不查表）。
 *
 * 一段字（{@link #plan}）：先按 NFC 规范化（e + U+0301 先拼成 é 再查）；换行 = 回车（0x28），Tab = 0x2B，\r 忽略（\r\n 只算一个回车）；
 * 别的控制字符、中文、emoji、这个布局没有的字打不出：数出来、跳过（一个 emoji 连着的 ZWJ、变体选择符、肤色，带着的组合附加符号，算一个，{@link #attaches}）。
 * 不补回车：内容不以换行结尾就不按回车。
 *
 * 每一步是一个键 id（Proto.REGION_KEY 的格式：用法码 | 自带修饰键 << 8），按下 + 松开，经 MainActivity.onLocalKey 发（蓝牙模式走本地分流、桌面模式走 CMD_HID_KEY），
 * KeyHolds 当普通键（先按修饰键再按键、先松键再松修饰键，TouchRouter.pressKey / releaseKey）。不碰协议、服务、描述符。
 */
final class TypeOut {
    private TypeOut() {
    }

    static final int ENTER = 0x28, TAB = 0x2B, SPACE = 0x2C;
    /** 第三层的修饰键：Mac 模式左 Option（左 Alt）、Windows 模式右 Alt（AltGr = 左 Alt << 4）。 */
    static final int MAC_THIRD = Hid.MOD_ALT, WIN_THIRD = Hid.MOD_ALT << 4;

    /** 这个字表在这种模式下第三层按哪个修饰键：Mac 模式左 Option；Windows 模式有第三层的字才是右 Alt（AltGr），没有（美式）返回 0 = 不用第三层。 */
    static int thirdModifier(FixedLegends.Table t, String mode) {
        if (FixedLayout.MAC.equals(FixedLayout.validMode(mode))) return MAC_THIRD;
        return t != null && t.hasThird() ? WIN_THIRD : 0;
    }

    /** 一个布局在一种模式下的反查表：码位 → 要按的键（一个；死键拼的是两个：死键 + 后面那个键）。 */
    static final class Keys {
        private final Map<Integer, int[]> keys;
        /** 第三层的修饰键（0 = 这个布局不用第三层）。 */
        final int third;

        Keys(Map<Integer, int[]> keys, int third) {
            this.keys = keys;
            this.third = third;
        }

        /** 这个字（码位）要按的键；打不出返回 null。换行、Tab 不在这里（{@link #plan} 管）。返回的数组别改。 */
        int[] keysFor(int cp) {
            return keys.get(cp);
        }

        /** 能打的字有哪些（测试用）。 */
        Set<Integer> chars() {
            return Collections.unmodifiableSet(keys.keySet());
        }
    }

    /**
     * 建反查表。t = 字表（null = 这个布局这种模式没有数据：返回只会打空格的表，调用方本来就该先灰掉按钮）；
     * dead = 这个布局的死键组合（FixedData.deadKeys；null 当空的：死键拼的字都算打不出）；mode = FixedLayout.WIN / MAC。
     */
    static Keys build(FixedLegends.Table t, Map<String, String> dead, String mode) {
        int third = thirdModifier(t, mode);
        int[] layerMods = {0, Hid.MOD_SHIFT, third, third | Hid.MOD_SHIFT};
        int layers = third == 0 ? 2 : 4;
        Map<Integer, int[]> keys = new HashMap<>();
        Map<Integer, Long> keyRank = new HashMap<>();
        Map<String, Integer> deadKey = new HashMap<>();
        Map<String, Long> deadRank = new HashMap<>();
        if (t != null) {
            for (int u : t.usages()) {
                for (int l = 0; l < layers; l++) {
                    FixedLegends.Cell c = t.get(u, l);
                    if (c == null) continue;
                    long rank = rank(u, l);
                    int id = u | layerMods[l] << 8;
                    if (c.dead) {
                        Long had = deadRank.get(c.text);
                        if (had == null || rank < had) {
                            deadKey.put(c.text, id);
                            deadRank.put(c.text, rank);
                        }
                    } else if (c.text.codePointCount(0, c.text.length()) == 1) {
                        int cp = c.text.codePointAt(0);
                        Long had = keyRank.get(cp);
                        if (had == null || rank < had) {
                            keys.put(cp, new int[]{id});
                            keyRank.put(cp, rank);
                        }
                    }
                }
            }
        }
        // 空格一律 0x2C（字表里不放空格那一行）；排在所有键前面
        keys.put((int) ' ', new int[]{SPACE});
        keyRank.put((int) ' ', -1L);
        if (dead == null || dead.isEmpty()) return new Keys(keys, third);
        // 死键组合：直接有键的字不用它；几种拼法时先比死键、再比后面那个键
        Map<Integer, int[]> composed = new HashMap<>();
        Map<Integer, Long> composedRank = new HashMap<>();
        for (Map.Entry<String, String> e : dead.entrySet()) {
            String pair = e.getKey();
            if (pair.isEmpty() || e.getValue().isEmpty()) continue;
            int dcp = pair.codePointAt(0);
            String d = new String(Character.toChars(dcp));
            String next = pair.substring(d.length());
            if (next.codePointCount(0, next.length()) != 1) continue;
            Integer dk = deadKey.get(d);
            int[] nk = keys.get(next.codePointAt(0));
            if (dk == null || nk == null) continue;
            String result = e.getValue();
            if (result.codePointCount(0, result.length()) != 1) continue;
            int rcp = result.codePointAt(0);
            if (keys.containsKey(rcp)) continue;
            long rank = (deadRank.get(d) + 1) * 2048 + keyRank.get(next.codePointAt(0)) + 1;   // 先比死键、再比后面那个键（rank 都在 -1–1023）
            Long had = composedRank.get(rcp);
            if (had == null || rank < had) {
                composed.put(rcp, new int[]{dk, nk[0]});
                composedRank.put(rcp, rank);
            }
        }
        keys.putAll(composed);
        return new Keys(keys, third);
    }

    /**
     * 同一个字几个键都出得来时谁先（小的先）：层（普通 > Shift > 第三层 > 第三层 + Shift），同一层里用法码小的先
     * （字表里的键都在 0x04–0x38、0x64、0x87：ISO / ABNT2 才有的那两个码最大，自然排在后面）。
     */
    static long rank(int usage, int layer) {
        return layer * 256L + usage;
    }

    /** 一段字查完的结果：要按的键、每个打得出的字到哪个键为止、打不出的字。 */
    static final class Plan {
        /** 按顺序要按的键（每个按下 + 松开）。 */
        final int[] keys;
        /** 第 j 个打得出的字的最后一个键在 keys 里的下一个下标（keys 里前 ends[j] 个键都松开了 = 打完了 j + 1 个字）。 */
        final int[] ends;
        /** 打不出、跳过的字数（一个 emoji、带附加符号的一个字算一个，见 {@link #attaches}）。 */
        final int skipped;
        /** 打不出的字，去重、按出现的先后（最多 {@link #SKIPPED_SHOWN} 个）。 */
        final List<String> skippedChars;

        Plan(int[] keys, int[] ends, int skipped, List<String> skippedChars) {
            this.keys = keys;
            this.ends = ends;
            this.skipped = skipped;
            this.skippedChars = skippedChars;
        }

        /** 打得出的字数（换行、Tab、空格都算一个字）。 */
        int total() {
            return ends.length;
        }

        /** 前 pressed 个键都松开了时打完了几个字。 */
        int doneAfter(int pressed) {
            int lo = 0, hi = ends.length;   // 第一个 ends[j] > pressed 的 j
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ends[mid] <= pressed) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        /** 前 pressed 个键松开时正好在一个字的中间（死键按了、后面那个键还没按）。 */
        boolean midChar(int pressed) {
            int done = doneAfter(pressed);
            int start = done == 0 ? 0 : ends[done - 1];
            return pressed > start && done < ends.length;
        }
    }

    /** 打不出的字最多记几个（界面只写前几个）。 */
    static final int SKIPPED_SHOWN = 64;

    /** 查一段字（规则见类注释）。 */
    static Plan plan(CharSequence text, Keys k) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
        int[] keys = new int[Math.max(4, s.length() * 2)];
        int n = 0;
        List<Integer> ends = new ArrayList<>();
        int skipped = 0;
        Set<String> shown = new LinkedHashSet<>();
        StringBuilder cluster = new StringBuilder();
        int prev = -1;   // 打不出的那一串里上一个码位（-1 = 不在打不出的一串里）
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') continue;   // \r\n 只算一个回车；单独的 \r 也不算
            int[] ks = cp == '\n' ? new int[]{ENTER} : cp == '\t' ? new int[]{TAB} : k.keysFor(cp);
            if (ks != null) {
                if (cluster.length() > 0) {
                    remember(shown, cluster);
                    cluster.setLength(0);
                }
                prev = -1;
                if (n + ks.length > keys.length) keys = java.util.Arrays.copyOf(keys, keys.length * 2 + ks.length);
                for (int key : ks) keys[n++] = key;
                ends.add(n);
                continue;
            }
            if (cluster.length() > 0 && attaches(cp, prev, cluster)) {
                cluster.appendCodePoint(cp);
            } else {
                if (cluster.length() > 0) remember(shown, cluster);
                cluster.setLength(0);
                cluster.appendCodePoint(cp);
                skipped++;
            }
            prev = cp;
        }
        if (cluster.length() > 0) remember(shown, cluster);
        int[] e = new int[ends.size()];
        for (int j = 0; j < e.length; j++) e[j] = ends.get(j);
        return new Plan(java.util.Arrays.copyOf(keys, n), e, skipped, Collections.unmodifiableList(new ArrayList<>(shown)));
    }

    private static void remember(Set<String> shown, CharSequence cluster) {
        if (shown.size() < SKIPPED_SHOWN) shown.add(cluster.toString());
    }

    /**
     * 打不出的码位 cp 并进前一个打不出的字（prev 是那一串的上一个码位）吗：组合附加符号、格式字符（ZWJ、标签字符）、变体选择符（也是组合附加符号）、
     * emoji 肤色、紧跟 ZWJ 的、和前一个区旗字母凑成一面旗的。照 Unicode 的字位簇规则简化（JDK 和安卓的 BreakIterator 对 emoji 不一样，自己写一份，两边结果一样）。
     */
    static boolean attaches(int cp, int prev, CharSequence cluster) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.FORMAT) return true;
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) return true;   // emoji 肤色
        if (prev == 0x200D) return true;   // ZWJ 后面那个
        if (isRegional(cp) && isRegional(prev)) {
            // 区旗：两个一对；前面这一串里正好奇数个区旗字母时才并
            int count = 0;
            String c = cluster.toString();
            for (int i = 0; i < c.length(); ) {
                int x = c.codePointAt(i);
                i += Character.charCount(x);
                if (isRegional(x)) count++;
            }
            return count % 2 == 1;
        }
        return false;
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }
}
