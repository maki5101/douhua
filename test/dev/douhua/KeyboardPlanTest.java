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
        stickNeverSplits();
        stickMerged();
        stickHoleRules();
        stickOutside();
        cutGeometry();
        middleKeys();
        cutBar();
        perSideEdges();
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
     * 带红点 / 鼠标键尺寸的门槛：红点直径最大 64、嵌在键里至少 32，红帽和切口之间缝 3、键上的字离切口 10，
     * 键 44 × 48、隔 4，中键至少 56，键帽缩进 2，离边 20。
     */
    private static final KeyboardPlan.Sizes T = new KeyboardPlan.Sizes(GAP, MIN_PAD, MIN_KEY, 64, 32, 3, 10, 44, 48, 4, 56, 2, EDGE);
    /** 键盘三层的全部行：带九个快捷键（一共 7 行，同默认的快捷行）、不带快捷行（6 行）、一个快捷键、连苹果设备（底行多 🌐）。 */
    private static final KeyRows.Row[][] L9 = layers(false, 9), L0 = layers(false, 0), L1 = layers(false, 1), LA = layers(true, 9),
            LA0 = layers(true, 0);

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
        check(T.keysWidth() == 140 && T.holeWidth(44) == 144 && T.holeWidth(32) == 108,
                "尺寸：三键条至少 140 宽；空位放三个 44 宽的键要 144、32 宽的要 108（键帽四边缩进 2、键帽之间隔 4）");
        // 旧接口（boolean 的触控板开关）照旧：和新接口的触控板 / 关一模一样
        check(same(plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true),
                KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 900, 350, null, null, true, S)),
                "旧接口 = 新接口的触控板");
        // 没给红点尺寸（旧的三个数的 Sizes）：小红点当关，照样不分裂（小红点固定合并）
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 900, 350,
                null, null, true, S);
        check(p.stick == null && p.pad == null && p.keys == null && p.hole == null && !p.split, "没给红点尺寸：什么都不放，也不分裂");
        // 不选小红点时不让空位、没有切口
        p = plan(KeyboardPlan.MODE_AUTO, true, 400, 800, null, false);
        check(p.hole == null && p.cut == 0 && p.stickAt == KeyboardPlan.STICK_NONE, "触控板时：不让空位、没有红点");
    }

    /**
     * 小红点固定合并（2026-11-01 用户："小红点状态把分裂布局取消掉，默认就是合并的布局"）：不管"布局"选的什么、窗口多宽、存没存过分裂格子，
     * 都和合并一模一样；"布局"的值本身不改，换回触控板 / 关照它分裂。
     */
    static void stickNeverSplits() {
        int n = 0, bad = 0;
        String first = null;
        for (int w = 300; w <= 2600; w += 230) {
            for (int h = 150; h <= 1600; h += 145) {
                for (int mode = 0; mode < 3; mode++) {
                    for (float[] cell : new float[][]{null, {0, 0, 0.5f, 1}, {0.05f, 0.1f, 0.35f, 0.9f}}) {
                        n++;
                        KeyboardPlan.Plan p = stickPlan(mode, w, h, null, cell, w > h);
                        KeyboardPlan.Plan m = stickPlan(KeyboardPlan.MODE_MERGE, w, h, null, null, w > h);
                        boolean ok = !p.split && p.splitCell == null && p.leftHalf == null && Arrays.equals(p.keyboard, m.keyboard)
                                && Arrays.equals(p.stick, m.stick) && Arrays.equals(p.keys, m.keys) && Arrays.equals(p.hole, m.hole)
                                && !KeyboardPlan.shouldSplit(mode, KeyboardPlan.POINTER_STICK, w, h, T);
                        if (!ok) {
                            bad++;
                            if (first == null) first = w + "×" + h + " 布局 " + mode + " 格子 " + Arrays.toString(cell);
                        }
                    }
                }
            }
        }
        check(bad == 0, "小红点：扫了 " + n + " 种（布局 × 尺寸 × 存的格子），都不分裂、和合并一模一样（错 " + bad
                + (first == null ? "" : "，比如 " + first) + "）");
        check(plan(KeyboardPlan.MODE_AUTO, true, 900, 350, null, true).split
                        && KeyboardPlan.plan(KeyboardPlan.MODE_SPLIT, KeyboardPlan.POINTER_NONE, KeyboardPlan.KEYS_OFF, 400, 800, null, null,
                        false, T, L9).split, "触控板 / 关照旧按\"布局\"分裂");
        // 宽窗口、没调过：键盘占满（不给触控板腾右边那三分之一），红点嵌在键里
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_AUTO, 900, 350, null, null, true);
        eq(p.keyboard, new int[]{0, 0, 900, 350}, "宽窗口：占满");
        check(p.stickAt == KeyboardPlan.STICK_KEYS && p.keys != null, "宽窗口：嵌在键里、有三个鼠标键");
    }

    /** 合并（竖屏）：红点嵌在 g、h、v、b 交界，四个键各被圆弧切掉一块；三个鼠标键竖着跨底行和快捷行，别的行照原来的高度。 */
    static void stickMerged() {
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, null, null, false);
        eq(p.keyboard, new int[]{0, 240, 400, 800}, "竖屏合并：键盘照旧占下面七成，不再从下面切一排");
        // 7 行、每行 80：底行从 240 + 400 = 640 开始。空位以 g|h 那条缝（400 × 0.55 = 220）为中线、正好 144 宽，空格缩到 1.2 份
        eq(p.hole, new int[]{148, 292}, "空位（面板 x）");
        // 9 个快捷键分到两边每个只剩约 27–30（不到 32）：快捷行不让位、照旧铺满，三个键只占底行
        check(!p.spread, "400 宽 9 个快捷键：快捷行分不开");
        eq(p.keys, new int[]{148, 640, 292, 720}, "三个鼠标键只占底行（快捷行太挤，不让位）");
        KeyboardPlan.Plan five = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 400, 800,
                null, null, false, T, layers(false, 5));
        check(five.spread && Arrays.equals(five.hole, p.hole), "5 个快捷键：分得开（左 3 个各约 49、右 2 个各 54）");
        eq(five.keys, new int[]{148, 640, 292, 800}, "三个鼠标键跨底行和快捷行（连两行之间的缝）");
        eq(p.middleKey, new int[]{196, 244}, "中键那一格 196–244（键帽 198–242，44 宽）、正对 220；左右两格一样宽");
        check(p.stickAt == KeyboardPlan.STICK_KEYS, "嵌在键里");
        int d = p.stick[2] - p.stick[0];
        check(p.stick[0] + p.stick[2] == 440 && p.stick[1] + p.stick[3] == 2 * 560 && d == p.stick[3] - p.stick[1],
                "中心 (220, 560)：g|h 的缝、a–l / z–m 两行的交界：" + Arrays.toString(p.stick));
        check(d > 36 && d <= 64, "直径 " + d + "：比以前的方块（同一个窗口边长 36）大");
        near(p.cut, d / 2f + 1.5f, "切口半径 = 范围半径 + 缝的一半");
        List<List<KeyRows.Placed>> pl = KeyboardPlan.placedLayers(L9, 400, 560, T, p.hole, p.spread);
        String around = "";
        for (KeyRows.Placed k : pl.get(0)) {
            if (!KeyboardPlan.cuts(k, 220, 320, p.cut)) continue;
            around += k.key.label;
            float sh = KeyboardPlan.cutShare(k, 220, 320, p.cut);
            check(sh <= KeyboardPlan.STICK_LETTER_SHARE + 1e-4f, k.key.label + " 被切掉不到 16%：" + sh);
            check(KeyboardPlan.labelShift(k, 220, 320, p.cut, T.labelRoom) == 0, k.key.label + " 上的字不用挪（切的是角）");
        }
        check(around.equals("ghvb"), "字母层被切的正好是 g h v b：" + around);
        float most = 0;
        for (int layer = 1; layer < 3; layer++) {
            for (KeyRows.Placed k : pl.get(layer)) most = Math.max(most, KeyboardPlan.cutShare(k, 220, 320, p.cut));
        }
        check(most <= KeyboardPlan.STICK_MAX_SHARE + 1e-4f && most > 0.2f, "符号层、Fn 层被切得最多的不到 25%（这一层定的大小）：" + most);
        // 别的行照原来的高度：和指点关着时同一个位置的键一模一样（底行、快捷行以上）
        KeyboardPlan.Plan off = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_NONE, KeyboardPlan.KEYS_OFF, 400, 800,
                null, null, false, T, L9);
        check(Arrays.equals(off.keyboard, p.keyboard), "键盘和指点关着时一样大（不再多切一排）");
    }

    /** 空位怎么让（KeyboardPlan.stickHole）：先对称（中键正对红点）、空格尽量长；对称放不下就空格最短、右边到 . 原来的左边；再窄就不让。 */
    static void stickHoleRules() {
        KeyRows.Row[] r9 = L9[0], ra = LA[0];
        eq(KeyboardPlan.stickHole(900, r9, T), new int[]{360, 630}, "900 宽：空格 1.5 份，空位 3 份、以 495 为中线（. ↵ 铺 630–900，一起变宽）");
        eq(KeyboardPlan.stickHole(411, r9, T), new int[]{154, 298}, "411 宽（手机竖屏）：对称、空位正好 144，空格缩到 1.25 份");
        eq(KeyboardPlan.stickHole(360, r9, T), new int[]{126, 270}, "360 宽：空格缩到 1 份，正好对称");
        eq(KeyboardPlan.stickHole(884, ra, T), new int[]{414, 558}, "苹果 884 宽：多一个 🌐，空格缩到约 1.2 份照样对称");
        eq(KeyboardPlan.stickHole(411, ra, T), new int[]{185, 308}, "苹果 411 宽：对称放不下，空格 1 份、右边到 . 原来的左边（三个键各约 37）");
        eq(KeyboardPlan.stickHole(270, r9, T), new int[]{95, 203}, "270 宽：对称放不下、三个键各 32（最窄）");
        check(KeyboardPlan.stickHole(260, r9, T) == null && KeyboardPlan.stickHole(300, ra, T) == null, "再窄：不让位");
        check(KeyboardPlan.stickHole(900, KeyRows.LAYERS[0], T) == null, "没有底行：不让位");
        eq(KeyboardPlan.stickHole(900, L0[0], T), KeyboardPlan.stickHole(900, r9, T), "没有快捷行：横着一样");
        // 快捷行也让不让（KeyboardPlan.shortcutsSpread）：分到两边后每个键（连缝）都不窄于 32 才让
        check(KeyboardPlan.shortcutsSpread(new int[]{360, 630}, 900, r9, T), "900 宽 9 个：左 5 个各 72、右 4 个各 67，让");
        check(KeyboardPlan.shortcutsSpread(new int[]{414, 558}, 884, ra, T), "苹果 884 宽 9 个：各约 82，让");
        check(!KeyboardPlan.shortcutsSpread(new int[]{154, 298}, 411, r9, T), "411 宽（手机竖屏）9 个：左各 31、右各 28，不让");
        check(!KeyboardPlan.shortcutsSpread(new int[]{126, 270}, 360, r9, T), "360 宽 9 个：各 25 / 23，不让");
        KeyRows.Row[] r7 = layers(false, 7)[0];
        check(KeyboardPlan.shortcutsSpread(new int[]{154, 298}, 411, r7, T), "411 宽 7 个：左 4 个各 38、右 3 个各 38，让");
        check(!KeyboardPlan.shortcutsSpread(new int[]{360, 630}, 900, L0[0], T) && !KeyboardPlan.shortcutsSpread(null, 900, r9, T),
                "没有快捷行、没让空位：不让");
        // 正好 32 让、差一像素不让（左 1 个 32、右 1 个 32）
        KeyRows.Row[] r2 = layers(false, 2)[0];
        check(KeyboardPlan.shortcutsSpread(new int[]{32, 100}, 132, r2, T) && !KeyboardPlan.shortcutsSpread(new int[]{32, 101}, 132, r2, T),
                "边界：每个正好 32 让、31 不让");
        // 中键：对称时正对红点、左右一样宽；不对称时三个一样宽、挪到离红点最近的地方
        int[] m = KeyboardPlan.holeKeys(new int[]{360, 630}, 495, T);
        eq(m, new int[]{465, 525}, "900 宽：中键键帽 56（三个键帽总宽 258 / 5.4 不到 56），左右键帽各 101");
        check(m[0] + m[1] == 990 && m[0] - 360 == 630 - m[1], "对称：中键正对、左右两格一样宽");
        eq(KeyboardPlan.holeKeys(new int[]{185, 308}, 226, T), new int[]{226, 267}, "苹果 411：三个一样宽（37），中键挪到最近的地方");
        // 宽的空位：中键是三个键帽总宽的 1 / 5.4（2.2 : 1 : 2.2）
        int[] wide = KeyboardPlan.holeKeys(new int[]{0, 1012}, 506, T);
        int mid = wide[1] - wide[0] - 4, left = wide[0] - 4, right = 1012 - wide[1] - 4;
        check(mid == 185 && Math.abs(left - right) <= 1 && Math.abs(left - 2.2f * mid) <= 1.1f,
                "1012 宽的空位（键帽总宽 1000）：2.2 : 1 : 2.2（中键宽是单数时左右差 1）：" + left + " : " + mid + " : " + right);
        // 没有快捷行：三个键只占底行；一个快捷键：照样两行（那个键放空位左边，右边那截空着，按上去分给同一行的它，同以前它铺满整行）
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 400, 800,
                null, null, false, T, L0);
        eq(p.keys, new int[]{148, 240 + Math.round(560f * 5 / 6), 292, 800}, "没有快捷行：三个键只占底行");
        check(!p.spread, "没有快捷行：spread 为假");
        p = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 400, 800, null, null, false, T, L1);
        eq(p.keys, new int[]{148, 640, 292, 800}, "一个快捷键：照样跨两行");
        check(p.spread, "一个快捷键：快捷行让位");
        List<KeyRows.Placed> ps = KeyRows.place(L1[0], 400, 560, false, KeyRows.MIDDLE_PAD, T.capGap, p.hole, p.spread);
        KeyRows.Placed only = find(ps, "快0");
        check(only.left == 2 && only.right == 146, "一个快捷键放空位左边、铺满那一截");
        List<TouchRouter.Region> regs = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            KeyRows.Placed q = ps.get(i);
            regs.add(new TouchRouter.Region(q.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i, q.left, 240 + q.top, q.right, 240 + q.bottom));
        }
        regs.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 240, 400, 800));
        TouchRouter t = new TouchRouter(new KeyRowsTest.Quiet());
        t.setRegions(regs.toArray(new TouchRouter.Region[0]));
        TouchRouter.Region r = t.hit(380, 780);
        check(r != null && ps.get(r.id) == only, "空位右边那截快捷行（空着）分给同一行的那个快捷键");
    }

    /** 键太小嵌不下：放在键盘上面（对着那一点）；上下都没地方放旁边；四边都没地方才不放。 */
    static void stickOutside() {
        // 键盘调成窄窄的一条（180 宽：字母键 14 宽、50 高），32 的红点就要切掉 g 的三成多
        KeyboardPlan.Plan p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 800, new float[]{0, 0.55f, 0.45f, 1}, null, false);
        eq(p.keyboard, new int[]{0, 440, 180, 800}, "键盘照存的");
        check(p.stickAt == KeyboardPlan.STICK_OUTSIDE && p.cut == 0, "嵌不下：放外面、不切键");
        eq(p.stick, new int[]{67, 368, 131, 432}, "在键盘上面隔 8、对着 g|h（180 × 0.55 = 99）、64 大");
        check(p.keys == null && p.hole == null, "180 宽让不出三个鼠标键（空格 1 份时空位只有 72）：不显示");
        // 上下都没地方（键盘占满高）：放到键盘右边空着的那块，挨着键盘、隔 8，竖着对齐 g|h 那一点
        p = stickPlan(KeyboardPlan.MODE_MERGE, 400, 400, new float[]{0, 0, 0.45f, 1}, null, false);
        check(p.stickAt == KeyboardPlan.STICK_OUTSIDE && p.stick != null, "上下都没地方：放旁边");
        check(p.stick[0] == p.keyboard[2] + GAP && p.stick[2] <= 400 - EDGE, "放在键盘右边、隔一条缝、离右边至少 20（实际 " + Arrays.toString(p.stick) + "）");
        check(p.stick[2] - p.stick[0] == 64, "右边地方够：64 大");
        // 真的哪儿都没地方：窄窗口、键盘占满 → 不放红点
        p = stickPlan(KeyboardPlan.MODE_MERGE, 180, 400, new float[]{0, 0, 1, 1}, null, false);
        check(p.stick == null && p.stickAt == KeyboardPlan.STICK_NONE, "四边都没地方：不放红点");
        // 2026-11-01 用户主力机的实际情况：分屏下半边（约 641 × 288dp，扣掉边缘留白）换成合并，存下的横屏键盘只占左边六成、上下 11%–89%：
        // 嵌不下（键只有 32 高）、上下没地方，放在右边空着的那四成里；三个鼠标键照样在键盘里
        p = stickPlan(KeyboardPlan.MODE_MERGE, 641, 288, new float[]{0, 0.1075954f, 0.60815454f, 0.88579303f}, null, true);
        check(p.stick != null && p.stickAt == KeyboardPlan.STICK_OUTSIDE && p.stick[0] >= p.keyboard[2],
                "主力机分屏下半边合并：红点在键盘右边（实际 " + Arrays.toString(p.stick) + "）");
        check(p.stick[1] >= EDGE && p.stick[3] <= 288 - EDGE && p.stick[2] <= 641 - EDGE, "不出页面、离边至少 20");
        // 键盘约 390 宽、9 个快捷键分到两边每个不到 32：快捷行不让位，三个键只占底行的空位
        check(p.keys != null && p.keys[2] <= p.keyboard[2] && !p.spread
                        && p.keys[3] == p.keyboard[1] + KeyRows.holeBottom(L9[0], p.keyboard[3] - p.keyboard[1], false) && p.keys[3] < p.keyboard[3],
                "三个鼠标键在键盘里（底行的空位；9 个快捷键在这么窄的键盘上分不开，快捷行照旧铺满）");
    }

    /** 切口的几何：切掉多少面积（数值积分对公式）、碰不碰得到、字要挪多少。 */
    static void cutGeometry() {
        KeyRows.Key x = KeyRows.k(0x04, "x");
        KeyRows.Placed big = new KeyRows.Placed(x, 0, 0, 100, 100);
        nearTol(KeyboardPlan.cutShare(big, 50, 50, 20), (float) (Math.PI * 400 / 10000), 1e-6f, "整个圆在键里：πr² / 面积");
        nearTol(KeyboardPlan.cutShare(big, 100, 100, 30), (float) (Math.PI * 900 / 4 / 10000), 1e-6f, "圆心在角上：四分之一个圆");
        nearTol(KeyboardPlan.cutShare(big, 50, 100, 30), (float) (Math.PI * 900 / 2 / 10000), 1e-6f, "圆心在边上：半个圆");
        nearTol(KeyboardPlan.cutShare(new KeyRows.Placed(x, 0, 0, 20, 100), 10, 50, 30), (float) KeyboardPlanTest.stripArea(20, 30) / 2000, 1e-5f,
                "键比圆窄：圆被两条竖边截掉两头");
        nearTol(KeyboardPlan.cutShare(big, 102, 102, 30), (float) KeyboardPlanTest.quarterOff(30, 2) / 10000, 1e-5f, "圆心在角外 2 像素（缝的交点）");
        check(KeyboardPlan.cutShare(big, 200, 200, 30) == 0 && !KeyboardPlan.cuts(big, 200, 200, 30), "碰不到：0");
        check(KeyboardPlan.cuts(big, 120, 120, 30) && !KeyboardPlan.cuts(big, 125, 125, 30), "离角 28.3 碰到、35.4 碰不到");
        // 字：键 [0,100)×[0,60)（中心 (50, 30)），把字看成半径 10 的一块
        KeyRows.Placed k = new KeyRows.Placed(x, 0, 0, 100, 60);
        check(KeyboardPlan.labelShift(k, 50, 60, 15, 10) == 0, "切口离得远：不挪");
        near(KeyboardPlan.labelShift(k, 50, 60, 25, 10), -5, "从下沿正中切进来：往上挪 5（30 + 5 = 25 + 10）");
        near(KeyboardPlan.labelShift(k, 50, 0, 25, 10), 5, "从上沿切进来：往下挪");
        near(KeyboardPlan.labelShift(k, 50, 60, 40, 10), -20, "挪到正好贴着键帽上沿（30 − 10）");
        check(Float.isNaN(KeyboardPlan.labelShift(k, 50, 60, 41, 10)), "再大就挪出键帽：嵌不下");
        check(KeyboardPlan.labelShift(k, 130, 60, 25, 10) == 0, "横着隔得开（切口在键外 30）：不挪");
        nearTol(KeyboardPlan.labelShift(k, 60, 60, 25, 10), (float) -(Math.sqrt(35 * 35 - 10 * 10) - 30), 1e-4f, "斜着：挪到圆心距 = 35");
        check(KeyboardPlan.labelShift(k, 75, 60, 25, 10) == 0, "斜着离得够远：不挪");
        check(KeyboardPlan.cutRadius(20, T) == 21.5f, "切口半径 = 范围半径 + 1.5");
    }

    private static void nearTol(float got, float want, float tol, String what) {
        check(Math.abs(got - want) <= tol, what + "：想要 " + want + "，实际 " + got);
    }

    /** 半径 r 的圆夹在宽 width、圆心居中的竖条里的面积（数值积分 2 万条）。 */
    static double stripArea(double width, double r) {
        int n = 20000;
        double a = 0, step = width / n;
        for (int i = 0; i < n; i++) {
            double xx = -width / 2 + (i + 0.5) * step;
            a += 2 * Math.sqrt(Math.max(0, r * r - xx * xx)) * step;
        }
        return a;
    }

    /** 半径 r 的圆、圆心在矩形 [0,∞)² 的角外 (−o, −o) 时落进矩形的面积（从 0 积到 r − o，数值积分 2 万条）。 */
    static double quarterOff(double r, double o) {
        int n = 20000;
        double a = 0, step = (r - o) / n;
        for (int i = 0; i < n; i++) {
            double xx = (i + 0.5) * step + o;
            double hh = Math.sqrt(r * r - xx * xx) - o;
            if (hh > 0) a += hh * step;
        }
        return a;
    }

    /**
     * 中键：三个键总宽的 1 / 5.4（照真 ThinkPad 左 : 中 : 右约 2.2 : 1 : 2.2）、至少 56，左右键填满剩下的、不比它窄；
     * 对着给的中线，挪不过去就挪到最近的地方。
     */
    static void middleKeys() {
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 200, T), new int[]{164, 237}, "400 宽：中键 73（392 / 5.4）、居中");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 300, T), new int[]{250, 323}, "对着 300 挪不过去：右键只剩和中键一样宽");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 400, 48}, 0, T), new int[]{77, 150}, "往左挪到左键和中键一样宽");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 320, 48}, 160, T), new int[]{131, 189}, "320 宽：312 / 5.4 = 58，刚过 56");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 300, 48}, 150, T), new int[]{122, 178}, "300 宽：292 / 5.4 不到 56，取 56、左右各 118");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 200, 48}, 100, T), new int[]{72, 128}, "200 宽：取 56");
        eq(KeyboardPlan.middleKey(new int[]{0, 0, 150, 48}, 75, T), new int[]{52, 99}, "150 宽：三个一样宽（47）都不到 56，就三个一样宽");
        // 比例：宽的一排左 : 中 : 右 = 2.2 : 1 : 2.2（取整差不到 1 像素）
        int[] w = KeyboardPlan.middleKey(new int[]{0, 0, 1000, 48}, 500, T);
        int mid = w[1] - w[0], left = w[0] - 4, right = 1000 - w[1] - 4;
        check(mid == 184 && left == right && Math.abs(left - 2.2f * mid) <= 1.1f, "1000 宽：2.2 : 1 : 2.2：" + left + " : " + mid + " : " + right);
        // 三键条也照这个分（主界面、键盘页的触控板），中键在正中；中键宽是单数时左右差 1 像素
        int[][] c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 232}, 400, 800, false, T);
        int[] m = KeyboardPlan.middleKey(c[1], (c[1][0] + c[1][2]) / 2, T);
        check(m[1] - m[0] == 65 && Math.abs((m[0] - 4 - c[1][0]) - (c[1][2] - m[1] - 4)) <= 1,
                "三键条 360 宽：中键 65（352 / 5.4）、左右差不到 1：" + Arrays.toString(m));
    }

    /** 三键条从触控板上沿 / 下沿切出来（48 高）。 */
    /**
     * 屏幕边缘留白（2026-11-01）：页面往里收了的边"离页面边"少留（EdgeMargin.edges），各边不一样时 ⋯、菜单、调整栏、三键条、
     * 放在键盘外面的红点照各边的算；四边一样时（一个数的旧接口）和以前一模一样。
     */
    static void perSideEdges() {
        int[] pad = {0, 0, 400, 300};
        eq(KeyboardPlan.menuTop(pad, 400, 600, BW, BH, 0, new int[]{20, 12, 20, 20}), new int[]{172, 12}, "⋯：上边收了 8，离页面顶边只要 12");
        eq(KeyboardPlan.menuTop(pad, 400, 600, BW, BH, 0, EDGE), new int[]{172, 20}, "⋯：一个数时四边 20（同以前）");
        int[] right = {300, 0, 400, 300};
        eq(KeyboardPlan.menuTop(right, 400, 600, BW, BH, 0, new int[]{20, 20, 40, 20}), new int[]{304, 20}, "⋯：右边要 40，往左推到 304");
        eq(KeyboardPlan.menuTop(right, 400, 600, BW, BH, 0, EDGE), new int[]{322, 20}, "⋯：右边 20 时在触控板正中");
        check(KeyboardPlan.menuTop(pad, 400, 50, BW, BH, 0, new int[]{20, 12, 20, 12}) == null, "⋯：页面矮得上下留不出：放不下");
        // 菜单：左边收了 20 以上（e[0] = 0）时可以贴着页面左边；地方不够时两边按比例少留
        int[] leftAnchor = {0, 20, 56, 56};
        eq(KeyboardPlan.menuPosition(leftAnchor, 220, 200, 400, 600, new int[]{0, 20, 20, 20}, 8, false), new int[]{0, 64},
                "菜单：左边不用再留，贴着页面左边");
        eq(KeyboardPlan.menuPosition(leftAnchor, 220, 200, 400, 600, EDGE, 8, false), new int[]{20, 64}, "菜单：一个数时离左边 20");
        int[] rightAnchor = {170, 20, 226, 56};
        eq(KeyboardPlan.menuPosition(rightAnchor, 220, 200, 230, 600, new int[]{0, 20, 30, 20}, 8, false), new int[]{0, 64},
                "菜单：只空 10，左边要 0、右边要 30：全给右边");
        eq(KeyboardPlan.menuPosition(rightAnchor, 220, 200, 230, 600, EDGE, 8, false), new int[]{5, 64},
                "菜单：一个数时两边各一半（同以前）");
        // 菜单怎么排：上下都收了 20 以上（e 的上下是 0），七项一列每项还是 44；一个数时只有 41
        int[] g = KeyboardPlan.menuGrid(7, 360, 380, new int[]{20, 0, 20, 0}, 6, 6, 208, 44, 36, 120);
        check(g[0] == 1 && g[3] == 44, "菜单：上下不用再留，一列每项 44：" + Arrays.toString(g));
        g = KeyboardPlan.menuGrid(7, 360, 380, EDGE, 6, 6, 208, 44, 36, 120);
        check(g[0] == 1 && g[3] == 41, "菜单：一个数时一列每项 41：" + Arrays.toString(g));
        // 调整栏：上边要 12、下边 0
        check(KeyboardPlan.adjustBarTop(400, 36, 12, 0, 200, 400) == 12, "调整栏：键盘在下半，放上面、离页面顶边 12");
        check(KeyboardPlan.adjustBarTop(400, 36, 12, 0, 0, 200) == 364, "调整栏：键盘在上半，放下面、贴着页面下边");
        check(KeyboardPlan.adjustBarTop(400, 36, EDGE, 0, 200) == KeyboardPlan.adjustBarTop(400, 36, EDGE, EDGE, 0, 200),
                "调整栏：一个数 = 上下一样");
        // 三键条：下边收了 16（只要 4）
        KeyboardPlan.Sizes t4 = T.withEdges(new int[]{20, 20, 20, 4});
        int[][] c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 300}, 400, 300, false, t4);
        eq(c[1], new int[]{20, 248, 380, 296}, "三键条：下边只要 4");
        c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 300}, 400, 300, false, T);
        eq(c[1], new int[]{20, 232, 380, 280}, "三键条：四边 20 时离下边 20");
        c = KeyboardPlan.cutBar(new int[]{0, 0, 400, 300}, 400, 300, true, T.withEdges(new int[]{0, 0, 30, 0}));
        eq(c[1], new int[]{0, 0, 370, 48}, "三键条：左、上不用留，右边 30");
        // 四边一样的 withEdges = 原来那份
        KeyboardPlan.Sizes even = T.withEdges(new int[]{EDGE, EDGE, EDGE, EDGE});
        for (int mode : new int[]{KeyboardPlan.MODE_AUTO, KeyboardPlan.MODE_SPLIT, KeyboardPlan.MODE_MERGE}) {
            for (int pointer : new int[]{KeyboardPlan.POINTER_PAD, KeyboardPlan.POINTER_STICK}) {
                for (int[] wh : new int[][]{{400, 800}, {900, 350}, {300, 260}}) {
                    KeyboardPlan.Plan a = KeyboardPlan.plan(mode, pointer, KeyboardPlan.KEYS_TOP, wh[0], wh[1], null, null, wh[0] > wh[1], T, L9);
                    KeyboardPlan.Plan b = KeyboardPlan.plan(mode, pointer, KeyboardPlan.KEYS_TOP, wh[0], wh[1], null, null, wh[0] > wh[1], even, L9);
                    check(same(a, b) && Arrays.equals(a.keys, b.keys) && Arrays.equals(a.stick, b.stick),
                            "四边 20 的 withEdges 和原来一样：" + mode + " " + pointer + " " + Arrays.toString(wh));
                }
            }
        }
    }

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
        check(p.hole == null && !overlap(p.keys, p.keyboard, 0), "三键条不在键盘里（键盘不让空位）");
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
     * 交给真正的 TouchRouter / KeyFingers（照 MainActivity + KeyboardPanel 报区域的顺序：红点、三个鼠标键（铺满空位的三格）、键、键盘范围）：
     * 红点圆里的每一点归红点、空位里的每一点归那一格的鼠标键；别处和没有红点、鼠标键时一模一样（"缝分给最近的键只改按在缝里的触点"照样成立）；
     * 被切的键上看得见的那块（切口圆外）照样是那个键，红点报上来的正方形四个角也是；nearestKey 永远不选红点、鼠标键；
     * 面板自己收触摸时（KeyFingers 带洞：红点圆、空位）同样。三层都扫。
     */
    static void stickRouting() {
        Object[][] cases = {
                {400, 800, false, L9}, {360, 780, false, LA}, {900, 350, true, L9}, {1100, 700, true, L0}, {411, 880, false, L1},
                {884, 360, true, LA0},
        };
        int points = 0, inStick = 0, inKeys = 0, corners = 0, bad = 0;
        String first = null;
        for (Object[] cs : cases) {
            int w = (Integer) cs[0], h = (Integer) cs[1];
            KeyRows.Row[][] layers = (KeyRows.Row[][]) cs[3];
            KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_AUTO, KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, w, h, null, null,
                    (Boolean) cs[2], T, layers);
            check(p.stickAt == KeyboardPlan.STICK_KEYS && p.keys != null && !p.split, w + "×" + h + "：红点嵌在键里、有三个鼠标键、不分裂");
            if (p.stick == null || p.keys == null) continue;
            int[] k = p.keyboard;
            int pw = k[2] - k[0], ph = k[3] - k[1];
            float cx = (p.stick[0] + p.stick[2]) / 2f, cy = (p.stick[1] + p.stick[3]) / 2f;
            TouchRouter.Region stick = new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, p.stick[0], p.stick[1], p.stick[2], p.stick[3]);
            TouchRouter.Region[] tiles = new TouchRouter.Region[3];
            for (int i = 0; i < 3; i++) {
                int l = i == 0 ? p.keys[0] : i == 1 ? p.middleKey[0] : p.middleKey[1];
                int r = i == 0 ? p.middleKey[0] : i == 1 ? p.middleKey[1] : p.keys[2];
                tiles[i] = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, Proto.MOUSE_BUTTON_ID_BASE | (1 << i), l, p.keys[1], r, p.keys[3]);
            }
            TouchRouter.Region gap = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, 0, p.keys[0], p.keys[1], p.keys[2], p.keys[3]);
            for (int layer = 0; layer < 3; layer++) {
                List<KeyRows.Placed> keys = KeyRows.place(layers[layer], pw, ph, false, KeyRows.MIDDLE_PAD, T.capGap, p.hole, p.spread);
                List<TouchRouter.Region> keyRegions = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) {
                    KeyRows.Placed q = keys.get(i);
                    keyRegions.add(new TouchRouter.Region(q.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                            k[0] + q.left, k[1] + q.top, k[0] + q.right, k[1] + q.bottom));
                }
                TouchRouter.Region area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, k[0], k[1], k[2], k[3]);
                List<TouchRouter.Region> with = new ArrayList<>(), without = new ArrayList<>(keyRegions);
                with.add(stick);
                with.addAll(Arrays.asList(tiles));
                with.addAll(keyRegions);
                with.add(area);
                without.add(area);
                TouchRouter a = new TouchRouter(new KeyRowsTest.Quiet()), b = new TouchRouter(new KeyRowsTest.Quiet());
                a.setRegions(with.toArray(new TouchRouter.Region[0]));
                b.setRegions(without.toArray(new TouchRouter.Region[0]));
                TouchRouter.Region[] keyArr = keyRegions.toArray(new TouchRouter.Region[0]);
                TouchRouter.Region[] areaArr = {area};
                TouchRouter.Region[] holes = {stick, gap};
                TouchRouter.Region[] all = with.toArray(new TouchRouter.Region[0]);
                // 红点四周一圈（连挨着的键）、空位四周逐像素扫，别处隔 3 像素扫一个
                for (int y = k[1]; y < k[3]; y++) {
                    for (int x = k[0]; x < k[2]; x++) {
                        boolean nearStick = x >= p.stick[0] - 40 && x < p.stick[2] + 40 && y >= p.stick[1] - 40 && y < p.stick[3] + 40;
                        boolean nearGap = x >= p.keys[0] - 6 && x < p.keys[2] + 6 && y >= p.keys[1] - 6;
                        if (!nearStick && !nearGap && (x % 3 != 0 || y % 3 != 0)) continue;
                        if (nearGap && !nearStick && (x % 2 != 0 || y % 2 != 0)) continue;
                        points++;
                        TouchRouter.Region got = a.hit(x, y), plain = b.hit(x, y);
                        TouchRouter.Region finger = KeyFingers.pick(keyArr, areaArr, holes, x, y);
                        TouchRouter.Region tile = null;
                        for (TouchRouter.Region tr : tiles) if (tr.contains(x, y)) tile = tr;
                        String why = null;
                        if (stick.contains(x, y)) {
                            inStick++;
                            if (got != stick) why = "红点圆里却归了 " + got;
                            else if (finger != null) why = "面板自己收触摸：红点圆里却分给了键";
                        } else if (tile != null) {
                            inKeys++;
                            if (got != tile) why = "空位里却归了 " + got;
                            else if (finger != null) why = "面板自己收触摸：空位里却分给了键";
                        } else {
                            if (got != plain) why = "红点、空位外和没有它们时不一样：" + got + " / " + plain;
                            else if (finger == null ? KeyFingers.pick(keyArr, areaArr, x, y) != null
                                    : finger != KeyFingers.pick(keyArr, areaArr, x, y)) why = "面板自己收触摸：洞外和没洞时不一样";
                            // 正方形的角、切口圆里被切的那一圈（圆外）：落在键帽矩形里的就是那个键
                            boolean square = x >= p.stick[0] && x < p.stick[2] && y >= p.stick[1] && y < p.stick[3];
                            for (TouchRouter.Region kr : keyArr) {
                                if (!kr.contains(x, y)) continue;
                                if (square) corners++;
                                if (why == null && got != kr) why = "看着是键（" + kr + "）按上去却是 " + got;
                            }
                        }
                        if (why == null && TouchRouter.nearestKey(all, area, x, y) != null) {
                            TouchRouter.Region nk = TouchRouter.nearestKey(all, area, x, y);
                            if (nk.type == Proto.REGION_STICK || nk.type == Proto.REGION_MOUSE_BUTTON) why = "nearestKey 选了红点 / 鼠标键";
                        }
                        if (why != null) {
                            bad++;
                            if (first == null) first = w + "×" + h + " 第 " + layer + " 层 (" + x + ", " + y + ")：" + why;
                        }
                    }
                }
            }
        }
        check(bad == 0, "红点、鼠标键和键的分流扫了 " + points + " 个点（红点圆里 " + inStick + "、空位里 " + inKeys + "、方块角上的键 " + corners
                + "），" + bad + " 个不对" + (first == null ? "" : "，比如 " + first));
        check(inStick > 0 && inKeys > 0 && corners > 0, "扫到了红点圆里、空位里、方块角上的点");
        System.err.println("  小红点分流扫描：" + points + " 个点（红点圆里 " + inStick + "、空位里 " + inKeys + "、方块角上的键 " + corners + "）");
    }

    private static KeyRows.Placed find(List<KeyRows.Placed> ps, String label) {
        for (KeyRows.Placed q : ps) if (q.key.label.equals(label)) return q;
        return null;
    }

    /** 扫一遍：各种尺寸、布局、指点、三键条、存的位置、键（快捷行有没有、苹果）下，红点 / 切口 / 三个鼠标键 / 三键条 / ⋯ 守规矩。 */
    static void stickSweep() {
        float[][] saves = {null, {0, 0, 1, 1}, {0, 0.3f, 1, 1}, {0.4f, 0, 1, 1}, {0, 0, 0.6f, 1}, {0.2f, 0.4f, 0.8f, 0.9f}};
        float[][] cells = {null, {0, 0, 0.5f, 1}, {0.05f, 0.1f, 0.35f, 0.9f}, {0.1f, 0.4f, 0.4f, 1}, {0, 0.2f, 0.47f, 1}};
        KeyRows.Row[][][] boards = {L9, L0, LA, L1, LA0};
        int bad = 0, n = 0, inKeys = 0, outside = 0, none = 0, holes = 0, sym = 0, narrow = 0, bars = 0, tiny = 0, moved = 0, spreads = 0;
        String first = null;
        int bi = 0;
        for (int w = 150; w <= 2600; w += 110) {
            for (int h = 100; h <= 2000; h += 95) {
                for (int mode = 0; mode < 3; mode++) {
                    for (int pointer = 0; pointer < 3; pointer++) {
                        for (int keys = 0; keys < (pointer == KeyboardPlan.POINTER_PAD ? 3 : 1); keys++) {
                            for (float[] saved : saves) {
                                // 小红点不看分裂格子（固定合并，stickNeverSplits 查过），少扫几种
                                for (int ci = 0; ci < (pointer == KeyboardPlan.POINTER_STICK ? 2 : cells.length); ci++) {
                                    float[] cell = cells[ci];
                                    n++;
                                    KeyRows.Row[][] L = boards[bi++ % boards.length];
                                    KeyboardPlan.Plan p = KeyboardPlan.plan(mode, pointer, keys, w, h, saved, cell, w > h, T, L);
                                    String why = stickViolation(p, pointer, keys, w, h, L);
                                    if (p.stickAt == KeyboardPlan.STICK_KEYS) inKeys++;
                                    if (p.stickAt == KeyboardPlan.STICK_OUTSIDE) outside++;
                                    if (pointer == KeyboardPlan.POINTER_STICK && p.stick == null) none++;
                                    if (p.hole != null) {
                                        holes++;
                                        int cx = KeyRows.stickPoint(p.keyboard[2] - p.keyboard[0], 1, L[0].length)[0];
                                        if (p.hole[0] + p.hole[1] == 2 * cx && p.hole[1] - p.hole[0] >= T.holeWidth(T.keyW)) sym++;
                                        if (p.middleKey[1] - p.middleKey[0] - 4 < 44) narrow++;
                                        if (p.spread) spreads++;
                                    }
                                    if (pointer == KeyboardPlan.POINTER_PAD && p.keys != null) bars++;
                                    if (p.stick != null && p.stick[2] - p.stick[0] == 32) tiny++;
                                    if (p.stickAt == KeyboardPlan.STICK_KEYS && labelsMoved(p, L)) moved++;
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
        String seen = "扫到的里面有嵌在键里的 " + inKeys + "（有键上的字挪开的 " + moved + "）、放外面的 " + outside + "（取最小 32 的 " + tiny
                + "、放不下的 " + none + "）、让了空位的 " + holes + "（对称的 " + sym + "、鼠标键不到 44 宽的 " + narrow + "、快捷行也让位的 " + spreads
                + "）、三键条 " + bars;
        check(inKeys > 0 && outside > 0 && none > 0 && holes > 0 && sym > 0 && narrow > 0 && holes > sym && bars > 0 && moved > 0
                && spreads > 0 && spreads < holes, seen);
        System.err.println("  小红点扫描：" + seen);
    }

    /** 嵌着的红点有没有让哪个键上的字挪开（KeyboardPlan.labelShift 不是 0）。 */
    private static boolean labelsMoved(KeyboardPlan.Plan p, KeyRows.Row[][] L) {
        int[] k = p.keyboard;
        float cx = (p.stick[0] + p.stick[2]) / 2f - k[0], cy = (p.stick[1] + p.stick[3]) / 2f - k[1];
        for (List<KeyRows.Placed> layer : KeyboardPlan.placedLayers(L, k[2] - k[0], k[3] - k[1], T, p.hole, p.spread)) {
            for (KeyRows.Placed q : layer) {
                if (KeyboardPlan.cuts(q, cx, cy, p.cut) && KeyboardPlan.labelShift(q, cx, cy, p.cut, T.labelRoom) != 0) return true;
            }
        }
        return false;
    }

    private static String stickViolation(KeyboardPlan.Plan p, int pointer, int keys, int w, int h, KeyRows.Row[][] L) {
        int[] k = p.keyboard;
        if (k[0] < 0 || k[1] < 0 || k[2] > w || k[3] > h) return "键盘出了键盘区";
        if (p.split && KeyRows.splitUnit(w, p.middle) < MIN_KEY) return "分裂了但键太窄";
        if (pointer == KeyboardPlan.POINTER_STICK && p.split) return "小红点却分裂了";
        if (pointer != KeyboardPlan.POINTER_PAD && p.pad != null) return "没选触控板却放了触控板";
        if (pointer != KeyboardPlan.POINTER_PAD && p.menuArea != null) return "没有触控板却给 ⋯ 留了地方（⋯ 该在功能行上）";
        if (pointer != KeyboardPlan.POINTER_STICK && (p.stick != null || p.stickAt != KeyboardPlan.STICK_NONE || p.hole != null || p.cut != 0)) {
            return "没选小红点却放了红点 / 让了空位";
        }
        if ((p.stick == null) != (p.stickAt == KeyboardPlan.STICK_NONE)) return "stickAt 和 stick 对不上";
        if ((p.cut > 0) != (p.stickAt == KeyboardPlan.STICK_KEYS)) return "cut 和 stickAt 对不上";
        if (pointer == KeyboardPlan.POINTER_NONE && p.keys != null) return "指点关着却有三个鼠标键";
        if (pointer == KeyboardPlan.POINTER_PAD && keys == KeyboardPlan.KEYS_OFF && p.keys != null) return "没开三键条却切了";
        if (pointer == KeyboardPlan.POINTER_PAD && p.pad != null && p.menuArea == null) return "有触控板没给 ⋯ 地方";
        if ((p.keys == null) != (p.middleKey == null)) return "middleKey 和 keys 对不上";
        if (pointer == KeyboardPlan.POINTER_STICK && (p.keys == null) != (p.hole == null)) return "keys 和 hole 对不上";
        int pw = k[2] - k[0], ph = k[3] - k[1];
        KeyRows.Row[] letters = L[0];
        int floor = p.hole != null ? KeyRows.holeTop(letters, ph) : ph;
        if (p.keys != null) {
            int[] r = p.keys, m = p.middleKey;
            if (r[0] < 0 || r[1] < 0 || r[2] > w || r[3] > h) return "三个键出了页面";
            if (pointer == KeyboardPlan.POINTER_STICK) {
                int holeBottom = KeyRows.holeBottom(letters, ph, p.spread);
                if (!Arrays.equals(r, new int[]{k[0] + p.hole[0], k[1] + floor, k[0] + p.hole[1], k[1] + holeBottom})) {
                    return "三个键不是空位那一块（底行上沿到让位的最后一行下沿）：" + Arrays.toString(r);
                }
                if (!Arrays.equals(p.hole, KeyboardPlan.stickHole(pw, letters, T))) return "空位不是 stickHole 给的";
                if (p.spread != KeyboardPlan.shortcutsSpread(p.hole, pw, letters, T)) return "spread 不是 shortcutsSpread 给的";
                // 快捷行：让了位的每个键（连缝）不窄于 32；没让的照旧铺满整行（和指点关着时一模一样）、三个键只到底行下沿
                List<KeyRows.Placed> now = KeyRows.place(letters, pw, ph, false, KeyRows.MIDDLE_PAD, T.capGap, p.hole, p.spread);
                List<KeyRows.Placed> full = KeyRows.place(letters, pw, ph, false, KeyRows.MIDDLE_PAD, T.capGap);
                int shortcuts = 0;
                for (int i = 0; i < now.size(); i++) {
                    KeyRows.Placed q = now.get(i);
                    if (!q.key.label.startsWith("快")) continue;
                    shortcuts++;
                    if (p.spread && q.right - q.left + 2 * T.capGap < MIN_KEY - 1) return "快捷行让了位、快捷键却比 32 窄：" + (q.right - q.left);
                    if (!p.spread) {
                        KeyRows.Placed f = full.get(i);
                        if (f.key != q.key || f.left != q.left || f.right != q.right || f.top != q.top) return "快捷行没让位却不是铺满整行";
                    }
                }
                if (p.spread && shortcuts == 0) return "没有快捷行却说快捷行也让了位";
                if (!p.spread && shortcuts > 0 && holeBottom == ph) return "快捷行没让位、三个键却伸到了快捷行";
                int g = T.capGap;
                int lw = m[0] - r[0] - 2 * g, mw = m[1] - m[0] - 2 * g, rw = r[2] - m[1] - 2 * g;   // 三个键帽的宽
                if (lw < MIN_KEY || mw < MIN_KEY || rw < MIN_KEY) return "鼠标键比 32 窄：" + lw + " / " + mw + " / " + rw;
                if (lw < mw || rw < mw) return "左右键比中键窄";
                float unit = pw / 10f;
                List<KeyRows.Placed> ps = KeyRows.place(letters, pw, ph, false, KeyRows.MIDDLE_PAD, g, p.hole, p.spread);
                // 底行 ?123 [,.] 空格 │ 空位 │ 空格 ↵（2026-11-01：逗号句号合成一个键、右边第二个空格，份数同以前的 , .）
                KeyRows.Placed sp = null, right = null, dual = null;
                for (KeyRows.Placed q : ps) {
                    if (q.key.id == 0x2C && sp == null) sp = q;
                    else if (q.key.id == 0x2C) right = q;
                    if (q.key.id == Proto.dualKey(0x36, 0x37)) dual = q;
                }
                if (sp == null || right == null || dual == null) return "底行少了空格 / 右空格 / 合并键";
                if (right.left < p.hole[1] || dual.right > p.hole[0]) return "合并键 / 右空格摆错了边";
                int spW = sp.right - sp.left + 2 * g;
                if (spW < Math.round(unit * KeyRows.STICK_SPACE_MIN) - 2) return "空格比 1 份短：" + spW;
                if (spW > Math.round(unit * KeyRows.STICK_SPACE) + 2) return "空格比 1.5 份长：" + spW;
                if (p.hole[1] > Math.round(unit * 7.5f)) return "空位右边过了右空格原来的左边（右空格 ↵ 变窄了）";
                int cx = KeyRows.stickPoint(pw, ph, letters.length)[0];
                // 第 1 步（对称、够三个 44）；不然是第 2 步（空格最短、右边到 . 原来的左边——碰巧也可能对称，但不到 144 宽）
                if (p.hole[0] + p.hole[1] == 2 * cx && p.hole[1] - p.hole[0] >= T.holeWidth(T.keyW)) {
                    if (lw < T.keyW || mw < T.keyW || rw < T.keyW) return "对称却有鼠标键不到 44：" + lw + " / " + mw + " / " + rw;
                    if (Math.abs(m[0] + m[1] - 2 * (k[0] + cx)) > 1 || Math.abs(lw - rw) > 1) return "对称却中键没正对红点 / 左右不一样宽";
                } else {
                    int shortest = Math.round(unit * (KeyRows.holeUnits(letters)[0] - (KeyRows.STICK_SPACE - KeyRows.STICK_SPACE_MIN)));
                    if (p.hole[0] != shortest || p.hole[1] != Math.round(unit * 7.5f)) return "不对称时空位不是最宽的那段";
                }
                for (KeyRows.Row[] rows : L) {
                    for (KeyRows.Placed q : KeyRows.place(rows, pw, ph, false, KeyRows.MIDDLE_PAD, g, p.hole, p.spread)) {
                        if (q.right > p.hole[0] && q.left < p.hole[1] && q.bottom > floor && q.top < holeBottom) return "空位里还有键：" + q.key.label;
                    }
                }
            } else {
                int mid = m[1] - m[0], left = m[0] - 4 - r[0], right = r[2] - m[1] - 4, inner = r[2] - r[0] - 8;
                if (r[2] - r[0] < T.keysWidth() || r[3] - r[1] != 48) return "三键条排不下 / 不是 48 高";
                if (left < mid || right < mid) return "左右键比中键窄：" + left + " / " + mid + " / " + right;
                if (mid < Math.min(56, inner / 3)) return "中键太窄：" + mid;
                if (inner / 5.4f >= 56 && Math.abs(mid - inner / 5.4f) > 1) return "中键不是总宽的 1 / 5.4：" + mid + " / " + inner;
                if (overlap(r, k, 0) && !(p.split && !overlap(r, p.leftHalf, 0) && !overlap(r, p.rightHalf, 0))) return "三键条压着键盘";
                if (p.pad != null && overlap(r, p.pad, 0)) return "三键条压着触控板";
                if (r[0] < EDGE || r[1] < EDGE || r[2] > w - EDGE || r[3] > h - EDGE) return "三键条离页面边不到 20";
                if (p.pad != null && Math.min(p.pad[2] - p.pad[0], p.pad[3] - p.pad[1]) < MIN_PAD) return "切完触控板太小";
                if (Math.abs((m[0] + m[1]) / 2 - (r[0] + r[2]) / 2) > 1) return "三键条的中键不在正中";
            }
        } else if (pointer == KeyboardPlan.POINTER_STICK) {
            if (KeyboardPlan.stickHole(pw, letters, T) != null) return "让得出空位却没有三个鼠标键";
        }
        if (pointer == KeyboardPlan.POINTER_STICK) {
            int[] at = KeyRows.stickPoint(pw, ph, letters.length);
            List<List<KeyRows.Placed>> pl = KeyboardPlan.placedLayers(L, pw, ph, T, p.hole, p.spread);
            if (p.stick != null) {
                int[] s = p.stick;
                int size = s[2] - s[0];
                if (size != s[3] - s[1]) return "红点不是正方形";
                if (size < 32 || size > 64) return "红点不在 32–64：" + size;
                if (p.keys != null && overlap(s, p.keys, 0)) return "红点压着三个鼠标键";
                if (p.stickAt == KeyboardPlan.STICK_KEYS) {
                    if (s[0] + s[2] != 2 * (k[0] + at[0]) || s[1] + s[3] != 2 * (k[1] + at[1])) return "嵌着的红点不在 g、h、v、b 那一点上";
                    float rc = p.cut, cx = at[0], cy = at[1];
                    if (Math.abs(rc - (size / 2f + T.stickGap / 2f)) > 1e-3f) return "切口半径不对：" + rc;
                    if (cx - rc < 0 || cx + rc > pw || cy - rc < 0 || cy + rc > floor) return "切口出了键盘 / 碰到三个鼠标键那两行";
                    String cutLetters = "";
                    for (int i = 0; i < pl.size(); i++) {
                        for (KeyRows.Placed q : pl.get(i)) {
                            if (!KeyboardPlan.cuts(q, cx, cy, rc)) continue;
                            if (i == 0) cutLetters += q.key.label;
                            float sh = KeyboardPlan.cutShare(q, cx, cy, rc);
                            if (sh > KeyboardPlan.STICK_MAX_SHARE + 1e-4f) return "第 " + i + " 层的 " + q.key.label + " 被切掉超过 25%：" + sh;
                            if (i == 0 && size > 32 && sh > KeyboardPlan.STICK_LETTER_SHARE + 1e-4f) return "字母层的 " + q.key.label + " 被切掉超过 16%：" + sh;
                            // 键上的字：照 KeyboardPanel 往远处取整挪，挪完那块字（半径 labelRoom）不碰切口、不出键帽
                            float d = KeyboardPlan.labelShift(q, cx, cy, rc, T.labelRoom);
                            if (Float.isNaN(d)) return "第 " + i + " 层的 " + q.key.label + " 上的字躲不开切口";
                            int shift = d < 0 ? -(int) Math.ceil(-d) : (int) Math.ceil(d);
                            float lx = (q.left + q.right) / 2f - cx, ly = (q.top + q.bottom) / 2f + shift - cy;
                            if (Math.sqrt(lx * lx + ly * ly) < rc + T.labelRoom - 1e-3) return "第 " + i + " 层的 " + q.key.label + " 上的字被切口吃掉";
                            if (Math.abs(shift) > (q.bottom - q.top) / 2f - T.labelRoom + 1) return "第 " + i + " 层的 " + q.key.label + " 上的字挪出了键帽";
                        }
                    }
                    for (String l : new String[]{"g", "h", "v", "b"}) if (!cutLetters.contains(l)) return "字母层没切到 " + l + "：" + cutLetters;
                } else if (p.stickAt == KeyboardPlan.STICK_OUTSIDE) {
                    if (overlap(s, k, 0)) return "外面的红点压着键盘";
                    if (s[0] < EDGE || s[1] < EDGE || s[2] > w - EDGE || s[3] > h - EDGE) return "外面的红点离页面边不到 20";
                    if (KeyboardPlan.stickRadius(pl, pw, floor, at, T) != 0) return "嵌得进却放到了外面";
                } else {
                    return "stickAt 认不出：" + p.stickAt;
                }
            } else {
                if (KeyboardPlan.stickRadius(pl, pw, floor, at, T) != 0) return "嵌得进却没放红点";
                if (k[1] - GAP - EDGE >= 32 && w - 2 * EDGE >= 32) return "上面放得下却没放红点";
            }
        }
        if (p.menuArea != null) {
            int[] c = KeyboardPlan.menuTop(p.menuArea, w, h, BW, BH, p.menuMargin, EDGE);
            if (c != null) {
                int[] mm = {c[0], c[1], c[0] + BW, c[1] + BH};
                if (c[0] < EDGE || c[1] < EDGE || mm[2] > w - EDGE || mm[3] > h - EDGE) return "⋯ 离边不到 20";
                if (p.keys != null && overlap(mm, p.keys, 0)) return "⋯ 压着三个键";
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
