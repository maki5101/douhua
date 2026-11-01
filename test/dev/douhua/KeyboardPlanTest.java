package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * KeyboardPlan 的离线测试：布局按钮的轮换、分不分裂（自动 / 强制分裂 / 合并、键太窄时不分）、合并时触控板放在空得最多的一侧、
 * 太小（剩一条）不放、触控板隐藏、宽窗口没调过大小时的默认位置、调整栏按钮宽度；⋯ 菜单的几何（⋯ 放触控板哪个角、
 * 菜单弹在哪、调整栏放上还是下，再扫一遍各种尺寸查离边够远、不出屏、菜单不压着 ⋯；主界面的 ⋯ 放不下也照样放在触控板里；
 * 菜单项多、窗口矮时怎么排——先压矮再分列，扫一遍查排得下、没有空列、不出页面、高度够时一直一列）；再扫一遍各种尺寸查不变的规矩
 * （触控板在键盘区里、不压着键、够大，分裂了键就够宽）；分裂时对称调大小（默认格子、存过的格子怎么摆、拖法和约束、
 * 窗口变窄时撑开），再扫一遍存过格子的各种情况（两半对称、够宽、不重叠、不出界、够高）。
 * 指点选小红点（2026-11-01 照真的 ThinkPad 键盘摆）：键盘最下面那排三个鼠标键、红点在分裂中间那一列 / 嵌在 g、h、v、b 交界 / 放外面、
 * 嵌在键里多大（每个键让出多少），交给真正的 TouchRouter / KeyFingers 逐点核对红点外和没有红点时一样；三键条。尺寸按 1dp = 1px 算。
 */
public final class KeyboardPlanTest {
    private static int failures;
    private static int checks;

    private static final int GAP = 8, MIN_PAD = 96, MIN_KEY = 32;
    private static final KeyboardPlan.Sizes S = new KeyboardPlan.Sizes(GAP, MIN_PAD, MIN_KEY);

    public static void main(String[] args) {
        modes();
        middle();
        autoMode();
        forcedSplit();
        merged();
        padHidden();
        bar();
        menuCorner();
        padMenuTop();
        menuPosition();
        menuGrid();
        menuGridSweep();
        adjustBar();
        menuSweep();
        sweep();
        splitDefaultCell();
        splitSaved();
        splitDrag();
        splitFit();
        splitSweep();
        pointers();
        stickSplitDefault();
        stickAutoThreshold();
        stickSeam();
        stickMerged();
        stickOutside();
        stickMouseRow();
        stickSavedSplit();
        middleKeys();
        cutBar();
        padKeysInPlan();
        stickRouting();
        stickSweep();
        System.err.println("键盘布局 / 触控板位置：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ── "布局"按钮：自动 → 分裂 → 合并 → 自动 ──
    static void modes() {
        check(KeyboardPlan.nextMode(KeyboardPlan.MODE_AUTO) == KeyboardPlan.MODE_SPLIT, "自动 → 分裂");
        check(KeyboardPlan.nextMode(KeyboardPlan.MODE_SPLIT) == KeyboardPlan.MODE_MERGE, "分裂 → 合并");
        check(KeyboardPlan.nextMode(KeyboardPlan.MODE_MERGE) == KeyboardPlan.MODE_AUTO, "合并 → 自动");
        check(KeyboardPlan.validMode(7) == KeyboardPlan.MODE_AUTO && KeyboardPlan.validMode(-1) == KeyboardPlan.MODE_AUTO,
                "认不出的值当自动");
        check(KeyboardPlan.nextMode(7) == KeyboardPlan.MODE_SPLIT, "认不出的值按自动往下轮");
    }

    // ── 分裂时两半中间多宽（KeyRows.leftEnd 的 middle） ──
    static void middle() {
        check(KeyRows.leftEnd(900, KeyRows.MIDDLE_PAD) == 300 && KeyRows.rightStart(900, KeyRows.MIDDLE_PAD) == 600,
                "中间放触控板：两半各三分之一");
        check(KeyRows.leftEnd(900, 8) == 446 && KeyRows.rightStart(900, 8) == 454, "中间不放触控板：两半伸满，只隔 8");
        check(KeyRows.rightStart(901, 8) - KeyRows.leftEnd(901, 8) == 9, "除不尽时多的一像素归中间");
        check(KeyRows.leftEnd(5, 8) == 0 && KeyRows.rightStart(5, 8) == 5, "缝比整个还宽：两半都是 0 宽，不出负数");
        // 两半伸满时照样按 place 摆：左半的键都在 leftEnd 左边，右半的都在 rightStart 右边
        KeyRows.Row[] rows = KeyRows.LAYERS[KeyRows.LAYER_LETTERS];
        int w = 900, h = 400, le = KeyRows.leftEnd(w, GAP), rs = KeyRows.rightStart(w, GAP);
        List<KeyRows.Placed> ps = KeyRows.place(rows, w, h, true, GAP, 2);
        int i = 0;
        boolean ok = true;
        for (KeyRows.Row row : rows) {
            for (KeyRows.Key k : row.left) if (!k.spacer()) ok &= ps.get(i++).right <= le;
            for (KeyRows.Key k : row.right) if (!k.spacer()) ok &= ps.get(i++).left >= rs;
        }
        check(ok && i == ps.size(), "两半伸满：左半的键在 leftEnd 左边、右半的在 rightStart 右边");
        check(same(KeyRows.place(rows, w, h, true, 2), KeyRows.place(rows, w, h, true, KeyRows.MIDDLE_PAD, 2)),
                "不带 middle 的 place 就是三分之一布局");

        // 交给真正的 TouchRouter（照 KeyboardPanel 报区域：键 + 每半一块范围）：两半之间那条缝不归任何键，两边各归自己那一半
        TouchRouter.Region[] regs = new TouchRouter.Region[ps.size() + 2];
        for (int j = 0; j < ps.size(); j++) {
            KeyRows.Placed q = ps.get(j);
            regs[j] = new TouchRouter.Region(q.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, j, q.left, q.top, q.right, q.bottom);
        }
        regs[ps.size()] = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, le, KeyRows.halfBottom(rows, h, false));
        regs[ps.size() + 1] = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, rs, 0, w, KeyRows.halfBottom(rows, h, true));
        TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
        router.setRegions(regs);
        KeyRows.Placed t = null, y = null;
        for (KeyRows.Placed q : ps) {
            if (q.key.label.equals("t")) t = q;
            if (q.key.label.equals("y")) y = q;
        }
        float rowY = (t.top + t.bottom) / 2f;
        TouchRouter.Region gotT = router.hit(le - 1, rowY), gotY = router.hit(rs, rowY);
        check(gotT != null && ps.get(gotT.id) == t, "两半伸满：左半最右边归 t");
        check(gotY != null && ps.get(gotY.id) == y, "两半伸满：右半最左边归 y");
        check(router.hit((le + rs) / 2f, rowY) == null, "两半伸满：中间那条缝什么都不是");
    }

    // ── 自动 ──
    static void autoMode() {
        KeyboardPlan.Plan p = plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true);
        check(p.split && p.middle == KeyRows.MIDDLE_PAD && p.padSide == KeyboardPlan.PAD_MIDDLE, "宽窗口：分裂，触控板在中间");
        eq(p.keyboard, new int[]{0, 0, 900, 350}, "宽窗口分裂：键盘占满");
        eq(p.pad, new int[]{308, 0, 592, 350}, "宽窗口分裂：触控板在两半中间、左右各隔 8");

        p = plan(KeyboardPlan.MODE_AUTO, true, 400, 800, null, false);
        check(!p.split && p.padSide == KeyboardPlan.PAD_TOP, "竖屏：不分裂，触控板在上方（同以前）");
        eq(p.keyboard, new int[]{0, 240, 400, 800}, "竖屏默认：键盘占下面七成");
        eq(p.pad, new int[]{0, 0, 400, 232}, "竖屏默认：触控板在键盘上方");

        // 宽，但分裂后（中间放触控板）一份只有 500/3/5.5 ≈ 30 < 32：不分，按合并摆（宽窗口默认靠左三分之二、触控板在右）
        p = plan(KeyboardPlan.MODE_AUTO, true, 500, 240, null, true);
        check(!p.split && p.padSide == KeyboardPlan.PAD_RIGHT, "宽但小的窗口：键太窄不分裂，触控板在右");
        eq(p.keyboard, new int[]{0, 0, 333, 240}, "宽窗口合并默认：靠左三分之二、满高");
        // 同一个窗口触控板隐藏：两半伸满一份约 45，够宽，分裂
        p = plan(KeyboardPlan.MODE_AUTO, false, 500, 240, null, true);
        check(p.split && p.middle == GAP && p.pad == null, "宽但小、触控板隐藏：两半伸满就够宽，分裂");

        check(!plan(KeyboardPlan.MODE_AUTO, true, 1999, 1000, null, true).split, "差一点不到两倍：自动不分");
        check(!KeyboardPlan.shouldSplit(KeyboardPlan.MODE_SPLIT, true, 500, 0, S), "高是 0（还没量好）：不分");
    }

    // ── 强制分裂 ──
    static void forcedSplit() {
        // 手机竖屏：三分之一布局一份只有约 24，中间放不了触控板；两半伸满一份约 36，够。触控板去上方
        KeyboardPlan.Plan p = plan(KeyboardPlan.MODE_SPLIT, true, 400, 800, null, false);
        check(p.split && p.middle == GAP && p.padSide == KeyboardPlan.PAD_TOP, "竖屏强制分裂：两半伸满，触控板在上方");
        eq(p.keyboard, new int[]{0, 240, 400, 800}, "窄窗口分裂：占满宽，上下沿照合并时的");
        eq(p.pad, new int[]{0, 0, 400, 232}, "窄窗口分裂：触控板在上方");

        // 用户把竖屏键盘调矮了（下面四成）：分裂也跟着这个高度
        p = plan(KeyboardPlan.MODE_SPLIT, true, 400, 800, new float[]{0.1f, 0.6f, 0.9f, 1}, false);
        eq(p.keyboard, new int[]{0, 480, 400, 800}, "窄窗口分裂：高度照用户调过的，宽度占满");

        // 太窄：两半伸满一份也不到 32（300 宽：约 26）→ 按合并摆
        p = plan(KeyboardPlan.MODE_SPLIT, true, 300, 600, null, false);
        check(!p.split, "太窄的窗口强制分裂也不分（键按不准）");

        // 平板横屏（不到两倍宽）：横屏默认占满，中间放触控板
        p = plan(KeyboardPlan.MODE_SPLIT, true, 1000, 700, null, true);
        check(p.split && p.padSide == KeyboardPlan.PAD_MIDDLE, "平板横屏强制分裂：触控板在中间");
        eq(p.keyboard, new int[]{0, 0, 1000, 700}, "平板横屏强制分裂：占满");

        // 中间、上方、下方都放得下时取大的：上方 1000 × 272 比中间 318 × 301、下方 1000 × 111 大
        p = plan(KeyboardPlan.MODE_SPLIT, true, 1000, 700, new float[]{0, 0.4f, 1, 0.83f}, true);
        int[] k = p.keyboard;
        check(p.split && p.padSide == KeyboardPlan.PAD_TOP, "上方空得多：触控板去上方，两半伸满");
        check(k[1] == 280 && k[3] == 581, "分裂键盘上下沿照合并时的");
        // 宽窗口强制分裂 = 自动分裂
        check(same(plan(KeyboardPlan.MODE_SPLIT, true, 900, 350, null, true), plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true)),
                "宽窗口里强制分裂和自动一样");
    }

    // ── 合并：触控板放在空得最多的那一侧 ──
    static void merged() {
        KeyboardPlan.Plan p = plan(KeyboardPlan.MODE_MERGE, true, 900, 350, null, true);
        check(!p.split && p.padSide == KeyboardPlan.PAD_RIGHT, "宽窗口合并、没调过：触控板在右");
        eq(p.keyboard, new int[]{0, 0, 600, 350}, "宽窗口合并、没调过：键盘靠左三分之二、满高");
        eq(p.pad, new int[]{608, 0, 900, 350}, "宽窗口合并：触控板占右边那块");
        check(Arrays.equals(p.rect, new float[]{0, 0, KeyboardPlan.WIDE_MERGED_WIDTH, 1}), "没调过：plan.rect 是默认位置（调整从这里开始拖）");

        p = plan(KeyboardPlan.MODE_MERGE, true, 900, 350, new float[]{1f / 3, 0, 1, 1}, true);
        check(p.padSide == KeyboardPlan.PAD_LEFT, "键盘拖到右边：触控板跑到左边");
        eq(p.pad, new int[]{0, 0, 292, 350}, "触控板占左边空出来的那块");

        p = plan(KeyboardPlan.MODE_MERGE, true, 900, 350, new float[]{0, 0, 1, 1}, true);
        check(p.pad == null && p.padSide == KeyboardPlan.PAD_NONE, "键盘占满：没地方放触控板");
        check(Arrays.equals(p.rect, new float[]{0, 0, 1, 1}), "调过：plan.rect 是存下的位置");

        // 剩一条：以前只看高 ≥ 56 就放，现在短边 < 96 不放
        p = plan(KeyboardPlan.MODE_MERGE, true, 400, 800, new float[]{0, 0.1f, 1, 1}, false);
        check(p.pad == null, "上方只剩 72 高的一条：不放触控板");
        p = plan(KeyboardPlan.MODE_MERGE, true, 1000, 400, new float[]{0.1f, 0, 1, 1}, true);
        check(p.pad == null, "左边只剩 92 宽的一条（再高也不放）");

        // 面积大的那块：上方 1000 × 232 比左右 192 × 800 大
        p = plan(KeyboardPlan.MODE_MERGE, true, 1000, 800, new float[]{0.2f, 0.3f, 0.8f, 1}, false);
        check(p.padSide == KeyboardPlan.PAD_TOP, "上方面积最大：放上方");
        // 左右一样大：右边优先
        p = plan(KeyboardPlan.MODE_MERGE, true, 1000, 800, new float[]{0.3f, 0, 0.7f, 1}, false);
        check(p.padSide == KeyboardPlan.PAD_RIGHT, "左右一样大：放右边");
        // 键盘调到上半截：下方
        p = plan(KeyboardPlan.MODE_MERGE, true, 400, 800, new float[]{0, 0, 1, 0.6f}, false);
        check(p.padSide == KeyboardPlan.PAD_BOTTOM, "键盘在上面：触控板在下方");
        eq(p.pad, new int[]{0, 488, 400, 800}, "下方那块");

        // 宽窗口默认靠左三分之二只在触控板显示、右边那三分之一放得下它时用；不然照不宽的窗口占满，不留一块空着
        p = plan(KeyboardPlan.MODE_MERGE, false, 2400, 1000, null, true);
        check(p.pad == null, "大的宽窗口合并、触控板关：不放");
        eq(p.keyboard, new int[]{0, 0, 2400, 1000}, "大的宽窗口合并、触控板关：键盘满宽");
        for (int mode = 0; mode < 3; mode++) {
            p = plan(mode, true, 300, 140, null, true);
            check(p.pad == null, "小的宽窗口（右边三分之一只有 92 宽）布局 " + mode + "：放不下触控板");
            if (!p.split) eq(p.keyboard, new int[]{0, 0, 300, 140}, "小的宽窗口布局 " + mode + "：键盘满宽");
        }
        p = plan(KeyboardPlan.MODE_MERGE, true, 600, 90, null, true);
        check(p.pad == null, "矮的宽窗口合并（右边三分之一只有 90 高）：放不下触控板");
        eq(p.keyboard, new int[]{0, 0, 600, 90}, "矮的宽窗口合并：键盘满宽");

        // 宽窗口里自动分裂，所以"宽窗口默认靠左"只在合并（或分裂不成）时用到；横屏不宽时默认照旧占满
        p = plan(KeyboardPlan.MODE_MERGE, true, 1000, 700, null, true);
        eq(p.keyboard, new int[]{0, 0, 1000, 700}, "横屏不到两倍宽、没调过：照旧占满");
        check(p.pad == null, "横屏占满：没有触控板（同以前）");
    }

    // ── 触控板隐藏 ──
    static void padHidden() {
        KeyboardPlan.Plan p = plan(KeyboardPlan.MODE_AUTO, false, 900, 350, null, true);
        check(p.split && p.pad == null && p.middle == GAP, "宽窗口隐藏触控板：分裂，两半伸满只隔一条缝");
        eq(p.keyboard, new int[]{0, 0, 900, 350}, "宽窗口隐藏触控板：键盘占满");
        p = plan(KeyboardPlan.MODE_MERGE, false, 900, 350, null, true);
        check(!p.split && p.pad == null && p.padSide == KeyboardPlan.PAD_NONE, "合并隐藏触控板：不放");
        eq(p.keyboard, new int[]{0, 0, 900, 350}, "宽窗口合并隐藏触控板、没调过：键盘占满宽（三分之二只为腾地方给触控板）");
        p = plan(KeyboardPlan.MODE_MERGE, false, 900, 350, new float[]{0, 0, 0.6f, 1}, true);
        check(p.pad == null, "合并隐藏触控板、调过：不放");
        eq(p.keyboard, new int[]{0, 0, 540, 350}, "合并隐藏触控板、调过：键盘照自己的大小位置，右边空着");
        p = plan(KeyboardPlan.MODE_AUTO, false, 400, 800, null, false);
        check(!p.split && p.pad == null, "竖屏隐藏触控板：上方空着");
    }

    // ── 调整栏按钮宽度 ──
    static void bar() {
        check(KeyboardPlan.barButtonWidth(1080, 2, 20, 8, 104) == 104, "宽窗口：定宽 104");
        // 200 宽：扣掉两边各 20，每格 80，扣掉按钮两边各 8 → 64
        check(KeyboardPlan.barButtonWidth(200, 2, 20, 8, 104) == 64, "窄窗口：一起缩");
        int each = KeyboardPlan.barButtonWidth(200, 2, 20, 8, 104);
        check(2 * (each + 16) <= 200 - 40, "缩了以后离两边屏幕边至少 20");
        check(KeyboardPlan.barButtonWidth(400, 4, 20, 8, 104) == 74, "四个按钮在 400 宽里：74");
        check(KeyboardPlan.barButtonWidth(30, 2, 20, 8, 104) == 0, "窄到放不下：0，不出负数");
        check(KeyboardPlan.barButtonWidth(400, 0, 20, 8, 104) == 0, "没有按钮：0");
    }

    private static final int BW = 56, BH = 36, MARGIN = 0, EDGE = 20;

    // ── ⋯ 放在触控板顶上正中，离任何一条屏幕边至少 EDGE ──
    static void menuCorner() {
        // 手机竖屏合并：触控板在上面、贴着屏幕上边 → 顶上正中，往下推到离上边 20
        int[] c = KeyboardPlan.menuTop(new int[]{0, 0, 400, 232}, 400, 800, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{(400 - BW) / 2, EDGE}, "触控板在上：顶上正中，离屏幕上边推到 20");
        // 触控板在下面（键盘挪到上面）：贴着触控板顶边
        c = KeyboardPlan.menuTop(new int[]{0, 568, 400, 800}, 400, 800, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{(400 - BW) / 2, 568}, "触控板在下：贴着触控板顶边、正中");
        // 宽窗口合并：触控板在右边三分之一，按触控板的中线居中（不是页面的）
        c = KeyboardPlan.menuTop(new int[]{608, 0, 900, 350}, 900, 350, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{(608 + 900 - BW) / 2, EDGE}, "触控板在右边：按触控板居中，离上边 20");
        // 分裂中间
        c = KeyboardPlan.menuTop(new int[]{308, 0, 592, 350}, 900, 350, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{(308 + 592 - BW) / 2, EDGE}, "触控板在两半中间：顶上正中");
        // 触控板不贴边：不用往里推
        c = KeyboardPlan.menuTop(new int[]{100, 100, 300, 300}, 400, 800, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{(400 - BW) / 2, 100}, "触控板四周空着：贴着它的顶边");
        // 触控板贴着屏幕左边、很窄：居中后离左边不到 20 → 往右推，推完还在触控板里
        c = KeyboardPlan.menuTop(new int[]{0, 300, 90, 600}, 400, 800, BW, BH, MARGIN, EDGE);
        eq(c, new int[]{EDGE, 300}, "窄触控板贴左边：往右推到离左边 20");
        // 放不下：触控板太矮（推到离边 20 后出了触控板）→ null，⋯ 去功能行
        check(KeyboardPlan.menuTop(new int[]{0, 0, 400, 50}, 400, 800, BW, BH, MARGIN, EDGE) == null,
                "触控板太矮（离上边 20 放不下 36 高的 ⋯）：放不下");
        check(KeyboardPlan.menuTop(new int[]{0, 0, 60, 300}, 60, 800, BW, BH, MARGIN, EDGE) == null,
                "页面太窄（左右各留 20 放不下 56 宽）：放不下");
        check(KeyboardPlan.menuTop(new int[]{0, 300, 70, 600}, 400, 800, BW, BH, MARGIN, EDGE) == null,
                "窄触控板贴左边、推到离边 20 后出了触控板：放不下");
        check(KeyboardPlan.menuTop(null, 400, 800, BW, BH, MARGIN, EDGE) == null, "没有触控板：null");
    }

    // ── 主界面的 ⋯：放得下同 menuTop，放不下也照样放在触控板里 ──
    static void padMenuTop() {
        int[] pad = {0, 0, 400, 600};
        eq(KeyboardPlan.padMenuTop(pad, 400, 800, BW, BH, MARGIN, EDGE), KeyboardPlan.menuTop(pad, 400, 800, BW, BH, MARGIN, EDGE),
                "放得下：和键盘页一样（顶上正中、离屏幕上边 20）");
        pad = new int[]{68, 0, 400, 800};   // 自定义按键开着：触控板从左边一列后面开始
        eq(KeyboardPlan.padMenuTop(pad, 400, 800, BW, BH, MARGIN, EDGE), new int[]{(68 + 400 - BW) / 2, EDGE}, "按触控板（不是页面）的中线居中");
        // 三键条在上沿：⋯ 在三键条正下方隔一条缝
        eq(KeyboardPlan.padMenuTop(new int[]{0, 60, 400, 800}, 400, 800, BW, BH, GAP, EDGE), new int[]{(400 - BW) / 2, 68},
                "三键条在上沿：三键条下面隔一条缝");
        // 触控板太矮（离上边 20 放不下）：不再管离边，往上挪进触控板
        eq(KeyboardPlan.padMenuTop(new int[]{0, 0, 400, 50}, 400, 800, BW, BH, MARGIN, EDGE), new int[]{(400 - BW) / 2, 50 - BH},
                "太矮：留在触控板里，离上边少一点");
        // 页面太窄：左右各离 20 放不下，居中在触控板里
        eq(KeyboardPlan.padMenuTop(new int[]{0, 0, 60, 300}, 60, 800, BW, BH, MARGIN, EDGE), new int[]{2, EDGE},
                "太窄：在触控板里居中，上边照样离 20");
        // 触控板比 ⋯ 还小：贴着触控板左上角
        eq(KeyboardPlan.padMenuTop(new int[]{100, 100, 140, 120}, 400, 800, BW, BH, MARGIN, EDGE), new int[]{100, 100},
                "比 ⋯ 还小：贴左上角");
        // 扫一遍：放得下 ⋯ 的触控板，⋯ 一定整个在触控板里；放得下 menuTop 的和它一样
        int bad = 0, n = 0;
        String first = null;
        for (int w = 120; w <= 1600; w += 70) {
            for (int h = 100; h <= 1600; h += 70) {
                for (int l = 0; l < w; l += 47) {
                    for (int t = 0; t < h; t += 53) {
                        int[] p = {l, t, w, h};
                        if (w - l < BW || h - t < BH) continue;
                        for (int margin : new int[]{0, GAP}) {
                            int[] at = KeyboardPlan.padMenuTop(p, w, h, BW, BH, margin, EDGE);
                            int[] top = KeyboardPlan.menuTop(p, w, h, BW, BH, margin, EDGE);
                            n++;
                            String why = null;
                            if (at == null) why = "没放";
                            else if (at[0] < p[0] || at[1] < p[1] || at[0] + BW > p[2] || at[1] + BH > p[3]) why = "出了触控板 " + Arrays.toString(at);
                            else if (top != null && !Arrays.equals(top, at)) why = "放得下却和 menuTop 不一样";
                            if (why != null) {
                                bad++;
                                if (first == null) first = Arrays.toString(p) + " 页面 " + w + "×" + h + "：" + why;
                            }
                        }
                    }
                }
            }
        }
        check(bad == 0, "主界面 ⋯ 扫 " + n + " 种都在触控板里（错 " + bad + "，第一个 " + first + "）");
    }

    private static final int MPAD = 6, MGAP = 6, MAX_W = 208, ITEM_H = 44, MIN_ITEM = 36, MIN_COL = 120;

    private static int[] grid(int count, int w, int h) {
        return KeyboardPlan.menuGrid(count, w, h, EDGE, MPAD, MGAP, MAX_W, ITEM_H, MIN_ITEM, MIN_COL);
    }

    // ── 菜单怎么排：先压矮，再分列，最矮的窗口贴边、缩 ──
    static void menuGrid() {
        eq(grid(7, 400, 800), new int[]{1, 7, 208, 44, 220, 12 + 7 * 44 + 6 * 6}, "手机竖屏，键盘屏七项：一列，每项 44");
        eq(grid(4, 400, 800), new int[]{1, 4, 208, 44, 220, 12 + 4 * 44 + 3 * 6}, "主界面四项：一列，每项 44");
        eq(grid(7, 800, 360), new int[]{1, 7, 208, 38, 220, 12 + 7 * 38 + 6 * 6}, "手机横屏全屏：还是一列，每项压到 38");
        eq(grid(7, 800, 300), new int[]{2, 4, 208, 44, 12 + 2 * 208 + 6, 12 + 4 * 44 + 3 * 6}, "再矮：一列压到 36 也放不下，分两列（4 + 3）");
        eq(grid(7, 260, 250), new int[]{2, 4, 121, 44, 260, 12 + 4 * 44 + 3 * 6}, "小窗：离边 20 时两列太窄，贴边放两列");
        eq(grid(7, 200, 160), new int[]{2, 4, 91, 32, 200, 12 + 4 * 32 + 3 * 6}, "最矮的小窗：列宽放宽到 60、每项最高的那种");
        eq(grid(4, 800, 150), new int[]{2, 2, 208, 44, 12 + 2 * 208 + 6, 12 + 2 * 44 + 6}, "主界面四项、窗口很矮：两列两行");
        int[] g = grid(7, 2000, 100);
        check(g[1] * g[0] >= 7 && g[5] <= 100 && g[4] <= 2000, "又宽又矮：排得下、不出页面（实际 " + Arrays.toString(g) + "）");
        g = grid(4, 3000, 3000);
        check(g[0] == 1 && g[2] == 208 && g[3] == 44, "地方很大：一列、最宽 208、每项 44（不撑大）");
        g = grid(0, 400, 800);
        check(g[0] == 1 && g[1] == 1, "没有项（不该发生）：当一项排，不出 0 行");
    }

    // ── 扫一遍：菜单排得下所有项、没有空着的一列、不出页面；高度够的时候一直是一列；配上 menuPosition 不出屏 ──
    static void menuGridSweep() {
        int bad = 0, n = 0;
        String first = null;
        for (int count : new int[]{4, 7}) {
            for (int w = 200; w <= 2600; w += 40) {
                for (int h = 150; h <= 2400; h += 30) {
                    int[] g = grid(count, w, h);
                    n++;
                    String why = null;
                    int cols = g[0], rows = g[1];
                    if (cols * rows < count) why = "放不下所有项";
                    else if ((cols - 1) * rows >= count) why = "多出一列空的";
                    else if (g[4] != 2 * MPAD + cols * g[2] + (cols - 1) * MGAP || g[5] != 2 * MPAD + rows * g[3] + (rows - 1) * MGAP) why = "宽高算错";
                    else if (g[4] > w || g[5] > h) why = "出了页面";
                    else if (g[2] > MAX_W || g[3] > ITEM_H) why = "比最大的还大";
                    else if (h >= 2 * EDGE + 2 * MPAD + count * MIN_ITEM + (count - 1) * MGAP && cols != 1) why = "高度够一列却分了列";
                    else if (w >= 300 && h >= 250 && g[3] < MIN_ITEM) why = "普通的小窗里项被压得比 36 还矮";
                    if (why == null) {
                        // ⋯ 在页面上随便几个地方，菜单都不出页面
                        int[][] anchors = {{w / 2 - 28, 20, w / 2 + 28, 56}, {w - 60, h - 50, w - 20, h - 20}, {10, h / 2, 50, h / 2 + 36}};
                        for (int[] a : anchors) {
                            for (boolean center : new boolean[]{true, false}) {
                                int[] m = KeyboardPlan.menuPosition(a, g[4], g[5], w, h, EDGE, 8, center);
                                if (m[0] < 0 || m[1] < 0 || m[0] + g[4] > w || m[1] + g[5] > h) why = "菜单出屏 " + Arrays.toString(m);
                            }
                        }
                    }
                    if (why != null) {
                        bad++;
                        if (first == null) first = count + " 项、" + w + "×" + h + "：" + why + " " + Arrays.toString(g);
                    }
                }
            }
        }
        check(bad == 0, "菜单排法扫 " + n + " 种不出错（错 " + bad + "，第一个 " + first + "）");
    }

    // ── 菜单贴着 ⋯ 弹出，不出屏 ──
    static void menuPosition() {
        int mw = 220, mh = 212;
        // ⋯ 在右半：右边对齐，下面放得下就放下面
        int[] a = {332, 184, 380, 224};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8), new int[]{380 - mw, 232}, "⋯ 在右半：右对齐、放下面");
        // ⋯ 在左半：左边对齐
        a = new int[]{28, 184, 76, 224};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8), new int[]{28, 232}, "⋯ 在左半：左对齐");
        // 下面放不下：放上面
        a = new int[]{332, 700, 380, 740};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8), new int[]{380 - mw, 700 - 8 - mh}, "下面放不下：放上面");
        // 功能行最右边的键贴着屏幕右边：往里挪到离右边 20
        a = new int[]{360, 240, 400, 290};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8), new int[]{400 - EDGE - mw, 298}, "⋯ 键贴着右边：菜单离右边 20");
        // 上下都放不下（横屏矮窗口）：放空得多的那边，再挪进屏里
        a = new int[]{800, 150, 848, 190};
        int[] p = KeyboardPlan.menuPosition(a, mw, mh, 900, 350, EDGE, 8);
        check(p[1] >= EDGE && p[1] + mh <= 350 - EDGE, "上下都放不下：挪进屏里、离上下边 20（实际 " + Arrays.toString(p) + "）");
        // 页面比菜单还小一点：少留边，但不出屏
        p = KeyboardPlan.menuPosition(new int[]{100, 50, 140, 90}, mw, mh, 240, 230, EDGE, 8);
        check(p[0] >= 0 && p[0] + mw <= 240 && p[1] >= 0 && p[1] + mh <= 230, "页面只比菜单大一点：不出屏（实际 " + Arrays.toString(p) + "）");
        // 小窗 320×330、触控板关着，⋯ 是功能行最右边的键：上下都放不下、挪进屏后压着 ⋯ → 横着放到 ⋯ 左边
        a = new int[]{296, 100, 318, 140};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 320, 330, EDGE, 8), new int[]{296 - 8 - mw, 330 - EDGE - mh}, "竖着躲不开 ⋯：放到它左边");
        // ⋯ 在左半：放到它右边
        a = new int[]{2, 100, 24, 140};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 320, 330, EDGE, 8), new int[]{24 + 8, 330 - EDGE - mh}, "竖着躲不开 ⋯（在左半）：放到它右边");
        // 触控板顶上正中的 ⋯：菜单和它居中对齐、放下面
        a = new int[]{172, 20, 228, 56};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8, true), new int[]{200 - mw / 2, 64}, "触控板顶上的 ⋯：居中对齐、放下面");
        // 居中对齐但 ⋯ 偏右、菜单居中会出右边：挪回离右边 20
        a = new int[]{330, 20, 386, 56};
        eq(KeyboardPlan.menuPosition(a, mw, mh, 400, 800, EDGE, 8, true), new int[]{400 - EDGE - mw, 64}, "居中会出屏：挪回离右边 20");
    }

    // ── 调整栏：哪边压着键盘少放哪边，离边 20 ──
    static void adjustBar() {
        check(KeyboardPlan.adjustBarTop(800, 36, EDGE, 240, 800) == EDGE, "键盘在下面七成：栏在上面，离上边 20");
        check(KeyboardPlan.adjustBarTop(800, 36, EDGE, 0, 500) == 800 - EDGE - 36, "键盘挪到上面：栏在下面，离下边 20");
        check(KeyboardPlan.adjustBarTop(350, 36, EDGE, 0, 350) == EDGE, "键盘占满高：叠在上面");
        check(KeyboardPlan.adjustBarTop(800, 36, EDGE, 30, 700) == 800 - EDGE - 36, "上面压得多：放下面");
        check(KeyboardPlan.adjustBarTop(60, 36, EDGE, 0, 60) == 12, "页面太矮：竖着居中");
    }

    // ── 扫一遍：各种尺寸、布局、触控板开关下，触控板上的 ⋯ 离边够远、在触控板里，菜单不出屏、旁边放得下时不压着 ⋯ ──
    static void menuSweep() {
        int bad = 0, n = 0, onPad = 0;
        String first = null;
        int[] modes = {KeyboardPlan.MODE_AUTO, KeyboardPlan.MODE_SPLIT, KeyboardPlan.MODE_MERGE};
        for (int w = 200; w <= 2600; w += 100) {
            for (int h = 150; h <= 2400; h += 90) {
                for (int mode : modes) {
                    for (boolean padOn : new boolean[]{true, false}) {
                        KeyboardPlan.Plan p = KeyboardPlan.plan(mode, padOn, w, h, null, w < h ? false : true, S);
                        int[] c = KeyboardPlan.menuTop(p.pad, w, h, BW, BH, MARGIN, EDGE);
                        String why = null;
                        if (p.pad == null && c != null) why = "没有触控板却放了 ⋯";
                        int[] anchor;
                        if (c != null) {
                            onPad++;
                            if (c[0] < EDGE || c[1] < EDGE || c[0] + BW > w - EDGE || c[1] + BH > h - EDGE) why = "⋯ 离边不到 20";
                            int[] pad = p.pad;
                            if (c[0] < pad[0] || c[1] < pad[1] || c[0] + BW > pad[2] || c[1] + BH > pad[3]) why = "⋯ 出了触控板";
                            anchor = new int[]{c[0], c[1], c[0] + BW, c[1] + BH};
                        } else {
                            anchor = new int[]{p.keyboard[2] - 40, p.keyboard[1], p.keyboard[2], p.keyboard[1] + 30};
                        }
                        int mw = Math.min(220, w), mh = Math.min(212, h);
                        int[] m = KeyboardPlan.menuPosition(anchor, mw, mh, w, h, EDGE, 8, c != null);
                        if (m[0] < 0 || m[1] < 0 || m[0] + mw > w || m[1] + mh > h) why = "菜单出屏 " + Arrays.toString(m);
                        // ⋯ 那一侧横着放得下菜单时（menuPosition 的离边规则），菜单不压着 ⋯
                        int mx = Math.max(0, Math.min(EDGE, (w - mw) / 2));
                        boolean rightSide = anchor[0] + anchor[2] >= w;
                        boolean besideFits = rightSide ? anchor[0] - 8 - mw >= mx : anchor[2] + 8 + mw <= w - mx;
                        boolean overlaps = m[0] < anchor[2] && anchor[0] < m[0] + mw && m[1] < anchor[3] && anchor[1] < m[1] + mh;
                        if (besideFits && overlaps) why = "菜单压着 ⋯ " + Arrays.toString(m);
                        n++;
                        if (why != null) {
                            bad++;
                            if (first == null) first = w + "×" + h + " mode " + mode + " pad " + padOn + "：" + why;
                        }
                    }
                }
            }
        }
        check(bad == 0, "⋯ / 菜单扫 " + n + " 种（⋯ 在触控板上 " + onPad + " 种）不出错（错 " + bad + "，第一个 " + first + "）");
        check(onPad > n / 4, "触控板显示时大多放得下 ⋯（" + onPad + " / " + n + "）");
    }

    // ── 扫一遍：不管什么尺寸、布局、存的位置，规矩都守住 ──
    static void sweep() {
        float[][] saves = {null, {0, 0, 1, 1}, {0, 0.3f, 1, 1}, {0.4f, 0, 1, 1}, {0, 0, 0.6f, 1}, {0.1f, 0.1f, 0.9f, 0.7f},
                {0.3f, 0.5f, 0.7f, 0.75f}};
        int bad = 0, n = 0;
        String first = null;
        for (int w = 100; w <= 2600; w += 75) {
            for (int h = 60; h <= 1900; h += 65) {
                for (int mode = 0; mode < 3; mode++) {
                    for (int pad = 0; pad < 2; pad++) {
                        for (float[] saved : saves) {
                            for (int land = 0; land < 2; land++) {
                                n++;
                                String why = violation(mode, pad == 1, w, h, saved, land == 1);
                                if (why != null) {
                                    bad++;
                                    if (first == null) first = w + "×" + h + " 布局 " + mode + " 触控板 " + pad + " 存的 "
                                            + Arrays.toString(saved) + "：" + why;
                                }
                            }
                        }
                    }
                }
            }
        }
        check(bad == 0, "扫了 " + n + " 种，" + bad + " 种不对" + (first == null ? "" : "，比如 " + first));
    }

    private static String violation(int mode, boolean padOn, int w, int h, float[] saved, boolean land) {
        KeyboardPlan.Plan p = KeyboardPlan.plan(mode, padOn, w, h, saved, land, S);
        int[] k = p.keyboard;
        if (k[0] < 0 || k[1] < 0 || k[2] > w || k[3] > h || k[2] < k[0] || k[3] < k[1]) return "键盘出了键盘区";
        if (mode == KeyboardPlan.MODE_MERGE && p.split) return "合并却分裂了";
        if (p.split && KeyRows.splitUnit(w, p.middle) < MIN_KEY) return "分裂了但键太窄";
        if (p.split && (k[0] != 0 || k[2] != w)) return "分裂没占满宽";
        if (mode == KeyboardPlan.MODE_AUTO && p.split && !KeyRows.shouldSplit(w, h)) return "自动在窄窗口分裂了";
        if (p.split && (p.middle == KeyRows.MIDDLE_PAD) != (p.padSide == KeyboardPlan.PAD_MIDDLE)) {
            return "触控板不在中间却用三分之一布局（或反过来）";
        }
        if (!padOn && p.pad != null) return "隐藏了还放触控板";
        if (saved == null && !p.split && p.pad == null && (k[0] != 0 || k[2] != w)) return "没调过、没放触控板，键盘却没占满宽";
        if ((p.pad == null) != (p.padSide == KeyboardPlan.PAD_NONE)) return "padSide 和 pad 对不上";
        if (p.split && p.seam != (p.middle == GAP)) return "只隔一条缝和 seam 对不上";
        if (p.split && (p.splitCell == null || p.leftHalf == null)) return "分裂了却没有格子 / 两半";
        if (p.pad == null) return null;
        int[] r = p.pad;
        if (r[0] < 0 || r[1] < 0 || r[2] > w || r[3] > h) return "触控板出了键盘区";
        if (Math.min(r[2] - r[0], r[3] - r[1]) < MIN_PAD) return "触控板太小";
        if (p.padSide == KeyboardPlan.PAD_MIDDLE) {
            // 在两半之间、和键盘一样高，左右离两半各至少一条缝
            if (r[0] < KeyRows.leftEnd(w) + GAP || r[2] > KeyRows.rightStart(w) - GAP) return "中间的触控板压着两半";
            if (r[1] != k[1] || r[3] != k[3]) return "中间的触控板和键盘不一样高";
        } else if (r[0] < k[2] + GAP && k[0] < r[2] + GAP && r[1] < k[3] + GAP && k[1] < r[3] + GAP) {
            return "触控板压着键盘（或没隔开一条缝）";
        }
        return null;
    }

    // ── 分裂：没存过格子时，默认摆法折算成的格子 ──
    static void splitDefaultCell() {
        KeyboardPlan.Plan p = plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true);
        check(p.split && !p.seam, "三分之一布局：两半之间有触控板，不是一条缝");
        near(p.splitCell, new float[]{0, 0, (300 + GAP / 2f) / 900, 1}, "三分之一布局的格子：右边量到两半中间那条线（连着半条缝）");
        eq(p.leftHalf, new int[]{0, 0, 300, 350}, "三分之一布局：左半");
        eq(p.rightHalf, new int[]{600, 0, 900, 350}, "三分之一布局：右半");
        // 把默认格子当存过的喂回去：摆出来一模一样（从默认开始拖、还没拖动时不跳）
        KeyboardPlan.Plan q = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, p.splitCell, true, S);
        check(sameHalves(p, q) && Arrays.equals(p.pad, q.pad) && p.padSide == q.padSide, "默认格子喂回去：两半、触控板都不变");

        p = plan(KeyboardPlan.MODE_AUTO, false, 900, 350, null, true);
        check(p.seam && p.splitCell[2] == KeyboardPlan.SPLIT_MEET, "触控板隐藏：两半伸满，格子右边 = 0.5");
        q = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, false, 900, 350, null, p.splitCell, true, S);
        check(sameHalves(p, q) && q.seam && q.middle == GAP, "伸满的格子喂回去：一样，只隔一条缝");

        // 竖屏强制分裂：格子的上下沿照合并时的（下面七成）
        p = plan(KeyboardPlan.MODE_SPLIT, true, 400, 800, null, false);
        near(p.splitCell, new float[]{0, 0.3f, 0.5f, 1}, "竖屏强制分裂的格子：上下沿照合并、伸满");
        q = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 400, 800, null, p.splitCell, false, S);
        check(sameHalves(p, q) && q.padSide == p.padSide && Arrays.equals(q.pad, p.pad), "竖屏强制分裂的格子喂回去：一样");

        check(plan(KeyboardPlan.MODE_MERGE, true, 900, 350, null, true).splitCell == null, "合并：没有格子");
    }

    // ── 分裂：存过的格子怎么摆 ──
    static void splitSaved() {
        // 1000 × 400：左半从 50 到 round(300 − 4) = 296，右半镜像 704–950，中间 408 宽放触控板
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 1000, 400, null,
                new float[]{0.05f, 0.1f, 0.3f, 0.9f}, true, S);
        eq(p.keyboard, new int[]{50, 40, 950, 360}, "存过的格子：面板从左半左边到右半右边");
        eq(p.leftHalf, new int[]{50, 40, 296, 360}, "存过的格子：左半");
        eq(p.rightHalf, new int[]{704, 40, 950, 360}, "存过的格子：右半是镜像");
        check(p.middle == 408 && !p.seam, "中间隔 408，不是一条缝");
        check(p.padSide == KeyboardPlan.PAD_MIDDLE, "中间够宽：触控板在中间");
        eq(p.pad, new int[]{304, 40, 696, 360}, "中间的触控板离两半各一条缝、和两半一样高");
        check(KeyRows.leftEnd(p.keyboard[2] - p.keyboard[0], p.middle) == 246, "面板里按 middle 算出来的一半就是 246 宽");

        // 内侧边往中间拖：中间窄到放不下触控板（408 → 108，扣两条缝剩 92 < 96）就不放中间；上下只剩 32 高，也不放
        p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 1000, 400, null, new float[]{0.05f, 0.1f, 0.45f, 0.9f}, true, S);
        check(p.middle == 108 && !p.seam && p.pad == null, "中间 108：放不下触控板，两半不动、中间空着");
        // 键盘调矮了：下方够大，触控板去下方
        p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 1000, 400, null, new float[]{0.05f, 0, 0.45f, 0.6f}, true, S);
        check(p.padSide == KeyboardPlan.PAD_BOTTOM, "中间放不下、下方够：触控板去下方");
        eq(p.pad, new int[]{0, 248, 1000, 400}, "下方那块");
        // 合拢：只隔一条缝，范围伸到缝的中线
        p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 1000, 400, null, new float[]{0.05f, 0.1f, 0.5f, 0.9f}, true, S);
        check(p.seam && p.middle == GAP, "格子右边 0.5：两半只隔一条缝");
        check(p.leftHalf[2] + GAP == p.rightHalf[0], "只隔一条缝：左半右边 + 缝 = 右半左边");
        // 触控板关着：存过的照样不往中间伸
        p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, false, 1000, 400, null, new float[]{0.05f, 0.1f, 0.3f, 0.9f}, true, S);
        check(p.pad == null && p.middle == 408 && !p.seam, "触控板关着：两半留在调的位置，中间空着");
        // 合并键盘存的位置不影响分裂；分裂的格子也不影响合并
        KeyboardPlan.Plan a = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, 1000, 400, new float[]{0.2f, 0.5f, 0.8f, 1},
                new float[]{0.05f, 0.1f, 0.3f, 0.9f}, true, S);
        check(Arrays.equals(a.leftHalf, new int[]{50, 40, 296, 360}), "宽窗口分裂：合并存的位置不影响存过的格子");
        KeyboardPlan.Plan m = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, true, 1000, 400, new float[]{0.2f, 0.5f, 0.8f, 1},
                new float[]{0.05f, 0.1f, 0.3f, 0.9f}, true, S);
        eq(m.keyboard, new int[]{200, 200, 800, 400}, "合并：照合并存的位置，不看分裂的格子");
        // 存过格子不改变分不分裂（自动、窄窗口照样不分）
        check(!KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, true, 400, 800, null, new float[]{0, 0.5f, 0.5f, 1}, false, S).split,
                "自动、竖屏：存过格子也不分裂");
    }

    // ── 分裂：拖法和约束（KeyboardPlan.dragSplit）──
    static void splitDrag() {
        int w = 1000;
        float min = KeyboardPlan.minCell(w, S);   // (176 + 4 + 1) / 1000
        check(KeyboardPlan.minHalf(S) == 176, "一半至少 5.5 份 × 32 = 176");
        float[] c = {0.05f, 0.1f, 0.3f, 0.9f};
        KeyboardPlan.dragSplit(c, KeyboardSnap.RIGHT, 0.1f, 0.3f, w, S);
        near(c, new float[]{0.05f, 0.1f, 0.4f, 0.9f}, "拖内侧边往中间：两半一起变宽（上下位移不管）");
        KeyboardPlan.dragSplit(c, KeyboardSnap.RIGHT, 1, 0, w, S);
        check(c[2] == KeyboardPlan.SPLIT_MEET, "拖内侧边过头：停在两半只隔一条缝，不重叠");
        KeyboardPlan.dragSplit(c, KeyboardSnap.RIGHT, -1, 0, w, S);
        near(c[2], 0.05f + min, "拖内侧边往外过头：停在一半最窄");
        KeyboardPlan.dragSplit(c, KeyboardSnap.LEFT, 1, 0, w, S);
        near(c[0], c[2] - min, "拖外侧边往里过头：停在一半最窄");
        KeyboardPlan.dragSplit(c, KeyboardSnap.LEFT, -1, 0, w, S);
        check(c[0] == 0, "拖外侧边往外过头：停在屏幕边");

        c = new float[]{0, 0.3f, 0.5f, 0.8f};
        KeyboardPlan.dragSplit(c, KeyboardSnap.MOVE, 0.2f, -0.1f, w, S);
        near(c, new float[]{0, 0.2f, 0.5f, 0.7f}, "拖中间：只上下挪，左右位移不要（两半对称）");
        KeyboardPlan.dragSplit(c, KeyboardSnap.MOVE, 0, -1, w, S);
        near(c, new float[]{0, 0, 0.5f, 0.5f}, "拖中间往上过头：停在顶上，高度不变");
        KeyboardPlan.dragSplit(c, KeyboardSnap.MOVE, 0, 1, w, S);
        near(c, new float[]{0, 0.5f, 0.5f, 1}, "拖中间往下过头：停在底下，高度不变");
        KeyboardPlan.dragSplit(c, KeyboardSnap.TOP, 0, 1, w, S);
        near(c[1], 1 - KeyboardPlan.MIN_HEIGHT, "拖上边往下过头：停在最矮");
        KeyboardPlan.dragSplit(c, KeyboardSnap.TOP, 0, -1, w, S);
        check(c[1] == 0, "拖上边往上过头：停在顶上");
        KeyboardPlan.dragSplit(c, KeyboardSnap.BOTTOM, 0, -1, w, S);
        near(c[3], KeyboardPlan.MIN_HEIGHT, "拖下边往上过头：停在最矮");

        // 拖完照样摆得出来：一半够宽（拖到最窄时正好够）
        c = new float[]{0.2f, 0, 0.5f, 1};
        KeyboardPlan.dragSplit(c, KeyboardSnap.RIGHT, -1, 0, w, S);
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, true, w, 400, null, c, true, S);
        int half = p.leftHalf[2] - p.leftHalf[0];
        check(half >= KeyboardPlan.minHalf(S) && half <= KeyboardPlan.minHalf(S) + 1, "拖到最窄：一半正好 176（取整差一像素）实际 " + half);

        // 按在哪一半：两半合拢时两条内侧边的把手范围（各 ±28dp）重叠，按离哪条近算哪一半
        c = new float[]{0.05f, 0, 0.5f, 1};
        p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, false, w, 400, null, c, true, S);
        int li = p.leftHalf[2], ri = p.rightHalf[0];
        check(ri - li == GAP, "两半合拢：只隔一条缝");
        check(KeyboardPlan.onRightHalf(ri + 10, li, ri), "合拢时按在右半内侧边往里 10：算右半（要镜像）");
        check(!KeyboardPlan.onRightHalf(li - 10, li, ri), "合拢时按在左半内侧边往里 10：算左半");
        check(KeyboardPlan.onRightHalf(ri - 2, li, ri) && !KeyboardPlan.onRightHalf(li + 2, li, ri), "缝里按中线分");
        // 按在右半内侧边上往右拖 0.05：镜像成左半内侧边往左，两半一起变窄、右半内侧边跟着手指往右
        boolean mirror = KeyboardPlan.onRightHalf(ri + 10, li, ri);
        KeyboardPlan.dragSplit(c, KeyboardSnap.RIGHT, mirror ? -0.05f : 0.05f, 0, w, S);
        KeyboardPlan.Plan q = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, false, w, 400, null, c, true, S);
        check(q.leftHalf[2] < li && q.rightHalf[0] > ri, "合拢时拖右半内侧边往右：两半一起变窄（左 " + q.leftHalf[2] + " 右 " + q.rightHalf[0] + "）");
        check(Math.abs(q.rightHalf[0] - ri - 50) <= 2, "右半内侧边跟着手指走约 50 像素，实际 " + (q.rightHalf[0] - ri));
        // 两半拉开但不到两个把手宽：重叠的那段同样按中线分
        check(KeyboardPlan.onRightHalf(505, 480, 520) && !KeyboardPlan.onRightHalf(495, 480, 520), "隔 40 时按中线分");
    }

    // ── 分裂：存过的格子在变窄的窗口里撑开（KeyboardPlan.fitSplit）──
    static void splitFit() {
        // 500 宽：一半要 176，格子至少 (176 + 5) / 500 = 0.362；先往中间伸到 0.5，再往外伸
        float[] f = KeyboardPlan.fitSplit(new float[]{0.3f, 0, 0.4f, 1}, 500, S);
        near(f, new float[]{0.5f - KeyboardPlan.minCell(500, S), 0, 0.5f, 1}, "窄窗口：先往中间伸满，再往外伸");
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, false, 500, 240, null, new float[]{0.3f, 0, 0.4f, 1}, true, S);
        check(p.split && p.leftHalf[2] - p.leftHalf[0] >= 176, "窄窗口：撑开以后一半够宽");
        near(p.splitCell, f, "plan 给的格子是撑开以后的（调整从它开始拖）");
        float[] in = {0.3f, 0, 0.4f, 1};
        KeyboardPlan.fitSplit(in, 500, S);
        near(in, new float[]{0.3f, 0, 0.4f, 1}, "fitSplit 不改传进来的（存的那份）");
        // 宽窗口里够宽的不动
        near(KeyboardPlan.fitSplit(new float[]{0.05f, 0.1f, 0.3f, 0.9f}, 1000, S), new float[]{0.05f, 0.1f, 0.3f, 0.9f}, "够宽：不动");
        // 坏值（出界、重叠、太矮）也收拾干净
        near(KeyboardPlan.fitSplit(new float[]{-0.2f, 0.9f, 0.8f, 0.95f}, 1000, S),
                new float[]{0, 1 - KeyboardPlan.MIN_HEIGHT, 0.5f, 1}, "出界、重叠、太矮：夹回来");
        // 贴边伸满都不够的窗口本来就不分裂
        check(!KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, false, 300, 200, null, new float[]{0, 0, 0.5f, 1}, true, S).split,
                "贴边伸满也不够宽：不分裂");
    }

    // ── 分裂：存过格子的各种情况扫一遍 ──
    static void splitSweep() {
        float[] ls = {0, 0.02f, 0.1f, 0.25f, 0.4f}, rs = {0.15f, 0.3f, 0.42f, 0.49f, 0.5f}, ts = {0, 0.2f, 0.5f}, hs = {0.25f, 0.5f, 1};
        int bad = 0, n = 0;
        String first = null;
        for (int w = 300; w <= 2600; w += 115) {
            for (int h = 100; h <= 1500; h += 140) {
                for (int pad = 0; pad < 2; pad++) {
                    for (float l : ls) for (float r : rs) for (float t : ts) for (float hh : hs) {
                        float[] cell = {l, t, r, Math.min(1, t + hh)};
                        n++;
                        String why = splitViolation(pad == 1, w, h, cell);
                        if (why != null) {
                            bad++;
                            if (first == null) first = w + "×" + h + " 触控板 " + pad + " 格子 " + Arrays.toString(cell) + "：" + why;
                        }
                    }
                }
            }
        }
        check(bad == 0, "存过格子扫了 " + n + " 种，" + bad + " 种不对" + (first == null ? "" : "，比如 " + first));
    }

    private static String splitViolation(boolean padOn, int w, int h, float[] cell) {
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, padOn, w, h, null, cell, true, S);
        if (!p.split) return KeyboardPlan.shouldSplit(KeyboardPlan.MODE_SPLIT, padOn, w, h, S) ? "该分裂没分" : null;
        int[] k = p.keyboard, a = p.leftHalf, b = p.rightHalf;
        if (k[0] < 0 || k[1] < 0 || k[2] > w || k[3] > h) return "键盘出了键盘区";
        if (a[0] != k[0] || b[2] != k[2] || a[0] != w - b[2]) return "两半不对称（外侧边）";
        if (a[2] - a[0] != b[2] - b[0]) return "两半不一样宽";
        if (a[2] - a[0] < KeyboardPlan.minHalf(S)) return "一半太窄：" + (a[2] - a[0]);
        if (b[0] - a[2] < GAP) return "两半重叠或者没隔开一条缝";
        int pw = k[2] - k[0];
        if (b[0] - a[2] != KeyRows.rightStart(pw, p.middle) - KeyRows.leftEnd(pw, p.middle)) return "两半的间隔和面板按 middle 摆的对不上";
        if (b[0] - a[2] > p.middle + 1) return "两半的间隔比 middle 多出不止除不尽的一像素";
        if (KeyRows.leftEnd(pw, p.middle) != a[2] - a[0]) return "面板按 middle 算的一半和 leftHalf 对不上";
        if (p.seam != (p.middle == GAP)) return "seam 不对";
        if (k[3] - k[1] < Math.round(KeyboardPlan.MIN_HEIGHT * h) - 1) return "太矮";
        if (!padOn && p.pad != null) return "隐藏了还放触控板";
        if (p.pad == null) return null;
        int[] r = p.pad;
        if (r[0] < 0 || r[1] < 0 || r[2] > w || r[3] > h) return "触控板出了键盘区";
        if (Math.min(r[2] - r[0], r[3] - r[1]) < MIN_PAD) return "触控板太小";
        if (p.padSide == KeyboardPlan.PAD_MIDDLE) {
            if (p.seam) return "只隔一条缝却在中间放触控板";
            if (r[0] < a[2] + GAP || r[2] > b[0] - GAP || r[1] != k[1] || r[3] != k[3]) return "中间的触控板压着两半或者不一样高";
        } else if (r[1] < k[3] + GAP && k[1] < r[3] + GAP) {
            return "上 / 下的触控板压着键盘";
        }
        return null;
    }

    // ══════════ 指点：小红点、三键条（2026-10-31；2026-11-01 改成照真的 ThinkPad 键盘摆） ══════════

    /**
     * 带红点 / 鼠标键尺寸的门槛：红点最大 64、嵌在键里至少 32，键 44 × 48、隔 4，中键至少 56，键帽缩进 2，离边 20
     * （分裂中间留的一列 80，键盘最下面那排从框里切走 52）。
     */
    private static final KeyboardPlan.Sizes T = new KeyboardPlan.Sizes(GAP, MIN_PAD, MIN_KEY, 64, 32, 44, 48, 4, 56, 2, EDGE);
    /** 键盘三层的全部行：带九个快捷键（一共 7 行，同默认的快捷行）、不带快捷行（6 行）、连苹果设备（底行多 🌐）。 */
    private static final KeyRows.Row[][] L9 = layers(false, 9), L0 = layers(false, 0), LA = layers(true, 9);

    private static KeyRows.Row[][] layers(boolean apple, int shortcuts) {
        KeyRows.Key[] keys = new KeyRows.Key[shortcuts];
        for (int i = 0; i < shortcuts; i++) keys[i] = KeyRows.k((0x04 + i) | (0x01 << 8), "快" + i);   // 像"复制"那样自带 Ctrl 的组合键
        return KeyRows.layers(true, KeyRows.bottomRows(apple, keys));
    }

    private static KeyboardPlan.Plan stickPlan(int mode, int w, int h, float[] saved, float[] cell, boolean land) {
        return KeyboardPlan.plan(mode, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, w, h, saved, cell, land, T, L9);
    }

    static void pointers() {
        check(KeyboardPlan.nextPointer(KeyboardPlan.POINTER_PAD) == KeyboardPlan.POINTER_STICK, "触控板 → 小红点");
        check(KeyboardPlan.nextPointer(KeyboardPlan.POINTER_STICK) == KeyboardPlan.POINTER_NONE, "小红点 → 关");
        check(KeyboardPlan.nextPointer(KeyboardPlan.POINTER_NONE) == KeyboardPlan.POINTER_PAD, "关 → 触控板");
        check(KeyboardPlan.validPointer(9) == KeyboardPlan.POINTER_PAD, "认不出的值当触控板");
        check(T.stickColumn() == 80 && T.mouseRow() == 52 && T.keysWidth() == 140, "尺寸：中间一列 80、最下面那排切走 52、三个键至少 140 宽");
        // 旧接口（boolean 的触控板开关）照旧：和新接口的触控板 / 关一模一样
        check(same(plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true),
                KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 900, 350, null, null, true, S)),
                "旧接口 = 新接口的触控板");
        // 没给红点尺寸（旧的三个数的 Sizes）：小红点当关
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 900, 350,
                null, null, true, S);
        check(p.stick == null && p.pad == null && p.keys == null && Arrays.equals(p.block, p.keyboard), "没给红点尺寸：什么都不放");
        // 不选小红点时框就是键盘面板
        p = plan(KeyboardPlan.MODE_AUTO, true, 400, 800, null, false);
        check(Arrays.equals(p.block, p.keyboard) && p.stickAt == KeyboardPlan.STICK_NONE, "触控板时：框 = 键盘面板，没有红点");
    }

    /** 分裂、没调过：中间只留一列刚够红点，两半各自变宽；红点在这一列正中、和 a–l / z–m 两行的交界齐平，不压键；三个键是最下面一排。 */
    static void stickSplitDefault() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_AUTO, 900, 350, null, null, true);
        check(p.split && p.middle == 80 && !p.seam, "宽窗口：分裂，中间 80，两块范围不伸到中线");
        check(p.pad == null && p.padSide == KeyboardPlan.PAD_NONE && p.menuArea == null, "选了小红点：不放触控板，⋯ 回功能行");
        eq(p.block, new int[]{0, 0, 900, 350}, "框占满");
        eq(p.keyboard, new int[]{0, 0, 900, 298}, "键盘面板从框里让出最下面 52");
        eq(p.keys, new int[]{2, 300, 898, 348}, "三个键横跨整块（左半 + 中间 + 右半）、48 高，四边和键一样缩进 2");
        eq(p.leftHalf, new int[]{0, 0, 410, 298}, "左半 410 宽（触控板时 300，以前红点组时 372）");
        eq(p.rightHalf, new int[]{490, 0, 900, 298}, "右半对称");
        check(p.stickAt == KeyboardPlan.STICK_COLUMN, "红点在中间那一列、不压键");
        // 7 行：a–l 是第 4 行（下标 3），它和 z–m 之间那条线在 298 × 4 / 7 ≈ 170
        eq(p.stick, new int[]{418, 138, 482, 202}, "红点 64、在这一列正中，竖着和两行的交界齐平");
        KeyRows.Placed a = find(KeyboardPlan.placedLayers(L9, 900, 298, true, 80, T).get(0), "a");
        check(a.bottom + 2 == (p.stick[1] + p.stick[3]) / 2, "红点中心 = a 那一行的下沿（连缩进）");
        eq(p.middleKey, new int[]{339, 561}, "中键 222（总宽的四分之一）、对着红点；左右键各 333");
        KeyboardPlan.Plan pad = plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true);
        check(p.leftHalf[2] - p.leftHalf[0] > pad.leftHalf[2] - pad.leftHalf[0], "两半比触控板时的三分之一宽");
        near(p.splitCell[2], (410 + GAP / 2f) / 900, "折算成格子：右边到这一列的中线减半条缝");
        KeyboardPlan.Plan q = stickPlan(KeyboardPlan.MODE_AUTO, 900, 350, null, p.splitCell, true);
        check(sameHalves(p, q) && Arrays.equals(p.stick, q.stick) && Arrays.equals(p.keys, q.keys), "默认格子喂回去：两半、红点、三个键不变");
        // 没有快捷行（6 行）：交界在 298 × 4 / 6 ≈ 199
        KeyboardPlan.Plan six = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 900, 350,
                null, null, true, T, L0);
        check((six.stick[1] + six.stick[3]) / 2 == 199, "6 行时红点跟着那条线走：" + Arrays.toString(six.stick));
    }

    /** 自动分不分裂：按中间只留 80 算（比三分之一布局、以前的 156 一列都窄，更多地方能分）。 */
    static void stickAutoThreshold() {
        check(stickPlan(KeyboardPlan.MODE_AUTO, 440, 220, null, null, true).split, "440 × 220：小红点能分（一份 32.7）");
        check(!plan(KeyboardPlan.MODE_AUTO, true, 440, 220, null, true).split, "同一个窗口触控板分不了（一份 26.7）");
        check(!stickPlan(KeyboardPlan.MODE_AUTO, 430, 215, null, null, true).split, "430 × 215：小红点也分不了（一份 31.8）");
        check(KeyboardPlan.shouldSplit(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_STICK, 440, 220, T)
                && !KeyboardPlan.shouldSplit(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_PAD, 440, 220, T), "shouldSplit 按指点算");
    }

    /** 强制分裂的窄窗口：留不出一列，两半往中间伸、只隔一条缝，红点嵌在缝上（左半 g、v 和右半 g、v 的交界）。 */
    static void stickSeam() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_SPLIT, 400, 800, null, null, false);
        check(p.split && p.middle == GAP && p.seam, "手机竖屏强制分裂：两半伸满、只隔一条缝");
        eq(p.block, new int[]{0, 240, 400, 800}, "框照合并时的上下沿");
        eq(p.keys, new int[]{2, 750, 398, 798}, "三个键在框的最下面");
        check(p.stickAt == KeyboardPlan.STICK_KEYS, "红点嵌在键里");
        int size = p.stick[2] - p.stick[0];
        check(size >= 32 && size <= 64 && p.stick[0] + p.stick[2] - 400 <= 1 && p.stick[0] + p.stick[2] - 400 >= 0,
                "红点在缝的正中、32–64：" + Arrays.toString(p.stick));
        check((p.stick[1] + p.stick[3]) / 2 == 240 + Math.round(508f * 4 / 7), "竖着在 a–l / z–m 交界");
        List<KeyRows.Placed> letters = KeyboardPlan.placedLayers(L9, 400, 508, true, GAP, T).get(0);
        int touched = 0;
        for (KeyRows.Placed k : letters) {
            float sh = KeyboardPlan.share(k, p.stick[0], p.stick[1] - 240, size);
            if (sh > 0) {
                touched++;
                check((k.key.label.equals("g") || k.key.label.equals("v")) && sh <= KeyboardPlan.STICK_LETTER_SHARE + 1e-6f,
                        "压着的是两边的 g、v，各让出不到 16%：" + k.key.label + " " + sh);
            }
        }
        check(touched == 4, "压着四个键（左右各一个 g、一个 v）：" + touched);
    }

    /** 合并：红点嵌在 g、h、v、b 交界那一点，四个键各让出一个角；三个键是最下面一排，中键对着红点。 */
    static void stickMerged() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, null, null, false);
        eq(p.block, new int[]{0, 240, 400, 800}, "竖屏合并：框照旧占下面七成（上面空着，同指点关着）");
        eq(p.keyboard, new int[]{0, 240, 400, 748}, "键盘面板让出最下面 52");
        eq(p.keys, new int[]{2, 750, 398, 798}, "三个键横跨整块");
        check(p.stickAt == KeyboardPlan.STICK_KEYS, "嵌在键里");
        int size = p.stick[2] - p.stick[0];
        check(Math.abs(p.stick[0] + p.stick[2] - 2 * 220) <= 1 && (p.stick[1] + p.stick[3]) / 2 == 240 + Math.round(508f * 4 / 7),
                "中心在 g|h、v|b 那条缝（400 × 5.5 / 10 = 220）和两行的交界：" + Arrays.toString(p.stick));
        check(size >= 32 && size <= 64, "32–64：" + size);
        List<List<KeyRows.Placed>> pl = KeyboardPlan.placedLayers(L9, 400, 508, false, KeyRows.MIDDLE_PAD, T);
        String around = "";
        for (KeyRows.Placed k : pl.get(0)) {
            float sh = KeyboardPlan.share(k, p.stick[0], p.stick[1] - 240, size);
            if (sh > 0) around += k.key.label;
            if (sh > 0) check(sh <= KeyboardPlan.STICK_LETTER_SHARE + 1e-6f, k.key.label + " 让出不到 16%：" + sh);
        }
        check(around.equals("ghvb"), "字母层压着的正好是 g h v b：" + around);
        for (int layer = 1; layer < 3; layer++) {
            for (KeyRows.Placed k : pl.get(layer)) {
                check(KeyboardPlan.share(k, p.stick[0], p.stick[1] - 240, size) <= KeyboardPlan.STICK_MAX_SHARE + 1e-6f,
                        "第 " + layer + " 层的 " + k.key.label + " 让出不到 25%");
            }
        }
        int c = (p.stick[0] + p.stick[2]) / 2;
        check(Math.abs((p.middleKey[0] + p.middleKey[1]) / 2 - c) <= 1, "中键对着红点：" + Arrays.toString(p.middleKey));
        check(p.middleKey[1] - p.middleKey[0] == 97, "中键 97（388 的四分之一）");
        // 宽窗口合并、没调过：键盘占满（以前给红点组让出右边一列）
        p = stickPlan(KeyboardPlan.MODE_MERGE, 900, 350, null, null, true);
        eq(p.block, new int[]{0, 0, 900, 350}, "宽窗口合并：框占满");
        check(p.stickAt == KeyboardPlan.STICK_KEYS, "宽窗口合并：也嵌在键里");
    }

    /** 键太小嵌不下：放在键盘的框上面（对着那一点）；上下都没地方就不放，三个键照样有。 */
    static void stickOutside() {
        // 键盘调成窄窄的一条（180 宽：字母键 14 宽、40 高），32 的红点就要占掉 g 的三成多
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, new float[]{0, 0.55f, 0.45f, 1}, null, false);
        eq(p.block, new int[]{0, 440, 180, 800}, "框照存的");
        check(p.stickAt == KeyboardPlan.STICK_OUTSIDE, "嵌不下：放外面");
        eq(p.stick, new int[]{67, 368, 131, 432}, "在框上面隔 8、对着 g|h（180 × 0.55 = 99）、64 大");
        check(p.keys != null, "三个键照样有");
        p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 400, new float[]{0, 0, 0.45f, 1}, null, false);
        check(p.stick == null && p.stickAt == KeyboardPlan.STICK_NONE && p.keys != null, "上下都没地方：不放红点，三个键照样有");
    }

    /** 键盘最下面那排三个鼠标键：框太矮（切完键盘面板不到 3 × 48）就不切。 */
    static void stickMouseRow() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, new float[]{0, 0.75f, 1, 1}, null, false);
        check(p.keys != null && p.keyboard[3] - p.keyboard[1] == 148, "框 200 高：切，键盘面板剩 148");
        p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, new float[]{0, 0.8f, 1, 1}, null, false);
        check(p.keys == null && p.middleKey == null && Arrays.equals(p.keyboard, p.block), "框 160 高：不切，键盘面板就是整个框");
    }

    /** 中键：总宽的四分之一、至少 56，左右键不比它窄；对着给的中线，挪不过去就挪到最近的地方。 */
    static void middleKeys() {
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 200, T), new int[]{151, 249}, "400 宽：中键 98、居中");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 300, T), new int[]{200, 298}, "对着 300 挪不过去：右键只剩和中键一样宽");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 0, T), new int[]{102, 200}, "往左挪到左键和中键一样宽");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 200, 48}, 100, T), new int[]{72, 128}, "200 宽：四分之一不到 56，取 56");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 150, 48}, 75, T), new int[]{52, 99}, "150 宽：三个一样宽（47）都不到 56，就三个一样宽");
        // 三键条也照这个分（主界面、键盘页的触控板）
        int[][] c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 232}, 400, 800, false, T);
        int[] m = KeyboardPlan.middleKey(c[1], (c[1][0] + c[1][2]) / 2, T);
        check(m[1] - m[0] == 88 && m[0] - 4 - c[1][0] == c[1][2] - m[1] - 4, "三键条 360 宽：中键 88、左右一样宽：" + Arrays.toString(m));
    }

    /** 存过格子：中间够一列就在这一列正中；合拢了嵌在缝上；拉开一点（不到一列）照样在正中。 */
    static void stickSavedSplit() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_SPLIT, 1000, 400, null, new float[]{0, 0.1f, 0.38f, 1}, true);
        check(p.stickAt == KeyboardPlan.STICK_COLUMN && p.stick[2] - p.stick[0] == 64 && p.stick[0] + p.stick[2] == 1000,
                "存过、中间够宽：红点 64 在中间正中：" + Arrays.toString(p.stick));
        eq(p.keys, new int[]{2, 350, 998, 398}, "三个键在框的最下面、横跨两半和中间");
        p = stickPlan(KeyboardPlan.MODE_SPLIT, 1000, 400, null, new float[]{0.05f, 0.1f, 0.5f, 0.9f}, true);
        check(p.seam && p.stickAt == KeyboardPlan.STICK_KEYS, "合拢：嵌在两半交界的缝上");
        p = stickPlan(KeyboardPlan.MODE_SPLIT, 1000, 400, null, new float[]{0, 0.2f, 0.47f, 1}, true);
        check(!p.seam && p.middle < 80 && p.stick != null && p.stick[0] + p.stick[2] - 1000 <= 1 && p.stick[0] + p.stick[2] >= 1000,
                "拉开一点（中间不到 80）：照样在正中");
    }

    /** 三键条从触控板上沿 / 下沿切出来（48 高）。 */
    static void cutBar() {
        int[][] c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 232}, 400, 800, false, T);
        eq(c[1], new int[]{20, 184, 380, 232}, "下沿：贴着触控板下边，左右离屏幕边 20");
        eq(c[0], new int[]{0, 0, 400, 180}, "触控板变矮 48 + 4");
        c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 232}, 400, 800, true, T);
        eq(c[1], new int[]{20, 20, 380, 68}, "上沿：触控板贴着屏幕上边，三键条离上边 20");
        eq(c[0], new int[]{0, 72, 400, 232}, "上面那 20 空着，触控板从三键条下面 4 开始");
        c = KeyboardPlan.cutBar(new int[]{0, 568, 400, 800}, 400, 800, false, T);
        eq(c[1], new int[]{20, 732, 380, 780}, "下沿贴着屏幕下边：往上推到离下边 20");
        check(KeyboardPlan.cutBar(new int[]{0, 0, 400, 140}, 400, 800, false, T) == null, "切完触控板不到 96：不切");
        check(KeyboardPlan.cutBar(new int[]{0, 0, 170, 400}, 170, 800, false, T) == null, "三个键排不下：不切");
        check(KeyboardPlan.cutBar(new int[]{0, 0, 400, 232}, 400, 800, false, S) == null, "没给键的尺寸：不切");
    }

    static void padKeysInPlan() {
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_BOTTOM, 400, 800,
                null, null, false, T);
        eq(p.keys, new int[]{20, 184, 380, 232}, "竖屏键盘页：三键条在触控板下沿（贴着键盘那一边）");
        eq(p.pad, new int[]{0, 0, 400, 180}, "触控板切掉那一条");
        check(Arrays.equals(p.middleKey, KeyboardPlan.middleKey(p.keys, 200, T)), "三键条的中键对着它的中线");
        check(Arrays.equals(p.block, p.keyboard), "三键条不在键盘的框里");
        check(Arrays.equals(p.menuArea, p.pad) && p.menuMargin == 0, "下沿：⋯ 照旧在触控板顶上正中");
        p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_TOP, 400, 800, null, null, false, T);
        eq(p.keys, new int[]{20, 20, 380, 68}, "上沿");
        int[] c = KeyboardPlan.menuTop(p.menuArea, 400, 800, BW, BH, p.menuMargin, EDGE);
        eq(c, new int[]{(400 - BW) / 2, 68 + GAP}, "上沿：⋯ 在三键条正下方、隔 8");
        p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_NONE, KeyboardPlan.KEYS_BOTTOM, 400, 800, null, null, false, T);
        check(p.keys == null && p.pad == null && p.middleKey == null, "指点关着：没有三键条");
        // 小触控板太矮：切了不够 96，不切，触控板照旧
        p = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_BOTTOM, 400, 800,
                new float[]{0, 0.15f, 1, 1}, null, false, T);
        check(p.keys == null && p.pad != null && p.pad[3] == 112, "太矮切不了：不显示三键条，触控板照旧");
    }

    /**
     * 交给真正的 TouchRouter / KeyFingers（照 MainActivity + KeyboardPanel 报区域的顺序：红点、三个鼠标键、键、键盘范围）：
     * 红点范围里的每一点都归红点；范围外的每一点和没有红点时一模一样（"缝分给最近的键只改按在缝里的触点"照样成立，红点只改它自己那一块）；
     * nearestKey 永远不选红点；面板自己收触摸时（KeyFingers 带洞）同样。三层、合并 / 分裂只隔一条缝 / 分裂中间一列都扫。
     */
    static void stickRouting() {
        Object[][] cases = {
                {KeyboardPlan.MODE_MERGE, 400, 800, false, L9},
                {KeyboardPlan.MODE_MERGE, 360, 780, false, LA},
                {KeyboardPlan.MODE_SPLIT, 400, 800, false, L9},
                {KeyboardPlan.MODE_AUTO, 900, 350, true, L9},
                {KeyboardPlan.MODE_MERGE, 1100, 700, true, L0},
        };
        int points = 0, inStick = 0, bad = 0;
        String first = null;
        for (Object[] cs : cases) {
            int mode = (Integer) cs[0], w = (Integer) cs[1], h = (Integer) cs[2];
            KeyRows.Row[][] layers = (KeyRows.Row[][]) cs[4];
            KeyboardPlan.Plan p = KeyboardPlan.plan(mode, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, w, h, null, null,
                    (Boolean) cs[3], T, layers);
            check(p.stick != null && p.keys != null, w + "×" + h + "：有红点、有三个键");
            int[] k = p.keyboard;
            int pw = k[2] - k[0], ph = k[3] - k[1];
            for (int layer = 0; layer < 3; layer++) {
                List<KeyRows.Placed> keys = KeyRows.place(layers[layer], pw, ph, p.split, p.middle, T.capGap);
                List<TouchRouter.Region> keyRegions = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) {
                    KeyRows.Placed q = keys.get(i);
                    keyRegions.add(new TouchRouter.Region(q.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                            k[0] + q.left, k[1] + q.top, k[0] + q.right, k[1] + q.bottom));
                }
                List<TouchRouter.Region> areas = new ArrayList<>();
                for (int[] a : panelAreas(p, layers[layer])) areas.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, a[0], a[1], a[2], a[3]));
                TouchRouter.Region stick = new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, p.stick[0], p.stick[1], p.stick[2], p.stick[3]);
                List<TouchRouter.Region> with = new ArrayList<>(), without = new ArrayList<>();
                with.add(stick);
                for (int i = 0; i < 3; i++) {
                    int l = i == 0 ? p.keys[0] : i == 1 ? p.middleKey[0] : p.middleKey[1] + 4;
                    int r = i == 0 ? p.middleKey[0] - 4 : i == 1 ? p.middleKey[1] : p.keys[2];
                    TouchRouter.Region mk = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, Proto.MOUSE_BUTTON_ID_BASE | (1 << i),
                            l, p.keys[1], r, p.keys[3]);
                    with.add(mk);
                    without.add(mk);
                }
                with.addAll(keyRegions);
                with.addAll(areas);
                without.addAll(keyRegions);
                without.addAll(areas);
                TouchRouter a = new TouchRouter(new KeyRowsTest.Quiet()), b = new TouchRouter(new KeyRowsTest.Quiet());
                a.setRegions(with.toArray(new TouchRouter.Region[0]));
                b.setRegions(without.toArray(new TouchRouter.Region[0]));
                TouchRouter.Region[] keyArr = keyRegions.toArray(new TouchRouter.Region[0]);
                TouchRouter.Region[] areaArr = areas.toArray(new TouchRouter.Region[0]);
                TouchRouter.Region[] holes = {stick};
                TouchRouter.Region[] all = with.toArray(new TouchRouter.Region[0]);
                // 红点四周一圈（连挨着的键）逐像素扫，别处隔 3 像素扫一个
                for (int y = p.block[1]; y < p.block[3]; y++) {
                    for (int x = p.block[0]; x < p.block[2]; x++) {
                        boolean near = x >= p.stick[0] - 48 && x < p.stick[2] + 48 && y >= p.stick[1] - 48 && y < p.stick[3] + 48;
                        if (!near && (x % 3 != 0 || y % 3 != 0)) continue;
                        points++;
                        boolean in = stick.contains(x, y);
                        TouchRouter.Region got = a.hit(x, y), plain = b.hit(x, y);
                        TouchRouter.Region finger = KeyFingers.pick(keyArr, areaArr, holes, x, y);
                        String why = null;
                        if (in) {
                            inStick++;
                            if (got != stick) why = "红点范围里却归了 " + got;
                            else if (finger != null) why = "面板自己收触摸：红点范围里却分给了键";
                        } else {
                            if (got != plain) why = "红点范围外和没有红点时不一样：" + got + " / " + plain;
                            else if (finger != KeyFingers.pick(keyArr, areaArr, x, y)) why = "面板自己收触摸：洞外和没洞时不一样";
                        }
                        for (TouchRouter.Region ar : areaArr) {
                            if (why == null && ar.contains(x, y) && TouchRouter.nearestKey(all, ar, x, y) == stick) why = "nearestKey 选了红点";
                        }
                        if (why != null) {
                            bad++;
                            if (first == null) first = w + "×" + h + " 第 " + layer + " 层 (" + x + ", " + y + ")：" + why;
                        }
                    }
                }
            }
        }
        check(bad == 0, "红点和键的分流扫了 " + points + " 个点（红点里 " + inStick + " 个），" + bad + " 个不对"
                + (first == null ? "" : "，比如 " + first));
        check(inStick > 0, "扫到了红点里的点");
    }

    /** 照 KeyboardPanel.areas 的规则算键盘范围（页面坐标）：整块一块；分裂时左右各一块，只隔一条缝时伸到缝的中线，每半只到最后一行有键的下沿。 */
    private static List<int[]> panelAreas(KeyboardPlan.Plan p, KeyRows.Row[] rows) {
        int[] k = p.keyboard;
        int w = k[2] - k[0], h = k[3] - k[1];
        List<int[]> out = new ArrayList<>();
        if (!p.split) {
            out.add(k.clone());
            return out;
        }
        int end = p.seam ? KeyRows.areaEnd(w, p.middle) : KeyRows.leftEnd(w, p.middle);
        int start = p.seam ? KeyRows.areaStart(w, p.middle) : KeyRows.rightStart(w, p.middle);
        out.add(new int[]{k[0], k[1], k[0] + end, k[1] + KeyRows.halfBottom(rows, h, false)});
        out.add(new int[]{k[0] + start, k[1], k[2], k[1] + KeyRows.halfBottom(rows, h, true)});
        return out;
    }

    private static KeyRows.Placed find(List<KeyRows.Placed> ps, String label) {
        for (KeyRows.Placed q : ps) if (q.key.label.equals(label)) return q;
        return null;
    }

    /** 扫一遍：各种尺寸、布局、指点、三键条、存的位置、键（快捷行有没有、苹果）下，红点 / 三个鼠标键 / 三键条 / ⋯ 守规矩。 */
    static void stickSweep() {
        float[][] saves = {null, {0, 0, 1, 1}, {0, 0.3f, 1, 1}, {0.4f, 0, 1, 1}, {0, 0, 0.6f, 1}, {0.2f, 0.4f, 0.8f, 0.9f}};
        float[][] cells = {null, {0, 0, 0.5f, 1}, {0.05f, 0.1f, 0.35f, 0.9f}, {0.1f, 0.4f, 0.4f, 1}, {0, 0.2f, 0.47f, 1}};
        KeyRows.Row[][][] boards = {L9, L0, LA};
        int bad = 0, n = 0, inKeys = 0, column = 0, outside = 0, none = 0, rows = 0, bars = 0, tiny = 0;
        String first = null;
        int bi = 0;
        for (int w = 150; w <= 2600; w += 110) {
            for (int h = 100; h <= 2000; h += 95) {
                for (int mode = 0; mode < 3; mode++) {
                    for (int pointer = 0; pointer < 3; pointer++) {
                        for (int keys = 0; keys < (pointer == KeyboardPlan.POINTER_PAD ? 3 : 1); keys++) {
                            for (float[] saved : saves) {
                                for (float[] cell : cells) {
                                    n++;
                                    KeyRows.Row[][] L = boards[bi++ % boards.length];
                                    KeyboardPlan.Plan p = KeyboardPlan.plan(mode, pointer, keys, w, h, saved, cell, w > h, T, L);
                                    String why = stickViolation(p, mode, pointer, keys, w, h, cell, L);
                                    if (p.stickAt == KeyboardPlan.STICK_KEYS) inKeys++;
                                    if (p.stickAt == KeyboardPlan.STICK_COLUMN) column++;
                                    if (p.stickAt == KeyboardPlan.STICK_OUTSIDE) outside++;
                                    if (pointer == KeyboardPlan.POINTER_STICK && p.stick == null) none++;
                                    if (pointer == KeyboardPlan.POINTER_STICK && p.keys != null) rows++;
                                    if (pointer == KeyboardPlan.POINTER_PAD && p.keys != null) bars++;
                                    if (p.stick != null && p.stick[2] - p.stick[0] == 32) tiny++;
                                    if (why != null) {
                                        bad++;
                                        if (first == null) first = w + "×" + h + " 布局 " + mode + " 指点 " + pointer + " 三键条 " + keys
                                                + " 存的 " + Arrays.toString(saved) + " 格子 " + Arrays.toString(cell) + "：" + why;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check(bad == 0, "红点 / 三个鼠标键 / 三键条扫了 " + n + " 种，" + bad + " 种不对" + (first == null ? "" : "，比如 " + first));
        String seen = "扫到的里面有嵌在键里的 " + inKeys + "、中间一列的 " + column + "、放外面的 " + outside + "（取最小 32 的 " + tiny
                + "、放不下的 " + none + "）、最下面那排 " + rows + "、三键条 " + bars;
        check(inKeys > 0 && column > 0 && outside > 0 && rows > 0 && bars > 0, seen);
        System.err.println("  小红点扫描：" + seen);
    }

    private static String stickViolation(KeyboardPlan.Plan p, int mode, int pointer, int keys, int w, int h, float[] cell,
                                         KeyRows.Row[][] L) {
        int[] k = p.keyboard, b = p.block;
        if (b[0] < 0 || b[1] < 0 || b[2] > w || b[3] > h) return "键盘的框出了键盘区";
        if (k[0] != b[0] || k[1] != b[1] || k[2] != b[2] || k[3] > b[3]) return "键盘面板不是框的上面那截";
        if (p.split && KeyRows.splitUnit(w, p.middle) < MIN_KEY) return "分裂了但键太窄";
        if (pointer != KeyboardPlan.POINTER_PAD && p.pad != null) return "没选触控板却放了触控板";
        if (pointer != KeyboardPlan.POINTER_PAD && p.menuArea != null) return "没有触控板却给 ⋯ 留了地方（⋯ 该在功能行上）";
        if (pointer != KeyboardPlan.POINTER_STICK && (p.stick != null || p.stickAt != KeyboardPlan.STICK_NONE)) return "没选小红点却放了红点";
        if ((p.stick == null) != (p.stickAt == KeyboardPlan.STICK_NONE)) return "stickAt 和 stick 对不上";
        if (pointer == KeyboardPlan.POINTER_NONE && p.keys != null) return "指点关着却有三个鼠标键";
        if (pointer == KeyboardPlan.POINTER_PAD && keys == KeyboardPlan.KEYS_OFF && p.keys != null) return "没开三键条却切了";
        if (pointer == KeyboardPlan.POINTER_PAD && p.pad != null && p.menuArea == null) return "有触控板没给 ⋯ 地方";
        if (pointer != KeyboardPlan.POINTER_STICK && !Arrays.equals(k, b)) return "没选小红点，框却不是键盘面板";
        if ((p.keys == null) != (p.middleKey == null)) return "middleKey 和 keys 对不上";
        if (p.keys != null) {
            int[] r = p.keys, m = p.middleKey;
            if (r[2] - r[0] < T.keysWidth() || r[3] - r[1] != 48) return "三个键排不下 / 不是 48 高";
            if (r[0] < 0 || r[1] < 0 || r[2] > w || r[3] > h) return "三个键出了页面";
            int mid = m[1] - m[0], left = m[0] - 4 - r[0], right = r[2] - m[1] - 4, inner = r[2] - r[0] - 8;
            if (left < mid || right < mid) return "左右键比中键窄：" + left + " / " + mid + " / " + right;
            if (mid < Math.min(56, inner / 3)) return "中键太窄：" + mid;
            if (pointer == KeyboardPlan.POINTER_STICK) {
                if (r[0] != b[0] + 2 || r[2] != b[2] - 2 || r[3] != b[3] - 2 || r[1] != k[3] + 2) return "最下面那排没贴着框的下沿、没和键对齐";
                if (k[3] - k[1] < 3 * 48) return "切完键盘面板太矮";
                if (p.stick != null) {
                    int c = (p.stick[0] + p.stick[2]) / 2;
                    if (Math.abs((m[0] + m[1]) / 2 - c) > 1 && left != mid && right != mid) return "中键没对着红点（也不是挪到了头）";
                }
            } else {
                if (overlap(r, b, 0) && !(p.split && !overlap(r, p.leftHalf, 0) && !overlap(r, p.rightHalf, 0))) return "三键条压着键盘";
                if (p.pad != null && overlap(r, p.pad, 0)) return "三键条压着触控板";
                if (r[0] < EDGE || r[1] < EDGE || r[2] > w - EDGE || r[3] > h - EDGE) return "三键条离页面边不到 20";
                if (p.pad != null && Math.min(p.pad[2] - p.pad[0], p.pad[3] - p.pad[1]) < MIN_PAD) return "切完触控板太小";
                if (Math.abs((m[0] + m[1]) / 2 - (r[0] + r[2]) / 2) > 1) return "三键条的中键不在正中";
            }
        } else if (pointer == KeyboardPlan.POINTER_STICK) {
            if (b[3] - b[1] - 52 >= 144 && b[2] - b[0] - 4 >= 140) return "放得下却没切最下面那排";
            if (!Arrays.equals(k, b)) return "没切那排，键盘面板却变矮了";
        }
        if (pointer == KeyboardPlan.POINTER_STICK && cell == null && mode != KeyboardPlan.MODE_MERGE && p.split && !p.seam
                && p.middle != 80) {
            return "没调过却不是 80 的一列：" + p.middle;
        }
        int pw = k[2] - k[0], ph = k[3] - k[1];
        int[] at = KeyRows.stickPoint(pw, ph, L[0].length, p.split, p.middle);
        List<List<KeyRows.Placed>> pl = pointer == KeyboardPlan.POINTER_STICK
                ? KeyboardPlan.placedLayers(L, pw, ph, p.split, p.middle, T) : null;
        if (p.stick != null) {
            int[] s = p.stick;
            int size = s[2] - s[0];
            if (size != s[3] - s[1]) return "红点不是正方形";
            if (size < 32 || size > 64) return "红点不在 32–64：" + size;
            if (p.keys != null && overlap(s, p.keys, 0)) return "红点压着三个鼠标键";
            float max = KeyboardPlan.maxShare(pl, s[0] - k[0], s[1] - k[1], size);
            boolean inPanel = s[0] >= k[0] && s[1] >= k[1] && s[2] <= k[2] && s[3] <= k[3];
            boolean atPoint = s[0] - k[0] == at[0] - size / 2 && s[1] - k[1] == at[1] - size / 2;
            switch (p.stickAt) {
                case KeyboardPlan.STICK_KEYS: {
                    if (max <= 0) return "说嵌在键里却一个键都没压着";
                    if (max > KeyboardPlan.STICK_MAX_SHARE + 1e-6f) return "有键让出超过 25%：" + max;
                    float letters = 0;
                    for (KeyRows.Placed q : pl.get(0)) letters = Math.max(letters, KeyboardPlan.share(q, s[0] - k[0], s[1] - k[1], size));
                    if (size > 32 && letters > KeyboardPlan.STICK_LETTER_SHARE + 1e-6f) return "字母层有键让出超过 16%：" + letters;
                    if (!inPanel || !atPoint) return "嵌着的红点不在 g、h、v、b 那一点上";
                    break;
                }
                case KeyboardPlan.STICK_COLUMN:
                    if (!p.split) return "合并却在中间那一列";
                    if (max > 0) return "中间那一列的红点压着键";
                    if (!inPanel || !atPoint) return "中间那一列的红点不在这一列正中、两行的交界";
                    if (!p.seam && p.middle >= 80 && ph >= 64 + 2 * Math.abs(ph / 2 - at[1]) && size != 64) return "中间留够了一列却不是 64";
                    break;
                case KeyboardPlan.STICK_OUTSIDE:
                    if (overlap(s, b, 0)) return "外面的红点压着键盘的框";
                    if (s[0] < EDGE || s[1] < EDGE || s[2] > w - EDGE || s[3] > h - EDGE) return "外面的红点离页面边不到 20";
                    if (KeyboardPlan.stickSize(pl, pw, ph, at, T) != 0) return "嵌得进却放到了外面";
                    break;
                default:
                    return "stickAt 认不出：" + p.stickAt;
            }
        } else if (pointer == KeyboardPlan.POINTER_STICK) {
            if (KeyboardPlan.stickSize(pl, pw, ph, at, T) != 0) return "嵌得进却没放红点";
            if (b[1] - GAP - EDGE >= 32 && w - 2 * EDGE >= 32) return "上面放得下却没放红点";
        }
        if (p.menuArea != null) {
            int[] c = KeyboardPlan.menuTop(p.menuArea, w, h, BW, BH, p.menuMargin, EDGE);
            if (c != null) {
                int[] m = {c[0], c[1], c[0] + BW, c[1] + BH};
                if (c[0] < EDGE || c[1] < EDGE || m[2] > w - EDGE || m[3] > h - EDGE) return "⋯ 离边不到 20";
                if (p.keys != null && overlap(m, p.keys, 0)) return "⋯ 压着三个键";
            }
        }
        return null;
    }

    /** 两块（{左, 上, 右, 下}）重叠（隔不到 gap 也算）。 */
    private static boolean overlap(int[] a, int[] b, int gap) {
        return a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap;
    }

    // ── 工具 ──

    private static boolean sameHalves(KeyboardPlan.Plan a, KeyboardPlan.Plan b) {
        return a.split && b.split && Arrays.equals(a.keyboard, b.keyboard) && Arrays.equals(a.leftHalf, b.leftHalf)
                && Arrays.equals(a.rightHalf, b.rightHalf);
    }

    private static void near(float got, float want, String what) {
        check(Math.abs(got - want) < 1e-4f, what + "：想要 " + want + "，实际 " + got);
    }

    private static void near(float[] got, float[] want, String what) {
        boolean ok = got != null && got.length == want.length;
        for (int i = 0; ok && i < want.length; i++) ok = Math.abs(got[i] - want[i]) < 1e-4f;
        check(ok, what + "：想要 " + Arrays.toString(want) + "，实际 " + Arrays.toString(got));
    }

    private static KeyboardPlan.Plan plan(int mode, boolean padOn, int w, int h, float[] saved, boolean land) {
        return KeyboardPlan.plan(mode, padOn, w, h, saved, land, S);
    }

    private static boolean same(KeyboardPlan.Plan a, KeyboardPlan.Plan b) {
        return a.split == b.split && a.middle == b.middle && a.padSide == b.padSide
                && Arrays.equals(a.keyboard, b.keyboard) && Arrays.equals(a.pad, b.pad);
    }

    private static boolean same(List<KeyRows.Placed> a, List<KeyRows.Placed> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            KeyRows.Placed x = a.get(i), y = b.get(i);
            if (x.key != y.key || x.left != y.left || x.top != y.top || x.right != y.right || x.bottom != y.bottom) return false;
        }
        return true;
    }

    private static void eq(int[] got, int[] want, String what) {
        check(Arrays.equals(got, want), what + "：想要 " + Arrays.toString(want) + "，实际 " + Arrays.toString(got));
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
