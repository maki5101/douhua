package dev.douhua;

/**
 * 自动启动的退避（AutoStartBackoff）：被系统拒过（Wi-Fi 未信任）就停止自动打开无线调试，手动启动成功一次
 * （或者盯守中用户在弹框里点了允许、接着的启动成功）才恢复。
 * 按用户会遇到的几段经过串起来测（每段从上一步的标记接着走），再把四个输入的全部组合过一遍。
 */
public final class AutoStartBackoffTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        // 平时：没退避，自动、手动都能自己打开无线调试；失败（不是被拒）、成功都不进退避
        boolean b = false;
        check(AutoStartBackoff.mayOpenWireless(true, b), "没退避：自动的能自己打开无线调试");
        b = AutoStartBackoff.after(b, true, false, false, false);
        check(!b, "自动启动失败（比如连不上）不进退避");
        b = AutoStartBackoff.after(b, true, false, true, false);
        check(!b, "自动启动成功不进退避");

        // 公共网络：打开豆花时自动启动，系统弹框、没允许 → 退避
        b = AutoStartBackoff.after(b, true, false, false, true);
        check(b, "自动启动结果是 Wi-Fi 未信任 → 退避");
        check(!AutoStartBackoff.mayOpenWireless(true, b), "退避中：自动的不再自己打开无线调试（打开豆花、开机任务、更新后重启都是自动）");
        check(AutoStartBackoff.mayOpenWireless(false, b), "退避中：手动的照样能打开");
        // 退避中无线调试本来开着（用户自己开的）：自动启动照常走、成功了，但这不是手动成功
        b = AutoStartBackoff.after(b, true, false, true, false);
        check(b, "退避中自动启动成功（无线调试本来开着）不解除退避");
        b = AutoStartBackoff.after(b, true, false, false, false);
        check(b, "退避中自动启动失败也不解除");
        // 手动启动失败（比如回家前点了一下、连不上）不解除
        b = AutoStartBackoff.after(b, false, false, false, false);
        check(b, "手动启动失败不解除退避");
        // 回到家，手动点"启动服务"成功 → 恢复
        b = AutoStartBackoff.after(b, false, false, true, false);
        check(!b, "手动启动成功一次 → 恢复自动启动");
        check(AutoStartBackoff.mayOpenWireless(true, b), "恢复后自动的又能自己打开无线调试");

        // 手动点了启动、弹框没允许：一样进退避（下次自动启动会弹同一个框）
        b = AutoStartBackoff.after(false, false, false, false, true);
        check(b, "手动启动被拒（Wi-Fi 未信任）也进退避");
        // 配对成功后的那次启动是手动的：成功即恢复
        b = AutoStartBackoff.after(b, false, false, true, false);
        check(!b, "配对成功后那次启动（手动）成功 → 恢复");

        // 自动启动弹了框，用户 40 秒后才点"始终允许"：30 秒宽限期到时先记上退避，盯守接着的那次启动成功 → 解除
        b = AutoStartBackoff.after(false, true, false, false, true);
        check(b, "自动启动宽限期里没允许（结果 Wi-Fi 未信任）→ 先退避");
        b = AutoStartBackoff.after(b, true, true, true, false);
        check(!b, "盯守中用户点了允许、接着的启动（最初是自动）成功 → 解除退避");
        // 盯守接着的那次又失败（比如连不上）：不解除；又被系统关回去：照样退避
        check(AutoStartBackoff.after(true, true, true, false, false), "用户允许后接着的启动失败 → 退避保持");
        check(AutoStartBackoff.after(false, true, true, false, true), "用户允许后接着的启动又是 Wi-Fi 未信任 → 退避");
        // 3 分钟里一直没点允许：盯守到点、不再启动，退避留着（正是"弹了框、没允许"）

        // 全组合：被拒一定退避；手动成功、用户允许后接着的成功一定解除；其余不变
        for (int i = 0; i < 32; i++) {
            boolean backoff = (i & 1) != 0, auto = (i & 2) != 0, ok = (i & 4) != 0, untrusted = (i & 8) != 0;
            boolean allowed = (i & 16) != 0;
            if (ok && untrusted) continue;   // 一次启动只有一个结果
            boolean want = untrusted || (backoff && !(ok && (!auto || allowed)));
            check(AutoStartBackoff.after(backoff, auto, allowed, ok, untrusted) == want,
                    "after(backoff=" + backoff + ", auto=" + auto + ", allowed=" + allowed
                            + ", ok=" + ok + ", untrusted=" + untrusted + ")");
        }
        check(AutoStartBackoff.mayOpenWireless(false, false) && AutoStartBackoff.mayOpenWireless(false, true)
                && AutoStartBackoff.mayOpenWireless(true, false) && !AutoStartBackoff.mayOpenWireless(true, true),
                "只有\"自动 + 退避中\"不能自己打开无线调试");

        System.out.println("自动启动退避测试：" + checks + " 项，失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("失败：" + what);
        }
    }
}
