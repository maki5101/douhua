package dev.douhua.adb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * adb 传输层的一条消息：24 字节小端头（command、arg0、arg1、data_length、data_crc32、magic）+ 数据。纯 JDK。
 * 出处：AOSP packages/modules/adb docs/dev/protocol.md、adb.h（amessage）。magic = command ^ 0xffffffff。
 * data_crc32 其实是逐字节无符号求和（不是 CRC）；连接版本 ≥ 0x01000001 时对方不校验，这里照旧算上（发 0x01000000 时必须对）。
 */
public final class AdbMessage {
    public static final int A_SYNC = 0x434e5953;
    public static final int A_CNXN = 0x4e584e43;
    public static final int A_AUTH = 0x48545541;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;
    public static final int A_STLS = 0x534c5453;

    /** CNXN 里报的协议版本（带校验和的老版本，最保守）。 */
    public static final int A_VERSION = 0x01000000;
    /** CNXN 里报的单条消息最大数据量。 */
    public static final int MAX_PAYLOAD = 256 * 1024;
    public static final int A_STLS_VERSION = 0x01000000;

    public static final int HEADER_SIZE = 24;
    /** 读的时候能接受的最大数据量（对方报得再大也不收，防止乱分配）。 */
    static final int READ_LIMIT = 1024 * 1024;

    public final int command;
    public final int arg0;
    public final int arg1;
    public final byte[] data;

    public AdbMessage(int command, int arg0, int arg1, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.data = data == null ? new byte[0] : data;
    }

    /** 数据是字符串的消息（OPEN 的服务名、CNXN 的 banner）：adb 要求末尾带 \0。 */
    public static AdbMessage withString(int command, int arg0, int arg1, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[b.length + 1];
        System.arraycopy(b, 0, d, 0, b.length);
        return new AdbMessage(command, arg0, arg1, d);
    }

    static int checksum(byte[] d) {
        int sum = 0;
        for (byte b : d) sum += b & 0xff;
        return sum;
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(~command);
        b.put(data);
        return b.array();
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(encode());
        out.flush();
    }

    /** 读一条；头不对（magic 不符、长度离谱、校验和不对）抛 IOException。流结束抛 EOFException。 */
    public static AdbMessage read(InputStream in) throws IOException {
        DataInputStream din = in instanceof DataInputStream ? (DataInputStream) in : new DataInputStream(in);
        byte[] h = new byte[HEADER_SIZE];
        din.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        int sum = b.getInt();
        int magic = b.getInt();
        if (magic != ~command) throw new IOException("adb 消息头坏了（magic 不符）");
        if (length < 0 || length > READ_LIMIT) throw new IOException("adb 消息长度不对：" + length);
        byte[] d = new byte[length];
        din.readFully(d);
        // 新版本协议对方发 0（不算校验和），只在非 0 时核对
        if (sum != 0 && sum != checksum(d)) throw new IOException("adb 消息校验和不对");
        return new AdbMessage(command, arg0, arg1, d);
    }

    /** 调试用的名字；不打印数据（可能是命令输出）。 */
    public static String name(int command) {
        switch (command) {
            case A_SYNC: return "SYNC";
            case A_CNXN: return "CNXN";
            case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            case A_STLS: return "STLS";
            default: return String.format("0x%08x", command);
        }
    }
}
