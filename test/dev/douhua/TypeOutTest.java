package dev.douhua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 贴字面板"打出来"的离线测试（2026-11-02）：反查（TypeOut）、发送节拍（TypeOutSender，假时钟）、和真 TouchRouter / KeyHolds 一起跑。
 * 从仓库根目录跑（读 res/raw 的 kbd_win / kbd_mac / kbd_catalog / kbd_dead_win / kbd_dead_mac：App 读的就是这几份）。
 *
 * 核心性质：反查出的键按字表 + 死键组合表"正着打一遍"（{@link #typeForward}，同主机：死键的字等下一个字，组合表里有就拼、没有就两个一起出）
 * 得到的就是原来的字——34 个布局 × 有数据的模式，字表里每个能出的字、组合表里每个拼得出的字都扫。
 */
public final class TypeOutTest {
    static FixedLegends.TableFile win, mac;
    static FixedLegends.DeadFile deadWin, deadMac;
    static List<FixedLegends.Layout> catalog;

    public static void main(String[] args) throws IOException {
        win = FixedLegends.parseTables(read("res/raw/kbd_win.txt"));
        mac = FixedLegends.parseTables(read("res/raw/kbd_mac.txt"));
        deadWin = FixedLegends.parseDead(read("res/raw/kbd_dead_win.txt"));
        deadMac = FixedLegends.parseDead(read("res/raw/kbd_dead_mac.txt"));
        catalog = FixedLegends.parseCatalog(read("res/raw/kbd_catalog.txt"));

        deadFile();
        roundTripAll();
        priority();
        spots();
        planShape();
        clusters();
        senderTiming();
        senderStop();
        senderGate();
        senderRandom();
        withRouter();
        latchedShiftUnlatched();
        wiring();
        System.out.println("打出来：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static FixedLegends.Table table(String lang, String mode) {
        return (FixedLayout.MAC.equals(mode) ? mac : win).sections.get(lang);
    }

    static Map<String, String> dead(String lang, String mode) {
        return (FixedLayout.MAC.equals(mode) ? deadMac : deadWin).sections.get(lang);
    }

    static TypeOut.Keys keys(String lang, String mode) {
        return TypeOut.build(table(lang, mode), dead(lang, mode), mode);
    }

    // ═══════════ 死键组合文件 ═══════════

    static void deadFile() {
        int sections = 0;
        for (FixedLegends.Layout l : catalog) {
            for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
                Map<String, String> d = dead(l.id, mode);
                check((d != null) == l.hasMode(mode), "死键组合：" + l.id + " " + mode + " 有没有那一段和清单的 modes 一致");
                if (d == null) continue;
                sections++;
                FixedLegends.Table t = table(l.id, mode);
                for (Map.Entry<String, String> e : d.entrySet()) {
                    String dk = new String(Character.toChars(e.getKey().codePointAt(0)));
                    check(deadTexts(t).contains(dk), "死键组合：" + l.id + " " + mode + " 的 " + dk + " 是字表里的死键");
                }
            }
        }
        check(sections == 65, "死键组合：65 段（34 个布局 × 有数据的模式），实际 " + sections);
        eq(dead("de", FixedLayout.WIN).get("´e"), "é", "德语 Windows：´ + e = é");
        eq(dead("de", FixedLayout.WIN).get("´c"), null, "德语 Windows：´ + c 拼不出（不是 ć）");
        eq(dead("us-intl", FixedLayout.WIN).get("'c"), "ç", "US-International：' + c = ç");
        eq(dead("pl", FixedLayout.WIN).get("~a"), "ą", "波兰程序员：~ + a = ą");
        check(dead("at", FixedLayout.WIN) == dead("de", FixedLayout.WIN), "别名：at = de");
        // 格式不对抛异常
        String[] bad = {"@x\n´\te\n", "´\te\té\n", "@x\n´\tee\té\n", "@x = y\n", "@x\n´\t\té\n"};
        String[] why = {"少一格", "不在段里", "一格两个字", "别名指向不存在的", "空格子"};
        for (int i = 0; i < bad.length; i++) {
            boolean threw = false;
            try {
                FixedLegends.parseDead(bad[i]);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "死键组合文件" + why[i] + "：抛异常");
        }
        FixedLegends.DeadFile f = FixedLegends.parseDead("#!platform test\n@x\n´\t\\u0020\t´\n´\te\té\n´\te\tx\n@y = x\n");
        eq(f.sections.get("x").get("´ "), "´", "转义的空格");
        eq(f.sections.get("x").get("´e"), "é", "同一对写了两次的先写的算");
        check(f.sections.get("y") == f.sections.get("x") && "test".equals(f.meta.get("platform").get(0)[0]), "别名、元数据");
    }

    static Set<String> deadTexts(FixedLegends.Table t) {
        Set<String> out = new TreeSet<>();
        for (int u : t.usages()) {
            for (int l = 0; l < 4; l++) {
                FixedLegends.Cell c = t.get(u, l);
                if (c != null && c.dead) out.add(c.text);
            }
        }
        return out;
    }

    // ═══════════ 正着打一遍（同主机） ═══════════

    /**
     * 照字表 + 死键组合表把键"打"成字（同主机）：回车 / Tab / 空格是 \n / \t / 空格；修饰键只能是 0、Shift、第三层、第三层 + Shift（别的组合返回 null）；
     * 死键的字等下一个字：组合表里有就拼，没有就两个一起出（反查不该产生这种）；最后还等着的死键写成 "[死键]"。
     */
    static String typeForward(int[] keys, FixedLegends.Table t, Map<String, String> dead, int third) {
        StringBuilder out = new StringBuilder();
        String pending = null;
        for (int key : keys) {
            int usage = key & 0xFF, mods = key >> 8;
            String text;
            boolean isDead = false;
            if (usage == TypeOut.ENTER && mods == 0) text = "\n";
            else if (usage == TypeOut.TAB && mods == 0) text = "\t";
            else if (usage == TypeOut.SPACE && mods == 0) text = " ";
            else {
                int layer = mods == 0 ? 0 : mods == Hid.MOD_SHIFT ? 1 : third != 0 && mods == third ? 2
                        : third != 0 && mods == (third | Hid.MOD_SHIFT) ? 3 : -1;
                if (layer < 0) return null;
                FixedLegends.Cell c = t.get(usage, layer);
                if (c == null) return null;
                text = c.text;
                isDead = c.dead;
            }
            if (pending != null) {
                if (isDead) return null;   // 连着两个死键：反查不该产生
                String r = dead == null ? null : dead.get(FixedLegends.deadPair(pending, text));
                out.append(r != null ? r : pending + text);
                pending = null;
            } else if (isDead) {
                pending = text;
            } else {
                out.append(text);
            }
        }
        if (pending != null) out.append('[').append(pending).append(']');
        return out.toString();
    }

    static String typeForward(TypeOut.Plan p, String lang, String mode, TypeOut.Keys k) {
        return typeForward(p.keys, table(lang, mode), dead(lang, mode), k.third);
    }

    // ═══════════ 反查：全扫 ═══════════

    static void roundTripAll() {
        int tables = 0, chars = 0, composed = 0, multi = 0;
        for (FixedLegends.Layout l : catalog) {
            for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
                if (!l.hasMode(mode)) continue;
                FixedLegends.Table t = table(l.id, mode);
                Map<String, String> d = dead(l.id, mode);
                TypeOut.Keys k = keys(l.id, mode);
                tables++;
                String where = l.id + " " + mode;
                int third = TypeOut.thirdModifier(t, mode);
                check(third == (FixedLayout.MAC.equals(mode) ? TypeOut.MAC_THIRD : t.hasThird() ? TypeOut.WIN_THIRD : 0), where + "：第三层的修饰键");
                int layers = third == 0 ? 2 : 4;
                // 字表里每个能出的字（不是死键、正好一个字）都反查得回来
                for (int u : t.usages()) {
                    for (int layer = 0; layer < layers; layer++) {
                        FixedLegends.Cell c = t.get(u, layer);
                        if (c == null) continue;
                        if (c.text.codePointCount(0, c.text.length()) != 1) {
                            multi++;   // Mac 克罗地亚 / 斯洛文尼亚的"不换行空格 + 组合附加符号"（多个字的死键写法）：不反查
                            continue;
                        }
                        if (c.dead) {
                            // 死键 + 空格：拼出来的字（多半是死键自己那个字，个别布局是别的：罗马尼亚 Windows ˙ + 空格 = ·，瑞士法语 ¨ + 空格 = "）反查得到
                            String r = d == null ? null : d.get(FixedLegends.deadPair(c.text, " "));
                            check(r == null || k.keysFor(r.codePointAt(0)) != null, where + "：死键 " + c.text + " + 空格 = " + r + " 反查不到");
                            continue;
                        }
                        check(k.keysFor(c.text.codePointAt(0)) != null, where + "：字表里的 " + c.text + " 反查不到");
                    }
                }
                // 组合表里拼得出的字（后面那个字能直接打的）都反查得回来
                if (d != null) {
                    for (Map.Entry<String, String> e : d.entrySet()) {
                        String pair = e.getKey();
                        String dk = new String(Character.toChars(pair.codePointAt(0)));
                        String next = pair.substring(dk.length());
                        int[] nk = k.keysFor(next.codePointAt(0));
                        boolean nextDirect = nk != null && nk.length == 1 && !isDeadKey(t, nk[0], third);
                        if (!nextDirect || !deadKeyUsable(t, dk, third)) continue;
                        check(k.keysFor(e.getValue().codePointAt(0)) != null, where + "：" + dk + " + " + next + " = " + e.getValue() + " 反查不到");
                    }
                }
                // 每个能打的字正着打一遍就是它；顺带数死键拼的
                StringBuilder all = new StringBuilder();
                for (int cp : k.chars()) {
                    String s = new String(Character.toChars(cp));
                    String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
                    if (!nfc.equals(s)) continue;   // 不是 NFC 的格子（照理没有）：输入会先规范化，另说
                    TypeOut.Plan p = TypeOut.plan(s, k);
                    check(p.total() == 1 && p.skipped == 0, where + "：" + s + " 一个字");
                    String back = typeForward(p, l.id, mode, k);
                    eq(back, s, where + "：" + hex(s) + " 正着打一遍");
                    chars++;
                    if (p.keys.length == 2) composed++;
                    check(p.keys.length == 1 || p.keys.length == 2, where + "：" + s + " 一个或两个键");
                    all.append(s);
                }
                // 整串连着打（死键用掉了、不会粘到下一个字上），加上换行、Tab
                String text = all + "\n\t " + all;
                TypeOut.Plan p = TypeOut.plan(text, k);
                eq(typeForward(p, l.id, mode, k), text, where + "：能打的字连成一串正着打一遍");
                check(p.skipped == 0 && p.total() == text.codePointCount(0, text.length()), where + "：一串里没有跳过的");
            }
        }
        check(tables == 65, "扫了 65 张表（34 个布局 × 有数据的模式），实际 " + tables);
        check(chars > 9000 && composed > 2000, "扫到的字：" + chars + " 个，死键拼的 " + composed + " 个");
        System.out.println("  反查全扫：" + tables + " 张表、" + chars + " 个字（死键拼的 " + composed + " 个），多个字的格子不反查 " + multi + " 个");
    }

    static boolean isDeadKey(FixedLegends.Table t, int key, int third) {
        int usage = key & 0xFF, mods = key >> 8;
        if (usage == TypeOut.SPACE || usage == TypeOut.ENTER || usage == TypeOut.TAB) return false;
        int layer = mods == 0 ? 0 : mods == Hid.MOD_SHIFT ? 1 : mods == third ? 2 : 3;
        FixedLegends.Cell c = t.get(usage, layer);
        return c != null && c.dead;
    }

    /** 这个死键的字在可用的层里有键（Windows 美式没有第三层：第三层的死键不算）。 */
    static boolean deadKeyUsable(FixedLegends.Table t, String dk, int third) {
        int layers = third == 0 ? 2 : 4;
        for (int u : t.usages()) {
            for (int l = 0; l < layers; l++) {
                FixedLegends.Cell c = t.get(u, l);
                if (c != null && c.dead && c.text.equals(dk)) return true;
            }
        }
        return false;
    }

    // ═══════════ 先后 ═══════════

    static void priority() {
        // 普通 > Shift > 第三层 > 第三层 + Shift，同一层里 0x64 / 0x87 放后面、用法码小的先：照规则在每张表上重算一遍，和反查结果比
        int compared = 0;
        for (FixedLegends.Layout l : catalog) {
            for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
                if (!l.hasMode(mode)) continue;
                FixedLegends.Table t = table(l.id, mode);
                TypeOut.Keys k = keys(l.id, mode);
                int third = k.third;
                int[] mods = {0, Hid.MOD_SHIFT, third, third | Hid.MOD_SHIFT};
                java.util.Map<Integer, Integer> want = new java.util.HashMap<>();
                for (int layer = 0; layer < (third == 0 ? 2 : 4); layer++) {
                    for (boolean late : new boolean[]{false, true}) {
                        for (int u : t.usages()) {
                            if ((u == 0x64 || u == 0x87) != late) continue;
                            FixedLegends.Cell c = t.get(u, layer);
                            if (c == null || c.dead || c.text.codePointCount(0, c.text.length()) != 1) continue;
                            want.putIfAbsent(c.text.codePointAt(0), u | mods[layer] << 8);
                        }
                    }
                }
                want.put((int) ' ', TypeOut.SPACE);
                for (Map.Entry<Integer, Integer> e : want.entrySet()) {
                    int[] got = k.keysFor(e.getKey());
                    check(got != null && got.length == 1 && got[0] == e.getValue(),
                            l.id + " " + mode + "：" + hex(new String(Character.toChars(e.getKey()))) + " 应该是 " + Integer.toHexString(e.getValue())
                                    + "，实际 " + (got == null ? null : Arrays.toString(got)));
                    compared++;
                }
            }
        }
        check(compared > 5000, "先后：比了 " + compared + " 个字");
    }

    static void spots() {
        TypeOut.Keys usw = keys("us", FixedLayout.WIN), dew = keys("de", FixedLayout.WIN), dem = keys("de", FixedLayout.MAC),
                usm = keys("us", FixedLayout.MAC), intl = keys("us-intl", FixedLayout.WIN), frw = keys("fr", FixedLayout.WIN),
                plw = keys("pl", FixedLayout.WIN);
        check(usw.third == 0, "美式 Windows 没有第三层：不用右 Alt");
        check(dew.third == TypeOut.WIN_THIRD && dew.third == 0x40, "德语 Windows 第三层 = 右 Alt（AltGr）");
        check(dem.third == TypeOut.MAC_THIRD && dem.third == 0x04 && usm.third == 0x04, "Mac 模式第三层 = 左 Option");
        keysEq(usw, "a", 0x04);
        keysEq(usw, "A", 0x0204);
        keysEq(usw, "\\", 0x31);   // 0x64 也出 \，放后面
        keysEq(usw, "|", 0x0231);
        keysEq(usw, " ", 0x2C);
        keysEq(usw, "€");   // 打不出（没有第三层）
        keysEq(dew, "@", 0x4014);   // AltGr+Q
        keysEq(dew, "z", 0x1C);
        keysEq(dew, "<", 0x64);
        keysEq(dew, "|", 0x4064);
        keysEq(dew, "€", 0x4008);
        keysEq(dew, "é", 0x2E, 0x08);   // ´ 死键 + e
        keysEq(dew, "É", 0x2E, 0x0208);
        keysEq(dew, "´", 0x2E, 0x2C);   // 死键 + 空格
        keysEq(dew, "^", 0x35, 0x2C);
        keysEq(dew, "ê", 0x35, 0x08);
        keysEq(dew, "ć");   // 主机拼不出（Unicode 分解会以为 ´ + c 能）
        keysEq(dem, "@", 0x040F);   // ⌥L
        keysEq(dem, "<", 0x35);   // Mac 模式 Z 左边那个键发 0x35
        keysEq(usm, "é", 0x0408, 0x08);   // ⌥E（死键 ´）+ e
        keysEq(usm, "å", 0x0404);   // ⌥A 直接有
        keysEq(intl, "'", 0x34, 0x2C);   // ' 是死键：死键 + 空格
        keysEq(intl, "é", 0x4008);   // AltGr+E 直接有，不用死键
        keysEq(intl, "ç", 0x4036);   // AltGr+, 直接有
        keysEq(intl, "ć");   // ' + c 是 ç，拼不出 ć
        keysEq(frw, "é", 0x1F);
        keysEq(frw, "ê", 0x2F, 0x08);   // ^ 死键 + e
        keysEq(frw, "a", 0x14);   // AZERTY
        int[] ogonek = plw.keysFor('ą');
        check(ogonek != null, "波兰程序员：ą 打得出");
        for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
            TypeOut.Keys k = keys("us", mode);
            keysEq(k, "中");
            keysEq(k, "😀");
        }
        // 小表：几种拼法时先比死键（层、用法码）、再比后面那个键
        FixedLegends.Table tiny = FixedLegends.parseTables("@x\n04\ta\tA\n08\te\tE\n2e\t\\*´\t\\*`\n34\t\\*'\t\\*´\n").sections.get("x");
        java.util.Map<String, String> tinyDead = new java.util.HashMap<>();
        tinyDead.put("´e", "é");
        tinyDead.put("'e", "é");
        tinyDead.put("`e", "è");
        tinyDead.put("´E", "É");
        tinyDead.put("´ ", "´");
        tinyDead.put("´a", "é");   // 两种后面的键拼出同一个字：后面那个键小的先（a = 0x04 < e = 0x08）
        tinyDead.put("'q", "q̃");   // 拼出来不止一个字：不收
        // ǻ 两种拼法，各在一个维度上赢：´（死键先）+ A（Shift 层，后面那个键后）对 '（死键后）+ a（普通层，后面那个键先）——先比死键，挑 ´ + A
        tinyDead.put("´A", "\u01FB");
        tinyDead.put("'a", "\u01FB");
        TypeOut.Keys tk = TypeOut.build(tiny, tinyDead, FixedLayout.WIN);
        keysEq(tk, "é", 0x2E, 0x04);   // ´（普通层 0x2E）比 '（0x34）先；后面那个键 a 比 e 先
        keysEq(tk, "è", 0x022E, 0x08);
        keysEq(tk, "É", 0x2E, 0x0208);
        keysEq(tk, "´", 0x2E, 0x2C);   // ´ 在 0x2E 普通层和 0x34 Shift 层都是死键：普通层的先
        keysEq(tk, "\u01FB", 0x2E, 0x0204);   // 先比死键：´ + Shift A，不是 ' + a
        check(tk.third == 0, "小表没有第三层：不用右 Alt");
        // 没有数据（拉美的 Mac）：只会打空格
        TypeOut.Keys none = TypeOut.build(null, null, FixedLayout.MAC);
        check(none.keysFor('a') == null && none.keysFor(' ') != null && none.third == TypeOut.MAC_THIRD, "没有字表：只会打空格");
        TypeOut.Keys noDead = TypeOut.build(table("de", FixedLayout.WIN), null, FixedLayout.WIN);
        check(noDead.keysFor('é') == null && noDead.keysFor('@') != null, "死键组合读不出来：é 这类打不出，别的照打");
    }

    static void keysEq(TypeOut.Keys k, String s, int... want) {
        int[] got = k.keysFor(s.codePointAt(0));
        if (want.length == 0) check(got == null, s + " 打不出，实际 " + (got == null ? null : Arrays.toString(hexes(got))));
        else check(got != null && Arrays.equals(got, want), s + " 应该是 " + Arrays.toString(hexes(want)) + "，实际 "
                + (got == null ? null : Arrays.toString(hexes(got))));
    }

    // ═══════════ 一段字 ═══════════

    static void planShape() {
        TypeOut.Keys k = keys("de", FixedLayout.WIN);
        TypeOut.Plan p = TypeOut.plan("ab", k);
        check(Arrays.equals(p.keys, new int[]{0x04, 0x05}) && p.total() == 2, "ab 两个键，不补回车");
        p = TypeOut.plan("a\r\nb\rc\n", k);
        check(Arrays.equals(p.keys, new int[]{0x04, 0x28, 0x05, 0x06, 0x28}) && p.total() == 5, "\\r\\n 一个回车、单独的 \\r 不算、以换行结尾就打回车：" + Arrays.toString(hexes(p.keys)));
        p = TypeOut.plan("a\tb c", k);
        check(Arrays.equals(p.keys, new int[]{0x04, 0x2B, 0x05, 0x2C, 0x06}), "Tab、空格");
        p = TypeOut.plan("é", k);
        check(Arrays.equals(p.keys, new int[]{0x2E, 0x08}) && p.total() == 1 && p.skipped == 0, "e + 组合尖音符先拼成 é（NFC）");
        p = TypeOut.plan("aé中b\u0007c", k);
        check(p.total() == 4 && p.skipped == 2 && p.skippedChars.equals(Arrays.asList("中", "\u0007")), "打不出的数出来、跳过：" + p.skippedChars);
        check(Arrays.equals(p.ends, new int[]{1, 3, 4, 5}), "每个字到哪个键为止：" + Arrays.toString(p.ends));
        check(p.doneAfter(0) == 0 && p.doneAfter(1) == 1 && p.doneAfter(2) == 1 && p.doneAfter(3) == 2 && p.doneAfter(5) == 4, "按完几个键打完几个字");
        check(!p.midChar(0) && !p.midChar(1) && p.midChar(2) && !p.midChar(3) && !p.midChar(5), "停在 é 的死键后面 = 停在字中间");
        p = TypeOut.plan("中中中文", k);
        check(p.total() == 0 && p.skipped == 4 && p.skippedChars.equals(Arrays.asList("中", "文")), "打不出的去重、按先后");
        p = TypeOut.plan("", k);
        check(p.total() == 0 && p.skipped == 0 && p.keys.length == 0, "空的");
        p = TypeOut.plan(null, k);
        check(p.total() == 0, "null 当空的");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 200; i++) many.appendCodePoint(0x4E00 + i);
        p = TypeOut.plan(many, k);
        check(p.skipped == 200 && p.skippedChars.size() == TypeOut.SKIPPED_SHOWN, "打不出的最多记 " + TypeOut.SKIPPED_SHOWN + " 个");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("é€@ ");
        p = TypeOut.plan(big, k);
        check(p.total() == 20000 && p.keys.length == 25000, "长的一段（数组会长大）");
    }

    static void clusters() {
        TypeOut.Keys k = keys("us", FixedLayout.WIN);
        expectSkipped(k, "😀", 1);
        expectSkipped(k, "👨‍👩‍👧", 1);   // ZWJ 连起来的一家人
        expectSkipped(k, "👍🏽", 1);   // 肤色
        expectSkipped(k, "❤️", 1);   // 变体选择符
        expectSkipped(k, "🇨🇳🇺🇸", 2);   // 两面旗
        expectSkipped(k, "🇨🇳🇺", 2);
        expectSkipped(k, "中文", 2);
        expectSkipped(k, "q́", 1);   // q 打得出，组合尖音符单独算一个打不出的
        expectSkipped(k, "中́", 1);   // 打不出的字带的附加符号并进去
        expectSkipped(k, "😀a😀", 2);
        TypeOut.Plan p = TypeOut.plan("q́", k);
        check(p.total() == 1 && Arrays.equals(p.keys, new int[]{0x14}), "q 照打");
    }

    static void expectSkipped(TypeOut.Keys k, String s, int n) {
        TypeOut.Plan p = TypeOut.plan(s, k);
        check(p.skipped == n, hex(s) + " 打不出的算 " + n + " 个，实际 " + p.skipped);
    }

    // ═══════════ 发送节拍 ═══════════

    /** 假宿主：记下每个键的按下 / 松开和时刻；定时器排在 due，{@link #run} 按到点调。 */
    static final class FakeHost implements TypeOutSender.Host {
        TypeOutSender s;
        long now;
        boolean pending;
        long due;
        int changes;
        /** 每次 changed() 时的样子：{当时发了几个键事件, busy, state, end, done, released}——宿主（MainActivity.onTypeOutChanged）照它收尾、画进度。 */
        final List<int[]> changedAt = new ArrayList<>();
        final List<long[]> events = new ArrayList<>();   // {时刻, 键, 动作}
        int downNow = -1;
        boolean overlap;

        @Override
        public void key(int key, int action) {
            events.add(new long[]{now, key, action});
            if (action == Proto.KEY_DOWN) {
                if (downNow != -1) overlap = true;
                downNow = key;
            } else {
                if (downNow != key) overlap = true;   // 松开的不是正按着的那个
                downNow = -1;
            }
        }

        @Override
        public void schedule(long delayMs) {
            check(delayMs >= 0, "排的延迟不是负的");
            pending = true;
            due = now + delayMs;
        }

        @Override
        public void cancelSchedule() {
            pending = false;
        }

        @Override
        public void changed() {
            changes++;
            changedAt.add(new int[]{events.size(), s.busy() ? 1 : 0, s.state(), s.end(), s.done(), s.released()});
        }

        int[] lastChanged() {
            return changedAt.isEmpty() ? null : changedAt.get(changedAt.size() - 1);
        }

        /** 一直跑到空闲（late = 每次到点再晚多少毫秒）。 */
        void run(long late) {
            int guard = 0;
            while (pending && guard++ < 1_000_000) {
                pending = false;
                now = due + late;
                s.onTimer(now);
            }
        }
    }

    static FakeHost host() {
        FakeHost h = new FakeHost();
        h.s = new TypeOutSender(h);
        return h;
    }

    static void senderTiming() {
        // 文档（CLAUDE.md、docs/files.md、README、设置页说明）写死的数：默认 15 键 / 秒、等焦点最多 1.5 秒、交出去以后再等 150 毫秒；改了要连文档一起改
        check(TypeOutSender.RATE_DEFAULT == 15 && TypeOutSender.RATE_MIN == 5 && TypeOutSender.RATE_MAX == 40 && TypeOutSender.RATE_STEP == 1,
                "速度：5–40 键/秒、步长 1、默认 15（和文档一致）");
        check(TypeOutSender.GATE_TIMEOUT_MS == 1500 && TypeOutSender.GATE_SETTLE_MS == 150, "等焦点 1.5 秒、交出去以后 150 毫秒（和文档一致）");
        check(TypeOutSender.clampRate(0) == 5 && TypeOutSender.clampRate(100) == 40 && TypeOutSender.clampRate(15) == 15, "速度截到 5–40");
        check(Math.abs(TypeOutSender.halfMs(15) - 33.333) < 0.01 && TypeOutSender.halfMs(40) == 12.5 && TypeOutSender.halfMs(5) == 100, "半个键的时长");
        TypeOut.Keys k = keys("de", FixedLayout.WIN);
        for (int rate = TypeOutSender.RATE_MIN; rate <= TypeOutSender.RATE_MAX; rate++) {
            TypeOut.Plan p = TypeOut.plan("Hallo Welt, é@€\n" + "abcdefghijklmnopqrstuvwxyz0123456789".substring(0, rate % 30), k);
            FakeHost h = host();
            check(h.s.start(p, rate, false, 0), "开始");
            check(h.events.size() == 1 && h.events.get(0)[2] == Proto.KEY_DOWN && h.events.get(0)[0] == 0, "不等焦点：马上按下第一个键");
            h.run(0);
            check(h.s.state() == TypeOutSender.IDLE && h.s.end() == TypeOutSender.END_DONE && h.s.done() == p.total() && !h.s.midChar(), rate + " 键/秒：打完了");
            check(h.events.size() == p.keys.length * 2 && !h.overlap && h.downNow == -1, rate + " 键/秒：每个按下配一个松开、一次只按着一个");
            for (int i = 0; i < p.keys.length; i++) {
                check(h.events.get(2 * i)[1] == p.keys[i] && h.events.get(2 * i)[2] == Proto.KEY_DOWN
                        && h.events.get(2 * i + 1)[1] == p.keys[i] && h.events.get(2 * i + 1)[2] == Proto.KEY_UP, rate + "：第 " + i + " 个键按下松开");
            }
            double half = 500.0 / rate;
            long minGap = Long.MAX_VALUE, maxGap = 0;
            for (int i = 1; i < h.events.size(); i++) {
                long g = h.events.get(i)[0] - h.events.get(i - 1)[0];
                minGap = Math.min(minGap, g);
                maxGap = Math.max(maxGap, g);
            }
            check(minGap >= (long) half && maxGap <= (long) Math.ceil(half), rate + " 键/秒：每一下隔 " + minGap + "–" + maxGap + " 毫秒（半个键 " + half + "）");
            long last = h.events.get(h.events.size() - 1)[0];
            double want = (h.events.size() - 1) * half;
            check(Math.abs(last - want) < 1.01, rate + " 键/秒：总时长 " + last + "，应为 " + want);
            changedContract(h, p, rate + " 键/秒");
        }
        // 定时器每次到点都晚 50 毫秒：每一下照样至少隔半个键（晚了不把下一下提前）
        TypeOut.Plan p = TypeOut.plan("abcdefghij", k);
        FakeHost h = host();
        h.s.start(p, 40, false, 0);
        h.run(50);
        long minGap = Long.MAX_VALUE;
        for (int i = 1; i < h.events.size(); i++) minGap = Math.min(minGap, h.events.get(i)[0] - h.events.get(i - 1)[0]);
        check(minGap >= 12 && h.s.end() == TypeOutSender.END_DONE, "定时器每次晚到：两下之间至少还是半个键（" + minGap + "）");
        // 主线程只卡一次（第 at 次到点晚 500 毫秒）：之后照常一下一下来，不补发一串（下一下从这一下真正发生的时刻起算）；总时长正好多出卡的那 500 毫秒
        int stalls = 0;
        for (int rate : new int[]{TypeOutSender.RATE_MIN, 15, TypeOutSender.RATE_MAX}) {
            double half = 500.0 / rate;
            for (int at = 0; at < 2 * p.keys.length - 1; at++) {
                h = host();
                h.s.start(p, rate, false, 0);
                int n = 0;
                while (h.pending) {
                    h.pending = false;
                    h.now = h.due + (n++ == at ? 500 : 0);
                    h.s.onTimer(h.now);
                }
                check(h.s.end() == TypeOutSender.END_DONE && h.events.size() == 2 * p.keys.length, rate + " 键/秒，第 " + at + " 次卡：打完了");
                minGap = Long.MAX_VALUE;
                int zero = 0;
                for (int i = 1; i < h.events.size(); i++) {
                    long g = h.events.get(i)[0] - h.events.get(i - 1)[0];
                    minGap = Math.min(minGap, g);
                    if (g == 0) zero++;
                }
                check(minGap >= (long) half && zero == 0, rate + " 键/秒，第 " + at + " 次到点卡 500 毫秒：之后每一下还是至少隔半个键（最短 " + minGap
                        + "，挤在一起的 " + zero + " 个）");
                long last = h.events.get(h.events.size() - 1)[0];
                double want = (h.events.size() - 1) * half + 500;
                check(Math.abs(last - want) < 1.01, rate + " 键/秒，第 " + at + " 次卡：只变慢（总时长 " + last + "，应为 " + want + "）");
                stalls++;
            }
        }
        check(stalls == 57, "卡一次：3 种速度 × 19 个时刻（" + stalls + "）");
        // 早到的回调：什么都不发，照截止时刻再排
        h = host();
        h.s.start(p, 10, false, 0);
        int before = h.events.size();
        h.now = 10;
        h.s.onTimer(10);
        check(h.events.size() == before && h.pending && h.due == 50, "早到的回调什么都不发、排到截止时刻");
        // 空闲时的回调什么都不做
        h = host();
        h.s.onTimer(5);
        check(h.events.isEmpty() && !h.pending, "空闲时的回调什么都不做");
        // 没有键：算打完了，不开始
        h = host();
        check(!h.s.start(TypeOut.plan("中", k), 15, false, 0) && h.s.end() == TypeOutSender.END_DONE && h.events.isEmpty(), "没有键：不开始");
        // 正在打的时候不能再开始一次
        h = host();
        h.s.start(p, 15, false, 0);
        check(!h.s.start(p, 15, false, 5) && h.events.size() == 1, "正在打的时候再点不重来");
    }

    /**
     * 一次从头打到完的通知（MainActivity.onTypeOutChanged 靠它：开始时出停止按钮、不再让窗口拿焦点，每个键松开更新进度，结束时恢复输入框、写结果、震一下）：
     * 第一次通知在第一个键之前、已经在打；之后每个键松开紧跟一次，进度照 Plan.doneAfter；最后一个键松开那次已经空闲、结果是打完了。
     */
    static void changedContract(FakeHost h, TypeOut.Plan p, String what) {
        List<int[]> c = h.changedAt;
        check(!c.isEmpty() && c.get(0)[0] == 0 && c.get(0)[1] == 1, what + "：开始时通知一次（第一个键之前，已经在打）");
        int idle = 0;
        for (int[] x : c) if (x[1] == 0) idle++;
        check(idle == 1 && c.get(c.size() - 1)[1] == 0, what + "：只有最后一次通知是空闲的");
        for (int i = 1; i < h.events.size(); i += 2) {
            int[] at = null;
            for (int[] x : c) if (x[0] == i + 1) at = x;
            int released = (i + 1) / 2;
            boolean last = released == p.keys.length;
            check(at != null && at[5] == released && at[4] == p.doneAfter(released)
                            && (last ? at[1] == 0 && at[2] == TypeOutSender.IDLE && at[3] == TypeOutSender.END_DONE && at[4] == p.total()
                            : at[1] == 1 && at[2] == TypeOutSender.RUNNING && at[3] == TypeOutSender.END_NONE),
                    what + "：第 " + released + " 个键松开以后通知一次" + (last ? "（打完了：已经空闲、结果写好了）" : "（进度 " + p.doneAfter(released) + "）"));
        }
    }

    static void senderStop() {
        TypeOut.Keys k = keys("de", FixedLayout.WIN);
        TypeOut.Plan p = TypeOut.plan("aé b", k);   // a | ´ e | 空格 | b
        // 每一个时刻停一次：停了以后没有按着的键、不再发
        for (long at = 0; at < 300; at += 3) {
            FakeHost h = host();
            h.s.start(p, 15, false, 0);
            while (h.pending && h.due <= at) {
                h.pending = false;
                h.now = h.due;
                h.s.onTimer(h.now);
            }
            if (h.s.state() == TypeOutSender.IDLE) continue;
            h.now = at;
            int pressedBefore = h.s.released();
            boolean wasDown = h.s.keyDown();
            int notes = h.changedAt.size();
            h.s.stop(TypeOutSender.END_USER);
            check(h.downNow == -1 && !h.overlap && !h.pending, "在 " + at + " 毫秒停：没有按着的键、没有排着的");
            int[] c = h.lastChanged();
            check(h.changedAt.size() == notes + 1 && c[0] == h.events.size() && c[1] == 0 && c[2] == TypeOutSender.IDLE
                            && c[3] == TypeOutSender.END_USER && c[4] == h.s.done() && c[5] == h.s.released(),
                    "在 " + at + " 毫秒停：松开以后通知一次，那时已经空闲、结果是按了停止");
            check(h.s.end() == TypeOutSender.END_USER && h.s.done() == p.doneAfter(h.s.released()), "停了：结果和打完了几个字");
            check(h.s.released() == pressedBefore + (wasDown ? 1 : 0), "正按着的那个配了松开（算打出去了）");
            check(h.s.midChar() == p.midChar(h.s.released()), "停在字中间");
            int n = h.events.size();
            h.now = at + 1000;
            h.s.onTimer(h.now);   // 撤掉以前排的、迟到的回调
            h.s.stop(TypeOutSender.END_LOST);
            check(h.events.size() == n && h.s.end() == TypeOutSender.END_USER, "停了以后迟到的回调、再停一次什么都不做");
            // 不续发：再开始从头打
            h.s.start(p, 15, false, h.now);
            check(h.events.get(n)[1] == p.keys[0] && h.events.get(n)[2] == Proto.KEY_DOWN, "再点一次从头打");
            h.run(0);
            check(h.s.end() == TypeOutSender.END_DONE && h.downNow == -1, "从头打完");
        }
        // 停在死键后面：主机那边还等着拼字
        FakeHost h = host();
        h.s.start(p, 15, false, 0);
        int guard = 0;
        while (h.s.released() < 2 && guard++ < 100) {
            h.pending = false;
            h.now = h.due;
            h.s.onTimer(h.now);
        }
        h.s.stop(TypeOutSender.END_PAUSED);
        check(h.s.midChar() && h.s.done() == 1 && h.s.end() == TypeOutSender.END_PAUSED, "停在 ´ 后面：打完 1 个字、在字中间");
    }

    static void senderGate() {
        TypeOut.Keys k = keys("us", FixedLayout.WIN);
        TypeOut.Plan p = TypeOut.plan("ok", k);
        // 等到点焦点也没交出去：一个键都不发
        FakeHost h = host();
        h.s.start(p, 15, true, 0);
        check(h.s.state() == TypeOutSender.WAITING && h.events.isEmpty() && h.due == TypeOutSender.GATE_TIMEOUT_MS, "等焦点：先不发");
        check(h.changedAt.size() == 1 && h.lastChanged()[1] == 1 && h.lastChanged()[2] == TypeOutSender.WAITING,
                "等焦点：开始时通知一次（宿主画\"正在交焦点\"、窗口照旧能拿焦点）");
        h.run(0);
        check(h.s.end() == TypeOutSender.END_FOCUS && h.events.isEmpty() && h.s.done() == 0, "焦点交不出去：一个键都没发");
        check(h.changedAt.size() == 2 && h.lastChanged()[1] == 0 && h.lastChanged()[3] == TypeOutSender.END_FOCUS,
                "焦点交不出去：结束时通知一次，那时已经空闲、结果写好了");
        // 交出去了：过一会儿开始
        h = host();
        h.s.start(p, 15, true, 0);
        h.now = 300;
        h.s.gateOpen(300);
        check(h.s.state() == TypeOutSender.RUNNING && h.events.isEmpty() && h.due == 300 + TypeOutSender.GATE_SETTLE_MS, "焦点交出去了：过一会儿再按");
        check(h.changedAt.size() == 2 && h.lastChanged()[0] == 0 && h.lastChanged()[1] == 1 && h.lastChanged()[2] == TypeOutSender.RUNNING,
                "焦点交出去了：通知一次（宿主不再让窗口拿焦点）");
        h.s.gateOpen(310);
        check(h.due == 300 + TypeOutSender.GATE_SETTLE_MS && h.changedAt.size() == 2, "再报一次交出去不重排、不再通知");
        h.run(0);
        check(h.s.end() == TypeOutSender.END_DONE && h.events.size() == 4 && h.events.get(0)[0] == 300 + TypeOutSender.GATE_SETTLE_MS, "交出去以后打完");
        changedContract(h, p, "等焦点");
        // 等的时候停
        h = host();
        h.s.start(p, 15, true, 0);
        h.s.stop(TypeOutSender.END_CLOSED);
        check(h.s.end() == TypeOutSender.END_CLOSED && h.events.isEmpty() && !h.pending, "等焦点的时候停：什么都没发");
        check(h.changedAt.size() == 2 && h.lastChanged()[1] == 0 && h.lastChanged()[3] == TypeOutSender.END_CLOSED, "等焦点的时候停：通知一次，那时已经空闲");
        // 不等的时候报"交出去了"什么都不做
        h = host();
        h.s.gateOpen(0);
        check(h.s.state() == TypeOutSender.IDLE && h.events.isEmpty(), "空闲时报交出去什么都不做");
    }

    static void senderRandom() {
        Random r = new Random(20261102);
        TypeOut.Keys k = keys("de", FixedLayout.WIN);
        String alphabet = "abcXYZ é€@^´\n\t中😀";
        int runs = 0;
        for (int run = 0; run < 500; run++) {
            StringBuilder sb = new StringBuilder();
            int len = r.nextInt(30);
            for (int i = 0; i < len; i++) sb.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, r.nextInt(alphabet.codePointCount(0, alphabet.length())))));
            TypeOut.Plan p = TypeOut.plan(sb, k);
            FakeHost h = host();
            int rate = TypeOutSender.RATE_MIN + r.nextInt(TypeOutSender.RATE_MAX - TypeOutSender.RATE_MIN + 1);
            boolean gated = r.nextBoolean();
            h.s.start(p, rate, gated, 0);
            int steps = 0;
            while (h.s.busy() && steps++ < 10000) {
                int op = r.nextInt(20);
                if (op == 0) {
                    h.now += r.nextInt(30);
                    h.s.stop(TypeOutSender.END_USER + r.nextInt(7));
                } else if (op == 1) {
                    h.now += r.nextInt(30);
                    h.s.gateOpen(h.now);
                } else if (op == 2) {
                    h.now += r.nextInt(30);   // 早到的回调
                    h.s.onTimer(h.now);
                } else if (h.pending) {
                    h.pending = false;
                    h.now = Math.max(h.now, h.due + (r.nextInt(4) == 0 ? r.nextInt(80) : 0));
                    h.s.onTimer(h.now);
                } else {
                    break;
                }
                check(!h.overlap, "随机：一次只按着一个、松开的就是按着的");
            }
            check(!h.s.busy() || h.pending, "随机：没停的一定排着定时器");
            if (h.s.busy()) h.s.stop(TypeOutSender.END_USER);
            check(h.downNow == -1 && !h.s.busy() && !h.pending, "随机：结束时没有按着的键、没有排着的");
            int[] lc = h.lastChanged();
            check(lc != null && lc[0] == h.events.size() && lc[1] == 0 && lc[3] == h.s.end() && lc[4] == h.s.done(),
                    "随机：结束后最后一次通知在最后一个事件之后，那时已经空闲、结果和打完几个字对得上");
            int downs = 0, ups = 0;
            for (long[] e : h.events) {
                if (e[2] == Proto.KEY_DOWN) downs++;
                else ups++;
            }
            check(downs == ups && downs == h.s.released(), "随机：按下松开一样多 = 按完的键数");
            check(h.s.done() == p.doneAfter(h.s.released()) && h.s.done() <= p.total(), "随机：打完几个字照按完的键数");
            for (int i = 0; i < h.s.released(); i++) check(h.events.get(2 * i)[1] == p.keys[i], "随机：照顺序按");
            if (h.s.end() == TypeOutSender.END_DONE) check(h.s.released() == p.keys.length && h.s.done() == p.total(), "随机：打完了 = 全部按完");
            runs++;
        }
        check(runs == 500, "随机跑了 500 组");
    }

    // ═══════════ 和真 TouchRouter / KeyHolds 一起跑 ═══════════

    /** 发送节拍接到真分流（KeyHoldsTest 的 H：主机收到的报文 [修饰键|按着的键]），看主机收到的键和打出来的字。 */
    static void withRouter() {
        String[][] cases = {
                {"de", FixedLayout.WIN, "Grüße, Jürgen! é@€ |<>\n\tÉcole ^2"},
                {"us-intl", FixedLayout.WIN, "naïve café 'quote' \"x\" ç"},
                {"us", FixedLayout.WIN, "Hello, World! 1+1=2 ~`\\|"},
                {"fr", FixedLayout.MAC, "Déjà vu ê î @ #"},
                {"us", FixedLayout.MAC, "é è ü ñ å Ω"},
                {"pl", FixedLayout.WIN, "Zażółć gęślą jaźń"},
                {"br", FixedLayout.WIN, "Ação? / ç"},
                {"tr-f", FixedLayout.WIN, "Iıİi ğüşöç"},
        };
        for (String[] c : cases) {
            String lang = c[0], mode = c[1], text = c[2];
            TypeOut.Keys k = keys(lang, mode);
            TypeOut.Plan p = TypeOut.plan(text, k);
            check(p.skipped == 0, lang + " " + mode + "：" + text + " 都打得出（跳过 " + p.skippedChars + "）");
            KeyHoldsTest.H h = new KeyHoldsTest.H();
            FakeHost fake = host();
            List<int[]> presses = new ArrayList<>();
            TypeOutSender s = new TypeOutSender(new TypeOutSender.Host() {
                @Override
                public void key(int key, int action) {
                    h.to(fake.now);
                    h.t.key(key, action, fake.now);
                }

                @Override
                public void schedule(long delayMs) {
                    fake.schedule(delayMs);
                }

                @Override
                public void cancelSchedule() {
                    fake.cancelSchedule();
                }

                @Override
                public void changed() {
                }
            });
            fake.s = s;
            fake.now = 1000;
            s.start(p, 15, false, 1000);
            fake.run(0);
            check(s.end() == TypeOutSender.END_DONE, lang + "：打完了");
            h.to(fake.now + 5000);   // 按键定时器（修饰键按住到点那些）照理一个都没排
            // 主机看到的：每份报文里新出现的键 = 按下了它，带着那一刻的修饰键字节
            int[] prev = new int[0];
            for (String rep : h.r.keys) {
                int mods = Integer.parseInt(rep.substring(1, 3), 16);
                String body = rep.substring(4, rep.length() - 1).trim();
                int[] now = body.isEmpty() ? new int[0] : Arrays.stream(body.split(" ")).mapToInt(x -> Integer.parseInt(x, 16)).toArray();
                for (int u : now) {
                    boolean was = false;
                    for (int q : prev) if (q == u) was = true;
                    if (!was) presses.add(new int[]{u | mods << 8});
                }
                check(now.length <= 1, lang + "：一份报文里最多一个键（一次只按着一个）");
                prev = now;
            }
            int[] seen = presses.stream().mapToInt(x -> x[0]).toArray();
            check(Arrays.equals(seen, p.keys), lang + " " + mode + "：主机收到的键（连修饰键）就是反查出的："
                    + Arrays.toString(hexes(seen)) + " / " + Arrays.toString(hexes(p.keys)));
            eq(typeForward(seen, table(lang, mode), dead(lang, mode), k.third), Normalizer.normalize(text, Normalizer.Form.NFC),
                    lang + " " + mode + "：主机照这些键打出来的字");
            eq(h.lastKeys(), "[00|]", lang + "：打完以后主机上没有按着的键");
            check(h.r.clicks == 0 && h.r.capsSent == 0 && h.r.lastL == 0 && h.r.lastH == 0 && h.r.lastA == 0, lang + "：没震、没发 Caps、没锁住修饰键");
            boolean haptic = false;
            for (String e : h.r.all) if (e.startsWith("h")) haptic = true;
            check(!haptic, lang + "：打的过程中分流一下都没震");
            // 自带修饰键的键：先按修饰键再按键，先松键再松修饰键
            if (lang.equals("de")) {
                List<String> at = new ArrayList<>();
                KeyHoldsTest.H h2 = new KeyHoldsTest.H();
                h2.kd(0x4014, 0);
                h2.ku(0x4014, 30);
                at.addAll(h2.r.keys);
                check(at.equals(Arrays.asList("[40|]", "[40|14]", "[40|]", "[00|]")), "AltGr+Q：先按右 Alt 再按 Q、先松 Q 再松右 Alt：" + at);
            }
        }
    }

    /** 锁着 Shift 时开始：宿主先解开（unlatch），打出来的大小写照字；不解开的话第一个字带着 Shift。 */
    static void latchedShiftUnlatched() {
        TypeOut.Keys k = keys("us", FixedLayout.WIN);
        TypeOut.Plan p = TypeOut.plan("ab", k);
        for (boolean unlatch : new boolean[]{true, false}) {
            KeyHoldsTest.H h = new KeyHoldsTest.H();
            h.kd(0xE1, 0);   // 单独按住 Shift 0.5 秒：锁住
            h.ku(0xE1, 500);
            check(h.r.lastL == Hid.MOD_SHIFT, "Shift 锁住了");
            if (unlatch) h.t.unlatch(600);
            int from = h.r.keys.size();
            long t = 700;
            for (int key : p.keys) {
                h.kd(key, t);
                h.ku(key, t + 33);
                t += 66;
            }
            List<String> sent = h.r.keys.subList(from, h.r.keys.size());
            if (unlatch) check(sent.equals(Arrays.asList("[00|04]", "[00|]", "[00|05]", "[00|]")), "解开以后 ab 照字打：" + sent);
            else check(sent.get(0).equals("[02|04]"), "不解开的话第一个字带着 Shift（所以开始前要解开）：" + sent);
        }
    }

    // ═══════════ 接线（照 MainActivity.java 源码：它依赖安卓、离线编不到） ═══════════

    static final String[] DIRS = {"values", "values-zh", "values-ja", "values-ko", "values-fr", "values-de", "values-it", "values-es", "values-ar"};

    /**
     * 蓝牙模式下贴字面板打得开、和空中鼠标页互斥（审查后的修复：原来 PHONE_TEXT 在蓝牙模式下只弹一句、不开面板，"打出来"够不着）。
     * MainActivity 离线编不到，照源码文本核对（同 FKeyChoicesTest 读 Actions.java），免得以后把 btMode 判断加回去、测试照样全过：
     * 执行动作的那一处 case PHONE_TEXT 不看 btMode、不提前 return、调 openTextBar；openTextBar 在把面板设成看得见之前不 return，
     * 从空中鼠标页打开先退出它，照模式藏"发送"、换输入法上的键、换提示字；sendText 在蓝牙模式下什么都不做；
     * setAirMode 进空中鼠标页先收起面板（在 airOn = on 之前：收起时打出来跟着停、正按着的键在本地分流 releaseAll 之前配上松开）。
     * 九个 strings.xml 都有 text_hint 和 text_hint_bt，不带占位符，text_hint_bt 写着这种语言里"打出来"按钮（typeout_button）的名字。
     * 找不到这几段（源码写法变了）就报错，提醒跟着改这里。
     */
    static void wiring() throws IOException {
        String src = read("src/dev/douhua/MainActivity.java");
        // case PHONE_TEXT 有两处：画按钮亮不亮的那一处（return 面板看不看得见）和执行动作的那一处（开 / 收面板）
        int opens = 0;
        for (int i = src.indexOf("case PHONE_TEXT:"); i >= 0; i = src.indexOf("case PHONE_TEXT:", i + 1)) {
            String code = flat(caseBody(src, i + "case PHONE_TEXT:".length()));
            if (!code.contains("openTextBar()")) continue;
            opens++;
            check(!code.contains("btMode"), "MainActivity：执行动作的 case PHONE_TEXT 不看 btMode（蓝牙模式也开贴字面板）：" + code);
            check(!code.contains("return"), "MainActivity：执行动作的 case PHONE_TEXT 不提前 return：" + code);
        }
        check(opens == 1, "MainActivity：正好一处 case PHONE_TEXT 调 openTextBar（找到 " + opens + " 处；源码写法变了，这里要跟着改）");

        String open = flat(methodBody(src, "private void openTextBar()"));
        int shown = open.indexOf("textBar.setVisibility(View.VISIBLE);");
        check(shown >= 0, "MainActivity：openTextBar 里找得到 textBar.setVisibility(View.VISIBLE)（源码写法变了，这里要跟着改）");
        if (shown >= 0) check(!open.substring(0, shown).contains("return"), "MainActivity：openTextBar 把面板设成看得见之前不 return（蓝牙模式也开）");
        int air = open.indexOf("if (airOn) setAirMode(false);");
        check(air >= 0 && (shown < 0 || air < shown), "MainActivity：openTextBar 从空中鼠标页打开先退出它（在面板看得见之前）");
        check(open.contains("textSendButton.setVisibility(btMode ? View.GONE : View.VISIBLE);"), "MainActivity：openTextBar 蓝牙模式下藏起\"发送\"");
        check(open.contains("input.setImeOptions((btMode ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_SEND)"), "MainActivity：openTextBar 蓝牙模式下输入法上的键是\"完成\"");
        check(open.contains("input.setHint(btMode ? R.string.text_hint_bt : R.string.text_hint);"), "MainActivity：openTextBar 蓝牙模式下提示字换成 text_hint_bt（不提\"发送\"）");

        String send = flat(methodBody(src, "private void sendText()"));
        check(send.startsWith("if (btMode) return;"), "MainActivity：sendText 在蓝牙模式下一上来就返回（剪贴板到不了对方）");

        String setAir = flat(methodBody(src, "private void setAirMode(boolean on)"));
        int close = setAir.indexOf("if (on) closeTextBar();");
        int flip = setAir.indexOf("airOn = on;");
        check(close >= 0 && flip >= 0 && close < flip, "MainActivity：setAirMode 进空中鼠标页先收起贴字面板（在 airOn = on 之前）");

        Pattern p = Pattern.compile("<string name=\"(text_hint|text_hint_bt|typeout_button)\"[^>]*>(.*?)</string>");
        for (String d : DIRS) {
            String x = read("res/" + d + "/strings.xml");
            Map<String, String> got = new java.util.HashMap<>();
            int dup = 0;
            Matcher m = p.matcher(x);
            while (m.find()) if (got.put(m.group(1), m.group(2).replace("\\'", "'").replace("\\\"", "\"")) != null) dup++;
            check(dup == 0, d + "：text_hint / text_hint_bt / typeout_button 各一条");
            String hint = got.get("text_hint"), bt = got.get("text_hint_bt"), button = got.get("typeout_button");
            check(hint != null && bt != null && button != null, d + " 有 text_hint、text_hint_bt、typeout_button");
            if (hint == null || bt == null || button == null) continue;
            check(!hint.contains("%") && !bt.contains("%"), d + "：提示字不带占位符（和英文一样）");
            check(bt.contains(button), d + "：蓝牙模式的提示字写着\"打出来\"按钮的名字（" + button + "）：" + bt);
        }
    }

    /** 从 from 起到下一个 case / default: 为止（一个 case 的正文）。 */
    static String caseBody(String src, int from) {
        Matcher m = Pattern.compile("\\bcase\\s|\\bdefault\\s*:").matcher(src);
        return m.find(from) ? src.substring(from, m.start()) : src.substring(from);
    }

    /** 方法体（花括号里面，不含外面那一对）；跳过字符串、字符、注释里的括号。找不到这个方法返回 ""。 */
    static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) return "";
        i = src.indexOf('{', i + signature.length());
        if (i < 0) return "";
        int depth = 0;
        for (int j = i; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '"' || c == '\'') {   // 字符串、字符：跳到配对的引号（认反斜杠转义）
                for (j++; j < src.length() && src.charAt(j) != c; j++) if (src.charAt(j) == '\\') j++;
            } else if (src.startsWith("//", j)) {
                j = src.indexOf('\n', j);
                if (j < 0) return "";
            } else if (src.startsWith("/*", j)) {
                j = src.indexOf("*/", j + 2);
                if (j < 0) return "";
                j++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(i + 1, j);
            }
        }
        return "";
    }

    /** 去掉注释、空白并成一个空格、去掉头尾空白：比源码文本时不受换行、缩进、注释影响（注释里写着 btMode 不算）。 */
    static String flat(String code) {
        String s = code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    // ═══════════ 工具 ═══════════

    static String hex(String s) {
        StringBuilder b = new StringBuilder(s);
        b.append(" (");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            b.append(String.format("U+%04X", cp)).append(i < s.length() ? " " : "");
        }
        return b.append(')').toString();
    }

    static String[] hexes(int[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = Integer.toHexString(keys[i]);
        return out;
    }

    static int failures, checks;

    static void eq(String got, String want, String what) {
        check(want == null ? got == null : want.equals(got), what + "（得到 " + got + "，应为 " + want + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
