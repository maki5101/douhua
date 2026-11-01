package dev.douhua;

import java.util.Arrays;

/**
 * 键盘面板自己收触摸时（服务没接管、也没走本地分流），每根手指按的是哪个键：按手指编号（MotionEvent 的 pointer id）
 * 分别记，抬起只松开它自己那个键，别的手指按着的不受影响。纯 Java（离线测）。
 *
 * 按下时定键（{@link #pick}）：包含这个点的键优先；按在缝、空位、边距上分给最近的键——规则就是分流用的
 * {@link TouchRouter#nearestKey}，这里只是在面板自己的坐标里调它。嵌在键里的小红点那块不算任何键（pick 的 holes）。
 */
final class KeyFingers {
    static final int NONE = -1;
    /** MotionEvent 的 pointer id 是 0–31。 */
    private static final int POINTERS = 32;

    /** 手指编号 → 键（编号由调用方定，KeyboardPanel 用当前键列表的下标）；NONE = 这根手指没按着键。 */
    private final int[] keyOf = new int[POINTERS];

    KeyFingers() {
        Arrays.fill(keyOf, NONE);
    }

    /**
     * 按在 (x, y) 的手指该算哪个键：keys 里第一个包含它的；都不包含、但在键盘范围 area 里，就分给最近的键
     * （TouchRouter.nearestKey）；在范围外（多指时别处的手指也可能派到面板来）返回 null。
     */
    static TouchRouter.Region pick(TouchRouter.Region[] keys, TouchRouter.Region area, float x, float y) {
        if (!area.contains(x, y)) return null;
        for (TouchRouter.Region r : keys) if (r.contains(x, y)) return r;
        return TouchRouter.nearestKey(keys, area, x, y);
    }

    /**
     * 键盘有几块范围时（分裂键盘左右两半各一块）：落在哪块范围里就在那一块里定键（缝只分给这一块里的键，
     * 同分流 TouchRouter.hit）；哪块都不在（分裂时中间的触控板那一截）返回 null。
     */
    static TouchRouter.Region pick(TouchRouter.Region[] keys, TouchRouter.Region[] areas, float x, float y) {
        for (TouchRouter.Region a : areas) {
            if (a.contains(x, y)) return pick(keys, a, x, y);
        }
        return null;
    }

    /**
     * 同上，再加几块"洞"（嵌在键里的小红点）：落在洞里的手指不算任何键——连压在洞底下的键角也不算、缝里离得近的键也不分给它，
     * 同分流（红点的区域排在键前面，TouchRouter.hit 先命中它）。洞外的点和没有洞时一模一样。
     */
    static TouchRouter.Region pick(TouchRouter.Region[] keys, TouchRouter.Region[] areas, TouchRouter.Region[] holes,
                                   float x, float y) {
        for (TouchRouter.Region hole : holes) {
            if (hole.contains(x, y)) return null;
        }
        return pick(keys, areas, x, y);
    }

    /**
     * 这根手指按下了 key。返回这根手指之前按着、没等到抬起的键（照理不会有，调用方应当先松开它），没有返回 NONE。
     * 编号超出范围的手指不记，返回 NONE。
     */
    int down(int pointer, int key) {
        if (pointer < 0 || pointer >= POINTERS) return NONE;
        int old = keyOf[pointer];
        keyOf[pointer] = key;
        return old;
    }

    /** 这根手指抬起：返回它按着的键，没按着键返回 NONE。 */
    int up(int pointer) {
        if (pointer < 0 || pointer >= POINTERS) return NONE;
        int key = keyOf[pointer];
        keyOf[pointer] = NONE;
        return key;
    }

    /** 全部作废（ACTION_CANCEL、键要换掉了）：返回还按着的键（按手指编号排），都清掉。 */
    int[] cancel() {
        int n = 0;
        int[] held = new int[POINTERS];
        for (int p = 0; p < POINTERS; p++) {
            if (keyOf[p] == NONE) continue;
            held[n++] = keyOf[p];
            keyOf[p] = NONE;
        }
        return Arrays.copyOf(held, n);
    }
}
