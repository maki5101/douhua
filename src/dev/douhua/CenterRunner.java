package dev.douhua;

import android.os.Handler;

import java.util.List;

/**
 * 在主线程上按固定节奏一帧帧发鼠标位移（调试位移 DebugMoves 用；BtHid 只能在主线程用）。
 * 下一帧从这一帧实际发出的时刻往后算间隔，不追赶：宁可整体慢几毫秒，也不让两帧挤在一起
 * （按速度算加速的主机会因此多走一截，重放就不准了；归位后紧接着的跳挤在一起还会被屏幕边夹掉）。
 */
final class CenterRunner {
    interface Sink {
        void move(int dx, int dy);
    }

    /** 一帧：位移和距上一帧的间隔。 */
    static final class Move {
        final int dx, dy, delayMs;

        Move(int dx, int dy, int delayMs) {
            this.dx = dx;
            this.dy = dy;
            this.delayMs = delayMs;
        }
    }

    private final Handler main;
    private final Sink sink;
    private final Runnable tick = this::tick;
    private List<Move> moves;
    private int index;
    private Runnable done;
    private boolean running;

    CenterRunner(Handler main, Sink sink) {
        this.main = main;
        this.sink = sink;
    }

    /** 按顺序发完 moves，最后一帧发出后调 done。正在播的会被打断（不调它的 done）。 */
    void play(List<Move> moves, Runnable done) {
        cancel();
        this.moves = moves;
        index = 0;
        this.done = done;
        running = true;
        main.postDelayed(tick, moves.isEmpty() ? 0 : moves.get(0).delayMs);
    }

    void cancel() {
        running = false;
        main.removeCallbacks(tick);
    }

    boolean running() {
        return running;
    }

    private void tick() {
        if (!running) return;
        if (index < moves.size()) {
            Move m = moves.get(index++);
            sink.move(m.dx, m.dy);
        }
        if (index < moves.size()) {
            main.postDelayed(tick, moves.get(index).delayMs);
            return;
        }
        running = false;
        if (done != null) done.run();
    }
}
