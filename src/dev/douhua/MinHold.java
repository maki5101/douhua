package dev.douhua;

/**
 * 每个键、每个鼠标键至少按住 {@link #MIN_MS} 才松开（2026-11-04，用户："设一个最小时长，可以是人类能点的最短的时长"）。
 * 起因：双指轻点的右键按下、松开连着发（实测相隔 0.06–2 毫秒），Moonlight 时有时无地漏掉；动作里的单击 / 组合键、合并键的点一下、
 * 蓝牙的媒体键动作也是连着发。规则放在发报文的最后一步，一处管所有来源：桌面模式服务写 uhid 之前（server.Session）、
 * 蓝牙模式发之前（BtHid——服务转发回来的、本地分流的、App 直接发的都经它）。手指真按着的照手指的时长，只有比这还短的才拉长。
 *
 * 规矩：
 * - 松开离按下不到 MIN_MS：先留着（主机那头照旧按着），到点再发松开（{@link Sink#schedule} → {@link #onTimer}）。
 * - 留着的松开绝不和后面的输入叠在一起：新按下任何键 / 鼠标键、换了多媒体 / 系统键的码、滚轮，都先把所有留着的松开发掉；
 *   光标移动先把留着的鼠标键松掉（按得短就短，顺序比时长要紧：快打 "@a" 时留着的 Shift 不能把 a 变成 A，Ctrl 点一下马上点鼠标 / 滚滚轮
 *   不能变成 Ctrl+点击 / 缩放，右键留着时一挪光标不能变成右键拖动）。留着的键盘键不因光标移动提前松（Shift 带着挪光标没有意思，
 *   不然另一只手推着小红点时打的键又短了）。
 * - 只拉长、不缩短、不丢：每个按下照旧配一个松开，晚到的松开顶多晚 MIN_MS。
 *
 * 纯 Java（离线测，MinHoldTest）。线程：调用方保证串行（服务持 lock、App 在主线程）；定时器由调用方给，再排取代上一次，
 * 早到 / 过期的回调照当前状态处理、什么都不多做。
 */
public final class MinHold {
    /** 最短按住时长（毫秒）：人能点出来的最短（快打字的人一个键也按 50 毫秒左右）；和双击第二下按住的时长一样（GestureEngine.DOUBLE_HOLD_MS）。 */
    public static final long MIN_MS = 40;

    public interface Sink {
        void mouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 8 字节键盘报文（只在回调期间有效）。 */
        void keyboard(byte[] report);

        void consumer(int usage);

        void system(int usage);

        /** 过 delayMs 调一次 {@link #onTimer}（再排取代上一次）。 */
        void schedule(long delayMs);
    }

    private static final int KEYS = 6;

    private final Sink sink;

    // 鼠标：want = 来源要的，sent = 主机那头按着的（= want | 留着没松的）
    private int mouseWant, mouseSent;
    private final long[] buttonAt = new long[8];

    // 键盘：修饰键 8 位 + 最多 6 个键
    private int modWant, modSent;
    private final long[] modAt = new long[8];
    private final int[] keyWant = new int[KEYS];
    private int keyWantCount;
    private final int[] keySent = new int[KEYS];
    private int keySentCount;
    private final long[] keyAt = new long[256];
    private final byte[] report = new byte[Hid.KEYBOARD_REPORT_SIZE];
    /** 算报文用的临时数组（主线程上不每次分配）。 */
    private final int[] want = new int[KEYS], sentKeys = new int[KEYS];

    // 多媒体页、系统键：一次一个码
    private int consumerWant, consumerSent, systemWant, systemSent;
    private long consumerAt, systemAt;

    public MinHold(Sink sink) {
        this.sink = sink;
    }

    // ═══════════ 来源要发的 ═══════════

    public void mouse(int buttons, int dx, int dy, int wheel, int pan, long now) {
        buttons &= 0xFF;
        boolean moved = dx != 0 || dy != 0, wheeled = wheel != 0 || pan != 0, other = moved || wheeled;
        if ((buttons & ~mouseWant) != 0 || wheeled) {
            flushAll();
        } else if (moved && mouseSent != mouseWant) {   // 光标动了：留着的鼠标键先松（不然成了拖动）；留着的键盘键不管（Shift + 挪光标没有意思）
            mouseSent = mouseWant;
            sink.mouse(mouseSent, 0, 0, 0, 0);
        }
        boolean pureRelease = buttons != mouseWant && (buttons & ~mouseWant) == 0;
        mouseWant = buttons;
        int sent = buttons | (mouseSent & ~buttons & heldButtons(now));
        for (int b = 0; b < 8; b++) {
            if ((sent & ~mouseSent & (1 << b)) != 0) buttonAt[b] = now;
        }
        boolean changed = sent != mouseSent;
        mouseSent = sent;
        if (changed || other || !pureRelease) sink.mouse(sent, dx, dy, wheel, pan);
        reschedule(now);
    }

    public void keyboard(byte[] r, long now) {
        int mods = r[0] & 0xFF;
        int n = 0;
        for (int i = 2; i < r.length && i < 2 + KEYS; i++) {
            int u = r[i] & 0xFF;
            if (u != 0) want[n++] = u;
        }
        boolean press = (mods & ~modWant) != 0;
        for (int i = 0; i < n && !press; i++) press = !contains(keyWant, keyWantCount, want[i]);
        if (press) flushAll();
        boolean changedWant = mods != modWant || n != keyWantCount || !sameKeys(want, n);
        modWant = mods;
        System.arraycopy(want, 0, keyWant, 0, n);
        keyWantCount = n;

        int sentMods = mods | (modSent & ~mods & heldMods(now));
        for (int b = 0; b < 8; b++) {
            if ((sentMods & ~modSent & (1 << b)) != 0) modAt[b] = now;
        }
        int c = 0;
        for (int i = 0; i < n; i++) {
            sentKeys[c++] = want[i];
            if (!contains(keySent, keySentCount, want[i])) keyAt[want[i]] = now;
        }
        for (int i = 0; i < keySentCount && c < KEYS; i++) {   // 还没按够的、来源已经松了的：留着
            int u = keySent[i];
            if (!contains(want, n, u) && keyAt[u] + MIN_MS > now) sentKeys[c++] = u;
        }
        boolean changed = sentMods != modSent || c != keySentCount || !sameSent(sentKeys, c);
        modSent = sentMods;
        System.arraycopy(sentKeys, 0, keySent, 0, c);
        keySentCount = c;
        if (changed || !changedWant) sendKeyboard();
        reschedule(now);
    }

    public void consumer(int usage, long now) {
        if (usage != 0 && usage != consumerWant) flushAll();
        consumerWant = usage;
        if (usage != 0) {
            if (usage != consumerSent) consumerAt = now;
            consumerSent = usage;
            sink.consumer(usage);
        } else if (consumerSent != 0 && consumerAt + MIN_MS <= now) {
            consumerSent = 0;
            sink.consumer(0);
        }
        reschedule(now);
    }

    public void system(int usage, long now) {
        if (usage != 0 && usage != systemWant) flushAll();
        systemWant = usage;
        if (usage != 0) {
            if (usage != systemSent) systemAt = now;
            systemSent = usage;
            sink.system(usage);
        } else if (systemSent != 0 && systemAt + MIN_MS <= now) {
            systemSent = 0;
            sink.system(0);
        }
        reschedule(now);
    }

    /** 定时器到点：按够了的松开发出去，还有留着的就再排。 */
    public void onTimer(long now) {
        int mouse = mouseWant | (mouseSent & ~mouseWant & heldButtons(now));
        if (mouse != mouseSent) {
            mouseSent = mouse;
            sink.mouse(mouse, 0, 0, 0, 0);
        }
        int mods = modWant | (modSent & ~modWant & heldMods(now));
        int c = 0;
        for (int i = 0; i < keySentCount; i++) {
            int u = keySent[i];
            if (contains(keyWant, keyWantCount, u) || keyAt[u] + MIN_MS > now) sentKeys[c++] = u;
        }
        if (mods != modSent || c != keySentCount) {
            modSent = mods;
            System.arraycopy(sentKeys, 0, keySent, 0, c);
            keySentCount = c;
            sendKeyboard();
        }
        if (consumerWant == 0 && consumerSent != 0 && consumerAt + MIN_MS <= now) {
            consumerSent = 0;
            sink.consumer(0);
        }
        if (systemWant == 0 && systemSent != 0 && systemAt + MIN_MS <= now) {
            systemSent = 0;
            sink.system(0);
        }
        reschedule(now);
    }

    /** 留着的松开马上全发出去（收尾：关设备之前、断开之前）。 */
    public void flushNow() {
        flushAll();
    }

    /** 忘掉一切、什么都不发（换了主机 / 连接断了：主机那头已经不在了）。 */
    public void reset() {
        mouseWant = mouseSent = 0;
        modWant = modSent = 0;
        keyWantCount = keySentCount = 0;
        consumerWant = consumerSent = systemWant = systemSent = 0;
    }

    /** 主机那头现在按着的鼠标键（蓝牙的绝对坐标报文照抄它）。 */
    public int mouseButtons() {
        return mouseSent;
    }

    /** 有没有留着没松的。 */
    public boolean pending() {
        return mouseSent != mouseWant || modSent != modWant || keySentCount != keyWantCount
                || consumerSent != consumerWant || systemSent != systemWant;
    }

    // ═══════════ 内部 ═══════════

    /** 所有留着的松开现在发出去（新的输入来了）。 */
    private void flushAll() {
        if (mouseSent != mouseWant) {
            mouseSent = mouseWant;
            sink.mouse(mouseSent, 0, 0, 0, 0);
        }
        if (modSent != modWant || keySentCount != keyWantCount) {
            modSent = modWant;
            System.arraycopy(keyWant, 0, keySent, 0, keyWantCount);
            keySentCount = keyWantCount;
            sendKeyboard();
        }
        if (consumerSent != consumerWant) {
            consumerSent = consumerWant;
            sink.consumer(consumerSent);
        }
        if (systemSent != systemWant) {
            systemSent = systemWant;
            sink.system(systemSent);
        }
    }

    private void reschedule(long now) {
        long due = Long.MAX_VALUE;
        for (int b = 0; b < 8; b++) {
            if ((mouseSent & ~mouseWant & (1 << b)) != 0) due = Math.min(due, buttonAt[b] + MIN_MS);
            if ((modSent & ~modWant & (1 << b)) != 0) due = Math.min(due, modAt[b] + MIN_MS);
        }
        for (int i = 0; i < keySentCount; i++) {
            if (!contains(keyWant, keyWantCount, keySent[i])) due = Math.min(due, keyAt[keySent[i]] + MIN_MS);
        }
        if (consumerWant == 0 && consumerSent != 0) due = Math.min(due, consumerAt + MIN_MS);
        if (systemWant == 0 && systemSent != 0) due = Math.min(due, systemAt + MIN_MS);
        if (due != Long.MAX_VALUE) sink.schedule(Math.max(1, due - now));
    }

    /** 按下还不到 MIN_MS 的鼠标键位。 */
    private int heldButtons(long now) {
        int m = 0;
        for (int b = 0; b < 8; b++) {
            if (buttonAt[b] + MIN_MS > now) m |= 1 << b;
        }
        return m;
    }

    private int heldMods(long now) {
        int m = 0;
        for (int b = 0; b < 8; b++) {
            if (modAt[b] + MIN_MS > now) m |= 1 << b;
        }
        return m;
    }

    private void sendKeyboard() {
        java.util.Arrays.fill(report, (byte) 0);
        report[0] = (byte) modSent;
        for (int i = 0; i < keySentCount; i++) report[2 + i] = (byte) keySent[i];
        sink.keyboard(report);
    }

    private boolean sameKeys(int[] keys, int n) {
        for (int i = 0; i < n; i++) {
            if (keyWant[i] != keys[i]) return false;
        }
        return true;
    }

    private boolean sameSent(int[] keys, int n) {
        for (int i = 0; i < n; i++) {
            if (keySent[i] != keys[i]) return false;
        }
        return true;
    }

    private static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) return true;
        }
        return false;
    }
}
