package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体键盘逐级缩的状态（2026-11-01，方案 design-laptop §3.8），纯 Java（离线测，FixedLayoutTest.sizing）：这次显示哪一级、用哪份大小位置、
 * 调大小时按下抬起怎么换回、存哪份。KeyboardScreen 只调它（界面类离线编不到，1B 和小红点都在界面类里漏过必闪退的空指针，规则收在这里）。
 *
 * - 显示哪一级：每次量尺寸按整页宽现算（{@link #update}，KeyboardPlan.fixedLevel：第一个一格 ≥ 32dp 的），不记历史、不做回差。
 * - 大小位置按**显示着的**那个尺寸取、存（Boards 本来按 尺寸 × 指点 × 横竖屏 分开存）：缩成笔记本时用笔记本那份，在缩出来的笔记本上调大小也存进笔记本那份。
 *   存的是键盘区的 0–1 比例，TKL 调出来的比例套到笔记本上意思不同（键数、最窄宽度都不一样）；全尺寸和 TKL 来回换"各回各的"本来就是 1B 的规矩。
 * - 调大小从**显示着的**那份开始拖（存过的在窄窗口里可能被 KeyboardPlan.fitFixedRect 撑开过，{@link #planned} 记下）；没拖动就换回按下前那份、不存
 *   （同分裂的 planCell / cellBeforeDown）。拖到一半换了级（转屏、拖分隔条）、换了存的那几份、换了键盘：这一下作废，什么都不存，不会存进另一个尺寸。
 */
final class FixedSizing {
    /** 抬起时要存的一份：哪个尺寸、{左, 上, 右, 下}（0–1）。 */
    static final class Save {
        final String size;
        final float[] rect;

        Save(String size, float[] rect) {
            this.size = size;
            this.rect = rect;
        }
    }

    private List<FixedLayout> ladder = Collections.emptyList();
    /** 尺寸 → 现在这种指点 × 方向存的那份（没存过就没有这一项）。 */
    private final Map<String, float[]> saved = new HashMap<>();
    /** 显示着的那一级（ladder 的下标），-1 = 没显示实体键盘。 */
    private int level = -1;
    /** 显示那个尺寸的工作副本：planFixed 用它（custom 时）、拖的也是它（同一个数组，KeyboardScreen 的吸附直接写进来）。 */
    private final float[] work = new float[4];
    /** work 是用户的（存过的 / 正在拖的）；不然是默认位置（每次量尺寸按窗口重算，{@link #planned} 抄进来）。 */
    private boolean custom;
    /** 上次 planFixed 实际用的（撑过的、默认的）。 */
    private final float[] shown = new float[4];
    private boolean shownValid;
    /** 正在拖的尺寸；null = 没在拖，或者这一下作废了。 */
    private String dragSize;
    private final float[] beforeDown = new float[4], atDown = new float[4];
    private boolean customBefore;

    /** 换了键盘（语言 / 形状 / 尺寸 / 模式）：存一份拷贝，级数清掉（下次 {@link #update} 一定报"换了"，菜单的字跟着换），拖动作废。 */
    void setLadder(List<FixedLayout> l) {
        ladder = l == null ? Collections.emptyList() : new ArrayList<>(l);
        level = -1;
        custom = false;
        shownValid = false;
        dragSize = null;
    }

    List<FixedLayout> ladder() {
        return ladder;
    }

    /** 换了存的那几份（换指点、转屏、设置页回来）：拷贝进来，按显示着的尺寸重载工作副本，拖动作废。 */
    void setSaved(Map<String, float[]> m) {
        saved.clear();
        if (m != null) {
            for (Map.Entry<String, float[]> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue().length >= 4) saved.put(e.getKey(), Arrays.copyOf(e.getValue(), 4));
            }
        }
        dragSize = null;
        shownValid = false;
        reload();
    }

    /**
     * 每次量尺寸调：wanted = 选了实体键盘，w = 整页宽（已扣掉屏幕边缘留白）。显示的那一级和上次一样返回假；
     * 不一样（含显示 ↔ 不显示）就换过去、按新尺寸重载工作副本、正在拖的这一下作废，返回真（KeyboardScreen 据此松开面板的手指、通知菜单）。
     */
    boolean update(boolean wanted, int w, KeyboardPlan.Sizes s) {
        int lv = wanted ? KeyboardPlan.fixedLevel(ladder, w, s) : -1;
        if (lv == level) return false;
        level = lv;
        shownValid = false;
        dragSize = null;
        reload();
        return true;
    }

    /** 工作副本换成显示着的那个尺寸存的（没存过就是默认位置，等 {@link #planned}）。 */
    private void reload() {
        float[] r = level >= 0 ? saved.get(size()) : null;
        custom = r != null;
        if (custom) System.arraycopy(r, 0, work, 0, 4);
    }

    /** 显示着的那一级（-1 = 没显示实体键盘）。 */
    int level() {
        return level;
    }

    /** 显示着的那一块；没显示是 null。 */
    FixedLayout layout() {
        return level >= 0 && level < ladder.size() ? ladder.get(level) : null;
    }

    /** 显示着的尺寸（FixedLayout.ALL_SIZES 里的一种）；没显示是 null。 */
    String size() {
        FixedLayout l = layout();
        return l == null ? null : l.size;
    }

    /** 给 KeyboardPlan.planFixed 的 saved：用户的那份（存过 / 正在拖），默认位置时是 null。planFixed 会 clone，不会改它。 */
    float[] planInput() {
        return level >= 0 && custom ? work : null;
    }

    /** planFixed 实际用的（Plan.rect：撑过的、默认的）：记下来，调大小从它开始；默认位置时也抄进工作副本（默认位置跟着窗口走，同 1B）。 */
    void planned(float[] rect) {
        if (rect == null || rect.length < 4) return;
        System.arraycopy(rect, 0, shown, 0, 4);
        shownValid = true;
        if (!custom) System.arraycopy(rect, 0, work, 0, 4);
    }

    /**
     * 按到调大小的把手：从显示着的那份开始拖（存过的被撑开过就从撑开的开始，第一下不跳），按下前那份留着、没拖动就换回去。
     * 返回工作副本（同一个数组，拖的时候直接改它）；没显示实体键盘返回 null。
     */
    float[] beginDrag() {
        if (dragSize != null) {   // 上一下没抬起（照理不会：KeyboardScreen 每次抬起都 endDrag）：当它作废，先换回按下前那份
            System.arraycopy(beforeDown, 0, work, 0, 4);
            custom = customBefore;
            dragSize = null;
        }
        if (level < 0) return null;
        dragSize = size();
        System.arraycopy(work, 0, beforeDown, 0, 4);
        customBefore = custom;
        if (shownValid) System.arraycopy(shown, 0, work, 0, 4);
        System.arraycopy(work, 0, atDown, 0, 4);
        custom = true;   // 拖的途中就是用户的了，不然量尺寸时会换回默认位置
        return work;
    }

    /** 工作副本（拖的时候改的就是它）。 */
    float[] work() {
        return work;
    }

    /** 正在拖（按下了把手、还没抬起、没作废）。 */
    boolean dragging() {
        return dragSize != null;
    }

    /**
     * 抬起：这一下作废了（中途换过级、换过存的那几份、换过键盘）返回 null；没动（工作副本和按下时一样：只点了一下、拖出去又吸回原位）
     * 就换回按下前那份、不存，返回 null；动了就记成这个尺寸存的那份，返回要存进 Boards 的。
     */
    Save endDrag() {
        String sz = dragSize;
        dragSize = null;
        if (sz == null) return null;
        if (Arrays.equals(work, atDown)) {
            System.arraycopy(beforeDown, 0, work, 0, 4);
            custom = customBefore;
            return null;
        }
        saved.put(sz, work.clone());
        return new Save(sz, work.clone());
    }

    /** 恢复默认：清掉显示着的那个尺寸存的、回到默认位置，拖动作废；返回要在 Boards 里清的尺寸（没显示返回 null）。 */
    String reset() {
        dragSize = null;
        if (level < 0) return null;
        String sz = size();
        saved.remove(sz);
        custom = false;
        shownValid = false;
        return sz;
    }

    /** 这个尺寸存的那份（测试用；没有返回 null）。 */
    float[] savedFor(String size) {
        float[] r = saved.get(size);
        return r == null ? null : r.clone();
    }
}
