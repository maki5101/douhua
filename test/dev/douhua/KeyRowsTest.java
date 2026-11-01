package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * KeyRows 的离线测试：分裂时哪个键归哪半、两种布局的摆放，以及按 KeyboardPanel 报区域的方式（键 + 每半一块键盘范围
 * + 中间的触控板）交给真正的 TouchRouter / KeyFingers 时，缝只在各自那一半里分、触控板不被键盘吃掉。
 */
public final class KeyRowsTest {
    private static int failures;
    private static int checks;

    private static final int GAP = 4;

    public static void main(String[] args) {
        splitDecision();
        letterHalves();
        otherHalves();
        menuKey();
        bottomHalves();
        sameKeysBothLayouts();
        placement();
        routing();
        narrowGap();
        oneShortcut();
        functionKeys();
        stickPoint();
        holeRows();
        holePlacement();
        capKeysCover();
        capTextAndLit();
        layerRows();
        System.err.println("键盘分行 / 分裂：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ── 什么时候分裂 ──
    static void splitDecision() {
        check(KeyRows.shouldSplit(2000, 1000), "宽正好是高的两倍：分裂");
        check(!KeyRows.shouldSplit(1999, 1000), "差一点：不分裂");
        check(KeyRows.shouldSplit(2560, 700), "大屏分屏下半边：分裂");
        check(!KeyRows.shouldSplit(1080, 1600), "竖屏：不分裂");
        check(!KeyRows.shouldSplit(500, 0), "高是 0（还没量好）：不分裂");
        check(KeyRows.leftEnd(900) == 300 && KeyRows.rightStart(900) == 600, "两半各三分之一宽");
        check(KeyRows.leftEnd(1001) == 1001 - KeyRows.rightStart(1001), "宽度除不尽时两半照样一样宽");
    }

    // ── 字母层：左 q–t / a–g / ⇧ z–v，右 y–p / h–l / b n m ⌫；数字行 1–5 | 6–0 ──
    static void letterHalves() {
        KeyRows.Row[] rows = KeyRows.LAYERS[KeyRows.LAYER_LETTERS];
        eq(labels(rows[0].left), "1 2 3 4 5 _", "数字行左半");
        eq(labels(rows[0].right), "_ 6 7 8 9 0", "数字行右半");
        eq(labels(rows[1].left), "q w e r t _", "第一行左半");
        eq(labels(rows[1].right), "_ y u i o p", "第一行右半");
        eq(labels(rows[2].left), "_ a s d f g", "第二行左半：外侧的半格空位留在 a 左边");
        eq(labels(rows[2].right), "g h j k l _", "第二行右半：最左边再放一个 g（两边都能按），l 右边的半格空位留着");
        eq(labels(rows[3].left), "⇧ z x c v", "第三行左半");
        eq(labels(rows[3].right), "v b n m ⌫", "第三行右半：最左边再放一个 v");
        check(rows[2].right[0] != rows[2].left[5] && rows[2].right[0].id == rows[2].left[5].id, "右半的 g 是另一个键帽、发同一个键");
        check(rows[3].right[0] != rows[3].left[4] && rows[3].right[0].id == rows[3].left[4].id, "右半的 v 是另一个键帽、发同一个键");
        for (KeyRows.Row r : rows) {
            check(near(units(r.left), KeyRows.HALF_UNITS) && near(units(r.right), KeyRows.HALF_UNITS),
                    "字母层每半每行都是 " + KeyRows.HALF_UNITS + " 份：" + labels(r.left) + " | " + labels(r.right));
            check(near(units(r.whole), 10), "整块时一行 10 份：" + labels(r.whole));
        }
        // 错开的位置和整块时一样：h 比 y 往右半格、b 和 h 对齐
        check(near(offset(rows[2].right, "h") - offset(rows[1].right, "y"), 0.5f), "右半 h 比 y 往右错半格（同整块）");
        check(near(offset(rows[3].right, "b"), offset(rows[2].right, "h")), "右半 b 和 h 对齐（同整块）");
        check(near(offset(rows[2].left, "a"), 0.5f), "左半 a 比 q 往右错半格（同整块）");
    }

    // ── 功能行、符号层、Fn 层：按个数对半分，奇数时多的放左边 ──
    static void otherHalves() {
        KeyRows.Row top = KeyRows.TOP_ROWS[0];
        eq(labels(top.left), "Esc Tab Shift Ctrl Alt", "功能行左半");
        eq(labels(top.right), "Super Caps Fn Del", "功能行右半");
        KeyRows.Row[] sym = KeyRows.LAYERS[KeyRows.LAYER_SYMBOLS];
        eq(labels(sym[0].left), "1 2 3 4 5", "符号层数字行左半");
        eq(labels(sym[3].left), "; : ' \" ` ~", "符号层第四行 11 个键：左边 6 个");
        eq(labels(sym[3].right), "< > / ? ⌫", "符号层第四行：右边 5 个");
        KeyRows.Row[] fn = KeyRows.LAYERS[KeyRows.LAYER_FN];
        eq(labels(fn[0].left) + " | " + labels(fn[0].right), "F1 F2 F3 | F4 F5 F6", "Fn 层第一行");
        eq(labels(fn[3].left) + " | " + labels(fn[3].right), "Ins Del | PrtSc ⌫", "Fn 层第四行");
        KeyRows.Key[] seven = new KeyRows.Key[7];
        for (int i = 0; i < 7; i++) seven[i] = KeyRows.k(0x04 + i, "s" + i);
        KeyRows.Row r = KeyRows.halves(seven);
        check(r.left.length == 4 && r.right.length == 3, "快捷行 7 个：左 4 右 3");
        for (KeyRows.Row[] layer : KeyRows.LAYERS) {
            for (KeyRows.Row row : layer) {
                check(sameKeys(row.whole, concat(row.left, dropCopies(row.left, row.right))), "两半合起来正好是整行的键（右半多放的 g、v 不算；不多不少、顺序一样）："
                        + labels(row.whole));
            }
        }
    }

    // ── 功能行带 ⋯（小触控板没摆出来时）：最右边多一个特殊键，分裂时在右半最右；键宽、分行、缝照旧 ──
    static void menuKey() {
        KeyRows.Row plain = KeyRows.topRows(false)[0], menu = KeyRows.topRows(true)[0];
        check(plain == KeyRows.TOP_ROWS[0], "不带 ⋯：就是原来的功能行");
        eq(labels(menu.whole), "Esc Tab Shift Ctrl Alt Super Caps Fn Del ⋯", "带 ⋯：整块时在最右边");
        eq(labels(menu.left), "Esc Tab Shift Ctrl Alt", "带 ⋯：分裂左半（十个键对半分）");
        eq(labels(menu.right), "Super Caps Fn Del ⋯", "带 ⋯：分裂右半，⋯ 在右半最右");
        eq(labels(plain.left) + " | " + labels(plain.right), "Esc Tab Shift Ctrl Alt | Super Caps Fn Del", "不带 ⋯：分法不变");
        KeyRows.Key dots = menu.whole[menu.whole.length - 1];
        check(dots.id == KeyRows.MENU_BUTTON_ID && dots.special && dots.weight == 1, "⋯ 是特殊键（App 处理）、一份宽");
        check(dots == menu.right[menu.right.length - 1], "整块和分裂里的 ⋯ 是同一个键帽");
        boolean same = true;
        for (int i = 0; i < plain.whole.length; i++) same &= plain.whole[i] == menu.whole[i];
        check(same, "其余九个键和不带 ⋯ 时是同一批对象（键帽、高亮跟着走）");

        // 摆放：整块时十个键一样宽、铺满；分裂时 ⋯ 在右半最右边，挨着右半的边
        int w = 1080, h = 600;
        KeyRows.Row[] rows = rowsWithTop(KeyRows.topRows(true), KeyRows.LAYER_LETTERS);
        List<KeyRows.Placed> whole = KeyRows.place(rows, w, h, false, GAP);
        KeyRows.Placed esc = find(whole, "Esc"), d = find(whole, "⋯");
        check(Math.abs((esc.right - esc.left) - (d.right - d.left)) <= 1, "整块：⋯ 和 Esc 一样宽");
        check(d.right == w - GAP, "整块：⋯ 在最右边（只留缝）");
        List<KeyRows.Placed> split = KeyRows.place(rows, w, h, true, 8, GAP);
        KeyRows.Placed sd = find(split, "⋯");
        check(sd.right == w - GAP && sd.left >= KeyRows.rightStart(w, 8), "分裂：⋯ 在右半最右边");
        check(KeyRows.keys(rows, true).size() == KeyRows.keys(rowsWithTop(KeyRows.topRows(false), KeyRows.LAYER_LETTERS), true).size() + 1,
                "带 ⋯ 只多一个键");

        // 按 KeyboardPanel 的方式报区域：⋯ 是按钮，它右边的边距归 ⋯，Del 和 ⋯ 之间的缝各归各的
        List<TouchRouter.Region> regs = new ArrayList<>();
        for (int i = 0; i < whole.size(); i++) {
            KeyRows.Placed p = whole.get(i);
            regs.add(new TouchRouter.Region(p.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i, p.left, p.top, p.right, p.bottom));
        }
        regs.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, w, h));
        TouchRouter router = new TouchRouter(new Quiet());
        router.setRegions(regs.toArray(new TouchRouter.Region[0]));
        float y = (d.top + d.bottom) / 2f;
        TouchRouter.Region r = router.hit(w - 1, y);
        check(r != null && r.type == Proto.REGION_BUTTON && label(whole, r).equals("⋯"), "最右边的边距归 ⋯（按钮）");
        KeyRows.Placed del = find(whole, "Del");
        check(label(whole, router.hit(del.right + 1, y)).equals("Del") && label(whole, router.hit(d.left - 1, y)).equals("⋯"),
                "Del 和 ⋯ 之间的缝各归最近的");
    }

    // ── 底行：?123 , 空格 | 空格 . ↵；苹果设备的 🌐 放左半 ──
    static void bottomHalves() {
        KeyRows.Key[] sc = {KeyRows.k(0x06 | (1 << 8), "复制"), KeyRows.k(0x19 | (1 << 8), "粘贴"), KeyRows.k(0x1B | (1 << 8), "剪切")};
        KeyRows.Row[] b = KeyRows.bottomRows(false, sc);
        check(b.length == 2, "底行 + 快捷行");
        eq(labels(b[0].left), "?123 , Space", "底行左半");
        eq(labels(b[0].right), "Space . ↵", "底行右半");
        eq(labels(b[1].left) + " | " + labels(b[1].right), "复制 粘贴 | 剪切", "快捷行对半分");
        check(near(units(b[0].left), KeyRows.HALF_UNITS) && near(units(b[0].right), KeyRows.HALF_UNITS), "底行两半各 5.5 份");
        check(b[0].left[2] != b[0].right[0], "两个空格是两个键帽");
        check(b[0].left[2].id == 0x2C && b[0].right[0].id == 0x2C, "两个空格都是空格键");
        KeyRows.Row[] a = KeyRows.bottomRows(true, new KeyRows.Key[0]);
        check(a.length == 1, "快捷行空着就没有这一行");
        eq(labels(a[0].left), "?123 🌐 , Space", "苹果：🌐 在左半");
        eq(labels(a[0].right), "Space . ↵", "苹果：右半不变");
        eq(labels(a[0].whole), "?123 🌐 , Space . ↵", "苹果：整块底行不变");
    }

    // ── 同一个键在两种布局里是同一个对象（键帽缓存、高亮跟着走）；只有分裂的空格是另外的 ──
    static void sameKeysBothLayouts() {
        for (int l = 0; l < KeyRows.LAYERS.length; l++) {
            KeyRows.Row[] rows = rows(l, new KeyRows.Key[0]);
            IdentityHashMap<KeyRows.Key, Boolean> whole = new IdentityHashMap<>();
            for (KeyRows.Key k : KeyRows.keys(rows, false)) whole.put(k, true);
            int extra = 0;
            for (KeyRows.Key k : KeyRows.keys(rows, true)) if (!whole.containsKey(k)) extra++;
            int copies = l == KeyRows.LAYER_LETTERS ? 2 : 0;   // 字母层右半多一个 g、一个 v
            check(extra == 2 + copies, "第 " + l + " 层分裂时只多出两个空格键帽" + (copies > 0 ? "和右半的 g、v" : "") + "（实际 " + extra + "）");
            check(KeyRows.keys(rows, true).size() == KeyRows.keys(rows, false).size() + 1 + copies, "第 " + l + " 层分裂时多出的键（空格分成两个" + (copies > 0 ? "、右半的 g、v" : "") + "）");
        }
    }

    // ── 摆放：整块铺满；分裂时键都在自己那一半里，中间三分之一空着 ──
    static void placement() {
        int w = 2400, h = 700;
        KeyRows.Row[] rows = rows(KeyRows.LAYER_LETTERS, new KeyRows.Key[]{KeyRows.k(0x06 | (1 << 8), "复制")});
        List<KeyRows.Placed> whole = KeyRows.place(rows, w, h, false, GAP);
        check(whole.size() == KeyRows.keys(rows, false).size(), "整块：每个键都摆了");
        int minL = Integer.MAX_VALUE, maxR = 0;
        for (KeyRows.Placed p : whole) {
            minL = Math.min(minL, p.left);
            maxR = Math.max(maxR, p.right);
        }
        check(minL == GAP && maxR == w - GAP, "整块：一行铺满宽度（两头只留缝）");
        List<KeyRows.Placed> split = KeyRows.place(rows, w, h, true, GAP);
        int le = KeyRows.leftEnd(w), rs = KeyRows.rightStart(w);
        List<KeyRows.Key> order = KeyRows.keys(rows, true);
        check(split.size() == order.size(), "分裂：每个键都摆了");
        boolean inHalf = true, sameOrder = true;
        for (int i = 0; i < split.size(); i++) {
            KeyRows.Placed p = split.get(i);
            inHalf &= (p.left >= 0 && p.right <= le) || (p.left >= rs && p.right <= w);
            sameOrder &= p.key == order.get(i);
        }
        check(inHalf, "分裂：每个键整个落在左半或右半里，中间三分之一没有键");
        check(sameOrder, "摆放顺序和 keys() 一样（报区域的顺序）");
        // 字母层的键分裂时一样宽
        int qw = width(split, "q"), pw = width(split, "p"), aw = width(split, "a"), lw = width(split, "l"), zw = width(split, "z");
        check(Math.abs(qw - pw) <= 1 && Math.abs(qw - aw) <= 1 && Math.abs(qw - lw) <= 1 && Math.abs(qw - zw) <= 1,
                "分裂：字母键一样宽（q " + qw + " p " + pw + " a " + aw + " l " + lw + " z " + zw + "）");
        check(Math.abs((width(split, "⇧") + 2 * GAP) - 1.5f * (qw + 2 * GAP)) <= 2, "分裂：⇧ 是一格半");
        // 行等高、各行之间不重叠
        KeyRows.Placed q = find(split, "q"), a = find(split, "a");
        check(q.bottom <= a.top && a.top - q.bottom == 2 * GAP, "分裂：上下两行之间是两倍 gap 的缝");
        // 左右两半里同一行的键在同一高度
        KeyRows.Placed t = find(split, "t"), y = find(split, "y");
        check(t.top == y.top && t.bottom == y.bottom, "分裂：t 和 y 在同一高度");
    }

    // ── 按 KeyboardPanel 的方式报区域，交给真正的 TouchRouter：缝只在各自那一半里分，中间是触控板 ──
    static void routing() {
        int w = 2400, h = 700;
        KeyRows.Row[] rows = rows(KeyRows.LAYER_LETTERS, new KeyRows.Key[0]);
        List<KeyRows.Placed> split = KeyRows.place(rows, w, h, true, GAP);
        int le = KeyRows.leftEnd(w), rs = KeyRows.rightStart(w);
        List<TouchRouter.Region> regs = new ArrayList<>();
        for (int i = 0; i < split.size(); i++) {
            KeyRows.Placed p = split.get(i);
            // 编号用下标（同 KeyboardPanel 自己收触摸时），好区分两个空格
            regs.add(new TouchRouter.Region(p.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                    p.left, p.top, p.right, p.bottom));
        }
        TouchRouter.Region leftArea = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, le, h);
        TouchRouter.Region rightArea = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, rs, 0, w, h);
        regs.add(leftArea);
        regs.add(rightArea);
        TouchRouter.Region pad = new TouchRouter.Region(Proto.REGION_PAD, 0, le + 8, 0, rs - 8, h);
        regs.add(pad);
        TouchRouter.Region[] all = regs.toArray(new TouchRouter.Region[0]);
        TouchRouter.Region[] keys = regs.subList(0, split.size()).toArray(new TouchRouter.Region[0]);
        TouchRouter.Region[] areas = {leftArea, rightArea};
        TouchRouter router = new TouchRouter(new Quiet());
        router.setRegions(all);

        KeyRows.Placed t = find(split, "t"), y = find(split, "y");
        float rowY = (t.top + t.bottom) / 2f;
        // t 右边是半格空位 + 切口：在左半里的都归 t，不会跨到右半的 y
        check(label(split, router.hit(le - 1, rowY)) .equals("t"), "左半最右边（t 右边的空位）归 t");
        check(label(split, router.hit(rs + 1, rowY)).equals("y"), "右半最左边（y 左边的空位）归 y");
        check(router.hit((le + rs) / 2f, rowY) == pad, "正中间是触控板，不归任何键");
        check(router.hit(le + 3, rowY) == null, "左半和触控板之间的空白：什么都不是（不被分给键）");
        check(!router.onTarget(le + 3, rowY), "这块空白分屏透传时交给系统");
        KeyRows.Placed hKey = find(split, "h");
        float homeY = (hKey.top + hKey.bottom) / 2f;
        check(label(split, router.hit(rs + 2, homeY)).equals("g"), "右半第二行最左边是右半的 g");
        check(label(split, router.hit(w - 1, homeY)).equals("l"), "右半第二行最右边的半格空位归 l");
        KeyRows.Placed aKey = find(split, "a");
        check(label(split, router.hit(1, (aKey.top + aKey.bottom) / 2f)).equals("a"), "左半第二行最左边的半格空位归 a");

        // 逐点：落在哪一半的范围里，结果就是那一半里的键；和 nearestKey 在那一半里找的一样；KeyFingers 自己收触摸时也一样
        int wrongSide = 0, mismatch = 0, fingerMismatch = 0, total = 0;
        for (int x = 0; x < w; x += 9) {
            for (int yy = 0; yy < h; yy += 7) {
                TouchRouter.Region area = leftArea.contains(x, yy) ? leftArea : rightArea.contains(x, yy) ? rightArea : null;
                TouchRouter.Region got = router.hit(x, yy);
                TouchRouter.Region finger = KeyFingers.pick(keys, areas, x, yy);
                if (area == null) {
                    if (finger != null) fingerMismatch++;
                    continue;
                }
                total++;
                if (got == null || !inside(got, area)) wrongSide++;
                TouchRouter.Region expect = null;
                for (TouchRouter.Region k : keys) if (k.contains(x, yy)) expect = k;
                if (expect == null) expect = TouchRouter.nearestKey(all, area, x, yy);
                if (got != expect) mismatch++;
                if (finger == null || finger.id != got.id) fingerMismatch++;
            }
        }
        check(total > 1000, "逐点检查够多（" + total + "）");
        check(wrongSide == 0, "每个落在键盘范围里的点都分给了同一半里的键（错 " + wrongSide + "）");
        check(mismatch == 0, "按在键上照矩形、缝里照 nearestKey（错 " + mismatch + "）");
        check(fingerMismatch == 0, "面板自己收触摸（KeyFingers 两块范围）和分流结果一样（错 " + fingerMismatch + "）");

        // 两个空格：各自那一半的空格
        KeyRows.Placed spaceL = null, spaceR = null;
        for (KeyRows.Placed p : split) {
            if (p.key.id != 0x2C) continue;
            if (p.right <= le) spaceL = p;
            else spaceR = p;
        }
        check(spaceL != null && spaceR != null, "分裂时两个空格都摆了");
        TouchRouter.Region hl = router.hit(spaceL.left + 5, spaceL.bottom + 2);
        TouchRouter.Region hr = router.hit(spaceR.right - 5, spaceR.bottom + 2);
        check(hl != null && split.get(hl.id).key.id == 0x2C && split.get(hl.id) == spaceL, "左空格下边距归左空格");
        check(hr != null && split.get(hr.id) == spaceR, "右空格下边距归右空格");

        // 整块布局：一块范围，结果和以前一样（键在上、缝归最近的）
        List<KeyRows.Placed> whole = KeyRows.place(rows, w, h, false, GAP);
        List<TouchRouter.Region> wr = new ArrayList<>();
        for (int i = 0; i < whole.size(); i++) {
            KeyRows.Placed p = whole.get(i);
            wr.add(new TouchRouter.Region(p.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i, p.left, p.top, p.right, p.bottom));
        }
        TouchRouter.Region wa = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, w, h);
        wr.add(wa);
        TouchRouter rw = new TouchRouter(new Quiet());
        rw.setRegions(wr.toArray(new TouchRouter.Region[0]));
        int miss = 0;
        for (int x = 0; x < w; x += 13) for (int yy = 0; yy < h; yy += 11) if (rw.hit(x, yy) == null) miss++;
        check(miss == 0, "整块：键盘范围里每个点都有键");
    }

    // ── 中间不放触控板（两半只隔一条缝）：两块范围伸到缝的中线，缝分给各自那一半最近的键；中间放触控板时范围不变 ──
    static void narrowGap() {
        int w = 1080, h = 500, middle = 21;   // 手机横屏强制分裂、触控板关：缝 8dp × 2.625
        KeyRows.Row[] rows = rows(KeyRows.LAYER_LETTERS, new KeyRows.Key[0]);
        List<KeyRows.Placed> split = KeyRows.place(rows, w, h, true, middle, GAP);
        int le = KeyRows.leftEnd(w, middle), rs = KeyRows.rightStart(w, middle);
        int mid = KeyRows.areaEnd(w, middle);
        check(KeyRows.areaStart(w, middle) == mid && mid > le && mid < rs, "只隔一条缝：两块范围在缝的中线相接");
        check(KeyRows.areaEnd(w, KeyRows.MIDDLE_PAD) == KeyRows.leftEnd(w)
                && KeyRows.areaStart(w, KeyRows.MIDDLE_PAD) == KeyRows.rightStart(w), "中间放触控板：范围就是两半的边");
        List<TouchRouter.Region> regs = new ArrayList<>();
        for (int i = 0; i < split.size(); i++) {
            KeyRows.Placed p = split.get(i);
            regs.add(new TouchRouter.Region(p.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                    p.left, p.top, p.right, p.bottom));
        }
        TouchRouter.Region[] keys = regs.toArray(new TouchRouter.Region[0]);
        TouchRouter.Region[] areas = {   // 照 KeyboardPanel.areas()
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, KeyRows.areaEnd(w, middle), KeyRows.halfBottom(rows, h, false)),
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, KeyRows.areaStart(w, middle), 0, w, KeyRows.halfBottom(rows, h, true))};
        regs.addAll(Arrays.asList(areas));
        TouchRouter router = new TouchRouter(new Quiet());
        router.setRegions(regs.toArray(new TouchRouter.Region[0]));
        String[][] pairs = {{"t", "y"}, {"g", "g"}, {"v", "v"}};   // 右半最左边是多放的 g、v
        for (String[] pr : pairs) {
            KeyRows.Placed a = find(split, pr[0]);
            float y = (a.top + a.bottom) / 2f;
            check(label(split, router.hit(mid - 1, y)).equals(pr[0]), "缝里中线左边一点归 " + pr[0]);
            check(label(split, router.hit(mid, y)).equals(pr[1]), "中线那一像素归右半的 " + pr[1]);
            check(label(split, router.hit(le + 1, y)).equals(pr[0]) && label(split, router.hit(rs - 1, y)).equals(pr[1]),
                    pr[0] + "|" + pr[1] + " 之间的缝两侧各归各的");
            TouchRouter.Region f = KeyFingers.pick(keys, areas, mid - 1, y);
            check(f != null && label(split, f).equals(pr[0]), "面板自己收触摸：缝里偏左也归 " + pr[0]);
        }
        int miss = 0;
        for (int x = 0; x < w; x += 3) for (int yy = 0; yy < h; yy += 7) if (router.hit(x, yy) == null) miss++;
        check(miss == 0, "只隔一条缝：键盘范围里每个点都有键（空 " + miss + "）");
        // 按在键上的照旧按矩形：范围伸长不改键上的结果
        int wrong = 0;
        for (TouchRouter.Region k : keys) {
            TouchRouter.Region got = router.hit((k.left + k.right) / 2f, (k.top + k.bottom) / 2f);
            if (got != k) wrong++;
        }
        check(wrong == 0, "按在键上的结果不变（错 " + wrong + "）");
    }

    // ── 快捷行只有 1 个键：右半那一行是空的，右半的键盘范围只到底行下沿，按在那里什么都不是 ──
    static void oneShortcut() {
        int w = 2400, h = 700;
        KeyRows.Key copy = KeyRows.k(0x06 | (1 << 8), "复制");
        KeyRows.Row[] rows = rows(KeyRows.LAYER_LETTERS, new KeyRows.Key[]{copy});
        int n = rows.length;
        int bottomRowEnd = Math.round((float) h * (n - 1) / n);
        check(KeyRows.halfBottom(rows, h, true) == bottomRowEnd, "1 个快捷键：右半范围到底行下沿");
        check(KeyRows.halfBottom(rows, h, false) == h, "1 个快捷键：左半范围到底");
        KeyRows.Row[] full = rows(KeyRows.LAYER_LETTERS, new KeyRows.Key[]{copy, KeyRows.k(0x19 | (1 << 8), "粘贴")});
        check(KeyRows.halfBottom(full, h, true) == h && KeyRows.halfBottom(full, h, false) == h, "2 个快捷键：两半都到底");
        check(KeyRows.halfBottom(new KeyRows.Row[0], h, true) == 0, "一行都没有：0");

        // 照 KeyboardPanel.areas() 报区域，交给真正的 TouchRouter / KeyFingers
        List<KeyRows.Placed> split = KeyRows.place(rows, w, h, true, GAP);
        int le = KeyRows.leftEnd(w), rs = KeyRows.rightStart(w);
        List<TouchRouter.Region> regs = new ArrayList<>();
        for (int i = 0; i < split.size(); i++) {
            KeyRows.Placed p = split.get(i);
            regs.add(new TouchRouter.Region(p.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                    p.left, p.top, p.right, p.bottom));
        }
        TouchRouter.Region[] keys = regs.toArray(new TouchRouter.Region[0]);
        TouchRouter.Region[] areas = {
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, le, KeyRows.halfBottom(rows, h, false)),
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, rs, 0, w, KeyRows.halfBottom(rows, h, true))};
        regs.addAll(Arrays.asList(areas));
        TouchRouter router = new TouchRouter(new Quiet());
        router.setRegions(regs.toArray(new TouchRouter.Region[0]));

        KeyRows.Placed c = find(split, "复制");
        float scY = (c.top + c.bottom) / 2f;
        check(router.hit(1650, scY) == null && router.hit(w - 50, scY) == null, "快捷行右半（空的）：什么都不是，不分给空格 / ↵");
        check(KeyFingers.pick(keys, areas, 1650, scY) == null && KeyFingers.pick(keys, areas, w - 50, scY) == null,
                "面板自己收触摸：快捷行右半也不算");
        check(label(split, router.hit((c.left + c.right) / 2f, scY)).equals("复制"), "左半的快捷键照常");
        check(label(split, router.hit(c.left - 2, scY)).equals("复制"), "左半快捷键旁边的边距照常分给它");
        KeyRows.Placed enter = find(split, "↵");
        float eY = (enter.top + enter.bottom) / 2f;
        check(label(split, router.hit((enter.left + enter.right) / 2f, eY)).equals("↵"), "底行右半的 ↵ 照常");
        check(label(split, router.hit(w - 1, enter.bottom + 1)).equals("↵"), "↵ 下边距（还在底行里）照常归 ↵");
        TouchRouter.Region f = KeyFingers.pick(keys, areas, w - 1, enter.bottom + 1);
        check(f != null && label(split, f).equals("↵"), "面板自己收触摸：↵ 下边距也归 ↵");
    }

    // ── 主次键帽：哪些键用主题的功能键颜色（KeyRows.functionKey / numpadFunctionKey）──
    static void functionKeys() {
        // 整屏键盘上看得见的每个键（三层、两种功能行、两种底行），按键帽上写的字分：这些是普通键，其余全是功能键
        String plain = "1 2 3 4 5 6 7 8 9 0 q w e r t y u i o p a s d f g h j k l z x c v b n m "
                + "! @ # $ % ^ & * ( ) - _ = + [ ] { } \\ | ; : ' \" ` ~ < > / ? , . Space";
        java.util.Set<String> plainSet = new java.util.HashSet<>(Arrays.asList(plain.split(" ")));
        String fn = "Esc Tab Shift Ctrl Alt Super Caps Fn Del ⋯ ⇧ ⌫ ↵ ?123 🌐 F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12 "
                + "Home End PgUp PgDn Ins PrtSc";
        java.util.Set<String> fnSet = new java.util.HashSet<>(Arrays.asList(fn.split(" ")));
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<KeyRows.Row[]> all = new ArrayList<>();
        all.add(KeyRows.TOP_ROWS_MENU);
        all.add(KeyRows.TOP_ROWS);
        all.addAll(Arrays.asList(KeyRows.LAYERS));
        all.add(KeyRows.bottomRows(false, new KeyRows.Key[0]));
        all.add(KeyRows.bottomRows(true, new KeyRows.Key[0]));
        for (KeyRows.Row[] rows : all) {
            for (KeyRows.Row r : rows) {
                for (KeyRows.Key[] part : new KeyRows.Key[][]{r.whole, r.left, r.right, r.before, r.after}) {
                    if (part == null) continue;
                    for (KeyRows.Key k : part) {
                        if (k.spacer() || !seen.add(k.label + "/" + k.id)) continue;
                        boolean want = !plainSet.contains(k.label);
                        check(plainSet.contains(k.label) || fnSet.contains(k.label), "键 " + k.label + " 在清单里（漏了就补上）");
                        check(KeyRows.functionKey(k, false) == want, k.label + (want ? " 是功能键" : " 是普通键"));
                        // ⋯ 另画成强调色的底 + 反差字（2026-11-01）：只有它，别的键照主次键帽画
                        check(KeyRows.menuKey(k.id) == k.label.equals("⋯"), k.label + (k.label.equals("⋯") ? " 画成强调色的 ⋯" : " 不是 ⋯"));
                    }
                }
            }
        }
        check(seen.contains("⋯/" + KeyRows.MENU_BUTTON_ID), "功能行带 ⋯ 那份里看得见 ⋯");
        // 方向键、自带 Ctrl 的组合键（快捷行默认放的）、右 Shift 自带的符号
        check(KeyRows.functionKey(KeyRows.k(0x50, "←"), false) && KeyRows.functionKey(KeyRows.k(0x52, "↑"), false),
                "方向键是功能键");
        check(KeyRows.functionKey(KeyRows.k(0x06 | (1 << 8), "复制"), false), "自带 Ctrl 的组合键是功能键");
        check(KeyRows.functionKey(KeyRows.k(0x06 | (8 << 8), "⌘C"), false), "自带 GUI 的组合键是功能键");
        check(!KeyRows.functionKey(KeyRows.k(0x1E | (0x20 << 8), "!"), false), "自带右 Shift 的符号是普通键");
        check(KeyRows.functionKey(KeyRows.k(0x04 | (KeyRows.SHIFT << 8), "A"), true), "快捷行里的键一律是功能键（哪怕是 Shift + 字母）");
        check(KeyRows.functionKey(KeyRows.k(0x2C, "Space"), true), "快捷行里的空格也是功能键");
        // 数字键盘：7 8 9 ⌫ / 4 5 6 + / 1 2 3 - / 0 . Tab ↵
        int[] numPlain = {0x24, 0x25, 0x26, 0x21, 0x22, 0x23, 0x1E, 0x1F, 0x20, 0x27, 0x37};
        int[] numFn = {0x2A, 0x57, 0x2D, 0x2B, 0x28};
        for (int u : numPlain) check(!KeyRows.numpadFunctionKey(u), String.format("数字键盘 0x%02X 是普通键", u));
        for (int u : numFn) check(KeyRows.numpadFunctionKey(u), String.format("数字键盘 0x%02X 是功能键", u));
        check(!KeyRows.functionKey(KeyRows.k(0x2D, "-"), false) && KeyRows.numpadFunctionKey(0x2D),
                "- 在整屏键盘上是标点、在数字键盘上是运算符");
    }

    // ═══════════ 小工具 ═══════════

    private static KeyRows.Row[] rowsWithTop(KeyRows.Row[] top, int layer) {
        List<KeyRows.Row> l = new ArrayList<>(Arrays.asList(top));
        l.addAll(Arrays.asList(KeyRows.LAYERS[layer]));
        l.addAll(Arrays.asList(KeyRows.bottomRows(false, new KeyRows.Key[0])));
        return l.toArray(new KeyRows.Row[0]);
    }

    private static KeyRows.Row[] rows(int layer, KeyRows.Key[] shortcuts) {
        List<KeyRows.Row> l = new ArrayList<>(Arrays.asList(KeyRows.TOP_ROWS));
        l.addAll(Arrays.asList(KeyRows.LAYERS[layer]));
        l.addAll(Arrays.asList(KeyRows.bottomRows(false, shortcuts)));
        return l.toArray(new KeyRows.Row[0]);
    }

    // ── 小红点嵌在哪：g|h、v|b 那条缝和 a–l / z–m 两行的交界（2026-11-01；只有整块，小红点状态不分裂） ──
    static void stickPoint() {
        check(near(KeyRows.STICK_UNITS, 5.5f) && near(KeyRows.LETTER_UNITS, 10), "g|h 在一行的 5.5 / 10 份处");
        KeyRows.Row[] letters = KeyRows.LAYERS[KeyRows.LAYER_LETTERS];
        check(near(KeyRows.unitsBefore(letters[3].whole, "b"), KeyRows.STICK_UNITS), "第三行 v|b 也在 5.5 份处（四个键交在一点）");
        KeyRows.Row[] rows = KeyRows.rows(true, KeyRows.LAYER_LETTERS, KeyRows.bottomRows(false, new KeyRows.Key[]{KeyRows.k(0x04, "x")}));
        for (int[] wh : new int[][]{{400, 508}, {411, 588}, {360, 494}, {1001, 333}}) {
            int w = wh[0], h = wh[1];
            // 中心 = g 的右边 + 缩进 = h 的左边 − 缩进，= v / b 同样；竖着 = a 的下沿 + 缩进 = z 的上沿 − 缩进
            List<KeyRows.Placed> ps = KeyRows.place(rows, w, h, false, GAP / 2);
            int[] p = KeyRows.stickPoint(w, h, rows.length);
            int g = 0;
            boolean ok = true;
            for (KeyRows.Placed q : ps) {
                String l = q.key.label;
                if (l.equals("g") || l.equals("v")) ok &= q.right + GAP / 2 == p[0];
                if (l.equals("h") || l.equals("b")) ok &= q.left - GAP / 2 == p[0];
                if (l.equals("a")) ok &= q.bottom + GAP / 2 == p[1];
                if (l.equals("z")) ok &= q.top - GAP / 2 == p[1];
                if (l.equals("g")) g++;
            }
            check(ok && g == 1, w + "×" + h + "：小红点那一点正好在 g、h、v、b 交界 " + Arrays.toString(p));
        }
    }

    // ── 小红点状态：底行、快捷行让出空位（2026-11-01 用户："想用它占用空格行，还有包括空格下边那一行"） ──
    static void holeRows() {
        KeyRows.Key[] sc = {KeyRows.k(0x06 | (1 << 8), "复制"), KeyRows.k(0x19 | (1 << 8), "粘贴"), KeyRows.k(0x1B | (1 << 8), "剪切")};
        KeyRows.Row[] b = KeyRows.bottomRows(false, sc);
        // 2026-11-01：逗号句号合成一个键（点一下 , 按住 .），右边放第二个空格（用户"右手边没有空格"）；份数和以前的 , . 一样
        eq(labels(b[0].before) + " | " + labels(b[0].after), "?123 , Space | Space ↵", "底行：空位左边 ?123 [,.] 空格，右边 空格 ↵");
        KeyRows.Key dual = b[0].before[1], right = b[0].after[0];
        check(b[0].before[0] == b[0].whole[0] && b[0].after[1] == b[0].whole[4], "?123 ↵ 是整行时那两个键帽");
        check(dual == KeyRows.COMMA_PERIOD && dual != b[0].whole[1] && dual.id == Proto.dualKey(0x36, 0x37)
                && Proto.dualTap(dual.id) == b[0].whole[1].id && Proto.dualHold(dual.id) == b[0].whole[3].id
                && dual.weight == b[0].whole[1].weight && ".".equals(dual.corner) && !dual.special,
                "合并键：另一个键帽，点一下 = 整行时的 ,、按住 = .，和 , 一样 1 份，右上角小字 .");
        check(right == KeyRows.STICK_SPACE_R && right.id == 0x2C && right != b[0].whole[2] && right.weight == b[0].whole[3].weight,
                "右边的空格：又一个键帽，同一个 id，占 . 原来的 1 份");
        check(!KeyRows.functionKey(dual, false) && !KeyRows.functionKey(right, false), "合并键、右空格是普通键（主次键帽）");
        check(b[0].whole[1].corner == null && b[0].whole[3].corner == null, "整行时的 , . 照旧，没有角上小字");
        KeyRows.Key sp = b[0].before[2];
        check(sp.id == 0x2C && sp != b[0].whole[2] && sp.weight == KeyRows.STICK_SPACE, "缩短的空格：又一个键帽、平时 1.5 份");
        check(b[1].spread && b[1].before == null && b[1].holed() && b[0].holed(), "快捷行按个数分到两边；底行、快捷行都让位");
        KeyRows.Row[] a = KeyRows.bottomRows(true, new KeyRows.Key[0]);
        eq(labels(a[0].before) + " | " + labels(a[0].after), "?123 🌐 , Space | Space ↵", "苹果：🌐 照旧跟在 ?123 后面");
        check(a[0].before[3] == sp && a[0].before[2] == dual && a[0].after[0] == right, "苹果和不连苹果用同一个缩短的空格、合并键、右空格键帽");
        for (KeyRows.Row[] layer : KeyRows.LAYERS) for (KeyRows.Row r : layer) check(!r.holed(), "中间四行不让位");
        check(!KeyRows.TOP_ROWS[0].holed() && !KeyRows.TOP_ROWS_MENU[0].holed(), "功能行不让位");
        // 份数：左边 4（苹果 5）、右边 2.5、整行 10
        float[] u = KeyRows.holeUnits(KeyRows.rows(true, 0, b));
        check(u != null && near(u[0], 4) && near(u[1], 2.5f) && near(u[2], 10), "份数：左 4、右 2.5、整行 10：" + Arrays.toString(u));
        float[] ua = KeyRows.holeUnits(KeyRows.rows(true, 0, a));
        check(ua != null && near(ua[0], 5) && near(ua[1], 2.5f), "苹果：左 5");
        check(KeyRows.holeUnits(KeyRows.LAYERS[0]) == null, "没有底行：null");
        // 让位的行从第几行开始：7 行时第 6 行（下标 5）的上沿；6 行时同样是下标 5
        KeyRows.Row[] seven = KeyRows.rows(true, 0, b), six = KeyRows.rows(true, 0, a);
        check(KeyRows.holeTop(seven, 700) == 500 && KeyRows.holeTop(six, 600) == 500, "让位的行的上沿：底行的上沿");
        check(KeyRows.holeTop(KeyRows.LAYERS[0], 400) == -1, "没有让位的行：-1");
        // 空位的下沿：快捷行也让（spread）时到最下面，不让时到底行的下沿；没有快捷行时两样一样
        check(KeyRows.holeBottom(seven, 700, true) == 700 && KeyRows.holeBottom(seven, 700, false) == 600, "下沿：快捷行让 → 700，不让 → 底行下沿 600");
        check(KeyRows.holeBottom(six, 600, true) == 600 && KeyRows.holeBottom(six, 600, false) == 600, "没有快捷行：都是底行下沿");
        check(KeyRows.holeBottom(KeyRows.LAYERS[0], 400, true) == -1, "没有让位的行：-1");
        // 快捷行分到两边：按宽度比例，两边都有地方时每边至少一个
        check(KeyRows.spreadLeft(9, 400, 300) == 5 && KeyRows.spreadLeft(9, 300, 300) == 5 && KeyRows.spreadLeft(9, 100, 800) == 1,
                "9 个：4 : 3 → 5 个在左；一样宽 → 5（四舍五入）；左边很窄也至少 1 个");
        check(KeyRows.spreadLeft(2, 900, 10) == 1 && KeyRows.spreadLeft(1, 400, 250) == 1 && KeyRows.spreadLeft(1, 200, 250) == 0,
                "2 个：一边一个；1 个：放宽的那边");
        check(KeyRows.spreadLeft(3, 0, 300) == 0 && KeyRows.spreadLeft(3, 300, 0) == 3 && KeyRows.spreadLeft(0, 300, 300) == 0,
                "一边没地方：全放另一边；没有键：0");
        // keys()：让位时底行换成 before + after，顺序同 place；不让位、分裂时照旧
        List<KeyRows.Key> holed = KeyRows.keys(seven, false, true), plain = KeyRows.keys(seven, false);
        check(holed.size() == plain.size() && holed.contains(sp) && !holed.contains(b[0].whole[2]) && !plain.contains(sp),
                "让位：底行的空格换成缩短的那个，别的键不变");
        check(KeyRows.keys(seven, true, true).equals(KeyRows.keys(seven, true)), "分裂时 hole 不起作用");
    }

    /** 让位时的摆法：空位里一个键都没有；?123 , . ↵ 照整行时的宽度，只有空格缩短；快捷行分到两边、顺序不变；别的行和不让位时一模一样。 */
    static void holePlacement() {
        KeyRows.Key[] nine = new KeyRows.Key[9];
        for (int i = 0; i < 9; i++) nine[i] = KeyRows.k((0x04 + i) | (1 << 8), "快" + i);
        for (boolean apple : new boolean[]{false, true}) {
            for (int n : new int[]{9, 3, 1, 0}) {
                KeyRows.Row[] bottom = KeyRows.bottomRows(apple, Arrays.copyOf(nine, n));
                for (int layer = 0; layer < 3; layer++) {
                    KeyRows.Row[] rows = KeyRows.rows(true, layer, bottom);
                    for (int[] wh : new int[][]{{400, 560}, {884, 360}, {1001, 333}}) {
                        int w = wh[0], h = wh[1];
                        float u = w / 10f;
                        int[] hole = {Math.round(u * (apple ? 4.6f : 3.8f)), Math.round(u * 7.2f)};
                        String what = (apple ? "苹果 " : "") + n + " 个快捷键 第 " + layer + " 层 " + w + "×" + h;
                        List<KeyRows.Placed> ps = KeyRows.place(rows, w, h, false, KeyRows.MIDDLE_PAD, GAP, hole, true);
                        List<KeyRows.Placed> base = KeyRows.place(rows, w, h, false, GAP);
                        List<KeyRows.Key> order = KeyRows.keys(rows, false, true);
                        check(ps.size() == order.size(), what + "：每个键都摆了");
                        boolean sameOrder = true;
                        for (int i = 0; i < ps.size() && i < order.size(); i++) sameOrder &= ps.get(i).key == order.get(i);
                        check(sameOrder, what + "：摆放顺序同 keys()");
                        int top = KeyRows.holeTop(rows, h);
                        boolean clear = true, othersSame = true;
                        int bi = 0;
                        for (KeyRows.Placed p : ps) {
                            if (p.top >= top) {
                                clear &= p.right + GAP <= hole[0] || p.left - GAP >= hole[1];
                            } else {
                                KeyRows.Placed q = base.get(bi);
                                othersSame &= q.key == p.key && q.left == p.left && q.top == p.top && q.right == p.right && q.bottom == p.bottom;
                            }
                            bi++;
                        }
                        check(clear, what + "：空位 " + Arrays.toString(hole) + " 里一个键都没有");
                        check(othersSame, what + "：底行上面的行和不让位时一模一样");
                        // 底行（2026-11-01 起 ?123 [,.] 空格 │ 空位 │ 空格 ↵）：?123 和合并键照整行时 ?123 , 的宽度、位置；空格从合并键的右边到空位左边；
                        // ↵ 到最右边；右边那个空格从空位右边开始，和 ↵ 按 1 : 1.5 分（份数同以前的 . ↵）
                        KeyRows.Placed sym = find(ps, "?123"), comma = find(ps, ","), space = find(ps, "Space"), dot = findLast(ps, "Space"), enter = find(ps, "↵");
                        check(comma.key == KeyRows.COMMA_PERIOD && comma.key.id == Proto.dualKey(0x36, 0x37) && ".".equals(comma.key.corner),
                                what + "：逗号的位置是合并键（点一下 , 按住 .，右上角小字 .）");
                        check(dot.key == KeyRows.STICK_SPACE_R && dot.key.id == space.key.id && space.key.id == 0x2C && dot != space,
                                what + "：右边是第二个空格（另一个键帽、同一个 id）");
                        KeyRows.Placed sym0 = find(base, "?123"), comma0 = find(base, ",");
                        check(sym.left == sym0.left && sym.right == sym0.right && comma.left == comma0.left && comma.right == comma0.right,
                                what + "：?123 , 和整行时一样宽、一样位置");
                        check(space.left == comma.right + 2 * GAP && space.right == hole[0] - GAP, what + "：空格伸到空位左边");
                        check(dot.left == hole[1] + GAP && enter.right == w - GAP && Math.abs(1.5f * (dot.right - dot.left + 2 * GAP)
                                - (enter.right - enter.left + 2 * GAP)) <= 2, what + "：右空格 ↵ 铺满空位右边、按 1 : 1.5 分");
                        if (n > 0) {
                            List<KeyRows.Placed> sc = new ArrayList<>();
                            for (KeyRows.Placed p : ps) if (p.key.label.startsWith("快")) sc.add(p);
                            int k = KeyRows.spreadLeft(n, hole[0], w - hole[1]);
                            boolean split = sc.size() == n;
                            for (int i = 0; split && i < n; i++) {
                                KeyRows.Placed p = sc.get(i);
                                split &= p.key == nine[i] && (i < k ? p.right <= hole[0] - GAP : p.left >= hole[1] + GAP);
                            }
                            check(split, what + "：快捷行顺序不变、前 " + k + " 个在空位左边、其余在右边");
                            if (k > 0) check(sc.get(0).left == GAP && sc.get(k - 1).right == hole[0] - GAP, what + "：左边的快捷键铺满空位左边");
                            if (k < n) check(sc.get(k).left == hole[1] + GAP && sc.get(n - 1).right == w - GAP, what + "：右边的铺满空位右边");
                        }
                        // 快捷行不让位（spread 为假）：底行照样让，快捷行和不让位时一模一样（铺满整行）
                        List<KeyRows.Placed> ns = KeyRows.place(rows, w, h, false, KeyRows.MIDDLE_PAD, GAP, hole, false);
                        boolean bottomSame = ns.size() == ps.size(), shortcutsFull = true;
                        for (int i = 0; bottomSame && i < ns.size(); i++) {
                            KeyRows.Placed x = ns.get(i), y = ps.get(i), z = base.get(i);
                            if (x.key.label.startsWith("快")) {
                                shortcutsFull &= x.key == z.key && x.left == z.left && x.top == z.top && x.right == z.right && x.bottom == z.bottom;
                            } else {
                                bottomSame &= x.key == y.key && x.left == y.left && x.top == y.top && x.right == y.right && x.bottom == y.bottom;
                            }
                        }
                        check(bottomSame, what + "：快捷行不让位时别的行（含底行的空位）照旧");
                        check(shortcutsFull, what + "：快捷行不让位时铺满整行、和不让位时一模一样");
                        // 不给 hole：和以前一模一样（不分裂时没有让位）
                        check(sameList(KeyRows.place(rows, w, h, false, KeyRows.MIDDLE_PAD, GAP, null, true), base), what + "：hole = null 照旧");
                    }
                }
            }
        }
    }

    /**
     * 每个会显示出来的键都有键帽：照 KeyboardPanel 建键帽的范围（构造时 TOP_ROWS_MENU + 三层的中间四行，rebuildBottom 时底行 + 快捷行，
     * 都经 KeyRows.capKeys）收一遍，再拿功能行带不带 ⋯ × 三层 × 整块 / 分裂 / 小红点让位时 keys() 给的键去比
     * （2026-11-01：小红点状态的缩短空格只在 before 里，以前没有键帽，一进小红点状态就闪退）。
     */
    static void capKeysCover() {
        KeyRows.Key[] nine = new KeyRows.Key[9];
        for (int i = 0; i < 9; i++) nine[i] = KeyRows.k((0x04 + i) | (1 << 8), "快" + i);
        int missing = 0, combos = 0;
        String first = null;
        for (boolean apple : new boolean[]{false, true}) {
            for (int n : new int[]{9, 3, 1, 0}) {
                KeyRows.Row[] bottom = KeyRows.bottomRows(apple, Arrays.copyOf(nine, n));
                java.util.IdentityHashMap<KeyRows.Key, Boolean> caps = new java.util.IdentityHashMap<>();
                List<KeyRows.Row[]> built = new ArrayList<>();
                built.add(KeyRows.TOP_ROWS_MENU);
                built.addAll(Arrays.asList(KeyRows.LAYERS));
                built.add(bottom);
                boolean dup = false;
                for (KeyRows.Row[] rows : built) {
                    List<KeyRows.Key> ks = KeyRows.capKeys(rows);
                    java.util.IdentityHashMap<KeyRows.Key, Boolean> once = new java.util.IdentityHashMap<>();
                    for (KeyRows.Key k : ks) {
                        dup |= once.put(k, Boolean.TRUE) != null || k.spacer();
                        caps.put(k, Boolean.TRUE);
                    }
                }
                check(!dup, (apple ? "苹果 " : "") + n + " 个快捷键：capKeys 不重复、不含空位");
                for (boolean menu : new boolean[]{false, true}) {
                    for (int layer = 0; layer < 3; layer++) {
                        KeyRows.Row[] rows = KeyRows.rows(menu, layer, bottom);
                        for (boolean[] mode : new boolean[][]{{false, false}, {true, false}, {false, true}}) {
                            combos++;
                            for (KeyRows.Key k : KeyRows.keys(rows, mode[0], mode[1])) {
                                if (caps.containsKey(k)) continue;
                                missing++;
                                if (first == null) first = (apple ? "苹果 " : "") + n + " 个快捷键 第 " + layer + " 层"
                                        + (mode[0] ? " 分裂" : mode[1] ? " 小红点让位" : " 整块") + "：" + k.label + "（" + k.weight + " 份）";
                            }
                        }
                    }
                }
            }
        }
        check(missing == 0, "每个会显示的键都有键帽（" + combos + " 种）" + (first == null ? "" : "，缺的比如 " + first));
        // 缩短的空格就在里面（就是它以前漏了）
        KeyRows.Row[] b = KeyRows.bottomRows(false, new KeyRows.Key[0]);
        check(KeyRows.capKeys(b).contains(b[0].before[b[0].before.length - 1]), "缩短的空格有键帽");
        // 小红点状态的合并键、右边的空格（2026-11-01 新加的两个键帽）同样在里面，连苹果设备那份也是
        for (boolean apple : new boolean[]{false, true}) {
            List<KeyRows.Key> ks = KeyRows.capKeys(KeyRows.bottomRows(apple, new KeyRows.Key[0]));
            boolean dual = false, right = false;
            for (KeyRows.Key k : ks) {
                dual |= k == KeyRows.COMMA_PERIOD;
                right |= k == KeyRows.STICK_SPACE_R;
            }
            check(dual && right, (apple ? "苹果：" : "") + "合并键、右空格有键帽");
        }
    }

    // ── 键帽上的字、高亮（2026-11-01：按住修饰键也换字，Caps 的显示；KeyboardPanel 照它画） ──
    static void capTextAndLit() {
        KeyRows.Key a = null, one = null, at = null, shiftKey = null, caps = null, ctrl = null, fn = null;
        for (KeyRows.Row[] rows : KeyRows.LAYERS) {
            for (KeyRows.Row r : rows) {
                for (KeyRows.Key k : r.whole) {
                    if (k.id == 0x04 && a == null) a = k;
                    if (k.id == 0x1E && k.shifted != null) one = k;
                    if (k.id == (0x1F | (KeyRows.SHIFT << 8))) at = k;
                    if (k.id == 0xE1 && shiftKey == null) shiftKey = k;
                }
            }
        }
        for (KeyRows.Key k : KeyRows.TOP_ROWS_MENU[0].whole) {
            if (k.id == 0x39) caps = k;
            if (k.id == 0xE0) ctrl = k;
            if (k.id == KeyRows.FN_BUTTON_ID) fn = k;
        }
        check(a != null && one != null && at != null && shiftKey != null && caps != null && ctrl != null && fn != null, "找得到 a、1、@、⇧、Caps、Ctrl、Fn");
        if (a == null || one == null || at == null || caps == null || ctrl == null || fn == null) return;
        int sh = KeyRows.SHIFT, rsh = KeyRows.RSHIFT;
        // 字母：别的主机 Shift ⊕ Caps，苹果 Shift ∨ Caps
        eq(KeyRows.capText(a, 0, false, false), "a", "a：什么都没有");
        eq(KeyRows.capText(a, sh, false, false), "A", "a：Shift（锁住或按着）");
        eq(KeyRows.capText(a, rsh, false, false), "A", "a：右 Shift 也算");
        eq(KeyRows.capText(a, 0, true, false), "A", "a：Caps");
        eq(KeyRows.capText(a, sh, true, false), "a", "a：Shift + Caps（Windows / Linux / 安卓）：小写");
        eq(KeyRows.capText(a, sh, true, true), "A", "a：Shift + Caps（苹果）：大写");
        eq(KeyRows.capText(a, 0, true, true), "A", "a：Caps（苹果）");
        eq(KeyRows.capText(a, 0x01 | 0x04 | 0x08, false, false), "a", "a：Ctrl / Alt / Super 不换字");
        // 数字行的上档字只看 Shift，不看 Caps
        eq(KeyRows.capText(one, 0, true, false), "1", "1：Caps 不换");
        eq(KeyRows.capText(one, sh, false, false), "!", "1：Shift 换上档字");
        eq(KeyRows.capText(one, sh, true, false), "!", "1：Shift + Caps 照样是上档字");
        eq(KeyRows.capText(at, sh, true, false), at.label, "符号层的 @：不换");
        check(!KeyRows.letterKey(at) && !KeyRows.letterKey(one) && KeyRows.letterKey(a) && !KeyRows.letterKey(KeyRows.COMMA_PERIOD), "哪些是字母键");
        eq(KeyRows.capText(KeyRows.COMMA_PERIOD, sh, true, false), ",", "合并键：键帽一直写 ,");
        // 高亮：修饰键看 latched | armed（左右八个），Caps 看显示的大写锁定，特殊键不看
        check(KeyRows.lit(shiftKey, sh, false) && !KeyRows.lit(shiftKey, 0, true) && !KeyRows.lit(shiftKey, rsh, false), "⇧：只看左 Shift 那一位");
        check(KeyRows.lit(ctrl, 0x01, false) && !KeyRows.lit(ctrl, 0x10, false), "Ctrl：左 Ctrl 那一位");
        check(KeyRows.lit(caps, 0, true) && !KeyRows.lit(caps, 0xFF, false), "Caps：只看大写锁定的显示");
        check(!KeyRows.lit(fn, 0xFF, true) && !KeyRows.lit(a, 0xFF, true), "Fn、字母：不高亮");
        check(KeyRows.lit(KeyRows.k(0xE6, "AltGr"), 0x40, false), "右 Alt（实体键盘的 AltGr）：0x40");
        check(!KeyRows.lit(KeyRows.k(0xE1 | (1 << 8), "x"), 0xFF, false), "自带修饰键的键不是修饰键");
    }

    private static boolean sameList(List<KeyRows.Placed> a, List<KeyRows.Placed> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            KeyRows.Placed x = a.get(i), y = b.get(i);
            if (x.key != y.key || x.left != y.left || x.top != y.top || x.right != y.right || x.bottom != y.bottom) return false;
        }
        return true;
    }

    // ── 一层的全部行（KeyboardPanel 和 KeyboardPlan 共用）：功能行、中间四行、底行 + 快捷行 ──
    static void layerRows() {
        KeyRows.Row[] bottom = KeyRows.bottomRows(true, new KeyRows.Key[]{KeyRows.k(0x04, "x"), KeyRows.k(0x05, "y")});
        KeyRows.Row[][] all = KeyRows.layers(true, bottom);
        check(all.length == 3, "三层");
        for (int layer = 0; layer < 3; layer++) {
            KeyRows.Row[] r = KeyRows.rows(true, layer, bottom);
            check(r.length == 7 && r[0] == KeyRows.TOP_ROWS_MENU[0] && r[1] == KeyRows.LAYERS[layer][0] && r[5] == bottom[0]
                    && r[6] == bottom[1] && Arrays.equals(r, all[layer]), "第 " + layer + " 层：功能行（带 ⋯）、中间四行、底行、快捷行");
        }
        check(KeyRows.rows(false, 0, KeyRows.bottomRows(false, new KeyRows.Key[0])).length == 6
                && KeyRows.rows(false, 0, bottom)[0] == KeyRows.TOP_ROWS[0], "没有快捷行时 6 行；不带 ⋯ 的功能行");
    }

    private static String labels(KeyRows.Key[] keys) {
        StringBuilder b = new StringBuilder();
        for (KeyRows.Key k : keys) {
            if (b.length() > 0) b.append(' ');
            b.append(k);
        }
        return b.toString();
    }

    private static float units(KeyRows.Key[] keys) {
        float s = 0;
        for (KeyRows.Key k : keys) s += k.weight;
        return s;
    }

    /** 这一行里标着 label 的键左边有多少份。 */
    private static float offset(KeyRows.Key[] keys, String label) {
        float s = 0;
        for (KeyRows.Key k : keys) {
            if (!k.spacer() && k.label.equals(label)) return s;
            s += k.weight;
        }
        throw new IllegalArgumentException(label);
    }

    private static boolean sameKeys(KeyRows.Key[] whole, KeyRows.Key[] parts) {
        List<KeyRows.Key> a = new ArrayList<>(), b = new ArrayList<>();
        for (KeyRows.Key k : whole) if (!k.spacer()) a.add(k);
        for (KeyRows.Key k : parts) if (!k.spacer()) b.add(k);
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** 右半里和左半某个键同一个键（同 id、另一个键帽）的那几个去掉：右半多放的 g、v。 */
    private static KeyRows.Key[] dropCopies(KeyRows.Key[] left, KeyRows.Key[] right) {
        List<KeyRows.Key> out = new ArrayList<>();
        for (KeyRows.Key k : right) {
            boolean copy = false;
            if (!k.spacer()) for (KeyRows.Key l : left) if (l != k && l.id == k.id) copy = true;
            if (!copy) out.add(k);
        }
        return out.toArray(new KeyRows.Key[0]);
    }

    private static KeyRows.Key[] concat(KeyRows.Key[] a, KeyRows.Key[] b) {
        KeyRows.Key[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static KeyRows.Placed find(List<KeyRows.Placed> ps, String label) {
        for (KeyRows.Placed p : ps) if (p.key.label.equals(label)) return p;
        throw new IllegalArgumentException(label);
    }

    private static KeyRows.Placed findLast(List<KeyRows.Placed> ps, String label) {
        KeyRows.Placed last = null;
        for (KeyRows.Placed p : ps) if (p.key.label.equals(label)) last = p;
        if (last == null) throw new IllegalArgumentException(label);
        return last;
    }

    private static int width(List<KeyRows.Placed> ps, String label) {
        KeyRows.Placed p = find(ps, label);
        return p.right - p.left;
    }

    private static String label(List<KeyRows.Placed> ps, TouchRouter.Region r) {
        return r == null || r.id < 0 || r.id >= ps.size() ? "-" : ps.get(r.id).key.label;
    }

    private static boolean inside(TouchRouter.Region r, TouchRouter.Region area) {
        return r.left >= area.left && r.right <= area.right && r.top >= area.top && r.bottom <= area.bottom;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4;
    }

    private static void eq(String got, String want, String what) {
        check(got.equals(want), what + "：想要 [" + want + "]，实际 [" + got + "]");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }

    static final class Quiet implements TouchRouter.Output {
        @Override public void mouse(int buttons, int dx, int dy, int wheel, int pan) { }
        @Override public void keyboard(byte[] report) { }
        @Override public void haptic(int kind) { }
        @Override public void gesture(int fingers, int kind) { }
        @Override public void button(int id, int action) { }
        @Override public void modifiers(int latched, int held, int armed, int fn) { }
        @Override public void caps(boolean sent) { }
        @Override public void schedule(long delayMs) { }
        @Override public void cancelSchedule() { }
        @Override public void scheduleKeys(long delayMs) { }
        @Override public void cancelKeys() { }
        @Override public void startTicks() { }
        @Override public void stopTicks() { }
        @Override public void gamepad(byte[] report) { }
    }
}
