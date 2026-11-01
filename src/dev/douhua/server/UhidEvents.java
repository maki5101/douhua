package dev.douhua.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * /dev/uhid 上内核发来的请求（GET / SET_REPORT）怎么解、回复怎么拼。纯 Java、不 import android.*（ServerPartsTest 测字节布局）。
 * 布局照 Linux include/uapi/linux/uhid.h（结构体都是 packed，整数是本机字节序，和 Uhid 写 CREATE2 / INPUT2 一样）：
 *   struct uhid_event { u32 type; union {...} }
 *   GET_REPORT：id:u32, rnum:u8, rtype:u8
 *   SET_REPORT：id:u32, rnum:u8, rtype:u8, size:u16, data[size]
 *   GET_REPORT_REPLY：id:u32, err:u16, size:u16, data[size]
 *   SET_REPORT_REPLY：id:u32, err:u16
 * 内核发出请求后等 5 秒，收不到同一个 id 的回复就当失败（-EIO）；所以回复里的 id 要原样带回。
 */
final class UhidEvents {
    private UhidEvents() {}

    static final int START = 2, STOP = 3, OPEN = 4, CLOSE = 5, OUTPUT = 6;
    static final int GET_REPORT = 9, GET_REPORT_REPLY = 10, SET_REPORT = 13, SET_REPORT_REPLY = 14;
    /** enum uhid_report_type。 */
    static final int FEATURE_REPORT = 0, OUTPUT_REPORT = 1, INPUT_REPORT = 2;
    static final int EIO = 5;
    /** 一次 read 的缓冲：sizeof(struct uhid_event)（最大的成员是 create2：4 + 128 + 64 + 64 + 2 + 2 + 16 + 4096 = 4376），多留一点。 */
    static final int EVENT_MAX = 4380;
    private static final int DATA_MAX = 4096;

    /** 内核来的一个请求。只有 GET / SET_REPORT 解出 id 等字段；别的类型只有 type（读掉不管）。 */
    static final class Request {
        final int type, id, rnum, rtype;
        /** SET_REPORT 的数据（不带报告编号；没有编号的描述符就是报告本身），别的类型是 null。 */
        final byte[] data;

        Request(int type, int id, int rnum, int rtype, byte[] data) {
            this.type = type;
            this.id = id;
            this.rnum = rnum;
            this.rtype = rtype;
            this.data = data;
        }
    }

    /** 解一次 read 读到的 len 个字节；连 type 都不够时返回 null，GET / SET 的字段不够也返回 null。 */
    static Request parse(byte[] buf, int len, ByteOrder order) {
        if (len < 4) return null;
        ByteBuffer b = ByteBuffer.wrap(buf, 0, len).order(order);
        int type = b.getInt();
        if (type == GET_REPORT) {
            if (len < 10) return null;
            return new Request(type, b.getInt(), b.get() & 0xFF, b.get() & 0xFF, null);
        }
        if (type == SET_REPORT) {
            if (len < 12) return null;
            int id = b.getInt();
            int rnum = b.get() & 0xFF;
            int rtype = b.get() & 0xFF;
            int size = Math.min(Math.min(b.getShort() & 0xFFFF, DATA_MAX), len - 12);
            byte[] data = new byte[size];
            b.get(data);
            return new Request(type, id, rnum, rtype, data);
        }
        return new Request(type, 0, 0, 0, null);
    }

    /** 事件类型的名字（服务日志里记内核发来了什么）。 */
    static String typeName(int type) {
        switch (type) {
            case START: return "START";
            case STOP: return "STOP";
            case OPEN: return "OPEN";
            case CLOSE: return "CLOSE";
            case OUTPUT: return "OUTPUT";
            case GET_REPORT: return "GET_REPORT";
            case SET_REPORT: return "SET_REPORT";
            default: return "类型 " + type;
        }
    }

    /** 字节转成 "00 05" 这样的十六进制（日志用，最多前 16 个）。 */
    static String hex(byte[] data) {
        if (data == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 16); i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(java.util.Locale.ROOT, "%02x", data[i] & 0xFF));
        }
        if (data.length > 16) sb.append(" …");
        return sb.toString();
    }

    /** GET_REPORT 的回复：err = 0 时带上 data（不带报告编号），失败时 err = EIO、不带数据。 */
    static byte[] getReportReply(int id, int err, byte[] data, ByteOrder order) {
        int size = err == 0 && data != null ? Math.min(data.length, DATA_MAX) : 0;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 2 + 2 + size).order(order);
        b.putInt(GET_REPORT_REPLY).putInt(id).putShort((short) err).putShort((short) size);
        if (size > 0) b.put(data, 0, size);
        return b.array();
    }

    static byte[] setReportReply(int id, int err, ByteOrder order) {
        return ByteBuffer.allocate(4 + 4 + 2).order(order).putInt(SET_REPORT_REPLY).putInt(id).putShort((short) err).array();
    }
}
