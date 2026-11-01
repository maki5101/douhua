package dev.douhua;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 手柄页的手感参数（GamepadTouch 用；设置 → 手柄里每项一个滑块 / 开关，Prefs.gamepadConfig 按 gp_* 读，没设过的是这里的默认值）。纯 Java（离线测）。
 * App 经 {@link Proto#CMD_GAMEPAD_CONFIG} 发给服务的分流，本地分流直接用同一份；编解码只在这里一处。
 *
 * 线上格式：length:u16，然后 length 字节的字段（按下面 {@link #write} 的顺序，大端）。读的一方只读认识的字段：
 * 后面多出来的（新版本加的）跳过，短了（旧版本发的）缺的字段用默认值——以后加参数不用再升协议。
 * 新字段一律加在最后，已有字段的顺序、类型不能动。
 */
public final class GamepadConfig {
    /** 摇杆推满要离中心多远（dp）。 */
    public float stickRadiusDp = 56;
    /** 摇杆死区（占推满的比例）：推量 ≤ 它报 0。 */
    public float deadzone = 0.08f;
    /**
     * 抗死区：刚推出死区时就从这么多起（out = anti + (1 − anti)(m − dz)/(1 − dz)）。默认和死区一样大（等于推出死区后不重新缩放）：
     * Moonlight 默认把 ≤ 7% 的推量清零、超过的不重新缩放，刚推出豆花死区的那一点不至于被它吃掉。
     */
    public float antiDeadzone = 0.08f;
    /** 摇杆按下点当中心（浮动）；关 = 区域中心。 */
    public boolean floatingCenter = true;
    /** 推过半径时底座跟着手指走（反向推马上生效）；关 = 顶住不动。 */
    public boolean followBase = true;
    /** 十字键 8 方向；关 = 4 方向。 */
    public boolean dpad8 = true;
    /** 8 方向时斜向扇区多宽（度，正向的是 90 − 它）：斜向窄一点，平台游戏里不容易误按成斜下。 */
    public float diagonalDeg = 30;
    /** 十字键离中心不到半径的这么多比例 = 回中。 */
    public float dpadDeadzone = 0.25f;
    /** 面键簇：离两个键的边都在这么近以内（dp）= 两个一起按（拇指压在两颗中间）。 */
    public float chordDp = 10;
    /** 面键簇里手指滑动换键（含两颗中间一起按）；关 = 按下时挑一个键，滑动不换。 */
    public boolean clusterSlide = true;
    /** 手指离开面键簇的范围这么远（dp）全松，回到范围里再按。 */
    public float clusterExitDp = 24;
    /** 十字键按着换方向时"咔嗒"。 */
    public boolean dpadTicks = true;
    /** 摇杆推到边（≥ 95%）"咔嗒"。 */
    public boolean rimTicks = true;

    public GamepadConfig copy() {
        GamepadConfig c = new GamepadConfig();
        c.stickRadiusDp = stickRadiusDp;
        c.deadzone = deadzone;
        c.antiDeadzone = antiDeadzone;
        c.floatingCenter = floatingCenter;
        c.followBase = followBase;
        c.dpad8 = dpad8;
        c.diagonalDeg = diagonalDeg;
        c.dpadDeadzone = dpadDeadzone;
        c.chordDp = chordDp;
        c.clusterSlide = clusterSlide;
        c.clusterExitDp = clusterExitDp;
        c.dpadTicks = dpadTicks;
        c.rimTicks = rimTicks;
        return c;
    }

    /** 收进合理范围（线上来的、以后设置页存的值都过一遍）。 */
    GamepadConfig sane() {
        stickRadiusDp = clamp(stickRadiusDp, 16, 200, 56);
        deadzone = clamp(deadzone, 0, 0.5f, 0.08f);
        antiDeadzone = clamp(antiDeadzone, 0, 0.5f, 0.08f);
        diagonalDeg = clamp(diagonalDeg, 10, 80, 30);
        dpadDeadzone = clamp(dpadDeadzone, 0.05f, 0.8f, 0.25f);
        chordDp = clamp(chordDp, 0, 40, 10);
        clusterExitDp = clamp(clusterExitDp, 0, 200, 24);
        return this;
    }

    private static float clamp(float v, float min, float max, float fallback) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return fallback;
        return Math.max(min, Math.min(max, v));
    }

    /** 写成线上格式（length:u16 + 字段）。 */
    public void write(DataOutputStream out) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        writeFields(d);
        d.flush();
        out.writeShort(bytes.size());
        bytes.writeTo(out);
    }

    /** 字段，按这个顺序；新字段只能加在最后。 */
    void writeFields(DataOutputStream d) throws IOException {
        d.writeFloat(stickRadiusDp);
        d.writeFloat(deadzone);
        d.writeFloat(antiDeadzone);
        d.writeBoolean(floatingCenter);
        d.writeBoolean(followBase);
        d.writeBoolean(dpad8);
        d.writeFloat(diagonalDeg);
        d.writeFloat(dpadDeadzone);
        d.writeFloat(chordDp);
        d.writeBoolean(clusterSlide);
        d.writeFloat(clusterExitDp);
        d.writeBoolean(dpadTicks);
        d.writeBoolean(rimTicks);
    }

    /** 读线上格式：长度不管多长都整段读走（多的跳过），缺的字段用默认值。 */
    public static GamepadConfig read(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] payload = new byte[len];
        in.readFully(payload);
        return parse(payload);
    }

    static GamepadConfig parse(byte[] payload) {
        GamepadConfig c = new GamepadConfig();
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            c.stickRadiusDp = d.readFloat();
            c.deadzone = d.readFloat();
            c.antiDeadzone = d.readFloat();
            c.floatingCenter = d.readBoolean();
            c.followBase = d.readBoolean();
            c.dpad8 = d.readBoolean();
            c.diagonalDeg = d.readFloat();
            c.dpadDeadzone = d.readFloat();
            c.chordDp = d.readFloat();
            c.clusterSlide = d.readBoolean();
            c.clusterExitDp = d.readFloat();
            c.dpadTicks = d.readBoolean();
            c.rimTicks = d.readBoolean();
        } catch (IOException shorter) {
            // 旧版本发来的短：读到哪算哪，剩下的是默认值（读了半截的那个字段也没写进去）
        }
        return c.sane();
    }
}
