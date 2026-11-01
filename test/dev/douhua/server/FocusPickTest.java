package dev.douhua.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 分屏里豆花拿着键盘焦点时挑谁接过焦点（{@link FocusPick}）的离线测试：从 RunningTaskInfo 按字段名读（假对象，字段名照 AOSP 的 TaskInfo，
 * 16 那种全有、13 那种没有 isVisibleRequested / isTopActivityNoDisplay、缺了必须有的字段要抛）、挑的规则（典型分屏、桌面 / SystemUI / 画中画 /
 * 看不见 / 正要藏起来 / 别的屏都不挑、豆花没拿着焦点就不交、最近用过的优先、一样就取靠前的；只在分屏时交——豆花是小窗 / 全屏 / 窗口模式读不出来不交，
 * 分屏时只交给另一半、浮在上面的小窗不抢；只读时"豆花拿着焦点的话"那一问），再拿系统给列表的两种排法（14–16、13）和随机列表扫一遍。
 * 不依赖 Android：tools/test.sh 用电脑上的 JDK 编译运行。
 */
public final class FocusPickTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        read16();
        read13();
        readWithoutWindowingMode();
        readMissingFieldThrows();
        readNulls();
        typicalSplit();
        notFocusedNoHandOff();
        noOwnTask();
        onlyHomeVisible();
        excluded();
        accepted();
        freeformOwn();
        notSplitOwn();
        splitOtherHalfOnly();
        assumeFocus();
        ownByBase();
        newestFirst();
        otherDisplay();
        focusedOn();
        systemOrder14();
        systemOrder13();
        randomLists();

        System.err.println("交焦点挑任务：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 假的 RunningTaskInfo（字段名照 android.app.TaskInfo） ═══════════

    static final class Component {
        private final String pkg;

        Component(String pkg) {
            this.pkg = pkg;
        }

        public String getPackageName() {
            return pkg;
        }
    }

    /** 16 那种：字段都有。 */
    static final class Info16 {
        public int taskId, displayId, topActivityType;
        public Component topActivity, baseActivity;
        public boolean isVisible, isVisibleRequested, isFocused, isTopActivityNoDisplay;
        public long lastActiveTime;
        int mode;

        public int getWindowingMode() {
            return mode;
        }
    }

    /** 13 那种：没有 isVisibleRequested、isTopActivityNoDisplay。 */
    static final class Info13 {
        public int taskId, displayId, topActivityType;
        public Component topActivity, baseActivity;
        public boolean isVisible, isFocused;
        public long lastActiveTime;

        public int getWindowingMode() {
            return 6;
        }
    }

    /** 没有 getWindowingMode（读不出窗口模式）。 */
    static final class InfoNoMode {
        public int taskId, displayId, topActivityType;
        public Component topActivity;
        public boolean isVisible, isFocused;
    }

    /** 缺 isFocused：这个系统版本的字段对不上。 */
    static final class InfoBroken {
        public int taskId, displayId, topActivityType;
        public Component topActivity;
        public boolean isVisible;
    }

    static void read16() throws Exception {
        Info16 i = new Info16();
        i.taskId = 42;
        i.displayId = 3;
        i.topActivityType = 1;
        i.topActivity = new Component("com.limelight");
        i.baseActivity = new Component("com.limelight.base");
        i.isVisible = true;
        i.isVisibleRequested = false;
        i.isFocused = true;
        i.isTopActivityNoDisplay = true;
        i.lastActiveTime = 1234;
        i.mode = 6;
        FocusPick.Task t = FocusPick.read(i);
        check(t.taskId == 42 && t.displayId == 3 && t.activityType == 1, "16：taskId / displayId / 类型");
        check("com.limelight".equals(t.topPackage) && "com.limelight.base".equals(t.basePackage), "16：两个包名");
        check(t.visible && Boolean.FALSE.equals(t.visibleRequested) && t.focused && t.noDisplay, "16：看得见 / 正要藏起来 / 焦点 / noDisplay");
        check(t.windowingMode == 6 && t.lastActive == 1234, "16：窗口模式走 getWindowingMode、lastActiveTime");
    }

    static void read13() throws Exception {
        Info13 i = new Info13();
        i.taskId = 7;
        i.topActivity = new Component("a.b");
        i.isVisible = true;
        FocusPick.Task t = FocusPick.read(i);
        check(t.visibleRequested == null, "13：没有 isVisibleRequested → 不知道");
        check(!t.noDisplay && t.basePackage == null && t.windowingMode == 6, "13：没有 noDisplay = 假，baseActivity 是 null");
        check(FocusPick.candidate(t), "13：看得见的普通应用照样能挑（不知道 isVisibleRequested 不挡）");
    }

    static void readWithoutWindowingMode() throws Exception {
        InfoNoMode i = new InfoNoMode();
        i.topActivity = new Component("x.y");
        i.isVisible = true;
        FocusPick.Task t = FocusPick.read(i);
        check(t.windowingMode == FocusPick.UNKNOWN && t.lastActive == 0, "没有 getWindowingMode → 不知道；没有 lastActiveTime → 0");
        check(FocusPick.candidate(t), "窗口模式不知道不挡候选");
    }

    static void readMissingFieldThrows() {
        boolean threw = false;
        try {
            FocusPick.read(new InfoBroken());
        } catch (NoSuchFieldException e) {
            threw = true;
        } catch (ReflectiveOperationException e) {
            threw = false;
        }
        check(threw, "缺必须有的字段（isFocused）抛 NoSuchFieldException（调用方当这个系统版本不支持）");
        threw = false;
        try {
            FocusPick.readAll(Arrays.asList(new Info16(), new InfoBroken()));
        } catch (ReflectiveOperationException e) {
            threw = true;
        }
        check(threw, "列表里有一个读不出也抛");
    }

    static void readNulls() throws Exception {
        Info16 i = new Info16();
        FocusPick.Task t = FocusPick.read(i);
        check(t.topPackage == null && t.basePackage == null && !FocusPick.candidate(t), "没有 Activity（topActivity 是 null）不挑");
        check(FocusPick.readAll(null).isEmpty(), "列表是 null → 空");
        check(FocusPick.readAll(Arrays.asList(null, i, null)).size() == 1, "列表里的 null 跳过");
    }

    // ═══════════ 挑谁 ═══════════

    static FocusPick.Task task(int id, int display, String pkg, boolean visible, boolean focused, int type, long last) {
        return new FocusPick.Task(id, display, pkg, pkg, visible, visible, focused, type, 6, false, last);
    }

    static FocusPick.Task own(boolean focused) {
        return task(1, 0, FocusPick.OWN_PACKAGE, true, focused, 1, 100);
    }

    static void typicalSplit() {
        // 用户主力机的样子：Moonlight 在上、豆花在下分屏，豆花刚从设置页回来拿着焦点；桌面在后面看不见
        List<FocusPick.Task> l = Arrays.asList(own(true), task(2, 0, "com.limelight", true, false, 1, 99),
                task(3, 0, "com.sec.android.app.launcher", false, false, 2, 50));
        FocusPick.Pick p = FocusPick.pick(l, 0);
        check(p.target != null && p.target.taskId == 2, "典型分屏：交给 Moonlight");
        check(p.why.contains("com.limelight") && p.why.contains("task 2"), "原因里写包名和 task 编号：" + p.why);
    }

    static void notFocusedNoHandOff() {
        List<FocusPick.Task> l = Arrays.asList(task(2, 0, "com.limelight", true, true, 1, 99), own(false));
        FocusPick.Pick p = FocusPick.pick(l, 0);
        check(p.target == null && p.why.contains("焦点已经不在豆花") && p.why.contains("com.limelight"), "豆花没拿着焦点（用户已经点了别处）：不交");
        p = FocusPick.pick(Arrays.asList(own(false), task(2, 0, "com.limelight", true, false, 1, 99)), 0);
        check(p.target == null, "谁都没拿着焦点：不交");
    }

    static void noOwnTask() {
        FocusPick.Pick p = FocusPick.pick(Arrays.asList(task(2, 0, "com.limelight", true, true, 1, 99)), 0);
        check(p.target == null && p.why.contains("没有豆花的任务"), "这块屏上没有豆花：不交");
        check(FocusPick.pick(new ArrayList<>(), 0).target == null, "空列表：不交");
    }

    static void onlyHomeVisible() {
        // 分屏的另一半没东西、只有桌面看得见：不交给桌面（WMS 点桌面也不挪焦点）
        List<FocusPick.Task> l = Arrays.asList(own(true), task(3, 0, "com.sec.android.app.launcher", true, false, 2, 99));
        FocusPick.Pick p = FocusPick.pick(l, 0);
        check(p.target == null && p.why.contains("没有别的看得见的应用"), "只有桌面看得见：不交（豆花照旧拿着焦点，不会没有焦点窗口）");
        // 豆花开成小窗浮在桌面上：小窗时本来就不交
        l = Arrays.asList(ownMode(5), new FocusPick.Task(3, 0, "com.sec.android.app.launcher", null, true, true, false, 2, 1, false, 99));
        p = FocusPick.pick(l, 0);
        check(p.target == null && p.why.contains("小窗"), "小窗浮在桌面上：不交");
    }

    static void excluded() {
        FocusPick.Task sysui = task(4, 0, FocusPick.SYSTEM_UI, true, false, 1, 99);
        FocusPick.Task recents = task(5, 0, "r", true, false, 3, 99);
        FocusPick.Task assistant = task(6, 0, "a", true, false, 4, 99);
        FocusPick.Task dream = task(7, 0, "d", true, false, 5, 99);
        FocusPick.Task pinned = new FocusPick.Task(8, 0, "p", "p", true, true, false, 1, FocusPick.WINDOWING_MODE_PINNED, false, 99);
        FocusPick.Task invisible = task(9, 0, "i", false, false, 1, 99);
        FocusPick.Task hiding = new FocusPick.Task(10, 0, "h", "h", true, false, false, 1, 6, false, 99);
        FocusPick.Task noDisplay = new FocusPick.Task(11, 0, "n", "n", true, true, false, 1, 6, true, 99);
        FocusPick.Task noActivity = new FocusPick.Task(12, 0, null, null, true, true, false, 1, 6, false, 99);
        FocusPick.Task home = task(13, 0, "home", true, false, 2, 99);
        String[] names = {"SystemUI", "最近任务", "助手", "屏保", "画中画", "看不见", "正要藏起来", "noDisplay", "没有 Activity", "桌面"};
        FocusPick.Task[] all = {sysui, recents, assistant, dream, pinned, invisible, hiding, noDisplay, noActivity, home};
        for (int i = 0; i < all.length; i++) {
            check(!FocusPick.candidate(all[i]), names[i] + "不挑");
            check(FocusPick.pick(Arrays.asList(own(true), all[i]), 0).target == null, names[i] + "：只有它时不交");
        }
        List<FocusPick.Task> l = new ArrayList<>(Arrays.asList(all));
        l.add(0, own(true));
        l.add(task(20, 0, "ok", true, false, 1, 1));
        FocusPick.Pick p = FocusPick.pick(l, 0);
        check(p.target != null && p.target.taskId == 20, "混在一起：只挑那个普通的（它的 lastActiveTime 最小也一样）");
    }

    static void accepted() {
        check(FocusPick.candidate(task(2, 0, "x", true, false, FocusPick.ACTIVITY_TYPE_UNDEFINED, 0)), "类型 0（没定）照样能挑");
        check(FocusPick.candidate(new FocusPick.Task(2, 0, "x", "x", true, null, false, 1, 6, false, 0)), "13 不知道 isVisibleRequested 能挑");
        // candidate 只看任务自己；在不在分屏的另一半由 pick 另外筛（下面几组）
        check(FocusPick.candidate(new FocusPick.Task(2, 0, "x", "x", true, true, false, 1, 5, false, 0)), "candidate：小窗（5）本身不挡");
        check(FocusPick.candidate(new FocusPick.Task(2, 0, "x", null, true, true, false, 1, 1, false, 0)), "candidate：全屏模式（1）本身不挡");
    }

    /** 豆花的任务，窗口模式随便给。 */
    static FocusPick.Task ownMode(int mode) {
        return new FocusPick.Task(1, 0, FocusPick.OWN_PACKAGE, FocusPick.OWN_PACKAGE, true, true, true, 1, mode, false, 100);
    }

    /**
     * 审查发现的那种：豆花是小窗浮在全屏应用上（Moonlight 全屏 / 浏览器全屏）。setFocusedTask 会把那个全屏应用挪到最上面、盖住豆花的小窗，
     * 所以小窗时不交，后面那个应用多近、多合适都一样。
     */
    static void freeformOwn() {
        FocusPick.Task full = new FocusPick.Task(2, 0, "com.limelight", "com.limelight", true, true, false, 1, 1, false, 99);
        FocusPick.Task split = new FocusPick.Task(3, 0, "b", "b", true, true, false, 1, 6, false, 98);
        FocusPick.Task popup = new FocusPick.Task(4, 0, "c", "c", true, true, false, 1, 5, false, 97);
        for (List<FocusPick.Task> l : Arrays.asList(Arrays.asList(ownMode(5), full), Arrays.asList(ownMode(5), split),
                Arrays.asList(ownMode(5), popup), Arrays.asList(full, ownMode(5), split, popup))) {
            FocusPick.Pick p = FocusPick.pick(l, 0);
            check(p.target == null && p.why.contains("小窗") && p.why.contains("盖住") && !p.ownNotFocused, "豆花是小窗：不交（" + l.size() + " 个任务）：" + p.why);
        }
    }

    /** 豆花的窗口模式不是分屏（6）：全屏 1（App 说没占满、系统却说全屏，对不上）、没定 0、读不出来、别的——都不交。 */
    static void notSplitOwn() {
        FocusPick.Task other = task(2, 0, "com.limelight", true, false, 1, 99);
        for (int mode : new int[]{FocusPick.UNKNOWN, 0, 1, 2, 3, 4, 7}) {
            FocusPick.Pick p = FocusPick.pick(Arrays.asList(ownMode(mode), other), 0);
            check(p.target == null && p.why.contains("不是分屏"), "豆花窗口模式 " + mode + "：不交：" + p.why);
        }
        check(FocusPick.pick(Arrays.asList(ownMode(FocusPick.UNKNOWN), other), 0).why.contains("读不出来"), "读不出来时原因里写明");
        check(FocusPick.pick(Arrays.asList(ownMode(6), other), 0).target == other, "豆花在分屏里（6）：交给另一半");
    }

    /**
     * 分屏时只交给另一半（窗口模式也是 6）：浮在分屏上的第三个应用的小窗（5）哪怕 lastActiveTime 最大、排在最前也不挑（审查发现：按最近挑会把焦点给它）；
     * 全屏的（1）、读不出来的也不挑。另一半没有能挑的就不交。
     */
    static void splitOtherHalfOnly() {
        FocusPick.Task popup = new FocusPick.Task(5, 0, "com.third", "com.third", true, true, false, 1, 5, false, 999);
        FocusPick.Task fullBehind = new FocusPick.Task(6, 0, "com.full", "com.full", true, true, false, 1, 1, false, 998);
        FocusPick.Task unknown = new FocusPick.Task(7, 0, "com.unknown", "com.unknown", true, true, false, 1, FocusPick.UNKNOWN, false, 997);
        FocusPick.Task half = task(2, 0, "com.limelight", true, false, 1, 10);
        FocusPick.Pick p = FocusPick.pick(Arrays.asList(popup, ownMode(6), fullBehind, unknown, half), 0);
        check(p.target == half, "分屏 + 浮着的小窗 + 后面的全屏 + 读不出来的：交给分屏的另一半");
        p = FocusPick.pick(Arrays.asList(popup, ownMode(6), fullBehind, unknown), 0);
        check(p.target == null && p.why.contains("分屏里没有别的"), "另一半没有能挑的：不交（小窗、全屏的都不顶上）：" + p.why);
    }

    /** needFocus = false（Server tasks 只读、豆花没拿着焦点时）：当豆花拿着焦点算，别的规则一样。 */
    static void assumeFocus() {
        FocusPick.Task moon = task(2, 0, "com.limelight", true, true, 1, 99);
        List<FocusPick.Task> l = Arrays.asList(moon, own(false));
        FocusPick.Pick p = FocusPick.pick(l, 0);
        check(p.target == null && p.ownNotFocused, "交焦点生效以后（焦点在 Moonlight）：照常不交，标出是因为豆花没拿着焦点");
        FocusPick.Pick h = FocusPick.pick(l, 0, false);
        check(h.target == moon && !h.ownNotFocused, "只读的那一问：豆花拿着焦点的话会交给 Moonlight（它这时拿着焦点也能挑）");
        h = FocusPick.pick(Arrays.asList(moon, new FocusPick.Task(1, 0, FocusPick.OWN_PACKAGE, null, true, true, false, 1, 5, false, 1)), 0, false);
        check(h.target == null && h.why.contains("小窗"), "只读的那一问也守分屏的规矩：豆花是小窗就说不交");
        check(FocusPick.pick(Arrays.asList(moon), 0, false).target == null && !FocusPick.pick(Arrays.asList(moon), 0).ownNotFocused,
                "没有豆花的任务：两种都不交、不算\"没拿着焦点\"");
        p = FocusPick.pick(Arrays.asList(own(true), task(2, 0, "x", true, false, 1, 1)), 0);
        check(p.target != null && !p.ownNotFocused, "交了的不带 ownNotFocused");
    }

    static void ownByBase() {
        // 豆花的任务里开了别人的 Activity（比如选文件）：最底下是豆花，也算豆花的任务
        FocusPick.Task t = new FocusPick.Task(1, 0, "com.android.documentsui", FocusPick.OWN_PACKAGE, true, true, true, 1, 6, false, 100);
        check(t.own() && !FocusPick.candidate(t), "最底下是豆花：算豆花的、不挑");
        FocusPick.Pick p = FocusPick.pick(Arrays.asList(t, task(2, 0, "com.limelight", true, false, 1, 99)), 0);
        check(p.target != null && p.target.taskId == 2, "这时豆花的任务拿着焦点，照样交给另一半");
    }

    static void newestFirst() {
        List<FocusPick.Task> l = Arrays.asList(own(true), task(2, 0, "old", true, false, 1, 10), task(3, 0, "new", true, false, 1, 20));
        check(FocusPick.pick(l, 0).target.taskId == 3, "最近用过的优先（lastActiveTime 大）");
        l = Arrays.asList(own(true), task(2, 0, "first", true, false, 1, 10), task(3, 0, "second", true, false, 1, 10));
        check(FocusPick.pick(l, 0).target.taskId == 2, "一样大取列表里靠前的");
    }

    static void otherDisplay() {
        List<FocusPick.Task> l = Arrays.asList(own(true), task(2, 2, "external", true, false, 1, 999), task(3, 0, "here", true, false, 1, 1));
        check(FocusPick.pick(l, 0).target.taskId == 3, "别的屏上的不挑（哪怕更近）");
        l = Arrays.asList(task(1, 2, FocusPick.OWN_PACKAGE, true, true, 1, 5), task(3, 0, "here", true, false, 1, 1));
        check(FocusPick.pick(l, 0).target == null, "豆花在别的屏上拿着焦点：这块屏不交");
    }

    static void focusedOn() {
        List<FocusPick.Task> l = Arrays.asList(task(5, 2, "x", true, true, 1, 1), task(2, 0, "a", true, false, 1, 1), task(3, 0, "b", true, true, 1, 1));
        check(FocusPick.focusedOn(l, 0).taskId == 3, "focusedOn：这块屏上拿着焦点的");
        check(FocusPick.focusedOn(l, 1) == null, "focusedOn：没有就是 null");
    }

    /**
     * 14–16 的 RunningTasks.getTasks 给的顺序：有焦点的看得见的 → 看得见的从上到下 → 看不见的；看得见的 lastActiveTime 重写成 now + size - i（递减），
     * 看不见的照原样（更早）。挑出来的应该是看得见的里第一个能挑的。
     */
    static void systemOrder14() {
        long now = 1_000_000;
        List<FocusPick.Task> raw = new ArrayList<>();
        raw.add(task(1, 0, FocusPick.OWN_PACKAGE, true, true, 1, 0));
        raw.add(task(4, 0, FocusPick.SYSTEM_UI, true, false, 1, 0));
        raw.add(task(2, 0, "com.limelight", true, false, 1, 0));
        raw.add(task(5, 0, "com.other", true, false, 1, 0));
        raw.add(task(3, 0, "launcher", false, false, 2, 500));
        raw.add(task(6, 0, "background", false, false, 1, 900));
        int size = raw.size(), visible = 4;
        List<FocusPick.Task> l = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            FocusPick.Task t = raw.get(i);
            long last = i < visible ? now + size - i : t.lastActive;
            l.add(new FocusPick.Task(t.taskId, t.displayId, t.topPackage, t.basePackage, t.visible, t.visibleRequested, t.focused,
                    t.activityType, t.windowingMode, t.noDisplay, last));
        }
        check(FocusPick.pick(l, 0).target.taskId == 2, "14–16 的顺序：交给看得见的里最上面那个能挑的（Moonlight）");
    }

    /** 13 的 RunningTasks 按 lastActiveTime 从大到小排，看得见的现刷新（不是焦点的再往下挪一点）：一样挑到最近的看得见的。 */
    static void systemOrder13() {
        List<FocusPick.Task> l = Arrays.asList(
                new FocusPick.Task(1, 0, FocusPick.OWN_PACKAGE, null, true, null, true, 1, 6, false, 5000),
                new FocusPick.Task(2, 0, "com.limelight", null, true, null, false, 1, 6, false, 4999),
                new FocusPick.Task(6, 0, "background", null, false, null, false, 1, 1, false, 4000));
        check(FocusPick.pick(l, 0).target.taskId == 2, "13 的顺序：交给 Moonlight");
    }

    /**
     * 随机列表：挑出来的一定能挑、在这块屏上、在分屏里（6）、是最近的（一样取靠前的），而且豆花拿着焦点的那个任务在分屏里；
     * 不交时一定是豆花没拿着焦点、豆花不在分屏里，或者分屏里真没有能挑的。
     */
    static void randomLists() {
        Random r = new Random(1101);
        String[] pkgs = {FocusPick.OWN_PACKAGE, "com.limelight", FocusPick.SYSTEM_UI, "a", "b", null};
        int[] modes = {FocusPick.UNKNOWN, 1, 2, 5, 6, 6, 6, 6, 6};   // 偏向分屏（6），不然交出去的情况太少
        int picked = 0, notFocused = 0, notSplit = 0, none = 0;
        boolean ok = true;
        for (int iter = 0; iter < 5000; iter++) {
            int n = 1 + r.nextInt(8);
            List<FocusPick.Task> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String pkg = pkgs[r.nextInt(pkgs.length)];
                String base = r.nextInt(5) == 0 ? pkgs[r.nextInt(pkgs.length)] : pkg;
                Boolean req = r.nextInt(4) == 0 ? null : r.nextBoolean();
                int mode = modes[r.nextInt(modes.length)];
                // 偏向"能挑"的那种（看得见、普通应用），不然交出去的情况太少
                l.add(new FocusPick.Task(i + 1, r.nextInt(3) == 0 ? 2 : 0, pkg, base, r.nextInt(4) != 0, req, r.nextInt(3) == 0,
                        r.nextInt(3) == 0 ? r.nextInt(6) : 1, mode, r.nextInt(8) == 0, r.nextInt(5)));
            }
            // 三分之二的列表里放一个拿着焦点的豆花（就是要交焦点的那种情况），位置随便；多半在分屏里，偶尔是小窗
            if (r.nextInt(3) != 0) l.add(r.nextInt(l.size() + 1), new FocusPick.Task(99, 0, FocusPick.OWN_PACKAGE, FocusPick.OWN_PACKAGE,
                    true, true, true, 1, r.nextInt(5) == 0 ? 5 : 6, false, r.nextInt(5)));
            FocusPick.Pick p = FocusPick.pick(l, 0);
            // 模型：这块屏上第一个拿着焦点的豆花任务、它在不在分屏里、分屏里有没有能挑的
            FocusPick.Task ownF = null;
            boolean anyCandidate = false;
            for (FocusPick.Task t : l) {
                if (t.displayId != 0) continue;
                if (t.own() && t.focused && ownF == null) ownF = t;
                anyCandidate |= FocusPick.candidate(t) && t.windowingMode == 6;
            }
            boolean split = ownF != null && ownF.windowingMode == 6;
            if (p.target == null) {
                ok &= ownF == null || !split || !anyCandidate;
                if (ownF == null) notFocused++;
                else if (!split) notSplit++;
                else none++;
                continue;
            }
            picked++;
            ok &= split && FocusPick.candidate(p.target) && p.target.windowingMode == 6 && p.target.displayId == 0;
            int idx = l.indexOf(p.target);
            for (int i = 0; i < l.size(); i++) {
                FocusPick.Task t = l.get(i);
                if (t.displayId != 0 || !FocusPick.candidate(t) || t.windowingMode != 6) continue;
                ok &= t.lastActive < p.target.lastActive || (t.lastActive == p.target.lastActive && i >= idx);
            }
        }
        check(ok, "随机 5000 组：挑出来的能挑、在这块屏、在分屏里、最近（交了 " + picked + "，豆花没拿着焦点 " + notFocused
                + "，豆花不在分屏里 " + notSplit + "，分屏里没有能挑的 " + none + "）");
        check(picked > 500 && notFocused > 500 && notSplit > 200 && none > 200,
                "随机里四种情况都有（" + picked + " / " + notFocused + " / " + notSplit + " / " + none + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
