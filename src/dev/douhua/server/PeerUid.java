package dev.douhua.server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * 查本机 TCP 连接的另一端是哪个 uid：在 /proc/net/tcp{,6} 里找"本地端口=对方端口、远端端口=我方端口"那一行，
 * 它属于对方进程，uid 列就是对方的 uid。shell 能读这两个文件，普通 App 读不了。
 */
final class PeerUid {
    private PeerUid() {}

    /** 找不到返回 -1。 */
    static int lookup(int peerPort, int localPort) {
        String local = String.format(":%04X", peerPort);
        String remote = String.format(":%04X", localPort);
        for (String file : new String[]{"/proc/net/tcp6", "/proc/net/tcp"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                r.readLine();   // 表头
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    // sl local_address rem_address st tx:rx tr:when retrnsmt uid ...
                    if (f.length > 7 && f[1].endsWith(local) && f[2].endsWith(remote)) {
                        return Integer.parseInt(f[7]);
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Server.log("读 " + file + " 失败：" + e);
            }
        }
        return -1;
    }
}
