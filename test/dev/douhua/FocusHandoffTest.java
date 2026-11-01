package dev.douhua;

import java.util.Random;

/**
 * App 什么时候请服务把键盘焦点交给另一半（{@link FocusHandoff}）的离线测试：六个条件少一个都不发；同一次拿到焦点只发一次；
 * 重新连上算新的一次；两次之间至少隔 1 秒、隔不够的等够了再问；再拿 MainActivity 的用法（每次问之前撤掉排着的再问、按返回的毫秒数排）跑随机序列，
 * 查"不满足时绝不发、两次至少隔 1 秒、同一次焦点同一条连接只发一次、满足后最多 1 秒内一定发出去"。
 * 不依赖 Android：tools/test.sh 用电脑上的 JDK 编译运行。
 */
public final class FocusHandoffTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        eligibility();
        oncePerFocus();
        gap();
        reconnect();
        randomSequences();

        System.err.println("交焦点的时机：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static void eligibility() {
        int yes = 0;
        for (int m = 0; m < 64; m++) {
            boolean desktop = (m & 1) != 0, full = (m & 2) != 0, external = (m & 4) != 0, connected = (m & 8) != 0,
                    text = (m & 16) != 0, resumed = (m & 32) != 0;
            boolean e = FocusHandoff.eligible(desktop, full, external, connected, text, resumed);
            if (e) {
                yes++;
                check(desktop && !full && !external && connected && !text && resumed, "只有 桌面模式 + 分屏 + 没接外接屏 + 连着 + 贴字面板没开 + 在前台 才发");
            }
        }
        check(yes == 1, "64 种组合里正好一种能发");
        check(!FocusHandoff.eligible(true, true, false, true, false, true), "全屏不发（交出去就没有焦点窗口了）");
        check(!FocusHandoff.eligible(false, false, false, true, false, true), "蓝牙模式不发");
        check(!FocusHandoff.eligible(true, false, true, true, false, true), "接着外接屏不发（DexFocus 管）");
        check(!FocusHandoff.eligible(true, false, false, true, true, true), "贴字面板开着不发（它要焦点打字）");
    }

    static void oncePerFocus() {
        FocusHandoff f = new FocusHandoff();
        check(f.decide(false, true, 0) == FocusHandoff.NONE, "没拿着焦点不发");
        check(f.decide(true, false, 0) == FocusHandoff.NONE, "条件不满足不发");
        check(f.decide(true, true, 0) == FocusHandoff.NOW, "拿着焦点、条件满足：马上发");
        f.sent(0);
        check(f.decide(true, true, 5000) == FocusHandoff.NONE, "同一次拿到焦点只发一次（服务没找到可交的也不反复问）");
        f.focusLost();
        check(f.decide(true, true, 6000) == FocusHandoff.NOW, "丢了焦点再拿到：新的一次，再发");
    }

    static void gap() {
        FocusHandoff f = new FocusHandoff();
        f.sent(10_000);
        f.focusLost();
        check(f.decide(true, true, 10_300) == 700, "1 秒内又拿到焦点：700ms 后再问");
        check(f.decide(true, true, 10_999) == 1, "差 1ms：1ms 后再问");
        check(f.decide(true, true, 11_000) == FocusHandoff.NOW, "够 1 秒了：发");
        check(f.decide(false, true, 10_300) == FocusHandoff.NONE, "隔不够时没拿着焦点：什么都不做（不排再问）");
    }

    static void reconnect() {
        FocusHandoff f = new FocusHandoff();
        f.sent(0);
        check(f.decide(true, true, 2000) == FocusHandoff.NONE, "发过了");
        f.reconnected();
        check(f.decide(true, true, 2000) == FocusHandoff.NOW, "重新连上服务：同一次焦点也再发一次（旧连接上的可能没做完）");
        f.sent(2000);
        f.reconnected();
        check(f.decide(true, true, 2500) == 500, "重新连上也守 1 秒的间隔");
    }

    /**
     * 照 MainActivity 的用法跑随机序列：每个事件（拿到 / 丢掉焦点、条件变、重新连上、时间走）之后问一次（先撤掉排着的再问），
     * 返回正数就排一个再问；时间走到排着的那一刻也问一次。
     */
    static void randomSequences() {
        Random r = new Random(1101);
        int sends = 0;
        boolean safe = true, spaced = true, once = true, live = true;
        for (int seq = 0; seq < 400; seq++) {
            FocusHandoff f = new FocusHandoff();
            long now = 0, lastSend = Long.MIN_VALUE / 2, retryAt = -1;
            boolean focus = false, eligible = false, sentThisEpisode = false;
            long satisfiedSince = -1;   // focus && eligible 从什么时候起一直成立、而且这一次还没发过
            for (int step = 0; step < 200; step++) {
                int ev = r.nextInt(6);
                switch (ev) {
                    case 0:
                        if (!focus) sentThisEpisode = false;
                        focus = true;
                        break;
                    case 1:
                        if (focus) {
                            f.focusLost();
                            sentThisEpisode = false;
                        }
                        focus = false;
                        break;
                    case 2:
                        eligible = r.nextBoolean();
                        break;
                    case 3:
                        f.reconnected();
                        sentThisEpisode = false;
                        break;
                    default:
                        long dt = 1 + r.nextInt(r.nextBoolean() ? 300 : 2500);
                        long target = now + dt;
                        // 中间排着的再问先到点
                        while (retryAt >= 0 && retryAt <= target) {
                            now = retryAt;
                            retryAt = -1;
                            long w = f.decide(focus, eligible, now);
                            if (w == FocusHandoff.NOW) {
                                spaced &= now - lastSend >= FocusHandoff.MIN_GAP_MS;
                                safe &= focus && eligible;
                                once &= !sentThisEpisode;
                                f.sent(now);
                                lastSend = now;
                                sentThisEpisode = true;
                                satisfiedSince = -1;   // 这一次发出去了：之后再满足（重新连上、再拿到焦点）从那一刻重新算
                                sends++;
                            } else if (w > 0) {
                                retryAt = now + w;
                            }
                            live &= checkLive(focus, eligible, sentThisEpisode, satisfiedSince, now);
                        }
                        now = target;
                        live &= checkLive(focus, eligible, sentThisEpisode, satisfiedSince, now);
                        continue;   // 只是时间走：不另外问（MainActivity 也只在事件和排着的再问时问）
                }
                // 事件之后问一次（先撤掉排着的）
                retryAt = -1;
                long w = f.decide(focus, eligible, now);
                if (w == FocusHandoff.NOW) {
                    spaced &= now - lastSend >= FocusHandoff.MIN_GAP_MS;
                    safe &= focus && eligible;
                    once &= !sentThisEpisode;
                    f.sent(now);
                    lastSend = now;
                    sentThisEpisode = true;
                    sends++;
                } else if (w > 0) {
                    check(w <= FocusHandoff.MIN_GAP_MS, "再问的间隔不超过 1 秒");
                    retryAt = now + w;
                }
                satisfiedSince = focus && eligible && !sentThisEpisode ? (satisfiedSince < 0 ? now : satisfiedSince) : -1;
            }
        }
        check(safe, "随机序列：没拿着焦点 / 条件不满足时绝不发");
        check(spaced, "随机序列：两次之间至少隔 1 秒");
        check(once, "随机序列：同一次焦点、同一条连接上只发一次");
        check(live, "随机序列：拿着焦点、条件满足、这一次还没发的，最多 1 秒内一定发出去");
        check(sends > 1000, "随机序列真发了不少次（" + sends + "）");
    }

    /** 满足了超过 1 秒还没发就是漏了。 */
    private static boolean checkLive(boolean focus, boolean eligible, boolean sent, long since, long now) {
        if (!focus || !eligible || sent || since < 0) return true;
        return now - since <= FocusHandoff.MIN_GAP_MS;
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
