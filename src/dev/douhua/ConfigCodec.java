package dev.douhua;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * {@link Proto#CMD_CONFIG} 的负载（不含命令字节）：App 写（Link）、服务读（server.Session）、服务自测写（server.TestClient），
 * 三处共用这一份。以前三处各写一遍，加一项要改三个地方，顺序差一个字节后面的命令就全读错。纯 Java（离线测往返）。
 * 字段顺序见 Proto.CMD_CONFIG；加字段只能加在末尾，并且要升协议版本（服务按定长顺序读）。
 */
public final class ConfigCodec {
    private ConfigCodec() {}

    /** density = 每 dp 多少像素（服务把屏幕像素换成 dp 用）。 */
    public static void write(DataOutput out, GestureEngine.Config c, float density) throws IOException {
        out.writeFloat(c.pointerSpeed);
        out.writeFloat(c.acceleration);
        out.writeFloat(c.scrollStepDp);
        out.writeByte(c.naturalScroll ? 1 : 0);
        out.writeByte(c.pinchZoom ? 1 : 0);
        out.writeByte(c.edgeScroll ? 1 : 0);
        out.writeByte(c.edgeScrollHorizontal ? 1 : 0);
        out.writeFloat(c.edgeWidthDp);
        out.writeByte(c.scrollTicks);
        out.writeByte(c.longPressDrag ? 1 : 0);
        out.writeInt(c.longPressMs);
        out.writeByte(c.tapToDrag ? 1 : 0);
        out.writeInt(c.dragWindowMs);
        out.writeFloat(density);
        out.writeFloat(c.slowFactor);
        out.writeByte(c.texture ? 1 : 0);
        // 协议 18 起：惯性、连续滚动、小红点
        out.writeByte(c.inertia ? 1 : 0);
        out.writeInt(c.inertiaTauMs);
        out.writeFloat(c.inertiaMinSpeed);
        out.writeByte(c.smoothScroll ? 1 : 0);
        out.writeFloat(c.stickSpeed);
        out.writeByte(c.stickTap ? 1 : 0);
        out.writeByte(c.stickInertia ? 1 : 0);
    }

    /** 读进 c（覆盖 c 里的每一项），返回 density。 */
    public static float read(DataInput in, GestureEngine.Config c) throws IOException {
        c.pointerSpeed = in.readFloat();
        c.acceleration = in.readFloat();
        c.scrollStepDp = in.readFloat();
        c.naturalScroll = in.readUnsignedByte() == 1;
        c.pinchZoom = in.readUnsignedByte() == 1;
        c.edgeScroll = in.readUnsignedByte() == 1;
        c.edgeScrollHorizontal = in.readUnsignedByte() == 1;
        c.edgeWidthDp = in.readFloat();
        c.scrollTicks = in.readUnsignedByte();
        c.longPressDrag = in.readUnsignedByte() == 1;
        c.longPressMs = in.readInt();
        c.tapToDrag = in.readUnsignedByte() == 1;
        c.dragWindowMs = in.readInt();
        float density = in.readFloat();
        c.slowFactor = in.readFloat();
        c.texture = in.readUnsignedByte() == 1;
        c.inertia = in.readUnsignedByte() == 1;
        c.inertiaTauMs = in.readInt();
        c.inertiaMinSpeed = in.readFloat();
        c.smoothScroll = in.readUnsignedByte() == 1;
        c.stickSpeed = in.readFloat();
        c.stickTap = in.readUnsignedByte() == 1;
        c.stickInertia = in.readUnsignedByte() == 1;
        return density;
    }
}
