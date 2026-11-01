package dev.douhua.server;

/**
 * 把归系统的那些手指（{@link TouchSplit}）拼成一条自洽的触摸事件流，交给注入。纯 Java、不依赖 Android（离线测试）：
 * 只吐出纯数据（{@link Event}），由 {@link Injector#motion} 变成 MotionEvent。
 *
 * 为什么要严格自洽：Android 16 起系统对注入的触摸流做一致性校验（InputDispatcher 的 shouldRejectInjectedMotionLocked，
 * 规则在 input_verifier.rs），不自洽的事件直接拒，而且那份校验状态只在屏幕被移除时才清。规则（我们按它拼）：
 * - DOWN / UP 只带 1 根手指；DOWN 时不能已有手指按着。
 * - POINTER_DOWN：已按着的个数 = 事件里的个数 − 1，新手指的编号不能已经在。
 * - MOVE / POINTER_UP / CANCEL：事件里的手指集合必须正好等于当前按着的。
 * - CANCEL 必须带 FLAG_CANCELED（系统不替你加）。
 *
 * 用法和 MtDecoder 的回调一一对应：down / move / frame / up；手掌、丢数据、释放独占时 {@link #cancelAll}。
 * cancelAll 之后，那些还按着的手指算作废：之后的移动、抬起都不再注入，抬起时忘掉；新按下的手指照常开一条新流。
 * 手指编号用 MtDecoder 的 slot（0–15），同一根手指整个过程不变；事件里的手指按编号从小到大排。
 */
final class InjectStream {
    /** 和 android.view.MotionEvent 的常量一致（这里不能引用 Android 类）。 */
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;
    static final int ACTION_CANCEL = 3;
    static final int ACTION_POINTER_DOWN = 5;
    static final int ACTION_POINTER_UP = 6;
    static final int ACTION_POINTER_INDEX_SHIFT = 8;
    static final int FLAG_CANCELED = 0x20;
    /**
     * MotionEvent.FLAG_NO_FOCUS_CHANGE（@hide，值取自 AOSP MotionEventFlag.aidl）：系统分发这个 DOWN 时不把键盘焦点挪给点到的窗口
     * （InputDispatcher 对带 POLICY_FLAG_TRUSTED 的事件认它，注入的都带）。按在豆花窗口里手势带的手指（{@link TouchSplit#SYSTEM_IN_WINDOW}）
     * 的 DOWN / POINTER_DOWN 带上它，见 {@link #down}。
     */
    static final int FLAG_NO_FOCUS_CHANGE = 0x40;

    /** 一个要注入的事件。数组按指针下标排（下标 = 在本事件里的位置，不是编号）。 */
    static final class Event {
        /** 带指针下标的完整动作码（POINTER_DOWN / POINTER_UP 的高位是变化那根手指的下标）。 */
        final int action;
        final int[] ids;
        final float[] xs;
        final float[] ys;
        final int flags;
        final long downTime;
        final long eventTime;

        Event(int action, int[] ids, float[] xs, float[] ys, int flags, long downTime, long eventTime) {
            this.action = action;
            this.ids = ids;
            this.xs = xs;
            this.ys = ys;
            this.flags = flags;
            this.downTime = downTime;
            this.eventTime = eventTime;
        }

        int masked() {
            return action & 0xff;
        }

        int actionIndex() {
            return (action >> ACTION_POINTER_INDEX_SHIFT) & 0xff;
        }

        /** 系统收下这个事件之后，系统那边按着的手指编号（从小到大）：抬起的那根去掉，UP / CANCEL 后一根不剩。 */
        int[] downAfter() {
            int m = masked();
            if (m == ACTION_UP || m == ACTION_CANCEL) return new int[0];
            if (m != ACTION_POINTER_UP) return ids.clone();
            int skip = actionIndex();
            int[] out = new int[Math.max(0, ids.length - 1)];
            int j = 0;
            for (int i = 0; i < ids.length && j < out.length; i++) if (i != skip) out[j++] = ids[i];
            return out;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(name(masked()));
            if (masked() == ACTION_POINTER_DOWN || masked() == ACTION_POINTER_UP) b.append('#').append(actionIndex());
            b.append('[');
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) b.append(',');
                b.append(ids[i]);
            }
            b.append(']');
            if ((flags & FLAG_CANCELED) != 0) b.append(" canceled");
            if ((flags & FLAG_NO_FOCUS_CHANGE) != 0) b.append(" no-focus");
            return b.toString();
        }
    }

    interface Sink {
        void emit(Event e);
    }

    static String name(int masked) {
        switch (masked) {
            case ACTION_DOWN: return "DOWN";
            case ACTION_UP: return "UP";
            case ACTION_MOVE: return "MOVE";
            case ACTION_CANCEL: return "CANCEL";
            case ACTION_POINTER_DOWN: return "POINTER_DOWN";
            case ACTION_POINTER_UP: return "POINTER_UP";
            default: return "ACTION_" + masked;
        }
    }

    private static final int SLOTS = MtDecoder.SLOTS;

    // ── 残留记录（Passthrough 落盘用的格式，放这里好离线测）──

    /** 一行："屏幕编号 编号 编号 …"（系统那边按着的注入手指，只有 slot 编号，没有坐标）。 */
    static String formatDown(int display, int[] ids) {
        StringBuilder b = new StringBuilder().append(display);
        for (int id : ids) b.append(' ').append(id);
        return b.append('\n').toString();
    }

    /** 读回 {@link #formatDown} 写的记录：屏幕编号对得上才返回按着的编号（从小到大、去重、只要 0–15），否则 / 读不懂返回 null。 */
    static int[] parseDown(String text, int display) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            if (Integer.parseInt(parts[0]) != display) return null;
            boolean[] seen = new boolean[SLOTS];
            int n = 0;
            for (int i = 1; i < parts.length; i++) {
                int id = Integer.parseInt(parts[i]);
                if (id < 0 || id >= SLOTS) return null;
                if (!seen[id]) n++;
                seen[id] = true;
            }
            int[] out = new int[n];
            int j = 0;
            for (int id = 0; id < SLOTS; id++) if (seen[id]) out[j++] = id;
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 清残留用的 CANCEL：手指集合正好是 ids（系统要求和它记着的按着集合完全相等），带 FLAG_CANCELED。 */
    static Event cancelOf(int[] ids, long t) {
        return new Event(ACTION_CANCEL, ids.clone(), new float[ids.length], new float[ids.length], FLAG_CANCELED, t, t);
    }

    /**
     * 没有记录（或记录对不上）时猜残留：先试单根 {0}…{15}，再试两根的组合（编号从小到大，120 种）。
     * 三根以上不试：每次被拒系统都往 logcat 打一大份分发状态。Passthrough 单根一个都没清掉才试两根。
     */
    static int[][] residueGuesses(boolean pairs) {
        if (!pairs) {
            int[][] out = new int[SLOTS][];
            for (int i = 0; i < SLOTS; i++) out[i] = new int[]{i};
            return out;
        }
        int[][] out = new int[SLOTS * (SLOTS - 1) / 2][];
        int k = 0;
        for (int i = 0; i < SLOTS; i++) {
            for (int j = i + 1; j < SLOTS; j++) out[k++] = new int[]{i, j};
        }
        return out;
    }

    private final Sink sink;
    /** 在系统那边按着（已注入 DOWN / POINTER_DOWN、还没抬起或取消）。 */
    private final boolean[] down = new boolean[SLOTS];
    /** 被 cancelAll 作废、手指还没真抬起：之后的移动、抬起都不注入。 */
    private final boolean[] voided = new boolean[SLOTS];
    private final float[] x = new float[SLOTS];
    private final float[] y = new float[SLOTS];
    private boolean moved;
    private long downTime;

    InjectStream(Sink sink) {
        this.sink = sink;
    }

    /** 现在系统那边按着几根（测试和收尾用）。 */
    int count() {
        int n = 0;
        for (boolean d : down) if (d) n++;
        return n;
    }

    void down(int slot, float px, float py, long t) {
        down(slot, px, py, t, false);
    }

    /**
     * 手指按下。noFocus：这根是按在豆花窗口里手势带的（{@link TouchSplit#SYSTEM_IN_WINDOW}），它的 DOWN / POINTER_DOWN 带
     * {@link #FLAG_NO_FOCUS_CHANGE}——只有这两种会让系统挪焦点（POINTER_DOWN 拆给新窗口时变成 DOWN），别的事件不带。
     */
    void down(int slot, float px, float py, long t, boolean noFocus) {
        if (slot < 0 || slot >= SLOTS || down[slot]) return;
        voided[slot] = false;
        boolean first = count() == 0;
        if (first) downTime = t;
        down[slot] = true;
        x[slot] = px;
        y[slot] = py;
        int flags = noFocus ? FLAG_NO_FOCUS_CHANGE : 0;
        if (first) {
            emit(ACTION_DOWN, t, flags);
        } else {
            emit(ACTION_POINTER_DOWN | (indexOf(slot) << ACTION_POINTER_INDEX_SHIFT), t, flags);
        }
    }

    void move(int slot, float px, float py) {
        if (slot < 0 || slot >= SLOTS || !down[slot]) return;
        if (px == x[slot] && py == y[slot]) return;
        x[slot] = px;
        y[slot] = py;
        moved = true;
    }

    /** 一帧报完：有手指动过就发一个 MOVE（带上所有按着的手指）。 */
    void frame(long t) {
        if (!moved) return;
        moved = false;
        if (count() > 0) emit(ACTION_MOVE, t, 0);
    }

    /** 手指抬起：最后一根是 UP，否则 POINTER_UP。作废的、没按着的只忘掉。 */
    void up(int slot, long t) {
        if (slot < 0 || slot >= SLOTS) return;
        voided[slot] = false;
        if (!down[slot]) return;
        // 同一帧里先动过、还没 frame 就抬起（MtDecoder 的顺序是动完才 frame、frame 之后才抬起，这里只是兜底）：先把移动发掉
        if (moved) frame(t);
        if (count() == 1) {
            emit(ACTION_UP, t, 0);
        } else {
            emit(ACTION_POINTER_UP | (indexOf(slot) << ACTION_POINTER_INDEX_SHIFT), t, 0);
        }
        down[slot] = false;
    }

    /**
     * 整条流取消（带 FLAG_CANCELED，手指集合 = 当前全部按着的）；没有按着的什么都不发。
     * 还按着的手指记成作废，抬起前不再注入。释放独占、会话结束、手掌、丢数据都走这里；可以重复调。
     */
    void cancelAll(long t) {
        if (count() == 0) return;
        emit(ACTION_CANCEL, t, FLAG_CANCELED);
        for (int s = 0; s < SLOTS; s++) {
            if (down[s]) voided[s] = true;
            down[s] = false;
        }
        moved = false;
    }

    /** 这根手指被 cancelAll 作废了、还没抬起。 */
    boolean isVoided(int slot) {
        return slot >= 0 && slot < SLOTS && voided[slot];
    }

    /** 按着的手指里 slot 排第几（编号从小到大）。 */
    private int indexOf(int slot) {
        int i = 0;
        for (int s = 0; s < slot; s++) if (down[s]) i++;
        return i;
    }

    private void emit(int action, long t, int flags) {
        int n = count();
        int[] ids = new int[n];
        float[] xs = new float[n];
        float[] ys = new float[n];
        int i = 0;
        for (int s = 0; s < SLOTS; s++) {
            if (!down[s]) continue;
            ids[i] = s;
            xs[i] = x[s];
            ys[i] = y[s];
            i++;
        }
        sink.emit(new Event(action, ids, xs, ys, flags, downTime, t));
    }
}
